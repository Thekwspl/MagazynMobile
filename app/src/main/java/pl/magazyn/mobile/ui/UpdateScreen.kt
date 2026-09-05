package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.magazyn.mobile.BuildConfig

@Composable
fun UpdateScreen(contentPadding: PaddingValues, viewModel: UpdateViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var permissionHint by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Aktualizacje", style = MaterialTheme.typography.headlineSmall)
        Text("Zainstalowana wersja: ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            state.repository, viewModel::setRepository, Modifier.fillMaxWidth(),
            label = { Text("Repozytorium GitHub") }, placeholder = { Text("użytkownik/MagazynMobile") }, singleLine = true,
            supportingText = { Text("Darmowe pobieranie wymaga publicznego repozytorium i wydania GitHub z plikiem APK.") },
        )
        Button(onClick = viewModel::saveAndCheck, enabled = !state.checking && !state.downloading, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(7.dp)); Text(if (state.checking) "Sprawdzanie…" else "Sprawdź aktualizacje")
        }
        state.release?.let { release ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(release.title, style = MaterialTheme.typography.titleMedium)
                    Text("Wersja ${release.version}")
                    if (release.notes.isNotBlank()) Text(release.notes.take(1200), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.downloading) {
            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text("Pobieranie: ${state.progress}%")
        }
        if (state.updateAvailable && state.downloadedFile == null) Button(onClick = viewModel::download, enabled = !state.downloading, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Download, null); Spacer(Modifier.width(7.dp)); Text("Pobierz aktualizację")
        }
        if (state.downloadedFile != null) Button(onClick = { permissionHint = !viewModel.install() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.InstallMobile, null); Spacer(Modifier.width(7.dp)); Text("Zainstaluj")
        }
        if (permissionHint) Text("Android otworzył zgodę „Instaluj nieznane aplikacje”. Włącz ją dla Magazyn Mobile, wróć tutaj i ponownie wybierz „Zainstaluj”.", color = MaterialTheme.colorScheme.primary)
        state.message?.let { Text(it, color = if (state.updateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        HorizontalDivider()
        Text("Ważne", style = MaterialTheme.typography.titleMedium)
        Text("Aktualizacja zachowa dane tylko wtedy, gdy APK ma ten sam identyfikator aplikacji i jest podpisane tym samym kluczem co obecna instalacja. Klucza podpisującego nie wolno zgubić ani publikować.")
    }
}
