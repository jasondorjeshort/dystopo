package io.github.jasondorjeshort.dystopo.app.firstrun

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T237 (ADR 0056 B2): a person reads one noun for a downloaded map, "offline map". This test reads the string literals
 * of the app's sources, the shared route messages the phone shows and the web page (`tools/demo/index.html` and its
 * scripts) for the retired nouns: pack, region pack, offline area, keep offline, and region (the noun for a downloaded
 * map). Code, routes, JSON keys and stored data keep "pack" (ADR 0033), and the shape rule skips most of them: a literal
 * is read when it has a space or a capital first letter (a sentence, or a `const val` holding one; a lone JSON key is
 * not), outside `*Routes.kt` files and lines that call `Log.x(` or `Thread(`. Templates read as `{}`, so `${pack.name}`
 * is code, not the noun.
 *
 * A hit is either reworded in ADR 0056's words or allowed by a line of [ALLOW], `path | a fragment of that sentence |
 * reason`, which any task may add in its own branch: the fragment is part of the one sentence (never a bare word for a
 * whole file), so a new user-visible "pack" in an allowed file still fails. A line that allows nothing fails too.
 */
class RetiredNounsTest {
    private val root = File("..")

    /** The sources read, by path from the repository root. */
    private val sources: List<File> = buildList {
        addAll(File(root, "androidApp/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && !it.name.endsWith("Routes.kt") })
        addAll(File(root, "shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/routing").walkTopDown().filter { it.isFile && it.extension == "kt" })
        addAll(File(root, "tools/demo").listFiles().orEmpty().filter { it.isFile && (it.extension == "js" || it.extension == "html") })
    }.sortedBy { it.path }

    private fun rel(f: File): String = f.relativeTo(root).invariantSeparatorsPath

    private data class Allow(val path: String, val fragment: String, val reason: String, val lineNo: Int)

    private val allows: List<Allow> by lazy {
        File(ALLOW).readLines().mapIndexedNotNull { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            val parts = line.split(" | ")
            require(parts.size == 3 && parts.all { it.isNotBlank() }) { "$ALLOW:${i + 1}: want `path | fragment | reason`, got \"$line\"" }
            require(' ' in parts[1].trim()) {
                "$ALLOW:${i + 1}: the fragment \"${parts[1]}\" must be part of one sentence (words with a space between), not a bare word"
            }
            Allow(parts[0].trim(), parts[1].trim(), parts[2].trim(), i + 1)
        }
    }

    @Test
    fun noRetiredNounWhereAPersonReads() {
        val problems = ArrayList<String>()
        val used = HashSet<Allow>()
        var read = 0
        for (f in sources) {
            val path = rel(f)
            val lines = f.readLines()
            for ((line, text) in Literals.of(f)) {
                if (!(' ' in text || text.firstOrNull()?.isUpperCase() == true)) continue
                val code = lines.getOrNull(line - 1).orEmpty()
                if (f.extension == "kt" && SKIP_LINE.containsMatchIn(code)) continue
                read++
                val noun = retired(text) ?: continue
                val allow = allows.firstOrNull { path.endsWith(it.path) && it.fragment in text }
                if (allow != null) { used += allow; continue }
                problems += "$path:$line \"$text\" (retired: $noun)"
            }
        }
        val stale = allows.filter { it !in used }.map { "$ALLOW:${it.lineNo}: \"${it.path} | ${it.fragment}\" allows nothing now; remove the line" }
        assertTrue(read > 2000, "expected to read the app's sentences (read $read): has the lexer or a path changed?")
        assertEquals(
            emptyList(), problems + stale,
            "retired nouns in user-visible text (ADR 0056: \"offline map\"). Reword each in those words, or add " +
                "`path | a fragment of the sentence | reason` to $ALLOW",
        )
    }

    @Test
    fun theNounsAreFound() {
        assertEquals("pack", retired("Import pack files"))
        assertEquals("pack", retired("Packs"))
        assertEquals("offline area", retired("Offline areas (pinned)"))
        assertEquals("keep offline", retired("Keep offline around here"))
        assertEquals("keep offline", retired("Trips are not kept offline automatically"))
        assertEquals("keep offline", retired("Keep each trip offline:"))
        assertEquals("region", retired("Download this region"))
        assertEquals(null, retired("Offline maps, in the budget"))
        assertEquals(null, retired("Packages and backpacks"))
    }

    @Test
    fun theLexerReadsLiteralsNotCode() {
        val kt = """
            val a = "Hello ${'$'}{if (n == 1) "1 map" else "${'$'}n maps"} on ${'$'}server" // "a comment pack"
            /* "block pack" */ val c = '"'; val d = '\''
            val e = ${"\"\"\""}raw ${'$'}{x} text${"\"\"\""}
            val f = "esc \" quote ›"
        """.trimIndent()
        val got = Literals.kotlin(kt).map { it.second }
        assertEquals(listOf("1 map", "{} maps", "Hello {} on {}", "raw {} text", "esc \" quote ›"), got)
        val js = """
            const r = s.match(/filename="([^"]+)"/); // "comment pack"
            say(`On ${'$'}{p ? "map " + p : "none"} now`, 'single pack');
        """.trimIndent()
        assertEquals(listOf("map ", "none", "On {} now", "single pack"), Literals.js(js).map { it.second })
        val html = "<!-- a pack comment -->\n<label title=\"Build packs\">Map name <input placeholder=\"from the view\"></label>\n<script>const x = \"in script\";</script><style>p { }</style>"
        assertEquals(listOf("Build packs", "Map name", "from the view", "in script"), Literals.html(html).map { it.second })
    }

    private companion object {
        const val ALLOW = "src/test/resources/retired-nouns-allow.txt"
        val SKIP_LINE = Regex("""\bLog\.[a-z]\(|\bThread\(""")
        val RETIRED: List<Pair<String, Regex>> = listOf(
            "pack" to Regex("""\bpacks?\b""", RegexOption.IGNORE_CASE),
            "offline area" to Regex("""\boffline areas?\b""", RegexOption.IGNORE_CASE),
            "keep offline" to Regex("""\b(?:keep|keeps|keeping|kept)(?: [\w']+){0,3} offline\b""", RegexOption.IGNORE_CASE),
            "region" to Regex("""\bregions?\b""", RegexOption.IGNORE_CASE),
        )

        fun retired(text: String): String? = RETIRED.firstOrNull { it.second.containsMatchIn(text) }?.first
    }
}

/**
 * The string literals of a Kotlin, JavaScript or HTML source with the line each starts on: comments skipped, a template's
 * code (`${...}`, Kotlin's `$name`) read as code and replaced by `{}` in the text, the strings inside it read on their own.
 */
internal object Literals {
    fun of(f: File): List<Pair<Int, String>> = when (f.extension) {
        "kt" -> kotlin(f.readText())
        "js" -> js(f.readText())
        else -> html(f.readText())
    }

    fun kotlin(src: String): List<Pair<Int, String>> = Lexer(src, js = false).run { code(false); out }

    fun js(src: String, firstLine: Int = 1): List<Pair<Int, String>> = Lexer(src, js = true, line = firstLine).run { code(false); out }

    /** Text nodes and the `title`, `placeholder`, `aria-label` and `alt` attributes outside scripts and styles; scripts as JavaScript. */
    fun html(src: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        fun lineAt(i: Int) = 1 + src.substring(0, i).count { it == '\n' }
        var i = 0
        val text = StringBuilder()
        var textAt = 0
        fun flush() {
            val t = text.toString().replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty()) out += lineAt(textAt) to t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            text.clear()
        }
        while (i < src.length) {
            when {
                src.startsWith("<!--", i) -> { flush(); i = src.indexOf("-->", i).let { if (it < 0) src.length else it + 3 } }
                src.startsWith("<", i) && i + 1 < src.length && (src[i + 1].isLetter() || src[i + 1] == '/') -> {
                    flush()
                    val end = src.indexOf('>', i).let { if (it < 0) src.length else it }
                    val tag = src.substring(i, end)
                    for (m in ATTR.findAll(tag)) out += lineAt(i + m.range.first) to m.groupValues[2]
                    val name = tag.drop(1).takeWhile { it.isLetterOrDigit() }.lowercase()
                    i = end + 1
                    if (name == "script" || name == "style") {
                        val close = src.indexOf("</$name", i).let { if (it < 0) src.length else it }
                        if (name == "script") out += js(src.substring(i, close), lineAt(i))
                        i = close
                    }
                }
                else -> { if (text.isEmpty()) textAt = i; text.append(src[i]); i++ }
            }
        }
        flush()
        return out
    }

    private val ATTR = Regex("""\b(title|placeholder|aria-label|alt)="([^"]*)"""")

    private class Lexer(val s: String, val js: Boolean, var line: Int = 1) {
        var i = 0
        val out = ArrayList<Pair<Int, String>>()
        /** The last significant code character, to tell a JavaScript regex literal from a division. */
        var last = '('

        fun ch(k: Int = 0): Char = if (i + k < s.length) s[i + k] else '\u0000'
        fun adv() { if (s[i] == '\n') line++; i++ }

        /** Code until the end, or until the `}` that closes a template's `${` when [inTemplate]. */
        fun code(inTemplate: Boolean) {
            var depth = 0
            while (i < s.length) {
                val c = ch()
                when {
                    c == '/' && ch(1) == '/' -> while (i < s.length && ch() != '\n') adv()
                    c == '/' && ch(1) == '*' -> {
                        adv(); adv()
                        while (i < s.length && !(ch() == '*' && ch(1) == '/')) adv()
                        if (i < s.length) { adv(); adv() }
                    }
                    !js && s.startsWith("\"\"\"", i) -> { repeat(3) { adv() }; string("\"\"\"", escapes = false, multiline = true, templates = true); last = 'a' }
                    c == '"' -> { adv(); string("\"", escapes = true, multiline = false, templates = !js); last = 'a' }
                    js && c == '\'' -> { adv(); string("'", escapes = true, multiline = false, templates = false); last = 'a' }
                    js && c == '`' -> { adv(); string("`", escapes = true, multiline = true, templates = true); last = 'a' }
                    !js && c == '\'' -> { charLiteral(); last = 'a' }
                    js && c == '/' && last in REGEX_BEFORE -> { regex(); last = 'a' }
                    c == '{' -> { depth++; adv(); last = c }
                    c == '}' -> {
                        if (inTemplate && depth == 0) { adv(); return }
                        depth--; adv(); last = c
                    }
                    else -> { if (!c.isWhitespace()) last = c; adv() }
                }
            }
        }

        fun string(delim: String, escapes: Boolean, multiline: Boolean, templates: Boolean) {
            val start = line
            val sb = StringBuilder()
            while (i < s.length) {
                if (s.startsWith(delim, i)) { repeat(delim.length) { adv() }; break }
                val c = ch()
                if (c == '\n' && !multiline) break // unterminated: stop at the line's end
                if (escapes && c == '\\') {
                    val e = ch(1)
                    adv(); adv()
                    when (e) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'u' -> if (i + 4 <= s.length) { sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: '?'); repeat(4) { adv() } }
                        else -> sb.append(e)
                    }
                    continue
                }
                if (templates && c == '$' && ch(1) == '{') { adv(); adv(); code(inTemplate = true); sb.append("{}"); continue }
                if (templates && !js && c == '$' && (ch(1).isLetter() || ch(1) == '_')) {
                    adv()
                    while (ch().isLetterOrDigit() || ch() == '_') adv()
                    sb.append("{}")
                    continue
                }
                sb.append(c); adv()
            }
            out += start to sb.toString()
        }

        fun charLiteral() {
            adv()
            if (ch() == '\\') { adv(); adv(); while (i < s.length && ch() != '\'' && ch() != '\n') adv() } else if (i < s.length) adv()
            if (ch() == '\'') adv()
        }

        fun regex() {
            adv()
            var inClass = false
            while (i < s.length && ch() != '\n') {
                val c = ch()
                when {
                    c == '\\' -> { adv(); if (i < s.length) adv(); continue }
                    c == '[' -> inClass = true
                    c == ']' -> inClass = false
                    c == '/' && !inClass -> { adv(); break }
                }
                adv()
            }
        }

        companion object {
            const val REGEX_BEFORE = "(,=:[!&|?{};+-*%<>~^"
        }
    }
}
