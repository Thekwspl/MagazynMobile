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
import pl.magazyn.mobile.data.EmployeeEntity
import pl.magazyn.mobile.data.EmployeeJobPositionEntity
import pl.magazyn.mobile.data.EmployeeSummary
import pl.magazyn.mobile.data.JobPositionEntity
import pl.magazyn.mobile.data.CustodyEntity
import pl.magazyn.mobile.data.StockBalanceEntity
import pl.magazyn.mobile.data.StockMovementEntity
import pl.magazyn.mobile.data.StockMovementLineEntity
import pl.magazyn.mobile.data.IssueAmendmentEntity
import pl.magazyn.mobile.data.IssueReturnEntity
import pl.magazyn.mobile.data.EmployeeIssue
import pl.magazyn.mobile.domain.StockMath
import pl.magazyn.mobile.domain.normalizeCommaSeparated
import pl.magazyn.mobile.domain.normalizeFirstName
import pl.magazyn.mobile.domain.normalizePersonName
import pl.magazyn.mobile.domain.normalizePhoneNumbers

data class IssueRequest(val productId: String, val quantity: Long)

class PeopleViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    val people = database.employeeDao().observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val products = database.productDao().observeWithStock("warehouse-main")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val jobPositions = database.jobPositionDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun possessions(employeeId: String) = database.movementDao().observeActiveCustody(employeeId)
    fun issueHistory(employeeId: String) = database.movementDao().observeEmployeeIssues(employeeId)

    fun removePerson(employeeId: String) {
        viewModelScope.launch { database.employeeDao().archive(employeeId) }
    }

    fun savePerson(existing: EmployeeSummary?, firstName: String, lastName: String, phoneNumbers: String, positionNames: String, aliases: String, tags: String) {
        val normalizedFirstName = normalizeFirstName(firstName)
        val normalizedLastName = normalizePersonName(lastName)
        if (normalizedFirstName.isBlank() || normalizedLastName.isBlank()) return
        viewModelScope.launch {
            database.withTransaction {
                val employeeId = existing?.id ?: UUID.randomUUID().toString()
                val employee = EmployeeEntity(
                    id = employeeId,
                    fullName = "$normalizedFirstName $normalizedLastName",
                    firstName = normalizedFirstName,
                    lastName = normalizedLastName,
                    phoneNumbers = normalizePhoneNumbers(phoneNumbers),
                    aliases = normalizeCommaSeparated(aliases),
                    tags = normalizeCommaSeparated(tags),
                )
                if (existing == null) database.employeeDao().insert(employee) else database.employeeDao().update(employee)
                database.jobPositionDao().deleteLinks(employeeId)
                positionNames.split(",")
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinctBy(String::lowercase)
                    .forEach { name ->
                        val positionId = "position-" + name.lowercase()
                            .replace(Regex("[^a-ząćęłńóśźż0-9]+"), "-")
                            .trim('-')
                        database.jobPositionDao().upsert(listOf(JobPositionEntity(positionId, name)))
                        database.jobPositionDao().link(
                            listOf(EmployeeJobPositionEntity(employeeId, positionId)),
                        )
                    }
            }
        }
    }

    fun issueToPerson(employeeId: String, items: List<IssueRequest>, effectiveDate: String) {
        val validItems = items.filter { it.productId.isNotBlank() && it.quantity > 0L }
        if (validItems.isEmpty() || runCatching { LocalDate.parse(effectiveDate) }.isFailure) return
        viewModelScope.launch {
            database.withTransaction {
                val movementId = UUID.randomUUID().toString()
                database.movementDao().insertMovement(
                    StockMovementEntity(
                        id = movementId,
                        type = "ISSUE",
                        warehouseId = "warehouse-main",
                        employeeId = employeeId,
                        effectiveDate = effectiveDate,
                        createdAtEpochMillis = System.currentTimeMillis(),
                        note = "Wydanie bez zamówienia",
                    ),
                )
                validItems.forEach { item ->
                    val product = database.productDao().findById(item.productId) ?: return@forEach
                    val current = database.stockDao().find("warehouse-main", item.productId)?.quantity ?: 0.0
                    database.stockDao().upsert(
                        listOf(StockBalanceEntity("warehouse-main", item.productId, StockMath.afterIssue(current, item.quantity.toDouble()))),
                    )
                    database.movementDao().insertLine(
                        StockMovementLineEntity(
                            id = UUID.randomUUID().toString(),
                            movementId = movementId,
                            productId = item.productId,
                            quantityDelta = -item.quantity.toDouble(),
                            unit = product.unit,
                        ),
                    )
                    if (product.isReturnable) {
                        database.movementDao().insertCustody(
                            CustodyEntity(
                                id = UUID.randomUUID().toString(),
                                employeeId = employeeId,
                                productId = item.productId,
                                quantity = item.quantity.toDouble(),
                                issuedMovementId = movementId,
                                issuedDate = effectiveDate,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun correctIssue(employeeId: String, current: EmployeeIssue, productId: String, quantity: Long, effectiveDate: String, delete: Boolean) {
        if ((!delete && (productId.isBlank() || quantity <= 0L)) || runCatching { LocalDate.parse(effectiveDate) }.isFailure) return
        if (!delete && quantity.toDouble() < current.returnedQuantity) return
        if (!delete && current.returnedQuantity > 0 && productId != current.productId) return
        viewModelScope.launch {
            database.withTransaction {
                val replacementProductId = if (delete) current.productId else productId
                val replacementQuantity = if (delete) 0.0 else quantity.toDouble()
                val oldProduct = database.productDao().findById(current.productId) ?: return@withTransaction
                val newProduct = database.productDao().findById(replacementProductId) ?: return@withTransaction
                val now = System.currentTimeMillis()

                if (current.movementType == "ISSUE") {
                    val correctionMovementId = UUID.randomUUID().toString()
                    database.movementDao().insertMovement(
                        StockMovementEntity(
                            id = correctionMovementId,
                            type = "ISSUE_CORRECTION",
                            warehouseId = "warehouse-main",
                            employeeId = employeeId,
                            effectiveDate = effectiveDate,
                            createdAtEpochMillis = now,
                            note = if (delete) "Usunięcie błędnego wydania" else "Korekta wcześniejszego wydania",
                        ),
                    )
                    val oldStock = database.stockDao().find("warehouse-main", current.productId)?.quantity ?: 0.0
                    val quantityToRestore = if (delete) current.quantity - current.returnedQuantity else current.quantity
                    database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", current.productId, oldStock + quantityToRestore)))
                    database.movementDao().insertLine(StockMovementLineEntity(UUID.randomUUID().toString(), correctionMovementId, current.productId, quantityToRestore, oldProduct.unit))
                    if (!delete) {
                        val newStock = database.stockDao().find("warehouse-main", replacementProductId)?.quantity ?: 0.0
                        database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", replacementProductId, newStock - replacementQuantity)))
                        database.movementDao().insertLine(StockMovementLineEntity(UUID.randomUUID().toString(), correctionMovementId, replacementProductId, -replacementQuantity, newProduct.unit))
                    }
                }

                val custody = database.movementDao().findActiveCustody(current.movementId, current.productId)
                when {
                    custody != null && (delete || !newProduct.isReturnable) -> database.movementDao().deleteCustody(custody.id)
                    custody != null -> database.movementDao().updateCustody(custody.copy(productId = replacementProductId, quantity = replacementQuantity - current.returnedQuantity, issuedDate = effectiveDate))
                    !delete && newProduct.isReturnable -> database.movementDao().insertCustody(
                        CustodyEntity(UUID.randomUUID().toString(), employeeId, replacementProductId, replacementQuantity, current.movementId, effectiveDate),
                    )
                }
                database.movementDao().insertAmendment(
                    IssueAmendmentEntity(
                        id = UUID.randomUUID().toString(),
                        originalLineId = current.lineId,
                        replacementProductId = replacementProductId,
                        replacementQuantity = replacementQuantity,
                        replacementDate = effectiveDate,
                        isDeleted = delete,
                        createdAtEpochMillis = now,
                    ),
                )
            }
        }
    }

    fun returnIssue(employeeId: String, current: EmployeeIssue, quantity: Long, effectiveDate: String) {
        val remainingQuantity = (current.quantity - current.returnedQuantity).toLong()
        if (quantity <= 0L || quantity > remainingQuantity || runCatching { LocalDate.parse(effectiveDate) }.isFailure) return
        viewModelScope.launch {
            database.withTransaction {
                val product = database.productDao().findById(current.productId) ?: return@withTransaction
                val movementId = UUID.randomUUID().toString()
                database.movementDao().insertMovement(
                    StockMovementEntity(movementId, "RETURN", "warehouse-main", employeeId, effectiveDate = effectiveDate, createdAtEpochMillis = System.currentTimeMillis(), note = "Zwrot z historii wydań"),
                )
                val currentStock = database.stockDao().find("warehouse-main", current.productId)?.quantity ?: 0.0
                database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", current.productId, currentStock + quantity)))
                database.movementDao().insertLine(StockMovementLineEntity(UUID.randomUUID().toString(), movementId, current.productId, quantity.toDouble(), product.unit))
                database.movementDao().insertIssueReturn(
                    IssueReturnEntity(UUID.randomUUID().toString(), current.lineId, quantity.toDouble(), effectiveDate, System.currentTimeMillis()),
                )
                var remaining = quantity.toDouble()
                database.movementDao().findActiveCustodiesForEmployee(employeeId, current.productId).forEach custodyLoop@{ custody ->
                    if (remaining <= 0) return@custodyLoop
                    if (custody.quantity <= remaining) {
                        database.movementDao().updateCustody(custody.copy(returnedDate = effectiveDate))
                        remaining -= custody.quantity
                    } else {
                        database.movementDao().updateCustody(custody.copy(quantity = custody.quantity - remaining))
                        remaining = 0.0
                    }
                }
            }
        }
    }
}
