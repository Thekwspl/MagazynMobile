package pl.magazyn.mobile.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import pl.magazyn.mobile.data.*
import pl.magazyn.mobile.domain.*

class AgentQuickInputTest {
    private val raw = "  Tekst\n ";
    private fun response(intent: String) = JSONObject().put("schemaVersion", 2).put("sessionId", "s")
        .put("status", "proposal").put("intent", intent).put("recipient", JSONObject.NULL).put("deliveryDate", JSONObject.NULL)
        .put("items", JSONArray()).put("warnings", JSONArray()).put("questions", JSONArray()).put("candidates", JSONArray())
        .put("clarifications", JSONArray()).put("needsData", JSONArray()).put("error", JSONObject.NULL)
        .put("task", JSONObject.NULL).put("note", JSONObject.NULL).put("contact", JSONObject.NULL).apply {
            when (intent) {
                "NOTE" -> put("note", JSONObject().put("text", raw))
                "TASK" -> put("task", JSONObject().put("title", "Transport").put("date", "2026-10-11").put("description", "Opis")
                    .put("steps", JSONArray().put(JSONObject().put("time", "09:30").put("placeId", "t").put("placeText", "KL").put("note", "")
                        .put("people", JSONArray().put(JSONObject().put("employeeId", "p").put("displayText", "Jan").put("note", "")))))
                "CONTACT" -> put("contact", JSONObject().put("fullName", "Jan Kowalski").put("position", "Spawacz").put("phoneNumbers", JSONArray().put("+47 123 45 678")))
            }
        }
    private class Source : AgentDataSource {
        override suspend fun catalog(revision: Long) = JSONObject().put("revision", revision)
        override suspend fun currentStock(ids: List<String>): List<Pair<String, Double>> = error("No ORDER reads for TASK/NOTE/CONTACT")
        override suspend fun product(id: String): ProductEntity? = null
        override suspend fun person(id: String) = if (id == "p") EmployeeSummary("p", "Jan Kowalski", "Jan", "Kowalski", "", "", "", "") else null
        override suspend fun shipyard(id: String): ShipyardEntity? = null
        override suspend fun taskPlace(id: String) = if (id == "t") TaskPlaceView("t", "Kleven", false, "KL") else null
    }
    private class Client(val reply: AgentReply) : AgentClient {
        var mode: QuickInputMode? = null
        override suspend fun authStatus() = JSONObject().put("account", JSONObject().put("type", "chatgpt"))
        override suspend fun fullSync(catalog: JSONObject) {}
        override suspend fun message(text: String) = reply
        override suspend fun message(text: String, mode: QuickInputMode): AgentReply { this.mode = mode; return reply }
        override suspend fun toolResults(sessionId: String, results: JSONObject): AgentReply = error("Unexpected read")
        override suspend fun answers(sessionId: String, answers: List<AgentClarificationAnswer>) = reply
        override suspend fun choice(sessionId: String, candidateId: String) = reply
    }
    @Test fun nonOrderResultsUseExistingModelsAndDoNotReadStockOrWriteAnything() = runBlocking {
        for (intent in listOf("TASK", "NOTE", "CONTACT")) {
            val client = Client(AgentProtocol.parse(response(intent).toString()))
            val repo = AgentRepository(client, Source()) { 1 }
            val mode = if (intent == "CONTACT") QuickInputMode.ALL else QuickInputMode.valueOf(intent)
            val reply = repo.analyze(raw, mode)
            val review = repo.review(reply)
            assertEquals(mode, client.mode)
            assertEquals(ParsedInputKind.valueOf(intent), review.kind)
            assertTrue(review.agentProposal)
            assertTrue(review.items.isEmpty())
            if (intent == "TASK") {
                assertEquals("Kleven", review.taskDraft!!.steps.single().placeText)
                assertEquals("Jan Kowalski", review.taskDraft!!.steps.single().people.single().displayText)
            }
            if (intent == "CONTACT") assertEquals(listOf("+47 123 45 678"), review.phoneNumbers)
        }
    }
    @Test fun wrongModeUnknownTaskIdsAndAlteredOriginalAreRejected() = runBlocking {
        val wrong = Client(AgentProtocol.parse(response("NOTE").toString()))
        assertTrue(runCatching { AgentRepository(wrong, Source()) { 1 }.analyze(raw, QuickInputMode.TASK) }.isFailure)
        val task = response("TASK")
        task.getJSONObject("task").getJSONArray("steps").getJSONObject(0).put("placeId", "missing")
        val repo = AgentRepository(Client(AgentProtocol.parse(task.toString())), Source()) { 1 }
        val reply = repo.analyze(raw, QuickInputMode.TASK)
        assertTrue(runCatching { repo.review(reply) }.isFailure)
        val changed = response("NOTE").put("note", JSONObject().put("text", "changed"))
        assertTrue(runCatching { AgentRepository(Client(AgentProtocol.parse(changed.toString())), Source()) { 1 }.analyze(raw) }.isFailure)
    }
    @Test fun strictProtocolRejectsExtraFieldsMixedKindsAndOldServer() {
        for (bad in listOf(response("NOTE").put("intent", "WRITE"), response("NOTE").put("schemaVersion", 1),
            response("TASK").put("note", JSONObject().put("text", raw)), response("NOTE").put("execute", "save"))) {
            assertTrue(runCatching { AgentProtocol.parse(bad.toString()) }.isFailure)
        }
        val badTask = response("TASK")
        badTask.getJSONObject("task").getJSONArray("steps").getJSONObject(0).put("time", "25:00")
        assertTrue(runCatching { AgentProtocol.parse(badTask.toString()) }.isFailure)
    }
    @Test fun catalogExportsOnlyEnabledRulesAndStableExistingTargetsWithoutPrivateFields() {
        val rule = ParserLearningRuleEntity("r", "kask", "kask", "Kask Biały", null, "szt.", updatedAtEpochMillis = 1)
        fun exported(rules: List<ParserLearningRuleEntity>) = AgentCatalog.export(1, emptyList(), listOf(ProductEntity("white", "Kask Biały", null, "szt.")), emptyList(), emptyList(), emptyList(), rules)
            .getJSONObject("recognitionRules").getJSONArray("learned")
        val active = exported(listOf(rule, rule.copy(id = "off", isEnabled = false)))
        assertEquals(1, active.length())
        assertEquals("white", active.getJSONObject(0).getJSONArray("targetIds").getString(0))
        assertEquals(0, exported(listOf(rule.copy(learnedName = "Nieistniejący"))).getJSONObject(0).getJSONArray("targetIds").length())
        assertEquals(0, exported(emptyList()).length())
        assertEquals("Kask Czerwony", exported(listOf(rule.copy(learnedName = "Kask Czerwony"))).getJSONObject(0).getString("learnedName"))
    }
}
