package io.github.jasondorjeshort.dystopo.app.areas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.MainActivity
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T156 (ADR 0046): the out-of-process half of the resume idea. One WorkManager unique one-time work ([UNIQUE]) keeps an
 * area download going when the app is in the background or its process is gone; T151's cursor, check and backoff
 * ([ResumeCheck], [AreaDownload]) stay the one resume logic, and this worker only runs them and keeps the process alive.
 *
 * - **The chain.** [handOver] on every start (a tap replaces the work, so a fresh worker runs at once while the app is on
 *   screen; any other start keeps the work there is) and [ensure] from the resume check while a record may resume. The
 *   work has a `CONNECTED` constraint (never `UNMETERED`, ADR 0023): losing the network stops the worker and regaining it
 *   runs it again, so the constraint is the connectivity trigger (no network callback of our own); WorkManager persists
 *   it, so it runs after a process death and a reboot. A user's force-stop cancels it until the next launch, when
 *   T151's start trigger runs the check, which enqueues it again.
 * - **A run** ([doWork]): reads the records (the repair at start), watches a download already running or else runs the
 *   resume check ([ResumeCheck.TRIGGER_WORKER], from the cursor), and goes on watching while downloads follow one another.
 *   It asks for the foreground with the `dataSync` type and the notification (progress, Cancel); when that is refused (a
 *   run started in the background, or Android 15's six hours a day of `dataSync` spent) it goes on as a plain job, which
 *   the system stops after about ten minutes. At the end it appends the next step when a record may still resume
 *   ([chainNext]: after the record's backoff), else the chain ends.
 * - **A stop** (the constraint, the job's time, the foreground service's timeout): with the app in the background the walk
 *   is paused ([AreaDownload.pause]: the cursor saved, the record partial, no failure counted) and WorkManager runs the
 *   stopped work again later; with the app on screen the download goes on in the process. A stop by the app (a tap's
 *   replace) leaves the download alone.
 * - **Cancel** in the notification ([DownloadCancelReceiver]) is the user's cancel ([AreaDownload.cancel], kept in the
 *   record), never resumed.
 *
 * `GET /areas/resume` `chain` is [json]: the work's state, attempts and last stop reason, and this process's runs.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val run = Run(id.toString(), runAttemptCount, System.currentTimeMillis())
        last = run
        runs.incrementAndGet()
        active.incrementAndGet()
        Log.i(TAG, "download worker run ${run.work} (attempt ${run.attempt}, app ${if (Foreground.started) "on screen" else "in the background"})")
        try {
            withContext(Dispatchers.IO) {
                OnlineService.get(applicationContext as DystopoApp) // reads the records: the repair at start, T151's start trigger
                ResumeCheck.settle()
                if (AreaDownload.job?.running != true) run.check = ResumeCheck.runNow(ResumeCheck.TRIGGER_WORKER).let { "${it["started"] ?: "nothing started"}; ${it["note"] ?: ""}".trim(' ', ';') }
            }
            while (true) {
                val j = AreaDownload.job?.takeIf { it.running } ?: break
                if (run.foreground == null) run.foreground = tryForeground(j, run)
                run.watched += j.id
                while (!j.handedOn) {
                    if (run.foreground == true) runCatching { notify(applicationContext, j) }
                    delay(WATCH_MS)
                }
                withContext(Dispatchers.IO) { ResumeCheck.settle() } // the download-over check: the next area may have started
            }
            val next = withContext(Dispatchers.IO) { chainNext(Areas.all(), System.currentTimeMillis(), AppSettings.pinnedCacheMb) }
            if (next != null) followUp(next)
            run.end(if (next != null) "next in ${next / 1000} s" else "chain ends")
            return Result.success()
        } catch (e: CancellationException) {
            val reason = stopReason
            val pause = pauseOnStop(reason, Foreground.started)
            val paused = pause && AreaDownload.pause(PAUSE_REASON + stopReasonName(reason))
            run.end("stopped: ${stopReasonName(reason)}${if (paused) ", download paused" else if (pause) "" else ", download left running"}")
            lastStop = stopReasonName(reason)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download worker failed", e)
            run.end("failed: ${e.message ?: e.javaClass.simpleName}")
            return Result.retry()
        } finally {
            active.decrementAndGet()
        }
    }

    /** The foreground with the `dataSync` type; false (a plain job, about ten minutes) when the system refuses it. */
    private suspend fun tryForeground(j: AreaDownload.Job, run: Run): Boolean = try {
        setForeground(foregroundInfo(applicationContext, j))
        Log.i(TAG, "download worker in the foreground")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        run.foregroundError = "${e.javaClass.simpleName}: ${e.message}"
        Log.i(TAG, "download worker not in the foreground, a plain job: ${run.foregroundError}")
        false
    }

    /** What one run did, for [json] and the log. */
    class Run(val work: String, val attempt: Int, val startedAt: Long) {
        @Volatile var foreground: Boolean? = null
        @Volatile var foregroundError: String? = null
        @Volatile var check: String? = null
        val watched: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        @Volatile var endedAt = 0L
        @Volatile var outcome: String? = null

        fun end(what: String) {
            endedAt = System.currentTimeMillis()
            outcome = what
            Log.i(TAG, "download worker run $work: $what (${endedAt - startedAt} ms, watched ${watched.toList()}, foreground $foreground)")
        }

        fun json(): Map<String, Any?> = linkedMapOf(
            "work" to work, "attempt" to attempt, "startedAt" to startedAt, "endedAt" to endedAt.takeIf { it != 0L }, "foreground" to foreground,
            "foregroundError" to foregroundError, "check" to check, "watched" to watched.toList(), "outcome" to outcome,
        )
    }

    companion object {
        /** The unique work's name. */
        const val UNIQUE = "area-download"
        const val TAG = "DystopoResume"
        private const val CHANNEL = "area-downloads"
        private const val NOTIFICATION_ID = 156
        private const val WATCH_MS = 2_000L
        const val PAUSE_REASON = "paused by the system: "

        private val runs = AtomicInteger()
        private val active = AtomicInteger()

        /** T156: a worker runs in this process now (its job gives the process the network in the background). */
        val running: Boolean get() = active.get() > 0
        private val cancels = AtomicInteger()
        @Volatile private var last: Run? = null
        @Volatile private var lastStop: String? = null

        private val app: Context get() = DystopoApp.instance

        /** T156: hands a started download to the chain ([handOverPolicy]). */
        fun handOver(context: Context, trigger: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, handOverPolicy(trigger), request(0L))
        }

        /** T156: keeps the chain enqueued (KEEP: nothing when it is), its run after [delayMs]. */
        fun ensure(delayMs: Long) {
            WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request(delayMs))
        }

        /** The chain's next step, after the running one ends (APPEND_OR_REPLACE: a child of the running work). */
        private fun followUp(delayMs: Long) {
            WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request(delayMs))
        }

        private fun request(delayMs: Long): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(DownloadWorker::class.java)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()) // ADR 0023: never UNMETERED
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MS, TimeUnit.MILLISECONDS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag(UNIQUE)
                .build()

        /** The notification's Cancel ([DownloadCancelReceiver]): the user's cancel of [id], kept in the record. */
        fun cancelFromNotification(id: String?) {
            cancels.incrementAndGet()
            if (AreaDownload.cancel(id)) return
            // No job runs it (paused between runs): the record keeps the cancel, so no trigger resumes it.
            if (id != null) Areas.update(id) { r -> if (unfinished(r)) r.copy(state = "cancelled", cancelled = true) else r }
        }

        /** `GET /areas/resume` `chain`: the unique work's state and this process's runs. */
        fun json(): Map<String, Any?> {
            val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork(UNIQUE).get(2, TimeUnit.SECONDS)
            return linkedMapOf(
                "name" to UNIQUE,
                "enqueued" to infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED },
                "running" to infos.any { it.state == WorkInfo.State.RUNNING },
                "work" to infos.map { w ->
                    linkedMapOf(
                        "id" to w.id.toString(), "state" to w.state.name, "attempts" to w.runAttemptCount, "stopReason" to stopReasonName(w.stopReason),
                        "nextAt" to w.nextScheduleTimeMillis.takeIf { it != Long.MAX_VALUE }, "initialDelayMs" to w.initialDelayMillis,
                        "network" to w.constraints.requiredNetworkType.name,
                    )
                },
                "runs" to runs.get(), "runningHere" to running, "last" to last?.json(), "lastStop" to lastStop, "notificationCancels" to cancels.get(),
            )
        }

        private fun foregroundInfo(context: Context, j: AreaDownload.Job): ForegroundInfo {
            val n = notification(context, j)
            return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION_ID, n)
        }

        private fun notify(context: Context, j: AreaDownload.Job) {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, j))
        }

        private fun notification(context: Context, j: AreaDownload.Job): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val cancel = PendingIntent.getBroadcast(
                context, 1, Intent(context, DownloadCancelReceiver::class.java).putExtra(DownloadCancelReceiver.EXTRA_ID, j.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val planned = j.planned.get()
            val done = j.done.get()
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(notificationTitle(j.name))
                .setContentText(notificationText(planned, done))
                .setProgress(planned.coerceAtLeast(0), done.coerceIn(0, planned.coerceAtLeast(0)), planned <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel), CANCEL_LABEL, cancel).build())
            if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            return b.build()
        }

        /** WorkManager's backoff after a run that failed outright ([Result.retry]); T151's backoff spaces the chain's steps. */
        const val BACKOFF_MS = 30_000L
    }
}

