package io.github.jasondorjeshort.dystopo.horizon

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** T309: what a label on the Horizon view's plot names; [weight] multiplies its score (later: huts, waypoints, the car). */
enum class LabelKind(val key: String, val weight: Double) {
    PEAK("peak", 1.0),

    /** T312: an aircraft of the Flights layer, placed in a second pass after the peaks ([HorizonLabels.placeLayers]). */
    AIRCRAFT("aircraft", 1.0),
}

/**
 * T309: one thing the Horizon view may name on its plot: [bearingDeg] true, [angleDeg] its apparent elevation above the
 * horizontal, [distanceM], [clearanceDeg] its height above the terrain's line of sight at its bearing (negative: out of
 * sight by that much; +90 with nothing in front), [eleM], [promM] its prominence (null: unknown, [HorizonLabels.prominence]
 * stands in), [dominant] the highest skyline peak of the view (placed first, never dropped), [ref] what it stands for.
 */
data class HorizonLabel(
    val name: String,
    val kind: LabelKind,
    val bearingDeg: Double,
    val angleDeg: Double,
    val distanceM: Double,
    val clearanceDeg: Double,
    val eleM: Double,
    val promM: Double? = null,
    val dominant: Boolean = false,
    val ref: Any? = null,
) {
    val outOfSight: Boolean get() = clearanceDeg < 0
}

/**
 * T309: which labels the Horizon view names, and where (the owner, 2026-11-20: "some sort of algorithm to prioritize peaks
 * (and more later) based on distance and height above horizon. Then put the highest ones up based on some sort of density
 * function"). The research behind it (the coordinator's, 2026-11-20): no app publishes its weights; the common practice is
 * a ranking by importance and a greedy placement, highest first, with collision tests, the density emerging from the room
 * left. Every weight here is a first guess, to tune against real rims.
 *
 * **Score** ([score]): `S = 4.0 Dn + 2.5 Hn + 1.5 An + 1.0 Pn + 0.5 En`, each term 0 to 1: `Dn = 1 / (1 + d / 2 km)` (distance
 * weighs most), `Hn` the clearance over 1.5° (0.5 just out of sight: barely below the line), `An` the apparent angle over 6°, `Pn` the prominence on a log
 * scale to 1000 m, `En` the elevation within the candidates' range; +10 for the dominant peak, ×0.9 out of sight, × the
 * kind's weight. The dominant peak, then the closest candidate, are always placed. Ties: nearer, then by name.
 *
 * **Placement** ([place]): highest score first, each label at the first of [STEPS] places inward from its mark where its
 * box touches no label placed before, no ring label, no hour label and not the sun's arc, and only where no label already
 * placed stands within the density gap in bearing: `4° × sqrt(nLocal / 5)`, at least 2°, nLocal the candidates within ±15°
 * (a crowded stretch of the rim gets sparser names, an empty one takes close neighbours). No fixed cap per sector.
 */
object HorizonLabels {
    // The score's weights (first guesses).
    const val W_DISTANCE = 4.0 // T309's fix round: 3.0 over 5 km first; Alta Vista, 663 m off and hidden, was not named
    const val W_HEIGHT = 2.5
    const val W_ANGLE = 1.5
    const val W_PROMINENCE = 1.0
    const val W_ELEVATION = 0.5
    const val DOMINANT_BONUS = 10.0
    const val OUT_OF_SIGHT_FACTOR = 0.9

    /** A candidate just out of sight is barely below the line, not absent: half the clearance term. */
    const val OUT_OF_SIGHT_HEIGHT = 0.5

    // The terms' scales.
    const val DISTANCE_SCALE_M = 2000.0
    const val HEIGHT_FULL_DEG = 1.5
    const val ANGLE_FULL_DEG = 6.0
    const val PROMINENCE_STEP_M = 50.0
    const val PROMINENCE_FULL_M = 1000.0

    /** Prominence unknown: the elevation over the median of the candidates within this distance of it. */
    const val PROMINENCE_NEAR_M = 5000.0

    // The density gap.
    const val GAP_DEG = 4.0
    const val GAP_MIN_DEG = 2.0
    const val GAP_LOCAL = 5.0
    const val LOCAL_DEG = 15.0

    /** Places tried inward from a label's mark: [FIRST_STEP_DP] in, then [STEP_DP] more each. */
    const val STEPS = 4
    const val FIRST_STEP_DP = 16.0
    const val STEP_DP = 15.0

