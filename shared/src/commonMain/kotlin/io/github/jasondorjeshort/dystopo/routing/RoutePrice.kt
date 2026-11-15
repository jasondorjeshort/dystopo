package io.github.jasondorjeshort.dystopo.routing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/*
 * T290: a drive's cost, edge by edge and turn by turn: the table the drive search sums, so "why this road and not that
 * one" has an answer in JSON (devtool `route --breakdown`, `--against`, `--price`; the phone's `GET /route?breakdown=1`).
 * The rows are made by the search's own functions where it adds them ([DriveSearch.price]: `CostModel.partCost`,
 * `edgeTimeS`, `fuelJ`, the turn function's seconds and [CostModel.turnCost]); nothing here prices anything.
 */

/**
 * One stretch of a priced route, in travel order: a base edge ([kind] `edge`, from fraction [f0] to [f1] of edge [edge]),
 * or a whole overlay shortcut ([kind] `shortcut`: only its stored cost exists, ADR 0062's residual; its time and fuel are
 * the sums of the base edges it stands for and of the turns its cell prices inside it). [firstPoint] to [lastPoint] are
 * the indices of the route's points it covers (the first is the point it shares with the stretch before; T291 reads
 * them). [seconds] and [fuelJ] of a shortcut include its inner turns ([turnS], [turnFuelJ]), which are null when the
 * preference counts no turns.
 */
class PricePart(
    val kind: String,
    val wayClass: WayClass?,
    val name: String?,
    val edge: Int,
    val f0: Double,
    val f1: Double,
    val unpaved: Boolean,
    /** 0, [WayAttrs.BRIDGE] or [WayAttrs.TUNNEL]. */
    val brunnel: Int,
    val lengthM: Double,
    /** The speed the drive assumes on the edge ([CostModel.speedKmh]); null for a shortcut. */
    val speedKmh: Double?,
    val seconds: Double,
    val cost: Double,
    val fuelJ: Double,
    val ascentM: Double,
    val descentM: Double,
    val firstPoint: Int,
    val lastPoint: Int,
    /** A shortcut's base edges and the turns its cell priced inside it (null for an edge, or when no turns count). */
    val baseEdges: Int? = null,
    val turns: Int? = null,
    val turnS: Double? = null,
    val turnFuelJ: Double? = null,
) {
    fun json(index: Int): Map<String, Any?> = linkedMapOf<String, Any?>(
        "i" to index, "kind" to kind, "class" to wayClass?.label, "name" to name,
        "lengthM" to RoutePrice.r1(lengthM), "speedKmh" to speedKmh?.let(RoutePrice::r1), "seconds" to RoutePrice.r2(seconds),
        "cost" to RoutePrice.r3(cost), "fuelJ" to fuelJ.roundToLong(), "ascentM" to RoutePrice.r1(ascentM), "descentM" to RoutePrice.r1(descentM),
        "brunnel" to when (brunnel) { WayAttrs.BRIDGE -> "bridge"; WayAttrs.TUNNEL -> "tunnel"; else -> null },
        "unpaved" to unpaved, "points" to listOf(firstPoint, lastPoint),
    ).also { m ->
        if (kind == "edge") { m["edge"] = edge; m["from"] = RoutePrice.r6(f0); m["to"] = RoutePrice.r6(f1) }
        else { m.remove("class"); m.remove("name"); m.remove("speedKmh"); m.remove("brunnel"); m.remove("unpaved"); m["baseEdges"] = baseEdges; m["turns"] = turns; m["turnS"] = turnS?.let(RoutePrice::r2) }
    }
}

/**
 * What lies between stretch [afterPart] and the next one: a turn at a junction ([kind] `turn`: every junction of more than
 * two drivable roads, every reversal and every turn that costs seconds; a bend of one road is left out), or a step across
 * an overlay cell's border ([kind] `border`), which the search does not price (ADR 0062's residual). [unnamed]: both the
 * road arrived by and the road left on have no name, so the through exit is told by class alone (a pack without road
 * names, T282's note).
 */
