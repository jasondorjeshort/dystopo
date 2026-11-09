package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup
import io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout
import io.github.jasondorjeshort.dystopo.app.settings.SettingsRow
import io.github.jasondorjeshort.dystopo.app.sync.SettingsSync
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T203: when the terrain source watches, what users read about it, and its setting. */
class TerrainSourceTest {
    @Test
    fun itWatchesOnFootWithABarometerWhileRecordingOrInTheForeground() {
        assertNull(TerrainSource.whyOff(setting = true, barometer = true, activity = Activity.FOOT, recording = true, foreground = false))
        assertNull(TerrainSource.whyOff(true, true, Activity.FOOT, recording = false, foreground = true))
        assertEquals("background", TerrainSource.whyOff(true, true, Activity.FOOT, recording = false, foreground = false))
        assertEquals("setting-off", TerrainSource.whyOff(false, true, Activity.FOOT, true, true))
        assertEquals("no-barometer", TerrainSource.whyOff(true, false, Activity.FOOT, true, true))
        assertEquals("activity", TerrainSource.whyOff(true, true, Activity.ROAD, true, true))
    }

    @Test
    fun theDiagnosticsLineSaysWhenThePhoneHasNoBarometer() {
        assertEquals("Estimated position", TerrainText.DIAGNOSTICS)
        assertEquals("not available: this phone has no barometer", TerrainText.diagnostics(barometer = false, setting = true))
        assertEquals("on (barometer present)", TerrainText.diagnostics(barometer = true, setting = true))
        assertEquals("off in settings (barometer present)", TerrainText.diagnostics(barometer = true, setting = false))
    }

    @Test
    fun theChipAndTheStateLine() {
        assertEquals("Estimated position, about 80 m", TerrainText.estimating(72.4, ContourUnits.METERS))
        assertEquals("Estimated position, about 240 ft", TerrainText.estimating(72.4, ContourUnits.FEET))
        assertEquals("No position, last known 12 min ago", TerrainText.lost(12 * 60_000L + 5_000))
        assertEquals("No position, last known 40 s ago", TerrainText.lost(45_000))
        val s = RecordingStatus(RecState.WAITING_FOR_GPS, "Walk", 1200.0, ContourUnits.METERS, silentS = 120, estimate = "Estimated position, about 80 m")
        assertEquals("Waiting for GPS: no fix for 2 min. Estimated position, about 80 m", s.controlsLine)
        assertTrue(s.text.endsWith(". Estimated position, about 80 m"), s.text)
        // Poor fixes still coming: the line is the estimate's alone.
        assertEquals("Estimated position, about 80 m", s.copy(state = RecState.RECORDING).controlsLine)
        assertNull(s.copy(state = RecState.RECORDING, estimate = null).controlsLine)
        // No word the survey ruled out.
        for (t in listOf(TerrainText.estimating(50.0, ContourUnits.METERS), TerrainText.lost(1000), TerrainText.diagnostics(true, true))) {
            assertTrue("dead reckoning" !in t.lowercase() && "terrain" !in t.lowercase(), t)
        }
    }

    @Test
    fun theSettingIsARowASyncedKeyAndARouteParameter() {
        assertEquals(SettingsGroup.NAVIGATION, SettingsRow.ESTIMATE_POSITION.group)
        assertEquals("Estimate position when GPS is poor", SettingsRow.ESTIMATE_POSITION.label)
        assertTrue(SettingsRow.ESTIMATE_POSITION in SettingsLayout.rows(SettingsGroup.NAVIGATION))
        assertTrue(AppSettings.KEY_ESTIMATE_POSITION in SettingsRoutes.KEYS)
        assertEquals(false, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("estimatePosition" to "0")))[io.github.jasondorjeshort.dystopo.app.AppSettings.ESTIMATE_POSITION])
        assertEquals(true, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("estimatePosition" to "on")))[io.github.jasondorjeshort.dystopo.app.AppSettings.ESTIMATE_POSITION])
        assertNull(assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("units" to "m")))[io.github.jasondorjeshort.dystopo.app.AppSettings.ESTIMATE_POSITION])
        assertTrue(assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("estimatePosition" to "maybe"))).message.contains("1 or 0"))
        assertEquals("app.estimatePosition", SettingsSync.syncedKey(SettingsSync.ESTIMATE_POSITION))
        // A synced "off" from another phone becomes the route's parameter.
        val plan = SettingsSync.plan(mapOf("app.estimatePosition" to "off"), emptyMap(), SettingsRoutes.KEYS)
        assertEquals("off", plan.params["estimatePosition"])
        assertEquals(false, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params))[io.github.jasondorjeshort.dystopo.app.AppSettings.ESTIMATE_POSITION])
    }
}
