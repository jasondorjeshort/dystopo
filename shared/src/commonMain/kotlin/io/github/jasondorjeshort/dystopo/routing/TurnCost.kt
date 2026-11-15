package io.github.jasondorjeshort.dystopo.routing

import kotlin.math.abs

/*
 * T281 (ADR 0062): what a drive pays for a turn, computed on the fly from the graph's geometry (no table per junction,
 * no change to the graph file). The drive search ([DriveSearch]) and the overlay's cell build and unpack
 * ([DriveOverlay]) ask it at every junction they pass, for the edge they arrived by and the edge they leave on.
 *
 * The geometry is fixed here: each way's bearing over the first [Directions.BEARING_M] of its line
 * ([RoutingGraph.endBearing], a lazily built cache), the signed turn angle (positive to the right), the count of
 * distinct drivable roads at the junction across every piece that has a node there (twins included, parallel copies of
 * one road where packs overlap counted once: [Junction.roads]), whether the outgoing way is the incoming one taken back
 * (a reversal, read from geometry, never from edge ids) and whether the turn crosses oncoming traffic for the
 * [DrivingSide] (T283: [DrivingSides], in `DrivingSide.kt`). What a turn costs is the [TurnTable]'s: [TurnTable.DRIVE]
 * since T282 ([DriveTurns], ADR 0062 Decision 4).
 */

/** One drivable way at a junction: edge [edge] of [piece] leaving the junction from its from node ([atFrom]) or its to node. */
class JunctionRoad(val piece: GraphPiece, val edge: Int, val atFrom: Boolean) {
    private val g get() = piece.graph

    /** The bearing it leaves the junction at, degrees from north clockwise ([RoutingGraph.endBearing]). */
    val bearingDeg: Double get() = g.endBearing[2 * edge + if (atFrom) 0 else 1].toDouble()
    val wayClass: WayClass get() = g.wayClass(edge)
    val name: String? get() = g.name(edge)
    val surface: Surface get() = g.surface(edge)
    val level: Int get() = g.nodeLevel[if (atFrom) g.edgeFrom[edge] else g.edgeTo[edge]].toInt()

    /** T282: whether a car may leave the junction along it (a one-way pointing in may not). */
    val canLeave: Boolean get() = DRIVE.allows(g, edge, atFrom)

    /**
     * Whether [other] is this same road: the same class, name and level leaving within [TurnCost.SAME_ROAD_DEG] of the
     * same bearing (a copy of the way in an overlapping pack, or the way itself).
     */
    fun sameRoad(other: JunctionRoad): Boolean =
        wayClass == other.wayClass && name == other.name && level == other.level && TurnCost.angleBetween(bearingDeg, other.bearingDeg) <= TurnCost.SAME_ROAD_DEG

    private companion object {
        val DRIVE = CostModel(TravelMode.DRIVE)
    }
}

/**
 * The node a turn is made at, as every piece that has it: [pieces][i] has it as node [nodes][i] (one entry inside a cell
 * build; the base piece and its twins in neighbouring tiles and packs in a search). Overlay pieces add no roads (their
 * edges are shortcuts).
 */
class Junction(val pieces: List<GraphPiece>, val nodes: IntArray) {
    /** The distinct drivable roads at the junction, in either direction of travel ([JunctionRoad.sameRoad] counted once). */
    val roads: List<JunctionRoad> by lazy {
        val out = ArrayList<JunctionRoad>()
        for (i in pieces.indices) {
            val p = pieces[i]
            if (p.overlay != null || !p.graph.source.drive) continue
            val g = p.graph
            val n = nodes[i]
            for (k in g.adjStart[n] until g.adjStart[n + 1]) {
                val a = g.adjEdge[k]
                val e = a shr 1
                if (!DRIVE.usable(g, e)) continue
                val r = JunctionRoad(p, e, a and 1 == 0)
                if (out.none { it.sameRoad(r) }) out += r
            }
        }
        out
    }

    private companion object {
        val DRIVE = CostModel(TravelMode.DRIVE)
    }
}

