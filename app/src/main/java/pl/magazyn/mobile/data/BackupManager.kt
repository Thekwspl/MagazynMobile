package pl.magazyn.mobile.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import pl.magazyn.mobile.MagazynApplication

class BackupManager(private val application: MagazynApplication) {
    private val database get() = application.database

    fun createEncryptedBackup(uri: Uri, password: String) {
        require(password.length >= 6) { "Hasło musi mieć co najmniej 6 znaków" }
        val snapshot = File(application.cacheDir, "magazyn-backup-snapshot-${System.nanoTime()}.db")
        try {
            createConsistentSnapshot(snapshot)
            validateDatabase(snapshot, requireCurrentSchema = true)
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv)
            application.contentResolver.openOutputStream(uri, "w")?.use { rawOutput ->
                val output = DataOutputStream(rawOutput)
                output.write(MAGIC)
                output.writeInt(BACKUP_FORMAT_VERSION)
                output.write(salt)
                output.write(iv)
                CipherOutputStream(output, cipher).use { encrypted -> snapshot.inputStream().use { it.copyTo(encrypted) } }
            } ?: error("Nie udało się utworzyć pliku kopii")
        } finally {
            deleteDatabaseFiles(snapshot)
        }
    }

    /**
     * Room korzysta tu z systemowego SQLite. VACUUM INTO pojawiło się dopiero
     * w SQLite 3.27, więc na starszych urządzeniach kopiujemy schemat i dane
     * przez sam silnik SQLite, z jednego transakcyjnego snapshotu źródła.
     */
    private fun createConsistentSnapshot(snapshot: File) {
        deleteDatabaseFiles(snapshot)
        val sqlite = database.openHelper.writableDatabase
        if (supportsVacuumInto(sqlite)) {
            sqlite.execSQL("VACUUM INTO ?", arrayOf(snapshot.absolutePath))
        } else {
            createTransactionalSnapshot(snapshot)
        }
        FileOutputStream(snapshot, true).use { it.fd.sync() }
    }

    private fun supportsVacuumInto(sqlite: SupportSQLiteDatabase): Boolean {
        val version = sqlite.query("SELECT sqlite_version()").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "0"
        }
        val parts = version.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return major > 3 || major == 3 && (minor > 27 || minor == 27 && patch >= 0)
    }

    private fun createTransactionalSnapshot(snapshot: File) {
        val source = application.getDatabasePath(MagazynApplication.DATABASE_NAME)
        require(source.isFile) { "Nie znaleziono aktywnej bazy danych" }
        val snapshotDatabase = SQLiteDatabase.openDatabase(
            snapshot.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        )
        try {
            snapshotDatabase.execSQL("ATTACH DATABASE ? AS backup_source", arrayOf(source.absolutePath))
            snapshotDatabase.execSQL("PRAGMA foreign_keys=OFF")
            snapshotDatabase.beginTransactionNonExclusive()
            try {
                // Pierwszy odczyt ustala niezmienny snapshot WAL źródła na całą transakcję.
                val schema = readSchema(snapshotDatabase)
                val userVersion = queryPragmaInt(snapshotDatabase, "PRAGMA backup_source.user_version")
                val applicationId = queryPragmaInt(snapshotDatabase, "PRAGMA backup_source.application_id")

                schema.filter { it.type == "table" }.forEach { item ->
                    snapshotDatabase.execSQL(item.sql)
                }
                schema.filter { it.type == "table" }.forEach { item ->
                    val table = quoteIdentifier(item.name)
                    snapshotDatabase.execSQL("INSERT INTO main.$table SELECT * FROM backup_source.$table")
                }
                copySqliteSequenceIfPresent(snapshotDatabase)
                schema.filter { it.type != "table" }.forEach { item ->
                    snapshotDatabase.execSQL(item.sql)
                }
                snapshotDatabase.execSQL("PRAGMA user_version=$userVersion")
                snapshotDatabase.execSQL("PRAGMA application_id=$applicationId")
                snapshotDatabase.setTransactionSuccessful()
            } finally {
                snapshotDatabase.endTransaction()
            }
        } finally {
            snapshotDatabase.close()
        }
    }

    private fun readSchema(database: SQLiteDatabase): List<SnapshotSchemaObject> =
        database.rawQuery(
            """
                SELECT type, name, sql
                FROM backup_source.sqlite_master
                WHERE sql IS NOT NULL
                  AND name NOT LIKE 'sqlite_%'
                  AND name != 'android_metadata'
                ORDER BY CASE type
                    WHEN 'table' THEN 0
                    WHEN 'index' THEN 1
                    WHEN 'view' THEN 2
                    WHEN 'trigger' THEN 3
                    ELSE 4
                END, name
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SnapshotSchemaObject(
                            type = cursor.getString(0),
                            name = cursor.getString(1),
                            sql = cursor.getString(2),
                        ),
                    )
                }
            }
        }

    private fun copySqliteSequenceIfPresent(database: SQLiteDatabase) {
        val sourceHasSequence = database.rawQuery(
            "SELECT 1 FROM backup_source.sqlite_master WHERE name='sqlite_sequence' LIMIT 1",
            null,
        ).use { it.moveToFirst() }
        val destinationHasSequence = database.rawQuery(
            "SELECT 1 FROM main.sqlite_master WHERE name='sqlite_sequence' LIMIT 1",
            null,
        ).use { it.moveToFirst() }
        if (sourceHasSequence && destinationHasSequence) {
            database.execSQL("DELETE FROM main.sqlite_sequence")
            database.execSQL("INSERT INTO main.sqlite_sequence(name, seq) SELECT name, seq FROM backup_source.sqlite_sequence")
        }
    }

    private fun queryPragmaInt(database: SQLiteDatabase, query: String): Int =
        database.rawQuery(query, null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private data class SnapshotSchemaObject(val type: String, val name: String, val sql: String)

    fun restoreEncryptedBackup(uri: Uri, password: String) {
        require(password.length >= 6) { "Wpisz hasło użyte podczas tworzenia kopii" }
        val temporary = File.createTempFile("magazyn-restore-", ".db", application.cacheDir)
        val validation = application.getDatabasePath(VALIDATION_DATABASE_NAME)
        try {
            RestoreJournal.recordStage(application, "RESTORE_STARTED")
            application.contentResolver.openInputStream(uri)?.use { rawInput ->
                val input = DataInputStream(rawInput)
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                require(magic.contentEquals(MAGIC)) { "To nie jest kopia Magazyn Mobile" }
                val backupFormat = input.readInt()
                require(backupFormat == BACKUP_FORMAT_VERSION) { "Nieobsługiwana wersja pliku kopii" }
                val salt = ByteArray(16).also(input::readFully)
                val iv = ByteArray(12).also(input::readFully)
                try {
                    CipherInputStream(input, cipher(Cipher.DECRYPT_MODE, password, salt, iv)).use { decrypted ->
                        temporary.outputStream().use { decrypted.copyTo(it) }
                    }
                } catch (_: AEADBadTagException) {
                    error("Nieprawidłowe hasło albo uszkodzony plik kopii")
                } catch (failure: java.io.IOException) {
                    if (failure.cause is AEADBadTagException) error("Nieprawidłowe hasło albo uszkodzony plik kopii")
                    throw failure
                }
            } ?: error("Nie udało się odczytać pliku kopii")
            RestoreJournal.recordStage(application, "BACKUP_DECRYPTED")
            validateDatabase(temporary)
            migrateAndValidateInStaging(temporary, validation)
            replaceDatabase(validation)
        } finally {
            temporary.delete()
            deleteDatabaseFiles(validation)
        }
    }

    fun restartApplication() {
        val launchIntent = application.packageManager.getLaunchIntentForPackage(application.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: return
        val pending = PendingIntent.getActivity(application, 9021, launchIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = application.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 800L, pending)
        Process.killProcess(Process.myPid())
    }

    private fun validateDatabase(file: File, requireCurrentSchema: Boolean = false) {
        val header = file.inputStream().use { input -> ByteArray(16).also { require(input.read(it) == 16) } }
        require(header.contentEquals("SQLite format 3\u0000".toByteArray())) { "Odszyfrowany plik nie jest prawidłową bazą SQLite" }
        val sqlite = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val version = sqlite.rawQuery("PRAGMA user_version", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            require(BackupCompatibility.isSupportedDatabaseVersion(version)) {
                if (version > DATABASE_SCHEMA_VERSION) "Kopia pochodzi z nowszej, nieobsługiwanej wersji aplikacji"
                else "Kopia ma nieprawidłową wersję bazy"
            }
            if (requireCurrentSchema) {
                require(version == DATABASE_SCHEMA_VERSION) { "Nie udało się zmigrować kopii do aktualnej wersji bazy" }
            }
            sqlite.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).equals("ok", true)) { "Kontrola spójności kopii nie powiodła się" }
            }
            sqlite.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                require(!cursor.moveToFirst()) { "Kopia zawiera niespójne powiązania między danymi" }
            }
        } finally {
            sqlite.close()
        }
    }

    /**
     * Room otwiera osobną, tymczasową bazę z tym samym zestawem migracji. Dzięki
     * temu nie podmieniamy aktywnej bazy, dopóki schema Room i integralność SQLite
     * nie są już sprawdzone na kopii roboczej.
     */
    private fun migrateAndValidateInStaging(source: File, staging: File) {
        deleteDatabaseFiles(staging)
        source.copyTo(staging, overwrite = true)
        RestoreJournal.recordStage(application, "TEMP_DB_CREATED")
        var validationDatabase: AppDatabase? = null
        try {
            RestoreJournal.recordStage(application, "MIGRATION_STARTED")
            validationDatabase = application.createDatabase(VALIDATION_DATABASE_NAME)
            validationDatabase.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                if (cursor.moveToFirst() && cursor.getInt(0) != 0) error("Nie można bezpiecznie przygotować kopii do przywrócenia")
            }
            validationDatabase.close()
            validationDatabase = null
            RestoreJournal.deleteSidecars(staging)
            validateDatabase(staging, requireCurrentSchema = true)
            RestoreJournal.recordStage(application, "MIGRATION_OK")
        } finally {
            validationDatabase?.close()
        }
    }

    private fun replaceDatabase(source: File) {
        val destination = application.getDatabasePath(MagazynApplication.DATABASE_NAME)
        val staged = File(destination.parentFile, "${MagazynApplication.DATABASE_NAME}.restoring")
        val rollback = RestoreJournal.rollbackFile(application)
        val rollbackStaged = File(rollback.parentFile, "${rollback.name}.creating")
        destination.parentFile?.mkdirs()
        source.copyTo(staged, overwrite = true)
        FileOutputStream(staged, true).use { it.fd.sync() }
        rollback.parentFile?.mkdirs()
        rollbackStaged.delete()
        check(rollback.delete() || !rollback.exists()) { "Nie można przygotować bezpiecznego rollbacku" }
        application.beginDatabaseReplacement()
        var replacementAttempted = false
        try {
            RestoreJournal.recordStage(application, "DB_CLOSED")
            stabilizeClosedDatabase(destination)
            destination.copyTo(rollbackStaged, overwrite = true)
            FileOutputStream(rollbackStaged, true).use { it.fd.sync() }
            moveReplacing(rollbackStaged, rollback)
            RestoreJournal.recordStage(application, "ROLLBACK_CREATED")
            RestoreJournal.markPendingVerification(application)
            RestoreJournal.deleteSidecars(destination)
            replacementAttempted = true
            moveReplacing(staged, destination)
            RestoreJournal.recordStage(application, "DB_REPLACED_AWAITING_HEALTHCHECK")
        } catch (error: Exception) {
            val rollbackRestored = if (!replacementAttempted) {
                true
            } else {
                runCatching {
                    check(rollback.isFile) { "Brak pliku rollbacku" }
                    val rollbackRestore = File(destination.parentFile, "${destination.name}.rollback-after-failure")
                    rollback.copyTo(rollbackRestore, overwrite = true)
                    FileOutputStream(rollbackRestore, true).use { it.fd.sync() }
                    RestoreJournal.deleteSidecars(destination)
                    moveReplacing(rollbackRestore, destination)
                }.isSuccess
            }
            runCatching {
                RestoreJournal.markRolledBack(
                    application,
                    if (rollbackRestored) "ROLLBACK_AFTER_REPLACE_FAILURE" else "ROLLBACK_AFTER_REPLACE_FAILURE_FAILED",
                )
            }
            throw RestoreRequiresRestartException(
                if (rollbackRestored) {
                    "Nie udało się zainstalować kopii. Zachowano poprzednią bazę; aplikacja musi uruchomić się ponownie."
                } else {
                    "Nie udało się zainstalować kopii ani automatycznie odtworzyć poprzedniej bazy. Aplikacja musi uruchomić się ponownie i użyć zapisanego rollbacku."
                },
                error,
            )
        } finally {
            staged.delete()
            rollbackStaged.delete()
        }
    }

    /** Room jest już zamknięty, więc żaden zapis nie może wejść między checkpoint a kopię rollbacku. */
    private fun stabilizeClosedDatabase(databaseFile: File) {
        require(databaseFile.isFile) { "Nie znaleziono aktywnej bazy danych" }
        val sqlite = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            sqlite.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                if (cursor.moveToFirst() && cursor.getInt(0) != 0) error("Nie udało się ustabilizować aktywnej bazy przed przywróceniem")
            }
        } finally {
            sqlite.close()
        }
        RestoreJournal.deleteSidecars(databaseFile)
        validateDatabase(databaseFile, requireCurrentSchema = true)
    }

    private fun moveReplacing(source: File, destination: File) {
        runCatching {
            java.nio.file.Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            java.nio.file.Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteDatabaseFiles(databaseFile: File) {
        databaseFile.delete()
        RestoreJournal.deleteSidecars(databaseFile)
    }

    private fun cipher(mode: Int, password: String, salt: ByteArray, iv: ByteArray): Cipher {
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 150_000, 256)).encoded
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        }
    }

    private companion object {
        val MAGIC = "MAGAZYN_BACKUP\u0001".toByteArray()
        /** Wersja zaszyfrowanego kontenera kopii, niezależna od wersji aplikacji i Room. */
        const val BACKUP_FORMAT_VERSION = 1
        const val VALIDATION_DATABASE_NAME = "magazyn-restore-validation.db"
    }
}

class RestoreRequiresRestartException(message: String, cause: Throwable) : IllegalStateException(message, cause)
