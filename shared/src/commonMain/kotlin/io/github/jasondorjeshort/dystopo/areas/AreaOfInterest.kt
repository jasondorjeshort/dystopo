package io.github.jasondorjeshort.dystopo.areas

import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.packs.Sha256
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round

/** A point of a corridor's centre line. */
data class AreaPoint(val lat: Double, val lon: Double)

/** Where an [AreaOfInterest] is: a rectangle, or a line with a buffer on both sides. */
sealed class AreaShape {
    abstract val kind: String

    /** A rectangle in degrees (west < east: areas do not cross the antimeridian). */
    data class Box(val west: Double, val south: Double, val east: Double, val north: Double) : AreaShape() {
        override val kind get() = KIND_BOX
    }

    /** The ground within [bufferM] metres of the line through [points] (a route or a track). */
    data class Corridor(val points: List<AreaPoint>, val bufferM: Double) : AreaShape() {
        override val kind get() = KIND_CORRIDOR
    }

    companion object {
        const val KIND_BOX = "box"
        const val KIND_CORRIDOR = "corridor"
    }
}

/**
 * T75 (design principle 4): "tell us where you are going". A box or a route corridor with a zoom range; it
 * expands to a manifest ([AreaManifest]) of what the map needs there offline. [minZoom]..[maxZoom] are tile
 * zooms of the data (DEM tiles to [maxZoom], vector tiles to `min(maxZoom, 14)`); the map overzooms past them.
 *
 * JSON (the `area` object of a manifest, and the body of `POST /area` on the server or `POST /areas/download` on
 * the phone):
 * ```
 * {"kind": "box", "bbox": [w, s, e, n], "minZoom": 8, "maxZoom": 14, "name": "..."}
 * {"kind": "corridor", "line": [[lon, lat], ...], "bufferM": 500, "minZoom": 8, "maxZoom": 15, "name": "..."}
 * ```
 * `line` is in GeoJSON order (longitude first). [id] is a content id of the geometry and zooms (not the name),
 * so the same area asked twice is the same area.
 */
data class AreaOfInterest(val shape: AreaShape, val minZoom: Int, val maxZoom: Int, val name: String? = null) {
    init {
        require(minZoom in 0..MAX_ZOOM && maxZoom in minZoom..MAX_ZOOM) { "need 0 <= minZoom <= maxZoom <= $MAX_ZOOM (got $minZoom..$maxZoom)" }
        when (shape) {
            is AreaShape.Box -> require(
                shape.west >= -180 && shape.east <= 180 && shape.west < shape.east && shape.south >= -MAX_LAT && shape.north <= MAX_LAT && shape.south < shape.north,
            ) { "bbox ${shape.west},${shape.south},${shape.east},${shape.north} is not a box (west < east within -180..180, south < north within +-$MAX_LAT)" }
            is AreaShape.Corridor -> {
                require(shape.points.isNotEmpty()) { "a corridor needs at least one point" }
                require(shape.points.all { abs(it.lat) <= MAX_LAT && abs(it.lon) <= 180 }) { "corridor points must be within +-$MAX_LAT latitude and +-180 longitude" }
                require(shape.bufferM in MIN_BUFFER_M..MAX_BUFFER_M) { "bufferM must be $MIN_BUFFER_M to $MAX_BUFFER_M metres (got ${shape.bufferM})" }
            }
        }
    }

    val kind: String get() = shape.kind

    /** The rectangle holding the whole area (for a corridor: the line's box grown by the buffer). */
    val bounds: PackBounds
        get() = when (shape) {
            is AreaShape.Box -> PackBounds(shape.west, shape.south, shape.east, shape.north)
            is AreaShape.Corridor -> {
                val lats = shape.points.map { it.lat }
                val lons = shape.points.map { it.lon }
                val dLat = shape.bufferM / M_PER_DEG
                val maxAbsLat = minOf(MAX_LAT, max(abs(lats.min()), abs(lats.max())) + dLat)
                val dLon = shape.bufferM / (M_PER_DEG * cos(maxAbsLat * PI / 180))
                PackBounds(
                    (lons.min() - dLon).coerceAtLeast(-180.0), (lats.min() - dLat).coerceAtLeast(-MAX_LAT),
                    (lons.max() + dLon).coerceAtMost(180.0), (lats.max() + dLat).coerceAtMost(MAX_LAT),
                )
            }
        }

    /** Length of a corridor's line in metres (0 for a box). */
    val lengthM: Double get() = (shape as? AreaShape.Corridor)?.let { Polyline.lengthM(it.points) } ?: 0.0

    fun json(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("kind" to kind)
        when (shape) {
            is AreaShape.Box -> m["bbox"] = listOf(shape.west, shape.south, shape.east, shape.north)
            is AreaShape.Corridor -> {
                m["line"] = shape.points.map { listOf(r7(it.lon), r7(it.lat)) }
                m["bufferM"] = shape.bufferM
            }
        }
        m["minZoom"] = minZoom
        m["maxZoom"] = maxZoom
        if (name != null) m["name"] = name
        return m
    }

