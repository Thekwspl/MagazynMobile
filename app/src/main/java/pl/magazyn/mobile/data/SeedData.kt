package pl.magazyn.mobile.data

import androidx.room.withTransaction

class SeedData(private val database: AppDatabase) {
    suspend fun ensureCreated() {
        database.withTransaction {
            if (database.warehouseDao().count() == 0) {
                database.warehouseDao().upsert(
                    listOf(WarehouseEntity("warehouse-main", "Magazyn główny", isMain = true)),
                )
            }
            val commonPlaces = listOf("Mykle", "NK", "Kleven", "Ulstein", "Sandvik", "SK", "M2", "Ulstein 3", "M1", "Idar", "Bjorn Ove")
            commonPlaces.forEachIndexed { index, name ->
                if (database.taskStructureDao().findPlaceByName(name) == null) {
                    database.taskStructureDao().insertPlace(TaskPlaceEntity("seed-task-place-$index", name))
                }
            }
            listOf("Ulstein" to "UL", "Kleven" to "KL").forEach { (name, alias) ->
                val place = database.taskStructureDao().findPlaceByName(name)
                if (place != null && database.taskStructureDao().findAlias(pl.magazyn.mobile.domain.ImportParser.key(alias)) == null) {
                    database.taskStructureDao().insertAlias(TaskPlaceAliasEntity("seed-task-alias-${alias.lowercase()}", place.id, alias, pl.magazyn.mobile.domain.ImportParser.key(alias)))
                }
            }
        }
    }
}
