package pl.magazyn.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteParserTest {
    @Test fun defaultsOnlyGenericHelmetToWhiteHelmet() {
        val parser = NoteParser()
        assertEquals("Kask Biały", parser.parse("Kask x 1").items.single().name)
        assertEquals("Kask czerwony", parser.parse("Kask czerwony x 1").items.single().name)
    }
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
        assertEquals(listOf("Bluza", "Spodnie"), result.items.slice(1..2).map { it.name })
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

        assertEquals(listOf("Bluza monterska", "Spodnie monterskie"), result.items.map { it.name })
        assertEquals(listOf("58", "58"), result.items.map { it.variant })
    }

    @Test
    fun expandsCompactWorkwearCodeAboveFortyEight() {
        val result = parser.parse("Jan Kowalski - m50")

        assertEquals(listOf("Bluza monterska", "Spodnie monterskie"), result.items.map { it.name })
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
    fun sortsRecognizedPackageAlphabeticallyWithoutLocaleDependentRules() {
        val items = listOf(
            ParsedItem("Spodnie", null, 1, "szt.", 1f),
            ParsedItem("Bluza", null, 1, "szt.", 1f),
            ParsedItem("Buty", null, 1, "para", 1f),
        )

        assertEquals(listOf("Bluza", "Buty", "Spodnie"), sortRecognizedPackageItems(items).map { it.name })
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

    @Test fun allKeepsAutomaticOrderTaskAndContactDetection() {
        assertEquals(ParsedInputKind.ORDER, parser.parse("Kask x 1", QuickInputMode.ALL).kind)
        assertEquals(ParsedInputKind.TASK, parser.parse("[ ] Zadzwonić do Kleven", QuickInputMode.ALL).kind)
        assertEquals(ParsedInputKind.CONTACT, parser.parse("Adam Pawlak +47 123 45 678", QuickInputMode.ALL).kind)
    }

    @Test fun forcedOrderKeepsProductsEvenWithTaskMarker() {
        val result = parser.parse("[ ] Kask x 2", QuickInputMode.ORDER)
        assertEquals(ParsedInputKind.ORDER, result.kind)
        assertEquals("Kask Biały", result.items.single().name)
        assertEquals(2, result.items.single().quantity)
        assertEquals(emptyList<String>(), result.tasks)
        assertNull(result.taskDraft)
    }

    @Test fun forcedTaskUsesExistingStructuredParserWithoutTaskKeyword() {
        val text = "Transport jutro\n9:30 Kl\nPiech Łukasz\nGłowacki Roman\n10:00 UL\nJanik Sylwester"
        val places = listOf(TaskPlaceLookup("kl", "Kleven", listOf("Kl")), TaskPlaceLookup("ul", "Ulstein", listOf("UL")))
        val employees = listOf(TaskEmployeeLookup("p", "Łukasz", "Piech"), TaskEmployeeLookup("g", "Roman", "Głowacki"), TaskEmployeeLookup("j", "Sylwester", "Janik"))
        val expected = TaskTextParser().parse(text, places, employees)
        val result = parser.parse(text, QuickInputMode.TASK, places, employees)
        assertEquals(ParsedInputKind.TASK, result.kind)
        assertEquals(expected, result.taskDraft)
        assertEquals(listOf("09:30", "10:00"), result.taskDraft!!.steps.map { it.time })
        assertEquals(2, result.taskDraft!!.steps.first().people.size)
        assertEquals(emptyList<ParsedItem>(), result.items)
        assertNull(result.person)
    }

    @Test fun forcedNoteDoesNotExtractOrdersContactsOrTasks() {
        val result = parser.parse("[ ] Adam Pawlak +47 123 45 678 - kask x2", QuickInputMode.NOTE)
        assertEquals(ParsedInputKind.NOTE, result.kind)
        assertEquals(emptyList<ParsedItem>(), result.items)
        assertEquals(emptyList<ParsedPerson>(), result.people)
        assertEquals(emptyList<String>(), result.phoneNumbers)
        assertEquals(emptyList<String>(), result.tasks)
        assertNull(result.taskDraft)
        assertNull(result.shipyardName)
    }

    @Test fun forcedOrderRetainsHelmetColorsAndClothingConventions() {
        assertEquals("Kask Biały", parser.parse("Kask x1", QuickInputMode.ORDER).items.single().name)
        assertEquals("Kask czerwony", parser.parse("Kask czerwony x1", QuickInputMode.ORDER).items.single().name)
        assertEquals(listOf("Bluza monterska", "Spodnie monterskie"), parser.parse("Jan Kowalski - m50", QuickInputMode.ORDER).items.map { it.name })
        assertEquals("Buty monterskie", parser.parse("Jan Kowalski - m45", QuickInputMode.ORDER).items.single().name)
    }

    @Test fun blankForcedOrderDoesNotInventItems() {
        val result = parser.parse(" ", QuickInputMode.ORDER)
        assertEquals(ParsedInputKind.ORDER, result.kind)
        assertEquals(emptyList<ParsedItem>(), result.items)
    }

}
