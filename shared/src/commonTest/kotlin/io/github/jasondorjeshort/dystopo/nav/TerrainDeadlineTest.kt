package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.TerrainFixture
import io.github.jasondorjeshort.dystopo.tiles.ShadeTiles
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T193: the turn-back deadline from the terrain sunset at the line's end, over synthetic terrain swept as the phone
 * sweeps it ([ShadeTiles.pointAnswer] over [Horizon.terrainAround]): a ridge west of the end moves the deadline earlier
 * and the card shows both sunsets; a summit end keeps the sun past the flat sunset, so the flat one counts; the states
 * before the sweep answers, without data and on another day keep the flat sunset with the reason.
 */
class TerrainDeadlineTest {
    private val h = 3_600_000L
    private val lat = 46.0
    private val lon = -121.0

    /** Noon, local mean solar time, on 28 October 2026 at the point: a late-October day. */
    private val noon = Sun.epochDay(2026, 10, 28) * 86_400_000L + 20 * h + 4 * 60_000L
    private val terrain = DeadlineSetting(DeadlineKind.TERRAIN)

    private fun day(f: TerrainFixture, at: Long = noon): Sun.TerrainDay {
        val t = Horizon.terrainAround(f.lat, f.lon, 14) { w -> f.grid(w) }
        return ShadeTiles.pointAnswer(t, f.lat, f.lon, at).day
    }

    private val clock: (Long) -> String = { ms ->
        val local = ((ms + (lon / 15 * h).toLong()) % 86_400_000L + 86_400_000L) % 86_400_000L / 60_000
        "${local / 60}:" + (local % 60).toString().padStart(2, '0')
    }

    @Test
    fun aRidgeWestOfTheEndGivesAnEarlierDeadline() {
        // A ridge 400 m high, its crest 2 km west of the end: about 11 degrees up toward the setting sun.
        val f = TerrainFixture(lat, lon) { e, _ -> 400 * exp(-((e + 2000) / 300).let { it * it }) }
        val d = day(f)
        val flat = Sun.dayAt(lat, lon, noon).sunsetMs!!
        val dl = Deadline.resolve(terrain, lat, lon, noon, 0, d)
        assertEquals(TerrainSunset.BEHIND, dl.terrain)
        assertEquals(d.sunsetMs, dl.atMs)
        assertEquals(flat, dl.flatSunsetMs)
        assertEquals(d.sunsetMs, dl.terrainSunsetMs)
        val earlierMin = (flat - dl.atMs!!) / 60_000.0
        assertTrue(earlierMin in 45.0..120.0, "the ridge takes the sun $earlierMin min before the flat sunset")
        assertEquals(Deadline.BEHIND_RIDGE, dl.label)

        // The clock counts to it, and the card shows both times.
        val t = TurnAround(NavPath("out and back", listOf(NavPoint(lat, lon, 1600.0), NavPoint(lat + 0.02, lon, 1600.0), NavPoint(lat, lon + 0.00002, 1600.0))), TurnPace(false) { l, _, _ -> l / 1.25 })
        val c = t.clock(1000.0, noon, dl, 30)
        assertEquals((dl.atMs!! - 30 * 60_000L - (t.retraceS(1000.0) * 1000).toLong()).toDouble(), c.turnBackMs!!.toDouble(), 1000.0)
        assertEquals("Sun behind the ridge ${clock(dl.atMs!!)}, flat horizon ${clock(flat)}, 30 min margin, standard pace", c.label(clock))
        assertTrue(c.spoken(clock).endsWith("to be out by the time the sun goes behind the ridge."), c.spoken(clock))
        val json = c.json()["deadline"] as Map<*, *>
        assertEquals("terrain", json["kind"])
        assertEquals("behind", json["terrain"])
        assertEquals(flat, json["flatSunsetMs"])
    }

    @Test
    fun aSummitEndKeepsTheFlatSunset() {
        // A cone 1500 m high, the end on its top: the horizon dips below level, so the sun leaves the terrain after
        // the flat sunset and the flat one counts.
        val f = TerrainFixture(lat, lon) { e, n -> maxOf(0.0, 1500 - 0.3 * hypot(e, n)) }
        val d = day(f)
        val flat = Sun.dayAt(lat, lon, noon).sunsetMs!!
        assertTrue(d.sunsetMs!! > flat, "the summit's terrain sunset ${d.sunsetMs} is after the flat $flat")
        val dl = Deadline.resolve(terrain, lat, lon, noon, 0, d)
        assertEquals(TerrainSunset.LATER, dl.terrain)
        assertEquals(flat, dl.atMs)
        assertEquals(d.sunsetMs, dl.terrainSunsetMs)
        assertEquals("sunset, flat horizon", dl.label)
        val t = TurnAround(NavPath("line", listOf(NavPoint(lat - 0.01, lon, 1000.0), NavPoint(lat, lon, 1500.0))), TurnPace(false) { l, _, _ -> l })
        val c = t.clock(100.0, noon, dl, 30)
        assertEquals("Sunset ${clock(flat)}, flat horizon, terrain sunset later, ${clock(d.sunsetMs!!)}, 30 min margin, standard pace", c.label(clock))
        assertTrue(c.spoken(clock).endsWith("to be out by sunset."))
    }

