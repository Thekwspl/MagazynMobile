package pl.magazyn.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration17To22Test {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "migration-17-22.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migratesRepresentativeDataFrom17To22AndOpensWithCurrentRoom() = runBlocking {
        helper.createDatabase(databaseName, 17).apply {
            execSQL("INSERT INTO warehouses(id,name,isMain,isArchived) VALUES('warehouse-main','Główny',1,0)")
            execSQL("INSERT INTO employees(id,fullName,firstName,lastName,phoneNumbers,aliases,tags,isArchived) VALUES('employee-17','Jan Kowalski','Jan','Kowalski','','','',0)")
            execSQL("INSERT INTO products(id,name,variant,unit,category,groupName,subgroupName,aliases,tags,photoUri,isReturnable,lowStockThreshold,repeatIssueWeeks,isArchived) VALUES('product-17','Bluza','52','szt.','','Odzież','Bluzy','','','',1,2,4,0)")
            execSQL("INSERT INTO stock_balances(warehouseId,productId,quantity,isKnown) VALUES('warehouse-main','product-17',7,1)")
            execSQL("INSERT INTO stock_movements(id,type,warehouseId,employeeId,recipientLabel,effectiveDate,createdAtEpochMillis,note) VALUES('movement-17','ISSUE','warehouse-main','employee-17','','2026-09-01',1,'fixture')")
            execSQL("INSERT INTO stock_movement_lines(id,movementId,productId,quantityDelta,unit) VALUES('line-17','movement-17','product-17',-2,'szt.')")
            execSQL("INSERT INTO order_notebooks(id,rawText,status,detectedType,createdAtEpochMillis) VALUES('notebook-17','Treść','ACCEPTED','TASK',1)")
            execSQL("INSERT INTO notebook_tasks(id,notebookId,text,isCompleted,position,dueDate,priority,employeeId,shipyardId,productId,orderId) VALUES('task-17','notebook-17','Zadanie',0,0,'2026-09-02','HIGH','employee-17',NULL,'product-17',NULL)")
            execSQL("INSERT INTO orders(id,notebookId,employeeId,recipientLabel,siteLabel,status,plannedIssueDate,createdAtEpochMillis) VALUES('order-17','notebook-17','employee-17','Kowalski Jan',NULL,'DRAFT','2026-09-03',1)")
            execSQL("INSERT INTO order_lines(id,orderId,productId,rawText,quantity,unit,verificationStatus,isPrepared) VALUES('order-line-17','order-17','product-17','Bluza 52',1,'szt.','VERIFIED',0)")
            close()
        }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
            .build()
        try {
            val sqlite = migrated.openHelper.writableDatabase
            assertEquals(DATABASE_SCHEMA_VERSION, scalarInt(sqlite, "PRAGMA user_version"))
            assertEquals("ok", scalarText(sqlite, "PRAGMA integrity_check").lowercase())
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }

            assertEquals("Bluza", migrated.productDao().findById("product-17")?.name)
            assertEquals(7.0, migrated.stockDao().find("warehouse-main", "product-17")?.quantity ?: Double.NaN, 0.0)
            assertEquals("DRAFT", migrated.orderDao().findById("order-17")?.status)
            assertTrue(migrated.taskStructureDao().getStepsForTaskNow("task-17").isNotEmpty())
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM notebook_task_employees WHERE taskId='task-17' AND employeeId='employee-17'"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM stock_movement_lines WHERE id='line-17'"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM room_master_table"))
        } finally {
            migrated.close()
        }
    }

    private fun scalarInt(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
        database.query(sql).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private fun scalarText(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        database.query(sql).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
}
