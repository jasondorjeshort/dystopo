package io.github.jasondorjeshort.dystopo.model

import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.GraphSource
import io.github.jasondorjeshort.dystopo.routing.RoutingGraphBuilder
import io.github.jasondorjeshort.dystopo.routing.Surface
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.routing.WayAttrs
import io.github.jasondorjeshort.dystopo.routing.WayClass
import io.github.jasondorjeshort.dystopo.routing.WayLine
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import io.github.jasondorjeshort.dystopo.store.RouteImport
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.EntityKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T60: route plans (path codec, editor, summary, GPX) and their sync. Synthetic network about 111 m per 0.001 degree. */
class RoutePlanTest {
    private val path = WayAttrs(WayClass.PATH, Surface.UNPAVED)

    /** An L of trail (east along the equator, then north) and a separate line far to the east; elevation rises 100 m per 0.01 degree north. */
    private val slope = ElevationSampler { lat, _ -> 1000.0 + lat * 10_000 }
    private val graph = RoutingGraphBuilder(slope).build(
        listOf(
            WayLine.of(path, 0.0 to 0.0, 0.0 to 0.005, 0.0 to 0.010),
            WayLine.of(path, 0.0 to 0.010, 0.005 to 0.010, 0.010 to 0.010),
            WayLine.of(path, 0.0 to 0.050, 0.0 to 0.060),
        ),
        GraphSource("test", "fp", "", 14, 0),
    )
    private val editor = RouteEditor(graph, slope)
    private val ids = UuidGenerator(Random(60))

    @Test
    fun pathEncodesExactlyAndCompactly() {
        val p = RoutePath(
            "hike", listOf(ViaPoint(46.78641234567, -121.7366), ViaPoint(46.8024, -121.7289)),
            listOf(RouteLeg(LegKind.TRAIL, listOf(RoutePoint(46.78641234567, -121.7366, 1650.123), RoutePoint(46.79, -121.73, null), RoutePoint(46.8024, -121.7289, 2058.0)))),
        ).quantized()
        val text = p.encode()
        assertEquals(p, RoutePath.decode(text))
        assertEquals(text, RoutePath.decode(text).encode())
        assertEquals(46.7864123, p.via[0].lat)
        assertEquals(1650.12, p.legs[0].points[0].ele)
        assertTrue(text.length < 100, "path text is ${text.length} chars")
        assertFailsWith<IllegalArgumentException> { RoutePath.decode("AAAA") }
        assertFailsWith<IllegalArgumentException> { RoutePath.decode("not base64!") }
        assertFailsWith<IllegalArgumentException> { RoutePath.decode(text.dropLast(3)) }
        assertNull(RoutePath.decodeOrNull("@@"))
        for (n in 0..40) {
            val b = Random(n).nextBytes(n)
            assertEquals(b.toList(), Base64Url.decode(Base64Url.encode(b)).toList())
        }
        val wps = listOf(Waypoint(ids.next(), "Camp", 46.80123456789, -121.7, 1900.5, 123, "d", "c", "Flag", "t", "<x:y/>"), Waypoint("not-a-uuid", "", 0.1, 0.2))
        assertEquals(wps, RouteWaypoints.decode(RouteWaypoints.encode(wps)))
    }

    @Test
    fun legsFollowTrailsAndFallBackToStraightLines() {
        // Start near the trail (snapped onto it), a via at the corner, an end on the separate line.
        val p = editor.create(listOf(0.0003 to 0.001, 0.0 to 0.0101, 0.0 to 0.055))
        assertEquals(3, p.via.size)
        assertEquals(0.0, p.via[0].lat, 1e-9, "snapped onto the trail")
        assertEquals(listOf(LegKind.TRAIL, LegKind.STRAIGHT), p.legs.map { it.kind })
        assertEquals(listOf("not_connected"), editor.takeNotes())
        // Every leg starts and ends exactly at its via points.
        for ((i, leg) in p.legs.withIndex()) {
            assertEquals(p.via[i], ViaPoint(leg.points.first().lat, leg.points.first().lon))
            assertEquals(p.via[i + 1], ViaPoint(leg.points.last().lat, leg.points.last().lon))
        }
        // The straight leg is sampled every 25 m with DEM elevations.
        val straight = p.legs[1]
        assertTrue(straight.points.size > 150, "${straight.points.size} points")
        assertTrue(straight.points.all { it.ele != null })
        assertEquals(p, RoutePath.decode(p.encode()), "the editor returns what the store keeps")

        // A point off every trail stays where it was put, joined by straight legs.
        val off = editor.create(listOf(0.02 to 0.02, 0.0 to 0.0))
        assertEquals(ViaPoint(0.02, 0.02), off.via[0])
        assertEquals(LegKind.STRAIGHT, off.legs[0].kind)
        assertEquals(listOf("start_off_network"), editor.takeNotes())
        // No graph at all: straight, two points without a sampler.
        val bare = RouteEditor(null).create(listOf(0.0 to 0.0, 0.001 to 0.001))
        assertEquals(2, bare.legs.single().points.size)
    }

