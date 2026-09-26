package pl.magazyn.mobile.ui

import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.domain.ImportParser
import pl.magazyn.mobile.domain.ParsedItem
import pl.magazyn.mobile.domain.matchesSearch
import pl.magazyn.mobile.domain.offlineProductMatchScore

internal class RecognizedProductIndex(private val products: List<ProductWithStock>) {
    private val byExactLabel = buildMap<String, MutableList<ProductWithStock>> {
        products.forEach { product ->
            (listOf(product.name) + product.aliases.split(',') + product.tags.split(','))
                .map(ImportParser::key)
                .filter(String::isNotBlank)
                .distinct()
                .forEach { label -> getOrPut(label) { mutableListOf() }.add(product) }
        }
    }

    fun matches(item: ParsedItem, strictOfflineMatching: Boolean): List<ProductWithStock> {
        val exactCandidates = byExactLabel[ImportParser.key(item.name)].orEmpty()
        if (exactCandidates.isNotEmpty()) {
            matchingProducts(item, exactCandidates, strictOfflineMatching).takeIf(List<ProductWithStock>::isNotEmpty)?.let { return it }
        }
        return matchingProducts(item, products, strictOfflineMatching)
    }
}

internal fun recognizedItemKey(item: ParsedItem, originalIndex: Int): String = listOf(
    originalIndex.toString(),
    ImportParser.key(item.name),
    ImportParser.key(item.variant.orEmpty()),
    ImportParser.key(item.recipientName.orEmpty()),
).joinToString(":")

internal fun isDefaultRecipient(recipient: String, defaultRecipient: String): Boolean {
    val recipientKey = ImportParser.key(recipient)
    return recipientKey.isNotBlank() && recipientKey == ImportParser.key(defaultRecipient)
}

internal fun matchingProducts(
    item: ParsedItem,
    products: List<ProductWithStock>,
    strictOfflineMatching: Boolean,
): List<ProductWithStock> {
    if (!strictOfflineMatching) {
        val nameKey = ImportParser.key(item.name)
        val variantKey = ImportParser.key(item.variant.orEmpty())
        return products.mapNotNull { product ->
            val labels = listOf(product.name) + product.aliases.split(',') + product.tags.split(',')
            val nameScore = when {
                ImportParser.key(product.name) == nameKey -> 4
                labels.any { ImportParser.key(it) == nameKey } -> 3
                matchesSearch(item.name, product.name, product.aliases, product.tags) -> 1
                else -> 0
            }
            if (nameScore == 0) null else {
                val variantMatches = variantKey.isBlank() ||
                    ImportParser.key(product.variant.orEmpty()) == variantKey ||
                    (product.aliases.split(',') + product.tags.split(',')).any { ImportParser.key(it) == variantKey }
                if (!variantMatches) null else product to nameScore
            }
        }.sortedWith(compareByDescending<Pair<ProductWithStock, Int>> { it.second }.thenBy { it.first.name }.thenBy { it.first.variant.orEmpty() }).map { it.first }
    }
    val scored = products.mapNotNull { product ->
        offlineProductMatchScore(
            item.name, item.variant, product.name, product.variant, product.aliases, product.tags,
        )?.let { score -> product to score }
    }
    val bestScore = scored.maxOfOrNull { it.second } ?: return emptyList()
    return scored.filter { it.second == bestScore }
        .sortedWith(compareBy<Pair<ProductWithStock, Int>> { it.first.name }.thenBy { it.first.variant.orEmpty() })
        .map { it.first }
}
