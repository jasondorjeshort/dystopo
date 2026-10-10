package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.LegKind
import io.github.jasondorjeshort.dystopo.model.RouteLeg
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.RouteWaypoints
import io.github.jasondorjeshort.dystopo.model.ViaPoint
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T65: per-waypoint route writes, compaction of superseded route fields, the path size cap. */
class RouteOpsTest {
    private val route = "30000000-0000-4000-8000-000000000001"
    private fun wp(n: Int, name: String = "W$n") = Waypoint("40000000-0000-4000-8000-00000000000$n", name, 0.001 * n, 0.002, 100.0 + n)

    private fun line(n: Int, lat0: Double = 0.0) = RoutePath(
        "hike", listOf(ViaPoint(lat0, 0.0), ViaPoint(lat0 + 0.0001 * (n - 1), 0.001)),
        listOf(RouteLeg(LegKind.STRAIGHT, List(n) { RoutePoint(lat0 + 0.0001 * it, 0.001 * it / (n - 1), 1000.0 + it) })),
    ).quantized()

    private fun pair(): Pair<InMemoryTrackStore, InMemoryTrackStore> {
        val a = InMemoryTrackStore("dev-a")
        val b = InMemoryTrackStore("dev-b")
        a.putRoutePlan(RoutePlan(route, "Trip", line(3), listOf(wp(1)), type = "hike"), 10)
        syncPair(a, b, 20)
        return a to b
    }

    @Test
    fun concurrentWaypointEditsOnTwoDevicesAllSurvive() {
        val (a, b) = pair()
        // A adds W2 and renames W1; B adds W3 at the same time. With one `waypoints` list (T60) the later list won.
        assertTrue(a.setRouteWaypoints(route, listOf(wp(1, "First"), wp(2)), 30))
        assertTrue(b.setRouteWaypoints(route, listOf(wp(1), wp(3)), 31))
        syncPair(a, b, 40)
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals(listOf("First", "W2", "W3"), a.routePlan(route)!!.waypoints.map { it.name })
        // Concurrent remove of W2 on A and rename of W3 on B: both apply.
        a.setRouteWaypoints(route, a.routePlan(route)!!.waypoints.filter { it.name != "W2" }, 50)
        b.setRouteWaypoints(route, b.routePlan(route)!!.waypoints.map { if (it.name == "W3") it.copy(name = "Third") else it }, 51)
        syncPair(a, b, 60)
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals(listOf("First", "Third"), b.routePlan(route)!!.waypoints.map { it.name })
        // Two edits of the same waypoint: last writer wins.
        a.setRouteWaypoints(route, listOf(wp(1, "A wins?"), wp(3, "Third")), 70)
        b.setRouteWaypoints(route, listOf(wp(1, "B wins"), wp(3, "Third")), 71)
        syncPair(a, b, 80)
        assertEquals("B wins", a.routePlan(route)!!.waypoints.first().name)
        // Nothing changed: nothing written.
        val ops = a.opCount
        assertTrue(a.setRouteWaypoints(route, a.routePlan(route)!!.waypoints, 90))
        assertEquals(ops, a.opCount)
        assertFalse(a.setRouteWaypoints("50000000-0000-4000-8000-000000000001", listOf(wp(1)), 90), "no such route")
    }

