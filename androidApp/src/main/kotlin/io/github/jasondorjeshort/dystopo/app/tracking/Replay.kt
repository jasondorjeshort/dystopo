package io.github.jasondorjeshort.dystopo.app.tracking

import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.store.TrackExport

/**
 * Plays a GPX file's track points back as injected location fixes (debug `POST /location/replay`),
 * at `rate` times real time, keeping the file's own timestamps on the fixes. Each fix goes to the
 * recorder (through [Tracking.injected]) and to the map's location puck. One replay at a time; a new
 * one cancels the old. T146: [startTracks] replays tracks built in the app (`POST /location/replay?route=current&kmh=`,
 * the current route as a timed track) with no request body, so the loopback server's 1 MiB body limit does not bind;
 * [finished] says the last replay sent every fix (`GET /location/replay` `finished`).
 *
 * T159: a [ReplayScript] scripts what a failing GPS does: fixes at a given accuracy over a range of fixes, and a stall
 * (no fix for some seconds of real time before a given fix; the fixes after it later by as much), so the watchdog and
 * the network fallback run on the emulator. The puck moves only while the app is in the foreground (a stopped map has
 * nothing to draw, and the replay should cost the main thread nothing in the background).
 */
class Replay(private val tracking: Tracking) {
    @Volatile private var thread: Thread? = null
    @Volatile var total = 0
        private set
    @Volatile var sent = 0
        private set
    @Volatile var rate = 1.0
        private set
    @Volatile var startedAt = 0L
        private set
    @Volatile var durationMs = 0L
        private set
    /** True while no replay thread is feeding fixes (before the first, after one ends or is cancelled). */
    @Volatile private var idle = true

    /** T146: true once the last replay sent its last fix; false while one runs, after a cancel, a failure, or before any. */
    @Volatile var finished = false
        private set
    @Volatile var cancelled = false
        private set

    /** T146: where the last replay's fixes came from: `body` (a posted GPX) or `route` (the current route, timed). */
    @Volatile var source: String? = null
        private set
    @Volatile var kmh: Double? = null
        private set

    val running: Boolean get() = !idle

    /**
     * Parses [gpx] and starts feeding its fixes; returns the schedule (fix count, duration). Throws on
     * a bad file or one without track points.
     *
     * Replays keep the file's (old) times, and the recorder drops a fix that is not newer than the
     * last one it kept. So replaying into a track that already holds a newer fix (a GPS or
     * `POST /location` fix stamped "now", e.g. when recording started a moment before the replay)
     * would record nothing after that point: the single-point track seen once in T31. So the replay is
     * moved onto the recording's timeline ([TrackExport.continueAfter], T33): its first fix comes one
     * second after the last kept one, intervals unchanged; the result reports `shiftedMs`.
     */
    fun start(gpx: String, rate: Double, accuracyM: Double = 5.0, script: (Int) -> ReplayScript = { ReplayScript() }): Map<String, Any?> =
        startTracks(Gpx.parse(gpx).tracks, rate, accuracyM, "body", null, script)

