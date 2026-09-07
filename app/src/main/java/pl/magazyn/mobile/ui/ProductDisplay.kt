package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

fun productSecondaryLine(groupName: String, subgroupName: String): String =
    listOf(groupName.trim(), subgroupName.trim()).filter(String::isNotBlank).joinToString(" • ")

/** Dane tekstowe wiersza; łatwe do sprawdzenia bez uruchamiania Compose. */
data class ProductRowText(val title: String, val secondary: String?, val quantity: String?, val unit: String?)

fun productRowText(name: String, variant: String?, groupName: String, subgroupName: String, stockQuantity: Double?, unit: String?): ProductRowText =
    ProductRowText(
        title = listOf(name.trim(), variant?.trim().orEmpty()).filter(String::isNotBlank).joinToString(" • "),
        secondary = productSecondaryLine(groupName, subgroupName).takeIf(String::isNotBlank),
        quantity = stockQuantity?.let(::formatWholeQuantity),
        unit = unit?.trim()?.takeIf(String::isNotBlank),
    )

@Composable
fun ProductInfo(
    name: String,
    variant: String?,
    groupName: String = "",
    subgroupName: String = "",
    modifier: Modifier = Modifier,
    /** Stan tylko dla ekranów, które znają właściwy magazyn/kontekst. */
    stockQuantity: Double? = null,
    unit: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                val title = listOf(name.trim(), variant?.trim().orEmpty()).filter(String::isNotBlank).joinToString(" • ")
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            productSecondaryLine(groupName, subgroupName).takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (stockQuantity != null) {
            Spacer(Modifier.width(10.dp))
            Column(Modifier.widthIn(min = 38.dp).padding(end = 4.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(formatWholeQuantity(stockQuantity), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                unit?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
