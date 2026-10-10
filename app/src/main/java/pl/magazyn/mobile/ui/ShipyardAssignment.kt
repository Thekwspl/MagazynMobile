package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.magazyn.mobile.data.ShipyardEntity
import pl.magazyn.mobile.data.ShipyardLeaderLink
import pl.magazyn.mobile.data.ShipyardResolver

@Composable
internal fun ShipyardAssignment(
    assignedId: String?, historicalLabel: String, shipyards: List<ShipyardEntity>,
    leaders: List<ShipyardLeaderLink>, onAssign: (String) -> Unit,
) {
    val resolved = remember(assignedId, historicalLabel, shipyards, leaders) {
        ShipyardResolver.resolve(assignedId, historicalLabel, shipyards, leaders)
    }
    var editing by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<String?>(null) }
    val current = assignedId?.let { id -> shipyards.firstOrNull { it.id == id }?.name }
    Text("Stocznia: ${current ?: "nieprzypisana"}")
    if (assignedId == null && resolved.candidates.isNotEmpty())
        Text("Sugestia: ${resolved.candidates.mapNotNull { id -> shipyards.firstOrNull { it.id == id }?.name }.joinToString()}. Potwierdź ręcznie.")
    TextButton(onClick = { chosen = assignedId; editing = true }) { Text("Popraw przypisanie stoczni") }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("Wybierz stocznię") },
        text = { Column {
            Text("Historyczna nazwa: $historicalLabel")
            LazyColumn(Modifier.heightIn(max = 380.dp)) { items(shipyards, key = { it.id }) { yard ->
                TextButton(onClick = { chosen = yard.id }, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = chosen == yard.id, onClick = { chosen = yard.id })
                    Text(yard.name + if (yard.id in resolved.candidates) " (sugerowana)" else "")
                }
            } }
        } },
        confirmButton = { Button(onClick = {
            chosen?.let(onAssign)
            editing = false
        }, enabled = chosen != null) { Text("Potwierdź przypisanie") } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Anuluj") } },
    )
}
