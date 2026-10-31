package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.gpx.StatsOptions
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.routing.CostModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

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
     * back along the way out projects onto the way out, so its progress runs backwards). T180: [headingBack], the
     * direction flip, is on the way back too (the arrival clock), and the only way a retrace-only clock gets there.
     */
    fun clock(
        alongM: Double,
        nowMs: Long,
        deadline: Deadline,
        marginMin: Int,
        offLineM: Double = 0.0,
        farthestM: Double = alongM,
        headingBack: Boolean = false,
    ): TurnClock {
        require(marginMin >= 0) { "the margin is minutes, 0 or more" }
        val rejoin = if (offLineM > 0) pace.timeS(offLineM, 0.0, 0.0) else 0.0
        val retrace = retraceS(alongM) + rejoin
        val onward = if (retraceOnly) Double.POSITIVE_INFINITY else onwardS(alongM) + rejoin
        val turnable = retraceOnly || loop
        val onwardShorter = !retraceOnly && onward <= retrace
        // T180: [headingBack] is the direction flip (free tracking's [Retrace], navigation's [Navigator]).
        val turnedBack = (turnable && headingBack) || (!retraceOnly && loop && farthestM - alongM > TURNED_BACK_M)
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
        /**
         * Progress this far short of the farthest reached: the walker has turned back. T180: also the hysteresis of the
         * direction flip ([PathTracker], [Retrace], [Navigator]): a turn is accepted only after a backward stretch this
         * long. 200 m, measured on the out-and-back fixtures (`WayBackTest.theHysteresisOnTheFixtures`: a walk out with a
         * 50 m step back, a three-minute stop and three switchback legs of 120 m 20 m apart, 5 m of GPS noise a fix, 40
         * seeds): 100 m flipped falsely 9 times, 150 m once, 200 m never; it is also what the survey's users ask for.
         * It was 100 m (T163).
         */
        const val TURNED_BACK_M = 200.0

        /** T180: the near-start guard: no flip while the backward stretch began this close to the start (as [LOOP_M]). */
        const val NEAR_START_M = 250.0
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

/**
 * T180: one recorded fix as the way back reads it: the position, the fix time, the recorder's per-fix speed (T158; null
 * when the source gave none) and whether the recorder flagged it (inaccurate or a network fix).
 */
data class WayPoint(val lat: Double, val lon: Double, val ele: Double? = null, val timeMs: Long? = null, val speedMps: Double? = null, val flagged: Boolean = false)

/** T180: where the way back of a recording starts, and why. */
enum class AnchorSource(val key: String) {
    /** The recording's first good fix (every watch's "back to start"). */
    START("start"),

    /** The first point of the walk after a vehicle stretch, by the speed rule ([WalkStart]). */
    WALK("walk"),

    /** "The way back starts here" on the recording card. */
    HERE("here");
}

/**
 * T180: the speed rule for the way back's start. The recorder keeps one activity per track and detects no transition,
 * so a drive to the trailhead recorded in the same track would put the way back at home. Over the per-fix speeds (the
 * fix's own, else the straight-line speed over the last [WINDOW_MS]): a vehicle stretch, faster than [VEHICLE_MPS] for
 * [VEHICLE_MS] in all and not broken by a walk, followed by walking, under [WALK_MPS] for [WALK_MS] without a break of
 * [BREAK_MS], puts the start at the walk's first point; the last such walk wins (a queue on the way in is passed by the
 * drive after it). The only prior art is the phones' parked-car marker; no outdoor app or watch moves its start.
 */
object WalkStart {
    const val VEHICLE_MPS = 8.0
    const val WALK_MPS = 2.5

    /** N of the task: two minutes over 8 m/s is a kilometre or more of driving, which no walk or GPS jump makes. */
    const val VEHICLE_MS = 120_000L

    /** M of the task: three minutes under 2.5 m/s outlasts a stop at a junction; a longer queue is passed by the drive after it. */
    const val WALK_MS = 180_000L

    /** Faster than [WALK_MPS] this long ends a walk (a single fast fix does not). */
    const val BREAK_MS = 15_000L
    const val WINDOW_MS = 30_000L

    /** A gap between fixes counts at most this long (a tunnel or the phone off is neither driving nor walking). */
    const val MAX_STEP_MS = 60_000L

    /** The index of the walk's first point, or null when the points show no vehicle stretch followed by a walk. */
    fun find(points: List<WayPoint>): Int? = Scanner(points).apply { while (next()) Unit }.anchor

    /**
     * The rule read point by point over [points] (a list that may grow between calls): [next] reads one more point,
     * false when none is left; [anchor] is the walk's first point so far.
     */
    class Scanner(private val points: List<WayPoint>) {
        var anchor: Int? = null
            private set
        private var i = 0
        private var vehicleMs = 0L
        private var walkStart = -1
        private var walkMs = 0L
        private var breakMs = 0L
        private var windowFrom = 0

        fun next(): Boolean {
            if (i + 1 >= points.size) return false
            i++
            val t = points[i].timeMs ?: return true
            val t0 = points[i - 1].timeMs ?: return true
            val dt = (t - t0).coerceIn(0, MAX_STEP_MS)
            while (windowFrom < i - 1 && (points[windowFrom].timeMs ?: t) < t - WINDOW_MS) windowFrom++
            val v = points[i].speedMps ?: run {
                val a = points[windowFrom]
                val span = (t - (a.timeMs ?: t)).coerceAtLeast(1)
                TrackStats.distanceM(a.lat, a.lon, points[i].lat, points[i].lon) / (span / 1000.0)
            }
            if (v > VEHICLE_MPS) vehicleMs += dt
            if (v < WALK_MPS) {
                if (walkStart < 0) {
                    walkStart = i
                    walkMs = 0
                }
                walkMs += dt
                breakMs = 0
            } else if (walkStart >= 0) {
                breakMs += dt
                if (breakMs >= BREAK_MS) {
                    walkStart = -1
                    walkMs = 0
                    breakMs = 0
                }
            }
            if (walkStart >= 0 && walkMs >= WALK_MS) {
                if (vehicleMs >= VEHICLE_MS) anchor = walkStart
                vehicleMs = 0
            }
            return true
        }
    }

    /** The recording's first good fix: the first point the recorder did not flag, else the first. */
    fun firstGood(points: List<WayPoint>): Int = points.indexOfFirst { !it.flagged }.takeIf { it >= 0 } ?: 0
}

/**
 * T180: the way back of a recording, fix by fix: the recorded line from its start ([AnchorSource]) thinned to vertices
 * [thinM] apart (a stop's GPS jitter would otherwise add line and phantom time), and the direction.
 *
 * - **Out** (the default): the way back is the whole line from its newest point to the start, as T163 read it.
 * - **The turn:** each new vertex is matched to the line recorded before it (more than [exclM] of line back, so not to
 *   itself). An unbroken run of matches within [snapM] whose matched place moves back along the old line by
 *   [turnBackM], while the walker moved at least half that in a straight line (a stop's jitter never does), begun more
 *   than [nearStartM] from the start (the near-start guard), is a turn: [returning] and [headingBack] go on ([flips]
 *   counts the flips) and the way back is the old line from the matched place ([alongM]) to the start.
 * - **Returning:** each vertex is matched near the last match on the old line up to the turn ([farM]). A walker who
 *   heads out again on it for [turnBackM] is no longer heading back (the clock still times back from the match), and
 *   heading back again for [turnBackM] flips once more. Off the old line by more than [snapM] the straight way back to
 *   it is added ([offM]); after [LEAVE_M] of walking more than [OFF_M] off it the recording is out again (new ground).
 */
class Retrace(
    val snapM: Double = NavPath.SNAP_M,
    val turnBackM: Double = TurnAround.TURNED_BACK_M,
    val nearStartM: Double = TurnAround.NEAR_START_M,
    val thinM: Double = THIN_M,
    val exclM: Double = 2 * NavPath.SNAP_M,
) {
    private val pts = ArrayList<NavPoint>()
    private var cum = DoubleArray(64)

    /** The kept vertices: the line the clock times. */
    val points: List<NavPoint> get() = pts
    val totalM: Double get() = if (pts.isEmpty()) 0.0 else cum[pts.size - 1]

    var returning = false
        private set
    var headingBack = false
        private set
    var flips = 0
        private set

    /** Where the way back starts from along the line: its end while out, the matched place on the old line while returning. */
    var alongM = 0.0
        private set

    /** While returning and off the old line by more than [snapM], the straight distance back to it; else 0. */
    var offM = 0.0
        private set

    /** While returning, the turn's place along the line (the old line is matched up to here). */
    var farM = 0.0
        private set

    /** Each flip to heading back: the line's length then and the matched place on the old line. */
    val flipsAt = ArrayList<Pair<Double, Double>>()

    private var stS = Double.NaN
    private var stM = 0.0
    private var stMin = 0.0
    private var stLat = 0.0
    private var stLon = 0.0
    private var retMin = 0.0
    private var retMax = 0.0
    private var offFromS = Double.NaN

    private var sumLat = 0.0
    private var sumLon = 0.0
    private var sumEle = 0.0
    private var nEle = 0
    private var pending = 0

    /**
     * Adds the next recorded point. The first point is the first vertex; after it the points gather until their mean is
     * [thinM] from the last vertex, and the mean becomes the next one (see [THIN_M]). True when a vertex was added.
     */
    fun add(lat: Double, lon: Double, ele: Double? = null): Boolean {
        val n = pts.size
        if (n == 0) {
            cum[0] = 0.0
            pts += NavPoint(lat, lon, ele)
            return true
        }
        sumLat += lat
        sumLon += lon
        if (ele != null) { sumEle += ele; nEle++ }
        pending++
        val mLat = sumLat / pending
        val mLon = sumLon / pending
        val last = pts[n - 1]
        val d = TrackStats.distanceM(last.lat, last.lon, mLat, mLon)
        if (d < thinM) return false
        if (n == cum.size) cum = cum.copyOf(n * 2)
        cum[n] = cum[n - 1] + d
        pts += NavPoint(mLat, mLon, if (nEle > 0) sumEle / nEle else null)
        sumLat = 0.0; sumLon = 0.0; sumEle = 0.0; nEle = 0; pending = 0
        update(mLat, mLon)
        return true
    }

    private fun update(lat: Double, lon: Double) {
        val s = totalM
        if (!returning) {
            alongM = s
            offM = 0.0
            val m = match(lat, lon, s - exclM, if (stS.isNaN()) null else stMin)
            if (m == null || m.second > snapM) {
                stS = Double.NaN
                return
            }
            val along = m.first
            if (!stS.isNaN() && along <= stMin + PathTracker.RUN_SLACK_M) {
                stMin = minOf(stMin, along)
            } else {
                stS = s
                stM = along
                stMin = along
                stLat = lat
                stLon = lon
            }
            val moved = TrackStats.distanceM(stLat, stLon, lat, lon)
            if (stM - along >= turnBackM && moved >= turnBackM / 2 && stM >= nearStartM) {
                returning = true
                headingBack = true
                flips++
                flipsAt += s to along
                farM = (stS + stM) / 2
                alongM = along
                retMin = along
                retMax = along
                offFromS = Double.NaN
                stS = Double.NaN
            }
            return
        }
        val m = match(lat, lon, farM, alongM)
        if (m != null && m.second <= OFF_M) {
            alongM = m.first
            offM = if (m.second <= snapM) 0.0 else m.second
            offFromS = Double.NaN
            if (headingBack) {
                retMin = minOf(retMin, m.first)
                if (m.first - retMin >= turnBackM) {
                    headingBack = false
                    retMax = m.first
                }
            } else {
                retMax = maxOf(retMax, m.first)
                if (retMax - m.first >= turnBackM) {
                    headingBack = true
                    flips++
                    flipsAt += s to m.first
                    retMin = m.first
                }
            }
            return
        }
        if (offFromS.isNaN()) offFromS = s
        if (m != null) {
            alongM = m.first
            offM = m.second
        }
        if (s - offFromS >= LEAVE_M) {
            returning = false
            headingBack = false
            alongM = s
            offM = 0.0
            stS = Double.NaN
        }
    }

    /**
     * The place of ([lat], [lon]) on the line up to [maxAlongM], as (along, distance). Near [nearM] (within [WINDOW_M]
     * either side) first: of the passes there within [snapM] (local minima of the distance), the one nearest [nearM],
     * one ahead of it counting double (the walker comes back toward the start); else the closest point of the prefix.
     */
    private fun match(lat: Double, lon: Double, maxAlongM: Double, nearM: Double?): Pair<Double, Double>? {
        if (maxAlongM <= 0) return null
        if (nearM != null) {
            val w = near(lat, lon, nearM - WINDOW_M, minOf(maxAlongM, nearM + WINDOW_M), maxAlongM, nearM)
            if (w != null) return w
        }
        return near(lat, lon, 0.0, maxAlongM, maxAlongM, null)
    }

    /** With [nearM], the best pass within [snapM] (null when none); without, the closest point between [fromM] and [toM]. */
    private fun near(lat: Double, lon: Double, fromM: Double, toM: Double, maxAlongM: Double, nearM: Double?): Pair<Double, Double>? {
        val n = pts.size
        if (n < 2 || toM < fromM) return null
        var lo = 0
        var hi = n - 1
        val f = fromM.coerceAtLeast(0.0)
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= f) lo = mid else hi = mid
        }
        val kx = cos(lat * PI / 180) * M_PER_DEG
        var best: Pair<Double, Double>? = null
        var bestCost = Double.POSITIVE_INFINITY
        var prevT = 2.0
        var i = lo
        while (i < n - 1 && cum[i] <= toM && cum[i] < maxAlongM) {
            val a = pts[i]
            val b = pts[i + 1]
            val bx = (b.lon - a.lon) * kx
            val by = (b.lat - a.lat) * M_PER_DEG
            val px = (lon - a.lon) * kx
            val py = (lat - a.lat) * M_PER_DEG
            val len2 = bx * bx + by * by
            val segLen = cum[i + 1] - cum[i]
            val tMax = if (segLen <= 0) 0.0 else ((maxAlongM - cum[i]) / segLen).coerceIn(0.0, 1.0)
            val t = if (len2 <= 0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, tMax)
            val dx = px - bx * t
            val dy = py - by * t
            val d = sqrt(dx * dx + dy * dy)
            val along = cum[i] + segLen * t
            if (nearM == null) {
                if (best == null || d < best.second) best = along to d
            } else {
                // A pass: the segment's inside, or a vertex both neighbours end at, or the prefix's own end.
                val inner = t > 1e-9 && t < tMax - 1e-9
                val vertex = t <= 1e-9 && (i == lo || prevT >= 1 - 1e-9)
                val end = t >= tMax - 1e-9 && (i + 1 >= n - 1 || cum[i + 1] >= maxAlongM || cum[i + 1] > toM)
                if ((inner || vertex || end) && d <= snapM) {
                    val c = if (along <= nearM) nearM - along else 2 * (along - nearM)
                    if (c < bestCost) { bestCost = c; best = along to d }
                }
            }
            prevT = t
            i++
        }
        return best
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "vertices" to pts.size, "totalM" to r1(totalM), "returning" to returning, "headingBack" to headingBack, "flips" to flips,
        "alongM" to r1(alongM), "offM" to r1(offM), "farM" to if (returning) r1(farM) else null,
        "flipsAt" to flipsAt.map { (s, m) -> linkedMapOf("lineM" to r1(s), "matchedM" to r1(m)) },
        "snapM" to snapM, "turnBackM" to turnBackM, "nearStartM" to nearStartM, "thinM" to thinM,
    )

    companion object {
        /**
         * The vertices' spacing: the mean of the fixes since the last vertex becomes the next one when it is this far
         * from it (about six walking fixes at one a second, which shrinks 4 m of GPS noise to 1.6 m and lengthens the
         * line by about 1 percent; at 5 m the same noise lengthened it by 28 percent).
         */
        const val THIN_M = 15.0

        /** Returning, a match farther than this is off the old line. */
        const val OFF_M = 50.0

        /** Walked this far off the old line while returning: new ground, the recording is out again. */
        const val LEAVE_M = 150.0

        /** The sequential match looks this far either side of the last match first. */
        const val WINDOW_M = 300.0

        private const val M_PER_DEG = TrackStats.EARTH_RADIUS_M * PI / 180
    }
}

