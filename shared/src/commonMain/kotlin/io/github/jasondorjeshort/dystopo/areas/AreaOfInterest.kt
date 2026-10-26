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

/** Where an [AreaOfInterest] is: a rectangle, a line with a buffer on both sides, or (T140, ADR 0041) a polygon. */
sealed class AreaShape {
    abstract val kind: String

    /** A rectangle in degrees (west < east: areas do not cross the antimeridian). */
    data class Box(val west: Double, val south: Double, val east: Double, val north: Double) : AreaShape() {
        override val kind get() = KIND_BOX
    }

    /** The ground within [bufferM] metres of the line through [points] (a route or a track; one point: a disc). */
    data class Corridor(val points: List<AreaPoint>, val bufferM: Double) : AreaShape() {
        override val kind get() = KIND_CORRIDOR
    }

    /**
     * T140 (ADR 0041 §1): the inside of [rings] by the even-odd rule (holes and further parts are more rings), grown by
     * [bufferM] metres. Each ring is open here (its first point not repeated; the JSON writes it closed, as GeoJSON does),
     * has at least three points and never crosses the antimeridian; rings never overlap (the writer's guarantee).
     */
    data class Polygon(val rings: List<List<AreaPoint>>, val bufferM: Double = 0.0) : AreaShape() {
        override val kind get() = KIND_POLYGON
    }

    companion object {
        const val KIND_BOX = "box"
        const val KIND_CORRIDOR = "corridor"
        const val KIND_POLYGON = "polygon"
        /** The kinds this build reads (`GET /area`'s `shapes`). */
        val KINDS = listOf(KIND_BOX, KIND_CORRIDOR, KIND_POLYGON)
    }
}

/**
 * T140 (ADR 0041 §2): an area or a manifest this build cannot read because a newer one wrote it: a manifest `version`
 * above [AreaManifest.VERSION], or an area `kind` it does not know. [message] is the raw sentence; the user sees
 * [USER_SENTENCE] with it under it (the area card, the Share screen).
 */