    @Test
    fun aFullListWriteAndAPutReplaceThePerWaypointState() {
        val (a, _) = pair()
        a.setRouteWaypoints(route, listOf(wp(1), wp(2)), 30)
        // A full `waypoints` write (an older build, or a plan put) replaces everything before it.
        a.setField(EntityKind.ROUTE, route, "waypoints", RouteWaypoints.encode(listOf(wp(4))), 40)
        assertEquals(listOf("W4"), a.routePlan(route)!!.waypoints.map { it.name })
        a.setRouteWaypoints(route, listOf(wp(4), wp(5)), 50)
        assertEquals(listOf("W4", "W5"), a.routePlan(route)!!.waypoints.map { it.name })
        a.putRoutePlan(RoutePlan(route, "Trip", line(3), listOf(wp(6)), type = "hike"), 60)
        assertEquals(listOf("W6"), a.routePlan(route)!!.waypoints.map { it.name })
        // `wpt:` fields are route fields; other kinds do not accept them.
        assertTrue(RouteOps.waypointField("x") in EntityKind.ROUTE.fields)
        assertFalse("wpt:" in EntityKind.ROUTE.fields)
        assertFalse(RouteOps.waypointField("x") in EntityKind.TRACK.fields)
        assertFalse(EntityKind.ROUTE.fields.any { it.startsWith("wpt:") }, "iteration lists the named fields only")
    }

    @Test
    fun compactionKeepsOnlyTheLatestPathAndTheStateIsUnchanged() {
        val (a, b) = pair()
        repeat(20) { i -> assertTrue(a.setRoutePath(route, line(3 + i), 30L + i)) }
        a.setRouteWaypoints(route, listOf(wp(1, "x")), 60)
        a.setRouteWaypoints(route, listOf(wp(1, "y")), 61)
        val before = a.snapshot()
        val opsBefore = a.opCount
        val r = a.compact(1000)
        assertEquals(20, r.opsDropped, "20 older paths (the plan's and 19 edits); waypoint writes all stay")
        assertEquals(opsBefore - 20, a.opCount)
        assertEquals(before, a.snapshot())
        // A peer that missed every intermediate path still converges, and compacts the same way.
        syncPair(a, b, 2000)
        assertEquals(before, b.snapshot())
        assertEquals(line(22), b.routePlan(route)!!.path)
        // Compaction keeps the order (seen in the simulator, seed 59, when it dropped a waypoint's first write):
        // W3 added before W2, then W3 renamed.
        a.setRouteWaypoints(route, a.routePlan(route)!!.waypoints + wp(3), 3000)
        a.setRouteWaypoints(route, a.routePlan(route)!!.waypoints + wp(2), 3001)
        a.setRouteWaypoints(route, a.routePlan(route)!!.waypoints.map { if (it.name == "W3") it.copy(name = "Three") else it }, 3002)
        val order = a.routePlan(route)!!.waypoints.map { it.name }
        a.compact(3003)
        assertEquals(order, a.routePlan(route)!!.waypoints.map { it.name })
        assertEquals(listOf("y", "Three", "W2"), order, "in order of each waypoint's first write")
        // Nothing left to drop.
        assertEquals(0, a.compact(4000).opsDropped)
        // Only route fields: a renamed track keeps both writes.
        assertEquals(listOf(Hlc(5, 0, "d")), RouteOps.superseded(listOf(
            Op(Hlc(5, 0, "d"), SetField(EntityKind.ROUTE, route, "path", "x")),
            Op(Hlc(6, 0, "d"), SetField(EntityKind.ROUTE, route, "path", "y")),
            Op(Hlc(7, 0, "d"), SetField(EntityKind.ROUTE, route, "name", "n")),
        )))
    }

    @Test
    fun aPathOverTheCapIsRefusedWithAClearError() {
        val (a, _) = pair()
        val huge = line(60_000)
        val e = assertFailsWith<RouteOps.RouteTooLargeException> { a.setRoutePath(route, huge, 30) }
        assertTrue("too large" in e.message!! && "split it" in e.message!! && "60000 points" in e.message!!, e.message)
        assertFailsWith<RouteOps.RouteTooLargeException> { a.putRoutePlan(RoutePlan("50000000-0000-4000-8000-000000000002", "Huge", huge), 31) }
        assertEquals(line(3), a.routePlan(route)!!.path, "nothing written")
        // A long but reasonable route (10,000 points, about 100 km at 10 m spacing) fits.
        assertTrue(a.setRoutePath(route, line(10_000), 32))
    }
}
