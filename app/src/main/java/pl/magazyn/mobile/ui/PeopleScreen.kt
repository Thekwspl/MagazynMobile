package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import pl.magazyn.mobile.data.EmployeeSummary
import pl.magazyn.mobile.data.EmployeePossession
import pl.magazyn.mobile.data.EmployeeIssue
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.domain.matchesSearch
import pl.magazyn.mobile.domain.removeDigits

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    contentPadding: PaddingValues,
    startAdding: Boolean = false,
    initialPersonId: String? = null,
    startIssuing: Boolean = false,
    viewModel: PeopleViewModel = viewModel(),
) {
    val people by viewModel.people.collectAsStateWithLifecycle()
    val jobPositions by viewModel.jobPositions.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(initialPersonId) }
    var showNew by rememberSaveable { mutableStateOf(startAdding) }
    val listState = rememberLazyListState()
    val newPersonSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val profileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val visible = people.filter {
        query.isBlank() || listOf(it.fullName, it.phoneNumbers, it.aliases, it.tags, it.positions).any { field -> field.contains(query, true) }
    }
    val selected = selectedId?.let { id -> people.firstOrNull { it.id == id } }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        ScreenHeader("Osoby", "Dodaj osobę", { showNew = true })
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Szukaj po nazwisku, ksywce lub tagu") }, singleLine = true)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.id }) { person ->
                OutlinedCard(onClick = { selectedId = person.id }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text(person.listDisplayName(), fontWeight = FontWeight.SemiBold)
                                if (person.phoneNumbers.isNotBlank()) PhoneNumbersInline(person.phoneNumbers)
                            }
                            if (person.positions.isNotBlank()) Text(person.positions, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        ModalBottomSheet(sheetState = newPersonSheetState, onDismissRequest = { showNew = false }) {
            PersonEditor(null, jobPositions, { showNew = false }) { existing, firstName, lastName, phones, positions, aliases, tags ->
                viewModel.savePerson(existing, firstName, lastName, phones, positions, aliases, tags)
                showNew = false
            }
        }
    }
    selected?.let { person ->
        val possessions by viewModel.possessions(person.id).collectAsStateWithLifecycle(initialValue = emptyList())
        val history by viewModel.issueHistory(person.id).collectAsStateWithLifecycle(initialValue = emptyList())
        val products by viewModel.products.collectAsStateWithLifecycle()
        ModalBottomSheet(sheetState = profileSheetState, onDismissRequest = { selectedId = null }) {
            PersonProfile(
                person = person,
                products = products,
                possessions = possessions,
                history = history,
                jobPositions = jobPositions,
                startIssuing = startIssuing,
                onClose = { selectedId = null },
                onSave = { existing, firstName, lastName, phones, positions, aliases, tags -> viewModel.savePerson(existing, firstName, lastName, phones, positions, aliases, tags) },
                onIssue = { items, date -> viewModel.issueToPerson(person.id, items, date) },
                onCorrectIssue = { issue, productId, quantity, date, delete ->
                    viewModel.correctIssue(person.id, issue, productId, quantity, date, delete)
                },
                onReturnIssue = { issue, quantity, date -> viewModel.returnIssue(person.id, issue, quantity, date) },
                onRemovePerson = {
                    viewModel.removePerson(person.id)
                    selectedId = null
                },
            )
        }
    }
}

