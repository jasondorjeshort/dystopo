package io.github.jasondorjeshort.dystopo.routing

import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min

/**
 * T284 (ADR 0063): what a drive is optimised for. [TIME] is the drive time with its turn seconds (ADR 0062); [FUEL] the
 * fuel energy of one car that burns fuel ([FuelModel]). Each has its own overlay cells ([DriveOverlay]) and its own cell
 * file; [key] names it in the JSON (`metric`), in the fuel cell file's header and in the cell key.
 */
enum class DriveMetric(val key: String) {
    TIME("time"),
    FUEL("fuel");

    companion object {
        fun of(key: String?): DriveMetric? = entries.firstOrNull { it.key == key }
    }
}

/**
 * T284 (ADR 0063 Decision 2): the fuel energy, in joules of fuel, of one invented car that burns fuel, per directed edge:
 *
 * `fuel = P0 t + [ F u L + m g ascent - min(m g descent, F L / 2) ] / eta`, `F = Fr s + ka v^2`,
 *
 * with `t` the edge's drive time ([CostModel.edgeTimeS], so an unpaved edge is slower by the time model's own factor),
 * `v = L / t`, `L` its length, `u` the unsteadiness of its class ([classFactor], standing in for the stops the graph cannot
 * see) and `s` its surface factor ([unpavedFactor] on unpaved, else 1). The descent credit is capped at half the
 * resistance, so no edge is negative; a combustion car never earns fuel back downhill (idle is the floor). A partial edge
 * at a route's end takes its length, time, ascent, descent and so the cap in proportion.
 *
 * A turn ([turnJ]) costs its idle over the turn's seconds plus the energy to regain the out-edge's speed from [turnKmh],
 * never under zero (ADR 0063 Decision 4, the second critic's point); a turn of no seconds (straight on, a bend, a tile
 * cut, the through exit) costs nothing, as in the survey's "a priced turn".
 *
 * **Invariants, enforced:** every unsteadiness and surface factor is 1 or more, and the masses, forces, efficiency and
 * idle are positive (a model built otherwise is refused). [floorJPerM], the least joules per metre over all speeds with
 * the descent credit at its cap, and [divisorJPerM], the least flat joules per metre, are computed here from the
 * parameters, never written: the search's cost is joules over [divisorJPerM] and its potential's factor
 * [minCostPerM] = floor / divisor, so no directed edge costs less than that factor times its length.
 *
 * The first values are ADR 0063's; [hash] is a hash of every parameter, written in the fuel cell file's header so a
 * file priced with other values is refused and rebuilt.
 */
