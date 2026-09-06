package pl.magazyn.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate

@Composable
fun BackupScreen(contentPadding: PaddingValues, viewModel: BackupViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var password by rememberSaveable { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { viewModel.create(it, password) }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pełna kopia danych", style = MaterialTheme.typography.titleLarge)
        Text("Jeden zaszyfrowany plik zawiera całą bazę aplikacji. Zachowaj hasło — bez niego nie da się odtworzyć kopii.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            password,
            { password = it },
            Modifier.fillMaxWidth(),
            label = { Text("Hasło kopii — minimum 6 znaków") },
            leadingIcon = { Icon(Icons.Default.Lock, null) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )
        Button(
            onClick = { createFile.launch("Magazyn-Mobile-${LocalDate.now()}.magazynbackup") },
            enabled = password.length >= 6 && !state.working,
            modifier = Modifier.fillMaxWidth(),
        ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(7.dp)); Text("Utwórz kopię bezpieczeństwa") }
        HorizontalDivider()
        Text("Przywracanie zastępuje wszystkie obecne dane zawartością kopii. Aplikacja uruchomi się ponownie po poprawnym sprawdzeniu pliku.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { openFile.launch(arrayOf("application/octet-stream", "*/*")) },
            enabled = password.length >= 6 && !state.working,
            modifier = Modifier.fillMaxWidth(),
        ) { Icon(Icons.Default.Restore, null); Spacer(Modifier.width(7.dp)); Text("Wybierz kopię do przywrócenia") }
        if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { message ->
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Text(message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        state.error?.let { error ->
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                    Column {
                        Text("Nie utworzono kopii", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        Text("Klucz Gemini nie jest częścią kopii i na nowym telefonie trzeba wpisać go ponownie.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Zastąpić wszystkie dane?") },
            text = { Text("Najpierw zostanie sprawdzone hasło, format i spójność kopii. Po powodzeniu obecna baza zostanie zastąpiona, a aplikacja uruchomi się ponownie.") },
            confirmButton = { Button(onClick = { restoreUri = null; viewModel.restore(uri, password) }) { Text("Przywróć kopię") } },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Anuluj") } },
        )
    }
}