/** T156: the notification's Cancel: the user's cancel of the area download it names ([DownloadWorker.cancelFromNotification]). */
class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID)
        val pending = goAsync()
        Thread({
            try {
                DownloadWorker.cancelFromNotification(id)
            } finally {
                pending.finish()
            }
        }, "download-cancel").start()
    }

    companion object {
        const val EXTRA_ID = "area"
    }
}

/** T156: the notification channel's name (Settings › Notifications). */
internal const val CHANNEL_NAME = "Area downloads"

/** T156: the notification's action. */
internal const val CANCEL_LABEL = "Cancel"

/** T156: the card's line after the system paused the download in the background (the chain goes on from the cursor). */
internal const val RESUME_PAUSED = "The download paused in the background."

/** T156: the notification's title. Pure, tested. */
internal fun notificationTitle(name: String): String = "Downloading $name"

/** T156: the notification's text, "1,234 of 5,678 tiles", or "Getting the tile list" before the manifest. Pure, tested. */
internal fun notificationText(planned: Int, done: Int): String =
    if (planned <= 0) "Getting the tile list" else String.format(java.util.Locale.US, "%,d of %,d tiles", done.coerceIn(0, planned), planned)

/**
 * T156: the hand-over of a started download: a tap ([AreaDownload.TRIGGER_USER]) replaces the chain, so a fresh worker runs
 * now (not after a backoff the chain was waiting out) and shows the notification while the app is on screen; any other
 * start (the resume check, the worker's own) keeps the chain there is. Pure, tested.
 */
