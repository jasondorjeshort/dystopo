package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.dem.HorizonProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** T324: a text style as the drawer measures it: [width] of a string, [ascent] above the baseline (negative) and [descent] below, px. */
class PanoramaText(val width: (String) -> Double, val ascent: Double, val descent: Double)

/**
 * T324: what the Panorama's lens draws for an [aim] on a plot [width] by [height] px at [dp] px per dp, as plain numbers,
 * so the phone's Canvas and devtool draw one scene and neither writes geometry (wave 45, pass two of ADR 0064):
 *
 * - [layers], far to near: each skyline of the [LayeredSkyline] as the ground below it, filled outlines in px cut to the
 *   view ([Layer.rings], flat x, y pairs, filled with the even-odd rule) and its ridge line ([Layer.ridges], open
 *   polylines on the view). Filled in order (the farthest first) they give every pixel the layer
 *   [LayeredSkyline.layerAt] gives its ray; the tint is the drawer's, by [Layer.index] (0 the nearest).
 * - [level]: the level line (x0, y0, x1, y1) or null when it is off the view.
 * - [tape]: the bearing tape along the top, its ticks and labels (the eight compass points and the degrees between, the
 *   step chosen so no two labels touch).
 * - [names] placed by [HorizonLabels.place] through the lens, kept off [blocked] (the tape, past the view's edges, the
 *   zoom buttons' corner) with the forced names (the dominant, the closest, a pick) kept inside [keepIn]; [marks] every
 *   candidate on the view; [leftOut] every candidate not named, with its reason.
 *
 * Built on every frame of a drag: the skylines' directions are computed once ([LayeredSkyline.directions]), the outlines
 * cost a rotation per bin. During a gesture the names are not placed again: [build] with `held` keeps each name's offset
 * from its mark (wave 45's choice (g)).
 */
