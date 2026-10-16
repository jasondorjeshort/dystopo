package io.github.jasondorjeshort.dystopo.model

import io.github.jasondorjeshort.dystopo.routing.Instruction
import io.github.jasondorjeshort.dystopo.routing.Maneuver
import io.github.jasondorjeshort.dystopo.routing.WayClass
import io.github.jasondorjeshort.dystopo.sync.ByteReader
import io.github.jasondorjeshort.dystopo.sync.ByteWriter
import kotlin.math.roundToLong

/*
 * Trips (T60): a planned route as a first-class, synced entity. A route is drawn through via points;
 * between two consecutive via points runs one leg, routed along the trail graph ([LegKind.TRAIL]), a
 * straight line where there is no trail ([LegKind.STRAIGHT], drawn dashed), or geometry kept as it was
 * imported ([LegKind.FIXED]). Waypoints (named points of interest) belong to the route.
 *
 * In the operation log a route is a `PutRoute` (name and the other GPX fields) plus two field writes
 * (`SetField`, last writer wins): `path` ([RoutePath.encode]: mode, via points and every leg's
 * geometry in one value, so a reroute can never pair one device's via points with another's legs) and
 * `waypoints` ([RouteWaypoints.encode]). Older builds ignore fields they do not know. T108 (ADR 0033): a third,
 * `directions` ([RouteDirections]), keeps the turn directions with the trip, tied to the path they were made for.
 *
 * Coordinates in a path are quantised to 1e-7 degrees (about 1 cm) and elevations to centimetres
 * ([RoutePath.quantized]), so a path decodes to exactly what was encoded and a GPX written from it is
 * byte-identical on every replica.
 */

/** How a leg between two via points was made. Codes are part of the encoded path; never reuse one. */
enum class LegKind(val code: Int, val label: String) {
    /** Routed along the trail graph. */
    TRAIL(1, "trail"),

    /** A straight line: no trail connects the two via points (or there was no graph). Drawn dashed. */
    STRAIGHT(2, "straight"),

    /** Geometry kept as imported (GPX, or a pre-T60 route); rerouted when one of its via points moves. */
    FIXED(3, "fixed");

    companion object {
        fun of(code: Int): LegKind = entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("unknown leg kind $code")
        fun ofLabel(label: String?): LegKind? = entries.firstOrNull { it.label == label }
    }
}

/** A point the route is drawn through (where the user put it, snapped onto a trail when one was near). */
data class ViaPoint(val lat: Double, val lon: Double)

/**
 * The geometry from via point i to via point i + 1: at least two points, the first and last equal to those via points.
 * [timeS] (T89): for a drive leg the router planned, its travel time by the router's per-road speeds (whole seconds),
 * which [RouteSummary] adds up so every editor shows the router's time; null for every other leg.
 */
data class RouteLeg(val kind: LegKind, val points: List<RoutePoint>, val timeS: Double? = null)

/**
 * A route's shape: the travel [mode] (`hike`, `bike`), the [via] points and one [legs] entry between
 * each consecutive pair (so `legs.size == via.size - 1`, or no legs for fewer than two via points).
 */
data class RoutePath(val mode: String, val via: List<ViaPoint>, val legs: List<RouteLeg>) {
    /** The whole route as one point list: every leg, the shared point between two legs once. */
    val points: List<RoutePoint>
        get() {
            if (legs.isEmpty()) return via.map { RoutePoint(it.lat, it.lon) }
            val out = ArrayList<RoutePoint>(legs.sumOf { it.points.size })
            for ((i, leg) in legs.withIndex()) out.addAll(if (i == 0) leg.points else leg.points.drop(1))
            return out
        }

    /** Index in [points] of each via point. */
    val viaIndexes: List<Int>
        get() {
            if (legs.isEmpty()) return via.indices.toList()
            val out = ArrayList<Int>(via.size)
            var k = 0
            out += 0
            for (leg in legs) { k += leg.points.size - 1; out += k }
            return out
        }

    /** The same path with coordinates rounded to 1e-7 degrees and elevations to centimetres (what [encode] keeps). */
    fun quantized(): RoutePath = RoutePath(
        mode,
        via.map { ViaPoint(q7(it.lat), q7(it.lon)) },
        legs.map { leg -> RouteLeg(leg.kind, leg.points.map { RoutePoint(q7(it.lat), q7(it.lon), it.ele?.let(::qEle)) }, leg.timeS?.let { kotlin.math.round(it) }) },
    )

