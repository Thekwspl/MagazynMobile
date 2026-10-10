package pl.magazyn.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.queryLong
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
}
