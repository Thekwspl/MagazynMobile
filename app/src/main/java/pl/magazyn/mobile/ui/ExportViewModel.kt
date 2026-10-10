package pl.magazyn.mobile.ui

import android.app.Application
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.*

data class ExportReady(val uri: android.net.Uri, val mimeType: String, val fileName: String, val subject: String)
data class ExportUiState(val working: Boolean = false, val ready: ExportReady? = null, val error: String? = null)
data class ExportPreview(
    val selection: ExportSelection,
    val loading: Boolean = true,
    val title: String = "",
    val products: List<ProductWithStock> = emptyList(),
    val deliveries: List<DeliveryReportLine> = emptyList(),
    val error: String? = null,
) {
    val rowCount get() = if (selection.report == ExportReportType.STOCK) products.size else deliveries.size
    val deliveryCount get() = deliveries.map { it.movementId }.distinct().size
    val canExport get() = !loading && error == null && rowCount > 0 &&
        (selection.report != ExportReportType.DELIVERIES || selection.dateError() == null)
    fun forSelection(current: ExportSelection) = if (selection == current) this else ExportPreview(current)
}

class ExportViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    val shipyards = database.shipyardDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _state = MutableStateFlow(ExportUiState())
    val state = _state.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun preview(selection: ExportSelection): Flow<ExportPreview> {
        val data = if (selection.report == ExportReportType.DELIVERIES) {
            val error = selection.dateError()
            if (error != null) flowOf(ExportPreview(selection, loading = false, error = error))
            else database.movementDao().observeDeliveryReport(selection.dateFrom, selection.dateTo).map {
                ExportPreview(selection, loading = false, title = "Dostawy · ${selection.dateFrom} – ${selection.dateTo}", deliveries = it)
            }
        } else {
            combine(database.warehouseDao().observeAll(), database.shipyardDao().observeAll()) { warehouses, yards ->
                resolveExportStockTarget(selection, warehouses, yards)
            }.flatMapLatest { target ->
                val items = if (target.source == StockReportSource.MAIN) database.productDao().observeWithStock(target.id)
                    else database.shipyardDao().observeStock(target.id).map { rows -> rows.map { it.toExportProduct() } }
                items.map { ExportPreview(selection, loading = false, title = target.name, products = it) }
                    .onStart { emit(ExportPreview(selection)) }
            }
        }
        return data.onStart { emit(ExportPreview(selection)) }.catch {
            emit(ExportPreview(selection, loading = false, error = exportError("Odczyt raportu", it)))
        }
    }

    fun export(selection: ExportSelection, format: StockExportFormat) {
        if (_state.value.working) return
        _state.value = ExportUiState(working = true)
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                val ready = withContext(Dispatchers.IO) {
                    // Ponowny odczyt wybranego źródła: nigdy nie eksportujemy starego podglądu innej stoczni.
                    val snapshot = preview(selection).first { !it.loading }
                    require(snapshot.canExport) { snapshot.error ?: "Brak danych dla wybranego raportu." }
                    val now = LocalDateTime.now()
                    val table = if (selection.report == ExportReportType.STOCK) stockExportTable(snapshot.title, snapshot.products, now)
                        else deliveryExportTable(selection, snapshot.deliveries, now)
                    val fileName = exportFileName(selection, snapshot.title, format, now)
                    val file = File(File(context.cacheDir, "exports"), fileName)
                    try { StockExporter.write(file, table, format) }
                    catch (error: Exception) { file.delete(); throw error }
                    ExportReady(FileProvider.getUriForFile(context, "${context.packageName}.files", file), format.mimeType, fileName,
                        "${table.title} · ${table.subtitle}")
                }
                _state.value = ExportUiState(ready = ready)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = ExportUiState(error = exportError("Przygotowanie ${format.name}", error))
            }
        }
    }

    fun consumeReady() { _state.value = ExportUiState() }
    fun shareFailed(error: Exception) { _state.value = ExportUiState(error = exportError("Udostępnianie pliku", error)) }
}

private fun exportError(stage: String, error: Throwable) = "$stage: ${error.javaClass.simpleName}: ${error.message ?: "brak szczegółów"}"
