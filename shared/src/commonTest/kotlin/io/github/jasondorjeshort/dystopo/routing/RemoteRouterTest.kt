package io.github.jasondorjeshort.dystopo.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T77: the phone asks the server for a route it cannot plan on its own packs. */
class RemoteRouterTest {
    /** What `GET /route` answers (RouteRoutes), cut down: one trail leg of three points. */
    private val reply = """{"pack":"rainier-east","mode":"drive","legs":[{"kind":"trail","points":3,
        "coordinates":[[-121.7366,46.7864,1645.0],[-121.6,46.7,1200.5],[-120.5059,46.6021,330.0]]}],
        "summary":{"distanceM":120000.0,"ascentM":210.0,"descentM":1525.0},"notes":[],"messages":{},"ms":42}"""

    private val straight = """{"legs":[{"kind":"straight","points":2,"coordinates":[[-121.7,46.7],[-120.5,46.6]]}],
        "summary":{"distanceM":95000.0},"notes":["end_off_network"],
        "messages":{"end_off_network":"no road open to cars within 300 m of the end point (none within 10.0 km)"}}"""

    @Test
    fun aRouteFromTheServerBecomesAPlannedRoute() {
        val urls = ArrayList<String>()
        val r = RemoteRouter("http://10.0.2.2:9181/", { u -> urls += u; 200 to reply })
            .route(46.7864, -121.7366, 46.6021, -120.5059, TravelMode.DRIVE, 300.0, "To Yakima")
        assertIs<RouteResult.Found>(r)
        assertEquals("http://10.0.2.2:9181/route?from=46.7864,-121.7366&to=46.6021,-120.5059&mode=drive&snap=300", urls.single())
        val p = r.plan
        assertEquals(3, p.route.points.size)
        assertEquals(1645.0, p.route.points.first().ele)
        assertEquals("drive", p.route.type)
        assertEquals(210.0, p.ascentM)
        assertTrue(p.route.description!!.contains("planned by the Dystopo server at 10.0.2.2:9181"), p.route.description)
        assertEquals("http://10.0.2.2:9181/", p.memory["remote"])
    }

    @Test
    fun straightLegsAndErrorsAreFailures() {
        val off = RemoteRouter("http://s", { 200 to straight }).route(46.7, -121.7, 46.6, -120.5, TravelMode.DRIVE)
        assertIs<RouteResult.Failed>(off)
        assertEquals(RouteFailure.END_OFF_NETWORK, off.reason)
        assertTrue(off.message.startsWith("no road open to cars"), off.message)
        val down = RemoteRouter("http://s", { throw IllegalStateException("connection refused") }).route(46.7, -121.7, 46.6, -120.5)
        assertIs<RouteResult.Failed>(down)
        assertEquals(RouteFailure.REMOTE_UNAVAILABLE, down.reason)
        assertTrue(down.message.contains("connection refused"))
        val bad = RemoteRouter("http://s", { 400 to """{"error":"bad point"}""" }).route(46.7, -121.7, 46.6, -120.5)
        assertEquals(RouteFailure.REMOTE_UNAVAILABLE, (bad as RouteResult.Failed).reason)
        assertTrue(bad.message.contains("bad point"))
    }

    /** A local router that always fails with [reason] (and counts its calls). */
    private class Failing(val reason: RouteFailure) : Router {
        var calls = 0
        override fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: TravelMode, maxSnapM: Double, name: String, ids: io.github.jasondorjeshort.dystopo.model.UuidGenerator): RouteResult {
            calls++
            return RouteResult.Failed(reason, "local: ${reason.code}")
        }
        override fun nearest(lat: Double, lon: Double, maxDistanceM: Double): EdgeSnap? = null
    }

    @Test
    fun theServerIsAskedOnlyForEndsBeyondTheLocalPacks() {
        var asked = 0
        val remote = RemoteRouter("http://s", { asked++; 200 to reply })
        // Inside the packs (covers is true for both ends): an off-network tap stays the local failure.
        val inside = FallbackRouter(Failing(RouteFailure.START_OFF_NETWORK), remote) { _, _ -> true }
        assertEquals("local: start_off_network", (inside.route(46.78, -121.77, 46.79, -121.77) as RouteResult.Failed).message)
        assertEquals(0, asked)
        // The end beyond them: the server's route.
        val beyond = FallbackRouter(Failing(RouteFailure.END_OFF_NETWORK), remote) { lat, _ -> lat > 46.7 }
        assertIs<RouteResult.Found>(beyond.route(46.7864, -121.7366, 46.6021, -120.5059, TravelMode.DRIVE))
        assertEquals(1, asked)
        // A pack built before drive data is not the server's business.
        val old = FallbackRouter(Failing(RouteFailure.DRIVE_UNSUPPORTED), remote) { _, _ -> false }
        assertIs<RouteResult.Failed>(old.route(46.7864, -121.7366, 46.6021, -120.5059, TravelMode.DRIVE))
        assertEquals(1, asked)
        // No local packs at all: straight to the server; when it fails too, its failure.
        assertIs<RouteResult.Found>(FallbackRouter(null, remote) { _, _ -> false }.route(46.7864, -121.7366, 46.6021, -120.5059))
        val down = FallbackRouter(Failing(RouteFailure.END_OFF_NETWORK), RemoteRouter("http://s", { throw RuntimeException("x") })) { _, _ -> false }
        assertEquals("local: end_off_network", (down.route(46.7864, -121.7366, 46.6021, -120.5059) as RouteResult.Failed).message)
        assertNull(FallbackRouter(null, remote) { _, _ -> false }.nearest(46.7, -121.7, 300.0))
    }
}
