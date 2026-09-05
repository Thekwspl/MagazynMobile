package pl.magazyn.mobile.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Business
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.data.ShipyardStockItem
import pl.magazyn.mobile.data.StockExportFormat

private data class ShipyardLine(
    val key: String = UUID.randomUUID().toString(),
    val productId: String = "",
    val query: String = "",
    val quantity: String = "",
    val showSuggestions: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShipyardsScreen(
    contentPadding: PaddingValues,
    initialShipyardId: String? = null,
    onShipyard: ((String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    viewModel: ShipyardsViewModel = viewModel(),
    exportViewModel: ExportViewModel = viewModel(),
) {
    val context = LocalContext.current
    val shipyards by viewModel.shipyards.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val exportState by exportViewModel.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(initialShipyardId.orEmpty()) }
    var search by rememberSaveable { mutableStateOf("") }
    var newName by rememberSaveable { mutableStateOf("") }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var editShipyardId by rememberSaveable { mutableStateOf<String?>(null) }
    var manageDialog by remember { mutableStateOf(false) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmNegative by rememberSaveable { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var leadersDialog by remember { mutableStateOf(false) }
    var returnDialog by remember { mutableStateOf(false) }
    var exportFormat by rememberSaveable { mutableStateOf(StockExportFormat.XLSX.name) }
    val lines = remember { mutableStateListOf(ShipyardLine()) }
    val selected = shipyards.firstOrNull { it.id == selectedId }
    val shipyardStockFlow = remember(selectedId) {
        if (selectedId.isBlank()) flowOf(emptyList<ShipyardStockItem>()) else viewModel.stock(selectedId)
    }
    val shipyardStock by shipyardStockFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val leaderIdsFlow = remember(selectedId) {
        if (selectedId.isBlank()) flowOf(emptyList<String>()) else viewModel.leaderIds(selectedId)
    }
    val leaderIds by leaderIdsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val leaders = people.filter { it.id in leaderIds }
    val totals = lines.mapNotNull { it.quantity.toLongOrNull()?.let { quantity -> it.productId to quantity } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
    val createsNegative = totals.any { (id, quantity) -> products.firstOrNull { it.id == id }?.let { it.stockQuantity - quantity < 0 } == true }
    val valid = selected != null && lines.all { it.productId.isNotBlank() && (it.quantity.toLongOrNull() ?: 0L) > 0L }
    val visibleShipyards = shipyards.filter { search.isBlank() || it.name.contains(search, true) }
    val exportItems = shipyardStock.map { item ->
        ProductWithStock(item.productId, item.name, item.variant, item.unit, "", "", "", "", "", "", false, 0.0, 0, false, item.quantity, true)
    }

    if (selected == null) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(16.dp)) {
            Text("Stocznie", style = MaterialTheme.typography.headlineSmall)
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    search,
                    { search = it },
                    Modifier.weight(1f),
                    label = { Text("Wyszukaj stocznię") },
                    singleLine = true,
                )
                FilledIconButton(onClick = { manageDialog = true }) {
                    Icon(Icons.Default.Settings, "Dodaj, edytuj lub usuń stocznię")
                }
            }
            if (visibleShipyards.isEmpty()) {
                Text(if (search.isBlank()) "Brak stoczni. Użyj ikony ustawień, aby dodać pierwszą." else "Nie znaleziono stoczni.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(145.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    gridItems(visibleShipyards, key = { it.id }) { shipyard ->
                        ElevatedCard(
                            onClick = { onShipyard?.invoke(shipyard.id) ?: run { selectedId = shipyard.id } },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                        ) {
                            Column(
                                Modifier.fillMaxSize().padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Icon(Icons.Default.Business, null, tint = MaterialTheme.colorScheme.primary)
                                Text(shipyard.name, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        if (manageDialog) {
            ShipyardManagementDialog(
                shipyards = shipyards,
                newName = newName,
                onNewNameChange = { newName = it },
                editId = editShipyardId,
                onEdit = { editShipyardId = it },
                onAdd = { viewModel.addShipyard(newName); newName = "" },
                onRename = { id, name -> viewModel.renameShipyard(id, name); editShipyardId = null },
                onDelete = { pendingDeleteId = it },
                onDismiss = { manageDialog = false; editShipyardId = null },
            )
        }
        pendingDeleteId?.let { id ->
            val name = shipyards.firstOrNull { it.id == id }?.name ?: "tę stocznię"
            AlertDialog(
                onDismissRequest = { pendingDeleteId = null },
                title = { Text("Usunąć stocznię?") },
                text = { Text("$name zniknie z aktywnej listy. Dotychczasowa historia wydań zostanie zachowana.") },
                confirmButton = { TextButton(onClick = { viewModel.removeShipyard(id); pendingDeleteId = null }) { Text("Usuń") } },
                dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("Anuluj") } },
            )
        }
        return
    }
    val activeShipyard = selected

    LaunchedEffect(exportState.ready) {
        exportState.ready?.let { ready ->
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = ready.mimeType
                putExtra(Intent.EXTRA_STREAM, ready.uri)
                putExtra(Intent.EXTRA_SUBJECT, "Stan stoczni · ${activeShipyard.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Udostępnij dane stoczni"))
            exportViewModel.consumeReady()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(contentPadding).imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { back -> IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wróć do stoczni") } }
                Column(Modifier.weight(1f)) {
                    Text(activeShipyard.name, style = MaterialTheme.typography.headlineSmall)
                    Text("Wydanie i stan stoczni", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Wydaj przedmioty", style = MaterialTheme.typography.titleLarge)
                FilledTonalButton(onClick = { returnDialog = true }, enabled = shipyardStock.isNotEmpty()) { Text("Zwrot ze stoczni") }
            }
        }
        items(lines.size, key = { lines[it].key }) { index ->
            ShipyardProductLine(
                line = lines[index],
                products = products,
                canRemove = lines.size > 1,
                onChange = { lines[index] = it; confirmNegative = false },
                onRemove = { lines.removeAt(index); confirmNegative = false },
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDatePicker = true }, Modifier.weight(1f)) { Text(formatDisplayDate(date)) }
                OutlinedButton(onClick = { lines.add(ShipyardLine()) }, Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text("Dodaj przedmiot") }
            }
        }
        if (createsNegative) {
            item {
                Column {
                    Text("Co najmniej jedna pozycja utworzy stan ujemny.", color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(confirmNegative, { confirmNegative = it })
                        Text("Potwierdzam wydanie mimo braku stanu")
                    }
                }
            }
        }
        item {
            Button(
                onClick = {
                    viewModel.issue(activeShipyard, lines.map { ShipyardIssueRequest(it.productId, it.quantity.toLongOrNull() ?: 0L) }, date)
                    lines.clear()
                    lines.add(ShipyardLine())
                    confirmNegative = false
                },
                enabled = valid && (!createsNegative || confirmNegative),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Zapisz wydanie dla stoczni") }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Obecny stan magazynowy", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Prowadzący", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (leaders.isEmpty()) "Nie przypisano" else leaders.joinToString(", ") { it.listDisplayName() }, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { leadersDialog = true }) { Icon(Icons.Default.Edit, null); Text("Zmień") }
                    }
                    if (shipyardStock.isEmpty()) Text("Brak przedmiotów na stanie tej stoczni.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text("Przedmiot", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("Stan", fontWeight = FontWeight.SemiBold)
                        }
                        shipyardStock.forEach { stockItem ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stockItem.name + stockItem.variant?.let { " · $it" }.orEmpty(), Modifier.weight(1f))
                                Text("${formatWholeQuantity(stockItem.quantity)} ${stockItem.unit}", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = { exportDialog = true }, Modifier.fillMaxWidth()) { Text("Eksportuj dane") } }
    }
    if (showDatePicker) {
        val initial = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate().toString() }
                showDatePicker = false
            }) { Text("Wybierz") } },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Anuluj") } },
        ) { DatePicker(state) }
    }
    if (exportDialog) {
        val selectedFormat = StockExportFormat.valueOf(exportFormat)
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text("Eksport danych stoczni") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("${activeShipyard.name} · ${exportItems.size} pozycji")
                StockExportFormat.entries.forEach { format ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selectedFormat == format, { exportFormat = format.name })
                        Text(format.name)
                    }
                }
                exportState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(onClick = {
                exportViewModel.export("Stocznia ${activeShipyard.name}", exportItems, selectedFormat)
                exportDialog = false
            }, enabled = exportItems.isNotEmpty() && !exportState.working) { Text("Utwórz i udostępnij") } },
            dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("Anuluj") } },
        )
    }
    if (leadersDialog) {
        ShipyardLeadersDialog(
            shipyardName = activeShipyard.name,
            people = people,
            selectedIds = leaderIds.toSet(),
            onDismiss = { leadersDialog = false },
            onSave = { ids -> viewModel.saveLeaders(activeShipyard.id, ids); leadersDialog = false },
        )
    }
    if (returnDialog) {
        ShipyardReturnDialog(
            shipyard = activeShipyard,
            stock = shipyardStock,
            onDismiss = { returnDialog = false },
            onReturn = { items, returnDate ->
                viewModel.returnToMainWarehouse(activeShipyard, items, returnDate)
                returnDialog = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShipyardReturnDialog(
    shipyard: pl.magazyn.mobile.data.ShipyardEntity,
    stock: List<ShipyardStockItem>,
    onDismiss: () -> Unit,
    onReturn: (List<ShipyardIssueRequest>, String) -> Unit,
) {
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val quantities = remember { mutableStateMapOf<String, String>() }
    val requests = stock.mapNotNull { item -> quantities[item.productId]?.toLongOrNull()?.takeIf { it > 0 }?.let { ShipyardIssueRequest(item.productId, it) } }
    val valid = requests.isNotEmpty() && requests.all { request -> request.quantity <= (stock.firstOrNull { it.productId == request.productId }?.quantity ?: 0.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zwrot ze stoczni") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(shipyard.name, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { showDatePicker = true }, Modifier.fillMaxWidth()) { Text("Data zwrotu: ${formatDisplayDate(date)}") }
                stock.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name + item.variant?.let { " · $it" }.orEmpty())
                            Text("Na stoczni: ${formatWholeQuantity(item.quantity)} ${item.unit}", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedTextField(
                            quantities[item.productId].orEmpty(),
                            { value -> quantities[item.productId] = value.filter(Char::isDigit) },
                            Modifier.width(92.dp), label = { Text("Ilość") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = (quantities[item.productId]?.toLongOrNull() ?: 0L) > item.quantity,
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { Button(onClick = { onReturn(requests, date) }, enabled = valid) { Text("Zwróć do głównego") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
    if (showDatePicker) {
        val initial = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate().toString() }
                showDatePicker = false
            }) { Text("Wybierz") } },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Anuluj") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun ShipyardManagementDialog(
    shipyards: List<pl.magazyn.mobile.data.ShipyardEntity>,
    newName: String,
    onNewNameChange: (String) -> Unit,
    editId: String?,
    onEdit: (String?) -> Unit,
    onAdd: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zarządzaj stoczniami") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        newName,
                        onNewNameChange,
                        Modifier.weight(1f).keepAboveKeyboard(),
                        label = { Text("Nowa stocznia") },
                        singleLine = true,
                    )
                    FilledIconButton(onClick = onAdd, enabled = newName.isNotBlank()) { Icon(Icons.Default.Add, "Dodaj") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(shipyards, key = { it.id }) { shipyard ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(shipyard.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { onEdit(shipyard.id) }) { Icon(Icons.Default.Edit, "Edytuj ${shipyard.name}") }
                            IconButton(onClick = { onDelete(shipyard.id) }) { Icon(Icons.Default.DeleteOutline, "Usuń ${shipyard.name}") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Gotowe") } },
    )
    editId?.let { id ->
        val shipyard = shipyards.firstOrNull { it.id == id } ?: return@let
        var editedName by rememberSaveable(id) { mutableStateOf(shipyard.name) }
        AlertDialog(
            onDismissRequest = { onEdit(null) },
            title = { Text("Edytuj stocznię") },
            text = { OutlinedTextField(editedName, { editedName = it }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Nazwa") }, singleLine = true) },
            confirmButton = { Button(onClick = { onRename(id, editedName) }, enabled = editedName.isNotBlank()) { Text("Zapisz") } },
            dismissButton = { TextButton(onClick = { onEdit(null) }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun ShipyardLeadersDialog(
    shipyardName: String,
    people: List<pl.magazyn.mobile.data.EmployeeSummary>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val chosen = remember(selectedIds) { mutableStateMapOf<String, Boolean>().apply { selectedIds.forEach { put(it, true) } } }
    val visible = people.filter { person ->
        query.isBlank() || listOf(person.fullName, person.aliases, person.tags, person.positions).any { it.contains(query, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Prowadzący · $shipyardName") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Wyszukaj osobę") }, singleLine = true)
            Text("Możesz przypisać więcej niż jedną osobę.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(visible, key = { it.id }) { person ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(chosen[person.id] == true, { chosen[person.id] = it })
                        Column(Modifier.weight(1f)) {
                            Text(person.listDisplayName(), fontWeight = FontWeight.SemiBold)
                            if (person.positions.isNotBlank()) Text(person.positions, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        } },
        confirmButton = { Button(onClick = { onSave(chosen.filterValues { it }.keys) }) { Text("Zapisz") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun ShipyardProductLine(
    line: ShipyardLine,
    products: List<ProductWithStock>,
    canRemove: Boolean,
    onChange: (ShipyardLine) -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = line.query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val suggestions = products.filter { product ->
        tokens.isNotEmpty() && pl.magazyn.mobile.domain.matchesSearch(
            line.query, product.name, product.variant.orEmpty(), product.aliases,
            product.tags, product.category, product.groupName, product.subgroupName,
        )
    }.take(4)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Przedmiot", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                if (canRemove) IconButton(onClick = onRemove) { Icon(Icons.Default.DeleteOutline, "Usuń pozycję") }
            }
            OutlinedTextField(
                line.query,
                { onChange(line.copy(query = it, productId = "", showSuggestions = true)) },
                Modifier.fillMaxWidth().keepAboveKeyboard(),
                label = { Text("Nazwa lub wariant") },
                singleLine = true,
            )
            if (line.showSuggestions) suggestions.forEach { product ->
                OutlinedCard(onClick = {
                    onChange(line.copy(productId = product.id, query = product.name + product.variant?.let { " · $it" }.orEmpty(), showSuggestions = false))
                }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(9.dp)) {
                        Text(product.name + product.variant?.let { " · $it" }.orEmpty(), Modifier.weight(1f))
                        Text(if (product.stockKnown) "${formatWholeQuantity(product.stockQuantity)} ${product.unit}" else "stan ?", fontWeight = FontWeight.SemiBold)
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
