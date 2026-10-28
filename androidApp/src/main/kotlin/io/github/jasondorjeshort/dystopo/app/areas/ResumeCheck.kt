package io.github.jasondorjeshort.dystopo.app.areas

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.areas.TileRow
import io.github.jasondorjeshort.dystopo.areas.TileSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * T151 (the resume idea's in-process half): interrupted area downloads pick themselves up.
 *
 * - **The cursor** ([DownloadCursor], in the record): the last row of the download's walk before which every row is
 *   whole ([AreaDownload]'s row walk, [TileRow.ORDER]), with the tiles that failed in those rows kept as rows. It is saved
 *   every [AreaDownload.CURSOR_SAVE_MS] while rows complete and when the run ends, so a resume (this check, Retry, or the
 *   background worker that follows) walks the failed rows and then the rows after it: its cost follows what is left.
 * - **The repair at start** ([repairedAtStart], run when the records are read): a record left "downloading" by a process
 *   that ended becomes "partial" (Retry shows at once) or "cancelled" when the user had cancelled it.
 * - **The cheap check** ([check]): the records already read from the small areas file, filtered to unfinished ones
 *   (downloading or partial, no live job, not cancelled), nothing from the tile cache; with none it returns at once. It
 *   starts the oldest one that may go ([decide]); one area download at a time stays the rule.
 * - **Triggers**: app start ([Areas.attach]) and the app coming to the foreground ([Foreground]'s flag), a run ending
 *   ([TRIGGER_DOWNLOAD_OVER]) and the backoff's timer. Triggers that arrive while a check is queued make one check
 *   ([Coalescer]).
 * - **Not against the user**: a cancel is never resumed (the record's `cancelled`), a budget refusal stops the resumes until
 *   the budget changes, and runs that fail without progress back off ([BACKOFF_MS]) and stop after [MAX_FAILURES] with Retry
 *   ([afterRun]). A run cut off by the network (stalled, [AreaDownload]) backs off but is not counted: losing service is
 *   normal and the owner's requirement is that the download continues later without being asked. App start and the
 *   foreground trigger skip the backoff's wait (the user is there), never the stop or the cancel.
 *
 * T156 (ADR 0046): the out-of-process half is the WorkManager chain ([DownloadWorker]). A check that finds an unfinished
 * record which may resume and starts nothing keeps the chain enqueued ([DownloadWorker.ensure], KEEP: nothing while it is
 * there), so the download goes on when the process is gone, on regaining the network (the chain's `CONNECTED` constraint)
 * and after a reboot; after a user's force-stop, which cancels the chain until the next launch, the start trigger here is
 * what brings it back. The worker's own check ([TRIGGER_WORKER]) skips the backoff's wait like the user's triggers: the
 * chain's delay and its network constraint are the wait when it runs.
 *
 * No user setting (the wave 21 research found no app that resumes by itself to take a default from; the owner's
 * requirement is resume without a toggle; data use stays steered by the visible size and budget, ADR 0023).
 * `GET /areas/resume`, `POST /areas/resume` ([AreaRoutes]).
 */
object ResumeCheck {
    const val TRIGGER_START = "start"
    const val TRIGGER_FOREGROUND = "foreground"
    const val TRIGGER_DOWNLOAD_OVER = "download over"
    const val TRIGGER_BACKOFF = "backoff"
    const val TRIGGER_DEBUG = "debug"
    /** T156: the WorkManager chain's run ([DownloadWorker]). */
    const val TRIGGER_WORKER = "worker"

    /** Triggers that mean the user is there: they skip the backoff's wait. */
    val USER_PRESENT = setOf(TRIGGER_START, TRIGGER_FOREGROUND, TRIGGER_DEBUG)

    /** T156: triggers that skip the backoff's wait: the user's, and the chain's run (its delay and constraint were the wait). */
    val SKIP_WAIT = USER_PRESENT + TRIGGER_WORKER

    private const val TAG = "DystopoResume"
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "area-resume").apply { isDaemon = true } }
    private val coalescer = Coalescer({ worker.execute(it) }) { triggers -> runCatching { check(triggers) }.onFailure { Log.w(TAG, "resume check $triggers", it) } }
    private val seen = ConcurrentHashMap<String, AtomicInteger>()
    private val runs = AtomicInteger()
    private val installed = AtomicBoolean(false)
    @Volatile private var timer: ScheduledFuture<*>? = null
    @Volatile private var timerAt = 0L
    @Volatile var last: Map<String, Any?>? = null
        private set

    /** Listens for the app coming to the foreground (once per process; the start trigger covers the first start). */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            Foreground.flow.drop(1).collect { if (it) request(TRIGGER_FOREGROUND) }
        }
    }

    /** Asks for a check (coalesced with one already queued). */
    fun request(trigger: String) {
        seen.getOrPut(trigger) { AtomicInteger() }.incrementAndGet()
        coalescer.request(trigger)
    }

    /** `POST /areas/resume`: a check now, on the check's thread, answered when it is done. */
    fun runNow(trigger: String = TRIGGER_DEBUG): Map<String, Any?> {
        seen.getOrPut(trigger) { AtomicInteger() }.incrementAndGet()
        return worker.submit(Callable { check(listOf(trigger)) }).get(30, TimeUnit.SECONDS)
    }

    /** T156: the check's note when it leaves a ready record to the chain. */
    const val LEFT_TO_CHAIN = "left to the chain: the app is in the background and no worker runs"

    /** T156: waits until every check asked for before now has run (the check's thread runs them in order). */
    fun settle() {
        worker.submit(Callable { Unit }).get(30, TimeUnit.SECONDS)
    }

    private fun check(triggers: List<String>): Map<String, Any?> {
        val t0 = System.nanoTime()
        val now = System.currentTimeMillis()
        val records = Areas.all() // the records read from the small areas file; nothing from the tile cache
        val live = AreaDownload.job?.takeIf { it.running }?.id
        val d = decide(records, live, now, AppSettings.pinnedCacheMb, triggers.any { it in SKIP_WAIT })
        val checkMs = (System.nanoTime() - t0) / 1e6
        var started: String? = null
        var note: String? = null
        // T156: in the background only a worker's job has the network (Android 15 blocks a cached app's), so the in-process
        // triggers (the backoff's timer, a run's end) start a download there only while a worker runs; else the chain does.
        val here = mayStartHere(triggers, Foreground.started, DownloadWorker.running)
        if (d.start != null && !here) note = LEFT_TO_CHAIN
        d.start?.takeIf { here }?.let { id ->
            val r = records.first { it.id == id }
            try {
                AreaDownload.resume(r, triggers.joinToString(", "))
                started = id
            } catch (e: AreaDownload.BudgetRefused) {
                // Not against the user: the budget's refusal does not loop; Retry, or a change of the budget, lets it go again.
                Areas.update(id) { it.copy(error = e.message, autoResume = (it.autoResume ?: AutoResume()).copy(stopped = AutoResume.STOP_REFUSED, budgetMb = AppSettings.pinnedCacheMb)) }
                note = "refused: ${e.message}"
            } catch (e: IllegalStateException) {
                note = e.message // another download started meanwhile (its end asks again), or no online source
            }
        }
        d.nextAt?.let { schedule(it, now) }
        // T156: an unfinished record that may resume keeps the chain enqueued (a start hands over by itself).
        val chain = if (started == null) chainDelay(d, now)?.let { delay -> runCatching { DownloadWorker.ensure(delay) }.exceptionOrNull()?.let { "not enqueued: ${it.message}" } ?: "kept, ${delay / 1000} s" } else null
        return linkedMapOf<String, Any?>(
            "run" to runs.incrementAndGet(), "at" to now, "triggers" to triggers, "checkMs" to Math.round(checkMs * 1000) / 1000.0,
            "records" to records.size, "unfinished" to d.unfinished, "started" to started, "waitingFor" to d.waitingFor, "note" to note,
            "nextAt" to d.nextAt, "chain" to chain, "ms" to Math.round((System.nanoTime() - t0) / 1e3) / 1000.0,
        ).also {
            last = it
            if (d.unfinished.isNotEmpty() || started != null) Log.i(TAG, "resume check: $it")
        }
    }

    /** The backoff's timer: one check at [at] (the earliest one asked for wins). */
    private fun schedule(at: Long, now: Long) {
        if (timer?.isDone == false && timerAt in now..at) return
        timer?.cancel(false)
        timerAt = at
        timer = worker.schedule({ request(TRIGGER_BACKOFF) }, (at - now).coerceAtLeast(0) + 50, TimeUnit.MILLISECONDS)
    }

    /** `GET /areas/resume`: the last check, the triggers seen, the repair at start, and the unfinished records now (read, nothing started). */
    fun json(): Map<String, Any?> {
        val t0 = System.nanoTime()
        val d = decide(Areas.all(), AreaDownload.job?.takeIf { it.running }?.id, System.currentTimeMillis(), AppSettings.pinnedCacheMb, userPresent = false)
        val ms = (System.nanoTime() - t0) / 1e6
        return linkedMapOf(
            "last" to last, "runs" to runs.get(), "triggersSeen" to seen.mapValues { it.value.get() }.toSortedMap(), "queued" to coalescer.queued,
            "timerAt" to timerAt.takeIf { timer?.isDone == false }, "repairedAtStart" to synchronized(Areas.repaired) { Areas.repaired.toList() },
            "unfinishedNow" to d.unfinished, "nowMs" to Math.round(ms * 1000) / 1000.0,
            "backoffMs" to BACKOFF_MS, "maxFailures" to MAX_FAILURES, "foreground" to Foreground.started,
            "chain" to runCatching { DownloadWorker.json() }.getOrElse { mapOf("error" to it.toString()) }, // T156
        )
    }

    /**
     * The waits after a run that did not finish, by consecutive attempt: half a minute for a blip, then 2, 10 and 30 minutes,
     * then every 2 hours. The first ones cover a phone walking out of coverage and back; the last one keeps a phone that
     * stays offline (or a server that stays down) from trying more than a few times a day.
     */
    val BACKOFF_MS = listOf(30_000L, 120_000L, 600_000L, 1_800_000L, 7_200_000L)

    /**
     * Runs in a row that fetched nothing and were not cut off by the network before the resumes stop at "partial" with
     * Retry: five covers the backoff's first four waits (about 45 minutes), long enough for a server restart.
     */
    const val MAX_FAILURES = 5
}

/**
 * T151: where an unfinished download got to. [row] is the last row of the walk ([TileRow.ORDER]) before which every row is
 * whole (null: none yet), [wholeRows] how many rows that is, and [failed] the tiles that failed in those rows, as rows per
 * route and zoom (walked again first on a resume). Only meaningful with the manifest it was made over (`files/areas/<id>.json`,
 * whose rows never change once made).
 */
data class DownloadCursor(val row: TileRow?, val wholeRows: Int = 0, val failed: Map<String, Map<Int, TileSet>> = emptyMap(), val savedAt: Long = 0L) {
    /** The row as a short string (`z/route/y/x0`; "" when none): what [AutoResume.runFrom] compares. */
    val key: String get() = row?.let { "${it.z}/${it.route}/${it.y}/${it.x0}" } ?: ""

    val failedTiles: Int get() = failed.values.sumOf { zs -> zs.values.sumOf { it.size } }
    val failedRows: Int get() = failed.values.sumOf { zs -> zs.values.sumOf { it.rowCount } }

    /** The rows of [walk] after [row] (all of them when there is none). */
    fun after(walk: Sequence<TileRow>): Sequence<TileRow> = row?.let { c -> walk.dropWhile { TileRow.ORDER.compare(it, c) <= 0 } } ?: walk

    /** The tiles of the rows of [walk] up to and including [row]. */
    fun tilesThrough(walk: Sequence<TileRow>): Long = row?.let { c -> walk.takeWhile { TileRow.ORDER.compare(it, c) <= 0 }.sumOf { it.count.toLong() } } ?: 0L

    fun json(): Map<String, Any?> = linkedMapOf(
        "z" to row?.z, "route" to row?.route, "y" to row?.y, "x0" to row?.x0, "x1" to row?.x1, "wholeRows" to wholeRows,
        "failedTiles" to failedTiles,
        "failed" to failed.toSortedMap().mapValues { (_, zs) -> zs.toSortedMap().mapKeys { it.key.toString() }.mapValues { it.value.rows() } },
        "savedAt" to savedAt,
    )

    companion object {
        /** The cursor in [m] ([json]); null when it is not one. */
        fun parse(m: Map<*, *>): DownloadCursor? = runCatching {
            val z = (m["z"] as? Number)?.toInt()
            val row = if (z == null) null else TileRow(z, m["route"] as String, (m["y"] as Number).toInt(), (m["x0"] as Number).toInt(), ((m["x1"] ?: m["x0"]) as Number).toInt())
            val failed = (m["failed"] as? Map<*, *>).orEmpty().mapNotNull { (route, zs) ->
                if (route !is String || zs !is Map<*, *>) null else route to zs.mapNotNull { (zk, rows) ->
                    val zz = zk.toString().toIntOrNull() ?: return@mapNotNull null
                    zz to TileSet.fromRows(zz, (rows as List<*>).map { r -> (r as List<*>).map { (it as Number).toInt() } })
                }.toMap()
            }.toMap()
            DownloadCursor(row, (m["wholeRows"] as? Number)?.toInt() ?: 0, failed, (m["savedAt"] as? Number)?.toLong() ?: 0L)
        }.getOrNull()
    }
}

/**
 * T151: the automatic resume's state in a record. [failures]: runs in a row that fetched nothing and were not cut off by
 * the network; [attempts]: runs in a row that did not finish (the index into [ResumeCheck.BACKOFF_MS]); [nextAt]: when the
 * next automatic try may start (the user's triggers do not wait for it); [stopped]: why the resumes stopped
 * ([STOP_FAILURES], or [STOP_REFUSED] with the [budgetMb] that refused it); [runFrom]: the cursor's [DownloadCursor.key]
 * when the run now going started (the repair at start compares it to tell an interrupted run that got somewhere from one
 * that did not, so a download that crashes the app at the same row stops after [ResumeCheck.MAX_FAILURES] starts).
 */
data class AutoResume(
    val failures: Int = 0, val attempts: Int = 0, val nextAt: Long = 0L, val stopped: String? = null, val budgetMb: Long? = null,
    val runFrom: String? = null, val trigger: String? = null,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "failures" to failures, "attempts" to attempts, "nextAt" to nextAt, "stopped" to stopped, "budgetMb" to budgetMb, "runFrom" to runFrom, "trigger" to trigger,
    )

    companion object {
        const val STOP_FAILURES = "failures"
        const val STOP_REFUSED = "refused"

        fun parse(m: Map<*, *>): AutoResume = AutoResume(
            failures = (m["failures"] as? Number)?.toInt() ?: 0, attempts = (m["attempts"] as? Number)?.toInt() ?: 0,
            nextAt = (m["nextAt"] as? Number)?.toLong() ?: 0L, stopped = m["stopped"] as? String, budgetMb = (m["budgetMb"] as? Number)?.toLong(),
            runFrom = m["runFrom"] as? String, trigger = m["trigger"] as? String,
        )
    }
}

