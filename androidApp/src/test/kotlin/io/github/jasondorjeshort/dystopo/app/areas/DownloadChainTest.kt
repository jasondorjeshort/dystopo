package io.github.jasondorjeshort.dystopo.app.areas

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T156 (ADR 0046): the WorkManager chain's pure pieces. The hand-over of a started download (a tap replaces the chain, any
 * other start keeps it), the chain kept by a check that started nothing, the next step after a run (the re-enqueue: at the
 * record's backoff, never at once, none once nothing may resume), the pause on a stop, and the notification's text.
 */
class DownloadChainTest {
    private val disc = AreaOfInterest(AreaShape.Corridor(listOf(AreaPoint(46.7859, -121.7356)), 5000.0), 8, 15, "Paradise")
    private val now = 1_000_000_000L

    private fun record(id: String, state: String, cancelled: Boolean = false, auto: AutoResume? = null, pack: String? = null) =
        Areas.Record(id = id, name = id, area = disc, createdAt = 1L, state = state, source = "sources", cancelled = cancelled, autoResume = auto, pack = pack)

    @Test
    fun aTapReplacesTheChainAndEveryOtherStartKeepsIt() {
        assertEquals(ExistingWorkPolicy.REPLACE, handOverPolicy(AreaDownload.TRIGGER_USER))
        for (t in listOf(ResumeCheck.TRIGGER_WORKER, ResumeCheck.TRIGGER_START, ResumeCheck.TRIGGER_FOREGROUND, ResumeCheck.TRIGGER_BACKOFF, ResumeCheck.TRIGGER_DOWNLOAD_OVER, "start, foreground")) {
            assertEquals(ExistingWorkPolicy.KEEP, handOverPolicy(t), t)
        }
    }

    @Test
    fun theWorkersCheckSkipsTheBackoffLikeTheUsersTriggers() {
        assertTrue(ResumeCheck.TRIGGER_WORKER in ResumeCheck.SKIP_WAIT)
        assertTrue(ResumeCheck.SKIP_WAIT.containsAll(ResumeCheck.USER_PRESENT))
        assertFalse(ResumeCheck.TRIGGER_BACKOFF in ResumeCheck.SKIP_WAIT)
        assertFalse(ResumeCheck.TRIGGER_DOWNLOAD_OVER in ResumeCheck.SKIP_WAIT)
        // A record in its backoff is started by the worker's check, not by a download-over one.
        val waiting = listOf(record("a", "partial", auto = AutoResume(attempts = 1, nextAt = now + 60_000)))
        assertEquals("a", decide(waiting, null, now, 2048, userPresent = true).start)
        assertNull(decide(waiting, null, now, 2048, userPresent = false).start)
    }

    @Test
    fun inTheBackgroundOnlyAWorkerOrTheUserStartsADownloadInTheProcess() {
        val inProcess = listOf(ResumeCheck.TRIGGER_BACKOFF, ResumeCheck.TRIGGER_DOWNLOAD_OVER)
        for (t in inProcess) {
            assertFalse(mayStartHere(listOf(t), appOnScreen = false, workerRunning = false), "$t in the background: the chain's job starts it")
            assertTrue(mayStartHere(listOf(t), appOnScreen = false, workerRunning = true), "$t while a worker runs (the next area)")
            assertTrue(mayStartHere(listOf(t), appOnScreen = true, workerRunning = false), "$t with the app on screen")
        }
        for (t in ResumeCheck.SKIP_WAIT) assertTrue(mayStartHere(listOf(t), appOnScreen = false, workerRunning = false), t)
        assertTrue(mayStartHere(listOf(ResumeCheck.TRIGGER_BACKOFF, ResumeCheck.TRIGGER_WORKER), appOnScreen = false, workerRunning = false))
    }

    @Test
    fun aRunEndsTheChainOnlyWhenNothingMayResume() {
        assertNull(chainNext(emptyList(), now, 2048))
        assertNull(chainNext(listOf(record("done", "done"), record("pack", "partial", pack = "p")), now, 2048))
        assertNull(chainNext(listOf(record("c", "partial", cancelled = true)), now, 2048), "a cancel is never resumed")
        assertNull(chainNext(listOf(record("s", "partial", auto = AutoResume(stopped = AutoResume.STOP_FAILURES))), now, 2048), "stopped after failures")
        assertNull(chainNext(listOf(record("r", "partial", auto = AutoResume(stopped = AutoResume.STOP_REFUSED, budgetMb = 2048))), now, 2048), "refused by this budget")
        // The same refusal under another budget may go again: a step, as for any ready record.
        assertEquals(READY_AGAIN_MS, chainNext(listOf(record("r", "partial", auto = AutoResume(stopped = AutoResume.STOP_REFUSED, budgetMb = 1024))), now, 2048))
    }

    @Test
    fun theNextStepWaitsForTheRecordsBackoffAndNeverRunsAtOnce() {
        // A run that ended partial: T151's afterRun set the backoff; the step follows it.
        val after = afterRun(null, "partial", progressed = true, stalled = true, now = now, budgetMb = 2048)!!
        assertEquals(ResumeCheck.BACKOFF_MS[0], chainNext(listOf(record("a", "partial", auto = after)), now, 2048))
        val later = AutoResume(attempts = 3, nextAt = now + 600_000)
        assertEquals(600_000L, chainNext(listOf(record("a", "partial", auto = later)), now, 2048))
        // The earliest of two backoffs.
        assertEquals(120_000L, chainNext(listOf(record("a", "partial", auto = later), record("b", "partial", auto = AutoResume(attempts = 2, nextAt = now + 120_000))), now, 2048))
        // A backoff already over, or a few seconds away: at least MIN_STEP_MS, so a step never loops at once.
        assertEquals(MIN_STEP_MS, chainNext(listOf(record("a", "partial", auto = AutoResume(attempts = 1, nextAt = now + 1_000))), now, 2048))
        // Ready after the run (its check started nothing): a later try, not now.
        assertEquals(READY_AGAIN_MS, chainNext(listOf(record("a", "partial")), now, 2048))
        assertEquals(READY_AGAIN_MS, chainNext(listOf(record("a", "downloading")), now, 2048), "a record still being written")
    }

    @Test
    fun aCheckThatStartedNothingKeepsTheChainWhileARecordMayResume() {
        fun delayOf(vararg rs: Areas.Record, live: String? = null) = chainDelay(decide(rs.toList(), live, now, 2048, userPresent = false), now)
        assertNull(delayOf())
        assertNull(delayOf(record("c", "partial", cancelled = true), record("d", "done")))
        assertEquals(0L, delayOf(record("a", "partial")), "ready: at once")
        assertEquals(0L, delayOf(record("a", "downloading"), live = "a"), "running: the worker watches it")
        assertEquals(90_000L, delayOf(record("a", "partial", auto = AutoResume(attempts = 1, nextAt = now + 90_000))), "at the backoff's end")
    }

    @Test
    fun aStopPausesTheDownloadOnlyInTheBackgroundAndNotForTheAppsOwnReplace() {
        for (r in listOf(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY, WorkInfo.STOP_REASON_TIMEOUT, WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT, WorkInfo.STOP_REASON_QUOTA, WorkInfo.STOP_REASON_UNKNOWN)) {
            assertTrue(pauseOnStop(r, appOnScreen = false), stopReasonName(r))
            assertFalse(pauseOnStop(r, appOnScreen = true), stopReasonName(r))
        }
        assertFalse(pauseOnStop(WorkInfo.STOP_REASON_CANCELLED_BY_APP, appOnScreen = false))
        assertEquals("foreground service timeout", stopReasonName(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT))
        assertEquals("connectivity", stopReasonName(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY))
    }

    @Test
    fun aPausedRunBacksOffWithoutCountingAFailure() {
        // AreaDownload.pause ends the walk as a network stall: T151's afterRun then backs off and counts no failure, so
        // the system's stops (the six hours, the ten minutes) never end the resumes.
        var a: AutoResume? = AutoResume(failures = 2, attempts = 2)
        repeat(10) { a = afterRun(a, "partial", progressed = false, stalled = true, now = now, budgetMb = 2048) }
        assertEquals(2, a!!.failures)
        assertNull(a!!.stopped)
    }

    @Test
    fun theNotificationSaysTheTiles() {
        assertEquals("Downloading Paradise", notificationTitle("Paradise"))
        assertEquals("Getting the tile list", notificationText(0, 0))
        assertEquals("0 of 538 tiles", notificationText(538, 0))
        assertEquals("1,234 of 5,678 tiles", notificationText(5678, 1234))
        assertEquals("538 of 538 tiles", notificationText(538, 600))
        assertEquals("Cancel", CANCEL_LABEL)
    }
}
