package io.github.jasondorjeshort.dystopo.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T240 (T226's follow-up): Compose state a composition reads must be made on the main thread before any composition,
 * not by whichever thread touches its owner first (`docs/reference/android-app.md`, The UI thread and the watchdog).
 * This test reads the app's sources for every named `object` whose top level (outside every function and nested
 * block) holds `mutableStateOf` or a sibling (`mutableIntStateOf`, `mutableStateListOf`, `derivedStateOf`, ...).
 * Each must either be prepared from `DystopoApp.onCreate` or `MainActivity.onCreate` or be a `*UiState` object. Prepared
 * is: its name appears as `Name.member` in either `onCreate`'s body, or in the body of the `prepare()` of an owner
 * those name (one level of calls, as `Tracking.prepare()` touches `TerrainSource.display`).
 *
 * The rest are named in [ALLOW], `path | Object | reason`, one line each: a follow-up, not a pass. A line that allows
 * nothing (the object is gone, prepared now or a `*UiState`) fails too. A regex read, not a parse: the checks that
 * the read found what it expects name the pattern they could not find.
 */
class ComposeStatePrepareTest {
    private val root = File("..")
    private val mainDir = File(root, "androidApp/src/main/kotlin")

    private fun rel(f: File): String = f.relativeTo(root).invariantSeparatorsPath

    private val sources: List<File> by lazy { mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList() }

    private data class Allow(val path: String, val name: String, val reason: String, val lineNo: Int)

    private val allows: List<Allow> by lazy {
        File(ALLOW).readLines().mapIndexedNotNull { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            val parts = line.split(" | ")
            require(parts.size == 3 && parts.all { it.isNotBlank() }) { "$ALLOW:${i + 1}: want `path | Object | reason`, got \"$line\"" }
            Allow(parts[0].trim(), parts[1].trim(), parts[2].trim(), i + 1)
        }
    }

    @Test
    fun everyObjectWithComposeStateIsPreparedOrAllowed() {
        val found = ArrayList<Pair<String, Found>>()
        val prepares = ArrayList<Prepare>()
        val onCreates = ArrayList<String>()
        val unbalanced = ArrayList<String>()
        for (f in sources) {
            val scan = scan(f.readText())
            found += scan.objects.map { rel(f) to it }
            prepares += scan.prepares
            if (f.name == "DystopoApp.kt" || f.name == "MainActivity.kt") onCreates += scan.onCreates
            if (scan.unbalanced) unbalanced += rel(f)
        }
        assertEquals(emptyList(), unbalanced, "the braces of these files did not balance after strings and comments were blanked: has the reader's string handling failed?")
        assertTrue(onCreates.size == 2, "expected `fun onCreate(` bodies in DystopoApp.kt and MainActivity.kt (found ${onCreates.size}): has that pattern changed?")
        val named = onCreates.flatMap { REF.findAll(it).map { m -> m.groupValues[1] } }.toSet()
        val prepared = named + prepares.filter { it.owner in named }.flatMap { REF.findAll(it.body).map { m -> m.groupValues[1] } }
        assertTrue(
            "Areas" in prepared && "MapViewport" in prepared && "TrackingUiState" in prepared,
            "expected Areas, MapViewport and TrackingUiState among the prepared names (read $prepared): has `X.prepare()` in onCreate or Tracking.prepare's body changed?",
        )
        assertTrue(found.size > 15, "expected to find the app's objects holding Compose state (found ${found.size}): has the pattern `object Name { ... mutableStateOf` changed?")

        val problems = ArrayList<String>()
        val used = HashSet<Allow>()
        for ((path, o) in found) {
            if (o.name in prepared || o.name.endsWith("UiState")) continue
            val allow = allows.firstOrNull { it.name == o.name && path.endsWith(it.path) }
            if (allow != null) { used += allow; continue }
            problems += "$path:${o.line} object ${o.name} holds Compose state and is not prepared"
        }
        val stale = allows.filter { it !in used }.map { "$ALLOW:${it.lineNo}: \"${it.path} | ${it.name}\" allows nothing now (gone, prepared or a *UiState); remove the line" }
        assertEquals(
            emptyList(), problems + stale,
            "Compose state made off the main thread breaks a composition that reads it (T226). Pass either way: call " +
                "`Name.prepare()` (touching the state) from DystopoApp.onCreate or MainActivity.onCreate, or move the state into a `*UiState` object; " +
                "or, for a follow-up, add `path | Name | reason` to $ALLOW",
        )
    }

    @Test
    fun theReaderFindsObjectsStatesAndPrepares() {
        val src = """
            package p
            // object Commented { val a = mutableStateOf(1) }
            object Plain { val n = 1; fun f() { val x = mutableStateOf(2) } }
            object Holds { val s = "}{ object Fake" ; var a by mutableStateOf(0) }
            object Derived { val d: Int by derivedStateOf { 1 } }
            class K(val a: Int)
            fun top() { }
            class Owner { companion object { fun prepare() { Holds.a; Derived.d } } }
            object Short { fun prepare() = Holds.a }
            class App { override fun onCreate() { Holds.prepare() } }
        """.trimIndent()
        val s = scan(src)
        assertEquals(listOf("Holds", "Derived"), s.objects.map { it.name })
        assertEquals(listOf(4, 5), s.objects.map { it.line })
        assertEquals(listOf("Owner", "Short"), s.prepares.map { it.owner })
        assertEquals(1, s.onCreates.size)
        assertEquals(false, s.unbalanced)
    }

