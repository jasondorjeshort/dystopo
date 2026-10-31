package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.Polygon
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T179: water along a route over synthetic z14 tiles near Paradise (a public place). The route runs east along the
 * middle row of a tile; streams cross it north to south at known columns (a tile unit is about 0.41 m here).
 */
class WaterAlongTest {
    private val z = 14
    private val tx = floor(WebMercator.lonToX(-121.74, z)).toInt()
    private val ty = floor(WebMercator.latToY(46.79, z)).toInt()

    private fun lon(u: Double, dx: Int = 0) = WebMercator.xToLon(tx + dx + u / 4096, z)
    private fun lat(v: Double) = WebMercator.yToLat(ty + v / 4096, z)
    private fun route(from: Double = 100.0, to: Double = 4000.0, dxTo: Int = 0) =
        listOf(RoutePoint(lat(2048.0), lon(from)), RoutePoint(lat(2048.0), lon(to, dxTo)))

    private fun s(v: String) = Value.StringValue(v)
    private fun vline(x: Int, props: Map<String, Value>) = Feature(null, props, Geometry.Lines(listOf(listOf(TilePoint(x, 0), TilePoint(x, 4096)))))

    private fun tiles(vararg layers: Layer, dx: Int = 0): Map<Pair<Int, Int>, ByteArray> = mapOf((tx + dx to ty) to VectorTile(layers.toList()).encode())
    private fun source(map: Map<Pair<Int, Int>, ByteArray>) = WaterTiles { zz, x, y -> if (zz == z) map[x to y] else null }

    private val waterway = Layer(
        "waterway",
        listOf(
            vline(800, mapOf("class" to s("river"), "name" to s("Test River"))),
            vline(1600, mapOf("class" to s("stream"), "intermittent" to Value.IntValue(1))),
            vline(3000, mapOf("class" to s("stream"), "name" to s("Test Creek"))),
            vline(3600, mapOf("class" to s("stream"), "name" to s("Dry Fork"), "intermittent" to Value.IntValue(1))),
        ),
    )
    private val drainage = Layer(
        "drainage",
        listOf(
            vline(2300, mapOf("class" to s("likely_dry"), "catchment_km2" to Value.DoubleValue(0.3))), // a gully
            vline(2600, mapOf("class" to s("likely_dry"), "catchment_km2" to Value.DoubleValue(0.05))), // under channel size
            vline(3050, mapOf("class" to s("seasonal"), "catchment_km2" to Value.DoubleValue(1.2))), // 20 m from Test Creek
            vline(3640, mapOf("class" to s("likely_running"), "catchment_km2" to Value.DoubleValue(8.0))), // 16 m from Dry Fork
        ),
    )
    private val bridge = Layer(
        "transportation",
        listOf(Feature(null, mapOf("class" to s("path"), "brunnel" to s("bridge")), Geometry.Lines(listOf(listOf(TilePoint(780, 2048), TilePoint(820, 2048)))))),
    )
    private val poi = Layer(
        "outdoor_poi",
        listOf(
            Feature(null, mapOf("class" to s("spring"), "name" to s("Near Spring")), Geometry.Points(listOf(TilePoint(1200, 2048 + 120)))), // ~49 m off
            Feature(null, mapOf("class" to s("spring")), Geometry.Points(listOf(TilePoint(1300, 2048 + 800)))), // ~330 m off
            Feature(null, mapOf("class" to s("hut")), Geometry.Points(listOf(TilePoint(1400, 2048)))),
        ),
    )
    private val lake = Layer(
        "water",
        listOf(
            Feature(
                null, mapOf("class" to s("lake")),
                Geometry.Polygons(listOf(Polygon(listOf(TilePoint(1900, 2048 - 147), TilePoint(2100, 2048 - 147), TilePoint(2100, 2048 - 600), TilePoint(1900, 2048 - 600))))),
            ),
        ),
    )
    private val lakeName = Layer("water_name", listOf(Feature(null, mapOf("class" to s("lake"), "name" to s("Test Lake")), Geometry.Points(listOf(TilePoint(2000, 2048 - 400))))))

