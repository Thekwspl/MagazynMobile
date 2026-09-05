package pl.magazyn.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizationTest {
    @Test
    fun personNamesAreCapitalizedAndContainNoDigits() {
        assertEquals("Grzegorz", normalizePersonName("grz3egorz"))
        assertEquals("Kamil Janusz", normalizePersonName("  KAMIL   JANUSZ  "))
    }

    @Test
    fun aliasesAndTagsAreStoredAsCommaSeparatedValues() {
        assertEquals("Wojdył, Wojdylo, serwis", normalizeCommaSeparated("Wojdył|Wojdylo, serwis"))
    }

    @Test
    fun productNameStartsWithCapitalWithoutDamagingAcronyms() {
        assertEquals("Filtry do 3M BHP", normalizeDisplayName("  filtry   do 3M BHP "))
    }
}
