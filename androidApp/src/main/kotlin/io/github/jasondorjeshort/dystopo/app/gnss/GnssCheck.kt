package io.github.jasondorjeshort.dystopo.app.gnss

import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.gnss.GnssHealth
import io.github.jasondorjeshort.dystopo.gpx.TrackStats

/**
 * T307: "Check my GPS": [GnssHealth.CHECK_SECONDS] of the GNSS status, one reading a second, while the phone stands still,
 * then [GnssHealth.judge]'s verdict with the terrain horizon where it stood ([Gnss.horizonAt]) and the session's time to
 * first fix. It holds the GNSS itself ([Gnss.hold] `check`), so it runs on when the view closes; it stops with
 * [GnssText.moved] when the phone moves more than [GnssHealth.MAX_MOVE_M] from where it started, and with
 * [GnssText.STOPPED] when stopped. The last result rides in Send diagnostics (`reports/DiagnosticDump.kt`, section
 * `gnss`), without the position.
 */
object GnssCheck {
    enum class State(val code: String) { IDLE("idle"), RUNNING("running"), DONE("done"), STOPPED("stopped") }

    /** A finished check: its verdict and when it ran, or why it stopped. */
    class Result(val atMs: Long, val seconds: Int, val source: String, val verdict: GnssHealth.Verdict?, val stopped: String?, val horizon: Boolean)

    @Volatile var state = State.IDLE
        private set
    @Volatile var seconds = GnssHealth.CHECK_SECONDS
        private set
    @Volatile var elapsedS = 0
        private set
    @Volatile var last: Result? = null
        private set
    @Volatile private var cancel = false
    @Volatile private var run = 0

    val secondsLeft: Int get() = (seconds - elapsedS).coerceAtLeast(0)

    /** Starts a check of [length] seconds; false while one runs. */
    @Synchronized
    fun start(length: Int = GnssHealth.CHECK_SECONDS): Boolean {
        if (state == State.RUNNING) return false
        seconds = length.coerceIn(1, 600)
        elapsedS = 0
        cancel = false
        state = State.RUNNING
        val mine = ++run
        Gnss.hold("check", true)
        Thread({ body(mine) }, "dystopo-gnss-check").apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        if (state == State.RUNNING) cancel = true
    }

    private fun body(mine: Int) {
        val samples = ArrayList<GnssHealth.Sample>()
        var anchor = Gnss.position()?.let { it.latitude to it.longitude }
        val t0 = SystemClock.elapsedRealtime()
        var stopped: String? = null
        try {
            while (true) {
                if (cancel) { stopped = GnssText.STOPPED; break }
                samples += GnssHealth.Sample(System.currentTimeMillis(), Gnss.satellites)
                val p = Gnss.position()
                if (anchor == null && p != null) anchor = p.latitude to p.longitude
                if (anchor != null && p != null && TrackStats.distanceM(anchor.first, anchor.second, p.latitude, p.longitude) > GnssHealth.MAX_MOVE_M) {
                    stopped = GnssText.moved(io.github.jasondorjeshort.dystopo.app.AppSettings.units)
                    break
                }
                anchor?.let { Gnss.horizonAt(it.first, it.second) } // started early, ready at the end
                elapsedS = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()
                if (elapsedS >= seconds) break
                Thread.sleep(1000L - (SystemClock.elapsedRealtime() - t0) % 1000L)
            }
            val horizon = if (stopped == null) anchor?.let { a ->
                val until = SystemClock.elapsedRealtime() + 20_000
                var h = Gnss.horizonAt(a.first, a.second)
                while (h == null && SystemClock.elapsedRealtime() < until) { Thread.sleep(200); h = Gnss.horizonAt(a.first, a.second) }
                h?.profile
            } else null
            val verdict = if (stopped == null) GnssHealth.judge(samples, horizon?.let { p -> { az: Double -> p.angleAt(az) } }, Gnss.ttffMs) else null
            if (mine == run) {
                last = Result(System.currentTimeMillis(), seconds, Gnss.source, verdict, stopped, horizon != null)
                state = if (stopped == null) State.DONE else State.STOPPED
            }
        } catch (e: Exception) {
            if (mine == run) {
                last = Result(System.currentTimeMillis(), seconds, Gnss.source, null, "${e.javaClass.simpleName}: ${e.message}", false)
                state = State.STOPPED
            }
        } finally {
            Gnss.hold("check", false)
        }
    }

    fun json(): Map<String, Any?> {
        val r = last
        return linkedMapOf(
            "state" to state.code, "seconds" to seconds, "elapsedS" to elapsedS, "secondsLeft" to secondsLeft,
            "last" to r?.let {
                linkedMapOf(
                    "atMs" to it.atMs, "seconds" to it.seconds, "source" to it.source, "stopped" to it.stopped, "horizon" to it.horizon,
                    "verdict" to it.verdict?.let(GnssHealth::json),
                    "headline" to it.verdict?.let { v -> GnssText.headline(v.kind) },
                    "advice" to it.verdict?.let { v -> GnssText.advice(v) },
                    "figures" to it.verdict?.let { v -> GnssText.figures(v) },
                )
            },
        )
    }

    /**
     * Send diagnostics' `gnss` section: the view's state, the status now and the last check, as `key=value` lines of whole
     * numbers and words (no position, no decimals the report scrubber could read as a coordinate).
     */
    fun dumpLines(): List<String> {
        val s = GnssHealth.summarize(Gnss.satellites)
        val out = ArrayList<String>()
        out += "state open=${if (GnssUi.open) 1 else 0} held=${if (Gnss.held) 1 else 0} engine=${Gnss.engine} source=${Gnss.source} permission=${if (Gnss.permitted()) 1 else 0}"
        out += "status inView=${s.inView} used=${s.used} best4=${s.best4Mean?.let { Math.round(it) } ?: "-"} constellations=${s.constellations.joinToString(",") { it.code }.ifEmpty { "-" }} " +
            "bands=${s.bands.joinToString("+") { it.label }.ifEmpty { "-" }} ttffMs=${Gnss.ttffMs ?: "-"} engineTtffMs=${Gnss.engineTtffMs ?: "-"} callbacks=${Gnss.statusCallbacks.get()}"
        val r = last
        out += if (r == null) "check none" else {
            val v = r.verdict
            "check atMs=${r.atMs} seconds=${r.seconds} source=${r.source} " + if (v == null) "stopped=${r.stopped?.replace(' ', '_')}" else
                "verdict=${v.kind.code} t4=${v.t4?.let { Math.round(it) } ?: "-"} t4c=${v.t4Constellation?.code ?: "-"} used=${v.usedMedian} inView=${v.inViewMedian} " +
                    "above=${v.aboveHorizon} behind=${v.behindHorizon} strongest=${v.strongestAbove?.let { Math.round(it) } ?: "-"} samples=${v.samples} horizon=${if (r.horizon) 1 else 0} " +
                    "notes=${v.notes.joinToString(",") { it.code }.ifEmpty { "-" }}"
        }
        out += "reset ${Gnss.lastReset?.let { if (it) "taken" else "refused" } ?: "none"}"
        return out
    }
}
