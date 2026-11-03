package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.controls.Controls
import io.github.jasondorjeshort.dystopo.controls.MapControls
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T208 (ADR 0053 §5 part b, build step 1): the web page's map option controls carry `data-control="<id>"` and follow
 * the placement manifest ([WebControls] reads the three files). Each refusal names its lift.
 */
class WebControlsTest {
    private val texts = WebControls.FILES.associateWith { File("../$it").readText() } // tests run in devtool/

    @Test
    fun thePageFollowsTheManifest() {
        val refusals = WebControls.refusals(texts, Controls.items)
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
        // Every map option the web has is marked, on the element the manifest names.
        val marked = all.filter { it.control != null }.associate { it.id to it.control }
        for ((el, id) in Controls.webElements()) assertEquals(id, marked[el], "#$el")
        assertEquals(10, marked.size, "nine map option controls and the panel's Hillshade checkbox: $marked")
    }

    @Test
    fun aMissingAttributeNamesTheElementFileLineAndBothFixes() {
        val index = texts.getValue(WebControls.FILES[0])
        val before = """<select id="opt-slope" data-control="slope">"""
        assertTrue(before in index, "the page's slope select changed; update this test's string")
        val edited = texts + (WebControls.FILES[0] to index.replace(before, """<select id="opt-slope">"""))
        val line = index.substring(0, index.indexOf(before)).count { it == '\n' } + 1
        val refusals = WebControls.refusals(edited, Controls.items)
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
        val r = WebControls.refusals(edited, Controls.items)
        assertTrue(r.any { "data-control=\"nightMode\", and the manifest has no item \"nightMode\"" in it && WebControls.MANIFEST in it }, r.joinToString("\n"))
        val moved = texts + (WebControls.FILES[0] to index.replace("""id="contours" checked""", """id="contours" data-control="trails" checked"""))
        val m = WebControls.refusals(moved, Controls.items)
        assertTrue(m.any { "does not list the element \"contours\"" in it }, m.joinToString("\n"))
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
