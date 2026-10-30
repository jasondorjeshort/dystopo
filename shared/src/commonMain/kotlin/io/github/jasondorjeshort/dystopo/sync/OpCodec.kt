package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.GapReason
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.TrackGap
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.Uuid
import io.github.jasondorjeshort.dystopo.model.Waypoint

/**
 * The compact binary form of operations and bundles (format version 1; documented in
 * docs/PLANNING.md › Sync). Hand-written so every platform shares it without a serialization
 * dependency. Integers are LEB128 varints (signed ones zigzag), doubles 8 bytes little-endian (exact,
 * so a GPX survives a sync byte for byte), strings a varint length plus UTF-8, and ids a tag byte:
 * 1 + 16 bytes for a canonical lowercase UUID, 0 + string for anything else, 2 for none.
 *
 * T158 adds two operation types rather than new bits in an old one (an older reader would misparse those):
 * [TYPE_APPEND_V2] (points with accuracy, speed, course, vertical accuracy and flags; a varint field mask per
 * point, unknown mask or flag bits refused) and [TYPE_GAP] (start and end as signed varints, the reason's code as
 * a string, an unknown reason refused). A build before T158 refuses both by name in [readOp] ("unknown operation
 * type 5" or "6"), so the whole bundle is refused and nothing is imported (ADR 0033 §3).
 */
object OpCodec {
    fun encode(op: Op): ByteArray = ByteWriter().also { writeOp(it, op) }.toByteArray()

    /** Decodes one operation; any malformed input is a [SyncFormatException]. */
    fun decode(bytes: ByteArray): Op = SyncFormatException.guard { ByteReader(bytes).let { r -> readOp(r).also { r.end() } } }

    /** 64-bit FNV-1a of an append's segment and points: two appends with the same digest add the same points. */
    fun digest(append: AppendPoints): String {
        val w = ByteWriter()
        w.uvarint(append.segment.toLong())
        if (append.type == TYPE_APPEND_V2) {
            w.byte(TYPE_APPEND_V2) // T158: the v1 digest stays what it was; v2 content is marked
            writePoints2(w, append.points)
        } else {
            writePoints(w, append.points)
        }
        var h = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
        val bytes = w.toByteArray()
        for (b in bytes) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }

    fun writeOp(w: ByteWriter, op: Op) {
        writeHlc(w, op.hlc)
        val b = op.body
        w.byte(b.type)
        w.byte(b.kind.code)
        w.id(b.id)
        when (b) {
            is PutTrack -> {
                w.svarint(b.createdAt); w.string(b.name)
                w.nstring(b.description); w.nstring(b.comment); w.nstring(b.source); w.nstring(b.trackType); w.nstring(b.extensions)
            }
            is PutWaypoint -> {
                val p = b.waypoint
                w.nid(b.trackId); w.string(p.name); w.double(p.lat); w.double(p.lon); w.ndouble(p.ele); w.nsvarint(p.time)
                w.nstring(p.description); w.nstring(p.comment); w.nstring(p.symbol); w.nstring(p.type); w.nstring(p.extensions)
            }
            is PutRoute -> {
                val r = b.route
                w.string(r.name); w.nstring(r.description); w.nstring(r.comment); w.nstring(r.type); w.nstring(r.extensions)
                w.uvarint(r.points.size.toLong())
                for (p in r.points) {
                    w.double(p.lat); w.double(p.lon); w.ndouble(p.ele); w.nsvarint(p.time)
                    w.nstring(p.name); w.nstring(p.description); w.nstring(p.symbol); w.nstring(p.extensions)
                }
            }
            is PutSetting -> w.string(b.value)
            is SetField -> { w.string(b.field); w.nstring(b.value) }
            is AppendPoints -> {
                w.uvarint(b.segment.toLong())
                if (b.type == TYPE_APPEND_V2) writePoints2(w, b.points) else writePoints(w, b.points)
            }
            is AddGap -> { w.svarint(b.gap.start); w.svarint(b.gap.end); w.string(b.gap.reason.code) }
            is Delete -> Unit
        }
    }

