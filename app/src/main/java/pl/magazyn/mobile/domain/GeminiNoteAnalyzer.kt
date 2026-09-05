package pl.magazyn.mobile.domain

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AiCatalogItem(
    val name: String,
    val variant: String?,
    val unit: String,
    val aliases: String,
    val tags: String,
)

class GeminiNoteAnalyzer {
    @Volatile var lastModel: String = PRIMARY_MODEL
        private set
    suspend fun analyze(
        apiKey: String,
        rawText: String,
        catalog: List<AiCatalogItem>,
        shipyards: List<String>,
        redactPhoneNumbers: Boolean,
    ): ParsedNote = withContext(Dispatchers.IO) {
        val textForApi = if (redactPhoneNumbers) redactPhones(rawText) else rawText
        val catalogText = catalog.take(600).joinToString("\n") {
            listOfNotNull(
                it.name,
                it.variant?.takeIf(String::isNotBlank)?.let { value -> "wariant=$value" },
                "jednostka=${it.unit}",
                it.aliases.takeIf(String::isNotBlank)?.let { value -> "aliasy=$value" },
                it.tags.takeIf(String::isNotBlank)?.let { value -> "tagi=$value" },
            ).joinToString(" | ")
        }
        val prompt = """
            Jesteś parserem polskich notatek magazynowych BHP. Nie wykonujesz żadnych operacji — tylko proponujesz strukturę do ręcznej weryfikacji.
            Rozpoznaj typ: ORDER, TASK, CONTACT albo NOTE. Zachowaj każdą pozycję zamówienia osobno. Jeżeli wiadomość dotyczy konkretnej stoczni, zwróć jej nazwę w shipyardName; w przeciwnym razie null. Zapis DD.MM oznacza proponowaną datę wydania w bieżącym roku; zwróć ją jako suggestedIssueDate w formacie YYYY-MM-DD.
            Odbiorcą pozycji może być osoba albo stocznia. Jeżeli zamówienie ma przypisaną stocznię, a przy pozycji nie wskazano osobnego odbiorcy, pozostaw recipientName jako null — aplikacja użyje tej stoczni jako odbiorcy domyślnego.
            Jedna osoba może dostać dowolną liczbę różnych przedmiotów — powtórz recipientName przy każdej jej pozycji. Jeżeli jedno określenie oznacza kilka osobnych przedmiotów (np. „spodnie + bluza”, „spodnie i bluza” albo komplet składający się z obu), zwróć każdy przedmiot jako oddzielny element tablicy items.
            Firmowe reguły odzieży: „kombinezon”, „ciuchy” i „ubranie” bez wskazania konkretnej części zawsze oznaczają komplet dwóch pozycji: spodnie i bluzę. Skrót mXX dla XX >= 48 oznacza spodnie monterskie i bluzę monterską w rozmiarze XX, a sXX oznacza spodnie spawalnicze i bluzę spawalniczą. Jeżeli przed skrótem podano „spodnie” albo „bluza”, zwróć tylko tę część. Dla XX < 48 skróty oznaczają buty: mXX monterskie, sXX spawalnicze.
            Osoba może mieć stanowisko w nawiasie. Rozwiń potoczną formę imienia do pełnej tylko gdy jesteś pewny (np. Krzyś/Krzychu -> Krzysztof, Grześ/Grzechu -> Grzegorz, Kuba -> Jakub, Arek -> Arkadiusz). Nie zmieniaj nazwiska, nawet jeśli wygląda jak imię.
            Ilość musi być liczbą całkowitą. Gdy jej brak, wpisz 1. Nie zgaduj brakującego rozmiaru/typu.
            Jeżeli pozycja dotyczy całej ekipy, zachowaj odbiorcę opisowo. Zwrot lub wymianę dopisz do notes.
            Zwróć WYŁĄCZNIE poprawny JSON w postaci:
            {"kind":"ORDER","shipyardName":"Ulstein","suggestedIssueDate":"2026-09-05","people":[{"fullName":"Jan Kowalski","position":"spawacz","confidence":0.9}],"items":[{"recipientName":"Jan Kowalski","name":"Buty spawalnicze","variant":"44","quantity":1,"unit":"para","notes":"","confidence":0.9}],"phoneNumbers":[],"tasks":[]}
            Dozwolone jednostki: szt., para, paczka, opak., pudełko, karton, komplet. confidence od 0 do 1.

            KATALOG (pomaga dopasować nazwę; nie oznacza stanu magazynowego):
            $catalogText

            STOCZNIE (wybieraj nazwę z tej listy, jeżeli pasuje):
            ${shipyards.joinToString("\n")}

            NOTATKA:
            $textForApi
        """.trimIndent()
        val parsed = parseResponse(post(apiKey, prompt))
        parsed.copy(suggestedIssueDate = extractShortIssueDate(rawText) ?: parsed.suggestedIssueDate)
    }

    suspend fun testConnection(apiKey: String): Unit = withContext(Dispatchers.IO) {
        post(apiKey, "Odpowiedz wyłącznie słowem OK.")
    }

