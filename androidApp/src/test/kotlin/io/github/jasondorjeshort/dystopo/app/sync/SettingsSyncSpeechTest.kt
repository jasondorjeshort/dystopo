package io.github.jasondorjeshort.dystopo.app.sync

import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T126: spoken directions sync like the other user choices: `app.navSpeech` (`on` or `off`) in the log, taken on another
 * device through `POST /settings`'s parser (a [SettingsRoutes] key, unlike the scale bar's both-units choice).
 */
class SettingsSyncSpeechTest {
    private val key = "app.navSpeech"

    @Test
    fun theSpeechKeyIsWrittenAndTakenOnAnotherDevice() {
        var clock = 1_000L
        val a = InMemoryTrackStore("aaaa", wallClock = { clock })
        val b = InMemoryTrackStore("bbbb", wallClock = { clock })
        assertEquals(key, SettingsSync.syncedKey(SettingsSync.NAV_SPEECH))
        assertTrue(SettingsSync.NAV_SPEECH in SettingsRoutes.KEYS)

        assertEquals(1, SettingsSync.write(a, listOf(key), mapOf(key to SettingsSync.onOff(true)), clock))
        clock += 1_000
        b.apply(a.bundle(b.vector()), clock)
        assertEquals("on", b.settings()[key])

        val plan = SettingsSync.plan(b.settings(), mapOf(key to "off"), SettingsRoutes.KEYS)
        assertEquals(mapOf<String, Any?>("navSpeech" to "on"), plan.params)
        assertTrue(plan.local.isEmpty())
        val parsed = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params))
        assertEquals(true, parsed.navSpeech)

        // Already on: nothing to apply.
        assertTrue(SettingsSync.plan(b.settings(), mapOf(key to "on"), SettingsRoutes.KEYS).params.isEmpty())
    }
}