class PanoramaScene(
    val aim: PanoramaAim,
    val lens: LensView,
    val layers: List<Layer>,
    val level: DoubleArray?,
    val tape: Tape,
    val blocked: List<RimBox>,
    val keepIn: RimBox,
    val names: List<Name>,
    val marks: List<Mark>,
    val leftOut: List<LeftOut>,
) {
    val width: Double get() = lens.width
    val height: Double get() = lens.height

    /** One skyline's ground: [index] in the [LayeredSkyline] (0 the nearest), its [rangeM], [rings] to fill and [ridges] to stroke. */
    class Layer(val index: Int, val rangeM: Double, val rings: List<DoubleArray>, val ridges: List<DoubleArray>)

    /** A tick of the tape at [x] for [bearingDeg], from [top] to [bottom] (px); [major] where a label may stand. */
    class Tick(val x: Double, val bearingDeg: Double, val major: Boolean, val top: Double, val bottom: Double)

    /** A label of the tape: [text] centred on [x], its baseline at [baseline], its [box]. */
    class TapeLabel(val text: String, val bearingDeg: Double, val x: Double, val baseline: Double, val box: RimBox)

    /** The tape: its [box] (a plate along the top), the steps chosen, the [ticks] and the [labels]. */
    class Tape(val box: RimBox, val tickStepDeg: Double, val labelStepDeg: Double, val ticks: List<Tick>, val labels: List<TapeLabel>)

    /** A name placed: [placed] (its baseline centre, box and leader) and its mark ([markX], [markY]) where the triangle stands. */
    class Name(val placed: HorizonLabels.Placed, val markX: Double, val markY: Double) {
        val label: HorizonLabel get() = placed.label
    }

    /** A candidate on the view: its mark at ([x], [y]); [named] when its name is placed. */
    class Mark(val label: HorizonLabel, val x: Double, val y: Double, val named: Boolean)

    /** A candidate not named, and why: [REASON_BEHIND], [REASON_OFF_VIEW] or [REASON_NO_ROOM]. */
    class LeftOut(val label: HorizonLabel, val reason: String)

    companion object {
        /** The tape's height, dp; the labels' baseline [TAPE_BASELINE_DP] above its lower edge; ticks [TICK_MAJOR_DP] and [TICK_MINOR_DP] long. */
        const val TAPE_DP = 22.0
        const val TAPE_BASELINE_DP = 8.0
        const val TICK_MAJOR_DP = 6.0
        const val TICK_MINOR_DP = 3.5

        /** Ticks stand at least this far apart at the view's centre, dp. */
        const val TICK_MIN_DP = 12.0

        /** Two labels of the tape keep at least this gap, dp. */
        const val TAPE_GAP_DP = 6.0

        /** The tape's steps, degrees: each divides the next and 45 or 90, so the eight compass points fall on the labels. */
        val TAPE_STEPS = listOf(1.0, 5.0, 15.0, 45.0, 90.0)

        /** The eight compass points, from north clockwise. */
        val COMPASS8 = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        /** The zoom buttons (wave 45's choice d): [ZOOM_BUTTON_DP] square, one above the other, [ZOOM_MARGIN_DP] from the plot's lower right corner and apart. */
        const val ZOOM_BUTTON_DP = 48.0
        const val ZOOM_MARGIN_DP = 8.0

        /** The boxes of "Zoom in" (the upper) and "Zoom out" on a plot [width] by [height] px at [dp]. */
        fun zoomButtons(width: Double, height: Double, dp: Double): List<RimBox> {
            val s = ZOOM_BUTTON_DP * dp
            val m = ZOOM_MARGIN_DP * dp
            val out = RimBox(width - m - s, height - m - s, width - m, height - m)
            return listOf(RimBox(out.left, out.top - m - s, out.right, out.top - m), out)
        }

        /** The corner the zoom buttons stand in, margins included: the box names keep off. */
        fun zoomCorner(width: Double, height: Double, dp: Double): RimBox {
            val b = zoomButtons(width, height, dp)
            return RimBox(b[0].left - ZOOM_MARGIN_DP * dp, b[0].top - ZOOM_MARGIN_DP * dp, width, height)
        }

        const val REASON_BEHIND = "behind the viewer"
        const val REASON_OFF_VIEW = "off the view"
        const val REASON_NO_ROOM = "no room"

        /**
         * The scene for [aim] on a plot [width] by [height] px at [dp] over [skyline], naming [ranked] ([HorizonLabels.rank])
         * in [nameText], the tape's labels in [tapeText]; [zoomBox] the zoom buttons' corner (names keep off it); [picked]
         * placed first; [held] the names of the previous scene, kept at their offsets from their marks instead of placed
         * again (a gesture in progress; a name whose mark left the view goes).
         */
        fun build(
            aim: PanoramaAim, width: Double, height: Double, dp: Double, skyline: LayeredSkyline, ranked: List<HorizonLabels.Scored>,
            nameText: PanoramaText, tapeText: PanoramaText, zoomBox: RimBox? = null, picked: HorizonLabel? = null, held: List<Name>? = null,
        ): PanoramaScene {
            val lens = aim.lens(width, height, dp)
            val dirs = skyline.directions
            val layers = (skyline.profiles.indices.reversed()).map { i ->
                val o = LensOutline.of(lens, skyline.profiles[i], dirs[i])
                Layer(i, skyline.rangesM[i], o.rings, o.ridges)
            }
            val tape = tape(lens, tapeText)
            val big = 1e6
            val edges = listOf(RimBox(-big, -big, 0.0, big), RimBox(width, -big, big, big), RimBox(-big, -big, big, 0.0), RimBox(-big, height, big, big))
            val blocked = edges + tape.box + listOfNotNull(zoomBox)
            val keepIn = RimBox(0.0, tape.box.bottom, width, height)
            val names = if (held != null) {
                held.mapNotNull { n ->
                    val at = lens.mark(n.label.angleDeg, n.label.bearingDeg) ?: return@mapNotNull null
                    val dx = at.x - n.markX
                    val dy = at.y - n.markY
                    val p = n.placed
                    fun RimBox.moved() = RimBox(left + dx, top + dy, right + dx, bottom + dy)
                    Name(HorizonLabels.Placed(p.scored, p.x + dx, p.y + dy, p.box.moved(), p.gapDeg, p.leader?.map { RimPt(it.x + dx, it.y + dy) }, p.mark.moved()), at.x, at.y)
                }
            } else {
                HorizonLabels.place(lens, ranked, nameText.width, nameText.ascent, nameText.descent, blocked, first = picked, keepIn = keepIn).map { p ->
                    val at = lens.mark(p.label.angleDeg, p.label.bearingDeg)!!
                    Name(p, at.x, at.y)
                }
            }
            val named = names.map { it.label }.toSet()
            val marks = ranked.mapNotNull { s -> lens.mark(s.label.angleDeg, s.label.bearingDeg)?.let { Mark(s.label, it.x, it.y, s.label in named) } }
            val leftOut = ranked.filter { it.label !in named }.map { s ->
                val p = lens.project(s.label.angleDeg, s.label.bearingDeg)
                LeftOut(s.label, if (p == null) REASON_BEHIND else if (!lens.onView(p)) REASON_OFF_VIEW else REASON_NO_ROOM)
            }
            return PanoramaScene(aim, lens, layers, level(lens), tape, blocked, keepIn, names, marks, leftOut)
        }

        /** The level line on [lens] (no roll: a row, f tan(tilt) below the centre), or null off the view. */
        fun level(lens: LensView): DoubleArray? {
            if (abs(lens.tiltDeg) >= 89.999) return null
            val y = lens.cy + lens.focalPx * tan(lens.tiltDeg * PI / 180)
            return if (y in 0.0..lens.height) doubleArrayOf(0.0, y, lens.width, y) else null
        }

        /** The text of a tape label at [deg] (a whole number of degrees): a compass point at the multiples of 45, else the degrees. */
        fun tapeText(deg: Double): String {
            val d = deg.roundToInt().mod(360)
            return if (d % 45 == 0) COMPASS8[d / 45] else "$d°"
        }

        /**
         * The tape for [lens] in [text]: a plate [TAPE_DP] tall along the top; ticks where the bearings at its lower edge
         * cross a multiple of the tick step (the finest of [TAPE_STEPS] at least [TICK_MIN_DP] apart at the centre);
         * labels at the finest label step whose labels, wholly on the view, keep [TAPE_GAP_DP] apart (the steps are
         * tried on the real positions, so the edges' wider spacing and a tilted row are counted).
         */
        fun tape(lens: LensView, text: PanoramaText): Tape {
            val dp = lens.dp
            val bottom = TAPE_DP * dp
            val box = RimBox(0.0, 0.0, lens.width, bottom)
            val dpPerDeg = lens.pxPerDegAtCentre / dp
            val tick = TAPE_STEPS.firstOrNull { it * dpPerDeg >= TICK_MIN_DP } ?: TAPE_STEPS.last()
            val at = ArrayList<Pair<Double, Double>>()
            var m = 0.0
            while (m < 360.0 - 1e-9) {
                lens.xOnRow(m, bottom)?.takeIf { it in 0.0..lens.width }?.let { at += m to it }
                m += tick
            }
            at.sortBy { it.second }
            val baseline = bottom - TAPE_BASELINE_DP * dp
            val gap = TAPE_GAP_DP * dp
            fun labels(step: Double): List<TapeLabel> = at.filter { (deg, _) -> isMultiple(deg, step) }.mapNotNull { (deg, x) ->
                val s = tapeText(deg)
                val w = text.width(s)
                val b = RimBox(x - w / 2, baseline + text.ascent, x + w / 2, baseline + text.descent)
                if (b.left < 0 || b.right > lens.width) null else TapeLabel(s, deg, x, baseline, b)
            }
            fun touch(l: List<TapeLabel>) = l.zipWithNext().any { (a, b) -> b.box.left - a.box.right < gap }
            var step = TAPE_STEPS.last()
            var chosen: List<TapeLabel>? = null
            for (s in TAPE_STEPS) {
                if (s < tick || !isMultiple(s, tick)) continue
                val l = labels(s)
                if (!touch(l)) {
                    step = s; chosen = l; break
                }
            }
            // Crowded beyond every step (the row near the zenith): keep the cardinals first, then the rest that fit.
            val kept = chosen ?: run {
                val all = labels(step).sortedBy { l -> if (isMultiple(l.bearingDeg, 90.0)) 0 else 1 }
                val k = ArrayList<TapeLabel>()
                for (l in all) if (k.none { o -> l.box.left < o.box.right + gap && o.box.left < l.box.right + gap }) k += l
                k.sortedBy { it.x }
            }
            val ticks = at.map { (deg, x) ->
                val major = isMultiple(deg, step)
                Tick(x, deg, major, bottom - (if (major) TICK_MAJOR_DP else TICK_MINOR_DP) * dp, bottom)
            }
            return Tape(box, tick, step, ticks, kept)
        }

        private fun isMultiple(v: Double, step: Double): Boolean {
            val q = v / step
            return abs(q - q.roundToInt()) < 1e-6
        }
    }
}

