package pl.magazyn.mobile.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration45DataSafetyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "migration-4-5-order-safety.db"

    @Before
    fun prepareCleanDatabase() {
        context.deleteDatabase(databaseName)
    }

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun order104IsNeverDeletedUsingItsIdAlone() {
        val version4 = open(
            version = 4,
            onCreate = { database -> createFocusedVersion4Fixture(database) },
        )
        version4.writableDatabase.apply {
            execSQL("INSERT INTO orders(id, employeeId) VALUES('order-104', NULL)")
            execSQL("INSERT INTO orders(id, employeeId) VALUES('real-order', NULL)")
            execSQL("INSERT INTO order_lines(id, orderId, productId) VALUES('line-104', 'order-104', NULL)")
        }
        version4.close()

        val version5 = open(version = 5, onUpgrade = { database -> MIGRATION_4_5.migrate(database) })
        try {
            assertEquals(1, count(version5.writableDatabase, "SELECT COUNT(*) FROM orders WHERE id='order-104'"))
            assertEquals(1, count(version5.writableDatabase, "SELECT COUNT(*) FROM order_lines WHERE id='line-104'"))
            assertEquals(1, count(version5.writableDatabase, "SELECT COUNT(*) FROM orders WHERE id='real-order'"))
        } finally {
            version5.close()
        }
    }

    private fun open(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit = {},
        onUpgrade: (SupportSQLiteDatabase) -> Unit = {},
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = onUpgrade(db)
            })
            .build(),
    ).also { it.writableDatabase }

    private fun createFocusedVersion4Fixture(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE stock_movements(id TEXT PRIMARY KEY NOT NULL, employeeId TEXT)")
        db.execSQL("CREATE TABLE stock_movement_lines(id TEXT PRIMARY KEY NOT NULL, productId TEXT)")
        db.execSQL("CREATE TABLE stock_balances(warehouseId TEXT NOT NULL, productId TEXT NOT NULL, quantity REAL NOT NULL, PRIMARY KEY(warehouseId, productId))")
        db.execSQL("CREATE TABLE orders(id TEXT PRIMARY KEY NOT NULL, employeeId TEXT)")
        db.execSQL("CREATE TABLE order_lines(id TEXT PRIMARY KEY NOT NULL, orderId TEXT NOT NULL, productId TEXT)")
        db.execSQL("CREATE TABLE products(id TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE employees(id TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE job_positions(id TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE employee_job_positions(employeeId TEXT NOT NULL, positionId TEXT NOT NULL, PRIMARY KEY(employeeId, positionId))")
    }

    private fun count(database: SupportSQLiteDatabase, sql: String): Int =
        database.query(sql).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
}
