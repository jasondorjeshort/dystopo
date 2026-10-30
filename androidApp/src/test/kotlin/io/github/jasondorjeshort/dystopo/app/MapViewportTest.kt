package io.github.jasondorjeshort.dystopo.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T162: the start-up crash of finding `2026-10-28-open-view-ignores-the-location-fix.md` and its fix in [MapViewport].
 */
class MapViewportTest {
    /**
     * The mechanism, in the Compose runtime the app ships: state made on another thread (in the global snapshot) while a
     * composition's snapshot is open cannot be read in that snapshot. On the phone the other thread was `CacheBudget`'s
     * eviction pass reading the view at launch (logged on the emulator: `MapViewport.<clinit>` on `cache-budget` from
     * `CacheGuard.viewBox`), the snapshot the first composition's, the reader `AttributionLine`.
     */
    @Test
    fun stateMadeOffThreadDuringACompositionCannotBeReadInIt() {
        val composition = Snapshot.takeMutableSnapshot()
        try {
            var state: MutableState<Int>? = null
            thread { state = mutableStateOf(1) }.join()
            val e = assertFailsWith<IllegalStateException> { composition.enter { state!!.value } }
            assertTrue(e.message!!.contains("created after the snapshot was taken"), e.message)
        } finally {
            composition.dispose()
        }
    }

    /** The fix: the state is made by [MapViewport.prepare] before any composition; other threads read [MapViewport.current]. */
    @Test
    fun preparedStateIsReadableInALaterCompositionAndTheCopyFollows() {
        MapViewport.prepare()
        val box = PackBounds(-121.9, 46.7, -121.5, 47.0)
        MapViewport.set(box)
        val composition = Snapshot.takeMutableSnapshot()
        try {
            assertEquals(box, composition.enter { MapViewport.bounds })
        } finally {
            composition.dispose()
        }
        var offMain: PackBounds? = null
        thread { offMain = MapViewport.current }.join()
        assertEquals(box, offMain)
    }
}
