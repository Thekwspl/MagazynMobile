package pl.magazyn.mobile.domain

import org.junit.Assert.*
import org.junit.Test

class GeminiNoteAnalyzerTaskTest {
    private val places = listOf(
        TaskPlaceLookup("sk", "SK", emptyList()),
        TaskPlaceLookup("kleven", "Kleven", listOf("KL")),
        TaskPlaceLookup("ulstein", "Ulstein", listOf("UL")),
    )
    private val employees = listOf(
        TaskEmployeeLookup("w", "Grzegorz", "Wielopolski"),
        TaskEmployeeLookup("p", "Łukasz", "Piech"),
        TaskEmployeeLookup("g", "Roman", "Głowacki"),
    )

    @Test fun readsStructuredTaskAndResolvesAliasesLocally() {
        val note = GeminiNoteAnalyzer().parseResponse(
            """{"kind":"TASK","task":{"title":"Zjazd","date":"2026-09-08","notes":"","steps":[{"time":"9:00","place":"SK","people":[{"employeeName":"Wielopolski Grzegorz","note":""}]},{"time":"9:30","place":"KL","people":[{"employeeName":"Piech Łukasz","note":""},{"employeeName":"Głowacki Roman","note":""}]}]}}""",
            places, employees,
        )
        assertEquals(ParsedInputKind.TASK, note.kind)
        assertEquals("Zjazd", note.taskDraft!!.title)
        assertEquals(listOf("SK", "Kleven"), note.taskDraft!!.steps.map { it.placeText })
        assertEquals(2, note.taskDraft!!.steps[1].people.size)
        assertNotNull(note.taskDraft!!.steps[0].people.single().employeeId)
    }

    @Test fun keepsUnknownEmployeeAndPlaceForReview() {
        val note = GeminiNoteAnalyzer().parseResponse(
            """{"kind":"TASK","task":{"title":"Przyjazd","notes":"Wizzair","steps":[{"time":"14:20","place":"Hareid","people":[{"employeeName":"Nowak Jan","note":"SAS 15:25"}]}]}}""",
            places, employees,
        )
        val step = note.taskDraft!!.steps.single()
        assertNull(step.placeId)
        assertNull(step.people.single().employeeId)
        assertEquals(ParseConfidence.REVIEW, step.confidence)
        assertEquals("SAS 15:25", step.people.single().note)
    }

    @Test fun toleratesMissingTaskFieldsAndInvalidTime() {
        val note = GeminiNoteAnalyzer().parseResponse("""{"kind":"TASK","task":{"steps":[{"time":"wieczorem","people":[]}]}}""", places, employees)
        assertEquals("Zadanie", note.taskDraft!!.title)
        assertNull(note.taskDraft!!.steps.single().time)
    }

    @Test fun malformedJsonDoesNotNeedNetworkAndCanBeHandledByCaller() {
        assertTrue(runCatching { GeminiNoteAnalyzer().parseResponse("nie json", places, employees) }.isFailure)
    }
}
