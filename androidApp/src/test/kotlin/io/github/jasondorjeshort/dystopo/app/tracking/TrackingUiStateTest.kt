package io.github.jasondorjeshort.dystopo.app.tracking

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        // T229: the tracker's own thread starts after the state and the settings, before the corridors' thread can ask for it.
        val started = onCreate.indexOf("Tracking.start(")
        assertTrue(started > prepared && started > onCreate.indexOf("AppSettings.load(") && started < onCreate.indexOf("openTiles()"), "Tracking.start after prepare and AppSettings.load, before openTiles")
        val controls = File(app, "tracking/TrackControls.kt").readText()
        assertFalse(Regex("""remember\s*\{\s*Tracking\.of""").containsMatchIn(controls), "the composition reads TrackingUiState.tracking; it never waits for the tracker")
    }

    // ---- T229: the tracker built on its own thread ----

    private class Box(val n: Int)

    /** Off the main thread a caller before readiness waits for it, and one after gets it at once. */
    @Test fun aCallerOffTheMainThreadWaitsForReadinessAndOneAfterGetsItAtOnce() {
        val ready = TrackerReady<Box>({ false })
        var got: Box? = null
        val waiter = thread(name = "dystopo-corridors") { got = ready.await(5_000, { "test" }) }
        Thread.sleep(100)
        assertTrue(waiter.isAlive, "the caller waits while the tracker is being built")
        val built = Box(1)
        ready.set(built)
        waiter.join(5_000)
        assertSame(built, got)
        assertSame(built, ready.await(1, { "after" }))
    }

    /** On the main thread before readiness: a failed check that names the caller, never a silent null; after it, the value. */
    @Test fun theMainThreadBeforeReadinessFailsByName() {
        val ready = TrackerReady<Box>({ true })
        val e = assertFailsWith<IllegalStateException> { ready.await(5_000, { "LiveReadout.hook(LiveReadout.kt:169)" }) }
        assertTrue(e.message!!.contains("LiveReadout.hook(LiveReadout.kt:169)"), e.message)
        assertTrue(e.message!!.contains("main thread"), e.message)
        val built = Box(2)
        ready.set(built)
        assertSame(built, ready.await(5_000, { "main after" }))
    }

    /** The service's callbacks may wait on the main thread ([Tracking.awaitReady]); a failed build lets the caller build it. */
    @Test fun theServiceWaitsOnTheMainThreadAndAFailedBuildIsTheCallersToRetry() {
        val ready = TrackerReady<Box>({ true })
        thread { Thread.sleep(50); ready.set(Box(3)) }
        assertEquals(3, ready.await(5_000, { "TrackingService.onStartCommand" }, allowMain = true)!!.n)
        val failing = TrackerReady<Box>({ false })
        failing.failed(IllegalStateException("database locked"))
        assertNull(failing.await(5_000, { "corridors" }))
        assertTrue(failing.failure is IllegalStateException)
        assertFailsWith<java.util.concurrent.TimeoutException> { TrackerReady<Box>({ false }).await(50, { "nobody builds" }) }
    }

    /** The Record rule: a tap before readiness waits ("Starting"), a second tap meanwhile is refused, the first runs once when ready. */
    @Test fun aTapBeforeReadinessIsQueuedOnceAndRunOnce() {
        val ready = TrackerReady<Box>({ false })
        val runs = java.util.concurrent.atomic.AtomicInteger()
        val waiting = java.util.Collections.synchronizedList(ArrayList<Boolean>())
        fun tap() = ready.once(5_000, { "ActivityPicker.choose" }, { error("no fallback build") }, { waiting += it }) { b -> runs.incrementAndGet(); b.n }
        var first: Int? = -1
        val t1 = thread { first = tap() }
        val deadline = System.currentTimeMillis() + 5_000
        while (!ready.waiting && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(ready.waiting, "the first tap waits")
        assertNull(tap(), "a second tap while the first waits does nothing")
        assertEquals(0, runs.get())
        ready.set(Box(7))
        t1.join(5_000)
        assertEquals(7, first)
        assertEquals(1, runs.get(), "run once")
        assertEquals(listOf(true, false), waiting.toList(), "Starting shown, then cleared")
        assertFalse(ready.waiting)
        assertEquals(7, tap(), "after readiness a tap acts at once")
        assertEquals(2, runs.get())
        assertEquals(listOf(true, false), waiting.toList(), "no Starting once ready")
    }

    @Test fun theRecordGroupSaysStartingWhileARecordingWaitsAndNeverStopsInOneTap() { // T229, T253
        assertEquals(listOf("Record"), recordGroup(recording = false, paused = false, starting = false))
        assertEquals(listOf(RECORD_STARTING), recordGroup(recording = false, paused = false, starting = true))
        assertEquals(listOf(PAUSE), recordGroup(recording = true, paused = false, starting = true), "recording: Pause only")
        assertEquals(listOf(RESUME, FINISH), recordGroup(recording = true, paused = true, starting = false), "paused: Resume and Finish")
    }
}
