package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.gpx.StatsOptions
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.routing.CostModel
import kotlin.math.roundToLong

/**
 * The pace the turn-around clock times the way back with (T163): seconds for a stretch of [lengthM] that climbs
 * [ascentM] and descends [descentM], and whether it is the user's own fitted pace ([fitted]) or the standard estimate,
 * which the card's label says.
 */
class TurnPace(val fitted: Boolean, private val stretchTimeS: (lengthM: Double, ascentM: Double, descentM: Double) -> Double) {
    fun timeS(lengthM: Double, ascentM: Double, descentM: Double): Double = stretchTimeS(lengthM, ascentM, descentM)

    val label: String get() = if (fitted) YOUR_PACE else STANDARD_PACE

    companion object {
        const val YOUR_PACE = "your pace"
        const val STANDARD_PACE = "standard pace"

        /**
         * The routing time model of [model]: T164's fitted curve when the user has one in that mode and has not chosen
         * the standard estimate ([CostModel.fitted], its time with the descent, so the way down is timed as walked; the
         * label says "your pace"), else the standard estimate (Naismith for hiking, the touring pace for bikes).
         */
        fun of(model: CostModel): TurnPace = TurnPace(model.fitted) { l, up, down -> model.timeS(l, up, down) }
    }
}

/** What the clock counts down to: the day's sunset, the end of civil twilight, or a time the user typed. */
enum class DeadlineKind(val key: String, val label: String) {
    SUNSET("sunset", "sunset"),
    CIVIL("civil", "civil twilight"),
    TIME("time", "your time");
}

/**
 * The deadline setting (T163, `turnDeadline`): `sunset` (the default; every surveyed watch and app counts to sunset),
 * `civil` (the end of civil twilight, which relies on a headlamp) or a typed local clock time `HH:MM` (a pickup, a last
 * ferry). There is no "off": the clock always runs while navigating.
 */
data class DeadlineSetting(val kind: DeadlineKind, val minuteOfDay: Int? = null) {
    init {
        require((kind == DeadlineKind.TIME) == (minuteOfDay != null)) { "a typed deadline needs its time, and only it" }
        require(minuteOfDay == null || minuteOfDay in 0 until 1440) { "a time of day is 00:00 to 23:59" }
    }

    /** The setting's stored and synced value. */
    val value: String get() = if (kind == DeadlineKind.TIME) hhmm(minuteOfDay!!) else kind.key

    companion object {
        const val DEFAULT = "sunset"
        val SUNSET = DeadlineSetting(DeadlineKind.SUNSET)

        private val TIME = Regex("""^(\d{1,2}):(\d{2})$""")

        /** `sunset`, `civil` or `H:MM`/`HH:MM` (24-hour); null for anything else. */
        fun parse(raw: String?): DeadlineSetting? {
            val v = raw?.trim()?.lowercase() ?: return null
            if (v == "sunset") return SUNSET
            if (v == "civil" || v == "civil twilight" || v == "twilight") return DeadlineSetting(DeadlineKind.CIVIL)
            val m = TIME.matchEntire(v) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h !in 0..23 || min !in 0..59) return null
            return DeadlineSetting(DeadlineKind.TIME, h * 60 + min)
        }

        fun hhmm(minuteOfDay: Int): String {
            val h = minuteOfDay / 60
            val m = minuteOfDay % 60
            return (if (h < 10) "0$h" else "$h") + ":" + (if (m < 10) "0$m" else "$m")
        }
    }
}

/** A deadline placed in time: [atMs] (null when the sun does not go down that far that day), and how the card names it. */
data class Deadline(val kind: DeadlineKind, val atMs: Long?, val label: String) {
    companion object {
        /** The sun events are for a level horizon; the label says so (a valley's own sunset is earlier). */
        const val FLAT_HORIZON = "flat horizon"

        /**
         * [setting] for a walk at [lat], [lon] (the end of the line) at [nowMs]. A typed time is that clock time on the
         * day that starts at [localMidnightMs] (the phone's own zone; the caller knows it); the sun's events are the
         * local day's at the point ([Sun.dayAt]).
         */
        fun resolve(setting: DeadlineSetting, lat: Double, lon: Double, nowMs: Long, localMidnightMs: Long): Deadline = when (setting.kind) {
            DeadlineKind.TIME -> Deadline(DeadlineKind.TIME, localMidnightMs + setting.minuteOfDay!! * 60_000L, DeadlineKind.TIME.label)
            DeadlineKind.SUNSET -> Deadline(DeadlineKind.SUNSET, Sun.dayAt(lat, lon, nowMs).sunsetMs, "${DeadlineKind.SUNSET.label}, $FLAT_HORIZON")
            DeadlineKind.CIVIL -> Deadline(DeadlineKind.CIVIL, Sun.dayAt(lat, lon, nowMs).civilEndMs, "${DeadlineKind.CIVIL.label}, $FLAT_HORIZON")
        }
    }
}

