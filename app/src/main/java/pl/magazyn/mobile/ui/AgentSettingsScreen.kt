package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.magazyn.mobile.BuildConfig
import pl.magazyn.mobile.agent.AgentConnectionStore

@Composable
fun AgentSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { AgentConnectionStore(context, BuildConfig.DEBUG) }
    var saved by remember(store) { mutableStateOf(store.status()) }
    var endpoint by remember(store) { mutableStateOf(saved.endpoint) }
    var tokenInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackScreenHeader("Połączenie Codex", "Bezpieczny dostęp do agent-service", onBack)
        Text("Podaj adres HTTPS bez dodatkowej ścieżki. Login ChatGPT pozostaje na serwerze.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(endpoint, { endpoint = it; message = null }, Modifier.fillMaxWidth(),
            label = { Text("Adres agent-service (HTTPS)") }, singleLine = true)
        Text(if (saved.tokenSaved) "Token zapisany na tym telefonie" else "Brak zapisanego tokenu",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(tokenInput, { tokenInput = it; message = null }, Modifier.fillMaxWidth(),
            label = { Text(if (saved.tokenSaved) "Nowy token (opcjonalnie przy zmianie adresu)" else "Token klienta") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Button(onClick = {
            runCatching { store.save(endpoint, tokenInput) }.onSuccess {
                saved = store.status()
                endpoint = saved.endpoint
                tokenInput = ""
                message = "Konfiguracja zapisana."
            }.onFailure { message = it.message ?: "Nie udało się zapisać konfiguracji." }
        }, enabled = endpoint.isNotBlank() && !testing, modifier = Modifier.fillMaxWidth()) {
            Text("Zapisz konfigurację")
        }
        OutlinedButton(onClick = {
            scope.launch {
                testing = true
                message = runCatching {
                    // Test uses the saved settings; no catalog sync or order is started.
                    store.testConnection()
                }.getOrElse { it.message ?: "Test połączenia nie powiódł się." }
                testing = false
            }
        }, enabled = !testing, modifier = Modifier.fillMaxWidth()) {
            if (testing) CircularProgressIndicator() else Text("Test zapisanej konfiguracji")
        }
        OutlinedButton(onClick = {
            runCatching { store.clear() }.onSuccess {
                saved = store.status()
                endpoint = saved.endpoint
                tokenInput = ""
                message = "Konfiguracja usunięta."
            }.onFailure { message = it.message ?: "Nie udało się usunąć konfiguracji." }
        }, enabled = saved.tokenSaved || (saved.endpoint.isNotBlank() && saved.endpoint != pl.magazyn.mobile.agent.AgentEndpoint.DEBUG_LOOPBACK),
            modifier = Modifier.fillMaxWidth()) { Text("Usuń konfigurację") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (BuildConfig.DEBUG) Text("Test lokalny: 127.0.0.1:8787 wymaga adb reverse tcp:8787 tcp:8787 i tokenu klienta.",
            style = MaterialTheme.typography.bodySmall)
    }
}
