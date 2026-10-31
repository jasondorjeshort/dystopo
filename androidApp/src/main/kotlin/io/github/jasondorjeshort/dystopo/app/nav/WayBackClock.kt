package io.github.jasondorjeshort.dystopo.app.nav

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.tracking.WayBackAnchor
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.nav.AnchorSource
import io.github.jasondorjeshort.dystopo.nav.Deadline
import io.github.jasondorjeshort.dystopo.nav.DeadlineSetting
import io.github.jasondorjeshort.dystopo.nav.TurnAlerts
import io.github.jasondorjeshort.dystopo.nav.TurnAround
import io.github.jasondorjeshort.dystopo.nav.TurnClock
import io.github.jasondorjeshort.dystopo.nav.TurnPace
import io.github.jasondorjeshort.dystopo.nav.TurnState
import io.github.jasondorjeshort.dystopo.nav.WayBack
import io.github.jasondorjeshort.dystopo.nav.WayPoint
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.store.LocationFix

/**
 * T180, the way back on a recording: T163's turn-around clock in free tracking, on screen while a track records and no
 * route is navigated, so a recording needs nothing set (the deadline and the margin are the standing settings; no
 * prompt at Record and no trip-type question: the survey found no app that asks).
 *
 * - **The line:** the recorded track from the way back's start ([WayBack]: the first good fix, or the walk after a drive
 *   by the speed rule, or "The way back starts here", kept per device by [WayBackAnchor]), its direction read by
 *   [io.github.jasondorjeshort.dystopo.nav.Retrace]: out, the way back is the whole line; after a turn, the old line
 *   from where the walker is on it, and the clock gives the arrival ("Out by").
 * - **On screen:** the card in the navigation card's place ([NavOverlay]'s `WayBackCard`) and a line on the recording
 *   card (`tracking/TrackControls.kt`), from [ui]: "Turn back by 14:10" with "Sunset 19:12, flat horizon, 30 min margin,
 *   standard pace", amber at the margin and red at the time, "Heading back" after the flip.
 * - **Alerts:** one tone and buzz at the margin and one at the time through navigation's alert path ([NavSession]'s
 *   [NavAlerts]), and the one spoken line through [NavSpeech.sayTurnAround] (the spoken-directions setting and its mute).
 *   Navigating, navigation's own clock runs instead and this one is quiet.
 *
 * Fed by `tracking/Tracking.kt` on its thread ([onPoint], [onStart], [onStop]); read every second on the main thread
 * ([tick]). Debug: `GET /nav/turnaround` without navigation, `GET /tracks` `wayBack`, `POST /tracks/wayback`.
 */
object WayBackClock {
    /** What the card and the recording line show; null when nothing records, while navigating, or before a fix. */
    data class Ui(
        val text: String,
        val label: String,
        val colour: String,
        val headingBack: Boolean,
        /** Where the way back goes: [AnchorSource.key]. */
        val source: String,
        /** The recording card's line naming it ("Way back to the recording's start"). */
        val sourceLine: String,
    )

    /** The recording card's words for each source (ADR 0031: the owner's to adjust). */
    fun sourceLine(s: AnchorSource): String = when (s) {
        AnchorSource.START -> "Way back to the recording's start"
        AnchorSource.WALK -> "Way back to where the drive ended"
        AnchorSource.HERE -> "Way back to where you set it"
    }

    const val HEADING_BACK = "Heading back"
    const val STARTS_HERE = "The way back starts here"

    private val main = Handler(Looper.getMainLooper())
    private var trackId: String? = null
    private var wayBack: WayBack? = null
    private var override: WayBackAnchor.Here? = null
    private val alerts = TurnAlerts()
    private var spoken: String? = null
    private val events = ArrayList<Map<String, Any?>>()
    private var lastClock: TurnClock? = null
    private var lastPoint: WayPoint? = null
    @Volatile private var ticking = false
    private var rebuilds = 0
    private var pointsFed = 0L

    private fun pace() = TurnPace.of(CostModel(TravelMode.HIKE))

    /** A recording started (tracking thread): a fresh line, no alerts given, the spoken line not said. */
    @Synchronized
    fun onStart(id: String) {
        trackId = id
        override = WayBackAnchor.get(id)
        wayBack = WayBack(pace())
        alerts.reset()
        events.clear()
        spoken = null
        lastClock = null
        lastPoint = null
        startTicker()
    }

    /** The recording stopped (any thread): the card goes, and the speech engine is released when nothing navigates. */
    fun onStop() {
        synchronized(this) {
            trackId = null
            wayBack = null
            lastClock = null
        }
        ticking = false
        main.post { NavUiState.wayBack = null }
        if (!NavSession.active && spoken != null) NavSpeech.end()
    }

    /**
     * A fix the recorder kept for track [id] (tracking thread); [points] gives the whole recording when the line must be
     * rebuilt (a recording resumed after a process death, an override set), read on this same thread.
     */
    fun onPoint(id: String, fix: LocationFix, flagged: Boolean, points: () -> List<TrackPoint>) {
        synchronized(this) {
            val p = WayPoint(fix.lat, fix.lon, fix.ele, fix.time, fix.speedMps, flagged)
            if (trackId != id || wayBack == null) {
                rebuild(id, points())
            } else {
                wayBack!!.add(p)
                pointsFed++
            }
            lastPoint = p
        }
        startTicker()
    }

