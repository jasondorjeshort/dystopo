package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.model.RouteEditor
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T102: turn directions ([Directions]) on synthetic networks near the equator (about 111 m per 0.001 degree):
 * the turn classes, nothing at a junction with no choice, straight-through junctions merged, the name fallback,
 * the lead distance by mode and speed, the priorities and the level filter, drive's own choices, trips with via
 * points and straight legs, the JSON, and the Paradise fixture plus a drive (ADR 0032: tests carry a drive case).
 */
class DirectionsTest {
    private val source = GraphSource("directions", "fp", "", 14, 0, drive = true)
    private fun trail(name: String?) = WayAttrs(WayClass.PATH, Surface.UNPAVED, name = name)
    private fun road(name: String?, cls: WayClass = WayClass.MINOR, kmh: Int = 0, flags: Int = 0) = WayAttrs(cls, Surface.PAVED, name = name, maxspeedKmh = kmh, flags = flags)
    private fun graph(vararg lines: WayLine) = RoutingGraphBuilder().build(lines.toList(), source)
    private fun line(a: WayAttrs, vararg p: Pair<Double, Double>) = WayLine.of(a, *p)

    private fun plan(g: RoutingGraph, from: Pair<Double, Double>, to: Pair<Double, Double>, mode: TravelMode = TravelMode.HIKE): PlannedRoute {
        val r = Router(g).route(from.first, from.second, to.first, to.second, mode)
        assertIs<RouteResult.Found>(r, "expected a route, got $r")
        return r.plan
    }

    private fun types(p: PlannedRoute) = p.instructions.map { it.type }
    private fun texts(p: PlannedRoute) = p.instructions.map { DirectionsText.of(it, p.mode) }

    /** A plus: Main Trail west to east along the equator, Cross Trail south to north through (0, 0). */
    private fun plus() = graph(
        line(trail("Main Trail"), 0.0 to -0.002, 0.0 to 0.002),
        line(trail("Cross Trail"), -0.002 to 0.0, 0.002 to 0.0),
    )