    /** T309: the places beside the mark, either side along the rim: the name's near edge this far from it, [SIDE_INWARD_DP] in. */
    const val SIDE_GAP_DP = 9.0
    const val SIDE_INWARD_DP = 4.0

    /** T309: a name whose box is farther than this from its mark gets a leader line to it. */
    const val LEADER_DP = 10.0

    private fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)
    private fun log2(v: Double) = ln(v) / ln(2.0)

    /** The candidates' elevation range, which [score]'s elevation term spans. */
    class Context(val minEleM: Double, val maxEleM: Double) {
        companion object {
            fun of(labels: List<HorizonLabel>) = Context(labels.minOfOrNull { it.eleM } ?: 0.0, labels.maxOfOrNull { it.eleM } ?: 0.0)
        }
    }

    /** One label's terms and score, for the report and `GET /horizon`. */
    class Scored(val label: HorizonLabel, val dn: Double, val hn: Double, val an: Double, val pn: Double, val en: Double, val score: Double)

    fun score(l: HorizonLabel, ctx: Context): Scored {
        val dn = 1 / (1 + l.distanceM / DISTANCE_SCALE_M)
        val hn = if (l.outOfSight) OUT_OF_SIGHT_HEIGHT else clamp01(l.clearanceDeg / HEIGHT_FULL_DEG)
        val an = clamp01(l.angleDeg / ANGLE_FULL_DEG)
        val prom = l.promM ?: 0.0
        val pn = clamp01(log2(1 + max(prom, 0.0) / PROMINENCE_STEP_M) / log2(1 + PROMINENCE_FULL_M / PROMINENCE_STEP_M))
        val en = if (ctx.maxEleM > ctx.minEleM) clamp01((l.eleM - ctx.minEleM) / (ctx.maxEleM - ctx.minEleM)) else 0.0
        var s = W_DISTANCE * dn + W_HEIGHT * hn + W_ANGLE * an + W_PROMINENCE * pn + W_ELEVATION * en
        if (l.outOfSight) s *= OUT_OF_SIGHT_FACTOR
        s *= l.kind.weight
        if (l.dominant) s += DOMINANT_BONUS
        return Scored(l, dn, hn, an, pn, en, s)
    }

    /** [labels] scored and ranked, highest first (ties: nearer, then by name). */
    fun rank(labels: List<HorizonLabel>): List<Scored> {
        val ctx = Context.of(labels)
        return labels.map { score(it, ctx) }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.label.distanceM }.thenBy { it.label.name })
    }

    /**
     * Prominence where it is unknown: [eleM] over the median elevation of [others] within [PROMINENCE_NEAR_M] (by the
     * [near] test), at least 0; 0 with none near.
     */
    fun prominence(eleM: Double, nearEle: List<Double>): Double {
        if (nearEle.isEmpty()) return 0.0
        val s = nearEle.sorted()
        val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        return max(0.0, eleM - median)
    }

    /** The bearing between [a] and [b] the short way round, 0 to 180 degrees. */
    fun apart(a: Double, b: Double): Double {
        val d = (((a - b) % 360) + 360) % 360
        return if (d > 180) 360 - d else d
    }

    /** The density gap (degrees) for a label with [nLocal] candidates within ±[LOCAL_DEG] of its bearing (itself in). */
    fun gapDeg(nLocal: Int): Double = max(GAP_MIN_DEG, GAP_DEG * sqrt(nLocal / GAP_LOCAL))

    /**
     * A label placed: its [scored] entry, the text's baseline centre ([x], [y], px), its box, the gap it was held to, and
     * (T309) its [leader] line from the mark to the box when the box stands more than [LEADER_DP] from the mark.
     */
    class Placed(
        val scored: Scored, val x: Double, val y: Double, val box: RimBox, val gapDeg: Double, val leader: List<RimPt>? = null,
        /** Its triangle's box, which later names keep off. */
        val mark: RimBox = box,
    ) {
        val label: HorizonLabel get() = scored.label
    }

    /** The point of [b] nearest [p]. */
    private fun nearest(b: RimBox, p: RimPt) = RimPt(p.x.coerceIn(b.left, b.right), p.y.coerceIn(b.top, b.bottom))

    /**
     * [ranked] placed on [g], highest first: each by its mark ([LabelProjection.mark] of its angle and bearing; T322: a
     * label whose mark is off the view is left out, forced or not), at the nearest
     * of the places around it (inward, [LabelProjection.away], at [STEPS] steps, and beside it either side; T309)
     * where its box (the text [width] of its name, [ascent] above the baseline
     * (negative) and [descent] below, padded 2 dp) touches no label placed before, none of [blocked] (the ring and hour
     * labels) and no segment of [lines] (the sun's arc), and only when no label placed stands within its density gap
     * ([LabelProjection.separation]: in bearing on the rim; T322); a box farther than [LEADER_DP] from its mark gets a leader line, which crosses no name and which no later
     * name crosses. The dominant label, then the closest of the view, are placed first and whatever the density: at their
     * first free place, else at the first. The rest without a free place are left out (the list has them). [first] goes
     * before everything (a picked label). T312: [forceClosest] false places the closest by rank like the rest (the aircraft
     * pass: no forced name over a peak's).
     */
    fun place(
        g: LabelProjection, ranked: List<Scored>, width: (String) -> Double, ascent: Double, descent: Double,
        blocked: List<RimBox> = emptyList(), lines: List<List<RimPt>> = emptyList(), first: HorizonLabel? = null,
        forceClosest: Boolean = true,
    ): List<Placed> {
        val placed = ArrayList<Placed>()
        val pad = 2 * g.dp
        val labels = ranked.map { it.label }
        val closest = if (forceClosest) ranked.minByOrNull { it.label.distanceM }?.label else null
        // Stable: the picked one, the dominant, the closest, then the rank.
        val order = ranked.sortedWith(compareByDescending<Scored> { it.label == first }.thenByDescending { it.label.dominant }.thenByDescending { it.label == closest })
        for (s in order) {
            val l = s.label
            val at = g.mark(l.angleDeg, l.bearingDeg) ?: continue
            val nLocal = labels.count { g.separation(it, l) <= LOCAL_DEG }
            val gap = gapDeg(nLocal)
            val forced = l.dominant || l == first || l == closest
            if (!forced && placed.any { g.separation(it.label, l) < gap }) continue
            val w = width(l.name)
            val away = g.away(at, l.angleDeg, l.bearingDeg)
            val ux = away.x
            val uy = away.y
            fun placeAt(x: Double, y: Double): Placed {
                val box = RimBox(x - w / 2 - pad, y + ascent - pad, x + w / 2 + pad, y + descent + pad)
                val n = nearest(box, at)
                val far = hypot(n.x - at.x, n.y - at.y) > LEADER_DP * g.dp
                return Placed(s, x, y, box, gap, if (far) listOf(at, n) else null, RimBox(at.x - 6 * g.dp, at.y - 6 * g.dp, at.x + 6 * g.dp, at.y + 5 * g.dp))
            }
            // The places around the mark, nearest first: inward along the radius, and beside it either side along the rim.
            val inward = (0 until STEPS).map { step ->
                val d = (FIRST_STEP_DP + STEP_DP * step) * g.dp
                placeAt(at.x + ux * d, at.y + uy * d + 4 * g.dp)
            }
            // Beside: the box's centre off the mark along the rim's tangent (-uy, ux) by its half extent that way and a gap.
            val halfW = w / 2 + pad
            val halfH = (descent - ascent) / 2 + pad
            val side = listOf(1.0, -1.0).map { k ->
                val off = halfW * kotlin.math.abs(uy) + halfH * kotlin.math.abs(ux) + SIDE_GAP_DP * g.dp
                val cx = at.x + ux * SIDE_INWARD_DP * g.dp - uy * off * k
                val cy = at.y + uy * SIDE_INWARD_DP * g.dp + ux * off * k
                placeAt(cx, cy - (ascent + descent) / 2)
            }
            fun dist(p: Placed) = nearest(p.box, at).let { n -> hypot(n.x - at.x, n.y - at.y) }
            val places = (inward + side).sortedBy(::dist)
            // T309: a name covers no named peak's triangle, its own included, and no triangle stands under a name placed before.
            val mark = RimBox(at.x - 6 * g.dp, at.y - 6 * g.dp, at.x + 6 * g.dp, at.y + 5 * g.dp)
            if (!forced && placed.any { it.box.overlaps(mark) }) continue
            // A forced name with no free place takes the nearest that is over no other name, else the first inward.
            var chosen: Placed? = if (forced) places.firstOrNull { p -> placed.none { it.box.overlaps(p.box) } } ?: inward.first() else null
            for (p in places) {
                val box = p.box
                val free = !box.overlaps(mark) && placed.none { it.box.overlaps(box) || it.mark.overlaps(box) || (it.leader != null && box.crosses(it.leader)) } &&
                    blocked.none { it.overlaps(box) } && lines.none { box.crosses(it) } &&
                    (p.leader == null || placed.none { it.box.crosses(p.leader) })
                if (free) {
                    chosen = p
                    break
                }
            }
            if (chosen != null && (forced || placed.none { it.box.overlaps(chosen.box) })) placed += chosen
        }
        return placed
    }

    /** T312: the two passes' names: [peaks] as [place] put them alone, then [aircraft] around them. */
    class Layers(val peaks: List<Placed>, val aircraft: List<Placed>)

    /**
     * T312: the peaks and the aircraft placed in two passes. The first is [place] on [peaks] alone, unchanged (it forces the
     * closest label and counts every bearing in its density gap, so mixing the kinds would reshuffle peak names at each
     * poll); the second places [aircraft] (ranked among themselves) with the peaks' boxes and marks added to [blocked], the
     * closest aircraft not forced, so no aircraft name covers a peak's; [firstAircraft] (a picked one) goes first.
     * [aircraftBlocked] is kept off by the second pass only (round 2: the compass letters and the north mark,
     * [RimGeometry.compassBoxes]; the ring labels are in [blocked], which both passes keep off).
     */
    fun placeLayers(
        g: LabelProjection, peaks: List<Scored>, aircraft: List<Scored>, width: (String) -> Double, ascent: Double, descent: Double,
        blocked: List<RimBox> = emptyList(), lines: List<List<RimPt>> = emptyList(), firstPeak: HorizonLabel? = null, firstAircraft: HorizonLabel? = null,
        aircraftBlocked: List<RimBox> = emptyList(),
    ): Layers {
        val p = place(g, peaks, width, ascent, descent, blocked, lines, firstPeak)
        if (aircraft.isEmpty()) return Layers(p, emptyList())
        val taken = blocked + aircraftBlocked + p.flatMap { listOf(it.box, it.mark) }
        val a = place(g, aircraft, width, ascent, descent, taken, lines + p.mapNotNull { it.leader }, firstAircraft, forceClosest = false)
        return Layers(p, a)
    }
}

