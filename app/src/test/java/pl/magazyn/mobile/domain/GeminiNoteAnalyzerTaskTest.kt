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

    @Test fun sortsOnlyItemsExpandedFromOneRecognizedPackage() {
        val note = GeminiNoteAnalyzer().parseResponse(
            """{"kind":"ORDER","items":[{"name":"Kombinezon monterski","variant":"54","quantity":1,"unit":"szt.","confidence":1.0}]}""",
        )

        assertEquals(listOf("Bluza monterska", "Spodnie monterskie"), note.items.map { it.name })
    }

    @Test fun eachModeAddsHintToSharedPromptWithoutRemovingRulesOrRedaction() {
        val analyzer = GeminiNoteAnalyzer()
        QuickInputMode.entries.forEach { mode ->
            val prompt = analyzer.buildPrompt("Adam Pawlak +47 123 45 678", emptyList(), emptyList(), redactPhoneNumbers = true, mode = mode)
            assertTrue(prompt.contains(mode.geminiInstruction))
            assertTrue(prompt.contains("Kask Biały"))
            assertTrue(prompt.contains("spodnie monterskie"))
            assertTrue(prompt.contains("Nie wymyślaj brakujących danych"))
            assertTrue(prompt.contains("confidence"))
            assertFalse(prompt.contains("+47 123 45 678"))
        }
    }

    @Test fun mismatchedGeminiKindsAreRejectedForEveryForcedMode() {
        val analyzer = GeminiNoteAnalyzer()
        listOf(QuickInputMode.ORDER, QuickInputMode.TASK, QuickInputMode.NOTE).forEach { mode ->
            val wrong = ParsedNote(null, emptyList(), kind = ParsedInputKind.CONTACT)
            assertTrue(runCatching { analyzer.validateModeResult(wrong, mode) }.exceptionOrNull() is GeminiModeResponseException)
        }
        analyzer.validateModeResult(ParsedNote(null, emptyList(), kind = ParsedInputKind.CONTACT), QuickInputMode.ALL)
    }

    @Test fun incompleteOrderOrTaskAndStructuredNoteAreRejected() {
        val analyzer = GeminiNoteAnalyzer()
        assertTrue(runCatching { analyzer.validateModeResult(analyzer.parseResponse("""{"kind":"ORDER","items":[]}"""), QuickInputMode.ORDER) }.isFailure)
        assertTrue(runCatching { analyzer.validateModeResult(analyzer.parseResponse("""{"kind":"TASK"}"""), QuickInputMode.TASK) }.isFailure)
        assertTrue(runCatching { analyzer.validateModeResult(analyzer.parseResponse("""{"kind":"NOTE","items":[{"name":"Kask","quantity":1}]}"""), QuickInputMode.NOTE) }.isFailure)
    }

    @Test fun validForcedKindsRemainProposalsForReview() {
        val analyzer = GeminiNoteAnalyzer()
        val jsons = mapOf(
            QuickInputMode.ORDER to """{"kind":"ORDER","items":[{"name":"Kask czerwony","quantity":1}]}""",
            QuickInputMode.TASK to """{"kind":"TASK","task":{"title":"Transport","notes":"Uzupełnić godzinę"}}""",
            QuickInputMode.NOTE to """{"kind":"NOTE","items":[],"people":[],"tasks":[]}""",
        )
        jsons.forEach { (mode, json) ->
            val note = analyzer.parseResponse(json)
            analyzer.validateModeResult(note, mode)
            assertEquals(mode.preferredKind, note.kind)
            assertTrue(note.analyzedByAi)
        }
    }

}
