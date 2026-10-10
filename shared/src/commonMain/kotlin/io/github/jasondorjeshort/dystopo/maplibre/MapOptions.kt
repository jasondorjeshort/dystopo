package io.github.jasondorjeshort.dystopo.maplibre

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.tiles.HillshadeMode
import io.github.jasondorjeshort.dystopo.tiles.ShadeOptions

/** How many contour labels the style asks MapLibre to place (symbol spacing along index lines). */
enum class ContourLabelDensity(val key: String, val spacingFactor: Double) {
    SPARSE("sparse", 2.0), NORMAL("normal", 1.0), DENSE("dense", 0.55);

    companion object {
        fun parse(s: String): ContourLabelDensity? = entries.firstOrNull { it.key == s.trim().lowercase() }
    }
}

/**
 * The user's map options (T47, "rebuild the map to taste"): hillshade style and intensity, the slope-angle
 * overlay, the contour interval and label density, layer toggles and night mode. [MapLibreStyle] builds
 * the style from them; the hillshade and slope rasters come from [io.github.jasondorjeshort.dystopo.tiles.ShadeTiles]
 * except in the default case (single light at full intensity, no slope), which keeps MapLibre's own
 * `raster-dem` hillshade ([nativeHillshade]).
 *
 * [contourInterval] is in the user's units (feet or metres), null for the per-zoom table; it applies to
 * contour tiles from [FIXED_INTERVAL_FROM_ZOOM] up (below that the table's coarser lines keep a
 * whole-range overview readable). The defaults reproduce the map as it was before T47.
 */