class FuelModel(
    val massKg: Double = 1600.0,
    val gravity: Double = 9.81,
    val rollingN: Double = 230.0,
    val airNPerMps2: Double = 0.4,
    val efficiency: Double = 0.35,
    val idleW: Double = 6200.0,
    val turnKmh: Double = 20.0,
    val unpavedFactor: Double = 1.8,
    /** Unsteadiness by drivable class ([WayClass.TRACK] up); classes cars may not use are never priced. */
    val classFactor: Map<WayClass, Double> = FIRST_CLASS_FACTORS,
) {
    init {
        require(massKg > 0 && gravity > 0 && rollingN > 0 && airNPerMps2 > 0 && efficiency > 0 && efficiency <= 1 && idleW > 0 && turnKmh >= 0) {
            "fuel model: masses, forces, efficiency (0 to 1) and idle must be positive"
        }
        require(unpavedFactor >= 1.0) { "fuel model: the unpaved factor must be 1 or more (got $unpavedFactor)" }
        for (c in DRIVABLE) {
            val u = classFactor[c] ?: throw IllegalArgumentException("fuel model: no unsteadiness factor for ${c.label}")
            require(u >= 1.0) { "fuel model: the unsteadiness factor of ${c.label} must be 1 or more (got $u)" }
        }
    }

    /** The smallest unsteadiness factor of a drivable class. */
    val minClassFactor: Double = DRIVABLE.minOf { classFactor.getValue(it) }

    /** [classFactor] of [c]; the largest one for a class cars may not use (never priced). */
    fun unsteadiness(c: WayClass): Double = classFactor[c] ?: DRIVABLE.maxOf { classFactor.getValue(it) }

    fun surfaceFactor(s: Surface): Double = if (s == Surface.UNPAVED) unpavedFactor else 1.0

    /**
     * The fuel of [lengthM] driven in [timeS] seconds with [ascentM] of climb and [descentM] of descent on a road of
     * unsteadiness [u] and surface factor [s], joules. Zero for no length or no time.
     */
    fun edgeJ(lengthM: Double, timeS: Double, ascentM: Double, descentM: Double, u: Double, s: Double): Double {
        if (lengthM <= 0.0 || timeS <= 0.0) return 0.0
        val v = lengthM / timeS
        val f = rollingN * s + airNPerMps2 * v * v
        val climb = massKg * gravity * max(0.0, ascentM)
        val credit = min(massKg * gravity * max(0.0, descentM), f * lengthM / 2)
        return idleW * timeS + (f * u * lengthM + climb - credit) / efficiency
    }

    /** A turn of [seconds] onto an edge driven at [outMps] metres a second: idle plus the energy to regain speed, joules. */
    fun turnJ(seconds: Double, outMps: Double): Double {
        if (!(seconds > 0.0)) return 0.0
        val vt = turnKmh / 3.6
        return idleW * seconds + max(0.0, massKg * (outMps * outMps - vt * vt) / 2) / efficiency
    }

    /** The speed (m/s) at which `idle / v + (Fr + ka v^2) k / eta` is least: d/dv = 0 gives `v^3 = P0 eta / (2 ka k)`. */
    private fun bestMps(k: Double): Double = cbrt(idleW * efficiency / (2 * airNPerMps2 * k))

    private fun perMetre(v: Double, k: Double): Double = idleW / v + (rollingN + airNPerMps2 * v * v) * k / efficiency

    /** The speed of [floorJPerM], m/s. */
    val floorMps: Double = bestMps(minClassFactor - 0.5)

    /**
     * The least joules per metre any edge can cost: over all speeds, on the steadiest class, paved, with the descent
     * credit at its cap (`F (u - 1/2)`) and no climb. 857.9 J/m at the first values, at about 63 km/h.
     */
    val floorJPerM: Double = perMetre(floorMps, minClassFactor - 0.5)

    /** The speed of [divisorJPerM], m/s. */
    val divisorMps: Double = bestMps(minClassFactor)

    /** The least flat joules per metre: the search's unit of cost. 1323.9 at the first values, at about 50 km/h. */
    val divisorJPerM: Double = perMetre(divisorMps, minClassFactor)

    /** The potential's factor: no directed edge costs less than this times its length (0.648 at the first values). */
    val minCostPerM: Double = floorJPerM / divisorJPerM

    /** Every parameter, in a fixed order, as text: what [hash] is taken of. */
    val parameters: String = buildString {
        append("m=").append(massKg).append(";g=").append(gravity).append(";Fr=").append(rollingN).append(";ka=").append(airNPerMps2)
        append(";eta=").append(efficiency).append(";P0=").append(idleW).append(";vturn=").append(turnKmh).append(";s=").append(unpavedFactor)
        for (c in DRIVABLE) append(";u.").append(c.label).append('=').append(classFactor.getValue(c))
    }

    /** A hash of [parameters] (FNV-1a), in the fuel cell file's header. */
    val hash: Int = parameters.encodeToByteArray().let { RoutingGraph.fnv1a(it, it.size) }

    fun json(): Map<String, Any?> = linkedMapOf(
        "floorJPerM" to r1(floorJPerM), "floorKmh" to r1(floorMps * 3.6), "divisorJPerM" to r1(divisorJPerM), "divisorKmh" to r1(divisorMps * 3.6),
        "minCostPerM" to (minCostPerM * 1e4).toLong() / 1e4, "hash" to (hash.toLong() and 0xFFFFFFFFL),
    )

    companion object {
        /** The classes cars may use ([CostModel.driveSpeedKmh] above zero). */
        val DRIVABLE: List<WayClass> = WayClass.entries.filter { CostModel.driveSpeedKmh(it) > 0 }

        /** ADR 0063 Decision 2's first values; tuning may only raise one (Decision 8). */
        val FIRST_CLASS_FACTORS: Map<WayClass, Double> = mapOf(
            WayClass.MOTORWAY to 1.0, WayClass.TRUNK to 1.0, WayClass.PRIMARY to 1.05, WayClass.SECONDARY to 1.1,
            WayClass.TERTIARY to 1.15, WayClass.MINOR to 1.25, WayClass.SERVICE to 1.3, WayClass.TRACK to 1.3,
        )

        /** The model at ADR 0063's first values. */
        val DEFAULT = FuelModel()

        private fun r1(v: Double) = kotlin.math.round(v * 10) / 10.0
    }
}
