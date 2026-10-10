package pl.magazyn.mobile.agent

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import pl.magazyn.mobile.domain.ParsedInputKind
import pl.magazyn.mobile.domain.ParsedTaskDraft
import pl.magazyn.mobile.domain.ParsedTaskStep
import pl.magazyn.mobile.domain.ParsedTaskPerson
import pl.magazyn.mobile.domain.ParseConfidence

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
data class AgentContact(val fullName: String, val position: String?, val phoneNumbers: List<String>)
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
    val intent: ParsedInputKind = ParsedInputKind.ORDER,
    val task: ParsedTaskDraft? = null,
    val noteText: String? = null,
    val contact: AgentContact? = null,
)

private fun <T> JSONArray.typed(block: (JSONObject) -> T): List<T> = (0 until length()).map { block(getJSONObject(it)) }
private fun JSONArray.strings(): List<String> = (0 until length()).map { get(it) as? String ?: throw AgentFailure("Niepoprawny tekst odpowiedzi agenta.") }

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
    const val VERSION = 2
    private fun JSONObject.exact(vararg expected: String) {
        val actual = keys().asSequence().toSet()
        if (actual != expected.toSet()) throw AgentFailure("Niepoprawne pola żądania agenta.")
    }
    private fun JSONObject.text(key: String): String {
        val value = get(key)
        if (value !is String || value.isBlank() || value.length > 128) throw AgentFailure("Niepoprawne ID w żądaniu agenta.")
        return value
    }
    private fun JSONObject.string(key: String, max: Int = 100_000): String =
        (get(key) as? String)?.takeIf { it.length <= max } ?: throw AgentFailure("Niepoprawny tekst odpowiedzi agenta.")
    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else string(key)
    private fun date(value: String?): String? {
        if (value != null && (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value) || runCatching { java.time.LocalDate.parse(value) }.isFailure))
            throw AgentFailure("Niepoprawna data agenta.")
        return value
    }
    private fun task(json: JSONObject): ParsedTaskDraft {
        json.exact("title", "date", "description", "steps")
        val title = json.string("title", 500).takeIf(String::isNotBlank) ?: throw AgentFailure("Brak tytułu zadania.")
        val steps = json.getJSONArray("steps").typed { step ->
            step.exact("time", "placeId", "placeText", "note", "people")
            val time = step.nullableString("time")
            if (time != null && !Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(time)) throw AgentFailure("Niepoprawna godzina zadania.")
            val placeId = if (step.isNull("placeId")) null else step.text("placeId")
            val people = step.getJSONArray("people").typed { person ->
                person.exact("employeeId", "displayText", "note")
                val id = if (person.isNull("employeeId")) null else person.text("employeeId")
                val display = person.string("displayText", 500).takeIf(String::isNotBlank) ?: throw AgentFailure("Brak opisu osoby zadania.")
                ParsedTaskPerson(id, display, person.string("note"), if (id == null) ParseConfidence.REVIEW else ParseConfidence.CERTAIN)
            }
            if (people.size > 100) throw AgentFailure("Zbyt wiele osób zadania.")
            ParsedTaskStep(time, placeId, step.string("placeText"), step.string("note"), people, ParseConfidence.REVIEW)
        }
        if (steps.size > 100) throw AgentFailure("Zbyt wiele etapów zadania.")
        return ParsedTaskDraft(title, date(json.nullableString("date")), json.string("description"), steps, ParseConfidence.REVIEW)
    }
    private fun recipient(args: JSONObject): AgentRecipientKey {
        val kind = args.text("recipientKind")
        if (kind != "person" && kind != "shipyard") throw AgentFailure("Niepoprawny rodzaj odbiorcy agenta.")
        return AgentRecipientKey(kind, args.text("recipientId"))
    }
    private fun candidate(json: JSONObject): AgentCandidate {
        json.exact("id", "label", "kind")
        val candidate = AgentCandidate(json.text("id"), json.string("label", 500), json.string("kind"))
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
        val question = json.string("question", 1000)
        val candidates = json.getJSONArray("candidates").typed(::candidate)
        if (question.isBlank() || question.length > 1000 ||
            (type == AgentClarificationType.CHOICE && (candidates.isEmpty() || candidates.size > 100 || candidates.map { it.id }.distinct().size != candidates.size)) ||
            (type != AgentClarificationType.CHOICE && candidates.isNotEmpty()))
            throw AgentFailure("Niepoprawne pytanie doprecyzowujące agenta.")
        return AgentClarificationQuestion(json.text("id"), question, type, candidates, (json.get("required") as? Boolean) ?: throw AgentFailure("Niepoprawna wymagalność pytania."))
    }
    private fun request(json: JSONObject): AgentToolRequest {
        json.exact("id", "tool", "arguments")
        val id = json.text("id")
        val args = json.getJSONObject("arguments")
        return when (json.text("tool")) {
            "get_current_stock" -> {
                args.exact("productIds")
                val ids = args.getJSONArray("productIds").let { a -> (0 until a.length()).map {
                    (a.get(it) as? String)?.takeIf { id -> id.isNotBlank() && id.length <= 128 } ?: throw AgentFailure("Niepoprawne ID produktu.")
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
        if (json.get("schemaVersion") != VERSION) throw AgentFailure("Niezgodna wersja protokołu agenta. Wymagany agent-service v2.")
        json.exact("schemaVersion", "sessionId", "status", "intent", "items", "warnings", "questions", "candidates",
            "clarifications", "needsData", "recipient", "deliveryDate", "error", "task", "note", "contact")
        val intent = runCatching { ParsedInputKind.valueOf(json.string("intent")) }.getOrNull()
            ?: throw AgentFailure("Nieobsługiwany typ odpowiedzi agenta.")
        val status = when (json.getString("status")) {
            "needs_data" -> AgentStatus.NEEDS_DATA
            "needs_user_choice" -> AgentStatus.NEEDS_USER_CHOICE
            "proposal" -> AgentStatus.PROPOSAL
            "error" -> AgentStatus.ERROR
            else -> throw AgentFailure("Nieznany status agenta.")
        }
        val recipient = if (json.isNull("recipient")) null else json.getJSONObject("recipient").let {
            it.exact("id", "label", "kind")
            AgentRecipient(it.text("id"), it.string("label"), it.string("kind"))
        }
        val task = if (json.isNull("task")) null else task(json.getJSONObject("task"))
        val noteText = if (json.isNull("note")) null else json.getJSONObject("note").let { it.exact("text"); it.string("text") }
        val contact = if (json.isNull("contact")) null else json.getJSONObject("contact").let {
            it.exact("fullName", "position", "phoneNumbers")
            val name = it.string("fullName", 500).takeIf(String::isNotBlank) ?: throw AgentFailure("Brak nazwy osoby.")
            val phones = it.getJSONArray("phoneNumbers").strings()
            if (phones.size > 20 || phones.any { phone -> phone.isBlank() || phone.length > 100 }) throw AgentFailure("Niepoprawne numery telefonu.")
            AgentContact(name, it.nullableString("position"), phones)
        }
        val error = if (json.isNull("error")) null else json.getJSONObject("error").let {
            it.exact("code", "message"); it.text("code"); it.string("message", 2000)
        }
        val reply = AgentReply(
            json.text("sessionId"), status, recipient, date(json.nullableString("deliveryDate")),
            json.getJSONArray("items").typed {
                it.exact("productId", "label", "quantity", "unit", "available")
                val quantity = (it.get("quantity") as? Number)?.toDouble() ?: throw AgentFailure("Niepoprawna ilość.")
                val available = if (it.isNull("available")) null else (it.get("available") as? Number)?.toDouble() ?: throw AgentFailure("Niepoprawny stan.")
                if (!quantity.isFinite() || quantity <= 0 || quantity % 1 != 0.0 || quantity > 9_007_199_254_740_991.0 || available?.isFinite() == false)
                    throw AgentFailure("Niepoprawna ilość lub stan.")
                AgentItem(it.text("productId"), it.string("label"), quantity, it.text("unit"), available)
            },
            json.getJSONArray("warnings").strings(), json.getJSONArray("questions").strings(),
            json.getJSONArray("candidates").typed(::candidate),
            json.getJSONArray("clarifications").typed(::clarification),
            json.getJSONArray("needsData").typed(::request),
            error, intent, task, noteText, contact,
        )
        if (reply.sessionId.isBlank() || (recipient != null && recipient.kind !in setOf("person", "shipyard")) ||
            reply.candidates.any { it.kind !in setOf("person", "product", "shipyard", "task_place") } ||
            (status == AgentStatus.NEEDS_DATA && reply.needsData.isEmpty()) || reply.needsData.size > 4 ||
            reply.needsData.map { it.id }.distinct().size != reply.needsData.size ||
            reply.clarifications.map { it.id }.distinct().size != reply.clarifications.size || reply.clarifications.size > 20 ||
            ((status == AgentStatus.NEEDS_USER_CHOICE) != reply.clarifications.isNotEmpty()) ||
            (status == AgentStatus.ERROR && reply.error.isNullOrBlank()) ||
            (status != AgentStatus.NEEDS_DATA && reply.needsData.isNotEmpty()) ||
            (intent != ParsedInputKind.TASK && task != null) || (intent != ParsedInputKind.NOTE && noteText != null) ||
            (intent != ParsedInputKind.CONTACT && contact != null) ||
            (intent != ParsedInputKind.ORDER && (reply.items.isNotEmpty() || recipient != null || reply.deliveryDate != null || reply.needsData.isNotEmpty())) ||
            (status == AgentStatus.PROPOSAL && when (intent) {
                ParsedInputKind.ORDER -> reply.items.isEmpty() || recipient == null || reply.items.any { it.available == null }
                ParsedInputKind.TASK -> task == null
                ParsedInputKind.NOTE -> noteText.isNullOrBlank()
                ParsedInputKind.CONTACT -> contact == null
            })) throw AgentFailure("Niekompletna odpowiedź agenta.")
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
