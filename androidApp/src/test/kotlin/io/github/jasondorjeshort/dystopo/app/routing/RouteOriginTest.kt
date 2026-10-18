package io.github.jasondorjeshort.dystopo.app.routing

import io.github.jasondorjeshort.dystopo.routing.Crow
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T114: where "Route to here" starts ([RouteOrigin]: the newer of the recorder's and the map's fix, as the waypoint's
 * "here" is chosen), how the route card names its ends, the stale-fix age, and the unsaved route keeping a fix end.
 */
class RouteOriginTest {
    private val paradise = 46.7864 to -121.7366
    private val panorama = 46.8024 to -121.7289

    @Test
    fun theOriginIsTheNewerFixTheRecordersOnATie() {
        val rec = RouteOrigin.Fix(paradise.first, paradise.second, 10_000, "recorder")
        val dev = RouteOrigin.Fix(paradise.first + 0.001, paradise.second, 20_000, "device")
        assertNull(RouteOrigin.choose(null, null))
        assertEquals(rec, RouteOrigin.choose(rec, null))
        assertEquals(dev, RouteOrigin.choose(null, dev))
        assertEquals(dev, RouteOrigin.choose(rec, dev))
        assertEquals("recorder", RouteOrigin.choose(rec.copy(timeMs = 30_000), dev)?.source)
        assertEquals("recorder", RouteOrigin.choose(rec.copy(timeMs = 20_000), dev)?.source) // a tie: the recorder's, as Tracking.dropWaypoint
    }

    @Test
    fun aFreshFixIsYourLocationAStaleOneSaysItsAge() {
        val fix = RouteOrigin.Fix(paradise.first, paradise.second, 1_000_000, "device")
        val fresh = RouteOrigin.end(fix, 1_000_000 + 119_999)
        assertEquals(119, fresh.ageS)
        assertFalse(fresh.stale)
        assertEquals("Your location", fresh.label)
        assertEquals("Your location", RouteOrigin.end(fix, 1_000_000 + 120_999).label) // 120 s: still fresh
        val stale = RouteOrigin.end(fix, 1_000_000 + 14 * 60_000 + 20_000)
        assertTrue(stale.stale)
        assertEquals("Your location, 14 min ago", stale.label)
        assertEquals("Your location, 3 h ago", RouteOrigin.end(fix, 1_000_000 + 3 * 3_600_000 + 59_000).label)
        assertEquals("Your location, 2 days ago", RouteOrigin.end(fix, 1_000_000 + 50L * 3_600_000).label)
        // A fix stamped in the future (another clock) counts as new.
        assertEquals(0, RouteOrigin.end(fix, 999_000).ageS)
        assertEquals(RouteOrigin.STALE_S, 120L)
    }

    @Test
    fun aPlacedPointIsItsCoordinates() {
        val e = RouteOrigin.End(panorama.first, panorama.second)
        assertFalse(e.isFix)
        assertFalse(e.stale)
        assertEquals("46.80240, -121.72890", e.label)
        assertEquals("point", e.json()["kind"])
        assertNull(e.json()["stale"])
        val f = RouteOrigin.end(RouteOrigin.Fix(paradise.first, paradise.second, 0, "recorder"), 200_000)
        assertEquals("fix", f.json()["kind"])
        assertEquals(true, f.json()["stale"])
        assertEquals("recorder", f.json()["source"])
    }

    @Test
    fun theUnsavedRouteKeepsAnEndThatWasTheFix() {
        val slope = ElevationSampler { lat, _ -> 1600.0 + lat * 1000 }
        val p = Crow.plan(paradise.first, paradise.second, panorama.first, panorama.second, slope, "Route")
        val from = RouteOrigin.end(RouteOrigin.Fix(paradise.first, paradise.second, 5_000, "device"), 5_000 + 840_000)
        val to = RouteOrigin.End(panorama.first, panorama.second)
        val r = assertNotNull(SessionRoute.decode(SessionRoute.encode(p, paradise, panorama, 1L, from, to)))
        val f = assertNotNull(r.from)
        assertEquals(from.fix, f.fix)
        assertEquals(840L, f.ageS)
        assertEquals("Your location, 14 min ago", f.label)
        assertNull(r.to) // a placed point needs no extra key
        // A file from before T114 (no fix keys) reads as two placed points.
        val old = assertNotNull(SessionRoute.decode(SessionRoute.encode(p, paradise, panorama, 1L)))
        assertNull(old.from)
        assertNull(old.to)
        assertEquals(paradise, old.start)
    }
}
