package pl.magazyn.mobile.data

import pl.magazyn.mobile.domain.ImportParser

/** Historical labels are suggestions; only a unique exact match can be read as linked. */
object ShipyardResolver {
    data class Resolution(val confirmedId: String?, val candidates: List<String>)

    fun resolve(id: String?, label: String, shipyards: List<ShipyardEntity>,
                leaders: List<ShipyardLeaderLink> = emptyList(), leaderId: String? = null): Resolution {
        if (id != null) return Resolution(id.takeIf { stable -> shipyards.any { it.id == stable } }, emptyList())
        val key = ImportParser.key(label)
        if (key.isBlank()) return Resolution(null, emptyList())
        fun tokens(text: String) = text.split(',').map(ImportParser::key).filter(String::isNotBlank)
        val matching = shipyards.mapNotNull { yard ->
            val rank = when {
                ImportParser.key(yard.name) == key -> 0
                key in tokens(yard.aliases) -> 1
                key in tokens(yard.tags) -> 2
                else -> return@mapNotNull null
            }
            yard.id to rank
        }
        val preferred = matching.minOfOrNull { it.second }
        val candidates = matching.filter { it.second == preferred }.map { it.first }
        val all = matching.map { it.first }
        // A leader may strengthen a suggestion, but never override conflicting exact names.
        val suggested = if (leaderId != null) {
            val led = leaders.filter { it.employeeId == leaderId }.map { it.shipyardId }.toSet()
            (all + led).distinct()
        } else all
        return Resolution(candidates.singleOrNull(), suggested)
    }
}
