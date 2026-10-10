package io.github.jasondorjeshort.dystopo.render

import kotlin.math.abs
import kotlin.math.roundToLong

/** Units contour levels are expressed in. [meters] is the size of one unit. */
enum class ContourUnits(val meters: Double, val symbol: String) {
    FEET(0.3048, "ft"),
    METERS(1.0, "m");

    companion object {
        fun parse(symbol: String): ContourUnits = entries.firstOrNull { it.symbol == symbol }
            ?: throw IllegalArgumentException("unknown units '$symbol'; use ft or m")
    }
}

/**
 * Resolved contour parameters: an [interval] in [units], with every [indexEvery]th line an
 * index (bold, labeled) contour. Elevations handed to the methods here are always meters.
 */
data class ContourSpec(val interval: Double, val indexEvery: Int, val units: ContourUnits) {
    init {
        require(interval > 0) { "interval must be positive" }
        require(indexEvery >= 0) { "indexEvery must be >= 0 (0 disables index contours)" }
    }

    val intervalMeters: Double get() = interval * units.meters

    /** Meters between index contours, or 0 when index contours are disabled. */
    val indexIntervalMeters: Double get() = if (indexEvery == 0) 0.0 else intervalMeters * indexEvery

    /** Contour levels (meters) strictly inside the elevation range, as exact multiples of the interval in [units]. */
    fun levelsMeters(minMeters: Double, maxMeters: Double): List<Double> =
        Contours.levels(minMeters / units.meters, maxMeters / units.meters, interval).map { it * units.meters }

    /** True when [levelMeters] falls on an index contour. */
    fun isIndex(levelMeters: Double): Boolean {
        if (indexEvery == 0) return false
        val n = levelMeters / indexIntervalMeters
        return abs(n - n.roundToLong()) < 1e-6
    }

    /** Label text for a level: the elevation in [units], without a unit suffix ("6400"). */
    fun label(levelMeters: Double): String = (levelMeters / units.meters).roundToLong().toString()
}

/**
 * Zoom-dependent contour density. Web Mercator pixel size halves with every zoom level, so the
 * interval roughly doubles per zoom to keep the same on-screen density. The tables are tuned so
 * that lines on a 30-degree slope at mid-latitudes stay about 6-10 px apart; the steeper ground
 * is handled by [Contours.cutSteep].
 */
object ContourStyle {
    /** Zoom → (interval, indexEvery) in feet. Zooms below the first key use the first entry, above the last use the last. */
    private val feet = listOf(
        10 to ContourSpec(2000.0, 5, ContourUnits.FEET),
        11 to ContourSpec(1000.0, 5, ContourUnits.FEET),
        12 to ContourSpec(400.0, 5, ContourUnits.FEET),
        13 to ContourSpec(200.0, 5, ContourUnits.FEET),
        14 to ContourSpec(80.0, 5, ContourUnits.FEET),
        15 to ContourSpec(40.0, 5, ContourUnits.FEET),
        16 to ContourSpec(20.0, 5, ContourUnits.FEET),
    )

    private val meters = listOf(
        10 to ContourSpec(500.0, 2, ContourUnits.METERS),
        11 to ContourSpec(200.0, 5, ContourUnits.METERS),
        12 to ContourSpec(100.0, 5, ContourUnits.METERS),
        13 to ContourSpec(50.0, 4, ContourUnits.METERS),
        14 to ContourSpec(20.0, 5, ContourUnits.METERS),
        15 to ContourSpec(10.0, 5, ContourUnits.METERS),
        16 to ContourSpec(5.0, 4, ContourUnits.METERS),
    )

    /** The default spec for [zoom] in [units]. */
    fun forZoom(zoom: Int, units: ContourUnits): ContourSpec {
        val table = if (units == ContourUnits.FEET) feet else meters
        val clamped = zoom.coerceIn(table.first().first, table.last().first)
        return table.first { it.first == clamped }.second
    }

    /**
     * The spec for [zoom], with any of [interval] and [indexEvery] overriding the table.
     * An explicit interval without an explicit indexEvery keeps the table's indexEvery.
     */
    fun resolve(zoom: Int, units: ContourUnits, interval: Double? = null, indexEvery: Int? = null): ContourSpec {
        val base = forZoom(zoom, units)
        return ContourSpec(interval ?: base.interval, indexEvery ?: base.indexEvery, units)
    }
}
