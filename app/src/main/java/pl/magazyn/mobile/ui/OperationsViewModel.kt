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
import pl.magazyn.mobile.data.*

enum class WarehouseOperationType(val label: String) {
    DELIVERY("Dostawa"),
    SHIPYARD_RETURN("Zwrot ze stoczni"),
    FOUND("Znalezione")
}

data class OperationLineRequest(val productId: String, val quantity: Long)

class OperationsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    val warehouses = database.warehouseDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val people = database.employeeDao().observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shipyards = database.shipyardDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun products(warehouseId: String) = database.productDao().observeWithStock(warehouseId)
    fun shipyardStock(shipyardId: String) = database.shipyardDao().observeStock(shipyardId)

    fun submit(
        type: WarehouseOperationType,
        employeeId: String?,
        shipyardId: String?,
        lines: List<OperationLineRequest>,
        effectiveDate: String,
    ) {
        val valid = lines.filter { it.productId.isNotBlank() && it.quantity > 0 }
        if (valid.isEmpty() || runCatching { LocalDate.parse(effectiveDate) }.isFailure) return
        viewModelScope.launch {
            database.withTransaction {
                val mainWarehouse = warehouses.value.firstOrNull { it.isMain } ?: return@withTransaction
                val employee = employeeId?.let { database.employeeDao().findById(it) }
                val shipyard = shipyards.value.firstOrNull { it.id == shipyardId }
                val movementWarehouseId = mainWarehouse.id
                val movementId = UUID.randomUUID().toString()
                val recipient = when (type) {
                    WarehouseOperationType.SHIPYARD_RETURN -> shipyard?.name.orEmpty()
                    else -> ""
                }
                database.movementDao().insertMovement(
                    StockMovementEntity(
                        id = movementId,
                        type = type.name,
                        warehouseId = movementWarehouseId,
                        employeeId = employee?.id,
                        recipientLabel = recipient,
                        effectiveDate = effectiveDate,
                        createdAtEpochMillis = System.currentTimeMillis(),
                        note = when (type) {
                            WarehouseOperationType.SHIPYARD_RETURN -> "Zwrot ze stoczni ${shipyard?.name.orEmpty()}"
                            else -> type.label
                        },
                    ),
                )
                valid.forEach { line ->
                    val product = database.productDao().findById(line.productId) ?: return@forEach
                    val quantity = line.quantity.toDouble()
                    when (type) {
                        WarehouseOperationType.DELIVERY, WarehouseOperationType.FOUND -> {
                            changeWarehouseStock(mainWarehouse.id, line.productId, quantity)
                        }
                        WarehouseOperationType.SHIPYARD_RETURN -> {
                            val yard = shipyard ?: return@forEach
                            val currentYard = database.shipyardDao().findStock(yard.id, line.productId)?.quantity ?: 0.0
                            database.shipyardDao().upsertStock(ShipyardStockBalanceEntity(yard.id, line.productId, currentYard - quantity))
                            changeWarehouseStock(mainWarehouse.id, line.productId, quantity)
                        }
                    }
                    database.movementDao().insertLine(
                        StockMovementLineEntity(
                            id = UUID.randomUUID().toString(),
                            movementId = movementId,
                            productId = line.productId,
                            quantityDelta = quantity,
                            unit = product.unit,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun changeWarehouseStock(warehouseId: String, productId: String, delta: Double) {
        val current = database.stockDao().find(warehouseId, productId)?.quantity ?: 0.0
        database.stockDao().upsert(listOf(StockBalanceEntity(warehouseId, productId, current + delta)))
    }
}