    fun readOp(r: ByteReader): Op {
        val hlc = readHlc(r)
        val type = r.byte()
        val kind = EntityKind.of(r.byte())
        val id = r.id()
        val body: OpBody = when (type) {
            TYPE_PUT -> when (kind) {
                EntityKind.TRACK -> PutTrack(id, r.svarint(), r.string(), r.nstring(), r.nstring(), r.nstring(), r.nstring(), r.nstring())
                EntityKind.WAYPOINT -> {
                    val trackId = r.nid()
                    PutWaypoint(trackId, Waypoint(id, r.string(), r.double(), r.double(), r.ndouble(), r.nsvarint(), r.nstring(), r.nstring(), r.nstring(), r.nstring(), r.nstring()))
                }
                EntityKind.ROUTE -> {
                    val name = r.string(); val desc = r.nstring(); val cmt = r.nstring(); val t = r.nstring(); val ext = r.nstring()
                    val n = r.count()
                    val pts = List(n) { RoutePoint(r.double(), r.double(), r.ndouble(), r.nsvarint(), r.nstring(), r.nstring(), r.nstring(), r.nstring()) }
                    PutRoute(Route(id, name, pts, desc, cmt, t, ext))
                }
                EntityKind.SETTING -> PutSetting(id, r.string())
            }
            TYPE_SET -> SetField(kind, id, r.string(), r.nstring())
            TYPE_APPEND -> {
                if (kind != EntityKind.TRACK) throw SyncFormatException("append to a $kind")
                val segment = r.uvarint()
                if (segment > Int.MAX_VALUE) throw SyncFormatException("segment $segment out of range")
                AppendPoints(id, segment.toInt(), readPoints(r))
            }
            TYPE_APPEND_V2 -> {
                if (kind != EntityKind.TRACK) throw SyncFormatException("append to a $kind")
                val segment = r.uvarint()
                if (segment > Int.MAX_VALUE) throw SyncFormatException("segment $segment out of range")
                val points = readPoints2(r)
                // Written only when a point has a fix field (AppendPoints.type), so it re-encodes byte for byte.
                if (points.none { it.hasFixFields }) throw SyncFormatException("append version 2 without fix fields")
                AppendPoints(id, segment.toInt(), points)
            }
            TYPE_GAP -> {
                if (kind != EntityKind.TRACK) throw SyncFormatException("gap on a $kind")
                val start = r.svarint()
                val end = r.svarint()
                val code = r.string()
                val reason = GapReason.of(code) ?: throw SyncFormatException("unknown gap reason \"$code\"")
                if (end < start) throw SyncFormatException("gap ends before it starts")
                AddGap(id, TrackGap(start, end, reason))
            }
            TYPE_DELETE -> Delete(kind, id)
            else -> throw SyncFormatException("unknown operation type $type")
        }
        return Op(hlc, body)
    }

    private const val HAS_ELE = 1
    private const val HAS_TIME = 2
    private const val HAS_EXT = 4

    private fun writePoints(w: ByteWriter, points: List<TrackPoint>) {
        w.uvarint(points.size.toLong())
        for (p in points) {
            w.byte((if (p.ele != null) HAS_ELE else 0) or (if (p.time != null) HAS_TIME else 0) or (if (p.extensions != null) HAS_EXT else 0))
            w.double(p.lat); w.double(p.lon)
            p.ele?.let { w.double(it) }
            p.time?.let { w.svarint(it) }
            p.extensions?.let { w.string(it) }
        }
    }

    private fun readPoints(r: ByteReader): List<TrackPoint> {
        val n = r.count()
        return List(n) {
            val f = r.byte()
            val lat = r.double(); val lon = r.double()
            TrackPoint(lat, lon, if (f and HAS_ELE != 0) r.double() else null, if (f and HAS_TIME != 0) r.svarint() else null, if (f and HAS_EXT != 0) r.string() else null)
        }
    }

