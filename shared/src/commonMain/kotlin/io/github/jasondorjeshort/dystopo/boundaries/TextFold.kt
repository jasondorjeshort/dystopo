package io.github.jasondorjeshort.dystopo.boundaries

/**
 * Case and accent folding for name lookups (`Côte` finds `cote`), by a small table of the Latin letters with
 * diacritics that place names use, since `commonMain` has no Unicode normaliser. Letters outside the table are
 * only lower-cased; typographic apostrophes become `'`.
 */
object TextFold {
    private val TABLE: Map<Char, String> = buildMap {
        fun group(base: String, letters: String) = letters.forEach { this[it] = base }
        group("a", "àáâãäåāăą")
        group("c", "çćĉċč")
        group("d", "ďđð")
        group("e", "èéêëēĕėęě")
        group("g", "ĝğġģ")
        group("h", "ĥħ")
        group("i", "ìíîïĩīĭįı")
        group("j", "ĵ")
        group("k", "ķ")
        group("l", "ĺļľŀł")
        group("n", "ñńņňŉ")
        group("o", "òóôõöøōŏő")
        group("r", "ŕŗř")
        group("s", "śŝşšș")
        group("t", "ţťŧț")
        group("u", "ùúûüũūŭůűų")
        group("w", "ŵ")
        group("y", "ýÿŷ")
        group("z", "źżž")
        group("ae", "æ")
        group("oe", "œ")
        group("ss", "ß")
        group("th", "þ")
        group("'", "‘’ʼ`")
    }

    fun fold(text: String): String {
        val lower = text.lowercase()
        val sb = StringBuilder(lower.length)
        for (c in lower) sb.append(TABLE[c] ?: c.toString())
        return sb.toString()
    }
}
