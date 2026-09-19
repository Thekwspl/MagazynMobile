package pl.magazyn.mobile.ui

import pl.magazyn.mobile.data.EmployeeSummary
import pl.magazyn.mobile.data.normalizePhoneKey
import pl.magazyn.mobile.data.splitPhones
import pl.magazyn.mobile.domain.matchesSearch

fun personDisplayName(lastName: String, firstName: String, fallback: String = ""): String = listOf(lastName, firstName)
    .filter(String::isNotBlank)
    .joinToString(" ")
    .ifBlank { fallback }

fun EmployeeSummary.listDisplayName(): String = personDisplayName(lastName, firstName, fullName)

fun distinctPersonPhoneNumbers(phoneNumbers: String): List<String> = splitPhones(phoneNumbers)
    .distinctBy(::normalizePhoneKey)

private fun phoneSearchDigits(value: String): String = value.filter(Char::isDigit)

fun EmployeeSummary.matchesPersonSearch(query: String): Boolean {
    val value = query.trim()
    if (value.isBlank()) return true
    val phoneLike = value.any(Char::isDigit) && value.all { character ->
        character.isDigit() || character.isWhitespace() || character in "+-()./"
    }
    if (phoneLike) {
        val queryDigits = phoneSearchDigits(value)
        return queryDigits.length >= 3 && distinctPersonPhoneNumbers(phoneNumbers).any { phoneNumber ->
            phoneSearchDigits(phoneNumber).contains(queryDigits)
        }
    }
    return matchesSearch(value, fullName, firstName, lastName, positions, aliases, tags)
}
