package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.controls.ControlItem
import io.github.jasondorjeshort.dystopo.controls.Platform

/**
 * T208 (ADR 0053 §5 part b): reads the web page's interactive elements out of its three files (`index.html`,
 * `trips.js`, `trip-sync.js`) as text, the way `SlopeLegendTest` and `MarkerTableTest` read the page, and checks them
 * against the placement manifest:
 *
 * - elements by tag (`input`, `select`, `button`, `summary`) in markup and in script strings, with their `id` and
 *   `data-control` attributes;
 * - each `createElement` call of those tags assigned to a name, and the `id` and `data-control` set on that name on
 *   the next lines (`x.id = "..."`, `x.dataset.control = "..."`, `x.setAttribute("data-control", "...")`);
 * - `addControl(...)` of a `new maplibregl.X(...)`, directly or through a name, as the element `maplibregl.X`.
 *
 * The rules ([refusals]), each refusal naming its lift (ADR 0053's table): (1) an element in scope with neither
 * `data-control="<id>"` nor a place inside an element with `data-control="flow:<parent-id>"`; (2) a `data-control` id
 * (or flow parent) the manifest lacks, or an element whose id the item's web placement does not list; (3) a manifest
 * web element the page lacks. Build step 1's scope for rule 1 ([inStep1Scope]): the controls inside the Map options
 * section (`details#options`, not its own summary, which step 2 places with the panel's other disclosures) and every
 * element the manifest names; build step 2 widens it to every element of the three files.
 *
 * Regex readers: when an anchor they rely on is missing they fail naming the pattern ([anchorMissing]), not with a
 * parse error.
 */
object WebControls {
    val FILES = listOf("tools/demo/index.html", "tools/demo/trips.js", "tools/demo/trip-sync.js")
    const val MANIFEST = "shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/controls/MapControls.kt"
    const val ATTR = "data-control"
    const val FLOW = "flow:"

    /** The Map options section of `index.html`: step 1's scope for rule 1. */
    const val SCOPE_OPEN = "<details id=\"options\">"
    const val SCOPE_CLOSE = "</details>"

    data class Element(val file: String, val line: Int, val offset: Int, val tag: String, val id: String?, val control: String?, val source: String) {
        val label: String get() = "<$tag" + (id?.let { " id=\"$it\"" } ?: "") + ">" + if (source == "markup") "" else " ($source)"
        val where: String get() = "$file:$line"
    }

    private val TAG = Regex("""<(input|select|button|summary)(?=[\s>/])([^>]*)>""")
    private val CREATE = Regex("""(?:\b(\w+)\s*=\s*)?document\.createElement\(\s*["'](input|select|button|summary)["']\s*\)""")
    private val NEW_CONTROL = Regex("""\b(\w+)\s*=\s*new\s+maplibregl\.(\w+)\s*\(""")
    private val ADD_CONTROL = Regex("""addControl\(\s*(?:new\s+maplibregl\.(\w+)|(\w+))\s*[,)(]""")
    private val FLOW_OPEN = Regex("""<(\w+)(?=[\s>])[^>]*\bdata-control\s*=\s*["']flow:([^"']+)["'][^>]*>""")
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    private fun attr(attrs: String, name: String): String? =
        Regex("""(?<![\w-])${Regex.escape(name)}\s*=\s*["']([^"']*)["']""").find(attrs)?.groupValues?.get(1)

    private fun lineOf(text: String, offset: Int): Int {
        var n = 1
        for (i in 0 until offset) if (text[i] == '\n') n++
        return n
    }

    /** Blanks HTML comments (keeping offsets and lines) so a tag named in one is not read as an element. */
    private fun withoutComments(text: String): String =
        COMMENT.replace(text) { m -> m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("") }

    /** Every interactive element [text] (the file [file]) declares. */
    fun read(file: String, text: String): List<Element> {
        val t = withoutComments(text)
        val out = ArrayList<Element>()
        for (m in TAG.findAll(t)) {
            val attrs = m.groupValues[2]
            out += Element(file, lineOf(t, m.range.first), m.range.first, m.groupValues[1], attr(attrs, "id"), attr(attrs, ATTR), "markup")
        }
        val lines = t.split('\n')
        for (m in CREATE.findAll(t)) {
            val name = m.groupValues[1].ifEmpty { null }
            val line = lineOf(t, m.range.first)
            var id: String? = null
            var control: String? = null
            if (name != null) {
                // The attributes set on the name on this line and the next three.
                val after = lines.subList(line - 1, minOf(lines.size, line + 3)).joinToString("\n")
                val n = Regex.escape(name)
                id = Regex("""\b$n\.id\s*=\s*["']([^"']*)["']""").find(after)?.groupValues?.get(1)
                control = Regex("""\b$n\.dataset\.control\s*=\s*["']([^"']*)["']""").find(after)?.groupValues?.get(1)
                    ?: Regex("""\b$n\.setAttribute\(\s*["']data-control["']\s*,\s*["']([^"']*)["']""").find(after)?.groupValues?.get(1)
            }
            out += Element(file, line, m.range.first, m.groupValues[2], id, control, "createElement" + (name?.let { " $it" } ?: ""))
        }
        val classes = NEW_CONTROL.findAll(t).associate { it.groupValues[1] to it.groupValues[2] }
        for (m in ADD_CONTROL.findAll(t)) {
            val cls = m.groupValues[1].ifEmpty { classes[m.groupValues[2]] } ?: continue // a control of the page's own (ScaleBarControl)
            out += Element(file, lineOf(t, m.range.first), m.range.first, "maplibregl.$cls", null, null, "addControl")
        }
        return out.sortedBy { it.offset }
    }

