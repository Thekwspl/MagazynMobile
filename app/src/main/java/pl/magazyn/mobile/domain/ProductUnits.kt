package pl.magazyn.mobile.domain

/** Catalog units are authoritative. A different message unit needs a human decision, never a conversion. */
object ProductUnits {
    private val quantityUnit = Regex("""(?i)(?:x\s*)?(\d+(?:[.,]\d+)?)\s*(szt\.?|sztuk(?:a|i)?|par(?:a|y)?|opak\.?|opakowani(?:e|a)?|paczk(?:a|i|ę)|metr(?:y|ów)?|m|l|litr(?:y|ów)?|kpl\.?|komplet(?:y)?|rolk(?:a|i)|karton(?:y)?|pudełk(?:o|a))(?![\p{L}])""")
    fun explicitQuantity(text: String): Long? = quantityUnit.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.let { kotlin.math.round(it).toLong() }
    fun explicitUnit(text: String): String? = quantityUnit.find(text)?.groupValues?.get(2)
    fun messageUnit(text: String, labels: List<String>): String? {
        val fragments = text.split(Regex("[;\\n]"))
        val matching = fragments.filter { fragment -> labels.filter(String::isNotBlank).any { label ->
            ImportParser.key(fragment).contains(ImportParser.key(label))
        } }.mapNotNull(::explicitUnit).distinctBy(::key)
        return matching.singleOrNull()
    }
    fun removeExplicitQuantity(text: String): String = text.replace(quantityUnit, "")
    fun key(unit: String): String = when (val value = ImportParser.key(unit).trim('.')) {
        "szt", "sztuka", "sztuki", "sztuk" -> "szt"
        "para", "pary", "par" -> "para"
        "opak", "opakowanie", "opakowania" -> "opak"
        "paczka", "paczki", "paczke" -> "paczka"
        "m", "metr", "metry", "metrow" -> "m"
        "l", "litr", "litry", "litrow" -> "l"
        "kpl", "komplet", "komplety" -> "kpl"
        else -> value
    }
    fun conflict(requestedUnit: String?, catalogUnit: String): Boolean =
        !requestedUnit.isNullOrBlank() && key(requestedUnit) != key(catalogUnit)
    fun needsConfirmation(item: ParsedItem, catalogUnit: String): Boolean =
        conflict(item.requestedUnit, catalogUnit) && item.confirmedCatalogUnit != catalogUnit
    fun catalogItem(item: ParsedItem, catalogUnit: String): ParsedItem = item.copy(unit = catalogUnit)
}
