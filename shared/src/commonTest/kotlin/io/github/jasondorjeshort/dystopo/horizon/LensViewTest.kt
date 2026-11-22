package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.TerrainFixture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T322: the Panorama's lens ([LensView]) as a projection for [HorizonLabels.place], and the layered skyline ([LayeredSkyline]). */
class LensViewTest {
    private val w = 1080.0
    private val h = 720.0
    private val dp = 2.625
    private fun lens(bearing: Double = 90.0, tilt: Double = 0.0, roll: Double = 0.0, fov: Double = 60.0) = LensView(w, h, dp, bearing, tilt, roll, fov)
    private fun tanD(d: Double) = tan(d * PI / 180)
    private fun near(a: Double, b: Double, tol: Double = 1e-6) = assertTrue(abs(a - b) <= tol, "$a against $b")

    @Test
    fun theCentreAMarkTenDegreesRightAndOneBehind() {
        val l = lens()
        val c = assertNotNull(l.mark(0.0, 90.0))
        near(c.x, w / 2); near(c.y, h / 2)
        val right = assertNotNull(l.mark(0.0, 100.0))
        near(right.x, w / 2 + l.focalPx * tanD(10.0)); near(right.y, h / 2)
        assertNull(l.mark(0.0, 270.0), "behind the viewer: off the view")
        assertNull(l.project(0.0, 270.0))
        assertNull(l.mark(0.0, 175.0), "beside the view: off it")
        // Below the horizon: placed where it falls, not clamped to the level.
        val below = assertNotNull(l.mark(-5.0, 90.0))
        near(below.y, h / 2 + l.focalPx * tanD(5.0))
        // The ray at a pixel is the inverse.
        val (el, az) = l.ray(right.x, right.y)
        near(el, 0.0, 1e-9); near(az, 100.0, 1e-9)
        // Away is straight up at the level, unrolled.
        val a = l.away(right, 0.0, 100.0)
        near(a.x, 0.0, 1e-9); near(a.y, -1.0, 1e-9)
    }

    @Test
    fun aRollOfNinetyDegreesTurnsRightIntoUp() {
        val l = lens(roll = 90.0)
        val right = assertNotNull(l.mark(0.0, 100.0))
        near(right.x, w / 2, 1e-6); near(right.y, h / 2 - l.focalPx * tanD(10.0), 1e-6)
        // The zenith's side is now to the left: names step left.
        val a = l.away(right, 0.0, 100.0)
        near(a.x, -1.0, 1e-6); near(a.y, 0.0, 1e-6)
    }

    @Test
    fun straightDownLooksAtTheNadirWithTheBearingAtTheTop() {
        val l = lens(bearing = 30.0, tilt = -90.0)
        val nadir = assertNotNull(l.mark(-90.0, 123.0))
        near(nadir.x, w / 2, 1e-6); near(nadir.y, h / 2, 1e-6)
        val ahead = assertNotNull(l.mark(-80.0, 30.0))
        near(ahead.x, w / 2, 1e-6); near(ahead.y, h / 2 - l.focalPx * tanD(10.0), 1e-6)
        // The away direction from a mark below the view's centre-top is toward the top (the horizon's side).
        assertTrue(l.away(ahead, -80.0, 30.0).y < -0.99)
        assertNull(l.mark(0.0, 30.0), "the level is off a 60 degree view looking straight down")
    }

    @Test
    fun theBearingWrapsAtNorth() {
        val l = lens(bearing = 355.0)
        val east = assertNotNull(l.mark(0.0, 5.0))
        val west = assertNotNull(l.mark(0.0, 345.0))
        near(east.x - w / 2, l.focalPx * tanD(10.0), 1e-6)
        near(w / 2 - west.x, l.focalPx * tanD(10.0), 1e-6)
        val a = HorizonLabel("A", LabelKind.PEAK, 359.0, 0.0, 1000.0, 1.0, 1000.0)
        val b = HorizonLabel("B", LabelKind.PEAK, 1.0, 0.0, 1000.0, 1.0, 1000.0)
        val c = HorizonLabel("C", LabelKind.PEAK, 3.0, 0.0, 1000.0, 1.0, 1000.0)
        assertTrue(l.separation(a, b) < l.separation(a, c), "359 and 1 are close across north")
        // The separation is the screen distance over the reference scale (the rim's dp per degree at its usual size).
        near(LensView.REF_DP_PER_DEG, (411.0 / 2 - 24.0) * PI / 180, 1e-12)
        val pa = l.mark(0.0, 359.0)!!
        val pb = l.mark(0.0, 1.0)!!
        near(l.separation(a, b), (pb.x - pa.x) / (LensView.REF_DP_PER_DEG * dp), 1e-9)
    }

