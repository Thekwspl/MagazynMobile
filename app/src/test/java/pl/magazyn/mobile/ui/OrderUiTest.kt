package pl.magazyn.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.domain.ParsedItem

class OrderUiTest {
    @Test
    fun compactHeaderUsesDateAndOnlyUnambiguousRecipient() {
        assertEquals("24.09.2026 · Kowalski Jan", compactOrderHeader("24.09.2026", listOf("Kowalski Jan")))
        assertEquals("24.09.2026 · Ulstein - Elektro", compactOrderHeader("24.09.2026", listOf("Ulstein - Elektro", "Ulstein - Elektro")))
        assertEquals("24.09.2026 · 3 odbiorców", compactOrderHeader("24.09.2026", listOf("Jan", "Anna", "Ulstein")))
        assertEquals("24.09.2026", compactOrderHeader("24.09.2026", listOf("", " ")))
    }

    @Test
    fun compactProductTitleContainsNoGroupOrSubgroup() {
        assertEquals("Rękawice robocze · XL", productTitle("Rękawice robocze", "XL"))
        assertEquals("Okulary ochronne", productTitle("Okulary ochronne", null))
    }

    @Test
    fun recognizedOrderKeepsStableUniqueKeysForLargeInputWithoutChangingItems() {
        val items = (0 until 50).map { index -> parsedItem(name = "Produkt $index") }
        val snapshot = items.toList()
        val keys = items.mapIndexed { index, item -> recognizedItemKey(item, index) }

        assertEquals(50, keys.distinct().size)
        assertEquals(snapshot, items)
    }

    @Test
    fun productIndexKeepsAmbiguityAndMissingProductVisible() {
        val products = listOf(product("gloves-m", "Rękawice robocze", "M"), product("gloves-xl", "Rękawice robocze", "XL"))
        val index = RecognizedProductIndex(products)

        assertEquals(listOf("gloves-xl"), index.matches(parsedItem("Rękawice robocze", "XL"), true).map { it.id })
        assertEquals(2, index.matches(parsedItem("Rękawice robocze"), true).size)
        assertTrue(index.matches(parsedItem("Nieistniejący produkt"), true).isEmpty())
    }

    @Test
    fun defaultRecipientIsHiddenOnlyForAnExactNormalizedMatch() {
        assertTrue(isDefaultRecipient("Kowalski Jan", "  kowalski   jan "))
        assertEquals(false, isDefaultRecipient("Kowalski Jan", "Kowalski Adam"))
    }

    private fun parsedItem(name: String, variant: String? = null) = ParsedItem(
        name = name,
        variant = variant,
        quantity = 2,
        unit = "szt.",
        confidence = 1f,
    )

    private fun product(id: String, name: String, variant: String?) = ProductWithStock(
        id = id,
        name = name,
        variant = variant,
        unit = "szt.",
        category = "BHP",
        groupName = "Odzież",
        subgroupName = "Rękawice",
        aliases = "",
        tags = "",
        photoUri = "",
        isReturnable = false,
        lowStockThreshold = 0.0,
        repeatIssueWeeks = 0,
        isArchived = false,
        stockQuantity = 10.0,
        stockKnown = true,
    )
}