/**
 * T180: the turn-around clock of a recording in free tracking: the points in order from the recording's start, the way
 * back's start ([anchorIndex], [anchorSource]: the first good fix, the speed rule's walk, or an override), the [Retrace]
 * from it, and the clock (`retraceOnly`, the arrival clock once heading back). Pure; the phone feeds it the recorder's
 * fixes, `devtool nav --turn-around --replay` a GPX's points.
 */
class WayBack(val pace: TurnPace, private val override: Int? = null) {
    private val all = ArrayList<WayPoint>()
    private val scanner = WalkStart.Scanner(all)
    var retrace = Retrace()
        private set
    var anchorIndex = 0
        private set
    var anchorSource = AnchorSource.START
        private set
    private var fed = 0
    private var turn: TurnAround? = null
    private var turnAt = -1

    val points: List<WayPoint> get() = all

    /** Adds the recording's next point; re-anchors (and rebuilds the retrace) when the speed rule moves the start. */
    fun add(p: WayPoint) {
        all += p
        val (i, src) = anchor()
        if (i != anchorIndex || src != anchorSource) {
            anchorIndex = i
            anchorSource = src
            rebuild()
        } else {
            feed()
        }
    }

    private fun anchor(): Pair<Int, AnchorSource> {
        override?.let { if (it in all.indices) return it to AnchorSource.HERE }
        while (scanner.next()) Unit
        val walk = scanner.anchor
        return if (walk != null) walk to AnchorSource.WALK else WalkStart.firstGood(all) to AnchorSource.START
    }

