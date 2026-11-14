package io.github.jasondorjeshort.dystopo.routing

import kotlin.math.abs

/**
 * T281 (ADR 0062): what a drive pays for a turn, computed on the fly from the graph's geometry (no table per junction,
 * no change to the graph file). The drive search ([DriveSearch]) and the overlay's cell build and unpack
 * ([DriveOverlay]) ask it at every junction they pass, for the edge they arrived by and the edge they leave on.
 *
 * The geometry is fixed here: each way's bearing over the first [Directions.BEARING_M] of its line
 * ([RoutingGraph.endBearing], a lazily built cache), the signed turn angle (positive to the right), the count of
 * distinct drivable roads at the junction across every piece that has a node there (twins included, parallel copies of
 * one road where packs overlap counted once: [Junction.roads]), whether the outgoing way is the incoming one taken back
 * (a reversal, read from geometry, never from edge ids) and whether the turn crosses oncoming traffic for the
 * [DrivingSide]. What a turn costs is the [TurnTable]'s: all zero until T282 fills [TurnTable.DRIVE].
 */
enum class DrivingSide {
    /** Traffic keeps right: a left turn crosses it. The default where the side is not known. */
    RIGHT,
    /** Traffic keeps left: a right turn crosses it. */
    LEFT,
}

/** One drivable way at a junction: edge [edge] of [piece] leaving the junction from its from node ([atFrom]) or its to node. */
class JunctionRoad(val piece: GraphPiece, val edge: Int, val atFrom: Boolean) {
    private val g get() = piece.graph

    /** The bearing it leaves the junction at, degrees from north clockwise ([RoutingGraph.endBearing]). */
    val bearingDeg: Double get() = g.endBearing[2 * edge + if (atFrom) 0 else 1].toDouble()
    val wayClass: WayClass get() = g.wayClass(edge)
    val name: String? get() = g.name(edge)
    val surface: Surface get() = g.surface(edge)
    val level: Int get() = g.nodeLevel[if (atFrom) g.edgeFrom[edge] else g.edgeTo[edge]].toInt()

    /**
     * Whether [other] is this same road: the same class, name and level leaving within [TurnCost.SAME_ROAD_DEG] of the
     * same bearing (a copy of the way in an overlapping pack, or the way itself).
     */
    fun sameRoad(other: JunctionRoad): Boolean =
        wayClass == other.wayClass && name == other.name && level == other.level && TurnCost.angleBetween(bearingDeg, other.bearingDeg) <= TurnCost.SAME_ROAD_DEG
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

        /** The table drives use. T281: all zero (no behaviour change); T282 fills it. */
        val DRIVE: TurnTable = ZERO
    }
}

/**
 * The turn function: [seconds] of leaving a junction along one edge after arriving by another, and [cost], those seconds
 * as search cost through [model]'s [CostModel.secondsCost] (today drive metres at [CostModel.DRIVE_REF_KMH], like an
 * edge's time). [side] is the driving side (T283 looks it up per search; right until then).
 */
class TurnCost(
    val table: TurnTable = TurnTable.DRIVE,
    val side: DrivingSide = DrivingSide.RIGHT,
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
        val turn = Turn(JunctionRoad(inPiece, inEdge, atFrom = !inForward), JunctionRoad(outPiece, outEdge, atFrom = outForward), junction, side)
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
