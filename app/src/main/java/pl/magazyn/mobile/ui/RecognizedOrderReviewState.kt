package pl.magazyn.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import pl.magazyn.mobile.domain.ParsedItem

internal class RecognizedOrderReviewState(items: List<ParsedItem>) {
    private val reviewRows = mutableStateListOf<RecognizedOrderRow>().apply {
        items.forEachIndexed { index, item -> add(RecognizedOrderRow(recognizedItemKey(item, index), item)) }
    }
    val rows: List<RecognizedOrderRow> get() = reviewRows
    var editor by mutableStateOf<RecognizedOrderItemDraft?>(null)
        private set

    fun open(row: RecognizedOrderRow? = null) {
        if (editor == null) editor = RecognizedOrderItemDraft(row)
    }

    fun dismiss() {
        editor = null
    }

    fun save(draft: RecognizedOrderItemDraft, item: ParsedItem) {
        if (editor !== draft) return
        val row = draft.row
        if (row == null) {
            reviewRows.add(RecognizedOrderRow(recognizedItemKey(item, rows.size), draft.initial).apply { this.item = item })
        } else {
            row.item = item
        }
        dismiss()
    }
}

internal class RecognizedOrderRow(val key: String, val source: ParsedItem) {
    var item by mutableStateOf(source)
    var approved by mutableStateOf(true)
}

internal class RecognizedOrderItemDraft(val row: RecognizedOrderRow?) {
    val initial = row?.item ?: ParsedItem("", null, 1, "szt.", 1f)
    var item by mutableStateOf(initial)
        private set
    var quantityText by mutableStateOf(initial.quantity.toString())
        private set

    val quantity: Long? get() = quantityText.toLongOrNull()?.takeIf { it > 0 }

    fun update(value: ParsedItem) {
        item = value.copy(requiresSourceConfirmation = row?.source?.requiresSourceConfirmation == true)
    }

    fun updateQuantity(value: String) {
        if (value == quantityText) return
        quantityText = value
        update(item.copy(confirmedCatalogUnit = null))
    }

    fun confirmSource() {
        item = item.copy(requiresSourceConfirmation = false)
    }

    fun confirmUnit(unit: String) {
        item = item.copy(confirmedCatalogUnit = unit)
    }
}
