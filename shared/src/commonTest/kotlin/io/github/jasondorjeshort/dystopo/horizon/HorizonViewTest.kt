package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.Peak
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.dem.TerrainFixture
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.nav.Sun
import io.github.jasondorjeshort.dystopo.tiles.ShadeTiles
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T308: the Horizon view's shared pieces: the layer state, the sun's path and its terrain times (equal to the turn-around
 * clock's, which reaches [Sun.onTerrain] through [ShadeTiles.pointAnswer]), and the peaks the rim names (on the skyline
 * only, one per 30 degree sector), with [Viewshed.peaks] on the plot's own [io.github.jasondorjeshort.dystopo.dem.HorizonProfile].
 */
class HorizonViewTest {
    // Paradise on Rainier (a public place in areas.json).
    private val lat = 46.786
    private val lon = -121.735

    @Test
    fun theLayersAreAllOnByDefaultAndEachTurnsAlone() {
        val d = HorizonLayers.DEFAULT
        assertEquals(listOf(HorizonLayer.SATELLITES, HorizonLayer.PEAKS, HorizonLayer.SUN), d.on)
        val noSats = d.with(HorizonLayer.SATELLITES, false)
        assertFalse(noSats.satellites)
        assertTrue(noSats.peaks && noSats.sun)
        assertEquals(mapOf("satellites" to false, "peaks" to true, "sun" to true), noSats.json())
        assertEquals(d, noSats.with(HorizonLayer.SATELLITES, true))
        assertEquals(HorizonLayers(false, false, true), HorizonLayers.parse("sun"))
        assertEquals(HorizonLayers(true, false, true), HorizonLayers.parse(" Satellites,sun "))
        assertEquals(d, HorizonLayers.parse("all"))
        assertEquals(emptyList(), HorizonLayers.parse("none")!!.on)
        assertNull(HorizonLayers.parse("satellites,moon"))
        assertEquals(HorizonLayer.PEAKS, HorizonLayer.of("PEAKS"))
        assertEquals(listOf("Satellites", "Peaks", "Sun"), HorizonLayer.entries.map { it.label })
    }

