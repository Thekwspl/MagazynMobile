package pl.magazyn.mobile.data

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.seedCoreData

@RunWith(AndroidJUnit4::class)
class BackupRestoreIntegrationTest {
    @Test
    fun encryptedBackupRestoreAndHealthCheckRecoverSavedData() = runBlocking {
        IsolatedApplicationEnvironment.create("backup-happy-path").use { environment ->
            val database = environment.database
            database.seedCoreData(stock = 2.0)
            val manager = BackupManager(environment.application)
            val backup = File(environment.root, "happy-path.backup")

            manager.createEncryptedBackup(Uri.fromFile(backup), PASSWORD)
            database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", "product-1", 7.0)))
            manager.restoreEncryptedBackup(Uri.fromFile(backup), PASSWORD)

            val activeFile = environment.application.getDatabasePath(MagazynApplication.DATABASE_NAME)
            assertTrue(RestoreJournal.rollbackFile(environment.application).isFile)
            assertEquals(null, RestoreJournal.recoverBeforeDatabaseOpen(environment.application, activeFile))
            assertTrue(RestoreJournal.isHealthCheckRequired(environment.application))

            val restored = environment.application.createDatabase(MagazynApplication.DATABASE_NAME)
            try {
                RestoredDatabaseHealthCheck.verify(restored)
                assertEquals(2.0, restored.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN, 0.0)
                RestoreJournal.confirmAfterHealthCheck(environment.application)
                assertFalse(RestoreJournal.rollbackFile(environment.application).exists())
            } finally {
                restored.close()
            }
        }
    }

    @Test
    fun wrongPasswordAndCorruptBackupLeaveActiveDatabaseUntouched() = runBlocking {
        IsolatedApplicationEnvironment.create("backup-invalid-input").use { environment ->
            val database = environment.database
            database.seedCoreData(stock = 4.0)
            val manager = BackupManager(environment.application)
            val validBackup = File(environment.root, "valid.backup")
            manager.createEncryptedBackup(Uri.fromFile(validBackup), PASSWORD)

            assertFails { manager.restoreEncryptedBackup(Uri.fromFile(validBackup), "wrong-password") }
            assertEquals(4.0, database.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN, 0.0)
            assertFalse(RestoreJournal.rollbackFile(environment.application).exists())

            val corrupt = File(environment.root, "corrupt.backup").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            assertFails { manager.restoreEncryptedBackup(Uri.fromFile(corrupt), PASSWORD) }
            assertEquals(4.0, database.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN, 0.0)
            assertFalse(RestoreJournal.rollbackFile(environment.application).exists())
        }
    }

    @Test
    fun unconfirmedRestoreRecoversPreviousDatabaseFromRollback() = runBlocking {
        IsolatedApplicationEnvironment.create("backup-rollback").use { environment ->
            val database = environment.database
            database.seedCoreData(stock = 2.0)
            val manager = BackupManager(environment.application)
            val backup = File(environment.root, "rollback.backup")
            manager.createEncryptedBackup(Uri.fromFile(backup), PASSWORD)
            database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", "product-1", 7.0)))

            manager.restoreEncryptedBackup(Uri.fromFile(backup), PASSWORD)
            val activeFile = environment.application.getDatabasePath(MagazynApplication.DATABASE_NAME)
            RestoreJournal.recoverBeforeDatabaseOpen(environment.application, activeFile)
            assertTrue(RestoreJournal.isHealthCheckRequired(environment.application))
            RestoreJournal.markHealthCheckFailed(environment.application)

            val diagnostic = RestoreJournal.recoverBeforeDatabaseOpen(environment.application, activeFile)
            assertNotNull(diagnostic)
            val recovered = environment.application.createDatabase(MagazynApplication.DATABASE_NAME)
            try {
                RestoredDatabaseHealthCheck.verify(recovered)
                assertEquals(7.0, recovered.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN, 0.0)
                assertEquals("ROLLBACK_RESTORED_PREVIOUS_DB", RestoreJournal.lastStage(environment.application))
            } finally {
                recovered.close()
            }
        }
    }

    @Test
    fun legacyTransactionalSnapshotPreservesRoomMetadataAndSqliteMetadata() = runBlocking {
        IsolatedApplicationEnvironment.create("backup-fallback").use { environment ->
            val database = environment.database
            database.seedCoreData(stock = 3.0)
            val sourceSqlite = database.openHelper.writableDatabase
            sourceSqlite.execSQL("PRAGMA application_id=2468")
            sourceSqlite.execSQL("CREATE TABLE snapshot_sequence_probe(id INTEGER PRIMARY KEY AUTOINCREMENT, value TEXT NOT NULL)")
            sourceSqlite.execSQL("INSERT INTO snapshot_sequence_probe(value) VALUES('probe')")
            val snapshot = File(environment.root, "fallback-snapshot.db")

            val method = BackupManager::class.java.getDeclaredMethod("createTransactionalSnapshot", File::class.java)
            method.isAccessible = true
            method.invoke(BackupManager(environment.application), snapshot)

            val sqlite = SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals(DATABASE_SCHEMA_VERSION, scalarInt(sqlite, "PRAGMA user_version"))
                assertEquals(2468, scalarInt(sqlite, "PRAGMA application_id"))
                assertEquals("ok", scalarText(sqlite, "PRAGMA integrity_check").lowercase())
                sqlite.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM products WHERE id='product-1'"))
                assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM room_master_table"))
                assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_products_name'"))
                assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM snapshot_sequence_probe WHERE value='probe'"))
                assertEquals(1, scalarInt(sqlite, "SELECT seq FROM sqlite_sequence WHERE name='snapshot_sequence_probe'"))
            } finally {
                sqlite.close()
            }
        }
    }

    private fun assertFails(block: () -> Unit) {
        var failure: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            failure = error
        }
        assertNotNull("Oczekiwano kontrolowanego odrzucenia backupu", failure)
    }

    private fun scalarInt(database: SQLiteDatabase, sql: String): Int =
        database.rawQuery(sql, null).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private fun scalarText(database: SQLiteDatabase, sql: String): String =
        database.rawQuery(sql, null).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

    private companion object {
        const val PASSWORD = "release-gate-password"
    }
}
