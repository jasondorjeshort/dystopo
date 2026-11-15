package io.github.jasondorjeshort.dystopo.routing

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T290: a drive's cost table ([TileRouter.price], [RoutePrice]) sums to the plan's own figures under every preference, in
 * full and through overlay cells; a route's own line priced again is the plan; the other preference's route is never
 * cheaper under this one; a line off the graph's geometry is refused; the plan's JSON does not change.
 */
class RoutePriceTest {
    private val source = GraphSource("test", "fp", "", 14, 0, drive = true)

    private fun found(r: RouteResult): PlannedRoute {
        assertIs<RouteResult.Found>(r, "expected a route, got $r")
        return r.plan
    }

    private fun close(expected: Double, actual: Double, label: String) =
        assertTrue(abs(expected - actual) <= 1e-6 * max(1.0, abs(expected)), "$label: expected $expected, priced $actual")

    /** The plan's own figures against its table: `timeS`, `turnCostS`, `turns`, `cost`, `fuelIndex`, the point count. */
    private fun assertSums(plan: PlannedRoute, price: RoutePrice, label: String) {
        close(plan.timeS, price.timeS, "$label timeS")
        close(plan.cost, price.cost, "$label cost")
        close(plan.fuelIndex!!, price.fuelJ, "$label fuelIndex")
        assertEquals(plan.turns, price.turns, "$label turns")
        if (plan.turnCostS == null) assertTrue(price.joins.none { it.kind == "turn" }, "$label: quick has no turn rows")
        else close(plan.turnCostS!!, price.turnS, "$label turnCostS")
        assertEquals(plan.route.points.size, price.points, "$label points")
        // The rows cover the points in order, end to end.
        assertEquals(0, price.parts.first().firstPoint, label)
        assertEquals(plan.route.points.size - 1, price.parts.last().lastPoint, label)
        price.parts.zipWithNext().forEach { (a, b) -> assertEquals(a.lastPoint, b.firstPoint, "$label: rows are contiguous") }
        assertEquals(plan.metersByClass.values.sum(), price.lengthM, 0.01 * plan.distanceM + 1.0, "$label length")
    }

    /**
     * A small town over rolling ground near 0 N 0 E: a named main street east-west, unnamed minor streets every 0.002
     * degrees (about 220 m) both ways, a named tertiary avenue north-south, a one-way, an unpaved lane and a service road.
     */
    private fun town(): RoutingGraph {
        val minor = WayAttrs(WayClass.MINOR, Surface.PAVED)
        val main = WayAttrs(WayClass.SECONDARY, Surface.PAVED, name = "Main Street")
        val avenue = WayAttrs(WayClass.TERTIARY, Surface.PAVED, name = "Hill Avenue")
        val lines = ArrayList<WayLine>()
        for (i in 0..8) {
            val v = i * 0.002
            lines += WayLine.of(if (i == 4) main else minor, v to 0.0, v to 0.016)
            lines += WayLine.of(if (i == 3) avenue else if (i == 6) minor.copy(flags = WayAttrs.ONEWAY) else minor, 0.0 to v, 0.016 to v)
        }
        lines += WayLine.of(WayAttrs(WayClass.MINOR, Surface.UNPAVED), 0.001 to 0.001, 0.015 to 0.015)
        lines += WayLine.of(WayAttrs(WayClass.SERVICE, Surface.PAVED), 0.005 to 0.003, 0.0055 to 0.0045, 0.007 to 0.005)
        val ground = ElevationSampler { lat, lon -> 100.0 + 40.0 * kotlin.math.sin(lat * 400) * kotlin.math.cos(lon * 300) }
        return RoutingGraphBuilder(ground).build(lines, source)
    }

    private val townDrives = listOf(
        (0.0005 to 0.0003) to (0.0155 to 0.0159),
        (0.0159 to 0.0151) to (0.0011 to 0.0007),
        (0.0083 to 0.0001) to (0.0039 to 0.0157),
        (0.0121 to 0.0097) to (0.0123 to 0.0031),
    )