@Composable
private fun PersonEditor(
    person: EmployeeSummary?,
    jobPositions: List<pl.magazyn.mobile.data.JobPositionEntity>,
    onCancel: () -> Unit,
    embeddedInScrollableProfile: Boolean = false,
    onSave: (EmployeeSummary?, String, String, String, String, String, String) -> Unit,
) {
    var firstName by rememberSaveable(person?.id) { mutableStateOf(person?.firstName.orEmpty()) }
    var lastName by rememberSaveable(person?.id) { mutableStateOf(person?.lastName.orEmpty()) }
    var phoneNumbers by rememberSaveable(person?.id) { mutableStateOf(person?.phoneNumbers.orEmpty()) }
    var positions by rememberSaveable(person?.id) { mutableStateOf(person?.positions.orEmpty()) }
    var aliases by rememberSaveable(person?.id) { mutableStateOf(person?.aliases.orEmpty()) }
    var tags by rememberSaveable(person?.id) { mutableStateOf(person?.tags.orEmpty()) }
    val valid = firstName.isNotBlank() && lastName.isNotBlank()
    val editorModifier = if (embeddedInScrollableProfile) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
    }
    Column(editorModifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (person == null) "Nowa osoba" else "Edytuj dane osoby", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(firstName, { firstName = removeDigits(it) }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Imię *") }, singleLine = true)
        OutlinedTextField(lastName, { lastName = removeDigits(it) }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Nazwisko *") }, singleLine = true)
        if ((firstName.isNotBlank() || lastName.isNotBlank()) && !valid) Text("Imię i nazwisko są wymagane", color = MaterialTheme.colorScheme.error)
        Text("Pozostałe pola są opcjonalne", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            phoneNumbers,
            { phoneNumbers = it.filter { character -> character.isDigit() || character in "+-(),; " } },
            Modifier.fillMaxWidth().keepAboveKeyboard(),
            label = { Text("Numery telefonów, oddzielone przecinkami") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        PositionSelector(jobPositions, positions, { positions = it })
        OutlinedTextField(aliases, { aliases = it }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Ksywki i aliasy, oddzielone przecinkami") })
        OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Tagi, oddzielone przecinkami") })
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel) { Text("Anuluj") }
            Button(onClick = { onSave(person, firstName, lastName, phoneNumbers, positions, aliases, tags) }, enabled = valid) { Text("Zapisz") }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonProfile(
    person: EmployeeSummary,
    products: List<ProductWithStock>,
    possessions: List<EmployeePossession>,
    history: List<EmployeeIssue>,
    jobPositions: List<pl.magazyn.mobile.data.JobPositionEntity>,
    startIssuing: Boolean,
    onClose: () -> Unit,
    onSave: (EmployeeSummary?, String, String, String, String, String, String) -> Unit,
    onIssue: (List<IssueRequest>, String) -> Unit,
    onCorrectIssue: (EmployeeIssue, String, Long, String, Boolean) -> Unit,
    onReturnIssue: (EmployeeIssue, Long, String) -> Unit,
    onRemovePerson: () -> Unit,
) {
    var editing by rememberSaveable(person.id) { mutableStateOf(false) }
    var issuing by rememberSaveable(person.id) { mutableStateOf(startIssuing) }
    var correctingIssue by remember { mutableStateOf<EmployeeIssue?>(null) }
    var confirmPersonRemoval by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (editing) {
            PersonEditor(person, jobPositions, { editing = false }, embeddedInScrollableProfile = true) { existing, firstName, lastName, phones, positions, aliases, tags ->
                onSave(existing, firstName, lastName, phones, positions, aliases, tags)
                editing = false
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(person.fullName, style = MaterialTheme.typography.titleLarge)
            if (person.phoneNumbers.isNotBlank()) PhoneNumbersInline(person.phoneNumbers)
        }
        if (person.positions.isNotBlank()) Text(person.positions, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = true }) { Text("Edytuj dane") }
            Button(onClick = { issuing = !issuing }) { Text("Wydaj przedmiot") }
        }
        if (person.aliases.isNotBlank()) ProfileField("Ksywki i aliasy", person.aliases)
        if (person.tags.isNotBlank()) ProfileField("Tagi", person.tags)
        if (issuing) {
            IssueForm(products = products, history = history, onIssue = { items, date ->
                onIssue(items, date)
                issuing = false
            })
        }
        HorizontalDivider()
        Text("Aktualnie posiada", style = MaterialTheme.typography.titleMedium)
        if (possessions.isEmpty()) Text("Brak aktywnie powierzonego sprzętu")
        if (possessions.isNotEmpty()) PossessionsTable(possessions)
        HorizontalDivider()
        Text("Historia wydań", style = MaterialTheme.typography.titleMedium)
        if (history.isEmpty()) Text("Brak zapisanych wydań")
        if (history.isNotEmpty()) IssueHistoryTable(history, onEdit = { correctingIssue = it })
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            TextButton(onClick = { confirmPersonRemoval = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.DeleteOutline, null)
                Text("Usuń osobę")
            }
            TextButton(onClick = onClose) { Text("Zamknij") }
        }
        Spacer(Modifier.height(16.dp))
    }
    correctingIssue?.let { issue ->
        IssueCorrectionDialog(
            issue = issue,
            products = products,
            onDismiss = { correctingIssue = null },
            onSave = { productId, quantity, date ->
                onCorrectIssue(issue, productId, quantity, date, false)
                correctingIssue = null
            },
            onDelete = {
                onCorrectIssue(issue, issue.productId, 0, issue.effectiveDate, true)
                correctingIssue = null
            },
            onReturn = { quantity, date ->
                onReturnIssue(issue, quantity, date)
                correctingIssue = null
            },
        )
    }
    if (confirmPersonRemoval) {
        AlertDialog(
            onDismissRequest = { confirmPersonRemoval = false },
            title = { Text("Usunąć osobę?") },
            text = { Text("Osoba zniknie z aktywnej listy. Jej wcześniejsze wydania pozostaną w historii magazynu.") },
            confirmButton = { Button(onClick = onRemovePerson, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = { confirmPersonRemoval = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun ProfileField(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun PossessionsTable(items: List<EmployeePossession>) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)).padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Text("Przedmiot", Modifier.weight(1.7f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
            Text("Ilość", Modifier.weight(0.7f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
            Text("Wydano", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
        }
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.productName + item.variant?.let { " · $it" }.orEmpty(), Modifier.weight(1.7f), style = MaterialTheme.typography.bodySmall)
                Text(formatWholeQuantity(item.quantity) + " " + item.unit, Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall)
                Text(formatDisplayDate(item.issuedDate), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        }
    }
}

@Composable
private fun IssueHistoryTable(items: List<EmployeeIssue>, onEdit: (EmployeeIssue) -> Unit) {
    val today = remember { LocalDate.now() }
    val latestIssueByProduct = remember(items) {
        items.filterNot { it.isDeleted }.groupBy { it.productId }.mapValues { (_, issues) ->
            issues.maxByOrNull { runCatching { LocalDate.parse(it.effectiveDate) }.getOrDefault(LocalDate.MIN) }?.movementId
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)).padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Text("Przedmiot", Modifier.weight(1.8f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
            Text("Data wydania", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        }
        items.forEach { item ->
            val issueDate = runCatching { LocalDate.parse(item.effectiveDate) }.getOrNull()
            val nextAllowed = issueDate?.plusWeeks(item.repeatIssueWeeks.toLong())
            val isLatest = latestIssueByProduct[item.productId] == item.movementId
            val tooEarly = isLatest && item.repeatIssueWeeks > 0 && nextAllowed != null && today.isBefore(nextAllowed)
            val rowColor = if (tooEarly) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f) else androidx.compose.ui.graphics.Color.Transparent
            val contentColor = if (tooEarly) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface
            Row(
                Modifier.fillMaxWidth().background(rowColor).padding(horizontal = 8.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1.8f)) {
                    Text(
                        item.productName + item.variant?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isDeleted) MaterialTheme.colorScheme.onSurfaceVariant else contentColor,
                        textDecoration = if (item.isDeleted) TextDecoration.LineThrough else null,
                    )
                    if (item.isDeleted) Text("Usunięte korektą", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    else if (item.isAmended) Text("Po korekcie", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (item.returnedQuantity > 0) Text(
                        "Zwrócono ${formatWholeQuantity(item.returnedQuantity)} ${item.unit}" + item.lastReturnedDate?.let { " · ${formatDisplayDate(it)}" }.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (tooEarly) {
                        Text(
                            "Jeszcze za wcześnie · ponownie od ${formatDisplayDate(nextAllowed.toString())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(formatDisplayDate(item.effectiveDate), Modifier.weight(0.85f), style = MaterialTheme.typography.bodySmall, color = contentColor, textAlign = TextAlign.End)
                IconButton(onClick = { onEdit(item) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, "Popraw wydanie", modifier = Modifier.size(18.dp)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IssueCorrectionDialog(
    issue: EmployeeIssue,
    products: List<ProductWithStock>,
    onDismiss: () -> Unit,
    onSave: (String, Long, String) -> Unit,
    onDelete: () -> Unit,
    onReturn: (Long, String) -> Unit,
) {
    var productId by rememberSaveable(issue.lineId) { mutableStateOf(issue.productId) }
    var query by rememberSaveable(issue.lineId) { mutableStateOf(issue.productName + issue.variant?.let { " · $it" }.orEmpty()) }
    val remainingToReturn = (issue.quantity - issue.returnedQuantity).coerceAtLeast(0.0).toLong()
    var quantity by rememberSaveable(issue.lineId) { mutableStateOf(formatWholeQuantity(issue.quantity)) }
    var returnQuantity by rememberSaveable(issue.lineId) { mutableStateOf(remainingToReturn.toString()) }
    var date by rememberSaveable(issue.lineId) { mutableStateOf(issue.effectiveDate) }
    var showSuggestions by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tokens = query.split(Regex("\\s+")).filter(String::isNotBlank)
    val suggestions = products.filter { product ->
        tokens.isNotEmpty() && matchesSearch(query, product.name, product.variant.orEmpty(), product.aliases, product.tags)
    }.take(6)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Popraw wcześniejsze wydanie") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Oryginalny zapis pozostanie w historii, a aplikacja utworzy korektę stanów.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    query,
                    { query = it; productId = ""; showSuggestions = true },
                    Modifier.fillMaxWidth(),
                    label = { Text("Przedmiot") },
                    singleLine = true,
                    enabled = issue.returnedQuantity == 0.0,
                )
                if (issue.returnedQuantity > 0) Text("Po zapisanym zwrocie można zmienić ilość i datę, ale nie rodzaj przedmiotu.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showSuggestions && issue.returnedQuantity == 0.0) suggestions.forEach { product ->
                    OutlinedCard(
                        onClick = {
                            productId = product.id
                            query = product.name + product.variant?.let { " · $it" }.orEmpty()
                            showSuggestions = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), Arrangement.SpaceBetween) {
                            Text(product.name + product.variant?.let { " · $it" }.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("${formatWholeQuantity(product.stockQuantity)} ${product.unit}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                OutlinedTextField(
                    quantity,
                    { quantity = it.filter(Char::isDigit) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Ilość") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedButton(onClick = { showDatePicker = true }, Modifier.fillMaxWidth()) { Text("Data operacji: ${formatDisplayDate(date)}") }
                HorizontalDivider()
                OutlinedTextField(
                    returnQuantity,
                    { returnQuantity = it.filter(Char::isDigit) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Ilość zwracana") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = remainingToReturn > 0,
                )
                FilledTonalButton(
                    onClick = { onReturn(returnQuantity.toLongOrNull() ?: 0L, date) },
                    enabled = remainingToReturn > 0 && (returnQuantity.toLongOrNull() ?: 0L) in 1L..remainingToReturn,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (remainingToReturn > 0) "Zwróć do magazynu" else "Całość została zwrócona") }
                if (!confirmDelete) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Default.DeleteOutline, null)
                        Text("Usuń to wydanie")
                    }
                } else {
                    Text(
                        if (issue.movementType == "ISSUE") "Przedmiot wróci na stan magazynu. Tej operacji nie ukrywamy z historii."
                        else "Historyczny wpis zostanie oznaczony jako usunięty. Obecny stan magazynu głównego pozostanie bez zmian.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), Arrangement.End) {
                        TextButton(onClick = { confirmDelete = false }) { Text("Nie usuwaj") }
                        Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Potwierdź usunięcie") }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(productId, quantity.toLongOrNull() ?: 0L, date) },
                enabled = productId.isNotBlank() && (quantity.toLongOrNull() ?: 0L) >= issue.returnedQuantity.toLong() && !confirmDelete,
            ) { Text("Zapisz korektę") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
    if (showDatePicker) {
        val initial = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate().toString() }
                    showDatePicker = false
                }) { Text("Wybierz") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Anuluj") } },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IssueForm(products: List<ProductWithStock>, history: List<EmployeeIssue>, onIssue: (List<IssueRequest>, String) -> Unit) {
    val lines = remember { mutableStateListOf(NewIssueLine()) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmNegative by rememberSaveable { mutableStateOf(false) }
    val selectedDate = runCatching { LocalDate.parse(date) }.getOrNull()
    val totals = lines.mapNotNull { line -> line.quantity.toLongOrNull()?.let { line.productId to it } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, quantities) -> quantities.sum() }
    val createsNegative = totals.any { (productId, quantity) ->
        products.firstOrNull { it.id == productId }?.let { it.stockQuantity - quantity < 0 } == true
    }
    val allValid = lines.isNotEmpty() && lines.all { it.productId.isNotBlank() && (it.quantity.toLongOrNull() ?: 0L) > 0L }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Wydanie bez zamówienia", style = MaterialTheme.typography.titleMedium)
            lines.forEachIndexed { index, line ->
                IssueLineEditor(
                    line = line,
                    products = products,
                    canRemove = lines.size > 1,
                    onChange = { lines[index] = it; confirmNegative = false },
                    onRemove = { lines.removeAt(index); confirmNegative = false },
                )
                val selected = products.firstOrNull { it.id == line.productId }
                val repeatWeeks = selected?.repeatIssueWeeks ?: 0
                val previousDate = if (selectedDate == null || repeatWeeks <= 0 || selected == null) null else history.asSequence()
                    .filter { it.productId == selected.id && !it.isDeleted }
                    .mapNotNull { runCatching { LocalDate.parse(it.effectiveDate) }.getOrNull() }
                    .filter { !it.isAfter(selectedDate) }
                    .maxOrNull()
                val nextAllowed = previousDate?.plusWeeks(repeatWeeks.toLong())
                if (selected != null && nextAllowed != null && selectedDate != null && selectedDate.isBefore(nextAllowed)) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Uwaga: ${selected.name} wydano ostatnio ${formatDisplayDate(previousDate.toString())}. " +
                                "Zalecane ponowne wydanie od ${formatDisplayDate(nextAllowed.toString())}. Możesz mimo to kontynuować.",
                            Modifier.padding(10.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { lines.add(NewIssueLine()); confirmNegative = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, null)
                Text("Dodaj kolejny przedmiot")
            }
            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) { Text("Data wydania: " + formatDisplayDate(date)) }
            if (createsNegative) {
                Text("Co najmniej jedna pozycja utworzy stan ujemny.", color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(confirmNegative, { confirmNegative = it })
                    Text("Potwierdzam wydanie mimo braku stanu")
                }
            }
            Button(
                onClick = { onIssue(lines.map { IssueRequest(it.productId, it.quantity.toLongOrNull() ?: 0L) }, date) },
                enabled = allValid && (!createsNegative || confirmNegative),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Zapisz wydanie") }
        }
    }
    if (showDatePicker) {
        val initial = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate().toString() }
                    showDatePicker = false
                }) { Text("Wybierz") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Anuluj") } },
        ) { DatePicker(state) }
    }
}

private data class NewIssueLine(
    val key: String = UUID.randomUUID().toString(),
    val productId: String = "",
    val productQuery: String = "",
    val quantity: String = "",
    val suggestionsVisible: Boolean = false,
)

@Composable
private fun IssueLineEditor(
    line: NewIssueLine,
    products: List<ProductWithStock>,
    canRemove: Boolean,
    onChange: (NewIssueLine) -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = line.productQuery.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val suggestions = products.filter { product ->
        tokens.isNotEmpty() && matchesSearch(
            line.productQuery, product.name, product.variant.orEmpty(), product.aliases, product.tags,
            product.category, product.groupName, product.subgroupName,
        )
    }.take(4)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pozycja", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                if (canRemove) IconButton(onClick = onRemove) { Icon(Icons.Default.DeleteOutline, "Usuń pozycję") }
            }
            OutlinedTextField(
                line.productQuery,
                { onChange(line.copy(productQuery = it, productId = "", suggestionsVisible = true)) },
                Modifier.fillMaxWidth().keepAboveKeyboard(),
                label = { Text("Przedmiot") },
                placeholder = { Text("Wpisz nazwę lub wariant") },
                singleLine = true,
            )
            if (line.suggestionsVisible && suggestions.isNotEmpty()) {
                suggestions.forEach { product ->
                    OutlinedCard(
                        onClick = {
                            onChange(
                                line.copy(
                                    productId = product.id,
                                    productQuery = product.name + product.variant?.let { " · $it" }.orEmpty(),
                                    suggestionsVisible = false,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(product.name + product.variant?.let { " · $it" }.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (product.stockKnown) formatWholeQuantity(product.stockQuantity) + " " + product.unit else "stan ?",
                                Modifier.padding(start = 8.dp),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                line.quantity,
                { onChange(line.copy(quantity = it.filter(Char::isDigit))) },
                Modifier.fillMaxWidth().keepAboveKeyboard(),
                label = { Text("Ilość") },
                placeholder = { Text("1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}

@Composable
fun ScreenHeader(title: String, actionLabel: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onAction) {
            Icon(Icons.Default.Add, null)
            Text(actionLabel)
        }
    }
}
