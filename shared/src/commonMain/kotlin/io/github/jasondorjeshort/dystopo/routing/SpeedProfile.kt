package io.github.jasondorjeshort.dystopo.routing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/*
 * T291: a drive as a speed profile, pass one (the idea `docs/ideas/2026-11-15-a-drive-is-a-speed-profile.md`). A finished
 * route, priced by T290's table ([RoutePrice]) and laid on the route's points, gets a speed limit at every point of a
 * grid of about [ProfileLimits.STEP_M]: the edge's drive speed ([CostModel.driveMps]), the bend's radius under a lateral
 * limit, and each priced turn's speed by its angle. One forward pass under an acceleration limit and one backward pass
 * under a braking limit give the speed a car can hold; time is the integral and fuel is [FuelModel]'s idle and traction
 * terms over the same profile. No search reads it (the survey of 2026-11-15: no published router carries speed in its
 * search); devtool `route --profile` prints it beside the table model's figures, which do not change.
 */

/**
 * The turn speeds of a priced turn, km/h, by its angle ([of]): none under [freeDeg]; [at45Kmh] at 45 degrees; at 90
 * degrees [kerb90Kmh] on the kerb side and [across90Kmh] across traffic; [at135Kmh] at 135; [reversalKmh] for a reversal
 * (and at 180). Linear between; from [freeDeg] to 45 degrees from the road's own speed (the lesser of the two edges'
 * speeds, "no limit") down to [at45Kmh].
 *
 * The first values are the survey's own arithmetic (`out/wave34-research/speed-profile-values.md`, §4 and the
 * recommendation: a corner of 10 to 15 m radius at 2 m/s2 gives 16 to 19 km/h), between published field figures of 14.5
 * to 34 km/h for a right-angle turn (85th percentile mid-turn 20.9 to 33.8 km/h on wide urban turns, one Texas study;
 * 14.5 to 17.5 km/h at 70 to 150 degree turns, one video study). Left against right as published numbers: not
 * established. [LOW] and [HIGH] scale every speed by 14.5 / 20 and 34 / 20, the field range's ends against the 90 degree
 * kerb-side value.
 */
class TurnSpeeds(
    val freeDeg: Double = 20.0,
    val at45Kmh: Double = 35.0,
    val kerb90Kmh: Double = 20.0,
    val across90Kmh: Double = 15.0,
    val at135Kmh: Double = 12.0,
    val reversalKmh: Double = 8.0,
) {
    /** The speed of a turn of [angleDeg] (signed or not), km/h; [roadKmh] where no limit applies (under [freeDeg]). */
    fun of(angleDeg: Double, across: Boolean, reversal: Boolean, roadKmh: Double): Double {
        if (reversal) return min(reversalKmh, roadKmh)
        val a = abs(angleDeg)
        val at90 = if (across) across90Kmh else kerb90Kmh
        val v = when {
            a < freeDeg -> return roadKmh
            a <= 45.0 -> lerp(if (roadKmh.isFinite()) max(roadKmh, at45Kmh) else at45Kmh, at45Kmh, (a - freeDeg) / (45.0 - freeDeg))
            a <= 90.0 -> lerp(at45Kmh, at90, (a - 45.0) / 45.0)
            a <= 135.0 -> lerp(at90, at135Kmh, (a - 90.0) / 45.0)
            else -> lerp(at135Kmh, reversalKmh, (a - 135.0) / 45.0)
        }
        return min(v, roadKmh)
    }

    fun scaled(k: Double) = TurnSpeeds(freeDeg, at45Kmh * k, kerb90Kmh * k, across90Kmh * k, at135Kmh * k, reversalKmh * k)

    companion object {
        val FIRST = TurnSpeeds()
        val LOW = FIRST.scaled(14.5 / 20.0)
        val HIGH = FIRST.scaled(34.0 / 20.0)

        private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t.coerceIn(0.0, 1.0)
    }
}

