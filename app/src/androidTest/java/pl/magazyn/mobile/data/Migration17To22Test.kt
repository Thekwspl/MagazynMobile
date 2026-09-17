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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration17To24Test {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "migration-17-22.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Before
    fun prepareCleanDatabase() {
        context.deleteDatabase(databaseName)
    }

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migratesRepresentativeDataFrom17To24AndOpensWithCurrentRoom() = runBlocking {
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
            .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24)
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
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM employees WHERE id='employee-17' AND hrappkaId IS NULL AND hrappkaExternalId IS NULL AND hrappkaDoNotHire=0"))
            assertEquals(0, scalarInt(sqlite, "SELECT COUNT(*) FROM employee_hrappka_phones"))
            assertEquals(0, scalarInt(sqlite, "SELECT COUNT(*) FROM employee_hrappka_links"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM room_master_table"))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migration22To24PreservesEmployeeHistoryCustodyAndStock() = runBlocking {
        helper.createDatabase(databaseName, 17).apply {
            execSQL("INSERT INTO warehouses(id,name,isMain,isArchived) VALUES('warehouse-main','Główny',1,0)")
            execSQL("INSERT INTO employees(id,fullName,firstName,lastName,phoneNumbers,aliases,tags,isArchived) VALUES('employee-22','Anna Testowa','Anna','Testowa','+48 500 000 001','','',0)")
            execSQL("INSERT INTO products(id,name,variant,unit,category,groupName,subgroupName,aliases,tags,photoUri,isReturnable,lowStockThreshold,repeatIssueWeeks,isArchived) VALUES('product-22','Kask',NULL,'szt.','','BHP','','','','',1,0,0,0)")
            execSQL("INSERT INTO stock_balances(warehouseId,productId,quantity,isKnown) VALUES('warehouse-main','product-22',4,1)")
            execSQL("INSERT INTO stock_movements(id,type,warehouseId,employeeId,recipientLabel,effectiveDate,createdAtEpochMillis,note) VALUES('movement-22','ISSUE','warehouse-main','employee-22','','2026-09-01',1,'fixture 22')")
            execSQL("INSERT INTO stock_movement_lines(id,movementId,productId,quantityDelta,unit) VALUES('line-22','movement-22','product-22',-1,'szt.')")
            execSQL("INSERT INTO custodies(id,employeeId,productId,quantity,issuedMovementId,issuedDate,returnedDate) VALUES('custody-22','employee-22','product-22',1,'movement-22','2026-09-01',NULL)")
            beginTransaction()
            try {
                listOf(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
                    .forEach { migration -> migration.migrate(this) }
                version = 22
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
            close()
        }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_22_23, MIGRATION_23_24)
            .build()
        try {
            val sqlite = migrated.openHelper.writableDatabase
            assertEquals(24, scalarInt(sqlite, "PRAGMA user_version"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM employees WHERE id='employee-22' AND hrappkaId IS NULL AND hrappkaExternalId IS NULL AND hrappkaDoNotHire=0"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM stock_movements WHERE id='movement-22'"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM stock_movement_lines WHERE id='line-22'"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM custodies WHERE id='custody-22' AND returnedDate IS NULL"))
            assertEquals(4, scalarInt(sqlite, "SELECT quantity FROM stock_balances WHERE warehouseId='warehouse-main' AND productId='product-22'"))
            assertEquals(0, scalarInt(sqlite, "SELECT COUNT(*) FROM employee_hrappka_phones"))
            assertEquals("ok", scalarText(sqlite, "PRAGMA integrity_check").lowercase())
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migration23To24MovesExistingHrappkaLinkAndPhoneAndNormalizesUnicodeNames() = runBlocking {
        helper.createDatabase(databaseName, 17).apply {
            execSQL("INSERT INTO warehouses(id,name,isMain,isArchived) VALUES('warehouse-main','Główny',1,0)")
            execSQL("INSERT INTO employees(id,fullName,firstName,lastName,phoneNumbers,aliases,tags,isArchived) VALUES('employee-a','PIOTR PAWŁOWSKI','PIOTR','PAWŁOWSKI','+48 500 000 001','','tag',0)")
            execSQL("INSERT INTO employees(id,fullName,firstName,lastName,phoneNumbers,aliases,tags,isArchived) VALUES('employee-b','ANNA BUKOWIECKA-ŁYTKA','ANNA','BUKOWIECKA-ŁYTKA','','','',0)")
            execSQL("INSERT INTO products(id,name,variant,unit,category,groupName,subgroupName,aliases,tags,photoUri,isReturnable,lowStockThreshold,repeatIssueWeeks,isArchived) VALUES('product','Kask',NULL,'szt.','','BHP','','','','',1,0,0,0)")
            execSQL("INSERT INTO stock_balances(warehouseId,productId,quantity,isKnown) VALUES('warehouse-main','product',4,1)")
            execSQL("INSERT INTO stock_movements(id,type,warehouseId,employeeId,recipientLabel,effectiveDate,createdAtEpochMillis,note) VALUES('movement','ISSUE','warehouse-main','employee-a','','2026-09-01',1,'fixture')")
            execSQL("INSERT INTO stock_movement_lines(id,movementId,productId,quantityDelta,unit) VALUES('line','movement','product',-1,'szt.')")
            execSQL("INSERT INTO custodies(id,employeeId,productId,quantity,issuedMovementId,issuedDate,returnedDate) VALUES('custody','employee-a','product',1,'movement','2026-09-01',NULL)")
            beginTransaction()
            try {
                listOf(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23)
                    .forEach { migration -> migration.migrate(this) }
                execSQL("UPDATE employees SET hrappkaId=111, hrappkaExternalId='ABC', hrappkaDoNotHire=0 WHERE id='employee-a'")
                execSQL("INSERT INTO employee_hrappka_phones(employeeId,normalizedNumber,displayNumber) VALUES('employee-a','+48500000001','+48 500 000 001')")
                version = 23
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
            close()
        }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_23_24)
            .build()
        try {
            val sqlite = migrated.openHelper.writableDatabase
            assertEquals(24, scalarInt(sqlite, "PRAGMA user_version"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM employee_hrappka_links WHERE hrappkaId=111 AND employeeId='employee-a' AND externalId='ABC' AND doNotHire=0"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM employee_hrappka_phones WHERE hrappkaId=111 AND employeeId='employee-a'"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM employees WHERE id='employee-a' AND firstName='Piotr' AND lastName='Pawłowski' AND fullName='Piotr Pawłowski' AND phoneNumbers='+48 500 000 001' AND tags='tag' AND hrappkaId IS NULL AND hrappkaExternalId IS NULL AND hrappkaDoNotHire=0"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM employees WHERE id='employee-b' AND firstName='Anna' AND lastName='Bukowiecka-Łytka'"))
            assertEquals(0, scalarInt(sqlite, "SELECT COUNT(*) FROM employee_hrappka_links WHERE employeeId='employee-b'"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM stock_movements WHERE id='movement'"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM stock_movement_lines WHERE id='line'"))
            assertEquals(1, scalarInt(sqlite, "SELECT COUNT(*) FROM custodies WHERE id='custody' AND returnedDate IS NULL"))
            assertEquals(4, scalarInt(sqlite, "SELECT quantity FROM stock_balances WHERE warehouseId='warehouse-main' AND productId='product'"))
            assertEquals("ok", scalarText(sqlite, "PRAGMA integrity_check").lowercase())
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            migrated.close()
        }
    }

    private fun scalarInt(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
        database.query(sql).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private fun scalarText(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        database.query(sql).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
}
