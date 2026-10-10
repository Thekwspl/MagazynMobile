package pl.magazyn.mobile.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import pl.magazyn.mobile.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(contentPadding: PaddingValues, showTitle: Boolean = true, viewModel: ExportViewModel = viewModel()) {
    val context = LocalContext.current
    val shipyards by viewModel.shipyards.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var reportName by rememberSaveable { mutableStateOf(ExportReportType.STOCK.name) }
    var sourceName by rememberSaveable { mutableStateOf(StockReportSource.MAIN.name) }
    var shipyardId by rememberSaveable { mutableStateOf("") }
    var dateFrom by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var dateTo by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var formatName by rememberSaveable { mutableStateOf(StockExportFormat.XLSX.name) }
    var pickFrom by remember { mutableStateOf<Boolean?>(null) }
    val selection = ExportSelection(ExportReportType.valueOf(reportName), StockReportSource.valueOf(sourceName), shipyardId, dateFrom, dateTo)
    val previewFlow = remember(selection) { viewModel.preview(selection) }
    val observedPreview by previewFlow.collectAsStateWithLifecycle(initialValue = ExportPreview(selection))
    // collectAsState może przez jedną klatkę zachować poprzednią wartość po zmianie Flow.
    val preview = observedPreview.forSelection(selection)
    val format = StockExportFormat.valueOf(formatName)

    LaunchedEffect(state.ready) {
        state.ready?.let { ready ->
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = ready.mimeType
                    putExtra(Intent.EXTRA_STREAM, ready.uri)
                    putExtra(Intent.EXTRA_SUBJECT, ready.subject)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Udostępnij raport"))
                viewModel.consumeReady()
            } catch (error: Exception) { viewModel.shareFailed(error) }
        }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showTitle) Text("Eksport danych", style = MaterialTheme.typography.headlineSmall)
        Text("Utwórz raport i udostępnij go przez dowolną aplikację w telefonie.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ExportMenu("Rodzaj raportu", selection.report.label, ExportReportType.entries.map { it.name to it.label }, !state.working) { reportName = it }
        if (selection.report == ExportReportType.STOCK) {
            ExportMenu("Źródło danych", selection.source.label, StockReportSource.entries.map { it.name to it.label }, !state.working) { sourceName = it }
            if (selection.source == StockReportSource.SHIPYARD) {
                ExportMenu("Stocznia", shipyards.firstOrNull { it.id == shipyardId }?.name ?: "Wybierz stocznię", shipyards.map { it.id to it.name }, !state.working) { shipyardId = it }
                if (shipyards.isEmpty()) Text("Brak aktywnych stoczni.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickFrom = true }, modifier = Modifier.weight(1f), enabled = !state.working) {
                    Column { Text("Data od"); Text(formatDisplayDate(dateFrom)) }
                }
                OutlinedButton(onClick = { pickFrom = false }, modifier = Modifier.weight(1f), enabled = !state.working) {
                    Column { Text("Data do"); Text(formatDisplayDate(dateTo)) }
                }
            }
        }
        Text("Format", style = MaterialTheme.typography.titleMedium)
        StockExportFormat.entries.forEach { choice ->
            OutlinedCard(onClick = { formatName = choice.name }, modifier = Modifier.fillMaxWidth(), enabled = !state.working) {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    RadioButton(format == choice, { formatName = choice.name }, enabled = !state.working)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(choice.name, fontWeight = FontWeight.SemiBold)
                        Text(when (choice) {
                            StockExportFormat.XLSX -> "Arkusz dla Excel, LibreOffice i Google Sheets"
                            StockExportFormat.CSV -> "Prosty plik tekstowy zgodny z Excelem"
                            StockExportFormat.PDF -> "Raport do przeglądania lub wydruku"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (preview.loading) "Wczytuję dane…" else if (selection.report == ExportReportType.STOCK) "${preview.rowCount} pozycji"
                    else "${preview.deliveryCount} dostaw · ${preview.rowCount} pozycji", style = MaterialTheme.typography.titleMedium)
                if (preview.title.isNotBlank()) Text(preview.title, style = MaterialTheme.typography.bodySmall)
                if (!preview.loading && preview.error == null && preview.rowCount == 0) {
                    Text(if (selection.report == ExportReportType.STOCK) "Brak pozycji dla wybranego źródła." else "Brak dostaw w wybranym okresie.")
                }
                if (selection.report == ExportReportType.STOCK) Text("Nazwa, wariant, grupa, podgrupa, kategoria, stan i jednostka.", style = MaterialTheme.typography.bodySmall)
                else Text("Daty dostaw, wszystkie pozycje, ilości, magazyny, uwagi i powiązanie z operacją.", style = MaterialTheme.typography.bodySmall)
                preview.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        Button(onClick = { viewModel.export(selection, format) }, enabled = preview.canExport && !state.working, modifier = Modifier.fillMaxWidth()) {
            if (state.working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Share, null)
            Spacer(Modifier.width(7.dp))
            Text(if (state.working) "Przygotowuję…" else "Utwórz i udostępnij ${format.name}")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    pickFrom?.let { from ->
        val initial = LocalDate.parse(if (from) dateFrom else dateTo)
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickFrom = null },
            confirmButton = { TextButton(onClick = {
                picker.selectedDateMillis?.let { millis ->
                    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate().toString()
                    if (from) dateFrom = date else dateTo = date
                }
                pickFrom = null
            }) { Text("Wybierz") } },
            dismissButton = { TextButton(onClick = { pickFrom = null }) { Text("Anuluj") } },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun ExportMenu(label: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = enabled && choices.isNotEmpty()) { Text(value) }
            DropdownMenu(expanded, { expanded = false }) {
                choices.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false }) }
            }
        }
    }
}
