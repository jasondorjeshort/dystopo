package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.mvt.Value

/**
 * The kinds of line a route may follow, from the `transportation` layer's `class` and `subclass`
 * (the same in the Dystopo outdoors profile and the OpenMapTiles schema; see tilebuild/PROFILE.md).
 * Motorways, trunks, railways, ferries, aerialways and via ferratas are not routable.
 */
enum class WayClass(val code: Int, val label: String) {
    PATH(1, "path"),
    FOOTWAY(2, "footway"),
    CYCLEWAY(3, "cycleway"),
    BRIDLEWAY(4, "bridleway"),
    STEPS(5, "steps"),
    TRACK(6, "track"),
    SERVICE(7, "service"),
    MINOR(8, "minor"),
    TERTIARY(9, "tertiary"),
    SECONDARY(10, "secondary"),
    PRIMARY(11, "primary");

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun ofCode(code: Int): WayClass? = byCode[code]

        /** The routable class of a `transportation` feature, or null when routes must not use it. */
        fun classify(cls: String?, subclass: String?): WayClass? = when (cls) {
            "path" -> when (subclass) {
                "footway", "pedestrian" -> FOOTWAY
                "cycleway" -> CYCLEWAY
                "bridleway" -> BRIDLEWAY
                "steps" -> STEPS
                "via_ferrata", "platform", "corridor", "elevator", "escalator" -> null
                else -> PATH
            }
            "pedestrian" -> FOOTWAY
            "track" -> TRACK
            "service" -> SERVICE
            "minor" -> MINOR
            "tertiary" -> TERTIARY
            "secondary" -> SECONDARY
            "primary" -> PRIMARY
            else -> null
        }
    }
}

/** OpenMapTiles `surface`: paved or unpaved, or not tagged. */
enum class Surface(val code: Int, val label: String) {
    UNKNOWN(0, "unknown"), PAVED(1, "paved"), UNPAVED(2, "unpaved");

    companion object {
        fun ofCode(code: Int): Surface = entries.firstOrNull { it.code == code } ?: UNKNOWN
        fun of(value: String?): Surface = when (value) {
            "paved" -> PAVED
            "unpaved" -> UNPAVED
            else -> UNKNOWN
        }
    }
}

/**
 * What the router knows about one line: class, surface, OSM `sac_scale` (0 none, 1 hiking ..
 * 6 difficult_alpine_hiking; outdoors profile only), flags ([PRIVATE], [INFORMAL], [POOR_VISIBILITY]),
 * and whether it is a bridge or tunnel (no junctions are made where it crosses other lines).
 */
data class WayAttrs(
    val cls: WayClass,
    val surface: Surface = Surface.UNKNOWN,
    val sac: Int = 0,
    val flags: Int = 0,
    val brunnel: Boolean = false,
) {
    /** Surface and sac scale in one byte, as stored in the graph. */
    val packed: Int get() = surface.code or (sac.coerceIn(0, 7) shl 2)

    companion object {
        const val PRIVATE = 1
        const val INFORMAL = 2
        const val POOR_VISIBILITY = 4

        val SAC_SCALES = listOf(
            "hiking", "mountain_hiking", "demanding_mountain_hiking", "alpine_hiking", "demanding_alpine_hiking", "difficult_alpine_hiking",
        )

        fun surfaceOf(packed: Int): Surface = Surface.ofCode(packed and 3)
        fun sacOf(packed: Int): Int = (packed shr 2) and 7

        /** Attributes of a `transportation` feature, or null when it is not routable. */
        fun of(props: Map<String, Value>): WayAttrs? {
            fun s(k: String) = (props[k] as? Value.StringValue)?.value
            val cls = WayClass.classify(s("class"), s("subclass")) ?: return null
            var flags = 0
            if (s("access") == "no" || s("access") == "private") flags = flags or PRIVATE
            val informal = props["informal"]
            if (informal != null && (informal.asDouble() == 1.0 || informal.asText() == "yes" || informal.asText() == "true")) flags = flags or INFORMAL
            if (s("trail_visibility") in setOf("bad", "horrible", "no")) flags = flags or POOR_VISIBILITY
            val sac = s("sac_scale")?.let { SAC_SCALES.indexOf(it) + 1 } ?: 0
            val brunnel = s("brunnel") == "bridge" || s("brunnel") == "tunnel"
            return WayAttrs(cls, Surface.of(s("surface")), sac, flags, brunnel)
        }
    }
}