    @Test
    fun classesARiverAnIntermittentStreamAGullyAndMergedCrossings() {
        val r = WaterAlong.find(route(), source(tiles(waterway, drainage, bridge, poi, lake, lakeName)), z)
        val streams = r.items.filter { it.kind == WaterKind.STREAM }
        assertEquals(5, streams.size, r.items.joinToString("\n"))

        val river = streams[0]
        assertEquals("Test River", river.name)
        assertEquals(WaterClass.LIKELY_FLOWING, river.waterClass)
        assertEquals(WaterBasis.MAPPED, river.basis)
        assertTrue(river.bridge, "the path crosses the river on a mapped bridge")
        assertFalse(river.merged)
        assertNull(river.catchmentKm2)

        val seasonal = streams[1]
        assertEquals(WaterClass.MAY_RUN_DRY, seasonal.waterClass)
        assertEquals(WaterBasis.MAPPED, seasonal.basis)
        assertTrue(seasonal.intermittent)
        assertFalse(seasonal.bridge)

        val gully = streams[2]
        assertEquals(WaterClass.USUALLY_DRY, gully.waterClass)
        assertEquals("Gully, usually dry", gully.label)
        assertEquals(WaterBasis.TERRAIN, gully.basis)
        assertEquals(0.3, gully.catchmentKm2)
        assertNull(gully.name)

        // Mapped without the intermittent tag, 1.2 km² from the terrain: the terrain's class stands (the map does not raise it).
        val creek = streams[3]
        assertEquals("Test Creek", creek.name)
        assertTrue(creek.merged)
        assertEquals(1.2, creek.catchmentKm2)
        assertEquals(WaterClass.MAY_RUN_DRY, creek.waterClass)
        assertEquals(WaterBasis.TERRAIN, creek.basis)

        // Mapped intermittent over an 8 km² catchment: the tag caps it at "may run dry".
        val fork = streams[4]
        assertEquals("Dry Fork", fork.name)
        assertTrue(fork.merged)
        assertEquals(8.0, fork.catchmentKm2)
        assertEquals(WaterClass.MAY_RUN_DRY, fork.waterClass)
        assertEquals(WaterBasis.MAPPED, fork.basis)

        assertEquals(1, r.stats.droppedDrainage, "the 0.05 km² line is under channel size")
        assertEquals(2, r.stats.mergedDrainage)
        assertTrue(r.stats.drainageLayer)

        val spring = r.items.single { it.kind == WaterKind.SPRING }
        assertEquals("Near Spring", spring.name)
        assertEquals("Mapped spring", spring.label)
        assertTrue(abs(spring.offsetM - 49.0) < 3, "offset ${spring.offsetM}")
        val lakeItem = r.items.single { it.kind == WaterKind.LAKE }
        assertEquals("Test Lake", lakeItem.name)
        assertTrue(abs(lakeItem.offsetM - 60.0) < 3, "offset ${lakeItem.offsetM}")

        // Route order, and alongM matches the column (the route starts at column 100).
        assertEquals(r.items.sortedBy { it.alongM }, r.items)
        val m = river.alongM / (800 - 100)
        assertTrue(abs(gully.alongM - m * (2300 - 100)) < 1.0)
        assertTrue(r.items.none { it.label.contains("reliable", true) || it.label.contains("safe", true) })
    }

    @Test
    fun withoutTheDrainageLayerOnlyMappedWaterIsListed() {
        val r = WaterAlong.find(route(), source(tiles(waterway, poi)), z)
        assertFalse(r.stats.drainageLayer)
        assertEquals(4, r.items.count { it.kind == WaterKind.STREAM })
        assertTrue(r.items.filter { it.kind == WaterKind.STREAM }.all { it.basis == WaterBasis.MAPPED && !it.merged })
        assertEquals(WaterClass.LIKELY_FLOWING, r.items.single { it.name == "Test Creek" }.waterClass)
    }

    @Test
    fun aSeparateDrainageSourceIsReadAndNoneMeansNone() {
        val water = source(tiles(waterway))
        val dem = source(tiles(drainage))
        val both = WaterAlong.find(route(), water, z, drainage = dem)
        assertEquals(2, both.stats.mergedDrainage)
        val none = WaterAlong.find(route(), source(tiles(waterway, drainage)), z, drainage = null)
        assertEquals(0, none.stats.drainageCrossings)
    }

    @Test
    fun aLineInTheBufferOfTheNextTileIsCountedOnce() {
        // A stream 40 units west of the next tile's left edge, drawn in both tiles (the right tile holds it as buffer).
        val inLeft = Layer("waterway", listOf(vline(4056, mapOf("class" to s("stream")))))
        val inRight = Layer("waterway", listOf(vline(-40, mapOf("class" to s("stream")))))
        val map = tiles(inLeft) + tiles(inRight, dx = 1)
        val r = WaterAlong.find(route(from = 3000.0, to = 1000.0, dxTo = 1), source(map), z)
        assertEquals(2, r.stats.tiles)
        assertEquals(1, r.items.size, r.items.joinToString())
    }

    @Test
    fun aStreamBesideTheTrailIsOneCrossing() {
        // A stream zigzagging across the route three times within 20 m.
        val zig = Layer(
            "waterway",
            listOf(Feature(null, mapOf("class" to s("stream"), "name" to s("Zig")), Geometry.Lines(listOf(listOf(
                TilePoint(1000, 1900), TilePoint(1010, 2200), TilePoint(1030, 1900), TilePoint(1050, 2200),
            ))))),
        )
        val r = WaterAlong.find(route(), source(tiles(zig)), z)
        assertEquals(1, r.items.size, r.items.joinToString())
    }

    @Test
    fun theThresholdsClassACatchment() {
        assertNull(WaterAlong.classOf(0.09))
        assertEquals(WaterClass.USUALLY_DRY, WaterAlong.classOf(0.1))
        assertEquals(WaterClass.MAY_RUN_DRY, WaterAlong.classOf(0.5))
        assertEquals(WaterClass.MAY_RUN_DRY, WaterAlong.classOf(1.99))
        assertEquals(WaterClass.LIKELY_FLOWING, WaterAlong.classOf(2.0))
        assertEquals(WaterClass.MAY_RUN_DRY, WaterAlong.mappedClassOf("ditch", false))
        assertEquals(WaterClass.LIKELY_FLOWING, WaterAlong.mappedClassOf("stream", false))
    }
}
