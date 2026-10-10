package io.github.jasondorjeshort.dystopo.search

/**
 * Accent folding and tokenising for place search, in pure Kotlin (no ICU, no java.text.Normalizer).
 *
 * [fold] lowercases, maps the precomposed Latin letters of Latin-1 and Latin Extended-A (plus the few
 * others place names use) to their base letters through a small table, expands the ligatures (ß, æ,
 * œ, þ), drops combining marks (U+0300..U+036F, for text that arrives decomposed) and turns every
 * apostrophe into '. So "Mönch", "Monch" and "MONCH" fold to the same string. Letters outside the
 * table (Greek, Cyrillic, CJK) pass through lowercased, so they still match themselves.
 *
 * [tokens] splits folded text into runs of letters and digits. An apostrophe inside a word joins
 * ("Andrew's" is "andrews"), and the part after it is a token too when it has three letters or more,
 * so French elisions work both ways ("l'Épée" gives "lepee" and "epee").
 */
object TextFold {
    private val SINGLE: Map<Char, Char> = buildMap {
        fun add(base: Char, variants: String) = variants.forEach { put(it, base) }
        add('a', "àáâãäåāăąǎǟǡǻȁȃạảấầẩẫậắằẳẵặ")
        add('c', "çćĉċč")
        add('d', "ďđð")
        add('e', "èéêëēĕėęěȅȇẹẻẽếềểễệ")
        add('g', "ĝğġģǧ")
        add('h', "ĥħ")
        add('i', "ìíîïĩīĭįıǐȉȋỉị")
        add('j', "ĵ")
        add('k', "ķĸǩ")
        add('l', "ĺļľŀł")
        add('n', "ñńņňŉŋǹ")
        add('o', "òóôõöøōŏőǒǫȍȏọỏốồổỗộớờởỡợơ")
        add('r', "ŕŗřȑȓ")
        add('s', "śŝşšș")
        add('t', "ţťŧț")
        add('u', "ùúûüũūŭůűųǔǖǘǚǜȕȗụủứừửữựư")
        add('w', "ŵẁẃẅ")
        add('y', "ýÿŷỳỹỷỵ")
        add('z', "źżž")
    }
    private val MULTI: Map<Char, String> = mapOf('ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'þ' to "th", 'ĳ' to "ij")
    private const val APOS = '\''
    private const val APOSTROPHES = "'’ʼ`´"

    /** Lowercase, accents removed, ligatures expanded, apostrophes unified to '. Other punctuation stays (see [tokens]). */
    fun fold(text: String): String {
        val lower = text.lowercase()
        val sb = StringBuilder(lower.length)
        for (c in lower) {
            when {
                c.code in 0x300..0x36F -> Unit // combining diacritical marks
                c in APOSTROPHES -> sb.append(APOS)
                else -> {
                    val m = MULTI[c]
                    if (m != null) sb.append(m) else sb.append(SINGLE[c] ?: c)
                }
            }
        }
        return sb.toString()
    }

    /** Folded runs of letters and digits, in order (see the class comment for apostrophes). */
    fun tokens(text: String): List<String> = tokenize(text, elisions = true)

    private fun tokenize(text: String, elisions: Boolean): List<String> {
        val folded = fold(text)
        val out = ArrayList<String>(4)
        val word = StringBuilder()
        var afterApostrophe = -1
        fun flush() {
            if (word.isEmpty()) return
            out += word.toString()
            if (elisions && afterApostrophe in 1..word.length - 3) out += word.substring(afterApostrophe)
            word.clear()
            afterApostrophe = -1
        }
        for (i in folded.indices) {
            val c = folded[i]
            when {
                c.isLetterOrDigit() -> word.append(c)
                c == APOS && word.isNotEmpty() && i + 1 < folded.length && folded[i + 1].isLetterOrDigit() -> afterApostrophe = word.length
                else -> flush()
            }
        }
        flush()
        return out
    }

    /** [fold] with every non-alphanumeric run collapsed to one space: the form names are compared in. */
    fun normalized(text: String): String = tokenize(text, elisions = false).joinToString(" ")

    /**
     * Abbreviations indexed both ways, so "Mt Rainier" finds "Mount Rainier" and the reverse. Only
     * unambiguous ones: "st" could be street, so it is not expanded to saint.
     */
    val SYNONYMS: Map<String, String> = mapOf("mount" to "mt", "mt" to "mount", "fort" to "ft", "ft" to "fort")
}