    /**
     * The `path` field value: a versioned binary form (deltas in 1e-7 degrees and centimetres) as base64url.
     * Version 1 unless a leg has a [RouteLeg.timeS]; then version 2 ([VERSION_TIMES], T89), which appends each
     * leg's time. Builds before T89 refuse version 2 and show the route's put points as one fixed leg instead
     * (the Materializer's rule for a newer format), so only drive routes planned on a newer build look different there.
     */
    fun encode(): String {
        val w = ByteWriter()
        val times = legs.any { it.timeS != null }
        w.byte(if (times) VERSION_TIMES else VERSION)
        w.string(mode)
        w.uvarint(via.size.toLong())
        var lat = 0L
        var lon = 0L
        for (v in via) {
            val a = e7(v.lat); val o = e7(v.lon)
            w.svarint(a - lat); w.svarint(o - lon)
            lat = a; lon = o
        }
        w.uvarint(legs.size.toLong())
        lat = 0; lon = 0
        var ele = 0L
        for (leg in legs) {
            w.byte(leg.kind.code)
            w.uvarint(leg.points.size.toLong())
            for (p in leg.points) {
                val a = e7(p.lat); val o = e7(p.lon)
                w.svarint(a - lat); w.svarint(o - lon)
                lat = a; lon = o
                val e = p.ele
                if (e == null) w.uvarint(0) else {
                    val cm = (e * 100).roundToLong()
                    val d = cm - ele
                    w.uvarint(((d shl 1) xor (d shr 63)) + 1)
                    ele = cm
                }
            }
        }
        // T89: version 2, then per leg 0 (no time) or 1 + whole seconds.
        if (times) for (leg in legs) w.uvarint(leg.timeS?.let { kotlin.math.round(it).toLong().coerceAtLeast(0) + 1 } ?: 0L)
        return Base64Url.encode(w.toByteArray())
    }

    companion object {
        const val VERSION = 1

        /** T89: version 1 plus each leg's [RouteLeg.timeS]; written only when a leg has one. */
        const val VERSION_TIMES = 2

        /** An empty path (no via points yet). */
        fun empty(mode: String = "hike") = RoutePath(mode, emptyList(), emptyList())

        /**
         * Imported geometry as a path: the first and last point are the via points and everything is one
         * [LegKind.FIXED] leg; a single point is one via point; none is [empty].
         */
        fun fixed(points: List<RoutePoint>, mode: String = "hike"): RoutePath {
            if (points.isEmpty()) return empty(mode)
            val pts = points.map { RoutePoint(it.lat, it.lon, it.ele) }
            if (pts.size == 1) return RoutePath(mode, listOf(ViaPoint(pts[0].lat, pts[0].lon)), emptyList()).quantized()
            return RoutePath(mode, listOf(ViaPoint(pts.first().lat, pts.first().lon), ViaPoint(pts.last().lat, pts.last().lon)), listOf(RouteLeg(LegKind.FIXED, pts))).quantized()
        }

        /**
         * A route the router planned in one piece (the phone's route card, kept as a trip): its start and end as via
         * points and its line as one [LegKind.TRAIL] leg, with the router's [timeS] on a drive (T89; dropped for
         * hike and bike, whose time the summary models from distance and climb). T108: a crow line is one
         * [LegKind.STRAIGHT] leg, as every crow leg is.
         */
        fun planned(points: List<RoutePoint>, mode: String, timeS: Double): RoutePath {
            val pts = points.map { RoutePoint(it.lat, it.lon, it.ele) }
            require(pts.size >= 2) { "a planned route has two points or more" }
            val leg = RouteLeg(if (mode == "crow") LegKind.STRAIGHT else LegKind.TRAIL, pts, timeS.takeIf { mode == "drive" })
            return RoutePath(mode, listOf(ViaPoint(pts.first().lat, pts.first().lon), ViaPoint(pts.last().lat, pts.last().lon)), listOf(leg)).quantized()
        }

        /** Decodes a `path` field value; throws [IllegalArgumentException] for anything malformed. */
        fun decode(text: String): RoutePath {
            val bytes = Base64Url.decode(text)
            try {
                val r = ByteReader(bytes)
                val version = r.byte()
                require(version == VERSION || version == VERSION_TIMES) { "unknown path version $version" }
                val mode = r.string()
                val nv = r.count()
                var lat = 0L
                var lon = 0L
                val via = List(nv) {
                    lat += r.svarint(); lon += r.svarint()
                    ViaPoint(lat / 1e7, lon / 1e7)
                }
                val nl = r.count()
                require(if (nv < 2) nl == 0 else nl == nv - 1) { "$nl legs for $nv via points" }
                lat = 0; lon = 0
                var ele = 0L
                val legs = List(nl) {
                    val kind = LegKind.of(r.byte())
                    val n = r.count()
                    require(n >= 2) { "a leg needs two points" }
                    RouteLeg(kind, List(n) {
                        lat += r.svarint(); lon += r.svarint()
                        val u = r.uvarint()
                        val e = if (u == 0L) null else {
                            val z = u - 1
                            ele += (z ushr 1) xor -(z and 1)
                            ele / 100.0
                        }
                        RoutePoint(lat / 1e7, lon / 1e7, e)
                    })
                }
                val timed = if (version != VERSION_TIMES) legs else legs.map { leg ->
                    val t = r.uvarint()
                    require(t >= 0) { "bad leg time" }
                    if (t == 0L) leg else leg.copy(timeS = (t - 1).toDouble())
                }
                r.end()
                return RoutePath(mode, via, timed)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: RuntimeException) {
                throw IllegalArgumentException("corrupt path: ${e.message}")
            }
        }

        fun decodeOrNull(text: String?): RoutePath? = text?.let { runCatching { decode(it) }.getOrNull() }

        private fun e7(v: Double): Long = (v * 1e7).roundToLong()
        fun q7(v: Double): Double = e7(v) / 1e7
        fun qEle(v: Double): Double = (v * 100).roundToLong() / 100.0
    }
}