/** A point on the sky plot (px). */
data class RimPt(val x: Double, val y: Double)

/** A box on the sky plot (px, y down). */
data class RimBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun overlaps(o: RimBox): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun contains(p: RimPt): Boolean = p.x in left..right && p.y in top..bottom

    /** Whether the line through [pts] touches this box (a vertex inside, or a segment crossing it). */
    fun crosses(pts: List<RimPt>): Boolean {
        if (pts.any(::contains)) return true
        for (i in 0 until pts.size - 1) if (segment(pts[i], pts[i + 1])) return true
        return false
    }

    private fun segment(a: RimPt, b: RimPt): Boolean {
        if (max(a.x, b.x) < left || min(a.x, b.x) > right || max(a.y, b.y) < top || min(a.y, b.y) > bottom) return false
        val corners = listOf(RimPt(left, top), RimPt(right, top), RimPt(right, bottom), RimPt(left, bottom))
        val sides = corners.map { (b.x - a.x) * (it.y - a.y) - (b.y - a.y) * (it.x - a.x) }
        return sides.any { it >= 0 } && sides.any { it <= 0 }
    }
}

/**
 * T309: the sky plot's geometry in px (the app's `PlotGeometry` draws with the same numbers): centre [cx], [cy], the rim's
 * [radius], turned by [rotationDeg], [dp] px per dp. [at] places an elevation (the rim 0, the centre 90; below 0 on the
 * rim) and a true azimuth. The ring labels ([ringLabel]) and the sun's hour labels ([hourLabel]) are where the plot
 * draws them, so names keep off them.
 */
