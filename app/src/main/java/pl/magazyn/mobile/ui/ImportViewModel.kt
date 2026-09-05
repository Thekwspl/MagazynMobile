package pl.magazyn.mobile.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.EmployeeEntity
import pl.magazyn.mobile.data.ImportBatchEntity
import pl.magazyn.mobile.data.ImportPendingRowEntity
import pl.magazyn.mobile.data.ImportSourceRowEntity
import pl.magazyn.mobile.data.ProductEntity
import pl.magazyn.mobile.data.StockBalanceEntity
import pl.magazyn.mobile.data.StockMovementEntity
import pl.magazyn.mobile.data.StockMovementLineEntity
import pl.magazyn.mobile.data.ShipyardEntity
import pl.magazyn.mobile.data.ShipyardStockBalanceEntity
import pl.magazyn.mobile.domain.ImportKind
import pl.magazyn.mobile.domain.ImportParser
import pl.magazyn.mobile.domain.ImportPreview
import pl.magazyn.mobile.domain.PersonIssueImportRow
import pl.magazyn.mobile.domain.ShipyardIssueImportRow
import pl.magazyn.mobile.domain.StockImportRow
import pl.magazyn.mobile.domain.normalizeDisplayName

data class ImportUiState(
    val loading: Boolean = false,
    val preview: ImportPreview? = null,
    val error: String? = null,
    val resultMessage: String? = null,
)

