package io.github.jasondorjeshort.dystopo.mvt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MvtTest {
    private fun p(x: Int, y: Int) = TilePoint(x, y)
    private fun pts(vararg xy: Int) = List(xy.size / 2) { p(xy[2 * it], xy[2 * it + 1]) }

    /** Clockwise on screen (y down) = positive area = exterior per spec. */
    private val squareCw = pts(0, 0, 10, 0, 10, 10, 0, 10)
    private val holeCcw = pts(2, 2, 2, 8, 8, 8, 8, 2)

    private fun roundTrip(tile: VectorTile): VectorTile {
        val bytes = MvtEncoder.encode(tile)
        val back = MvtDecoder.decode(bytes)
        assertEquals(tile, back)
        return back
    }

    private fun feature(geometry: Geometry, id: Long? = null, props: Map<String, Value> = emptyMap()) = Feature(id, props, geometry)

    @Test
    fun pointsRoundTrip() {
        val tile = VectorTile(listOf(Layer("poi", listOf(
            feature(Geometry.Points(listOf(p(25, 17))), id = 1),
            feature(Geometry.Points(pts(5, 7, 3, 2, -100, 5000)), id = 2),
            feature(Geometry.Points(listOf(p(0, 0)))),
        ))))
        val back = roundTrip(tile)
        assertEquals(GeometryType.POINT, back.layers[0].features[1].type)
        assertEquals(3, back.layers[0].features[1].geometry.vertexCount)
        assertNull(back.layers[0].features[2].id)
    }

    @Test
    fun linesRoundTrip() {
        val tile = VectorTile(listOf(Layer("trails", listOf(
            feature(Geometry.Lines(listOf(pts(2, 2, 2, 10, 10, 10)))),
            feature(Geometry.Lines(listOf(pts(0, 0, 4096, 4096), pts(-64, 100, 7, 7, 8, 8, 9, 9)))),
        ), extent = 4096, version = 2)))
        roundTrip(tile)
    }

    @Test
    fun polygonsWithHolesAndMultipolygonsRoundTrip() {
        val a = Polygon(squareCw, listOf(holeCcw))
        val b = Polygon(pts(20, 20, 30, 20, 30, 30, 20, 30))
        val c = Polygon(pts(100, 100, 300, 100, 300, 300, 100, 300), listOf(pts(110, 110, 110, 150, 150, 150, 150, 110), pts(200, 200, 200, 250, 250, 250, 250, 200)))
        val tile = VectorTile(listOf(Layer("water", listOf(
            feature(Geometry.Polygons(listOf(a))),
            feature(Geometry.Polygons(listOf(a, b))),
            feature(Geometry.Polygons(listOf(b, c, a))),
        ))))
        val back = roundTrip(tile)
        val multi = back.layers[0].features[2].geometry as Geometry.Polygons
        assertEquals(3, multi.polygons.size)
        assertEquals(2, multi.polygons[1].interiors.size)
    }

    @Test
    fun encoderNormalizesRingWinding() {
        // Exterior given counterclockwise and hole clockwise: the encoder must reverse both.
        val wrong = Polygon(squareCw.asReversed(), listOf(holeCcw.asReversed()))
        val bytes = MvtEncoder.encode(VectorTile(listOf(Layer("l", listOf(feature(Geometry.Polygons(listOf(wrong))))))))
        val poly = (MvtDecoder.decode(bytes).layers[0].features[0].geometry as Geometry.Polygons).polygons.single()
        assertTrue(signedDoubleArea(poly.exterior) > 0)
        assertTrue(signedDoubleArea(poly.interiors.single()) < 0)
        assertEquals(squareCw.toSet(), poly.exterior.toSet())
        assertEquals(holeCcw.toSet(), poly.interiors.single().toSet())
    }

    @Test
    fun propertiesOfEveryTypeRoundTrip() {
        val props = linkedMapOf<String, Value>(
            "name" to Value.StringValue("Lac Blanc"),
            "ele_f" to Value.FloatValue(2352.5f),
            "ele_d" to Value.DoubleValue(2352.123456789),
            "int" to Value.IntValue(-42),
            "uint" to Value.UintValue(-1), // 2^64 - 1
            "sint" to Value.SintValue(-123456789012L),
            "bool_t" to Value.BoolValue(true),
            "bool_f" to Value.BoolValue(false),
            "empty" to Value.StringValue(""),
            "unicode" to Value.StringValue("Mont Blanc / Monte Bianco — 4 808 m ⛰"),
        )
        val tile = VectorTile(listOf(Layer("places", listOf(
            feature(Geometry.Points(listOf(p(1, 1))), id = Long.MAX_VALUE, props = props),
            feature(Geometry.Points(listOf(p(2, 2))), id = 0, props = mapOf("name" to Value.StringValue("Lac Blanc"), "int" to Value.IntValue(-42))),
            feature(Geometry.Points(listOf(p(3, 3)))),
        ))))
        val back = roundTrip(tile)
        val layer = back.layers[0]
        assertEquals(props.keys.toList(), layer.keys)
        assertEquals(emptyMap(), layer.features[2].properties)
        val f = layer.features[0]
        assertEquals("18446744073709551615", f.properties["uint"]!!.asText())
        assertEquals(-42.0, f.properties["int"]!!.asDouble())
        assertNull(f.properties["name"]!!.asDouble())
        assertEquals("true", f.properties["bool_t"]!!.asText())

        // Keys and values are written once per layer: each distinct string appears once in the bytes.
        val bytes = MvtEncoder.encode(tile)
        assertEquals(1, countOccurrences(bytes, "Lac Blanc".encodeToByteArray()))
        assertEquals(1, countOccurrences(bytes, "name".encodeToByteArray()))
    }

    private fun countOccurrences(haystack: ByteArray, needle: ByteArray): Int {
        var n = 0
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            n++
        }
        return n
    }

    @Test
    fun severalLayersRoundTrip() {
        val tile = VectorTile(listOf(
            Layer("a", listOf(feature(Geometry.Points(listOf(p(1, 2))))), extent = 256, version = 1),
            Layer("b", emptyList(), extent = 8192),
            Layer("c", listOf(feature(Geometry.Lines(listOf(pts(0, 0, 1, 1))), props = mapOf("k" to Value.BoolValue(true))))),
        ))
        val back = roundTrip(tile)
        assertEquals(listOf("a", "b", "c"), back.layers.map { it.name })
        assertEquals(256, back.layer("a")!!.extent)
        assertEquals(1, back.layer("a")!!.version)
        assertEquals(8192, back.layer("b")!!.extent)
    }

    @Test
    fun unknownGeometryTypeKeepsRawCommands() {
        val g = Geometry.Unknown(listOf(9, 50, 34))
        val back = roundTrip(VectorTile(listOf(Layer("x", listOf(feature(g))))))
        assertEquals(GeometryType.UNKNOWN, back.layers[0].features[0].type)
    }

    // --- Decoding command streams given as worked examples in the MVT 2.1 specification ---

    @Test
    fun decodesSpecGeometryExamples() {
        assertEquals(Geometry.Points(listOf(p(25, 17))), MvtDecoder.decodeGeometry(GeometryType.POINT, intArrayOf(9, 50, 34)))
        assertEquals(Geometry.Points(pts(5, 7, 3, 2)), MvtDecoder.decodeGeometry(GeometryType.POINT, intArrayOf(17, 10, 14, 3, 9)))
        assertEquals(
            Geometry.Lines(listOf(pts(2, 2, 2, 10, 10, 10))),
            MvtDecoder.decodeGeometry(GeometryType.LINESTRING, intArrayOf(9, 4, 4, 18, 0, 16, 16, 0)),
        )
        assertEquals(
            Geometry.Lines(listOf(pts(2, 2, 2, 10, 10, 10), pts(1, 1, 3, 5))),
            MvtDecoder.decodeGeometry(GeometryType.LINESTRING, intArrayOf(9, 4, 4, 18, 0, 16, 16, 0, 9, 17, 17, 10, 4, 8)),
        )
        assertEquals(
            Geometry.Polygons(listOf(Polygon(pts(3, 6, 8, 12, 20, 34)))),
            MvtDecoder.decodeGeometry(GeometryType.POLYGON, intArrayOf(9, 6, 12, 18, 10, 12, 24, 44, 15)),
        )
        // Two polygons, the second with a hole: winding decides which ring belongs where.
        val multi = MvtDecoder.decodeGeometry(
            GeometryType.POLYGON,
            intArrayOf(9, 0, 0, 26, 20, 0, 0, 20, 19, 0, 15, 9, 22, 2, 26, 18, 0, 0, 18, 17, 0, 15, 9, 4, 13, 26, 0, 8, 8, 0, 0, 7, 15),
        )
        assertEquals(
            Geometry.Polygons(listOf(
                Polygon(pts(0, 0, 10, 0, 10, 10, 0, 10)),
                Polygon(pts(11, 11, 20, 11, 20, 20, 11, 20), listOf(pts(13, 13, 13, 17, 17, 17, 17, 13))),
            )),
            multi,
        )
    }

    @Test
    fun encodesSpecGeometryExamples() {
        assertEquals(listOf(9, 50, 34), MvtEncoder.encodeGeometry(Geometry.Points(listOf(p(25, 17)))).toIntArray().toList())
        assertEquals(listOf(17, 10, 14, 3, 9), MvtEncoder.encodeGeometry(Geometry.Points(pts(5, 7, 3, 2))).toIntArray().toList())
        assertEquals(
            listOf(9, 4, 4, 18, 0, 16, 16, 0),
            MvtEncoder.encodeGeometry(Geometry.Lines(listOf(pts(2, 2, 2, 10, 10, 10)))).toIntArray().toList(),
        )
        assertEquals(
            listOf(9, 6, 12, 18, 10, 12, 24, 44, 15),
            MvtEncoder.encodeGeometry(Geometry.Polygons(listOf(Polygon(pts(3, 6, 8, 12, 20, 34))))).toIntArray().toList(),
        )
    }

    @Test
    fun lenientRingHandling() {
        // Explicitly closed ring (last point repeats the first) loses the duplicate.
        val closed = MvtDecoder.decodeGeometry(GeometryType.POLYGON, intArrayOf(9, 0, 0, 34, 20, 0, 0, 20, 19, 0, 0, 19, 15))
        assertEquals(Geometry.Polygons(listOf(Polygon(squareCw))), closed)
        // Zero-area ring is dropped; a hole without an exterior stands alone.
        // (cursor is at (20,0) after the degenerate ring, so the hole's MoveTo is a (-18, 2) delta)
        val odd = MvtDecoder.decodeGeometry(GeometryType.POLYGON, intArrayOf(9, 0, 0, 18, 20, 0, 20, 0, 15, 9, 35, 4, 26, 0, 12, 12, 0, 0, 11, 15))
        assertEquals(Geometry.Polygons(listOf(Polygon(holeCcw))), odd)
        // Single-point "line" is dropped.
        assertEquals(Geometry.Lines(emptyList()), MvtDecoder.decodeGeometry(GeometryType.LINESTRING, intArrayOf(9, 2, 2)))
    }

    @Test
    fun malformedGeometryThrows() {
        assertFailsWith<IllegalArgumentException> { MvtDecoder.decodeGeometry(GeometryType.LINESTRING, intArrayOf(10, 2, 2)) }
        assertFailsWith<IllegalArgumentException> { MvtDecoder.decodeGeometry(GeometryType.POLYGON, intArrayOf(15)) }
        assertFailsWith<IllegalArgumentException> { MvtDecoder.decodeGeometry(GeometryType.POINT, intArrayOf(17, 2, 2)) }
        assertFailsWith<IllegalArgumentException> { MvtDecoder.decodeGeometry(GeometryType.POINT, intArrayOf(11, 2, 2)) }
    }

    // --- Hand-assembled protobuf, exercising paths the encoder doesn't produce ---

    @Test
    fun decodesHandBuiltTileWithTablesAfterFeaturesAndUnknownFields() {
        val value = ProtoWriter().apply { varintField(4, 99); varintField(1000, 5) } // int_value plus an unknown field
        val feature = ProtoWriter().apply {
            varintField(3, 1)                        // type POINT
            varintField(4, 9); varintField(4, 50); varintField(4, 34) // geometry, expanded instead of packed
            varintField(2, 0); varintField(2, 0)     // tags, expanded
            fixed64Field(77, 1L)                      // unknown field
        }
        val layer = ProtoWriter().apply {
            stringField(1, "late")
            messageField(2, feature)
            stringField(3, "k")                       // key table after the feature
            messageField(4, value)
            bytesField(200, byteArrayOf(1, 2, 3))     // unknown field
            // no version, no extent: defaults 1 and 4096
        }
        val tile = ProtoWriter().apply {
            varintField(9, 1)                          // unknown top-level field
            messageField(3, layer)
        }.toByteArray()
        val decoded = VectorTile.decode(tile)
        val l = decoded.layers.single()
        assertEquals("late", l.name)
        assertEquals(1, l.version)
        assertEquals(4096, l.extent)
        val f = l.features.single()
        assertEquals(Geometry.Points(listOf(p(25, 17))), f.geometry)
        assertEquals(mapOf("k" to Value.IntValue(99)), f.properties)
    }

    @Test
    fun badTagIndexThrows() {
        val feature = ProtoWriter().apply { varintField(3, 1); packedUint32Field(4, intArrayOf(9, 0, 0)); packedUint32Field(2, intArrayOf(0, 3)) }
        val layer = ProtoWriter().apply { stringField(1, "l"); stringField(3, "k"); messageField(4, ProtoWriter().apply { varintField(7, 1) }); messageField(2, feature) }
        val bytes = ProtoWriter().apply { messageField(3, layer) }.toByteArray()
        assertFailsWith<IllegalArgumentException> { VectorTile.decode(bytes) }
    }

    @Test
    fun emptyTileAndLayer() {
        assertEquals(VectorTile(emptyList()), VectorTile.decode(ByteArray(0)))
        assertEquals(VectorTile(listOf(Layer("empty", emptyList()))), roundTrip(VectorTile(listOf(Layer("empty", emptyList())))))
    }

    @Test
    fun manyFeaturesRoundTrip() {
        var seed = 12345
        fun rnd(bound: Int): Int { seed = seed * 1103515245 + 12345; return ((seed ushr 8) % bound + bound) % bound }
        val features = ArrayList<Feature>()
        for (i in 0 until 2000) {
            val x = rnd(4096); val y = rnd(4096); val w = 1 + rnd(200); val h = 1 + rnd(200)
            val g: Geometry = when (i % 3) {
                0 -> Geometry.Points(List(1 + rnd(4)) { p(rnd(4096), rnd(4096)) })
                1 -> Geometry.Lines(listOf(List(2 + rnd(30)) { p(rnd(4096) - 128, rnd(4096) - 128) }))
                else -> Geometry.Polygons(listOf(Polygon(pts(x, y, x + w, y, x + w, y + h, x, y + h))))
            }
            features += feature(g, id = i.toLong(), props = mapOf("class" to Value.StringValue("c${i % 7}"), "rank" to Value.IntValue((i % 13).toLong())))
        }
        val back = roundTrip(VectorTile(listOf(Layer("many", features))))
        assertEquals(2000, back.layers[0].features.size)
        assertEquals(listOf("class", "rank"), back.layers[0].keys)
    }
}