/** T151: the card's line when a download stopped as the app closed. */
internal const val RESUME_INTERRUPTED = "The download stopped when the app closed."

/** T151: the card's line when the network stopped answering during a download. */
internal const val RESUME_STALLED = "The download paused: the network stopped answering."

/** T151: the card's line once the resumes stopped after repeated failures. */
internal const val RESUME_STOPPED = "The download kept failing, so it stopped trying; press Retry to try again."

/**
 * T151: the repair at start of [r] when no job runs for it ([live] is the running job's id): a record left "downloading"
 * becomes "partial" (or "cancelled" when the user had cancelled it); null when it needs none. An interrupted run that did
 * not move the cursor counts as a failure ([AutoResume.runFrom]). Pure, tested.
 */
internal fun repairedAtStart(r: Areas.Record, live: String?): Areas.Record? {
    if (r.state != "downloading" || r.pack != null || r.opaque != null || r.id == live) return null
    if (r.cancelled) return r.copy(state = "cancelled", error = null)
    val prev = r.autoResume ?: AutoResume()
    val progressed = prev.runFrom == null || prev.runFrom != (r.cursor?.key ?: "")
    val failures = if (progressed) 0 else prev.failures + 1
    val stopped = if (failures >= ResumeCheck.MAX_FAILURES) AutoResume.STOP_FAILURES else null
    return r.copy(
        state = "partial", error = if (stopped != null) RESUME_STOPPED else RESUME_INTERRUPTED,
        autoResume = prev.copy(failures = failures, attempts = if (progressed) 0 else prev.attempts, nextAt = 0L, stopped = stopped, runFrom = null),
    )
}

