package io.github.jasondorjeshort.dystopo.mvt

/**
 * A decoded Mapbox Vector Tile (MVT 2.1). Geometry is in integer tile coordinates: the origin is
 * the tile's top-left corner, x grows right, y grows down, and [Layer.extent] is the tile's width
 * in these units. Coordinates may fall outside `0..extent` (buffer).
 */
data class VectorTile(val layers: List<Layer>) {
    fun layer(name: String): Layer? = layers.firstOrNull { it.name == name }

    fun encode(): ByteArray = MvtEncoder.encode(this)

    companion object {
        fun decode(bytes: ByteArray): VectorTile = MvtDecoder.decode(bytes)
    }
}

data class Layer(
    val name: String,
    val features: List<Feature>,
    /** Tile width in geometry units; 4096 is the usual value. */
    val extent: Int = 4096,
    /** MVT spec version; 2 for current tiles. Version 1 tiles don't guarantee ring winding. */
    val version: Int = 2,
) {
    /** Distinct property keys across the layer's features, in first-seen order (the layer's key table). */
    val keys: List<String> by lazy { features.asSequence().flatMap { it.properties.keys }.distinct().toList() }
}

data class Feature(
    /** Feature id (uint64 in the tile; stored as its bit pattern), or null when absent. */
    val id: Long?,
    val properties: Map<String, Value>,
    val geometry: Geometry,
) {
    val type: GeometryType get() = geometry.type
}

enum class GeometryType(val code: Int) {
    UNKNOWN(0), POINT(1), LINESTRING(2), POLYGON(3);

    companion object {
        fun fromCode(code: Int): GeometryType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** A property value. Each subclass is one `oneof` branch of the tile's `Value` message, so round trips keep the wire type. */
sealed class Value {
    data class StringValue(val value: String) : Value()
    data class FloatValue(val value: Float) : Value()
    data class DoubleValue(val value: Double) : Value()
    /** `int_value`: plain varint int64. */
    data class IntValue(val value: Long) : Value()
    /** `uint_value`: uint64, stored as its bit pattern (negative [value] means above `Long.MAX_VALUE`). */
    data class UintValue(val value: Long) : Value()
    /** `sint_value`: zigzag-encoded int64, efficient for negative numbers. */
    data class SintValue(val value: Long) : Value()
    data class BoolValue(val value: Boolean) : Value()

    /** The value as a number, or null for strings and booleans. */
    fun asDouble(): Double? = when (this) {
        is FloatValue -> value.toDouble()
        is DoubleValue -> value
        is IntValue -> value.toDouble()
        is UintValue -> value.toULong().toDouble()
        is SintValue -> value.toDouble()
        is StringValue, is BoolValue -> null
    }

    /** Human-readable form; what a label would show. */
    fun asText(): String = when (this) {
        is StringValue -> value
        is FloatValue -> value.toString()
        is DoubleValue -> value.toString()
        is IntValue -> value.toString()
        is UintValue -> value.toULong().toString()
        is SintValue -> value.toString()
        is BoolValue -> value.toString()
    }
}

data class TilePoint(val x: Int, val y: Int)

/**
 * A polygon: one exterior ring and zero or more holes. Rings are implicitly closed (the first
 * point is not repeated at the end). In a tile that follows the spec the exterior has positive
 * [signedDoubleArea] (clockwise on screen, y down) and holes have negative area.
 */
data class Polygon(val exterior: List<TilePoint>, val interiors: List<List<TilePoint>> = emptyList())

sealed class Geometry {
    abstract val type: GeometryType

    /** One point, or several for a multipoint. */
    data class Points(val points: List<TilePoint>) : Geometry() {
        override val type get() = GeometryType.POINT
    }

    /** One or more linestrings, each with at least two points. */
    data class Lines(val lines: List<List<TilePoint>>) : Geometry() {
        override val type get() = GeometryType.LINESTRING
    }

    /** One or more polygons. */
    data class Polygons(val polygons: List<Polygon>) : Geometry() {
        override val type get() = GeometryType.POLYGON
    }

    /** Geometry of type UNKNOWN; the raw command integers are kept as-is. */
    data class Unknown(val commands: List<Int>) : Geometry() {
        override val type get() = GeometryType.UNKNOWN
    }

    /** Number of vertices in this geometry. */
    val vertexCount: Int
        get() = when (this) {
            is Points -> points.size
            is Lines -> lines.sumOf { it.size }
            is Polygons -> polygons.sumOf { p -> p.exterior.size + p.interiors.sumOf { it.size } }
            is Unknown -> 0
        }
}

/**
 * Twice the signed area of a ring by the surveyor's (shoelace) formula in tile coordinates
 * (y down): positive means the ring runs clockwise on screen, which the MVT spec requires for
 * exterior rings; negative for holes; zero for degenerate rings.
 */
fun signedDoubleArea(ring: List<TilePoint>): Long {
    var sum = 0L
    val n = ring.size
    for (i in 0 until n) {
        val a = ring[i]
        val b = ring[if (i + 1 == n) 0 else i + 1]
        sum += a.x.toLong() * b.y - b.x.toLong() * a.y
    }
    return sum
}
