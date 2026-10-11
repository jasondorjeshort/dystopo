package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One graph tile the router has loaded (T76): the tile's [graph] and where its nodes continue into
 * other pieces. A node within [GraphPieces.JOIN_M] of a tile border of the source zoom (z14 for packs:
 * every line of a pack ends at those borders, and the graph tile borders are among them) is a
 * boundary node; the router looks for its twin, a node at the same place, in the pieces around it.
 * Within one graph file twins sit at exactly the same microdegrees (the tiler writes a cut into both
 * tiles); between two packs the lines were clipped separately, so a twin may be up to
 * [GraphPieces.JOIN_M] away.
 */
class GraphPiece(
    /** Which graph file the piece came from (index in the [GraphPieces] it was loaded by). */
    val archive: Int,
    val x: Int,
    val y: Int,
    val graph: RoutingGraph,
    /** Zoom of the borders whose nodes may continue elsewhere; negative for a piece that is the whole network. */
    private val joinZoom: Int,
) {
    /** Nodes that may continue in another piece. */
    val boundary: BooleanArray by lazy {
        val g = graph
        BooleanArray(g.nodeCount) { n ->
            if (joinZoom < 0) return@BooleanArray false
            val lat = g.nodeLat[n] / E6
            val fx = WebMercator.lonToX(g.nodeLon[n] / E6, joinZoom)
            val fy = WebMercator.latToY(lat, joinZoom)
            val tileM = WebMercator.EARTH_CIRCUMFERENCE_M * cos(lat * PI / 180) / (1L shl joinZoom)
            val dx = min(fx - floor(fx), floor(fx) + 1 - fx) * tileM
            val dy = min(fy - floor(fy), floor(fy) + 1 - fy) * tileM
            min(dx, dy) <= 2 * GraphPieces.JOIN_M
        }
    }

    /** Boundary nodes by exact position, and in cells of about [CELL_DEG] for the tolerant match. */
    private val exact: HashMap<Long, Int> by lazy {
        HashMap<Long, Int>().also { m -> for (n in boundary.indices) if (boundary[n]) m.putIfAbsent(pos(graph.nodeLon[n], graph.nodeLat[n]), n) }
    }
    private val cells: HashMap<Long, IntList> by lazy {
        HashMap<Long, IntList>().also { m ->
            for (n in boundary.indices) if (boundary[n]) m.getOrPut(cell(graph.nodeLon[n], graph.nodeLat[n])) { IntList(2) }.add(n)
        }
    }

    /**
     * This piece's node at ([lonE6], [latE6]): exactly there, else (with [tolerant]) the nearest boundary
     * node within [GraphPieces.JOIN_M]; -1 when there is none.
     */
    fun twin(lonE6: Int, latE6: Int, tolerant: Boolean): Int {
        exact[pos(lonE6, latE6)]?.let { return it }
        if (!tolerant) return -1
        val cx = floor(lonE6 / CELL_E6).toLong()
        val cy = floor(latE6 / CELL_E6).toLong()
        var best = -1
        var bestD = GraphPieces.JOIN_M
        for (dy in -1L..1L) for (dx in -1L..1L) {
            val list = cells[((cx + dx) shl 32) xor ((cy + dy) and 0xFFFFFFFFL)] ?: continue
            for (i in 0 until list.size) {
                val n = list[i]
                val d = TrackStats.distanceM(latE6 / E6, lonE6 / E6, graph.nodeLat[n] / E6, graph.nodeLon[n] / E6)
                if (d <= bestD) { bestD = d; best = n }
            }
        }
        return best
    }

    override fun toString() = "piece $archive:$x/$y (${graph.nodeCount} nodes)"

    private companion object {
        const val E6 = 1e6
        const val CELL_DEG = 0.0001
        const val CELL_E6 = CELL_DEG * E6
        fun pos(lon: Int, lat: Int): Long = (lon.toLong() shl 32) xor (lat.toLong() and 0xFFFFFFFFL)
        fun cell(lon: Int, lat: Int): Long = (floor(lon / CELL_E6).toLong() shl 32) xor (floor(lat / CELL_E6).toLong() and 0xFFFFFFFFL)
    }
}

/**
 * Where a [TileRouter] gets its graph: the pieces in each tile of a grid at [tileZoom], loaded on
 * demand. A remote or hierarchical source (T77) can stand behind the same shape.
 */
interface GraphPieces {
    /** Zoom of the tile grid ([GraphTiler.ZOOM] for graph files; 0 for one whole graph). */
    val tileZoom: Int

    /** True when there is no network at all (no tiles anywhere). */
    val isEmpty: Boolean

    /** The pieces of tile ([x], [y]) from every graph that has one there (loads them; may be empty). */
    fun pieces(x: Int, y: Int): List<GraphPiece>

    /** Tiles at [tileZoom] that may hold network within the box, as (x, y). */
    fun tilesIn(west: Double, south: Double, east: Double, north: Double): List<Pair<Int, Int>> {
        val z = tileZoom
        val n = (1 shl z) - 1
        val x0 = floor(WebMercator.lonToX(west, z)).toInt().coerceIn(0, n)
        val x1 = floor(WebMercator.lonToX(east, z)).toInt().coerceIn(0, n)
        val y0 = floor(WebMercator.latToY(north, z)).toInt().coerceIn(0, n)
        val y1 = floor(WebMercator.latToY(south, z)).toInt().coerceIn(0, n)
        val out = ArrayList<Pair<Int, Int>>()
        for (y in y0..y1) for (x in x0..x1) out += x to y
        return out
    }