/** Where the clock stands (T163). */
enum class TurnState(val key: String) {
    /** Before the warning: "Turn back by 14:10". */
    AHEAD("ahead"),

    /** Within the margin of the turn-back time (amber): the first alert and the spoken line. */
    MARGIN("margin"),

    /** The turn-back time has come (red): the second alert. */
    TURN("turn"),

    /** On a loop or an out-and-back, going on is now the shorter way out, or the walker has turned back: the arrival clock. */
    RETURNING("returning"),

    /** A point-to-point line (no way back to the start to time): the arrival clock only. */
    ARRIVAL("arrival"),

    /** The deadline does not exist today (the sun stays up): nothing to count to. */
    NO_DEADLINE("noDeadline");
}

/**
 * One reading of the turn-around clock. [turnBackMs] = deadline - margin - [retraceS]: the latest time to start back
 * from here and be out [marginMin] before the deadline; [warnAtMs] the margin before it. [arrivalMs] is when the walker
 * is out going the shorter way from now ([returnS]); after the turn-back time and on the way out it is what the card
 * shows. [colour]: `normal`, `amber` or `red`.
 */
data class TurnClock(
    val nowMs: Long,
    val deadline: Deadline,
    val marginMin: Int,
    val paceLabel: String,
    val loop: Boolean,
    val alongM: Double,
    /** Back along the line walked to its start (plus the way back to the line when off it). */
    val retraceS: Double,
    /** On along the rest of the line to its end; for a loop the end is the start. */
    val onwardS: Double,
    val returnS: Double,
    /** `back` (retrace) or `on` (the rest of the line). */
    val way: String,
    val turnBackMs: Long?,
    val warnAtMs: Long?,
    val arrivalMs: Long,
    val state: TurnState,
    val colour: String,
) {
    /** The card's line: "Turn back by 14:10", "Turn back now, out by 18:52", "Out by 18:05". [clock] formats a time. */
    fun text(clock: (Long) -> String): String = when (state) {
        TurnState.AHEAD, TurnState.MARGIN -> "Turn back by ${clock(turnBackMs!!)}"
        TurnState.TURN -> "Turn back now, out by ${clock(arrivalMs)}"
        TurnState.RETURNING, TurnState.ARRIVAL -> "Out by ${clock(arrivalMs)}"
        TurnState.NO_DEADLINE -> "Out by ${clock(arrivalMs)}"
    }

    /** The label under it: "Sunset 19:12, flat horizon, 30 min margin, standard pace". */
    fun label(clock: (Long) -> String): String {
        val d = deadline.atMs?.let { at ->
            when (deadline.kind) {
                DeadlineKind.TIME -> "Your time ${clock(at)}"
                else -> deadline.kind.label.replaceFirstChar { it.uppercase() } + " " + clock(at) + ", " + Deadline.FLAT_HORIZON
            }
        } ?: "No ${deadline.kind.label} today"
        return "$d, $marginMin min margin, $paceLabel"
    }

    /** The one spoken line, at the margin: "Turn back by 14:10 to be out by sunset." */
    fun spoken(clock: (Long) -> String): String {
        val by = when (deadline.kind) {
            DeadlineKind.SUNSET -> "sunset"
            DeadlineKind.CIVIL -> "the end of twilight"
            DeadlineKind.TIME -> deadline.atMs?.let(clock) ?: "your time"
        }
        return "Turn back by ${turnBackMs?.let(clock) ?: "now"} to be out by $by."
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "state" to state.key, "colour" to colour, "nowMs" to nowMs,
        "deadline" to linkedMapOf("kind" to deadline.kind.key, "atMs" to deadline.atMs, "label" to deadline.label),
        "marginMin" to marginMin, "pace" to paceLabel, "loop" to loop, "alongM" to r1(alongM),
        "retraceS" to retraceS.roundToLong(), "onwardS" to onwardS.roundToLong(), "returnS" to returnS.roundToLong(), "way" to way,
        "turnBackMs" to turnBackMs, "warnAtMs" to warnAtMs, "arrivalMs" to arrivalMs,
        "arrivalIfTurnedAtMs" to turnBackMs?.let { it + (retraceS * 1000).roundToLong() },
    )
}

