package pl.magazyn.mobile.domain

import org.junit.Assert.*
import org.junit.Test

class ProductUnitsTest {
    @Test fun equivalentNamesAreNotConversions() {
        listOf("szt." to "sztuki", "m" to "metr", "l" to "litry", "opak." to "opakowanie", "para" to "pary").forEach { (a, b) ->
            assertFalse(ProductUnits.conflict(a, b))
        }
        assertTrue(ProductUnits.conflict("szt.", "opak."))
        assertFalse(ProductUnits.conflict(null, "opak."))
    }
    @Test fun explicitUnitAndQuantitySurviveOfflineRecognitionWithoutGuessingConversion() {
        val item = NoteParser().parse("Śruby 10 szt.", QuickInputMode.ORDER).items.single()
        assertEquals("Śruby", item.name)
        assertEquals(10L, item.quantity)
        assertEquals("szt.", item.requestedUnit)
        val matched = ProductUnits.catalogItem(item, "opak.")
        assertEquals(10L, matched.quantity)
        assertEquals("opak.", matched.unit)
        assertTrue(ProductUnits.needsConfirmation(matched, "opak."))
        assertFalse(ProductUnits.needsConfirmation(matched.copy(confirmedCatalogUnit = "opak."), "opak."))
        assertTrue(ProductUnits.needsConfirmation(matched.copy(confirmedCatalogUnit = "opak."), "para"))
    }
    @Test fun changingProductReplacesUnitAndRetainsRequestedUnitAndHistoricalNotes() {
        val item = ParsedItem("Nieznany", null, 15, "szt.", 1f, notes = "Historia", requestedUnit = "szt.")
        val mapped = ProductUnits.catalogItem(item, "m")
        assertEquals("m", mapped.unit)
        assertEquals("szt.", mapped.requestedUnit)
        assertEquals("Historia", mapped.notes)
        assertEquals(15L, mapped.quantity)
    }
    @Test fun geminiKeepsMessageUnitSeparateFromCatalogUnit() {
        val item = GeminiNoteAnalyzer().parseResponse("""{"kind":"ORDER","items":[{"name":"Śruby","quantity":10,"unit":"opak.","requestedUnit":"szt."}]}""").items.single()
        assertTrue(ProductUnits.needsConfirmation(item, "opak."))
        assertEquals(10L, item.quantity)
    }
}
