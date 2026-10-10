package pl.magazyn.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.magazyn.mobile.data.HistoryLine

class NotesAndIssueUiTest {
    @Test
    fun noteRequiresNonBlankTextAndKeepsReadableExcerpt() {
        assertFalse(canSaveNote("  \n "))
        assertTrue(canSaveNote("Treść"))
        assertEquals("Pierwsza druga linia", noteExcerpt("  Pierwsza\n\n druga   linia "))
    }

    @Test
    fun convertedNoteRequiresExplicitRepeatConfirmation() {
        assertFalse(noteNeedsRepeatWarning("ACTIVE"))
        assertTrue(noteNeedsRepeatWarning("CONVERTED"))
    }

    @Test
    fun emptyIssueDraftDoesNotBlockBackButEveryUserChangeDoes() {
        assertFalse(issueDraftIsDirty(listOf(""), listOf("1"), 1, "2026-10-10", "2026-10-10", false))
        assertTrue(issueDraftIsDirty(listOf("Kask"), listOf("1"), 1, "2026-10-10", "2026-10-10", false))
        assertTrue(issueDraftIsDirty(listOf(""), listOf("2"), 1, "2026-10-10", "2026-10-10", false))
        assertTrue(issueDraftIsDirty(listOf(""), listOf(""), 1, "2026-10-10", "2026-10-10", false))
        assertTrue(issueDraftIsDirty(listOf(""), listOf("0"), 1, "2026-10-10", "2026-10-10", false))
        assertTrue(issueDraftIsDirty(listOf("", ""), listOf("1", "1"), 2, "2026-10-10", "2026-10-10", false))
        assertTrue(issueDraftIsDirty(listOf(""), listOf("1"), 1, "2026-10-11", "2026-10-10", false))
        assertTrue(issueDraftIsDirty(listOf(""), listOf("1"), 1, "2026-10-10", "2026-10-10", true))
    }

    @Test
    fun initialAddedAndFreshIssueLinesStartAtOneAndAllowEditing() {
        val initial = NewIssueLine()
        assertEquals("1", initial.quantity)
        val edited = initial.copy(quantity = "3")
        assertEquals("3", edited.quantity)
        assertEquals("", edited.copy(quantity = "").quantity)
        val added = NewIssueLine()
        assertEquals("1", added.quantity)
        // Po zapisaniu ekran zostaje zamknięty; nowe otwarcie tworzy świeżą pozycję.
        val reopened = NewIssueLine()
        assertEquals("1", reopened.quantity)
        assertFalse(issueDraftIsDirty(listOf(reopened.productQuery), listOf(reopened.quantity), 1, "2026-10-10", "2026-10-10", false))
    }

    @Test
    fun historyDetailsNeverTruncateLongOperations() {
        listOf(1, 5, 30, 50).forEach { count ->
            val lines = (1..count).map { index ->
                HistoryLine("line-$index", "product-$index", "Przedmiot $index", null, -1.0, "szt.", "", "")
            }
            assertEquals(count, historyDetailsLines(lines).size)
            assertEquals(lines.map { it.id }, historyDetailsLines(lines).map { it.id })
        }
    }

    @Test fun quickInputDefaultsToAllAndModeChangesKeepOriginalText() {
        val initial = QuickInputUiState()
        assertEquals(pl.magazyn.mobile.domain.QuickInputMode.ALL, initial.mode)
        val typed = initial.edit("  Kask\n  jutro ")
        pl.magazyn.mobile.domain.QuickInputMode.entries.forEach { mode ->
            assertEquals(typed.text, typed.select(mode).text)
            assertEquals(mode, typed.select(mode).mode)
        }
        assertEquals(typed, typed.select(typed.mode))
    }

    @Test fun oldAnalysisSnapshotStaysInvalidEvenAfterSwitchingModeBack() {
        val request = QuickInputUiState(text = "Kask")
        val changed = request.select(pl.magazyn.mobile.domain.QuickInputMode.NOTE)
        assertFalse(request == changed)
        assertFalse(request == changed.select(pl.magazyn.mobile.domain.QuickInputMode.ALL))
        assertFalse(request == request.edit("Kask x2").edit("Kask"))
    }

    @Test fun plainNotePreservesRawTextAndUsesExistingNoteModel() {
        val raw = "  Adam Pawlak +47 123 45 678\n[ ] Kask x2\n "
        val note = plainNoteEntity("note", raw, 123)
        assertEquals(raw, note.rawText)
        assertEquals("NOTE", note.detectedType)
        assertEquals("ACTIVE", note.status)
        assertEquals(123L, note.createdAtEpochMillis)
        assertTrue(runCatching { plainNoteEntity("blank", " ") }.isFailure)
    }

    @Test fun codexSupportsEveryQuickInputMode() {
        assertTrue(pl.magazyn.mobile.domain.QuickInputMode.ALL.supportsCodex)
        assertTrue(pl.magazyn.mobile.domain.QuickInputMode.ORDER.supportsCodex)
        assertTrue(pl.magazyn.mobile.domain.QuickInputMode.TASK.supportsCodex)
        assertTrue(pl.magazyn.mobile.domain.QuickInputMode.NOTE.supportsCodex)
    }

}
