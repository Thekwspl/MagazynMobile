package pl.magazyn.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.HrImportPlan
import pl.magazyn.mobile.data.HrImportReport
import pl.magazyn.mobile.data.HrManualAssignment
import pl.magazyn.mobile.data.HrSynchroImporter
import pl.magazyn.mobile.domain.HrSynchroContractParser
import pl.magazyn.mobile.domain.HrSynchroExport

data class HrSynchroImportUiState(
    val loading: Boolean = false,
    val plan: HrImportPlan? = null,
    val assignments: Map<Long, HrManualAssignment> = emptyMap(),
    val report: HrImportReport? = null,
    val error: String? = null,
)

class HrSynchroImportViewModel(application: Application) : AndroidViewModel(application) {
    private val importer = HrSynchroImporter((application as MagazynApplication).database)
    private var loadedExport: HrSynchroExport? = null
    private val _state = MutableStateFlow(HrSynchroImportUiState())
    val state: StateFlow<HrSynchroImportUiState> = _state.asStateFlow()

    fun load(uri: Uri) {
        viewModelScope.launch {
            _state.value = HrSynchroImportUiState(loading = true)
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("Etap READING: nie udało się otworzyć wybranego pliku")
                    val parsed = HrSynchroContractParser.parse(text)
                    parsed to importer.prepare(parsed)
                }
            }.onSuccess { (export, plan) ->
                loadedExport = export
                _state.value = HrSynchroImportUiState(plan = plan)
            }.onFailure { error ->
                loadedExport = null
                _state.value = HrSynchroImportUiState(error = error.message ?: "Etap READING: ${error.javaClass.simpleName}")
            }
        }
    }

    fun assignToEmployee(hrappkaId: Long, employeeId: String) = updateAssignment(
        hrappkaId,
        HrManualAssignment(employeeId = employeeId),
    )

    fun createAsNew(hrappkaId: Long) = updateAssignment(hrappkaId, HrManualAssignment(createNew = true))

    fun skipForThisImport(hrappkaId: Long) = updateAssignment(hrappkaId, HrManualAssignment(skip = true))

    private fun updateAssignment(hrappkaId: Long, assignment: HrManualAssignment) {
        val export = loadedExport ?: return
        val assignments = _state.value.assignments + (hrappkaId to assignment)
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { withContext(Dispatchers.IO) { importer.prepare(export, assignments) } }
                .onSuccess { plan -> _state.value = _state.value.copy(loading = false, plan = plan, assignments = assignments) }
                .onFailure { error -> _state.value = _state.value.copy(loading = false, error = error.message ?: "Etap PLAN: ${error.javaClass.simpleName}") }
        }
    }

    fun confirmImport() {
        val export = loadedExport ?: return
        val current = _state.value
        if (current.plan?.needsAssignmentCount != 0) return
        viewModelScope.launch {
            _state.value = current.copy(loading = true, error = null)
            runCatching { withContext(Dispatchers.IO) { importer.import(export, current.assignments) } }
                .onSuccess { report ->
                    loadedExport = null
                    _state.value = HrSynchroImportUiState(report = report)
                }
                .onFailure { error ->
                    _state.value = current.copy(loading = false, error = "Etap DATABASE: ${error.message ?: error.javaClass.simpleName}")
                }
        }
    }

    fun reset() {
        loadedExport = null
        _state.value = HrSynchroImportUiState()
    }
}