    private class Found(val name: String, val line: Int)
    private class Prepare(val owner: String?, val body: String)
    private class Scan(val objects: List<Found>, val prepares: List<Prepare>, val onCreates: List<String>, val unbalanced: Boolean)

    private class Frame(val name: String?, val kind: String?, val header: String, val line: Int) {
        val own = StringBuilder()
        val all = StringBuilder()
    }

    private fun scan(raw: String): Scan {
        val s = Blanker(raw).run()
        val stack = ArrayList<Frame>()
        stack += Frame(null, null, "", 1)
        val objects = ArrayList<Found>()
        val prepares = ArrayList<Prepare>()
        val onCreates = ArrayList<String>()
        var line = 1
        var boundary = -1
        var unbalanced = false
        fun owner(): String? = stack.lastOrNull { it.name != null }?.name
        for (i in s.indices) {
            val c = s[i]
            when (c) {
                '{' -> {
                    val header = s.substring(boundary + 1, i)
                    var name: String? = null
                    var kind: String? = null
                    DECL.findAll(header).lastOrNull()?.let { m ->
                        var rest = header.substring(m.range.last + 1)
                        while (true) {
                            val n = rest.replace(PARENS, " ")
                            if (n == rest) break
                            rest = n
                        }
                        if (!BAD_AFTER.containsMatchIn(rest)) {
                            val k = m.groupValues[1]
                            if (k.startsWith("companion")) { name = owner(); kind = "companion" } else { name = m.groupValues[2].ifEmpty { null }; kind = k }
                        }
                    }
                    stack += Frame(name, kind, header, line)
                    boundary = i
                }
                '}' -> {
                    if (stack.size == 1) {
                        unbalanced = true
                    } else {
                        val f = stack.removeAt(stack.size - 1)
                        if (f.kind == "object" && f.name != null && STATE.containsMatchIn(f.own)) objects += Found(f.name, f.line)
                        if (FUN_PREPARE.containsMatchIn(f.header)) prepares += Prepare(owner(), f.all.toString())
                        if (FUN_ONCREATE.containsMatchIn(f.header)) onCreates += f.all.toString()
                    }
                    boundary = i
                }
                ';' -> boundary = i
                '\n' -> line++
            }
            if (c != '{' && c != '}') {
                stack.last().own.append(c)
                for (f in stack) f.all.append(c)
                if (c == 'f' && s.startsWith("fun", i)) {
                    val m = PREPARE_EXPR.toPattern().matcher(s).region(i, s.length)
                    if (m.lookingAt()) prepares += Prepare(owner(), m.group())
                }
            }
        }
        if (stack.size != 1) unbalanced = true
        return Scan(objects, prepares, onCreates, unbalanced)
    }

    /** A Kotlin source with comments and the insides of string and character literals replaced by spaces (newlines kept). */
    private class Blanker(val src: String) {
        val out = StringBuilder(src.length)
        var i = 0

        fun at(k: Int) = if (i + k < src.length) src[i + k] else '\u0000'
        fun blank() { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
        fun keep() { out.append(src[i]); i++ }

        fun run(): String { code(false); return out.toString() }

        fun code(inTemplate: Boolean) {
            var depth = 0
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '/' && at(1) == '/' -> while (i < src.length && src[i] != '\n') blank()
                    c == '/' && at(1) == '*' -> {
                        blank(); blank()
                        while (i < src.length && !(src[i] == '*' && at(1) == '/')) blank()
                        if (i < src.length) { blank(); blank() }
                    }
                    src.startsWith("\"\"\"", i) -> { repeat(3) { keep() }; string("\"\"\"", raw = true) }
                    c == '"' -> { keep(); string("\"", raw = false) }
                    c == '\'' -> {
                        keep()
                        if (at(0) == '\\') { blank(); blank() }
                        while (i < src.length && src[i] != '\'' && src[i] != '\n') blank()
                        if (i < src.length && src[i] == '\'') keep()
                    }
                    c == '{' -> { depth++; keep() }
                    c == '}' -> {
                        if (inTemplate && depth == 0) { blank(); return }
                        depth--; keep()
                    }
                    else -> keep()
                }
            }
        }

        fun string(delim: String, raw: Boolean) {
            while (i < src.length) {
                if (src.startsWith(delim, i)) { repeat(delim.length) { keep() }; return }
                val c = src[i]
                if (c == '\n' && !raw) return
                if (!raw && c == '\\') { blank(); if (i < src.length) blank(); continue }
                if (c == '$' && at(1) == '{') { blank(); blank(); code(inTemplate = true); continue }
                blank()
            }
        }
    }

    private companion object {
        const val ALLOW = "src/test/resources/compose-state-prepare-allow.txt"
        val STATE = Regex("""\b(?:mutableStateOf|mutableIntStateOf|mutableLongStateOf|mutableFloatStateOf|mutableDoubleStateOf|mutableStateListOf|mutableStateMapOf|mutableStateSetOf|derivedStateOf)\b""")
        val REF = Regex("""\b([A-Z]\w*)\.\w+""")
        val DECL = Regex("""\b(companion\s+object|object|class|interface)\b\s*(\w*)""")
        val BAD_AFTER = Regex("""\b(fun|val|var|class|object|interface|import|typealias)\b|[=;]""")
        val PARENS = Regex("""\([^()]*\)""")
        val FUN_PREPARE = Regex("""\bfun\s+prepare\s*\(""")
        val FUN_ONCREATE = Regex("""\bfun\s+onCreate\s*\(""")
        val PREPARE_EXPR = Regex("""fun\s+prepare\s*\(\s*\)\s*=\s*[^\n]*""")
    }
}
