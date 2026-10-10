package io.github.jasondorjeshort.dystopo.app.tracking

import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.store.TrackExport

/**
 * Plays a GPX file's track points back as injected location fixes (debug `POST /location/replay`),
 * at `rate` times real time, keeping the file's own timestamps on the fixes. Each fix goes to the
 * recorder (through [Tracking.injected]) and to the map's location puck. One replay at a time; a new
 * one cancels the old.
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
    @Volatile var finished = true
        private set

    val running: Boolean get() = !finished

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
    fun start(gpx: String, rate: Double, accuracyM: Double = 5.0): Map<String, Any?> {
        val doc = Gpx.parse(gpx)
        val original = TrackExport.replay(doc.tracks, rate, accuracyM)
        require(original.isNotEmpty()) { "the GPX has no track points" }
        val last = tracking.call { tracking.recorder.lastAcceptedTime }
        val timed = TrackExport.continueAfter(original, last)
        val shiftedMs = timed.first().fix.time - original.first().fix.time
        cancel()
        total = timed.size
        sent = 0
        this.rate = rate
        durationMs = timed.last().atMs
        finished = false
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
            } catch (_: InterruptedException) {
                // cancelled
            } catch (e: Exception) {
                Log.w(Tracking.TAG, "replay failed", e)
            } finally {
                finished = true
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

    fun json(): Map<String, Any?> = mapOf(
        "running" to !finished,
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
     * one behind), while a replay without `record=` adds its fixes to the recording.
     */
    fun refusal(rate: Double, bodyEmpty: Boolean, record: String?, recording: Boolean): Pair<Int, String>? = when {
        !(rate > 0 && rate <= 10_000) -> 400 to "rate must be in (0, 10000]"
        bodyEmpty -> 400 to "POST a GPX file as the body"
        record != null && recording -> 409 to "already recording: stop that track first (POST /tracks/stop), or replay without record= to add the fixes to it"
        else -> null
    }
}
