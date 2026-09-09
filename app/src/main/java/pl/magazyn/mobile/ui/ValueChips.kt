package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        values.map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase() }.forEach { value ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(value, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { onRemove(value) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "Usuń $value", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
