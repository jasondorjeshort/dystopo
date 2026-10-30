package io.github.jasondorjeshort.dystopo.app.sync

import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T164: the route-time fit, its "Use the standard estimate" switch and its reset sync like the other user choices
 * (`app.paceFit`, `app.paceStandard`, `app.paceSince`), taken on another device through `POST /settings`'s parser; a
 * reader without the keys (v0.1.14 and before) skips them (ADR 0033), and a fit of another version is not taken.
 */
class SettingsSyncPaceTest {
    private val fit = "1;hike:1.0734,2.8029,-0.0575,5,22788,0.057"
    private val keys = listOf("app.paceFit", "app.paceStandard", "app.paceSince")

    @Test
    fun thePaceKeysAreWrittenAndTakenOnAnotherDevice() {
        var clock = 1_000L
        val a = InMemoryTrackStore("aaaa", wallClock = { clock })
        val b = InMemoryTrackStore("bbbb", wallClock = { clock })
        assertEquals(keys, listOf(SettingsSync.PACE_FIT, SettingsSync.PACE_STANDARD, SettingsSync.PACE_SINCE).map { SettingsSync.syncedKey(it) })
        for (k in listOf(SettingsSync.PACE_FIT, SettingsSync.PACE_STANDARD, SettingsSync.PACE_SINCE)) assertTrue(k in SettingsRoutes.KEYS)

        val values = mapOf("app.paceFit" to fit, "app.paceStandard" to SettingsSync.onOff(false), "app.paceSince" to "0")
        assertEquals(3, SettingsSync.write(a, keys, values, clock))
        clock += 1_000
        b.apply(a.bundle(b.vector()), clock)
        assertEquals(fit, b.settings()["app.paceFit"])

        val plan = SettingsSync.plan(b.settings(), mapOf("app.paceFit" to "", "app.paceStandard" to "on", "app.paceSince" to "0"), SettingsRoutes.KEYS)
        assertEquals(mapOf<String, Any?>("paceFit" to fit, "paceStandard" to "off"), plan.params)
        val parsed = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params))
        assertEquals(fit, parsed.paceFit)
        assertEquals(false, parsed.paceStandard)

        // An older reader, without the keys, skips them and takes the rest (ADR 0033).
        val old = SettingsSync.plan(b.settings(), emptyMap(), SettingsRoutes.KEYS - setOf("paceFit", "paceStandard", "paceSince"))
        assertEquals(keys.toSet(), old.skipped.toSet())
        // A fit of a later version is refused by this reader's parser, so a pull drops that one value and keeps the rest.
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("paceFit" to "2;hike:whatever")))
    }
}
