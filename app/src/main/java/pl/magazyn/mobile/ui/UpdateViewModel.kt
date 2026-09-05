package pl.magazyn.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.magazyn.mobile.BuildConfig
import pl.magazyn.mobile.data.AppUpdater
import pl.magazyn.mobile.data.GitHubRelease
import pl.magazyn.mobile.data.isNewerVersion

data class UpdateUiState(
    val repository: String = "",
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val release: GitHubRelease? = null,
    val updateAvailable: Boolean = false,
    val downloadedFile: File? = null,
    val message: String? = null,
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val updater = AppUpdater(application)
    private val preferences = application.getSharedPreferences("updates", 0)
    private val _state = MutableStateFlow(UpdateUiState(repository = preferences.getString("github_repository", "").orEmpty()))
    val state = _state.asStateFlow()

    fun setRepository(value: String) { _state.value = _state.value.copy(repository = value, message = null) }
    fun saveAndCheck() {
        val repository = _state.value.repository.trim().removePrefix("https://github.com/").trim('/')
        preferences.edit().putString("github_repository", repository).apply()
        _state.value = _state.value.copy(repository = repository)
        check()
    }
    fun check() {
        if (_state.value.repository.isBlank()) { _state.value = _state.value.copy(message = "Najpierw wpisz użytkownik/repozytorium."); return }
        viewModelScope.launch {
            _state.value = _state.value.copy(checking = true, message = null, downloadedFile = null)
            runCatching { updater.latestRelease(_state.value.repository) }
                .onSuccess { release -> _state.value = _state.value.copy(checking = false, release = release, updateAvailable = isNewerVersion(release.version, BuildConfig.VERSION_NAME), message = if (isNewerVersion(release.version, BuildConfig.VERSION_NAME)) "Dostępna jest nowa wersja." else "Masz najnowszą wersję.") }
                .onFailure { _state.value = _state.value.copy(checking = false, message = it.message ?: "Nie udało się sprawdzić aktualizacji.") }
        }
    }
    fun download() {
        val release = _state.value.release ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(downloading = true, progress = 0, message = null)
            runCatching { updater.download(release) { progress -> _state.value = _state.value.copy(progress = progress) } }
                .onSuccess { _state.value = _state.value.copy(downloading = false, progress = 100, downloadedFile = it, message = "APK pobrane. Możesz rozpocząć instalację.") }
                .onFailure { _state.value = _state.value.copy(downloading = false, message = it.message ?: "Nie udało się pobrać aktualizacji.") }
        }
    }
    fun install(): Boolean = _state.value.downloadedFile?.let(updater::install) ?: false
}
