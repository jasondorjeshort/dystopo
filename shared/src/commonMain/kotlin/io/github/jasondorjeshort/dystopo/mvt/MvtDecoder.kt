package io.github.jasondorjeshort.dystopo.mvt

/**
 * Decoder for the Mapbox Vector Tile format (vector_tile.proto 2.1), written from the spec:
 * a protobuf `Tile` with repeated `Layer` messages, each holding key and value tables and
 * `Feature`s whose geometry is a stream of MoveTo/LineTo/ClosePath commands with zigzag deltas.
 *
 * Lenient where the spec allows it: unknown fields are skipped, repeated fields may be packed or
 * not, rings closed with an explicit duplicate of the first point are accepted, degenerate rings
 * (zero area) and single-point lines are dropped. Structural errors (unknown commands, LineTo
 * without a MoveTo, truncated messages, tag indices out of range) throw [IllegalArgumentException].
 */
object MvtDecoder {
    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val CLOSE_PATH = 7

    fun decode(bytes: ByteArray): VectorTile {
        val r = ProtoReader(bytes)
        val layers = ArrayList<Layer>()
        while (r.hasMore) {
            val key = r.readKey()
            val wire = ProtoReader.wireType(key)
            if (ProtoReader.fieldNumber(key) == 3 && wire == 2) layers += readLayer(r.readMessage()) else r.skip(wire)
        }
        return VectorTile(layers)
    }

    private class RawFeature(val id: Long?, val tags: IntArray, val type: Int, val commands: IntArray)

    private fun readLayer(r: ProtoReader): Layer {
        var name = ""
        var version = 1
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Value>()
        val raws = ArrayList<RawFeature>()
        while (r.hasMore) {
            val key = r.readKey()
            val wire = ProtoReader.wireType(key)
            when (ProtoReader.fieldNumber(key)) {
                1 -> if (wire == 2) name = r.readString() else r.skip(wire)
                2 -> if (wire == 2) raws += readFeature(r.readMessage()) else r.skip(wire)
                3 -> if (wire == 2) keys += r.readString() else r.skip(wire)
                4 -> if (wire == 2) values += readValue(r.readMessage()) else r.skip(wire)
                5 -> if (wire == 0) extent = r.readVarint().toInt() else r.skip(wire)
                15 -> if (wire == 0) version = r.readVarint().toInt() else r.skip(wire)
                else -> r.skip(wire)
            }
        }
        // Key/value tables may come after the features in the stream, so resolve tags only now.
        val features = raws.map { raw ->
            Feature(raw.id, properties(raw.tags, keys, values, name), decodeGeometry(GeometryType.fromCode(raw.type), raw.commands))
        }
        return Layer(name, features, extent, version)
    }

    private fun readFeature(r: ProtoReader): RawFeature {
        var id: Long? = null
        var type = 0
        val tags = IntVec(8)
        val commands = IntVec(32)
        while (r.hasMore) {
            val key = r.readKey()
            val wire = ProtoReader.wireType(key)
            when (ProtoReader.fieldNumber(key)) {
                1 -> if (wire == 0) id = r.readVarint() else r.skip(wire)
                2 -> if (wire == 0 || wire == 2) r.readUint32s(wire, tags) else r.skip(wire)
                3 -> if (wire == 0) type = r.readVarint().toInt() else r.skip(wire)
                4 -> if (wire == 0 || wire == 2) r.readUint32s(wire, commands) else r.skip(wire)
                else -> r.skip(wire)
            }
        }
        return RawFeature(id, tags.toIntArray(), type, commands.toIntArray())
    }

    private fun readValue(r: ProtoReader): Value {
        var value: Value? = null
        while (r.hasMore) {
            val key = r.readKey()
            val wire = ProtoReader.wireType(key)
            value = when (ProtoReader.fieldNumber(key)) {
                1 -> if (wire == 2) Value.StringValue(r.readString()) else null
                2 -> if (wire == 5) Value.FloatValue(Float.fromBits(r.readFixed32())) else null
                3 -> if (wire == 1) Value.DoubleValue(Double.fromBits(r.readFixed64())) else null
                4 -> if (wire == 0) Value.IntValue(r.readVarint()) else null
                5 -> if (wire == 0) Value.UintValue(r.readVarint()) else null
                6 -> if (wire == 0) Value.SintValue(ProtoReader.zigzagDecode(r.readVarint())) else null
                7 -> if (wire == 0) Value.BoolValue(r.readVarint() != 0L) else null
                else -> null
            } ?: run { r.skip(wire); value }
        }
        return value ?: throw IllegalArgumentException("value with no field set")
    }

