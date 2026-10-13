package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Long drives (T77, PLANNING › Routing): a one-level partition overlay over the graph tiles, the
 * idea of multi-level Dijkstra (Delling et al., "Customizable Route Planning") with the z11 graph
 * tiles as its cells, so it composes across packs the way the tiles do.
 *
 * For one tile of one pack the overlay keeps only the tile's **border nodes**, the nodes on the
 * tile's border where a road of [MIN_CLASS] or larger crosses it, and one directed **shortcut** for
 * each pair of them that the tile's drive network connects, costing exactly the cheapest drive inside
 * the tile (one-ways, roads closed to cars and `destination` roads respected). A long drive searches
 * the tiles around its two ends in full and every other tile through its overlay, which is a few
 * dozen nodes instead of thousands, and expands the shortcuts it used back into the tile's own edges
 * ([unpack]), so the route has the same points, names and numbers as one found without the overlay.
 *
 * Exact within the cells, approximate across them: a drive between the end areas crosses tile
 * borders only on roads of [MIN_CLASS] or larger, which every long drive does; when that finds
 * nothing [TileRouter] searches again without the overlay. Only tiles that one pack alone covers get
 * an overlay; where packs overlap or meet inside a tile it is searched in full, so the joins between
 * packs (anywhere on a z14 border) stay what they are.
 */
