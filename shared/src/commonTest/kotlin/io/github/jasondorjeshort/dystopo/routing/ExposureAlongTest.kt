package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.Slope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T195: the ground's slope, aspect and ridges along a route, on synthetic terrain (metres east and north of a centre). */
class ExposureAlongTest {
    private val lat0 = 46.8
    private val lon0 = -121.7
    private val naismith = CostModel(TravelMode.HIKE, pace = null)

    /** Metres east and north of the centre for ([lat], [lon]), the inverse of [RidgeRule.offset]. */
    private fun en(lat: Double, lon: Double): Pair<Double, Double> {
        val n = (lat - lat0) / 180.0 * PI * 6378137.0
        val e = (lon - lon0) / 180.0 * PI * 6378137.0 * cos(lat0 * PI / 180.0)
        return e to n
    }

    private fun terrain(f: (e: Double, n: Double) -> Double) = ElevationSampler { lat, lon -> en(lat, lon).let { (e, n) -> f(e, n) } }

    /** A cone [deg] steep from a summit 2000 m up at the centre. */
    private fun cone(deg: Double) = terrain { e, n -> 2000.0 - hypot(e, n) * tan(deg * PI / 180) }

    /** A circle (or an arc from [fromDeg] to [toDeg], bearings) of [radiusM] round the centre, a point a degree. */
    private fun arc(radiusM: Double, fromDeg: Double = 0.0, toDeg: Double = 360.0): List<RoutePoint> =
        (0..(toDeg - fromDeg).toInt()).map { k ->
            val b = (fromDeg + k) * PI / 180
            RidgeRule.offset(lat0, lon0, radiusM * sin(b), radiusM * cos(b)).let { (la, lo) -> RoutePoint(la, lo) }
        }

    private fun line(e0: Double, n0: Double, e1: Double, n1: Double, n: Int = 40): List<RoutePoint> = (0..n).map { k ->
        val t = k.toDouble() / n
        RidgeRule.offset(lat0, lon0, e0 + (e1 - e0) * t, n0 + (n1 - n0) * t).let { (la, lo) -> RoutePoint(la, lo) }
    }

    @Test
    fun coneSlopeAndAspectByQuarter() {
        val r = ExposureAlong.find(arc(1000.0), cone(40.0), naismith)
        assertTrue(r.samples.size in 250..253, "about 2 pi km in 25 m steps: ${r.samples.size}")
        for (s in r.samples) {
            assertEquals(40.0, s.slopeDeg, 0.5, "the cone's slope everywhere")
            // The ground faces away from the summit: its aspect is the bearing from the centre.
            val (e, n) = en(s.lat, s.lon)
            val bearing = (atan2(e, n) * 180 / PI + 360) % 360
            val diff = abs(((s.aspectDeg - bearing + 540) % 360) - 180)
            assertTrue(diff < 2.0, "aspect ${s.aspectDeg} against bearing $bearing")
        }
        // By quarter: the samples nearest north, east, south and west face that way.
        for ((quarter, side) in listOf(0.0 to 0, 90.0 to 2, 180.0 to 4, 270.0 to 6)) {
            val s = r.samples.minBy { s -> en(s.lat, s.lon).let { (e, n) -> abs(((atan2(e, n) * 180 / PI - quarter + 540) % 360) - 180) } }
            assertEquals(side, s.side, "the $quarter quarter faces ${Slope.COMPASS_SIDES[side]}")
        }
        assertEquals(r.lengthM, r.over30.distanceM, 1e-6)
        assertEquals(r.lengthM, r.verySteep.distanceM, 1e-6)
        assertEquals(0.0, r.steep.distanceM, 1e-6, "none of 30 to 34")
        assertEquals((0 until 8).toList(), r.shownSides)
        assertEquals("6.28 km on slopes over 30 degrees, facing every direction; 6.28 km over 35 degrees", ExposureText.cardLine(r, ContourUnits.METERS))
        assertEquals(0.0, r.ridge.distanceM, 1e-6, "1 km out on a 40 degree cone is a slope, not a ridge")
        assertEquals(1, r.stretches.size, "one steep stretch round the cone")
        assertEquals(r.totalTimeS, r.over30.timeS, 1e-6)
    }

    @Test
    fun coneBandsAndTheSidesOfAnArc() {
        // A 32 degree cone: all 30 to 34, nothing over 35; the arc from 10 to 80 degrees faces north to east.
        val r = ExposureAlong.find(arc(1000.0, 10.0, 80.0), cone(32.0), naismith)
        assertEquals(r.lengthM, r.steep.distanceM, 1e-6)
        assertEquals(0.0, r.verySteep.distanceM, 1e-6)
        assertEquals(listOf(0, 1, 2), r.shownSides)
        assertEquals("1.22 km on slopes over 30 degrees, facing north to east", ExposureText.cardLine(r, ContourUnits.METERS))
        assertEquals("Time on slopes over 30 degrees: 15 min", ExposureText.timeLine(r))
        // A gentler cone: no steep ground.
        val flat = ExposureAlong.find(arc(1000.0, 10.0, 80.0), cone(20.0), naismith)
        assertEquals(0.0, flat.over30.distanceM, 1e-6)
        assertEquals("No slopes over 30 degrees along the route", ExposureText.cardLine(flat, ContourUnits.METERS))
        assertNull(ExposureText.timeLine(flat))
    }

