package io.github.jasondorjeshort.dystopo.horizon

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * T324: where the Panorama's lens looks (wave 45's choices (c) and (d), ADR 0064): [bearingDeg] true at the view's centre
 * (0 to 360, wrapping), [tiltDeg] from the level ([MIN_TILT_DEG] to [MAX_TILT_DEG]), [fovDeg] across the plot's width
 * ([MIN_FOV_DEG] to [MAX_FOV_DEG]); no roll (up on the screen is up; the roll is pass three's). Pure: each gesture is a
 * new aim, so the phone's plot steers by these numbers alone and draws [PanoramaScene] for the result.
 *
 * - [drag]: the direction at the view's centre goes where the finger takes it (exactly, not by a fixed degrees per px),
 *   so the ground under a finger near the centre stays under it; the bearing wraps, the tilt stops at its limits. Near
 *   straight up a sideways drag turns the bearing fast, as the bearings crowd round the zenith.
 * - [pinch] by a factor about a focus (the direction under the focus stays there) and [zoom] (the buttons' step, about the
 *   centre) change the field of view within its limits.
 * - [turnTo] brings a direction to the centre column by the shorter way round and tilts only when it would be off the view.
 */
data class PanoramaAim(val bearingDeg: Double, val tiltDeg: Double, val fovDeg: Double) {
    init {
        require(tiltDeg in MIN_TILT_DEG..MAX_TILT_DEG) { "tiltDeg must be in [$MIN_TILT_DEG, $MAX_TILT_DEG]" }
        require(fovDeg in MIN_FOV_DEG..MAX_FOV_DEG) { "fovDeg must be in [$MIN_FOV_DEG, $MAX_FOV_DEG]" }
    }

    /** The lens for a plot [width] by [height] px at [dp] px per dp. */
    fun lens(width: Double, height: Double, dp: Double): LensView = LensView(width, height, dp, bearingDeg, tiltDeg, 0.0, fovDeg)

    /** The focal length on a plot [width] px wide, px. */
    fun focalPx(width: Double): Double = focal(width, fovDeg)

    /** A drag by ([dx], [dy]) px (y down) on a plot [width] px wide: the direction at the centre moves by that much on the screen. */
    fun drag(dx: Double, dy: Double, width: Double): PanoramaAim {
        val f = focalPx(width)
        return aimAt(tiltDeg, bearingDeg, dx / f, -dy / f, fovDeg, bearingDeg)
    }

    /**
     * A pinch by [factor] (above 1 spreads the fingers: zooms in) about the point ([focusX], [focusY]) of a plot [width] by
     * [height] px: the field of view narrows by the factor (the focal length grows by it), held to its limits, and the
     * direction under the focus stays under it.
     */
    fun pinch(factor: Double, focusX: Double, focusY: Double, width: Double, height: Double): PanoramaAim {
        require(factor > 0) { "factor must be positive" }
        val (el, az) = lens(width, height, 1.0).ray(focusX, focusY)
        val fov = (2 * atan(tan(fovDeg / 2 * DEG) / factor) / DEG).coerceIn(MIN_FOV_DEG, MAX_FOV_DEG)
        val f = focal(width, fov)
        return aimAt(el, az, (focusX - width / 2) / f, -(focusY - height / 2) / f, fov, bearingDeg)
    }

    /** One press of "Zoom in" ([inward]) or "Zoom out": a pinch by [ZOOM_STEP] about the centre. */
    fun zoom(inward: Boolean, width: Double, height: Double): PanoramaAim =
        pinch(if (inward) ZOOM_STEP else 1 / ZOOM_STEP, width / 2, height / 2, width, height)

    /** [aim] after a turn of [turnDeg] (negative: to the left; the shorter way round, -180 to 180). */
    data class Turn(val aim: PanoramaAim, val turnDeg: Double)

    /**
     * Turned to the direction ([elDeg], [azDeg]): the bearing becomes [azDeg] (the turn the shorter way round, for an
     * animation), and the tilt changes only when the direction would otherwise stand off the view's height ([topPx] from
     * the top, the tape, to the bottom of a plot [height] px tall): then the view is tilted to it, held to the tilt's
     * limits.
     */
    fun turnTo(elDeg: Double, azDeg: Double, width: Double, height: Double, topPx: Double = 0.0): Turn {
        val turn = signedDeg(azDeg - bearingDeg)
        val d = elDeg - tiltDeg
        val y = if (abs(d) < 89.0) height / 2 - focalPx(width) * tan(d * DEG) else Double.NaN
        val tilt = if (y in topPx..height) tiltDeg else elDeg.coerceIn(MIN_TILT_DEG, MAX_TILT_DEG)
        return Turn(PanoramaAim(wrapDeg(azDeg), tilt, fovDeg), turn)
    }

    companion object {
        private const val DEG = PI / 180

        /** The field of view at opening, degrees (T322's proposal, wave 45's choice c). */
        const val OPENING_FOV_DEG = 60.0

        /** At opening the level line stands this far down the plot (T322's proposal: the level set low). */
        const val OPENING_LEVEL_AT = 2.0 / 3.0

        /** The tilt's limits (choice d): 45 degrees below the level (the terrain below the horizon is not drawn) to straight up. */
        const val MIN_TILT_DEG = -45.0
        const val MAX_TILT_DEG = 90.0

        /** The field of view's limits (choice d): ADR 0064's widest; the narrowest the 0.1 degree sweep carries. */
        const val MIN_FOV_DEG = 10.0
        const val MAX_FOV_DEG = 100.0

        /** One press of a zoom button changes the focal length by this factor. */
        const val ZOOM_STEP = 1.5

        /**
         * The aim at opening for a plot [width] by [height] px looking toward [bearingDeg]: [fovDeg] across (60 unless
         * given) and the tilt that sets the level line [OPENING_LEVEL_AT] down the plot.
         */
        fun opening(width: Double, height: Double, bearingDeg: Double, fovDeg: Double = OPENING_FOV_DEG): PanoramaAim {
            val f = focal(width, fovDeg)
            val tilt = atan((OPENING_LEVEL_AT - 0.5) * height / f) / DEG
            return PanoramaAim(wrapDeg(bearingDeg), tilt.coerceIn(MIN_TILT_DEG, MAX_TILT_DEG), fovDeg)
        }

        fun focal(width: Double, fovDeg: Double): Double = width / 2 / tan(fovDeg / 2 * DEG)

        /** [deg] in 0 until 360. */
        fun wrapDeg(deg: Double): Double = ((deg % 360) + 360) % 360

        /** [deg] in (-180, 180]. */
        fun signedDeg(deg: Double): Double {
            val d = wrapDeg(deg)
            return if (d > 180) d - 360 else d
        }

        /**
         * The aim (with field of view [fov]) that shows the direction ([elDeg], [azDeg]) at the screen offset ([sx], [sy])
         * from the centre, over the focal length (y up). With no roll the ray at that offset, in a frame turned to the
         * bearing, is (sx, cos t - sy sin t, sin t + sy cos t) for the tilt t; its length is fixed, so its elevation gives
         * t in closed form and its level part the bearing. The tilt is then held to its limits; [keepBearing] stands when
         * the direction is straight up or down from the view (no bearing to turn to).
         */
        internal fun aimAt(elDeg: Double, azDeg: Double, sx: Double, sy: Double, fov: Double, keepBearing: Double): PanoramaAim {
            val len = sqrt(1 + sx * sx + sy * sy)
            val arg = (len * sin(elDeg * DEG) / sqrt(1 + sy * sy)).coerceIn(-1.0, 1.0)
            val t = ((asin(arg) - atan(sy)) / DEG).coerceIn(MIN_TILT_DEG, MAX_TILT_DEG)
            val tr = t * DEG
            val level = kotlin.math.cos(tr) - sy * sin(tr)
            val bearing = if (abs(sx) < 1e-12 && abs(level) < 1e-12) keepBearing else azDeg - atan2(sx, level) / DEG
            return PanoramaAim(wrapDeg(bearing), t, fov)
        }
    }
}

/**
 * T324: the bearing steps the Panorama's skylines are swept at for a field of view and a plot width (wave 45's choice
 * (f)): the coarse step first, so a drag round the circle never waits on a sweep, and last the step fine enough for that
 * zoom, so the skyline shows no straight runs. A caller sweeps and draws them in order.
 */
object PanoramaSweep {
    /** The steps tried, coarse to fine, degrees. */
    val STEPS = listOf(1.0, 0.5, 0.25, 0.1)

    /**
     * The longest straight run of the skyline at the view's centre that does not show, px: T322's renders at 15 degrees on
     * a 1080 px view showed runs of 18 px (0.25 degree) and not of 7 px (0.1 degree).
     */
    const val MAX_RUN_PX = 12.0

    /** The steps for [fovDeg] across [widthPx]: [STEPS]' first, then the coarsest whose run at the centre is at most [MAX_RUN_PX] (0.1 at the narrowest). */
    fun steps(fovDeg: Double, widthPx: Double): List<Double> {
        val pxPerDeg = PanoramaAim.focal(widthPx, fovDeg) * PI / 180
        val fine = STEPS.firstOrNull { it * pxPerDeg <= MAX_RUN_PX } ?: STEPS.last()
        return if (fine == STEPS.first()) listOf(fine) else listOf(STEPS.first(), fine)
    }
}
