package io.github.jasondorjeshort.dystopo.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T276: a route's kind beside its mode, the guess on foot from the share in town, and the five chips. */
class TripKindTest {
    @Test
    fun theKindByMode() {
        assertEquals(TripKind.EVERYDAY, TripKinds.byMode(TravelMode.DRIVE))
        assertNull(TripKinds.byMode(TravelMode.BIKE), "T278: a bike route decides by its share")
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.byMode(TravelMode.CROW))
        assertNull(TripKinds.byMode(TravelMode.HIKE), "on foot the route decides")
        // The mode's kind wins over anything a route on foot would say, and over a tap meant for foot.
        val trails = mapOf(WayClass.PATH to 900.0)
        assertEquals(KindChoice(TripKind.EVERYDAY, KindReason.MODE), TripKinds.choose(TravelMode.DRIVE, trails, tapped = TripKind.BACKCOUNTRY))
        assertEquals(KindChoice(TripKind.BACKCOUNTRY, KindReason.MODE), TripKinds.choose(TravelMode.CROW, emptyMap()))
    }

    @Test
    fun aRouteOnFootIsGuessedFromItsShareInTown() {
        // The Chamonix streets of the table: footways, minor and service roads, steps.
        val town = mapOf(WayClass.FOOTWAY to 1586.0, WayClass.MINOR to 501.0, WayClass.SERVICE to 164.0, WayClass.STEPS to 32.0)
        assertEquals(KindChoice(TripKind.EVERYDAY, KindReason.SHARE, 1.0), TripKinds.choose(TravelMode.HIKE, town))
        // A valley trail: paths and tracks with a road to reach them.
        val trail = mapOf(WayClass.PATH to 1487.0, WayClass.TRACK to 969.0, WayClass.MINOR to 932.0, WayClass.SERVICE to 67.0)
        val t = TripKinds.choose(TravelMode.HIKE, trail)
        assertEquals(TripKind.BACKCOUNTRY, t.kind)
        assertEquals(KindReason.SHARE, t.reason)
        assertEquals(999.0 / 3455.0, t.townShare!!, 1e-9)
        // Bridleways are off road; cycleways and steps are town.
        assertEquals(0.0, TripKinds.townShare(mapOf(WayClass.BRIDLEWAY to 100.0))!!)
        assertEquals(1.0, TripKinds.townShare(mapOf(WayClass.CYCLEWAY to 50.0, WayClass.STEPS to 50.0))!!)
    }

    @Test
    fun aGradedFootwayIsATrail() {
        // The paved trails at Paradise are footways with a sac_scale grade: by class alone the walk to Panorama Point is
        // 87 percent "town"; the graded metres take it out.
        val byClass = mapOf(WayClass.FOOTWAY to 2553.0, WayClass.PATH to 398.0, WayClass.STEPS to 2.0)
        val graded = mapOf(WayClass.FOOTWAY to 2297.0, WayClass.STEPS to 2.0)
        val c = TripKinds.guess(byClass, graded)
        assertEquals(TripKind.BACKCOUNTRY, c.kind)
        assertEquals(256.0 / 2953.0, c.townShare!!, 1e-9)
        assertEquals(TripKind.EVERYDAY, TripKinds.guess(byClass).kind, "without the grades it read as a walk")
    }

    @Test
    fun theThresholdIsInclusive() {
        val t = TripKinds.TOWN_SHARE
        assertEquals(TripKind.EVERYDAY, TripKinds.guess(mapOf(WayClass.MINOR to t * 1000, WayClass.PATH to (1 - t) * 1000)).kind)
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.guess(mapOf(WayClass.MINOR to t * 1000 - 1, WayClass.PATH to (1 - t) * 1000 + 1)).kind)
    }

    @Test
    fun anUnknownShareIsHikeNeverWalk() {
        // A server's route (RemoteRouter) and a restored one carry no metres by class.
        assertEquals(KindChoice(TripKind.BACKCOUNTRY, KindReason.UNKNOWN), TripKinds.guess(emptyMap()))
        assertEquals(KindChoice(TripKind.BACKCOUNTRY, KindReason.UNKNOWN), TripKinds.choose(TravelMode.HIKE, emptyMap()))
        assertEquals(KindChoice(TripKind.BACKCOUNTRY, KindReason.UNKNOWN), TripKinds.guess(mapOf(WayClass.PATH to 0.0)))
        assertNull(TripKinds.townShare(emptyMap()))
    }

    @Test
    fun aTapWinsOnFoot() {
        val town = mapOf(WayClass.MINOR to 900.0)
        val c = TripKinds.choose(TravelMode.HIKE, town, tapped = TripKind.BACKCOUNTRY)
        assertEquals(TripKind.BACKCOUNTRY, c.kind)
        assertEquals(KindReason.TAP, c.reason)
        assertEquals(1.0, c.townShare)
        assertEquals(KindChoice(TripKind.EVERYDAY, KindReason.TAP, null), TripKinds.choose(TravelMode.HIKE, emptyMap(), tapped = TripKind.EVERYDAY))
    }

    @Test
    fun theFiveChips() {
        assertEquals(listOf("Walk", "Hike", "Bike", "Drive", "Line"), RouteChip.entries.map { it.label })
        assertTrue(RouteChip.entries.all { TravelMode.of(it.mode.label) == it.mode }, "every chip is a mode the server knows")
        assertEquals(RouteChip.WALK, RouteChip.of(TravelMode.HIKE, TripKind.EVERYDAY))
        assertEquals(RouteChip.HIKE, RouteChip.of(TravelMode.HIKE, TripKind.BACKCOUNTRY))
        assertNull(RouteChip.of(TravelMode.HIKE, null), "no foot chip is lit while the kind is not known")
        assertEquals(RouteChip.DRIVE, RouteChip.of(TravelMode.DRIVE, null))
        assertEquals(RouteChip.BIKE, RouteChip.of(TravelMode.BIKE, TripKind.BACKCOUNTRY))
        assertEquals(RouteChip.LINE, RouteChip.of(TravelMode.CROW, TripKind.BACKCOUNTRY))
        // Each chip's own kind is the one its mode gives, or (on foot) the one it names.
        for (c in RouteChip.entries) assertEquals(c.kind, TripKinds.choose(c.mode, emptyMap(), tapped = c.kind).kind)
        assertEquals(TripKind.EVERYDAY, TripKind.of("Everyday"))
        assertEquals(KindReason.SHARE, KindReason.of("share"))
        assertNull(TripKind.of("walk"))
    }

    /**
     * T278: a bike route is everyday when most of it runs on roads and streets ([TripKinds.BIKE_TOWN_SHARE], the T278
     * report's table of `devtool route --mode bike`), backcountry on tracks and trails or when its share is not known.
     */
    @Test
    fun aBikeRouteIsGuessedFromItsShareInTown() {
        // Chamonix's streets and the valley cycleway on a bike (the table's cham-c): everyday.
        val streets = mapOf(WayClass.CYCLEWAY to 2459.0, WayClass.FOOTWAY to 653.0, WayClass.MINOR to 414.0, WayClass.SERVICE to 189.0, WayClass.TRACK to 72.0)
        val c = TripKinds.choose(TravelMode.BIKE, streets)
        assertEquals(TripKind.EVERYDAY, c.kind)
        assertEquals(KindReason.SHARE, c.reason)
        // A valley track and trail (the table's Bossons): backcountry.
        val trail = mapOf(WayClass.PATH to 1487.0, WayClass.TRACK to 969.0, WayClass.MINOR to 932.0, WayClass.SERVICE to 67.0)
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.choose(TravelMode.BIKE, trail).kind)
        // A graded trail is a trail on a bike too.
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.guess(mapOf(WayClass.FOOTWAY to 1000.0), mapOf(WayClass.FOOTWAY to 900.0), TravelMode.BIKE).kind)
        // No share known (a server's route, a restored one): backcountry, as before T278.
        assertEquals(KindChoice(TripKind.BACKCOUNTRY, KindReason.UNKNOWN), TripKinds.choose(TravelMode.BIKE, emptyMap()))
        // The threshold, both sides.
        val t = TripKinds.BIKE_TOWN_SHARE
        assertEquals(t, TripKinds.threshold(TravelMode.BIKE))
        assertEquals(TripKinds.TOWN_SHARE, TripKinds.threshold(TravelMode.HIKE))
        assertEquals(TripKind.EVERYDAY, TripKinds.guess(mapOf(WayClass.MINOR to t * 1000, WayClass.TRACK to (1 - t) * 1000), mode = TravelMode.BIKE).kind)
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.guess(mapOf(WayClass.MINOR to t * 1000 - 1, WayClass.TRACK to (1 - t) * 1000 + 1), mode = TravelMode.BIKE).kind)
        // Trip details: on every backcountry card, and on an everyday Bike card as its override; not on a Walk or a Drive.
        assertTrue(TripKinds.hasDetails(TravelMode.HIKE, TripKind.BACKCOUNTRY))
        assertTrue(TripKinds.hasDetails(TravelMode.BIKE, TripKind.EVERYDAY))
        assertTrue(TripKinds.hasDetails(TravelMode.BIKE, TripKind.BACKCOUNTRY))
        assertTrue(TripKinds.hasDetails(TravelMode.CROW, TripKind.BACKCOUNTRY))
        assertTrue(!TripKinds.hasDetails(TravelMode.HIKE, TripKind.EVERYDAY))
        assertTrue(!TripKinds.hasDetails(TravelMode.DRIVE, TripKind.EVERYDAY))
        // The Bike chip is lit whatever the kind (no chip overrides a bike's kind).
        assertEquals(RouteChip.BIKE, RouteChip.of(TravelMode.BIKE, TripKind.EVERYDAY))
        assertEquals(RouteChip.BIKE, RouteChip.of(TravelMode.BIKE, null))
    }

    /** T278: the kind a kept trip is navigated with (absent reads as by the mode, as the trip card does). */
    @Test
    fun aKeptTripIsNavigatedByItsKind() {
        assertEquals(TripKind.EVERYDAY, TripKinds.navKind(TravelMode.HIKE, TripKind.EVERYDAY), "a kept Walk")
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.navKind(TravelMode.HIKE, TripKind.BACKCOUNTRY), "a kept Hike")
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.navKind(TravelMode.HIKE, null), "a hike kept before T278")
        assertEquals(TripKind.EVERYDAY, TripKinds.navKind(TravelMode.DRIVE, null), "a drive kept before T278")
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.navKind(TravelMode.CROW, null))
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.navKind(TravelMode.BIKE, TripKind.EVERYDAY), "a kept ride as before")
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.navKind(null, null), "a mode this build does not know")
    }

    @Test
    fun theRouterCountsTheGradedMetres() {
        // A footway with a hiking grade and a plain one, end to end: the router's metres by class and the graded ones.
        val g = RoutingGraphBuilder(null).build(
            listOf(
                WayLine.of(WayAttrs(WayClass.FOOTWAY, sac = 1), 0.0 to 0.0, 0.0 to 0.002),
                WayLine.of(WayAttrs(WayClass.FOOTWAY), 0.0 to 0.002, 0.0 to 0.003),
            ),
            GraphSource("test", "fp", "", 14, 0),
        )
        val r = Router(g).route(0.0, 0.0, 0.0, 0.003)
        assertIs<RouteResult.Found>(r)
        val p = r.plan
        val all = p.metersByClass.getValue(WayClass.FOOTWAY)
        val graded = p.gradedMetersByClass.getValue(WayClass.FOOTWAY)
        assertEquals(2.0 / 3.0, graded / all, 0.01)
        assertEquals(TripKind.BACKCOUNTRY, TripKinds.of(p).kind)
        assertTrue("gradedMetersByClass" in p.json(false))
    }
}
