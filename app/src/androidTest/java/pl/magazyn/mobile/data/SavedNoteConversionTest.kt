package pl.magazyn.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import pl.magazyn.mobile.*
import pl.magazyn.mobile.agent.*
import pl.magazyn.mobile.domain.*
import pl.magazyn.mobile.ui.insertPlainNote
import pl.magazyn.mobile.ui.HomeViewModel
import pl.magazyn.mobile.ui.RecognitionMethod

/** AI transports are fixtures; these tests do not claim a real model connection. */
@RunWith(AndroidJUnit4::class)
class SavedNoteConversionTest {
    private lateinit var env: IsolatedApplicationEnvironment
    private val db get() = env.database
    @Before fun setup() = runBlocking { env = IsolatedApplicationEnvironment.create(); db.seedCoreData() }
    @After fun cleanup() { env.close() }

    @Test fun allSixConversionsRequireReviewKeepSourceAndDoNotOverwriteQuickInput() = runBlocking {
        AiKeyStore(env.application).saveApiKey("fixture-api-key")
        RecognitionMethod.entries.forEach { method ->
            listOf(QuickInputMode.ORDER, QuickInputMode.TASK).forEach { mode ->
                val id = "$method-$mode"
                val raw = if (mode == QuickInputMode.ORDER) "Jan Kowalski - Produkt rozmiar 52 x2" else "Transport jutro\n09:30 Port"
                db.notebookDao().insertPlainNote(raw, id)
                var requestedMode: QuickInputMode? = null
                val client = FixtureAgent { selected -> requestedMode = selected; proposal(selected) }
                val analyzer = GeminiNoteAnalyzer { _, prompt ->
                    assertTrue(prompt.contains(if (mode == QuickInputMode.ORDER) "oczekiwany kind to ORDER" else "oczekiwany kind to TASK"))
                    geminiResponse(mode)
                }
                val vm = env.track(HomeViewModel(env.application, analyzer) { AgentRepository(client, RoomAgentDataSource(db)) { 1 } })
                val ordersBeforeAnalysis = db.queryLong("SELECT COUNT(*) FROM orders")
                val tasksBeforeAnalysis = db.queryLong("SELECT COUNT(*) FROM notebook_tasks")
                vm.runAndAwaitViewModelWork {
                    vm.updateQuickInput("Niedokończona wiadomość")
                    vm.selectQuickInputMode(QuickInputMode.NOTE)
                    vm.analyzeSavedNote(id, raw, mode, method)
                }
                eventually("Analiza $method $mode powinna się zakończyć") {
                    vm.noteReview.value != null || vm.aiAnalysis.value.result != null || vm.codexAnalysis.value.result != null
                }
                vm.runAndAwaitViewModelWork {
                    if (method != RecognitionMethod.OFFLINE) {
                        val result = vm.aiAnalysis.value.result ?: vm.codexAnalysis.value.result!!
                        val request = vm.aiAnalysis.value.request ?: vm.codexAnalysis.value.request!!
                        assertTrue(vm.openReview(result, request))
                    }
                }
                val review = vm.noteReview.value!!
                assertEquals(id, review.sourceNoteId)
                assertEquals(raw, review.rawText)
                assertEquals(mode.preferredKind, review.note.kind)
                assertEquals("ACTIVE", db.notebookDao().findNote(id)!!.status)
                assertEquals(ordersBeforeAnalysis, db.queryLong("SELECT COUNT(*) FROM orders"))
                assertEquals(tasksBeforeAnalysis, db.queryLong("SELECT COUNT(*) FROM notebook_tasks"))
                assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM stock_movements"))
                if (method == RecognitionMethod.CODEX) assertEquals(mode, requestedMode)
                val beforeOrders = db.queryLong("SELECT COUNT(*) FROM orders")
                val beforeTasks = db.queryLong("SELECT COUNT(*) FROM notebook_tasks")
                var saves = 0
                vm.runAndAwaitViewModelWork {
                    fun save() {
                        if (mode == QuickInputMode.ORDER) vm.saveDraftOrder(raw, review.note, review.note.items.map { it to it }, false, review.id) { saves++ }
                        else vm.saveTaskDraft(raw, review.note.taskDraft!!, review.id) { saves++ }
                    }
                    save(); save()
                }
                assertEquals(1, saves)
                assertEquals(beforeOrders + if (mode == QuickInputMode.ORDER) 1 else 0, db.queryLong("SELECT COUNT(*) FROM orders"))
                assertEquals(beforeTasks + if (mode == QuickInputMode.TASK) 1 else 0, db.queryLong("SELECT COUNT(*) FROM notebook_tasks"))
                assertEquals(raw, db.notebookDao().findNote(id)!!.rawText)
                assertEquals(if (mode == QuickInputMode.ORDER) "CONVERTED" else "CONVERTED_TASK", db.notebookDao().findNote(id)!!.status)
                assertEquals("Niedokończona wiadomość", vm.quickInput.value.text)
                assertEquals(QuickInputMode.NOTE, vm.quickInput.value.mode)
                assertNotNull(vm.conversionMessage.value)
                vm.runAndAwaitViewModelWork {
                    if (mode == QuickInputMode.TASK) vm.saveTaskDraft(raw, review.note.taskDraft!!, review.id) { saves++ }
                    else vm.saveDraftOrder(raw, review.note, review.note.items.map { it to it }, false, review.id) { saves++ }
                }
                assertEquals(1, saves)
            }
        }
    }