    @Test
    fun ridgeCrestIsARidgeAndItsFlanksAreNot() {
        // A north-south crest with 20 degree flanks; the route crosses it west to east.
        val t = tan(20 * PI / 180)
        val dem = terrain { e, _ -> 2000.0 - abs(e) * t }
        assertTrue(RidgeRule.tpiM(dem, lat0, lon0) > 30, "the crest stands above its ring: ${RidgeRule.tpiM(dem, lat0, lon0)}")
        assertTrue(RidgeRule.onRidge(dem, lat0, lon0))
        val flank = RidgeRule.offset(lat0, lon0, 300.0, 0.0)
        assertEquals(0.0, RidgeRule.tpiM(dem, flank.first, flank.second), 0.5, "an even flank: the ring's mean is the centre")
        assertFalse(RidgeRule.onRidge(dem, flank.first, flank.second))
        val r = ExposureAlong.find(line(-500.0, 0.0, 500.0, 0.0), dem, naismith)
        val ridges = r.stretches.filter { it.kind == StretchKind.RIDGE }
        assertEquals(1, ridges.size, "one ridge stretch")
        val mid = (ridges[0].fromM + ridges[0].toM) / 2
        assertEquals(500.0, mid, 15.0, "centred on the crest")
        assertTrue(ridges[0].distanceM in 50.0..250.0, "a crest, not the flanks: ${ridges[0].distanceM}")
        assertEquals(0.0, r.over30.distanceM, 1e-6, "20 degree flanks")
        for (s in r.samples) assertEquals(20.0, s.slopeDeg, if (abs(en(s.lat, s.lon).first) < 15) 20.0 else 0.5)
        assertEquals(r.ridge.distanceM, ridges[0].distanceM, 1e-6)
        assertEquals("Along ridges: ${io.github.jasondorjeshort.dystopo.render.UnitFormat.distance(r.ridge.distanceM, ContourUnits.METERS)}, ${ExposureText.minutes(r.ridge.timeS)}", ExposureText.ridgeLine(r, ContourUnits.METERS))
        // A valley of the same shape is no ridge anywhere.
        val valley = ExposureAlong.find(line(-500.0, 0.0, 500.0, 0.0), terrain { e, _ -> 1000.0 + abs(e) * t }, naismith)
        assertEquals(0.0, valley.ridge.distanceM, 1e-6)
        assertEquals("Along ridges: none", ExposureText.ridgeLine(valley, ContourUnits.METERS))
    }

    @Test
    fun noDataSaysNothing() {
        val r = ExposureAlong.find(line(0.0, 0.0, 400.0, 0.0), ElevationSampler { _, _ -> Double.NaN }, naismith)
        assertFalse(r.hasData)
        assertNull(ExposureText.cardLine(r, ContourUnits.METERS))
        assertNull(ExposureText.ridgeLine(r, ContourUnits.METERS))
        assertEquals(r.lengthM, r.noData.distanceM, 1e-6)
        assertTrue(ExposureAlong.find(listOf(RoutePoint(lat0, lon0)), cone(40.0)).samples.isEmpty())
    }

    @Test
    fun sideWordsAndRows() {
        assertEquals("north and east", ExposureText.sideWords(listOf(0, 2)))
        assertEquals("north", ExposureText.sideWords(listOf(0)))
        assertEquals("north and northeast", ExposureText.sideWords(listOf(1, 0)))
        assertEquals("northwest to northeast", ExposureText.sideWords(listOf(7, 0, 1)))
        assertEquals("northwest to northeast and south", ExposureText.sideWords(listOf(4, 7, 0, 1)))
        assertEquals("north, east and southwest", ExposureText.sideWords(listOf(0, 2, 5)))
        assertEquals("every direction", ExposureText.sideWords((0..7).toList()))
        assertEquals(0, Slope.compassSide(359.0)); assertEquals(0, Slope.compassSide(22.4)); assertEquals(1, Slope.compassSide(22.5))
        assertEquals(6, Slope.compassSide(270.0)); assertEquals(-1, Slope.compassSide(Double.NaN))
        val s = ExposureStretch(StretchKind.SLOPE, 1420.0, 1800.0, 350.0, 540.0, 100.0, 38.4, listOf(0), 0.0, 0.0)
        assertEquals("At 1.42 km: 350 m over 30 degrees (up to 38), facing north, 9 min", ExposureText.row(s, ContourUnits.METERS))
        assertEquals("At 0.9 mi: 0.2 mi over 30 degrees (up to 38), facing north, 9 min", ExposureText.row(s, ContourUnits.FEET))
        val ridge = ExposureStretch(StretchKind.RIDGE, 2100.0, 2400.0, 300.0, 360.0, 0.0, 22.0, emptyList(), 0.0, 0.0)
        assertEquals("At 2.10 km: 300 m along a ridge, 6 min", ExposureText.row(ridge, ContourUnits.METERS))
        assertEquals("1 h 05 min", ExposureText.minutes(3900.0))
        // The survey's words: none of the hazard words in anything shown.
        val shown = listOf(ExposureText.HELP, ExposureText.row(s, ContourUnits.METERS), ExposureText.row(ridge, ContourUnits.METERS)).joinToString(" ").lowercase()
        for (w in listOf("avalanche", "hazard", "danger", "risk", "exposure")) assertFalse(shown.contains(w), w)
    }

    @Test
    fun hornAtMatchesTheGridGradient() {
        // A plane rising 0.5 m a metre to the east: 26.6 degrees, facing west.
        val v = DoubleArray(9) { i -> (i % 3 - 1) * 5.0 }
        val (dx, dy) = Slope.hornAt(v, 10.0)
        assertEquals(0.5, dx, 1e-9); assertEquals(0.0, dy, 1e-9)
        assertEquals(270.0, Slope.aspect(dx, dy), 1e-9)
        val holed = v.copyOf().also { it[0] = Double.NaN }
        assertTrue(Slope.hornAt(holed, 10.0).first < 0.5, "a hole takes the centre's value")
        assertTrue(Slope.hornAt(DoubleArray(9) { if (it == 4) Double.NaN else 1.0 }, 10.0).first.isNaN())
    }
}