/** T151: what a check found: the unfinished records with why each waits, the one to start, the earliest backoff, the job waited for. */
internal data class ResumeDecision(val unfinished: List<Map<String, Any?>>, val start: String?, val nextAt: Long?, val waitingFor: String?)

/** T151: a record the resumes look at: a tile download (not a pack, not opaque) left downloading or partial. Pure. */
internal fun unfinished(r: Areas.Record): Boolean = r.pack == null && r.opaque == null && (r.state == "downloading" || r.state == "partial")

/**
 * T151: the cheap check's decision over [records] (in memory): each unfinished one with its reason (`running`, `ending`,
 * `cancelled`, `stopped`, `refused`, `backoff`, `ready`), the oldest `ready` one to start when no job runs ([live] null), the
 * earliest backoff's end, and the running job when something waits for it. [userPresent] (app start, foreground) skips the
 * backoff's wait; a budget refusal waits for another [budgetMb]. Pure, tested.
 */
internal fun decide(records: List<Areas.Record>, live: String?, now: Long, budgetMb: Long, userPresent: Boolean): ResumeDecision {
    val out = ArrayList<Map<String, Any?>>()
    val ready = ArrayList<Areas.Record>()
    var nextAt: Long? = null
    for (r in records) {
        if (!unfinished(r)) continue
        val a = r.autoResume
        val why = when {
            r.id == live -> "running"
            r.cancelled -> "cancelled"
            a?.stopped == AutoResume.STOP_FAILURES -> "stopped"
            a?.stopped == AutoResume.STOP_REFUSED && a.budgetMb == budgetMb -> "refused"
            r.state == "downloading" -> "ending" // its job just ended and the record is being written
            !userPresent && a != null && a.nextAt > now -> "backoff".also { nextAt = minOf(nextAt ?: Long.MAX_VALUE, a.nextAt) }
            else -> "ready".also { ready += r }
        }
        out += linkedMapOf("id" to r.id, "name" to r.name, "state" to r.state, "why" to why, "cursor" to r.cursor?.key, "failedTiles" to r.cursor?.failedTiles, "nextAt" to a?.nextAt?.takeIf { it > 0 })
    }
    val start = if (live == null) ready.minByOrNull { it.createdAt }?.id else null
    return ResumeDecision(out, start, nextAt, if (live != null && ready.isNotEmpty()) live else null)
}