    companion object {
        /** How far apart two nodes of different packs may be and still be one junction (lines clipped separately). */
        const val JOIN_M = 2.0
    }
}

/** One in-memory graph as a single piece (what `Router(graph)` routes on): no tiles, no joins. */
class WholeGraph(val graph: RoutingGraph) : GraphPieces {
    private val piece = GraphPiece(0, 0, 0, graph, joinZoom = -1)
    override val tileZoom: Int get() = 0
    override val isEmpty: Boolean get() = graph.edgeCount == 0
    override fun pieces(x: Int, y: Int): List<GraphPiece> = if (x == 0 && y == 0) listOf(piece) else emptyList()
}

/**
 * Decoded graph tiles shared by every route, least recently used dropped first once their
 * [GraphPiece.graph] sizes ([RoutingGraph.approxBytes], derived indexes included) pass [maxBytes].
 * A route keeps strong references to the pieces it uses, so eviction never pulls a tile from under
 * a search. commonMain has no locks: on a platform with threads pass [lock] (on the JVM
 * `{ block -> synchronized(obj) { block() } }`); tile reads and decoding run outside it.
 */
class GraphTileCache(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val lock: (() -> Unit) -> Unit = { it() },
) {
    private class Key(val archive: GraphTileArchive, val x: Int, val y: Int) {
        override fun equals(other: Any?) = other is Key && other.archive === archive && other.x == x && other.y == y
        override fun hashCode() = (archive.hashCode() * 31 + x) * 31 + y
    }

    // Insertion order = recency (re-inserted on each hit).
    private val map = LinkedHashMap<Key, GraphPiece>()
    var loads = 0L
        private set
    var hits = 0L
        private set
    var evictions = 0L
        private set
    var loadErrors = 0L
        private set

    /** The piece for tile ([x], [y]) of [archive] (index [index] in the caller's list), loading it when needed; null when absent or corrupt. */
    fun get(archive: GraphTileArchive, index: Int, x: Int, y: Int): GraphPiece? {
        if (!archive.has(x, y)) return null
        val k = Key(archive, x, y)
        var hit: GraphPiece? = null
        lock { map.remove(k)?.let { p -> hits++; map[k] = p; hit = p } }
        hit?.let { return it }
        val g = try {
            archive.tile(x, y)
        } catch (e: IllegalArgumentException) {
            lock { loadErrors++ }
            return null
        } ?: return null
        var p = GraphPiece(index, x, y, g, joinZoom = maxOf(archive.source.zoom, archive.tileZoom)) // the deeper borders include the other zoom's
        lock {
            val other = map[k] // another thread loaded it meanwhile
            if (other != null) p = other else {
                loads++
                map[k] = p
                trim()
            }
        }
        return p
    }

    private fun trim() {
        var total = map.values.sumOf { it.graph.approxBytes() }
        val it = map.entries.iterator()
        while (total > maxBytes && map.size > 1 && it.hasNext()) {
            val e = it.next()
            total -= e.value.graph.approxBytes()
            it.remove()
            evictions++
        }
    }

    /** Drops every tile of [archive] (its file was rebuilt or its pack removed). */
    fun forget(archive: GraphTileArchive) = lock { map.keys.removeAll { it.archive === archive } }

    fun clear() = lock { map.clear() }

    fun json(): Map<String, Any?> {
        var out: Map<String, Any?> = emptyMap()
        lock {
            out = linkedMapOf(
                "tiles" to map.size, "bytes" to map.values.sumOf { it.graph.approxBytes() }, "maxBytes" to maxBytes,
                "loads" to loads, "hits" to hits, "evictions" to evictions, "loadErrors" to loadErrors,
            )
        }
        return out
    }

    companion object {
        /** 32 MB: every graph tile of a few national-park packs. */
        const val DEFAULT_MAX_BYTES = 32L shl 20
    }
}

/**
 * The graph files of every pack, as one tiled network (T76): a tile is looked up in each file, and
 * pieces from different packs join at their boundary nodes, so a route runs across pack borders.
 * All files must share one tile zoom.
 */
class TiledGraph(val archives: List<GraphTileArchive>, val cache: GraphTileCache = GraphTileCache()) : GraphPieces {
    init {
        require(archives.map { it.tileZoom }.distinct().size <= 1) { "graph files with different tile zooms: ${archives.map { it.tileZoom }}" }
    }

    override val tileZoom: Int = archives.firstOrNull()?.tileZoom ?: GraphTiler.ZOOM
    override val isEmpty: Boolean get() = archives.all { it.entries.isEmpty() }

    override fun pieces(x: Int, y: Int): List<GraphPiece> = archives.withIndex().mapNotNull { (i, a) -> cache.get(a, i, x, y) }

    /** Whole-network figures: what keeping every tile in memory would take (the T76 comparison). */
    fun wholeBytes(): Long = archives.sumOf { a -> a.entries.sumOf { e -> a.tile(e.x, e.y)?.let { g -> g.adjStart; g.nearest(0.0, 0.0, 1.0); g.approxBytes() } ?: 0L } }

    companion object {
        /** Bounding box test helper: tiles whose square lies within [m] metres of a point. */
        fun around(lat: Double, lon: Double, m: Double): DoubleArray {
            val dLat = m / 111_195.0
            val dLon = m / (111_195.0 * max(0.01, cos(lat * PI / 180)))
            return doubleArrayOf(lon - dLon, lat - dLat, lon + dLon, lat + dLat)
        }
    }
}
