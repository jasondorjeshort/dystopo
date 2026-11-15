package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.mvt.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T73: drive mode on synthetic road networks (about 111 m per 0.001 degree near the equator): one-ways,
 * roads closed to cars, destination-only roads, speed limits, trunks and motorways, packs without the
 * road data; and hike and bike unchanged by any of it. T281: every drive here runs on both the drive search (at zero
 * turn cost) and the node search, which must agree ([BothSearches]).
 */
class DriveRoutingTest {
    private val drive = GraphSource("test", "fp", "", 14, 0, drive = true)
    private val minor = WayAttrs(WayClass.MINOR, Surface.PAVED)
    private val oneway = minor.copy(flags = WayAttrs.ONEWAY)
    private val path = WayAttrs(WayClass.PATH)

    private fun graph(vararg lines: WayLine, source: GraphSource = drive) = RoutingGraphBuilder().build(lines.toList(), source)

    private fun found(r: RouteResult): PlannedRoute {
        assertIs<RouteResult.Found>(r, "expected a route, got $r")
        return r.plan
    }

    private fun failed(r: RouteResult): RouteResult.Failed {
        assertIs<RouteResult.Failed>(r, "expected a failure, got $r")
        return r
    }

    @Test
    fun oneWayLoopIsDrivenOnlyItsWay() {
        // A square block of one-way streets, clockwise on the map: A(0,0) -> B(0,0.002) -> C(0.002,0.002) -> D(0.002,0) -> A.
        // The last side is drawn the other way and tagged oneway=-1, which must mean the same thing.
        val g = graph(
            WayLine.of(oneway, 0.0 to 0.0, 0.0 to 0.002),
            WayLine.of(oneway, 0.0 to 0.002, 0.002 to 0.002),
            WayLine.of(oneway, 0.002 to 0.002, 0.002 to 0.0),
            WayLine.of(minor.copy(flags = WayAttrs.ONEWAY_REVERSE), 0.0 to 0.0, 0.002 to 0.0),
        )
        // With the flow: from the middle of A-B to the middle of B-C, about 222 m.
        val withFlow = found(bothSearches(g).route(0.0, 0.001, 0.001, 0.002, TravelMode.DRIVE))
        assertEquals(222.6, withFlow.distanceM, 2.0)
        // Against it: from the middle of B-C back to the middle of A-B goes all the way round, about 668 m.
        val against = found(bothSearches(g).route(0.001, 0.002, 0.0, 0.001, TravelMode.DRIVE))
        assertEquals(667.9, against.distanceM, 3.0)
        assertTrue(against.route.points.any { it.lat > 0.0019 && it.lon < 0.0001 }, "passes D")
        // Backwards along one side: 0.0015 -> 0.0005 on A-B is three sides plus the two bits, about 779 m.
        val back = found(bothSearches(g).route(0.0, 0.0015, 0.0, 0.0005, TravelMode.DRIVE))
        assertEquals(779.2, back.distanceM, 3.0)
        // Hiking and cycling ignore one-ways.
        assertEquals(222.6, found(bothSearches(g).route(0.001, 0.002, 0.0, 0.001, TravelMode.HIKE)).distanceM, 2.0)
        assertEquals(111.3, found(bothSearches(g).route(0.0, 0.0015, 0.0, 0.0005, TravelMode.BIKE)).distanceM, 1.0)
    }

    @Test
    fun oneWayStreetWithNoWayRoundIsNotConnected() {
        val g = graph(WayLine.of(oneway, 0.0 to 0.0, 0.0 to 0.002))
        found(bothSearches(g).route(0.0, 0.0002, 0.0, 0.0018, TravelMode.DRIVE))
        val r = failed(bothSearches(g).route(0.0, 0.0018, 0.0, 0.0002, TravelMode.DRIVE))
        assertEquals(RouteFailure.NOT_CONNECTED, r.reason)
        assertTrue(r.message.contains("one-way"), r.message)
    }

    @Test
    fun privateRoadsAreClosedToCarsButNotToWalkers() {
        // A straight private road (1 km) or a public road around (1.2 km).
        val private = minor.copy(flags = WayAttrs.MOTOR_NO)
        val g = graph(
            WayLine.of(private, 0.0 to 0.0, 0.0 to 0.009),
            WayLine.of(minor, 0.0 to 0.0, 0.002 to 0.0015, 0.002 to 0.0075, 0.0 to 0.009),
        )
        val car = found(bothSearches(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.DRIVE))
        assertTrue(car.distanceM > 1200, "drove around: ${car.distanceM}")
        val walker = found(bothSearches(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.HIKE))
        assertEquals(1000.8, walker.distanceM, 2.0)
        // A point only on the private road does not snap for a car: the nearest road open to cars is named.
        val gate = graph(WayLine.of(private, 0.0 to 0.0, 0.0 to 0.009), WayLine.of(minor, 0.005 to 0.0, 0.005 to 0.009))
        val r = failed(bothSearches(gate).route(0.0, 0.001, 0.005, 0.005, TravelMode.DRIVE, maxSnapM = 300.0))
        assertEquals(RouteFailure.START_OFF_NETWORK, r.reason)
        assertTrue(r.message.contains("no road open to cars") && r.message.contains("556 m"), r.message)
    }

