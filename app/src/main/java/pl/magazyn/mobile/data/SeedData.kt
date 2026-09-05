package pl.magazyn.mobile.data

import androidx.room.withTransaction

class SeedData(private val database: AppDatabase) {
    suspend fun ensureCreated() {
        database.withTransaction {
            if (database.warehouseDao().count() > 0) return@withTransaction
            database.warehouseDao().upsert(
                listOf(WarehouseEntity("warehouse-main", "Magazyn główny", isMain = true)),
            )
        }
    }
}
