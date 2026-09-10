package pl.magazyn.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import pl.magazyn.mobile.data.*
import pl.magazyn.mobile.domain.*

private enum class TaskFilter(val label: String) { OPEN("Otwarte"), DONE("Zakończone"), ALL("Wszystkie") }
private data class TaskPriority(val value: String, val label: String)
private val priorities = listOf(TaskPriority("LOW", "Niski"), TaskPriority("NORMAL", "Normalny"), TaskPriority("HIGH", "Wysoki"), TaskPriority("URGENT", "Pilny"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    contentPadding: PaddingValues,
    startAdding: Boolean = false,
    onNewTask: (() -> Unit)? = null,
    onCloseEditor: (() -> Unit)? = null,
    viewModel: TasksViewModel = viewModel(),
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()
    val shipyards by viewModel.shipyards.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val allSteps by viewModel.steps.collectAsStateWithLifecycle()
    val allStepPeople by viewModel.stepPeople.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(TaskFilter.OPEN) }
    var query by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf<NotebookTaskView?>(null) }
    var creating by remember { mutableStateOf(startAdding) }
    var deleting by remember { mutableStateOf<NotebookTaskView?>(null) }

    val visible = remember(tasks, filter, query, allSteps, allStepPeople) {
        tasks.filter { task ->
            val taskSteps = allSteps.filter { it.taskId == task.id }
            val peopleText = taskSteps.flatMap { step -> allStepPeople.filter { it.taskStepId == step.id } }.joinToString(" ") { it.displayName }
            (filter == TaskFilter.ALL || (filter == TaskFilter.OPEN && !task.isCompleted) || (filter == TaskFilter.DONE && task.isCompleted)) &&
                (query.isBlank() || listOf(task.text, task.description, task.place, peopleText, task.shipyardName, task.productName, task.orderName)
                    .filterNotNull().any { ImportParser.key(it).contains(ImportParser.key(query)) })
        }
    }

    if (creating || edited != null) {
        val closeEditor: () -> Unit = {
            creating = false
            edited = null
            onCloseEditor?.invoke()
            Unit
        }
        BackHandler(onBack = closeEditor)
        val taskSteps = edited?.let { task -> allSteps.filter { it.taskId == task.id } }.orEmpty()
        TaskEditorScreen(
            contentPadding, edited, taskSteps, allStepPeople, people, places, shipyards, products, orders,
            onDismiss = closeEditor,
            onCreatePlace = viewModel::createPlace,
            onAddAlias = viewModel::addPlaceAlias,
            onSave = { title, description, date, priority, steps, shipyard, product, order ->
                edited?.let { viewModel.updateTask(it.id, title, description, date, priority, steps, shipyard, product, order) }
                    ?: viewModel.createTask(title, description, date, priority, steps, shipyard, product, order)
                closeEditor()
            },
        )
        return
    }

    Scaffold(modifier = Modifier.fillMaxSize().padding(contentPadding)) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Zadania", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                FilledTonalButton(onClick = { onNewTask?.invoke() ?: run { creating = true } }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Nowe zadanie") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Szukaj w zadaniach") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskFilter.entries.forEach { item -> FilterChip(filter == item, { filter = item }, label = { Text(item.label) }) }
            }
            if (visible.isEmpty()) Text("Brak pasujących zadań.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { task ->
                    val taskSteps = allSteps.filter { it.taskId == task.id }
                    TaskCard(task, taskSteps, taskSteps.associate { step -> step.id to allStepPeople.filter { it.taskStepId == step.id } },
                        { viewModel.setCompleted(task.id, it) }, viewModel::setStepCompleted, viewModel::setStepPersonCompleted,
                        { edited = task }, { deleting = task })
                }
            }
        }
    }
    deleting?.let { task ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Usunąć zadanie?") }, text = { Text(task.text) },
            confirmButton = { TextButton(onClick = { viewModel.deleteTask(task.id); deleting = null }) { Text("Usuń", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Anuluj") } })
    }
}

@Composable
private fun TaskCard(
    task: NotebookTaskView,
    steps: List<NotebookTaskStepView>,
    people: Map<String, List<NotebookTaskStepPersonView>>,
    onCompleted: (Boolean) -> Unit,
    onStepCompleted: (String, Boolean) -> Unit,
    onPersonCompleted: (String, Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(task.id) { mutableStateOf(!task.isCompleted) }
    LaunchedEffect(task.isCompleted) { expanded = !task.isCompleted }
    val collapsed = task.isCompleted && !expanded
    StructuredWorkCard(
        expanded = !collapsed,
        onClick = { if (task.isCompleted) expanded = !expanded },
        header = {
                if (!collapsed) Checkbox(task.isCompleted, onCompleted)
                Column(Modifier.weight(1f).padding(top = if (collapsed) 2.dp else 8.dp)) {
                    Text(task.text, fontWeight = FontWeight.SemiBold, color = if (task.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    task.dueDate?.let { Text(formatDisplayDate(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    task.description.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (collapsed) 2 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!collapsed) {
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edytuj") }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Usuń") }
                }
        },
    ) {
            steps.forEach { step ->
                Column(Modifier.fillMaxWidth().padding(start = 22.dp, top = 4.dp).alpha(if (step.isCompleted) 0.55f else 1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(step.isCompleted, { onStepCompleted(step.id, it) })
                        Text(listOfNotNull(step.time, step.placeName).joinToString("  •  ").ifBlank { "Etap" }, fontWeight = FontWeight.SemiBold)
                    }
                    step.note.takeIf(String::isNotBlank)?.let { Text(it, Modifier.padding(start = 48.dp), style = MaterialTheme.typography.bodySmall) }
                    people[step.id].orEmpty().forEach { person ->
                        Row(Modifier.padding(start = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(person.isCompleted, { onPersonCompleted(person.id, it) })
                            Column(Modifier.weight(1f)) {
                                Text(person.displayName, style = MaterialTheme.typography.bodyMedium)
                                person.note.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                if (person.employeeId == null) Text("Nie znaleziono pracownika", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            val phone = person.phoneNumbers.split(',').map(String::trim).firstOrNull(String::isNotBlank)
                            IconButton(enabled = phone != null, onClick = { phone?.let { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(it)}"))) } }) {
                                Icon(Icons.Default.Phone, if (phone == null) "Brak numeru" else "Zadzwoń")
                            }
                        }
                    }
                }
            }
            task.linkedProductName?.let { ProductInfo(it, task.linkedProductVariant, task.linkedProductGroup.orEmpty(), task.linkedProductSubgroup.orEmpty(), Modifier.padding(start = 48.dp, top = 6.dp)) }
            task.shipyardName?.let { Text("Stocznia: $it", Modifier.padding(start = 48.dp), style = MaterialTheme.typography.bodySmall) }
            task.orderName?.let { Text("Zamówienie: $it", Modifier.padding(start = 48.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditorScreen(
    contentPadding: PaddingValues,
    task: NotebookTaskView?,
    storedSteps: List<NotebookTaskStepView>,
    storedPeople: List<NotebookTaskStepPersonView>,
    people: List<EmployeeSummary>,
    places: List<TaskPlaceView>,
    shipyards: List<ShipyardEntity>,
    products: List<ProductWithStock>,
    orders: List<OrderSummary>,
    onDismiss: () -> Unit,
    onCreatePlace: (String, List<String>, (String?) -> Unit) -> Unit,
    onAddAlias: (String, String, (String?) -> Unit) -> Unit,
    onSave: (String, String, String?, String, List<ParsedTaskStep>, String?, String?, String?) -> Unit,
) {
    var title by remember(task?.id) { mutableStateOf(task?.text.orEmpty()) }
    var description by remember(task?.id) { mutableStateOf(task?.description.orEmpty()) }
    var date by remember(task?.id) { mutableStateOf(task?.dueDate) }
    var priority by remember(task?.id) { mutableStateOf(task?.priority ?: "NORMAL") }
    var shipyardId by remember(task?.id) { mutableStateOf(task?.shipyardId) }
    var productId by remember(task?.id) { mutableStateOf(task?.productId) }
    var orderId by remember(task?.id) { mutableStateOf(task?.orderId) }
    var showDatePicker by remember { mutableStateOf(false) }
    val drafts = remember(task?.id, storedSteps, storedPeople, places, people) {
        val sourceSteps = if (storedSteps.isNotEmpty() || task == null) storedSteps.map { step ->
            ParsedTaskStep(step.time, step.placeId, step.placeName.orEmpty(), step.note, storedPeople.filter { it.taskStepId == step.id }.map { person ->
                ParsedTaskPerson(person.employeeId, person.displayName, person.note, if (person.employeeId == null) ParseConfidence.REVIEW else ParseConfidence.CERTAIN, person.id, person.isCompleted, person.completedAtEpochMillis, person.completedBy)
            }, ParseConfidence.CERTAIN, step.id, step.isCompleted, step.completedAtEpochMillis, step.completedBy)
        } else {
            val legacyIds = task.employeeIds?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
            val legacyPlace = places.firstOrNull { ImportParser.key(it.name) == ImportParser.key(task.place) }
            if (task.place.isBlank() && legacyIds.isEmpty()) emptyList() else listOf(
                ParsedTaskStep(
                    placeId = legacyPlace?.id,
                    placeText = legacyPlace?.name ?: task.place,
                    people = legacyIds.mapNotNull { id -> people.firstOrNull { it.id == id }?.let { ParsedTaskPerson(it.id, it.listDisplayName(), confidence = ParseConfidence.CERTAIN) } },
                    confidence = ParseConfidence.LIKELY,
                ),
            )
        }
        mutableStateListOf<ParsedTaskStep>().apply { addAll(sourceSteps) }
    }
    Column(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Wróć") }; Text(if (task == null) "Nowe zadanie" else "Edytuj zadanie", style = MaterialTheme.typography.headlineSmall) }
        HorizontalDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Tytuł zadania *") }, singleLine = true)
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Opis / notatka") }, minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { priorities.forEach { item -> FilterChip(priority == item.value, { priority = item.value }, label = { Text(item.label) }) } }
            OutlinedButton(onClick = { showDatePicker = true }, Modifier.fillMaxWidth()) { Text(date?.let(::formatDisplayDate) ?: "Dodaj termin") }
            Text("Etapy / punkty", style = MaterialTheme.typography.titleMedium)
            drafts.forEachIndexed { index, step -> StepEditor(index, step, people, places, { drafts[index] = it }, { drafts.removeAt(index) }, onCreatePlace, onAddAlias) }
            OutlinedButton(onClick = { drafts += ParsedTaskStep(confidence = ParseConfidence.CERTAIN) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("Dodaj etap") }
            RelationPicker("Stocznia", shipyardId, shipyards, { it.id }, { it.name }, { shipyardId = it })
            ProductRelationPicker(productId, products) { productId = it }
            RelationPicker("Zamówienie", orderId, orders, { it.id }, { it.recipient + " · " + formatDisplayDate(it.plannedIssueDate) }, { orderId = it })
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onDismiss, Modifier.weight(1f)) { Text("Anuluj") }; Button(onClick = { onSave(title, description, date, priority, drafts.toList(), shipyardId, productId, orderId) }, enabled = title.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Zapisz") } }
    }
    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() })
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = { TextButton(onClick = { date = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }; showDatePicker = false }) { Text("Ustaw") } }, dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Anuluj") } }) { DatePicker(state) }
    }
}

@Composable
private fun StepEditor(index: Int, step: ParsedTaskStep, people: List<EmployeeSummary>, places: List<TaskPlaceView>, onChange: (ParsedTaskStep) -> Unit, onRemove: () -> Unit, onCreatePlace: (String, List<String>, (String?) -> Unit) -> Unit, onAddAlias: (String, String, (String?) -> Unit) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Etap ${index + 1}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); IconButton(onClick = onRemove) { Icon(Icons.Default.DeleteOutline, "Usuń etap") } }
        OutlinedTextField(step.time.orEmpty(), { value -> onChange(step.copy(time = value.filter { it.isDigit() || it == ':' }.ifBlank { null })) }, Modifier.fillMaxWidth(), label = { Text("Godzina (opcjonalnie)") }, placeholder = { Text("09:40") }, singleLine = true)
        PlacePicker(step, places, onChange, onCreatePlace, onAddAlias)
        PeoplePicker(people, step.people) { updated -> onChange(step.copy(people = updated, isCompleted = if (updated.isEmpty()) step.isCompleted else updated.all { it.isCompleted })) }
        OutlinedTextField(step.note, { onChange(step.copy(note = it)) }, Modifier.fillMaxWidth(), label = { Text("Notatka etapu") })
    } }
}

@Composable
private fun PlacePicker(step: ParsedTaskStep, places: List<TaskPlaceView>, onChange: (ParsedTaskStep) -> Unit, onCreatePlace: (String, List<String>, (String?) -> Unit) -> Unit, onAddAlias: (String, String, (String?) -> Unit) -> Unit) {
    var query by remember(step.placeId, step.placeText) { mutableStateOf(step.placeText) }
    var showNew by remember { mutableStateOf(false) }; var showAlias by remember { mutableStateOf(false) }
    val key = ImportParser.key(query)
    val matches = if (query.isBlank()) places else places.filter { place -> (listOf(place.name) + place.aliases.split(',')).any { ImportParser.key(it).contains(key) } }
    OutlinedTextField(query, { query = it; onChange(step.copy(placeId = null, placeText = it)) }, Modifier.fillMaxWidth(), label = { Text("Miejsce") }, leadingIcon = { Icon(Icons.Default.LocationOn, null) }, trailingIcon = { Row { if (step.placeId != null) IconButton(onClick = { showAlias = true }) { Icon(Icons.Default.Edit, "Edytuj aliasy") }; IconButton(onClick = { showNew = true }) { Icon(Icons.Default.AddLocation, "Dodaj miejsce") } } }, singleLine = true)
    if (step.placeId == null) SuggestionList(matches, key = { it.id }) { place -> Surface(Modifier.fillMaxWidth().clickable { query = place.name; onChange(step.copy(placeId = place.id, placeText = place.name)) }, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) { Column(Modifier.padding(9.dp)) { Text(place.name); place.aliases.takeIf(String::isNotBlank)?.let { Text("Aliasy: $it", style = MaterialTheme.typography.labelSmall) } } } }
    if (showNew) PlaceDialog(query, { showNew = false }) { name, aliases, result -> onCreatePlace(name, aliases) { error -> result(error); if (error == null) { query = normalizeDisplayName(name); showNew = false } } }
    if (showAlias && step.placeId != null) AliasDialog({ showAlias = false }) { alias, result -> onAddAlias(step.placeId, alias) { error -> result(error); if (error == null) showAlias = false } }
}

@Composable
private fun PlaceDialog(initialName: String, onDismiss: () -> Unit, onSave: (String, List<String>, (String?) -> Unit) -> Unit) {
    var name by remember { mutableStateOf(initialName) }; var aliases by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Dodaj miejsce") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Nazwa główna") }); OutlinedTextField(aliases, { aliases = it }, label = { Text("Aliasy, po przecinku") }); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { Button(onClick = { onSave(name, aliases.split(',')) { error = it } }, enabled = name.isNotBlank()) { Text("Dodaj") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } })
}

@Composable
private fun AliasDialog(onDismiss: () -> Unit, onSave: (String, (String?) -> Unit) -> Unit) {
    var alias by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Dodaj alias miejsca") }, text = { Column { OutlinedTextField(alias, { alias = it }, label = { Text("Alias / skrót") }); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { Button(onClick = { onSave(alias) { error = it } }, enabled = alias.isNotBlank()) { Text("Dodaj") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } })
}

@Composable
private fun PeoplePicker(people: List<EmployeeSummary>, selected: List<ParsedTaskPerson>, onSelected: (List<ParsedTaskPerson>) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = if (query.isBlank()) emptyList() else people.filter { matchesSearch(query, it.fullName, it.aliases, it.tags) && selected.none { chosen -> chosen.employeeId == it.id } }
    selected.forEachIndexed { index, person -> Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(person.displayText); person.note.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall) } }; IconButton(onClick = { onSelected(selected.toMutableList().also { it.removeAt(index) }) }) { Icon(Icons.Default.Clear, "Usuń") } } }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Wyszukaj osobę") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
    SuggestionList(matches, key = { it.id }) { person -> Surface(Modifier.fillMaxWidth().clickable { onSelected(selected + ParsedTaskPerson(person.id, person.listDisplayName(), confidence = ParseConfidence.CERTAIN)); query = "" }, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) { Text(person.listDisplayName(), Modifier.padding(10.dp)) } }
}

