package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.mvt.Value

/**
 * The kinds of line a route may follow, from the `transportation` layer's `class` and `subclass`
 * (the same in the Dystopo outdoors profile and the OpenMapTiles schema; see tilebuild/PROFILE.md).
 * Railways, ferries, aerialways and via ferratas are not routable. Motorways and trunks (T73) are in
 * the graph for drive mode only: hiking and cycling never use them ([CostModel]).
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
    PRIMARY(11, "primary"),
    /** T73: drive mode only. */
    TRUNK(12, "trunk"),
    /** T73: drive mode only. */
    MOTORWAY(13, "motorway");

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
            "trunk" -> TRUNK
            "motorway" -> MOTORWAY
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
 * 6 difficult_alpine_hiking; outdoors profile only), flags ([PRIVATE], [INFORMAL], [POOR_VISIBILITY];
 * T73 for drive mode: [ONEWAY] or [ONEWAY_REVERSE] relative to the line's direction, [MOTOR_NO] and
 * [MOTOR_DESTINATION]), the posted speed limit in km/h ([maxspeedKmh], 0 when not tagged; T73),
 * whether it is a bridge or tunnel (no junctions are made where it crosses other lines), and (T49) its
 * name: OSM `name`, else the first `route_name` (the outdoors profile has both on tracks and trails
 * from z13; plain OpenMapTiles keeps names in `transportation_name`, so its lines have none).
 */
data class WayAttrs(
    val cls: WayClass,
    val surface: Surface = Surface.UNKNOWN,
    val sac: Int = 0,
    val flags: Int = 0,
    val brunnel: Boolean = false,
    val name: String? = null,
    /** T73: OSM `maxspeed` in km/h (the profile's `maxspeed`), 0 when not tagged. */
    val maxspeedKmh: Int = 0,
    /** T89: with [brunnel], a tunnel rather than a bridge. */
    val tunnel: Boolean = false,
) {
    /** T89: 0 on the ground, [BRIDGE] or [TUNNEL]. */
    val brunnelKind: Int get() = if (!brunnel) 0 else if (tunnel) TUNNEL else BRIDGE

    /** Surface and sac scale in one byte, as stored in the graph; since T89 the bridge or tunnel kind in bits 5 and 6. */
    val packed: Int get() = surface.code or (sac.coerceIn(0, 7) shl 2) or (brunnelKind shl 5)

    companion object {
        const val PRIVATE = 1
        const val INFORMAL = 2
        const val POOR_VISIBILITY = 4
        /** T73: motor vehicles may travel only in the line's direction (`oneway=1`, roundabouts, motorways). */
        const val ONEWAY = 8
        /** T73: motor vehicles may travel only against the line's direction (`oneway=-1`). */
        const val ONEWAY_REVERSE = 16
        /** T73: closed to motor vehicles (`motor_access` no or private; OpenMapTiles `access=no` on roads). */
        const val MOTOR_NO = 32
        /** T73: motor vehicles only to reach a place on it (`motor_access=destination`): drive mode uses it at a route's ends only. */
        const val MOTOR_DESTINATION = 64
        /** The drive-mode bits ([ONEWAY] .. [MOTOR_DESTINATION]). */
        const val DRIVE_FLAGS = ONEWAY or ONEWAY_REVERSE or MOTOR_NO or MOTOR_DESTINATION

        /** The largest speed the graph stores (one unsigned byte); faster limits are clamped to it. */
        const val MAX_SPEED_KMH = 250

        val SAC_SCALES = listOf(
            "hiking", "mountain_hiking", "demanding_mountain_hiking", "alpine_hiking", "demanding_alpine_hiking", "difficult_alpine_hiking",
        )

        fun surfaceOf(packed: Int): Surface = Surface.ofCode(packed and 3)
        fun sacOf(packed: Int): Int = (packed shr 2) and 7
        /** T89: 0, [BRIDGE] or [TUNNEL] from the packed byte. */
        fun brunnelOf(packed: Int): Int = (packed shr 5) and 3

        /** T89: [brunnelKind] values (and the node levels of [RoutingGraph.nodeLevel]). */
        const val BRIDGE = 1
        const val TUNNEL = 2

        /**
         * Attributes of a `transportation` feature, or null when it is not routable. [openMapTiles]: the
         * feature is from the OpenMapTiles schema, whose `access=no` closes a road to everyone (in the
         * outdoors profile `access` is about walking, and motor access is `motor_access`, T73).
         */
        fun of(props: Map<String, Value>, openMapTiles: Boolean = false): WayAttrs? {
            fun s(k: String) = (props[k] as? Value.StringValue)?.value
            val cls = WayClass.classify(s("class"), s("subclass")) ?: return null
            var flags = 0
            if (s("access") == "no" || s("access") == "private") flags = flags or PRIVATE
            // T73: one-way (1 or -1, as an integer or a string) and motor access.
            when (props["oneway"]?.let { v -> v.asDouble()?.let { d -> if (d > 0) "1" else if (d < 0) "-1" else "0" } ?: v.asText().trim().lowercase() }) {
                "1", "yes", "true" -> flags = flags or ONEWAY
                "-1" -> flags = flags or ONEWAY_REVERSE
            }
            when (s("motor_access")) {
                "no", "private" -> flags = flags or MOTOR_NO
                "destination" -> flags = flags or MOTOR_DESTINATION
            }
            if (openMapTiles && s("access") == "no" && cls.code >= WayClass.TRACK.code) flags = flags or MOTOR_NO
            val maxspeed = props["maxspeed"]?.let { v -> v.asDouble() ?: v.asText().trim().toDoubleOrNull() }
                ?.takeIf { it > 0 }?.toInt()?.coerceAtMost(MAX_SPEED_KMH) ?: 0
            val informal = props["informal"]
            if (informal != null && (informal.asDouble() == 1.0 || informal.asText() == "yes" || informal.asText() == "true")) flags = flags or INFORMAL
            if (s("trail_visibility") in setOf("bad", "horrible", "no")) flags = flags or POOR_VISIBILITY
            val sac = s("sac_scale")?.let { SAC_SCALES.indexOf(it) + 1 } ?: 0
            val brunnel = s("brunnel") == "bridge" || s("brunnel") == "tunnel"
            val tunnel = s("brunnel") == "tunnel"
            val name = s("name")?.trim()?.ifEmpty { null } ?: s("route_name")?.substringBefore(';')?.trim()?.ifEmpty { null }
                ?: s("ref")?.takeIf { cls.code >= WayClass.SERVICE.code }?.substringBefore(';')?.trim()?.ifEmpty { null } // T73: a road known by its number
            return WayAttrs(cls, Surface.of(s("surface")), sac, flags, brunnel, name, maxspeed, tunnel)
        }
    }
}