    @Test
    fun aNarrowerViewNamesMore() {
        // Twenty peaks across 14 degrees ahead, low on the skyline.
        val peaks = (0 until 20).map { i ->
            HorizonLabel("Peak $i", LabelKind.PEAK, 83.0 + i * 0.7, 1.0 + (i % 5) * 0.4, 8000.0 + 400.0 * i, 0.5, 2000.0 + 10.0 * i, 100.0 + 5.0 * i)
        }
        val ranked = HorizonLabels.rank(peaks)
        val width = { s: String -> 6.5 * dp * s.length }
        fun named(fov: Double) = HorizonLabels.place(lens(fov = fov), ranked, width, -11.0 * dp, 3.0 * dp)
        val wide = named(60.0)
        val narrow = named(15.0)
        assertTrue(narrow.size > wide.size, "15 degrees: ${narrow.size}, 60 degrees: ${wide.size}")
        // No two names overlap, and each stands above its mark (away is up at the level).
        for (p in narrow) {
            assertTrue(narrow.none { it !== p && it.box.overlaps(p.box) })
            val at = lens(fov = 15.0).mark(p.label.angleDeg, p.label.bearingDeg)!!
            assertTrue(p.box.bottom <= at.y + 8 * dp || p.y < at.y, "${p.label.name} above or beside its mark")
        }
        // A label off the view is left out, even the forced ones.
        val behind = HorizonLabel("Behind", LabelKind.PEAK, 270.0, 2.0, 500.0, 1.0, 3000.0, dominant = true)
        assertTrue(HorizonLabels.place(lens(), HorizonLabels.rank(peaks + behind), width, -11.0 * dp, 3.0 * dp).none { it.label.name == "Behind" })
    }

    @Test
    fun t329ALensFromAxesIsTheLensOfItsAngles() {
        for ((b, t, r) in listOf(Triple(90.0, 0.0, 0.0), Triple(10.0, 35.0, -20.0), Triple(250.0, -70.0, 120.0))) {
            val axes = LensAxes.of(b, t, r)
            val a = LensView.of(w, h, dp, axes, 60.0)
            near(a.bearingDeg, b, 1e-9); near(a.tiltDeg, t, 1e-9); near(a.rollDeg, r, 1e-9)
            val c = lens(b, t, r)
            for ((el, az) in listOf(0.0 to b, 10.0 to b + 12, -5.0 to b - 20)) {
                val p = assertNotNull(a.project(el, az))
                val q = assertNotNull(c.project(el, az))
                near(p.x, q.x, 1e-6); near(p.y, q.y, 1e-6)
            }
            assertTrue(abs(((axes.right cross axes.up) dot axes.forward) + 1) < 1e-12, "right x up = -forward")
        }
        // Within two degrees of the poles the bearing written is the one held.
        val held = LensView.of(w, h, dp, LensAxes.of(10.0, 89.0, 0.0), 60.0, heldBearingDeg = 200.0)
        assertEquals(200.0, held.bearingDeg)
        assertEquals(10.0, LensView.of(w, h, dp, LensAxes.of(10.0, 87.0, 0.0), 60.0, heldBearingDeg = 200.0).bearingDeg, 1e-9)
    }

    @Test
    fun theSkylinesAnglesNeverFallAsTheRangeGrows() {
        // Cones at 2, 8, 20 and 45 km in different directions, on gently rolling ground, near Paradise (a public place).
        fun cone(e: Double, n: Double, ce: Double, cn: Double, hM: Double, rad: Double) = max(0.0, hM * (1 - sqrt((e - ce) * (e - ce) + (n - cn) * (n - cn)) / rad))
        val fx = TerrainFixture(46.786, -121.735) { e, n ->
            1600.0 + 20 * kotlin.math.sin(e / 900) * kotlin.math.cos(n / 1300) +
                cone(e, n, 2000.0, 0.0, 300.0, 800.0) + cone(e, n, 0.0, 8000.0, 900.0, 2500.0) +
                cone(e, n, -20_000.0, -3000.0, 1800.0, 5000.0) + cone(e, n, 30_000.0, 33_000.0, 2500.0, 8000.0)
        }
        val terrain = fx.terrain(Horizon.windowsAround(fx.lat, fx.lon, 12, 60_000.0))
        val ranges = LayeredSkyline.ranges(60_000.0)
        assertEquals(listOf(3_000.0, 10_000.0, 30_000.0, 60_000.0), ranges)
        val s = LayeredSkyline.sweep(terrain, fx.lat, fx.lon, ranges, 0.5, 1.7)
        assertEquals(4, s.profiles.size)
        for (i in 0 until s.outer.size) for (k in 1 until s.profiles.size) {
            assertTrue(s.profiles[k].angleDeg[i] >= s.profiles[k - 1].angleDeg[i], "bearing ${s.outer.bearingDeg(i)}: ${ranges[k]} below ${ranges[k - 1]}")
        }
        // Each cone raises its own layer: the 8 km one toward north is in the 10 km skyline, not the 3 km one.
        assertTrue(s.profiles[1].angleAt(0.0) > s.profiles[0].angleAt(0.0) + 3)
        assertTrue(s.profiles[3].angleAt(42.3) > s.profiles[2].angleAt(42.3) + 0.5, "the 45 km cone shows only at the view range")
        // The layer of a direction: the near cone's flank toward the east, the sky above all.
        assertEquals(0, s.layerAt(s.profiles[0].angleAt(90.0) - 0.5, 90.0))
        assertEquals(-1, s.layerAt(30.0, 90.0))
        assertEquals(listOf(100_000.0), LayeredSkyline.ranges(100_000.0).takeLast(1))
        assertEquals(listOf(3_000.0, 10_000.0), LayeredSkyline.ranges(10_000.0))
    }
}
