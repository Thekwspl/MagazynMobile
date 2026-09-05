package pl.magazyn.mobile.domain

import java.util.Locale

private val polishLocale = Locale("pl", "PL")

fun removeDigits(value: String): String = value.filterNot(Char::isDigit)

fun normalizePersonName(value: String): String = removeDigits(value)
    .trim()
    .replace(Regex("\\s+"), " ")
    .split(" ")
    .joinToString(" ") { word ->
        word.lowercase(polishLocale).replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(polishLocale) else character.toString()
        }
    }

private val polishFirstNameExpansions = mapOf(
    "krzys" to "Krzysztof", "krzysiu" to "Krzysztof", "krzychu" to "Krzysztof",
    "grzes" to "Grzegorz", "grzesiu" to "Grzegorz", "grzechu" to "Grzegorz",
    "kuba" to "Jakub", "kubus" to "Jakub", "bartek" to "Bartosz",
    "tomek" to "Tomasz", "tomaszek" to "Tomasz", "piotrek" to "Piotr",
    "wojtek" to "Wojciech", "jarek" to "Jarosław", "slawek" to "Sławomir",
    "mirek" to "Mirosław", "radek" to "Radosław", "arek" to "Arkadiusz",
    "czarek" to "Cezary", "przemek" to "Przemysław", "seba" to "Sebastian",
    "mati" to "Mateusz", "maciek" to "Maciej", "marcinek" to "Marcin",
    "darek" to "Dariusz", "zbyszek" to "Zbigniew", "olek" to "Aleksander",
    "alek" to "Aleksander", "heniek" to "Henryk", "staszek" to "Stanisław",
    "franek" to "Franciszek", "witek" to "Witold", "waldek" to "Waldemar",
    "rysiek" to "Ryszard", "janek" to "Jan", "jasiu" to "Jan",
)

fun normalizeFirstName(value: String): String {
    val normalized = normalizePersonName(value)
    if (normalized.isBlank()) return normalized
    return normalized.split(" ").joinToString(" ") { part ->
        polishFirstNameExpansions[ImportParser.key(part).replace(" ", "")] ?: part
    }
}

fun normalizeFullPersonName(value: String): String {
    val parts = removeDigits(value).trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (parts.isEmpty()) return ""
    return (listOf(normalizeFirstName(parts.first())) + parts.drop(1).map(::normalizePersonName)).joinToString(" ")
}

fun normalizeCommaSeparated(value: String): String = value
    .split(Regex("[,;|\\n]+"))
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy { it.lowercase(polishLocale) }
    .joinToString(", ")

fun normalizeDisplayName(value: String): String = value
    .trim()
    .replace(Regex("\\s+"), " ")
    .replaceFirstChar { character ->
        if (character.isLowerCase()) character.titlecase(polishLocale) else character.toString()
    }

fun normalizePhoneNumbers(value: String): String = value
    .split(Regex("[,;|\\n]+"))
    .map { it.trim().replace(Regex("\\s+"), " ") }
    .filter { number -> number.count(Char::isDigit) >= 7 }
    .distinct()
    .joinToString(", ")
