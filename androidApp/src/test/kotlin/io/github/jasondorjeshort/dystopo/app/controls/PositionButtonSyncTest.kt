package io.github.jasondorjeshort.dystopo.app.controls

import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.sync.SettingsSync
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T176: the position button's three settings sync like the other user choices: `app.mapControls`, `app.recenterZoom` and
 * `app.orientationLock` (`on` or `off`) in the log, taken on another device through `POST /settings`'s parser.
 */
class PositionButtonSyncTest {
    @Test
    fun theThreeKeysAreWrittenAndTakenOnAnotherDevice() {
        val keys = listOf(SettingsSync.MAP_CONTROLS, SettingsSync.RECENTER_ZOOM, SettingsSync.ORIENTATION_LOCK)
        for (k in keys) assertTrue(k in SettingsRoutes.KEYS, k)
        val synced = keys.map { SettingsSync.syncedKey(it) }
        assertEquals(listOf("app.mapControls", "app.recenterZoom", "app.orientationLock"), synced)

        var clock = 1_000L
        val a = InMemoryTrackStore("aaaa", wallClock = { clock })
        val b = InMemoryTrackStore("bbbb", wallClock = { clock })
        val values = mapOf("app.mapControls" to "3", "app.recenterZoom" to "15", "app.orientationLock" to SettingsSync.onOff(true))
        assertEquals(3, SettingsSync.write(a, synced, values, clock))
        clock += 1_000
        b.apply(a.bundle(b.vector()), clock)

        val current = mapOf("app.mapControls" to "1", "app.recenterZoom" to "14", "app.orientationLock" to "off")
        val plan = SettingsSync.plan(b.settings(), current, SettingsRoutes.KEYS)
        assertEquals(mapOf<String, Any?>("mapControls" to "3", "recenterZoom" to "15", "orientationLock" to "on"), plan.params)
        val parsed = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params))
        assertEquals(3, parsed.mapControls)
        assertEquals(15, parsed.recenterZoom)
        assertEquals(true, parsed.orientationLock)

        // Already the same: nothing to apply.
        assertTrue(SettingsSync.plan(b.settings(), values, SettingsRoutes.KEYS).params.isEmpty())
    }
}
