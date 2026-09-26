package pl.magazyn.mobile.data

import pl.magazyn.mobile.domain.ImportParser

/** Historical labels are suggestions; only a unique match across all textual signals can be read as linked. */
object ShipyardResolver {
    data class Resolution(val confirmedId: String?, val candidates: List<String>)

    fun resolve(id: String?, label: String, shipyards: List<ShipyardEntity>,
                leaders: List<ShipyardLeaderLink> = emptyList(), leaderId: String? = null): Resolution {
        if (id != null) return Resolution(id.takeIf { stable -> shipyards.any { it.id == stable } }, emptyList())
        val key = ImportParser.key(label)
        if (key.isBlank()) return Resolution(null, emptyList())
        fun tokens(text: String) = text.split(',').map(ImportParser::key).filter(String::isNotBlank)
        val matching = shipyards.filter { yard ->
            ImportParser.key(yard.name) == key || key in tokens(yard.aliases) || key in tokens(yard.tags)
        }.map { it.id }
        // A leader may strengthen a suggestion, but never confirm a yard or resolve a text conflict.
        val suggested = if (leaderId != null) {
            val led = leaders.filter { it.employeeId == leaderId }.map { it.shipyardId }.toSet()
            (matching + led).distinct()
        } else matching
        return Resolution(matching.singleOrNull(), suggested)
    }
}
