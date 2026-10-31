package io.github.jasondorjeshort.dystopo.app.controls

import io.github.jasondorjeshort.dystopo.app.MapController.CameraMode
import kotlin.math.abs

/**
 * T100: the map's orientation, one state for the whole app (the owner's rule of 2026-10-13, task T100 in `docs/TASKS.md`).
 * [NORTH_UP] is the default; [FREE] is entered only by rotating the map with a gesture; [FACING] turns the map
 * to the heading and is offered by the recentre button only when the phone has a compass. [label] is the
 * user-visible name (the button's state description; the navigation card's "North up" and "Heading up").
 */
enum class Orientation(val key: String, val label: String) {
    NORTH_UP("north-up", "North up"),
    FREE("free", "Free rotate"),
    FACING("facing", "Heading up");

    companion object {
        fun parse(s: String?): Orientation? = when (s?.trim()?.lowercase()) {
            "north-up", "north", "northup", "north_up" -> NORTH_UP
            "free", "free-rotate", "rotate" -> FREE
            "facing", "heading", "heading-up", "headingup", "heading_up" -> FACING
            else -> null
        }
    }
}

/** A heading and where it came from (`simulated`, `course`, `compass`). */
data class HeadingReading(val deg: Double, val source: String)

/**
 * T100: the rules of the recentre button and of the facing camera, kept free of Android so they are unit-tested
 * (`OrientationRulesTest`). [MapOrientation] applies them on the main thread.
 */
object OrientationRules {
    /** A turn of the map to the heading is eased once the heading is this far from the map's bearing (degrees). */
    const val TURN_DEG = 2.0

    /** Faster than this (m/s) counts as moving: the heading is the course over ground, else the compass. */
    const val MOVING_MPS = 1.0

    /** A course older than this no longer says the walker is moving (ms): a walker at 1 m/s makes a 5 m step in 5 s. */
    const val COURSE_FRESH_MS = 10_000L

    /** The fix counts as centred within this many dp of the map's centre (the camera may lag a fix by a frame). */
    const val CENTRED_DP = 6f

    /**
     * T176: what a tap on the position button does. RECENTER brings the fix to the centre (zooming in with it when
     * [Tap.zoomTo] is set); ZOOM, on a centred map farther out than the close zoom, zooms in to it; ADVANCE, on a
     * centred map at the close zoom, advances the orientation (the one-button layout only, never under the lock);
     * NONE keeps everything (two or three buttons, or the lock: the camera only goes on following).
     */
    enum class TapAction { RECENTER, ZOOM, ADVANCE, NONE }

    /** What a tap does: [action], the orientation after it ([next]) and the zoom to ease to ([zoomTo]; null keeps it). */
    data class Tap(val action: TapAction, val next: Orientation, val zoomTo: Double? = null)

    /** The camera counts as at the close zoom within this many zoom levels below it (a pinch rarely lands on a whole level). */
    const val CLOSE_EPS = 0.05

    /** T176: the close zoom a tap brings the map to, unless [AppSettings.recenterZoom] says otherwise (the owner's reference). */
    const val DEFAULT_CLOSE_ZOOM = 14

    /** T176: the close zooms `recenterZoom` accepts, and the ones its row offers. */
    val CLOSE_ZOOM_RANGE = 10..18
    val CLOSE_ZOOM_CHOICES = listOf(12, 13, 14, 15, 16)

    /** T176: the layouts of the position button (`mapControls`): one button, two (plus orientation), three (plus the lock). */
    val LAYOUTS = 1..3

    /**
     * The tap rule (owner, 2026-10-13; T176, 2026-10-30): off centre a tap recentres on the fix and keeps the mode, and
     * zooms in to [closeZoom] when the camera is farther out; on a centred map farther out it only zooms in; on a centred
     * map at or nearer than [closeZoom] it advances the mode on the one-button layout ([layout] 1) unless [locked]
     * (north-up to facing when there is a compass, facing to north-up, free rotate to north-up), and does nothing on two
     * or three buttons, where the orientation button owns that. A tap never zooms out. [closeZoom] null (navigation keeps
     * its own zoom: a tap from the navigation card, or any tap while navigating) never zooms, and a centred map then
     * counts as close. Free rotate is never entered by a tap.
     */
    fun tap(
        current: Orientation,
        centred: Boolean,
        compass: Boolean,
        zoom: Double? = null,
        closeZoom: Double? = null,
        layout: Int = 1,
        locked: Boolean = false,
    ): Tap {
        val farOut = closeZoom != null && zoom != null && zoom < closeZoom - CLOSE_EPS
        val zoomTo = if (farOut) closeZoom else null
        return when {
            !centred -> Tap(TapAction.RECENTER, current, zoomTo)
            farOut -> Tap(TapAction.ZOOM, current, zoomTo)
            layout == 1 && !locked -> Tap(TapAction.ADVANCE, next(current, compass))
            else -> Tap(TapAction.NONE, current)
        }
    }

    /**
     * T176: the lock. Turned on, a free-rotated map goes north-up (free rotate is not a mode one chooses); north-up and
     * facing stay as chosen. While on, a bearing found off north at rest in north-up turns the camera back to north
     * ([AtRestBearing.TURN_NORTH]) instead of entering free rotate.
     */
    fun onLock(current: Orientation): Orientation = if (current == Orientation.FREE) Orientation.NORTH_UP else current

    enum class AtRestBearing { KEEP, TO_FREE, TO_NORTH_UP, TURN_NORTH }

