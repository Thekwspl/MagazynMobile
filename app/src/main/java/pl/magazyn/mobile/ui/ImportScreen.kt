package pl.magazyn.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.magazyn.mobile.domain.ImportKind
import pl.magazyn.mobile.domain.StockImportRow

@Composable
fun ImportScreen(contentPadding: PaddingValues, showTitle: Boolean = true, viewModel: ImportViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::load)
    }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showTitle) Text("Import danych", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Zalecana kolejność: 1. Stan Magazynowy, 2. Osoby, 3. Wydanie Stocznie.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.loading,
        ) {
            Icon(Icons.Default.FileOpen, null)
            Text("Wybierz plik XLSX")
        }

        if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        state.error?.let { MessageCard(it, warning = true) }
        state.resultMessage?.let { MessageCard(it, warning = false) }

        state.preview?.let { preview ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(preview.kind.title, style = MaterialTheme.typography.titleLarge)
                    Text(preview.fileName, fontWeight = FontWeight.SemiBold)
                    HorizontalDivider()
                    SummaryLine("Wiersze danych", preview.totalRows.toString())
                    if (preview.kind == ImportKind.STOCK) {
                        val stockRows = preview.rows.filterIsInstance<StockImportRow>()
                        SummaryLine("Potwierdzony stan", stockRows.count { it.quantityKnown }.toString())
                        SummaryLine("Stan nieustalony", stockRows.count { !it.quantityKnown }.toString())
                    }
                    SummaryLine("Błędy", preview.errors.size.toString())
                    SummaryLine("Powtórzone wiersze", preview.duplicateRows.toString())
                    if (preview.kind != ImportKind.STOCK) {
                        SummaryLine(
                            if (preview.kind == ImportKind.SHIPYARDS) "Nowe produkty do utworzenia" else "Nazwy do mapowania",
                            preview.unresolvedProductNames.size.toString(),
                        )
                    }
                    if (preview.unresolvedProductNames.isNotEmpty()) {
                        Text("Przykłady nierozpoznanych nazw:", style = MaterialTheme.typography.labelMedium)
                        preview.unresolvedProductNames.take(6).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        Text(
                            if (preview.kind == ImportKind.SHIPYARDS) {
                                "Brakujące produkty zostaną utworzone automatycznie. Ich dane będzie można później poprawić."
                            } else {
                                "Zostaną zachowane w kolejce mapowania — nic nie zginie."
                            },
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    preview.errors.take(4).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (preview.alreadyImported) {
                        Text(
                            if (preview.kind == ImportKind.SHIPYARDS) "Ten plik był już importowany. Możesz bezpiecznie naprawić brakujące stocznie i pozycje."
                            else "Ten sam plik został już zaimportowany.",
                            color = if (preview.kind == ImportKind.SHIPYARDS) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!preview.alreadyImported || preview.kind == ImportKind.SHIPYARDS) {
                        Button(onClick = viewModel::confirmImport, modifier = Modifier.fillMaxWidth(), enabled = !state.loading) {
                            Text(if (preview.alreadyImported) "Napraw import stoczni" else "Zatwierdź import")
                        }
                    }
                    TextButton(onClick = viewModel::clearPreview, modifier = Modifier.align(Alignment.End)) { Text("Wybierz inny plik") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MessageCard(text: String, warning: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (warning) Icons.Default.Warning else Icons.Default.CheckCircle, null, tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(text)
        }
    }
}