/** One turn as a [TurnTable] reads it: from [inRoad] (seen leaving the junction, so the way back) onto [outRoad]. */
class Turn(
    /** The way arrived by, as a road leaving the junction (its bearing points back the way the car came). */
    val inRoad: JunctionRoad,
    /** The way left on. */
    val outRoad: JunctionRoad,
    val junction: Junction,
    val side: DrivingSide,
) {
    /** The heading the car arrives with, degrees from north clockwise. */
    val inHeadingDeg: Double get() = TurnCost.norm360(inRoad.bearingDeg + 180.0)

    /** The turn, degrees in (-180, 180]: 0 straight on, positive to the right, negative to the left. */
    val angleDeg: Double get() = TurnCost.signed(outRoad.bearingDeg - inHeadingDeg)

    /** The way arrived by taken back (a U-turn), read from geometry: [JunctionRoad.sameRoad]. */
    val reversal: Boolean get() = inRoad.sameRoad(outRoad)

    /** Whether the turn crosses oncoming traffic: a left turn where traffic keeps right, a right turn where it keeps left. */
    val acrossTraffic: Boolean get() = if (side == DrivingSide.RIGHT) angleDeg < 0 else angleDeg > 0

    /** The distinct drivable roads at the junction ([Junction.roads]). */
    val roads: Int get() = junction.roads.size

    /**
     * T282 (ADR 0062 Decision 4): whether [outRoad] is the through exit: the one road a car may leave on, other than the
     * way back, that has [inRoad]'s name and class (its class when both are unnamed), when exactly one road does. A road
     * that bends where a spur or a lot goes straight on stays the road (T108's hairpin lot).
     */
    val throughExit: Boolean get() {
        var match: JunctionRoad? = null
        for (r in junction.roads) {
            if (r.sameRoad(inRoad) || !r.canLeave || r.wayClass != inRoad.wayClass || r.name != inRoad.name) continue
            if (match != null) return false
            match = r
        }
        return match != null && match.sameRoad(outRoad)
    }
}

/**
 * What a [Turn] costs, in seconds; the result is clamped at zero (never negative: the search's potentials need it) and
 * may be infinite (a forbidden turn; T281 forbids none).
 */
fun interface TurnTable {
    fun seconds(turn: Turn): Double

    companion object {
        /** Every turn free: the drive search then costs what the node search costs. */
        val ZERO = TurnTable { 0.0 }

        /** The table drives use: [DriveTurns] (T282, ADR 0062 Decision 4). */
        val DRIVE: TurnTable = DriveTurns
    }
}

/**
 * T282 (ADR 0062 Decision 4): the seconds a car loses at a turn, the first values from the survey of 2026-11-14
 * (one open router's angle curve and its U-turn charge). In order:
 * - a reversal (the way arrived by taken back, [Turn.reversal]): [REVERSAL_S], never refused, also at a dead end;
 * - a node of two or fewer distinct drivable roads (a bend, a tile cut): nothing;
 * - the through exit ([Turn.throughExit]): nothing, however sharp the bend;
 * - under [FREE_DEG] of turn: nothing;
 * - else by the angle, rising linearly from nothing at [FREE_DEG]: kerb-side to [KERB_90_S] at 90 degrees and
 *   [KERB_150_S] at 150 and over, across traffic ([Turn.acrossTraffic]) to [ACROSS_90_S] and [ACROSS_150_S]; and on
 *   such a priced turn [CLASS_STEP_S] per class step down ([WayClass] order, at most [CLASS_STEPS_MAX] steps) plus
 *   [ROUGH_S] onto `service`, `track` or an unpaved road from a paved road of another kind.
 * Joining a higher class costs nothing extra (no survey supports it). Roundabouts and links are not marked in the graph
 * and are priced by their angles alone. The numbers here are the record after T282's tuning (its report).
 */
object DriveTurns : TurnTable {
    const val REVERSAL_S = 25.0
    const val FREE_DEG = 30.0
    const val KERB_90_S = 3.0
    const val KERB_150_S = 6.0
    const val ACROSS_90_S = 7.0
    const val ACROSS_150_S = 9.0
    const val CLASS_STEP_S = 1.0
    const val CLASS_STEPS_MAX = 3
    const val ROUGH_S = 2.0

