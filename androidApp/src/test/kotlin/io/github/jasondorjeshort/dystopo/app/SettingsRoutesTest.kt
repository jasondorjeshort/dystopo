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
        assertEquals(60, p.navStaleS)
        assertEquals(5000, p.navStartM)
        assertEquals(20, p.navSnoozeMin)
        assertEquals(null, ok("units" to "m").navStaleS)
        bad("navStaleS" to "2")
        bad("navStaleS" to "abc")
        bad("navStartM" to 60_000)
        bad("navSnoozeMin" to 0)
        bad("navSnoozeMin" to "241")
    }

    @Test
    fun spokenDirectionsAreAFlag() { // T126
        assertEquals(true, ok("navSpeech" to "1").navSpeech)
        assertEquals(false, ok("navSpeech" to "off").navSpeech)
        assertEquals(true, ok("navSpeech" to "on").navSpeech, "the synced value (on or off) parses too")
        assertNull(ok("units" to "m").navSpeech)
        assertTrue(bad("navSpeech" to "loud").message.contains("navSpeech"))
    }

    @Test
    fun paceKeysAreAFlagAFitAndAnInstant() { // T164
        assertEquals(true, ok("paceStandard" to "on").paceStandard)
        assertEquals(false, ok("paceStandard" to "0").paceStandard)
        val fit = "1;hike:1.0734,2.8029,-0.0575,5,22788,0.057"
        assertEquals(fit, ok("paceFit" to fit).paceFit)
        assertEquals("", ok("paceFit" to "").paceFit, "a reset clears it")
        assertTrue(bad("paceFit" to "2;hike:1,2,0,5,30000,0").message.contains("paceFit"), "another version is refused, so a pull skips it")
        bad("paceFit" to "1;hike:fast")
        assertEquals(1_790_000_000_000L, ok("paceSince" to "1790000000000").paceSince)
        bad("paceSince" to "-1")
        bad("paceSince" to "yesterday")
        assertNull(ok("units" to "m").paceFit)
    }

    @Test
    fun theTurnAroundDeadlineAndMargin() { // T163: sunset, civil or a typed time; never off; the margin in whole minutes
        assertEquals("sunset", ok("turnDeadline" to "sunset").turnDeadline)
        assertEquals("civil", ok("turnDeadline" to "Civil").turnDeadline)
        assertEquals("07:30", ok("turnDeadline" to "7:30").turnDeadline, "a typed time is stored as HH:MM")
        assertNull(ok("units" to "m").turnDeadline)
        assertTrue(bad("turnDeadline" to "off").message.contains("turnDeadline"), "the clock cannot be switched off")
        bad("turnDeadline" to "25:00")
        assertEquals(30, ok("turnMarginMin" to "30").turnMarginMin)
        assertEquals(0, ok("turnMarginMin" to 0).turnMarginMin)
        assertEquals(45, ok("turnMarginMin" to "45").turnMarginMin, "a typed value outside the presets")
        bad("turnMarginMin" to "-5")
        bad("turnMarginMin" to "241")
        bad("turnMarginMin" to "soon")
    }

    @Test
    fun theReadoutOptionsAndTheBarometerSwitch() { // T161: whole values per activity and entry, the rows' edits, the switch
        assertEquals("shown=0;pace=1;damping=medium;pm=0;knots=0", ok("readout.foot.speed" to "pace=1").readoutValues["readout.foot.speed"]) // T177: speed is off by default on foot (the bar shows elevation and climb)
        assertEquals("shown=0;pace=0;damping=high;pm=1;knots=0", ok("readout.air.climb" to "shown=0;damping=high;pm=1").readoutValues["readout.air.climb"])
        assertEquals(mapOf(io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.COURSE to "shown=0"), ok("readout.course" to "shown=0").readoutEdits)
        assertEquals(false, ok("readoutBarometer" to "0").readoutBarometer)
        assertEquals(true, ok("readoutBarometer" to "on").readoutBarometer, "the synced value is on or off")
        assertNull(ok("units" to "m").readoutBarometer)
        assertTrue(ok("units" to "m").readoutEdits.isEmpty())
        assertTrue(bad("readout.foot.speed" to "damping=wild").message.contains("readout.foot.speed"))
        bad("readout.speed" to "shown=2")
        bad("readout.speed" to "fast")
        bad("readout.skate.speed" to "shown=1")
        bad("readoutBarometer" to "maybe")
    }

    @Test
    fun thePositionButtonSettings() { // T176: the layout 1, 2 or 3; the close zoom in whole levels; the lock, synced as on or off
        assertEquals(2, ok("mapControls" to "2").mapControls)
        assertEquals(3, ok("mapControls" to 3).mapControls)
        assertEquals(15, ok("recenterZoom" to "15").recenterZoom)
        assertEquals(true, ok("orientationLock" to "1").orientationLock)
        assertEquals(false, ok("orientationLock" to "off").orientationLock, "the synced value is on or off")
        val none = ok("units" to "m")
        assertNull(none.mapControls)
        assertNull(none.recenterZoom)
        assertNull(none.orientationLock)
        assertTrue(bad("mapControls" to "4").message.contains("mapControls"))
        bad("mapControls" to "0")
        bad("recenterZoom" to "9")
        bad("recenterZoom" to "19")
        bad("recenterZoom" to "close")
        bad("orientationLock" to "north")
    }

    @Test
    fun thePressureFallWarning() { // T178: the four choices by code, label or threshold
        assertEquals(io.github.jasondorjeshort.dystopo.readout.FallWarning.MORE, ok("pressureFallWarning" to "more").pressureFallWarning)
        assertEquals(io.github.jasondorjeshort.dystopo.readout.FallWarning.FEWER, ok("pressureFallWarning" to "6").pressureFallWarning)
        assertEquals(io.github.jasondorjeshort.dystopo.readout.FallWarning.OFF, ok("pressureFallWarning" to "off").pressureFallWarning)
        assertEquals(io.github.jasondorjeshort.dystopo.readout.FallWarning.STANDARD, ok("pressureFallWarning" to "Standard").pressureFallWarning)
        assertEquals(null, ok("units" to "m").pressureFallWarning)
        assertTrue(bad("pressureFallWarning" to "5").message.contains("pressureFallWarning"))
    }

    @Test
    fun theViewRange() { // T182: 10, 30 or 100 km, synced as the number
        assertEquals(10, ok("viewRange" to "10").viewRange)
        assertEquals(100, ok("viewRange" to 100).viewRange)
        assertEquals(30, ok("viewRange" to "30 km").viewRange)
        assertNull(ok("units" to "m").viewRange)
        assertTrue(bad("viewRange" to "20").message.contains("viewRange"))
        bad("viewRange" to "30.5")
        bad("viewRange" to "far")
        assertTrue("viewRange" in SettingsRoutes.KEYS)
    }

    @Test
    fun theTimeEstimateForm() { // T184: range (the default) or one number
        assertEquals("range", ok("timeEstimateForm" to "range").timeEstimateForm)
        assertEquals("one", ok("timeEstimateForm" to "One").timeEstimateForm)
        assertEquals(null, ok("units" to "m").timeEstimateForm)
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
}
