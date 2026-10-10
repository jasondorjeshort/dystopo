package io.github.jasondorjeshort.dystopo.style

import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.Polygon
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Scene building on synthetic tiles that went through the MVT encoder and decoder. */
class OsmSceneTest {
    private fun p(x: Int, y: Int) = TilePoint(x, y)
    private fun s(v: String): Value = Value.StringValue(v)
    private fun line(props: Map<String, Value>, vararg xy: Int) =
        Feature(null, props, Geometry.Lines(listOf(List(xy.size / 2) { p(xy[2 * it], xy[2 * it + 1]) })))
    private fun point(props: Map<String, Value>, x: Int, y: Int) = Feature(null, props, Geometry.Points(listOf(p(x, y))))
    private fun box(props: Map<String, Value>, x0: Int, y0: Int, x1: Int, y1: Int) =
        Feature(null, props, Geometry.Polygons(listOf(Polygon(listOf(p(x0, y0), p(x1, y0), p(x1, y1), p(x0, y1))))))

    /** Encodes and decodes, so the test sees what a real archive would give. */
    private fun tile(z: Int, x: Int, y: Int, vararg layers: Pair<String, List<Feature>>) =
        SourceTile(TileId(z, x, y), VectorTile.decode(VectorTile(layers.map { (name, f) -> Layer(name, f) }).encode()))

    private val trail = mapOf("class" to s("path"), "subclass" to s("path"))
    private val peak = mapOf("class" to s("peak"), "name" to s("Test Peak"), "ele" to Value.IntValue(3000))

    /** Two side-by-side z14 tiles, each with the other's geometry in its 64-unit buffer, like Planetiler output. */
    private fun seamTiles(): List<SourceTile> = listOf(
        tile(
            14, 100, 200,
            "transportation" to listOf(line(trail, 2048, 2048, 4096, 2048, 4160, 2048)),
            "mountain_peak" to listOf(point(peak, 4086, 1000)),
            "water" to listOf(box(mapOf("class" to s("lake")), 3000, 0, 4160, 1000)),
        ),
        tile(
            14, 101, 200,
            "transportation" to listOf(line(trail, -64, 2048, 0, 2048, 2048, 2048)),
            "mountain_peak" to listOf(point(peak, -10, 1000)),
            "water" to listOf(box(mapOf("class" to s("lake")), -64, 0, 1000, 1000)),
        ),
    )

    private val seamWindow = SceneWindow(14, 100 * 256, 200 * 256, 512, 256)

    private fun length(pts: DoubleArray) = OsmScene.length(pts)

    @Test
    fun noDoubledGeometryAtTileSeams() {
        val scene = OsmScene.build(DefaultOsmStyle.style, seamTiles(), seamWindow)
        val trailLayer = scene.layers.filterIsInstance<SceneLayer.Line>().single { it.style.id == "trail" }
        // Clipped to each tile's own square and joined: one line, exactly from x=128 to x=384.
        val merged = trailLayer.lines.single()
        assertEquals(256.0, length(merged), 1e-9)
        val xs = (0 until merged.size / 2).map { merged[2 * it] }.sorted()
        assertEquals(128.0, xs.first(), 1e-9)
        assertEquals(384.0, xs.last(), 1e-9)
        assertTrue((0 until merged.size / 2).all { merged[2 * it + 1] == 128.0 })

        // The peak sits in tile A's square; its buffered copy in tile B is dropped.
        val peaks = scene.labels.filter { it.layer.id == "peaks" }
        assertEquals(1, peaks.size)
        assertEquals(listOf("Test Peak", "9843 ft"), OsmScene.build(DefaultOsmStyle.style, seamTiles(), seamWindow, "ft").labels.single { it.layer.id == "peaks" }.lines)
        assertEquals(4086 / 16.0, peaks[0].x, 1e-9)

        // Water: the two clipped rings cover the lake once (no overlap in the buffer).
        val water = scene.layers.filterIsInstance<SceneLayer.Fill>().single { it.style.id == "water" }
        val area = water.rings.sumOf { abs(signedArea(it)) }
        assertEquals((4096 - 3000 + 1000) / 16.0 * (1000 / 16.0), area, 1e-6)
        // The lake outline is stroked without the clip edges along the tile seam.
        val edge = scene.layers.filterIsInstance<SceneLayer.Line>().single { it.style.id == "water-edge" }
        val seamX = 256.0
        for (l in edge.lines) for (i in 1 until l.size / 2) {
            val vertical = l[2 * i] == seamX && l[2 * i - 2] == seamX
            assertTrue(!vertical, "no outline drawn along the seam")
        }
    }

    @Test
    fun overzoomScalesParentTile() {
        val t = tile(14, 100, 200, "transportation" to listOf(line(trail, 0, 2048, 4096, 2048)))
        // z15 window exactly covering the z14 tile: everything doubles.
        val full = OsmScene.build(DefaultOsmStyle.style, listOf(t), SceneWindow(15, 100 * 512, 200 * 512, 512, 512))
        val l = full.layers.filterIsInstance<SceneLayer.Line>().single { it.style.id == "trail" }.lines.single()
        assertEquals(listOf(0.0, 256.0, 512.0, 256.0), l.toList())
        // A window over the tile's right half: the line is cut at the image margin (16 px).
        val half = OsmScene.build(DefaultOsmStyle.style, listOf(t), SceneWindow(15, 100 * 512 + 256, 200 * 512, 256, 512))
        val h = half.layers.filterIsInstance<SceneLayer.Line>().single { it.style.id == "trail" }.lines.single()
        assertEquals(listOf(-16.0, 256.0, 256.0, 256.0), h.toList())
    }