/** The `waypoints` field of a route: its waypoints in order, exact (doubles as stored). */
object RouteWaypoints {
    const val VERSION = 1

    fun encode(list: List<Waypoint>): String {
        val w = ByteWriter()
        w.byte(VERSION)
        w.uvarint(list.size.toLong())
        for (p in list) {
            w.id(p.id); w.string(p.name); w.double(p.lat); w.double(p.lon); w.ndouble(p.ele); w.nsvarint(p.time)
            w.nstring(p.description); w.nstring(p.comment); w.nstring(p.symbol); w.nstring(p.type); w.nstring(p.extensions)
        }
        return Base64Url.encode(w.toByteArray())
    }

    fun decode(text: String): List<Waypoint> {
        val bytes = Base64Url.decode(text)
        try {
            val r = ByteReader(bytes)
            val version = r.byte()
            require(version == VERSION) { "unknown waypoints version $version" }
            val n = r.count()
            return List(n) {
                Waypoint(r.id(), r.string(), r.double(), r.double(), r.ndouble(), r.nsvarint(), r.nstring(), r.nstring(), r.nstring(), r.nstring(), r.nstring())
            }.also { r.end() }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("corrupt waypoints: ${e.message}")
        }
    }

    fun decodeOrNull(text: String?): List<Waypoint>? = text?.let { runCatching { decode(it) }.getOrNull() }
}

/**
 * A planned route as the app shows it (T60): the GPX fields of [Route], the [path] it was drawn with
 * and its [waypoints]. [toRoute] is the GPX `rte` (the path's points as `rtept`s). T108: [directions], the turn
 * directions saved with it; a plan read from a store has them only when they were made for its [path] (else null:
 * not saved, or saved for a path an edit has since replaced), and [savedDirections] checks that for any plan.
 */
data class RoutePlan(
    val id: String,
    val name: String,
    val path: RoutePath,
    val waypoints: List<Waypoint> = emptyList(),
    val description: String? = null,
    val comment: String? = null,
    val type: String? = null,
    val extensions: String? = null,
    val directions: SavedDirections? = null,
) {
    fun toRoute(): Route = Route(id, name, path.points, description, comment, type, extensions)

    /** The waypoint by id, or null. */
    fun waypoint(id: String): Waypoint? = waypoints.firstOrNull { it.id == id }

    /** T108: the saved turn directions when they were made for this plan's [path], else null. */
    val savedDirections: List<Instruction>? get() = directions?.takeIf { it.isFor(path) }?.list
}