data class MapOptions(
    val hillshade: HillshadeMode = HillshadeMode.SINGLE,
    val hillshadeIntensity: Double = 1.0,
    val slope: Boolean = false,
    val contourInterval: Double? = null,
    val contourLabels: ContourLabelDensity = ContourLabelDensity.NORMAL,
    val trails: Boolean = true,
    val water: Boolean = true,
    val landcover: Boolean = true,
    val pois: Boolean = true,
    val boundaries: Boolean = true,
    val night: Boolean = false,
) {
    init {
        require(hillshadeIntensity in 0.0..1.0) { "hillshadeIntensity must be in 0..1" }
    }

    /** True when MapLibre's own hillshade layer draws the relief (no shade tiles needed for it). */
    val nativeHillshade: Boolean get() = hillshade == HillshadeMode.SINGLE && hillshadeIntensity >= 1.0

    /** What the generated shade tiles draw; null when the style needs none. */
    val shade: ShadeOptions?
        get() {
            val hs = if (nativeHillshade) HillshadeMode.OFF else hillshade
            if (hs == HillshadeMode.OFF && !slope) return null
            return ShadeOptions(hs, if (hs == HillshadeMode.OFF) 0.0 else hillshadeIntensity, slope, night)
        }

    /** Whether a style layer of [DefaultOsmStyle][io.github.jasondorjeshort.dystopo.style.DefaultOsmStyle] with this id is shown. */
    fun showsLayer(id: String): Boolean = when (LayerGroup.of(id)) {
        LayerGroup.TRAILS -> trails
        LayerGroup.WATER -> water
        LayerGroup.LANDCOVER -> landcover
        LayerGroup.POIS -> pois
        LayerGroup.BOUNDARIES -> boundaries
        null -> true
    }

    fun json(units: ContourUnits): Map<String, Any?> = linkedMapOf(
        "hillshade" to hillshade.key,
        "hillshadeIntensity" to hillshadeIntensity,
        "slope" to if (slope) "on" else "off",
        "contourInterval" to (contourInterval?.let { fmt(it) } ?: "auto"),
        "contourIntervalUnits" to units.symbol,
        "contourIntervalChoices" to listOf("auto") + intervals(units).map { fmt(it) },
        "contourLabels" to contourLabels.key,
        "trails" to trails,
        "water" to water,
        "landcover" to landcover,
        "pois" to pois,
        "boundaries" to boundaries,
        "night" to if (night) "on" else "off",
        "shadeTiles" to (shade != null),
    )

    /** The layer toggles, for the settings sheet and `/settings`. */
    enum class LayerGroup(val key: String) {
        TRAILS("trails"), WATER("water"), LANDCOVER("landcover"), POIS("pois"), BOUNDARIES("boundaries");

        companion object {
            private val byId: Map<String, LayerGroup> = buildMap {
                listOf("track", "trail", "trail-mountain", "trail-demanding", "trail-alpine", "footway", "path-paved", "steps", "trail-names")
                    .forEach { put(it, TRAILS) }
                listOf("water", "water-edge", "river", "stream", "stream-intermittent", "water-names", "waterway-names").forEach { put(it, WATER) }
                listOf("wood", "wetland", "rock", "glacier").forEach { put(it, LANDCOVER) }
                listOf("huts", "trailheads", "campsites", "shelters", "waterfalls", "viewpoints", "springs", "caves", "outdoor-pois", "parking")
                    .forEach { put(it, POIS) }
                listOf("wilderness-fill", "park-boundary", "wilderness-boundary", "admin-boundary", "park-names").forEach { put(it, BOUNDARIES) }
            }

            /** The group a style layer id belongs to, or null for layers no toggle hides (roads, peaks, places, contours). */
            fun of(id: String): LayerGroup? = byId[id]

            fun ids(group: LayerGroup): List<String> = byId.filterValues { it == group }.keys.toList()
        }
    }

    companion object {
        /** Contour tile zoom from which a fixed [contourInterval] replaces the table. */
        const val FIXED_INTERVAL_FROM_ZOOM = 11

        val FEET_INTERVALS = listOf(10.0, 20.0, 40.0, 50.0, 80.0, 100.0, 200.0, 250.0, 500.0, 1000.0)
        val METRE_INTERVALS = listOf(2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 250.0)

        /** The fixed intervals offered in [units]. */
        fun intervals(units: ContourUnits): List<Double> = if (units == ContourUnits.FEET) FEET_INTERVALS else METRE_INTERVALS

        val KEYS = setOf(
            "hillshade", "hillshadeIntensity", "slope", "contourInterval", "contourLabels",
            "trails", "water", "landcover", "pois", "boundaries", "night",
        )

        /**
         * [base] with the options in [params] applied (keys from [KEYS] only; others are ignored here and
         * checked by the caller). Throws [IllegalArgumentException] with a message for a bad value. A
         * contour interval must be one of [intervals] for [units].
         */
        fun parse(params: Map<String, Any?>, base: MapOptions, units: ContourUnits): MapOptions {
            var o = base
            params["hillshade"]?.let { o = o.copy(hillshade = HillshadeMode.parse(it.toString()) ?: bad("hillshade must be off, single or multi (got $it)")) }
            params["hillshadeIntensity"]?.let { raw ->
                val v = (raw as? Number)?.toDouble() ?: raw.toString().trim().toDoubleOrNull()
                if (v == null || !v.isFinite() || v < 0.0 || v > 1.0) bad("hillshadeIntensity must be a number from 0 to 1 (got $raw)")
                o = o.copy(hillshadeIntensity = kotlin.math.round(v * 1000) / 1000)
            }
            params["slope"]?.let { o = o.copy(slope = bool("slope", it)) }
            params["contourInterval"]?.let { raw ->
                val s = raw.toString().trim().lowercase()
                o = if (s == "auto" || s.isEmpty()) o.copy(contourInterval = null) else {
                    val v = (raw as? Number)?.toDouble() ?: s.removeSuffix(units.symbol).trim().toDoubleOrNull()
                    if (v == null || intervals(units).none { it == v }) {
                        bad("contourInterval must be auto or one of ${intervals(units).map { fmt(it) }} (${units.symbol}; got $raw)")
                    }
                    o.copy(contourInterval = v)
                }
            }
            params["contourLabels"]?.let { o = o.copy(contourLabels = ContourLabelDensity.parse(it.toString()) ?: bad("contourLabels must be sparse, normal or dense (got $it)")) }
            params["trails"]?.let { o = o.copy(trails = bool("trails", it)) }
            params["water"]?.let { o = o.copy(water = bool("water", it)) }
            params["landcover"]?.let { o = o.copy(landcover = bool("landcover", it)) }
            params["pois"]?.let { o = o.copy(pois = bool("pois", it)) }
            params["boundaries"]?.let { o = o.copy(boundaries = bool("boundaries", it)) }
            params["night"]?.let { o = o.copy(night = bool("night", it)) }
            return o
        }

        private fun bool(key: String, raw: Any): Boolean = when (raw.toString().trim().lowercase()) {
            "1", "true", "on", "yes" -> true
            "0", "false", "off", "no" -> false
            else -> bad("$key must be on or off (got $raw)")
        }

        private fun bad(message: String): Nothing = throw IllegalArgumentException(message)

        /** 100.0 → "100", 2.5 → "2.5". */
        fun fmt(d: Double): String = if (d == kotlin.math.floor(d)) d.toLong().toString() else d.toString()
    }
}
