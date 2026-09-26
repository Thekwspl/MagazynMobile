package pl.magazyn.mobile.agent

import android.content.Context
import org.json.JSONObject
import pl.magazyn.mobile.data.AiKeyStore

data class AgentConnectionStatus(val endpoint: String, val tokenSaved: Boolean)

class AgentConnectionStore(context: Context, private val debug: Boolean) {
    private val preferences = context.getSharedPreferences("agent_connection_preferences", Context.MODE_PRIVATE)
    private val secrets = AiKeyStore(context)

    fun status(): AgentConnectionStatus = AgentConnectionStatus(
        preferences.getString("endpoint", null) ?: if (debug) AgentEndpoint.DEBUG_LOOPBACK else "",
        secrets.hasAgentClientToken(),
    )

    fun save(endpoint: String, newToken: String) {
        val valid = AgentEndpoint.validate(endpoint, debug)
        if (newToken.isBlank() && !secrets.hasAgentClientToken())
            throw AgentFailure("Brak tokenu klienta. Wpisz go przed zapisaniem konfiguracji.")
        if (newToken.isNotBlank()) secrets.saveAgentClientToken(newToken)
        if (!preferences.edit().putString("endpoint", valid).commit())
            throw AgentFailure("Nie udało się zapisać adresu agent-service.")
    }

    fun clear() {
        secrets.clearAgentClientToken()
        if (!preferences.edit().remove("endpoint").commit())
            throw AgentFailure("Nie udało się usunąć adresu agent-service.")
    }

    fun client(): AgentClient {
        val endpoint = status().endpoint.takeIf(String::isNotBlank)
            ?: throw AgentFailure("Brak konfiguracji Codex. Podaj adres HTTPS w Ustawieniach.")
        val token = secrets.readAgentClientToken()
            ?: throw AgentFailure("Brak tokenu klienta Codex. Zapisz go w Ustawieniach.")
        return HttpAgentClient(endpoint, token, debug)
    }

    suspend fun testConnection(): String = AgentConnectionCheck.describe(client().authStatus())
}

object AgentConnectionCheck {
    fun describe(auth: JSONObject): String = when {
        auth.optJSONObject("account")?.optString("type") == "chatgpt" || auth.optString("authMode") == "chatgpt" ->
            "Połączenie działa. Codex jest zalogowany kontem ChatGPT."
        else -> "Agent-service odpowiada, ale Codex nie jest zalogowany kontem ChatGPT."
    }
}