    /**
     * T146: replays [tracks] (built in the app, no GPX text) as [start] does; [source] and [kmh] are reported. T159: [script]
     * gets the number of fixes (for a stall in the middle) and gives the script.
     */
    fun startTracks(tracks: List<Track>, rate: Double, accuracyM: Double, source: String, kmh: Double?, script: (Int) -> ReplayScript = { ReplayScript() }): Map<String, Any?> {
        val plain = TrackExport.replay(tracks, rate, accuracyM)
        require(plain.isNotEmpty()) { "the GPX has no track points" }
        val sc = script(plain.size)
        val original = sc.apply(plain)
        val last = tracking.call { tracking.recorder.lastAcceptedTime }
        val timed = TrackExport.continueAfter(original, last)
        val shiftedMs = timed.first().fix.time - original.first().fix.time
        cancel()
        val gen = generation.incrementAndGet()
        total = timed.size
        sent = 0
        this.rate = rate
        durationMs = timed.last().atMs
        this.source = source
        this.kmh = kmh
        this.script = sc
        idle = false
        finished = false
        cancelled = false
        startedAt = SystemClock.elapsedRealtime()
        val start = startedAt
        val t = Thread({
            // T146: only the current run (generation) writes the counters. A cancel that landed while the puck's
            // injectLocation waited on the main thread was swallowed by its runCatching, and the old run went on
            // feeding fixes at its own rate beside the new one, counting into its `sent` (seen: a rate-30 drive left
            // running under the battery script's rate-1 replay).
            fun current() = generation.get() == gen
            try {
                for (f in timed) {
                    val wait = start + f.atMs - SystemClock.elapsedRealtime()
                    if (wait > 0) Thread.sleep(wait)
                    if (!current()) throw InterruptedException()
                    tracking.injected.publish(f.fix)
                    // Move the puck too (best effort; the map may not be attached); T159: only while the map is on screen.
                    val moved = if (!io.github.jasondorjeshort.dystopo.app.Foreground.started) Result.success(Unit)
                    else runCatching { DystopoApp.instance.map.injectLocation(f.fix.lat, f.fix.lon, (f.fix.accuracyM ?: accuracyM).toFloat(), null) }
                    if (moved.exceptionOrNull() is InterruptedException || !current()) throw InterruptedException()
                    sent++
                }
                if (current()) finished = true
            } catch (_: InterruptedException) {
                if (current()) cancelled = true
            } catch (e: Exception) {
                Log.w(Tracking.TAG, "replay failed", e)
            } finally {
                if (current()) idle = true
            }
        }, "dystopo-replay").apply { isDaemon = true }
        thread = t
        t.start()
        return json() + mapOf("shiftedMs" to shiftedMs)
    }

    /** T159: the last replay's script (accuracy ranges and a stall). */
    @Volatile var script: ReplayScript = ReplayScript()
        private set

    /** T146: bumped by every start and cancel; a replay thread stops at its next fix once it is no longer current. */
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    fun cancel() {
        val t = thread
        generation.incrementAndGet()
        if (t != null && !idle) {
            cancelled = true
            idle = true
        }
        t?.interrupt()
        t?.join(2000)
        thread = null
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "running" to !idle,
        "finished" to finished, // T146
        "cancelled" to cancelled,
        "source" to source,
        "kmh" to kmh,
        "fixes" to total,
        "sent" to sent,
        "rate" to rate,
        "durationMs" to durationMs,
        "elapsedMs" to if (startedAt == 0L) null else SystemClock.elapsedRealtime() - startedAt,
        // T159: the script, and whether the replay is in its stall now (waiting to send the fix after it).
        "script" to script.json(),
        "stalling" to (!idle && script.stall?.beforeFix == sent),
    )
}

/**
 * T159: what a replay ([Replay], `POST /location/replay`) does besides playing the file: [ranges] report fixes at a
 * given accuracy (over the point's own and the `accuracy=` default), and [stall] holds every fix from one on back by
 * some seconds of real time, their times later by as much, so the recorder sees the GPS go silent. Pure, unit-tested
 * (`ReplayScriptTest`).
 *
 * Query forms: `accuracy=5` (every fix without its own), `accuracy=5,120@40-60,200@80` (fixes 40 to 60 at 120 m and fix
 * 80 at 200 m, numbered from 0); `stall=60` (60 s before the middle fix) or `stall=60@100` (before fix 100).
 */
data class ReplayScript(val ranges: List<AccuracyRange> = emptyList(), val stall: Stall? = null) {
    data class AccuracyRange(val accuracyM: Double, val from: Int, val to: Int)
    data class Stall(val seconds: Double, val beforeFix: Int)

    fun apply(timed: List<TrackExport.Timed>): List<TrackExport.Timed> {
        val stallMs = stall?.let { (it.seconds * 1000).toLong() } ?: 0L
        return timed.mapIndexed { i, t ->
            var fix = t.fix
            ranges.lastOrNull { i in it.from..it.to }?.let { fix = fix.copy(accuracyM = it.accuracyM) }
            if (stall != null && i >= stall.beforeFix) TrackExport.Timed(t.atMs + stallMs, fix.copy(time = fix.time + stallMs))
            else t.copy(fix = fix)
        }
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "accuracy" to ranges.map { mapOf("accuracyM" to it.accuracyM, "from" to it.from, "to" to it.to) },
        "stall" to stall?.let { mapOf("seconds" to it.seconds, "beforeFix" to it.beforeFix) },
    )

