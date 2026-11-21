package io.github.jasondorjeshort.dystopo.aircraft

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T314: the estimated path before the app saw a flight, and the route-line planner. */
class RouteLinesTest {
    private val sea = Airport("KSEA", "SEA", 47.4479, -122.3103, "Seattle–Tacoma International Airport", "Seattle")
    private val den = Airport("KDEN", "DEN", 39.8600, -104.6738, "Denver International Airport", "Denver")
    private val pdx = Airport("KPDX", "PDX", 45.5887, -122.5980, "Portland International Airport", "Portland")
    private val seaDen = FlightRoute("ASA1670", listOf(sea, den))

    private fun along(a: Airport, b: Airport, m: Double): Pair<Double, Double> =
        AircraftGeo.move(a.lat, a.lon, AircraftGeo.bearingDeg(a.lat, a.lon, b.lat, b.lon), m)

    private fun check(route: FlightRoute, p: Pair<Double, Double>, to: Airport, vrFpm: Double = 0.0): RoutePlausibility.Match =
        RoutePlausibility.check(route, p.first, p.second, AircraftGeo.bearingDeg(p.first, p.second, to.lat, to.lon), 420.0, false, vrFpm)

    private fun pt(p: Pair<Double, Double>, t: Long) = TrackPoint(p.first, p.second, 9000.0, t)

    /** An aircraft as the tracker hands it over, at [p] with [match]. */
    private fun now(hex: String, p: Pair<Double, Double>, match: RoutePlausibility.Match?): AircraftNow {
        val r = AircraftReport(hex, match?.route?.callSign, null, null, null, p.first, p.second, 30000.0, 30000.0, false, 120.0, 420.0, 0.0, 0.0)
        return AircraftNow(r, p.first, p.second, 0.0, 9000.0, AltitudeKind.GEOMETRIC, 0.0, false, match, null, null, null, hex, emptyList())
    }

    @Test
    fun estimatedPastRunsFromTheOriginToTheTracksFirstPointOnTheGreatCircle() {
        val first = along(sea, den, 300_000.0)
        val here = along(sea, den, 420_000.0)
        val m = check(seaDen, here, den)
        assertTrue(m.plausible, m.reason)
        val history = listOf(pt(first, 0), pt(along(sea, den, 360_000.0), 1), pt(here, 2))
        val past = assertNotNull(RouteLines.estimatedPast(m, history))
        assertEquals(sea.lat to sea.lon, past.first(), "starts at the origin airport")
        assertEquals(first, past.last(), "ends exactly at the history's first point, where the solid track starts")
        assertEquals(RouteLines.points(300_000.0), past.size)
        assertTrue(past.size in RouteLines.MIN_POINTS..RouteLines.MAX_POINTS)
        for ((la, lo) in past) {
            val xt = AircraftGeo.crossTrackM(sea.lat, sea.lon, first.first, first.second, la, lo)
            assertTrue(abs(xt) < 1.0, "on the great circle: $xt m off")
        }
        assertNull(RouteLines.estimatedPastWhyNot(m, history))
        assertEquals("Dotted: estimated path from SEA", AircraftText.estimatedLine(sea, AirportNaming.BOTH))
        assertEquals("Dotted: estimated path from Seattle", AircraftText.estimatedLine(sea, AirportNaming.CITIES))
    }

    @Test
    fun noEstimateWithoutAPlausibleRouteOrNearTheOrigin() {
        val here = along(sea, den, 420_000.0)
        val history = listOf(pt(along(sea, den, 300_000.0), 0), pt(here, 1))
        assertNull(RouteLines.estimatedPast(null, history), "no route")
        // 400 km off the leg: not plausible.
        val off = AircraftGeo.move(here.first, here.second, 30.0, 400_000.0)
        val bad = check(seaDen, off, den)
        assertTrue(!bad.plausible)
        assertNull(RouteLines.estimatedPast(bad, listOf(pt(off, 0))))
        // The track starts 10 km from the origin: the solid track covers it.
        val good = check(seaDen, here, den)
        val nearOrigin = listOf(pt(along(sea, den, 10_000.0), 0), pt(here, 1))
        assertNull(RouteLines.estimatedPast(good, nearOrigin))
        assertEquals("track starts at the origin", RouteLines.estimatedPastWhyNot(good, nearOrigin))
        // Just past 15 km it draws.
        assertNotNull(RouteLines.estimatedPast(good, listOf(pt(along(sea, den, 16_000.0), 0), pt(here, 1))))
        assertNull(RouteLines.estimatedPast(good, emptyList()), "no track yet")
    }

    @Test
    fun anABARouteWhoseTrackBeganOnTheEarlierLegDrawsNothingBackwards() {
        val route = FlightRoute("TST1", listOf(sea, pdx, sea))
        // Seen first on the way out to PDX, 60 km from SEA, then through PDX and 40 km back towards SEA.
        val out1 = along(sea, pdx, 60_000.0)
        val out2 = along(sea, pdx, 150_000.0)
        val atPdx = along(pdx, sea, 3_000.0)
        val back = along(pdx, sea, 40_000.0)
        val m = check(route, back, sea, 1500.0) // climbing out of PDX (level, it would read as arriving there on the first leg)
        assertTrue(m.plausible, m.reason)
        assertEquals("PDX", m.origin?.code, "the current leg is the way back")
        val history = listOf(pt(out1, 0), pt(out2, 1), pt(atPdx, 2), pt(back, 3))
        assertNull(RouteLines.estimatedPast(m, history))
        assertEquals("track passed the origin after it began", RouteLines.estimatedPastWhyNot(m, history))
        // Begun behind the origin on the leg's line (beyond its slack): the along-track rule.
        val behind = AircraftGeo.move(sea.lat, sea.lon, AircraftGeo.bearingDeg(sea.lat, sea.lon, den.lat, den.lon) + 180.0, 300_000.0)
        val here = along(sea, den, 420_000.0)
        val g = check(seaDen, here, den)
        assertEquals("track starts on an earlier leg", RouteLines.estimatedPastWhyNot(g, listOf(pt(behind, 0), pt(here, 1))))
        // The same route with the track begun on the way back draws from PDX.
        val fresh = listOf(pt(along(pdx, sea, 25_000.0), 0), pt(back, 1))
        val past = assertNotNull(RouteLines.estimatedPast(m, fresh))
        assertEquals(pdx.lat to pdx.lon, past.first())
    }

