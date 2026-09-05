package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
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
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.data.ShipyardStockItem

private data class OperationDraftLine(
    val key: String = UUID.randomUUID().toString(),
    val productId: String = "",
    val query: String = "",
    val quantity: String = "",
    val suggestionsVisible: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationsScreen(contentPadding: PaddingValues, viewModel: OperationsViewModel = viewModel()) {
    val warehouses by viewModel.warehouses.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val shipyards by viewModel.shipyards.collectAsStateWithLifecycle()
    var typeName by rememberSaveable { mutableStateOf(WarehouseOperationType.DELIVERY.name) }
    val type = WarehouseOperationType.valueOf(typeName)
    var destinationId by rememberSaveable { mutableStateOf("") }
    var employeeId by rememberSaveable { mutableStateOf("") }
    var shipyardId by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmNegative by rememberSaveable { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    val lines = remember { mutableStateListOf(OperationDraftLine()) }

    LaunchedEffect(warehouses) {
        if (destinationId.isBlank()) destinationId = warehouses.firstOrNull { it.isMain }?.id ?: warehouses.firstOrNull()?.id.orEmpty()
    }
    val stockWarehouseId = destinationId
    val productsFlow = remember(stockWarehouseId) {
        if (stockWarehouseId.isBlank()) flowOf(emptyList<ProductWithStock>()) else viewModel.products(stockWarehouseId)
    }
    val products by productsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val yardStockFlow = remember(shipyardId) {
        if (shipyardId.isBlank()) flowOf(emptyList<ShipyardStockItem>()) else viewModel.shipyardStock(shipyardId)
    }
    val yardStock by yardStockFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val requested = lines.mapNotNull { line -> line.quantity.toLongOrNull()?.let { line.productId to it } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
    val createsNegative = when (type) {
        WarehouseOperationType.SHIPYARD_RETURN -> requested.any { (id, qty) -> (yardStock.firstOrNull { it.productId == id }?.quantity ?: 0.0) - qty < 0 }
        else -> false
    }
    val selectorsValid = destinationId.isNotBlank() && when (type) {
        WarehouseOperationType.SHIPYARD_RETURN -> shipyardId.isNotBlank()
        else -> true
    }
    val linesValid = lines.isNotEmpty() && lines.all { it.productId.isNotBlank() && (it.quantity.toLongOrNull() ?: 0) > 0 }

    LazyColumn(
        Modifier.fillMaxSize().padding(contentPadding).imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Operacje magazynowe", style = MaterialTheme.typography.headlineSmall) }
        item {
            OperationTypeSelector(type) {
                typeName = it.name
                lines.clear()
                lines.add(OperationDraftLine())
                confirmNegative = false
                savedMessage = null
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Magazyn główny", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Towar trafi do magazynu głównego.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (type == WarehouseOperationType.SHIPYARD_RETURN) {
                        ChoiceField("Stocznia", shipyards, shipyardId, { it.id }, { it.name }) { shipyardId = it; confirmNegative = false }
                    }
                }
            }
        }
        items(lines.size, key = { lines[it].key }) { index ->
            OperationProductLine(
                line = lines[index],
                products = products,
                yardStock = if (type == WarehouseOperationType.SHIPYARD_RETURN) yardStock else emptyList(),
                canRemove = lines.size > 1,
                onChange = { lines[index] = it; confirmNegative = false },
                onRemove = { lines.removeAt(index); confirmNegative = false },
            )
        }
        item {
            OutlinedButton(onClick = { lines.add(OperationDraftLine()) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Text("Dodaj kolejny przedmiot")
            }
        }
        item { OutlinedButton(onClick = { showDatePicker = true }, Modifier.fillMaxWidth()) { Text("Data operacji: ${formatDisplayDate(date)}") } }
        if (createsNegative) item {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(10.dp)) {
                    Text("Ta operacja spowoduje stan ujemny.", color = MaterialTheme.colorScheme.onErrorContainer)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(confirmNegative, { confirmNegative = it })
                        Text("Potwierdzam wykonanie mimo braku stanu", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        item {
            Button(
                onClick = {
                    viewModel.submit(
                        type, employeeId.ifBlank { null }, shipyardId.ifBlank { null },
                        lines.map { OperationLineRequest(it.productId, it.quantity.toLongOrNull() ?: 0) }, date,
                    )
                    lines.clear(); lines.add(OperationDraftLine()); confirmNegative = false
                    savedMessage = "Zapisano: ${type.label}"
                },
                enabled = selectorsValid && linesValid && (!createsNegative || confirmNegative),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Zatwierdź operację") }
        }
        savedMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) } }
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
}

@Composable
private fun OperationTypeSelector(selected: WarehouseOperationType, onSelect: (WarehouseOperationType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        WarehouseOperationType.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { type ->
                    FilterChip(selected == type, { onSelect(type) }, { Text(type.label) }, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun <T> ChoiceField(label: String, choices: List<T>, selectedId: String, id: (T) -> String, text: (T) -> String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) {
            Text(choices.firstOrNull { id(it) == selectedId }?.let(text) ?: label, Modifier.weight(1f))
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.fillMaxWidth(0.9f)) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(text(choice)) }, onClick = { onSelect(id(choice)); expanded = false }) }
        }
    }
}

@Composable
private fun OperationProductLine(
    line: OperationDraftLine,
    products: List<ProductWithStock>,
    yardStock: List<ShipyardStockItem>,
    canRemove: Boolean,
    onChange: (OperationDraftLine) -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = line.query.split(Regex("\\s+")).filter(String::isNotBlank)
    val matches = products.filter { product ->
        tokens.isNotEmpty() && pl.magazyn.mobile.domain.matchesSearch(
            line.query, product.name, product.variant.orEmpty(), product.aliases,
            product.tags, product.groupName, product.subgroupName,
        )
    }.take(5)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pozycja", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                if (canRemove) IconButton(onClick = onRemove) { Icon(Icons.Default.DeleteOutline, "Usuń pozycję") }
            }
            OutlinedTextField(
                line.query,
                { onChange(line.copy(query = it, productId = "", suggestionsVisible = true)) },
                Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Przedmiot") }, singleLine = true,
            )
            if (line.suggestionsVisible) matches.forEach { product ->
                OutlinedCard(onClick = {
                    onChange(line.copy(productId = product.id, query = product.name + product.variant?.let { " · $it" }.orEmpty(), suggestionsVisible = false))
                }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), Arrangement.SpaceBetween) {
                        Text(product.name + product.variant?.let { " · $it" }.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        val yardQuantity = yardStock.firstOrNull { it.productId == product.id }?.quantity
                        Text(
                            if (yardQuantity != null) "stocznia: ${formatWholeQuantity(yardQuantity)} ${product.unit}"
                            else if (product.stockKnown) "${formatWholeQuantity(product.stockQuantity)} ${product.unit}" else "stan ?",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            OutlinedTextField(
                line.quantity,
                { onChange(line.copy(quantity = it.filter(Char::isDigit))) },
                Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Ilość") }, placeholder = { Text("1") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
            )
        }
    }
}
