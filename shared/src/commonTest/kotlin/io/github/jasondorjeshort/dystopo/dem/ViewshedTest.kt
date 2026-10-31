package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T182: [Viewshed] over synthetic terrain whose visible ground is known (a bowl, a ridge with a peak behind it). */
class ViewshedTest {
    private val deg = PI / 180

    /** Metres east and north of the fixture's centre to a lat, lon (small offsets: the local scale). */
    private fun TerrainFixture.latLon(east: Double, north: Double): Pair<Double, Double> {
        val z = 20
        val (x, y) = at(z, east, north)
        return WebMercator.yToLat(y / 256, z) to WebMercator.xToLon(x / 256, z)
    }

    /** A bowl: rising as the square of the distance to a rim 300 m up at 2 km, falling steeply behind it to level ground at 2.5 km. */
    private val bowl = TerrainFixture(46.0, -121.0) { e, n ->
        val r = sqrt(e * e + n * n)
        when {
            r <= 2000 -> 300 * (r / 2000) * (r / 2000)
            r <= 2500 -> 300 - (r - 2000) * 0.6
            else -> 0.0
        }
    }

    private fun run(f: TerrainFixture, rangeM: Double): Pair<Terrain, ViewshedResult> {
        val plan = Viewshed.plan(f.lat, f.lon, rangeM)
        val terrain = Viewshed.terrain(plan) { f.grid(it) }
        return terrain to Viewshed.compute(terrain, plan)
    }

    @Test
    fun theBowlShowsItsRimAndHidesTheGroundBehindIt() {
        val (_, r) = run(bowl, 4000.0)
        assertEquals(1, r.levels.size, "one band inside 5 km")
        for (b in 0 until 360 step 15) {
            val e = sin(b * deg)
            val n = cos(b * deg)
            fun state(d: Double) = bowl.latLon(e * d, n * d).let { (lat, lon) -> r.stateAt(lat, lon) }
            assertEquals(Viewshed.VISIBLE, state(800.0), "inner slope at $b")
            assertEquals(Viewshed.VISIBLE, state(1700.0), "inner slope at $b")
            assertEquals(Viewshed.VISIBLE, state(1985.0), "rim at $b")
            assertEquals(Viewshed.HIDDEN, state(2200.0), "behind the rim at $b")
            assertEquals(Viewshed.HIDDEN, state(3500.0), "level ground beyond at $b")
            assertEquals(Viewshed.NONE, state(4300.0), "past the range at $b")
        }
        // The visible ground is the bowl inside the rim: pi * 2 km squared, 12.57 km2.
        assertEquals(PI * 4.0, r.visibleKm2, 0.4)
        assertEquals(PI * 16.0 - PI * 4.0, r.hiddenKm2, 0.6)
        assertEquals(0.0, r.noDataKm2, 1e-9)
        assertEquals(0, r.bearingsShortOfData)
        // The horizon the rays met: the rim all round, atan(300 / 2000) less the eye, about 8.5 degrees, 2 km out.
        for (i in 0 until r.rays step r.rays / 16) {
            assertEquals(8.48, r.horizon.angleDeg[i], 0.15, "horizon at ray $i")
            assertEquals(2000.0, r.horizon.distanceM[i], 30.0)
        }
    }

    @Test
    fun levelGroundIsSeenToTheHorizonDistance() {
        // From 1.7 m over level ground the curvature (with refraction) hides the ground past sqrt(2 R h / (1 - k)), 5 km.
        val flat = TerrainFixture(46.0, -121.0) { _, _ -> 0.0 }
        val (_, r) = run(flat, 8000.0)
        val limit = sqrt(2 * Horizon.EARTH_RADIUS_M * Viewshed.EYE_HEIGHT_M / (1 - Horizon.REFRACTION_K))
        assertEquals(4990.0, limit, 10.0)
        for (b in listOf(0.0, 77.0, 200.0)) {
            fun state(d: Double) = flat.latLon(sin(b * deg) * d, cos(b * deg) * d).let { (lat, lon) -> r.stateAt(lat, lon) }
            assertEquals(Viewshed.VISIBLE, state(limit - 300), "short of the horizon at $b")
            assertEquals(Viewshed.HIDDEN, state(limit + 400), "past the horizon at $b")
        }
    }

