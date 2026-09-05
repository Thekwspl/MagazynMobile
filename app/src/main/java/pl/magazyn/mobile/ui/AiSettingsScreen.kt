package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.magazyn.mobile.data.AiKeyStore
import pl.magazyn.mobile.domain.GeminiNoteAnalyzer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AiKeyStore(context) }
    val analyzer = remember { GeminiNoteAnalyzer() }
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(store.hasApiKey()) }
    var redactPhones by remember { mutableStateOf(store.redactPhoneNumbers) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var lastConnection by remember { mutableStateOf(store.lastConnection()) }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Wróć") }
            Column {
                Text("Ustawienia AI", style = MaterialTheme.typography.headlineSmall)
                Text("Gemini 3.6 Flash · analiza notatek po ręcznym uruchomieniu", style = MaterialTheme.typography.labelMedium)
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (configured) Icons.Default.CheckCircle else Icons.Default.Key, null)
                    Text(if (configured) "Klucz jest zapisany na tym telefonie" else "Brak zapisanego klucza", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it; status = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (configured) "Nowy klucz API (tylko jeśli chcesz zmienić)" else "Klucz API Gemini") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        runCatching { store.saveApiKey(keyInput) }
                            .onSuccess {
                                keyInput = ""
                                configured = true
                                status = "Klucz zapisany bezpiecznie."
                            }
                            .onFailure { status = "Nie udało się zapisać klucza: ${it.message.orEmpty()}" }
                    },
                    enabled = keyInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Zapisz klucz") }
                OutlinedButton(
                    onClick = {
                        val key = store.readApiKey() ?: return@OutlinedButton
                        scope.launch {
                            testing = true
                            status = runCatching {
                                analyzer.testConnection(key)
                                store.recordConnection(true, analyzer.lastModel, "Test połączenia zakończony powodzeniem")
                                "Połączenie działa. Model: ${analyzer.lastModel}."
                            }.getOrElse {
                                store.recordConnection(false, analyzer.lastModel, it.message ?: "Test nieudany")
                                it.message ?: "Test połączenia nie powiódł się."
                            }
                            lastConnection = store.lastConnection()
                            testing = false
                        }
                    },
                    enabled = configured && !testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Sprawdź połączenie")
                }
                TextButton(
                    onClick = { store.clearApiKey(); configured = false; keyInput = ""; status = "Klucz usunięty z telefonu." },
                    enabled = configured,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Icon(Icons.Default.DeleteOutline, null)
                    Text("Usuń klucz")
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                lastConnection?.let { last ->
                    val whenText = java.time.Instant.ofEpochMilli(last.atEpochMillis).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", java.util.Locale("pl", "PL")))
                    HorizontalDivider()
                    Text("Ostatnia próba: $whenText", style = MaterialTheme.typography.labelMedium)
                    Text("${if (last.success) "Sukces" else "Błąd"} · ${last.model}\n${last.message}", style = MaterialTheme.typography.bodySmall, color = if (last.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ukrywaj numery telefonów", style = MaterialTheme.typography.titleSmall)
                Text("Przed wysłaniem notatki numery są zastępowane znacznikiem.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = redactPhones,
                onCheckedChange = { redactPhones = it; store.redactPhoneNumbers = it },
            )
        }
        Text(
            "Klucz jest szyfrowany przez Android Keystore, nie trafia do kodu aplikacji ani kopii zapasowej. Po ponownej instalacji aplikacji trzeba wpisać go ponownie. Tekst analizowany przez AI jest wysyłany bezpośrednio z telefonu do Gemini. Wynik zawsze wymaga ręcznego zatwierdzenia.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
