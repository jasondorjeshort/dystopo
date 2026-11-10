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
        for (a in Activity.entries) assertEquals(ReadoutOptions.phoneDefaults(a), ReadoutSettings.options(a, emptyMap(), barometer = io.github.jasondorjeshort.dystopo.readout.BarometerMode.AUTO)) // T177: the bar's defaults
        assertTrue(ReadoutSettings.options(Activity.FOOT, emptyMap(), barometer = io.github.jasondorjeshort.dystopo.readout.BarometerMode.NEVER).barometer == io.github.jasondorjeshort.dystopo.readout.BarometerMode.NEVER)
        assertEquals(42, ReadoutSettings.KEYS.size) // T257: seven entries for each of six activities
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
    fun theCourseDegreesNorthAndTheExtendedEntries() { // T257
        val course = ReadoutOptions.phoneDefaults(Activity.BOAT).course
        assertEquals(course.copy(degrees = false), ReadoutSettings.merge(ReadoutEntry.COURSE, "deg=0", course))
        assertEquals(course.copy(magnetic = true), ReadoutSettings.merge(ReadoutEntry.COURSE, "mag=1", course))
        assertNull(ReadoutSettings.merge(ReadoutEntry.COURSE, "mag=2", course))
        assertEquals(EntryOptions(shown = true), ReadoutSettings.merge(ReadoutEntry.SPEED, "deg=0;mag=1", EntryOptions(shown = true)), "degrees and north are the course's only")
        assertEquals("shown=1;pace=0;damping=medium;pm=0;knots=0;mag=1", ReadoutSettings.parseValue(Activity.BOAT, ReadoutEntry.COURSE, "mag=1"))
        // A newer build's key in a stored or synced whole value is kept (ADR 0033); a row's edit drops it.
        assertEquals("shown=1;pace=0;damping=medium;pm=0;knots=0;hue=red", ReadoutSettings.parseValue(Activity.FOOT, ReadoutEntry.SPEED, "shown=1;hue=red"))
        // An old stored composite: T161's four keys only, the course "SSE 163°" true, the pressure shown, the extended off.
        val old = mapOf("readout.foot.course" to "shown=1;pace=0;damping=medium;pm=0;knots=0", "readout.foot.speed" to "shown=1;pace=1;damping=high;pm=0;knots=0")
        val o = ReadoutSettings.options(Activity.FOOT, old, barometer = io.github.jasondorjeshort.dystopo.readout.BarometerMode.AUTO)
        assertTrue(o.course.shown && o.course.degrees && !o.course.magnetic)
        assertTrue(o.pressure.shown)
        assertFalse(o.extended)
        assertEquals("readout.foot.fixAge", ReadoutSettings.key(Activity.FOOT, ReadoutEntry.FIX_AGE))
        assertEquals(ReadoutEntry.PRESSURE, ReadoutSettings.entryOfEditKey("readout.pressure"))
        val ext = ReadoutSettings.EXTENDED.fold(o) { acc, e -> acc.with(e, acc[e].copy(shown = true)) }
        assertTrue(ext.extended)
        assertFalse(ReadoutSettings.offersSmoothing(ReadoutEntry.PRESSURE))
        assertTrue(ReadoutSettings.offersNorth(ReadoutEntry.COURSE))
    }

    @Test
    fun storedValuesSitOverTheDefaultsAndKnotsOnlyCountsAloft() {
        val stored = mapOf("readout.air.speed" to "knots=1", "readout.foot.climb" to "shown=0", "readout.foot.speed" to "knots=1")
        val air = ReadoutSettings.options(Activity.AIR, stored, barometer = io.github.jasondorjeshort.dystopo.readout.BarometerMode.AUTO)
        assertTrue(air.knots)
        val foot = ReadoutSettings.options(Activity.FOOT, stored, barometer = io.github.jasondorjeshort.dystopo.readout.BarometerMode.AUTO)
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
        assertEquals(io.github.jasondorjeshort.dystopo.readout.BarometerMode.NEVER, parsed[io.github.jasondorjeshort.dystopo.app.AppSettings.READOUT_BAROMETER]) // T250: an old peer's off is Never
        assertTrue(ReadoutSettings.options(Activity.FOOT, parsed.readoutValues, barometer = io.github.jasondorjeshort.dystopo.readout.BarometerMode.AUTO).speed.pace)
    }

    @Test
    fun theBarSwitchAndTheClimbWindow() { // T177
        assertEquals(listOf(30, 60, 300, 600, 60, 300, 600, 30), listOf("30", "60", "300", "600", "1min", "5 min", "10min", "30s").map { ReadoutSettings.parseClimbWindow(it) })
        for (bad in listOf(null, "", "45", "2min", "fast", "0")) assertNull(ReadoutSettings.parseClimbWindow(bad), "$bad")
        assertEquals(300, ReadoutSettings.options(Activity.FOOT, emptyMap(), barometer = io.github.jasondorjeshort.dystopo.readout.BarometerMode.AUTO, climbWindowS = 300).climbWindowS)
        assertEquals(60, ReadoutSettings.options(Activity.FOOT, emptyMap(), barometer = io.github.jasondorjeshort.dystopo.readout.BarometerMode.AUTO).climbWindowS)
        val p = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("readoutBar" to "0", "climbWindow" to "5min")))
        assertEquals(false, p[io.github.jasondorjeshort.dystopo.app.AppSettings.READOUT_BAR])
        assertEquals(300, p[io.github.jasondorjeshort.dystopo.app.AppSettings.CLIMB_WINDOW])
        assertEquals(true, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("readoutBar" to "on")))[io.github.jasondorjeshort.dystopo.app.AppSettings.READOUT_BAR], "the synced value is on or off")
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("climbWindow" to "120")))
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("readoutBar" to "maybe")))
        val none = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("units" to "m")))
        assertNull(none[io.github.jasondorjeshort.dystopo.app.AppSettings.READOUT_BAR])
        assertNull(none[io.github.jasondorjeshort.dystopo.app.AppSettings.CLIMB_WINDOW])
        assertTrue(ReadoutSettings.BAR in SettingsRoutes.KEYS && ReadoutSettings.CLIMB_WINDOW in SettingsRoutes.KEYS)
        // Synced: app.readoutBar on/off, app.climbWindow in seconds, applied on the other phone through the same parse.
        var clock = 1_000L
        val a = InMemoryTrackStore("aaaa", wallClock = { clock })
        val b = InMemoryTrackStore("bbbb", wallClock = { clock })
        val barKey = SettingsSync.syncedKey(SettingsSync.READOUT_BAR)
        val winKey = SettingsSync.syncedKey(SettingsSync.CLIMB_WINDOW)
        assertEquals(listOf("app.readoutBar", "app.climbWindow"), listOf(barKey, winKey))
        assertEquals(2, SettingsSync.write(a, listOf(barKey, winKey), mapOf(barKey to "off", winKey to "300"), clock))
        clock += 1_000
        b.apply(a.bundle(b.vector()), clock)
        val plan = SettingsSync.plan(b.settings(), mapOf(barKey to "on", winKey to "60"), SettingsRoutes.KEYS)
        val synced = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params))
        assertEquals(false, synced[io.github.jasondorjeshort.dystopo.app.AppSettings.READOUT_BAR])
        assertEquals(300, synced[io.github.jasondorjeshort.dystopo.app.AppSettings.CLIMB_WINDOW])
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