    @Test
    fun theOverlaySaysWhereTheDataStopped() {
        // The terrain ends 3 km east of the point (no data past it): those cells are NO_DATA, hatched, and the horizon's
        // reach stops there.
        val f = TerrainFixture(46.0, -121.0) { e, _ -> if (e > 3000) Double.NaN else 0.0 }
        val (_, r) = run(f, 4500.0)
        val (lat, lon) = f.latLon(3800.0, 0.0)
        assertEquals(Viewshed.NO_DATA, r.stateAt(lat, lon))
        assertTrue(r.noDataKm2 > 1.0, "no data ${r.noDataKm2}")
        assertTrue(r.bearingsShortOfData > r.rays / 4, "short ${r.bearingsShortOfData} of ${r.rays}")
        assertEquals(3000.0, r.horizon.reachedM[r.rays / 4], 20.0) // due east
        assertEquals(4500.0, r.horizon.reachedM[r.rays * 3 / 4], 20.0) // due west
        val level = r.levels[0]
        val argb = Viewshed.overlayArgb(level)
        var hatched = 0
        var tinted = 0
        for (j in 0 until level.window.height) for (i in 0 until level.window.width) {
            val p = argb[j * level.window.width + i]
            when (level.cells[j * level.window.width + i]) {
                Viewshed.NO_DATA -> if (p != 0) { hatched++; assertEquals(Viewshed.NO_DATA_RGB, p and 0xFFFFFF) }
                Viewshed.VISIBLE -> if (level.owns(i, j)) { tinted++; assertEquals(89, p ushr 24); assertEquals(Viewshed.OVERLAY_RGB, p and 0xFFFFFF) }
                else -> assertEquals(0, p, "hidden and unreached ground stays clear")
            }
        }
        assertTrue(hatched > 100 && tinted > 1000, "hatched $hatched, tinted $tinted")
    }

    @Test
    fun thePlanStepsDownTheDemWithDistanceAndTheLevelsTileTheDisc() {
        val lat = 46.79
        val lon = -121.74
        val p10 = Viewshed.plan(lat, lon, 10_000.0)
        val p30 = Viewshed.plan(lat, lon, 30_000.0)
        val p100 = Viewshed.plan(lat, lon, 100_000.0)
        assertEquals(listOf(13, 11), p10.levels.map { it.window.zoom })
        assertEquals(listOf(13, 11), p30.levels.map { it.window.zoom })
        assertEquals(listOf(13, 11, 9), p100.levels.map { it.window.zoom })
        assertEquals(listOf(Viewshed.NEAR_M, 30_000.0, 100_000.0), p100.levels.map { it.toM })
        for (p in listOf(p10, p30, p100)) {
            for (l in p.levels) assertTrue(l.window.width <= 1300, "level ${l.window.zoom} is ${l.window.width} cells across at ${p.rangeM}")
        }
        assertFailsWith<IllegalArgumentException> { Viewshed.plan(lat, lon, 150_000.0) }

        // Every point within the range belongs to exactly one level.
        val f = TerrainFixture(lat, lon) { e, n -> 50 * sin(e / 900) * cos(n / 1300) }
        val plan = Viewshed.plan(lat, lon, 40_000.0)
        val terrain = Viewshed.terrain(plan) { f.grid(it) }
        val r = Viewshed.compute(terrain, plan)
        var k = 0
        val holes = ArrayList<String>()
        for (step in 1..4000) {
            val d = 40_000.0 * sqrt((step * 0.6180339887) % 1.0)
            val b = step * 137.5 * deg
            val (plat, plon) = f.latLon(sin(b) * d, cos(b) * d)
            var owners = 0
            for (l in r.levels) {
                val w = l.window
                val i = floor(WebMercator.lonToX(plon, w.zoom) * 256 - w.x0).toInt()
                val j = floor(WebMercator.latToY(plat, w.zoom) * 256 - w.y0).toInt()
                if (i in 0 until w.width && j in 0 until w.height && l.owns(i, j)) {
                    owners++
                    if (l.cells[j * w.width + i] == Viewshed.NONE) holes += "z${w.zoom} at ${d.toInt()} m"
                }
            }
            if (d < 39_700) assertEquals(1, owners, "point $step at $d m, bearing ${(step * 137.5) % 360}")
            k++
        }
        assertTrue(holes.isEmpty(), "drawn cells no ray marked: $holes")
    }

    /** A north-south ridge 200 m high 1 km east; a peak 3 km east behind it, 150 m high (hidden), and one 4.5 km west, 700 m (seen, on the skyline). */
    private val ridgeAndPeaks = TerrainFixture(46.0, -121.0) { e, n ->
        fun cone(ce: Double, cn: Double, h: Double, rad: Double) = (h * (1 - sqrt((e - ce) * (e - ce) + (n - cn) * (n - cn)) / rad)).coerceAtLeast(0.0)
        200 * exp(-((e - 1000) / 150).let { it * it }) + cone(3000.0, 0.0, 150.0, 400.0) + cone(-4500.0, 0.0, 700.0, 1500.0)
    }

