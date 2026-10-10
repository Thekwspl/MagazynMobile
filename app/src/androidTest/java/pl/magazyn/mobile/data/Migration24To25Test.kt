package pl.magazyn.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration24To25Test {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-24-25.db"
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun preservesDataAndAssignsOnlyExactUnambiguousYardNames() {
        context.deleteDatabase(name)
        helper.createDatabase(name, 24).apply {
            execSQL("INSERT INTO warehouses(id,name,isMain,isArchived) VALUES('warehouse-main','Main',1,0)")
            execSQL("INSERT INTO products(id,name,variant,unit,category,groupName,subgroupName,aliases,tags,photoUri,isReturnable,lowStockThreshold,repeatIssueWeeks,isArchived,isHidden) VALUES('g','Gloves',NULL,'szt.','','','','','','',0,0,0,0,0)")
            execSQL("INSERT INTO employees(id,fullName,firstName,lastName,phoneNumbers,aliases,tags,isArchived,hrappkaDoNotHire) VALUES('p','Jan','Jan','','','','',0,0)")
            execSQL("INSERT INTO shipyards(id,name,isArchived) VALUES('s','Ulstein',0)")
            execSQL("INSERT INTO shipyard_leaders(shipyardId,employeeId) VALUES('s','p')")
            execSQL("INSERT INTO shipyard_stock_balances(shipyardId,productId,quantity) VALUES('s','g',-2)")
            execSQL("INSERT INTO stock_balances(warehouseId,productId,quantity,isKnown) VALUES('warehouse-main','g',-5,1)")
            execSQL("INSERT INTO orders(id,notebookId,employeeId,recipientLabel,siteLabel,status,plannedIssueDate,createdAtEpochMillis) VALUES('a',NULL,NULL,'Ulstein','Ulstein','DRAFT','2026-01-01',1)")
            execSQL("INSERT INTO orders(id,notebookId,employeeId,recipientLabel,siteLabel,status,plannedIssueDate,createdAtEpochMillis) VALUES('b',NULL,NULL,'Nieznana','Nieznana','DRAFT','2026-01-01',1)")
            execSQL("INSERT INTO order_lines(id,orderId,productId,rawText,quantity,unit,verificationStatus,isPrepared) VALUES('order-line','a','g','Gloves',2,'szt.','VERIFIED',1)")
            execSQL("INSERT INTO stock_movements(id,type,warehouseId,employeeId,recipientLabel,effectiveDate,createdAtEpochMillis,note) VALUES('m','SHIPYARD_ISSUE','warehouse-main',NULL,'Ulstein','2026-01-01',1,'')")
            execSQL("INSERT INTO stock_movements(id,type,warehouseId,employeeId,recipientLabel,effectiveDate,createdAtEpochMillis,note) VALUES('n','SHIPYARD_ISSUE','warehouse-main',NULL,'ulstein','2026-01-01',1,'')")
            execSQL("INSERT INTO stock_movements(id,type,warehouseId,employeeId,recipientLabel,effectiveDate,createdAtEpochMillis,note) VALUES('h','HISTORICAL_SHIPYARD_IMPORT','warehouse-main',NULL,'Ulstein','2026-01-01',1,'')")
            execSQL("INSERT INTO stock_movements(id,type,warehouseId,employeeId,recipientLabel,effectiveDate,createdAtEpochMillis,note) VALUES('history','HISTORICAL_ISSUE_IMPORT','warehouse-main',NULL,'Ulstein','2025-01-01',1,'Historia')")
            execSQL("INSERT INTO stock_movements(id,type,warehouseId,employeeId,recipientLabel,effectiveDate,createdAtEpochMillis,note) VALUES('person-history','HISTORICAL_ISSUE_IMPORT','warehouse-main','p','Ulstein','2025-01-01',1,'Pracownik')")
            execSQL("INSERT INTO stock_movement_lines(id,movementId,productId,quantityDelta,unit) VALUES('history-line','history','g',-3,'szt.')")
            close()
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_24_25).build()
        try {
            val sqlite = db.openHelper.writableDatabase
            fun count(sql: String) = sqlite.query(sql).use { c -> check(c.moveToFirst()); c.getInt(0) }
            assertEquals(25, count("PRAGMA user_version"))
            assertEquals(1, count("SELECT COUNT(*) FROM shipyards WHERE id='s' AND name='Ulstein'"))
            assertEquals(1, count("SELECT COUNT(*) FROM shipyard_leaders WHERE shipyardId='s' AND employeeId='p'"))
            assertEquals(-2, count("SELECT quantity FROM shipyard_stock_balances WHERE shipyardId='s' AND productId='g'"))
            assertEquals(-5, count("SELECT quantity FROM stock_balances WHERE warehouseId='warehouse-main' AND productId='g' AND isKnown=1"))
            assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE id='a' AND shipyardId='s' AND recipientLabel='Ulstein' AND siteLabel='Ulstein'"))
            assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE id='b' AND shipyardId IS NULL AND recipientLabel='Nieznana'"))
            assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE id='m' AND shipyardId='s' AND recipientLabel='Ulstein'"))
            assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE id='n' AND shipyardId IS NULL AND recipientLabel='ulstein'"))
            assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE id='h' AND shipyardId='s' AND recipientLabel='Ulstein'"))
            // Historyczne wydania pozostają w legacy do świadomego przypisania przez użytkownika.
            assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE id='history' AND shipyardId IS NULL AND employeeId IS NULL AND recipientLabel='Ulstein' AND effectiveDate='2025-01-01' AND note='Historia'"))
            assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE id='person-history' AND shipyardId IS NULL AND employeeId='p' AND recipientLabel='Ulstein'"))
            assertEquals(1, count("SELECT COUNT(*) FROM stock_movement_lines WHERE id='history-line' AND movementId='history' AND productId='g' AND quantityDelta=-3 AND unit='szt.'"))
            assertEquals(1, count("SELECT COUNT(*) FROM order_lines WHERE id='order-line' AND orderId='a' AND productId='g' AND rawText='Gloves' AND quantity=2 AND verificationStatus='VERIFIED' AND isPrepared=1"))
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
