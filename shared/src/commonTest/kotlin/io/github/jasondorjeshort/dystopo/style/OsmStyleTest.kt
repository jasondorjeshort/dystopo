package io.github.jasondorjeshort.dystopo.style

import io.github.jasondorjeshort.dystopo.mvt.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OsmStyleTest {
    private fun s(v: String) = Value.StringValue(v)

    @Test
    fun defaultStyleParses() {
        val style = DefaultOsmStyle.style
        assertTrue(style.layers.count { it is StyleLayer.Contours } == 1, "one contour marker")
        val ids = style.layers.map { it.id }
        // Ordering decisions the renderer relies on: tints under contours, water over them.
        assertTrue(ids.indexOf("wood") < ids.indexOf("contours"))
        assertTrue(ids.indexOf("glacier") < ids.indexOf("contours"))
        assertTrue(ids.indexOf("water") > ids.indexOf("contours"))
        // Peaks claim label space before trail names.
        assertTrue(ids.indexOf("peaks") < ids.indexOf("trail-names"))
        for (id in listOf("water", "river", "stream", "glacier", "wood", "park-boundary", "road-major", "track", "trail", "footway", "steps", "peaks", "places", "trail-names")) {
            assertTrue(id in style.byId, "default style has $id")
        }
    }

    @Test
    fun filters() {
        val f = Filter.parse(mapOf("class" to listOf("path", "track"), "!subclass" to listOf("steps"), "name" to true, "rank" to mapOf("<=" to 3L)))
        assertTrue(f.matches(mapOf("class" to s("path"), "name" to s("A"), "rank" to Value.IntValue(2))))
        assertTrue(f.matches(mapOf("class" to s("track"), "subclass" to s("x"), "name" to s("A"), "rank" to Value.SintValue(3))))
        assertFalse(f.matches(mapOf("class" to s("path"), "subclass" to s("steps"), "name" to s("A"), "rank" to Value.IntValue(1))))
        assertFalse(f.matches(mapOf("class" to s("path"), "rank" to Value.IntValue(1))), "name required")
        assertFalse(f.matches(mapOf("class" to s("path"), "name" to s("A"), "rank" to Value.IntValue(4))))
        assertFalse(f.matches(mapOf("class" to s("path"), "name" to s("A"))), "comparison needs the value")
        assertFalse(f.matches(mapOf("class" to s("primary"), "name" to s("A"), "rank" to Value.IntValue(1))))
        // Numbers compare by value whatever their wire type.
        val n = Filter.parse(mapOf("intermittent" to 1L))
        for (v in listOf(Value.IntValue(1), Value.UintValue(1), Value.SintValue(1), Value.FloatValue(1f), Value.DoubleValue(1.0))) {
            assertTrue(n.matches(mapOf("intermittent" to v)), "$v")
        }
        assertFalse(n.matches(mapOf("intermittent" to Value.IntValue(0))))
        assertTrue(Filter.parse(mapOf("!intermittent" to 1L)).matches(emptyMap()), "negation matches absent keys")
        assertTrue(Filter.parse(mapOf("name" to false)).matches(emptyMap()))
    }

    @Test
    fun zoomValuesAndColors() {
        val z = ZoomValue.parse(listOf(listOf(10L, 1.0), listOf(15L, 2.0)), 0.0)
        assertEquals(1.0, z.at(4.0))
        assertEquals(1.4, z.at(12.0), 1e-12)
        assertEquals(2.0, z.at(18.0))
        assertEquals(3.0, ZoomValue.parse(3L, 0.0).at(12.0))
        assertEquals(StyleColor(0xc0, 0x39, 0x2b), StyleColor.parse("#c0392b"))
        assertEquals(StyleColor(0xf6, 0xf1, 0xe4, 0x99), StyleColor.parse("#f6f1e499"))
        assertEquals(StyleColor(255, 0, 0), StyleColor.parse("#f00"))
        assertFailsWith<IllegalArgumentException> { StyleColor.parse("red") }
    }

    @Test
    fun outdoorsStyleKeys() {
        val style = OsmStyle.parse(
            """{"layers": [
              {"id": "c", "type": "line", "source-layer": "natural_line", "color": "#000",
               "ticks": {"spacing": [[12, 3], [15, 5]], "length": 4, "side": "downhill"},
               "modifiers": [{"filter": {"informal": 1}, "width": 0.5}, {"filter": {"trail_visibility": "bad"}, "opacity": 0.4}]},
              {"id": "p", "type": "symbol", "source-layer": "outdoor_poi", "text": "", "color": "#000", "text-optional": true,
               "marker": {"shape": "star", "size": 9, "color": "#c25e00", "text": "P", "text-color": "#ff0"}}
            ]}""",
        )
        val c = style.byId["c"] as StyleLayer.Line
        val t = c.ticks!!
        assertEquals(TickSide.DOWNHILL, t.side)
        assertEquals(11.0 / 3, t.spacing.at(13.0), 1e-9)
        assertEquals(1.0, t.width.at(13.0))
        assertEquals(listOf(0.5 to 1.0, 1.0 to 0.4), c.modifiers.map { it.width to it.opacity })
        assertTrue(c.modifiers[0].filter.matches(mapOf("informal" to Value.IntValue(1))))
        val p = style.byId["p"] as StyleLayer.Symbol
        assertTrue(p.textOptional)
        assertEquals(Marker(MarkerShape.STAR, 9.0, StyleColor(0xc2, 0x5e, 0), "P", StyleColor(255, 255, 0)), p.marker)
        assertFailsWith<IllegalArgumentException> {
            OsmStyle.parse("""{"layers": [{"id": "m", "type": "symbol", "source-layer": "x", "text": "", "color": "#000", "marker": {"shape": "hexagon"}}]}""")
        }
        assertFailsWith<IllegalArgumentException> {
            OsmStyle.parse("""{"layers": [{"id": "t", "type": "line", "source-layer": "x", "color": "#000", "ticks": {"side": "up"}}]}""")
        }
        // Every marker shape and tick side in the default style parses, and the outdoors layers are there.
        for (id in listOf("cliff", "wilderness-boundary", "trail-mountain", "trail-demanding", "trail-alpine", "huts", "trailheads", "campsites",
            "shelters", "waterfalls", "viewpoints", "springs", "caves", "parking", "natural-line-names", "ridge-names")) {
            assertTrue(id in DefaultOsmStyle.style.byId, "default style has $id")
        }
    }

    @Test
    fun parseErrorsNameTheLayer() {
        val e = assertFailsWith<IllegalArgumentException> {
            OsmStyle.parse("""{"layers": [{"id": "bad", "type": "line", "source-layer": "x", "color": "#123456", "dash": ["a"]}]}""")
        }
        assertTrue("bad" in (e.message ?: ""), e.message)
        assertFailsWith<IllegalArgumentException> { OsmStyle.parse("""{"layers": [{"id": "a", "type": "contours"}, {"id": "a", "type": "contours"}]}""") }
        assertFailsWith<IllegalArgumentException> { OsmStyle.parse("""{"layers": [{"id": "a", "type": "circle", "source-layer": "x"}]}""") }
    }

    @Test
    fun textTemplates() {
        val peak = mapOf("name" to s("Mount Rainier"), "ele" to Value.SintValue(4392))
        assertEquals(listOf("Mount Rainier", "14409 ft"), TextTemplate.render("{name}\n{elevation}", peak, "ft"))
        assertEquals(listOf("Mount Rainier", "4392 m"), TextTemplate.render("{name}\n{elevation}", peak, "m"))
        // A line whose placeholders are all missing is dropped; literal text alone stays.
        assertEquals(listOf("4392 m"), TextTemplate.render("{name}\n{elevation}", mapOf("ele" to Value.IntValue(4392)), "m"))
        assertEquals(emptyList(), TextTemplate.render("{name}", emptyMap(), "m"))
        assertEquals(listOf("Trail 12"), TextTemplate.render("Trail {ref}", mapOf("ref" to Value.IntValue(12)), "m"))
    }
}