    @Test
    fun theSunsPathOverAFlatHorizonRunsFromSunriseToSunset() {
        val s = SunPath.of(lat, lon, 2026, 11, 19, null)
        assertFalse(s.onTerrain)
        val flat = s.terrain.flat
        val rise = assertNotNull(flat.sunriseMs)
        val set = assertNotNull(flat.sunsetMs)
        assertTrue(s.path.all { it.altitudeDeg >= SunPath.LOWEST_DEG })
        assertTrue(s.path.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs == SunPath.STEP_MS }, "one unbroken arc, every five minutes")
        assertTrue(abs(s.path.first().timeMs - rise) < 15 * 60_000L, "the arc starts at sunrise")
        assertTrue(abs(s.path.last().timeMs - set) < 15 * 60_000L, "and ends at sunset")
        val top = s.path.maxBy { it.altitudeDeg }
        // Mid-November at 46.8 N: the noon sun about 90 - 46.8 - 19.5 = 23.7 degrees up, due south.
        assertEquals(23.7, top.altitudeDeg, 1.0)
        assertEquals(180.0, top.azimuthDeg, 2.0)
        assertTrue(abs(top.timeMs - flat.noonMs) <= SunPath.STEP_MS)
        assertTrue(s.path.filter { it.altitudeDeg > 0.5 }.all { it.clear }, "nothing blocks a flat horizon")
        // The flat horizon stands in for the terrain: its times are within a minute of the almanac's.
        assertTrue(abs(s.terrain.sunsetMs!! - set) < 90_000L, "${s.terrain.sunsetMs} against $set")
        // Hour marks fall on whole hours of the clock given (UTC-8 here) and only while the sun is up.
        val marks = SunPath.hourMarks(s, lat, lon, -8 * 3_600_000L, null)
        assertTrue(marks.size in 8..10, "${marks.size} hour marks")
        assertTrue(marks.all { (it.timeMs - 8 * 3_600_000L) % 3_600_000L == 0L && it.altitudeDeg >= 0 })
    }

    @Test
    fun theViewsTerrainSunsetOverARidgeIsThePointAnswers() {
        // A ridge 500 m high, its crest 2.5 km west of the point: the sun leaves early in the afternoon.
        val f = TerrainFixture(lat, lon) { e, _ -> 500 * exp(-((e + 2500) / 400).let { it * it }) }
        val terrain = Horizon.terrainAround(lat, lon, 14) { w -> f.grid(w) }
        val profile = Horizon.sweep(terrain, lat, lon, observerHeightM = Horizon.EYE_HEIGHT_M)
        val view = SunPath.of(lat, lon, 2026, 11, 19, profile::angleAt)
        assertTrue(view.onTerrain)
        val answer = ShadeTiles.pointAnswer(terrain, lat, lon, view.terrain.flat.noonMs)
        val sunset = assertNotNull(view.terrain.sunsetMs)
        assertEquals(answer.day.sunsetMs, sunset, "the same terrain sunset as the turn-around clock's")
        assertEquals(answer.day.sunriseMs, view.terrain.sunriseMs)
        val earlier = (view.terrain.flat.sunsetMs!! - sunset) / 60_000.0
        assertTrue(earlier > 30, "the ridge takes the sun $earlier min early")
        // The arc is clear before the terrain sunset and behind the ridge after it (a sample's centre, a minute's slack).
        assertTrue(view.path.filter { it.timeMs in view.terrain.flat.noonMs..sunset - 120_000L }.all { it.clear })
        assertTrue(view.path.filter { it.timeMs > sunset + 120_000L }.none { it.clear })
    }

    /** Metres east and north of the fixture's centre to a lat, lon. */
    private fun TerrainFixture.latLon(east: Double, north: Double): Pair<Double, Double> {
        val z = 20
        val (x, y) = at(z, east, north)
        return WebMercator.yToLat(y / 256, z) to WebMercator.xToLon(x / 256, z)
    }

    @Test
    fun peaksAreTestedOnThePlotsOwnHorizon() {
        // A ridge 200 m high 1 km east with a knob 3 km east behind it (hidden), and a dome 4.5 km west, 700 m (on the skyline).
        val f = TerrainFixture(lat, lon) { e, n ->
            fun cone(ce: Double, cn: Double, h: Double, rad: Double) = (h * (1 - sqrt((e - ce) * (e - ce) + (n - cn) * (n - cn)) / rad)).coerceAtLeast(0.0)
            200 * exp(-((e - 1000) / 150).let { it * it }) + cone(3000.0, 0.0, 150.0, 400.0) + cone(-4500.0, 0.0, 700.0, 1500.0)
        }
        val terrain = Horizon.terrainAround(lat, lon, 14) { w -> f.grid(w) }
        val profile = Horizon.sweep(terrain, lat, lon, observerHeightM = Horizon.EYE_HEIGHT_M)
        val (eLat, eLon) = f.latLon(3000.0, 0.0)
        val (wLat, wLon) = f.latLon(-4500.0, 0.0)
        val (nLat, nLon) = f.latLon(0.0, 40_000.0)
        val views = Viewshed.peaks(terrain, profile, listOf(Peak("East Knob", "peak", eLat, eLon, 150.0), Peak("West Dome", "volcano", wLat, wLon, null), Peak("Far Peak", "peak", nLat, nLon, 500.0)))
        assertEquals(listOf("East Knob", "West Dome"), views.map { it.peak.name }, "beyond the plot's 30 km range dropped")
        assertFalse(views[0].visible)
        assertTrue(views[1].skyline)
        assertEquals(270.0, views[1].bearingDeg, 0.5)
        assertEquals(listOf("West Dome"), PeakRim.skyline(views).map { it.peak.name })
        assertEquals(listOf("West Dome"), PeakRim.labelled(views).map { it.peak.name })
    }

    private fun view(name: String, bearing: Double, ele: Double, skyline: Boolean = true, distance: Double = 5000.0) =
        PeakView(Peak(name, "peak", 0.0, 0.0, ele), distance, bearing, "", ele, false, 5.0, skyline, 1.0, skyline)

    @Test
    fun theRimNamesTheHighestSkylinePeakOfEachThirtyDegreeSector() {
        val peaks = listOf(
            view("Low North", 5.0, 2000.0), view("High North", 25.0, 2500.0), view("Hidden Giant", 15.0, 4000.0, skyline = false),
            view("Edge", 30.0, 1800.0), view("Last", 359.9, 1500.0), view("Twin Far", 200.0, 2200.0, distance = 9000.0),
            view("Twin Near", 205.0, 2200.0, distance = 3000.0),
        )
        assertEquals(listOf(0, 1, 11, 6), listOf(0.0, 30.0, 359.9, 200.0).map { PeakRim.sector(it) })
        assertEquals(0, PeakRim.sector(360.0))
        assertEquals(listOf("High North", "Edge", "Twin Near", "Last"), PeakRim.labelled(peaks).map { it.peak.name })
        assertEquals(listOf("Low North", "High North", "Edge", "Twin Far", "Twin Near", "Last"), PeakRim.skyline(peaks).map { it.peak.name }, "every skyline peak listed, clockwise")
    }
}
