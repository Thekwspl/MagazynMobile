package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import kotlinx.coroutines.flow.flowOf
import pl.magazyn.mobile.data.ProductWithStock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(contentPadding: PaddingValues, viewModel: InventoryViewModel = viewModel()) {
    val warehouses by viewModel.warehouses.collectAsStateWithLifecycle()
    var warehouseId by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var warehouseMenu by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val entered = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(warehouses) {
        if (warehouseId.isBlank()) warehouseId = warehouses.firstOrNull { it.isMain }?.id ?: warehouses.firstOrNull()?.id.orEmpty()
    }
    val productsFlow = remember(warehouseId) { if (warehouseId.isBlank()) flowOf(emptyList<ProductWithStock>()) else viewModel.products(warehouseId) }
    val products by productsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val tokens = query.split(Regex("\\s+")).filter(String::isNotBlank)
    val visible = products.filter { product ->
        pl.magazyn.mobile.domain.matchesSearch(query, product.name, product.variant.orEmpty(), product.category, product.groupName, product.subgroupName, product.aliases, product.tags)
    }
    val counts = entered.mapNotNull { (id, text) -> text.toLongOrNull()?.let { InventoryCount(id, it) } }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Text("Inwentaryzacja", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { warehouseMenu = true }, Modifier.fillMaxWidth()) {
                    Text(warehouses.firstOrNull { it.id == warehouseId }?.name ?: "Wybierz magazyn")
                }
                DropdownMenu(warehouseMenu, { warehouseMenu = false }) {
                    warehouses.forEach { warehouse -> DropdownMenuItem(text = { Text(warehouse.name) }, onClick = {
                        warehouseId = warehouse.id; entered.clear(); warehouseMenu = false; saved = false
                    }) }
                }
            }
            OutlinedButton(onClick = { showDatePicker = true }) { Text(formatDisplayDate(date)) }
        }
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("Szukaj przedmiotu") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
        )
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Przedmiot", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("Stan", Modifier.width(65.dp), fontWeight = FontWeight.SemiBold)
                Text("Policzono", Modifier.width(92.dp), fontWeight = FontWeight.SemiBold)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(visible, key = { it.id }) { product ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(product.name + product.variant?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        val input = entered[product.id]?.toLongOrNull()
                        if (input != null) {
                            val diff = input - product.stockQuantity.toLong()
                            Text("Różnica: ${if (diff >= 0) "+" else ""}$diff", style = MaterialTheme.typography.labelSmall, color = if (diff == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(if (product.stockKnown) formatWholeQuantity(product.stockQuantity) else "?", Modifier.width(65.dp))
                    OutlinedTextField(
                        entered[product.id].orEmpty(),
                        { value -> entered[product.id] = value.filter(Char::isDigit); confirmSave = false; saved = false },
                        Modifier.width(92.dp).keepAboveKeyboard(),
                        placeholder = { Text(formatWholeQuantity(product.stockQuantity)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    )
                }
                HorizontalDivider()
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Uzupełniono: ${counts.size} pozycji", style = MaterialTheme.typography.labelMedium)
            if (!confirmSave) {
                Button(onClick = { confirmSave = true }, enabled = counts.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Sprawdź i zatwierdź") }
            } else {
                Text("Zapiszesz policzone stany. Puste pola pozostaną bez zmian.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirmSave = false }, Modifier.weight(1f)) { Text("Wróć") }
                    Button(onClick = {
                        viewModel.applyInventory(warehouseId, counts, date)
                        entered.clear(); confirmSave = false; saved = true
                    }, Modifier.weight(1f)) { Text("Zapisz") }
                }
            }
            if (saved) Text("Inwentaryzacja została zapisana w historii.", color = MaterialTheme.colorScheme.primary)
        }
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
