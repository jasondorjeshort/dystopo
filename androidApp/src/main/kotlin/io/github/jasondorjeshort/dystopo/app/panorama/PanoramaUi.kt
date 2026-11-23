package io.github.jasondorjeshort.dystopo.app.panorama

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.horizon.PanoramaAim
import io.github.jasondorjeshort.dystopo.horizon.PanoramaScene

/**
 * T326: the Panorama's own state (wave 45's choice (i): which sky view is open, the place and a pick are `HorizonUi`'s; the
 * aim is this view's). [aim] is null from an opening until the plot knows its size and the opening's bearing
 * ([opened]; choice (c)); a "View from here" hop and "Back to here" keep it, and it is not kept from one opening to the
 * next. Steering ([steer]) is the plot's drag, pinch and zoom buttons, the accessibility actions and `POST /panorama`.
 */
object PanoramaUi {
    /** Where the lens looks, or null while the aim at opening is pending. */
    var aim by mutableStateOf<PanoramaAim?>(null)

    /** Counts the openings (the plot sets the aim at opening once per count). */
    var openings by mutableStateOf(0)
        private set

    /** True while a finger drags or pinches: the names keep their offsets from their marks (choice (g)). */
    var gesturing by mutableStateOf(false)

    /** When the view last opened (elapsed realtime, ms). */
    @Volatile var openedAtMs = 0L
        private set

    /** True once the user steered since the opening: the compass's first reading no longer turns the view. */
    @Volatile var steered = false
        private set

    /** What the opening's bearing came from: `peak`, `heading`, `north`, or `heading-late` (the compass's first reading within 1.5 s). */
    @Volatile var openingSource: String? = null

    /** From the opening to the first skyline drawn, ms (null until drawn). */
    @Volatile var firstSkylineMs: Long? = null

    /** The plot as last laid out: its size (px) and px per dp; its rectangle and the switch's in the Compose root (px). */
    @Volatile var plotSize: Triple<Double, Double, Double>? = null
    @Volatile var plotPx: List<Int>? = null
    @Volatile var switchPx: List<Int>? = null

    // T351: the overlays as last placed on the plot by their rule (plot px; Compose state: the scene keeps its names off
    // them, and the waiting line and a flight's card stand in its free band), whether the layers' panel is open (the
    // Layers button, Back and a tap outside close it) and the panel's rectangle in the Compose root (px).
    var overlays by mutableStateOf<io.github.jasondorjeshort.dystopo.horizon.PanoramaOverlays.Layout?>(null)
    var layersOpen by mutableStateOf(false)
    @Volatile var layersPx: List<Int>? = null
    // end T351

    /** The scene as last drawn, its build time (ms) last and most since the opening, and the builds counted. */
    @Volatile var scene: PanoramaScene? = null
    @Volatile var sceneMsLast = 0.0
    @Volatile var sceneMsMax = 0.0
    @Volatile var sceneBuilds = 0
    @Volatile var sceneOffMain = false

    /** The sweep steps the zoom last asked for ([io.github.jasondorjeshort.dystopo.horizon.PanoramaSweep.steps]). */
    @Volatile var wanted: List<Double> = emptyList()

    /** What the view last drew (`GET /panorama` `drawn`). */
    @Volatile var drawn: Map<String, Any?>? = null

    /**
     * T327: the two picks as last seen and the target ([PanoramaTargets]); Compose state, written on the main thread by
     * [apply] (a row, a name, the arrow, Back, the route) and by the screen when `HorizonUi`'s picks change.
     */
    var targets by mutableStateOf(TargetState())
        private set

    /** T327: a turn to the target asked for (counted; the screen animates it, [TURN_MS], at once under reduced motion). */
    var turnRequests by mutableStateOf(0)
        private set

    /** T327: the target's direction now (elevation, true bearing), or null (no target, or it is gone from its layer); the screen writes it. */
    @Volatile var targetDir: Pair<Double, Double>? = null

    /** T327: the edge arrow as last drawn (null: none), its rectangle in the Compose root (px). */
    @Volatile var arrow: io.github.jasondorjeshort.dystopo.horizon.OffViewArrow? = null
    @Volatile var arrowPx: List<Int>? = null

    /** T327: what the Sun, Satellites and Flights layers last drew on the lens (`GET /panorama` `layers`). */
    @Volatile var layersDrawn: Map<String, Any?> = emptyMap()

    /** T327: the sun's and the satellites' marks, the flights' marks and the flights' names as last drawn (the plot's taps; a gesture holds the names). */
    @Volatile var lensMarks: List<LensMark> = emptyList()
    @Volatile var flightMarks: List<io.github.jasondorjeshort.dystopo.app.horizon.FlightMark> = emptyList()
    @Volatile var flightNames: List<HeldFlightName> = emptyList()