/** T108: turn directions saved with a trip and the key of the path they were made for ([RouteDirections.pathKey]). */
data class SavedDirections(val pathKey: Long, val list: List<Instruction>) {
    fun isFor(path: RoutePath): Boolean = pathKey == RouteDirections.pathKey(path)

    companion object {
        /** [list] saved for [path], quantised as the field keeps it ([RouteDirections.quantized]). */
        fun of(list: List<Instruction>, path: RoutePath) = SavedDirections(RouteDirections.pathKey(path), RouteDirections.quantized(list))
    }
}

/**
 * T108 (ADR 0033): the `directions` field of a route: the turn directions ([Instruction]) saved with the trip, so the
 * trip card and navigation have them without a graph (offline, on a phone that never planned the trip, after a sync).
 * The value carries the key of the path it was made for ([pathKey]: FNV-1a 64 of the path value, [RoutePath.encode] of
 * the quantised path), and [io.github.jasondorjeshort.dystopo.sync.Materializer.routePlan] takes it only when that is
 * the route's path now: directions written for another path (an edit on a build that does not write them, a browser
 * edit, two phones editing at once with the path from one and the directions from the other) count as absent, the
 * same on every replica, and are made again where a graph is ([io.github.jasondorjeshort.dystopo.routing.Directions]).
 * Builds before T108 ignore the field (it is not one of their route fields), so they keep working without it.
 *
 * Version 1, as base64url: u8 version, u64 path key (8 bytes, big-endian), uvarint count, then per instruction:
 * string type ([Maneuver.code]), u8 priority, svarint along (decimetres, delta from the previous), svarint lat and lon
 * (1e-7 degrees, deltas), nstring name, u8 way class ([WayClass.code] + 1, 0 none), u8 flags (1 same way, 2 via),
 * svarint turn (degrees), uvarint bearing (degrees), uvarint lead and stretch (decimetres), then uvarint via when
 * flag 2. A value that does not decode (a newer version, an unknown type) counts as absent.
 */
object RouteDirections {
    const val VERSION = 1

    /** The longest `directions` value a device writes (64 KiB of base64url, a couple of thousand instructions); longer lists are not saved. */
    const val MAX_CHARS = 64 * 1024

