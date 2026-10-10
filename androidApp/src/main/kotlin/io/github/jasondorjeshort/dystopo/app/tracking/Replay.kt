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
     * records nothing after that point: the single-point track seen once in T31. The result then
     * carries a `warning`; record into a fresh track with `record=` (source `injected`) instead.
     */
    fun start(gpx: String, rate: Double, accuracyM: Double = 5.0): Map<String, Any?> {
        val doc = Gpx.parse(gpx)
        val timed = TrackExport.replay(doc.tracks, rate, accuracyM)
        require(timed.isNotEmpty()) { "the GPX has no track points" }
        val last = tracking.call { tracking.recorder.lastAcceptedTime }
        val warning = if (last != null && last >= timed.first().fix.time) {
            "the recording already holds a fix newer than this replay's first (${timed.first().fix.time} <= $last): " +
                "the recorder will drop the replayed fixes as out of order; use record=<name> to replay into a fresh track"
        } else null
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
        return if (warning == null) json() else json() + mapOf("warning" to warning)
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
