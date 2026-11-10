package io.github.jasondorjeshort.dystopo.app.nav

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.tracking.WayBackAnchor
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.nav.AnchorSource
import io.github.jasondorjeshort.dystopo.nav.DatedClock
import io.github.jasondorjeshort.dystopo.nav.Deadline
import io.github.jasondorjeshort.dystopo.nav.DeadlineSetting
import io.github.jasondorjeshort.dystopo.nav.TurnActivity
import io.github.jasondorjeshort.dystopo.nav.TurnAlerts
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
 * route is navigated. T252: **off by default** (the owner's ruling after three flights; the ADR 0039 survey found no app
 * with such a card always on, so the default is not a setting): the recording panel's "Turn-back time" chip turns it on
 * for this recording ([setOn], kept per track by [WayBackAnchor]); the deadline and the margin are the standing settings.
 *
 * - **The line:** the recorded track from the way back's start ([WayBack]: the first good fix, or the walk after a drive
 *   by the speed rule, or "The way back starts here", kept per device by [WayBackAnchor]), its direction read by
 *   [io.github.jasondorjeshort.dystopo.nav.Retrace]: out, the way back is the whole line; after a turn, the old line
 *   from where the walker is on it, and the clock gives the arrival ("Out by"). The line is fed while the card is off,
 *   so turning it on mid-recording times the whole way back.
 * - **The pace** (T252): the recording's activity ([activity]: the recording's while it records, as T250's readout reads
 *   it, else the remembered one) through its routing cost model ([TurnActivity.mode]: on foot walks, a bike rides, T164's
 *   fitted pace when the user has one); for any other activity (driving, a train, a boat, flying) no clock, the card
 *   saying why ([TurnActivity.noClockText]), and no clock either when the way back is longer than a day
 *   ([TurnActivity.MAX_WAY_BACK_S]): the card says nothing it cannot stand behind. A tap on the card shows the basis
 *   ([TurnActivity.basis]).
 * - **The date** (T252): the deadline is the recording's day's ([Deadline.dayFor]), never the reading's midnight, and
 *   every time the card shows off today carries its day ("21:10 yesterday", [DatedClock]). The 348 hours the owner saw
 *   after a flight were a walk home at the hiking pace: the deadline was the day's sunset, the turn-back time (the
 *   deadline less hundreds of hours of walking) two weeks earlier, shown as a bare clock time.
 * - **On screen:** the card in the navigation card's place ([NavOverlay]'s `WayBackCard`) and a line on the recording
 *   card (`tracking/TrackControls.kt`), from [ui]: "Turn back by 14:10" with "Sunset 19:12, flat horizon, 30 min margin,
 *   standard walking pace", amber at the margin and red at the time, "Heading back" after the flip.
 * - **Alerts:** one tone and buzz at the margin and one at the time through navigation's alert path ([NavSession]'s
 *   [NavAlerts]), and the one spoken line through [NavSpeech.sayTurnAround] (the spoken-directions setting and its mute);
 *   none while the card is off or shows no clock. Navigating, navigation's own clock runs instead and this one is quiet.
 *
 * Fed by `tracking/Tracking.kt` on its thread ([onPoint], [onStart], [onStop]); read every second on the main thread
 * ([tick]). Debug: `GET /nav/turnaround` without navigation, `GET /tracks` `wayBack`, `POST /tracks/wayback`.
 */
object WayBackClock {
    /** What the card and the recording line show; null when nothing records, the card is off, while navigating, or before a fix. */
    data class Ui(
        val text: String,
        val label: String,
        val colour: String,
        val headingBack: Boolean,
        /** Where the way back goes: [AnchorSource.key]. */
        val source: String,
        /** The recording card's line naming it ("Way back to the recording's start"). */
        val sourceLine: String,
        /** T252: false when the card says why there is no clock ([text] and [label] are that line). */
        val clock: Boolean = true,
        /** T252: the basis, shown when the card is tapped; null with no clock. */
        val basis: String? = null,
    )