    /**
     * At rest, what the bearing does to the mode: a north-up map turned by something else is free rotate (unlocked) or is
     * turned back to north (locked); a free-rotated map turned back to north is north-up. [offNorthDeg] is the bearing's
     * distance from north, 0..180.
     */
    fun atRestBearing(current: Orientation, offNorthDeg: Double, locked: Boolean): AtRestBearing = when {
        current == Orientation.NORTH_UP && offNorthDeg > 0.5 -> if (locked) AtRestBearing.TURN_NORTH else AtRestBearing.TO_FREE
        current == Orientation.FREE && (offNorthDeg < 0.05 || locked) -> AtRestBearing.TO_NORTH_UP
        else -> AtRestBearing.KEEP
    }

    /** T176: the layouts the room allows: [want] buttons, fewer while a column of [fits] does not fit (0: none fits). */
    fun shownButtons(want: Int, fits: (Int) -> Boolean): Int {
        var n = want.coerceIn(LAYOUTS)
        while (n > 0 && !fits(n)) n--
        return n
    }

    /** The next mode: of a centred tap on one button, and of the orientation button (two and three buttons, also under the lock). */
    fun next(current: Orientation, compass: Boolean): Orientation = when (current) {
        Orientation.NORTH_UP -> if (compass) Orientation.FACING else Orientation.NORTH_UP
        Orientation.FACING -> Orientation.NORTH_UP
        Orientation.FREE -> Orientation.NORTH_UP
    }

    enum class AtRest { KEEP, CATCH_UP, STOP }

    /**
     * Following, with the camera at rest and none of our moves in flight. [driftPx]: how far the camera's centre is
     * from where our last move put it (null before any); [fixOffsetPx]: how far the fix is from the centre. STOP when
     * the centre was moved by someone else (a pan, a route fit, a search result); CATCH_UP when only the fix moved on
     * (fixes are skipped while two fingers turn or pinch the map about its centre, so at driving speed the fix is well
     * off centre when they let go); else KEEP. Before any move of ours, the fix's offset decides.
     */
    fun atRest(driftPx: Double?, fixOffsetPx: Double?, tolerancePx: Double): AtRest = when {
        driftPx != null && driftPx > tolerancePx -> AtRest.STOP
        driftPx != null -> if (fixOffsetPx != null && fixOffsetPx > tolerancePx) AtRest.CATCH_UP else AtRest.KEEP
        fixOffsetPx != null && fixOffsetPx > tolerancePx -> AtRest.STOP
        else -> AtRest.KEEP
    }

    /** Centred: the camera is following the fix, or the fix sits within [tolerancePx] of the centre. */
    fun centred(following: Boolean, offsetPx: Double?, tolerancePx: Double): Boolean =
        following || (offsetPx != null && offsetPx <= tolerancePx)

    /** Navigation's camera mode (T61) as a view of the one state: not following is FREE. */
    fun cameraMode(o: Orientation, following: Boolean): CameraMode = when {
        !following -> CameraMode.FREE
        o == Orientation.FACING -> CameraMode.HEADING_UP
        else -> CameraMode.FOLLOW
    }

    /**
     * What setting navigation's camera mode does to the one state, as (orientation, following): FREE stops
     * following and keeps the orientation; FOLLOW follows north-up (a free-rotated map keeps its rotation);
     * HEADING_UP follows facing the heading.
     */
    fun fromCameraMode(mode: CameraMode, current: Orientation): Pair<Orientation, Boolean> = when (mode) {
        CameraMode.FREE -> current to false
        CameraMode.FOLLOW -> (if (current == Orientation.FACING) Orientation.NORTH_UP else current) to true
        CameraMode.HEADING_UP -> Orientation.FACING to true
    }

    /** The bearing the camera should have: north-up 0, free rotate as it is, facing the heading (as it is without one). */
    fun bearing(o: Orientation, currentDeg: Double, headingDeg: Double?): Double = when (o) {
        Orientation.NORTH_UP -> 0.0
        Orientation.FREE -> currentDeg
        Orientation.FACING -> headingDeg ?: currentDeg
    }

    /** Whether the camera should turn from [fromDeg] to [toDeg] (the shorter way round is at least [thresholdDeg]). */
    fun turns(fromDeg: Double, toDeg: Double, thresholdDeg: Double = TURN_DEG): Boolean = abs(rel(toDeg - fromDeg)) >= thresholdDeg

    /** -180..180. */
    fun rel(deg: Double): Double {
        var d = (deg % 360 + 360) % 360
        if (d > 180) d -= 360
        return d
    }

    /**
     * The heading for facing (and navigation's readout): a simulated one while fixes are injected (debug); the
     * course over ground while moving (faster than [MOVING_MPS], course younger than [COURSE_FRESH_MS]); the
     * compass when slow or still and it is running; else the last course; else none.
     */
    fun heading(
        simulatedDeg: Double?,
        injected: Boolean,
        courseDeg: Double?,
        courseAgeMs: Long?,
        speedMps: Double?,
        compassDeg: Double?,
        compassFresh: Boolean,
    ): HeadingReading? {
        if (simulatedDeg != null && injected) return HeadingReading(norm(simulatedDeg), "simulated")
        val moving = courseDeg != null && courseAgeMs != null && courseAgeMs <= COURSE_FRESH_MS && speedMps != null && speedMps >= MOVING_MPS
        if (moving) return HeadingReading(norm(courseDeg!!), "course")
        if (compassFresh && compassDeg != null) return HeadingReading(norm(compassDeg), "compass")
        if (courseDeg != null) return HeadingReading(norm(courseDeg), "course")
        return null
    }

    fun norm(deg: Double): Double = ((deg % 360) + 360) % 360
}
