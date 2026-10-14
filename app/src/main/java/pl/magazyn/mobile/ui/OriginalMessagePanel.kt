package pl.magazyn.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OriginalMessagePanel(text: String, modifier: Modifier = Modifier, initiallyCollapsed: Boolean = false) {
    if (text.isBlank()) return
    var expanded by rememberSaveable(text) { mutableStateOf(!initiallyCollapsed) }
    Surface(tonalElevation = 1.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text("Oryginalna wiadomość  ${if (expanded) "▲" else "▼"}",
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (expanded) Text(text, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
        }
    }
}
