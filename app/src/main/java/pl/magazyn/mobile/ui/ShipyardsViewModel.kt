package pl.magazyn.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.ShipyardEntity
import pl.magazyn.mobile.data.StockBalanceEntity
import pl.magazyn.mobile.data.StockMovementEntity
import pl.magazyn.mobile.data.StockMovementLineEntity
import pl.magazyn.mobile.data.ShipyardStockBalanceEntity
import pl.magazyn.mobile.data.ShipyardLeaderEntity
import pl.magazyn.mobile.domain.StockMath
import pl.magazyn.mobile.domain.normalizeDisplayName

data class ShipyardIssueRequest(val productId: String, val quantity: Long)

class ShipyardsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    val shipyards = database.shipyardDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val products = database.productDao().observeWithStock("warehouse-main")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val people = database.employeeDao().observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun stock(shipyardId: String) = database.shipyardDao().observeStock(shipyardId)
    fun leaderIds(shipyardId: String) = database.shipyardDao().observeLeaderIds(shipyardId)

    fun saveLeaders(shipyardId: String, employeeIds: Set<String>) {
        viewModelScope.launch {
            database.withTransaction {
                database.shipyardDao().clearLeaders(shipyardId)
                if (employeeIds.isNotEmpty()) database.shipyardDao().insertLeaders(employeeIds.map { ShipyardLeaderEntity(shipyardId, it) })
            }
        }
    }

    fun addShipyard(name: String) {
        val normalized = normalizeDisplayName(name)
        if (normalized.isBlank()) return
        viewModelScope.launch {
            val matching = database.shipyardDao().getAllNow().firstOrNull { it.name.equals(normalized, true) }
            if (matching != null && !matching.isArchived) return@launch
            if (matching != null) {
                database.shipyardDao().restoreById(matching.id)
                database.shipyardDao().updateName(matching.id, normalized)
            } else database.shipyardDao().insert(ShipyardEntity(UUID.randomUUID().toString(), normalized))
        }
    }

    fun renameShipyard(id: String, name: String) {
        val normalized = normalizeDisplayName(name)
        if (normalized.isBlank()) return
        viewModelScope.launch {
            val all = database.shipyardDao().getAllNow()
            if (all.any { it.id != id && it.name.equals(normalized, true) && !it.isArchived }) return@launch
            val previous = all.firstOrNull { it.id == id } ?: return@launch
            database.withTransaction {
                database.shipyardDao().updateName(id, normalized)
                database.shipyardDao().renameOrderSiteLabels(previous.name, normalized)
                database.shipyardDao().renameOrderRecipients(previous.name, normalized)
                database.shipyardDao().renameMovementRecipients(previous.name, normalized)
            }
        }
    }

    fun removeShipyard(id: String) {
        viewModelScope.launch { database.shipyardDao().archive(id) }
    }

    fun issue(shipyard: ShipyardEntity, items: List<ShipyardIssueRequest>, effectiveDate: String) {
        val valid = items.filter { it.productId.isNotBlank() && it.quantity > 0L }
        if (valid.isEmpty() || runCatching { LocalDate.parse(effectiveDate) }.isFailure) return
        viewModelScope.launch {
            database.withTransaction {
                val movementId = UUID.randomUUID().toString()
                database.movementDao().insertMovement(
                    StockMovementEntity(
                        id = movementId,
                        type = "SHIPYARD_ISSUE",
                        warehouseId = "warehouse-main",
                        employeeId = null,
                        recipientLabel = shipyard.name,
                        effectiveDate = effectiveDate,
                        createdAtEpochMillis = System.currentTimeMillis(),
                        note = "Wydanie dla stoczni",
                    ),
                )
                valid.forEach { item ->
                    val product = database.productDao().findById(item.productId) ?: return@forEach
                    val current = database.stockDao().find("warehouse-main", item.productId)?.quantity ?: 0.0
                    database.stockDao().upsert(
                        listOf(StockBalanceEntity("warehouse-main", item.productId, StockMath.afterIssue(current, item.quantity.toDouble()))),
                    )
                    val currentShipyardStock = database.shipyardDao().findStock(shipyard.id, item.productId)?.quantity ?: 0.0
                    database.shipyardDao().upsertStock(
                        ShipyardStockBalanceEntity(shipyard.id, item.productId, currentShipyardStock + item.quantity.toDouble()),
                    )
                    database.movementDao().insertLine(
                        StockMovementLineEntity(UUID.randomUUID().toString(), movementId, item.productId, -item.quantity.toDouble(), product.unit),
                    )
                }
            }
        }
    }

    fun returnToMainWarehouse(shipyard: ShipyardEntity, items: List<ShipyardIssueRequest>, effectiveDate: String) {
        val valid = items.filter { it.productId.isNotBlank() && it.quantity > 0L }
        if (valid.isEmpty() || runCatching { LocalDate.parse(effectiveDate) }.isFailure) return
        viewModelScope.launch {
            database.withTransaction {
                if (valid.any { (database.shipyardDao().findStock(shipyard.id, it.productId)?.quantity ?: 0.0) < it.quantity }) return@withTransaction
                val movementId = UUID.randomUUID().toString()
                database.movementDao().insertMovement(
                    StockMovementEntity(movementId, "SHIPYARD_RETURN", "warehouse-main", null, shipyard.name, effectiveDate, System.currentTimeMillis(), "Zwrot ze stoczni"),
                )
                valid.forEach { item ->
                    val product = database.productDao().findById(item.productId) ?: return@forEach
                    val main = database.stockDao().find("warehouse-main", item.productId)?.quantity ?: 0.0
                    val yard = database.shipyardDao().findStock(shipyard.id, item.productId)?.quantity ?: 0.0
                    database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", item.productId, main + item.quantity)))
                    database.shipyardDao().upsertStock(ShipyardStockBalanceEntity(shipyard.id, item.productId, yard - item.quantity))
                    database.movementDao().insertLine(StockMovementLineEntity(UUID.randomUUID().toString(), movementId, item.productId, item.quantity.toDouble(), product.unit))
                }
            }
        }
    }
}
