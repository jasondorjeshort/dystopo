package io.github.jasondorjeshort.dystopo.mvt

/**
 * Minimal Mapbox Vector Tile encoder, the inverse of [MvtDecoder]. Good enough for test
 * fixtures and small tiles: keys and values are deduplicated per layer in first-seen order,
 * geometry is written with one MoveTo per point run or ring, and ring winding is normalized to
 * the spec (exterior clockwise on screen, holes counterclockwise), reversing rings as needed.
 */
object MvtEncoder {
    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val CLOSE_PATH = 7

    fun encode(tile: VectorTile): ByteArray {
        val w = ProtoWriter(4096)
        for (layer in tile.layers) w.messageField(3, encodeLayer(layer))
        return w.toByteArray()
    }

    private fun encodeLayer(layer: Layer): ProtoWriter {
        val keys = LinkedHashMap<String, Int>()
        val values = LinkedHashMap<Value, Int>()
        // Features are encoded first to fill the key/value tables, but written after them so the
        // layer has the conventional order: header, tables, features.
        val features = ProtoWriter(1024)
        for (f in layer.features) features.messageField(2, encodeFeature(f, keys, values))

        val w = ProtoWriter(features.size + 256)
        w.varintField(15, layer.version.toLong())
        w.stringField(1, layer.name)
        w.varintField(5, layer.extent.toLong())
        for (k in keys.keys) w.stringField(3, k)
        for (v in values.keys) w.messageField(4, encodeValue(v))
        w.raw(features)
        return w
    }

    private fun encodeFeature(f: Feature, keys: MutableMap<String, Int>, values: MutableMap<Value, Int>): ProtoWriter {
        val w = ProtoWriter(64)
        f.id?.let { w.varintField(1, it) }
        if (f.properties.isNotEmpty()) {
            val tags = IntArray(f.properties.size * 2)
            var i = 0
            for ((k, v) in f.properties) {
                tags[i++] = keys.getOrPut(k) { keys.size }
                tags[i++] = values.getOrPut(v) { values.size }
            }
            w.packedUint32Field(2, tags)
        }
        w.varintField(3, f.geometry.type.code.toLong())
        val cmds = encodeGeometry(f.geometry)
        w.packedUint32Field(4, cmds.data, cmds.size)
        return w
    }

    private fun encodeValue(v: Value): ProtoWriter {
        val w = ProtoWriter(16)
        when (v) {
            is Value.StringValue -> w.stringField(1, v.value)
            is Value.FloatValue -> w.fixed32Field(2, v.value.toRawBits())
            is Value.DoubleValue -> w.fixed64Field(3, v.value.toRawBits())
            is Value.IntValue -> w.varintField(4, v.value)
            is Value.UintValue -> w.varintField(5, v.value)
            is Value.SintValue -> w.varintField(6, ProtoReader.zigzagEncode(v.value))
            is Value.BoolValue -> w.varintField(7, if (v.value) 1 else 0)
        }
        return w
    }

    private class Cursor(val out: IntVec) {
        var x = 0
        var y = 0

        fun command(id: Int, count: Int) = out.add((count shl 3) or id)

        fun point(p: TilePoint) {
            out.add(ProtoReader.zigzagEncode(p.x - x))
            out.add(ProtoReader.zigzagEncode(p.y - y))
            x = p.x
            y = p.y
        }
    }

    internal fun encodeGeometry(g: Geometry): IntVec {
        val out = IntVec(64)
        val c = Cursor(out)
        when (g) {
            is Geometry.Points -> {
                require(g.points.isNotEmpty()) { "point geometry needs at least one point" }
                c.command(MOVE_TO, g.points.size)
                g.points.forEach { c.point(it) }
            }
            is Geometry.Lines -> for (line in g.lines) {
                require(line.size >= 2) { "linestring needs at least two points" }
                c.command(MOVE_TO, 1)
                c.point(line[0])
                c.command(LINE_TO, line.size - 1)
                for (i in 1 until line.size) c.point(line[i])
            }
            is Geometry.Polygons -> for (poly in g.polygons) {
                ring(c, poly.exterior, exterior = true)
                for (hole in poly.interiors) ring(c, hole, exterior = false)
            }
            is Geometry.Unknown -> g.commands.forEach { out.add(it) }
        }
        return out
    }

    private fun ring(c: Cursor, input: List<TilePoint>, exterior: Boolean) {
        var ring = if (input.size > 3 && input.first() == input.last()) input.subList(0, input.size - 1) else input
        require(ring.size >= 3) { "ring needs at least three distinct points" }
        val area2 = signedDoubleArea(ring)
        require(area2 != 0L) { "ring has zero area" }
        if ((area2 > 0) != exterior) ring = ring.asReversed()
        c.command(MOVE_TO, 1)
        c.point(ring[0])
        c.command(LINE_TO, ring.size - 1)
        for (i in 1 until ring.size) c.point(ring[i])
        c.command(CLOSE_PATH, 1)
    }
}