class RimGeometry(val cx: Double, val cy: Double, val radius: Double, val rotationDeg: Double, override val dp: Double) : LabelProjection {
    /** T322: the rim as a [LabelProjection]: every mark is on the view ([at], clamped to the rim). */
    override fun mark(elDeg: Double, azDeg: Double): RimPt = at(elDeg, azDeg)

    /** T322: toward the centre (the zenith), with T309's guard at the centre itself. */
    override fun away(at: RimPt, elDeg: Double, azDeg: Double): RimPt {
        val dx = cx - at.x
        val dy = cy - at.y
        val len = max(hypot(dx, dy), 1.0)
        return RimPt(dx / len, dy / len)
    }

    /** T322: the bearing between the two the short way round ([HorizonLabels.apart]), as T309 held the gap. */
    override fun separation(a: HorizonLabel, b: HorizonLabel): Double = HorizonLabels.apart(a.bearingDeg, b.bearingDeg)

    fun at(elDeg: Double, azDeg: Double): RimPt {
        val r = radius * (90 - elDeg.coerceIn(0.0, 90.0)) / 90
        val a = (azDeg - rotationDeg) * PI / 180
        return RimPt(cx + r * sin(a), cy - r * cos(a))
    }

    /** The box of a ring's label ("0°", "30°", "60°" at [elDeg]): left-aligned 3 dp right of and 3 dp above the ring at 45° on screen. */
    fun ringLabel(elDeg: Double, width: Double, ascent: Double, descent: Double): RimBox {
        val p = at(elDeg, rotationDeg + 45)
        val x = p.x + 3 * dp
        val y = p.y - 3 * dp
        return RimBox(x, y + ascent, x + width, y + descent)
    }

