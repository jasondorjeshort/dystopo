package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.model.LegKind
import io.github.jasondorjeshort.dystopo.model.RouteEditor
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T89: one drive time everywhere. The router adds up each road's time at its speed (a fast secondary,
 * a slow minor road, a speed limit); the trip summary used a flat 45 km/h on the points instead. Now a
 * drive leg keeps the router's time, so `devtool route` (the router), the route card and the kept trip
 * ([RoutePath.planned]), the trip editor and the browser ([RouteEditor] then [RouteSummary]) show one
 * number, and it survives the path's encoding (the sync value).
 */
class DriveTimeTest {
    private val source = GraphSource("drive-time", "fp", "", 14, 0, drive = true)

    /** A trailhead drive near the equator: 22 km of secondary (65 km/h), then a 2.2 km minor road limited to 30. */
    private fun graph(): RoutingGraph = RoutingGraphBuilder().build(
        listOf(
            WayLine.of(WayAttrs(WayClass.SECONDARY, Surface.PAVED, name = "Valley Highway"), 0.0 to 0.0, 0.0 to 0.2),
            WayLine.of(WayAttrs(WayClass.MINOR, Surface.PAVED, name = "Trailhead Road", maxspeedKmh = 30), 0.0 to 0.2, 0.02 to 0.2),
            WayLine.of(WayAttrs(WayClass.PATH, name = "Ridge Trail"), 0.0 to 0.0, 0.02 to 0.2),
        ),
        source,
    )

    private fun found(r: RouteResult): PlannedRoute {
        assertIs<RouteResult.Found>(r, "expected a route, got $r")
        return r.plan
    }

    @Test
    fun theCardTheSheetAndTheReportShowTheRoutersTime() {
        val router = Router(graph())
        val plan = found(router.route(0.0, 0.001, 0.019, 0.2, TravelMode.DRIVE))
        // The router: 22 km at 65 km/h plus 2.1 km at 30 km/h (about 25 minutes), not 24 km at 45 km/h (32).
        val expected = plan.metersByClass.getValue(WayClass.SECONDARY) / (65 / 3.6) + plan.metersByClass.getValue(WayClass.MINOR) / (30 / 3.6)
        assertEquals(expected, plan.timeS, 1.0)
        val flat = CostModel(TravelMode.DRIVE).timeS(plan.distanceM, 0.0)
        assertTrue(flat - plan.timeS > 60, "the flat 45 km/h figure (${flat}s) differs from the router's (${plan.timeS}s), so the test means something")

        // The route card and Keep: one trail leg carrying the router's time.
        val kept = RoutePath.planned(plan.route.points, "drive", plan.timeS)
        assertEquals(round(plan.timeS), RouteSummary.of(kept, emptyList()).timeS)
        // The trip editor and the browser (the server's RouteEditor, then the summary in its reply).
        val ed = RouteEditor(router)
        val trip = ed.create(listOf(0.0 to 0.001, 0.019 to 0.2), "drive")
        assertEquals(LegKind.TRAIL, trip.legs.single().kind)
        assertEquals(round(plan.timeS), RouteSummary.of(trip, emptyList()).timeS)
        // The stored value: the time survives encode and decode (version 2), and a second leg adds its own.
        val decoded = RoutePath.decode(trip.encode())
        assertEquals(trip, decoded)
        assertEquals(RouteSummary.of(trip, emptyList()).timeS, RouteSummary.of(decoded, emptyList()).timeS)
        val back = ed.addVia(trip, 0.0, 0.002).first // a new end back near the start: a second drive leg
        assertEquals(2, back.legs.size)
        assertEquals(back.legs.sumOf { it.timeS!! }, RouteSummary.of(back, emptyList()).timeS)
    }

    @Test
    fun hikeAndBikePathsAreUnchangedAndLegsWithoutATimeUse45() {
        val router = Router(graph())
        val ed = RouteEditor(router)
        val hike = ed.create(listOf(0.0 to 0.001, 0.019 to 0.2), "hike")
        assertNull(hike.legs.single().timeS)
        assertEquals(RoutePath.VERSION, io.github.jasondorjeshort.dystopo.model.Base64Url.decode(hike.encode())[0].toInt())
        assertNull(RoutePath.planned(hike.points, "bike", 1234.0).legs.single().timeS)
        // Switching a drive to hiking drops the times; a fixed (imported) drive leg is timed at 45 km/h.
        val drive = ed.setMode(hike, "drive")
        assertTrue(drive.legs.single().timeS!! > 0)
        assertNull(ed.setMode(drive, "hike").legs.single().timeS)
        val imported = RoutePath.fixed(drive.points, "drive")
        val s = RouteSummary.of(imported, emptyList())
        assertEquals(CostModel(TravelMode.DRIVE).timeS(s.distanceM, 0.0), s.timeS, 1.0)
    }
}