/**
 * T324: the ground below one skyline ({el <= the profile's angle at az}) as seen through a lens with no roll, cut to the
 * view. The skyline is a closed loop of directions round the circle; only a part of it is in front of the lens. The loop
 * is cut where it crosses a cone round the view's axis whose image is a circle [DISK] times the view's half diagonal
 * (so the cut, and anything behind the lens, is far off the view), the pieces inside are projected, and they are joined
 * along that circle on the side the ground is on (each arc tested by the ray at its middle). The rings are then cut to
 * the view's rectangle. With no crossing the loop is all inside (looking up past the skyline: the ground is round the
 * outside of it, a hole) or all outside (all ground or all sky, by the ray at the centre).
 */
internal object LensOutline {
    const val DISK = 2.0
    private const val ARC_STEP = 5.0 * PI / 180

    class Result(val rings: List<DoubleArray>, val ridges: List<DoubleArray>)

    private class Piece(val entryPhi: Double, val exitPhi: Double, val pts: DoubleArray)
    private class Event(val phi: Double, val piece: Int, val entry: Boolean)

    fun of(lens: LensView, profile: HorizonProfile, dirs: DoubleArray): Result {
        val n = profile.size
        val w = lens.width
        val h = lens.height
        val cx = lens.cx
        val cy = lens.cy
        val f = lens.focalPx
        val r = DISK * hypot(w / 2, h / 2)
        val k2 = (r / f) * (r / f)
        val c = DoubleArray(3)
        val inside = BooleanArray(n)
        var any = false
        var all = true
        for (i in 0 until n) {
            lens.toCamera(dirs[3 * i], dirs[3 * i + 1], dirs[3 * i + 2], c)
            val ins = c[2] > 0 && c[0] * c[0] + c[1] * c[1] <= k2 * c[2] * c[2]
            inside[i] = ins
            any = any || ins
            all = all && ins
        }
        fun ground(x: Double, y: Double): Boolean = lens.ray(x, y).let { (el, az) -> el <= profile.angleAt(az) }
        val rect = doubleArrayOf(0.0, 0.0, w, 0.0, w, h, 0.0, h)
        if (!any) return Result(if (ground(cx, cy)) listOf(rect) else emptyList(), emptyList())

        fun project(i: Int, out: DoubleList) {
            lens.toCamera(dirs[3 * i], dirs[3 * i + 1], dirs[3 * i + 2], c)
            out.add(cx + f * c[0] / c[2], cy - f * c[1] / c[2])
        }
        if (all) {
            val loop = DoubleList(2 * n)
            for (i in 0 until n) project(i, loop)
            val pts = loop.toArray()
            val rings = if (pointIn(cx, cy, pts) == ground(cx, cy)) listOf(pts) else listOf(circle(cx, cy, r), pts)
            return Result(rings.mapNotNull { clipRing(it, w, h) }, clipPolyline(pts, true, w, h))
        }

        // The crossing of the cone on the segment from bin i to bin j (one inside, one not): bisection on the chord.
        fun cross(i: Int, j: Int): Double {
            var lo = 0.0
            var hi = 1.0
            val insideAtLo = inside[i]
            repeat(40) {
                val s = (lo + hi) / 2
                val e = dirs[3 * i] + s * (dirs[3 * j] - dirs[3 * i])
                val nn = dirs[3 * i + 1] + s * (dirs[3 * j + 1] - dirs[3 * i + 1])
                val u = dirs[3 * i + 2] + s * (dirs[3 * j + 2] - dirs[3 * i + 2])
                lens.toCamera(e, nn, u, c)
                val ins = c[2] > 0 && c[0] * c[0] + c[1] * c[1] <= k2 * c[2] * c[2]
                if (ins == insideAtLo) lo = s else hi = s
            }
            val s = (lo + hi) / 2
            lens.toCamera(
                dirs[3 * i] + s * (dirs[3 * j] - dirs[3 * i]), dirs[3 * i + 1] + s * (dirs[3 * j + 1] - dirs[3 * i + 1]),
                dirs[3 * i + 2] + s * (dirs[3 * j + 2] - dirs[3 * i + 2]), c,
            )
            return atan2(-c[1], c[0])
        }

        // The pieces inside, from an entry to an exit, walking the loop from the first entry.
        var i0 = -1
        for (i in 0 until n) if (!inside[i] && inside[(i + 1) % n]) { i0 = i; break }
        val pieces = ArrayList<Piece>()
        var cur: DoubleList? = null
        var entryPhi = 0.0
        for (step in 0 until n) {
            val i = (i0 + step) % n
            val j = (i + 1) % n
            when {
                !inside[i] && inside[j] -> {
                    entryPhi = cross(i, j)
                    cur = DoubleList(64).also { it.add(cx + r * cos(entryPhi), cy + r * sin(entryPhi)); project(j, it) }
                }
                inside[i] && inside[j] -> project(j, cur!!)
                inside[i] && !inside[j] -> {
                    val exitPhi = cross(i, j)
                    cur!!.add(cx + r * cos(exitPhi), cy + r * sin(exitPhi))
                    pieces += Piece(entryPhi, exitPhi, cur.toArray())
                    cur = null
                }
            }
        }

        // Join the pieces along the circle on the ground's side.
        val events = ArrayList<Event>()
        for ((k, p) in pieces.withIndex()) {
            events += Event(norm(p.entryPhi), k, true)
            events += Event(norm(p.exitPhi), k, false)
        }
        events.sortBy { it.phi }
        val exitAt = IntArray(pieces.size)
        for ((k, e) in events.withIndex()) if (!e.entry) exitAt[e.piece] = k
        val m = events.size
        val used = BooleanArray(pieces.size)
        val rings = ArrayList<DoubleArray>()
        for (start in pieces.indices) {
            if (used[start]) continue
            val ring = DoubleList(256)
            var p = start
            var guard = 0
            while (guard++ <= pieces.size) {
                used[p] = true
                ring.addAll(pieces[p].pts)
                val ek = exitAt[p]
                val phiE = events[ek].phi
                val plus = events[(ek + 1) % m]
                val minus = events[(ek - 1 + m) % m]
                val dPlus = ccw(phiE, plus.phi)
                val mid = phiE + dPlus / 2
                val goPlus = ground(cx + r * cos(mid), cy + r * sin(mid))
                val next = if (goPlus) plus else minus
                val delta = if (goPlus) dPlus else ccw(next.phi, phiE)
                val steps = ceil(delta / ARC_STEP).toInt()
                for (s in 1 until steps) {
                    val a = phiE + (if (goPlus) 1 else -1) * delta * s / steps
                    ring.add(cx + r * cos(a), cy + r * sin(a))
                }
                if (!next.entry || next.piece == start || used[next.piece]) break
                p = next.piece
            }
            clipRing(ring.toArray(), w, h)?.let { rings += it }
        }
        val ridges = pieces.flatMap { clipPolyline(it.pts, false, w, h) }
        return Result(rings, ridges)
    }

