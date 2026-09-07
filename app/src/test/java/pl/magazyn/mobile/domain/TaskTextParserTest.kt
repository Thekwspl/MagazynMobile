package pl.magazyn.mobile.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TaskTextParserTest {
    private val places = listOf(
        TaskPlaceLookup("mykle", "Mykle", emptyList()), TaskPlaceLookup("nk", "NK", emptyList()),
        TaskPlaceLookup("kleven", "Kleven", listOf("KL")), TaskPlaceLookup("ulstein", "Ulstein", listOf("UL")),
        TaskPlaceLookup("sk", "SK", emptyList()), TaskPlaceLookup("m2", "M2", emptyList()),
        TaskPlaceLookup("ulstein3", "Ulstein 3", emptyList()), TaskPlaceLookup("m1", "M1", emptyList()),
        TaskPlaceLookup("idar", "Idar", emptyList()), TaskPlaceLookup("bjorn", "Bjorn Ove", emptyList()),
        TaskPlaceLookup("sandvik", "Sandvik", emptyList()),
    )
    private val employees = listOf(
        employee("Kulig", "Alan"), employee("Łachańska", "Julia"), employee("Szeluga", "Wiktor"),
        employee("Guściora", "Damian"), employee("Janik", "Sylwester"), employee("Piech", "Łukasz"),
        employee("Głowacki", "Roman"), employee("Kohut", "Kamil"), employee("Szymański", "Adam"),
        employee("Mazurak", "Tomasz"), employee("Kulikowski", "Artur"), employee("Jonas", "Damulis"),
        employee("Kościelny", "Euzebiusz"), employee("Tanasiewicz", "Andrzej"), employee("Leman", "Łukasz"),
        employee("Golec", "Damian"), employee("Skotnicki", "Łukasz"), employee("Pozarzycki", "Michał"),
    )
    private val parser = TaskTextParser()
    private val today = LocalDate.of(2026, 9, 7)

    @Test fun parsesOnePlaceAndOnePersonTomorrow() {
        val result = parser.parse("Skatte jutro.\n8:00 Mykle\nKulig Alan", places, employees, today)
        assertEquals("2026-09-08", result.date)
        assertEquals("08:00", result.steps.single().time)
        assertEquals("mykle", result.steps.single().placeId)
        assertNotNull(result.steps.single().people.single().employeeId)
    }

    @Test fun parsesSeveralPeopleUnderOnePlaceAndAliasIgnoringCase() {
        val result = parser.parse("Zjazd jutro\n9:40 ul\nKohut Kamil\nSzymański Adam\nMazurak Tomasz\nKulikowski Artur", places, employees, today)
        assertEquals("Ulstein", result.steps.single().placeText)
        assertEquals(4, result.steps.single().people.size)
    }

    @Test fun parsesSeveralPlacesAndHoursWithEmptyLinesAndDots() {
        val result = parser.parse("Zjazd jutro\n\n9:30 Kl\nPiech Łukasz.\nGłowacki Roman\n\n9:40 UL\nJanik Sylwester", places, employees, today)
        assertEquals(listOf("09:30", "09:40"), result.steps.map { it.time })
        assertEquals(listOf("Kleven", "Ulstein"), result.steps.map { it.placeText })
    }

    @Test fun keepsDescriptionBeforeStructuredList() {
        val text = "Zjazd oczywiście już zmiany. Znów problem z Jonasem.\nOstatnio nie powiedział nikomu że zjeżdża.\n4:20 Ulstein\nJonas Damulis"
        val result = parser.parse(text, places, employees, today)
        assertEquals("04:20", result.steps.single().time)
        assertTrue(result.description.contains("problem", true))
        assertTrue(result.description.contains("Ostatnio"))
    }

    @Test fun parsesMainTimeAndPerPersonPlacesWithParenthesizedNotes() {
        val result = parser.parse("Przyjazd 14:20\nTanasiewicz Andrzej Ul (dasz Kasi)\nLeman Łukasz Kl (dasz Kasi)", places, employees, today)
        assertEquals(2, result.steps.size)
        assertTrue(result.steps.all { it.time == "14:20" })
        assertEquals("dasz Kasi", result.steps.first().people.single().note)
    }

    @Test fun keepsAirlineAndPersonSpecificFlightAsNotes() {
        val result = parser.parse("Przyjazd dziś\nWizzair\nSkotnicki Łukasz Kl SAS 12:35. Weźmiesz go razem z tymi wyżej i tak.", places, employees, today)
        assertEquals(today.toString(), result.date)
        assertTrue(result.description.contains("Wizzair"))
        val person = result.steps.single().people.single()
        assertTrue(person.note.contains("SAS 12:35"))
        assertTrue(person.note.contains("Weźmiesz"))
    }

    @Test fun marksUnknownEmployeeAndUnknownPlaceForReview() {
        val result = parser.parse("Zjazd jutro\n9:00 Nieznane\nNowak Jan", places, employees, today)
        assertNull(result.steps.single().placeId)
        assertNull(result.steps.single().people.single().employeeId)
        assertEquals(ParseConfidence.REVIEW, result.steps.single().people.single().confidence)
    }

    @Test fun handlesNoTimeAndPolishCharacters() {
        val result = parser.parse("Przyjazd dziś\nKościelny Euzebiusz M2", places, employees, today)
        assertNull(result.steps.single().time)
        assertEquals("M2", result.steps.single().placeText)
        assertNotNull(result.steps.single().people.single().employeeId)
    }

    @Test fun flagsAmbiguousTextInsteadOfInventingData() {
        val result = parser.parse("Wizzair\nSAS 15:25\nZjazd i przyjazd o 14:20 zrobi Mati. Ty o 18 jednego z Hareid", places, employees, today)
        assertTrue(result.steps.isEmpty())
        assertTrue(result.description.contains("SAS"))
    }

    @Test fun detectsAliasConflictCaseInsensitively() {
        assertTrue(placeAliasConflicts("uL", "kleven", mapOf("ulstein" to listOf("Ulstein", "UL"), "kleven" to listOf("Kleven"))))
        assertFalse(placeAliasConflicts("kl", "kleven", mapOf("ulstein" to listOf("Ulstein", "UL"), "kleven" to listOf("Kleven", "KL"))))
    }

    private fun employee(last: String, first: String) = TaskEmployeeLookup("$last-$first", first, last)
}