    @Test
    fun destinationRoadsOnlyAtTheEnds() {
        // A destination-only shortcut (1 km) between two public roads, and a public road around (1.2 km).
        val dest = minor.copy(flags = WayAttrs.MOTOR_DESTINATION)
        val lines = arrayOf(
            WayLine.of(dest, 0.0 to 0.0, 0.0 to 0.009),
            WayLine.of(minor, 0.0 to 0.0, 0.002 to 0.0015, 0.002 to 0.0075, 0.0 to 0.009),
            WayLine.of(minor, -0.003 to 0.0, 0.0 to 0.0), // public approach from the south-west
            WayLine.of(minor, 0.0 to 0.009, -0.003 to 0.009), // public exit to the south-east
        )
        val g = graph(*lines)
        // Through traffic goes round.
        val through = found(bothSearches(g).route(-0.003, 0.0, -0.003, 0.009, TravelMode.DRIVE))
        assertTrue(through.distanceM > 1850, "went round: ${through.distanceM}")
        // To a house on the destination road: allowed, as the last leg.
        val toHouse = found(bothSearches(g).route(-0.003, 0.0, 0.0, 0.004, TravelMode.DRIVE))
        assertEquals(333.9 + 445.2, toHouse.distanceM, 3.0)
        // From it: allowed as the first leg.
        val fromHouse = found(bothSearches(g).route(0.0, 0.005, -0.003, 0.009, TravelMode.DRIVE))
        assertEquals(445.2 + 333.9, fromHouse.distanceM, 3.0)
    }

    @Test
    fun driveRefusesTrailsAndTakesTheRoad() {
        // From a trailhead to another: a trail straight across (1 km) and a road around (1.2 km).
        val g = graph(
            WayLine.of(path, 0.0 to 0.0, 0.0 to 0.009),
            WayLine.of(minor, 0.0 to 0.0, 0.002 to 0.0015, 0.002 to 0.0075, 0.0 to 0.009),
        )
        val car = found(bothSearches(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.DRIVE))
        assertEquals(setOf(WayClass.MINOR), car.metersByClass.keys)
        assertTrue(car.route.description!!.contains(Router.DRIVE_NOTICE), car.route.description) // ADR 0032: the one sentence
        // 1.2 km at 40 km/h.
        assertEquals(car.distanceM / (40 / 3.6), car.timeS, 2.0)
        val hike = found(bothSearches(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.HIKE))
        assertEquals(setOf(WayClass.PATH), hike.metersByClass.keys)
        // Only a trail: a car does not get there.
        val trailOnly = graph(WayLine.of(path, 0.0 to 0.0, 0.0 to 0.009), WayLine.of(minor, 0.0 to 0.0, -0.002 to 0.0))
        val r = failed(bothSearches(trailOnly).route(-0.002, 0.0, 0.0, 0.009, TravelMode.DRIVE))
        assertEquals(RouteFailure.END_OFF_NETWORK, r.reason)
    }

    @Test
    fun speedLimitsAndHighways() {
        // A slow minor road straight across (1 km at 40 km/h: 90 s) or a trunk around (1.2 km at 90 km/h: 49 s).
        val trunk = WayAttrs(WayClass.TRUNK, Surface.PAVED, maxspeedKmh = 90)
        val g = graph(
            WayLine.of(minor, 0.0 to 0.0, 0.0 to 0.009),
            WayLine.of(trunk, 0.0 to 0.0, 0.002 to 0.0015, 0.002 to 0.0075, 0.0 to 0.009),
        )
        val car = found(bothSearches(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.DRIVE))
        assertEquals(setOf(WayClass.TRUNK), car.metersByClass.keys)
        assertEquals(car.distanceM / 25.0, car.timeS, 1.0)
        // Hikers and cyclists never use the trunk, even when it is all there is.
        assertEquals(setOf(WayClass.MINOR), found(bothSearches(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.HIKE)).metersByClass.keys)
        assertEquals(setOf(WayClass.MINOR), found(bothSearches(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.BIKE)).metersByClass.keys)
        val highwayOnly = graph(WayLine.of(trunk, 0.0 to 0.0, 0.0 to 0.009))
        assertEquals(RouteFailure.NOT_CONNECTED, failed(bothSearches(highwayOnly).route(0.0, 0.0, 0.0, 0.009, TravelMode.HIKE)).reason)
        // A 20 km/h limit on the minor road makes it slower still; untagged it is the class speed.
        val m = CostModel(TravelMode.DRIVE)
        assertEquals(40.0, m.speedKmh(g, (0 until g.edgeCount).first { g.wayClass(it) == WayClass.MINOR }))
        assertEquals(90.0, m.speedKmh(g, (0 until g.edgeCount).first { g.wayClass(it) == WayClass.TRUNK }))
    }

    @Test
    fun trailCrossingAHighwayDoesNotJoinIt() {
        // A path crossing a trunk without a shared vertex (an underpass the data did not mark).
        val trunk = WayAttrs(WayClass.TRUNK, Surface.PAVED)
        val g = graph(WayLine.of(path, 0.0 to 0.0, 0.0 to 0.002), WayLine.of(trunk, -0.001 to 0.001, 0.001 to 0.001))
        assertEquals(2, g.edgeCount, "neither line is split")
        // A minor road crossing it does join.
        val r = graph(WayLine.of(minor, 0.0 to 0.0, 0.0 to 0.002), WayLine.of(trunk, -0.001 to 0.001, 0.001 to 0.001))
        assertEquals(4, r.edgeCount)
    }

