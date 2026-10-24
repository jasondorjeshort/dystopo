package io.github.jasondorjeshort.dystopo.app.sync

import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T136 (T130's follow-up): the trip corridor's width syncs like spoken directions: `app.tripCorridorM` (whole metres, `0`
 * off) in the log, taken on another device through `POST /settings`'s parser, which refuses a width out of range.
 */
class SettingsSyncCorridorTest {
    private val key = "app.tripCorridorM"

    @Test
    fun theWidthIsWrittenAndTakenOnAnotherDevice() {
        var clock = 1_000L
        val a = InMemoryTrackStore("aaaa", wallClock = { clock })
        val b = InMemoryTrackStore("bbbb", wallClock = { clock })
        assertEquals(key, SettingsSync.syncedKey(SettingsSync.TRIP_CORRIDOR_M))
        assertTrue(SettingsSync.TRIP_CORRIDOR_M in SettingsRoutes.KEYS)

        assertEquals(1, SettingsSync.write(a, listOf(key), mapOf(key to "2000"), clock))
        clock += 1_000
        b.apply(a.bundle(b.vector()), clock)
        assertEquals("2000", b.settings()[key])

        val plan = SettingsSync.plan(b.settings(), mapOf(key to "1000"), SettingsRoutes.KEYS)
        assertEquals(mapOf<String, Any?>("tripCorridorM" to "2000"), plan.params)
        assertTrue(plan.local.isEmpty() && plan.skipped.isEmpty())
        val parsed = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params))
        assertEquals(2000, parsed.tripCorridorM)

        // The same width already: nothing to apply.
        assertTrue(SettingsSync.plan(b.settings(), mapOf(key to "2000"), SettingsRoutes.KEYS).params.isEmpty())
    }

    @Test
    fun offAndOutOfRangeWidths() {
        assertEquals(0, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("tripCorridorM" to "0"))).tripCorridorM)
        assertEquals(500, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("tripCorridorM" to 500))).tripCorridorM)
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("tripCorridorM" to "50")))
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("tripCorridorM" to "20000")))
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("tripCorridorM" to "wide")))
        // A request without the width leaves it alone (so it is not published).
        assertNull(assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("navSpeech" to "1"))).tripCorridorM)
    }
}
