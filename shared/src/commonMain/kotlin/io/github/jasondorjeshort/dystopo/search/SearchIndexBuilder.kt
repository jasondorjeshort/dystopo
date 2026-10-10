package io.github.jasondorjeshort.dystopo.search

import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.MvtDecoder
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesHeader
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.TileIds
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Counters from one index build, for reports. */
data class SearchBuildStats(
    val zooms: List<Int>,
    val tiles: Int,
    val tileErrors: Int,
    val features: Int,
    val occurrences: Int,
    val entries: Int,
    val tokens: Int,
)

/**
 * Builds a [SearchIndex] from a vector PMTiles pack (Dystopo outdoors or OpenMapTiles schema; see
 * [PlaceClassifier]) by decoding every tile at the archive's max zoom (capped at 14), which holds every
 * named feature, and at two and four zooms lower, which only say which places the map already shows
 * when zoomed out (a ranking signal: [SearchEntry.minZoom]); those add 1/4 + 1/16 to the tile count.
 *
 * The same place appears in many tiles (tile buffers repeat points; a river or trail is cut into one
 * piece per tile), so occurrences with the same folded name and kind are merged by single-linkage
 * clustering: points within [POINT_MERGE_KM], line and polygon pieces within 2.5 max-zoom tile widths
 * of each other; a cluster only the lower zooms have joins the nearest max-zoom one ([LOW_ZOOM_ATTACH_KM]). Each cluster becomes one entry placed at its max-zoom occurrence nearest the cluster's centre.
 * Lines are placed at their middle vertex, polygons (OpenMapTiles parks) at the centre of the exterior's
 * bounding box.
 *
 * Rivers and streams with one name merge as one family (the stronger kind wins), and a point mapped
 * twice as different kinds within [SAME_PLACE_KM] (a summit that is also a viewpoint) keeps the
 * higher-ranked one.
 *
 * Rank (0..255) = kind weight + for point kinds 5 per zoom the place shows below max zoom (up to 20) +
 * for peaks and volcanoes 1 per 250 m of elevation (up to 20).
 */
