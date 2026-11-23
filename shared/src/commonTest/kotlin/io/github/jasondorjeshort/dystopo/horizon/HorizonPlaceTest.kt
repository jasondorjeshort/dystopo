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

    // T321: the switch's rule, and the place line from a peak's "View from here".

    @Test
    fun theSwitchOpensAtTheMapsSelection() {
        val marker = HorizonPoint(46.8529, -121.7604, "Mount Rainier")
        // Nothing selected: the fix.
        assertEquals(SwitchFrom.FIX, HorizonPlace.switchFrom(longPressOpen = false, searchMarker = null))
        // A long-press card open: its point, whether or not a search marker stands too (the card is the newer choice).
        assertEquals(SwitchFrom.LONG_PRESS, HorizonPlace.switchFrom(longPressOpen = true, searchMarker = null))
        assertEquals(SwitchFrom.LONG_PRESS, HorizonPlace.switchFrom(longPressOpen = true, searchMarker = marker))
        // The card closed again (Cancel, an action, or the switch itself): back to the marker if one stands, else the fix.
        assertEquals(SwitchFrom.SEARCH, HorizonPlace.switchFrom(longPressOpen = false, searchMarker = marker))
        assertEquals(SwitchFrom.FIX, HorizonPlace.switchFrom(longPressOpen = false, searchMarker = null))
        assertEquals(listOf("longPress", "search", "fix"), SwitchFrom.entries.map { it.key })
    }

    @Test
    fun viewFromAPeak() {
        // Little Tahoma, a peak the Horizon view lists from Paradise (coordinates rounded): the view stands on it, named.
        val peak = io.github.jasondorjeshort.dystopo.dem.Peak("Little Tahoma", "peak", 46.8496, -121.7123, 3394.0)
        val p = HorizonPlace.pointOf(peak)
        assertEquals(HorizonPoint(46.8496, -121.7123, "Little Tahoma"), p)
        val l = HorizonPlace.line(p, lat, lon, ContourUnits.METERS)
        assertEquals("named", l.kind)
        assertTrue(l.moved)
        assertTrue(l.text.startsWith("At Little Tahoma, ") && l.text.endsWith(" NNE of you"), l.text)
        assertEquals(TrackStats.distanceM(lat, lon, p.lat, p.lon), l.distanceM!!, 1e-6)
        assertTrue(HorizonPlace.line(p, lat, lon, ContourUnits.FEET).text.matches(Regex("At Little Tahoma, \\d+\\.\\d mi NNE of you")))
        // A hop from one peak to another reads from the fix, not from the last point.
        val summit = HorizonPlace.pointOf(io.github.jasondorjeshort.dystopo.dem.Peak("Mount Rainier", "peak", 46.8529, -121.7604, 4392.0))
        assertTrue(HorizonPlace.line(summit, lat, lon, ContourUnits.METERS).text.endsWith(" NNW of you"))
    }

    // ---- T326: the switch for three views ----

    private val summit = HorizonPoint(46.8529, -121.7604, "Mount Rainier")

    @Test
    fun theMapToThePanoramaOpensAtTheMapsSelection() {
        val map = SkyState(SkyView.MAP, null, null)
        assertEquals(SwitchAction.OPEN_AT_SELECTION, HorizonPlace.switchAction(SkyView.MAP, SkyView.PANORAMA))
        // With a selection (a long-press card or a search marker, as switchFrom picks it): the view stands there.
        assertEquals(SkyState(SkyView.PANORAMA, summit, null), HorizonPlace.switched(map, SkyView.PANORAMA, summit))
        // With none: at the fix.
        assertEquals(SkyState(SkyView.PANORAMA, null, null), HorizonPlace.switched(map, SkyView.PANORAMA, null))
        // The Horizon segment by the same rule.
        assertEquals(SkyState(SkyView.HORIZON, summit, null), HorizonPlace.switched(map, SkyView.HORIZON, summit))
    }

    @Test
    fun betweenTheSkyViewsThePlaceAndThePickStay() {
        val horizon = SkyState(SkyView.HORIZON, summit, "Little Tahoma@1023")
        assertEquals(SwitchAction.KEEP_PLACE, HorizonPlace.switchAction(SkyView.HORIZON, SkyView.PANORAMA))
        // The map's selection is not read again: a different one passed in changes nothing.
        val pano = HorizonPlace.switched(horizon, SkyView.PANORAMA, HorizonPoint(1.0, 2.0))
        assertEquals(SkyState(SkyView.PANORAMA, summit, "Little Tahoma@1023"), pano)
        assertEquals(horizon, HorizonPlace.switched(pano, SkyView.HORIZON, null))
        // At the fix with a pick, the same.
        val atFix = SkyState(SkyView.PANORAMA, null, "Mount Rainier@3470")
        assertEquals(atFix.copy(view = SkyView.HORIZON), HorizonPlace.switched(atFix, SkyView.HORIZON, summit))
    }

    @Test
    fun theMapSegmentLetsBothGo() {
        for (v in listOf(SkyView.HORIZON, SkyView.PANORAMA)) {
            assertEquals(SwitchAction.CLOSE, HorizonPlace.switchAction(v, SkyView.MAP))
            assertEquals(SkyState(SkyView.MAP, null, null), HorizonPlace.switched(SkyState(v, summit, "Mount Rainier@3470"), SkyView.MAP, summit))
            // The segment already shown does nothing.
            assertEquals(SwitchAction.NONE, HorizonPlace.switchAction(v, v))
            val s = SkyState(v, summit, "Mount Rainier@3470")
            assertEquals(s, HorizonPlace.switched(s, v, null))
        }
        assertEquals(SwitchAction.NONE, HorizonPlace.switchAction(SkyView.MAP, SkyView.MAP))
        assertEquals(listOf("Map", "Horizon", "Panorama", "List"), SkyView.entries.map { it.label }) // T344: the fourth
        assertEquals(SkyView.PANORAMA, SkyView.of("panorama"))
        assertNull(SkyView.of("camera"))
    }

    @Test
    fun theOpeningsBearing() {
        // A picked peak wins, at the fix or elsewhere, over a heading.
        assertEquals(347.0, HorizonPlace.openingBearing(347.0, atFix = true, headingDeg = 170.0))
        assertEquals(12.5, HorizonPlace.openingBearing(372.5, atFix = false, headingDeg = null))
        // At the fix with a heading: the heading.
        assertEquals(170.0, HorizonPlace.openingBearing(null, atFix = true, headingDeg = 170.0))
        assertEquals(350.0, HorizonPlace.openingBearing(null, atFix = true, headingDeg = -10.0))
        // Neither: north; a heading away from the fix is not the view's direction.
        assertEquals(0.0, HorizonPlace.openingBearing(null, atFix = true, headingDeg = null))
        assertEquals(0.0, HorizonPlace.openingBearing(null, atFix = false, headingDeg = 170.0))
        // The pick's bearing from its key.
        assertEquals(347.0, HorizonPlace.bearingOfKey("Mount Rainier@3470")!!, 1e-9)
        assertEquals(0.5, HorizonPlace.bearingOfKey("A@B@5")!!, 1e-9)
        assertNull(HorizonPlace.bearingOfKey(null))
        assertNull(HorizonPlace.bearingOfKey("no bearing"))
    }

    // T344: the List view, the switch's fourth segment, keeps the place and a pick between the sky views as they do, opens
    // at the map's selection from the map, and "Map" lets go.
    @Test
    fun theListIsASkyViewLikeTheOthers() {
        assertEquals(SkyView.LIST, SkyView.of("list"))
        assertEquals(SwitchAction.OPEN_AT_SELECTION, HorizonPlace.switchAction(SkyView.MAP, SkyView.LIST))
        assertEquals(SkyState(SkyView.LIST, summit, null), HorizonPlace.switched(SkyState(SkyView.MAP, null, null), SkyView.LIST, summit))
        for (v in listOf(SkyView.HORIZON, SkyView.PANORAMA)) {
            assertEquals(SwitchAction.KEEP_PLACE, HorizonPlace.switchAction(v, SkyView.LIST))
            assertEquals(SwitchAction.KEEP_PLACE, HorizonPlace.switchAction(SkyView.LIST, v))
            val s = SkyState(v, summit, "Mount Rainier@3470")
            assertEquals(s.copy(view = SkyView.LIST), HorizonPlace.switched(s, SkyView.LIST, null))
        }
        assertEquals(SwitchAction.CLOSE, HorizonPlace.switchAction(SkyView.LIST, SkyView.MAP))
        assertEquals(SwitchAction.NONE, HorizonPlace.switchAction(SkyView.LIST, SkyView.LIST))
    }
    // end T344
}