    @Test
    fun openMapTilesClassification() {
        fun tr(vararg kv: Pair<String, String>) = kv.associate { (k, v) -> k to s(v) }
        val t = tile(
            14, 100, 200,
            "transportation" to listOf(
                line(tr("class" to "path", "subclass" to "path"), 100, 100, 900, 100),
                line(tr("class" to "path", "subclass" to "footway"), 100, 300, 900, 300),
                line(tr("class" to "path", "subclass" to "steps"), 100, 500, 900, 500),
                line(tr("class" to "path", "subclass" to "footway", "surface" to "paved"), 100, 700, 900, 700),
                line(tr("class" to "track"), 100, 900, 900, 900),
                line(tr("class" to "primary"), 100, 1100, 900, 1100),
                line(tr("class" to "tertiary", "surface" to "unpaved"), 100, 1300, 900, 1300),
                line(tr("class" to "path", "subclass" to "path", "sac_scale" to "alpine_hiking"), 100, 1500, 900, 1500),
            ),
            "landcover" to listOf(
                box(tr("class" to "ice", "subclass" to "glacier"), 0, 0, 100, 100),
                box(tr("class" to "wood"), 0, 0, 100, 100),
                box(tr("class" to "grass"), 0, 0, 100, 100),
            ),
            "waterway" to listOf(
                line(mapOf("class" to s("stream"), "intermittent" to Value.IntValue(0)), 0, 2000, 4000, 2000),
                line(mapOf("class" to s("stream"), "intermittent" to Value.IntValue(1)), 0, 2100, 4000, 2100),
                line(mapOf("class" to s("river"), "brunnel" to s("tunnel")), 0, 2200, 4000, 2200),
            ),
        )
        val scene = OsmScene.build(DefaultOsmStyle.style, listOf(t), SceneWindow(15, 100 * 512, 200 * 512, 512, 512))
        fun n(id: String) = scene.counts[id]?.features ?: 0
        assertEquals(1, n("trail"))
        assertEquals(1, n("footway"))
        assertEquals(1, n("steps"))
        assertEquals(1, n("path-paved"))
        assertEquals(1, n("track"))
        assertEquals(1, n("road-major"))
        assertEquals(1, n("road-unpaved"))
        assertEquals(0, n("road-minor"), "unpaved tertiary is not drawn as paved")
        assertEquals(1, n("trail-difficult"), "sac_scale alpine_hiking")
        assertEquals(1, n("glacier"))
        assertEquals(1, n("wood"))
        assertEquals(1, n("stream"))
        assertEquals(1, n("stream-intermittent"))
        assertEquals(0, n("river"), "tunnels are not drawn")
    }

    @Test
    fun zoomRangesHideLayers() {
        val t = tile(11, 10, 20, "transportation" to listOf(line(mapOf("class" to s("path"), "subclass" to s("steps")), 0, 0, 4096, 4096)))
        val scene = OsmScene.build(DefaultOsmStyle.style, listOf(t), SceneWindow(11, 10 * 256, 20 * 256, 256, 256))
        assertTrue(scene.layers.none { it.style.id == "steps" }, "steps start at z14")
        assertTrue("steps" !in scene.counts)
    }

    @Test
    fun lineLabelsMergeAcrossTilesAndFollowTheLine() {
        val name = mapOf("class" to s("path"), "name" to s("Long Trail"))
        val tiles = listOf(
            tile(14, 100, 200, "transportation_name" to listOf(line(name, 0, 2048, 4096, 2048, 4160, 2048))),
            tile(14, 101, 200, "transportation_name" to listOf(line(name, -64, 2048, 0, 2048, 4096, 2048))),
        )
        val scene = OsmScene.build(DefaultOsmStyle.style, tiles, seamWindow)
        val req = scene.labels.single { it.layer.id == "trail-names" }
        assertEquals(512.0, length(assertNotNull(req.path)), 1e-9)
        val placer = LabelPlacer(FakeMeasurer, 512, 256, 14.0, 1.4)
        val placed = placer.placeAll(scene.labels)
        val label = placed.single { it.style.id == "trail-names" }
        val glyphs = assertNotNull(label.glyphs)
        assertEquals("Long Trail".length, glyphs.size)
        assertTrue(glyphs.all { abs(it.y - 128.0) < 1e-6 && abs(it.angle) < 1e-9 }, "on the line, level")
        assertTrue(glyphs.zipWithNext().all { (a, b) -> b.x > a.x }, "left to right")
    }

    private fun signedArea(r: DoubleArray): Double {
        var sum = 0.0
        val n = r.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += r[2 * i] * r[2 * j + 1] - r[2 * j] * r[2 * i + 1]
        }
        return sum / 2
    }
}