/**
 * The limits a [SpeedProfile] runs under (physical constants of one invented driver and car, never a user setting: the
 * survey found no app offering a driving style; ADR 0044). Each value is a first value inside a range the survey gives;
 * [LOW], [FIRST] and [HIGH] are the low end, the first value and the high end of every range at once (devtool
 * `route --profile-limits`). Tests of textbook cases build flat limits ([flat]).
 */
class ProfileLimits(
    val key: String,
    /**
     * The lateral acceleration a car holds in a bend up to [lateralSlowKmh], m/s2: 2.0, range 1.5 to 2.5. Sourced: AASHTO's
     * design side friction (via Iowa DOT) gives 3.1, 2.0, 1.4 and 1.0 m/s2 at 15, 30, 50 and 70 mph, falling with speed
     * for comfort; the 2.0 and its fall are the survey's choice inside that range.
     */
    val lateralSlowMps2: Double = 2.0,
    /** The lateral acceleration from [lateralFastKmh] up, m/s2: 1.5 (the survey's taper; the fall is sourced as above). */
    val lateralFastMps2: Double = 1.5,
    val lateralSlowKmh: Double = 60.0,
    val lateralFastKmh: Double = 100.0,
    /**
     * Forward acceleration at rest, m/s2: 1.5, range 1.0 to 2.0. Sourced: comfortable acceleration 1.5 to 2.0 m/s2
     * (FHWA's crash-reconstruction appendix, after Akcelik and Besley 2002), falling with speed (Akcelik and Biggs 1987).
     */
    val accelRestMps2: Double = 1.5,
    /**
     * Forward acceleration at [accelFastKmh], m/s2, reached linearly from [accelRestMps2]: 0.8. The fall is sourced
     * (Akcelik and Biggs 1987: a linear decrease fits drivers); the end value is the survey's own arithmetic.
     */
    val accelFastMps2: Double = 0.8,
    val accelFastKmh: Double = 100.0,
    /**
     * Braking, m/s2, flat: 2.0, range 1.5 to 3.0. Sourced: passengers prefer about 1.5 to 2.5 m/s2 (a test-track study);
     * AASHTO's 3.4 is a stopping-distance design value, not comfort. Whether it falls with speed: not established.
     */
    val brakeMps2: Double = 2.0,
    val turns: TurnSpeeds = TurnSpeeds.FIRST,
) {
    /** The lateral limit at [v] m/s: [lateralSlowMps2] up to [lateralSlowKmh], linear to [lateralFastMps2] at [lateralFastKmh], flat after. */
    fun lateral(v: Double): Double {
        val kmh = v * 3.6
        if (kmh <= lateralSlowKmh) return lateralSlowMps2
        if (kmh >= lateralFastKmh) return lateralFastMps2
        return lateralSlowMps2 + (lateralFastMps2 - lateralSlowMps2) * (kmh - lateralSlowKmh) / (lateralFastKmh - lateralSlowKmh)
    }

    /** The acceleration limit at [v] m/s: linear from [accelRestMps2] at rest to [accelFastMps2] at [accelFastKmh], flat after. */
    fun accel(v: Double): Double {
        val kmh = v * 3.6
        if (kmh >= accelFastKmh) return accelFastMps2
        return accelRestMps2 + (accelFastMps2 - accelRestMps2) * kmh / accelFastKmh
    }

    /**
     * The highest speed, m/s, at which a bend of [radiusM] keeps under the lateral limit: `v^2 = a(v) r`, solved by
     * bisection ([lateral] falls with speed, so `v^2 - a(v) r` rises).
     */
    fun bendMps(radiusM: Double): Double {
        if (!(radiusM < Double.POSITIVE_INFINITY)) return Double.POSITIVE_INFINITY
        var lo = 0.0
        var hi = sqrt(lateralSlowMps2 * radiusM) + 1.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (mid * mid - lateral(mid) * radiusM > 0) hi = mid else lo = mid
        }
        return lo
    }

    companion object {
        /**
         * The grid the profile is solved on, metres: each edge row is cut into equal steps of at most this (and the
         * line is resampled every this many metres for its radius). The survey's own method (about 10 m).
         */
        const val STEP_M = 10.0

        /**
         * The radius at a point is the circumradius of the line's points this many metres before and after it, along
         * the line. The survey's own method (20 to 30 m either side; mapped nodes lie too close for a radius from three
         * neighbours, which a patent's text warns gives radii far too small).
         */
        const val RADIUS_HALF_SPAN_M = 25.0

        /** A deflection under this many degrees per 20 m of line reads as straight (the survey's own method). */
        const val STRAIGHT_DEG_PER_20M = 3.0

        /** No radius is read above this, metres (the survey's own method). */
        const val RADIUS_CAP_M = 1000.0

        val FIRST = ProfileLimits("first")

        /** The low end of every range: lateral 1.5, acceleration 1.0 at rest, braking 1.5, turns at 14.5 / 20 of the first. */
        val LOW = ProfileLimits(
            "low", lateralSlowMps2 = 1.5, lateralFastMps2 = 1.5 * 1.5 / 2.0, accelRestMps2 = 1.0, accelFastMps2 = 0.8 * 1.0 / 1.5,
            brakeMps2 = 1.5, turns = TurnSpeeds.LOW,
        )

        /** The high end of every range: lateral 2.5, acceleration 2.0 at rest, braking 3.0, turns at 34 / 20 of the first. */
        val HIGH = ProfileLimits(
            "high", lateralSlowMps2 = 2.5, lateralFastMps2 = 2.5 * 1.5 / 2.0, accelRestMps2 = 2.0, accelFastMps2 = 0.8 * 2.0 / 1.5,
            brakeMps2 = 3.0, turns = TurnSpeeds.HIGH,
        )

        fun of(key: String?): ProfileLimits? = when (key) {
            null, "first" -> FIRST
            "low" -> LOW
            "high" -> HIGH
            else -> null
        }

        /** Flat limits for textbook cases: one lateral, one acceleration and one braking value at every speed. */
        fun flat(lateral: Double, accel: Double, brake: Double, turns: TurnSpeeds = TurnSpeeds.FIRST) = ProfileLimits(
            "flat", lateral, lateral, accelRestMps2 = accel, accelFastMps2 = accel, brakeMps2 = brake, turns = turns,
        )
    }
}