    @Test
    fun editsRerouteOnlyTheLegsTheyTouch() {
        val p0 = editor.create(listOf(0.0 to 0.0, 0.0 to 0.005, 0.0 to 0.010, 0.005 to 0.010))
        assertEquals(3, p0.legs.size)
        // Drag the corner via north along the trail: legs 1 and 2 change, leg 0 is kept as it was.
        val moved = editor.moveVia(p0, 2, 0.002, 0.0101)
        assertEquals(p0.legs[0], moved.legs[0])
        assertNotEquals(p0.legs[1], moved.legs[1])
        assertEquals(0.002, moved.via[2].lat, 1e-6)
        assertEquals(0.010, moved.via[2].lon, 1e-6, "snapped back onto the trail")
        // The leg from (0, 0.005) to the moved via goes round the corner: about 556 + 222 m.
        val leg1 = moved.legs[1].points.zipWithNext { a, b -> TrackStats.distanceM(a.lat, a.lon, b.lat, b.lon) }.sum()
        assertEquals(778.0, leg1, 5.0)

        val inserted = editor.insertVia(moved, 1, 0.0, 0.0025)
        assertEquals(5, inserted.via.size)
        assertEquals(moved.legs.drop(1), inserted.legs.drop(2), "legs after the new via shift by one")
        val removed = editor.removeVia(inserted, 1)
        assertEquals(moved.via, removed.via)
        assertEquals(moved.legs.drop(1), removed.legs.drop(1))
        // addVia puts a point beside the route into the leg it lies on, else at the end.
        val (added, at) = editor.addVia(p0, 0.0004, 0.0075)
        assertEquals(2, at)
        assertEquals(5, added.via.size)
        val (appended, end) = editor.addVia(p0, 0.010, 0.010)
        assertEquals(4, end)
        assertEquals(LegKind.TRAIL, appended.legs.last().kind)
        assertFailsWith<IllegalArgumentException> { editor.moveVia(p0, 9, 0.0, 0.0) }
        // Bike mode reroutes every leg.
        assertEquals("bike", editor.setMode(p0, "bike").mode)
    }

    @Test
    fun summaryUsesTheHikeModelAndPlacesWaypoints() {
        val p = editor.create(listOf(0.0 to 0.0, 0.010 to 0.010))
        val camp = Waypoint(ids.next(), "Camp", 0.005, 0.0102)
        val s = RouteSummary.of(RoutePlan("r", "R", p, listOf(camp)))
        assertEquals(2224.0, s.distanceM, 10.0)
        assertEquals(100.0, s.ascentM!!, 3.0)
        assertEquals(CostModel(TravelMode.HIKE).timeS(s.distanceM, s.ascentM!!), s.timeS)
        assertEquals(RouteSummary.PROFILE_SAMPLES, s.profile.size)
        val w = s.waypoints.single()
        assertEquals(1112.0 + 556.0, w.alongM, 10.0)
        assertEquals(22.0, w.offM, 3.0)
        assertEquals(1050.0, w.eleM!!, 3.0)
        assertEquals(0.0, s.straightM)
    }

