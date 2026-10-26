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
    fun start(gpx: String, rate: Double, accuracyM: Double = 5.0): Map<String, Any?> =
        startTracks(Gpx.parse(gpx).tracks, rate, accuracyM, "body", null)

    /** T146: replays [tracks] (built in the app, no GPX text) as [start] does; [source] and [kmh] are reported. */
    fun startTracks(tracks: List<Track>, rate: Double, accuracyM: Double, source: String, kmh: Double?): Map<String, Any?> {
        val original = TrackExport.replay(tracks, rate, accuracyM)
        require(original.isNotEmpty()) { "the GPX has no track points" }
        val last = tracking.call { tracking.recorder.lastAcceptedTime }
        val timed = TrackExport.continueAfter(original, last)
        val shiftedMs = timed.first().fix.time - original.first().fix.time
        cancel()
        total = timed.size
        sent = 0
        this.rate = rate
        durationMs = timed.last().atMs
        this.source = source
        this.kmh = kmh
        idle = false
        finished = false
        cancelled = false
        startedAt = SystemClock.elapsedRealtime()
        val start = startedAt
        val t = Thread({
            try {
                for (f in timed) {
                    val wait = start + f.atMs - SystemClock.elapsedRealtime()
                    if (wait > 0) Thread.sleep(wait)
                    tracking.injected.publish(f.fix)
                    // Move the puck too (best effort; the map may not be attached).
                    runCatching { DystopoApp.instance.map.injectLocation(f.fix.lat, f.fix.lon, accuracyM.toFloat(), null) }
                    sent++
                }
                finished = true
            } catch (_: InterruptedException) {
                cancelled = true
            } catch (e: Exception) {
                Log.w(Tracking.TAG, "replay failed", e)
            } finally {
                idle = true
            }
        }, "dystopo-replay").apply { isDaemon = true }
        thread = t
        t.start()
        return json() + mapOf("shiftedMs" to shiftedMs)
    }

    fun cancel() {
        thread?.interrupt()
        thread?.join(2000)
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
    )
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
