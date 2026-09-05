package pl.magazyn.mobile.domain

import kotlin.math.roundToLong
import java.time.DateTimeException
import java.time.LocalDate

data class ParsedPerson(
    val fullName: String,
    val position: String?,
    val confidence: Float,
)

data class ParsedItem(
    val name: String,
    val variant: String?,
    val quantity: Long,
    val unit: String,
    val confidence: Float,
    val recipientName: String? = null,
    val notes: String = "",
)

enum class ParsedInputKind { ORDER, TASK, CONTACT, NOTE }

data class ParsedNote(
    val person: ParsedPerson?,
    val items: List<ParsedItem>,
    val people: List<ParsedPerson> = person?.let { listOf(it) } ?: emptyList(),
    val phoneNumbers: List<String> = emptyList(),
    val tasks: List<String> = emptyList(),
    val kind: ParsedInputKind = ParsedInputKind.NOTE,
    val analyzedByAi: Boolean = false,
    val shipyardName: String? = null,
    val suggestedIssueDate: String? = null,
)

/**
 * Celowo prosty parser offline. Każdy wynik nadal wymaga zatwierdzenia.
 * Reguły będą później zasilane aliasami z lokalnej bazy.
 */
class NoteParser {
    private val positions = listOf("spawacz", "monter", "fitter", "rusztowania")
    private val sizeRegex = Regex(
        """(?<![\p{L}\p{N}])(?:r\.?\s*|rozmiar\s*)(\d{1,2}|[A-Z]{1,3})(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )
    private val compactWorkwearSizeRegex = Regex("""(?<!\w)([ms])\s*(\d{2})(?!\w)""", RegexOption.IGNORE_CASE)
    private val quantityRegex = Regex("""(?:x\s*|[-–—]\s*)(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
    private val phoneRegex = Regex("""(?<!\w)(?:\+\d{1,3}[\s-]?)?(?:\d[\s-]?){7,12}(?!\w)""")

    fun parse(text: String): ParsedNote {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return ParsedNote(null, emptyList())
        val suggestedIssueDate = extractShortIssueDate(text)
        val phoneNumbers = phoneRegex.findAll(normalized)
            .map { it.value.trim().trimEnd('-', ' ') }
            .filter { it.count(Char::isDigit) >= 7 }
            .distinct()
            .toList()
        val contentWithoutPhones = phoneRegex.replace(normalized, "").trim()
        val taskMarkers = listOf("do zrobienia", "zadanie", "pamiętaj", "pamietaj", "przypomnij", "zadzwoń", "zadzwon", "sprawdź", "sprawdz")
        val looksLikeTask = taskMarkers.any { normalized.contains(it, true) } || text.lineSequence().any { it.trim().startsWith("[ ]") }

        val textWithoutSuggestedDate = SHORT_DATE_REGEX.replace(text, " ")
        val parsedSegments = textWithoutSuggestedDate.lineSequence()
            .flatMap { line -> line.split(';').asSequence() }
            .flatMap { line -> line.split(Regex(",(?!\\s*\\d)")).asSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull(::parseOrderSegment)
            .toList()
        val expandedSegments = parsedSegments.flatMap { (person, item) ->
            expandCompoundItem(item).flatMap(::expandWarehouseClothingConvention).map { person to it }
        }
        val items = expandedSegments.map { it.second }
        val people = expandedSegments.mapNotNull { it.first }.distinctBy { normalizeKey(it.fullName) }
        val contactPerson = contentWithoutPhones.split(Regex("\\s+")).take(2).joinToString(" ").takeIf { it.split(' ').size >= 2 }
        val kind = when {
            looksLikeTask -> ParsedInputKind.TASK
            items.isNotEmpty() -> ParsedInputKind.ORDER
            phoneNumbers.isNotEmpty() -> ParsedInputKind.CONTACT
            else -> ParsedInputKind.NOTE
        }
        val tasks = if (kind == ParsedInputKind.TASK) {
            text.lineSequence()
                .map { it.trim().removePrefix("-").removePrefix("•").removePrefix("[ ]").trim() }
                .filter(String::isNotBlank)
                .toList()
                .ifEmpty { listOf(normalized) }
        } else emptyList()

        return ParsedNote(
            person = people.firstOrNull() ?: if (kind == ParsedInputKind.CONTACT) contactPerson?.let { ParsedPerson(it, null, 0.7f) } else null,
            people = people,
            items = if (kind == ParsedInputKind.ORDER) items else emptyList(),
            phoneNumbers = phoneNumbers,
            tasks = tasks,
            kind = kind,
            suggestedIssueDate = suggestedIssueDate,
        )
    }

    private fun parseOrderSegment(segment: String): Pair<ParsedPerson?, ParsedItem>? {
        val cleanSegment = segment.trim().removePrefix("-").removePrefix("•").trim()
        if (cleanSegment.isBlank() || REQUEST_INTRO.any { cleanSegment.equals(it, true) || cleanSegment.startsWith(it, true) && cleanSegment.length < it.length + 18 }) return null
        val separatorIndex = cleanSegment.indexOfFirst { it == '-' || it == '–' || it == '—' }
        val recipientText = if (separatorIndex > 0) cleanSegment.substring(0, separatorIndex).trim() else ""
        val itemText = if (separatorIndex > 0) cleanSegment.substring(separatorIndex + 1).trim() else cleanSegment
        val compactWorkwearSize = compactWorkwearSizeRegex.find(itemText)
        if (separatorIndex <= 0 && !quantityRegex.containsMatchIn(itemText) && !sizeRegex.containsMatchIn(itemText) && compactWorkwearSize == null) return null
        val variant = sizeRegex.find(itemText)?.groupValues?.get(1) ?: compactWorkwearSize?.groupValues?.get(2)
        val quantity = quantityRegex.find(itemText)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.roundToLong() ?: 1L
        val cleanedName = itemText
            .replace(phoneRegex, "")
            .replace(sizeRegex, "")
            .replace(compactWorkwearSizeRegex, "")
            .replace(quantityRegex, "")
            .replace(Regex("(?i)\\b(po|proszę|poproszę)\\b"), "")
            .trim(' ', ',', '.', ':', ';')
        val conventionName = compactWorkwearSize?.let { match ->
            inferCompactCodeProduct(cleanedName, match.groupValues[1], match.groupValues[2].toInt())
        } ?: cleanedName
        if (conventionName.length < 2 || REQUEST_INTRO.any { conventionName.equals(it, true) }) return null
        val position = positions.firstOrNull { recipientText.contains(it, ignoreCase = true) }
        val personName = recipientText.substringBefore('(')
            .let { value -> position?.let { value.replace(it, "", ignoreCase = true) } ?: value }
            .trim()
            .takeIf { candidate -> candidate.split(Regex("\\s+")).size >= 2 }
        val person = personName?.let { ParsedPerson(it, position, confidence = 0.72f) }
        return person to ParsedItem(
            name = conventionName,
            variant = variant,
            quantity = quantity.coerceAtLeast(1),
            unit = when {
                conventionName.contains("but", true) -> "para"
                conventionName.contains("paczk", true) -> "paczka"
                conventionName.contains("karton", true) -> "karton"
                else -> "szt."
            },
            confidence = 0.58f,
            recipientName = personName,
        )
    }

    private fun expandCompoundItem(item: ParsedItem): List<ParsedItem> {
        val parts = item.name.split(Regex("(?i)\\s+(?:\\+|i|oraz)\\s+"))
            .map { it.trim(' ', ',', '.', ':', ';') }
            .filter { it.length >= 2 }
        return if (parts.size < 2) listOf(item) else parts.map { item.copy(name = it) }
    }

    private fun inferCompactCodeProduct(rawName: String, code: String, size: Int): String {
        val monterskie = code.equals("m", true)
        val normalized = ImportParser.key(rawName)
        return if (size >= 48) {
            when {
                normalized.contains("spodnie") -> if (monterskie) "Spodnie monterskie" else "Spodnie spawalnicze"
                normalized.contains("bluza") -> if (monterskie) "Bluza monterska" else "Bluza spawalnicza"
                else -> if (monterskie) "Komplet monterski" else "Komplet spawalniczy"
            }
        } else {
            if (monterskie) "Buty monterskie" else "Buty spawalnicze"
        }
    }

    private fun normalizeKey(value: String): String = value.lowercase().replace(Regex("[^a-ząćęłńóśźż0-9]"), "")

    private companion object {
        val REQUEST_INTRO = listOf("dzień dobry", "dzien dobry", "siemka", "zapotrzebowanie", "dziś potrzeba tak", "dzis potrzeba tak", "poproszę o", "poprosze o")
    }
}

fun extractShortIssueDate(text: String, year: Int = LocalDate.now().year): String? =
    SHORT_DATE_REGEX.find(text)?.let { match ->
        val day = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        try {
            LocalDate.of(year, month, day).toString()
        } catch (_: DateTimeException) {
            null
        }
    }

private val SHORT_DATE_REGEX = Regex("""(?<!\d)(\d{2})\.(\d{2})(?!\.?\d)""")

/** Firmowa konwencja: ogólna odzież oznacza komplet składający się ze spodni i bluzy. */
fun expandWarehouseClothingConvention(item: ParsedItem): List<ParsedItem> {
    val compactCodeRegex = Regex("""(?<!\w)([ms])\s*(\d{2})(?!\w)""", RegexOption.IGNORE_CASE)
    val compactCode = compactCodeRegex.find(item.name + " " + item.variant.orEmpty())
    if (compactCode != null) {
        val monterskie = compactCode.groupValues[1].equals("m", true)
        val size = compactCode.groupValues[2].toInt()
        val baseName = compactCodeRegex.replace(item.name, "").trim()
        val baseKey = ImportParser.key(baseName)
        val variant = size.toString()
        if (size < 48) {
            return listOf(item.copy(name = if (monterskie) "Buty monterskie" else "Buty spawalnicze", variant = variant, unit = "para"))
        }
        if (baseKey.contains("spodnie")) {
            return listOf(item.copy(name = if (monterskie) "Spodnie monterskie" else "Spodnie spawalnicze", variant = variant))
        }
        if (baseKey.contains("bluza")) {
            return listOf(item.copy(name = if (monterskie) "Bluza monterska" else "Bluza spawalnicza", variant = variant))
        }
        return listOf(
            item.copy(name = if (monterskie) "Spodnie monterskie" else "Spodnie spawalnicze", variant = variant),
            item.copy(name = if (monterskie) "Bluza monterska" else "Bluza spawalnicza", variant = variant),
        )
    }

    val normalized = ImportParser.key(item.name)
    val explicitlySingle = listOf("spodnie", "bluza", "kurtka", "buty").any { normalized.contains(it) }
    val meansSet = listOf("kombinezon", "ciuchy", "ubranie", "komplet").any { marker ->
        normalized.split(' ').any { it == marker }
    }
    if (!meansSet || explicitlySingle) return listOf(item)

    val welding = normalized.contains("spaw")
    val assembly = normalized.contains("monter")
    val trousers = when {
        welding -> "Spodnie spawalnicze"
        assembly -> "Spodnie monterskie"
        else -> "Spodnie"
    }
    val sweatshirt = when {
        welding -> "Bluza spawalnicza"
        assembly -> "Bluza monterska"
        else -> "Bluza"
    }
    return listOf(item.copy(name = trousers), item.copy(name = sweatshirt))
}
