package pl.magazyn.mobile.agent

import java.net.URI

object AgentEndpoint {
    const val DEBUG_LOOPBACK = "http://127.0.0.1:8787"

    fun validate(value: String, allowDebugLoopback: Boolean): String {
        val raw = value.trim()
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri == null || uri.host.isNullOrBlank() || uri.userInfo != null || uri.rawQuery != null ||
            uri.rawFragment != null || uri.rawPath !in listOf("", "/") || uri.port !in -1..65535 || uri.port == 0 ||
            (uri.scheme?.lowercase() != "https" && !(allowDebugLoopback && uri.scheme?.lowercase() == "http" && uri.host == "127.0.0.1")))
            throw AgentFailure("Niepoprawny adres. Wpisz bazowy adres HTTPS bez ścieżki i parametrów.")
        return uri.toASCIIString().trimEnd('/')
    }
}
