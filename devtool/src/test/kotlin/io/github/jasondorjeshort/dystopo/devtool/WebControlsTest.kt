package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.controls.Controls
import io.github.jasondorjeshort.dystopo.controls.MapControls
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T208 (ADR 0053 §5 part b, build step 1): the web page's map option controls carry `data-control="<id>"` and follow
 * the placement manifest ([WebControls] reads the three files). Each refusal names its lift. T238 (build step 2): the
 * rule covers every interactive element of the three files (`allInScope`).
 */
class WebControlsTest {
    private val texts = WebControls.FILES.associateWith { File("../$it").readText() } // tests run in devtool/

    @Test
    fun thePageFollowsTheManifest() {
        val refusals = WebControls.refusals(texts, Controls.items, allInScope = true)
        assertTrue(refusals.isEmpty(), "The page and the controls manifest disagree (ADR 0053):\n" + refusals.joinToString("\n"))
    }

    @Test
    fun theReaderFindsThePagesControls() {
        // Every pattern the reader relies on still matches something, so a silent zero is a failure, not a pass.
        val all = texts.flatMap { (f, t) -> WebControls.read(f, t) }
        for (f in WebControls.FILES) assertTrue(all.any { it.file == f }, "the reader found no input, select, button or summary in $f")
        assertTrue(all.any { it.source.startsWith("createElement") }, "the reader found no createElement(\"button\" | \"input\" | ...) call")
        assertTrue(all.any { it.source == "addControl" && it.tag == "maplibregl.NavigationControl" }, "the reader found no addControl(new maplibregl.NavigationControl...)")
        assertTrue(WebControls.scopeSpan(texts.getValue(WebControls.FILES[0])) != null, WebControls.anchorMissing(WebControls.FILES[0], WebControls.SCOPE_OPEN))
        // Every element the manifest names is marked with its item (a MapLibre control by its class: no attribute).
        val marked = all.filter { it.control != null && it.id != null }.associate { it.id to it.control }
        for ((el, id) in Controls.webElements()) {
            if (el.startsWith("maplibregl.")) assertTrue(all.any { it.source == "addControl" && it.tag == el }, "the page adds no $el")
            else assertEquals(id, marked[el], "#$el")
        }
        // T238: every element of the page is in scope, and each carries an item or a flow (none is left out by the reader).
        val own = all.filter { it.control != null && !it.control!!.startsWith(WebControls.FLOW) }.map { it.control }.toSet()
        assertTrue(own.containsAll(listOf("panel", "units", "textScale", "mapOptions", "printSheet", "routesChip", "planningCard", "buildMap")), "$own")
        assertTrue(all.count { it.control?.startsWith(WebControls.FLOW) == true } >= 8, "the created rows' flow attributes are read: " + all.filter { it.control != null }.map { it.label + "=" + it.control })
    }

    @Test
    fun aMissingAttributeNamesTheElementFileLineAndBothFixes() {
        val index = texts.getValue(WebControls.FILES[0])
        val before = """<select id="opt-slope" data-control="slope">"""
        assertTrue(before in index, "the page's slope select changed; update this test's string")
        val edited = texts + (WebControls.FILES[0] to index.replace(before, """<select id="opt-slope">"""))
        val line = index.substring(0, index.indexOf(before)).count { it == '\n' } + 1
        val refusals = WebControls.refusals(edited, Controls.items, allInScope = true)
        val r = refusals.first { "has no data-control" in it }
        assertTrue(r.startsWith("tools/demo/index.html:$line: <select id=\"opt-slope\">"), r)
        assertTrue("data-control=\"slope\"" in r && "data-control=\"flow:<parent-id>\"" in r && WebControls.MANIFEST in r, r)
        // The manifest's element is gone from the page too: rule 3 names it and both edits.
        assertTrue(refusals.any { it.startsWith("the manifest's item \"slope\"") && "remove \"opt-slope\"" in it }, refusals.joinToString("\n"))
    }