class DriveOverlay(
    /** The graph tile zoom (the cells). */
    val tileZoom: Int = GraphTiler.ZOOM,
    /** Decoded overlay tiles kept, least recently used dropped first. */
    val maxTiles: Int = 4096,
    /** On a platform with threads, a lock (see [GraphTileCache]). */
    private val lock: (() -> Unit) -> Unit = { it() },
    private val clock: () -> Long = { 0L },
) {
    /** One tile's overlay: the base piece's border nodes, the shortcuts between them, and how to expand them. */
    class Cell(
        val x: Int,
        val y: Int,
        /** Border node i of the overlay is base node `baseNode[i]` of the tile's graph. */
        val baseNode: IntArray,
        /** Each shortcut's cost (the router's units: drive metres at [CostModel.DRIVE_REF_KMH]). */
        val cost: DoubleArray,
        val graph: RoutingGraph,
        val buildMs: Long,
    )

    /** A tile of one graph file (its [GraphSource] names the file and the build, so a rebuilt pack gets new cells). */
    private data class Key(val source: GraphSource, val x: Int, val y: Int)

    private val cells = LinkedHashMap<Key, Cell?>()
    var built = 0L
        private set
    var hits = 0L
        private set
    var buildMs = 0L
        private set
    var shortcuts = 0L
        private set
    var borderNodes = 0L
        private set

    /**
     * The overlay piece of tile ([x], [y]) when one pack alone has the tile and it has drive data, else
     * null (the caller searches the base pieces). Built on first use and kept.
     */
    fun piece(x: Int, y: Int, base: List<GraphPiece>): GraphPiece? {
        if (base.size != 1) return null
        val b = base[0]
        return piece(x, y, b.archive, b.graph.source) { b }
    }

    /**
     * The overlay piece of tile ([x], [y]) of the one graph file that has it ([archive], [source]); the
     * base tile is loaded through [base] only when the cell is not cached yet or a shortcut is expanded,
     * so a long drive does not decode the tiles it passes through.
     */
    fun piece(x: Int, y: Int, archive: Int, source: GraphSource, base: () -> GraphPiece?): GraphPiece? {
        if (!source.drive) return null
        val k = Key(source, x, y)
        var found = false
        var cell: Cell? = null
        lock { if (cells.containsKey(k)) { found = true; hits++; cell = cells.remove(k); cells[k] = cell } }
        var loaded: GraphPiece? = null
        if (!found) {
            val b = base() ?: return null
            loaded = b
            val c = build(b)
            cell = c
            lock {
                cells[k] = c
                built++
                if (c != null) { buildMs += c.buildMs; shortcuts += c.cost.size; borderNodes += c.baseNode.size }
                while (cells.size > maxTiles) { val it = cells.keys.iterator(); it.next(); it.remove() }
            }
        }
        // A new piece per search: it carries this search's base piece and archive index.
        return cell?.let { c ->
            GraphPiece(archive, x, y, c.graph, joinZoom = maxOf(tileZoom, source.zoom), overlay = Overlay(c) { loaded ?: base() ?: error("graph tile $x/$y of ${source.file} is gone") })
        }
    }

    fun json(): Map<String, Any?> {
        var out: Map<String, Any?> = emptyMap()
        lock {
            out = linkedMapOf(
                "tiles" to cells.size, "built" to built, "hits" to hits, "buildMs" to buildMs,
                "borderNodes" to borderNodes, "shortcuts" to shortcuts, "minClass" to MIN_CLASS.label,
            )
        }
        return out
    }

    /** The overlay cell of one base piece; null when the tile has no border crossing of a large road. */
    fun build(base: GraphPiece): Cell? {
        val t0 = clock()
        val g = base.graph
        val model = CostModel(TravelMode.DRIVE)
        val z = tileZoom
        // Border nodes: on the tile's border, with a road of MIN_CLASS or larger open to cars.
        val border = IntList()
        for (n in 0 until g.nodeCount) {
            if (!onBorder(g.nodeLon[n], g.nodeLat[n], base.x, base.y, z)) continue
            var major = false
            for (i in g.adjStart[n] until g.adjStart[n + 1]) {
                val e = g.adjEdge[i] shr 1
                if (g.wayClass(e).code >= MIN_CLASS.code && model.usable(g, e) && g.flags(e) and WayAttrs.MOTOR_DESTINATION == 0) { major = true; break }
            }
            if (major) border.add(n)
        }
        val k = border.size
        if (k == 0) return null
        val index = HashMap<Int, Int>(k * 2)
        for (i in 0 until k) index[border[i]] = i
        val from = IntList(); val to = IntList(); val cost = ArrayList<Double>(); val length = ArrayList<Float>()
        val search = TileSearch(g, model)
        for (i in 0 until k) {
            search.run(border[i], index.keys)
            for (j in 0 until k) {
                if (j == i) continue
                val c = search.dist[border[j]]
                if (c == Double.POSITIVE_INFINITY) continue
                from.add(i); to.add(j); cost += c; length += search.len[border[j]].toFloat()
            }
        }
        val m = from.size
        val nodeLon = IntArray(k) { g.nodeLon[border[it]] }
        val nodeLat = IntArray(k) { g.nodeLat[border[it]] }
        val nodeEle = FloatArray(k) { g.nodeEle[border[it]] }
        val graph = RoutingGraph(
            g.source, nodeLon, nodeLat, nodeEle, from.toArray(), to.toArray(), FloatArray(m) { length[it] }, FloatArray(m), FloatArray(m),
            ByteArray(m) { WayClass.MOTORWAY.code.toByte() }, ByteArray(m), ByteArray(m) { WayAttrs.ONEWAY.toByte() },
            IntArray(m + 1), IntArray(0), IntArray(0), FloatArray(0),
        )
        return Cell(base.x, base.y, IntArray(k) { border[it] }, cost.toDoubleArray(), graph, clock() - t0)
    }

    /** Whether a node lies on the border of tile ([x], [y]) at zoom [z] (within [GraphPieces.JOIN_M]). */
    private fun onBorder(lonE6: Int, latE6: Int, x: Int, y: Int, z: Int): Boolean {
        val lat = latE6 / E6
        val fx = WebMercator.lonToX(lonE6 / E6, z) - x
        val fy = WebMercator.latToY(lat, z) - y
        val tileM = WebMercator.EARTH_CIRCUMFERENCE_M * cos(lat * PI / 180) / (1L shl z)
        val d = min(min(fx, 1 - fx), min(fy, 1 - fy)) * tileM
        return d <= 2 * GraphPieces.JOIN_M
    }

    /**
     * What makes a [GraphPiece] an overlay piece: its cell, and the base piece the shortcuts expand
     * into (held while the overlay piece is cached; the router's own view holds both during a search).
     */
    class Overlay(val cell: Cell, loadBase: () -> GraphPiece) {
        /** The tile's own piece, loaded on the first [unpack]. */
        val base: GraphPiece by lazy(loadBase)

        fun cost(e: Int): Double = cell.cost[e]

        /**
         * Shortcut [e] as the base edges it stands for, in travel order: (edge, forward) pairs of [base]'s
         * graph, found again by the same search that priced it.
         */
        fun unpack(e: Int): List<Pair<Int, Boolean>> {
            val g = base.graph
            val a = cell.baseNode[cell.graph.edgeFrom[e]]
            val b = cell.baseNode[cell.graph.edgeTo[e]]
            val s = TileSearch(g, CostModel(TravelMode.DRIVE))
            s.run(a, setOf(b))
            val out = ArrayList<Pair<Int, Boolean>>()
            var v = b
            while (v != a) {
                val how = s.via[v]
                check(how >= 0) { "shortcut $e of overlay ${cell.x}/${cell.y} does not unpack" }
                val edge = how shr 1
                val fwd = how and 1 == 0
                out += edge to fwd
                v = if (fwd) g.edgeFrom[edge] else g.edgeTo[edge]
            }
            out.reverse()
            return out
        }
    }

    /** Dijkstra over one tile's drive network (no `destination` roads), stopping once [targets] are settled. */
    private class TileSearch(val g: RoutingGraph, val model: CostModel) {
        val dist = DoubleArray(g.nodeCount)
        val len = DoubleArray(g.nodeCount)
        val via = IntArray(g.nodeCount)
        private val closed = BooleanArray(g.nodeCount)
        private val weight = DoubleArray(g.edgeCount) { e ->
            if (g.flags(e) and WayAttrs.MOTOR_DESTINATION != 0) Double.POSITIVE_INFINITY else model.weight(g, e)
        }

        fun run(source: Int, targets: Set<Int>) {
            dist.fill(Double.POSITIVE_INFINITY); via.fill(-1); closed.fill(false)
            dist[source] = 0.0; len[source] = 0.0
            val heap = MinHeap()
            heap.push(0.0, source)
            var left = targets.count { it != source }
            while (heap.size > 0 && left > 0) {
                val u = heap.pop()
                if (closed[u]) continue
                closed[u] = true
                if (u != source && u in targets) left--
                for (i in g.adjStart[u] until g.adjStart[u + 1]) {
                    val a = g.adjEdge[i]
                    val e = a shr 1
                    val fwd = a and 1 == 0 // u is the edge's from node
                    val w = weight[e]
                    if (w == Double.POSITIVE_INFINITY || !model.allows(g, e, fwd)) continue
                    val v = if (fwd) g.edgeTo[e] else g.edgeFrom[e]
                    if (closed[v]) continue
                    val c = dist[u] + g.edgeLength[e] * w
                    if (c < dist[v]) {
                        dist[v] = c; len[v] = len[u] + g.edgeLength[e]; via[v] = e * 2 + if (fwd) 0 else 1
                        heap.push(c, v)
                    }
                }
            }
        }
    }

    companion object {
        /** The smallest road class whose border crossings join overlay tiles. */
        val MIN_CLASS = WayClass.TERTIARY

        /** Drives whose ends are at least this far apart (great circle) use the overlay. */
        const val MIN_DISTANCE_M = 40_000.0

        /** Tiles within this distance of either end are searched in full. */
        const val DETAIL_M = 5_000.0

        private const val E6 = 1e6
    }
}

/** A binary min-heap of (priority, node) with lazy deletion. */
internal class MinHeap {
    private var pri = DoubleArray(64)
    private var node = IntArray(64)
    var size = 0
        private set

    fun push(p: Double, v: Int) {
        if (size == pri.size) { pri = pri.copyOf(size * 2); node = node.copyOf(size * 2) }
        var i = size++
        while (i > 0) {
            val parent = (i - 1) / 2
            if (pri[parent] <= p) break
            pri[i] = pri[parent]; node[i] = node[parent]
            i = parent
        }
        pri[i] = p; node[i] = v
    }

    fun pop(): Int {
        val top = node[0]
        size--
        if (size > 0) {
            val p = pri[size]
            val v = node[size]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && pri[c + 1] < pri[c]) c++
                if (pri[c] >= p) break
                pri[i] = pri[c]; node[i] = node[c]
                i = c
            }
            pri[i] = p; node[i] = v
        }
        return top
    }
}