    /** The key of [path]: FNV-1a 64 over its quantised value ([RoutePath.encode]). */
    fun pathKey(path: RoutePath): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in path.quantized().encode().encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return h
    }

    /** [list] as the field keeps it: distances to decimetres, coordinates to 1e-7 degrees (what [decode] returns). */
    fun quantized(list: List<Instruction>): List<Instruction> = list.map {
        it.copy(alongM = dm(it.alongM) / 10.0, lat = e7(it.lat) / 1e7, lon = e7(it.lon) / 1e7, leadM = dm(it.leadM) / 10.0, stretchM = dm(it.stretchM) / 10.0)
    }

    /** The field value for [saved]; null when it would be longer than [MAX_CHARS]. */
    fun encode(saved: SavedDirections): String? {
        val w = ByteWriter()
        w.byte(VERSION)
        for (k in 7 downTo 0) w.byte((saved.pathKey ushr (8 * k)).toInt() and 0xff)
        w.uvarint(saved.list.size.toLong())
        var along = 0L
        var lat = 0L
        var lon = 0L
        for (i in saved.list) {
            w.string(i.type.code)
            w.byte(i.priority.coerceIn(0, 255))
            val a = dm(i.alongM); w.svarint(a - along); along = a
            val la = e7(i.lat); val lo = e7(i.lon)
            w.svarint(la - lat); w.svarint(lo - lon)
            lat = la; lon = lo
            w.nstring(i.name)
            w.byte(i.wayClass?.let { it.code + 1 } ?: 0)
            w.byte((if (i.sameWay) 1 else 0) or (if (i.via != null) 2 else 0))
            w.svarint(i.turnDeg.toLong())
            w.uvarint(((i.bearingDeg % 360 + 360) % 360).toLong())
            w.uvarint(dm(i.leadM).coerceAtLeast(0))
            w.uvarint(dm(i.stretchM).coerceAtLeast(0))
            i.via?.let { w.uvarint(it.toLong().coerceAtLeast(0)) }
        }
        return Base64Url.encode(w.toByteArray()).takeIf { it.length <= MAX_CHARS }
    }

    /** The field value for [list] made for [path]; null when too long. */
    fun encode(list: List<Instruction>, path: RoutePath): String? = encode(SavedDirections.of(list, path))

    /** Decodes a `directions` value; throws [IllegalArgumentException] for anything malformed or newer. */
    fun decode(text: String): SavedDirections {
        val bytes = Base64Url.decode(text)
        try {
            val r = ByteReader(bytes)
            val version = r.byte()
            require(version == VERSION) { "unknown directions version $version" }
            var key = 0L
            repeat(8) { key = (key shl 8) or r.byte().toLong() }
            val n = r.count()
            var along = 0L
            var lat = 0L
            var lon = 0L
            val list = List(n) {
                val code = r.string()
                val type = Maneuver.of(code) ?: throw IllegalArgumentException("unknown direction type '$code'")
                val priority = r.byte()
                along += r.svarint()
                lat += r.svarint(); lon += r.svarint()
                val name = r.nstring()
                val cls = r.byte().let { c -> if (c == 0) null else WayClass.entries.firstOrNull { it.code == c - 1 } ?: throw IllegalArgumentException("unknown way class ${c - 1}") }
                val flags = r.byte()
                val turn = r.svarint().toInt()
                val bearing = r.uvarint().toInt()
                val lead = r.uvarint()
                val stretch = r.uvarint()
                val via = if (flags and 2 != 0) r.uvarint().toInt() else null
                Instruction(type, priority, along / 10.0, lat / 1e7, lon / 1e7, name, cls, flags and 1 != 0, turn, bearing, lead / 10.0, stretch / 10.0, via)
            }
            r.end()
            return SavedDirections(key, list)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("corrupt directions: ${e.message}")
        }
    }

    fun decodeOrNull(text: String?): SavedDirections? = text?.let { runCatching { decode(it) }.getOrNull() }

    /** The directions in [text] when they decode and were made for [path], else null (absent, stale or newer). */
    fun forPath(text: String?, path: RoutePath): SavedDirections? = decodeOrNull(text)?.takeIf { it.isFor(path) }

    private fun dm(m: Double): Long = (m * 10).roundToLong()
    private fun e7(v: Double): Long = (v * 1e7).roundToLong()
}

/** RFC 4648 base64url without padding (no platform API in commonMain). */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val INDEX = IntArray(128) { -1 }.also { a -> ALPHABET.forEachIndexed { i, c -> a[c.code] = i } }

    fun encode(b: ByteArray): String {
        val sb = StringBuilder((b.size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= b.size) {
            val v = (b[i].toInt() and 0xff shl 16) or (b[i + 1].toInt() and 0xff shl 8) or (b[i + 2].toInt() and 0xff)
            sb.append(ALPHABET[v shr 18]).append(ALPHABET[v shr 12 and 63]).append(ALPHABET[v shr 6 and 63]).append(ALPHABET[v and 63])
            i += 3
        }
        when (b.size - i) {
            1 -> {
                val v = b[i].toInt() and 0xff shl 16
                sb.append(ALPHABET[v shr 18]).append(ALPHABET[v shr 12 and 63])
            }
            2 -> {
                val v = (b[i].toInt() and 0xff shl 16) or (b[i + 1].toInt() and 0xff shl 8)
                sb.append(ALPHABET[v shr 18]).append(ALPHABET[v shr 12 and 63]).append(ALPHABET[v shr 6 and 63])
            }
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        require(s.length % 4 != 1) { "bad base64 length" }
        val out = ByteArray(s.length * 3 / 4)
        var bits = 0
        var n = 0
        var k = 0
        for (c in s) {
            val v = if (c.code < 128) INDEX[c.code] else -1
            require(v >= 0) { "bad base64 character" }
            bits = (bits shl 6) or v
            n += 6
            if (n >= 8) {
                n -= 8
                out[k++] = (bits shr n and 0xff).toByte()
            }
        }
        return out.copyOf(k)
    }
}
