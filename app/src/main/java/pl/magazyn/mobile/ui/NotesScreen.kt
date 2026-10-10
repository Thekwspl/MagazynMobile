package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.magazyn.mobile.data.OrderNotebookEntity

@Composable
fun NotesScreen(
    contentPadding: PaddingValues,
    onNewNote: () -> Unit,
    onOpenNote: (String) -> Unit,
    viewModel: NotesViewModel = viewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        ScreenHeader("Notatki", "Nowa notatka", onNewNote)
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Brak zapisanych notatek", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(notes, key = { it.id }) { note ->
                    OutlinedCard(onClick = { onOpenNote(note.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(noteExcerpt(note.rawText), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Text(formatNoteTimestamp(note.createdAtEpochMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (note.status == "CONVERTED") {
                                    Text("Utworzono zamówienie", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NoteEditorScreen(
    contentPadding: PaddingValues,
    noteId: String? = null,
    onBack: () -> Unit,
    onSaved: (String) -> Unit = { onBack() },
    viewModel: NotesViewModel = viewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val existing = noteId?.let { id -> notes.firstOrNull { it.id == id } }
    var text by rememberSaveable(noteId) { mutableStateOf("") }
    var initialized by rememberSaveable(noteId) { mutableStateOf(noteId == null) }
    LaunchedEffect(existing?.id) {
        if (!initialized && existing != null) {
            text = existing.rawText
            initialized = true
        }
    }
    Column(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        NotesTopBar(if (noteId == null) "Nowa notatka" else "Edytuj notatkę", onBack)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 10.dp),
            label = { Text("Treść notatki") },
            minLines = 8,
        )
        Button(
            onClick = { viewModel.save(noteId, text, onSaved) },
            enabled = canSaveNote(text),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) { Text("Zapisz notatkę") }
    }
}

@Composable
fun NoteDetailsScreen(
    contentPadding: PaddingValues,
    noteId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onConvert: (OrderNotebookEntity) -> Unit,
    viewModel: NotesViewModel = viewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val note = notes.firstOrNull { it.id == noteId }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmRepeatConversion by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        NotesTopBar("Notatka", onBack)
        if (note == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(formatNoteTimestamp(note.createdAtEpochMillis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(note.rawText, style = MaterialTheme.typography.bodyLarge)
                if (note.status == "CONVERTED") {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ReceiptLong, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Z tej notatki utworzono już zamówienie")
                        }
                    }
                }
                HorizontalDivider()
                Button(onClick = { if (noteNeedsRepeatWarning(note.status)) confirmRepeatConversion = true else onConvert(note) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ReceiptLong, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Przerób na zamówienie")
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Edit, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Edytuj")
                }
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.DeleteOutline, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Usuń")
                }
            }
        }
    }

    if (confirmDelete && note != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć notatkę?") },
            text = { Text("Notatka zniknie z listy. Utworzone wcześniej zamówienie pozostanie bez zmian.") },
            confirmButton = { Button(onClick = { viewModel.delete(note.id) { onBack() } }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") } },
        )
    }
    if (confirmRepeatConversion && note != null) {
        AlertDialog(
            onDismissRequest = { confirmRepeatConversion = false },
            title = { Text("Zamówienie już utworzono") },
            text = { Text("Z tej notatki zapisano już zamówienie. Ponowne przetworzenie może utworzyć duplikat.") },
            confirmButton = { Button(onClick = { confirmRepeatConversion = false; onConvert(note) }) { Text("Przerób ponownie") } },
            dismissButton = { TextButton(onClick = { confirmRepeatConversion = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun NotesTopBar(title: String, onBack: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Wróć") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }
}

internal fun canSaveNote(text: String): Boolean = text.isNotBlank()

internal fun noteNeedsRepeatWarning(status: String): Boolean = status == "CONVERTED"

internal fun noteExcerpt(text: String): String = text.trim().replace(Regex("\\s+"), " ")

private fun formatNoteTimestamp(value: Long): String = NOTE_DATE_FORMAT.format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))

private val NOTE_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("pl", "PL"))