internal fun handOverPolicy(trigger: String): ExistingWorkPolicy =
    if (trigger == AreaDownload.TRIGGER_USER) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP

/**
 * T156: whether a check may start a download in this process: for the user's triggers and the worker's own, while the app
 * is on screen, or while a worker runs. In the background with no worker the process has no network (Android 15 blocks a
 * cached app's; the emulator showed a download started by the backoff's timer failing every tile at once), so the chain's
 * step, a job with the network, starts it instead. Pure, tested.
 */
internal fun mayStartHere(triggers: List<String>, appOnScreen: Boolean, workerRunning: Boolean): Boolean =
    appOnScreen || workerRunning || triggers.any { it in ResumeCheck.SKIP_WAIT }

/** T156: the reasons a check names for an unfinished record that may still resume. */
private val RESUMABLE = setOf("ready", "backoff", "ending", "running")

/**
 * T156: after a check that started nothing, the delay of the chain to keep ([DownloadWorker.ensure]): none (null) when no
 * record may resume, at once when one is ready or running (the worker then watches it), else at the earliest backoff's end.
 * Pure, tested.
 */
internal fun chainDelay(d: ResumeDecision, now: Long): Long? {
    val whys = d.unfinished.map { it["why"] }
    if (whys.none { it in RESUMABLE }) return null
    if (whys.any { it != "backoff" && it in RESUMABLE }) return 0L
    return ((d.nextAt ?: now) - now).coerceAtLeast(0L)
}

/**
 * T156: the chain's next step after a run, its delay in ms, or null when it ends (nothing unfinished, or only cancelled,
 * stopped or refused records: [decide]'s reasons). A record in its backoff gets a step at the backoff's end (at least
 * [MIN_STEP_MS]); a record still ready after the run (its check started nothing: no source, say) gets one after
 * [READY_AGAIN_MS], so a run never loops at once. The step's `CONNECTED` constraint adds the wait for the network. Pure, tested.
 */
internal fun chainNext(records: List<Areas.Record>, now: Long, budgetMb: Long): Long? {
    val d = decide(records, live = null, now = now, budgetMb = budgetMb, userPresent = false)
    val whys = d.unfinished.map { it["why"] }
    if (whys.none { it in RESUMABLE }) return null
    if (whys.any { it == "ready" || it == "ending" }) return READY_AGAIN_MS
    return ((d.nextAt ?: now) - now).coerceAtLeast(MIN_STEP_MS)
}

/** T156: the shortest wait before the chain's next step. */
internal const val MIN_STEP_MS = 30_000L

/** T156: the wait before trying again a record the run's check left ready (ResumeCheck's third backoff, 10 min). */
internal val READY_AGAIN_MS = ResumeCheck.BACKOFF_MS[2]

/**
 * T156: whether a stop of the worker pauses the download: not a stop by the app (a tap replacing the chain), and not while
 * the app is on screen (the process is not at risk then, and a lost network stalls the walk by itself). Pure, tested.
 */
internal fun pauseOnStop(stopReason: Int, appOnScreen: Boolean): Boolean = stopReason != WorkInfo.STOP_REASON_CANCELLED_BY_APP && !appOnScreen

/** T156: a stop reason's name for the JSON and the log. Pure, tested. */
internal fun stopReasonName(reason: Int): String = when (reason) {
    WorkInfo.STOP_REASON_NOT_STOPPED -> "not stopped"
    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled by the app"
    WorkInfo.STOP_REASON_PREEMPT -> "preempted"
    WorkInfo.STOP_REASON_TIMEOUT -> "timeout"
    WorkInfo.STOP_REASON_DEVICE_STATE -> "device state"
    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "connectivity"
    WorkInfo.STOP_REASON_QUOTA -> "quota"
    WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background restriction"
    WorkInfo.STOP_REASON_APP_STANDBY -> "app standby"
    WorkInfo.STOP_REASON_USER -> "user"
    WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system processing"
    WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "estimated launch time changed"
    WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "foreground service timeout"
    else -> "reason $reason"
}
