package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.Waypoint

/**
 * T158 test fixture: the reader of v0.1.14-alpha, kept to show what a phone on that release does with a newer
 * bundle (ADR 0033 §3). `readOp`, `readPoints` and `Bundle.decode` are copied from the tag `v0.1.14-alpha`
 * (`sync/OpCodec.kt` and `sync/Bundle.kt`), with the operation type codes written out as they were then (1 to 4);
 * the byte helpers ([ByteReader], [OpCodec.readHlc], [OpCodec.readVector]) are unchanged since. Never update this
 * file to follow the codec: it is the old reader.
 */
object V014Codec {
    fun readOp(r: ByteReader): Op {
        val hlc = OpCodec.readHlc(r)
        val type = r.byte()
        val kind = EntityKind.of(r.byte())
        val id = r.id()
        val body: OpBody = when (type) {
            1 -> when (kind) {
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
            2 -> SetField(kind, id, r.string(), r.nstring())
            3 -> {
                if (kind != EntityKind.TRACK) throw SyncFormatException("append to a $kind")
                val segment = r.uvarint()
                if (segment > Int.MAX_VALUE) throw SyncFormatException("segment $segment out of range")
                AppendPoints(id, segment.toInt(), readPoints(r))
            }
            4 -> Delete(kind, id)
            else -> throw SyncFormatException("unknown operation type $type")
        }
        return Op(hlc, body)
    }

    private const val HAS_ELE = 1
    private const val HAS_TIME = 2
    private const val HAS_EXT = 4

    private fun readPoints(r: ByteReader): List<TrackPoint> {
        val n = r.count()
        return List(n) {
            val f = r.byte()
            val lat = r.double(); val lon = r.double()
            TrackPoint(lat, lon, if (f and HAS_ELE != 0) r.double() else null, if (f and HAS_TIME != 0) r.svarint() else null, if (f and HAS_EXT != 0) r.string() else null)
        }
    }

    /** `Bundle.decode` of v0.1.14-alpha. */
    fun decodeBundle(bytes: ByteArray): Bundle = SyncFormatException.guard {
        if (!Bundle.isRaw(bytes)) throw SyncFormatException("not a sync bundle (no DSYNC header)")
        val r = ByteReader(bytes, 5)
        val version = r.byte()
        if (version != 1 && version != 2) throw SyncFormatException("bundle format version $version; this build reads 1 and 2")
        val from = r.id()
        val base = OpCodec.readVector(r)
        val vector = OpCodec.readVector(r)
        val more = if (version == 2) (r.uvarint() and 1L) != 0L else false
        val n = r.count()
        val ops = ArrayList<Op>(minOf(n, 1 shl 16))
        repeat(n) {
            val len = r.count()
            val sub = ByteReader(bytes, r.position, r.position + len)
            ops += readOp(sub).also { sub.end() }
            r.skip(len)
        }
        r.end()
        Bundle(from, base, vector, ops, more)
    }
}