@Composable
private fun <T> RelationPicker(label: String, selectedId: String?, choices: List<T>, id: (T) -> String, title: (T) -> String, onSelected: (String?) -> Unit) {
    var query by remember(selectedId, choices) { mutableStateOf(choices.firstOrNull { id(it) == selectedId }?.let(title).orEmpty()) }
    val matches = if (query.isBlank()) emptyList() else choices.filter { ImportParser.key(title(it)).contains(ImportParser.key(query)) }
    OutlinedTextField(query, { query = it; onSelected(null) }, Modifier.fillMaxWidth(), label = { Text("$label (opcjonalnie)") }, singleLine = true)
    if (selectedId == null) SuggestionList(matches, key = id) { choice -> Surface(Modifier.fillMaxWidth().clickable { query = title(choice); onSelected(id(choice)) }, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) { Text(title(choice), Modifier.padding(8.dp)) } }
}

@Composable
private fun ProductRelationPicker(selectedId: String?, products: List<ProductWithStock>, onSelected: (String?) -> Unit) {
    val selected = products.firstOrNull { it.id == selectedId }
    var query by remember(selectedId, products) { mutableStateOf(selected?.let { it.name + " " + it.variant.orEmpty() }.orEmpty()) }
    val matches = if (query.isBlank()) emptyList() else products.filter { matchesSearch(query, it.name, it.variant.orEmpty(), it.groupName, it.subgroupName, it.aliases, it.tags) }
    OutlinedTextField(query, { query = it; onSelected(null) }, Modifier.fillMaxWidth(), label = { Text("Przedmiot (opcjonalnie)") }, singleLine = true)
    if (selectedId == null) SuggestionList(matches, key = { it.id }) { product ->
        Surface(Modifier.fillMaxWidth().clickable { query = product.name + " " + product.variant.orEmpty(); onSelected(product.id) }, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            ProductInfo(product.name, product.variant, product.groupName, product.subgroupName, Modifier.padding(8.dp))
        }
    }
}
