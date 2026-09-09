package pl.magazyn.mobile.domain

/**
 * Punktacja używana wyłącznie przez lokalne rozpoznawanie produktów.
 * Dokładny wariant ma pierwszeństwo przed tagiem/aliasem wariantu, a brak
 * zgodności wariantu odrzuca kandydata zamiast wybierać zbliżony rozmiar.
 */
fun offlineProductMatchScore(
    requestedName: String,
    requestedVariant: String?,
    productName: String,
    productVariant: String?,
    aliases: String,
    tags: String,
): Int? {
    val requestedNameKey = ImportParser.key(requestedName)
    if (requestedNameKey.isBlank()) return null
    val labels = listOf(productName) + aliases.split(',') + tags.split(',')
    val nameScore = when {
        ImportParser.key(productName) == requestedNameKey -> 4
        labels.any { ImportParser.key(it) == requestedNameKey } -> 3
        matchesSearch(requestedName, productName, aliases, tags) -> 1
        else -> return null
    }

    val requestedVariantKey = ImportParser.key(requestedVariant.orEmpty())
    val variantScore = when {
        requestedVariantKey.isBlank() -> 1
        ImportParser.key(productVariant.orEmpty()) == requestedVariantKey -> 4
        (aliases.split(',') + tags.split(',')).any { ImportParser.key(it) == requestedVariantKey } -> 3
        else -> return null
    }
    return variantScore * 100 + nameScore
}