    private fun norm(a: Double): Double = ((a % (2 * PI)) + 2 * PI) % (2 * PI)

    /** The angle from [a] to [b] going the + way, in (0, 2 pi]. */
    private fun ccw(a: Double, b: Double): Double {
        val d = norm(b - a)
        return if (d < 1e-12) 2 * PI else d
    }

    private fun circle(cx: Double, cy: Double, r: Double): DoubleArray {
        val k = ceil(2 * PI / ARC_STEP).toInt()
        return DoubleArray(2 * k).also { for (i in 0 until k) { it[2 * i] = cx + r * cos(2 * PI * i / k); it[2 * i + 1] = cy + r * sin(2 * PI * i / k) } }
    }

    /** Whether ([x], [y]) is inside the ring [pts] (even-odd). */
    fun pointIn(x: Double, y: Double, pts: DoubleArray): Boolean {
        var inside = false
        val k = pts.size / 2
        var j = k - 1
        for (i in 0 until k) {
            val xi = pts[2 * i]; val yi = pts[2 * i + 1]
            val xj = pts[2 * j]; val yj = pts[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** The ring [pts] cut to the rectangle 0..[w] by 0..[h] (Sutherland-Hodgman), or null when nothing is left. */
    fun clipRing(pts: DoubleArray, w: Double, h: Double): DoubleArray? {
        var cur = pts
        for (edge in 0 until 4) {
            fun ins(x: Double, y: Double) = when (edge) { 0 -> x >= 0; 1 -> x <= w; 2 -> y >= 0; else -> y <= h }
            fun cut(ax: Double, ay: Double, bx: Double, by: Double, out: DoubleList) {
                val t = when (edge) { 0 -> (0 - ax) / (bx - ax); 1 -> (w - ax) / (bx - ax); 2 -> (0 - ay) / (by - ay); else -> (h - ay) / (by - ay) }
                out.add(ax + t * (bx - ax), ay + t * (by - ay))
            }
            val k = cur.size / 2
            if (k < 3) return null
            val out = DoubleList(cur.size + 8)
            var px = cur[2 * (k - 1)]
            var py = cur[2 * (k - 1) + 1]
            var pin = ins(px, py)
            for (i in 0 until k) {
                val x = cur[2 * i]
                val y = cur[2 * i + 1]
                val cin = ins(x, y)
                if (cin) {
                    if (!pin) cut(px, py, x, y, out)
                    out.add(x, y)
                } else if (pin) cut(px, py, x, y, out)
                px = x; py = y; pin = cin
            }
            cur = out.toArray()
        }
        return if (cur.size >= 6) cur else null
    }

    /** The polyline [pts] ([closed]: back to its start) cut to the rectangle: its runs on the view (Liang-Barsky per segment). */
    fun clipPolyline(pts: DoubleArray, closed: Boolean, w: Double, h: Double): List<DoubleArray> {
        val runs = ArrayList<DoubleArray>()
        val k = pts.size / 2
        var cur: DoubleList? = null
        val segs = if (closed) k else k - 1
        for (i in 0 until segs) {
            val j = (i + 1) % k
            val ax = pts[2 * i]; val ay = pts[2 * i + 1]
            val dx = pts[2 * j] - ax; val dy = pts[2 * j + 1] - ay
            var t0 = 0.0
            var t1 = 1.0
            var ok = true
            for ((p, q) in listOf(-dx to ax, dx to w - ax, -dy to ay, dy to h - ay)) {
                if (p == 0.0) { if (q < 0) ok = false } else {
                    val t = q / p
                    if (p < 0) { if (t > t1) ok = false else if (t > t0) t0 = t } else { if (t < t0) ok = false else if (t < t1) t1 = t }
                }
            }
            if (!ok) {
                cur?.let { if (it.size >= 4) runs += it.toArray() }
                cur = null
                continue
            }
            if (cur == null || t0 > 0) {
                cur?.let { if (it.size >= 4) runs += it.toArray() }
                cur = DoubleList(64).also { it.add(ax + t0 * dx, ay + t0 * dy) }
            }
            cur.add(ax + t1 * dx, ay + t1 * dy)
            if (t1 < 1) {
                if (cur.size >= 4) runs += cur.toArray()
                cur = null
            }
        }
        cur?.let { if (it.size >= 4) runs += it.toArray() }
        return runs
    }
}

/** A growable list of doubles (x, y pairs), so the outlines allocate no boxed points. */
internal class DoubleList(capacity: Int) {
    private var a = DoubleArray(maxOf(capacity, 4))
    var size = 0
        private set

    fun add(x: Double, y: Double) {
        if (size + 2 > a.size) a = a.copyOf(a.size * 2)
        a[size++] = x
        a[size++] = y
    }

    fun addAll(v: DoubleArray) {
        if (size + v.size > a.size) a = a.copyOf(maxOf(a.size * 2, size + v.size))
        v.copyInto(a, size)
        size += v.size
    }

    fun toArray(): DoubleArray = a.copyOf(size)
}
