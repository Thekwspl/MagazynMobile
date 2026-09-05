package pl.magazyn.mobile.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.flow.flowOf
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.data.StockExportFormat

@Composable
fun ExportScreen(contentPadding: PaddingValues, showTitle: Boolean = true, viewModel: ExportViewModel = viewModel()) {
    val context = LocalContext.current
    val warehouses by viewModel.warehouses.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var warehouseId by rememberSaveable { mutableStateOf("") }
    var formatName by rememberSaveable { mutableStateOf(StockExportFormat.XLSX.name) }
    var warehouseMenu by remember { mutableStateOf(false) }
    LaunchedEffect(warehouses) { if (warehouseId.isBlank()) warehouseId = warehouses.firstOrNull { it.isMain }?.id ?: warehouses.firstOrNull()?.id.orEmpty() }
    val productsFlow = remember(warehouseId) { if (warehouseId.isBlank()) flowOf(emptyList<ProductWithStock>()) else viewModel.products(warehouseId) }
    val products by productsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val warehouse = warehouses.firstOrNull { it.id == warehouseId }
    val format = StockExportFormat.valueOf(formatName)

    LaunchedEffect(state.ready) {
        state.ready?.let { ready ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = ready.mimeType
                putExtra(Intent.EXTRA_STREAM, ready.uri)
                putExtra(Intent.EXTRA_SUBJECT, "Stan magazynowy · ${warehouse?.name.orEmpty()}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Udostępnij stan magazynowy"))
            viewModel.consumeReady()
        }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showTitle) Text("Eksport stanu", style = MaterialTheme.typography.headlineSmall)
        Text("Utwórz plik dla przełożonego i wyślij go przez dowolną aplikację w telefonie.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { warehouseMenu = true }, Modifier.fillMaxWidth()) { Text(warehouse?.name ?: "Wybierz magazyn") }
            DropdownMenu(warehouseMenu, { warehouseMenu = false }) {
                warehouses.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { warehouseId = item.id; warehouseMenu = false }) }
            }
        }
        Text("Format", style = MaterialTheme.typography.titleMedium)
        StockExportFormat.entries.forEach { choice ->
            OutlinedCard(onClick = { formatName = choice.name }, Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    RadioButton(format == choice, { formatName = choice.name })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(choice.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (choice) {
                                StockExportFormat.XLSX -> "Arkusz dla Excel, LibreOffice i Google Sheets"
                                StockExportFormat.CSV -> "Prosty plik tekstowy zgodny z Excelem"
                                StockExportFormat.PDF -> "Czytelny raport do wysłania, przeglądania lub wydruku"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("${products.size} pozycji", style = MaterialTheme.typography.titleMedium)
                Text("Eksport zawiera nazwę, wariant, grupę, podgrupę, kategorię, stan i jednostkę.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            onClick = { warehouse?.let { viewModel.export(it.name, products, format) } },
            enabled = warehouse != null && products.isNotEmpty() && !state.working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Share, null)
            Spacer(Modifier.width(7.dp))
            Text(if (state.working) "Przygotowuję…" else "Utwórz i udostępnij ${format.name}")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