class PriceJoin(
    val kind: String,
    val afterPart: Int,
    val lat: Double,
    val lon: Double,
    val turn: Turn? = null,
    val seconds: Double = 0.0,
    val cost: Double = 0.0,
    val fuelJ: Double = 0.0,
) {
    val paid: Boolean get() = seconds > 0
    val unnamed: Boolean get() = turn != null && turn.inRoad.name == null && turn.outRoad.name == null

    fun json(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("after" to afterPart, "kind" to kind, "lat" to RoutePrice.r6(lat), "lon" to RoutePrice.r6(lon))
        val t = turn ?: return m.also { it["priced"] = false }
        m["angleDeg"] = RoutePrice.r1(t.angleDeg)
        m["acrossTraffic"] = t.acrossTraffic
        m["reversal"] = t.reversal
        m["roads"] = t.roads
        m["throughExit"] = t.throughExit
        m["fromClass"] = t.inRoad.wayClass.label
        m["toClass"] = t.outRoad.wayClass.label
        m["classStep"] = t.inRoad.wayClass.code - t.outRoad.wayClass.code // positive: down to a lesser class
        m["fromName"] = t.inRoad.name
        m["toName"] = t.outRoad.name
        m["unnamed"] = unnamed
        m["seconds"] = RoutePrice.r2(seconds)
        m["cost"] = RoutePrice.r3(cost)
        m["fuelJ"] = fuelJ.roundToLong()
        return m
    }
}

/**
 * T290: a drive priced under [preference]: its stretches ([parts]) and what lies between them ([joins]), with the sums.
 * Under the route's own preference the sums are the plan's own `timeS`, `turnCostS`, `cost` and `fuelIndex` (to rounding),
 * shortcuts included; [TileRouter.price] also prices a route under another preference (its base edges in full).
 */
