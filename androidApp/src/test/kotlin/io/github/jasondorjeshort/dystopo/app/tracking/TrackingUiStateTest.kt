package io.github.jasondorjeshort.dystopo.app.tracking

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T226: the first-launch crash at `Tracking.getUi` from `TrackControls.kt`. On the emulator, a constructor held on
 * `dystopo-corridors` until the first composition had opened crashed 5 launches of 5 with that stack before the fix.
 */
class TrackingUiStateTest {
    /** The old shape: [Tracking] built on another thread after the composition's snapshot opened, with its state in a field. */
    @Test fun stateMadeByTheBuildingThreadCannotBeReadInAnOpenComposition() {
        val composition = Snapshot.takeMutableSnapshot()
        try {
            var built: androidx.compose.runtime.MutableState<Tracking.UiState>? = null
            thread(name = "dystopo-corridors") { built = mutableStateOf(Tracking.UiState()) }.join()
            val e = assertFailsWith<IllegalStateException> { composition.enter { built!!.value } }
            assertTrue(e.message!!.contains("created after the snapshot was taken"), e.message)
        } finally {
            composition.dispose()
        }
    }

    /** The fix: made before the composition, it is readable whichever thread touches it meanwhile, and later writes are seen. */
    @Test fun preparedStateIsReadableInACompositionOpenedBeforeAnotherThreadTouchesIt() {
        TrackingUiState.prepare()
        val composition = Snapshot.takeMutableSnapshot()
        try {
            thread(name = "dystopo-corridors") { TrackingUiState.ui }.join()
            assertEquals(Tracking.UiState().recording, composition.enter { TrackingUiState.ui.recording })
        } finally {
            composition.dispose()
        }
        val before = TrackingUiState.ui
        try {
            TrackingUiState.ui = before.copy(message = "seen")
            val later = Snapshot.takeSnapshot()
            try {
                assertEquals("seen", later.enter { TrackingUiState.ui.message })
            } finally {
                later.dispose()
            }
        } finally {
            TrackingUiState.ui = before
        }
    }

    /** What keeps the fix: no state back in [Tracking], and the state made before the first thing that can start the corridors' thread. */
    @Test fun theStateStaysOutOfTheClassAndIsPreparedBeforeTheTilesOpen() {
        val app = File("src/main/kotlin/io/github/jasondorjeshort/dystopo/app")
        val tracking = File(app, "tracking/Tracking.kt").readText()
        assertFalse(tracking.substringBefore("internal object TrackingUiState").contains("by mutableStateOf"), "a state field in Tracking is made by whichever thread builds it")
        val onCreate = File(app, "DystopoApp.kt").readText().substringAfter("override fun onCreate()")
        val prepared = onCreate.indexOf("Tracking.prepare()")
        assertTrue(prepared >= 0, "Application.onCreate must call Tracking.prepare()")
        assertTrue(prepared < onCreate.indexOf("openTiles()"), "openTiles() starts the online service, which starts the corridors' thread")
    }
}
