package io.github.jasondorjeshort.dystopo.search

import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.Polygon
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Builds indexes from small synthetic vector archives around Mount Rainier (public names), one in
 * the Dystopo outdoors schema and one in the OpenMapTiles schema.
 */
class SearchIndexBuilderTest {
    private val extent = 4096

    /** A feature at [lon]/[lat] in tile z/x/y coordinates. */
    private class Pos(val z: Int, val x: Int, val y: Int, val px: Int, val py: Int)

    private fun pos(z: Int, lon: Double, lat: Double): Pos {
        val n = 1 shl z
        val fx = (lon + 180) / 360 * n
        val r = lat * PI / 180
        val fy = (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n
        val x = floor(fx).toInt()
        val y = floor(fy).toInt()
        return Pos(z, x, y, ((fx - x) * extent).toInt(), ((fy - y) * extent).toInt())
    }

    private fun props(vararg kv: Pair<String, Any>): Map<String, Value> = kv.associate { (k, v) ->
        k to when (v) {
            is String -> Value.StringValue(v)
            is Int -> Value.IntValue(v.toLong())
            else -> error("unsupported $v")
        }
    }

    /** Tiles under construction: (z, x, y) -> layer name -> features. */
    private class Tiles {
        val map = LinkedHashMap<Triple<Int, Int, Int>, LinkedHashMap<String, MutableList<Feature>>>()
        fun add(z: Int, x: Int, y: Int, layer: String, f: Feature) {
            map.getOrPut(Triple(z, x, y)) { LinkedHashMap() }.getOrPut(layer) { ArrayList() } += f
        }
    }

    private fun Tiles.point(z: Int, lon: Double, lat: Double, layer: String, vararg kv: Pair<String, Any>) {
        val p = pos(z, lon, lat)
        add(p.z, p.x, p.y, layer, Feature(null, props(*kv), Geometry.Points(listOf(TilePoint(p.px, p.py)))))
    }

    private fun archive(t: Tiles): PmtilesReader {
        val w = PmtilesWriter(TileType.MVT)
        for ((k, layers) in t.map) {
            w.addTile(k.first, k.second, k.third, VectorTile(layers.map { (name, fs) -> Layer(name, fs, extent) }).encode())
        }
        val chunks = ArrayList<ByteArray>()
        w.finish(ByteSink { chunks += it }, "{}", minLon = -121.95, minLat = 46.70, maxLon = -121.55, maxLat = 47.00, minZoom = 10, maxZoom = 14)
        val bytes = ByteArray(chunks.sumOf { it.size }).also { var p = 0; for (c in chunks) { c.copyInto(it, p); p += c.size } }
        return PmtilesReader.open(ByteRangeSource { offset, length ->
            bytes.copyOfRange(offset.toInt().coerceAtMost(bytes.size), minOf(bytes.size.toLong(), offset + length).toInt())
        })
    }

    private fun outdoorsTiles(): Tiles {
        val t = Tiles()
        for (z in listOf(10, 12, 14)) {
            t.point(z, -121.7603, 46.8529, "mountain_peak", "class" to "volcano", "name" to "Mount Rainier", "name_en" to "Mount Rainier", "ele" to 4392)
        }
        for (z in listOf(12, 14)) t.point(z, -121.7353, 46.7865, "place", "class" to "hamlet", "name" to "Paradise")
        t.point(14, -121.7270, 46.8020, "mountain_peak", "class" to "peak", "name" to "Panorama Point", "ele" to 2097)
        // The same summit mapped as a viewpoint as well: one place, kept as the peak.
        t.point(14, -121.7271, 46.8021, "outdoor_poi", "class" to "viewpoint", "name" to "Panorama Point")
        t.point(14, -121.7300, 46.7900, "outdoor_poi", "class" to "hut", "name" to "Paradise Inn", "ele" to 1650)

        // Panorama Point again in the buffer of the tile to its west: outside 0..extent, must be skipped.
        val pp = pos(14, -121.7270, 46.8020)
        t.add(14, pp.x - 1, pp.y, "mountain_peak", Feature(null, props("class" to "peak", "name" to "Panorama Point"),
            Geometry.Points(listOf(TilePoint(pp.px + extent, pp.py)))))

        // Paradise River crosses two z14 tiles; OSM calls one piece a river and the other a stream.
        val a = pos(14, -121.7400, 46.7700)
        val line = { y0: Int -> Geometry.Lines(listOf(listOf(TilePoint(100, y0), TilePoint(2000, y0 + 50), TilePoint(4000, y0 + 100)))) }
        t.add(14, a.x, a.y, "waterway", Feature(null, props("class" to "river", "name" to "Paradise River"), line(a.py)))
        t.add(14, a.x + 1, a.y, "waterway", Feature(null, props("class" to "stream", "name" to "Paradise River"), line(a.py + 100)))
        t.add(10, pos(10, -121.74, 46.77).x, pos(10, -121.74, 46.77).y, "waterway",
            Feature(null, props("class" to "river", "name" to "Paradise River"), line(pos(10, -121.74, 46.77).py)))

        // A trail piece that belongs to a hiking route; the route is findable by its own name.
        t.add(14, a.x, a.y, "transportation", Feature(null, props("class" to "path", "name" to "Skyline Trail", "route_name" to "Wonderland Trail;Skyline Loop"), line(a.py + 300)))
        // Unnamed features and layers that are not indexed are ignored.
        t.add(14, a.x, a.y, "transportation", Feature(null, props("class" to "path"), line(a.py + 400)))
        t.add(14, a.x, a.y, "landcover", Feature(null, props("class" to "ice", "name" to "Paradise Glacier"), line(a.py + 500)))
        return t
    }

    @Test
    fun buildsOneEntryPerPlaceFromAnOutdoorsPack() {
        var lastProgress = 0 to 0
        val b = SearchIndexBuilder(archive(outdoorsTiles()), "rainier-outdoors.pmtiles") { d, n -> lastProgress = d to n }
        val idx = b.build(builtAtMs = 42)
        val stats = b.stats!!
        assertEquals(listOf(10, 12, 14), stats.zooms)
        assertEquals(stats.tiles to stats.tiles, lastProgress)
        assertEquals(0, stats.tileErrors)

        val byName = idx.entries.groupBy { it.name }
        assertEquals(
            setOf("Mount Rainier", "Paradise", "Panorama Point", "Paradise Inn", "Paradise River", "Skyline Trail", "Wonderland Trail", "Skyline Loop"),
            byName.keys,
        )
        assertTrue(byName.values.all { it.size == 1 }, "duplicates: ${byName.filterValues { it.size > 1 }}")
        assertEquals(PlaceKind.PEAK, byName.getValue("Panorama Point").single().kind)
        assertEquals(PlaceKind.RIVER, byName.getValue("Paradise River").single().kind)
        assertEquals(10, byName.getValue("Paradise River").single().minZoom)
        assertEquals(PlaceKind.ROUTE, byName.getValue("Wonderland Trail").single().kind)
        assertEquals(PlaceKind.TRAIL, byName.getValue("Skyline Trail").single().kind)

        val rainier = byName.getValue("Mount Rainier").single()
        assertEquals(PlaceKind.VOLCANO, rainier.kind)
        assertEquals(4392, rainier.ele)
        assertEquals(10, rainier.minZoom)
        assertEquals(46.8529, rainier.lat, 1e-3)
        assertEquals(-121.7603, rainier.lon, 1e-3)
        assertEquals(null, rainier.alt) // name_en equal to name is not an alternate
        assertEquals(12, byName.getValue("Paradise").single().minZoom)

        assertEquals("rainier-outdoors.pmtiles", idx.source.file)
        assertEquals(42L, idx.source.builtAtMs)

        val hits = idx.search("para", lat = 46.786, lon = -121.735).map { it.entry.name }
        assertEquals("Paradise", hits.first())
        assertTrue(hits.containsAll(listOf("Paradise River", "Paradise Inn")), "$hits")
        // Survives the file format.
        assertEquals(hits, SearchIndex.decode(idx.encode()).search("para", lat = 46.786, lon = -121.735).map { it.entry.name })
    }

    @Test
    fun readsOpenMapTilesSchemaPacks() {
        val t = Tiles()
        t.point(14, -121.7603, 46.8529, "mountain_peak", "class" to "volcano", "name" to "Mount Rainier", "name:en" to "Mount Rainier", "name_int" to "Tahoma", "ele" to 4392, "rank" to 1)
        t.point(14, -121.7353, 46.7865, "place", "class" to "hamlet", "name" to "Paradise")
        t.point(14, -121.7300, 46.7900, "poi", "class" to "lodging", "subclass" to "alpine_hut", "name" to "Paradise Inn")
        t.point(14, -121.7400, 46.7750, "water_name", "class" to "lake", "name" to "Reflection Lakes")
        t.point(14, -121.70, 46.75, "transportation", "class" to "rail", "name" to "Old Railway")
        // A park polygon cut into two tile pieces: one entry.
        val p = pos(14, -121.75, 46.80)
        val square = Geometry.Polygons(listOf(Polygon(listOf(TilePoint(0, 0), TilePoint(4096, 0), TilePoint(4096, 4096), TilePoint(0, 4096)))))
        t.add(14, p.x, p.y, "park", Feature(null, props("class" to "national_park", "name" to "Mount Rainier National Park"), square))
        t.add(14, p.x + 1, p.y, "park", Feature(null, props("class" to "national_park", "name" to "Mount Rainier National Park"), square))

        val idx = SearchIndexBuilder(archive(t), "rainier-osm.pmtiles").build()
        val byName = idx.entries.associateBy { it.name }
        assertEquals(setOf("Mount Rainier", "Paradise", "Paradise Inn", "Reflection Lakes", "Mount Rainier National Park"), byName.keys)
        assertEquals(PlaceKind.HUT, byName.getValue("Paradise Inn").kind)
        assertEquals(PlaceKind.LAKE, byName.getValue("Reflection Lakes").kind)
        assertEquals(PlaceKind.PARK, byName.getValue("Mount Rainier National Park").kind)
        assertEquals("Tahoma", byName.getValue("Mount Rainier").alt)
        assertEquals("Mount Rainier", idx.search("tahoma").single().entry.name)
        assertEquals(listOf("Mount Rainier", "Mount Rainier National Park"), idx.search("mt rainier").map { it.entry.name })
    }

    @Test
    fun layerFilterKeepsOnlyNamedLayers() {
        val place = Layer("place", listOf(Feature(7, props("class" to "hamlet", "name" to "Paradise"), Geometry.Points(listOf(TilePoint(10, 20))))), extent)
        val water = Layer("water", listOf(Feature(null, props("class" to "lake"), Geometry.Points(listOf(TilePoint(1, 2))))), extent)
        val bytes = VectorTile(listOf(water, place, water.copy(name = "landcover"))).encode()
        val filtered = LayerFilter.filter(bytes, setOf("place"))
        assertTrue(filtered.size < bytes.size)
        assertEquals(VectorTile(listOf(place)), VectorTile.decode(filtered))
        val all = VectorTile(listOf(place)).encode()
        assertTrue(LayerFilter.filter(all, setOf("place")) === all)
        assertFailsWith<IllegalArgumentException> { LayerFilter.filter(bytes.copyOf(bytes.size - 3), setOf("place")) }
    }

    @Test
    fun refusesRasterArchives() {
        val w = PmtilesWriter(TileType.PNG)
        w.addTile(10, 1, 1, byteArrayOf(1, 2, 3))
        val chunks = ArrayList<ByteArray>()
        w.finish(ByteSink { chunks += it })
        val bytes = ByteArray(chunks.sumOf { it.size }).also { var p = 0; for (c in chunks) { c.copyInto(it, p); p += c.size } }
        val reader = PmtilesReader.open(ByteRangeSource { o, l -> bytes.copyOfRange(o.toInt(), minOf(bytes.size.toLong(), o + l).toInt()) })
        assertFailsWith<IllegalArgumentException> { SearchIndexBuilder(reader, "x-dem.pmtiles").build() }
    }

    @Test
    fun fingerprintChangesWithTheArchive() {
        val a = archive(outdoorsTiles()).header
        val t = outdoorsTiles().also { it.point(14, -121.70, 46.75, "place", "class" to "hamlet", "name" to "Longmire") }
        val b = archive(t).header
        assertTrue(SearchIndexBuilder.fingerprint(a) != SearchIndexBuilder.fingerprint(b))
    }
}