    @Test
    fun turnsAreClassedByAngle() {
        assertNull(Directions.classify(10.0))
        assertNull(Directions.classify(-24.0))
        assertEquals(Maneuver.SLIGHT_RIGHT, Directions.classify(30.0))
        assertEquals(Maneuver.SLIGHT_LEFT, Directions.classify(-45.0))
        assertEquals(Maneuver.RIGHT, Directions.classify(90.0))
        assertEquals(Maneuver.LEFT, Directions.classify(-60.0))
        assertEquals(Maneuver.SHARP_RIGHT, Directions.classify(140.0))
        assertEquals(Maneuver.SHARP_LEFT, Directions.classify(-150.0))
        assertEquals(Maneuver.U_TURN, Directions.classify(175.0))
        assertEquals(-90.0, Directions.norm(270.0))
        assertEquals(180.0, Directions.norm(-180.0))

        val g = plus()
        val left = plan(g, 0.0 to -0.002, 0.002 to 0.0)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.LEFT, Maneuver.ARRIVE), types(left))
        val turn = left.instructions[1]
        assertEquals(Instruction.MAJOR, turn.priority)
        assertEquals("Cross Trail", turn.name)
        assertEquals(-90.0, turn.turnDeg.toDouble(), 3.0)
        assertEquals(222.6, turn.alongM, 2.0)
        assertEquals(listOf("Head east on Main Trail", "Turn left onto Cross Trail", "Arrive at the end of the route"), texts(left))
        assertEquals(Maneuver.RIGHT, plan(g, 0.0 to -0.002, -0.002 to 0.0).instructions[1].type)
        // Straight across on the same trail: nothing between depart and arrive.
        assertEquals(listOf(Maneuver.DEPART, Maneuver.ARRIVE), types(plan(g, 0.0 to -0.002, 0.0 to 0.002)))
    }

    @Test
    fun noInstructionAtAJunctionWithNoChoice() {
        // Two trails joined end to end at a right angle: a bend, and a new name, but nowhere else to go.
        val g = graph(line(trail("East Trail"), 0.0 to 0.0, 0.0 to 0.002), line(trail("North Trail"), 0.0 to 0.002, 0.002 to 0.002))
        val p = plan(g, 0.0 to 0.0, 0.002 to 0.002)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.ARRIVE), types(p))
        // The arrival carries the way the route ends on? No: names are said at decisions only, so it is still East Trail.
        assertEquals("East Trail", p.instructions.first().name)
    }

    @Test
    fun straightThroughJunctionsAreMerged() {
        // Long Trail eastwards past three side trails, then a right turn onto Exit Trail.
        val g = graph(
            line(trail("Long Trail"), 0.0 to 0.0, 0.0 to 0.01),
            line(trail("Side One"), 0.0 to 0.002, 0.002 to 0.002),
            line(trail("Side Two"), 0.0 to 0.004, -0.002 to 0.004),
            line(trail("Side Three"), 0.0 to 0.006, 0.002 to 0.006),
            line(trail("Exit Trail"), 0.0 to 0.008, -0.002 to 0.008),
        )
        val p = plan(g, 0.0 to 0.0, -0.002 to 0.008)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.RIGHT, Maneuver.ARRIVE), types(p), texts(p).toString())
        assertEquals(890.4, p.instructions[1].alongM, 3.0)
        assertEquals(890.4, p.instructions[0].stretchM, 3.0) // one stretch from the start to the turn
        // Straight on where the name changes and another way leaves: continue onto the new name.
        val named = graph(
            line(trail("Long Trail"), 0.0 to 0.0, 0.0 to 0.002),
            line(trail("Ridge Trail"), 0.0 to 0.002, 0.0 to 0.004),
            line(trail("Spur"), 0.0 to 0.002, 0.002 to 0.002),
        )
        val c = plan(named, 0.0 to 0.0, 0.0 to 0.004)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.CONTINUE, Maneuver.ARRIVE), types(c))
        assertEquals(Instruction.NORMAL, c.instructions[1].priority)
        assertEquals("Continue onto Ridge Trail", texts(c)[1])
    }

    @Test
    fun namesFallBackFromNameToRefToClass() {
        fun attrs(vararg kv: Pair<String, String>) = WayAttrs.of(kv.associate { (k, v) -> k to Value.StringValue(v) })!!
        assertEquals("Wonderland Trail", attrs("class" to "path", "name" to "Wonderland Trail", "ref" to "1199").name)
        assertEquals("Wonderland Trail", attrs("class" to "path", "route_name" to "Wonderland Trail;Pacific Crest Trail", "ref" to "1199").name)
        assertEquals("1199", attrs("class" to "path", "ref" to "1199").name) // T102: a numbered trail keeps its number
        assertEquals("SR 123", attrs("class" to "secondary", "ref" to "SR 123").name)
        assertNull(attrs("class" to "path").name)
        // Unnamed: the class.
        val g = graph(line(trail("Main Trail"), 0.0 to -0.002, 0.0 to 0.002), line(trail(null), -0.002 to 0.0, 0.002 to 0.0))
        val p = plan(g, 0.0 to -0.002, 0.002 to 0.0)
        assertEquals("Turn left onto the path", texts(p)[1])
        assertEquals("the road", Directions.classWord(WayClass.TERTIARY))
        assertEquals("the track", Instruction(Maneuver.LEFT, 1, 0.0, 0.0, 0.0, wayClass = WayClass.TRACK).label)
    }

    @Test
    fun leadDistanceByModeAndSpeed() {
        assertEquals(27.8, Directions.leadM(TravelMode.HIKE), 0.1) // tens of metres on foot
        assertEquals(88.9, Directions.leadM(TravelMode.BIKE), 0.1)
        assertEquals(500.0, Directions.leadM(TravelMode.DRIVE, 40.0), 0.1) // hundreds of metres to miles in a car
        assertEquals(1312.5, Directions.leadM(TravelMode.DRIVE, 105.0), 0.1)
        assertEquals(150.0, Directions.leadM(TravelMode.DRIVE, 5.0), 0.1)
        assertEquals(3200.0, Directions.leadM(TravelMode.DRIVE, 400.0), 0.1)
        assertEquals(20.0, Directions.leadM(TravelMode.HIKE, 1.0), 0.1)
        // On a route: the approach road's speed, never further back than the start.
        val g = graph(
            line(road("Valley Road", WayClass.SECONDARY, kmh = 80), 0.0 to 0.0, 0.0 to 0.02),
            line(road("Trailhead Road"), 0.0 to 0.02, 0.002 to 0.02),
            line(road("Ranch Road"), 0.0 to 0.02, -0.002 to 0.02),
        )
        val car = plan(g, 0.0 to 0.0, 0.002 to 0.02, TravelMode.DRIVE)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.LEFT, Maneuver.ARRIVE), types(car))
        assertEquals(1000.0, car.instructions[1].leadM, 1.0) // 80 km/h for 45 s
        val walk = plan(g, 0.0 to 0.0, 0.002 to 0.02)
        assertEquals(27.8, walk.instructions[1].leadM, 0.1)
        val short = plan(g, 0.0 to 0.0198, 0.002 to 0.02, TravelMode.DRIVE)
        assertEquals(22.3, short.instructions[1].leadM, 1.0) // 22 m from the start: announced from the start
    }

    @Test
    fun prioritiesAndTheLevelFilter() {
        // A fork: Upper and Lower leave Main 17 degrees either side of straight on.
        val fork = graph(
            line(trail("Main"), 0.0 to 0.0, 0.0 to 0.002),
            line(trail("Upper"), 0.0 to 0.002, 0.0006 to 0.004),
            line(trail("Lower"), 0.0 to 0.002, -0.0006 to 0.004),
        )
        val up = plan(fork, 0.0 to 0.0, 0.0006 to 0.004)
        assertEquals(Maneuver.KEEP_LEFT, up.instructions[1].type)
        assertEquals(Instruction.MAJOR, up.instructions[1].priority)
        assertEquals("Keep left onto Upper", texts(up)[1])
        assertEquals(Maneuver.KEEP_RIGHT, plan(fork, 0.0 to 0.0, -0.0006 to 0.004).instructions[1].type)

        // A road that bends 45 degrees left at a side road going straight on: going straight would be wrong, major.
        val straighter = graph(
            line(road("Park Road"), 0.0 to 0.0, 0.0 to 0.002, 0.0015 to 0.0035),
            line(road("Side Road"), 0.0 to 0.002, 0.0 to 0.004),
        )
        val a = plan(straighter, 0.0 to 0.0, 0.0015 to 0.0035, TravelMode.DRIVE)
        assertEquals(Maneuver.SLIGHT_LEFT, a.instructions[1].type)
        assertEquals(Instruction.MAJOR, a.instructions[1].priority)
        assertEquals("Bear left to stay on Park Road", texts(a)[1])
        // The same bend with the side road at a right angle: the road's own bend, minor (only at the most detailed level).
        val bend = graph(
            line(road("Park Road"), 0.0 to 0.0, 0.0 to 0.002, 0.0015 to 0.0035),
            line(road("Side Road"), 0.0 to 0.002, -0.002 to 0.002),
        )
        val b = plan(bend, 0.0 to 0.0, 0.0015 to 0.0035, TravelMode.DRIVE)
        assertEquals(Maneuver.SLIGHT_LEFT, b.instructions[1].type)
        assertEquals(Instruction.MINOR, b.instructions[1].priority)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.ARRIVE), Directions.shown(b.instructions).map { it.type })
        assertEquals(3, Directions.shown(b.instructions, Instruction.MINOR).size)
        assertEquals(b.distanceM, Directions.shown(b.instructions)[0].stretchM, 1.0) // the hidden bend is no stretch of its own

        // Navigation's next instruction by progress, at the default level.
        val list = listOf(
            Instruction(Maneuver.DEPART, 1, 0.0, 0.0, 0.0), Instruction(Maneuver.SLIGHT_LEFT, 3, 100.0, 0.0, 0.0),
            Instruction(Maneuver.RIGHT, 1, 200.0, 0.0, 0.0), Instruction(Maneuver.ARRIVE, 1, 300.0, 0.0, 0.0),
        )
        assertEquals(0, Directions.next(list, 0.0))
        assertEquals(2, Directions.next(list, 15.0))
        assertEquals(1, Directions.next(list, 15.0, level = Instruction.MINOR))
        assertEquals(2, Directions.next(list, 205.0)) // still shown just past the junction
        assertEquals(3, Directions.next(list, 211.0))
        assertEquals(-1, Directions.next(list, 311.0))
    }

    @Test
    fun driveCountsOnlyWhatACarMayTake() {
        // Main Road bends right 60 degrees where a trail goes straight on: no choice by car, a real one on foot.
        val withTrail = graph(
            line(road("Main Road"), 0.0 to 0.0, 0.0 to 0.005, -0.0029 to 0.0067),
            line(trail("Lake Trail"), 0.0 to 0.005, 0.0 to 0.007),
        )
        assertEquals(listOf(Maneuver.DEPART, Maneuver.ARRIVE), types(plan(withTrail, 0.0 to 0.0, -0.0029 to 0.0067, TravelMode.DRIVE)))
        val onFoot = plan(withTrail, 0.0 to 0.0, -0.0029 to 0.0067)
        assertEquals(Maneuver.RIGHT, onFoot.instructions[1].type)
        assertEquals(Instruction.MAJOR, onFoot.instructions[1].priority) // the trail is straighter
        // A one-way street that only comes in: a car cannot leave along it.
        val oneWayIn = graph(
            line(road("Main Road"), 0.0 to 0.0, 0.0 to 0.005, -0.0029 to 0.0067),
            line(road("Mill Street", flags = WayAttrs.ONEWAY), 0.0 to 0.007, 0.0 to 0.005),
        )
        assertEquals(listOf(Maneuver.DEPART, Maneuver.ARRIVE), types(plan(oneWayIn, 0.0 to 0.0, -0.0029 to 0.0067, TravelMode.DRIVE)))
        assertEquals(Maneuver.RIGHT, plan(oneWayIn, 0.0 to 0.0, -0.0029 to 0.0067).instructions[1].type)
    }

    @Test
    fun pullOutsSplitsAndAKeepBeforeATurnSayNothing() {
        // A parking pull-out leaves Canyon Road 15 degrees to the right: by car following the road says nothing; on
        // foot it is a fork like any other.
        val pullOut = graph(
            line(road("Canyon Road", WayClass.SECONDARY), 0.0 to 0.0, 0.0 to 0.005, 0.0 to 0.01),
            line(road(null, WayClass.SERVICE), 0.0 to 0.005, -0.0005 to 0.0069),
        )
        assertEquals(listOf(Maneuver.DEPART, Maneuver.ARRIVE), types(plan(pullOut, 0.0 to 0.0, 0.0 to 0.01, TravelMode.DRIVE)))
        assertEquals(Maneuver.KEEP_LEFT, plan(pullOut, 0.0 to 0.0, 0.0 to 0.01).instructions[1].type)
        // The same road splitting in two (both ways named Loop Road): nothing.
        val split = graph(
            line(road("Loop Road"), 0.0 to 0.0, 0.0 to 0.005, 0.0 to 0.01),
            line(road("Loop Road"), 0.0 to 0.005, -0.0005 to 0.0069, -0.0005 to 0.01),
        )
        assertEquals(listOf(Maneuver.DEPART, Maneuver.ARRIVE), types(plan(split, 0.0 to 0.0, 0.0 to 0.01, TravelMode.DRIVE)))
        // Keep left at a fork, then 47 m on turn left onto Cross Road: by car the keep is left to the turn.
        val forkThenTurn = graph(
            line(road("Main Road"), 0.0 to 0.0, 0.0 to 0.002, 0.00013 to 0.0024, 0.00013 to 0.004),
            line(road("Lower Road"), 0.0 to 0.002, -0.00013 to 0.0024, -0.00013 to 0.004),
            line(road("Cross Road"), 0.00013 to 0.0024, 0.002 to 0.0024),
        )
        val car = plan(forkThenTurn, 0.0 to 0.0, 0.002 to 0.0024, TravelMode.DRIVE)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.LEFT, Maneuver.ARRIVE), types(car), texts(car).toString())
        assertEquals(268.0, car.instructions[1].leadM, 2.0) // back to the start: the dropped keep does not cut it short
        // On foot 47 m is a stretch of its own (merged only within 15 m).
        assertEquals(listOf(Maneuver.DEPART, Maneuver.KEEP_LEFT, Maneuver.LEFT, Maneuver.ARRIVE), types(plan(forkThenTurn, 0.0 to 0.0, 0.002 to 0.0024)))
    }

    @Test
    fun tripsSayTheirViaPointsAndStraightLegs() {
        val g = plus()
        val ed = RouteEditor(Router(g))
        // West end, north end, then a point 1 km north of any trail: the last leg is straight.
        val path = ed.create(listOf(0.0 to -0.002, 0.002 to 0.0, 0.012 to 0.0))
        val list = ed.directions(path)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.LEFT, Maneuver.OFF_NETWORK, Maneuver.ARRIVE), list.map { it.type })
        assertEquals(1, list[2].via)
        assertEquals("Head north off the trails", DirectionsText.of(list[2], TravelMode.HIKE))
        assertEquals(1112.0, list[2].stretchM, 2.0)
        // Out to a via point on the trail and back: turn back there.
        val back = ed.create(listOf(0.0 to -0.002, 0.0 to 0.001, 0.0011 to 0.0))
        val b = ed.directions(back)
        assertEquals(Maneuver.VIA, b[1].type)
        assertEquals("Turn back at via point 1", DirectionsText.of(b[1], TravelMode.HIKE))
        assertEquals(Instruction.MAJOR, b[1].priority)
        assertEquals(Maneuver.RIGHT, b[2].type) // at the crossing on the way back, north onto Cross Trail
        // No graph (a remote route, a track): only the frame.
        assertEquals(listOf(Maneuver.DEPART, Maneuver.ARRIVE), Directions.ofPath(path.copy(via = path.via.take(2), legs = path.legs.take(1)), null).map { it.type })
    }

    @Test
    fun jsonRoundTripsAndDistancesFollowTheUnits() {
        val p = plan(plus(), 0.0 to -0.002, 0.002 to 0.0)
        for (i in p.instructions) {
            val back = Instruction.parse(i.json(p.mode))
            assertNotNull(back)
            assertEquals(i.type, back.type)
            assertEquals(i.priority, back.priority)
            assertEquals(i.alongM, back.alongM, 0.06)
            assertEquals(i.name, back.name)
            assertEquals(i.wayClass, back.wayClass)
            assertEquals(i.turnDeg, back.turnDeg)
        }
        val json = p.json()["instructions"] as List<*>
        assertEquals("Turn left onto Cross Trail", (json[1] as Map<*, *>)["text"])
        assertEquals("left", (json[1] as Map<*, *>)["type"])
        assertNull(Instruction.parse(mapOf("type" to "sideways", "alongM" to 1.0, "lat" to 0.0, "lon" to 0.0)))
        val turn = p.instructions[1]
        assertEquals("In 300 m, turn left onto Cross Trail", DirectionsText.withDistance(turn, 300.0, ContourUnits.METERS, TravelMode.HIKE))
        assertEquals("In 328 ft, turn left onto Cross Trail", DirectionsText.withDistance(turn, 100.0, ContourUnits.FEET, TravelMode.HIKE))
    }

    @Test
    fun paradiseFixtureHike() {
        // RainierRoutingTest's Paradise network: up the Skyline Trail to Panorama Point.
        val fixture = RainierRoutingTest()
        val g = fixture.graph()
        val p = plan(g, 46.7864 to -121.7366, 46.8024 to -121.7289)
        val t = texts(p)
        assertTrue(t.first().startsWith("Head ") && t.first().endsWith(" on Skyline Trail") || t.first().endsWith(" on the footpath"), t.toString())
        assertEquals(Maneuver.ARRIVE, p.instructions.last().type)
        // Every instruction between lies on a junction where another way leaves.
        val router = Router(g)
        for (i in p.instructions.drop(1).dropLast(1)) {
            val ways = router.junctions!!.junction(Directions.e6(i.lon), Directions.e6(i.lat), TravelMode.HIKE)
            assertNotNull(ways, "instruction ${i.type} at ${i.lat},${i.lon} is on a node")
            assertTrue(ways.size >= 3, "instruction ${i.type} at ${i.lat},${i.lon}: ${ways.size} ways")
        }
        assertTrue(p.instructions.size in 3..8, t.toString())
        // Golden Gate junction to Myrtle Falls: one trail, nothing to say on the way.
        val gg = plan(g, 46.7989 to -121.7227, 46.7914 to -121.7325)
        assertEquals("Golden Gate Trail", gg.instructions.first().name)
    }

    @Test
    fun thenCuesJoinInstructionsTooCloseToSayApart() {
        // T116: Main Road east along the equator; Ridge Road north from 1.1 km; 100 m up it, Summit Road east (Ridge
        // Road goes on north, so both junctions are choices).
        val g = graph(
            line(road("Main Road"), 0.0 to 0.0, 0.0 to 0.03),
            line(road("Ridge Road"), 0.0 to 0.01, 0.0009 to 0.01, 0.003 to 0.01),
            line(road("Summit Road"), 0.0009 to 0.01, 0.0009 to 0.03),
        )
        fun shownTexts(p: PlannedRoute) = Directions.shown(p.instructions).map { DirectionsText.of(it, p.mode) }
        // By car the right turn comes 100 m after the left, inside its lead (150 m at least): the left says both.
        val car = plan(g, 0.0 to 0.0, 0.0009 to 0.03, TravelMode.DRIVE)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.LEFT, Maneuver.RIGHT, Maneuver.ARRIVE), types(car))
        assertEquals(100.1, car.instructions[2].leadM, 1.0) // capped at the gap: the cue's trigger
        assertEquals(
            listOf("Head east on Main Road", "Turn left onto Ridge Road, then turn right", "Turn right onto Summit Road", "Arrive at the end of the route"),
            shownTexts(car),
        )
        assertEquals(listOf(null, Maneuver.RIGHT, null, null), Directions.shown(car.instructions).map { it.then?.type })
        // The planned list itself carries no cue (it is saved as it is); the JSON's text and `then` carry the cue.
        assertTrue(car.instructions.all { it.then == null })
        assertEquals("Turn left onto Ridge Road", texts(car)[1])
        @Suppress("UNCHECKED_CAST") val json = car.json()["instructions"] as List<Map<String, Any?>>
        assertEquals(listOf("Head east on Main Road", "Turn left onto Ridge Road, then turn right", "Turn right onto Summit Road", "Arrive at the end of the route"), json.map { it["text"] })
        assertEquals(listOf(null, "right", null, null), json.map { it["then"] })
        assertEquals(car.instructions.map { it.alongM }, Directions.cued(car.instructions).map { it.alongM }) // text only
        assertEquals("In 300 m, turn left onto Ridge Road, then turn right", DirectionsText.withDistance(Directions.shown(car.instructions)[1], 300.0, ContourUnits.METERS, TravelMode.DRIVE))
        // On foot the lead is about 28 m: 100 m apart, each is said on its own.
        val walk = plan(g, 0.0 to 0.0, 0.0009 to 0.03)
        assertEquals(types(car), types(walk))
        assertEquals(listOf("Head east on Main Road", "Turn left onto Ridge Road", "Turn right onto Summit Road", "Arrive at the end of the route"), shownTexts(walk))
        // A short drive: the start 55 m before the left turn and the end 55 m past the right: every step is close.
        val short = plan(g, 0.0 to 0.0095, 0.0009 to 0.0105, TravelMode.DRIVE)
        assertEquals(
            listOf("Head east on Main Road, then turn left", "Turn left onto Ridge Road, then turn right", "Turn right onto Summit Road, then arrive", "Arrive at the end of the route"),
            shownTexts(short),
        )
        // Saved and read back (the field keeps no cue), the list says the same: the cues follow from the list.
        val path = io.github.jasondorjeshort.dystopo.model.RoutePath.planned(short.route.points, short.mode.label, short.timeS)
        val back = io.github.jasondorjeshort.dystopo.model.RouteDirections.decode(io.github.jasondorjeshort.dystopo.model.RouteDirections.encode(short.instructions, path)!!).list
        assertTrue(back.all { it.then == null })
        assertEquals(shownTexts(short), Directions.shown(back).map { DirectionsText.of(it, TravelMode.DRIVE) })
        // A hidden (minor) instruction is no cue and gets none; a continue (a new name, nothing to do) is no cue.
        val list = listOf(
            Instruction(Maneuver.DEPART, 1, 0.0, 0.0, 0.0), Instruction(Maneuver.SLIGHT_LEFT, 3, 20.0, 0.0, 0.0, leadM = 20.0),
            Instruction(Maneuver.CONTINUE, 2, 400.0, 0.0, 0.0, name = "Ridge Road", leadM = 380.0),
            Instruction(Maneuver.KEEP_RIGHT, 1, 1000.0, 0.0, 0.0, leadM = 600.0), Instruction(Maneuver.ARRIVE, 1, 1100.0, 0.0, 0.0, leadM = 100.0),
        )
        assertEquals(listOf(null, null, Maneuver.KEEP_RIGHT, Maneuver.ARRIVE, null), Directions.cued(list).map { it.then?.type })
        assertEquals(listOf(null, null, Maneuver.KEEP_RIGHT, Maneuver.ARRIVE, null), Directions.cued(Directions.cued(list)).map { it.then?.type }) // idempotent
        assertEquals(listOf(Maneuver.SLIGHT_LEFT, null, Maneuver.KEEP_RIGHT, Maneuver.ARRIVE, null), Directions.cued(list, Instruction.MINOR).map { it.then?.type })
        assertEquals("Continue onto Ridge Road, then keep right", DirectionsText.of(Directions.cued(list)[2], TravelMode.DRIVE))
    }

    @Test
    fun aDriveToTheTrailhead() {
        // Paradise to Sunrise in miniature: the visitor centre road, right onto Stevens Canyon Road, straight on where it
        // becomes SR 123 (a road joins), left onto Sunrise Park Road; side roads passed straight through say nothing.
        val g = graph(
            line(road("Paradise Road", WayClass.TERTIARY, kmh = 56), 0.0 to 0.0, 0.0 to 0.01),
            line(road("Stevens Canyon Road", WayClass.SECONDARY, kmh = 56), 0.0 to 0.01, -0.03 to 0.01),
            line(road("Longmire Road", WayClass.TERTIARY), 0.0 to 0.01, 0.01 to 0.01),
            line(road("Box Canyon Road"), -0.01 to 0.01, -0.01 to 0.02),
            line(road("SR 123", WayClass.PRIMARY, kmh = 80), -0.03 to 0.01, -0.06 to 0.01),
            line(road("Ohanapecosh Road"), -0.03 to 0.01, -0.03 to 0.0),
            line(road("Sunrise Park Road", WayClass.TERTIARY), -0.06 to 0.01, -0.06 to 0.03),
            line(road("Cayuse Pass Road", WayClass.PRIMARY), -0.06 to 0.01, -0.07 to 0.01),
        )
        val p = plan(g, 0.0 to 0.0, -0.06 to 0.03, TravelMode.DRIVE)
        assertEquals(
            listOf(
                "Head east on Paradise Road", "Turn right onto Stevens Canyon Road", "Continue onto SR 123",
                "Turn left onto Sunrise Park Road", "Arrive at the end of the route",
            ),
            texts(p),
        )
        assertEquals(700.0, p.instructions[1].leadM, 1.0) // 56 km/h for 45 s
        assertEquals(1000.0, p.instructions[3].leadM, 1.0) // 80 km/h on SR 123
        assertTrue(p.instructions.all { it.priority <= Directions.DEFAULT_LEVEL })
        assertTrue(p.route.description!!.endsWith(Router.DRIVE_NOTICE), p.route.description)
        assertTrue("road navigator" !in Router.DRIVE_NOTICE)
        assertTrue(abs(p.instructions[1].turnDeg - 90) <= 3)
    }
}