    /** Rebuilds the line for [id] from [points] (the recorder's, in order), with the override when one is kept. */
    @Synchronized
    fun rebuild(id: String, points: List<TrackPoint>) {
        if (trackId != id) {
            alerts.reset()
            events.clear()
            spoken = null
        }
        trackId = id
        override = WayBackAnchor.get(id)
        val idx = override?.let { h -> points.indexOfFirst { (it.time ?: Long.MIN_VALUE) >= h.pointTimeMs }.takeIf { it >= 0 } ?: (points.size - 1) }
        val wb = WayBack(pace(), idx)
        for (tp in points) wb.add(WayPoint(tp.lat, tp.lon, tp.ele, tp.time, tp.speedMps, tp.flags != 0))
        wayBack = wb
        lastPoint = wb.points.lastOrNull()
        rebuilds++
        Log.i(NavSession.TAG, "way back rebuilt for $id: ${points.size} points, start ${wb.anchorSource.key} at point ${wb.anchorIndex}")
    }

    /**
     * "The way back starts here" (tracking thread, through `Tracking.wayBackHere`): the way back of [id] starts at its last
     * recorded point, kept per device; [points] the recording. False with no point yet.
     */
    fun here(id: String, points: List<TrackPoint>): Boolean {
        val last = points.lastOrNull() ?: return false
        WayBackAnchor.set(WayBackAnchor.Here(id, last.time ?: System.currentTimeMillis(), last.lat, last.lon, System.currentTimeMillis()))
        rebuild(id, points)
        refreshNow()
        return true
    }

    /** Back to the rule's start (`POST /tracks/wayback?reset=1`; tracking thread). */
    fun reset(id: String, points: List<TrackPoint>) {
        WayBackAnchor.clear(id)
        rebuild(id, points)
        refreshNow()
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        main.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            runCatching { tick(System.currentTimeMillis()) }.onFailure { Log.w(NavSession.TAG, "way back clock", it) }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun refreshNow() = main.post { runCatching { tick(System.currentTimeMillis()) } }

    /** Reads the clock at [now] and publishes it (main thread); quiet while navigating. */
    private fun tick(now: Long) {
        if (NavSession.active) {
            if (NavUiState.wayBack != null) NavUiState.wayBack = null
            return
        }
        val c = synchronized(this) { reading(now) }
        val src = synchronized(this) { wayBack?.anchorSource }
        val u = if (c == null || src == null) null else Ui(
            c.text(::clock), c.label(::clock), c.colour, c.state == TurnState.RETURNING && synchronized(this) { wayBack?.retrace?.headingBack == true },
            src.key, sourceLine(src),
        )
        if (NavUiState.wayBack != u) NavUiState.wayBack = u
        c?.let { alert(it) }
    }

    /** The clock now, kept as [lastClock]; null with nothing recording or no point yet. */
    private fun reading(now: Long): TurnClock? {
        val wb = wayBack ?: return null
        val start = wb.anchor ?: return null
        val setting = DeadlineSetting.parse(AppSettings.turnDeadline) ?: DeadlineSetting.SUNSET
        val deadline = Deadline.resolve(setting, start.lat, start.lon, now, localMidnight(now))
        return runCatching { wb.clock(now, deadline, AppSettings.turnMarginMin) }.getOrNull().also { lastClock = it }
    }

    /** T163's alerts in free tracking: a tone and a buzz at the margin and at the time, the spoken line once. */
    private fun alert(c: TurnClock) {
        val due = synchronized(this) { alerts.due(c) }
        for (k in due) {
            NavSession.turnAroundAlert(k.key)
            val line = synchronized(this) { if (spoken == null) c.spoken(::clock).also { spoken = it } else null }
            val said = line?.let { NavSpeech.sayTurnAround(it) }
            synchronized(this) {
                events += linkedMapOf(
                    "kind" to k.key, "atMs" to c.nowMs, "text" to c.text(::clock), "colour" to c.colour, "spoken" to line,
                    "speech" to (said?.let { if (it.muted) "muted" else it.engine } ?: line?.let { "off" }),
                )
            }
            Log.i(NavSession.TAG, "way back turn-around ${k.key}: ${c.text(::clock)}" + (line?.let { " / said: $it" } ?: ""))
        }
    }

    /** `GET /nav/turnaround` while a track records and nothing is navigated, and `GET /tracks` `wayBack`; null otherwise. */
    fun json(): Map<String, Any?>? = synchronized(this) {
        val id = trackId ?: return null
        val wb = wayBack ?: return linkedMapOf("source" to "recording", "id" to id, "error" to "no point recorded yet")
        val now = System.currentTimeMillis()
        val c = reading(now)
        linkedMapOf(
            "source" to "recording", "id" to id, "points" to wb.points.size,
            "anchor" to (wb.json()["anchor"] as Map<*, *>) + mapOf("line" to sourceLine(wb.anchorSource), "override" to override?.let { linkedMapOf("pointTimeMs" to it.pointTimeMs, "setAtMs" to it.setAtMs) }),
            "retrace" to wb.retrace.json(), "headingBack" to wb.retrace.headingBack,
            "deadlineSetting" to AppSettings.turnDeadline, "marginMin" to AppSettings.turnMarginMin, "pace" to wb.pace.label,
            "text" to c?.text(::clock), "label" to c?.label(::clock), "clock" to c?.json(),
            "turnBack" to c?.turnBackMs?.let(::clock), "arrival" to c?.arrivalMs?.let(::clock), "deadline" to c?.deadline?.atMs?.let(::clock),
            "alerts" to alerts.json() + mapOf("events" to ArrayList(events)), "spoken" to spoken,
            "ui" to NavUiState.wayBack?.let { linkedMapOf("text" to it.text, "label" to it.label, "colour" to it.colour, "headingBack" to it.headingBack, "sourceLine" to it.sourceLine) },
            "shownOnCard" to (NavUiState.wayBack != null && !NavSession.active), "navigating" to NavSession.active,
            "rebuilds" to rebuilds, "pointsFed" to pointsFed,
        )
    }

    private fun clock(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "%d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    private fun localMidnight(now: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    const val TICK_MS = 1000L
}