    @Test
    fun peaksBehindARidgeAreHiddenAndASummitOnTheSkylineIsSeen() {
        val f = ridgeAndPeaks
        val (terrain, r) = run(f, 10_000.0)
        val (eLat, eLon) = f.latLon(3000.0, 0.0)
        val (wLat, wLon) = f.latLon(-4500.0, 0.0)
        val (nLat, nLon) = f.latLon(0.0, 12_000.0)
        val peaks = listOf(
            Peak("East Knob", "peak", eLat, eLon, 150.0),
            Peak("West Dome", "volcano", wLat, wLon, null), // no tag: the DEM's elevation
            Peak("Far Peak", "peak", nLat, nLon, 500.0), // out of range
        )
        val views = Viewshed.peaks(terrain, r, peaks)
        assertEquals(listOf("East Knob", "West Dome"), views.map { it.peak.name }, "sorted by distance, out of range dropped")
        val east = views[0]
        assertFalse(east.visible, "behind the ridge: ${east.clearanceDeg}")
        assertTrue(east.clearanceDeg < -3)
        assertEquals(90.0, east.bearingDeg, 0.5)
        assertEquals("E", east.compass)
        assertEquals(3000.0, east.distanceM, 30.0)
        val west = views[1]
        assertTrue(west.visible && west.skyline, "west: clearance ${west.clearanceDeg}")
        assertTrue(west.eleFromDem)
        assertEquals(700.0, west.eleM, 10.0)
        assertEquals(270.0, west.bearingDeg, 0.5)
        assertEquals("W", west.compass)
        // Sorts: distance (the default), elevation, bearing.
        assertEquals(listOf("West Dome", "East Knob"), PeakSort.ELEVATION.sort(views).map { it.peak.name })
        assertEquals(listOf("East Knob", "West Dome"), PeakSort.BEARING.sort(views).map { it.peak.name })
        assertEquals(PeakSort.DISTANCE, PeakSort.of(null))
        assertEquals(PeakSort.BEARING, PeakSort.of("bearing"))
    }

    @Test
    fun peaksAreReadFromTheMountainPeakLayerInRange() {
        val lat = 46.80
        val lon = -121.75
        val z = 12
        val extent = 4096
        val tiles = HashMap<Triple<Int, Int, Int>, MutableList<Feature>>()
        fun point(plat: Double, plon: Double, vararg kv: Pair<String, Any>) {
            val fx = WebMercator.lonToX(plon, z)
            val fy = WebMercator.latToY(plat, z)
            val x = floor(fx).toInt()
            val y = floor(fy).toInt()
            val props = kv.associate { (k, v) -> k to if (v is String) Value.StringValue(v) else Value.IntValue((v as Int).toLong()) }
            tiles.getOrPut(Triple(z, x, y)) { ArrayList() } +=
                Feature(null, props, Geometry.Points(listOf(TilePoint(((fx - x) * extent).toInt(), ((fy - y) * extent).toInt()))))
        }
        point(46.8529, -121.7603, "class" to "volcano", "name" to "Mount Rainier", "ele" to 4392)
        point(46.8020, -121.7270, "class" to "peak", "name" to "Panorama Point", "ele" to 2097)
        point(46.7900, -121.7000, "class" to "saddle", "name" to "A Saddle", "ele" to 2000) // not a peak
        point(46.7950, -121.7100, "class" to "peak", "ele" to 1900) // unnamed
        point(47.3000, -121.7500, "class" to "peak", "name" to "Too Far", "ele" to 1500)
        val encoded = tiles.mapValues { (_, fs) -> VectorTile(listOf(Layer("mountain_peak", fs, extent), Layer("water", emptyList(), extent))).encode() }
        val peaks = Viewshed.readPeaks({ tz, tx, ty -> encoded[Triple(tz, tx, ty)] }, lat, lon, 10_000.0, zoom = z)
        assertEquals(setOf("Mount Rainier", "Panorama Point"), peaks.map { it.name }.toSet())
        val rainier = peaks.first { it.name == "Mount Rainier" }
        assertEquals("volcano", rainier.kind)
        assertEquals(4392.0, rainier.eleM)
        assertEquals(46.8529, rainier.lat, 1e-3)
        assertEquals(12, Viewshed.peakZoom(lat, 10_000.0))
        assertEquals(11, Viewshed.peakZoom(lat, 30_000.0))
        assertEquals(10, Viewshed.peakZoom(lat, 100_000.0))
    }
}