/**
 * T151: a record's [AutoResume] after a run ended in [state] (`done`, `partial`, `failed`, `refused`, `cancelled`):
 * [progressed] when it fetched any tile, [stalled] when the network stopped answering. Done clears it; a refusal stops the
 * resumes until the budget ([budgetMb]) changes; a partial end backs off ([ResumeCheck.BACKOFF_MS] by attempt) and, after
 * [ResumeCheck.MAX_FAILURES] runs in a row with no progress that were not stalls, stops. Pure, tested.
 */
internal fun afterRun(prev: AutoResume?, state: String, progressed: Boolean, stalled: Boolean, now: Long, budgetMb: Long): AutoResume? {
    val p = (prev ?: AutoResume()).copy(runFrom = null)
    return when (state) {
        "done" -> null
        "refused" -> p.copy(stopped = AutoResume.STOP_REFUSED, budgetMb = budgetMb)
        "partial" -> {
            val attempts = if (progressed) 1 else p.attempts + 1
            val failures = when { progressed -> 0; stalled -> p.failures; else -> p.failures + 1 }
            val wait = ResumeCheck.BACKOFF_MS[minOf(attempts, ResumeCheck.BACKOFF_MS.size) - 1]
            p.copy(attempts = attempts, failures = failures, nextAt = now + wait, stopped = if (failures >= ResumeCheck.MAX_FAILURES) AutoResume.STOP_FAILURES else null)
        }
        else -> p // failed (not resumed) or cancelled (the record's flag)
    }
}

