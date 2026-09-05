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

data class InventoryCount(val productId: String, val actualQuantity: Long)

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    val warehouses = database.warehouseDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun products(warehouseId: String) = database.productDao().observeWithStock(warehouseId)

    fun applyInventory(warehouseId: String, counts: List<InventoryCount>, effectiveDate: String) {
        if (warehouseId.isBlank() || counts.isEmpty() || runCatching { LocalDate.parse(effectiveDate) }.isFailure) return
        viewModelScope.launch {
            database.withTransaction {
                val movementId = UUID.randomUUID().toString()
                database.movementDao().insertMovement(
                    StockMovementEntity(
                        id = movementId,
                        type = "INVENTORY_CORRECTION",
                        warehouseId = warehouseId,
                        employeeId = null,
                        effectiveDate = effectiveDate,
                        createdAtEpochMillis = System.currentTimeMillis(),
                        note = "Inwentaryzacja · ${counts.size} pozycji",
                    ),
                )
                counts.forEach { count ->
                    val product = database.productDao().findById(count.productId) ?: return@forEach
                    val previous = database.stockDao().find(warehouseId, count.productId)?.quantity ?: 0.0
                    val actual = count.actualQuantity.toDouble()
                    database.stockDao().upsert(listOf(StockBalanceEntity(warehouseId, count.productId, actual, true)))
                    database.movementDao().insertLine(
                        StockMovementLineEntity(UUID.randomUUID().toString(), movementId, count.productId, actual - previous, product.unit),
                    )
                }
            }
        }
    }
}
