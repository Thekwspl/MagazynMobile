package pl.magazyn.mobile.ui

import android.app.Application
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.data.StockExportFormat
import pl.magazyn.mobile.data.StockExporter

data class ExportReady(val uri: android.net.Uri, val mimeType: String, val fileName: String)
data class ExportUiState(val working: Boolean = false, val ready: ExportReady? = null, val error: String? = null)

class ExportViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    val warehouses = database.warehouseDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _state = MutableStateFlow(ExportUiState())
    val state = _state.asStateFlow()

    fun products(warehouseId: String) = database.productDao().observeWithStock(warehouseId)

    fun export(warehouseName: String, items: List<ProductWithStock>, format: StockExportFormat) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _state.value = ExportUiState(working = true)
            runCatching {
                withContext(Dispatchers.IO) {
                    val safeWarehouse = warehouseName.lowercase().replace(Regex("[^a-ząćęłńóśźż0-9]+"), "-").trim('-')
                    val fileName = "stan-$safeWarehouse-${LocalDate.now()}.${format.extension}"
                    val file = File(File(context.cacheDir, "exports"), fileName)
                    StockExporter.write(file, warehouseName, items, format)
                    ExportReady(FileProvider.getUriForFile(context, "${context.packageName}.files", file), format.mimeType, fileName)
                }
            }.onSuccess { _state.value = ExportUiState(ready = it) }
                .onFailure { _state.value = ExportUiState(error = it.message ?: "Nie udało się przygotować eksportu.") }
        }
    }

    fun consumeReady() { _state.value = ExportUiState() }
}