/** What holds a point of the profile down: nothing, the edge's speed, a bend, a priced turn, or a stop (the route's ends). */
enum class Hold(val key: String) { NONE("none"), EDGE("edge"), BEND("bend"), TURN("turn"), STOP("stop") }

/**
 * One step of the grid: [lengthM] driven on a road whose drive speed is [roadMps], with [climbM] of climb and [dropM] of
 * descent in it (both 0 or more; the climbing share of the step is `climb / (climb + drop)` of its length, the rest
 * descends, at one grade), a rolling factor [surface] ([FuelModel.surfaceFactor]) and the class's unsteadiness
 * [unsteadiness] ([FuelModel.unsteadiness]; only the "kept" fuel reads it). [capMps] is the most the car may reach inside
 * the step: the road's speed, or a bend's that holds both its ends.
 */
class ProfileStep(
    val lengthM: Double,
    val roadMps: Double,
    val climbM: Double = 0.0,
    val dropM: Double = 0.0,
    val surface: Double = 1.0,
    val unsteadiness: Double = 1.0,
    val capMps: Double = roadMps,
) {
    fun capped(cap: Double) = ProfileStep(lengthM, roadMps, climbM, dropM, surface, unsteadiness, cap)

    /** The same step with its climb and descent netted into one grade (the reading of T291's text, kept for the finding). */
    fun netted() = ProfileStep(lengthM, roadMps, max(0.0, climbM - dropM), max(0.0, dropM - climbM), surface, unsteadiness, capMps)
}

/** A solved profile: the speed at every grid node, the time and the fuel (with the class unsteadiness at 1 and kept). */
class ProfileRun(
    val speeds: DoubleArray,
    val timeS: Double,
    val fuelJ: Double,
    val fuelKeptJ: Double,
    /** The free-flow time: every step at its road speed. */
    val freeFlowS: Double,
)

