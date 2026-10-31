package io.github.jasondorjeshort.dystopo.app.routing

import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.sync.SettingsSync
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.routing.WaterBasis
import io.github.jasondorjeshort.dystopo.routing.WaterClass
import io.github.jasondorjeshort.dystopo.routing.WaterItem
import io.github.jasondorjeshort.dystopo.routing.WaterKind
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T187: the words of water along a route on the phone ([WaterText]): the route card's line and the longest stretch, the
 * list's rows with the gap to the next, the navigation card's next water, and when the spoken line is due (only the last
 * source before a stretch of more than 5 km, once, within its lead); and the two settings' sync.
 */
class RouteWaterTest {
    private val m = ContourUnits.METERS

    private fun item(
        alongM: Double, kind: WaterKind = WaterKind.STREAM, cls: WaterClass? = WaterClass.LIKELY_FLOWING, name: String? = null,
        basis: WaterBasis = WaterBasis.MAPPED, bridge: Boolean = false, offsetM: Double = 0.0,
    ) = WaterItem(alongM, kind, if (kind == WaterKind.STREAM) cls else null, basis, name, null, bridge, false, false, null, offsetM, 46.79, -121.74)

    /** A 14 km walk: a gully, a flowing creek at 1.2 km, a seasonal one, a spring at 3 km, then nothing until a lake at 9.5 km. */
    private val walk = listOf(
        item(400.0, cls = WaterClass.USUALLY_DRY, basis = WaterBasis.TERRAIN),
        item(1_200.0, name = "Paradise River", bridge = true),
        item(2_100.0, cls = WaterClass.MAY_RUN_DRY),
        item(3_000.0, WaterKind.SPRING, offsetM = 60.0),
        item(9_500.0, WaterKind.LAKE),
    )
    private val lengthM = 14_000.0

    @Test
    fun theCardLineNamesTheCountAndTheFirstSource() {
        assertEquals("Water: 5 places, next likely flowing in 1.20 km", WaterText.cardLine(walk, m))
        assertEquals("Water: 2 crossings, next likely flowing in 1.20 km", WaterText.cardLine(walk.take(2), m))
        assertEquals("Water: 1 crossing, none likely flowing", WaterText.cardLine(walk.take(1), m))
        assertEquals("Water: none found along the route", WaterText.cardLine(emptyList(), m))
        assertEquals("Water: 1 place, next a mapped spring in 1.9 mi", WaterText.cardLine(listOf(walk[3]), ContourUnits.FEET))
        assertEquals("Longest stretch without water: 6.50 km", WaterText.longestLine(walk, lengthM, m))
        assertNull(WaterText.longestLine(walk.take(1), lengthM, m), "no source at all: the card line says so")
    }

    @Test
    fun eachRowHasItsDistanceClassBasisAndTheGapToTheNext() {
        assertEquals("1. 400 m  Gully, usually dry (from terrain) · next 800 m", WaterText.row(walk, 0, lengthM, m))
        assertEquals("2. 1.20 km  Stream, likely flowing (mapped) · Paradise River · bridge · next 900 m", WaterText.row(walk, 1, lengthM, m))
        assertEquals("4. 3.00 km  Mapped spring (mapped) · 60 m off the route · next 6.50 km", WaterText.row(walk, 3, lengthM, m))
        assertEquals("5. 9.50 km  Lake or pond (mapped) · end 4.50 km", WaterText.row(walk, 4, lengthM, m))
    }

    @Test
    fun theNavigationCardSkipsDryGulliesAndKeepsAWaterItIsAt() {
        assertEquals(1, WaterText.nextOnCard(walk, 0.0), "the gully is not named")
        assertEquals(1, WaterText.nextOnCard(walk, 1_220.0), "20 m past it: still the creek")
        assertEquals(2, WaterText.nextOnCard(walk, 1_300.0))
        assertEquals(-1, WaterText.nextOnCard(walk, 9_600.0))
        assertEquals("Water in 900 m: Stream, may run dry", WaterText.navLine(walk[2], 900.0, m))
        assertEquals("Water here: Mapped spring", WaterText.navLine(walk[3], 10.0, m))
    }

    @Test
    fun theSpokenLineIsTheLastSourceBeforeALongStretchOnce() {
        val lead = WaterText.leadM(TravelMode.HIKE)
        assertEquals(400.0, lead)
        val said = HashSet<Int>()
        assertEquals(-1, WaterText.dueSpoken(walk, lengthM, 0.0, lead, said), "the creek and the seasonal stream are not before a long stretch")
        assertEquals(-1, WaterText.dueSpoken(walk, lengthM, 2_500.0, lead, said), "500 m before the spring: outside the lead")
        assertEquals(3, WaterText.dueSpoken(walk, lengthM, 2_650.0, lead, said), "within 400 m of the spring, 6.5 km dry after it")
        said += 3
        assertEquals(-1, WaterText.dueSpoken(walk, lengthM, 2_900.0, lead, said), "said once")
        assertEquals(-1, WaterText.dueSpoken(walk, lengthM, 9_300.0, lead, said), "the lake has 4.5 km to the end: not long")
        // A walker who passed the spring before the line was due does not hear it late.
        assertEquals(-1, WaterText.dueSpoken(walk, lengthM, 3_100.0, lead, HashSet()))
        assertEquals(
            "In 350 m, water: mapped spring. Then none for 6.50 km.",
            WaterText.spoken(walk[3], 350.0, 6_500.0, toEnd = false, units = m),
        )
        assertEquals("Water here: lake or pond. Then none to the end, 6.00 km.", WaterText.spoken(walk[4], 0.0, 6_000.0, toEnd = true, units = m))
    }

    @Test
    fun noWordClaimsTheWaterIsThereOrDrinkable() {
        val texts = walk.indices.map { WaterText.row(walk, it, lengthM, m) } + WaterText.cardLine(walk, m) + WaterText.HINT +
            WaterText.spoken(walk[3], 350.0, 6_500.0, false, m)
        for (t in texts) for (w in listOf("reliable", "safe", "drinking", "potable")) assertFalse(w in t.lowercase(), "'$w' in: $t")
    }

    @Test
    fun theTwoSettingsSyncAndParse() {
        val keys = listOf(SettingsSync.WATER_ALONG_ROUTE, SettingsSync.WATER_SPOKEN)
        for (k in keys) assertTrue(k in SettingsRoutes.KEYS, k)
        val synced = keys.map { SettingsSync.syncedKey(it) }
        assertEquals(listOf("app.waterAlongRoute", "app.waterSpoken"), synced)
        var clock = 1_000L
        val a = InMemoryTrackStore("aaaa", wallClock = { clock })
        val b = InMemoryTrackStore("bbbb", wallClock = { clock })
        assertEquals(2, SettingsSync.write(a, synced, mapOf("app.waterAlongRoute" to "off", "app.waterSpoken" to "on"), clock))
        clock += 1_000
        b.apply(a.bundle(b.vector()), clock)
        val plan = SettingsSync.plan(b.settings(), mapOf("app.waterAlongRoute" to "on", "app.waterSpoken" to "off"), SettingsRoutes.KEYS)
        val parsed = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params))
        assertEquals(false, parsed.waterAlongRoute)
        assertEquals(true, parsed.waterSpoken)
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("waterSpoken" to "loud")))
        assertNull(assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("units" to "m"))).waterAlongRoute)
    }
}
