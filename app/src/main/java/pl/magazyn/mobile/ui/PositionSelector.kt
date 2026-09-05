package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.magazyn.mobile.data.JobPositionEntity
import pl.magazyn.mobile.domain.normalizeDisplayName

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PositionSelector(available: List<JobPositionEntity>, value: String, onValueChange: (String) -> Unit) {
    val selected = value.split(',').map(String::trim).filter(String::isNotBlank)
    val choices = (available.map { it.name } + selected).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    var custom by remember { mutableStateOf("") }
    Text("Stanowiska (opcjonalnie)", style = MaterialTheme.typography.labelLarge)
    if (available.isEmpty()) Text("Brak zapisanych stanowisk — dodaj pierwsze poniżej.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        choices.forEach { position ->
            val chosen = selected.any { it.equals(position, true) }
            FilterChip(
                selected = chosen,
                onClick = {
                    val changed = if (chosen) selected.filterNot { it.equals(position, true) } else selected + position
                    onValueChange(changed.distinctBy { it.lowercase() }.joinToString(", "))
                },
                label = { Text(position) },
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(custom, { custom = it }, Modifier.weight(1f), label = { Text("Nowe stanowisko") }, singleLine = true)
        FilledIconButton(onClick = {
            val name = normalizeDisplayName(custom)
            if (name.isNotBlank()) onValueChange((selected + name).distinctBy { it.lowercase() }.joinToString(", "))
            custom = ""
        }, enabled = custom.isNotBlank()) { Icon(Icons.Default.Add, "Dodaj stanowisko") }
    }
}
