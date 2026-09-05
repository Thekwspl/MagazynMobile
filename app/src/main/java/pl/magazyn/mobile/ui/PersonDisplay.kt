package pl.magazyn.mobile.ui

import pl.magazyn.mobile.data.EmployeeSummary

fun EmployeeSummary.listDisplayName(): String = listOf(lastName, firstName)
    .filter(String::isNotBlank)
    .joinToString(" ")
    .ifBlank { fullName }