    @Test
    fun packsBuiltBeforeTheRoadDataRefuseDrive() {
        val old = graph(WayLine.of(minor, 0.0 to 0.0, 0.0 to 0.009), source = GraphSource("rainier-outdoors.pmtiles", "fp", "", 14, 0))
        val r = failed(bothSearches(old).route(0.0, 0.0, 0.0, 0.009, TravelMode.DRIVE))
        assertEquals(RouteFailure.DRIVE_UNSUPPORTED, r.reason)
        assertTrue(r.message.contains("rainier-outdoors.pmtiles was built before it"), r.message)
        assertEquals("drive_unsupported", r.json()["error"])
        // Hiking on the same pack is unchanged.
        found(bothSearches(old).route(0.0, 0.0, 0.0, 0.009, TravelMode.HIKE))
        // What decides: the pack's metadata.
        assertTrue(RoutingGraphBuilder.driveCapable(mapOf("dystopo:profile" to "outdoors", "dystopo:profile_version" to "2")))
        assertFalse(RoutingGraphBuilder.driveCapable(mapOf("dystopo:profile" to "outdoors", "version" to "1")))
        assertFalse(RoutingGraphBuilder.driveCapable(mapOf("dystopo:profile" to "outdoors")))
        assertTrue(RoutingGraphBuilder.driveCapable(mapOf("dystopo:profile" to "openmaptiles")))
        assertFalse(RoutingGraphBuilder.driveCapable(emptyMap()))
    }

    @Test
    fun graphFileKeepsTheDriveData() {
        val g = graph(
            WayLine.of(oneway.copy(maxspeedKmh = 56), 46.79 to -121.75, 46.791 to -121.71),
            WayLine.of(minor.copy(flags = WayAttrs.MOTOR_DESTINATION), 46.79 to -121.75, 46.78 to -121.75),
        )
        val bytes = GraphTileFile.encode(g, 15)
        val a = GraphTileFile.open(bytes)
        assertTrue(a.source.drive)
        assertEquals(true, a.json()["drive"])
        val whole = a.whole()
        assertTrue((0 until whole.edgeCount).all { e -> whole.wayClass(e) != WayClass.MINOR || whole.flags(e) != 0 })
        assertTrue((0 until whole.edgeCount).filter { whole.flags(it) == WayAttrs.ONEWAY }.all { whole.speedKmh(it) == 56 })
        // Every cut piece of the one-way still runs east.
        for (e in 0 until whole.edgeCount) if (whole.flags(e) == WayAttrs.ONEWAY) assertTrue(whole.nodeLon[whole.edgeFrom[e]] < whole.nodeLon[whole.edgeTo[e]])
        val p = found(bothSearches(TiledGraph(listOf(a))).route(46.79, -121.749, 46.791, -121.711, TravelMode.DRIVE))
        assertEquals(p.distanceM / (56 / 3.6), p.timeS, 2.0)
        assertIs<RouteResult.Failed>(bothSearches(TiledGraph(listOf(a))).route(46.791, -121.711, 46.79, -121.749, TravelMode.DRIVE))
        // A version 3 file (before T73) is refused, saying why.
        val v3 = bytes.copyOf().also { it[4] = 3 }
        val e = kotlin.runCatching { GraphTileFile.open(v3) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && e.message!!.contains("before T73"), "$e")
    }

    @Test
    fun aLineDrawnTwiceInOppositeDirectionsIsTwoWay() {
        val g = graph(
            WayLine.of(oneway, 0.0 to 0.0, 0.0 to 0.002),
            WayLine.of(oneway, 0.0 to 0.002, 0.0 to 0.0),
        )
        assertEquals(1, g.edgeCount)
        assertEquals(0, g.flags(0))
        assertEquals(WayAttrs.ONEWAY, RoutingGraphBuilder.mergeDriveFlags(WayAttrs.ONEWAY, WayAttrs.ONEWAY_REVERSE, sameDirection = false))
        assertEquals(WayAttrs.MOTOR_DESTINATION, RoutingGraphBuilder.mergeDriveFlags(WayAttrs.MOTOR_NO, WayAttrs.MOTOR_DESTINATION, sameDirection = true))
        assertEquals(WayAttrs.PRIVATE, RoutingGraphBuilder.mergeDriveFlags(WayAttrs.PRIVATE or WayAttrs.MOTOR_NO, 0, sameDirection = true))
    }

    @Test
    fun tileAttributesAreRead() {
        fun s(vararg kv: Pair<String, Value>) = kv.toMap()
        val str = { v: String -> Value.StringValue(v) }
        val a = assertNotNull(WayAttrs.of(s("class" to str("tertiary"), "oneway" to Value.IntValue(1), "maxspeed" to Value.IntValue(56))))
        assertEquals(WayAttrs.ONEWAY, a.flags)
        assertEquals(56, a.maxspeedKmh)
        assertEquals(WayAttrs.ONEWAY_REVERSE, WayAttrs.of(s("class" to str("minor"), "oneway" to Value.SintValue(-1)))!!.flags)
        assertEquals(WayAttrs.MOTOR_NO, WayAttrs.of(s("class" to str("service"), "motor_access" to str("private")))!!.flags)
        assertEquals(WayAttrs.MOTOR_DESTINATION, WayAttrs.of(s("class" to str("track"), "motor_access" to str("destination")))!!.flags)
        assertEquals(WayClass.MOTORWAY, WayAttrs.of(s("class" to str("motorway")))!!.cls)
        // OpenMapTiles: access=no closes a road to cars; in the outdoors profile it is about walking.
        assertEquals(WayAttrs.PRIVATE or WayAttrs.MOTOR_NO, WayAttrs.of(s("class" to str("minor"), "access" to str("no")), openMapTiles = true)!!.flags)
        assertEquals(WayAttrs.PRIVATE, WayAttrs.of(s("class" to str("track"), "access" to str("no")))!!.flags)
    }

