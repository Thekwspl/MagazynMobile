package pl.magazyn.mobile.data

/** Czysta logika kompatybilności kopii — niezależna od Androida i łatwa do testowania. */
internal object BackupCompatibility {
    fun isSupportedDatabaseVersion(backupDatabaseVersion: Int): Boolean =
        backupDatabaseVersion in 1..DATABASE_SCHEMA_VERSION

    /**
     * Porównanie semantyczne używane tylko dla ewentualnych starszych metadanych
     * tekstowych. Nie porównuje wersji leksykograficznie ("0.10.0" > "0.9.13").
     */
    fun compareVersionNames(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        val count = maxOf(leftParts.size, rightParts.size)
        repeat(count) { index ->
            val compare = leftParts.getOrElse(index) { 0 }.compareTo(rightParts.getOrElse(index) { 0 })
            if (compare != 0) return compare
        }
        return 0
    }

    private fun versionParts(version: String): List<Int> =
        version.trim().removePrefix("v").split('.')
            .map { part -> part.toIntOrNull() ?: 0 }
}
