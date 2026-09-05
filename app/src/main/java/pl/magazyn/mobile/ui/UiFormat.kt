package pl.magazyn.mobile.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

private val displayDateFormatter = DateTimeFormatter.ofPattern("dd MMM uuuu", Locale("pl", "PL"))

fun formatDisplayDate(isoDate: String): String = runCatching {
    LocalDate.parse(isoDate).format(displayDateFormatter)
}.getOrDefault(isoDate)

fun formatWholeQuantity(value: Double): String = value.roundToLong().toString()

fun formatWholeQuantity(value: Long): String = value.toString()
