package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemovableValueChips(
    values: List<String>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        values.map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase() }.forEach { value ->
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(value, Modifier.padding(start = 9.dp), style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { onRemove(value) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Usuń $value", Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

/**
 * Wspólny interfejs edycji krótkich wartości. Warstwa danych nadal decyduje,
 * czy dodanie się powiodło, przekazując wynik przez callback [done].
 */
@Composable
fun EditableChipInput(
    items: List<String>,
    label: String,
    onAdd: (value: String, done: (Boolean) -> Unit) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun cleaned(values: List<String>) = values.map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase() }

    var value by remember { mutableStateOf("") }
    var visibleItems by remember { mutableStateOf(cleaned(items)) }
    LaunchedEffect(items) { visibleItems = cleaned(items) }

    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.weight(3f).keepAboveKeyboard(),
                label = { Text(label) },
                singleLine = true,
            )
            Button(
                onClick = {
                    val candidate = value.trim()
                    onAdd(candidate) { success ->
                        if (success) {
                            visibleItems = cleaned(visibleItems + candidate)
                            value = ""
                        }
                    }
                },
                enabled = value.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("Dodaj") }
        }
        RemovableValueChips(
            values = visibleItems,
            onRemove = { removed ->
                visibleItems = visibleItems.filterNot { it.equals(removed, ignoreCase = true) }
                onRemove(removed)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