/**
 * T151: the card's line for an unfinished download that continues by itself, "Continues by itself" or "... in about N min"
 * during a backoff; null when it does not (finished, cancelled, stopped, or running). Pure, tested.
 */
internal fun resumeLine(r: Areas.Record, now: Long): String? {
    if (r.pack != null || r.opaque != null || r.cancelled || r.state != "partial" || r.autoResume?.stopped != null) return null
    val wait = (r.autoResume?.nextAt ?: 0L) - now
    return if (wait > 60_000) "Continues by itself in about ${(wait + 59_999) / 60_000} min" else "Continues by itself"
}

/**
 * T151: one run for any number of requests that arrive while one is queued: [request] queues a run on [execute] unless one
 * is queued already, and the run gets every trigger asked for until it starts. A request during a run queues the next one.
 */
internal class Coalescer(private val execute: (Runnable) -> Unit, private val run: (List<String>) -> Unit) {
    private val lock = Any()
    private var pending = false
    private val triggers = ArrayList<String>()

    val queued: Boolean get() = synchronized(lock) { pending }

    /** True when this request queued a run (false: it joined the one queued). */
    fun request(trigger: String): Boolean {
        synchronized(lock) {
            triggers += trigger
            if (pending) return false
            pending = true
        }
        execute(Runnable {
            val t = synchronized(lock) { pending = false; triggers.toList().also { triggers.clear() } }
            run(t)
        })
        return true
    }
}
