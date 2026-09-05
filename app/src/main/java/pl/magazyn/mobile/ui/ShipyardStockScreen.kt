package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.flowOf
import pl.magazyn.mobile.data.ShipyardStockItem

@Composable
fun ShipyardStockScreen(
    contentPadding: PaddingValues,
    onIssue: () -> Unit,
    viewModel: ShipyardsViewModel = viewModel(),
) {
    val shipyards by viewModel.shipyards.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf("") }
    val selected = shipyards.firstOrNull { it.id == selectedId }
    val stockFlow = remember(selectedId) {
        if (selectedId.isBlank()) flowOf(emptyList<ShipyardStockItem>()) else viewModel.stock(selectedId)
    }
    val stock by stockFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    LazyColumn(
        Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Stocznie", style = MaterialTheme.typography.headlineSmall) }
        if (shipyards.isEmpty()) item { Text("Nie dodano jeszcze żadnej stoczni.") }
        items(shipyards.size, key = { shipyards[it].id }) { index ->
            val shipyard = shipyards[index]
            OutlinedCard(onClick = { selectedId = shipyard.id }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Business, null)
                    Text(shipyard.name, Modifier.padding(start = 10.dp).weight(1f), fontWeight = FontWeight.SemiBold)
                    RadioButton(selected = selectedId == shipyard.id, onClick = { selectedId = shipyard.id })
                }
            }
        }
        selected?.let { shipyard ->
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Na stanie: ${shipyard.name}", style = MaterialTheme.typography.titleMedium)
                        if (stock.isEmpty()) Text("Brak przedmiotów na stanie.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        stock.forEach { item ->
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), Arrangement.SpaceBetween) {
                                Text(item.name + item.variant?.let { " · $it" }.orEmpty(), Modifier.weight(1f))
                                Text("${formatWholeQuantity(item.quantity)} ${item.unit}", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Button(onClick = onIssue, modifier = Modifier.fillMaxWidth()) { Text("Przejdź do wydania stoczni") }
                    }
                }
            }
        }
    }
}
