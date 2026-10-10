package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.style.DefaultOsmStyle
import io.github.jasondorjeshort.dystopo.style.StyleLayer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The browser demo's `MARKERS` table (tools/demo/index.html) must match the renderer's outdoors POI
 * markers in [DefaultOsmStyle]: same shape, colour, size, first zoom, glyph and exclusions.
 */
class MarkerTableTest {
    private class DemoMarker(val cls: String, val shape: String, val size: Double, val color: String, val minzoom: Double, val text: String?)

    private val html = File("../tools/demo/index.html").readText() // tests run in devtool/

    private val demo: List<DemoMarker> by lazy {
        val block = Regex("""const MARKERS = \{(.*?)\n\};""", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
        val entry = Regex("""(\w+):\s*\{\s*shape: "(\w+)",\s*size: ([\d.]+),\s*color: "(#[0-9a-f]{6})",\s*minzoom: (\d+), rank: \d+(?:, text: "(\w+)")? \}""")
        entry.findAll(block).map { m ->
            val g = m.groupValues
            DemoMarker(g[1], g[2], g[3].toDouble(), g[4], g[5].toDouble(), g[6].ifEmpty { null })
        }.toList()
    }

    private val outdoorLayers = DefaultOsmStyle.style.layers.filterIsInstance<StyleLayer.Symbol>()
        .filter { it.select.sourceLayer == "outdoor_poi" && it.marker != null }

    private fun props(vararg kv: Pair<String, String>): Map<String, Value> = kv.associate { (k, v) -> k to Value.StringValue(v) }

    private fun rendererLayer(cls: String, subclass: String? = null): StyleLayer.Symbol? {
        val p = if (subclass == null) props("class" to cls) else props("class" to cls, "subclass" to subclass)
        return outdoorLayers.filter { it.select.filter.matches(p) }.also { assertTrue(it.size <= 1, "$cls matches ${it.map { l -> l.id }}") }.firstOrNull()
    }

    @Test
    fun demoMarkersMatchTheRenderer() {
        assertTrue(demo.size >= 9, "parsed ${demo.size} demo markers")
        for (d in demo) {
            val layer = rendererLayer(d.cls) ?: error("no renderer marker layer for outdoor_poi class ${d.cls}")
            val m = layer.marker!!
            assertEquals(m.shape.name.lowercase(), d.shape, "${d.cls} shape")
            assertEquals("#%02x%02x%02x".format(m.color.r, m.color.g, m.color.b), d.color, "${d.cls} colour")
            assertEquals(m.size, d.size, "${d.cls} size")
            assertEquals(layer.select.minZoom, d.minzoom, "${d.cls} first zoom")
            assertEquals(m.text, d.text, "${d.cls} glyph")
        }
        // And every class the renderer gives a marker is in the demo table.
        val demoClasses = demo.map { it.cls }.toSet()
        for (cls in listOf("hut", "trailhead", "campsite", "shelter", "waterfall", "viewpoint", "spring", "drinking_water", "cave", "parking")) {
            assertTrue(cls in demoClasses, "demo table lacks $cls")
        }
        assertEquals(outdoorLayers.size, demo.mapNotNull { rendererLayer(it.cls)?.id }.toSet().size, "every renderer POI marker layer is used by the demo")
    }

    @Test
    fun excludedSubclassesMatch() {
        val list = Regex("""MARKER_EXCLUDED_SUBCLASSES = \[(.*?)]""").find(html)!!.groupValues[1]
            .split(",").map { it.trim().trim('"') }.toSet()
        val pairs = mapOf("camp_pitch" to "campsite", "picnic_shelter" to "shelter", "gazebo" to "shelter", "street_side" to "parking")
        assertEquals(pairs.keys, list)
        for ((sub, cls) in pairs) {
            assertFalse(rendererLayer(cls, sub) != null, "renderer draws $cls/$sub but the demo does not")
            assertTrue(rendererLayer(cls, "other") != null, "renderer draws other $cls subclasses")
        }
    }
}
