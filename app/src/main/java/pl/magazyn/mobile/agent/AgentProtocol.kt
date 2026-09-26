package pl.magazyn.mobile.agent

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class AgentFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class AgentStatus { NEEDS_DATA, NEEDS_USER_CHOICE, PROPOSAL, ERROR }
data class AgentCandidate(val id: String, val label: String, val kind: String)
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
    val questions: List<String>,
    val candidates: List<AgentCandidate>,
    val needsData: List<AgentToolRequest>,
    val error: String?,
)

private fun <T> JSONArray.typed(block: (JSONObject) -> T): List<T> = (0 until length()).map { block(getJSONObject(it)) }
private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

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
        for (field in listOf("schemaVersion", "sessionId", "status", "intent", "items", "warnings", "questions", "candidates", "needsData"))
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
            json.getJSONArray("needsData").typed(::request),
            if (json.isNull("error")) null else json.getJSONObject("error").getString("message"),
        )
        if (reply.sessionId.isBlank() || (recipient != null && recipient.kind !in setOf("person", "shipyard")) ||
            reply.candidates.any { it.kind !in setOf("person", "product", "shipyard", "task_place") } ||
            (status == AgentStatus.NEEDS_DATA && reply.needsData.isEmpty()) || reply.needsData.size > 4 ||
            reply.needsData.map { it.id }.distinct().size != reply.needsData.size ||
            (status == AgentStatus.NEEDS_USER_CHOICE && (reply.questions.isEmpty() || reply.candidates.isEmpty())) ||
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
    fun result(id: String, tool: String, data: JSONObject): JSONObject =
        JSONObject().put("requestId", id).put("tool", tool).put("data", data)
}