    @Test
    fun gpxRoundTripKeepsIdsViaPointsLegsAndWaypoints() {
        val p = editor.create(listOf(0.0 to 0.0, 0.0 to 0.010, 0.0 to 0.055))
        val plan = RoutePlan(ids.next(), "Corner & beyond", p, listOf(Waypoint(ids.next(), "Lake", 0.0, 0.004, 1001.0, symbol = "Water")), description = "test", type = "hike")
        val gpx = RouteGpx.gpx(plan)
        assertTrue("<rte>" in gpx && "<rtept " in gpx && "<wpt " in gpx && "<dystopo:id>${plan.id}</dystopo:id>" in gpx)
        assertTrue("<dystopo:via>trail</dystopo:via>" in gpx && "<dystopo:via>straight</dystopo:via>" in gpx && "<dystopo:via></dystopo:via>" in gpx)
        val back = RouteGpx.plan(Gpx.parse(gpx).routes.single(), Gpx.parse(gpx).waypoints)
        assertEquals(plan, back)
        assertEquals(gpx, RouteGpx.gpx(back), "export, import, export is byte-identical")

        // A route from another app: its points become via points with fixed legs.
        val foreign = """<gpx version="1.1" creator="x" xmlns="http://www.topografix.com/GPX/1/1"><rte><name>Turns</name>
            <rtept lat="0.0" lon="0.0"/><rtept lat="0.0" lon="0.01"/><rtept lat="0.01" lon="0.01"/></rte></gpx>"""
        val s = InMemoryTrackStore()
        val r = RouteImport.import(s, foreign, now = 5, ids = ids)
        val imported = r.imported.single()
        assertEquals(3, imported.path.via.size)
        assertEquals(listOf(LegKind.FIXED, LegKind.FIXED), imported.path.legs.map { it.kind })
        // Dragging a via point of an imported route reroutes the legs beside it through the trails.
        val edited = editor.moveVia(imported.path, 1, 0.0, 0.0099)
        assertEquals(listOf(LegKind.TRAIL, LegKind.TRAIL), edited.legs.map { it.kind })
        // Our export imports with its ids, and a second import is skipped.
        val t = InMemoryTrackStore()
        assertEquals(plan, RouteImport.import(t, gpx, now = 6).imported.single())
        assertEquals(listOf(plan.id), RouteImport.import(t, gpx, now = 7).skipped)
        assertEquals(gpx, RouteGpx.gpx(t.routePlan(plan.id)!!))
    }

    @Test
    fun plannedRoutesSyncWithEditsAndWaypoints() {
        val a = InMemoryTrackStore("dev-a")
        val b = InMemoryTrackStore("dev-b")
        val id = ids.next()
        a.putRoutePlan(RoutePlan(id, "Loop", editor.create(listOf(0.0 to 0.0, 0.0 to 0.010)), type = "hike"), 10)
        val dragged = editor.moveVia(a.routePlan(id)!!.path, 1, 0.005, 0.0101)
        assertTrue(a.setRoutePath(id, dragged, 20))
        val w1 = Waypoint(ids.next(), "One", 0.0, 0.003)
        val w2 = Waypoint(ids.next(), "Two", 0.004, 0.010)
        assertTrue(a.setRouteWaypoints(id, listOf(w1, w2), 30))
        b.apply(Bundle.decode(a.bundle().encode()), 40)
        assertEquals(a.routePlan(id), b.routePlan(id))
        assertEquals(RouteGpx.gpx(a.routePlan(id)!!), RouteGpx.gpx(b.routePlan(id)!!))
        assertEquals(dragged, b.routePlan(id)!!.path)
        assertEquals(listOf("One", "Two"), b.routePlan(id)!!.waypoints.map { it.name })
        assertEquals(a.routeList(), b.routeList().map { it.copy(updatedAt = a.routeList().single().updatedAt) })

        // Concurrent edits: a rename on B and a drag on A both survive (separate fields); two drags resolve to the later one.
        b.setField(EntityKind.ROUTE, id, "name", "Loop (B)", 50)
        val dragA = editor.moveVia(dragged, 0, 0.0, 0.002)
        a.setRoutePath(id, dragA, 51)
        val dragB = editor.moveVia(dragged, 0, 0.0, 0.004)
        b.setRoutePath(id, dragB, 52)
        a.apply(Bundle.decode(b.bundle().encode()), 60)
        b.apply(Bundle.decode(a.bundle().encode()), 60)
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals("Loop (B)", a.routePlan(id)!!.name)
        assertEquals(dragB, a.routePlan(id)!!.path)
        // A legacy put after a plan resets the path to its points; a delete removes everything.
        a.putRoute(Route(id, "Legacy", listOf(RoutePoint(1.0, 2.0), RoutePoint(1.5, 2.5, 7.0))), 70)
        assertEquals(LegKind.FIXED, a.routePlan(id)!!.path.legs.single().kind)
        assertEquals(emptyList(), a.routePlan(id)!!.waypoints)
        assertTrue(a.deleteRoute(id, 80))
        b.apply(Bundle.decode(a.bundle().encode()), 90)
        assertNull(b.routePlan(id))
        assertEquals(emptyList(), b.routeList())
    }
}
