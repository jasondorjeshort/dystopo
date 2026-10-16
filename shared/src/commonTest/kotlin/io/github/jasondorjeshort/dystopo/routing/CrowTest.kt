package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.model.LegKind
import io.github.jasondorjeshort.dystopo.model.RouteEditor
import io.github.jasondorjeshort.dystopo.model.RouteLeg
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.model.ViaPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T108: crow mode (a straight line, no graph), a mode this build does not know (ADR 0033 Decision 3: named plainly,
 * not edited as a hike), and directions kept across an edit for the legs it left alone ([Directions.splice]).
 * Synthetic places near the equator (about 111 m per 0.001 degree).
 */
class CrowTest {
    /** A plane rising 1 m for every 0.0001 degree north. */
    private val slope = ElevationSampler { lat, _ -> 1000.0 + lat * 10_000 }

    private val source = GraphSource("crow", "fp", "", 14, 0, drive = true)
    private fun trail(name: String) = WayAttrs(WayClass.PATH, Surface.UNPAVED, name = name)

    /** Main Trail west to east along the equator, Cross Trail south to north through (0, 0). */
    private fun plus() = RoutingGraphBuilder().build(
        listOf(WayLine.of(trail("Main Trail"), 0.0 to -0.002, 0.0 to 0.002), WayLine.of(trail("Cross Trail"), -0.002 to 0.0, 0.002 to 0.0)),
        source,
    )

    @Test
    fun crowIsAStraightLineWithBearingProfileAndOneArrival() {
        val p = Crow.plan(0.0, 0.0, 0.01, 0.01, slope, "Line")
        assertEquals(TravelMode.CROW, p.mode)
        assertEquals(1573.0, p.distanceM, 5.0)
        assertEquals(45, Crow.bearingDeg(0.0, 0.0, 0.01, 0.01))
        assertEquals(100.0, p.ascentM!!, 1.0)
        assertEquals(0.0, p.descentM!!, 0.1)
        assertEquals(64, p.route.points.size, "a point every 25 m")
        assertTrue(p.profile.size >= 2 && p.profile.last().eleM!! > p.profile.first().eleM!!)
        assertEquals(listOf(Maneuver.ARRIVE), p.instructions.map { it.type })
        assertEquals(p.distanceM, p.instructions.single().alongM, 0.01)
        assertEquals(45, p.instructions.single().bearingDeg)
        assertTrue(p.route.description!!.contains("bearing 45°"), p.route.description)
        assertEquals(45, p.json()["bearingDeg"])
        assertEquals("crow", p.json()["mode"])
        // Without a DEM: the line, no heights.
        val flat = Crow.plan(0.0, 0.0, 0.01, 0.01, null)
        assertEquals(2, flat.route.points.size)
        assertEquals(null, flat.ascentM)
        // Hike's model over the line: a lower bound for walking it.
        assertEquals(CostModel(TravelMode.HIKE).timeS(p.distanceM, 100.0), p.timeS, 1.0)
    }

    @Test
    fun everyRouterDrawsCrowWithoutSearchingAndTheEditorMakesStraightLegs() {
        val g = plus()
        val r = Router(g).route(0.0005, -0.0015, 0.0015, 0.0015, TravelMode.CROW)
        assertIs<RouteResult.Found>(r)
        assertEquals(0, r.plan.expanded)
        assertEquals(0.0005, r.plan.route.points.first().lat, "not snapped onto Main Trail")
        // The remote router does not ask the server.
        var asked = 0
        val remote = RemoteRouter("http://server", { asked++; 500 to "" })
        assertIs<RouteResult.Found>(remote.route(0.0, 0.0, 0.001, 0.001, TravelMode.CROW))
        assertEquals(0, asked)

        val ed = RouteEditor(Router(g), slope)
        val path = ed.create(listOf(0.0005 to -0.0015, 0.0015 to 0.0015, 0.0002 to 0.0019), "crow")
        assertEquals(listOf(LegKind.STRAIGHT, LegKind.STRAIGHT), path.legs.map { it.kind })
        assertEquals(ViaPoint(0.0005, -0.0015), path.via.first(), "via points stay where they were put")
        assertEquals(emptyList(), ed.takeNotes(), "no off-network notes for crow")
        assertEquals(listOf(Maneuver.ARRIVE), ed.directions(path).map { it.type })
        val s = RouteSummary.of(path, emptyList())
        assertEquals(s.distanceM, s.straightM, 0.01)
        assertEquals(2, s.bearings.size)
        assertEquals(Crow.bearingDeg(0.0005, -0.0015, 0.0015, 0.0015), s.bearings[0].bearingDeg)
        // Kept from the route card: one straight leg.
        val kept = RoutePath.planned(Crow.plan(0.0, 0.0, 0.01, 0.01, slope).route.points, "crow", 0.0)
        assertEquals(listOf(LegKind.STRAIGHT), kept.legs.map { it.kind })
        // Back to hike: the legs follow the trails again.
        assertTrue(ed.setMode(path, "hike").legs.any { it.kind == LegKind.TRAIL })
    }