    /** `a` + 16 hex digits of the SHA-256 of the geometry and zooms (coordinates rounded to 1e-6 degrees). */
    val id: String
        get() {
            val canonical = when (shape) {
                is AreaShape.Box -> "box:" + listOf(shape.west, shape.south, shape.east, shape.north).joinToString(",") { r6s(it) }
                is AreaShape.Corridor -> "corridor:${r6s(shape.bufferM)}:" + shape.points.joinToString(";") { r6s(it.lat) + "," + r6s(it.lon) }
            } + ":$minZoom-$maxZoom"
            return "a" + Sha256.hex(canonical.encodeToByteArray()).take(16)
        }

    fun withName(n: String?): AreaOfInterest = copy(name = n)

    companion object {
        const val MAX_ZOOM = 16
        const val MAX_LAT = 85.05
        const val MIN_BUFFER_M = 10.0
        const val MAX_BUFFER_M = 20_000.0
        const val DEFAULT_BUFFER_M = 500.0
        /** Metres per degree of latitude (and of longitude at the equator), on the sphere web tiles use. */
        const val M_PER_DEG = 6378137.0 * PI / 180

        private fun r7(d: Double) = round(d * 1e7) / 1e7
        private fun r6s(d: Double): String = (round(d * 1e6) / 1e6).toString()

        /** Reads the JSON form (see the class comment); throws [IllegalArgumentException] with the reason. */
        fun parse(m: Map<String, Any?>): AreaOfInterest {
            fun int(k: String, default: Int): Int = when (val v = m[k]) {
                null -> default
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: throw IllegalArgumentException("$k must be an integer")
                else -> throw IllegalArgumentException("$k must be an integer")
            }
            val name = (m["name"] as? String)?.trim()?.ifEmpty { null }
            val kind = (m["kind"] as? String) ?: if (m["line"] != null) AreaShape.KIND_CORRIDOR else AreaShape.KIND_BOX
            return when (kind) {
                AreaShape.KIND_BOX -> {
                    val b = (m["bbox"] as? List<*>)?.map { (it as? Number)?.toDouble() }
                    require(b != null && b.size == 4 && b.all { it != null }) { "bbox must be [west, south, east, north]" }
                    AreaOfInterest(AreaShape.Box(b[0]!!, b[1]!!, b[2]!!, b[3]!!), int("minZoom", 8), int("maxZoom", 14), name)
                }
                AreaShape.KIND_CORRIDOR -> {
                    val line = (m["line"] as? List<*>)?.map { p ->
                        val c = (p as? List<*>)?.map { (it as? Number)?.toDouble() }
                        require(c != null && c.size >= 2 && c[0] != null && c[1] != null) { "line must be [[lon, lat], ...]" }
                        AreaPoint(c[1]!!, c[0]!!)
                    }
                    require(!line.isNullOrEmpty()) { "a corridor needs line: [[lon, lat], ...]" }
                    val buffer = (m["bufferM"] as? Number)?.toDouble() ?: (m["bufferM"] as? String)?.toDoubleOrNull() ?: DEFAULT_BUFFER_M
                    AreaOfInterest(AreaShape.Corridor(line, buffer), int("minZoom", 8), int("maxZoom", 15), name)
                }
                else -> throw IllegalArgumentException("kind must be box or corridor (got '$kind')")
            }
        }

        fun parse(json: String): AreaOfInterest = parse(MiniJson.parseObject(json))

        /**
         * From query parameters: `bbox=w,s,e,n` or `line=lat,lon;lat,lon;...` (latitude first, like the phone's other
         * routes), plus `bufferM`, `minZoom`, `maxZoom`, `name`.
         */
        fun fromQuery(q: Map<String, String>): AreaOfInterest {
            val name = q["name"]?.trim()?.ifEmpty { null }
            fun int(k: String, default: Int) = q[k]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("$k must be an integer") } ?: default
            q["bbox"]?.let { s ->
                val b = s.split(',').map { it.trim().toDoubleOrNull() }
                require(b.size == 4 && b.all { it != null }) { "bbox must be west,south,east,north" }
                return AreaOfInterest(AreaShape.Box(b[0]!!, b[1]!!, b[2]!!, b[3]!!), int("minZoom", 8), int("maxZoom", 14), name)
            }
            val line = (q["line"] ?: q["points"])?.split(';')?.filter { it.isNotBlank() }?.map { p ->
                val c = p.split(',').map { it.trim().toDoubleOrNull() }
                require(c.size == 2 && c.all { it != null }) { "line must be lat,lon;lat,lon;... (got '$p')" }
                AreaPoint(c[0]!!, c[1]!!)
            }
            require(!line.isNullOrEmpty()) { "give bbox=w,s,e,n or line=lat,lon;lat,lon;..." }
            val buffer = q["bufferM"]?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("bufferM must be a number") } ?: DEFAULT_BUFFER_M
            return AreaOfInterest(AreaShape.Corridor(line, buffer), int("minZoom", 8), int("maxZoom", 15), name)
        }
    }
}
