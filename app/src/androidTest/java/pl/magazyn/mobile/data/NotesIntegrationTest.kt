package pl.magazyn.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import pl.magazyn.mobile.domain.QuickInputMode
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.queryLong
import pl.magazyn.mobile.eventually
import pl.magazyn.mobile.runAndAwaitViewModelWork
import pl.magazyn.mobile.seedCoreData
import pl.magazyn.mobile.domain.ParsedInputKind
import pl.magazyn.mobile.domain.ParsedItem
import pl.magazyn.mobile.domain.ParsedNote
import pl.magazyn.mobile.ui.HomeViewModel

@RunWith(AndroidJUnit4::class)
class NotesIntegrationTest {
    private lateinit var environment: IsolatedApplicationEnvironment
    private lateinit var database: AppDatabase

    @Before
    fun setup() = runBlocking {
        environment = IsolatedApplicationEnvironment.create()
        database = environment.database
        database.seedCoreData()
    }

    @After
    fun cleanup() {
        environment.close()
    }

    @Test
    fun notesArePersistentOrderedEditableAndArchivableWithoutRoomMigration() = runBlocking {
        val dao = database.notebookDao()
        dao.insertNotebook(OrderNotebookEntity("older", "Starsza", "ACTIVE", "NOTE", 10))
        dao.insertNotebook(OrderNotebookEntity("newer", "Nowsza", "CONVERTED", "NOTE", 20))
        dao.insertNotebook(OrderNotebookEntity("order", "Zamówienie", "VERIFIED", "ORDER", 30))
        dao.insertNotebook(OrderNotebookEntity("archived", "Usunięta", "ARCHIVED", "NOTE", 40))

        assertEquals(listOf("newer", "older"), dao.observeNotes().first().map { it.id })
        dao.updateNoteText("older", "Poprawiona treść")
        assertEquals("Poprawiona treść", dao.findNote("older")?.rawText)
        dao.setNoteStatus("older", "ARCHIVED")
        assertEquals(listOf("newer"), dao.observeNotes().first().map { it.id })
        assertEquals(25, DATABASE_SCHEMA_VERSION)
    }