/**
 * T291: the profile of a priced drive (see the file comment). [of] lays a [RoutePrice] on its route's points; [solve] is
 * the forward and backward pass over any grid, the one the tests run with flat limits.
 *
 * **Time** is the integral over the grid, each step driven as fast as its two ends and the limits allow (accelerate, hold,
 * brake: exact for constant limits within a step), plus, for each priced turn across traffic only, the wait: the turn
 * table's seconds across traffic less its kerb-side seconds at that angle ([DriveTurns.angleSeconds]); the slowing and
 * the regain are already in the profile, so the table's full seconds would count them twice (the coordinator's reading,
 * not sourced; [Result] gives the time with and without it).
 *
 * **Fuel** is [FuelModel.idleW] over the whole time (the wait included) plus, per phase of each step (accelerating,
 * holding, braking), the traction work where it is positive over [FuelModel.efficiency]: resistance ([FuelModel.rollingN]
 * times the surface factor, plus [FuelModel.airNPerMps2] `v^2`), the kinetic term and the grade together, floored at zero.
 * A descent lowers what the engine gives down to nothing and never below; braking and coasting earn nothing back (a
 * combustion car: the survey's VT-CPFM and BRouter readings). This replaces the table model's capped descent credit and
 * its per-turn regain charge inside the profile. The grade is each edge row's climb and descent ([PricePart.ascentM] and
 * [PricePart.descentM], so T286's portal rule holds) spread over its length: every step climbs its share of the row's
 * climb over part of its length and descends its share of the descent over the rest, each part floored on its own, so
 * a row that rises and falls is not read as level (netting the two read the committed fuel-pass fixture's pass road, one
 * row over the hill, as flat; [Result.fuelNetGradeJ] keeps that reading beside). The class unsteadiness factor stands in
 * for stops the model cannot see; the profile models the unsteadiness it can see, so its own figure ([Result.fuelJ]) sets
 * it to 1 and [Result.fuelKeptJ] keeps it, so the gap is on the page.
 */
object SpeedProfile {
    /** One priced turn's place on the grid, for the JSON: the node, its speed limit and whether it held the profile. */
    class TurnAt(val node: Int, val capMps: Double, val angleDeg: Double, val across: Boolean, val waitS: Double)

