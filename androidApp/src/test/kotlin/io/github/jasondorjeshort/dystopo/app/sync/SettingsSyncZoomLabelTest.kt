package io.github.jasondorjeshort.dystopo.app.sync

import io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T346: the zoom label's switch ("Show zoom level") syncs as `app.scaleBar.zoom` (`on` or `off`) beside the scale bar's
 * both-units choice (T110, [SettingsSyncBothUnitsTest]); a second device takes it as a scale bar setting, outside
 * `POST /settings`. A log that never held it changes nothing, so an install where nobody touched the row keeps following
 * its build's default.
 */
class SettingsSyncZoomLabelTest {
    private val key = "app.scaleBar.zoom"

    @Test
    fun theZoomLabelKeyIsWrittenAndReadOnAnotherDevice() {
        var clock = 1_000L
        val a = InMemoryTrackStore("aaaa", wallClock = { clock })
        val b = InMemoryTrackStore("bbbb", wallClock = { clock })
        assertEquals(key, SettingsSync.syncedKey(SettingsSync.SCALE_BAR_ZOOM))
        assertTrue(SettingsSync.SCALE_BAR_ZOOM in SettingsSync.LOCAL_KEYS)

        // Device A turns the label off (the Map options row or POST /scalebar?zoom=off): one setting operation.
        val nowA = SettingsSync.localValues(bothUnits = false, showZoom = false)
        assertEquals("off", nowA[key])
        assertEquals(1, SettingsSync.write(a, listOf(key), nowA, clock))
        assertEquals(0, SettingsSync.write(a, listOf(key), nowA, clock), "the same value again writes nothing")

        // B merges A's log; its label is on (the default), so the pull turns it off on B.
        clock += 1_000
        b.apply(a.bundle(b.vector()), clock)
        val plan = SettingsSync.plan(b.settings(), SettingsSync.localValues(bothUnits = false, showZoom = true), routeKeys = setOf("units"))
        assertEquals(mapOf(SettingsSync.SCALE_BAR_ZOOM to false), plan.local)
        assertTrue(plan.params.isEmpty() && plan.skipped.isEmpty())

        // B turns it back on: last writer wins on A.
        clock += 1_000
        assertEquals(1, SettingsSync.write(b, listOf(key), SettingsSync.localValues(bothUnits = false, showZoom = true), clock))
        a.apply(b.bundle(a.vector()), clock)
        assertEquals("on", a.settings()[key])
        assertEquals(mapOf(SettingsSync.SCALE_BAR_ZOOM to true), SettingsSync.plan(a.settings(), nowA, setOf("units")).local)
    }

    @Test
    fun anUntouchedInstallKeepsTheDefault() {
        // The current values carry the default when nothing was chosen; a log without the key sets nothing.
        val current = SettingsSync.localValues(bothUnits = false)
        assertEquals(SettingsSync.onOff(ScaleBarSettings.ZOOM_LABEL_DEFAULT), current[key])
        assertTrue(SettingsSync.plan(mapOf("app.units" to "m"), current + ("app.units" to "m"), setOf("units")).local.isEmpty())
        // Each scale bar key is applied on its own: a log with both units only leaves the label alone.
        val both = SettingsSync.plan(mapOf("app.scaleBar.both" to "on"), current, setOf("units"))
        assertEquals(mapOf(SettingsSync.SCALE_BAR_BOTH to true), both.local)
    }

    @Test
    fun aValueThisBuildDoesNotTakeIsSkipped() {
        val plan = SettingsSync.plan(mapOf(key to "sometimes"), SettingsSync.localValues(bothUnits = false), setOf("units"))
        assertTrue(plan.local.isEmpty())
        assertEquals(listOf(key), plan.skipped)
    }
}
