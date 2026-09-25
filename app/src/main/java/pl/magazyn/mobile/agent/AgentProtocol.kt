package pl.magazyn.mobile.agent

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class AgentFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class AgentStatus { NEEDS_DATA, NEEDS_USER_CHOICE, PROPOSAL, ERROR }
data class AgentCandidate(val id: String, val label: String, val kind: String)
data class AgentRecipient(val id: String, val label: String, val kind: String)
data class AgentItem(val productId: String, val label: String, val quantity: Double, val unit: String, val available: Double?)
data class AgentToolRequest(val id: String, val tool: String, val productIds: List<String>)
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
            json.getJSONArray("needsData").typed {
                AgentToolRequest(it.getString("id"), it.getString("tool"), it.getJSONObject("arguments").getJSONArray("productIds").strings())
            },
            if (json.isNull("error")) null else json.getJSONObject("error").getString("message"),
        )
        if (reply.sessionId.isBlank() || (recipient != null && recipient.kind !in setOf("person", "shipyard")) ||
            reply.candidates.any { it.kind !in setOf("person", "product", "shipyard", "task_place") } ||
            (status == AgentStatus.NEEDS_DATA && reply.needsData.isEmpty()) ||
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

    fun stockResults(requests: List<Pair<String, List<Pair<String, Double>>>>): JSONObject = JSONObject().put("results", JSONArray().apply {
        requests.forEach { (requestId, stocks) -> put(JSONObject().put("requestId", requestId).put("tool", "get_current_stock")
            .put("data", JSONObject().put("stocks", JSONArray().apply {
                stocks.forEach { (id, available) -> put(JSONObject().put("productId", id).put("available", available)) }
            }))) }
    })
}
