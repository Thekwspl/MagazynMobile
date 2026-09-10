package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Compact, fixed context shared by recognized orders and recognized task lists. */
@Composable
fun OriginalMessagePanel(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Surface(tonalElevation = 1.dp, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier
                .heightIn(max = 112.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Text(
                "Oryginalna wiadomość",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
