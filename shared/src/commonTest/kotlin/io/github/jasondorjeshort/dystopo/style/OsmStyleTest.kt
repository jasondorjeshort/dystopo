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