    @Test
    fun theFlatSunsetUntilTheSweepAnswers() {
        val flat = Sun.dayAt(lat, lon, noon).sunsetMs!!
        val t = TurnAround(NavPath("line", listOf(NavPoint(lat - 0.01, lon, 1000.0), NavPoint(lat, lon, 1500.0))), TurnPace(false) { l, _, _ -> l })
        // Not back yet.
        val pending = Deadline.resolve(terrain, lat, lon, noon, 0)
        assertEquals(TerrainSunset.PENDING, pending.terrain)
        assertEquals(flat, pending.atMs)
        assertNull(pending.terrainSunsetMs)
        assertEquals("Sunset ${clock(flat)}, flat horizon, working out the terrain sunset", t.clock(100.0, noon, pending, 30).deadlineText(clock))
        // No elevation data at the end.
        val none = Deadline.resolve(terrain, lat, lon, noon, 0, noTerrainData = true)
        assertEquals(TerrainSunset.NO_DATA, none.terrain)
        assertEquals(flat, none.atMs)
        assertEquals("Sunset ${clock(flat)}, flat horizon, no elevation data for the terrain sunset", t.clock(100.0, noon, none, 30).deadlineText(clock))
        // Yesterday's answer (a cache across midnight) is not today's: not back yet.
        val ridge = TerrainFixture(lat, lon) { e, _ -> 400 * exp(-((e + 2000) / 300).let { it * it }) }
        val yesterday = day(ridge, noon - 24 * h)
        assertEquals(TerrainSunset.PENDING, Deadline.resolve(terrain, lat, lon, noon, 0, yesterday).terrain)
        // Flat ground: the terrain sunset is the flat one within a minute (the eye's 1.7 m dips the horizon a hair).
        val level = day(TerrainFixture(lat, lon) { _, _ -> 500.0 })
        val dl = Deadline.resolve(terrain, lat, lon, noon, 0, level)
        assertEquals(flat.toDouble(), assertNotNull(dl.atMs).toDouble(), 60_000.0)
        assertEquals(flat.toDouble(), assertNotNull(dl.terrainSunsetMs).toDouble(), 60_000.0)
    }

    @Test
    fun theSunNeverClearingTheTerrainKeepsTheFlatSunset() {
        // A deep pit: walls 3 km high all round, 1 km out.
        val f = TerrainFixture(lat, lon) { e, n -> if (hypot(e, n) > 1000) 3000.0 else 0.0 }
        val d = day(f)
        assertNull(d.sunsetMs)
        val dl = Deadline.resolve(terrain, lat, lon, noon, 0, d)
        assertEquals(TerrainSunset.NO_SUN, dl.terrain)
        assertEquals(Sun.dayAt(lat, lon, noon).sunsetMs, dl.atMs)
    }

    @Test
    fun theSettingAndTheKey() {
        assertEquals(DeadlineKind.TERRAIN, DeadlineSetting.parse("terrain")!!.kind)
        assertEquals(DeadlineKind.TERRAIN, DeadlineSetting.parse("Terrain sunset")!!.kind)
        assertEquals("terrain", DeadlineSetting.parse("terrain")!!.value)
        assertEquals(DeadlineSetting.DEFAULT, "sunset")
        // The key: the same point and date hit, another date or a point a few metres off miss.
        val k = Deadline.terrainKey(46.78512, -121.73498, noon)
        assertEquals(k, Deadline.terrainKey(46.785121, -121.734981, noon + 3 * h))
        assertTrue(k != Deadline.terrainKey(46.78512, -121.73498, noon + 24 * h))
        assertTrue(k != Deadline.terrainKey(46.78522, -121.73498, noon))
        // The sunset kinds carry the flat sunset too (GET /nav/turnaround flatSunsetMs).
        assertEquals(Sun.dayAt(lat, lon, noon).sunsetMs, Deadline.resolve(DeadlineSetting.SUNSET, lat, lon, noon, 0).flatSunsetMs)
    }
}
