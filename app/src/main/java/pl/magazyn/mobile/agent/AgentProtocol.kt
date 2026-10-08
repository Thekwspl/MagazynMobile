package pl.magazyn.mobile.agent

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class AgentFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class AgentStatus { NEEDS_DATA, NEEDS_USER_CHOICE, PROPOSAL, ERROR }
data class AgentCandidate(val id: String, val label: String, val kind: String)
enum class AgentClarificationType { CHOICE, YES_NO, TEXT }
data class AgentClarificationQuestion(
    val id: String,
    val question: String,
    val type: AgentClarificationType,
    val candidates: List<AgentCandidate>,
    val required: Boolean,
)
data class AgentClarificationAnswer(val questionId: String, val candidateId: String? = null, val text: String? = null)
data class AgentRecipient(val id: String, val label: String, val kind: String)
data class AgentItem(val productId: String, val label: String, val quantity: Double, val unit: String, val available: Double?)
sealed interface AgentToolRequest {
    val id: String
    data class Stock(override val id: String, val productIds: List<String>) : AgentToolRequest
    data class PersonItems(override val id: String, val personId: String) : AgentToolRequest
    data class ShipyardStock(override val id: String, val shipyardId: String) : AgentToolRequest
    data class ActiveOrders(override val id: String, val recipient: AgentRecipientKey) : AgentToolRequest
    data class RecentIssues(override val id: String, val recipient: AgentRecipientKey, val limit: Int) : AgentToolRequest
}
data class AgentRecipientKey(val kind: String, val id: String)
data class AgentPossession(val productId: String, val quantity: Double, val unit: String, val issuedDate: String)
data class AgentYardBalance(val productId: String, val quantity: Double, val unit: String)
data class AgentOrderRecord(val orderId: String, val status: String, val plannedIssueDate: String, val items: List<AgentOrderItem>)
data class AgentOrderItem(val productId: String, val quantity: Double, val unit: String)
data class AgentIssueRecord(val movementId: String, val lineId: String, val productId: String, val quantity: Double, val unit: String, val issuedDate: String)
data class AgentReply(
    val sessionId: String,
    val status: AgentStatus,
    val recipient: AgentRecipient?,
    val deliveryDate: String?,
    val items: List<AgentItem>,
    val warnings: List<String>,
    @Deprecated("Compatibility only; use clarifications")
    val questions: List<String>,
    @Deprecated("Compatibility only; use clarifications[].candidates")
    val candidates: List<AgentCandidate>,
    val clarifications: List<AgentClarificationQuestion>,
    val needsData: List<AgentToolRequest>,
    val error: String?,
)

private fun <T> JSONArray.typed(block: (JSONObject) -> T): List<T> = (0 until length()).map { block(getJSONObject(it)) }
private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

fun areRequiredClarificationsAnswered(
    questions: List<AgentClarificationQuestion>,
    answers: Collection<AgentClarificationAnswer>,
): Boolean {
    if (answers.map { it.questionId }.distinct().size != answers.size) return false
    if (answers.any { answer ->
            val question = questions.firstOrNull { it.id == answer.questionId } ?: return@any true
            when (question.type) {
                AgentClarificationType.CHOICE -> answer.text != null ||
                    question.candidates.none { it.id == answer.candidateId }
                AgentClarificationType.YES_NO, AgentClarificationType.TEXT ->
                    answer.candidateId != null || answer.text.isNullOrBlank() || (answer.text?.length ?: 0) > 2000
            }
        }) return false
    return questions.none { question -> question.required && answers.none { it.questionId == question.id } }
}