/**
 * The turn-around clock (T163, `docs/ideas` turn-around clock): from a position on a line (a planned route, a followed
 * track, or in free tracking the recorded track itself), the time back out by the [pace] model, against a [Deadline]
 * and a margin. The way out is the line walked back to its start, or on a loop or an out-and-back (start and end
 * within [loopM]) the rest of the line when that is shorter; a point-to-point line (a car at each end) has no turn and
 * gets the arrival clock only. With [retraceOnly] (free tracking) the way out is always back to the start.
 *
 * Times are summed segment by segment, each timed by [pace] in the direction it is walked (a climb out is a descent
 * back), on per-segment climbs and descents scaled to the line's totals by T24's 3 m rule, so GPS noise does not add
 * phantom climb. Prefix sums make each reading a binary search.
 */
class TurnAround(val path: NavPath, val pace: TurnPace, val loopM: Double = LOOP_M, val retraceOnly: Boolean = false) {
    val loop: Boolean = path.points.let { p -> TrackStats.distanceM(p.first().lat, p.first().lon, p.last().lat, p.last().lon) <= loopM }

    /** Seconds from the start to vertex i walking forward, and from vertex i back to the start walking backward. */
    private val fwd = DoubleArray(path.points.size)
    private val back = DoubleArray(path.points.size)

    init {
        val pts = path.points
        val ups = DoubleArray(pts.size)
        val downs = DoubleArray(pts.size)
        var rawUp = 0.0
        var rawDown = 0.0
        var lastEle: Double? = pts[0].ele
        for (i in 1 until pts.size) {
            val e = pts[i].ele
            if (e != null && lastEle != null) {
                if (e > lastEle) ups[i] = e - lastEle else downs[i] = lastEle - e
                rawUp += ups[i]
                rawDown += downs[i]
            }
            if (e != null) lastEle = e
        }
        val (up, down) = TrackStats.ascentDescent(pts.mapNotNull { it.ele }, StatsOptions().elevationThresholdM)
        val su = if (rawUp > 0) up / rawUp else 0.0
        val sd = if (rawDown > 0) down / rawDown else 0.0
        for (i in 1 until pts.size) {
            val len = path.cumM[i] - path.cumM[i - 1]
            fwd[i] = fwd[i - 1] + pace.timeS(len, ups[i] * su, downs[i] * sd)
            back[i] = back[i - 1] + pace.timeS(len, downs[i] * sd, ups[i] * su)
        }
    }

    /** Seconds to walk the whole line forward. */
    val totalS: Double get() = fwd.last()

    /** Seconds from [alongM] back along the line to its start. */
    fun retraceS(alongM: Double): Double = at(back, alongM)

    /** Seconds from [alongM] on along the line to its end. */
    fun onwardS(alongM: Double): Double = fwd.last() - at(fwd, alongM)