    class Result(
        val limits: ProfileLimits,
        val lengthM: Double,
        /** The grid's station of every node, metres from the start. */
        val stations: DoubleArray,
        val caps: DoubleArray,
        val holds: Array<Hold>,
        val run: ProfileRun,
        /** The run with the edges' speeds and the stops only, then with the bends added (the turns' and bends' shares). */
        val edgesOnly: ProfileRun,
        val withBends: ProfileRun,
        /** The run with each step's climb and descent netted into one grade (the reading this file does not take). */
        val netGrade: ProfileRun,
        val turns: List<TurnAt>,
        val waitS: Double,
        /** The table's free-flow edge seconds ([RoutePrice.edgeS]). */
        val tableEdgeS: Double,
        val fuel: FuelModel,
    ) {
        /** The integral alone, without the across-traffic wait. */
        val integralS: Double get() = run.timeS
        val timeS: Double get() = run.timeS + waitS
        val fuelJ: Double get() = run.fuelJ + fuel.idleW * waitS
        val fuelKeptJ: Double get() = run.fuelKeptJ + fuel.idleW * waitS
        val fuelNetGradeJ: Double get() = netGrade.fuelJ + fuel.idleW * waitS

        /** The runs of neighbouring nodes held at their limit, counted by what holds them ([Hold.NONE] left out). */
        fun holdCounts(): Map<Hold, Int> {
            val out = LinkedHashMap<Hold, Int>()
            for (h in Hold.entries) if (h != Hold.NONE) out[h] = 0
            var prev = Hold.NONE
            for (k in holds.indices) {
                val held = holds[k] != Hold.NONE && run.speeds[k] >= caps[k] - 1e-6
                val h = if (held) holds[k] else Hold.NONE
                if (h != Hold.NONE && h != prev) out[h] = out.getValue(h) + 1
                prev = h
            }
            return out
        }

        fun json(): Map<String, Any?> {
            val counts = holdCounts()
            return linkedMapOf(
                "limits" to limits.key, "lengthM" to RoutePrice.r1(lengthM), "nodes" to stations.size,
                "timeS" to RoutePrice.r2(timeS), "timeNoWaitS" to RoutePrice.r2(integralS), "waitS" to RoutePrice.r2(waitS),
                "freeFlowS" to RoutePrice.r2(run.freeFlowS), "tableEdgeS" to RoutePrice.r2(tableEdgeS),
                "fuelJ" to fuelJ.roundToLong(), "fuelClassKeptJ" to fuelKeptJ.roundToLong(), "fuelNetGradeJ" to fuelNetGradeJ.roundToLong(),
                "maxKmh" to RoutePrice.r1((run.speeds.maxOrNull() ?: 0.0) * 3.6),
                "meanKmh" to if (timeS > 0) RoutePrice.r1(lengthM / timeS * 3.6) else null,
                // The stretches where the profile runs at its limit, by what sets that limit.
                "holds" to counts.mapKeys { it.key.key },
                "turnsPriced" to turns.size, "turnsAcross" to turns.count { it.across },
                // Where the time and fuel go: starting, stopping and changes of road speed against free flow; then what
                // the bends add; then what the turns add (with the wait).
                "lostS" to linkedMapOf(
                    "speedChanges" to RoutePrice.r2(edgesOnly.timeS - edgesOnly.freeFlowS),
                    "bends" to RoutePrice.r2(withBends.timeS - edgesOnly.timeS),
                    "turns" to RoutePrice.r2(run.timeS - withBends.timeS + waitS),
                ),
                "addedFuelJ" to linkedMapOf(
                    "bends" to (withBends.fuelJ - edgesOnly.fuelJ).roundToLong(),
                    "turns" to (run.fuelJ - withBends.fuelJ + fuel.idleW * waitS).roundToLong(),
                ),
            )
        }
    }