    @Test fun changedTypeRejectsLateGeminiResponseEvenIfTransportFinishesAfterCancellation() = runBlocking {
        AiKeyStore(env.application).saveApiKey("fixture-api-key")
        db.notebookDao().insertPlainNote("Produkt rozmiar 52 x1", "source")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        val vm = env.track(HomeViewModel(env.application, GeminiNoteAnalyzer { _, _ ->
            started.complete(Unit)
            withContext(NonCancellable) { release.await(); finished.complete(Unit); geminiResponse(QuickInputMode.ORDER) }
        }))
        val existingWork = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        vm.analyzeSavedNote("source", "Produkt rozmiar 52 x1", QuickInputMode.ORDER, RecognitionMethod.GEMINI)
        withTimeout(15_000) { started.await() }
        val oldWork = vm.viewModelScope.coroutineContext[Job]!!.children.filterNot(existingWork::contains).toList()
        vm.runAndAwaitViewModelWork { vm.analyzeSavedNote("source", "Produkt rozmiar 52 x1", QuickInputMode.TASK, RecognitionMethod.OFFLINE) }
        release.complete(Unit)
        withTimeout(15_000) { finished.await(); oldWork.joinAll() }
        assertEquals(ParsedInputKind.TASK, vm.noteReview.value!!.note.kind)
        assertNull(vm.aiAnalysis.value.result)
        assertEquals("ACTIVE", db.notebookDao().findNote("source")!!.status)
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM orders"))
    }

    @Test fun sourceEditedDuringCodexAnalysisDiscardsLateProposal() = runBlocking {
        db.notebookDao().insertPlainNote("Produkt x2", "source")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val client = FixtureAgent { mode -> started.complete(Unit); release.await(); proposal(mode) }
        val vm = env.track(HomeViewModel(env.application, GeminiNoteAnalyzer()) { AgentRepository(client, RoomAgentDataSource(db)) { 1 } })
        val existingWork = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        vm.analyzeSavedNote("source", "Produkt x2", QuickInputMode.ORDER, RecognitionMethod.CODEX)
        withTimeout(15_000) { started.await() }
        val work = vm.viewModelScope.coroutineContext[Job]!!.children.filterNot(existingWork::contains).toList()
        db.notebookDao().updateNoteText("source", "Poprawiony tekst")
        release.complete(Unit)
        withTimeout(15_000) { work.joinAll() }
        assertNull(vm.codexAnalysis.value.result)
        assertNull(vm.noteReview.value)
        assertNotNull(vm.codexAnalysis.value.error)
        assertEquals("Poprawiony tekst", db.notebookDao().findNote("source")!!.rawText)
        assertEquals("ACTIVE", db.notebookDao().findNote("source")!!.status)
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM orders"))
    }

    @Test fun sourceEditedAfterReviewCannotBeSavedAndCancelPreservesTheEditedNote() = runBlocking {
        db.notebookDao().insertPlainNote("Transport jutro", "source")
        val vm = env.track(HomeViewModel(env.application))
        vm.runAndAwaitViewModelWork { vm.analyzeSavedNote("source", "Transport jutro", QuickInputMode.TASK, RecognitionMethod.OFFLINE) }
        val review = vm.noteReview.value!!
        db.notebookDao().updateNoteText("source", "Poprawiony tekst")
        vm.runAndAwaitViewModelWork { vm.saveTaskDraft(review.rawText, review.note.taskDraft!!, review.id) }
        assertNotNull(vm.reviewSave.value.error)
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM notebook_tasks"))
        vm.closeReview()
        assertEquals("Poprawiony tekst", db.notebookDao().findNote("source")!!.rawText)
        assertEquals("ACTIVE", db.notebookDao().findNote("source")!!.status)
    }

    @Test fun missingGeminiConfigurationAndCodexErrorNeverModifySource() = runBlocking {
        db.notebookDao().insertPlainNote("Transport", "source")
        val client = FixtureAgent { throw AgentFailure("Fixture connection error") }
        val vm = env.track(HomeViewModel(env.application, GeminiNoteAnalyzer()) { AgentRepository(client, RoomAgentDataSource(db)) { 1 } })
        vm.runAndAwaitViewModelWork { vm.analyzeSavedNote("source", "Transport", QuickInputMode.TASK, RecognitionMethod.GEMINI) }
        assertNotNull(vm.aiAnalysis.value.error)
        vm.runAndAwaitViewModelWork { vm.analyzeSavedNote("source", "Transport", QuickInputMode.TASK, RecognitionMethod.CODEX) }
        eventually("Błąd Codex powinien być widoczny") { vm.codexAnalysis.value.error != null }
        assertNull(vm.noteReview.value)
        assertEquals("Transport", db.notebookDao().findNote("source")!!.rawText)
        assertEquals("ACTIVE", db.notebookDao().findNote("source")!!.status)
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM notebook_tasks"))
    }

