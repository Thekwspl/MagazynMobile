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
            execSQL("INSERT INTO orders(id,notebookId,employeeId,recipientLabel,siteLabel,status,plannedIssueDate,createdAtEpochMillis) VALUES('a',NULL,NULL,'Ulstein','Ulstein','DRAFT','2026-01-01',1)")
            execSQL("INSERT INTO orders(id,notebookId,employeeId,recipientLabel,siteLabel,status,plannedIssueDate,createdAtEpochMillis) VALUES('b',NULL,NULL,'Nieznana','Nieznana','DRAFT','2026-01-01',1)")
            execSQL("INSERT INTO stock_movements(id,type,warehouseId,employeeId,recipientLabel,effectiveDate,createdAtEpochMillis,note) VALUES('m','SHIPYARD_ISSUE','warehouse-main',NULL,'Ulstein','2026-01-01',1,'')")
            execSQL("INSERT INTO stock_movements(id,type,warehouseId,employeeId,recipientLabel,effectiveDate,createdAtEpochMillis,note) VALUES('n','SHIPYARD_ISSUE','warehouse-main',NULL,'ulstein','2026-01-01',1,'')")
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
            assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE id='a' AND shipyardId='s' AND recipientLabel='Ulstein' AND siteLabel='Ulstein'"))
            assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE id='b' AND shipyardId IS NULL AND recipientLabel='Nieznana'"))
            assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE id='m' AND shipyardId='s' AND recipientLabel='Ulstein'"))
            assertEquals(1, count("SELECT COUNT(*) FROM stock_movements WHERE id='n' AND shipyardId IS NULL AND recipientLabel='ulstein'"))
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