class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun load(uri: Uri) {
        viewModelScope.launch {
            _state.value = ImportUiState(loading = true)
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "import.xlsx"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Nie udało się odczytać pliku")
                    val hash = ImportParser.sha256(bytes)
                    val imported = database.importDao().wasFileImported(hash)
                    val products = database.productDao().getAllNow()
                    ImportParser.parse(fileName, bytes, products, imported)
                }
            }.onSuccess { preview ->
                _state.value = ImportUiState(preview = preview)
            }.onFailure { error ->
                _state.value = ImportUiState(error = error.message ?: "Nie udało się odczytać pliku")
            }
        }
    }

    fun clearPreview() {
        _state.value = ImportUiState()
    }

    fun confirmImport() {
        val preview = _state.value.preview ?: return
        if (preview.alreadyImported && preview.kind != ImportKind.SHIPYARDS) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, resultMessage = null) }
            runCatching { withContext(Dispatchers.IO) { applyImport(preview) } }
                .onSuccess { message -> _state.value = ImportUiState(resultMessage = message) }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.message ?: "Import nie powiódł się") } }
        }
    }

    private suspend fun applyImport(preview: ImportPreview): String = database.withTransaction {
        val existingBatch = database.importDao().findBatchByHash(preview.fileHash)
        val repairMode = existingBatch != null
        if (repairMode && preview.kind != ImportKind.SHIPYARDS) error("Ten plik został już wcześniej zaimportowany")
        val batchId = existingBatch?.id ?: UUID.randomUUID().toString()
        var importedRows = 0
        var pendingRows = 0
        var skippedRows = 0
        val provisional = ImportBatchEntity(
            id = batchId,
            kind = preview.kind.name,
            sourceFileName = preview.fileName,
            fileHash = preview.fileHash,
            importedAtEpochMillis = System.currentTimeMillis(),
            totalRows = preview.totalRows,
            importedRows = 0,
            pendingRows = 0,
            skippedRows = preview.errors.size,
        )
        if (!repairMode) database.importDao().insertBatch(provisional)

        when (preview.kind) {
            ImportKind.STOCK -> {
                val products = database.productDao().getAllNow().associateByTo(mutableMapOf(), { ImportParser.key(it.name) }, { it })
                val movementId = UUID.randomUUID().toString()
                database.movementDao().insertMovement(
                    StockMovementEntity(
                        id = movementId,
                        type = "STOCK_IMPORT",
                        warehouseId = "warehouse-main",
                        employeeId = null,
                        effectiveDate = java.time.LocalDate.now().toString(),
                        createdAtEpochMillis = System.currentTimeMillis(),
                        note = "Import: ${preview.fileName}",
                    ),
                )
                preview.rows.filterIsInstance<StockImportRow>().forEach { row ->
                    val productKey = ImportParser.key(row.productName)
                    val existing = products[productKey]
                    val product = if (existing == null) {
                        ProductEntity(
                            id = "product-import-${ImportParser.sha256(productKey).take(20)}",
                            name = row.productName.trim(),
                            unit = row.unit,
                            category = inferCategory(row.productName),
                        ).also { database.productDao().insert(it); products[productKey] = it }
                    } else {
                        existing.copy(unit = row.unit).also { database.productDao().update(it); products[productKey] = it }
                    }
                    val oldQuantity = database.stockDao().find("warehouse-main", product.id)?.quantity ?: 0.0
                    database.stockDao().upsert(
                        listOf(StockBalanceEntity("warehouse-main", product.id, row.quantity.toDouble(), row.quantityKnown)),
                    )
                    if (row.quantityKnown) {
                        database.movementDao().insertLine(
                            StockMovementLineEntity(
                                id = UUID.randomUUID().toString(),
                                movementId = movementId,
                                productId = product.id,
                                quantityDelta = row.quantity.toDouble() - oldQuantity,
                                unit = row.unit,
                            ),
                        )
                    }
                    database.importDao().insertSourceRow(ImportSourceRowEntity(row.sourceKey, batchId, preview.kind.name, row.rowNumber))
                    importedRows++
                }
            }

            ImportKind.PEOPLE -> {
                val employees = database.employeeDao().getAllNow().associateByTo(mutableMapOf(), { ImportParser.key(it.firstName + " " + it.lastName) }, { it })
                val products = ImportParser.productLookup(database.productDao().getAllNow())
                preview.rows.filterIsInstance<PersonIssueImportRow>().forEach { row ->
                    val employeeKey = ImportParser.key(row.firstName + " " + row.lastName)
                    val employee = employees[employeeKey] ?: EmployeeEntity(
                        id = "employee-import-${ImportParser.sha256(employeeKey).take(20)}",
                        fullName = row.firstName + " " + row.lastName,
                        firstName = row.firstName,
                        lastName = row.lastName,
                    ).also { database.employeeDao().insert(it); employees[employeeKey] = it }
                    val inserted = database.importDao().insertSourceRow(ImportSourceRowEntity(row.sourceKey, batchId, preview.kind.name, row.rowNumber))
                    if (inserted == -1L) {
                        skippedRows++
                    } else {
                        val product = products[ImportParser.key(row.productName)]
                        if (product == null) {
                            database.importDao().upsertPendingRow(
                                ImportPendingRowEntity(
                                    sourceKey = row.sourceKey,
                                    batchId = batchId,
                                    kind = preview.kind.name,
                                    sourceRowNumber = row.rowNumber,
                                    recipientFirstName = row.firstName,
                                    recipientLastName = row.lastName,
                                    effectiveDate = row.effectiveDate,
                                    rawProductName = row.productName,
                                    quantity = 1,
                                ),
                            )
                            pendingRows++
                        } else {
                            insertHistoricalIssue(employee.id, "", row.effectiveDate, product, 1, preview.fileName)
                            importedRows++
                        }
                    }
                }
            }

            ImportKind.SHIPYARDS -> {
                val products = ImportParser.productLookup(database.productDao().getAllNow()).toMutableMap()
                val shipyards = database.shipyardDao().getAllNow()
                    .associateByTo(mutableMapOf(), { ImportParser.key(it.name) }, { it })
                preview.rows.filterIsInstance<ShipyardIssueImportRow>().forEach { row ->
                    val shipyardKey = ImportParser.key(row.shipyard)
                    val existingShipyard = shipyards[shipyardKey]
                    val resolvedShipyard = if (existingShipyard == null) {
                        ShipyardEntity(
                            id = "shipyard-import-${ImportParser.sha256(shipyardKey).take(20)}",
                            name = normalizeDisplayName(row.shipyard),
                        ).also { database.shipyardDao().insert(it); shipyards[shipyardKey] = it }
                    } else if (existingShipyard.isArchived) {
                        database.shipyardDao().restoreById(existingShipyard.id)
                        existingShipyard.copy(isArchived = false).also { shipyards[shipyardKey] = it }
                    } else existingShipyard

                    val wasPending = repairMode && database.importDao().isSourceRowPending(row.sourceKey)
                    val wasImported = repairMode && database.importDao().wasSourceRowImported(row.sourceKey)
                    val shouldImportStock = !repairMode || wasPending || !wasImported
                    if (!shouldImportStock) {
                        skippedRows++
                    } else {
                        if (!repairMode) {
                            database.importDao().insertSourceRow(ImportSourceRowEntity(row.sourceKey, batchId, preview.kind.name, row.rowNumber))
                        }

                        val productKey = ImportParser.key(row.productName)
                        val product = products[productKey] ?: ProductEntity(
                            id = "product-shipyard-import-${ImportParser.sha256(productKey).take(20)}",
                            name = normalizeDisplayName(row.productName),
                            unit = inferUnit(row.productName),
                            category = inferCategory(row.productName),
                        ).also { database.productDao().insert(it); products[productKey] = it }

                        insertHistoricalShipyardStock(resolvedShipyard, row.effectiveDate, product, row.quantity, preview.fileName)
                        if (wasPending) database.importDao().deletePendingRow(row.sourceKey)
                        importedRows++
                    }
                }
            }
        }

        val completed = provisional.copy(
            importedRows = importedRows,
            pendingRows = pendingRows,
            skippedRows = skippedRows + preview.errors.size,
        )
        if (!repairMode) database.importDao().updateBatch(completed)
        if (repairMode) {
            "Naprawiono import: dodano lub przywrócono stocznie, uzupełniono $importedRows brakujących pozycji. Bez zmian w magazynie głównym."
        } else {
            "Zaimportowano $importedRows, do mapowania $pendingRows, pominięto ${completed.skippedRows}."
        }
    }

    private suspend fun insertHistoricalShipyardStock(
        shipyard: ShipyardEntity,
        date: String,
        product: ProductEntity,
        quantity: Long,
        fileName: String,
    ) {
        val movementId = UUID.randomUUID().toString()
        database.movementDao().insertMovement(
            StockMovementEntity(
                id = movementId,
                type = "HISTORICAL_SHIPYARD_IMPORT",
                warehouseId = "warehouse-main",
                employeeId = null,
                recipientLabel = shipyard.name,
                effectiveDate = date,
                createdAtEpochMillis = System.currentTimeMillis(),
                note = "Stan stoczni z importu: $fileName (bez zmiany magazynu głównego)",
            ),
        )
        database.movementDao().insertLine(
            StockMovementLineEntity(UUID.randomUUID().toString(), movementId, product.id, -quantity.toDouble(), product.unit),
        )
        val current = database.shipyardDao().findStock(shipyard.id, product.id)?.quantity ?: 0.0
        database.shipyardDao().upsertStock(ShipyardStockBalanceEntity(shipyard.id, product.id, current + quantity.toDouble()))
    }

    private suspend fun insertHistoricalIssue(
        employeeId: String?,
        recipientLabel: String,
        date: String,
        product: ProductEntity,
        quantity: Long,
        fileName: String,
    ) {
        val movementId = UUID.randomUUID().toString()
        database.movementDao().insertMovement(
            StockMovementEntity(
                id = movementId,
                type = "HISTORICAL_ISSUE_IMPORT",
                warehouseId = "warehouse-main",
                employeeId = employeeId,
                recipientLabel = recipientLabel,
                effectiveDate = date,
                createdAtEpochMillis = System.currentTimeMillis(),
                note = "Historia z importu: $fileName",
            ),
        )
        database.movementDao().insertLine(
            StockMovementLineEntity(
                id = UUID.randomUUID().toString(),
                movementId = movementId,
                productId = product.id,
                quantityDelta = -quantity.toDouble(),
                unit = product.unit,
            ),
        )
    }

    private fun inferCategory(name: String): String {
        val value = ImportParser.key(name)
        return when {
            listOf("buty", "gumiaki", "kalosze").any(value::contains) -> "Obuwie"
            listOf("ciuchy", "bluza", "spodnie", "kurtka", "koszulka", "deszczowka", "skora").any(value::contains) -> "Odzież"
            listOf("rekawice", "kask", "okulary", "maska", "filtr", "przylbica", "nauszniki").any(value::contains) -> "BHP"
            else -> "Pozostałe"
        }
    }

    private fun inferUnit(name: String): String {
        val value = ImportParser.key(name)
        return when {
            listOf("buty", "gumiaki", "kalosze").any(value::contains) -> "para"
            listOf("rekawice").any(value::contains) -> "opak."
            else -> "szt."
        }
    }
}