class NeedsNewerApp(reason: String) : IllegalArgumentException(reason) {
    companion object {
        const val USER_SENTENCE = "This phone runs an older Dystopo; update it to use this area."
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
 * {"kind": "polygon", "rings": [[[lon, lat], ...], ...], "bufferM": 0, "minZoom": 8, "maxZoom": 15, "name": "..."}
 * ```
 * `line` and `rings` are in GeoJSON order (longitude first; a ring is written closed and read closed or open). [id] is
 * a content id of the geometry and zooms (not the name), so the same area asked twice is the same area. T140: a
 * polygon needs manifest version 2 ([manifestVersion]); a box and a corridor stay 1 (ADR 0041 §2).
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
            is AreaShape.Polygon -> {
                require(shape.rings.isNotEmpty()) { "a polygon needs at least one ring" }
                for (ring in shape.rings) {
                    require(ring.size >= 3) { "a polygon ring needs at least three points" }
                    require(ring.all { abs(it.lat) <= MAX_LAT && abs(it.lon) <= 180 }) { "polygon points must be within +-$MAX_LAT latitude and +-180 longitude" }
                    require(ring.maxOf { it.lon } - ring.minOf { it.lon } <= 180) { "a polygon ring must not cross the antimeridian: split it at 180 degrees into two rings" }
                }
                require(shape.bufferM in 0.0..MAX_BUFFER_M) { "bufferM must be 0 to $MAX_BUFFER_M metres (got ${shape.bufferM})" }
            }
        }
    }

    val kind: String get() = shape.kind

    /** T140 (ADR 0041 §2): the lowest manifest version that holds this area: 2 for a polygon, 1 for a box or a corridor. */
    val manifestVersion: Int get() = if (shape is AreaShape.Polygon) 2 else 1

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
            is AreaShape.Polygon -> PolygonRings.bounds(shape.rings, shape.bufferM)
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
            is AreaShape.Polygon -> {
                m["rings"] = shape.rings.map { ring -> (ring + ring.first()).map { listOf(r7(it.lon), r7(it.lat)) } } // closed, as GeoJSON
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
                is AreaShape.Polygon -> "polygon:${r6s(shape.bufferM)}:" + shape.rings.joinToString("|") { ring -> ring.joinToString(";") { r6s(it.lat) + "," + r6s(it.lon) } }
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
        /** T140 (ADR 0041 §4): the vertices of the circle a distance around a point above [MAX_BUFFER_M] is written as. */
        const val CIRCLE_VERTICES = 48
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
            val kind = (m["kind"] as? String) ?: if (m["line"] != null) AreaShape.KIND_CORRIDOR else if (m["rings"] != null) AreaShape.KIND_POLYGON else AreaShape.KIND_BOX
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
                AreaShape.KIND_POLYGON -> {
                    val rings = (m["rings"] as? List<*>)?.map { r ->
                        val pts = (r as? List<*>)?.map { p ->
                            val c = (p as? List<*>)?.map { (it as? Number)?.toDouble() }
                            require(c != null && c.size >= 2 && c[0] != null && c[1] != null) { "rings must be [[[lon, lat], ...], ...]" }
                            AreaPoint(c[1]!!, c[0]!!)
                        }
                        require(pts != null) { "rings must be [[[lon, lat], ...], ...]" }
                        openRing(pts)
                    }
                    require(!rings.isNullOrEmpty()) { "a polygon needs rings: [[[lon, lat], ...], ...]" }
                    val buffer = (m["bufferM"] as? Number)?.toDouble() ?: (m["bufferM"] as? String)?.toDoubleOrNull() ?: 0.0
                    AreaOfInterest(AreaShape.Polygon(rings, buffer), int("minZoom", 8), int("maxZoom", 15), name)
                }
                // T140 (ADR 0041 §2): older builds say "kind must be box or corridor"; the phone falls back on that prefix.
                else -> throw NeedsNewerApp("kind must be ${AreaShape.KINDS.dropLast(1).joinToString(", ")} or ${AreaShape.KINDS.last()} (got '$kind')")
            }
        }

        fun parse(json: String): AreaOfInterest = parse(MiniJson.parseObject(json))

        /** A ring without its closing point (GeoJSON repeats the first point last; [AreaShape.Polygon] keeps rings open). */
        fun openRing(points: List<AreaPoint>): List<AreaPoint> = if (points.size > 1 && points.first() == points.last()) points.dropLast(1) else points

        /**
         * T140 (ADR 0041 §4): the area within [radiusM] metres of [center]: the one-point corridor up to [MAX_BUFFER_M]
         * (what every build since T75 reads), beyond it a polygon circle of [CIRCLE_VERTICES] vertices drawn just outside
         * the circle, so it holds the whole disc.
         */
        fun around(center: AreaPoint, radiusM: Double, minZoom: Int = 8, maxZoom: Int = 15, name: String? = null): AreaOfInterest {
            require(radiusM > 0) { "the radius must be more than 0 metres" }
            if (radiusM <= MAX_BUFFER_M) return AreaOfInterest(AreaShape.Corridor(listOf(center), radiusM.coerceAtLeast(MIN_BUFFER_M)), minZoom, maxZoom, name)
            return AreaOfInterest(AreaShape.Polygon(listOf(PolygonRings.circle(center, radiusM, CIRCLE_VERTICES))), minZoom, maxZoom, name)
        }

        /**
         * From query parameters: `bbox=w,s,e,n` or `line=lat,lon;lat,lon;...` (latitude first, like the phone's other
         * routes), plus `bufferM`, `minZoom`, `maxZoom`, `name`. T140: `ring=lat,lon;lat,lon;...` for a polygon (further
         * rings, holes or parts, after a `|`; `bufferM` 0 by default), or `around=lat,lon` with `radiusM` ([around]).
         */
        fun fromQuery(q: Map<String, String>): AreaOfInterest {
            val name = q["name"]?.trim()?.ifEmpty { null }
            fun int(k: String, default: Int) = q[k]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("$k must be an integer") } ?: default
            fun points(s: String, what: String) = s.split(';').filter { it.isNotBlank() }.map { p ->
                val c = p.split(',').map { it.trim().toDoubleOrNull() }
                require(c.size == 2 && c.all { it != null }) { "$what must be lat,lon;lat,lon;... (got '$p')" }
                AreaPoint(c[0]!!, c[1]!!)
            }
            (q["ring"] ?: q["rings"])?.let { s ->
                val rings = s.split('|').filter { it.isNotBlank() }.map { openRing(points(it, "ring")) }
                require(rings.isNotEmpty()) { "ring must be lat,lon;lat,lon;lat,lon[|lat,lon;...]" }
                val buffer = q["bufferM"]?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("bufferM must be a number") } ?: 0.0
                return AreaOfInterest(AreaShape.Polygon(rings, buffer), int("minZoom", 8), int("maxZoom", 15), name)
            }
            q["around"]?.let { s ->
                val c = points(s, "around").singleOrNull() ?: throw IllegalArgumentException("around must be lat,lon")
                val r = (q["radiusM"] ?: q["radius"])?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("radiusM must be a number of metres") }
                    ?: throw IllegalArgumentException("around=lat,lon needs radiusM")
                return around(c, r, int("minZoom", 8), int("maxZoom", 15), name)
            }
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
            require(!line.isNullOrEmpty()) { "give bbox=w,s,e,n, line=lat,lon;lat,lon;..., ring=lat,lon;lat,lon;lat,lon or around=lat,lon with radiusM" }
            val buffer = q["bufferM"]?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("bufferM must be a number") } ?: DEFAULT_BUFFER_M
            return AreaOfInterest(AreaShape.Corridor(line, buffer), int("minZoom", 8), int("maxZoom", 15), name)
        }
    }
}