    @Test
    fun anIdTheManifestLacksAndAnElementItDoesNotList() {
        val index = texts.getValue(WebControls.FILES[0])
        val edited = texts + (WebControls.FILES[0] to index.replace("""data-control="night"""", """data-control="nightMode""""))
        val r = WebControls.refusals(edited, Controls.items, allInScope = true)
        assertTrue(r.any { "data-control=\"nightMode\", and the manifest has no item \"nightMode\"" in it && WebControls.MANIFEST in it }, r.joinToString("\n"))
        // The night item is now on no element: rule 3 names it, its file and both edits.
        assertTrue(r.any { it.startsWith("the manifest's item \"night\"") && "MapControls.kt" in it }, r.joinToString("\n"))
        val moved = texts + (WebControls.FILES[0] to index.replace("""id="contours" data-control="contours"""", """id="contours" data-control="trails""""))
        val m = WebControls.refusals(moved, Controls.items, allInScope = true)
        assertTrue(m.any { "does not list the element \"contours\"" in it && "MapControls.kt" in it }, m.joinToString("\n"))
        // T238: a summary without an id carries its item; taken off, rule 3 says no element carries it.
        val bare = texts + (WebControls.FILES[0] to index.replace("""<summary data-control="panel">""", "<summary>"))
        val b = WebControls.refusals(bare, Controls.items, allInScope = true)
        assertTrue(b.any { "<summary> has no data-control" in it }, b.joinToString("\n"))
        assertTrue(b.any { "item \"panel\" (${Controls.DIR}/PageControls.kt) has a web placement, and no element" in it }, b.joinToString("\n"))
        // Inside its section's flow (details#print, flow:printSheet) the print summary passes rule 1 bare; rule 3 still sees it gone.
        val print = texts + (WebControls.FILES[0] to index.replace("""<summary data-control="printSheet">""", "<summary>"))
        val pr = WebControls.refusals(print, Controls.items, allInScope = true)
        assertEquals(listOf("item \"printSheet\""), pr.map { it.substringAfter("the manifest's ").substringBefore(" (") }, pr.joinToString("\n"))
    }

    @Test
    fun aFlowContainerAndAMapLibreControlFollowTheManifest() {
        val js = """
            box.innerHTML = `<section data-control="flow:nowhere"><button type="button">Go</button></section>`;
            map.addControl(new maplibregl.NavigationControl(), "top-right");
            map.addControl(new maplibregl.FullscreenControl(), "top-right");
        """.trimIndent()
        val r = WebControls.refusals(mapOf("t.js" to js), Controls.items, allInScope = true)
        // The button inside the flow passes rule 1, but the flow's parent is no item.
        assertTrue(r.none { "<button> has no data-control" in it }, r.joinToString("\n"))
        assertTrue(r.any { it.startsWith("t.js:1: <section> has data-control=\"flow:nowhere\", and the manifest has no item \"nowhere\"") }, r.joinToString("\n"))
        // NavigationControl carries the orientation button's item (its web placement lists it); FullscreenControl nothing.
        assertTrue(r.none { "NavigationControl" in it && "has no data-control" in it }, r.joinToString("\n"))
        assertTrue(r.any { it.startsWith("t.js:3: <maplibregl.FullscreenControl> (addControl) has no data-control") && "list \"maplibregl.FullscreenControl\"" in it }, r.joinToString("\n"))
    }

    @Test
    fun theReaderReadsMarkupStringsCreateElementAndAddControl() {
        val js = """
            box.innerHTML = `<summary>Trips</summary><div><button id="a" type="button" data-control="hillshade">A</button>
              <ul data-control="flow:hillshade"><li><button type="button">Open</button></li></ul></div>`;
            const go = document.createElement("button"); go.type = "button";
            go.dataset.control = "flow:slope";
            const bare = document.createElement("input");
            map.addControl(new maplibregl.NavigationControl(), "top-right");
            const locate = new maplibregl.GeolocateControl({});
            map.addControl(locate, "top-right");
            map.addControl(new ScaleBarControl(), "bottom-left");
        """.trimIndent()
        val els = WebControls.read("t.js", js)
        assertEquals(listOf("summary", "button", "button", "button", "input", "maplibregl.NavigationControl", "maplibregl.GeolocateControl"), els.map { it.tag })
        assertEquals("hillshade", els[1].control)
        assertEquals("flow:slope", els[3].control)
        assertEquals(5, els[4].line)
        // Every element in scope: the summary, the bare input and the two map controls lack the attribute; the
        // button inside the flow list does not.
        val r = WebControls.refusals(mapOf("t.js" to js), MapControls.items, allInScope = true)
            .filter { "has no data-control" in it }
        assertEquals(listOf("t.js:1: <summary>", "t.js:5: <input> (createElement bare)", "t.js:6: <maplibregl.NavigationControl> (addControl)", "t.js:8: <maplibregl.GeolocateControl> (addControl)"), r.map { it.substringBefore(" has no") })
        // A tag named in an HTML comment is not an element.
        assertTrue(WebControls.read("c.html", "<!-- <button id=\"x\"> -->\n<p>").isEmpty())
    }
}
