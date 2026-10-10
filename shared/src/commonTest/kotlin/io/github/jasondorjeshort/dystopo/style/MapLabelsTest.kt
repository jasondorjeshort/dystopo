package io.github.jasondorjeshort.dystopo.style

import io.github.jasondorjeshort.dystopo.render.ContourLabels
import io.github.jasondorjeshort.dystopo.render.TextMeasurer
import io.github.jasondorjeshort.dystopo.render.TextSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Monospace stand-in for the renderer's font: 0.6 em per character, 1 em tall. */
object FakeMeasurer : TextMeasurer {
    override fun measure(text: String, fontPx: Double) = TextSize(0.6 * fontPx * text.length, fontPx)
}

class MapLabelsTest {
    private val style = DefaultOsmStyle.style
    private val peaks = style.byId["peaks"] as StyleLayer.Symbol
    private val places = style.byId["places"] as StyleLayer.Symbol
    private val trails = style.byId["trail-names"] as StyleLayer.Symbol

    private fun placer(w: Int = 800, h: Int = 600) = LabelPlacer(FakeMeasurer, w, h, 14.0, 1.4)

    @Test
    fun pointLabelsAvoidEachOtherAndFallBackAroundTheMarker() {
        val p = placer()
        val a = LabelRequest(peaks, listOf("Alpha", "1000 m"), 300.0, 300.0)
        val blocker = LabelRequest(places, listOf("Blocker"), 370.0, 420.0)
        val b = LabelRequest(peaks, listOf("Beta", "900 m"), 300.0, 420.0) // its right side is taken by the blocker
        val c = LabelRequest(places, listOf("Gamma"), 305.0, 305.0) // on top of a's marker
        val placed = p.placeAll(listOf(a, blocker, b, c))
        assertEquals(listOf("Alpha", "Blocker", "Beta"), placed.map { it.lines[0] })
        val first = placed[0]
        assertTrue(first.x > 300.0 && abs(first.y - 300.0) < 1e-9, "first choice is right of the marker")
        assertEquals(300.0, first.markerX)
        val beta = placed[2]
        assertTrue(beta.x < 300.0 && abs(beta.y - 420.0) < 1e-9, "fell back to the left: $beta")
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            assertTrue(!ContourLabels.boxesOverlap(placed[i].corners(), placed[j].corners()), "labels $i and $j overlap")
        }
        assertEquals(1, p.counts["places"]?.dropped)
        assertTrue(p.collides(first.corners()))
    }

    @Test
    fun labelsStayInsideTheImage() {
        val p = placer(200, 100)
        assertTrue(p.placeAll(listOf(LabelRequest(places, listOf("Edge Town"), 2.0, 50.0))).isEmpty())
        assertEquals(1, p.placeAll(listOf(LabelRequest(places, listOf("Mid"), 100.0, 50.0))).size)
    }

    @Test
    fun sameTextIsNotRepeatedNearby() {
        val p = placer()
        val placed = p.placeAll(listOf(
            LabelRequest(peaks, listOf("Twin"), 100.0, 100.0),
            LabelRequest(peaks, listOf("Twin"), 100.0, 250.0), // within the 200 px spacing
            LabelRequest(peaks, listOf("Twin"), 100.0, 400.0),
        ))
        assertEquals(listOf(100.0, 400.0), placed.map { it.markerY })
    }

    @Test
    fun lineLabelsAreUprightOnLinesDrawnRightToLeft() {
        val p = placer()
        val path = doubleArrayOf(700.0, 300.0, 100.0, 300.0)
        val label = p.placeAll(listOf(LabelRequest(trails, listOf("Westward Trail"), path = path))).single()
        val glyphs = assertNotNull(label.glyphs)
        assertTrue(glyphs.all { abs(it.angle) < 1e-9 }, "upright")
        assertTrue(glyphs.zipWithNext().all { (a, b) -> b.x > a.x }, "reads left to right")
        assertEquals("Westward Trail", glyphs.joinToString("") { it.text })
    }

    @Test
    fun lineLabelsFollowGentleCurvesButNotSwitchbacks() {
        // A wide arc: the label bends with it.
        val arc = DoubleArray(2 * 41) { i ->
            val t = PI * (0.25 + 0.5 * (i / 2) / 40.0)
            if (i % 2 == 0) 400 + 500 * kotlin.math.cos(t) else 650 - 500 * kotlin.math.sin(t)
        }
        val curved = placer().placeAll(listOf(LabelRequest(trails, listOf("Arc Trail"), path = arc))).single()
        val angles = assertNotNull(curved.glyphs).map { it.angle }
        assertTrue(angles.first() != angles.last(), "glyphs rotate along the curve")
        // Tight zig-zags (switchbacks every 12 px) take no label.
        val zig = DoubleArray(2 * 60) { i -> if (i % 2 == 0) 100.0 + (i / 2) * 10.0 else if ((i / 2) % 2 == 0) 300.0 else 312.0 }
        val p = placer()
        assertTrue(p.placeAll(listOf(LabelRequest(trails, listOf("Zigzag Trail"), path = zig))).isEmpty())
        assertEquals(1, p.counts["trail-names"]?.dropped)
    }

    @Test
    fun shortLinesGetNoLabel() {
        val p = placer()
        assertNull(p.placeAll(listOf(LabelRequest(trails, listOf("A Very Long Trail Name"), path = doubleArrayOf(10.0, 10.0, 60.0, 10.0)))).firstOrNull())
    }
}