    @Test
    fun hikeAndBikeAreUnchangedByTheDriveData() {
        // The Rainier fixture with drive-only data added: one-way and speed flags on its road, a private
        // motor flag on the trailhead spur, and a trunk crossing the Skyline Trail. Hike and bike routes
        // are the same to the decimetre, with the same cost; the numbers are the fixture's from before T73.
        val base = RainierRoutingTest().graph()
        val lines = RainierRoutingTest().lines().map { l ->
            if (l.attrs.cls == WayClass.SECONDARY) WayLine(l.lons, l.lats, l.attrs.copy(flags = WayAttrs.ONEWAY, maxspeedKmh = 56))
            else if (l.attrs.cls == WayClass.FOOTWAY) WayLine(l.lons, l.lats, l.attrs.copy(flags = l.attrs.flags or WayAttrs.MOTOR_NO))
            else l
        } + WayLine.of(WayAttrs(WayClass.TRUNK, Surface.PAVED), 46.7950 to -121.7420, 46.7950 to -121.7300)
        val withDrive = RoutingGraphBuilder(RainierRoutingTest().cone).build(lines, base.source.copy(drive = true))
        for (mode in listOf(TravelMode.HIKE, TravelMode.BIKE)) {
            val a = found(bothSearches(base).route(46.7864, -121.7366, 46.8024, -121.7289, mode))
            val b = found(bothSearches(withDrive).route(46.7864, -121.7366, 46.8024, -121.7289, mode))
            assertEquals(a.distanceM, b.distanceM, 0.05, "$mode distance")
            assertEquals(a.ascentM!!, b.ascentM!!, 0.05, "$mode ascent")
            assertEquals(a.cost, b.cost, 0.05, "$mode cost")
            assertEquals(a.route.points.size, b.route.points.size, "$mode points")
            assertEquals(a.timeS, b.timeS, 0.5, "$mode time")
            val golden = GOLDEN.getValue(mode)
            assertEquals(golden[0], a.distanceM, 0.05, "$mode golden distance")
            assertEquals(golden[1], a.ascentM!!, 0.05, "$mode golden ascent")
            assertEquals(golden[2], a.cost, 0.05, "$mode golden cost")
        }
    }

    private companion object {
        /** Paradise to Panorama Point on the fixture: distance, ascent, cost (measured on main before T73). */
        val GOLDEN = mapOf(
            TravelMode.HIKE to doubleArrayOf(2761.2075, 453.7901, 6391.4590),
            TravelMode.BIKE to doubleArrayOf(2761.2075, 453.7901, 14281.0284),
        )
    }

    // ---- T284: least fuel (ADR 0063) ----

    private val secondary = WayAttrs(WayClass.SECONDARY, Surface.PAVED)

    /** A router planning for least fuel ([DriveMetric.FUEL]) with [turns]. */
    private fun fuelRouter(g: RoutingGraph, turns: TurnCost = TurnCost()) = TileRouter(WholeGraph(g), turns = turns, metric = DriveMetric.FUEL)

