package io.github.jasondorjeshort.dystopo.app.routing

import io.github.jasondorjeshort.dystopo.app.trips.DIRECTIONS_NOT_SAVED
import io.github.jasondorjeshort.dystopo.app.trips.crowBearings
import io.github.jasondorjeshort.dystopo.app.trips.showDirectionsLabel
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.routing.Crow
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T108: the unsaved route on the route card outlives the process ([SessionRoute]), and the trip card's texts for
 * directions and crow trips.
 */
class SessionRouteTest {
    private val slope = ElevationSampler { lat, _ -> 1600.0 + lat * 1000 }

    @Test
    fun theUnsavedRouteComesBackAsItWas() {
        val p = Crow.plan(46.7864, -121.7366, 46.8024, -121.7289, slope, "Paradise to Panorama Point")
        val text = SessionRoute.encode(p, 46.7864 to -121.7366, 46.8024 to -121.7289, 1234L)
        val r = assertNotNull(SessionRoute.decode(text))
        assertEquals(p.route.id, r.plan.route.id)
        assertEquals(p.route.name, r.plan.route.name)
        assertEquals(TravelMode.CROW, r.plan.mode)
        assertEquals(RoutePath.planned(p.route.points, "crow", p.timeS).points, r.plan.route.points)
        assertEquals(p.distanceM, r.plan.distanceM)
        assertEquals(p.ascentM, r.plan.ascentM)
        assertEquals(p.timeS, r.plan.timeS)
        assertEquals(p.instructions.map { it.type }, r.plan.instructions.map { it.type })
        assertEquals(46.8024 to -121.7289, r.end)
        assertEquals(1234L, r.savedAt)
        assertEquals(true, r.plan.memory["restored"])
        // Anything else is not a route: another version, a mode this build does not know, damage.
        assertNull(SessionRoute.decode(text.replace("\"version\":1", "\"version\":2")))
        assertNull(SessionRoute.decode(text.replace("\"mode\":\"crow\"", "\"mode\":\"bushwhack\"")))
        assertNull(SessionRoute.decode(text.take(text.length / 2)))
        assertNull(SessionRoute.decode(""))
    }

    @Test
    fun theFixEndsKeepTheirSessionKeys() {
        // T122 (T114's follow-up): an end that was the phone's fix is saved as `startFix` / `endFix` (time, source, age
        // when planned) and comes back as a fix, so the card still says "From: Your location" after a restart.
        val p = Crow.plan(46.7864, -121.7366, 46.8024, -121.7289, slope, "Route")
        val fix = RouteOrigin.Fix(46.7864, -121.7366, 1_000_000L, "device")
        val from = RouteOrigin.end(fix, 1_000_000L + 300_000L) // five minutes old: stale
        val text = SessionRoute.encode(p, 46.7864 to -121.7366, 46.8024 to -121.7289, 2_000_000L, from, null)
        assertTrue("\"startFix\":{\"time\":1000000,\"source\":\"device\",\"ageS\":300}" in text, text)
        assertTrue("endFix" !in text, "a placed end has no fix key")
        val r = assertNotNull(SessionRoute.decode(text))
        assertEquals(from, r.from)
        assertEquals("Your location, 5 min ago", r.from!!.label)
        assertNull(r.to)
        // The other way round: the end is the fix (after a Swap).
        val swapped = SessionRoute.encode(p, 46.8024 to -121.7289, 46.7864 to -121.7366, 2_000_000L, null, from)
        val s = assertNotNull(SessionRoute.decode(swapped))
        assertNull(s.from)
        assertEquals(RouteOrigin.End(46.7864, -121.7366, fix, 300L), s.to)
        // A file from before T114 (no fix keys) reads as two placed points; a damaged fix key is a placed point too.
        val old = SessionRoute.encode(p, 46.7864 to -121.7366, 46.8024 to -121.7289, 2_000_000L)
        assertNull(assertNotNull(SessionRoute.decode(old)).from)
        assertNull(assertNotNull(SessionRoute.decode(text.replace("\"time\":1000000,", ""))).from)
        // A missing source reads as the recorder's.
        assertEquals("recorder", assertNotNull(SessionRoute.decode(text.replace("\"source\":\"device\",", ""))).from!!.fix!!.source)
    }

    @Test
    fun tripCardTexts() {
        assertEquals("Show 1 direction", showDirectionsLabel(1))
        assertEquals("Show 7 directions", showDirectionsLabel(7))
        assertTrue(DIRECTIONS_NOT_SAVED.startsWith("Directions were not saved with this route."))
        val crow = Crow.plan(0.0, 0.0, 0.01, 0.01, slope)
        val s = RouteSummary.of(RoutePath.planned(crow.route.points, "crow", crow.timeS), emptyList())
        assertEquals("Bearing 45° (northeast)", crowBearings(s))
    }
}
