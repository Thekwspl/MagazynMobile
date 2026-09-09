package pl.magazyn.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import pl.magazyn.mobile.StartupDiagnostics
import pl.magazyn.mobile.data.ProductVisibilityStore

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onAiSettings: () -> Unit,
    onUpdates: () -> Unit,
    onLearningRules: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    val visibilityStore = remember(context) {
        ProductVisibilityStore(context)
    }
    val diagnostics = remember(context) { StartupDiagnostics.from(context) }
    var lastStartupProblem by remember { mutableStateOf(diagnostics.lastProblem()) }
    val showHidden by visibilityStore.showHidden.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        BackScreenHeader("Ustawienia", "Aplikacja, AI i reguły lokalne", onBack)
        SettingsItem(Icons.Default.AutoAwesome, "Ustawienia AI", "Klucz Gemini i prywatność notatek", onAiSettings)
        SettingsItem(Icons.Default.SystemUpdate, "Aktualizacje", "Sprawdzanie i instalowanie nowej wersji", onUpdates)
        SettingsItem(Icons.Default.Psychology, "Uczenie offline", "Reguły używane bez internetu", onLearningRules)
        SettingsItem(Icons.Default.UploadFile, "Import danych", "Wczytaj dane z pliku", onImport)
        SettingsItem(Icons.Default.FileDownload, "Eksport danych", "Zapisz i udostępnij dane", onExport)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wyświetl ukryte przedmioty", fontWeight = FontWeight.SemiBold)
                Text("Tylko widoczność na zwykłych listach", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(showHidden, visibilityStore::setShowHidden)
        }
        lastStartupProblem?.let { problem ->
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp)) {
                Text("Ostatni problem uruchomienia", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text("Aplikacja zapisała krótki opis ostatniego nieoczekiwanego zamknięcia.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(problem, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                TextButton(onClick = { diagnostics.clearLastProblem(); lastStartupProblem = null }) { Text("Ukryj komunikat") }
            }
        }
    }
}

@Composable
private fun SettingsItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
        HorizontalDivider(Modifier.padding(top = 15.dp))
    }
}
