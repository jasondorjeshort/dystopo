package io.github.jasondorjeshort.dystopo.app.panorama

import io.github.jasondorjeshort.dystopo.horizon.HeadingCorrection
import io.github.jasondorjeshort.dystopo.horizon.PanoramaAim

/**
 * T331: the heading correction's lifetime (wave 46's choice (f)), a pure rule: a drag along the level line and the two
 * accessibility actions set it while the view follows the phone; a zoom, a pinch and a target keep it; Reset, leaving
 * Follow and closing the view drop it. It is never kept between sessions (the compass's error depends on where the phone
 * is and what is beside it). Null is no correction (no line on the plot); 0 is a correction that came back to none
 * ("Compass adjusted 0°", still with Reset).
 */
object PanoramaCorrection {
    sealed interface Event

    /** A finger's drag in Follow turned the drawing by [deg] (`HeadingCorrection.dragDeg`). */
    data class Dragged(val deg: Double) : Event

    /** "Adjust compass left" (-1) or "Adjust compass right" (+1), [HeadingCorrection.ACTION_STEP_DEG] each. */
    data class Stepped(val right: Boolean) : Event

    /** The debug route's `correction=<deg>|none`. */
    data class Set(val deg: Double?) : Event

    /** A pinch, a zoom button, a target set (a row, a name, the arrow): the correction stays. */
    data object Zoomed : Event
    data object Targeted : Event

    /** The line's Reset, the view leaving Follow (the button, the setting), the view closing or opening anew. */
    data object Reset : Event
    data object LeftFollow : Event
    data object Closed : Event

    /** The correction after [e], from [current] (null: none); [following] whether the view follows the phone now. */
    fun next(current: Double?, following: Boolean, e: Event): Double? = when (e) {
        is Dragged -> if (following) HeadingCorrection.add(current ?: 0.0, e.deg) else current
        is Stepped -> if (following) HeadingCorrection.add(current ?: 0.0, if (e.right) HeadingCorrection.ACTION_STEP_DEG else -HeadingCorrection.ACTION_STEP_DEG) else current
        is Set -> if (following) e.deg?.let { PanoramaAim.signedDeg(it) } else current
        Zoomed, Targeted -> current
        Reset, LeftFollow, Closed -> null
    }
}

/**
 * T331: the weak-compass line (wave 46's choice (h)), a pure rule: while the view follows the phone, the attitude sensor's
 * own accuracy status at low or unreliable (Android's 1 or 0; -1, no contact, counts as unreliable) for over [AFTER_MS]
 * shows the one quiet line under the plot; a better status, leaving Follow or no status at all takes it away at once.
 * The view never refuses to draw.
 */
object CompassHint {
    /** [lowSinceMs]: when the status last went low (null: not low now); [shown]: the line is up. */
    data class State(val lowSinceMs: Long? = null, val shown: Boolean = false)

    /** The status is weak this long before the line shows, ms. */
    const val AFTER_MS = 2000L

    /** Android's `SENSOR_STATUS_ACCURACY_LOW`: this and below is weak. */
    const val WEAK_AT_OR_BELOW = 1

    const val LINE = "Compass needs calibrating: move the phone in a figure 8"

    /** The state at [nowMs] for [status] (null: none reported) while [following]. */
    fun next(prev: State, following: Boolean, status: Int?, nowMs: Long): State {
        if (!following || status == null || status > WEAK_AT_OR_BELOW) return State()
        val since = prev.lowSinceMs ?: nowMs
        return State(since, nowMs - since > AFTER_MS)
    }
}
