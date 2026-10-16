package io.github.jasondorjeshort.dystopo.app.sync

import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T110 (T101's follow-up): the scale bar's both-units choice joins the synced settings. It is written to the log as
 * `app.scaleBar.both` (`on` or `off`) and a second device that merges the log takes it as a scale bar setting, not a
 * `POST /settings` key; a value it does not understand is skipped.
 */
class SettingsSyncBothUnitsTest {
    private val key = "app.scaleBar.both"

    @Test
    fun theBothUnitsKeyIsWrittenAndReadOnAnotherDevice() {
        var clock = 1_000L
        val a = InMemoryTrackStore("aaaa", wallClock = { clock })
        val b = InMemoryTrackStore("bbbb", wallClock = { clock })
        assertEquals(key, SettingsSync.syncedKey(SettingsSync.SCALE_BAR_BOTH))

        // Device A turns both units on (POST /scalebar?both=on or the Map options row): one setting operation.
        val nowA = SettingsSync.localValues(bothUnits = true)
        assertEquals("on", nowA[key])
        assertEquals(1, SettingsSync.write(a, listOf(key), nowA, clock))
        assertEquals("on", a.settings()[key])
        // Asked again with the same value, nothing more is written.
        assertEquals(0, SettingsSync.write(a, listOf(key), nowA, clock))

        // B merges A's log; its own bar shows one unit, so the pull sets both units on B, outside SettingsRoutes.
        clock += 1_000
        b.apply(a.bundle(b.vector()), clock)
        assertEquals("on", b.settings()[key])
        val plan = SettingsSync.plan(b.settings(), SettingsSync.localValues(bothUnits = false), routeKeys = setOf("units", "textScale"))
        assertEquals(mapOf(SettingsSync.SCALE_BAR_BOTH to true), plan.local)
        assertTrue(plan.params.isEmpty())
        assertTrue(plan.skipped.isEmpty())

        // Once B shows both units, the same log changes nothing.
        assertTrue(SettingsSync.plan(b.settings(), SettingsSync.localValues(bothUnits = true), setOf("units")).local.isEmpty())

        // B turns it off later: last writer wins on A after the next merge.
        clock += 1_000
        assertEquals(1, SettingsSync.write(b, listOf(key), SettingsSync.localValues(bothUnits = false), clock))
        a.apply(b.bundle(a.vector()), clock)
        assertEquals("off", a.settings()[key])
        assertEquals(mapOf(SettingsSync.SCALE_BAR_BOTH to false), SettingsSync.plan(a.settings(), nowA, setOf("units")).local)
    }

    @Test
    fun otherKeysStillGoThroughSettingsRoutesAndUnknownValuesAreSkipped() {
        val synced = mapOf("app.units" to "ft", key to "sideways", "app.somethingNew" to "1", "other.key" to "x")
        val plan = SettingsSync.plan(synced, mapOf("app.units" to "m", key to "off"), routeKeys = setOf("units"))
        assertEquals(mapOf<String, Any?>("units" to "ft"), plan.params)
        assertTrue(plan.local.isEmpty())
        assertEquals(listOf(key, "app.somethingNew"), plan.skipped)
    }
}
