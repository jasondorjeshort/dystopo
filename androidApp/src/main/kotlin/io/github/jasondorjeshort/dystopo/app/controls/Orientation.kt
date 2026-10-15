package io.github.jasondorjeshort.dystopo.app.controls

import io.github.jasondorjeshort.dystopo.app.MapController.CameraMode
import kotlin.math.abs

/**
 * T100: the map's orientation, one state for the whole app (docs/ideas/2026-10-13-recenter-button.md).
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

    enum class TapAction { RECENTER, ADVANCE }

    /** What a tap does: [action], and the orientation after it ([next]). */
    data class Tap(val action: TapAction, val next: Orientation)

    /**
     * The owner's tap rule (2026-10-13): off centre, a tap only recentres on the fix and keeps the mode; on a
     * centred map it advances the mode (north-up to facing when there is a compass, facing to north-up, free
     * rotate to north-up). Free rotate is never entered by a tap.
     */
    fun tap(current: Orientation, centred: Boolean, compass: Boolean): Tap =
        if (!centred) Tap(TapAction.RECENTER, current) else Tap(TapAction.ADVANCE, next(current, compass))

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