    /** T327: the turns animated and done (`GET /panorama` `turns`). */
    @Volatile var turnsDone = 0
    @Volatile var lastTurn: Map<String, Any?>? = null

    /**
     * T327, debug only (`POST /panorama?sunAt=`): the instant the Panorama's Sun layer is drawn for (the day's path, the
     * hours and the sun now), or null for the clock. Compose state so the view redraws when it is set.
     */
    var sunAtMs by mutableStateOf<Long?>(null)

    /** Makes the Compose state on the main thread (`MainActivity.onCreate`), before a debug route's thread reads it (T226). */
    fun prepare() {
        aim; openings; gesturing; targets; turnRequests; sunAtMs
        PanoramaFollow.following; PanoramaFollow.moving // T330
        PanoramaFollow.correctionDeg; PanoramaFollow.hintShown // T331
        overlays; layersOpen // T351
    }

    /**
     * T327: applies [e] to the target's rule (main thread); the picks it changes are written back to `HorizonUi` (the one
     * open state), and a new target asks for a turn. Answers whether the event was used (Back's ladder goes on when not).
     */
    fun apply(e: PanoramaTargets.Event): Boolean {
        val was = targets
        val step = PanoramaTargets.next(was, e)
        targets = step.state
        // Only the picks the rule changed are written back (the other may have changed in `HorizonUi` in the same frame).
        val ui = io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi
        if (was.peak != step.state.peak && ui.selected != step.state.peak) ui.selected = step.state.peak
        if (was.flight != step.state.flight && ui.selectedFlight != step.state.flight) ui.selectedFlight = step.state.flight
        if (step.turn) turnRequests++
        return step.used
    }

    /** T327: a turn to the target now (the edge arrow's tap, the route). */
    fun turnToTarget() {
        if (targets.target != null) turnRequests++
    }

    /** T327: a user's steer stops a turn in progress. */
    @Volatile var turnCancelled = 0
        private set

    /** An opening (the Panorama segment from the map or from the Horizon view; main thread): the aim at opening follows. */
    fun opened() {
        aim = null
        openings++
        openedAtMs = SystemClock.elapsedRealtime()
        steered = false
        openingSource = null
        firstSkylineMs = null
        gesturing = false
        resetSceneCost()
        // T327: a pick kept across the switch is the target at the opening (choice b); its turn follows the aim at opening.
        val ui = io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi
        targets = PanoramaTargets.next(TargetState(), PanoramaTargets.Opened(ui.selected, null, ui.selectedFlight, null)).state
        arrow = null
        arrowPx = null
        layersDrawn = emptyMap()
        lastTurn = null
        PanoramaFollow.correct(PanoramaCorrection.Closed) // T331: a correction is never kept from one opening to the next
        correctionPx = null
        layersOpen = false // T351: the layers' panel opens closed
    }

    fun resetSceneCost() {
        sceneMsLast = 0.0
        sceneMsMax = 0.0
        sceneBuilds = 0
        drawMsLast = 0.0 // T330
        drawMsMax = 0.0
        draws = 0
        drawTimes.clear()
    }

    // T330: what a frame costs (choice (c)'s measurements, task (5)): the plot's draw pass (ms, last and most since the
    // cost was reset), the passes counted, and their times over the last second or so for the frames per second.
    @Volatile var drawMsLast = 0.0
    @Volatile var drawMsMax = 0.0
    @Volatile var draws = 0
    private val drawTimes = ArrayDeque<Long>()

    /** A draw pass of [ms] at [atNanos] (the plot's Canvas). */
    fun drawn(ms: Double, atNanos: Long) {
        drawMsLast = ms
        if (ms > drawMsMax) drawMsMax = ms
        draws++
        synchronized(drawTimes) {
            drawTimes.addLast(atNanos)
            while (drawTimes.size > 240 || (drawTimes.size > 1 && atNanos - drawTimes.first() > 2_000_000_000L)) drawTimes.removeFirst()
        }
    }

    /** The draw passes in the second before [nowNanos]. */
    fun drawsPerSecond(nowNanos: Long): Int = synchronized(drawTimes) { drawTimes.count { nowNanos - it <= 1_000_000_000L } }
    /** T330: "Follow phone" as last laid out (in the Compose root, px; null: not shown) and the view's keep-screen-on flag. */
    @Volatile var followPx: List<Int>? = null
    @Volatile var keepScreenOn = false
    // end T330

    // T331: the correction's line with Reset and the weak-compass line as last laid out (in the Compose root, px; null:
    // not shown), and whether the edge arrow as last drawn takes a tap (Drag) or is a sign (Follow).
    @Volatile var correctionPx: List<Int>? = null
    @Volatile var resetPx: List<Int>? = null
    @Volatile var hintPx: List<Int>? = null
    @Volatile var arrowClickable = false
    // end T331