    @Test fun codexClarificationRoundKeepsSourceModeAndAllAnswers() = runBlocking {
        db.notebookDao().insertPlainNote("Transport", "source")
        val questions = listOf(
            AgentClarificationQuestion("place", "Miejsce?", AgentClarificationType.TEXT, emptyList(), true),
            AgentClarificationQuestion("confirm", "Potwierdzasz?", AgentClarificationType.YES_NO, emptyList(), true),
            AgentClarificationQuestion("person", "Osoba?", AgentClarificationType.CHOICE,
                listOf(AgentCandidate("employee-1", "Jan Kowalski", "person")), true))
        var reads = 0
        var answers: List<AgentClarificationAnswer> = emptyList()
        val client = object : FixtureAgent({ proposal(it).copy(status = AgentStatus.NEEDS_DATA, task = null,
            needsData = listOf(AgentToolRequest.Stock("stock", listOf("product-1")))) }) {
            override suspend fun toolResults(sessionId: String, results: JSONObject): AgentReply {
                assertEquals("session", sessionId)
                assertEquals(1, results.getJSONArray("results").length())
                reads++
                return proposal(QuickInputMode.TASK).copy(status = AgentStatus.NEEDS_USER_CHOICE, task = null, clarifications = questions)
            }
            override suspend fun answers(sessionId: String, supplied: List<AgentClarificationAnswer>): AgentReply {
                assertEquals("session", sessionId); answers = supplied; return proposal(QuickInputMode.TASK)
            }
        }
        val vm = env.track(HomeViewModel(env.application, GeminiNoteAnalyzer()) { AgentRepository(client, RoomAgentDataSource(db)) { 1 } })
        vm.analyzeSavedNote("source", "Transport", QuickInputMode.TASK, RecognitionMethod.CODEX)
        eventually("Codex powinien zadać pytania") { vm.codexAnalysis.value.reply != null }
        vm.runAndAwaitViewModelWork {
            vm.setCodexTextAnswer("place", "Port"); vm.setCodexTextAnswer("confirm", "Tak")
            vm.setCodexChoiceAnswer("person", "employee-1"); vm.submitCodexAnswers()
        }
        assertEquals(1, reads)
        assertEquals(listOf("Port", "Tak", null), answers.map { it.text })
        assertEquals("employee-1", answers.last().candidateId)
        assertEquals("source", vm.codexAnalysis.value.request!!.sourceNoteId)
        assertEquals(QuickInputMode.TASK, vm.codexAnalysis.value.request!!.mode)
        assertTrue(vm.openReview(vm.codexAnalysis.value.result!!, vm.codexAnalysis.value.request!!))
        vm.closeReview()
        assertEquals("ACTIVE", db.notebookDao().findNote("source")!!.status)
    }

    private fun geminiResponse(mode: QuickInputMode) = if (mode == QuickInputMode.ORDER)
        """{"kind":"ORDER","items":[{"name":"Produkt","variant":"52","quantity":2,"unit":"szt.","requestedUnit":null,"recipientName":"Jan Kowalski"}]}"""
        else """{"kind":"TASK","task":{"title":"Transport","steps":[{"time":"09:30","place":"Port","people":[]}]}}"""

    private fun proposal(mode: QuickInputMode) = AgentReply("session", AgentStatus.PROPOSAL,
        if (mode == QuickInputMode.ORDER) AgentRecipient("employee-1", "Jan Kowalski", "person") else null,
        null, if (mode == QuickInputMode.ORDER) listOf(AgentItem("product-1", "Produkt", 2.0, "szt.", 10.0)) else emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, mode.preferredKind!!,
        task = if (mode == QuickInputMode.TASK) ParsedTaskDraft("Transport", null, "", emptyList(), ParseConfidence.REVIEW) else null)

    private open class FixtureAgent(private val respond: suspend (QuickInputMode) -> AgentReply) : AgentClient {
        override suspend fun authStatus() = JSONObject().put("authMode", "chatgpt")
        override suspend fun fullSync(catalog: JSONObject) { assertTrue(catalog.has("recognitionRules")); assertTrue(catalog.getJSONArray("products").length() > 0) }
        override suspend fun message(text: String) = error("Forced mode required")
        override suspend fun message(text: String, mode: QuickInputMode) = respond(mode)
        override suspend fun toolResults(sessionId: String, results: JSONObject): AgentReply = error("Unexpected read")
        override suspend fun answers(sessionId: String, answers: List<AgentClarificationAnswer>): AgentReply = error("Unexpected answer")
        override suspend fun choice(sessionId: String, candidateId: String): AgentReply = error("Unexpected choice")
    }
}