    /** The recording card's words for each source (ADR 0031: the owner's to adjust). */
    fun sourceLine(s: AnchorSource): String = when (s) {
        AnchorSource.START -> "Way back to the recording's start"
        AnchorSource.WALK -> "Way back to where the drive ended"
        AnchorSource.HERE -> "Way back to where you set it"
    }

    const val HEADING_BACK = "Heading back"
    const val STARTS_HERE = "The way back starts here"

    /** T252: the recording panel's chip. */
    const val CHIP = "Turn-back time"

    /** T252: the card's basis is open (a tap on the card; `POST /tracks/wayback?basis=1|0`). */
    var basisOpen by mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())
    private var trackId: String? = null
    private var wayBack: WayBack? = null
    private var override: WayBackAnchor.Here? = null
    @Volatile private var on = false
    private val alerts = TurnAlerts()
    private var spoken: String? = null
    private val events = ArrayList<Map<String, Any?>>()
    private var lastClock: TurnClock? = null
    private var lastPoint: WayPoint? = null
    @Volatile private var ticking = false
    private var rebuilds = 0
    private var pointsFed = 0L

    /** T252: the card is on for the recording. */
    val isOn: Boolean get() = on

    /** T252: [isOn] for the chip to draw (main thread, Compose state). */
    var chipOn by mutableStateOf(false)
        private set

    private fun publishOn() { val v = on; main.post { chipOn = v } }

    /**
     * T252: the activity the clock runs on: the recording's while a track records (its track's, as the tracker publishes
     * it), else the remembered one (T250's rule for the readout). [activitySource] says which.
     */
    fun activity(): Activity = recordingActivity() ?: AppSettings.trackActivity

    val activitySource: String get() = if (recordingActivity() != null) "recording" else "remembered"

    private fun recordingActivity(): Activity? =
        runCatching { io.github.jasondorjeshort.dystopo.app.tracking.TrackingUiState.ui.takeIf { it.recording }?.activeActivity }.getOrNull()

    /** The pace for [a]: its mode's ([TurnActivity.mode]); for an activity with no clock the walking pace only feeds the line. */
    private fun pace(a: Activity = activity()) = TurnPace.forRecording(CostModel(TurnActivity.mode(a) ?: TravelMode.HIKE))

    /** A recording started (tracking thread): a fresh line, no alerts given, the spoken line not said; the card off unless kept on. */
    @Synchronized
    fun onStart(id: String) {
        trackId = id
        override = WayBackAnchor.get(id)
        on = WayBackAnchor.on(id)
        wayBack = WayBack(pace())
        alerts.reset()
        events.clear()
        spoken = null
        lastClock = null
        lastPoint = null
        main.post { basisOpen = false }
        publishOn()
        startTicker()
    }

    /** The recording stopped (any thread): the card goes, and the speech engine is released when nothing navigates. */
    fun onStop() {
        synchronized(this) {
            trackId = null
            wayBack = null
            lastClock = null
            on = false
        }
        publishOn()
        ticking = false
        main.post { NavUiState.wayBack = null }
        if (!NavSession.active && spoken != null) NavSpeech.end()
    }

    /**
     * T252: the chip and `POST /tracks/wayback?on=0|1`: the card on or off for the recording, kept per track. False when
     * nothing records. Turning it on gives fresh alerts (none were due while it was off).
     */
    fun setOn(value: Boolean): Boolean {
        synchronized(this) {
            val id = trackId ?: return false
            WayBackAnchor.setOn(id, value)
            if (value && !on) {
                alerts.reset()
                spoken = null
            }
            on = value
        }
        publishOn()
        refreshNow()
        return true
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
        on = WayBackAnchor.on(id)
        val idx = override?.let { h -> points.indexOfFirst { (it.time ?: Long.MIN_VALUE) >= h.pointTimeMs }.takeIf { it >= 0 } ?: (points.size - 1) }
        val wb = WayBack(pace(), idx)
        for (tp in points) wb.add(WayPoint(tp.lat, tp.lon, tp.ele, tp.time, tp.speedMps, tp.flags != 0))
        wayBack = wb
        lastPoint = wb.points.lastOrNull()
        rebuilds++
        publishOn()
        Log.i(NavSession.TAG, "way back rebuilt for $id: ${points.size} points, start ${wb.anchorSource.key} at point ${wb.anchorIndex}, on $on")
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

    /** Reads the clock at [now] and publishes it (main thread); quiet while navigating and while the card is off. */
    private fun tick(now: Long) {
        if (NavSession.active || !on) {
            if (NavUiState.wayBack != null) NavUiState.wayBack = null
            return
        }
        val a = activity()
        val (u, c) = synchronized(this) { ui(now, a).let { it to lastClock } }
        if (NavUiState.wayBack != u) NavUiState.wayBack = u
        if (u?.clock == true && c != null) alert(c)
    }

    /** The card for [a] at [now] (under the lock); null before a point. */
    private fun ui(now: Long, a: Activity): Ui? {
        val wb = wayBack ?: return null
        val src = wb.anchorSource
        val mode = TurnActivity.mode(a)
        if (mode == null) {
            lastClock = null
            return wb.anchor?.let { Ui(TurnActivity.noClockText(a), TurnActivity.NO_CLOCK_WHY, NORMAL, false, src.key, sourceLine(src), clock = false) }
        }
        val model = CostModel(mode)
        if (wb.pace.mode != mode || wb.pace.fitted != model.fitted) wb.pace = TurnPace.forRecording(model) // the activity changed
        val c = reading(now) ?: return null
        if (c.retraceS > TurnActivity.MAX_WAY_BACK_S) {
            lastClock = null
            return Ui(TurnActivity.NO_CLOCK, TurnActivity.tooFar(mode), NORMAL, false, src.key, sourceLine(src), clock = false)
        }
        val clock = dated(now)
        return Ui(
            c.text(clock), c.label(clock), c.colour, c.state == TurnState.RETURNING && wb.retrace.headingBack,
            src.key, sourceLine(src), clock = true,
            basis = TurnActivity.basis(src, wb.pace, c.marginMin, c.deadline.label, c.deadline.atMs?.let(clock)),
        )
    }

    /** The clock now, kept as [lastClock]; null with nothing recording or no point yet. */
    private fun reading(now: Long): TurnClock? {
        val wb = wayBack ?: return null
        val deadline = deadline(wb, now) ?: return null
        return runCatching { wb.clock(now, deadline, AppSettings.turnMarginMin) }.getOrNull().also { lastClock = it }
    }

    /**
     * T252: the deadline on the recording's day ([Deadline.dayFor] from the recording's first point), the terrain sunset
     * at the anchor for a terrain deadline (T193, through navigation's resolver).
     */
    private fun deadline(wb: WayBack, now: Long): Deadline? {
        val start = wb.anchor ?: return null
        val setting = DeadlineSetting.parse(AppSettings.turnDeadline) ?: DeadlineSetting.SUNSET
        val startMs = wb.points.firstOrNull()?.timeMs ?: start.timeMs ?: now
        val ref = Deadline.dayFor(setting, start.lat, start.lon, startMs, now, ::localMidnight)
        return NavSession.resolveDeadline(setting, start.lat, start.lon, ref, localMidnight(ref))
    }

    /** T163's alerts in free tracking: a tone and a buzz at the margin and at the time, the spoken line once. */
    private fun alert(c: TurnClock) {
        val due = synchronized(this) { alerts.due(c) }
        val clock = dated(c.nowMs)
        for (k in due) {
            NavSession.turnAroundAlert(k.key)
            val line = synchronized(this) { if (spoken == null) c.spoken(clock).also { spoken = it } else null }
            val said = line?.let { NavSpeech.sayTurnAround(it) }
            synchronized(this) {
                events += linkedMapOf(
                    "kind" to k.key, "atMs" to c.nowMs, "text" to c.text(clock), "colour" to c.colour, "spoken" to line,
                    "speech" to (said?.let { if (it.muted) "muted" else it.engine } ?: line?.let { "off" }),
                )
            }
            Log.i(NavSession.TAG, "way back turn-around ${k.key}: ${c.text(clock)}" + (line?.let { " / said: $it" } ?: ""))
        }
    }

    /** T196: the way back's line (the retrace's vertices from its start) and where the walker is on it; null before two vertices. */
    @Synchronized
    fun line(): Pair<List<io.github.jasondorjeshort.dystopo.nav.NavPoint>, Double>? =
        wayBack?.retrace?.takeIf { it.points.size >= 2 }?.let { r -> ArrayList(r.points) to r.alongM }

    /**
     * `GET /nav/turnaround` while a track records and nothing is navigated, and `GET /tracks` `wayBack`; null otherwise.
     * T252: `on`, the `activity` and its `activitySource`, `paceMode` (`hike`, `bike`, or null with no clock), `noClock`
     * (`off`, `activity` or `tooFar`; null while the clock shows), `retraceS`, `deadlineDate` and the card's `ui.basis`;
     * the reading (`text`, `label`, `clock`, ...) only while the card shows a clock.
     */
    fun json(): Map<String, Any?>? = synchronized(this) {
        val id = trackId ?: return null
        val wb = wayBack ?: return linkedMapOf("source" to "recording", "id" to id, "on" to on, "error" to "no point recorded yet")
        val now = System.currentTimeMillis()
        val a = activity()
        val mode = TurnActivity.mode(a)
        val c = if (on && mode != null && wb.pace.mode == mode) reading(now) else null
        val tooFar = c != null && c.retraceS > TurnActivity.MAX_WAY_BACK_S
        val shown = c?.takeIf { !tooFar }
        val clock = dated(now)
        val ui = NavUiState.wayBack
        linkedMapOf(
            "source" to "recording", "id" to id, "on" to on, "points" to wb.points.size,
            "activity" to a.code, "activitySource" to activitySource, "paceMode" to mode?.label,
            "noClock" to when { !on -> "off"; mode == null -> "activity"; tooFar -> "tooFar"; else -> null },
            "anchor" to (wb.json()["anchor"] as Map<*, *>) + mapOf("line" to sourceLine(wb.anchorSource), "override" to override?.let { linkedMapOf("pointTimeMs" to it.pointTimeMs, "setAtMs" to it.setAtMs) }),
            "retrace" to wb.retrace.json(), "headingBack" to wb.retrace.headingBack,
            "deadlineSetting" to AppSettings.turnDeadline, "marginMin" to AppSettings.turnMarginMin, "pace" to wb.pace.label,
            "retraceS" to c?.retraceS?.let { Math.round(it) }, "maxWayBackS" to TurnActivity.MAX_WAY_BACK_S.toLong(),
            "text" to shown?.text(clock), "label" to shown?.label(clock), "clock" to shown?.json(),
            "turnBack" to shown?.turnBackMs?.let(clock), "arrival" to shown?.arrivalMs?.let(clock), "deadline" to shown?.deadline?.atMs?.let(clock),
            "deadlineDate" to c?.deadline?.atMs?.let(::isoDate),
            "alerts" to alerts.json() + mapOf("events" to ArrayList(events)), "spoken" to spoken,
            "ui" to ui?.let { linkedMapOf("text" to it.text, "label" to it.label, "colour" to it.colour, "headingBack" to it.headingBack, "sourceLine" to it.sourceLine, "clock" to it.clock, "basis" to it.basis) },
            "basisOpen" to basisOpen,
            "shownOnCard" to (ui != null && !NavSession.active), "navigating" to NavSession.active,
            "rebuilds" to rebuilds, "pointsFed" to pointsFed,
        )
    }

    /** T218's clock text; T252: with its day when it is not [now]'s ("21:10 yesterday", "08:51 on 26 Oct"). */
    private fun dated(now: Long): (Long) -> String {
        val today = localMidnight(now)
        return { ms ->
            val diff = Math.round((localMidnight(ms) - today) / 86_400_000.0).toInt()
            DatedClock.text(io.github.jasondorjeshort.dystopo.app.ClockFormat.text(ms), diff, java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms)))
        }
    }

    private fun isoDate(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))

    private fun localMidnight(now: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val NORMAL = "normal"
    const val TICK_MS = 1000L
}
