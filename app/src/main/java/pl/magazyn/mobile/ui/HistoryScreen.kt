package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.magazyn.mobile.data.HistoryEntry
import pl.magazyn.mobile.data.HistoryLine

private enum class HistoryFilter(val label: String) {
    ALL("Wszystko"), ISSUE("Wydania"), RECEIPT("Przyjęcia"), CORRECTION("Korekty"), SHIPYARD("Stocznie"), IMPORT("Importy")
}
private enum class HistoryDateField { FROM, TO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(contentPadding: PaddingValues, viewModel: HistoryViewModel = viewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(HistoryFilter.ALL.name) }
    var selected by remember { mutableStateOf<HistoryEntry?>(null) }
    var showAdvancedFilters by rememberSaveable { mutableStateOf(false) }
    var dateFrom by rememberSaveable { mutableStateOf("") }
    var dateTo by rememberSaveable { mutableStateOf("") }
    var warehouseFilter by rememberSaveable { mutableStateOf("") }
    var categoryFilter by rememberSaveable { mutableStateOf("") }
    var tagFilter by rememberSaveable { mutableStateOf("") }
    var datePickerFor by remember { mutableStateOf<HistoryDateField?>(null) }
    val filter = HistoryFilter.valueOf(filterName)
    val warehouses = entries.map { it.warehouseName }.filter(String::isNotBlank).distinct().sorted()
    val categories = entries.flatMap { it.categories.split(',') }.map(String::trim).filter(String::isNotBlank).distinct().sorted()
    val tags = entries.flatMap { it.tags.split(',') }.map(String::trim).filter(String::isNotBlank).distinct().sorted()
    val visible = entries.filter { entry ->
        matchesFilter(entry.type, filter) && pl.magazyn.mobile.domain.matchesSearch(
            query, entry.recipient, entry.itemSummary, entry.note, movementLabel(entry.type), entry.effectiveDate, entry.warehouseName, entry.categories, entry.tags,
        ) && (dateFrom.isBlank() || entry.effectiveDate >= dateFrom)
            && (dateTo.isBlank() || entry.effectiveDate <= dateTo)
            && (warehouseFilter.isBlank() || entry.warehouseName == warehouseFilter)
            && (categoryFilter.isBlank() || entry.categories.split(',').any { it.trim().equals(categoryFilter, true) })
            && (tagFilter.isBlank() || entry.tags.split(',').any { it.trim().equals(tagFilter, true) })
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Text("Historia", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("Szukaj w historii") },
            placeholder = { Text("Osoba, stocznia, przedmiot lub typ ruchu") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(HistoryFilter.entries) { choice ->
                FilterChip(selected = filter == choice, onClick = { filterName = choice.name }, label = { Text(choice.label) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            TextButton(onClick = { showAdvancedFilters = !showAdvancedFilters }) {
                Icon(Icons.Default.Tune, null)
                Spacer(Modifier.width(5.dp))
                Text(if (showAdvancedFilters) "Ukryj filtry" else "Więcej filtrów")
            }
            if (dateFrom.isNotBlank() || dateTo.isNotBlank() || warehouseFilter.isNotBlank() || categoryFilter.isNotBlank() || tagFilter.isNotBlank()) {
                TextButton(onClick = { dateFrom = ""; dateTo = ""; warehouseFilter = ""; categoryFilter = ""; tagFilter = "" }) { Text("Wyczyść") }
            }
        }
        if (showAdvancedFilters) {
            HistoryAdvancedFilters(
                dateFrom, dateTo, warehouseFilter, categoryFilter, tagFilter,
                warehouses, categories, tags,
                onPickFrom = { datePickerFor = HistoryDateField.FROM },
                onPickTo = { datePickerFor = HistoryDateField.TO },
                onWarehouse = { warehouseFilter = it },
                onCategory = { categoryFilter = it },
                onTag = { tagFilter = it },
            )
            if (dateFrom.isNotBlank() && dateTo.isNotBlank() && dateFrom > dateTo) {
                Text("Data początkowa jest późniejsza niż końcowa.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("${visible.size} operacji", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (visible.isEmpty()) item { Text("Brak operacji pasujących do wybranych filtrów.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(visible, key = { it.id }) { entry -> HistoryCard(entry, onClick = { selected = entry }) }
        }
    }

    selected?.let { entry ->
        val lines by viewModel.lines(entry.id).collectAsStateWithLifecycle(initialValue = emptyList())
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            HistoryDetails(entry, lines, onClose = { selected = null })
        }
    }
    datePickerFor?.let { target ->
        val current = if (target == HistoryDateField.FROM) dateFrom else dateTo
        val initial = runCatching { java.time.LocalDate.parse(current) }.getOrDefault(java.time.LocalDate.now())
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { datePickerFor = null },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    val value = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.of("UTC")).toLocalDate().toString()
                    if (target == HistoryDateField.FROM) dateFrom = value else dateTo = value
                }
                datePickerFor = null
            }) { Text("Wybierz") } },
            dismissButton = { TextButton(onClick = { datePickerFor = null }) { Text("Anuluj") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun HistoryAdvancedFilters(
    dateFrom: String,
    dateTo: String,
    warehouse: String,
    category: String,
    tag: String,
    warehouses: List<String>,
    categories: List<String>,
    tags: List<String>,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onWarehouse: (String) -> Unit,
    onCategory: (String) -> Unit,
    onTag: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedButton(onClick = onPickFrom, Modifier.weight(1f)) { Text(if (dateFrom.isBlank()) "Od daty" else "Od ${formatDisplayDate(dateFrom)}", maxLines = 1) }
            OutlinedButton(onClick = onPickTo, Modifier.weight(1f)) { Text(if (dateTo.isBlank()) "Do daty" else "Do ${formatDisplayDate(dateTo)}", maxLines = 1) }
        }
        HistoryFilterDropdown("Magazyn", warehouse, warehouses, onWarehouse)
        HistoryFilterDropdown("Kategoria", category, categories, onCategory)
        HistoryFilterDropdown("Tag", tag, tags, onTag)
    }
}

@Composable
private fun HistoryFilterDropdown(label: String, value: String, choices: List<String>, onValue: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) { Text(if (value.isBlank()) "$label: wszystkie" else "$label: $value") }
        DropdownMenu(expanded, { expanded = false }, Modifier.fillMaxWidth(0.9f)) {
            DropdownMenuItem(text = { Text("Wszystkie") }, onClick = { onValue(""); expanded = false })
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { onValue(choice); expanded = false }) }
        }
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry, onClick: () -> Unit) {
    val colors = movementColors(entry.type)
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Surface(color = colors.first, contentColor = colors.second, shape = MaterialTheme.shapes.small) {
                    Text(movementLabel(entry.type), Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDisplayDate(entry.effectiveDate), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.ChevronRight, "Szczegóły", Modifier.size(18.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                if (entry.recipient.isNotBlank()) Text(entry.recipient, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text("${entry.lineCount} poz.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                entry.itemSummary.ifBlank { "Operacja bez pozycji" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HistoryDetails(entry: HistoryEntry, lines: List<HistoryLine>, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text(movementLabel(entry.type), style = MaterialTheme.typography.titleLarge)
                Text(formatDisplayDate(entry.effectiveDate), style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Zamknij") }
        }
        if (entry.recipient.isNotBlank()) DetailField("Odbiorca", entry.recipient)
        if (entry.note.isNotBlank()) DetailField("Informacja", entry.note)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text("Przedmiot", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text("Zmiana", fontWeight = FontWeight.SemiBold)
        }
        lines.forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(line.productName + line.variant?.let { " · $it" }.orEmpty(), Modifier.weight(1f))
                val positive = line.quantityDelta >= 0
                Text(
                    (if (positive) "+" else "") + formatWholeQuantity(line.quantityDelta) + " " + line.unit,
                    color = if (positive) Color(0xFF177245) else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        if (lines.isEmpty()) Text("Brak pozycji w tej operacji.")
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

private fun matchesFilter(type: String, filter: HistoryFilter): Boolean = when (filter) {
    HistoryFilter.ALL -> true
    HistoryFilter.ISSUE -> type == "ISSUE"
    HistoryFilter.RECEIPT -> type in setOf("DELIVERY", "RETURN", "FOUND", "SHIPYARD_RETURN")
    HistoryFilter.CORRECTION -> type.contains("CORRECTION") || type.contains("INVENTORY")
    HistoryFilter.SHIPYARD -> type.contains("SHIPYARD")
    HistoryFilter.IMPORT -> type.contains("IMPORT")
}

private fun movementLabel(type: String): String = when (type) {
    "ISSUE" -> "Wydanie osobie"
    "SHIPYARD_ISSUE" -> "Wydanie stoczni"
    "DELIVERY" -> "Dostawa"
    "RETURN" -> "Zwrot od osoby"
    "SHIPYARD_RETURN" -> "Zwrot ze stoczni"
    "FOUND" -> "Znalezione"
    "TRANSFER" -> "Przekazanie stoczni"
    "INVENTORY_CORRECTION" -> "Inwentaryzacja"
    "STOCK_CORRECTION" -> "Korekta stanu"
    "ISSUE_CORRECTION" -> "Korekta wydania"
    "HISTORICAL_ISSUE_IMPORT" -> "Import historyczny"
    "HISTORICAL_SHIPYARD_IMPORT" -> "Import stoczni"
    else -> type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun movementColors(type: String): Pair<Color, Color> = when {
    type.contains("CORRECTION") || type.contains("INVENTORY") -> Color(0xFFFFE0B2) to Color(0xFF6D3B00)
    type.contains("IMPORT") -> Color(0xFFE8DEF8) to Color(0xFF3E2A5B)
    type.contains("SHIPYARD") -> Color(0xFFD8EAF2) to Color(0xFF17324A)
    type == "ISSUE" -> Color(0xFFFFDAD6) to Color(0xFF6B1711)
    else -> Color(0xFFD9F2E4) to Color(0xFF164B32)
}
