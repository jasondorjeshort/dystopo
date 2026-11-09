package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRoutesTest {
    private fun ok(vararg p: Pair<String, Any?>) = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf(*p)))
    private fun bad(vararg p: Pair<String, Any?>) = assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf(*p)))

    @Test
    fun textScaleOutsideTheRangeIsRefusedNotClamped() {
        assertEquals(1.6, ok("textScale" to "1.6").textScale)
        assertEquals(3.0, ok("textScale" to 3).textScale)
        assertTrue(bad("textScale" to "9").message.contains("between"))
        bad("textScale" to "0.1")
        bad("textScale" to "NaN")
        bad("textScale" to "big")
    }

    @Test
    fun unitsParseAndCanBeCleared() {
        assertEquals(ContourUnits.METERS, ok("units" to "m").units)
        assertEquals(ContourUnits.METERS, ok("units" to "metres").units)
        assertEquals(ContourUnits.FEET, ok("units" to "ft").units)
        val cleared = ok("units" to "unset")
        assertTrue(cleared.unitsGiven)
        assertNull(cleared.units)
        assertFalse(ok("textScale" to "1").unitsGiven)
        bad("units" to "furlongs")
    }

    @Test
    fun warmupSwitchAndUnknownKeys() {
        assertEquals(false, ok("warmup" to "0").warmup)
        assertEquals(true, ok("warmup" to true).warmup)
        bad("warmup" to "maybe")
        bad("textscale" to "1.2")
    }

    @Test
    fun mapOptionsAreValidatedAndPassedThrough() {
        val o = ok("hillshade" to "multi", "slope" to "on", "night" to "1", "trails" to "off", "contourInterval" to "100")
        assertEquals(mapOf("hillshade" to "multi", "slope" to "on", "night" to "1", "trails" to "off", "contourInterval" to "100"), o.map)
        assertTrue(ok("textScale" to "1.2").map.isEmpty())
        assertTrue(bad("hillshade" to "loud").message.contains("hillshade"))
        bad("hillshadeIntensity" to "2")
        bad("contourInterval" to "33")
        // The interval is checked against the units in the same request, else the current ones.
        bad("contourInterval" to "40", "units" to "m")
        ok("contourInterval" to "40", "units" to "ft")
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("contourInterval" to "40"), ContourUnits.METERS))
        assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("contourInterval" to "50"), ContourUnits.METERS))
    }

    @Test
    fun cacheCapsAreWholeMegabytesInRange() {
        assertEquals(512L, ok("onlineCacheMb" to "512").onlineCacheMb)
        assertEquals(64L, ok("contourCacheMb" to 64).contourCacheMb)
        assertNull(ok("textScale" to "1").onlineCacheMb)
        assertTrue(bad("onlineCacheMb" to "1").message.contains("between"))
        bad("contourCacheMb" to "100000")
        bad("contourCacheMb" to "lots")
    }

    @Test
    fun navThresholdsAreWholeNumbersInRange() { // T91
        val p = ok("navStaleS" to "60", "navStartM" to 5000, "navSnoozeMin" to "20")
        assertEquals(60, p[AppSettings.NAV_STALE_S])
        assertEquals(5000, p[AppSettings.NAV_START_M])
        assertEquals(20, p[AppSettings.NAV_SNOOZE_MIN])
        assertEquals(null, ok("units" to "m")[AppSettings.NAV_STALE_S])
        bad("navStaleS" to "2")
        bad("navStaleS" to "abc")
        bad("navStartM" to 60_000)
        bad("navSnoozeMin" to 0)
        bad("navSnoozeMin" to "241")
    }

    @Test
    fun spokenDirectionsAreAFlag() { // T126
        assertEquals(true, ok("navSpeech" to "1")[AppSettings.NAV_SPEECH])
        assertEquals(false, ok("navSpeech" to "off")[AppSettings.NAV_SPEECH])
        assertEquals(true, ok("navSpeech" to "on")[AppSettings.NAV_SPEECH], "the synced value (on or off) parses too")
        assertNull(ok("units" to "m")[AppSettings.NAV_SPEECH])
        assertTrue(bad("navSpeech" to "loud").message.contains("navSpeech"))
    }

    @Test
    fun paceKeysAreAFlagAFitAndAnInstant() { // T164
        assertEquals(true, ok("paceStandard" to "on")[AppSettings.PACE_STANDARD])
        assertEquals(false, ok("paceStandard" to "0")[AppSettings.PACE_STANDARD])
        val fit = "1;hike:1.0734,2.8029,-0.0575,5,22788,0.057"
        assertEquals(fit, ok("paceFit" to fit)[AppSettings.PACE_FIT])
        assertEquals("", ok("paceFit" to "")[AppSettings.PACE_FIT], "a reset clears it")
        assertTrue(bad("paceFit" to "2;hike:1,2,0,5,30000,0").message.contains("paceFit"), "another version is refused, so a pull skips it")
        bad("paceFit" to "1;hike:fast")
        assertEquals(1_790_000_000_000L, ok("paceSince" to "1790000000000")[AppSettings.PACE_SINCE])
        bad("paceSince" to "-1")
        bad("paceSince" to "yesterday")
        assertNull(ok("units" to "m")[AppSettings.PACE_FIT])
    }

    @Test
    fun theTurnAroundDeadlineAndMargin() { // T163: sunset, civil or a typed time; never off; the margin in whole minutes
        assertEquals("sunset", ok("turnDeadline" to "sunset").turnDeadline)
        assertEquals("civil", ok("turnDeadline" to "Civil").turnDeadline)
        assertEquals("07:30", ok("turnDeadline" to "7:30").turnDeadline, "a typed time is stored as HH:MM")
        assertNull(ok("units" to "m").turnDeadline)
        assertTrue(bad("turnDeadline" to "off").message.contains("turnDeadline"), "the clock cannot be switched off")
        bad("turnDeadline" to "25:00")
        assertEquals(30, ok("turnMarginMin" to "30")[AppSettings.TURN_MARGIN_MIN])
        assertEquals(0, ok("turnMarginMin" to 0)[AppSettings.TURN_MARGIN_MIN])
        assertEquals(45, ok("turnMarginMin" to "45")[AppSettings.TURN_MARGIN_MIN], "a typed value outside the presets")
        bad("turnMarginMin" to "-5")
        bad("turnMarginMin" to "241")
        bad("turnMarginMin" to "soon")
    }

    @Test
    fun theReadoutOptionsAndTheBarometerSwitch() { // T161: whole values per activity and entry, the rows' edits, the switch
        assertEquals("shown=0;pace=1;damping=medium;pm=0;knots=0", ok("readout.foot.speed" to "pace=1").readoutValues["readout.foot.speed"]) // T177: speed is off by default on foot (the bar shows elevation and climb)
        assertEquals("shown=0;pace=0;damping=high;pm=1;knots=0", ok("readout.air.climb" to "shown=0;damping=high;pm=1").readoutValues["readout.air.climb"])
        assertEquals(mapOf(io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.COURSE to "shown=0"), ok("readout.course" to "shown=0").readoutEdits)
        assertEquals(false, ok("readoutBarometer" to "0")[AppSettings.READOUT_BAROMETER])
        assertEquals(true, ok("readoutBarometer" to "on")[AppSettings.READOUT_BAROMETER], "the synced value is on or off")
        assertNull(ok("units" to "m")[AppSettings.READOUT_BAROMETER])
        assertTrue(ok("units" to "m").readoutEdits.isEmpty())
        assertTrue(bad("readout.foot.speed" to "damping=wild").message.contains("readout.foot.speed"))
        bad("readout.speed" to "shown=2")
        bad("readout.speed" to "fast")
        bad("readout.skate.speed" to "shown=1")
        bad("readoutBarometer" to "maybe")
    }

    @Test
    fun thePositionButtonSettings() { // T176: the layout 1, 2 or 3; the close zoom in whole levels; the lock, synced as on or off
        assertEquals(2, ok("mapControls" to "2")[AppSettings.MAP_CONTROLS])
        assertEquals(3, ok("mapControls" to 3)[AppSettings.MAP_CONTROLS])
        assertEquals(15, ok("recenterZoom" to "15")[AppSettings.RECENTER_ZOOM])
        assertEquals(true, ok("orientationLock" to "1")[AppSettings.ORIENTATION_LOCK])
        assertEquals(false, ok("orientationLock" to "off")[AppSettings.ORIENTATION_LOCK], "the synced value is on or off")
        val none = ok("units" to "m")
        assertNull(none[AppSettings.MAP_CONTROLS])
        assertNull(none[AppSettings.RECENTER_ZOOM])
        assertNull(none[AppSettings.ORIENTATION_LOCK])
        assertTrue(bad("mapControls" to "4").message.contains("mapControls"))
        bad("mapControls" to "0")
        bad("recenterZoom" to "9")
        bad("recenterZoom" to "19")
        bad("recenterZoom" to "close")
        bad("orientationLock" to "north")
    }

    @Test
    fun thePressureFallWarning() { // T178: the four choices by code, label or threshold
        assertEquals(io.github.jasondorjeshort.dystopo.readout.FallWarning.MORE, ok("pressureFallWarning" to "more")[AppSettings.PRESSURE_FALL_WARNING])
        assertEquals(io.github.jasondorjeshort.dystopo.readout.FallWarning.FEWER, ok("pressureFallWarning" to "6")[AppSettings.PRESSURE_FALL_WARNING])
        assertEquals(io.github.jasondorjeshort.dystopo.readout.FallWarning.OFF, ok("pressureFallWarning" to "off")[AppSettings.PRESSURE_FALL_WARNING])
        assertEquals(io.github.jasondorjeshort.dystopo.readout.FallWarning.STANDARD, ok("pressureFallWarning" to "Standard")[AppSettings.PRESSURE_FALL_WARNING])
        assertEquals(null, ok("units" to "m")[AppSettings.PRESSURE_FALL_WARNING])
        assertTrue(bad("pressureFallWarning" to "5").message.contains("pressureFallWarning"))
    }

    @Test
    fun theViewRange() { // T182: 10, 30 or 100 km, synced as the number
        assertEquals(10, ok("viewRange" to "10")[AppSettings.VIEW_RANGE])
        assertEquals(100, ok("viewRange" to 100)[AppSettings.VIEW_RANGE])
        assertEquals(30, ok("viewRange" to "30 km")[AppSettings.VIEW_RANGE])
        assertNull(ok("units" to "m")[AppSettings.VIEW_RANGE])
        assertTrue(bad("viewRange" to "20").message.contains("viewRange"))
        bad("viewRange" to "30.5")
        bad("viewRange" to "far")
        assertTrue("viewRange" in SettingsRoutes.KEYS)
    }

    @Test
    fun theTimeEstimateForm() { // T184: range (the default) or one number
        assertEquals("range", ok("timeEstimateForm" to "range")[AppSettings.TIME_ESTIMATE_FORM])
        assertEquals("one", ok("timeEstimateForm" to "One")[AppSettings.TIME_ESTIMATE_FORM])
        assertEquals(null, ok("units" to "m")[AppSettings.TIME_ESTIMATE_FORM])
        assertTrue(bad("timeEstimateForm" to "both").message.contains("timeEstimateForm"))
        assertTrue("timeEstimateForm" in SettingsRoutes.KEYS)
    }

    @Test
    fun mapRequestsAndDebugRequestsGoToDifferentPools() {
        fun req(path: String) = Request("GET", path, emptyMap(), ByteArray(0))
        for (p in listOf("/dem/12/1/2.png", "/osm/1/0/0.pbf", "/contours/3/1/1.pbf", "/glyphs/Noto Sans Regular/0-255.pbf", "/style.json", "/sprite@2x.png", "/shade/12/1/2.png")) {
            assertTrue(Routes.isMapRequest(req(p)), p)
        }
        for (p in listOf("/health", "/screenshot", "/stats", "/settings", "/features", "/tracks", "/camera", "/shade")) {
            assertFalse(Routes.isMapRequest(req(p)), p)
        }
    }

    // T193: the terrain sunset as a fourth choice of the deadline row.
    @Test
    fun theTerrainDeadline() {
        assertEquals("terrain", ok("turnDeadline" to "terrain").turnDeadline)
        assertEquals("terrain", ok("turnDeadline" to "Terrain sunset").turnDeadline)
        assertTrue(bad("turnDeadline" to "ridge").message.contains("terrain"), "the error names the new choice")
        assertEquals("sunset", io.github.jasondorjeshort.dystopo.nav.DeadlineSetting.DEFAULT, "the default stays sunset (ADR 0044)")
    }
    // end T193

    // T194: the spoken terrain shade line's three choices.
    @Test
    fun theShadeSpokenSetting() {
        assertEquals(io.github.jasondorjeshort.dystopo.routing.ShadeSpoken.HOT, ok("shadeSpoken" to "hot")[AppSettings.SHADE_SPOKEN])
        assertEquals(io.github.jasondorjeshort.dystopo.routing.ShadeSpoken.HOT, ok("shadeSpoken" to "On hot days")[AppSettings.SHADE_SPOKEN])
        assertEquals(io.github.jasondorjeshort.dystopo.routing.ShadeSpoken.ALWAYS, ok("shadeSpoken" to "always")[AppSettings.SHADE_SPOKEN])
        assertEquals(io.github.jasondorjeshort.dystopo.routing.ShadeSpoken.OFF, ok("shadeSpoken" to "Off")[AppSettings.SHADE_SPOKEN])
        assertEquals(null, ok("units" to "m")[AppSettings.SHADE_SPOKEN])
        assertTrue(bad("shadeSpoken" to "sometimes").message.contains("off, hot or always"))
        assertTrue("shadeSpoken" in SettingsRoutes.KEYS)
        assertEquals(io.github.jasondorjeshort.dystopo.routing.ShadeSpoken.HOT, io.github.jasondorjeshort.dystopo.routing.ShadeSpoken.DEFAULT, "the default is On hot days (ADR 0044)")
    }
    // end T194

    // T213: the trail disagreements' layer switch, on by default, synced.
    @Test
    fun theShowDivergenceSetting() {
        assertEquals(false, ok("showDivergence" to "0")[AppSettings.SHOW_DIVERGENCE])
        assertEquals(true, ok("showDivergence" to "on")[AppSettings.SHOW_DIVERGENCE])
        assertEquals(null, ok("units" to "m")[AppSettings.SHOW_DIVERGENCE])
        assertTrue(bad("showDivergence" to "maybe").message.contains("1 or 0"))
        assertTrue(AppSettings.KEY_SHOW_DIVERGENCE in SettingsRoutes.KEYS)
        assertEquals("app.showDivergence", io.github.jasondorjeshort.dystopo.app.sync.SettingsSync.syncedKey(io.github.jasondorjeshort.dystopo.app.sync.SettingsSync.SHOW_DIVERGENCE))
    }
    // end T213
}