    private fun rebuild() {
        retrace = Retrace()
        fed = anchorIndex
        turn = null
        turnAt = -1
        feed()
    }

    private fun feed() {
        while (fed < all.size) {
            val p = all[fed++]
            if (fed - 1 >= anchorIndex) retrace.add(p.lat, p.lon, p.ele)
        }
    }

    /** The anchor point. */
    val anchor: WayPoint? get() = all.getOrNull(anchorIndex)

    /**
     * The clock at [nowMs] for [deadline] and [marginMin]: back along the line from where the [retrace] places the way
     * back's start; null before the first point.
     */
    fun clock(nowMs: Long, deadline: Deadline, marginMin: Int): TurnClock? {
        val r = retrace
        val pts = r.points
        if (pts.isEmpty()) return null
        val t = if (pts.size >= 2) {
            if (turnAt != pts.size) {
                turn = TurnAround(NavPath("way back", pts), pace, retraceOnly = true)
                turnAt = pts.size
            }
            turn!!
        } else {
            // One vertex (at the start, or just re-anchored here): nothing to walk back; a metre-long line read at 0.
            val p = pts[0]
            TurnAround(NavPath("way back", listOf(p, NavPoint(p.lat + 1e-5, p.lon, p.ele))), pace, retraceOnly = true)
        }
        val along = if (pts.size >= 2) r.alongM else 0.0
        return t.clock(along, nowMs, deadline, marginMin, offLineM = r.offM, headingBack = r.headingBack)
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "points" to all.size, "anchor" to linkedMapOf(
            "index" to anchorIndex, "source" to anchorSource.key,
            "lat" to anchor?.lat?.let(::r6), "lon" to anchor?.lon?.let(::r6), "timeMs" to anchor?.timeMs,
        ),
        "retrace" to retrace.json(),
    )
}