    // T158: the point fields of TYPE_APPEND_V2, one bit each in a varint mask.
    private const val V2_ACC = 8
    private const val V2_SPEED = 16
    private const val V2_COURSE = 32
    private const val V2_VACC = 64
    private const val V2_FLAGS = 128
    private const val V2_KNOWN = HAS_ELE or HAS_TIME or HAS_EXT or V2_ACC or V2_SPEED or V2_COURSE or V2_VACC or V2_FLAGS

    private fun writePoints2(w: ByteWriter, points: List<TrackPoint>) {
        w.uvarint(points.size.toLong())
        for (p in points) {
            var m = 0
            if (p.ele != null) m = m or HAS_ELE
            if (p.time != null) m = m or HAS_TIME
            if (p.extensions != null) m = m or HAS_EXT
            if (p.accuracyM != null) m = m or V2_ACC
            if (p.speedMps != null) m = m or V2_SPEED
            if (p.courseDeg != null) m = m or V2_COURSE
            if (p.verticalAccuracyM != null) m = m or V2_VACC
            if (p.flags != 0) m = m or V2_FLAGS
            w.uvarint(m.toLong())
            w.double(p.lat); w.double(p.lon)
            p.ele?.let { w.double(it) }
            p.time?.let { w.svarint(it) }
            p.extensions?.let { w.string(it) }
            p.accuracyM?.let { w.double(it) }
            p.speedMps?.let { w.double(it) }
            p.courseDeg?.let { w.double(it) }
            p.verticalAccuracyM?.let { w.double(it) }
            if (p.flags != 0) w.uvarint(p.flags.toLong())
        }
    }

    private fun readPoints2(r: ByteReader): List<TrackPoint> {
        val n = r.count()
        return List(n) {
            val m = r.uvarint()
            if (m and V2_KNOWN.toLong().inv() != 0L) throw SyncFormatException("unknown point fields ${m.toString(2)} (a newer format)")
            val f = m.toInt()
            val lat = r.double(); val lon = r.double()
            val ele = if (f and HAS_ELE != 0) r.double() else null
            val time = if (f and HAS_TIME != 0) r.svarint() else null
            val ext = if (f and HAS_EXT != 0) r.string() else null
            val acc = if (f and V2_ACC != 0) r.double() else null
            val speed = if (f and V2_SPEED != 0) r.double() else null
            val course = if (f and V2_COURSE != 0) r.double() else null
            val vacc = if (f and V2_VACC != 0) r.double() else null
            val flags = if (f and V2_FLAGS != 0) r.uvarint() else 0L
            if (flags and TrackPoint.KNOWN_FLAGS.toLong().inv() != 0L || (f and V2_FLAGS != 0 && flags == 0L)) {
                throw SyncFormatException("unknown point flags ${flags.toString(2)} (a newer format)")
            }
            TrackPoint(lat, lon, ele, time, ext, acc, speed, course, vacc, flags.toInt())
        }
    }

    fun writeHlc(w: ByteWriter, h: Hlc) { w.uvarint(h.wall); w.uvarint(h.counter.toLong()); w.id(h.device) }

    fun readHlc(r: ByteReader): Hlc {
        val wall = r.uvarint()
        val counter = r.uvarint()
        return Hlc.checked(wall, counter, r.id())
    }

    fun writeVector(w: ByteWriter, v: VersionVector) {
        w.uvarint(v.entries.size.toLong())
        v.entries.values.forEach { writeHlc(w, it) }
    }

    fun readVector(r: ByteReader): VersionVector = VersionVector(List(r.count()) { readHlc(r) }.associateBy { it.device })
}