    /**
     * The profile of [price]'s route, whose points are [lats] and [lons] (the plan's own, which the table's rows index).
     * The table must be priced under a preference that counts turns (devtool prices under [DrivePreference.TIME]) for its
     * turns to be priced; a shortcut row of an overlay is driven at its mean speed, its inner turns unseen. With
     * [restAtEnds] the car starts and ends at rest.
     */
    fun of(
        price: RoutePrice,
        lats: DoubleArray,
        lons: DoubleArray,
        limits: ProfileLimits = ProfileLimits.FIRST,
        fuel: FuelModel = FuelModel.DEFAULT,
        restAtEnds: Boolean = true,
    ): Result {
        require(lats.size == lons.size) { "profile: as many latitudes as longitudes" }
        val drive = CostModel(TravelMode.DRIVE)
        val line = Line(lats, lons)
        val radii = line.radii()

        val stations = ArrayList<Double>()
        val arcs = ArrayList<Double>()
        val steps = ArrayList<ProfileStep>()
        val endNode = IntArray(price.parts.size)
        stations += 0.0
        arcs += if (lats.isEmpty()) 0.0 else line.arc[0]
        var station = 0.0
        for ((p, part) in price.parts.withIndex()) {
            val len = part.lengthM
            if (len > 0) {
                val n = max(1, ceil(len / ProfileLimits.STEP_M - 1e-9).toInt())
                val road = roadMps(part, drive)
                val surface = if (part.unpaved) fuel.unpavedFactor else 1.0
                val u = if (part.wayClass != null) fuel.unsteadiness(part.wayClass) else 1.0
                val a0 = line.arcAt(part.firstPoint)
                val a1 = line.arcAt(part.lastPoint)
                for (i in 1..n) {
                    steps += ProfileStep(len / n, road, part.ascentM / n, part.descentM / n, surface, u)
                    stations += station + len * i / n
                    arcs += a0 + (a1 - a0) * i / n
                }
                station += len
            }
            endNode[p] = stations.size - 1
        }
        val nodes = stations.size

        // The limits at the nodes: the edges' speeds, then the bends (the radius at the nearest resampled point).
        val edgeCap = DoubleArray(nodes) { Double.POSITIVE_INFINITY }
        for ((k, st) in steps.withIndex()) {
            edgeCap[k] = min(edgeCap[k], st.roadMps)
            edgeCap[k + 1] = min(edgeCap[k + 1], st.roadMps)
        }
        val bendCap = DoubleArray(nodes) { k -> limits.bendMps(line.radiusAt(radii, arcs[k])) }
        // A step is held under a bend's speed only when both its ends are (an arc); a turn is a point.
        val bentSteps = steps.mapIndexed { k, st -> st.capped(min(st.roadMps, max(bendCap[k], bendCap[k + 1]))) }

        val turnCap = DoubleArray(nodes) { Double.POSITIVE_INFINITY }
        val turns = ArrayList<TurnAt>()
        var wait = 0.0
        for (j in price.joins) {
            val t = j.turn ?: continue
            if (!j.paid) continue
            val node = if (j.afterPart < 0) 0 else endNode[j.afterPart]
            val roadKmh = edgeCap[node] * 3.6 // the lesser of the two roads' speeds
            val cap = limits.turns.of(t.angleDeg, t.acrossTraffic, t.reversal, roadKmh) / 3.6
            val w = if (t.acrossTraffic) DriveTurns.angleSeconds(abs(t.angleDeg), true) - DriveTurns.angleSeconds(abs(t.angleDeg), false) else 0.0
            turnCap[node] = min(turnCap[node], cap)
            wait += max(0.0, w)
            turns += TurnAt(node, cap, t.angleDeg, t.acrossTraffic, max(0.0, w))
        }

        val stopCap = DoubleArray(nodes) { Double.POSITIVE_INFINITY }
        if (restAtEnds && nodes > 0) { stopCap[0] = 0.0; stopCap[nodes - 1] = 0.0 }

        val capEdges = DoubleArray(nodes) { min(edgeCap[it], stopCap[it]) }
        val capBends = DoubleArray(nodes) { min(capEdges[it], bendCap[it]) }
        val caps = DoubleArray(nodes) { min(capBends[it], turnCap[it]) }
        val holds = Array(nodes) { k ->
            val c = caps[k]
            when {
                c == Double.POSITIVE_INFINITY -> Hold.NONE
                c == stopCap[k] -> Hold.STOP
                c == turnCap[k] && turnCap[k] < capBends[k] -> Hold.TURN
                c == bendCap[k] && bendCap[k] < edgeCap[k] -> Hold.BEND
                else -> Hold.EDGE
            }
        }
        val edgesOnly = solve(steps, capEdges, limits, fuel)
        val withBends = solve(bentSteps, capBends, limits, fuel)
        val run = solve(bentSteps, caps, limits, fuel)
        val netGrade = solve(bentSteps.map { it.netted() }, caps, limits, fuel)
        return Result(limits, station, stations.toDoubleArray(), caps, holds, run, edgesOnly, withBends, netGrade, turns, wait, price.edgeS, fuel)
    }

    /** A row's drive speed, m/s: [CostModel.driveMps]'s arithmetic on its speed and surface; a shortcut's mean speed. */
    private fun roadMps(part: PricePart, drive: CostModel): Double {
        val kmh = part.speedKmh
        if (kmh != null) return kmh / 3.6 / drive.surfaceWeight(if (part.unpaved) Surface.UNPAVED else Surface.PAVED)
        val s = part.seconds - (part.turnS ?: 0.0)
        return if (s > 0) part.lengthM / s else Double.POSITIVE_INFINITY
    }