class RoutePrice(
    val preference: DrivePreference,
    val drivingSide: DrivingSide,
    val parts: List<PricePart>,
    val joins: List<PriceJoin>,
    /** The route's points the parts were laid on (the plan's own count when the steps are the plan's). */
    val points: Int,
) {
    val metric: DriveMetric get() = preference.metric
    val lengthM: Double get() = parts.sumOf { it.lengthM }
    /** Edge seconds only (a shortcut's inner turns left out). */
    val edgeS: Double get() = parts.sumOf { it.seconds - (it.turnS ?: 0.0) }
    /** Every turn's seconds: the turn rows' and the shortcuts' inner ones. */
    val turnS: Double get() = joins.sumOf { it.seconds } + parts.sumOf { it.turnS ?: 0.0 }
    val timeS: Double get() = parts.sumOf { it.seconds } + joins.sumOf { it.seconds }
    val cost: Double get() = parts.sumOf { it.cost } + joins.sumOf { it.cost }
    val fuelJ: Double get() = parts.sumOf { it.fuelJ } + joins.sumOf { it.fuelJ }
    val turnFuelJ: Double get() = joins.sumOf { it.fuelJ } + parts.sumOf { it.turnFuelJ ?: 0.0 }
    /** The turns paid for (more than zero seconds), as the plan's `turns` counts them; null when the preference counts none. */
    val turns: Int? get() = if (!preference.turns) null else joins.count { it.paid } + parts.sumOf { it.turns ?: 0 }
    val paidRows: List<PriceJoin> get() = joins.filter { it.paid }

    fun totalsJson(): Map<String, Any?> {
        val paid = paidRows
        val unnamed = paid.filter { it.unnamed }
        val time = timeS
        val fuel = fuelJ
        val turnFuel = turnFuelJ
        val rowFuel = paid.sumOf { it.fuelJ }
        return linkedMapOf(
            "lengthM" to r1(lengthM), "timeS" to r2(time), "edgeS" to r2(edgeS), "turnS" to r2(turnS),
            "cost" to r3(cost), "fuelJ" to fuel.roundToLong(), "edgeFuelJ" to (fuel - turnFuel).roundToLong(), "turnFuelJ" to turnFuel.roundToLong(),
            "ascentM" to r1(parts.sumOf { it.ascentM }), "descentM" to r1(parts.sumOf { it.descentM }),
            "turns" to turns,
            // The share of the time and of the fuel that is turns.
            "turnTimeShare" to share(turnS, time), "turnFuelShare" to share(turnFuel, fuel),
            // The paid turns with a row (a shortcut's inner turns have none), and those at junctions of unnamed roads.
            "turnRows" to paid.size, "turnsInShortcuts" to parts.sumOf { it.turns ?: 0 },
            "unnamedTurns" to unnamed.size, "unnamedTurnShare" to share(unnamed.size.toDouble(), paid.size.toDouble()),
            "unnamedTurnS" to r2(unnamed.sumOf { it.seconds }), "unnamedTurnFuelJ" to unnamed.sumOf { it.fuelJ }.roundToLong(),
            "unnamedTurnFuelShare" to share(unnamed.sumOf { it.fuelJ }, rowFuel),
            "acrossTraffic" to paid.count { it.turn?.acrossTraffic == true }, "reversals" to paid.count { it.turn?.reversal == true },
            "edges" to parts.count { it.kind == "edge" && it.lengthM > 0 }, "shortcuts" to parts.count { it.kind == "shortcut" },
            "borders" to joins.count { it.kind == "border" }, "points" to points,
        )
    }

    /** The table: the preference, the metric and the side, the totals, then the rows unless [rows] is false. */
    fun json(rows: Boolean = true): Map<String, Any?> = linkedMapOf<String, Any?>(
        "prefer" to preference.key, "metric" to metric.key, "drivingSide" to drivingSide.key, "totals" to totalsJson(),
    ).also { m ->
        if (rows) {
            m["edges"] = parts.mapIndexed { i, p -> p.json(i) }
            m["turns"] = joins.map { it.json() }
        }
    }

    companion object {
        fun r1(v: Double) = (v * 10).roundToLong() / 10.0
        fun r2(v: Double) = (v * 100).roundToLong() / 100.0
        fun r3(v: Double) = (v * 1000).roundToLong() / 1000.0
        fun r6(v: Double) = (v * 1e6).roundToLong() / 1e6
        private fun share(part: Double, whole: Double): Double? = if (whole > 0) (part / whole * 10000).roundToLong() / 10000.0 else null

        /**
         * [b]'s totals less [a]'s (`b - a`), for two routes priced under one preference (devtool `--against`): metres,
         * seconds, turn seconds, cost, fuel and turns, and the fuel [a] saves against [b] in percent (negative when it
         * uses more).
         */
        fun difference(a: RoutePrice, b: RoutePrice): Map<String, Any?> = linkedMapOf(
            "lengthM" to r1(b.lengthM - a.lengthM), "timeS" to r2(b.timeS - a.timeS), "turnS" to r2(b.turnS - a.turnS),
            "cost" to r3(b.cost - a.cost), "fuelJ" to (b.fuelJ - a.fuelJ).roundToLong(),
            "turns" to if (a.turns == null || b.turns == null) null else b.turns!! - a.turns!!,
            "fuelSavedPercent" to if (b.fuelJ > 0) r2(100 * (1 - a.fuelJ / b.fuelJ)) else null,
        )

        /**
         * Why [plan] has no table (the phone's `breakdown` `unavailable`): no steps were kept. Null when it has them.
         */
        fun unavailableReason(plan: PlannedRoute): String? = when {
            plan.trace != null -> null
            plan.mode != TravelMode.DRIVE -> "not a drive: only a drive is priced"
            plan.memory["restored"] == true -> "restored from the session file: its steps were not kept"
            plan.memory["remote"] != null -> "planned by a server: no steps exist on the phone"
            else -> "planned without keeping its steps (only a debug build keeps them)"
        }
    }
}

/** T290: [TileRouter.priceLine]'s answer: the table of a line lying on the graph's own geometry, or why it is refused. */
sealed class LinePrice {
    class Priced(val price: RoutePrice, val steps: Int) : LinePrice()

    /** The line's point [point] (from 0) is the first that does not lie on the network's geometry. */
    class Refused(val message: String, val point: Int, val lat: Double?, val lon: Double?) : LinePrice() {
        fun json(): Map<String, Any?> = linkedMapOf(
            "error" to "off_geometry", "message" to message, "point" to point, "lat" to lat?.let(RoutePrice::r6), "lon" to lon?.let(RoutePrice::r6),
        )
    }
}

/**
 * T290: what a planner keeps of a drive so it can be priced later ([TileRouter.keepTrace]; never by default, so a plan
 * that does not ask pays nothing): the search's steps before an overlay's shortcuts are unpacked (zero-length end steps
 * kept: the turn off or onto them was priced), the two snapped ends, the end areas when it ran through the overlay, how it
 * was planned and the driving side its turns were priced for.
 */
