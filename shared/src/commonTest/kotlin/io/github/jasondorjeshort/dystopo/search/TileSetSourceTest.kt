package io.github.jasondorjeshort.dystopo.search

import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileIds
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T377: a search index from tiles held outside a pack (an area kept as tiles in the online cache), through the builder's
 * [TileSetSource] seam. Synthetic tiles around Paradise on Mount Rainier (public names), in the OpenMapTiles schema the
 * online source serves.
 */
class TileSetSourceTest {
    private val extent = 4096

    private fun xy(z: Int, lon: Double, lat: Double): IntArray {
        val n = 1 shl z
        val fx = (lon + 180) / 360 * n
        val r = lat * PI / 180
        val fy = (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n
        val x = floor(fx).toInt()
        val y = floor(fy).toInt()
        return intArrayOf(x, y, ((fx - x) * extent).toInt(), ((fy - y) * extent).toInt())
    }

    /** Tiles under construction: (z, x, y) -> layer -> features; encoded on demand. */
    private class Tiles {
        val map = LinkedHashMap<Triple<Int, Int, Int>, LinkedHashMap<String, MutableList<Feature>>>()
    }

    private fun Tiles.point(z: Int, lon: Double, lat: Double, layer: String, vararg kv: Pair<String, String>) {
        val p = xy(z, lon, lat)
        map.getOrPut(Triple(z, p[0], p[1])) { LinkedHashMap() }.getOrPut(layer) { ArrayList() } +=
            Feature(null, kv.associate { (k, v) -> k to Value.StringValue(v) }, Geometry.Points(listOf(TilePoint(p[2], p[3]))))
    }

    private fun Tiles.bytes(): Map<Triple<Int, Int, Int>, ByteArray> =
        map.mapValues { (_, layers) -> VectorTile(layers.map { (name, fs) -> Layer(name, fs, extent) }).encode() }

    /** An area's tiles in the OpenMapTiles schema: Paradise at three zooms, two more places at z14. */
    private fun areaTiles(): Tiles = Tiles().apply {
        for (z in listOf(10, 12, 14)) point(z, -121.7353, 46.7865, "place", "class" to "village", "name" to "Paradise")
        point(14, -121.7300, 46.7900, "poi", "class" to "lodging", "subclass" to "alpine_hut", "name" to "Paradise Inn")
        point(14, -121.7400, 46.7750, "water_name", "class" to "lake", "name" to "Reflection Lakes")
    }

    /** [t]'s tiles as an area's: the ones listed in [keep] (all by default) present, as a cache would hold them. */
    private fun source(t: Tiles, key: String = "a0123456789abcdef:v", keep: (Triple<Int, Int, Int>) -> Boolean = { true }): TileListSource {
        val bytes = t.bytes().filterKeys(keep)
        val present = bytes.keys.groupBy({ it.first }, { TileIds.fromZxy(it.first, it.second, it.third) })
        return TileListSource(key, 8, 14, 46.79, present) { z, x, y -> bytes[Triple(z, x, y)] }
    }

    @Test
    fun anIndexFromAnAreasTiles() {
        val src = source(areaTiles())
        var progress = 0 to 0
        val b = SearchIndexBuilder(src, "a0123456789abcdef-search.idx") { d, n -> progress = d to n }
        val idx = b.build(builtAtMs = 7)
        assertEquals(listOf(10, 12, 14), b.stats!!.zooms)
        assertEquals(src.tileCount, b.stats!!.tiles)
        assertEquals(src.tileCount to src.tileCount, progress)
        assertEquals(setOf("Paradise", "Paradise Inn", "Reflection Lakes"), idx.entries.map { it.name }.toSet())
        assertEquals(10, idx.entries.single { it.name == "Paradise" }.minZoom)
        assertEquals(src.fingerprint, idx.source.fingerprint)
        assertEquals("Paradise", idx.search("paradise", lat = 46.786, lon = -121.735).first().entry.name)
        // Survives the file format, fingerprint included (how a stale index is told on the next use).
        assertEquals(src.fingerprint, SearchIndex.decode(idx.encode()).source.fingerprint)
    }

    @Test
    fun theFingerprintChangesWhenATileArrives() {
        val t = areaTiles()
        val z14 = t.map.keys.filter { it.first == 14 }
        val without = source(t) { it != z14.first() }
        val with = source(t)
        assertNotEquals(without.fingerprint, with.fingerprint, "a tile arrived")
        assertEquals(with.fingerprint, source(t).fingerprint, "the same tiles, the same fingerprint")
        assertNotEquals(with.fingerprint, source(t, key = "a0123456789abcdef:w").fingerprint, "other versions, another index")
        // The tile missing from the cache means a place missing from the index.
        val idx = SearchIndexBuilder(without, "x").build()
        assertTrue(idx.entries.size < SearchIndexBuilder(with, "x").build().entries.size)
        assertTrue(with.fingerprint.contains("14=${z14.size}."), with.fingerprint)
    }

    @Test
    fun aPlaceInAPackAndInAnAreaIsListedOnce() {
        // The pack: the same place in the outdoors schema, where it is a hamlet; the area's tiles call it a village.
        val pack = Tiles().apply { for (z in listOf(10, 12, 14)) point(z, -121.7356, 46.7867, "place", "class" to "hamlet", "name" to "Paradise") }
        val w = PmtilesWriter(TileType.MVT)
        for ((k, b) in pack.bytes()) w.addTile(k.first, k.second, k.third, b)
        val chunks = ArrayList<ByteArray>()
        w.finish(ByteSink { chunks += it }, "{}", minLon = -121.95, minLat = 46.70, maxLon = -121.55, maxLat = 47.00, minZoom = 10, maxZoom = 14)
        val bytes = ByteArray(chunks.sumOf { it.size }).also { var p = 0; for (c in chunks) { c.copyInto(it, p); p += c.size } }
        val reader = PmtilesReader.open(ByteRangeSource { o, l -> bytes.copyOfRange(o.toInt().coerceAtMost(bytes.size), minOf(bytes.size.toLong(), o + l).toInt()) })
        val packIdx = SearchIndexBuilder(reader, "rainier-outdoors.pmtiles").build()
        val areaIdx = SearchIndexBuilder(source(areaTiles()), "area").build()
        assertNotEquals(packIdx.entries.single().kind, areaIdx.entries.single { it.name == "Paradise" }.kind)
        val hits = MultiPackSearch.search(listOf("rainier" to packIdx, "Paradise area" to areaIdx), "paradise", lat = 46.786, lon = -121.735)
        assertEquals(1, hits.count { it.hit.entry.name == "Paradise" }, hits.map { it.json() }.toString())
        // Within one map two kinds of one name stay two places.
        val one = SearchIndex.of(listOf(
            SearchEntry("Paradise", null, PlaceKind.HAMLET, -121.7353, 46.7865, 50, 12, null),
            SearchEntry("Paradise", null, PlaceKind.HUT, -121.7354, 46.7866, 40, 14, null),
        ), SearchSource("one", "f", listOf(14), 0))
        assertEquals(2, MultiPackSearch.search(listOf("one" to one), "paradise").size)
    }

    @Test
    fun anAreaAtLessDetailScansToZ13() {
        assertEquals(listOf(9, 11, 13), SearchIndexBuilder.zoomsFor(8, 13))
        assertEquals(listOf(10, 12, 14), SearchIndexBuilder.zoomsFor(8, 15))
        val idx = SearchIndexBuilder(TileListSource("k", 8, 13, 46.79, emptyMap()) { _, _, _ -> null }, "x").build()
        assertEquals(listOf(9, 11, 13), idx.source.zooms)
        assertEquals(0, idx.entries.size)
    }

    @Test
    fun aPacksArchiveIsOneSource() {
        val w = PmtilesWriter(TileType.MVT)
        for ((k, b) in areaTiles().bytes()) w.addTile(k.first, k.second, k.third, b)
        val chunks = ArrayList<ByteArray>()
        w.finish(ByteSink { chunks += it }, "{}", minLon = -121.95, minLat = 46.70, maxLon = -121.55, maxLat = 47.00, minZoom = 10, maxZoom = 14)
        val bytes = ByteArray(chunks.sumOf { it.size }).also { var p = 0; for (c in chunks) { c.copyInto(it, p); p += c.size } }
        val reader = PmtilesReader.open(ByteRangeSource { o, l -> bytes.copyOfRange(o.toInt().coerceAtMost(bytes.size), minOf(bytes.size.toLong(), o + l).toInt()) })
        val src = PmtilesTileSource(reader)
        assertEquals(SearchIndexBuilder.fingerprint(reader.header), src.fingerprint)
        // The archive and the same tiles held as an area give the same places.
        val fromPack = SearchIndexBuilder(src, "p").build().entries.map { it.name to it.kind }.toSet()
        val fromArea = SearchIndexBuilder(source(areaTiles()), "a").build().entries.map { it.name to it.kind }.toSet()
        assertEquals(fromPack, fromArea)
        assertEquals(areaTiles().map.keys.count { it.first == 14 }, src.tileIds(14).size)
    }
}
