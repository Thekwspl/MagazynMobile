package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Wspólny viewport podpowiedzi. Zachowuje wszystkie wyniki, ale pokazuje naraz
 * około trzech kompaktowych wierszy; reszta pozostaje dostępna przez przewijanie.
 */
@Composable
fun <T> SuggestionList(
    items: List<T>,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    val keyResolver = key
    LazyColumn(
        modifier = modifier.fillMaxWidth().heightIn(max = 190.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(items, key = keyResolver?.let { resolver -> { _, item -> resolver(item) } }) { _, item ->
            itemContent(item)
        }
    }
}

fun Modifier.suggestionMenuHeight(): Modifier = heightIn(max = 190.dp)
