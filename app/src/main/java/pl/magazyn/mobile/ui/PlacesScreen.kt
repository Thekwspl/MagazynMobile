package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.magazyn.mobile.data.TaskPlaceView
import pl.magazyn.mobile.domain.ImportParser

@Composable
fun PlacesScreen(contentPadding: PaddingValues, viewModel: TasksViewModel = viewModel()) {
    val places by viewModel.places.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf<TaskPlaceView?>(null) }
    val editedPlace = edited?.let { selected -> places.firstOrNull { it.id == selected.id } ?: selected }
    val visible = places.filter { place -> query.isBlank() || (listOf(place.name) + place.aliases.split(',')).any { ImportParser.key(it).contains(ImportParser.key(query)) } }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Miejsca", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            FilledTonalButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Text("Dodaj") }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Szukaj nazwy lub aliasu") }, singleLine = true)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.id }) { place ->
                OutlinedCard(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(place.name); place.aliases.takeIf(String::isNotBlank)?.let { Text("Aliasy: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } }
                    IconButton(onClick = { edited = place }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Edit, "Edytuj") }
                } }
            }
        }
    }
    if (adding) SimpleNewPlaceDialog({ adding = false }) { name, aliases, result -> viewModel.createPlace(name, aliases) { error -> result(error); if (error == null) adding = false } }
    editedPlace?.let { place ->
        PlaceManagementDialog(
            place,
            { edited = null },
            viewModel::renamePlace,
            viewModel::addPlaceAlias,
            viewModel::removePlaceAlias,
            onDelete = { viewModel.archivePlace(place.id); edited = null },
        )
    }
}

@Composable
private fun SimpleNewPlaceDialog(onDismiss: () -> Unit, onSave: (String, List<String>, (String?) -> Unit) -> Unit) {
    var name by remember { mutableStateOf("") }; var aliases by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nowe miejsce") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Nazwa") }); OutlinedTextField(aliases, { aliases = it }, label = { Text("Aliasy, po przecinku") }); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { Button(onClick = { onSave(name, aliases.split(',')) { error = it } }, enabled = name.isNotBlank()) { Text("Dodaj") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } })
}

@Composable
private fun PlaceManagementDialog(place: TaskPlaceView, onDismiss: () -> Unit, onRename: (String, String, (String?) -> Unit) -> Unit, onAddAlias: (String, String, (String?) -> Unit) -> Unit, onRemoveAlias: (String, String) -> Unit, onDelete: () -> Unit) {
    var name by remember(place.id) { mutableStateOf(place.name) }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Miejsce → Aliasy") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Nazwa główna") })
        EditableChipInput(
            items = place.aliases.split(','),
            label = "Nowy alias",
            onAdd = { alias, done -> onAddAlias(place.id, alias) { error = it; done(it == null) } },
            onRemove = { onRemoveAlias(place.id, it) },
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { Button(onClick = { onRename(place.id, name) { error = it; if (it == null) onDismiss() } }) { Text("Zapisz") } }, dismissButton = { Row { TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.DeleteOutline, null); Text("Usuń") }; TextButton(onClick = onDismiss) { Text("Anuluj") } } })
}