    @Test
    fun leastTimeTakesTheHillAndLeastFuelTheFlatRoad() {
        // From A (0, 0) to B 8 km east: straight over a 300 m hill (split at its summit), or 10 km round on the flat, both
        // secondary roads at 65 km/h, with a short street at each end. Least time takes the 8 km; least fuel the 10 km:
        // 300 m of climb is about 10 km of flat driving at the best speed.
        val bLon = 0.07194
        val mid = bLon / 2
        val ground = ElevationSampler { lat, lon ->
            val d = io.github.jasondorjeshort.dystopo.gpx.TrackStats.distanceM(lat, lon, 0.0, mid)
            if (d >= 2500.0) 0.0 else 300.0 * (1 + kotlin.math.cos(kotlin.math.PI * d / 2500.0)) / 2
        }
        val g = RoutingGraphBuilder(ground).build(listOf(
            WayLine.of(minor, 0.0 to -0.005, 0.0 to 0.0),
            WayLine.of(secondary, 0.0 to 0.0, 0.0 to mid), WayLine.of(secondary, 0.0 to mid, 0.0 to bLon),
            WayLine.of(secondary, 0.0 to 0.0, -0.0270 to mid, 0.0 to bLon),
            WayLine.of(minor, 0.0 to bLon, 0.0 to bLon + 0.005),
        ), drive)
        val a = 0.0 to -0.0025
        val b = 0.0 to bLon + 0.0025
        val time = found(TileRouter(WholeGraph(g)).route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
        val fuel = found(fuelRouter(g).route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
        assertEquals(DriveMetric.TIME, time.metric)
        assertEquals(DriveMetric.FUEL, fuel.metric)
        assertEquals(8556.0, time.distanceM, 30.0, "over the hill")
        assertTrue(time.ascentM!! > 290.0, "${time.ascentM}")
        assertEquals(10556.0, fuel.distanceM, 60.0, "round on the flat")
        assertTrue(fuel.ascentM!! < 10.0, "${fuel.ascentM}")
        assertTrue(fuel.timeS > time.timeS && fuel.fuelIndex!! < time.fuelIndex!! * 0.8, "fuel ${fuel.timeS} s ${fuel.fuelIndex} J, time ${time.timeS} s ${time.fuelIndex} J")
        assertEquals(fuel.fuelIndex!! / FuelModel.DEFAULT.divisorJPerM, fuel.cost, 1e-6 * fuel.cost, "a fuel cost is joules over the divisor")
        // T284: a least-fuel route's time is the sum of its edges' times and its turn seconds.
        val edgesS = fuel.metersByClass.entries.sumOf { (c, m) -> m / (CostModel.driveSpeedKmh(c) / 3.6) }
        assertEquals(edgesS + fuel.turnCostS!!, fuel.timeS, 0.01)
        assertEquals(time.timeS, found(TileRouter(WholeGraph(g)).route(a.first, a.second, b.first, b.second, TravelMode.DRIVE)).timeS)
        // Its JSON names the metric beside the cost and carries fuelIndex; a hike carries neither.
        val json = fuel.json(profileSamples = false)
        assertEquals("fuel", json["metric"])
        assertEquals(listOf("cost", "metric"), json.keys.toList().let { k -> k.subList(k.indexOf("cost"), k.indexOf("cost") + 2) })
        assertEquals(kotlin.math.round(fuel.fuelIndex!!).toLong(), json["fuelIndex"])
        val hike = found(TileRouter(WholeGraph(g)).route(a.first, a.second, b.first, b.second, TravelMode.HIKE)).json(profileSamples = false)
        assertTrue("metric" !in hike && "fuelIndex" !in hike, "$hike")
    }

    @Test
    fun aToBDiffersFromBToAOnASlope() {
        // A (0, 0) to B 5 km east over a 300 m ridge: steep from A (1 km up), gentle to B (4 km down), split at the top.
        // A flat road round is 13.2 km. Up the steep side the climb is the same both ways, but the descent credit is
        // capped at half the resistance of the descending edge: 4 km of it from A, 1 km from B. So least fuel goes over
        // the ridge from A to B and round on the flat from B to A; least time takes the ridge both ways. No turns priced.
        val top = 0.009
        val bLon = 0.045
        val ground = ElevationSampler { lat, lon ->
            if (kotlin.math.abs(lat) > 1e-5 || lon <= 0.0 || lon >= bLon) 0.0
            else if (lon <= top) 300.0 * lon / top else 300.0 * (bLon - lon) / (bLon - top)
        }
        val g = RoutingGraphBuilder(ground).build(listOf(
            WayLine.of(minor, 0.0 to -0.005, 0.0 to 0.0),
            WayLine.of(secondary, 0.0 to 0.0, 0.0 to top), WayLine.of(secondary, 0.0 to top, 0.0 to bLon),
            WayLine.of(secondary, 0.0 to 0.0, -0.055 to bLon / 2, 0.0 to bLon),
            WayLine.of(minor, 0.0 to bLon, 0.0 to bLon + 0.005),
        ), drive)
        val zero = TurnCost(TurnTable.ZERO)
        val a = 0.0 to -0.0025
        val b = 0.0 to bLon + 0.0025
        fun go(r: Router, from: Pair<Double, Double>, to: Pair<Double, Double>) = found(r.route(from.first, from.second, to.first, to.second, TravelMode.DRIVE))
        val ab = go(fuelRouter(g, zero), a, b)
        val ba = go(fuelRouter(g, zero), b, a)
        assertTrue(ab.distanceM < 6000.0 && ab.ascentM!! > 290.0, "A to B over the ridge: ${ab.distanceM} m, +${ab.ascentM}")
        assertTrue(ba.distanceM > 13000.0 && ba.ascentM!! < 1.0, "B to A round on the flat: ${ba.distanceM} m, +${ba.ascentM}")
        for ((from, to) in listOf(a to b, b to a)) assertTrue(go(TileRouter(WholeGraph(g), turns = zero), from, to).distanceM < 6000.0, "least time takes the ridge")
    }

    /**
     * The reference under fuel: a one-directional Dijkstra over directed-edge states written plainly (as TurnCostTest's),
     * with the fuel model's edge costs ([CostModel.partCost]) and fuel turn costs.
     */
    private fun fuelReference(g: RoutingGraph, turns: TurnCost, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val model = turns.model
        val s = g.nearest(a.first, a.second, Router.DEFAULT_SNAP_M) { model.usable(g, it) } ?: return Double.POSITIVE_INFINITY
        val t = g.nearest(b.first, b.second, Router.DEFAULT_SNAP_M) { model.usable(g, it) } ?: return Double.POSITIVE_INFINITY
        val piece = GraphPiece(0, 0, 0, g, joinZoom = -1)
        fun part(e: Int, f0: Double, f1: Double) = model.partCost(g, e, f0, f1)
        var best = if (s.edge == t.edge) part(s.edge, s.fraction, t.fraction) else Double.POSITIVE_INFINITY
        val dist = DoubleArray(2 * g.edgeCount) { Double.POSITIVE_INFINITY }
        val done = BooleanArray(2 * g.edgeCount)
        val heap = MinHeap()
        fun offer(st: Int, c: Double) { if (c < dist[st]) { dist[st] = c; heap.push(c, st) } }
        offer(2 * s.edge, part(s.edge, s.fraction, 1.0))
        offer(2 * s.edge + 1, part(s.edge, s.fraction, 0.0))
        while (heap.size > 0) {
            val st = heap.pop()
            if (done[st]) continue
            done[st] = true
            val c = dist[st]
            if (c >= best) break
            val e = st shr 1
            val fwd = st and 1 == 0
            val v = if (fwd) g.edgeTo[e] else g.edgeFrom[e]
            val j = Junction(listOf(piece), intArrayOf(v))
            if (v == g.edgeFrom[t.edge]) best = minOf(best, c + turns.cost(piece, e, fwd, j, piece, t.edge, true) + part(t.edge, 0.0, t.fraction))
            if (v == g.edgeTo[t.edge]) best = minOf(best, c + turns.cost(piece, e, fwd, j, piece, t.edge, false) + part(t.edge, 1.0, t.fraction))
            for (i in g.adjStart[v] until g.adjStart[v + 1]) {
                val ad = g.adjEdge[i]
                val e2 = ad shr 1
                val atFrom = ad and 1 == 0
                val w = part(e2, if (atFrom) 0.0 else 1.0, if (atFrom) 1.0 else 0.0)
                if (w == Double.POSITIVE_INFINITY) continue
                offer(2 * e2 + if (atFrom) 0 else 1, c + turns.cost(piece, e, fwd, j, piece, e2, atFrom) + w)
            }
        }
        return best
    }

    @Test
    fun underFuelTheBidirectionalSearchEqualsAPlainEdgeDijkstraOnRandomGraphs() {
        val classes = listOf(WayClass.SERVICE, WayClass.MINOR, WayClass.TERTIARY, WayClass.SECONDARY, WayClass.PRIMARY, WayClass.TRUNK)
        var routes = 0
        var differ = 0
        for (seed in 1..20) {
            val rnd = kotlin.random.Random(500 + seed)
            val n = 6
            val step = 0.002
            val peak = rnd.nextDouble(0.0, n * step) to rnd.nextDouble(0.0, n * step)
            val ground = ElevationSampler { lat, lon -> 400.0 / (1 + ((lat - peak.first) * (lat - peak.first) + (lon - peak.second) * (lon - peak.second)) / (2 * step * step)) }
            fun attrs() = WayAttrs(
                classes[rnd.nextInt(classes.size)], if (rnd.nextInt(5) == 0) Surface.UNPAVED else Surface.PAVED,
                flags = when (rnd.nextInt(8)) { 0 -> WayAttrs.ONEWAY; 1 -> WayAttrs.ONEWAY_REVERSE; else -> 0 },
                maxspeedKmh = if (rnd.nextInt(4) == 0) 20 + 10 * rnd.nextInt(10) else 0,
            ).let { if (rnd.nextInt(6) == 0) it.copy(brunnel = true, tunnel = rnd.nextBoolean()) else it } // T286: some bridges and tunnels
            val pts = Array(n) { i -> Array(n) { j -> (i * step + rnd.nextDouble(-0.25, 0.25) * step) to (j * step + rnd.nextDouble(-0.25, 0.25) * step) } }
            val lines = ArrayList<WayLine>()
            for (i in 0 until n) for (j in 0 until n) {
                if (j + 1 < n && rnd.nextInt(10) < 8) lines += WayLine.of(attrs(), pts[i][j], pts[i][j + 1])
                if (i + 1 < n && rnd.nextInt(10) < 8) lines += WayLine.of(attrs(), pts[i][j], pts[i + 1][j])
            }
            val g = RoutingGraphBuilder(ground).build(lines, drive)
            val turns = TurnCost(TurnTable.DRIVE, DrivingSide.RIGHT, CostModel(TravelMode.DRIVE, metric = DriveMetric.FUEL))
            val r = fuelRouter(g, TurnCost(TurnTable.DRIVE, DrivingSide.RIGHT))
            repeat(12) {
                val a = rnd.nextDouble(0.0, 0.01) to rnd.nextDouble(0.0, 0.01)
                val b = rnd.nextDouble(0.0, 0.01) to rnd.nextDouble(0.0, 0.01)
                val want = fuelReference(g, turns, a, b)
                val got = r.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE)
                if (want == Double.POSITIVE_INFINITY) { assertIs<RouteResult.Failed>(got, "seed $seed $a -> $b"); return@repeat }
                val p = found(got)
                assertEquals(want, p.cost, 1e-6 * maxOf(1.0, want), "seed $seed $a -> $b")
                routes++
                val t = TileRouter(WholeGraph(g), turns = TurnCost(TurnTable.DRIVE, DrivingSide.RIGHT)).route(a.first, a.second, b.first, b.second, TravelMode.DRIVE)
                if (t is RouteResult.Found && kotlin.math.abs(t.plan.distanceM - p.distanceM) > 1.0) differ++
            }
        }
        assertTrue(routes > 150 && differ > 10, "routes $routes, of which $differ differ from least time")
    }

    // ---- T286: a bridge or tunnel on the straight grade between its ends ----

    /** The edge of [g] whose attributes mark a bridge or tunnel of [kind] ([WayAttrs.BRIDGE], [WayAttrs.TUNNEL]). */
    private fun marked(g: RoutingGraph, kind: Int): Int = (0 until g.edgeCount).single { g.brunnel(it) == kind }

    @Test
    fun leastFuelTakesTheTunnelUnderTheHillAndReportsTheEndsRise() {
        // A (0, 0) to B 8 km east. The ground rises 100 m from A to B with a 400 m hill halfway. Three secondary roads:
        // straight over the hill (split at its summit), a tunnel under it 220 m to the south (its ends at A and B), and a
        // flat road round, 10.4 km, beyond the hill. Least time takes the road over (the shortest); least fuel the tunnel,
        // whose climb is the 100 m rise from end to end. Without the mark the tunnel climbs the hill's flank as the road
        // over does and least fuel goes round; least time is the same route at the same cost either way.
        val bLon = 0.072
        val mid = bLon / 2
        val ground = ElevationSampler { lat, lon ->
            val d = io.github.jasondorjeshort.dystopo.gpx.TrackStats.distanceM(lat, lon, 0.0, mid)
            100.0 * (lon / bLon).coerceIn(0.0, 1.0) + if (d >= 2500.0) 0.0 else 400.0 * (1 + kotlin.math.cos(kotlin.math.PI * d / 2500.0)) / 2
        }
        fun graph(mark: Boolean) = RoutingGraphBuilder(ground).build(listOf(
            WayLine.of(minor, 0.0 to -0.005, 0.0 to 0.0),
            WayLine.of(secondary, 0.0 to 0.0, 0.0 to mid), WayLine.of(secondary, 0.0 to mid, 0.0 to bLon),
            WayLine.of(secondary.copy(brunnel = mark, tunnel = mark), 0.0 to 0.0, -0.002 to 0.012, -0.002 to bLon - 0.012, 0.0 to bLon),
            WayLine.of(secondary, 0.0 to 0.0, -0.030 to mid, 0.0 to bLon),
            WayLine.of(minor, 0.0 to bLon, 0.0 to bLon + 0.005),
        ), drive)
        val a = 0.0 to -0.0025
        val b = 0.0 to bLon + 0.0025
        fun go(r: Router) = found(r.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
        val g = graph(true)
        val plain = graph(false)
        val tunnel = marked(g, WayAttrs.TUNNEL)
        val rise = (g.nodeEle[g.edgeTo[tunnel]] - g.nodeEle[g.edgeFrom[tunnel]]).toDouble()
        assertEquals(100.0, kotlin.math.abs(rise), 1.0, "the ends' rise")
        assertTrue(g.edgeAscent[tunnel] + g.edgeDescent[tunnel] > 700.0, "the terrain over the tunnel: +${g.edgeAscent[tunnel]} -${g.edgeDescent[tunnel]}")
        val model = CostModel(TravelMode.DRIVE, metric = DriveMetric.FUEL)
        val up = rise > 0 // from its from node, uphill
        assertEquals(kotlin.math.abs(rise), model.climbM(g, tunnel, if (up) 0.0 else 1.0, if (up) 1.0 else 0.0), 1e-9)
        assertEquals(0.0, model.dropM(g, tunnel, if (up) 0.0 else 1.0, if (up) 1.0 else 0.0))

        val fuel = go(fuelRouter(g))
        assertEquals(8600.0, fuel.distanceM, 60.0, "least fuel through the tunnel (with the streets at each end)")
        assertEquals(100.0, fuel.ascentM!!, 2.0, "the reported ascent is the ends' rise")
        assertTrue(fuel.descentM!! < 2.0, "${fuel.descentM}")
        val time = go(TileRouter(WholeGraph(g)))
        assertTrue(time.distanceM < fuel.distanceM && time.ascentM!! > 450.0, "least time over the hill: ${time.distanceM} m, +${time.ascentM}")
        // Without the mark: least fuel goes round on the flat road, the tunnel's line reads the terrain, least time is unchanged.
        val plainFuel = go(fuelRouter(plain))
        assertTrue(plainFuel.distanceM > 10000.0 && plainFuel.ascentM!! < 110.0, "round: ${plainFuel.distanceM} m, +${plainFuel.ascentM}")
        val plainTunnel = (0 until plain.edgeCount).single { plain.edgeLength[it] == g.edgeLength[tunnel] && plain.edgeFrom[it] == g.edgeFrom[tunnel] }
        assertTrue(model.climbM(plain, plainTunnel, 0.0, 1.0) + model.climbM(plain, plainTunnel, 1.0, 0.0) > 700.0, "the unmarked line climbs the hill")
        val plainTime = go(TileRouter(WholeGraph(plain)))
        assertEquals(plainTime.distanceM, time.distanceM)
        assertEquals(plainTime.timeS, time.timeS)
        assertEquals(plainTime.cost, time.cost)
        // A hike keeps the terrain's figures on the marked edge.
        val hike = CostModel(TravelMode.HIKE)
        assertEquals(g.edgeAscent[tunnel].toDouble(), hike.climbM(g, tunnel, 0.0, 1.0), 1e-6)
        assertEquals(g.edgeDescent[tunnel].toDouble(), hike.dropM(g, tunnel, 0.0, 1.0), 1e-6)
    }

    @Test
    fun aBridgeOverAValleyNeitherDescendsNorClimbs() {
        // One secondary road A to B, 3 km east, across a 200 m deep valley 1.4 km wide: on a bridge between its rims, or
        // the same line unmarked (as the terrain reads it, down into the valley and up again).
        val bLon = 0.027
        val ground = ElevationSampler { _, lon ->
            val c = bLon / 2
            val half = 0.0063
            if (kotlin.math.abs(lon - c) >= half) 500.0 else 500.0 - 200.0 * (1 + kotlin.math.cos(kotlin.math.PI * (lon - c) / half)) / 2
        }
        fun graph(mark: Boolean) = RoutingGraphBuilder(ground).build(listOf(
            WayLine.of(minor, 0.0 to -0.005, 0.0 to 0.0),
            WayLine.of(secondary.copy(brunnel = mark), 0.0 to 0.0, 0.0 to 0.005, 0.0 to 0.010, 0.0 to 0.015, 0.0 to 0.020, 0.0 to bLon),
            WayLine.of(minor, 0.0 to bLon, 0.0 to bLon + 0.005),
        ), drive)
        val a = 0.0 to -0.0025
        val b = 0.0 to bLon + 0.0025
        fun go(r: Router) = found(r.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
        val g = graph(true)
        assertEquals(WayAttrs.BRIDGE, g.brunnel(marked(g, WayAttrs.BRIDGE)))
        val bridge = go(fuelRouter(g))
        val road = go(fuelRouter(graph(false)))
        assertEquals(road.distanceM, bridge.distanceM, 1e-6)
        assertTrue(bridge.ascentM!! < 1.0 && bridge.descentM!! < 1.0, "the bridge: +${bridge.ascentM} -${bridge.descentM}")
        assertTrue(road.ascentM!! > 180.0 && road.descentM!! > 180.0, "unmarked: +${road.ascentM} -${road.descentM}")
        assertTrue(bridge.fuelIndex!! < road.fuelIndex!!, "the bridge ${bridge.fuelIndex} J, through the valley ${road.fuelIndex} J")
        // Least time does not read the climb: the same time and cost; its reported ascent follows the rule too.
        val t1 = go(TileRouter(WholeGraph(g)))
        val t0 = go(TileRouter(WholeGraph(graph(false))))
        assertEquals(t0.timeS, t1.timeS)
        assertEquals(t0.cost, t1.cost)
        assertTrue(t1.ascentM!! < 1.0, "${t1.ascentM}")
    }

    @Test
    fun aMarkedEdgeDrivenBothWaysClimbsOneWayAndDescendsTheOther() {
        // A 4 km tunnel whose west portal is at 100 m and east portal at 300 m, under a 900 m ridge, in three edges (as a
        // pack's tile borders cut a long one; the inner nodes take the terrain's height, over 700 m): east-bound climbs the
        // 200 m rise between the portals and descends nothing, west-bound the reverse; each edge and each part of one takes
        // its length's share of the rise.
        val bLon = 0.036
        val ground = ElevationSampler { _, lon ->
            val ramp = 100.0 + 200.0 * (lon / bLon).coerceIn(0.0, 1.0)
            ramp + 900.0 * kotlin.math.max(0.0, 1 - kotlin.math.abs(lon - bLon / 2) / (bLon / 2.5))
        }
        val g = RoutingGraphBuilder(ground).build(listOf(
            WayLine.of(minor, 0.0 to -0.005, 0.0 to 0.0),
            WayLine.of(secondary.copy(brunnel = true, tunnel = true), 0.0 to 0.0, 0.0 to bLon / 3),
            WayLine.of(secondary.copy(brunnel = true, tunnel = true), 0.0 to bLon / 3, 0.0 to bLon / 2, 0.0 to 2 * bLon / 3),
            WayLine.of(secondary.copy(brunnel = true, tunnel = true), 0.0 to 2 * bLon / 3, 0.0 to bLon),
            WayLine.of(minor, 0.0 to bLon, 0.0 to bLon + 0.005),
        ), drive)
        val a = 0.0 to -0.0025
        val b = 0.0 to bLon + 0.0025
        val east = found(fuelRouter(g).route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
        val west = found(fuelRouter(g).route(b.first, b.second, a.first, a.second, TravelMode.DRIVE))
        assertEquals(200.0, east.ascentM!!, 2.0)
        assertTrue(east.descentM!! < 1.0, "${east.descentM}")
        assertEquals(200.0, west.descentM!!, 2.0)
        assertTrue(west.ascentM!! < 1.0, "${west.ascentM}")
        assertTrue(east.fuelIndex!! > west.fuelIndex!!, "east ${east.fuelIndex} J, west ${west.fuelIndex} J")
        val pieces = (0 until g.edgeCount).filter { g.brunnel(it) == WayAttrs.TUNNEL }
        assertEquals(3, pieces.size)
        assertTrue(pieces.sumOf { (g.edgeAscent[it] + g.edgeDescent[it]).toDouble() } > 1600.0, "the stored terrain over the tunnel")
        assertTrue(pieces.maxOf { maxOf(g.nodeEle[g.edgeFrom[it]], g.nodeEle[g.edgeTo[it]]) } > 700f, "an inner node on the terrain")
        val model = CostModel(TravelMode.DRIVE, metric = DriveMetric.FUEL)
        val total = pieces.sumOf { g.edgeLength[it].toDouble() }
        for (p in pieces) {
            val r = model.straightRise(g, p)
            val east = g.nodeLon[g.edgeTo[p]] > g.nodeLon[g.edgeFrom[p]]
            assertEquals((if (east) 200.0 else -200.0) * g.edgeLength[p] / total, r, 1.0, "edge $p: its share of the portals' rise")
        }
        val e = pieces.maxBy { g.edgeLength[it] }
        val rise = model.straightRise(g, e)
        val up = rise > 0
        assertEquals(kotlin.math.abs(rise) / 2, model.climbM(g, e, if (up) 0.25 else 0.75, if (up) 0.75 else 0.25), 1e-6)
        assertEquals(0.0, model.climbM(g, e, if (up) 0.75 else 0.25, if (up) 0.25 else 0.75))
        assertEquals(kotlin.math.abs(rise) / 2, model.dropM(g, e, if (up) 0.75 else 0.25, if (up) 0.25 else 0.75), 1e-6)
    }
}
