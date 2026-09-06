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
import pl.magazyn.mobile.data.CustodyEntity
import pl.magazyn.mobile.data.EmployeeEntity
import pl.magazyn.mobile.data.EmployeeJobPositionEntity
import pl.magazyn.mobile.data.JobPositionEntity
import pl.magazyn.mobile.data.OrderChangeEntity
import pl.magazyn.mobile.data.OrderLineEntity
import pl.magazyn.mobile.data.ProductEntity
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.data.StockBalanceEntity
import pl.magazyn.mobile.data.StockMovementEntity
import pl.magazyn.mobile.data.StockMovementLineEntity
import pl.magazyn.mobile.domain.normalizeCommaSeparated
import pl.magazyn.mobile.domain.normalizeDisplayName
import pl.magazyn.mobile.domain.normalizePersonName
import pl.magazyn.mobile.domain.normalizeFirstName
import pl.magazyn.mobile.domain.normalizePhoneNumbers

class OrdersViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    val orders = database.orderDao().observeActiveSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val people = database.employeeDao().observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val products = database.productDao().observeWithStock("warehouse-main")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val jobPositions = database.jobPositionDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun lines(orderId: String) = database.orderDao().observeLines(orderId)
    fun changes(orderId: String) = database.orderDao().observeChanges(orderId)

    fun updateOrder(orderId: String, employeeId: String?, recipientLabel: String, date: String) {
        if (runCatching { LocalDate.parse(date) }.isFailure) return
        viewModelScope.launch {
            val previous = database.orderDao().findById(orderId) ?: return@launch
            database.orderDao().updateOrder(orderId, employeeId, recipientLabel, date)
            val changed = buildList {
                if (previous.employeeId != employeeId || previous.recipientLabel != recipientLabel) add("odbiorcę na $recipientLabel")
                if (previous.plannedIssueDate != date) add("datę na $date")
            }
            if (changed.isNotEmpty()) log(orderId, "EDIT", "Zmieniono ${changed.joinToString(" i ")}")
        }
    }

    fun setPrepared(lineId: String, prepared: Boolean) {
        viewModelScope.launch {
            database.orderDao().setPrepared(lineId, prepared)
        }
    }

    fun updateLine(lineId: String, productId: String?, rawText: String, quantity: Long, unit: String) {
        if (quantity <= 0) return
        viewModelScope.launch {
            val previous = database.orderDao().findLineById(lineId) ?: return@launch
            val cleanText = rawText.trim()
            val newQuantity = quantity.toDouble()
            if (previous.productId == productId && previous.quantity == newQuantity && previous.unit == unit) return@launch
            database.orderDao().updateLine(
                lineId, productId, cleanText, newQuantity, unit,
                if (productId == null) "NEEDS_MAPPING" else "VERIFIED",
            )
            val previousName = previous.productId?.let { database.productDao().findById(it) }
                ?.let { it.name + it.variant?.let { variant -> " · $variant" }.orEmpty() }
                ?: previous.rawText
            val newName = productId?.let { database.productDao().findById(it) }
                ?.let { it.name + it.variant?.let { variant -> " · $variant" }.orEmpty() }
                ?: cleanText
            val descriptions = buildList {
                if (previous.productId != productId) {
                    add("Poprawiono:\n\nZ: \"$previousName\"\n\nNa: \"$newName\"")
                }
                if (previous.quantity != newQuantity || previous.unit != unit) {
                    add("Poprawiono ilość \"$newName\"\n\nZ: ${quantityLabel(previous.quantity)}\n\nNa: ${quantityLabel(newQuantity)}")
                }
            }
            if (descriptions.isNotEmpty()) log(previous.orderId, "EDIT_LINE", descriptions.joinToString("\n\n"))
        }
    }

    fun addLine(orderId: String) {
        viewModelScope.launch {
            database.orderDao().upsertLines(listOf(OrderLineEntity(UUID.randomUUID().toString(), orderId, null, "Nowa pozycja", 1.0, "szt.", "NEEDS_MAPPING")))
            log(orderId, "ADD_LINE", "Dodano nową pozycję")
        }
    }

    fun deleteLine(lineId: String) {
        viewModelScope.launch {
            val line = database.orderDao().findLineById(lineId) ?: return@launch
            database.orderDao().deleteLine(lineId)
            log(line.orderId, "DELETE_LINE", "Usunięto pozycję: ${line.rawText}")
        }
    }

    fun addProductTags(productId: String, tags: String) {
        viewModelScope.launch {
            val product = database.productDao().findById(productId) ?: return@launch
            database.productDao().update(product.copy(tags = normalizeCommaSeparated(listOf(product.tags, tags).filter(String::isNotBlank).joinToString(", "))))
        }
    }

    fun cancelOrder(orderId: String) {
        viewModelScope.launch { log(orderId, "CANCEL", "Anulowano zamówienie"); database.orderDao().setStatus(orderId, "CANCELLED") }
    }

    fun createPerson(firstName: String, lastName: String, phones: String, positions: String, aliases: String, onCreated: (String, String) -> Unit) {
        val first = normalizeFirstName(firstName)
        val last = normalizePersonName(lastName)
        if (first.isBlank() || last.isBlank()) return
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val fullName = "$first $last"
            database.withTransaction {
                database.employeeDao().insert(EmployeeEntity(id, fullName, first, last, normalizePhoneNumbers(phones), normalizeCommaSeparated(aliases)))
                positions.split(',').map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase).forEach { position ->
                    val normalized = normalizeDisplayName(position)
                    val positionId = "position-" + normalized.lowercase().replace(Regex("[^a-ząćęłńóśźż0-9]+"), "-").trim('-')
                    database.jobPositionDao().upsert(listOf(JobPositionEntity(positionId, normalized)))
                    database.jobPositionDao().link(listOf(EmployeeJobPositionEntity(id, positionId)))
                }
            }
            onCreated(id, fullName)
        }
    }

    fun createProduct(name: String, variant: String, unit: String, quantity: Long, tags: String, onCreated: (ProductWithStock) -> Unit) {
        val normalizedName = normalizeDisplayName(name)
        if (normalizedName.isBlank() || unit.isBlank() || quantity < 0L) return
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val entity = ProductEntity(id, normalizedName, variant.trim().ifBlank { null }, unit.trim(), tags = normalizeCommaSeparated(tags))
            database.withTransaction {
                database.productDao().insert(entity)
                database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", id, quantity.toDouble())))
            }
            onCreated(ProductWithStock(id, entity.name, entity.variant, entity.unit, "", "", "", "", entity.tags, "", false, 0.0, 0, false, quantity.toDouble(), true))
        }
    }

    fun realize(orderId: String, selectedEmployeeId: String?, selectedDate: String) {
        viewModelScope.launch {
            database.withTransaction {
                val order = database.orderDao().findById(orderId) ?: return@withTransaction
                database.orderDao().updateOrder(orderId, selectedEmployeeId, order.recipientLabel, selectedDate)
                val shipyard = if (selectedEmployeeId == null) order.siteLabel?.let { label ->
                    database.shipyardDao().getAllNow().firstOrNull { it.name.equals(label, true) && !it.isArchived }
                } else null
                if (selectedEmployeeId == null && shipyard == null) return@withTransaction
                val lines = database.orderDao().getLinesNow(orderId)
                if (lines.isEmpty() || lines.any { it.productId == null || !it.isPrepared }) return@withTransaction
                val movementId = UUID.randomUUID().toString()
                database.movementDao().insertMovement(
                    StockMovementEntity(
                        id = movementId,
                        type = if (shipyard == null) "ISSUE" else "SHIPYARD_ISSUE",
                        warehouseId = "warehouse-main",
                        employeeId = selectedEmployeeId,
                        recipientLabel = shipyard?.name.orEmpty(),
                        effectiveDate = selectedDate,
                        createdAtEpochMillis = System.currentTimeMillis(),
                        note = if (shipyard == null) "Realizacja zamówienia" else "Realizacja zamówienia dla stoczni",
                    ),
                )
                lines.forEach { line ->
                    val productId = line.productId ?: return@forEach
                    val product = database.productDao().findById(productId) ?: return@forEach
                    val current = database.stockDao().find("warehouse-main", productId)?.quantity ?: 0.0
                    database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", productId, current - line.quantity)))
                    if (shipyard != null) {
                        val currentShipyard = database.shipyardDao().findStock(shipyard.id, productId)?.quantity ?: 0.0
                        database.shipyardDao().upsertStock(pl.magazyn.mobile.data.ShipyardStockBalanceEntity(shipyard.id, productId, currentShipyard + line.quantity))
                    }
                    database.movementDao().insertLine(
                        StockMovementLineEntity(UUID.randomUUID().toString(), movementId, productId, -line.quantity, product.unit),
                    )
                    if (product.isReturnable && selectedEmployeeId != null) {
                        database.movementDao().insertCustody(
                            CustodyEntity(UUID.randomUUID().toString(), selectedEmployeeId, productId, line.quantity, movementId, selectedDate),
                        )
                    }
                }
                database.orderDao().setStatus(orderId, "ISSUED")
                log(orderId, "ISSUE", "Zrealizowano zamówienie i wydano ${lines.size} pozycji")
            }
        }
    }

    private suspend fun log(orderId: String, action: String, description: String) {
        database.orderDao().insertChange(OrderChangeEntity(UUID.randomUUID().toString(), orderId, action, description, System.currentTimeMillis()))
    }

    private fun quantityLabel(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
