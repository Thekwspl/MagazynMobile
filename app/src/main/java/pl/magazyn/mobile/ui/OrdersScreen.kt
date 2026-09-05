package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import pl.magazyn.mobile.data.EmployeeSummary
import pl.magazyn.mobile.data.OrderDetailLine
import pl.magazyn.mobile.data.OrderChangeEntity
import pl.magazyn.mobile.data.OrderSummary
import pl.magazyn.mobile.data.ProductWithStock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(contentPadding: PaddingValues, viewModel: OrdersViewModel = viewModel()) {
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()
    val jobPositions by viewModel.jobPositions.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = orders.firstOrNull { it.id == selectedId }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Text("Zamówienia", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
        if (orders.isEmpty()) {
            Text("Brak aktywnych szkiców zamówień.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(orders, key = { it.id }) { order ->
                    OutlinedCard(onClick = { selectedId = order.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text(order.recipient.ifBlank { "Nieprzypisana osoba" }, fontWeight = FontWeight.SemiBold)
                                Text(formatDisplayDate(order.plannedIssueDate), style = MaterialTheme.typography.labelMedium)
                            }
                            LinearProgressIndicator(
                                progress = { if (order.lineCount == 0) 0f else order.preparedCount.toFloat() / order.lineCount },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("Przygotowano ${order.preparedCount} z ${order.lineCount}", style = MaterialTheme.typography.bodySmall)
                            if (order.employeeId == null && order.siteLabel.isNullOrBlank()) Text("Nie rozpoznano osoby — otwórz i przypisz", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                            order.siteLabel?.takeIf(String::isNotBlank)?.let { Text("Stocznia: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                            if (order.unmappedCount > 0) Text("${order.unmappedCount} pozycji wymaga przypisania produktu", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    selected?.let { order ->
        ModalBottomSheet(onDismissRequest = { selectedId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            OrderDetails(
                order, people, products, jobPositions,
                linesFlow = { viewModel.lines(order.id) },
                changesFlow = { viewModel.changes(order.id) },
                onUpdateOrder = viewModel::updateOrder,
                onPrepared = viewModel::setPrepared,
                onUpdateLine = viewModel::updateLine,
                onAddLine = { viewModel.addLine(order.id) },
                onDeleteLine = viewModel::deleteLine,
                onAddProductTags = viewModel::addProductTags,
                onCreatePerson = viewModel::createPerson,
                onCreateProduct = viewModel::createProduct,
                onCancelOrder = { viewModel.cancelOrder(order.id); selectedId = null },
                onRealize = { employeeId, date -> viewModel.realize(order.id, employeeId, date); selectedId = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderDetails(
    order: OrderSummary,
    people: List<EmployeeSummary>,
    products: List<ProductWithStock>,
    jobPositions: List<pl.magazyn.mobile.data.JobPositionEntity>,
    linesFlow: () -> kotlinx.coroutines.flow.Flow<List<OrderDetailLine>>,
    changesFlow: () -> kotlinx.coroutines.flow.Flow<List<OrderChangeEntity>>,
    onUpdateOrder: (String, String?, String, String) -> Unit,
    onPrepared: (String, Boolean) -> Unit,
    onUpdateLine: (String, String?, String, Long, String) -> Unit,
    onAddLine: () -> Unit,
    onDeleteLine: (String) -> Unit,
    onAddProductTags: (String, String) -> Unit,
    onCreatePerson: (String, String, String, String, String, (String, String) -> Unit) -> Unit,
    onCreateProduct: (String, String, String, Long, String, (ProductWithStock) -> Unit) -> Unit,
    onCancelOrder: () -> Unit,
    onRealize: (String?, String) -> Unit,
) {
    val lines by remember(order.id) { linesFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val changes by remember(order.id) { changesFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var employeeId by rememberSaveable(order.id) { mutableStateOf(order.employeeId) }
    var date by rememberSaveable(order.id) { mutableStateOf(order.plannedIssueDate) }
    var showPersonPicker by remember { mutableStateOf(false) }
    var showNewPerson by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var editingLine by remember { mutableStateOf<OrderDetailLine?>(null) }
    var confirmNegative by rememberSaveable(order.id) { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    val createsNegative = lines.filter { it.productId != null }.groupBy { it.productId }.any { (_, grouped) ->
        grouped.first().stockQuantity - grouped.sumOf { it.quantity } < 0
    }
    val ready = (employeeId != null || !order.siteLabel.isNullOrBlank()) && lines.isNotEmpty() && lines.all { it.productId != null && it.isPrepared }

    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Kompletowanie zamówienia", style = MaterialTheme.typography.titleLarge)
        order.siteLabel?.takeIf(String::isNotBlank)?.let { Text("Stocznia: $it", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        OutlinedButton(onClick = { showPersonPicker = true }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Person, null)
            Spacer(Modifier.width(7.dp))
            Text(people.firstOrNull { it.id == employeeId }?.listDisplayName() ?: "Wyszukaj i przypisz osobę")
        }
        if (employeeId == null && order.siteLabel.isNullOrBlank()) Text("Nie rozpoznano osoby z notatki. Wybierz ją z bazy przed realizacją.", color = MaterialTheme.colorScheme.error)
        if (employeeId == null && !order.siteLabel.isNullOrBlank()) Text("To zamówienie zostanie wydane na stan stoczni.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        OutlinedButton(onClick = { showDatePicker = true }, Modifier.fillMaxWidth()) { Text("Data wydania: ${formatDisplayDate(date)}") }
        HorizontalDivider()
        lines.forEach { line ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(line.isPrepared, { onPrepared(line.id, it) }, enabled = line.productId != null)
                Column(Modifier.weight(1f)) {
                    Text(line.productName?.let { it + line.productVariant?.let { variant -> " · $variant" }.orEmpty() } ?: line.rawText, fontWeight = FontWeight.SemiBold)
                    Text("${formatWholeQuantity(line.quantity)} ${line.unit} · stan ${formatWholeQuantity(line.stockQuantity)}", style = MaterialTheme.typography.labelMedium)
                    if (line.productId == null) Text("Nie rozpoznano przedmiotu — przypisz go", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { editingLine = line }) { Icon(Icons.Default.Edit, "Popraw") }
                IconButton(onClick = { onDeleteLine(line.id) }) { Icon(Icons.Default.DeleteOutline, "Usuń pozycję") }
            }
            HorizontalDivider()
        }
        OutlinedButton(onClick = onAddLine, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("Dodaj pozycję") }
        HorizontalDivider()
        Text("Historia zmian", style = MaterialTheme.typography.titleMedium)
        if (changes.isEmpty()) {
            Text("Brak zapisanych zmian w tym zamówieniu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            changes.take(30).forEach { change ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(java.time.Instant.ofEpochMilli(change.createdAtEpochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", java.util.Locale("pl", "PL"))), Modifier.width(112.dp), style = MaterialTheme.typography.labelSmall)
                    Column(Modifier.weight(1f)) {
                        Text(change.description, style = MaterialTheme.typography.bodySmall)
                        Text(change.actorLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (createsNegative) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(confirmNegative, { confirmNegative = it })
                    Text("Realizacja utworzy stan ujemny — potwierdzam", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        if (lines.any { !it.isPrepared }) Text("Zaznacz przygotowanie każdej pozycji przed realizacją.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onRealize(employeeId, date) }, enabled = ready && (!createsNegative || confirmNegative), modifier = Modifier.fillMaxWidth()) { Text(if (employeeId == null && !order.siteLabel.isNullOrBlank()) "Zrealizuj i wydaj stoczni" else "Zrealizuj i wydaj") }
        TextButton(onClick = { confirmCancel = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Anuluj całe zamówienie") }
        Spacer(Modifier.height(16.dp))
    }
    editingLine?.let { line ->
        OrderLineDialog(line, products, onAddProductTags, onCreateProduct, { editingLine = null }) { product, quantity ->
            onUpdateLine(line.id, product?.id, product?.let { it.name + it.variant?.let { v -> " · $v" }.orEmpty() } ?: line.rawText, quantity, product?.unit ?: line.unit)
            editingLine = null
        }
    }
    if (showNewPerson) {
        NewOrderPersonDialog(
            jobPositions = jobPositions,
            onDismiss = { showNewPerson = false },
            onCreate = { first, last, phones, positions, aliases ->
                onCreatePerson(first, last, phones, positions, aliases) { id, fullName ->
                    employeeId = id
                    onUpdateOrder(order.id, id, fullName, date)
                    showNewPerson = false
                }
            },
        )
    }
    if (showPersonPicker) {
        OrderPersonPickerDialog(
            people = people,
            selectedId = employeeId,
            onDismiss = { showPersonPicker = false },
            onAddNew = { showPersonPicker = false; showNewPerson = true },
            onSelect = { person ->
                employeeId = person.id
                onUpdateOrder(order.id, person.id, person.fullName, date)
                showPersonPicker = false
            },
        )
    }
    if (showDatePicker) {
        val initial = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = { TextButton(onClick = {
            state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate().toString() }
            onUpdateOrder(order.id, employeeId, people.firstOrNull { it.id == employeeId }?.fullName ?: order.recipient, date)
            showDatePicker = false
        }) { Text("Wybierz") } }, dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Anuluj") } }) { DatePicker(state) }
    }
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false }, title = { Text("Anulować zamówienie?") },
        text = { Text("Zamówienie zniknie z aktywnej listy, ale pozostanie w bazie jako anulowane.") },
        confirmButton = { Button(onClick = onCancelOrder, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Anuluj zamówienie") } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Wróć") } },
    )
}

@Composable
private fun OrderPersonPickerDialog(
    people: List<EmployeeSummary>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onAddNew: () -> Unit,
    onSelect: (EmployeeSummary) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = people.filter { person ->
        query.isBlank() || pl.magazyn.mobile.domain.matchesSearch(
            query, person.fullName, person.aliases, person.tags, person.positions, person.phoneNumbers,
        )
    }.sortedWith(compareBy<EmployeeSummary> { it.lastName.lowercase() }.thenBy { it.firstName.lowercase() })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Przypisz osobę") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.fillMaxWidth().keepAboveKeyboard(),
                    label = { Text("Szukaj po nazwisku, imieniu lub ksywce") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                )
                FilledTonalButton(onClick = onAddNew, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Dodaj całkowicie nową osobę")
                }
                if (matches.isEmpty()) {
                    Text("Nie znaleziono osoby. Możesz dodać ją bez wychodzenia z zamówienia.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(matches, key = { it.id }) { person ->
                            OutlinedCard(
                                onClick = { onSelect(person) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = if (person.id == selectedId) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.outlinedCardColors(),
                            ) {
                                Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                                    Text(person.listDisplayName(), fontWeight = FontWeight.SemiBold)
                                    if (person.positions.isNotBlank()) Text(person.positions, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun NewOrderPersonDialog(jobPositions: List<pl.magazyn.mobile.data.JobPositionEntity>, onDismiss: () -> Unit, onCreate: (String, String, String, String, String) -> Unit) {
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    var phones by rememberSaveable { mutableStateOf("") }
    var positions by rememberSaveable { mutableStateOf("") }
    var aliases by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nowa osoba w zamówieniu") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedTextField(first, { first = it.filterNot(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Imię *") })
            OutlinedTextField(last, { last = it.filterNot(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Nazwisko *") })
            OutlinedTextField(phones, { phones = it }, Modifier.fillMaxWidth(), label = { Text("Telefony") })
            PositionSelector(jobPositions, positions, { positions = it })
            OutlinedTextField(aliases, { aliases = it }, Modifier.fillMaxWidth(), label = { Text("Ksywki/aliasy") })
        } },
        confirmButton = { Button(onClick = { onCreate(first, last, phones, positions, aliases) }, enabled = first.isNotBlank() && last.isNotBlank()) { Text("Dodaj i przypisz") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun OrderLineDialog(line: OrderDetailLine, products: List<ProductWithStock>, onAddProductTags: (String, String) -> Unit, onCreateProduct: (String, String, String, Long, String, (ProductWithStock) -> Unit) -> Unit, onDismiss: () -> Unit, onSave: (ProductWithStock?, Long) -> Unit) {
    var query by rememberSaveable(line.id) { mutableStateOf(line.productName ?: line.rawText) }
    var selectedId by rememberSaveable(line.id) { mutableStateOf(line.productId) }
    var quantity by rememberSaveable(line.id) { mutableStateOf(line.quantity.toLong().toString()) }
    var tags by rememberSaveable(line.id) { mutableStateOf("") }
    var addingProduct by remember { mutableStateOf(false) }
    var createdProduct by remember { mutableStateOf<ProductWithStock?>(null) }
    val availableProducts = createdProduct?.let { products + it } ?: products
    val tokens = query.split(Regex("\\s+")).filter(String::isNotBlank)
    val matches = availableProducts.map { product ->
        product to tokens.count { token -> pl.magazyn.mobile.domain.matchesSearch(token, product.name, product.variant.orEmpty(), product.aliases, product.tags, product.groupName, product.subgroupName, product.category) }
    }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }.take(8)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Popraw pozycję") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedTextField(query, { query = it; selectedId = null }, Modifier.fillMaxWidth(), label = { Text("Szukaj po nazwie, aliasie lub tagu") })
            if (matches.isEmpty()) {
                Text("Nie rozpoznano przedmiotu.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { addingProduct = true }) { Text("+ Dodaj nowy przedmiot bez wychodzenia") }
            }
            matches.forEach { product ->
                OutlinedCard(onClick = { selectedId = product.id; query = product.name + product.variant?.let { " · $it" }.orEmpty() }, Modifier.fillMaxWidth(), colors = if (selectedId == product.id) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.outlinedCardColors()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), Arrangement.SpaceBetween) { Text(product.name + product.variant?.let { " · $it" }.orEmpty(), Modifier.weight(1f)); Text("${formatWholeQuantity(product.stockQuantity)} ${product.unit}") }
                }
            }
            if (selectedId != null) {
                OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("Dopisz tagi do przedmiotu") })
                TextButton(onClick = { selectedId?.let { onAddProductTags(it, tags) }; tags = "" }, enabled = tags.isNotBlank()) { Text("Zapisz tagi") }
            }
            OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Ilość") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        } },
        confirmButton = { Button(onClick = { onSave(availableProducts.firstOrNull { it.id == selectedId }, quantity.toLongOrNull() ?: 1) }, enabled = selectedId != null && (quantity.toLongOrNull() ?: 0) > 0) { Text("Zapisz") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
    if (addingProduct) NewOrderProductDialog(query, { addingProduct = false }) { name, variant, unit, initialQuantity, newTags ->
        onCreateProduct(name, variant, unit, initialQuantity, newTags) { product ->
            createdProduct = product
            selectedId = product.id
            query = product.name + product.variant?.let { " · $it" }.orEmpty()
            addingProduct = false
        }
    }
}

@Composable
private fun NewOrderProductDialog(initialName: String, onDismiss: () -> Unit, onCreate: (String, String, String, Long, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var variant by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("szt.") }
    var quantity by rememberSaveable { mutableStateOf("0") }
    var tags by rememberSaveable { mutableStateOf("") }
    var unitMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nowy przedmiot w zamówieniu") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nazwa *") })
            OutlinedTextField(variant, { variant = it }, Modifier.fillMaxWidth(), label = { Text("Wariant") })
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { unitMenu = true }, Modifier.fillMaxWidth()) { Text("Jednostka: $unit") }
                DropdownMenu(unitMenu, { unitMenu = false }) { listOf("szt.", "para", "paczka", "opak.", "pudełko", "karton", "komplet").forEach { choice -> DropdownMenuItem({ Text(choice) }, { unit = choice; unitMenu = false }) } }
            }
            OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Stan początkowy") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("Tagi/aliasy") })
        } },
        confirmButton = { Button(onClick = { onCreate(name, variant, unit, quantity.toLongOrNull() ?: 0, tags) }, enabled = name.isNotBlank()) { Text("Dodaj i przypisz") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