class RouteTrace internal constructor(
    val router: TileRouter,
    internal val steps: List<TileRouter.Step>,
    internal val s: TileRouter.End,
    internal val t: TileRouter.End,
    /** The end areas the search kept in full, when it ran through the overlay; null for a search in full. */
    internal val detail: Set<Long>?,
    val preference: DrivePreference,
    val side: DrivingSide,
) {
    override fun toString(): String = "RouteTrace(${steps.size} steps, ${preference.key})"
}

/**
 * T290: builds a [RoutePrice] row by row as [DriveSearch.price] hands it what the search adds, and lays the rows on the
 * route's points as [TileRouter.plan] makes them (each step's points in travel order, a point equal to the one before
 * dropped), so each row knows the points it covers.
 */
internal class RoutePriceBuilder(
    private val router: TileRouter,
    private val model: CostModel,
    private val preference: DrivePreference,
    private val side: DrivingSide,
) {
    private val parts = ArrayList<PricePart>()
    private val joins = ArrayList<PriceJoin>()
    private var count = 0
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    private fun lay(g: RoutingGraph, e: Int, f0: Double, f1: Double) {
        if (f0 == f1) return // the plan leaves zero-length steps out
        for (p in router.subPoints(g, e, f0, f1)) {
            if (p.lat == lastLat && p.lon == lastLon) continue
            count++
            lastLat = p.lat
            lastLon = p.lon
        }
    }

    private fun first() = if (count == 0) 0 else count - 1

    /** Edge [e] of [g] from [f0] to [f1] at the search's own [cost] for it. */
    fun edge(g: RoutingGraph, e: Int, f0: Double, f1: Double, cost: Double) {
        val a = first()
        lay(g, e, f0, f1)
        val metres = g.edgeLength[e] * abs(f1 - f0) // as TileRouter.plan measures it
        parts += PricePart(
            "edge", g.wayClass(e), g.name(e), e, f0, f1, g.surface(e) == Surface.UNPAVED, g.brunnel(e), metres,
            model.speedKmh(g, e), model.edgeTimeS(g, e, metres), cost, model.fuelJ(g, e, f0, f1),
            model.climbM(g, e, f0, f1), model.dropM(g, e, f0, f1), a, max(a, count - 1),
        )
    }

    /**
     * A shortcut at its stored [cost], standing for the base edges [expansion] (piece, edge, forward), with the turns its
     * cell priced inside it ([inner]; null when the preference counts no turns).
     */
    fun shortcut(cost: Double, expansion: List<Triple<GraphPiece, Int, Boolean>>, inner: TurnSum?) {
        val a = first()
        var metres = 0.0
        var seconds = 0.0
        var fuel = 0.0
        var up = 0.0
        var down = 0.0
        for ((p, e, fwd) in expansion) {
            val g = p.graph
            val f0 = if (fwd) 0.0 else 1.0
            val f1 = 1.0 - f0
            lay(g, e, f0, f1)
            val m = g.edgeLength[e] * 1.0
            metres += m
            seconds += model.edgeTimeS(g, e, m)
            fuel += model.fuelJ(g, e, f0, f1)
            up += model.climbM(g, e, f0, f1)
            down += model.dropM(g, e, f0, f1)
        }
        parts += PricePart(
            "shortcut", null, null, -1, 0.0, 1.0, false, 0, metres, null,
            seconds + (inner?.seconds ?: 0.0), cost, fuel + (inner?.fuelJ ?: 0.0), up, down, a, max(a, count - 1),
            expansion.size, inner?.count, inner?.seconds, inner?.fuelJ,
        )
    }

    fun turn(turn: Turn, lat: Double, lon: Double, seconds: Double, cost: Double, fuelJ: Double) {
        joins += PriceJoin("turn", parts.size - 1, lat, lon, turn, seconds, cost, fuelJ)
    }

    fun border(lat: Double, lon: Double) {
        joins += PriceJoin("border", parts.size - 1, lat, lon)
    }

    fun build(): RoutePrice = RoutePrice(preference, side, parts, joins, count)
}
