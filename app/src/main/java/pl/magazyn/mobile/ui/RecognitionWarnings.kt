package pl.magazyn.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.magazyn.mobile.domain.*

@Composable
internal fun RecognitionWarnings(title: String, warnings: List<ReviewWarning>) {
    val unique = warnings.distinctBy { it.id }
    if (unique.isEmpty()) return
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$title  ${if (expanded) "▲" else "▼"}", Modifier.fillMaxWidth().clickable { expanded = !expanded },
                style = MaterialTheme.typography.titleSmall)
            Text("${unique.size} informacje wymagające sprawdzenia", style = MaterialTheme.typography.bodySmall)
            unique.filter { it.critical }.forEach { Text(it.message, color = MaterialTheme.colorScheme.error) }
            if (expanded) unique.filterNot { it.critical }.groupBy { it.category }.forEach { (category, entries) ->
                Text(category.label, style = MaterialTheme.typography.labelLarge)
                entries.forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
