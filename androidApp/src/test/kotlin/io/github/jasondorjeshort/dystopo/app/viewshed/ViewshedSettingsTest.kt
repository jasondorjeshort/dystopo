package io.github.jasondorjeshort.dystopo.app.viewshed

import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.sync.SettingsSync
import io.github.jasondorjeshort.dystopo.dem.Peak
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T182: the view range syncs like the other user choices (`app.viewRange`, km, taken on another device through
 * `POST /settings`'s parser), and the sheet's strings read in the user's units.
 */
class ViewshedSettingsTest {
    @Test
    fun theViewRangeIsWrittenAndTakenOnAnotherDevice() {
        assertTrue(SettingsSync.VIEW_RANGE in SettingsRoutes.KEYS)
        val synced = listOf(SettingsSync.syncedKey(SettingsSync.VIEW_RANGE))
        assertEquals(listOf("app.viewRange"), synced)
        var clock = 1_000L
        val a = InMemoryTrackStore("aaaa", wallClock = { clock })
        val b = InMemoryTrackStore("bbbb", wallClock = { clock })
        assertEquals(1, SettingsSync.write(a, synced, mapOf("app.viewRange" to "100"), clock))
        clock += 1_000
        b.apply(a.bundle(b.vector()), clock)
        val plan = SettingsSync.plan(b.settings(), mapOf("app.viewRange" to "30"), SettingsRoutes.KEYS)
        assertEquals(mapOf<String, Any?>("viewRange" to "100"), plan.params)
        assertEquals(100, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params)).viewRange)
    }

    @Test
    fun theStringsReadInTheUsersUnits() {
        assertEquals("30 km", ViewshedText.range(30, ContourUnits.METERS))
        assertEquals("19 mi", ViewshedText.range(30, ContourUnits.FEET))
        assertEquals("6 mi", ViewshedText.range(10, ContourUnits.FEET))
        assertEquals("62 mi", ViewshedText.range(100, ContourUnits.FEET))
        val p = PeakView(Peak("Mount Rainier", "volcano", 46.8529, -121.7603, 4392.0), 7620.0, 344.6, "N", 4392.0, false, 19.6, true, 0.4, true)
        assertEquals("4392 m · 7.62 km · N 345° · on the skyline", ViewshedText.peakLine(p, ContourUnits.METERS))
        assertEquals("14409 ft · 4.7 mi · N 345° · on the skyline", ViewshedText.peakLine(p, ContourUnits.FEET))
        assertEquals("30 km around, 3 peaks in view (1.2 s)", ViewshedText.done(30, ContourUnits.METERS, 1234.0, 3))
        assertEquals("10 km around, 1 peak in view (0.4 s)", ViewshedText.done(10, ContourUnits.METERS, 400.0, 1))
    }
}