    @Test
    fun successfulConversionKeepsOriginalNoteAndMarksItConvertedAtomically() = runBlocking {
        val rawText = "Kowalski jutro Produkt 52"
        database.notebookDao().insertNotebook(OrderNotebookEntity("source-note", rawText, "ACTIVE", "NOTE", 1))
        val item = ParsedItem(
            name = "Produkt",
            variant = "52",
            quantity = 1,
            unit = "szt.",
            confidence = 1f,
            recipientName = "Jan Kowalski",
            productId = "product-1",
            recipientId = "employee-1",
            recipientKind = "person",
        )
        val parsed = ParsedNote(person = null, items = listOf(item), kind = ParsedInputKind.ORDER)
        val viewModel = HomeViewModel(environment.application)

        viewModel.openSavedNoteAsOrder("source-note", rawText)
        viewModel.runAndAwaitViewModelWork {
            viewModel.saveDraftOrder(rawText, parsed, listOf(item to item), rememberCorrections = false)
        }

        val original = database.notebookDao().findNote("source-note")
        assertNotNull(original)
        assertEquals(rawText, original?.rawText)
        assertEquals("CONVERTED", original?.status)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM orders"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM order_notebooks WHERE detectedType='NOTE'"))
    }

    @Test
    fun cancelledConversionLeavesNoteActiveAndCreatesNoOrder() = runBlocking {
        val rawText = "Notatka do późniejszego zamówienia"
        database.notebookDao().insertNotebook(OrderNotebookEntity("cancelled-note", rawText, "ACTIVE", "NOTE", 1))
        val viewModel = HomeViewModel(environment.application)

        viewModel.openSavedNoteAsOrder("cancelled-note", rawText)
        viewModel.closeReview()

        assertEquals("ACTIVE", database.notebookDao().findNote("cancelled-note")?.status)
        assertEquals(rawText, database.notebookDao().findNote("cancelled-note")?.rawText)
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM orders"))
    }

    @Test fun quickInputNoteIsSavedOnceWithoutOrderTaskOrMovementAndClearedOnlyOnSuccess() = runBlocking {
        val viewModel = HomeViewModel(environment.application)
        val raw = "  Kowalski jutro 2 rękawice i kask\n[ ] Zadzwonić\n "
        var saved = 0
        viewModel.runAndAwaitViewModelWork {
            viewModel.updateQuickInput(raw)
            viewModel.selectQuickInputMode(QuickInputMode.NOTE)
            assertTrue(viewModel.openReview(viewModel.recognize(raw)))
            val review = viewModel.noteReview.value!!
            viewModel.saveReviewedNote(review) { saved++ }
            assertEquals(raw, viewModel.quickInput.value.text)
            viewModel.saveReviewedNote(review) { saved++ }
        }
        assertEquals(1, saved)
        assertEquals(listOf(raw), database.notebookDao().observeNotes().first().map { it.rawText })
        assertEquals("", viewModel.quickInput.value.text)
        assertNull(viewModel.noteReview.value)
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM orders"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM notebook_tasks"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM stock_movements"))
        viewModel.runAndAwaitViewModelWork {
            viewModel.updateQuickInput("Druga notatka")
            assertTrue(viewModel.openReview(viewModel.recognize("Druga notatka")))
            viewModel.saveReviewedNote(viewModel.noteReview.value!!)
        }
        assertEquals(2, database.notebookDao().observeNotes().first().size)
    }

    @Test fun modeChangeRejectsOldReviewAndOldResponseWithoutClearingText() = runBlocking {
        val viewModel = HomeViewModel(environment.application)
        viewModel.runAndAwaitViewModelWork {
            viewModel.updateQuickInput("Kask x1")
            viewModel.selectQuickInputMode(QuickInputMode.NOTE)
            val snapshot = viewModel.quickInput.value
            val result = viewModel.recognize(snapshot.text)
            assertTrue(viewModel.openReview(result))
            val oldReview = viewModel.noteReview.value!!
            viewModel.selectQuickInputMode(QuickInputMode.ORDER)
            assertEquals(snapshot.text, viewModel.quickInput.value.text)
            assertNull(viewModel.noteReview.value)
            viewModel.saveReviewedNote(oldReview)
            assertFalse(viewModel.openReview(result, snapshot))
            viewModel.selectQuickInputMode(QuickInputMode.NOTE)
            assertFalse(viewModel.openReview(result, snapshot))
        }
        assertTrue(database.notebookDao().observeNotes().first().isEmpty())
    }

    @Test fun viewModelKeepsForcedOrderProductsAndSupportsCodexModes() = runBlocking {
        val viewModel = HomeViewModel(environment.application)
        viewModel.runAndAwaitViewModelWork {
            assertEquals(QuickInputMode.ALL, viewModel.quickInput.value.mode)
            assertEquals(ParsedInputKind.CONTACT, viewModel.recognize("Adam Pawlak +47 123 45 678").kind)
            assertEquals(ParsedInputKind.TASK, viewModel.recognize("Zjazd jutro\n9:30 UL").kind)
            viewModel.updateQuickInput("[ ] Kask x2")
            viewModel.selectQuickInputMode(QuickInputMode.ORDER)
            val order = viewModel.recognize(viewModel.quickInput.value.text)
            assertEquals(ParsedInputKind.ORDER, order.kind)
            assertEquals("Kask Biały", order.items.single().name)
            listOf(QuickInputMode.TASK, QuickInputMode.NOTE).forEach { mode ->
                viewModel.selectQuickInputMode(mode)
                assertTrue(mode.supportsCodex)
                assertEquals(mode.preferredKind, viewModel.recognize(viewModel.quickInput.value.text).kind)
            }
        }
    }


    @Test fun forcedTaskHasPlaceAliasesBeforeOpeningReview() = runBlocking {
        database.taskStructureDao().insertPlace(TaskPlaceEntity("kl", "Kleven"))
        database.taskStructureDao().insertAlias(TaskPlaceAliasEntity("alias-kl", "kl", "KL", "kl"))
        val viewModel = HomeViewModel(environment.application)
        eventually("Słownik miejsc powinien być dostępny bez otwierania podglądu") {
            viewModel.taskPlaces.value.any { it.id == "kl" && it.aliases == "KL" }
        }
        viewModel.runAndAwaitViewModelWork {
            val raw = "Transport jutro\n9:30 KL"
            viewModel.updateQuickInput(raw)
            viewModel.selectQuickInputMode(QuickInputMode.TASK)
            val task = viewModel.recognize(raw)
            assertEquals(ParsedInputKind.TASK, task.kind)
            assertEquals("kl", task.taskDraft!!.steps.single().placeId)
            assertEquals("Kleven", task.taskDraft!!.steps.single().placeText)
            assertNull(viewModel.noteReview.value)
        }
    }

    @Test fun reviewedTaskIsSavedOnceAndStaleReviewIsRejected() = runBlocking {
        val viewModel = HomeViewModel(environment.application)
        var saves = 0
        viewModel.runAndAwaitViewModelWork {
            viewModel.updateQuickInput("Transport jutro")
            viewModel.selectQuickInputMode(QuickInputMode.TASK)
            assertTrue(viewModel.openReview(viewModel.recognize("Transport jutro")))
            val old = viewModel.noteReview.value!!
            viewModel.selectQuickInputMode(QuickInputMode.NOTE)
            viewModel.saveTaskDraft(old.rawText, old.note.taskDraft!!, reviewId = old.id) { saves++ }
            viewModel.selectQuickInputMode(QuickInputMode.TASK)
            assertTrue(viewModel.openReview(viewModel.recognize("Transport jutro")))
            val current = viewModel.noteReview.value!!
            viewModel.saveTaskDraft(current.rawText, current.note.taskDraft!!, reviewId = current.id) { saves++ }
            viewModel.saveTaskDraft(current.rawText, current.note.taskDraft!!, reviewId = current.id) { saves++ }
        }
        assertEquals(1, saves)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM notebook_tasks"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM orders"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM stock_movements"))
    }

}
