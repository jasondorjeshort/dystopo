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
 * **Score** ([score]): `S = 3.0 Dn + 2.5 Hn + 1.5 An + 1.0 Pn + 0.5 En`, each term 0 to 1: `Dn = 1 / (1 + d / 5 km)` (distance
 * weighs most), `Hn` the clearance over 1.5° (0 out of sight), `An` the apparent angle over 6°, `Pn` the prominence on a log
 * scale to 1000 m, `En` the elevation within the candidates' range; +10 for the dominant peak, ×0.85 out of sight, × the
 * kind's weight. Ties: nearer, then by name.
 *
 * **Placement** ([place]): highest score first, each label at the first of [STEPS] places inward from its mark where its
 * box touches no label placed before, no ring label, no hour label and not the sun's arc, and only where no label already
 * placed stands within the density gap in bearing: `4° × sqrt(nLocal / 5)`, at least 2°, nLocal the candidates within ±15°
 * (a crowded stretch of the rim gets sparser names, an empty one takes close neighbours). No fixed cap per sector.
 */
object HorizonLabels {
    // The score's weights (first guesses).
    const val W_DISTANCE = 3.0
    const val W_HEIGHT = 2.5
    const val W_ANGLE = 1.5
    const val W_PROMINENCE = 1.0
    const val W_ELEVATION = 0.5
    const val DOMINANT_BONUS = 10.0
    const val OUT_OF_SIGHT_FACTOR = 0.85

    // The terms' scales.
    const val DISTANCE_SCALE_M = 5000.0
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
    const val STEPS = 6
    const val FIRST_STEP_DP = 16.0
    const val STEP_DP = 15.0

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
        val hn = clamp01(max(l.clearanceDeg, 0.0) / HEIGHT_FULL_DEG)
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

    /** A label placed: its [scored] entry, the text's baseline centre ([x], [y], px), its box, and the gap it was held to. */
    class Placed(val scored: Scored, val x: Double, val y: Double, val box: RimBox, val gapDeg: Double) {
        val label: HorizonLabel get() = scored.label
    }

    /**
     * [ranked] placed on [g], highest first: each inward from its mark ([RimGeometry.at] of its angle and bearing) along the
     * radius at the first of [STEPS] places where its box (the text [width] of its name, [ascent] above the baseline
     * (negative) and [descent] below, padded 2 dp) touches no label placed before, none of [blocked] (the ring and hour
     * labels) and no segment of [lines] (the sun's arc), and only when no label placed stands within its density gap in
     * bearing. The dominant label is placed whatever: at its first free place, else at the first. The rest without a free
     * place are left out (the list has them). [first] goes before everything (a picked label).
     */
    fun place(
        g: RimGeometry, ranked: List<Scored>, width: (String) -> Double, ascent: Double, descent: Double,
        blocked: List<RimBox> = emptyList(), lines: List<List<RimPt>> = emptyList(), first: HorizonLabel? = null,
    ): List<Placed> {
        val placed = ArrayList<Placed>()
        val pad = 2 * g.dp
        val bearings = ranked.map { it.label.bearingDeg }
        val order = ranked.sortedByDescending { it.label == first } // stable: the rank kept otherwise
        for (s in order) {
            val l = s.label
            val nLocal = bearings.count { apart(it, l.bearingDeg) <= LOCAL_DEG }
            val gap = gapDeg(nLocal)
            val forced = l.dominant || l == first
            if (!forced && placed.any { apart(it.label.bearingDeg, l.bearingDeg) < gap }) continue
            val at = g.at(l.angleDeg, l.bearingDeg)
            val dx = g.cx - at.x
            val dy = g.cy - at.y
            val len = max(hypot(dx, dy), 1.0)
            val w = width(l.name)
            var chosen: Placed? = null
            for (step in 0 until STEPS) {
                val inward = (FIRST_STEP_DP + STEP_DP * step) * g.dp
                val x = at.x + dx / len * inward
                val y = at.y + dy / len * inward + 4 * g.dp
                val box = RimBox(x - w / 2 - pad, y + ascent - pad, x + w / 2 + pad, y + descent + pad)
                val p = Placed(s, x, y, box, gap)
                if (step == 0 && forced) chosen = p
                if (placed.none { it.box.overlaps(box) } && blocked.none { it.overlaps(box) } && lines.none { box.crosses(it) }) {
                    chosen = p
                    break
                }
            }
            if (chosen != null && (forced || placed.none { it.box.overlaps(chosen.box) })) placed += chosen
        }
        return placed
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
class RimGeometry(val cx: Double, val cy: Double, val radius: Double, val rotationDeg: Double, val dp: Double) {
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

    companion object {
        /** The plot [width] by [height] px at [dp] px per dp: the rim the square's half less room for N E S W (15 dp × 1.6). */
        fun of(width: Double, height: Double, dp: Double, rotationDeg: Double): RimGeometry =
            RimGeometry(width / 2, height / 2, min(width, height) / 2 - 15 * dp * 1.6, rotationDeg, dp)
    }
}