    private fun properties(tags: IntArray, keys: List<String>, values: List<Value>, layer: String): Map<String, Value> {
        if (tags.isEmpty()) return emptyMap()
        if (tags.size % 2 != 0) throw IllegalArgumentException("layer '$layer': odd number of tag integers")
        val out = LinkedHashMap<String, Value>(tags.size / 2)
        for (i in tags.indices step 2) {
            val k = tags[i]
            val v = tags[i + 1]
            if (k < 0 || k >= keys.size) throw IllegalArgumentException("layer '$layer': key index $k out of range (${keys.size} keys)")
            if (v < 0 || v >= values.size) throw IllegalArgumentException("layer '$layer': value index $v out of range (${values.size} values)")
            out[keys[k]] = values[v]
        }
        return out
    }

    private class Path(first: TilePoint) {
        val points = ArrayList<TilePoint>().apply { add(first) }
        var closed = false
    }

    /** Runs the command stream once into paths, then interprets them according to [type]. */
    internal fun decodeGeometry(type: GeometryType, commands: IntArray): Geometry {
        if (type == GeometryType.UNKNOWN) return Geometry.Unknown(commands.toList())
        val paths = ArrayList<Path>()
        var current: Path? = null
        var x = 0
        var y = 0
        var i = 0
        while (i < commands.size) {
            val cmd = commands[i++]
            val id = cmd and 7
            val count = cmd ushr 3
            when (id) {
                MOVE_TO, LINE_TO -> {
                    if (i + 2 * count > commands.size) throw IllegalArgumentException("geometry command stream truncated")
                    for (k in 0 until count) {
                        x += ProtoReader.zigzagDecode(commands[i++])
                        y += ProtoReader.zigzagDecode(commands[i++])
                        val p = TilePoint(x, y)
                        if (id == MOVE_TO) {
                            current = Path(p)
                            paths += current
                        } else {
                            (current ?: throw IllegalArgumentException("LineTo before any MoveTo")).points += p
                        }
                    }
                }
                CLOSE_PATH -> {
                    val path = current ?: throw IllegalArgumentException("ClosePath before any MoveTo")
                    path.closed = true
                }
                else -> throw IllegalArgumentException("unknown geometry command $id")
            }
        }
        return when (type) {
            GeometryType.POINT -> Geometry.Points(paths.flatMap { it.points })
            GeometryType.LINESTRING -> Geometry.Lines(paths.filter { it.points.size >= 2 }.map { it.points })
            GeometryType.POLYGON -> Geometry.Polygons(assemblePolygons(paths))
            GeometryType.UNKNOWN -> error("unreachable")
        }
    }

    /**
     * Groups rings into polygons by winding, per the spec: a ring with positive area starts a new
     * polygon as its exterior; a negative-area ring is a hole in the polygon before it. A hole with
     * no preceding exterior (invalid tile) is kept as a polygon of its own rather than dropped.
     */
    private fun assemblePolygons(paths: List<Path>): List<Polygon> {
        val out = ArrayList<Polygon>()
        var exterior: List<TilePoint>? = null
        var holes = ArrayList<List<TilePoint>>()
        fun flush() {
            exterior?.let { out += Polygon(it, holes) }
            exterior = null
            holes = ArrayList()
        }
        for (path in paths) {
            val ring = path.points
            if (ring.size > 3 && ring.first() == ring.last()) ring.removeAt(ring.size - 1)
            if (ring.size < 3) continue
            val area2 = signedDoubleArea(ring)
            when {
                area2 > 0 -> { flush(); exterior = ring }
                area2 < 0 -> if (exterior != null) holes += ring else { flush(); exterior = ring }
                else -> {} // degenerate ring: dropped
            }
        }
        flush()
        return out
    }
}
