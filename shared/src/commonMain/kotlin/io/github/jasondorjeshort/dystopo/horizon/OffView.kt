package io.github.jasondorjeshort.dystopo.horizon

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/** T324: which way to turn to bring a direction onto the Panorama's view; [word] as its line says it. */
enum class OffSide(val word: String) { LEFT("left"), RIGHT("right"), UP("up"), DOWN("down") }

/**
 * T324: a direction off the Panorama's view: turn [side] by [degrees] (left and right: the bearing from the view's centre
 * the shorter way round; up and down: the elevation from the view's centre); its arrow stands at ([x], [y]) on the edge
 * of the box the caller keeps arrows in, pointing ([dirX], [dirY]) (a unit vector, y down); [text] its line ("Sun, 40°
 * right"; "Mount Rainier, 12° up").
 */
data class OffViewArrow(val side: OffSide, val degrees: Double, val x: Double, val y: Double, val dirX: Double, val dirY: Double, val text: String)

/**
 * T324: the edge arrow for a picked thing that is off the lens (ADR 0064's ties to the lists; the Panorama's Shape:
 * "Sun, 40° right"). Pure, as [HorizonPlace.line] is: the phone draws the arrow and the line where this says.
 */
object OffView {
    /**
     * The arrow for [name] at ([elDeg], [azDeg]) on [lens] (no roll), standing on the edge of [keepIn] (the view less its
     * tape and margins), or null when its mark is on the view. Up or down when its bearing falls on the view at the view's
     * centre elevation; else left or right by the shorter way (exactly behind: right). The degrees are rounded in the text.
     */
    fun of(lens: LensView, elDeg: Double, azDeg: Double, name: String, keepIn: RimBox): OffViewArrow? {
        if (lens.mark(elDeg, azDeg) != null) return null
        val turn = PanoramaAim.signedDeg(azDeg - lens.bearingDeg)
        val column = if (abs(turn) < 90) lens.xOnRow(azDeg, lens.cy) else null
        if (column != null && column in 0.0..lens.width) {
            val up = elDeg > lens.tiltDeg
            val deg = abs(elDeg - lens.tiltDeg)
            val side = if (up) OffSide.UP else OffSide.DOWN
            return OffViewArrow(
                side, deg, column.coerceIn(keepIn.left, keepIn.right), if (up) keepIn.top else keepIn.bottom,
                0.0, if (up) -1.0 else 1.0, line(name, deg, side),
            )
        }
        val right = turn > 0 || turn == 180.0
        val d = elDeg - lens.tiltDeg
        val y = if (abs(d) < 89.0) lens.cy - lens.focalPx * tan(d * kotlin.math.PI / 180) else if (d > 0) keepIn.top else keepIn.bottom
        val side = if (right) OffSide.RIGHT else OffSide.LEFT
        val deg = abs(turn)
        return OffViewArrow(
            side, deg, if (right) keepIn.right else keepIn.left, y.coerceIn(keepIn.top, keepIn.bottom),
            if (right) 1.0 else -1.0, 0.0, line(name, deg, side),
        )
    }

    /** The arrow's line: "<name>, <degrees>° <side>". */
    fun line(name: String, degrees: Double, side: OffSide): String = "$name, ${degrees.roundToInt()}° ${side.word}"
}