/** Little helpers for the binary format: see [OpCodec]. */
class ByteWriter(initial: Int = 256) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun byte(v: Int) { ensure(1); buf[size++] = v.toByte() }

    fun bytes(b: ByteArray) { ensure(b.size); b.copyInto(buf, size); size += b.size }

    fun uvarint(v: Long) {
        require(v >= 0) { "negative varint" }
        var x = v
        while (x >= 0x80) { byte(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
        byte(x.toInt())
    }

    fun svarint(v: Long) = uvarint0((v shl 1) xor (v shr 63))

    private fun uvarint0(v: Long) {
        var x = v
        while (x and 0x7fL.inv() != 0L) { byte(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
        byte(x.toInt())
    }

    fun nsvarint(v: Long?) { if (v == null) byte(0) else { byte(1); svarint(v) } }

    fun double(d: Double) {
        val bits = d.toRawBits()
        for (i in 0 until 8) byte((bits ushr (8 * i)).toInt() and 0xff)
    }

    fun ndouble(d: Double?) { if (d == null) byte(0) else { byte(1); double(d) } }

    fun string(s: String) { val b = s.encodeToByteArray(); uvarint(b.size.toLong()); bytes(b) }

    /** Null as 0, else length + 1. */
    fun nstring(s: String?) {
        if (s == null) { uvarint(0); return }
        val b = s.encodeToByteArray(); uvarint(b.size.toLong() + 1); bytes(b)
    }

    fun id(s: String) {
        if (s.length == 36 && Uuid.isValid(s) && s == s.lowercase()) {
            byte(1)
            var i = 0
            while (i < 36) {
                if (s[i] == '-') { i++; continue }
                byte(s.substring(i, i + 2).toInt(16)); i += 2
            }
        } else {
            byte(0); string(s)
        }
    }

    fun nid(s: String?) { if (s == null) byte(2) else id(s) }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

class ByteReader(private val buf: ByteArray, private var pos: Int = 0, private val limit: Int = buf.size) {
    val position: Int get() = pos

    private fun need(n: Int) { if (pos + n > limit) throw SyncFormatException("truncated at byte $pos") }

    fun byte(): Int { need(1); return buf[pos++].toInt() and 0xff }

    fun bytes(n: Int): ByteArray { need(n); return buf.copyOfRange(pos, pos + n).also { pos += n } }

    fun skip(n: Int) { need(n); pos += n }

    fun uvarint(): Long {
        var shift = 0
        var v = 0L
        while (true) {
            if (shift > 63) throw SyncFormatException("varint too long at byte $pos")
            val b = byte()
            v = v or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return v
            shift += 7
        }
    }

    /** A count that must fit what is left (guards allocation on corrupt input). */
    fun count(): Int {
        val n = uvarint()
        if (n < 0 || n > limit - pos) throw SyncFormatException("count $n larger than the input")
        return n.toInt()
    }

    fun svarint(): Long { val u = uvarint(); return (u ushr 1) xor -(u and 1) }

    fun nsvarint(): Long? = if (byte() == 0) null else svarint()

    fun double(): Double {
        need(8)
        var bits = 0L
        for (i in 0 until 8) bits = bits or ((buf[pos + i].toLong() and 0xff) shl (8 * i))
        pos += 8
        return Double.fromBits(bits)
    }

    fun ndouble(): Double? = if (byte() == 0) null else double()

    fun string(): String = bytes(count()).decodeToString()

    fun nstring(): String? {
        val n = uvarint()
        if (n == 0L) return null
        if (n - 1 > limit - pos) throw SyncFormatException("string longer than the input")
        return bytes((n - 1).toInt()).decodeToString()
    }

    fun id(): String = when (val tag = byte()) {
        0 -> string()
        1 -> io.github.jasondorjeshort.dystopo.model.Uuid.format(bytes(16))
        else -> throw SyncFormatException("bad id tag $tag")
    }

    fun nid(): String? {
        if (pos < limit && (buf[pos].toInt() and 0xff) == 2) { pos++; return null }
        return id()
    }

    fun end() { if (pos != limit) throw SyncFormatException("${limit - pos} trailing bytes") }

    val remaining: Int get() = limit - pos
}