    /** A scene built in [ms] (the plot's draw path; `GET /panorama` `sceneMs`). */
    fun sceneBuilt(s: PanoramaScene, ms: Double) {
        scene = s
        sceneMsLast = ms
        if (ms > sceneMsMax) sceneMsMax = ms
        sceneBuilds++
    }

    /** Steers the aim by [f] (main thread); a no-op while the aim at opening is pending. [byUser] stops a late compass reading. */
    fun steer(byUser: Boolean = true, f: (PanoramaAim, Triple<Double, Double, Double>) -> PanoramaAim) {
        val a = aim ?: return
        val size = plotSize ?: return
        if (byUser) {
            steered = true
            turnCancelled++
        }
        aim = f(a, size)
    }

    /**
     * T327's seam: turns the view to the direction ([elDeg], [azDeg]) by [PanoramaAim.turnTo] (the shorter way round, the
     * tilt changed only when it would be off the view under the tape). Main thread. Returns the turn, or null before the aim.
     */
    fun turnTo(elDeg: Double, azDeg: Double): PanoramaAim.Turn? {
        val a = aim ?: return null
        val (w, h, dp) = plotSize ?: return null
        val t = a.turnTo(elDeg, azDeg, w, h, PanoramaScene.TAPE_DP * dp)
        steered = true
        aim = t.aim
        return t
    }

    /** The compass's first reading came within the opening's wait and nobody steered: the view turns to it once. */
    internal fun lateHeading(deg: Double) {
        val a = aim ?: return
        if (steered || a.follow) return // T330: in Follow the phone aims
        aim = a.copy(bearingDeg = PanoramaAim.wrapDeg(deg))
        openingSource = "heading-late"
    }

    /** One turn of the accessibility actions and the debug route: half the field of view. */
    fun turnStepDeg(a: PanoramaAim): Double = a.fovDeg / 2

    /** One look up or down of the accessibility actions: 10 degrees. */
    const val TILT_STEP_DEG = 10.0

    /** The compass's first reading is waited for this long at an opening at the fix (choice (c)). */
    const val HEADING_WAIT_MS = 1500L

    /** T327: a turn to the target is animated over this long (at once under reduced motion). */
    const val TURN_MS = 300L

    /**
     * T327: turns the view to the target's direction ([targetDir]) by [PanoramaAim.turnTo], over [TURN_MS] with the names
     * held as in a drag (choice g), or at once when [animate] is false (the system's animations off). A steer by the user
     * stops it where it is. Main thread (the screen's effect).
     */
    suspend fun runTurn(animate: Boolean) {
        val (el, az) = targetDir ?: return
        val a0 = aim ?: return
        val (w, h, dp) = plotSize ?: return
        val t = a0.turnTo(el, az, w, h, PanoramaScene.TAPE_DP * dp)
        val cancel = turnCancelled
        // T330 (choice (i)): in Follow nothing turns the view; the target is set and the arrow says which way to turn the phone.
        if (a0.follow) {
            lastTurn = linkedMapOf("target" to targets.target?.name, "fromBearingDeg" to Math.round(a0.bearingDeg * 10) / 10.0, "turnDeg" to Math.round(t.turnDeg * 10) / 10.0, "follow" to true)
            return
        }
        lastTurn = linkedMapOf(
            "target" to targets.target?.name, "fromBearingDeg" to Math.round(a0.bearingDeg * 10) / 10.0, "turnDeg" to Math.round(t.turnDeg * 10) / 10.0,
            "toBearingDeg" to Math.round(t.aim.bearingDeg * 10) / 10.0, "toTiltDeg" to Math.round(t.aim.tiltDeg * 100) / 100.0, "animated" to animate,
        )
        if (animate && (kotlin.math.abs(t.turnDeg) > 0.05 || kotlin.math.abs(t.aim.tiltDeg - a0.tiltDeg) > 0.05)) {
            gesturing = true
            try {
                val start = SystemClock.uptimeMillis()
                while (true) {
                    androidx.compose.runtime.withFrameNanos { }
                    if (turnCancelled != cancel || aim == null || aim?.follow == true) return // T330: Follow took over
                    val f = ((SystemClock.uptimeMillis() - start).toDouble() / TURN_MS).coerceIn(0.0, 1.0)
                    if (f >= 1.0) break
                    val e = f * f * (3 - 2 * f) // smoothstep
                    aim = PanoramaAim(
                        PanoramaAim.wrapDeg(a0.bearingDeg + t.turnDeg * e),
                        (a0.tiltDeg + (t.aim.tiltDeg - a0.tiltDeg) * e).coerceIn(PanoramaAim.MIN_TILT_DEG, PanoramaAim.MAX_TILT_DEG), a0.fovDeg,
                    )
                }
            } finally {
                gesturing = false
            }
        }
        if (turnCancelled != cancel) return
        steered = true
        aim = t.aim
        turnsDone++
    }
}
