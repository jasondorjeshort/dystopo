package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.controls.ControlItem
import io.github.jasondorjeshort.dystopo.controls.ControlKind
import io.github.jasondorjeshort.dystopo.controls.Controls
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
 * (or flow parent, on an element or on a flow's container) the manifest lacks, or an element whose id the item's web
 * placement does not list; (3) a manifest web element the page lacks, or a web item (other than a display) no element
 * of the page carries. A `maplibregl.X` control carries the item whose web placement lists `maplibregl.X` (the page
 * cannot set an attribute on MapLibre's own buttons; `demo-check.py` maps their classes on the live DOM).
 *
 * Scope of rule 1: build step 2 (T238, `allInScope`, what `WebControlsTest` applies) is every element of the three
 * files. Build step 1's narrower scope ([inStep1Scope]: the controls inside the Map options section and every element
 * the manifest names) stays for the reader's own tests of it.
 *
 * Regex readers: when an anchor they rely on is missing they fail naming the pattern ([anchorMissing]), not with a
 * parse error.
 */
object WebControls {
    val FILES = listOf("tools/demo/index.html", "tools/demo/trips.js", "tools/demo/trip-sync.js")
    /** The manifest's directory; a refusal names the file of the item it concerns ([Controls.sourceOf]). */
    const val MANIFEST = Controls.DIR
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
        // What each element carries: its own attribute, or for MapLibre's control the item that lists its class.
        fun idOf(e: Element) = e.id ?: e.tag.takeIf { e.source == "addControl" }
        fun controlOf(e: Element) = e.control ?: if (e.source == "addControl") webElements[e.tag] else null
        fun manifestOf(id: String) = if (byId[id] != null) Controls.sourceOf(id) else "$MANIFEST/"
        for (e in elements) {
            val inFlow = flows[e.file].orEmpty().any { e.offset in it && it.first != e.offset }
            val c = controlOf(e)
            // Rule 1: an element with neither attribute.
            if (c == null && !inFlow && (allInScope || inStep1Scope(e, scope, webElements.keys, index))) {
                val item = e.id?.let { webElements[it] }
                out += "${e.where}: ${e.label} has no $ATTR attribute. Fix: add $ATTR=\"${item ?: "<id>"}\" naming its item " +
                    "(an item with a web placement in $MANIFEST/, added there if the control is new), or $ATTR=\"$FLOW<parent-id>\" " +
                    "if it is an action inside the flow of a placed item" +
                    (if (e.source == "addControl") "; for a MapLibre control, list \"${e.tag}\" in its item's web elements" else "")
                continue
            }
            c ?: continue
            // Rule 2: an id the manifest lacks, or an element the item's web placement does not list.
            val id = c.removePrefix(FLOW)
            val item = byId[id]
            if (item == null) {
                out += "${e.where}: ${e.label} has $ATTR=\"$c\", and the manifest has no item \"$id\". Fix: add the item with a web " +
                    "placement in $MANIFEST/, or correct the attribute to an item's id"
                continue
            }
            val own = idOf(e)
            if (!c.startsWith(FLOW) && own != null && own !in item.placements[Platform.WEB]?.elements.orEmpty()) {
                out += "${e.where}: ${e.label} has $ATTR=\"$c\", and the item \"$id\" in ${manifestOf(id)} does not list the element " +
                    "\"$own\" in its web placement. Fix: add \"$own\" to that placement's elements, or correct the attribute"
            }
        }
        // Rule 2 for a flow's container (a <details>, <section>, <ul> or <div> marking the actions inside it).
        for ((f, t) in texts) {
            val clean = withoutComments(t)
            for (m in FLOW_OPEN.findAll(clean)) {
                val parent = m.groupValues[2]
                if (byId[parent] == null) out += "$f:${lineOf(clean, m.range.first)}: <${m.groupValues[1]}> has $ATTR=\"$FLOW$parent\", and the " +
                    "manifest has no item \"$parent\". Fix: add the item with a web placement in $MANIFEST/, or name a placed item's id"
            }
        }
        // Rule 3: a manifest web element the page lacks, or a web item no element carries.
        for (item in items) {
            val place = item.placements[Platform.WEB] ?: continue
            for (el in place.elements) {
                if (elements.none { idOf(it) == el && controlOf(it) == item.id }) {
                    val found = elements.firstOrNull { idOf(it) == el }
                    out += "the manifest's item \"${item.id}\" (${manifestOf(item.id)}, web placement) names the element \"$el\", which " +
                        (if (found == null) "none of ${FILES.joinToString()} has" else "${found.where} has without $ATTR=\"${item.id}\"") +
                        ". Fix: give the page's element id=\"$el\" and $ATTR=\"${item.id}\", or remove \"$el\" from the item's web placement"
                }
            }
            if (item.kind != ControlKind.DISPLAY && elements.none { controlOf(it) == item.id }) {
                out += "the manifest's item \"${item.id}\" (${manifestOf(item.id)}) has a web placement, and no element of " +
                    "${FILES.joinToString()} carries $ATTR=\"${item.id}\". Fix: put the attribute on the page's control for it, " +
                    "or remove the item's web placement"
            }
        }
        return out
    }
}
