package pl.magazyn.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductDisplayTest {
    @Test fun createsCompleteProductRowText() {
        val text = productRowText("Bluza Monterska", "52", "Monterskie", "Bluza", 18.0, "szt.")
        assertEquals("Bluza Monterska • 52", text.title)
        assertEquals("Monterskie • Bluza", text.secondary)
        assertEquals("18", text.quantity)
        assertEquals("szt.", text.unit)
    }

    @Test fun omitsEmptyOptionalFieldsWithoutSeparatorsOrInventedStock() {
        val text = productRowText("Marker", null, "Marker", "", null, "")
        assertEquals("Marker", text.title)
        assertEquals("Marker", text.secondary)
        assertNull(text.quantity)
        assertNull(text.unit)
    }
}
