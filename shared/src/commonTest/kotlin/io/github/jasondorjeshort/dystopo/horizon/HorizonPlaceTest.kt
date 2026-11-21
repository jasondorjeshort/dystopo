package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T317: the Horizon view's point source and its place line (fix, 40 m, 12 km SSE in both units, named). */
class HorizonPlaceTest {
    // Paradise on Rainier (a public place in areas.json).
    private val lat = 46.786
    private val lon = -121.735

    /** The point [m] metres from Paradise toward [deg] (flat-earth step; good to a few metres at these distances). */
    private fun toward(m: Double, deg: Double): Pair<Double, Double> {
        val r = deg * PI / 180
        return (lat + m * cos(r) / 111_195.0) to (lon + m * sin(r) / (111_195.0 * cos(lat * PI / 180)))
    }

    @Test
    fun theFixIsYourLocation() {
        val l = HorizonPlace.line(null, lat, lon, ContourUnits.METERS)
        assertEquals("Your location", l.text)
        assertFalse(l.moved)
        assertEquals("here", l.kind)
        // No fix at all: still the phone's own view.
        assertEquals("Your location", HorizonPlace.line(null, null, null, ContourUnits.FEET).text)
    }

    @Test
    fun fortyMetresAwayIsStillYourLocation() {
        val (pLat, pLon) = toward(40.0, 90.0)
        val l = HorizonPlace.line(HorizonPoint(pLat, pLon), lat, lon, ContourUnits.METERS)
        assertEquals("Your location", l.text)
        assertFalse(l.moved, "within ${HorizonPlace.HERE_M} m it is not tinted as moved")
        assertTrue(l.distanceM!! in 35.0..45.0)
        // 60 m is not.
        val (fLat, fLon) = toward(60.0, 90.0)
        val far = HorizonPlace.line(HorizonPoint(fLat, fLon), lat, lon, ContourUnits.METERS)
        assertEquals("60 m E of you", far.text)
        assertTrue(far.moved)
    }

    @Test
    fun twelveKilometresSsePerUnits() {
        val (pLat, pLon) = toward(12_000.0, 157.5)
        val p = HorizonPoint(pLat, pLon)
        val metric = HorizonPlace.line(p, lat, lon, ContourUnits.METERS)
        assertEquals("12.0 km SSE of you", metric.text)
        assertTrue(metric.moved)
        assertEquals("offset", metric.kind)
        assertEquals(157.5, metric.bearingDeg!!, 0.5)
        assertEquals("7.5 mi SSE of you", HorizonPlace.line(p, lat, lon, ContourUnits.FEET).text)
        // Recomputed as the fix moves: from the point's own place it is "Your location"; from 12 km further it doubles.
        assertEquals("Your location", HorizonPlace.line(p, pLat, pLon, ContourUnits.METERS).text)
        val (bLat, bLon) = toward(-12_000.0, 157.5)
        assertEquals("24.0 km SSE of you", HorizonPlace.line(p, bLat, bLon, ContourUnits.METERS).text)
    }

    @Test
    fun aNamedFeature() {
        // Mount Rainier's summit from Paradise: about 7 km NNE.
        val summit = HorizonPoint(46.8529, -121.7604, "Mount Rainier")
        val d = TrackStats.distanceM(lat, lon, summit.lat, summit.lon)
        val l = HorizonPlace.line(summit, lat, lon, ContourUnits.METERS)
        assertTrue(l.text.startsWith("At Mount Rainier, ") && l.text.endsWith(" NNW of you"), l.text)
        assertEquals("named", l.kind)
        assertTrue(l.moved)
        assertEquals(d, l.distanceM!!, 1e-6)
        // Without a fix: the name alone; nameless and without a fix: the coordinates.
        assertEquals("At Mount Rainier", HorizonPlace.line(summit, null, null, ContourUnits.METERS).text)
        val bare = HorizonPlace.line(HorizonPoint(46.853, -121.76), null, null, ContourUnits.METERS)
        assertEquals("46.8530, -121.7600", bare.text)
        assertEquals("unknown", bare.kind)
        assertNull(bare.distanceM)
        // Standing on the named feature is your location.
        assertEquals("Your location", HorizonPlace.line(summit, summit.lat, summit.lon, ContourUnits.METERS).text)
    }

    @Test
    fun thePointSource() {
        assertEquals(lat to lon, HorizonPlace.source(null, lat, lon))
        assertNull(HorizonPlace.source(null, null, null))
        assertEquals(46.85 to -121.76, HorizonPlace.source(HorizonPoint(46.85, -121.76), lat, lon))
        assertEquals(46.85 to -121.76, HorizonPlace.source(HorizonPoint(46.85, -121.76), null, null))
        // At a chosen point the satellites and the flights do not draw and their buttons are disabled; the rest as set.
        val all = HorizonLayers.DEFAULT
        assertEquals(HorizonLayers(satellites = false, peaks = true, sun = true, flights = false), HorizonPlace.layers(all, true))
        assertEquals(all, HorizonPlace.layers(all, false))
        assertEquals(HorizonLayers(false, false, true, false), HorizonPlace.layers(HorizonLayers(true, false, true, true), true))
        assertEquals(setOf(HorizonLayer.SATELLITES, HorizonLayer.FLIGHTS), HorizonPlace.disabled(true))
        assertTrue(HorizonPlace.disabled(false).isEmpty())
        assertEquals("N", HorizonPlace.compass16(359.0))
        assertEquals("SSE", HorizonPlace.compass16(157.5))
        assertEquals("NNW", HorizonPlace.compass16(340.0))
    }
}
