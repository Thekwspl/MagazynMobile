package pl.magazyn.mobile.ui

import pl.magazyn.mobile.data.EmployeeSummary

fun personDisplayName(lastName: String, firstName: String, fallback: String = ""): String = listOf(lastName, firstName)
    .filter(String::isNotBlank)
    .joinToString(" ")
    .ifBlank { fallback }

fun EmployeeSummary.listDisplayName(): String = personDisplayName(lastName, firstName, fullName)
