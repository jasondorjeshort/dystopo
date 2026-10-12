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
 * road data; and hike and bike unchanged by any of it.
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
        val withFlow = found(Router(g).route(0.0, 0.001, 0.001, 0.002, TravelMode.DRIVE))
        assertEquals(222.6, withFlow.distanceM, 2.0)
        // Against it: from the middle of B-C back to the middle of A-B goes all the way round, about 668 m.
        val against = found(Router(g).route(0.001, 0.002, 0.0, 0.001, TravelMode.DRIVE))
        assertEquals(667.9, against.distanceM, 3.0)
        assertTrue(against.route.points.any { it.lat > 0.0019 && it.lon < 0.0001 }, "passes D")
        // Backwards along one side: 0.0015 -> 0.0005 on A-B is three sides plus the two bits, about 779 m.
        val back = found(Router(g).route(0.0, 0.0015, 0.0, 0.0005, TravelMode.DRIVE))
        assertEquals(779.2, back.distanceM, 3.0)
        // Hiking and cycling ignore one-ways.
        assertEquals(222.6, found(Router(g).route(0.001, 0.002, 0.0, 0.001, TravelMode.HIKE)).distanceM, 2.0)
        assertEquals(111.3, found(Router(g).route(0.0, 0.0015, 0.0, 0.0005, TravelMode.BIKE)).distanceM, 1.0)
    }

    @Test
    fun oneWayStreetWithNoWayRoundIsNotConnected() {
        val g = graph(WayLine.of(oneway, 0.0 to 0.0, 0.0 to 0.002))
        found(Router(g).route(0.0, 0.0002, 0.0, 0.0018, TravelMode.DRIVE))
        val r = failed(Router(g).route(0.0, 0.0018, 0.0, 0.0002, TravelMode.DRIVE))
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
        val car = found(Router(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.DRIVE))
        assertTrue(car.distanceM > 1200, "drove around: ${car.distanceM}")
        val walker = found(Router(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.HIKE))
        assertEquals(1000.8, walker.distanceM, 2.0)
        // A point only on the private road does not snap for a car: the nearest road open to cars is named.
        val gate = graph(WayLine.of(private, 0.0 to 0.0, 0.0 to 0.009), WayLine.of(minor, 0.005 to 0.0, 0.005 to 0.009))
        val r = failed(Router(gate).route(0.0, 0.001, 0.005, 0.005, TravelMode.DRIVE, maxSnapM = 300.0))
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
        val through = found(Router(g).route(-0.003, 0.0, -0.003, 0.009, TravelMode.DRIVE))
        assertTrue(through.distanceM > 1850, "went round: ${through.distanceM}")
        // To a house on the destination road: allowed, as the last leg.
        val toHouse = found(Router(g).route(-0.003, 0.0, 0.0, 0.004, TravelMode.DRIVE))
        assertEquals(333.9 + 445.2, toHouse.distanceM, 3.0)
        // From it: allowed as the first leg.
        val fromHouse = found(Router(g).route(0.0, 0.005, -0.003, 0.009, TravelMode.DRIVE))
        assertEquals(445.2 + 333.9, fromHouse.distanceM, 3.0)
    }

    @Test
    fun driveRefusesTrailsAndTakesTheRoad() {
        // From a trailhead to another: a trail straight across (1 km) and a road around (1.2 km).
        val g = graph(
            WayLine.of(path, 0.0 to 0.0, 0.0 to 0.009),
            WayLine.of(minor, 0.0 to 0.0, 0.002 to 0.0015, 0.002 to 0.0075, 0.0 to 0.009),
        )
        val car = found(Router(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.DRIVE))
        assertEquals(setOf(WayClass.MINOR), car.metersByClass.keys)
        assertTrue(car.route.description!!.contains("not a road navigator"), car.route.description)
        // 1.2 km at 40 km/h.
        assertEquals(car.distanceM / (40 / 3.6), car.timeS, 2.0)
        val hike = found(Router(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.HIKE))
        assertEquals(setOf(WayClass.PATH), hike.metersByClass.keys)
        // Only a trail: a car does not get there.
        val trailOnly = graph(WayLine.of(path, 0.0 to 0.0, 0.0 to 0.009), WayLine.of(minor, 0.0 to 0.0, -0.002 to 0.0))
        val r = failed(Router(trailOnly).route(-0.002, 0.0, 0.0, 0.009, TravelMode.DRIVE))
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
        val car = found(Router(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.DRIVE))
        assertEquals(setOf(WayClass.TRUNK), car.metersByClass.keys)
        assertEquals(car.distanceM / 25.0, car.timeS, 1.0)
        // Hikers and cyclists never use the trunk, even when it is all there is.
        assertEquals(setOf(WayClass.MINOR), found(Router(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.HIKE)).metersByClass.keys)
        assertEquals(setOf(WayClass.MINOR), found(Router(g).route(0.0, 0.0, 0.0, 0.009, TravelMode.BIKE)).metersByClass.keys)
        val highwayOnly = graph(WayLine.of(trunk, 0.0 to 0.0, 0.0 to 0.009))
        assertEquals(RouteFailure.NOT_CONNECTED, failed(Router(highwayOnly).route(0.0, 0.0, 0.0, 0.009, TravelMode.HIKE)).reason)
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
        val r = failed(Router(old).route(0.0, 0.0, 0.0, 0.009, TravelMode.DRIVE))
        assertEquals(RouteFailure.DRIVE_UNSUPPORTED, r.reason)
        assertTrue(r.message.contains("rainier-outdoors.pmtiles was built before it"), r.message)
        assertEquals("drive_unsupported", r.json()["error"])
        // Hiking on the same pack is unchanged.
        found(Router(old).route(0.0, 0.0, 0.0, 0.009, TravelMode.HIKE))
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
        val p = found(TileRouter(TiledGraph(listOf(a))).route(46.79, -121.749, 46.791, -121.711, TravelMode.DRIVE))
        assertEquals(p.distanceM / (56 / 3.6), p.timeS, 2.0)
        assertIs<RouteResult.Failed>(TileRouter(TiledGraph(listOf(a))).route(46.791, -121.711, 46.79, -121.749, TravelMode.DRIVE))
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
            val a = found(Router(base).route(46.7864, -121.7366, 46.8024, -121.7289, mode))
            val b = found(Router(withDrive).route(46.7864, -121.7366, 46.8024, -121.7289, mode))
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
}