class SearchIndexBuilder(
    private val reader: PmtilesReader,
    private val fileName: String,
    /** Called after each tile: (done, total). */
    private val progress: (Int, Int) -> Unit = { _, _ -> },
) {
    private class Occurrence(
        val name: String, val alt: String?, val kind: PlaceKind,
        val lon: Double, val lat: Double, val zoom: Int, val ele: Int?,
        /** From a line or polygon: one feature cut into a piece per tile, merged over tile distances. */
        val spread: Boolean,
    )

    var stats: SearchBuildStats? = null
        private set

    fun build(builtAtMs: Long = 0L): SearchIndex {
        val h = reader.header
        require(h.tileType == TileType.MVT) { "not a vector archive (tile type ${h.tileType.label})" }
        val zooms = zoomsFor(h)
        val ids = zooms.flatMap { z -> tileIdsAt(z) }
        val occurrences = ArrayList<Occurrence>()
        var features = 0
        var errors = 0
        ids.forEachIndexed { i, id ->
            val t = TileIds.toZxy(id)
            try {
                val bytes = reader.tile(t.z, t.x, t.y)
                if (bytes != null) {
                    val tile = MvtDecoder.decode(LayerFilter.filter(bytes, PlaceClassifier.LAYERS))
                    for (layer in tile.layers) {
                        if (layer.name !in PlaceClassifier.LAYERS) continue
                        for (f in layer.features) {
                            features++
                            occurrences += occurrencesOf(layer.name, f, t.z, t.x, t.y, layer.extent)
                        }
                    }
                }
            } catch (e: IllegalArgumentException) {
                errors++ // one bad tile must not sink the index
            }
            progress(i + 1, ids.size)
        }
        val maxZ = zooms.last()
        val entries = merge(occurrences, maxZ, h)
        val index = SearchIndex.of(entries, SearchSource(fileName, fingerprint(h), zooms, builtAtMs))
        stats = SearchBuildStats(zooms, ids.size, errors, features, occurrences.size, entries.size, index.tokenCount)
        return index
    }

    /** Tile ids present at zoom [z] (run-length entries expanded), in id order. */
    private fun tileIdsAt(z: Int): List<Long> {
        val start = TileIds.zoomStart(z)
        val end = TileIds.zoomStart(z + 1)
        val out = ArrayList<Long>()
        for (e in reader.tileEntries()) {
            if (e.tileId >= end) break
            if (e.tileId + e.runLength <= start) continue
            for (k in 0 until e.runLength) {
                val id = e.tileId + k
                if (id in start until end) out += id
            }
        }
        return out
    }

    private fun occurrencesOf(layer: String, f: Feature, z: Int, x: Int, y: Int, extent: Int): List<Occurrence> {
        val props = f.properties
        val names = NAME_KEYS.mapNotNull { (props[it] as? Value.StringValue)?.value?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinct()
        // Outdoors packs: the hiking routes a trail piece belongs to ("Wonderland Trail;...") are places of their own.
        val routes = if (layer == "transportation") {
            (props["route_name"] as? Value.StringValue)?.value?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        } else emptyList()
        if (names.isEmpty() && routes.isEmpty()) return emptyList()
        val cls = (props["class"] as? Value.StringValue)?.value
        val sub = (props["subclass"] as? Value.StringValue)?.value
        val kind = PlaceClassifier.classify(layer, cls, sub) ?: return emptyList()
        val p = anchor(f.geometry) ?: return emptyList()
        // Points in the tile buffer belong to the neighbouring tile, which has them too.
        if (f.geometry is Geometry.Points && (p.x < 0 || p.y < 0 || p.x >= extent || p.y >= extent)) return emptyList()
        val n = (1 shl z).toDouble()
        val lon = (x + p.x.toDouble() / extent) / n * 360.0 - 180.0
        val my = (y + p.y.toDouble() / extent) / n
        val lat = atan(sinh(PI * (1 - 2 * my))) * 180.0 / PI
        val ele = props["ele"]?.asDouble()?.roundToInt()
        val spread = f.geometry !is Geometry.Points
        val out = ArrayList<Occurrence>(1 + routes.size)
        if (names.isNotEmpty()) out += Occurrence(names[0].take(MAX_NAME), names.getOrNull(1)?.take(MAX_NAME), kind, lon, lat, z, ele, spread)
        for (r in routes) if (r !in names) out += Occurrence(r.take(MAX_NAME), null, PlaceKind.ROUTE, lon, lat, z, null, spread)
        return out
    }

    private fun merge(occurrences: List<Occurrence>, maxZ: Int, h: PmtilesHeader): List<SearchEntry> {
        val midLat = (h.minLat + h.maxLat) / 2
        val tileKm = 40075.0 * cos(midLat * PI / 180) / (1 shl maxZ)
        // Rivers and streams are one family (OSM often tags pieces of one waterway differently), and so
        // are trails and the hiking routes that share their name.
        fun family(k: PlaceKind) = when (k) {
            PlaceKind.STREAM -> PlaceKind.RIVER.code
            PlaceKind.TRAIL -> PlaceKind.ROUTE.code
            else -> k.code
        }
        val groups = occurrences.groupBy { TextFold.normalized(it.name) + "\u0000" + family(it.kind) }
        val merged = ArrayList<SearchEntry>()
        for ((_, group) in groups) {
            val limit = if (group.any { it.spread }) max(SPREAD_MIN_MERGE_KM, 2.5 * tileKm) else POINT_MERGE_KM
            for (cluster in attachLowZoom(clusters(group, limit), maxZ)) merged += entry(cluster, maxZ)
        }
        // One place mapped twice as different point kinds (a summit that is also a viewpoint) keeps the stronger kind.
        merged.sortByDescending { it.rank }
        val out = ArrayList<SearchEntry>(merged.size)
        val kept = HashMap<String, MutableList<SearchEntry>>()
        for (e in merged) {
            if (!e.kind.line) {
                val same = kept.getOrPut(TextFold.normalized(e.name)) { ArrayList(1) }
                if (same.any { SearchIndex.distanceKm(it.lat, it.lon, e.lat, e.lon) <= SAME_PLACE_KM }) continue
                same += e
            }
            out += e
        }
        // Stable order: most important first, so a truncated listing still shows the big names.
        out.sortWith(compareByDescending<SearchEntry> { it.rank }.thenBy { it.name }.thenBy { it.lat })
        return out
    }

    /**
     * A cluster seen only at the lower scanned zooms, next to one that the max zoom has, is the same
     * place drawn generalised (a polygon piece's centre moves with the tiling): it only contributes its
     * zoom. Far from any max-zoom cluster it stays a place of its own (packs whose top zoom misses an area).
     */
    private fun attachLowZoom(clusters: List<List<Occurrence>>, maxZ: Int): List<List<Occurrence>> {
        if (clusters.size == 1) return clusters
        val (top, low) = clusters.partition { c -> c.any { it.zoom == maxZ } }
        if (top.isEmpty() || low.isEmpty()) return clusters
        val out = top.map { it.toMutableList() }
        val alone = ArrayList<List<Occurrence>>()
        for (c in low) {
            val o = c.first()
            val (best, d) = out.map { t -> t to t.minOf { SearchIndex.distanceKm(o.lat, o.lon, it.lat, it.lon) } }.minBy { it.second }
            if (d <= LOW_ZOOM_ATTACH_KM) best += c else alone += c
        }
        return out + alone
    }

    /** Single-linkage clusters of [group] with link distance [limitKm] (union-find over a coarse grid). */
    private fun clusters(group: List<Occurrence>, limitKm: Double): List<List<Occurrence>> {
        if (group.size == 1) return listOf(group)
        val parent = IntArray(group.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) { val n = parent[j]; parent[j] = r; j = n }
            return r
        }
        val cellDeg = limitKm / 111.0
        val grid = HashMap<Long, MutableList<Int>>()
        fun cell(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xFFFFFFFFL)
        group.forEachIndexed { i, o ->
            val cx = kotlin.math.floor(o.lon * cos(o.lat * PI / 180) / cellDeg).toLong()
            val cy = kotlin.math.floor(o.lat / cellDeg).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) {
                grid[cell(cx + dx, cy + dy)]?.forEach { j ->
                    val p = group[j]
                    if (SearchIndex.distanceKm(o.lat, o.lon, p.lat, p.lon) <= limitKm) parent[find(i)] = find(j)
                }
            }
            grid.getOrPut(cell(cx, cy)) { ArrayList(2) } += i
        }
        return group.indices.groupBy { find(it) }.values.map { idx -> idx.map { group[it] } }
    }

    private fun entry(cluster: List<Occurrence>, maxZ: Int): SearchEntry {
        val top = cluster.filter { it.zoom == maxZ }.ifEmpty { cluster }
        val cLat = top.sumOf { it.lat } / top.size
        val cLon = top.sumOf { it.lon } / top.size
        val rep = top.minBy { SearchIndex.distanceKm(cLat, cLon, it.lat, it.lon) }
        // The strongest kind among the pieces (a waterway that is a river somewhere is a river).
        val kind = cluster.maxBy { it.kind.weight }.kind
        val minZoom = cluster.minOf { it.zoom }
        val ele = cluster.mapNotNull { it.ele }.maxOrNull()
        val alt = cluster.firstNotNullOfOrNull { it.alt }?.takeIf { TextFold.normalized(it) != TextFold.normalized(rep.name) }
        // Lines (rivers, roads) show at low zooms because of their length, not their importance.
        val zoomBonus = if (kind.line) 0 else min(20, 5 * (maxZ - minZoom))
        val eleBonus = if ((kind == PlaceKind.PEAK || kind == PlaceKind.VOLCANO) && ele != null) (ele / 250).coerceIn(0, 20) else 0
        val rank = (kind.weight + zoomBonus + eleBonus).coerceIn(0, 255)
        return SearchEntry(rep.name, alt, kind, rep.lon, rep.lat, rank, minZoom, ele)
    }

    companion object {
        /** Name attributes in order of preference; the first is the display name, the next one found the alternate. */
        val NAME_KEYS = listOf("name", "name_en", "name:en", "name:latin", "name_int")
        const val POINT_MERGE_KM = 1.0
        const val SPREAD_MIN_MERGE_KM = 1.0
        /** How far a low-zoom-only cluster may be from a max-zoom one of the same name to count as the same place. */
        const val LOW_ZOOM_ATTACH_KM = 30.0
        /** Same name, different point kinds, this close: one place. */
        const val SAME_PLACE_KM = 0.5
        const val MAX_NAME = 120

        /** Max zoom scanned (z14 holds every feature in both schemas), plus two and four zooms lower for prominence. */
        fun zoomsFor(h: PmtilesHeader): List<Int> {
            val hi = min(h.maxZoom, 14)
            return listOf(hi - 4, hi - 2, hi).filter { it >= h.minZoom }.distinct()
        }

        /** Identifies the archive's content: the index is rebuilt when this changes. */
        fun fingerprint(h: PmtilesHeader): String =
            "v1:${h.tileDataLength}:${h.addressedTiles}:${h.tileContents}:${h.rootDirectoryOffset}:${h.metadataLength}:${h.minZoom}-${h.maxZoom}"

        private fun sinh(x: Double) = (exp(x) - exp(-x)) / 2

        /** The point a feature is found at: the point itself, a line's middle vertex, a polygon's box centre. */
        private fun anchor(g: Geometry): TilePoint? = when (g) {
            is Geometry.Points -> g.points.firstOrNull()
            is Geometry.Lines -> g.lines.maxByOrNull { it.size }?.let { it[it.size / 2] }
            is Geometry.Polygons -> g.polygons.maxByOrNull { it.exterior.size }?.exterior?.let { ring ->
                TilePoint((ring.minOf { it.x } + ring.maxOf { it.x }) / 2, (ring.minOf { it.y } + ring.maxOf { it.y }) / 2)
            }
            is Geometry.Unknown -> null
        }
    }
}