    @Test
    fun theTableSumsToThePlanUnderEveryPreference() {
        val g = town()
        for ((a, b) in townDrives) for (pref in DrivePreference.entries) {
            val router = TileRouter(WholeGraph(g), driveTurns = pref.turns, metric = pref.metric, keepTrace = true)
            val plan = found(router.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
            val label = "$a -> $b ${pref.key}"
            assertNotNull(plan.trace, label)
            val price = router.price(plan)
            assertEquals(pref, price.preference, label)
            assertSums(plan, price, label)
            if (pref == DrivePreference.QUICK) assertNull(price.turns, label)
        }
        // The town's drives turn, some of them at junctions of two unnamed streets.
        val router = TileRouter(WholeGraph(g), keepTrace = true)
        val price = router.price(found(router.route(0.0005, 0.0003, 0.0155, 0.0159, TravelMode.DRIVE)))
        assertTrue(price.turns!! >= 1 && price.turnS > 0, "${price.totalsJson()}")
        val json = price.json()
        @Suppress("UNCHECKED_CAST") val turns = json["turns"] as List<Map<String, Any?>>
        assertTrue(turns.any { it["kind"] == "turn" && it["seconds"] as Double > 0 }, "$turns")
        assertTrue(price.paidRows.all { it.turn != null && abs(it.turn!!.angleDeg) >= DriveTurns.FREE_DEG || it.turn!!.reversal }, "a paid turn turns")
        val totals = price.totalsJson()
        assertEquals(price.paidRows.count { it.unnamed }, totals["unnamedTurns"])
    }

    @Test
    fun theFixturesOfTheDriveTestsSumToThePlan() {
        // DriveRoutingTest's hill and ring: least time over the hill, least fuel round on the flat; a destination road at an end.
        val minor = WayAttrs(WayClass.MINOR, Surface.PAVED)
        val secondary = WayAttrs(WayClass.SECONDARY, Surface.PAVED)
        val bLon = 0.07194
        val mid = bLon / 2
        val ground = ElevationSampler { lat, lon ->
            val d = io.github.jasondorjeshort.dystopo.gpx.TrackStats.distanceM(lat, lon, 0.0, mid)
            if (d >= 2500.0) 0.0 else 300.0 * (1 + kotlin.math.cos(kotlin.math.PI * d / 2500.0)) / 2
        }
        val g = RoutingGraphBuilder(ground).build(listOf(
            WayLine.of(minor.copy(flags = WayAttrs.MOTOR_DESTINATION), 0.0 to -0.005, 0.0 to 0.0),
            WayLine.of(secondary, 0.0 to 0.0, 0.0 to mid), WayLine.of(secondary, 0.0 to mid, 0.0 to bLon),
            WayLine.of(secondary, 0.0 to 0.0, -0.0270 to mid, 0.0 to bLon),
            WayLine.of(minor, 0.0 to bLon, 0.0 to bLon + 0.005), WayLine.of(minor, 0.0 to bLon, 0.004 to bLon),
        ), source)
        val a = 0.0 to -0.0025
        val b = 0.0 to bLon + 0.0025
        val plans = DrivePreference.entries.associateWith { pref ->
            val router = TileRouter(WholeGraph(g), driveTurns = pref.turns, metric = pref.metric, keepTrace = true)
            router to found(router.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
        }
        for ((pref, rp) in plans) assertSums(rp.second, rp.first.price(rp.second), pref.key)
        assertTrue(plans.getValue(DrivePreference.FUEL).second.distanceM > plans.getValue(DrivePreference.TIME).second.distanceM + 1000, "the fuel route goes round")
    }

    @Test
    fun theOtherPreferencesRouteIsNeverCheaperUnderThisOne() {
        val g = town()
        for ((a, b) in townDrives) {
            val plans = DrivePreference.entries.associateWith { pref ->
                val router = TileRouter(WholeGraph(g), driveTurns = pref.turns, metric = pref.metric, keepTrace = true)
                router to found(router.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
            }
            for ((own, rp) in plans) for ((other, op) in plans) {
                if (own == other) continue
                val mine = rp.first.price(rp.second)
                val theirs = op.first.price(op.second, own)
                assertEquals(own, theirs.preference)
                assertTrue(theirs.cost >= mine.cost * (1 - 1e-9), "$a -> $b: the ${other.key} route under ${own.key} costs ${theirs.cost}, the ${own.key} route ${mine.cost}")
                // Priced under its own preference again from the other side, a route is its own plan.
                assertSums(op.second, op.first.price(op.second, other), "$a -> $b ${other.key} again")
            }
        }
    }

    @Test
    fun aRoutesOwnLinePricedAgainIsItsPlan() {
        val g = town()
        val tg = TiledGraph(listOf(GraphTileFile.open(GraphTileFile.encode(hillyWideGrid()))))
        val cases = townDrives.map { WholeGraph(g) as GraphPieces to it } +
            // Across graph tiles: the line passes tile cuts, where the next edge is a twin's.
            listOf((tg as GraphPieces) to ((0.2 to 0.13) to (0.8 to 0.97)), (tg as GraphPieces) to ((1.0 to 0.85) to (0.2 to 0.01)))
        for ((pieces, ends) in cases) for (pref in DrivePreference.entries) {
            val (a, b) = ends
            val router = TileRouter(pieces, driveTurns = pref.turns, metric = pref.metric, keepTrace = true)
            val plan = found(router.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
            val label = "$a -> $b ${pref.key}"
            val line = router.priceLine(plan.route.points.map { it.lat to it.lon }, pref)
            assertIs<LinePrice.Priced>(line, "$label: ${(line as? LinePrice.Refused)?.message}")
            assertSums(plan, line.price, "$label from its line")
            val own = router.price(plan)
            assertEquals(own.parts.size, line.price.parts.size, label)
        }
    }

    @Test
    fun aLineOffTheGraphsGeometryIsRefused() {
        val g = town()
        val router = TileRouter(WholeGraph(g), keepTrace = true)
        val plan = found(router.route(0.0005, 0.0003, 0.0155, 0.0159, TravelMode.DRIVE))
        val pts = plan.route.points.map { it.lat to it.lon }
        // A point moved 30 m off the road, half way: refused there, by its index.
        val k = pts.size / 2
        val moved = pts.toMutableList().also { it[k] = it[k].first + 0.00027 to it[k].second }
        val r = router.priceLine(moved)
        assertIs<LinePrice.Refused>(r)
        assertEquals(k, r.point, r.message)
        assertTrue(r.message.contains("point $k") && r.message.contains("map matching"), r.message)
        // A free-hand line between two points on roads is not on the geometry either.
        assertIs<LinePrice.Refused>(router.priceLine(listOf(0.0005 to 0.0003, 0.0155 to 0.0159)))
        // Its first point off any road: refused at point 0.
        val off = router.priceLine(listOf(0.0011 to 0.0013) + pts)
        assertIs<LinePrice.Refused>(off)
        assertEquals(0, off.point)
        // A line driven against the one-way is refused where it turns into it.
        val oneWay = found(TileRouter(WholeGraph(g), keepTrace = true).route(0.0031, 0.0120, 0.0129, 0.0120, TravelMode.DRIVE))
        assertIs<LinePrice.Priced>(router.priceLine(oneWay.route.points.map { it.lat to it.lon }))
        assertIs<LinePrice.Refused>(router.priceLine(oneWay.route.points.reversed().map { it.lat to it.lon }))
    }

    @Test
    fun thePlansJsonIsTheSameWithoutAndWithTheTraceAndAPlanThatDoesNotAskKeepsNone() {
        val g = town()
        for ((a, b) in townDrives) for (pref in DrivePreference.entries) {
            val plain = found(TileRouter(WholeGraph(g), driveTurns = pref.turns, metric = pref.metric).route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
            val kept = found(TileRouter(WholeGraph(g), driveTurns = pref.turns, metric = pref.metric, keepTrace = true).route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
            assertNull(plain.trace)
            assertNotNull(kept.trace)
            for (profile in listOf(true, false)) {
                assertEquals(
                    io.github.jasondorjeshort.dystopo.pmtiles.MiniJson.encode(plain.json(profile)),
                    io.github.jasondorjeshort.dystopo.pmtiles.MiniJson.encode(kept.json(profile)).replace(kept.route.id, plain.route.id),
                    "$a -> $b ${pref.key}",
                )
            }
            assertEquals("planned without keeping its steps (only a debug build keeps them)", RoutePrice.unavailableReason(plain))
            assertNull(RoutePrice.unavailableReason(kept))
            assertFailsWith<IllegalArgumentException> { TileRouter(WholeGraph(g)).price(plain) }
        }
        val hike = found(TileRouter(WholeGraph(g), keepTrace = true).route(0.0005, 0.0003, 0.0155, 0.0159, TravelMode.HIKE))
        assertNull(hike.trace, "only a drive keeps a trace")
        assertEquals("not a drive: only a drive is priced", RoutePrice.unavailableReason(hike))
        val restored = hike.copy(mode = TravelMode.DRIVE, memory = mapOf("restored" to true))
        assertTrue(RoutePrice.unavailableReason(restored)!!.startsWith("restored from the session file"))
        assertTrue(RoutePrice.unavailableReason(hike.copy(mode = TravelMode.DRIVE, memory = mapOf("remote" to "http://server")))!!.startsWith("planned by a server"))
    }

    /** DriveOverlayTest's wide grid of secondary roads over rolling ground, every third north-south road an unpaved minor one. */
    private fun hillyWideGrid(): RoutingGraph {
        val road = WayAttrs(WayClass.SECONDARY, Surface.PAVED, name = "Grid Highway")
        val minor = WayAttrs(WayClass.MINOR, Surface.UNPAVED)
        val lines = ArrayList<WayLine>()
        var v = 0.0
        var i = 0
        while (v <= 4.4 + 1e-9) { lines += WayLine.of(if (i++ % 3 == 1) minor else road, 0.0 to v, 1.6 to v); v += 0.2 }
        v = 0.0
        while (v <= 1.6 + 1e-9) { lines += WayLine.of(road, v to 0.0, v to 4.4); v += 0.2 }
        val ground = ElevationSampler { lat, lon -> 500.0 + 300.0 * kotlin.math.sin(lat * 7) * kotlin.math.cos(lon * 5) }
        return RoutingGraphBuilder(ground).build(lines, source)
    }

    @Test
    fun throughOverlayCellsAShortcutIsOneSummedRowAndTheSumsStillHold() {
        val archive = GraphTileFile.open(GraphTileFile.encode(hillyWideGrid()))
        val tg = TiledGraph(listOf(archive))
        val from = 0.2 to 0.1
        val to = 1.4 to 4.3
        for (pref in DrivePreference.entries) {
            val router = TileRouter(tg, DriveOverlay(), driveTurns = pref.turns, metric = pref.metric, keepTrace = true)
            val plan = found(router.route(from.first, from.second, to.first, to.second, TravelMode.DRIVE))
            assertTrue(plan.memory["overlayTiles"] as Int > 0, "${pref.key} used the overlay: ${plan.memory}")
            val price = router.price(plan)
            assertSums(plan, price, "${pref.key} through the cells")
            val shortcuts = price.parts.filter { it.kind == "shortcut" }
            assertTrue(shortcuts.isNotEmpty(), "${pref.key}: a shortcut row")
            assertTrue(price.joins.any { it.kind == "border" }, "${pref.key}: a border row")
            assertTrue(price.joins.filter { it.kind == "border" }.all { it.seconds == 0.0 && it.cost == 0.0 }, "a border crossing is unpriced")
            if (pref.turns) assertTrue(shortcuts.all { it.turns != null }, pref.key) else assertTrue(shortcuts.all { it.turns == null }, pref.key)
            // Under another preference the same route is priced on its base edges in full: no shortcut, no border.
            val other = if (pref == DrivePreference.FUEL) DrivePreference.TIME else DrivePreference.FUEL
            val full = router.price(plan, other)
            assertTrue(full.parts.none { it.kind == "shortcut" } && full.joins.none { it.kind == "border" }, pref.key)
            assertEquals(plan.route.points.size, full.points, pref.key)
        }
    }
}
