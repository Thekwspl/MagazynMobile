package pl.magazyn.mobile.data

import androidx.sqlite.db.SupportSQLiteDatabase

/** Lekka, wyłącznie odczytowa kontrola wykonywana przed usunięciem rollbacku restore. */
object RestoredDatabaseHealthCheck {
    private const val SENTINEL_ID = "__restore_health_check__"

    suspend fun verify(database: AppDatabase) {
        val sqlite = database.openHelper.writableDatabase
        val version = sqlite.query("PRAGMA user_version").use { cursor ->
            check(cursor.moveToFirst()) { "Nie można odczytać wersji przywróconej bazy" }
            cursor.getInt(0)
        }
        check(version == DATABASE_SCHEMA_VERSION) { "Przywrócona baza ma niezgodną wersję schematu" }
        checkIntegrity(sqlite)
        checkForeignKeys(sqlite)
        checkCriticalTables(sqlite)

        // Minimalne zapytania przez wygenerowane implementacje Room DAO. Pusty wynik jest poprawny.
        database.warehouseDao().count()
        database.employeeDao().findById(SENTINEL_ID)
        database.productDao().findById(SENTINEL_ID)
        database.stockDao().find(SENTINEL_ID, SENTINEL_ID)
        database.movementDao().findActiveCustodiesForEmployee(SENTINEL_ID, SENTINEL_ID)
        database.orderDao().findById(SENTINEL_ID)
        database.taskStructureDao().getStepsForTaskNow(SENTINEL_ID)
    }

    private fun checkIntegrity(sqlite: SupportSQLiteDatabase) {
        sqlite.query("PRAGMA integrity_check").use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0).equals("ok", true)) {
                "Kontrola integralności przywróconej bazy nie powiodła się"
            }
        }
    }

    private fun checkForeignKeys(sqlite: SupportSQLiteDatabase) {
        sqlite.query("PRAGMA foreign_key_check").use { cursor ->
            check(!cursor.moveToFirst()) { "Przywrócona baza zawiera naruszone powiązania danych" }
        }
    }

    private fun checkCriticalTables(sqlite: SupportSQLiteDatabase) {
        listOf(
            "warehouses",
            "employees",
            "products",
            "stock_balances",
            "stock_movements",
            "orders",
            "notebook_tasks",
        ).forEach { table ->
            sqlite.query("SELECT COUNT(*) FROM $table").use { cursor ->
                check(cursor.moveToFirst()) { "Nie można odczytać tabeli $table" }
            }
        }
    }
}
