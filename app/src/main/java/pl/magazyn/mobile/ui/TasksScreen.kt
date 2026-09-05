package pl.magazyn.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import pl.magazyn.mobile.data.EmployeeSummary
import pl.magazyn.mobile.data.NotebookTaskView
import pl.magazyn.mobile.data.OrderSummary
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.data.ShipyardEntity
import pl.magazyn.mobile.domain.ImportParser

private enum class TaskFilter(val label: String) { OPEN("Otwarte"), DONE("Zakończone"), ALL("Wszystkie") }
private data class TaskPriority(val value: String, val label: String)
private val priorities = listOf(TaskPriority("LOW", "Niski"), TaskPriority("NORMAL", "Normalny"), TaskPriority("HIGH", "Wysoki"), TaskPriority("URGENT", "Pilny"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(contentPadding: PaddingValues, startAdding: Boolean = false, viewModel: TasksViewModel = viewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()
    val shipyards by viewModel.shipyards.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(TaskFilter.OPEN) }
    var query by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf<NotebookTaskView?>(null) }
    var creating by remember { mutableStateOf(startAdding) }
    var deleting by remember { mutableStateOf<NotebookTaskView?>(null) }

    val visible = remember(tasks, filter, query) {
        tasks.filter { task ->
            (filter == TaskFilter.ALL || (filter == TaskFilter.OPEN && !task.isCompleted) || (filter == TaskFilter.DONE && task.isCompleted)) &&
                (query.isBlank() || listOf(task.text, task.employeeName, task.shipyardName, task.productName, task.orderName)
                    .filterNotNull().any { ImportParser.key(it).contains(ImportParser.key(query)) })
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        floatingActionButton = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Dodaj zadanie") } },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Text("Zadania", Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text("Szukaj w zadaniach") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Wyczyść") } },
                singleLine = true,
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskFilter.entries.forEach { item -> FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item.label) }) }
            }
            if (visible.isEmpty()) {
                Text(if (filter == TaskFilter.OPEN) "Brak otwartych zadań." else "Brak pasujących zadań.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id }) { task ->
                    TaskCard(task, onCompleted = { viewModel.setCompleted(task.id, it) }, onEdit = { edited = task }, onDelete = { deleting = task })
                }
            }
        }
    }

    if (creating || edited != null) TaskEditorDialog(
        task = edited, people = people, shipyards = shipyards, products = products, orders = orders,
        onDismiss = { creating = false; edited = null },
        onSave = { text, date, priority, person, shipyard, product, order ->
            edited?.let { viewModel.updateTask(it.id, text, date, priority, person, shipyard, product, order) }
                ?: viewModel.createTask(text, date, priority, person, shipyard, product, order)
            creating = false; edited = null
        },
    )
    deleting?.let { task ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Usunąć zadanie?") },
            text = { Text(task.text) },
            confirmButton = { TextButton(onClick = { viewModel.deleteTask(task.id); deleting = null }) { Text("Usuń", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun TaskCard(task: NotebookTaskView, onCompleted: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val overdue = task.dueDate?.let { runCatching { LocalDate.parse(it).isBefore(LocalDate.now()) }.getOrDefault(false) } == true && !task.isCompleted
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Checkbox(task.isCompleted, onCompleted)
            Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.text, fontWeight = FontWeight.SemiBold, color = if (task.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (task.priority != "NORMAL") SuggestionChip(onClick = {}, label = { Text(priorities.firstOrNull { it.value == task.priority }?.label ?: task.priority) })
                    task.dueDate?.let { Text(formatDisplayDate(it), style = MaterialTheme.typography.labelMedium, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                }
                listOfNotNull(
                    task.employeeName?.takeIf(String::isNotBlank)?.let { "Osoba: $it" },
                    task.shipyardName?.let { "Stocznia: $it" }, task.productName?.takeIf(String::isNotBlank)?.let { "Przedmiot: $it" },
                    task.orderName?.takeIf(String::isNotBlank)?.let { "Zamówienie: $it" },
                ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edytuj") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Usuń") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskEditorDialog(
    task: NotebookTaskView?, people: List<EmployeeSummary>, shipyards: List<ShipyardEntity>, products: List<ProductWithStock>, orders: List<OrderSummary>,
    onDismiss: () -> Unit, onSave: (String, String?, String, String?, String?, String?, String?) -> Unit,
) {
    var text by remember(task?.id) { mutableStateOf(task?.text.orEmpty()) }
    var date by remember(task?.id) { mutableStateOf(task?.dueDate) }
    var priority by remember(task?.id) { mutableStateOf(task?.priority ?: "NORMAL") }
    var personId by remember(task?.id) { mutableStateOf(task?.employeeId) }
    var shipyardId by remember(task?.id) { mutableStateOf(task?.shipyardId) }
    var productId by remember(task?.id) { mutableStateOf(task?.productId) }
    var orderId by remember(task?.id) { mutableStateOf(task?.orderId) }
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "Nowe zadanie" else "Edytuj zadanie") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Treść zadania *") }, minLines = 2)
                Text("Priorytet", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    priorities.forEach { item -> FilterChip(selected = priority == item.value, onClick = { priority = item.value }, label = { Text(item.label) }) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showDatePicker = true }, Modifier.weight(1f)) { Text(date?.let(::formatDisplayDate) ?: "Dodaj termin") }
                    if (date != null) IconButton(onClick = { date = null }) { Icon(Icons.Default.Clear, "Usuń termin") }
                }
                RelationPicker("Osoba", personId, people, { it.id }, { it.listDisplayName() }, { personId = it })
                RelationPicker("Stocznia", shipyardId, shipyards, { it.id }, { it.name }, { shipyardId = it })
                RelationPicker("Przedmiot", productId, products, { it.id }, { it.name + it.variant?.let { v -> " · $v" }.orEmpty() }, { productId = it })
                RelationPicker("Zamówienie", orderId, orders, { it.id }, { it.recipient + " · " + formatDisplayDate(it.plannedIssueDate) }, { orderId = it })
            }
        },
        confirmButton = { Button(onClick = { onSave(text, date, priority, personId, shipyardId, productId, orderId) }, enabled = text.isNotBlank()) { Text("Zapisz") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() })
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = { TextButton(onClick = {
                date = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }
                showDatePicker = false
            }) { Text("Ustaw") } },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Anuluj") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun <T> RelationPicker(label: String, selectedId: String?, choices: List<T>, id: (T) -> String, title: (T) -> String, onSelected: (String?) -> Unit) {
    var query by remember(selectedId, choices) { mutableStateOf(choices.firstOrNull { id(it) == selectedId }?.let(title).orEmpty()) }
    val matches = remember(query, choices) { if (query.isBlank()) emptyList() else choices.filter { ImportParser.key(title(it)).contains(ImportParser.key(query)) }.take(4) }
    OutlinedTextField(
        value = query, onValueChange = { query = it; if (choices.none { choice -> id(choice) == selectedId && title(choice) == it }) onSelected(null) },
        modifier = Modifier.fillMaxWidth(), label = { Text("$label (opcjonalnie)") }, singleLine = true,
        trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = ""; onSelected(null) }) { Icon(Icons.Default.Clear, "Wyczyść") } },
    )
    if (selectedId == null) matches.forEach { choice ->
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { query = title(choice); onSelected(id(choice)) },
            color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small,
        ) { Text(title(choice), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
    }
}
