package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Wspólna rama zatwierdzonych zadań i zamówień. */
@Composable
fun StructuredWorkCard(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), content = header)
            if (expanded) content()
        }
    }
}