    /** The spans of the elements in [text] that carry `data-control="flow:<parent>"`, as offset ranges. */
    fun flowSpans(text: String): List<IntRange> {
        val t = withoutComments(text)
        return FLOW_OPEN.findAll(t).map { m ->
            val tag = m.groupValues[1]
            var depth = 1
            var i = m.range.last + 1
            val open = Regex("""<$tag(?=[\s>])""")
            val close = "</$tag>"
            var end = t.length - 1
            while (i < t.length) {
                val nextClose = t.indexOf(close, i).takeIf { it >= 0 } ?: break
                val nextOpen = open.find(t, i)?.range?.first
                if (nextOpen != null && nextOpen < nextClose) { depth++; i = nextOpen + 1; continue }
                depth--
                i = nextClose + close.length
                if (depth == 0) { end = nextClose; break }
            }
            m.range.first..end
        }.toList()
    }

    /** The Map options section's span in `index.html`, or null when its anchor is missing. */
    fun scopeSpan(indexHtml: String): IntRange? {
        val t = withoutComments(indexHtml)
        val at = t.indexOf(SCOPE_OPEN).takeIf { it >= 0 } ?: return null
        val end = t.indexOf(SCOPE_CLOSE, at).takeIf { it >= 0 } ?: return null
        return at..end
    }

    fun anchorMissing(file: String, pattern: String) =
        "$file: the reader could not find $pattern (ADR 0053 build step 1's scope, the Map options section); restore it, " +
            "or move the scope in devtool/src/test/.../WebControls.kt (SCOPE_OPEN) to where the map options now are"

    /** Rule 1's scope in build step 1 (see the class comment). */
    fun inStep1Scope(e: Element, scope: IntRange?, webElements: Set<String>, indexHtml: String?): Boolean {
        if (e.id != null && e.id in webElements) return true
        if (e.file != FILES[0] || scope == null || indexHtml == null || e.offset !in scope) return false
        return e.offset != indexHtml.indexOf("<summary", scope.first) // the section's own summary: step 2's
    }

    /**
     * The refusals for the page [texts] (file → text) against [items]; empty when the page follows the manifest.
     * [allInScope] applies rule 1 to every element (build step 2's scope; the reader's own tests use it).
     */
    fun refusals(texts: Map<String, String>, items: List<ControlItem>, allInScope: Boolean = false): List<String> {
        val out = ArrayList<String>()
        val byId = items.associateBy { it.id }
        val webElements = items.flatMap { item -> item.placements[Platform.WEB]?.elements.orEmpty().map { it to item.id } }.toMap()
        val elements = texts.flatMap { (f, t) -> read(f, t) }
        val index = texts[FILES[0]]
        val scope = index?.let { scopeSpan(it) }
        if (index != null && scope == null && !allInScope) out += anchorMissing(FILES[0], SCOPE_OPEN + " ... " + SCOPE_CLOSE)
        val flows = texts.mapValues { (_, t) -> flowSpans(t) }
        for (e in elements) {
            val inFlow = flows[e.file].orEmpty().any { e.offset in it && it.first != e.offset }
            // Rule 1: an element with neither attribute.
            if (e.control == null && !inFlow && (allInScope || inStep1Scope(e, scope, webElements.keys, index))) {
                val item = e.id?.let { webElements[it] }
                out += "${e.where}: ${e.label} has no $ATTR attribute. Fix: add $ATTR=\"${item ?: "<id>"}\" naming its item " +
                    "(an item with a web placement in $MANIFEST, added there if the control is new), or $ATTR=\"$FLOW<parent-id>\" " +
                    "if it is an action inside the flow of a placed item"
                continue
            }
            val c = e.control ?: continue
            // Rule 2: an id the manifest lacks, or an element the item's web placement does not list.
            val id = c.removePrefix(FLOW)
            val item = byId[id]
            if (item == null) {
                out += "${e.where}: ${e.label} has $ATTR=\"$c\", and the manifest has no item \"$id\". Fix: add the item with a web " +
                    "placement in $MANIFEST, or correct the attribute to an item's id"
                continue
            }
            if (!c.startsWith(FLOW) && e.id != null && e.id !in item.placements[Platform.WEB]?.elements.orEmpty()) {
                out += "${e.where}: ${e.label} has $ATTR=\"$c\", and the item \"$id\" in $MANIFEST does not list the element " +
                    "\"${e.id}\" in its web placement. Fix: add \"${e.id}\" to that placement's elements, or correct the attribute"
            }
        }
        // Rule 3: a manifest web element the page lacks.
        for (item in items) for (el in item.placements[Platform.WEB]?.elements.orEmpty()) {
            if (elements.none { it.id == el && it.control == item.id }) {
                val found = elements.firstOrNull { it.id == el }
                out += "the manifest's item \"${item.id}\" ($MANIFEST, web placement) names the element \"$el\", which " +
                    (if (found == null) "none of ${FILES.joinToString()} has" else "${found.where} has without $ATTR=\"${item.id}\"") +
                    ". Fix: give the page's element id=\"$el\" and $ATTR=\"${item.id}\", or remove \"$el\" from the item's web placement"
            }
        }
        return out
    }
}