    override fun seconds(turn: Turn): Double {
        if (turn.reversal) return REVERSAL_S
        if (turn.roads <= 2 || turn.throughExit) return 0.0
        val a = abs(turn.angleDeg)
        if (a < FREE_DEG) return 0.0
        return angleSeconds(a, turn.acrossTraffic) + classSeconds(turn.inRoad, turn.outRoad)
    }

    /** The angle's part: nothing at [FREE_DEG], linear to the 90 degree value, then to the 150 degree value, flat after. */
    fun angleSeconds(angleDeg: Double, across: Boolean): Double {
        val at90 = if (across) ACROSS_90_S else KERB_90_S
        val at150 = if (across) ACROSS_150_S else KERB_150_S
        return when {
            angleDeg < FREE_DEG -> 0.0
            angleDeg <= 90.0 -> at90 * (angleDeg - FREE_DEG) / (90.0 - FREE_DEG)
            angleDeg < 150.0 -> at90 + (at150 - at90) * (angleDeg - 90.0) / 60.0
            else -> at150
        }
    }

    /** The class part of a priced turn from [from] onto [to]: steps down, and onto a rough road from a paved one. */
    fun classSeconds(from: JunctionRoad, to: JunctionRoad): Double {
        val steps = (from.wayClass.code - to.wayClass.code).coerceIn(0, CLASS_STEPS_MAX)
        return steps * CLASS_STEP_S + if (rough(to) && !rough(from)) ROUGH_S else 0.0
    }

    /** `service`, `track` or unpaved (a road of unknown surface counts as paved, as its drive time does). */
    private fun rough(r: JunctionRoad): Boolean = r.wayClass == WayClass.SERVICE || r.wayClass == WayClass.TRACK || r.surface == Surface.UNPAVED
}

/**
 * The turn function: [seconds] of leaving a junction along one edge after arriving by another, and [cost], those seconds
 * as search cost through [model]'s [CostModel.secondsCost] (today drive metres at [CostModel.DRIVE_REF_KMH], like an
 * edge's time). [side] is the driving side; T283: null (the default) means "the side where it is used", which
 * [at] fixes from [DrivingSides] (once per search at the route's start, once per overlay cell at the cell's centre);
 * a TurnCost used without [at] reads null as right.
 */
class TurnCost(
    val table: TurnTable = TurnTable.DRIVE,
    val side: DrivingSide? = null,
    val model: CostModel = CostModel(TravelMode.DRIVE),
) {
    /** True when every turn is free ([TurnTable.ZERO]): the searches then skip the junction geometry altogether. */
    val free: Boolean get() = table === TurnTable.ZERO

    /**
     * Seconds for arriving at [junction] along edge [inEdge] of [inPiece] (travelled from its from node to its to node
     * when [inForward]) and leaving along edge [outEdge] of [outPiece] ([outForward]: from its from node). Both pieces
     * must be among the junction's.
     */
    fun seconds(inPiece: GraphPiece, inEdge: Int, inForward: Boolean, junction: Junction, outPiece: GraphPiece, outEdge: Int, outForward: Boolean): Double {
        if (free) return 0.0
        // The way in, seen from the junction: it leaves the junction from its head end.
        val turn = Turn(JunctionRoad(inPiece, inEdge, atFrom = !inForward), JunctionRoad(outPiece, outEdge, atFrom = outForward), junction, side ?: DrivingSide.RIGHT)
        val s = table.seconds(turn)
        return if (s > 0) s else 0.0 // NaN and negatives read as free
    }

    /** [seconds] as search cost ([CostModel.secondsCost]). */
    fun cost(inPiece: GraphPiece, inEdge: Int, inForward: Boolean, junction: Junction, outPiece: GraphPiece, outEdge: Int, outForward: Boolean): Double =
        if (free) 0.0 else model.secondsCost(seconds(inPiece, inEdge, inForward, junction, outPiece, outEdge, outForward))

    companion object {
        /** Two ways of one class, name and level leaving a junction within this many degrees are one road. */
        const val SAME_ROAD_DEG = 5.0

        fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

        /** [deg] as (-180, 180]. */
        fun signed(deg: Double): Double {
            val d = norm360(deg)
            return if (d > 180.0) d - 360.0 else d
        }

        /** The angle between two bearings, 0 to 180. */
        fun angleBetween(a: Double, b: Double): Double = abs(signed(a - b))
    }
}