object AgentProtocol {
    private fun JSONObject.exact(vararg expected: String) {
        val actual = keys().asSequence().toSet()
        if (actual != expected.toSet()) throw AgentFailure("Niepoprawne pola żądania agenta.")
    }
    private fun JSONObject.text(key: String): String {
        val value = get(key)
        if (value !is String || value.isBlank() || value.length > 128) throw AgentFailure("Niepoprawne ID w żądaniu agenta.")
        return value
    }
    private fun recipient(args: JSONObject): AgentRecipientKey {
        val kind = args.text("recipientKind")
        if (kind != "person" && kind != "shipyard") throw AgentFailure("Niepoprawny rodzaj odbiorcy agenta.")
        return AgentRecipientKey(kind, args.text("recipientId"))
    }
    private fun candidate(json: JSONObject): AgentCandidate {
        json.exact("id", "label", "kind")
        val candidate = AgentCandidate(json.text("id"), json.getString("label"), json.getString("kind"))
        if (candidate.label.isBlank() || candidate.label.length > 500 ||
            candidate.kind !in setOf("person", "product", "shipyard", "task_place"))
            throw AgentFailure("Niepoprawny kandydat odpowiedzi agenta.")
        return candidate
    }
    private fun clarification(json: JSONObject): AgentClarificationQuestion {
        json.exact("id", "question", "type", "candidates", "required")
        val type = when (json.getString("type")) {
            "choice" -> AgentClarificationType.CHOICE
            "yes_no" -> AgentClarificationType.YES_NO
            "text" -> AgentClarificationType.TEXT
            else -> throw AgentFailure("Nieobsługiwany typ pytania agenta.")
        }
        val question = json.getString("question")
        val candidates = json.getJSONArray("candidates").typed(::candidate)
        if (question.isBlank() || question.length > 1000 ||
            (type == AgentClarificationType.CHOICE && (candidates.isEmpty() || candidates.map { it.id }.distinct().size != candidates.size)) ||
            (type != AgentClarificationType.CHOICE && candidates.isNotEmpty()))
            throw AgentFailure("Niepoprawne pytanie doprecyzowujące agenta.")
        return AgentClarificationQuestion(json.text("id"), question, type, candidates, json.getBoolean("required"))
    }
    private fun request(json: JSONObject): AgentToolRequest {
        json.exact("id", "tool", "arguments")
        val id = json.text("id")
        val args = json.getJSONObject("arguments")
        return when (json.text("tool")) {
            "get_current_stock" -> {
                args.exact("productIds")
                val ids = args.getJSONArray("productIds").let { a -> (0 until a.length()).map {
                    (a.get(it) as? String)?.takeIf(String::isNotBlank) ?: throw AgentFailure("Niepoprawne ID produktu.")
                } }
                if (ids.isEmpty() || ids.size > 20 || ids.size != ids.distinct().size) throw AgentFailure("Zbyt wiele produktów w żądaniu.")
                AgentToolRequest.Stock(id, ids)
            }
            "get_person_current_items" -> { args.exact("personId"); AgentToolRequest.PersonItems(id, args.text("personId")) }
            "get_shipyard_stock" -> { args.exact("shipyardId"); AgentToolRequest.ShipyardStock(id, args.text("shipyardId")) }
            "get_active_orders" -> { args.exact("recipientKind", "recipientId"); AgentToolRequest.ActiveOrders(id, recipient(args)) }
            "get_recent_issues" -> {
                args.exact("recipientKind", "recipientId", "limit")
                val limit = args.get("limit")
                if (limit !is Int || limit !in 1..20) throw AgentFailure("Limit historii musi być w zakresie 1–20.")
                AgentToolRequest.RecentIssues(id, recipient(args), limit)
            }
            else -> throw AgentFailure("Nieobsługiwane narzędzie agenta: ${json.getString("tool")}.")
        }
    }
    fun parse(body: String): AgentReply = try {
        val json = JSONObject(body)
        for (field in listOf("schemaVersion", "sessionId", "status", "intent", "items", "warnings", "questions", "candidates", "clarifications", "needsData"))
            if (!json.has(field) || json.isNull(field)) throw AgentFailure("Niekompletna odpowiedź agenta.")
        if (json.getInt("schemaVersion") != 1) throw AgentFailure("Nieobsługiwana wersja protokołu agenta.")
        if (json.getString("intent") != "ORDER") throw AgentFailure("Nieobsługiwany typ odpowiedzi agenta.")
        val status = when (json.getString("status")) {
            "needs_data" -> AgentStatus.NEEDS_DATA
            "needs_user_choice" -> AgentStatus.NEEDS_USER_CHOICE
            "proposal" -> AgentStatus.PROPOSAL
            "error" -> AgentStatus.ERROR
            else -> throw AgentFailure("Nieznany status agenta.")
        }
        val recipient = if (json.isNull("recipient")) null else json.getJSONObject("recipient").let {
            AgentRecipient(it.getString("id"), it.getString("label"), it.getString("kind"))
        }
        val reply = AgentReply(
            json.getString("sessionId"), status, recipient,
            if (json.isNull("deliveryDate")) null else json.getString("deliveryDate"),
            json.getJSONArray("items").typed {
                AgentItem(it.getString("productId"), it.getString("label"), it.getDouble("quantity"), it.getString("unit"),
                    if (it.isNull("available")) null else it.getDouble("available"))
            },
            json.getJSONArray("warnings").strings(), json.getJSONArray("questions").strings(),
            json.getJSONArray("candidates").typed {
                AgentCandidate(it.getString("id"), it.getString("label"), it.getString("kind"))
            },
            json.getJSONArray("clarifications").typed(::clarification),
            json.getJSONArray("needsData").typed(::request),
            if (json.isNull("error")) null else json.getJSONObject("error").getString("message"),
        )
        if (reply.sessionId.isBlank() || (recipient != null && recipient.kind !in setOf("person", "shipyard")) ||
            reply.candidates.any { it.kind !in setOf("person", "product", "shipyard", "task_place") } ||
            (status == AgentStatus.NEEDS_DATA && reply.needsData.isEmpty()) || reply.needsData.size > 4 ||
            reply.needsData.map { it.id }.distinct().size != reply.needsData.size ||
            reply.clarifications.map { it.id }.distinct().size != reply.clarifications.size || reply.clarifications.size > 20 ||
            ((status == AgentStatus.NEEDS_USER_CHOICE) != reply.clarifications.isNotEmpty()) ||
            (status == AgentStatus.ERROR && reply.error.isNullOrBlank()) ||
            (status == AgentStatus.PROPOSAL && reply.items.isEmpty())) throw AgentFailure("Niekompletna odpowiedź agenta.")
        reply
    } catch (e: JSONException) {
        throw AgentFailure("Niepoprawna odpowiedź JSON agenta.", e)
    }

    fun catalog(revision: Long, people: JSONArray, products: JSONArray, shipyards: JSONArray, taskPlaces: JSONArray) =
        JSONObject().put("revision", revision).put("people", people).put("products", products)
            .put("shipyards", shipyards).put("taskPlaces", taskPlaces)

    fun toolResults(results: List<JSONObject>): JSONObject = JSONObject().put("results", JSONArray(results))
    fun answers(answers: List<AgentClarificationAnswer>): JSONObject = JSONObject().put("answers", JSONArray().apply {
        answers.forEach { answer ->
            put(JSONObject().put("questionId", answer.questionId).apply {
                answer.candidateId?.let { put("candidateId", it) }
                answer.text?.let { put("text", it) }
            })
        }
    })
    fun result(id: String, tool: String, data: JSONObject): JSONObject =
        JSONObject().put("requestId", id).put("tool", tool).put("data", data)
}
