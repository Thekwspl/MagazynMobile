package pl.magazyn.mobile.domain

import pl.magazyn.mobile.data.ProductWithStock

/** A local source ledger independent of the AI protocol. Uncertain matches require a human decision. */
data class SourceOrderRequest(val id: String, val text: String)
enum class ReviewWarningCategory(val label: String) {
    GENERAL("Uwagi ogólne"), UNIT("Jednostki"), STOCK("Stany magazynowe"),
    PRODUCT("Nierozpoznane pozycje"), RECIPIENT("Odbiorcy"), QUANTITY("Ilości"), COVERAGE("Kompletność")
}
data class ReviewWarning(val id: String, val category: ReviewWarningCategory, val message: String, val critical: Boolean = false)

object OrderCoverage {
    private fun tokens(text: String) = ImportParser.key(text).split(' ').filter { it.length > 2 }
    private fun similar(a: String, b: String): Boolean {
        fun stem(word: String): String = listOf("ami", "ach", "ow", "om", "em", "a", "e", "i", "y", "u")
            .firstOrNull { word.endsWith(it) && word.length - it.length >= 4 }?.let { word.dropLast(it.length) } ?: word
        if (a == b || stem(a) == stem(b)) return true
        if (kotlin.math.abs(a.length - b.length) > 1 || minOf(a.length, b.length) < 5) return false
        val row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            var previous = row[0]; row[0] = i + 1
            for (j in b.indices) {
                val old = row[j + 1]
                row[j + 1] = minOf(row[j + 1] + 1, row[j] + 1, previous + if (a[i] == b[j]) 0 else 1)
                previous = old
            }
        }
        return row.last() <= 1
    }
    private fun labelMatches(text: String, label: String): Boolean {
        val wanted = tokens(label)
        val actual = tokens(text)
        return wanted.isNotEmpty() && wanted.all { word -> actual.any { similar(word, it) } }
    }
    private fun variantKey(text: String): String = ImportParser.key(text).removePrefix("m").let { it.toIntOrNull()?.toString() ?: it }
    private fun sourceQuantity(text: String): Long? = ProductUnits.explicitQuantity(text)
        ?: Regex("(?i)(?:^[-•]?\\s*|\\bx\\s*|×\\s*)(\\d+)\\b").find(text.trim())?.groupValues?.get(1)?.toLongOrNull()
    private fun recipient(text: String): String? = Regex("(?i)\\bdla\\s+(.+)$").find(text)?.groupValues?.get(1)?.trim()
    private fun variant(text: String): String? = Regex("(?i)\\b(?:rozmiar|r\\.)\\s*(m?\\d{1,2}|[a-z]{1,3})\\b").find(text)?.groupValues?.get(1)

    fun reconcile(raw: String, note: ParsedNote, catalog: List<ProductWithStock>, headerLabels: List<String>, rules: List<pl.magazyn.mobile.data.ParserLearningRuleEntity> = emptyList()): ParsedNote {
        if (note.kind != ParsedInputKind.ORDER || raw.isBlank()) return note
        val requests = raw.lineSequence().flatMap { it.split(';').asSequence() }.map(String::trim)
            .filter(String::isNotBlank).filterNot { line ->
                val withoutDate = line.replace(Regex("\\b\\d{1,2}\\.\\d{1,2}(?:\\.\\d{4})?\\b"), "").trim()
                withoutDate.isBlank() || headerLabels.any { ImportParser.key(it) == ImportParser.key(withoutDate) }
            }.mapIndexed { index, text -> SourceOrderRequest("source-$index", text) }.toList()
        val items = note.items.map { it.copy(sourceIds = emptySet()) }.toMutableList()
        requests.forEach { request ->
            val parsed = NoteParser().parse(request.text, QuickInputMode.ORDER, learningRules = rules).items
            val sourcePerson = recipient(request.text) ?: parsed.mapNotNull { it.recipientName }.distinct().singleOrNull()
            val recipientLabels = listOfNotNull(sourcePerson) + RecognitionRules.active(rules).filter {
                it.ruleType == "PERSON" && it.triggerKey == ImportParser.key(sourcePerson.orEmpty())
            }.map { it.learnedName }
            val sourceIsShipyard = sourcePerson != null && (headerLabels.any {
                ImportParser.key(it) == ImportParser.key(sourcePerson)
            } || Regex("(?i)\\bstoczni\\p{L}*\\b").containsMatchIn(sourcePerson))
            val sourceVariant = variant(request.text)
            val sourceLabels = (listOf(request.text) + parsed.map { it.name }).distinct()
            val candidates = catalog.filter { product ->
                (listOf(product.name) + product.aliases.split(',')).filter(String::isNotBlank).any { label ->
                    sourceLabels.any { labelMatches(it, label) }
                } && (sourceVariant == null || product.variant == null ||
                    variantKey(product.variant) == variantKey(sourceVariant))
            }
            val matching = items.indices.filter { index ->
                val item = items[index]
                val productMatch = item.productId != null && candidates.any { it.id == item.productId } ||
                    sourceLabels.any { labelMatches(it, item.name) } || candidates.any { labelMatches(item.name, it.name) && variantKey(item.variant.orEmpty()) == variantKey(it.variant.orEmpty()) }
                val correctRecipient = sourcePerson == null || (if (sourceIsShipyard) item.recipientKind == "shipyard" else item.recipientKind != "shipyard") &&
                    item.recipientName?.let { name -> recipientLabels.any { labelMatches(it, name) } } == true
                val correctVariant = sourceVariant == null || item.variant?.let {
                    variantKey(it) == variantKey(sourceVariant)
                } == true
                productMatch && correctRecipient && correctVariant
            }
            // One source can legitimately expand into several products; every distinct catalog target must be accounted for.
            val allTargetsCovered = candidates.isEmpty() || candidates.all { p -> matching.any { index ->
                items[index].productId == p.id || items[index].productId == null && labelMatches(items[index].name, p.name) &&
                    variantKey(items[index].variant.orEmpty()) == variantKey(p.variant.orEmpty())
            } }
            // A known product in a sentence does not account for an additional unknown product.
            // Keep the complete fragment for an explicit decision when meaningful words remain.
            val learnedLabels = RecognitionRules.active(rules).filter { rule ->
                rule.ruleType == "PRODUCT" && labelMatches(request.text, rule.sourceLabel) &&
                    parsed.any { ImportParser.key(it.name) == ImportParser.key(rule.learnedName) }
            }.map { it.sourceLabel }
            val accountedLabels = learnedLabels + candidates.flatMap { listOf(it.name) + it.aliases.split(',') } +
                matching.map { items[it].name } + listOfNotNull(sourcePerson)
            val accountedWords = accountedLabels.flatMap(::tokens)
            val syntaxWords = tokens("rozmiar sztuka sztuki sztuk para pary opakowanie opakowania opak paczka paczki metr metry litr litry komplet komplety kpl rolka rolki karton kartony pudelko pudelka dla ciuchy ubranie kombinezon monterskie spawalnicze jednoczesciowy one piece")
            val residual = tokens(request.text).any { word ->
                !word.matches(Regex("m?\\d+")) && accountedWords.none { similar(word, it) } && syntaxWords.none { similar(word, it) }
            }
            if (matching.isNotEmpty() && allTargetsCovered && !residual) matching.forEach { index ->
                val item = items[index]
                val requestedQuantity = sourceQuantity(request.text)
                items[index] = item.copy(sourceIds = item.sourceIds + request.id,
                    sourceText = listOfNotNull(item.sourceText, request.text).distinct().joinToString("\n"), requestedUnit = ProductUnits.explicitUnit(request.text) ?: item.requestedUnit,
                    requiresSourceConfirmation = item.requiresSourceConfirmation || item.sourceIds.isNotEmpty() || matching.size > 1 ||
                        requestedQuantity != null && requestedQuantity != item.quantity)
            } else {
                val guessed = parsed.singleOrNull()
                items += ParsedItem(name = guessed?.name ?: request.text.trimStart('-', '•', ' '),
                    variant = sourceVariant ?: guessed?.variant,
                    quantity = sourceQuantity(request.text) ?: guessed?.quantity ?: 1,
                    unit = ProductUnits.explicitUnit(request.text) ?: guessed?.unit ?: "szt.", confidence = 0f,
                    recipientName = sourcePerson ?: guessed?.recipientName, requestedUnit = ProductUnits.explicitUnit(request.text),
                    sourceIds = setOf(request.id), sourceText = request.text, requiresSourceConfirmation = true,
                    sourceRecipient = sourcePerson)
            }
        }
        return note.copy(items = items.map { item ->
            if (item.sourceIds.isEmpty()) item.copy(requiresSourceConfirmation = true,
                sourceText = "Pozycja propozycji bez jednoznacznego fragmentu źródła: ${item.name}") else item
        }, sourceRequests = requests)
    }

    fun canSave(note: ParsedNote, approved: List<ParsedItem>): Boolean =
        approved.none { it.requiresSourceConfirmation } && note.sourceRequests.all { source ->
            source.id in note.excludedSourceIds || approved.any { source.id in it.sourceIds }
        }

    fun warnings(note: ParsedNote): List<ReviewWarning> = note.reviewWarnings.distinctBy { it.id }
}
