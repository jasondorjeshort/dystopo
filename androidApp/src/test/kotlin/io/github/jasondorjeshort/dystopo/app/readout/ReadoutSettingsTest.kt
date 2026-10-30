package io.github.jasondorjeshort.dystopo.app.readout

import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.sync.SettingsSync
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.app.tracking.Replay
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.readout.Damping
import io.github.jasondorjeshort.dystopo.readout.EntryOptions
import io.github.jasondorjeshort.dystopo.readout.ReadoutEntry
import io.github.jasondorjeshort.dystopo.readout.ReadoutOptions
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T161: the readout's settings: one synced value per activity and entry over T160's defaults, the rows' edits, the barometer switch. */
class ReadoutSettingsTest {
    @Test
    fun unsetIsTheDefaultsOfEachActivity() {
        for (a in Activity.entries) assertEquals(ReadoutOptions.defaults(a), ReadoutSettings.options(a, emptyMap(), barometer = true))
        assertFalse(ReadoutSettings.options(Activity.FOOT, emptyMap(), barometer = false).barometer)
        assertEquals(24, ReadoutSettings.KEYS.size)
        assertEquals("readout.boat.speed", ReadoutSettings.key(Activity.BOAT, ReadoutEntry.SPEED))
        assertEquals(Activity.RAIL to ReadoutEntry.ELEVATION, ReadoutSettings.ofKey("readout.rail.elevation"))
        assertNull(ReadoutSettings.ofKey("readout.speed"))
        assertEquals(ReadoutEntry.CLIMB, ReadoutSettings.entryOfEditKey("readout.climb"))
    }

    @Test
    fun aValueSetsOnlyWhatItNamesAndUnknownKeysAreDropped() {
        val base = ReadoutOptions.defaults(Activity.FOOT).speed
        assertEquals(base.copy(pace = true), ReadoutSettings.merge(ReadoutEntry.SPEED, "pace=1", base))
        assertEquals(base.copy(damping = Damping.LOW), ReadoutSettings.merge(ReadoutEntry.SPEED, "damping=low;colour=red", base), "a newer build's key is dropped")
        for (v in listOf("", "colour=red", "shown=2", "damping=wild", "pm=yes")) assertNull(ReadoutSettings.merge(ReadoutEntry.SPEED, v, base), v)
        // Pace and knots are speed's only; pace wins over knots.
        assertEquals(EntryOptions(shown = true), ReadoutSettings.merge(ReadoutEntry.CLIMB, "pace=1;knots=1", EntryOptions(shown = true)))
        assertEquals(base.copy(pace = true), ReadoutSettings.merge(ReadoutEntry.SPEED, "pace=1;knots=1", base))
        assertEquals("shown=1;pace=0;damping=medium;pm=0;knots=1", ReadoutSettings.parseValue(Activity.AIR, ReadoutEntry.SPEED, "knots=1"))
    }

    @Test
    fun storedValuesSitOverTheDefaultsAndKnotsOnlyCountsAloft() {
        val stored = mapOf("readout.air.speed" to "knots=1", "readout.foot.climb" to "shown=0", "readout.foot.speed" to "knots=1")
        val air = ReadoutSettings.options(Activity.AIR, stored, barometer = true)
        assertTrue(air.knots)
        val foot = ReadoutSettings.options(Activity.FOOT, stored, barometer = true)
        assertFalse(foot.climb.shown)
        assertFalse(foot.knots, "knots is offered for boat and air only")
        assertTrue(ReadoutSettings.offersKnots(Activity.BOAT, ReadoutEntry.SPEED))
        assertFalse(ReadoutSettings.offersKnots(Activity.FOOT, ReadoutEntry.SPEED))
        assertFalse(ReadoutSettings.offersKnots(Activity.AIR, ReadoutEntry.CLIMB))
    }

    @Test
    fun theKeysSyncToAnotherDevice() {
        var clock = 1_000L
        val a = InMemoryTrackStore("aaaa", wallClock = { clock })
        val b = InMemoryTrackStore("bbbb", wallClock = { clock })
        val baro = SettingsSync.syncedKey(SettingsSync.READOUT_BAROMETER)
        val speed = SettingsSync.syncedKey(ReadoutSettings.key(Activity.FOOT, ReadoutEntry.SPEED))
        assertEquals("app.readoutBarometer", baro)
        assertEquals("app.readout.foot.speed", speed)
        val values = mapOf(baro to "off", speed to "shown=1;pace=1;damping=medium;pm=0;knots=0")
        assertEquals(2, SettingsSync.write(a, listOf(baro, speed), values, clock))
        clock += 1_000
        b.apply(a.bundle(b.vector()), clock)
        val plan = SettingsSync.plan(b.settings(), mapOf(baro to "on", speed to null), SettingsRoutes.KEYS)
        assertEquals(mapOf<String, Any?>("readoutBarometer" to "off", "readout.foot.speed" to "shown=1;pace=1;damping=medium;pm=0;knots=0"), plan.params)
        val parsed = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params))
        assertEquals(false, parsed.readoutBarometer)
        assertTrue(ReadoutSettings.options(Activity.FOOT, parsed.readoutValues, barometer = true).speed.pace)
    }

    @Test
    fun aReplayCarriesAPressureColumn() {
        val gpx = """<?xml version="1.0"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
            <trkpt lat="46.78" lon="-121.74"><ele>1650</ele><time>2026-10-30T10:00:00Z</time><extensions><pressure>835.5</pressure></extensions></trkpt>
            <trkpt lat="46.7801" lon="-121.74"><ele>1651</ele><time>2026-10-30T10:00:01Z</time></trkpt>
            <trkpt lat="46.7802" lon="-121.74"><ele>1652</ele><time>2026-10-30T10:00:02Z</time><extensions><pressure>5000</pressure></extensions></trkpt>
            </trkseg></trk></gpx>"""
        assertEquals(listOf(835.5, null, null), Replay.pressureColumn(Gpx.parse(gpx).tracks), "hPa, one per point; out of range is no pressure")
    }
}
