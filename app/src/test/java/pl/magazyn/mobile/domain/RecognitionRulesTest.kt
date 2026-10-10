package pl.magazyn.mobile.domain

import org.junit.Assert.*
import org.junit.Test
import pl.magazyn.mobile.data.ParserLearningRuleEntity

class RecognitionRulesTest {
    private fun item(name: String, variant: String? = null, quantity: Long = 1) = ParsedItem(name, variant, quantity, "szt.", 1f)
    private fun rule(target: String = "Kask Żółty", enabled: Boolean = true) = ParserLearningRuleEntity(
        "r", "kask", "kask", target, null, "szt.", updatedAtEpochMillis = 1, isEnabled = enabled)

    @Test fun genericHelmetUsesWhiteButExplicitColorBrandOrVariantWins() {
        assertEquals("Kask Biały", RecognitionRules.products(item("kask"), emptyList()).single().name)
        listOf("kask czerwony", "kask 3M").forEach { name ->
            assertEquals(name, RecognitionRules.products(item(name), listOf(rule())).single().name)
        }
        assertEquals("czerwony", RecognitionRules.products(item("kask", "czerwony"), emptyList()).single().variant)
        assertEquals("kask", RecognitionRules.products(item("kask", "3M"), listOf(rule())).single().name)
    }
    @Test fun activeRulePrecedesGeneralDefaultAndEditsAndDisableAreImmediate() {
        assertEquals("Kask Żółty", RecognitionRules.products(item("kask"), listOf(rule())).single().name)
        assertEquals("Kask Czerwony", RecognitionRules.products(item("kask"), listOf(rule("Kask Czerwony"))).single().name)
        assertEquals("Kask Biały", RecognitionRules.products(item("kask"), listOf(rule(enabled = false))).single().name)
        assertEquals("Kask Biały", RecognitionRules.products(item("kask"), emptyList()).single().name)
    }
    @Test fun genericSetsAndCompactCodesKeepSizeQuantityAndExplicitPart() {
        assertEquals(2, RecognitionRules.products(item("kombinezon", "52", 3), emptyList()).size)
        for (code in listOf("m52", "s54")) {
            val result = RecognitionRules.products(item(code, quantity = 2), emptyList())
            assertEquals(2, result.size)
            assertTrue(result.all { it.quantity == 2L && it.variant == code.drop(1) })
            assertEquals(1, RecognitionRules.products(item("spodnie $code"), emptyList()).size)
            assertEquals(1, RecognitionRules.products(item("bluza $code"), emptyList()).size)
        }
        assertEquals("Buty monterskie", RecognitionRules.products(item("m45"), emptyList()).single().name)
        assertEquals("Buty spawalnicze", RecognitionRules.products(item("s43"), emptyList()).single().name)
    }
    @Test fun onePieceAndAlreadyExpandedProductsRemainSingleItems() {
        listOf("Kombinezon jednoczęściowy", "Kombinezon m52 jednoczesciowy", "Kombinezon one-piece").forEach {
            assertEquals(listOf(item(it)), RecognitionRules.products(item(it), emptyList()))
        }
        val once = RecognitionRules.products(item("komplet monterski", "52"), emptyList())
        assertEquals(once, once.flatMap { RecognitionRules.products(it, emptyList()) })
        val parsed = NoteParser().parse("Kowalski Jan - Kombinezon m52 jednoczęściowy x2", QuickInputMode.ORDER)
        assertEquals(1, parsed.items.size)
        assertEquals("52", parsed.items.single().variant)
        assertEquals(2L, parsed.items.single().quantity)
        val onePiece = item("Kombinezon jednoczęściowy")
        val conflicting = rule("Komplet monterski").copy(triggerKey = ImportParser.key(onePiece.name))
        assertEquals(listOf(onePiece), RecognitionRules.products(onePiece, listOf(conflicting)))
        val trousers = item("spodnie")
        assertEquals(listOf(trousers), RecognitionRules.products(trousers, listOf(conflicting.copy(triggerKey = "spodnie"))))
        val red = item("kask czerwony")
        assertEquals(listOf(red), RecognitionRules.products(red, listOf(rule("Kask Biały").copy(triggerKey = "kask czerwony"))))
    }
    @Test fun offlineAndGeminiUseSameProductRulesWithoutReexpanding() {
        val rules = listOf(rule())
        val offline = NoteParser().parse("Kowalski Jan - kask", QuickInputMode.ORDER, learningRules = rules)
        val gemini = GeminiNoteAnalyzer().parseResponse("""{"kind":"ORDER","items":[{"name":"kask","quantity":1}]}""", learningRules = rules)
        assertEquals("Kask Żółty", offline.items.single().name)
        assertEquals(offline.items.single().name, gemini.items.single().name)
        assertEquals("Kask Biały", NoteParser().parse("kask", QuickInputMode.ORDER).items.single().name)
    }
    @Test fun personPositionAndPatternRulesHaveScopeAndForcedModesTakePriority() {
        val rules = listOf(rule().copy(ruleType = "PERSON", triggerKey = "jasio", learnedName = "Jan Kowalski"),
            rule().copy(ruleType = "POSITION", triggerKey = "spaw", learnedName = "Spawacz"),
            rule().copy(ruleType = "PATTERN", sourceLabel = "zapamiętaj", learnedName = "NOTE"))
        val input = ParsedNote(ParsedPerson("Jasio", "spaw", 1f), emptyList(), kind = ParsedInputKind.ORDER)
        val result = RecognitionRules.note(input, "Osoba", rules, QuickInputMode.ALL)
        assertEquals("Jan Kowalski", result.person!!.fullName)
        assertEquals("Spawacz", result.person!!.position)
        assertEquals(ParsedInputKind.NOTE, RecognitionRules.note(input, "zapamiętaj", rules, QuickInputMode.ALL).kind)
        assertEquals(ParsedInputKind.ORDER, RecognitionRules.note(input, "zapamiętaj", rules, QuickInputMode.ORDER).kind)
        assertEquals(input.copy(agentProposal = true), RecognitionRules.note(input.copy(agentProposal = true), "zapamiętaj", rules, QuickInputMode.ALL))
    }
}