    private suspend fun post(apiKey: String, prompt: String): String {
        var lastError: GeminiApiException? = null
        repeat(3) { attempt ->
            try {
                return postOnce(apiKey, prompt, lastModel)
            } catch (error: GeminiApiException) {
                lastError = error
                if (error.statusCode == 404 && lastModel != FALLBACK_MODEL) {
                    lastModel = FALLBACK_MODEL
                    return postOnce(apiKey, prompt, lastModel)
                }
                if (error.statusCode !in setOf(429, 500, 502, 503, 504) || attempt == 2) {
                    if (lastModel != FALLBACK_MODEL && error.statusCode in setOf(429, 503)) {
                        lastModel = FALLBACK_MODEL
                        return postOnce(apiKey, prompt, lastModel)
                    }
                    throw error
                }
                delay(1_200L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Nie udało się połączyć z usługą AI.")
    }

    private fun postOnce(apiKey: String, prompt: String, model: String): String {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        return try {
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.1).put("responseMimeType", "application/json"))
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw GeminiApiException(status, friendlyError(status, response))
            val root = JSONObject(response)
            root.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        } catch (error: GeminiApiException) {
            throw error
        } catch (error: UnknownHostException) {
            throw IOException("Telefon nie może odnaleźć serwera Google AI. Sprawdź połączenie, DNS, VPN albo blokadę sieci.", error)
        } catch (error: IOException) {
            throw IOException("Błąd połączenia z Google AI: ${error.message ?: error.javaClass.simpleName}", error)
        } catch (error: Exception) {
            throw IOException("Google AI zwróciło nieoczekiwaną odpowiedź: ${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(raw: String): ParsedNote {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = JSONObject(clean)
        val kind = runCatching { ParsedInputKind.valueOf(json.optString("kind", "NOTE")) }.getOrDefault(ParsedInputKind.NOTE)
        val peopleJson = json.optJSONArray("people") ?: JSONArray()
        val people = (0 until peopleJson.length()).mapNotNull { index ->
            peopleJson.optJSONObject(index)?.let {
                ParsedPerson(
                    fullName = normalizeFullPersonName(it.optString("fullName")),
                    position = it.nullableString("position"),
                    confidence = it.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f),
                ).takeIf { person -> person.fullName.isNotBlank() }
            }
        }
        val itemsJson = json.optJSONArray("items") ?: JSONArray()
        val items = (0 until itemsJson.length()).mapNotNull { index ->
            itemsJson.optJSONObject(index)?.let {
                val name = it.optString("name").trim()
                ParsedItem(
                    name = name,
                    variant = it.nullableString("variant"),
                    quantity = it.optLong("quantity", 1).coerceAtLeast(1),
                    unit = it.optString("unit", "szt.").ifBlank { "szt." },
                    confidence = it.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f),
                    recipientName = it.nullableString("recipientName")?.let(::normalizeFullPersonName),
                    notes = it.optString("notes").trim(),
                ).takeIf { item -> name.isNotBlank() }
            }
        }.flatMap(::expandWarehouseClothingConvention)
        return ParsedNote(
            person = people.firstOrNull(),
            people = people,
            items = items,
            phoneNumbers = json.stringList("phoneNumbers"),
            tasks = json.stringList("tasks"),
            kind = kind,
            analyzedByAi = true,
            shipyardName = json.nullableString("shipyardName"),
            suggestedIssueDate = json.nullableString("suggestedIssueDate")
                ?.takeIf { runCatching { java.time.LocalDate.parse(it) }.isSuccess },
        )
    }

    private fun JSONObject.stringList(name: String): List<String> {
        val values = optJSONArray(name) ?: return emptyList()
        return (0 until values.length()).mapNotNull { values.optString(it).trim().takeIf(String::isNotBlank) }
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf { it.isNotBlank() && !it.equals("null", true) }

    private fun redactPhones(text: String): String = PHONE_REGEX.replace(text) { "[NUMER TELEFONU UKRYTY]" }

    private fun friendlyError(status: Int, response: String): String = when (status) {
        400 -> "Usługa AI odrzuciła zapytanie (400). ${extractApiMessage(response)}"
        401, 403 -> "Klucz API jest nieprawidłowy albo nie ma dostępu do Gemini API."
        429 -> "Wyczerpano chwilowy darmowy limit Gemini. Spróbuj ponownie później."
        503 -> "Usługa AI jest chwilowo przeciążona. Spróbuj ponownie za moment albo wybierz analizę Offline."
        else -> "Usługa AI zwróciła błąd $status. ${extractApiMessage(response)}"
    }

    private fun extractApiMessage(response: String): String = runCatching {
        JSONObject(response).optJSONObject("error")?.optString("message")
    }.getOrNull()?.takeIf(String::isNotBlank)?.take(220) ?: response.take(220)

    private companion object {
        const val PRIMARY_MODEL = "gemini-3.6-flash"
        const val FALLBACK_MODEL = "gemini-3.6-flash"
        val PHONE_REGEX = Regex("""(?<!\w)(?:\+\d{1,3}[\s-]?)?(?:\d[\s-]?){7,12}(?!\w)""")
    }
}

class GeminiApiException(val statusCode: Int, message: String) : IOException(message)
