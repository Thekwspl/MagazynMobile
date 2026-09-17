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
    fun employeeNamesUseUnicodeAwareCapitalizationForEveryNamePart() {
        assertEquals("Piotr", normalizeEmployeeNamePart("PIOTR"))
        assertEquals("Piotr", normalizeEmployeeNamePart("pIoTr"))
        assertEquals("Pawłowski", normalizeEmployeeNamePart("PAWŁOWSKI"))
        assertEquals("Bukowiecka-Łytka", normalizeEmployeeNamePart("BUKOWIECKA-ŁYTKA"))
        assertEquals("Anna Maria", normalizeEmployeeNamePart("ANNA MARIA"))
        assertEquals("O'Connor", normalizeEmployeeNamePart("O'CONNOR"))
        assertEquals("Piotr", normalizeEmployeeNamePart("  PIOTR   "))
        assertEquals("Černý ŠŽ", normalizeEmployeeNamePart("ČERNÝ ŠŽ"))
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