    private fun at(cum: DoubleArray, alongM: Double): Double {
        val d = alongM.coerceIn(0.0, path.totalM)
        var lo = 0
        var hi = path.cumM.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (path.cumM[mid] <= d) lo = mid else hi = mid
        }
        val len = path.cumM[lo + 1] - path.cumM[lo]
        val f = if (len <= 0) 0.0 else ((d - path.cumM[lo]) / len).coerceIn(0.0, 1.0)
        return cum[lo] + (cum[lo + 1] - cum[lo]) * f
    }

    /**
     * The clock at [alongM] along the line, [offLineM] from it (its straight way back is added, as navigation adds it
     * to the ETA), at [nowMs], for [deadline] and [marginMin]. [farthestM] is the farthest the walker got along the line:
     * more than [TURNED_BACK_M] short of it, the walker has turned back already and the clock gives the arrival (a walk
     * back along the way out projects onto the way out, so its progress runs backwards).
     */
    fun clock(alongM: Double, nowMs: Long, deadline: Deadline, marginMin: Int, offLineM: Double = 0.0, farthestM: Double = alongM): TurnClock {
        require(marginMin >= 0) { "the margin is minutes, 0 or more" }
        val rejoin = if (offLineM > 0) pace.timeS(offLineM, 0.0, 0.0) else 0.0
        val retrace = retraceS(alongM) + rejoin
        val onward = if (retraceOnly) Double.POSITIVE_INFINITY else onwardS(alongM) + rejoin
        val turnable = retraceOnly || loop
        val onwardShorter = !retraceOnly && onward <= retrace
        val turnedBack = !retraceOnly && loop && farthestM - alongM > TURNED_BACK_M
        val returnS = if (!turnable) onward else minOf(retrace, onward)
        val arrival = nowMs + (returnS * 1000).roundToLong()
        val marginMs = marginMin * 60_000L
        val at = deadline.atMs
        fun arrivalColour(): String = when {
            at == null -> NORMAL
            arrival > at -> RED
            arrival > at - marginMs -> AMBER
            else -> NORMAL
        }
        val base = TurnClock(
            nowMs, deadline, marginMin, pace.label, loop, alongM, retrace, if (onward.isInfinite()) retrace else onward, returnS,
            if (turnable && !onwardShorter) "back" else "on", null, null, arrival, TurnState.ARRIVAL, NORMAL,
        )
        return when {
            at == null -> base.copy(state = TurnState.NO_DEADLINE)
            !turnable -> base.copy(state = TurnState.ARRIVAL, colour = arrivalColour())
            onwardShorter || turnedBack -> base.copy(state = TurnState.RETURNING, colour = arrivalColour())
            else -> {
                val turnBack = at - marginMs - (retrace * 1000).roundToLong()
                val warn = turnBack - marginMs
                val state = when {
                    nowMs >= turnBack -> TurnState.TURN
                    marginMs > 0 && nowMs >= warn -> TurnState.MARGIN
                    else -> TurnState.AHEAD
                }
                base.copy(
                    turnBackMs = turnBack, warnAtMs = warn, state = state,
                    colour = when (state) { TurnState.TURN -> RED; TurnState.MARGIN -> AMBER; else -> NORMAL },
                )
            }
        }
    }

    companion object {
        /** A line whose start and end are this close is a loop or an out-and-back: it has a turn. */
        const val LOOP_M = 250.0
        /** Progress this far short of the farthest reached: the walker has turned back. */
        const val TURNED_BACK_M = 100.0
        const val DEFAULT_MARGIN_MIN = 30
        /** The margin presets the setting offers (any whole minute in [MARGIN_RANGE] can be typed). */
        val MARGIN_PRESETS = listOf(0, 15, 30, 60, 90)
        val MARGIN_RANGE = 0..240
        const val NORMAL = "normal"
        const val AMBER = "amber"
        const val RED = "red"
    }
}

/**
 * Which alerts the clock has given in one navigation (pure, `TurnAroundTest`): one when it first reaches the margin
 * ([TurnState.MARGIN]) and one when it first reaches the turn-back time ([TurnState.TURN]); never again in that
 * navigation, so a walker who wobbles across either line hears each once. A clock that starts past the margin (a late
 * start) gives the turn alert alone. [due] returns the alerts due for a reading, in order.
 */
class TurnAlerts {
    var marginGiven = false
        private set
    var turnGiven = false
        private set

    fun reset() {
        marginGiven = false
        turnGiven = false
    }

    fun due(c: TurnClock): List<TurnState> {
        val out = ArrayList<TurnState>(2)
        if (c.state == TurnState.MARGIN && !marginGiven) {
            marginGiven = true
            out += TurnState.MARGIN
        }
        if (c.state == TurnState.TURN && !turnGiven) {
            turnGiven = true
            marginGiven = true // the margin has passed: its line would only repeat this one
            out += TurnState.TURN
        }
        return out
    }

    fun json(): Map<String, Any?> = linkedMapOf("marginGiven" to marginGiven, "turnGiven" to turnGiven)
}
