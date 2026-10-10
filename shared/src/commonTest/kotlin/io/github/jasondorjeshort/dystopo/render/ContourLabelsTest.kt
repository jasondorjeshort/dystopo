package io.github.jasondorjeshort.dystopo.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContourLabelsTest {
    /** 0.6 em per digit, ink height 0.72 em: close to a typical sans-serif. */
    private val measurer = TextMeasurer { text, fontPx -> TextSize(text.length * 0.6 * fontPx, 0.72 * fontPx) }
    private val spec = ContourSpec(40.0, 5, ContourUnits.FEET)
    private val params = LabelParams()

    private fun line(level: Double, closed: Boolean, n: Int, f: (Int) -> Pair<Double, Double>) =
        ContourLine(level, DoubleArray(2 * n) { i -> val (x, y) = f(i / 2); if (i % 2 == 0) x else y }, closed)

    private val indexLevel = 200 * 0.3048
    private val plainLevel = 40 * 0.3048

    @Test
    fun straightIndexLineGetsLabelsAtRepeatDistance() {
        val horizontal = line(indexLevel, false, 1001) { i -> i.toDouble() to 300.0 }
        val labels = ContourLabels.place(listOf(horizontal), spec, measurer, params, 1000, 600)
        assertEquals(1000 / params.repeatDistance.toInt(), labels.size)
        for (l in labels) {
            assertEquals("200", l.text)
            assertEquals(300.0, l.y, 1e-6)
            assertEquals(0.0, l.angle, 1e-9)
            assertEquals(params.fontPx, l.fontPx)
        }
        // Labels are spaced out and never overlap.
        val xs = labels.map { it.x }.sorted()
        for (i in 1 until xs.size) assertTrue(xs[i] - xs[i - 1] > labels[0].width)
    }

    @Test
    fun nonIndexLinesGetNoLabels() {
        val plain = line(plainLevel, false, 1001) { i -> i.toDouble() to 300.0 }
        assertTrue(ContourLabels.place(listOf(plain), spec, measurer, params, 1000, 600).isEmpty())
    }

    @Test
    fun textIsAlwaysUpright() {
        // Lines going in every direction: the chosen angle must be within (-90, 90] degrees.
        val lines = (0 until 12).map { k ->
            val a = 2 * PI * k / 12
            line(indexLevel, false, 500) { i -> (1000 + i * cos(a)) to (1000 + i * sin(a)) }
        }
        val labels = ContourLabels.place(lines, spec, measurer, params, 2000, 2000)
        assertTrue(labels.size >= 12, "placed ${labels.size}")
        for (l in labels) assertTrue(l.angle > -PI / 2 - 1e-9 && l.angle <= PI / 2 + 1e-9, "angle ${l.angle}")
        // The two horizontal lines (one runs left-to-right, one right-to-left) both read left-to-right.
        val horizontal = labels.filter { abs(it.y - 1000) < 1e-3 }
        assertTrue(horizontal.size >= 2, "horizontal labels: ${horizontal.size}")
        for (l in horizontal) assertEquals(0.0, l.angle, 1e-6)
    }

    @Test
    fun labelsSitOnStraightStretchesNotOnBends() {
        // A line with a sharp zigzag in the middle and straight runs on either side.
        val pts = ArrayList<Pair<Double, Double>>()
        for (i in 0 until 300) pts += i.toDouble() to 200.0
        for (i in 0 until 100) pts += (300 + i).toDouble() to (200.0 + if (i % 2 == 0) 0.0 else 30.0)
        for (i in 0 until 300) pts += (400 + i).toDouble() to 200.0
        val zig = line(indexLevel, false, pts.size) { pts[it] }
        val labels = ContourLabels.place(listOf(zig), spec, measurer, LabelParams(repeatDistance = 200.0), 800, 400)
        assertTrue(labels.isNotEmpty())
        for (l in labels) {
            val half = l.width / 2 + params.padding
            assertTrue(l.x + half <= 300.0 + 1e-6 || l.x - half >= 400.0 - 1e-6, "label at ${l.x} overlaps the zigzag")
        }
    }

    @Test
    fun labelsNeverOverlapEachOtherOrLeaveTheImage() {
        // Many parallel index lines close together: only some can carry labels at the same x.
        val lines = (0 until 20).map { k -> line(indexLevel, false, 601) { i -> i.toDouble() to (100.0 + k * 3) } }
        val labels = ContourLabels.place(lines, spec, measurer, params, 600, 300)
        assertTrue(labels.isNotEmpty())
        for (l in labels) for (c in l.corners(params.padding).toList().chunked(2)) {
            assertTrue(c[0] >= 0 && c[0] <= 600 && c[1] >= 0 && c[1] <= 300, "corner $c outside")
        }
        for (i in labels.indices) for (j in i + 1 until labels.size) {
            assertTrue(!ContourLabels.boxesOverlap(labels[i].corners(), labels[j].corners()), "labels $i and $j overlap")
        }
    }

    @Test
    fun closedRingIsLabeledAroundItsPerimeter() {
        val ring = line(indexLevel, true, 720) { i -> val a = 2 * PI * i / 720; (500 + 300 * cos(a)) to (500 + 300 * sin(a)) }
        val labels = ContourLabels.place(listOf(ring), spec, measurer, params, 1000, 1000)
        assertTrue(labels.size >= 4, "placed ${labels.size}")
        for (l in labels) assertEquals(300.0, hypot(l.x - 500, l.y - 500), 1.0)
    }

    @Test
    fun shortLinesGetNoLabel() {
        val stub = line(indexLevel, false, 20) { i -> i.toDouble() to 50.0 }
        assertTrue(ContourLabels.place(listOf(stub), spec, measurer, params, 1000, 1000).isEmpty())
    }

    @Test
    fun textScaleScalesTheFont() {
        val horizontal = line(indexLevel, false, 1001) { i -> i.toDouble() to 300.0 }
        val small = ContourLabels.place(listOf(horizontal), spec, measurer, LabelParams(textScale = 1.0), 1000, 600).first()
        val big = ContourLabels.place(listOf(horizontal), spec, measurer, LabelParams(textScale = 2.0), 1000, 600).first()
        assertEquals(2.0, big.fontPx / small.fontPx, 1e-9)
        assertEquals(2.0, big.width / small.width, 1e-9)
    }

    @Test
    fun orientedBoxOverlapTest() {
        val a = orientedBox(0.0, 0.0, 0.0, 10.0, 2.0)
        val b = orientedBox(0.0, 0.0, PI / 2, 10.0, 2.0) // crosses a
        val c = orientedBox(0.0, 5.0, 0.0, 10.0, 2.0) // above a, touching nothing
        val d = orientedBox(4.0, 5.0, -PI / 4, 10.0, 2.0) // diagonal, its AABB overlaps a but the boxes do not
        assertTrue(ContourLabels.boxesOverlap(a, b))
        assertTrue(!ContourLabels.boxesOverlap(a, c))
        assertTrue(!ContourLabels.boxesOverlap(a, d))
    }
}
