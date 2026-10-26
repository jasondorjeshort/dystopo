package io.github.jasondorjeshort.dystopo.app.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T146: `POST /location/replay?route=current&kmh=` (no body) beside the GPX body path ([ReplayChecks.refusal]). */
class ReplayChecksTest {
    private fun refusal(body: Boolean, route: String?, kmh: Double?, hasRoute: Boolean = true, rate: Double = 30.0) =
        ReplayChecks.refusal(rate, bodyEmpty = !body, record = null, recording = false, route = route, kmh = kmh, hasRoute = hasRoute)

    @Test
    fun theCurrentRouteWithNoBody() {
        assertNull(refusal(body = false, route = "current", kmh = 40.0))
        assertNull(refusal(body = false, route = "current", kmh = 300.0))
    }

    @Test
    fun refusals() {
        assertEquals(400, refusal(false, "current", null)!!.first, "kmh is needed")
        assertEquals(400, refusal(false, "current", 0.0)!!.first)
        assertEquals(400, refusal(false, "current", 301.0)!!.first)
        assertEquals(400, refusal(false, "kept", 40.0)!!.first, "only the current route")
        assertTrue("not both" in refusal(true, "current", 40.0)!!.second, "a body and a route")
        assertEquals(404, refusal(false, "current", 40.0, hasRoute = false)!!.first, "no route planned")
        assertEquals(400, refusal(false, "current", 40.0, rate = 0.0)!!.first, "the rate is checked first")
        // The GPX body path is unchanged, and its message names the route path.
        assertNull(refusal(true, null, null))
        val noBody = refusal(false, null, null)!!
        assertEquals(400, noBody.first)
        assertTrue("route=current" in noBody.second, noBody.second)
    }

    @Test
    fun recordingConflictBeforeNoRoute() {
        val r = ReplayChecks.refusal(30.0, bodyEmpty = true, record = "Drive", recording = true, route = "current", kmh = 40.0, hasRoute = false)!!
        assertEquals(409, r.first)
    }
}
