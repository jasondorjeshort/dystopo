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

    /** Parses [gpx] and starts feeding its fixes; returns the schedule (fix count, duration). Throws on a bad file or one without track points. */
    fun start(gpx: String, rate: Double, accuracyM: Double = 5.0): Map<String, Any?> {
        val doc = Gpx.parse(gpx)
        val timed = TrackExport.replay(doc.tracks, rate, accuracyM)
        require(timed.isNotEmpty()) { "the GPX has no track points" }
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
        return json()
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