    companion object {
        private val range = Regex("""^(\d+(?:\.\d+)?)@(\d+)(?:-(\d+))?$""")
        private val stallForm = Regex("""^(\d+(?:\.\d+)?)(?:@(\d+))?$""")

        /** The default accuracy in `accuracy=` (its plain number), or null; throws [IllegalArgumentException] as [parse] does. */
        fun defaultAccuracy(accuracy: String?): Double? = parts(accuracy).mapNotNull { it.toDoubleOrNull() }.lastOrNull()?.also {
            require(it > 0 && it.isFinite()) { "accuracy must be above 0 m" }
        }

        private fun parts(spec: String?): List<String> = spec?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        /**
         * Parses `accuracy=` (its ranges; the plain number is [defaultAccuracy]) and `stall=` for a replay of [fixes]
         * fixes; throws [IllegalArgumentException] with a readable message.
         */
        fun parse(accuracy: String?, stall: String?, fixes: Int): ReplayScript {
            defaultAccuracy(accuracy)
            val ranges = parts(accuracy).filter { it.toDoubleOrNull() == null }.map { part ->
                val m = range.matchEntire(part) ?: throw IllegalArgumentException("accuracy must be metres, metres@fix or metres@from-to (fixes from 0): $part")
                val a = m.groupValues[1].toDouble()
                val from = m.groupValues[2].toInt()
                val to = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: from
                require(a > 0) { "accuracy must be above 0 m: $part" }
                require(to >= from) { "accuracy range runs backwards: $part" }
                AccuracyRange(a, from, to)
            }
            val st = stall?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
                val m = stallForm.matchEntire(s) ?: throw IllegalArgumentException("stall must be seconds or seconds@fix: $s")
                val seconds = m.groupValues[1].toDouble()
                require(seconds > 0 && seconds <= 3600) { "stall must be above 0 and at most 3600 s" }
                val at = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: (fixes / 2)
                require(at in 1 until maxOf(fixes, 2)) { "the stall must come before a fix from 1 to ${fixes - 1}" }
                Stall(seconds, at)
            }
            return ReplayScript(ranges, st)
        }
    }
}

/** T65: when `POST /location/replay` is refused, kept apart from the route so it is unit-tested. */
object ReplayChecks {
    /**
     * Status and message for a refused replay, or null to go ahead: a rate outside (0, 10000] or no body is a
     * 400; `record=` while a track is already [recording] is a 409 (T57: the replay's own track would leave that
     * one behind), while a replay without `record=` adds its fixes to the recording. T146: with [route] (only
     * `current`) the fixes come from the current route timed at [kmh] (above 0, at most [maxKmh]) and there is no body;
     * a body as well is a 400. [hasRoute] false with `route=current` is a 404 (no route planned).
     */
    fun refusal(
        rate: Double, bodyEmpty: Boolean, record: String?, recording: Boolean,
        route: String? = null, kmh: Double? = null, hasRoute: Boolean = true, maxKmh: Double = 300.0,
    ): Pair<Int, String>? = when {
        !(rate > 0 && rate <= 10_000) -> 400 to "rate must be in (0, 10000]"
        route != null && route != "current" -> 400 to "route must be current (the route on the map)"
        route != null && !bodyEmpty -> 400 to "POST a GPX body or pass route=current, not both"
        route != null && !(kmh != null && kmh > 0 && kmh <= maxKmh) -> 400 to "route=current needs kmh=<speed in km/h>, above 0 and at most ${maxKmh.toInt()}"
        route == null && bodyEmpty -> 400 to "POST a GPX file as the body, or pass route=current&kmh=<n> to replay the current route"
        record != null && recording -> 409 to "already recording: stop that track first (POST /tracks/stop), or replay without record= to add the fixes to it"
        route != null && !hasRoute -> 404 to "no route: plan one first (POST /route)"
        else -> null
    }
}
