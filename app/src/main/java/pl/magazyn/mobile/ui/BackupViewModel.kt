package pl.magazyn.mobile.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.BackupManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BackupUiState(val working: Boolean = false, val message: String? = null, val error: String? = null)

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = BackupManager(application as MagazynApplication)
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun create(uri: Uri, password: String) {
        viewModelScope.launch {
            _state.value = BackupUiState(working = true)
            runCatching { withContext(Dispatchers.IO) { manager.createEncryptedBackup(uri, password) } }
                .onSuccess {
                    val createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale("pl", "PL")))
                    _state.value = BackupUiState(message = "Kopia utworzona poprawnie\n$createdAt")
                }
                .onFailure { _state.value = BackupUiState(error = it.message ?: "Nie udało się utworzyć kopii") }
        }
    }

    fun restore(uri: Uri, password: String) {
        viewModelScope.launch {
            _state.value = BackupUiState(working = true)
            runCatching { withContext(Dispatchers.IO) { manager.restoreEncryptedBackup(uri, password) } }
                .onSuccess {
                    Toast.makeText(getApplication(), "Kopia przywrócona. Uruchamiam aplikację ponownie…", Toast.LENGTH_LONG).show()
                    manager.restartApplication()
                }
                .onFailure { _state.value = BackupUiState(error = it.message ?: "Nie udało się przywrócić kopii") }
        }
    }
}