    @Test
    fun aModeThisBuildDoesNotKnowIsNamedAsItIsAndNotEdited() {
        assertEquals("hike", TravelMode.labelOf("hike"))
        assertEquals("crow", TravelMode.labelOf("crow"))
        assertEquals("bushwhack (unknown mode)", TravelMode.labelOf("bushwhack"))
        assertNotEquals("hike", TravelMode.labelOf("bushwhack"))
        assertTrue(TravelMode.known("crow"))
        assertTrue(!TravelMode.known("bushwhack"))
        assertEquals("hike, bike, drive or crow", TravelMode.labels)
        // A trip from a newer build: its summary keeps the mode's name.
        val newer = RoutePath(
            "bushwhack", listOf(ViaPoint(0.0, 0.0), ViaPoint(0.001, 0.001)),
            listOf(RouteLeg(LegKind.STRAIGHT, listOf(RoutePoint(0.0, 0.0), RoutePoint(0.001, 0.001)))),
        ).quantized()
        assertEquals("bushwhack", RouteSummary.of(newer, emptyList()).mode)
        // The editor refuses to replan it as a hike, saying why; changing it to a known mode is allowed.
        val ed = RouteEditor(Router(plus()))
        val e = assertFailsWith<IllegalArgumentException> { ed.moveVia(newer, 1, 0.002, 0.002) }
        assertTrue(e.message!!.contains("\"bushwhack\" is from a newer Dystopo"), e.message)
        assertFailsWith<IllegalArgumentException> { ed.reroute(newer) }
        assertEquals("hike", ed.setMode(newer, "hike").mode)
    }

    @Test
    fun anEditKeepsTheDirectionsOfLegsItLeftAlone() {
        val ed = RouteEditor(Router(plus()))
        // West along Main Trail, left onto Cross Trail at (0, 0), on to a via point, then a second leg north.
        val before = ed.create(listOf(0.0 to -0.0015, 0.0015 to 0.0, 0.0019 to 0.0))
        val withGraph = ed.directions(before)
        assertTrue(withGraph.any { it.type == Maneuver.LEFT && it.name == "Cross Trail" }, "$withGraph")
        // On a phone without the graph, the last via point moves: leg 1 is rerouted (straight here), leg 0 is kept.
        val bare = RouteEditor(null)
        val after = bare.moveVia(before, 2, 0.0018, 0.0003)
        assertEquals(before.legs[0], after.legs[0])
        val kept = bare.directions(after, before, withGraph)
        assertTrue(kept.any { it.type == Maneuver.LEFT && it.name == "Cross Trail" }, "the turn on the kept leg survives: $kept")
        assertEquals(kept.map { it.alongM }.sorted(), kept.map { it.alongM })
        assertTrue(kept.any { it.type == Maneuver.OFF_NETWORK }, "the new straight leg is said")
        // Without the old directions only the frame is left (what a fresh plan on that phone could say).
        assertTrue(bare.directions(after).none { it.type == Maneuver.LEFT })
        // A mode change starts over.
        val bike = bare.setMode(before, "bike")
        assertTrue(bare.directions(bike, before, withGraph).none { it.type == Maneuver.LEFT })
    }
}
