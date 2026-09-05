package pl.magazyn.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteParserTest {
    private val parser = NoteParser()

    @Test
    fun parsesPersonPositionAndShoeSize() {
        val result = parser.parse("Adam Pawlak spawacz - buty r.44")

        assertEquals("Adam Pawlak", result.person?.fullName)
        assertEquals("spawacz", result.person?.position)
        assertEquals("44", result.items.single().variant)
        assertEquals("para", result.items.single().unit)
    }

    @Test
    fun blankTextProducesNoSuggestions() {
        val result = parser.parse("   ")

        assertNull(result.person)
        assertEquals(emptyList<ParsedItem>(), result.items)
    }

    @Test
    fun recognizesPhoneNumberForPersonWithoutCreatingProduct() {
        val result = parser.parse("Adam Pawlak +47 123 45 678")

        assertEquals("Adam Pawlak", result.person?.fullName)
        assertEquals(listOf("+47 123 45 678"), result.phoneNumbers)
        assertEquals(emptyList<ParsedItem>(), result.items)
    }

    @Test
    fun recognizesChecklistAsTasks() {
        val result = parser.parse("[ ] Zadzwonić do Kleven\n[ ] Sprawdzić stan rękawic")

        assertEquals(ParsedInputKind.TASK, result.kind)
        assertEquals(listOf("Zadzwonić do Kleven", "Sprawdzić stan rękawic"), result.tasks)
        assertEquals(emptyList<ParsedItem>(), result.items)
    }

    @Test
    fun splitsMultilineOrderIntoSeparateItems() {
        val result = parser.parse("Adam Pawlak - buty r.44\nŁukasz Wojdyło - kombinezon r.48\nRękawice x 3")

        assertEquals(ParsedInputKind.ORDER, result.kind)
        assertEquals(4, result.items.size)
        assertEquals("Adam Pawlak", result.items[0].recipientName)
        assertEquals("Łukasz Wojdyło", result.items[1].recipientName)
        assertEquals(listOf("Spodnie", "Bluza"), result.items.slice(1..2).map { it.name })
        assertEquals(3, result.items[3].quantity)
    }

    @Test
    fun splitsCommaSeparatedProductsButKeepsNumericVariantsTogether() {
        val result = parser.parse("Filtry x2, okulary BHP x2, rękawice rozmiar 9, 10 x2")

        assertEquals(3, result.items.size)
        assertEquals("Filtry", result.items[0].name)
        assertEquals("okulary BHP", result.items[1].name)
        assertEquals("9", result.items[2].variant)
    }

    @Test
    fun sizeMarkerDoesNotConsumeLetterRInsideProductName() {
        val result = parser.parse("Filtry x2")

        assertEquals("Filtry", result.items.single().name)
        assertNull(result.items.single().variant)
    }

    @Test
    fun splitsCompoundClothingSetForOnePerson() {
        val result = parser.parse("Łukasz Wojdyło - spodnie + bluza r.50")

        assertEquals(2, result.items.size)
        assertEquals(listOf("spodnie", "bluza"), result.items.map { it.name })
        assertEquals(listOf("Łukasz Wojdyło", "Łukasz Wojdyło"), result.items.map { it.recipientName })
        assertEquals(listOf("50", "50"), result.items.map { it.variant })
    }

    @Test
    fun treatsUnspecifiedWorkwearAsTrousersAndSweatshirt() {
        val result = parser.parse("Jan Kowalski - kombinezon monterski r.58")

        assertEquals(listOf("Spodnie monterskie", "Bluza monterska"), result.items.map { it.name })
        assertEquals(listOf("58", "58"), result.items.map { it.variant })
    }

    @Test
    fun expandsCompactWorkwearCodeAboveFortyEight() {
        val result = parser.parse("Jan Kowalski - m50")

        assertEquals(listOf("Spodnie monterskie", "Bluza monterska"), result.items.map { it.name })
        assertEquals(listOf("50", "50"), result.items.map { it.variant })
    }

    @Test
    fun explicitClothingPartPreventsSetExpansion() {
        val result = parser.parse("Jan Kowalski - bluza s56")

        assertEquals(listOf("Bluza spawalnicza"), result.items.map { it.name })
        assertEquals("56", result.items.single().variant)
    }

    @Test
    fun compactCodeBelowFortyEightMeansShoes() {
        val result = parser.parse("Jan Kowalski - m45")

        assertEquals("Buty monterskie", result.items.single().name)
        assertEquals("45", result.items.single().variant)
        assertEquals("para", result.items.single().unit)
    }

    @Test
    fun recognizesShortIssueDateInCurrentYear() {
        val result = parser.parse("05.09 Jan Kowalski - m50")

        assertEquals("${java.time.LocalDate.now().year}-09-05", result.suggestedIssueDate)
        assertEquals(2, result.items.size)
    }

    @Test
    fun ignoresInvalidShortIssueDate() {
        val result = parser.parse("31.02 Jan Kowalski - buty r.44")

        assertNull(result.suggestedIssueDate)
    }
}
