package pl.magazyn.mobile.domain

fun matchesSearch(query: String, vararg fields: String): Boolean {
    val tokens = ImportParser.key(query).split(Regex("\\s+")).filter(String::isNotBlank)
    if (tokens.isEmpty()) return true
    val normalized = ImportParser.key(fields.joinToString(" "))
    val searchable = normalized.replace(" ", "")
    val words = normalized.split(' ').filter(String::isNotBlank)
    return tokens.all { rawToken ->
        val token = rawToken.replace(" ", "")
        searchable.contains(token) || (token.length >= 3 && words.any { word ->
            ((token.length - 1)..(token.length + 1)).any { prefixLength ->
                prefixLength > 0 && word.length >= prefixLength &&
                    editDistanceAtMostOne(token, word.take(prefixLength))
            }
        })
    }
}

private fun editDistanceAtMostOne(left: String, right: String): Boolean {
    if (left == right) return true
    if (kotlin.math.abs(left.length - right.length) > 1) return false
    var i = 0
    var j = 0
    var differences = 0
    while (i < left.length && j < right.length) {
        if (left[i] == right[j]) { i++; j++; continue }
        if (++differences > 1) return false
        when {
            left.length > right.length -> i++
            right.length > left.length -> j++
            else -> { i++; j++ }
        }
    }
    if (i < left.length || j < right.length) differences++
    return differences <= 1
}
