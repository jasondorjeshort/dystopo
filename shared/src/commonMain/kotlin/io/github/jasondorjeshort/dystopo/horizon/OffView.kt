package io.github.jasondorjeshort.dystopo.horizon

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** T324: which way to turn to bring a direction onto the Panorama's view; [word] as its line says it. */
enum class OffSide(val word: String) { LEFT("left"), RIGHT("right"), UP("up"), DOWN("down") }

/**
 * T324: a direction off the Panorama's view: turn [side] by [degrees] (left and right: about the screen's up axis, the
 * shorter way round; up and down: about the screen's right axis after that); its arrow stands at ([x], [y]) on the edge
 * of the box the caller keeps arrows in, pointing ([dirX], [dirY]) (a unit vector, y down); [text] its line ("Sun, 40°
 * right"; "Mount Rainier, 12° up").
 */
data class OffViewArrow(val side: OffSide, val degrees: Double, val x: Double, val y: Double, val dirX: Double, val dirY: Double, val text: String)

/**
 * T324: the edge arrow for a picked thing that is off the lens (ADR 0064's ties to the lists; the Panorama's Shape:
 * "Sun, 40° right"). Pure, as [HorizonPlace.line] is: the phone draws the arrow and the line where this says.
 */
object OffView {
    private const val DEG = PI / 180

    /**
     * The arrow for [name] at ([elDeg], [azDeg]) on [lens], standing on the edge of [keepIn] (the view less its tape and
     * margins), or null when its mark is on the view. T329: by the screen's axes, so it reads as the user must turn the
     * phone at any roll: up or down when the direction falls in front within the view's width (it is above or below
     * the view); else left or right by the side of the screen it is on (exactly behind: right). Left and right count the
     * turn about the screen's up axis, up and down the turn about its right axis after it; at no roll and no tilt they
     * are the bearing's and the elevation's differences. The degrees are rounded in the text.
     */
    fun of(lens: LensView, elDeg: Double, azDeg: Double, name: String, keepIn: RimBox): OffViewArrow? {
        if (lens.mark(elDeg, azDeg) != null) return null
        val el = elDeg * DEG
        val az = azDeg * DEG
        val c = DoubleArray(3)
        lens.toCamera(kotlin.math.cos(el) * sin(az), kotlin.math.cos(el) * cos(az), sin(el), c)
        val (cx, cy, cz) = Triple(c[0], c[1], c[2])
        val f = lens.focalPx
        // In front within the view's width (a direction square to the view straight above or below it counts).
        val inColumn = cz >= -1e-12 && abs(cx) * f <= lens.width / 2 * cz + 1e-9
        val sx = if (cz > 1e-9) lens.cx + f * cx / cz else lens.cx
        val pitch = atan2(cy, hypot(cx, cz)) / DEG
        if (inColumn) {
            val up = cy > 0
            val deg = abs(pitch)
            val side = if (up) OffSide.UP else OffSide.DOWN
            return OffViewArrow(
                side, deg, sx.coerceIn(keepIn.left, keepIn.right), if (up) keepIn.top else keepIn.bottom,
                0.0, if (up) -1.0 else 1.0, line(name, deg, side),
            )
        }
        val yaw = atan2(cx, cz) / DEG
        val right = cx > 1e-12 || (abs(cx) <= 1e-12 && cz < 0)
        val y = if (abs(pitch) < 89.0) lens.cy - f * kotlin.math.tan(pitch * DEG) else if (pitch > 0) keepIn.top else keepIn.bottom
        val side = if (right) OffSide.RIGHT else OffSide.LEFT
        val deg = abs(yaw)
        return OffViewArrow(
            side, deg, if (right) keepIn.right else keepIn.left, y.coerceIn(keepIn.top, keepIn.bottom),
            if (right) 1.0 else -1.0, 0.0, line(name, deg, side),
        )
    }

    /** The arrow's line: "<name>, <degrees>° <side>". */
    fun line(name: String, degrees: Double, side: OffSide): String = "$name, ${degrees.roundToInt()}° ${side.word}"
}

/**
 * T329: the heading correction of wave 46's choice (f): in Follow a drag along the drawn level line turns the drawing
 * about the vertical, following the finger at the view's centre; the travel across the line does nothing (the tilt and
 * the roll come from gravity; the compass is what errs). The correction is degrees added to the phone's true bearing
 * (positive: east), applied by [PhoneAttitude.turned] with the declination, wrapped to (-180, 180]. Its line ("Compass
 * adjusted 4° east") stands here beside the arrow's.
 */
object HeadingCorrection {
    private const val DEG = PI / 180

    /** One press of "Adjust compass left" or "Adjust compass right" (choice (f)), degrees. */
    const val ACTION_STEP_DEG = 1.0

    /**
     * The correction's change, degrees, for a finger's travel ([dx], [dy]) px (y down) on [lens]: the travel along the
     * level line's direction at the centre (the image of the level right, (r0 . right, -(r0 . up))), s px, turns the view
     * so the ray s px from the centre along it takes the centre's bearing: atan2(s / f, cos tilt). The drawing follows
     * the finger, so a drag to the right turns the view west (negative). Bounded at 90 degrees as the view nears
     * straight up or down.
     */
    fun dragDeg(lens: LensView, dx: Double, dy: Double): Double {
        val b = lens.bearingDeg * DEG
        val a = lens.axes
        val h = Enu(cos(b), -sin(b), 0.0)
        val lx = h dot a.right
        val ly = -(h dot a.up)
        val len = hypot(lx, ly)
        if (len < 1e-9) return 0.0
        val s = (dx * lx + dy * ly) / len
        val cosTilt = hypot(a.forward.e, a.forward.n)
        return -atan2(s / lens.focalPx, cosTilt) / DEG
    }

    /** [currentDeg] changed by [deltaDeg], wrapped to (-180, 180]. */
    fun add(currentDeg: Double, deltaDeg: Double): Double = PanoramaAim.signedDeg(currentDeg + deltaDeg)

    /** The correction's line: "Compass adjusted 4° east" (positive), "... west" (negative), "Compass adjusted 0°" when it rounds to none. */
    fun line(correctionDeg: Double): String {
        val d = abs(correctionDeg).roundToInt()
        return when {
            d == 0 -> "Compass adjusted 0°"
            correctionDeg > 0 -> "Compass adjusted $d° east"
            else -> "Compass adjusted $d° west"
        }
    }
}