    @Test
    fun selectedModeDrawsLinesOnlyForTheSelectedFlight() {
        val here = along(sea, den, 420_000.0)
        val m = check(seaDen, here, den)
        val other = along(sea, den, 900_000.0)
        val list = listOf(now("aaa111", here, m), now("bbb222", other, check(seaDen, other, den)))
        val history = listOf(pt(along(sea, den, 300_000.0), 0), pt(here, 1))
        val p = RouteLines.plan(list, RouteLinesMode.SELECTED, "aaa111", history, here.first, here.second)
        assertEquals("aaa111", p.selected)
        assertNotNull(p.estimatedPast)
        assertEquals(sea, p.estimatedFrom)
        assertEquals(RouteLines.REMAINING_SEGMENTS + 1, p.remaining?.size, "the dashed rest as T311 drew it")
        assertTrue(p.faint.isEmpty())
        assertEquals(1, p.drawn)
        assertEquals(0, p.capped)
        val none = RouteLines.plan(list, RouteLinesMode.SELECTED, null, emptyList(), null, null)
        assertEquals(0, none.drawn)
        assertNull(none.estimatedPast)
        assertTrue(none.faint.isEmpty())
    }

    @Test
    fun allModeDrawsOneFaintLinePerOtherPlausibleFlightCappedNearestTheCentre() {
        val centre = along(sea, den, 400_000.0)
        val list = ArrayList<AircraftNow>()
        // 60 plausible flights spread along the leg, one not plausible, one without a route.
        for (i in 0 until 60) {
            val p = along(sea, den, 100_000.0 + i * 20_000.0)
            list += now("p$i", p, check(seaDen, p, den))
        }
        val off = AircraftGeo.move(centre.first, centre.second, 30.0, 400_000.0)
        list += now("off", off, check(seaDen, off, den))
        list += now("none", centre, null)
        val p = RouteLines.plan(list, RouteLinesMode.ALL, "p15", emptyList(), centre.first, centre.second)
        assertEquals(RouteLines.CAP, p.faint.size)
        assertEquals(59 - RouteLines.CAP, p.capped, "59 others with a plausible route, 50 kept")
        assertTrue(p.faint.none { it.hex == "p15" || it.hex == "off" || it.hex == "none" })
        // The kept ones are the nearest the centre: the farthest kept is no farther than the nearest left out.
        val keptHex = p.faint.map { it.hex }.toSet()
        fun dist(a: AircraftNow) = AircraftGeo.distanceM(centre.first, centre.second, a.lat, a.lon)
        val kept = list.filter { it.hex in keptHex }
        val left = list.filter { it.hex !in keptHex && it.hex != "p15" && it.route?.plausible == true }
        assertEquals(9, left.size)
        assertTrue(kept.maxOf { dist(it) } <= left.minOf { dist(it) })
        for (f in p.faint) {
            val a = list.first { it.hex == f.hex }
            val first = f.points.first()
            assertTrue(AircraftGeo.distanceM(first.first, first.second, sea.lat, sea.lon) < 1.0, "starts at the origin")
            val last = f.points.last()
            assertTrue(AircraftGeo.distanceM(last.first, last.second, den.lat, den.lon) < 1.0, "ends at the destination")
            assertTrue(f.points.contains(a.lat to a.lon), "passes through the aircraft")
            assertTrue(f.points.size in RouteLines.MIN_POINTS..RouteLines.MAX_POINTS, "${f.points.size} points")
        }
        assertEquals(1 + RouteLines.CAP, p.drawn, "the selected flight and the faint lines")
    }

    @Test
    fun pointsScaleWithLength() {
        assertEquals(16, RouteLines.points(0.0))
        assertEquals(16, RouteLines.points(100_000.0))
        assertEquals(20, RouteLines.points(1_000_000.0))
        assertEquals(32, RouteLines.points(4_000_000.0))
        assertEquals(32, RouteLines.points(15_000_000.0))
    }

    @Test
    fun theCardNamesTheDottedEstimate() {
        val here = along(sea, den, 420_000.0)
        val a = now("aaa111", here, check(seaDen, here, den))
        val lines = AircraftText.card(a, ContourUnits.FEET, AirportNaming.BOTH, AircraftText.CREDIT, sea)
        assertEquals(AircraftText.ROUTE_HEDGE, lines[1])
        assertEquals("Dotted: estimated path from SEA", lines[2])
        assertTrue(AircraftText.card(a, ContourUnits.FEET, AirportNaming.BOTH, AircraftText.CREDIT).none { it.startsWith("Dotted") })
    }

    @Test
    fun theModeParses() {
        assertEquals(RouteLinesMode.SELECTED, RouteLinesMode.DEFAULT)
        assertEquals(RouteLinesMode.ALL, RouteLinesMode.of(" All "))
        assertNull(RouteLinesMode.of("some"))
    }
}
