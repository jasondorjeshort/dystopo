package io.github.jasondorjeshort.dystopo.app.sync

import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T163: the turn-around clock's deadline and margin sync like the other user choices (ADR 0044): `app.turnDeadline`
 * (`sunset`, `civil` or `HH:MM`) and `app.turnMarginMin` (whole minutes) in the log, taken on another device through
 * `POST /settings`'s parser; a value this build does not accept (an "off") is refused there.
 */
class SettingsSyncTurnAroundTest {
    private val deadline = "app.turnDeadline"
    private val margin = "app.turnMarginMin"

    @Test
    fun theKeysAreWrittenAndTakenOnAnotherDevice() {
        var clock = 1_000L
        val a = InMemoryTrackStore("aaaa", wallClock = { clock })
        val b = InMemoryTrackStore("bbbb", wallClock = { clock })
        assertEquals(deadline, SettingsSync.syncedKey(SettingsSync.TURN_DEADLINE))
        assertEquals(margin, SettingsSync.syncedKey(SettingsSync.TURN_MARGIN_MIN))
        assertTrue(SettingsSync.TURN_DEADLINE in SettingsRoutes.KEYS && SettingsSync.TURN_MARGIN_MIN in SettingsRoutes.KEYS)

        assertEquals(2, SettingsSync.write(a, listOf(deadline, margin), mapOf(deadline to "17:45", margin to "60"), clock))
        clock += 1_000
        b.apply(a.bundle(b.vector()), clock)
        val plan = SettingsSync.plan(b.settings(), mapOf(deadline to "sunset", margin to "30"), SettingsRoutes.KEYS)
        assertEquals(mapOf<String, Any?>("turnDeadline" to "17:45", "turnMarginMin" to "60"), plan.params)
        val parsed = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params))
        assertEquals("17:45", parsed.turnDeadline)
        assertEquals(60, parsed.turnMarginMin)
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("turnDeadline" to "off")))
    }
}