    /** The box of a sun hour label: centred on its dot, the baseline 6 dp above it. */
    fun hourLabel(elDeg: Double, azDeg: Double, width: Double, ascent: Double, descent: Double): RimBox {
        val p = at(elDeg, azDeg)
        val y = p.y - 6 * dp
        return RimBox(p.x - width / 2, y + ascent, p.x + width / 2, y + descent)
    }

    /**
     * T312: the box of a compass letter ("N", "E", "S", "W" at [azDeg]) as the plot draws it: [COMPASS_DP] text centred
     * 0.85 of its size outside the rim, the baseline 0.36 of its size below that point; [width], [ascent] (negative) and
     * [descent] measured at that size.
     */
    fun compassLabel(azDeg: Double, width: Double, ascent: Double, descent: Double): RimBox {
        val size = COMPASS_DP * dp
        val a = (azDeg - rotationDeg) * PI / 180
        val r = radius + size * 0.85
        val x = cx + r * sin(a)
        val y = cy - r * cos(a) + size * 0.36
        return RimBox(x - width / 2, y + ascent, x + width / 2, y + descent)
    }

    /** T312: the north mark's box: its triangle from 9 dp inside the rim to 2 dp outside it, padded 6 dp. */
    fun northMark(): RimBox {
        val a = -rotationDeg * PI / 180
        val inner = RimPt(cx + (radius - 9 * dp) * sin(a), cy - (radius - 9 * dp) * cos(a))
        val tip = RimPt(cx + (radius + 2 * dp) * sin(a), cy - (radius + 2 * dp) * cos(a))
        val pad = 6 * dp
        return RimBox(min(inner.x, tip.x) - pad, min(inner.y, tip.y) - pad, max(inner.x, tip.x) + pad, max(inner.y, tip.y) + pad)
    }

    /**
     * T312: what flight names keep off besides what peak names keep off: the four compass letters (their [width] measured
     * at [COMPASS_DP], the bold N included) and the north mark. Only the second pass gets these, so the peaks' names stand
     * where T309 put them.
     */
    fun compassBoxes(width: (String) -> Double, ascent: Double, descent: Double): List<RimBox> =
        listOf(0.0 to "N", 90.0 to "E", 180.0 to "S", 270.0 to "W").map { (az, l) -> compassLabel(az, width(l), ascent, descent) } + northMark()

    companion object {
        /** T312: the compass letters' text size (dp), as `SkyPlot` draws them (`PlotGeometry.labelPx`). */
        const val COMPASS_DP = 15.0

        /** The plot [width] by [height] px at [dp] px per dp: the rim the square's half less room for N E S W (15 dp × 1.6). */
        fun of(width: Double, height: Double, dp: Double, rotationDeg: Double): RimGeometry =
            RimGeometry(width / 2, height / 2, min(width, height) / 2 - 15 * dp * 1.6, rotationDeg, dp)
    }
}
