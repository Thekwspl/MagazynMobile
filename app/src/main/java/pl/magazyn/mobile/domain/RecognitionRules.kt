package pl.magazyn.mobile.domain

import pl.magazyn.mobile.data.ParserLearningRuleEntity

/** One phone-owned representation used by Offline, Gemini and the synchronized Codex catalog. */
object RecognitionRules {
    const val instructions = """Jawne informacje z wiadomości (kolor, marka, model, wariant, rozmiar) mają pierwszeństwo przed regułami. Potem stosuj dokładne aktywne reguły PRODUCT/PERSON/POSITION/PATTERN w ich zakresie, jednoznaczny alias/katalog, ogólne reguły; przy niepewności pytaj. Nie wymyślaj brakujących danych ani ID.
Samo ogólne słowo „kask” oznacza „Kask Biały”. „kask czerwony”, „kask 3M” i inne jawne doprecyzowania nie oznaczają białego.
Firmowe reguły odzieży: ogólny „kombinezon”, „ciuchy”, „ubranie”, „komplet” oznacza spodnie i bluzę. Monterskie: spodnie monterskie + bluza monterska; spawalnicze: spodnie spawalnicze + bluza spawalnicza. Jednoczęściowy/jednoczesciowy/one-piece pozostaje jednym produktem. Skrót mXX dla XX >= 48 oznacza spodnie monterskie i bluzę monterską w rozmiarze XX; sXX — spodnie spawalnicze i bluzę spawalniczą. XX < 48 oznacza buty monterskie/spawalnicze. Jawne „spodnie” albo „bluza” przed skrótem oznacza tylko tę część. Nie rozszerzaj kompletu dwukrotnie.
Jeśli brak ilości, domyślna ilość to 1. Zachowaj podaną ilość. Nie zgaduj rozmiarów ani wariantów. Nieistniejący, ukryty lub zarchiwizowany produkt wskazany regułą wymaga rozstrzygnięcia; żadnych sztucznych ID."""

    fun active(rules: List<ParserLearningRuleEntity>) = rules.filter { it.isEnabled && it.ruleType in setOf("PRODUCT", "PERSON", "POSITION", "PATTERN") }
    fun patternKind(raw: String, rules: List<ParserLearningRuleEntity>): ParsedInputKind? = active(rules)
        .firstOrNull { it.ruleType == "PATTERN" && it.sourceLabel.isNotBlank() && raw.contains(it.sourceLabel, true) }
        ?.learnedName?.uppercase()?.let { runCatching { ParsedInputKind.valueOf(it) }.getOrNull() }

    fun json(rules: List<ParserLearningRuleEntity>) = org.json.JSONObject().put("instructions", instructions)
        .put("learned", org.json.JSONArray().apply { active(rules).forEach { rule -> put(org.json.JSONObject()
            .put("id", rule.id).put("type", rule.ruleType).put("triggerKey", rule.triggerKey).put("sourceLabel", rule.sourceLabel)
            .put("learnedName", rule.learnedName).put("learnedVariant", rule.learnedVariant ?: org.json.JSONObject.NULL)
            .put("learnedUnit", rule.learnedUnit).put("resultExtra", rule.resultExtra)) } })

    fun isOnePiece(item: ParsedItem): Boolean {
        val key = ImportParser.key(item.name + " " + item.variant.orEmpty())
        return key.contains("jednoczesci") || key.contains("one-piece") || key.contains("one piece")
    }

    fun product(item: ParsedItem, rules: List<ParserLearningRuleEntity>): ParsedItem {
        val rule = active(rules).firstOrNull { it.ruleType == "PRODUCT" && it.triggerKey == ImportParser.key(item.name) }
        if (item.variant != null && rule != null && item.variant != rule.learnedVariant) return item
        return rule?.let { item.copy(name = it.learnedName, variant = item.variant ?: it.learnedVariant, unit = it.learnedUnit) } ?: item
    }

    fun products(item: ParsedItem, rules: List<ParserLearningRuleEntity>): List<ParsedItem> {
        val learned = product(item, rules)
        return sortRecognizedPackageItems(expandWarehouseClothingConvention(learned)).map {
            if (ImportParser.key(it.name) == "kask" && it.variant == null) it.copy(name = "Kask Biały") else it
        }
    }

    fun note(note: ParsedNote, raw: String, rules: List<ParserLearningRuleEntity>, mode: QuickInputMode): ParsedNote {
        if (note.agentProposal || mode == QuickInputMode.NOTE) return note
        val active = active(rules)
        fun person(person: ParsedPerson): ParsedPerson {
            val name = active.firstOrNull { it.ruleType == "PERSON" && it.triggerKey == ImportParser.key(person.fullName) }?.learnedName ?: person.fullName
            val position = active.firstOrNull { it.ruleType == "POSITION" && it.triggerKey == ImportParser.key(person.position.orEmpty()) }?.learnedName ?: person.position
            return person.copy(fullName = normalizeFullPersonName(name), position = position)
        }
        val people = note.people.map(::person)
        val kind = mode.preferredKind ?: patternKind(raw, rules) ?: note.kind
        if (kind == ParsedInputKind.NOTE) return ParsedNote(null, emptyList(), kind = kind, analyzedByAi = note.analyzedByAi)
        return note.copy(person = note.person?.let(::person), people = people, kind = kind,
            items = note.items.map { item -> item.copy(recipientName = item.recipientName?.let { person(ParsedPerson(it, null, 1f)).fullName }) })
    }
}
