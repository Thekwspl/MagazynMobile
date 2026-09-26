package pl.magazyn.mobile.agent

import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface AgentClient {
    suspend fun authStatus(): JSONObject
    suspend fun fullSync(catalog: JSONObject)
    suspend fun message(text: String): AgentReply
    suspend fun toolResults(sessionId: String, results: JSONObject): AgentReply
    suspend fun choice(sessionId: String, candidateId: String): AgentReply
}

class HttpAgentClient(endpoint: String, token: String, allowDebugLoopback: Boolean,
    private val connectTimeoutMs: Int = 8_000, private val readTimeoutMs: Int = 135_000) : AgentClient {
    private val base = AgentEndpoint.validate(endpoint, allowDebugLoopback)
    private val credential = token.trim().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
        ?: throw AgentFailure("Brak tokenu klienta Codex. Zapisz go w Ustawieniach.")

    private suspend fun request(method: String, path: String, body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        try {
            val connection = URL(base + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $credential")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                if (status == 401) throw AgentFailure("Token klienta został odrzucony (HTTP 401). Sprawdź konfigurację Codex.")
                if (status == 403) throw AgentFailure("Operacja jest zabroniona przez agent-service (HTTP 403).")
                if (status == 429) throw AgentFailure("Zbyt wiele żądań do agent-service (HTTP 429). Spróbuj później.")
                if (status !in 200..299) throw AgentFailure("Agent-service zwrócił błąd HTTP $status.")
                if (status == 204) "" else connection.inputStream.bufferedReader().use { it.readText() }
            } finally { connection.disconnect() }
        } catch (e: SocketTimeoutException) {
            throw AgentFailure("Przekroczono czas oczekiwania na agent-service.", e)
        } catch (e: SSLException) {
            throw AgentFailure("Błąd TLS lub certyfikatu agent-service. Sprawdź adres HTTPS i certyfikat.", e)
        } catch (e: IOException) {
            throw AgentFailure("Nie można połączyć się z agent-service. Sprawdź adres i działanie usługi.", e)
        }
    }

    override suspend fun authStatus(): JSONObject = try { JSONObject(request("GET", "/v1/auth/status")) }
    catch (e: org.json.JSONException) { throw AgentFailure("Niepoprawny status autoryzacji agent-service.", e) }
    override suspend fun fullSync(catalog: JSONObject) { request("PUT", "/v1/catalog/full-sync", catalog) }
    override suspend fun message(text: String) = AgentProtocol.parse(request("POST", "/v1/sessions/message", JSONObject().put("message", text)))
    override suspend fun toolResults(sessionId: String, results: JSONObject) =
        AgentProtocol.parse(request("POST", "/v1/sessions/${encode(sessionId)}/tool-results", results))
    override suspend fun choice(sessionId: String, candidateId: String) =
        AgentProtocol.parse(request("POST", "/v1/sessions/${encode(sessionId)}/choice", JSONObject().put("candidateId", candidateId)))

    private fun encode(id: String) = java.net.URLEncoder.encode(id, "UTF-8")
}