    /**
     * The forward and backward pass over [steps] with the node limits [caps] (one more than the steps; 0 is a stop), and
     * the time and fuel over the result. Each step is then driven as fast as its two ends allow: accelerate from its
     * first node's speed at the acceleration limit there, hold its [ProfileStep.capMps], brake to its last node's speed.
     */
    fun solve(steps: List<ProfileStep>, caps: DoubleArray, limits: ProfileLimits, fuel: FuelModel = FuelModel.DEFAULT): ProfileRun {
        require(caps.size == steps.size + 1) { "profile: one node limit more than the steps" }
        val n = caps.size
        // A node is no faster than its own limit and the caps of the steps either side of it.
        val v = DoubleArray(n) { k ->
            var c = caps[k]
            if (k < steps.size) c = min(c, steps[k].capMps)
            if (k > 0) c = min(c, steps[k - 1].capMps)
            c
        }
        // Forward: no faster than accelerating from the node before.
        for (k in 0 until steps.size) {
            val reach = sqrt(v[k] * v[k] + 2 * limits.accel(v[k]) * steps[k].lengthM)
            if (v[k + 1] > reach) v[k + 1] = reach
        }
        // Backward: no faster than braking to the node after allows.
        for (k in steps.size - 1 downTo 0) {
            val reach = sqrt(v[k + 1] * v[k + 1] + 2 * limits.brakeMps2 * steps[k].lengthM)
            if (v[k] > reach) v[k] = reach
        }
        var time = 0.0
        var free = 0.0
        var traction = 0.0
        var tractionKept = 0.0
        for ((k, st) in steps.withIndex()) {
            val ds = st.lengthM
            if (ds <= 0) continue
            free += ds / st.roadMps
            val va = v[k]
            val vb = v[k + 1]
            val a = limits.accel(va)
            val b = limits.brakeMps2
            // The peak inside the step: where accelerating from va meets braking to vb, held at the step's cap.
            var vp = sqrt(max(0.0, (2 * a * b * ds + b * va * va + a * vb * vb) / (a + b)))
            vp = min(vp, st.capMps)
            vp = max(vp, max(va, vb))
            val d1 = ((vp * vp - va * va) / (2 * a)).coerceIn(0.0, ds)
            val d3 = ((vp * vp - vb * vb) / (2 * b)).coerceIn(0.0, ds - d1)
            val d2 = ds - d1 - d3
            time += (if (d1 > 0) (vp - va) / a else 0.0) + (if (d3 > 0) (vp - vb) / b else 0.0) + (if (d2 > 0) d2 / vp else 0.0)
            for ((u, isKept) in listOf(1.0 to false, st.unsteadiness to true)) {
                val w = phaseWork(va, vp, d1, st, u, fuel) + phaseWork(vp, vp, d2, st, u, fuel) + phaseWork(vp, vb, d3, st, u, fuel)
                if (isKept) tractionKept += w else traction += w
            }
        }
        val idle = fuel.idleW * time
        return ProfileRun(v, time, idle + traction / fuel.efficiency, idle + tractionKept / fuel.efficiency, free)
    }

    /**
     * The positive work at the wheels over [d] metres of [step] from [v1] to [v2] (constant acceleration, so `v^2` is
     * linear in distance and the mean of `v^2` is the mean of its ends): resistance times [u], the kinetic change and the
     * grade, joules. The phase climbs over the step's climbing share of [d] and descends over the rest, with the
     * resistance and the kinetic change shared by length; each part is floored at zero on its own.
     */
    private fun phaseWork(v1: Double, v2: Double, d: Double, step: ProfileStep, u: Double, fuel: FuelModel): Double {
        if (d <= 0) return 0.0
        val resistance = (fuel.rollingN * step.surface + fuel.airNPerMps2 * (v1 * v1 + v2 * v2) / 2) * u * d
        val kinetic = fuel.massKg * (v2 * v2 - v1 * v1) / 2
        val share = d / step.lengthM
        val up = max(0.0, step.climbM)
        val down = max(0.0, step.dropM)
        val upShare = if (up + down > 0) up / (up + down) else 1.0
        val mg = fuel.massKg * fuel.gravity
        val climbing = (resistance + kinetic) * upShare + mg * up * share
        val descending = (resistance + kinetic) * (1 - upShare) - mg * down * share
        return max(0.0, climbing) + max(0.0, descending)
    }

    /** The route's line in local metres (an equirectangular projection at its mean latitude), with its arc length. */
    internal class Line(lats: DoubleArray, lons: DoubleArray) {
        val x = DoubleArray(lats.size)
        val y = DoubleArray(lats.size)
        val arc = DoubleArray(lats.size)

        init {
            val lat0 = if (lats.isEmpty()) 0.0 else lats.average()
            val k = cos(lat0 * PI / 180)
            for (i in lats.indices) {
                x[i] = EARTH_M * lons[i] * PI / 180 * k
                y[i] = EARTH_M * lats[i] * PI / 180
                if (i > 0) arc[i] = arc[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            }
        }

        val length: Double get() = if (arc.isEmpty()) 0.0 else arc.last()

        fun arcAt(point: Int): Double = if (arc.isEmpty()) 0.0 else arc[point.coerceIn(0, arc.size - 1)]

        /** The point at arc length [s] along the line. */
        fun at(s: Double): Pair<Double, Double> {
            if (arc.size == 1) return x[0] to y[0]
            var lo = 0
            var hi = arc.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (arc[mid] <= s) lo = mid else hi = mid
            }
            val seg = arc[hi] - arc[lo]
            val t = if (seg > 0) ((s - arc[lo]) / seg).coerceIn(0.0, 1.0) else 0.0
            return (x[lo] + (x[hi] - x[lo]) * t) to (y[lo] + (y[hi] - y[lo]) * t)
        }

        /**
         * The radius every [ProfileLimits.STEP_M] of arc: the circumradius of the points [ProfileLimits.RADIUS_HALF_SPAN_M]
         * before and after, straight (the cap) under [ProfileLimits.STRAIGHT_DEG_PER_20M] of deflection per 20 m or where
         * the span runs off an end, capped at [ProfileLimits.RADIUS_CAP_M]; then the median of each three neighbours.
         */
        fun radii(): DoubleArray {
            if (arc.size < 2) return DoubleArray(0)
            val total = length
            val count = (total / ProfileLimits.STEP_M).toInt() + 1
            val h = ProfileLimits.RADIUS_HALF_SPAN_M
            val raw = DoubleArray(count) { j ->
                val s = j * ProfileLimits.STEP_M
                if (s - h < 0 || s + h > total) ProfileLimits.RADIUS_CAP_M else circumradius(at(s - h), at(s), at(s + h), 2 * h)
            }
            return DoubleArray(count) { j ->
                if (j == 0 || j == count - 1) raw[j] else median(raw[j - 1], raw[j], raw[j + 1])
            }
        }

        /** The radius at arc [s]: the nearest resampled point's. */
        fun radiusAt(radii: DoubleArray, s: Double): Double {
            if (radii.isEmpty()) return ProfileLimits.RADIUS_CAP_M
            val j = kotlin.math.round(s / ProfileLimits.STEP_M).toInt().coerceIn(0, radii.size - 1)
            return radii[j]
        }

        private fun circumradius(a: Pair<Double, Double>, p: Pair<Double, Double>, b: Pair<Double, Double>, spanM: Double): Double {
            val ax = p.first - a.first
            val ay = p.second - a.second
            val bx = b.first - p.first
            val by = b.second - p.second
            val la = hypot(ax, ay)
            val lb = hypot(bx, by)
            if (la <= 0 || lb <= 0) return ProfileLimits.RADIUS_CAP_M
            val cosD = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1.0, 1.0)
            val deflectionDeg = acos(cosD) * 180 / PI
            if (deflectionDeg * 20.0 / spanM < ProfileLimits.STRAIGHT_DEG_PER_20M) return ProfileLimits.RADIUS_CAP_M
            val cross = abs(ax * by - ay * bx)
            if (cross <= 0) return ProfileLimits.RADIUS_CAP_M
            val lc = hypot(b.first - a.first, b.second - a.second)
            return min(ProfileLimits.RADIUS_CAP_M, la * lb * lc / (2 * cross))
        }

        private fun median(a: Double, b: Double, c: Double): Double = max(min(a, b), min(max(a, b), c))

        private fun hypot(dx: Double, dy: Double) = sqrt(dx * dx + dy * dy)

        companion object {
            const val EARTH_M = 6_371_008.8
        }
    }
}
