package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.pmtiles.ByteReader
import io.github.jasondorjeshort.dystopo.pmtiles.ByteWriter
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph.Companion.count
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph.Companion.fnv1a
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph.Companion.str
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Long drives (T77, PLANNING › Routing): a partition overlay over the graph tiles, the idea of
 * multi-level Dijkstra (Delling et al., "Customizable Route Planning") with the z11 graph tiles as
 * its cells, so it composes across packs the way the tiles do.
 *
 * **Level 1 (T77).** For one tile of one pack the overlay keeps only the tile's **border nodes**, the
 * nodes on the tile's border where a road of [MIN_CLASS] or larger crosses it, and one directed
 * **shortcut** for each pair of them that the tile's drive network connects, costing exactly the
 * cheapest drive inside the tile (one-ways, roads closed to cars and `destination` roads respected).
 * A long drive searches the tiles around its two ends in full and every other tile through its
 * overlay, which is a few dozen nodes instead of thousands, and expands the shortcuts it used back
 * into the tile's own edges ([Overlay.expand]), so the route has the same points, names and numbers
 * as one found without the overlay.
 *
 * **Level 2 (T89).** The same over the level-1 cells: a cell of [level2Zoom] (z8, 64 graph tiles) keeps
 * the level-1 border nodes on its own border and the cheapest drive between each pair through the
 * level-1 shortcuts inside it. A search uses it for a z8 tile with no end area in it whose graph tiles
 * all come from one pack; a level-2 shortcut expands into level-1 shortcuts and those into the base
 * edges. Level-2 cells are never built inside a search (that would decode up to 64 graph tiles for one
 * cell): they come from [buildLevel2] (the server's warm-up) or a cell file.
 *
 * **Cell files (T89).** [OverlayCellFile] keeps both levels of one graph file next to it, so a restarted
 * server reads cells instead of building them ([register]); a file is tied to its graph file's
 * [GraphSource] and ignored once the graph is rebuilt.
 *
 * Exact within the cells, approximate across them: a drive between the end areas crosses tile
 * borders only on roads of [MIN_CLASS] or larger, which every long drive does; when that finds
 * nothing [TileRouter] searches again without the overlay. Only tiles that one pack alone covers get
 * an overlay; where packs overlap or meet inside a tile it is searched in full, so the joins between
 * packs (anywhere on a z14 border) stay what they are.
 */
class DriveOverlay(
    /** The graph tile zoom (the level-1 cells). */
    val tileZoom: Int = GraphTiler.ZOOM,
    /** Decoded overlay cells kept (both levels), least recently used dropped first. */
    val maxTiles: Int = 4096,
    /** On a platform with threads, a lock (see [GraphTileCache]). */
    private val lock: (() -> Unit) -> Unit = { it() },
    private val clock: () -> Long = { 0L },
    /** T89: the level-2 cell zoom; [tileZoom] or more turns level 2 off. */
    val level2Zoom: Int = tileZoom - LEVEL2_STEP,
) {
    /** One tile's level-1 overlay: the base piece's border nodes, the shortcuts between them, and how to expand them. */
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

    /**
     * T89: one level-2 cell (a [level2Zoom] tile of one graph file): its border nodes, each level-1
     * border node `subNode[i]` of graph tile (`subX[i]`, `subY[i]`), and the shortcuts between them.
     */
    class Cell2(
        val x: Int,
        val y: Int,
        val subX: IntArray,
        val subY: IntArray,
        val subNode: IntArray,
        val cost: DoubleArray,
        val graph: RoutingGraph,
        val buildMs: Long,
    )

    /** A cell of one graph file (its [GraphSource] names the file and the build, so a rebuilt pack gets new cells). */
    private data class Key(val source: GraphSource, val level: Int, val x: Int, val y: Int)

    private val cells = LinkedHashMap<Key, Any?>()
    private val files = HashMap<GraphSource, OverlayCellFile>()
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
    /** T89: cells read from cell files, level-2 cells built, level-2 pieces handed to searches. */
    var loaded = 0L
        private set
    var built2 = 0L
        private set
    var used2 = 0L
        private set

    /** T89: whether level 2 is on (its zoom below the graph tile zoom). */
    val level2: Boolean get() = level2Zoom in 0 until tileZoom

    /** T89: cells of the graph file [file] describes are read from it from now on (and not built). */
    fun register(file: OverlayCellFile) = lock { files[file.source] = file }

    /** T89: forgets the cell file of [source] (its graph was rebuilt or its pack removed). */
    fun unregister(source: GraphSource) = lock { files.remove(source) }

    /** T89: whether a cell file is registered for [source]. */
    fun hasFile(source: GraphSource): Boolean {
        var has = false
        lock { has = files.containsKey(source) }
        return has
    }

    private fun file(source: GraphSource): OverlayCellFile? {
        var f: OverlayCellFile? = null
        lock { f = files[source] }
        return f
    }

    /** A cached cell as (found, cell); counts the hit. */
    private fun cached(k: Key): Pair<Boolean, Any?> {
        var found = false
        var cell: Any? = null
        lock { if (cells.containsKey(k)) { found = true; hits++; cell = cells.remove(k); cells[k] = cell } }
        return found to cell
    }

    private fun keep(k: Key, cell: Any?) = lock {
        cells[k] = cell
        while (cells.size > maxTiles) { val it = cells.keys.iterator(); it.next(); it.remove() }
    }

    /** The level-1 cell of tile ([x], [y]) of [source]: cached, else from its cell file, else built from [base]; and the base piece if it was loaded. */
    private fun cell(x: Int, y: Int, source: GraphSource, base: () -> GraphPiece?): Pair<Cell?, GraphPiece?> {
        val k = Key(source, 1, x, y)
        val (found, c) = cached(k)
        if (found) return c as Cell? to null
        file(source)?.let { f ->
            val got = f.cell1(x, y)
            lock { loaded++ }
            keep(k, got)
            return got to null
        }
        val b = base() ?: return null to null
        val made = build(b)
        lock {
            built++
            if (made != null) { buildMs += made.buildMs; shortcuts += made.cost.size; borderNodes += made.baseNode.size }
        }
        keep(k, made)
        return made to b
    }

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
     * base tile is loaded through [base] only when the cell is neither cached nor on file, or a shortcut
     * is expanded, so a long drive does not decode the tiles it passes through.
     */
    fun piece(x: Int, y: Int, archive: Int, source: GraphSource, base: () -> GraphPiece?): GraphPiece? {
        if (!source.drive) return null
        val (cell, loadedBase) = cell(x, y, source, base)
        // A new piece per search: it carries this search's base piece and archive index.
        return cell?.let { c ->
            GraphPiece(archive, x, y, c.graph, joinZoom = maxOf(tileZoom, source.zoom), overlay = Overlay(c) { loadedBase ?: base() ?: error("graph tile $x/$y of ${source.file} is gone") })
        }
    }

    /**
     * T89: the level-2 piece of [level2Zoom] tile ([x2], [y2]) of [source], when its cell is cached or on
     * file (never built here); null otherwise or when the tile has no border crossing. [level1] gives the
     * level-1 piece of a graph tile inside, for expanding the shortcuts a route uses.
     */
    fun piece2(x2: Int, y2: Int, archive: Int, source: GraphSource, level1: (Int, Int) -> GraphPiece?): GraphPiece? {
        if (!level2 || !source.drive) return null
        val k = Key(source, 2, x2, y2)
        val (found, c) = cached(k)
        val cell: Cell2? = if (found) c as Cell2? else {
            val f = file(source) ?: return null
            f.cell2(x2, y2).also { got -> lock { loaded++ }; keep(k, got) }
        }
        cell ?: return null
        lock { used2++ }
        val step = tileZoom - level2Zoom
        return GraphPiece(archive, x2 shl step, y2 shl step, cell.graph, joinZoom = maxOf(tileZoom, source.zoom), overlay = Overlay2(cell, step, level1))
    }

    /**
     * T89: builds (or returns the cached) level-2 cell of [level2Zoom] tile ([x2], [y2]) of [source] from the
     * level-1 cells of the graph tiles inside it that [has]; [base] loads a graph tile when its level-1 cell
     * must be built. For the server's warm-up and tests; searches never call it.
     */
    fun buildLevel2(x2: Int, y2: Int, source: GraphSource, has: (Int, Int) -> Boolean, base: (Int, Int) -> GraphPiece?): Cell2? {
        if (!level2 || !source.drive) return null
        val k = Key(source, 2, x2, y2)
        val (found, c) = cached(k)
        if (found) return c as Cell2?
        val t0 = clock()
        val step = tileZoom - level2Zoom
        val subs = ArrayList<Cell>()
        for (dy in 0 until (1 shl step)) for (dx in 0 until (1 shl step)) {
            val x = (x2 shl step) + dx
            val y = (y2 shl step) + dy
            if (!has(x, y)) continue
            cell(x, y, source) { base(x, y) }.first?.let { subs += it }
        }
        val union = Union(subs)
        // Border nodes of the level-2 cell: level-1 border nodes on its own border, one per place and level.
        val border = IntList()
        val seen = HashSet<Long>()
        for (u in 0 until union.size) {
            val (ci, j) = union.at(u)
            val g = subs[ci].graph
            if (!onBorder(g.nodeLon[j], g.nodeLat[j], x2, y2, level2Zoom)) continue
            if (seen.add(union.pos(u))) border.add(u)
        }
        val n = border.size
        val result: Cell2? = if (n == 0) null else {
            val from = IntList(); val to = IntList(); val cost = ArrayList<Double>(); val length = ArrayList<Float>()
            val target = HashMap<Long, Int>()
            for (i in 0 until n) target[union.pos(border[i])] = i
            for (i in 0 until n) {
                val s = union.search(border[i], Int.MAX_VALUE) { false }
                val reached = DoubleArray(n) { Double.POSITIVE_INFINITY }
                val len = DoubleArray(n)
                for (u in 0 until union.size) {
                    val t = target[union.pos(u)] ?: continue
                    if (s.dist[u] < reached[t]) { reached[t] = s.dist[u]; len[t] = s.len[u] }
                }
                for (j in 0 until n) {
                    if (j == i || reached[j] == Double.POSITIVE_INFINITY) continue
                    from.add(i); to.add(j); cost += reached[j]; length += len[j].toFloat()
                }
            }
            val m = from.size
            val subX = IntArray(n); val subY = IntArray(n); val subNode = IntArray(n)
            val lon = IntArray(n); val lat = IntArray(n); val level = ByteArray(n)
            for (i in 0 until n) {
                val (ci, j) = union.at(border[i])
                val sc = subs[ci]
                subX[i] = sc.x; subY[i] = sc.y; subNode[i] = j
                lon[i] = sc.graph.nodeLon[j]; lat[i] = sc.graph.nodeLat[j]; level[i] = sc.graph.nodeLevel[j]
            }
            Cell2(x2, y2, subX, subY, subNode, cost.toDoubleArray(), shortcutGraph(source, lon, lat, level, from.toArray(), to.toArray(), FloatArray(m) { length[it] }), clock() - t0)
        }
        lock {
            built2++
            if (result != null) { buildMs += result.buildMs; shortcuts += result.cost.size; borderNodes += result.subNode.size }
        }
        keep(k, result)
        return result
    }

    /**
     * T89: every cell of [source] that is cached now, both levels, for writing a cell file after a warm-up
     * ([OverlayCellFile.encode]); empty cells are left out (a file lists the cells that exist).
     */
    fun cellsOf(source: GraphSource): Pair<List<Cell>, List<Cell2>> {
        val one = ArrayList<Cell>()
        val two = ArrayList<Cell2>()
        lock {
            for ((k, v) in cells) {
                if (k.source != source) continue
                if (v is Cell) one += v
                if (v is Cell2) two += v
            }
        }
        return one to two
    }

    fun json(): Map<String, Any?> {
        var out: Map<String, Any?> = emptyMap()
        lock {
            out = linkedMapOf(
                "tiles" to cells.size, "built" to built, "hits" to hits, "buildMs" to buildMs,
                "borderNodes" to borderNodes, "shortcuts" to shortcuts, "minClass" to MIN_CLASS.label,
                // T89: the second level and the cell files.
                "level2Zoom" to if (level2) level2Zoom else null, "level2Built" to built2, "level2Used" to used2,
                "loadedFromFiles" to loaded, "cellFiles" to files.keys.map { it.file },
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
        val graph = shortcutGraph(
            g.source, IntArray(k) { g.nodeLon[border[it]] }, IntArray(k) { g.nodeLat[border[it]] }, ByteArray(k) { g.nodeLevel[border[it]] }, // T89: joins match levels
            from.toArray(), to.toArray(), FloatArray(m) { length[it] },
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
    open class Overlay(val cell: Cell?, loadBase: () -> GraphPiece) {
        /** The tile's own piece, loaded on the first [unpack]. */
        val base: GraphPiece by lazy(loadBase)

        open fun cost(e: Int): Double = cell!!.cost[e]

        /** T89: shortcut [e] as base edges in travel order: (piece, edge, forward). */
        open fun expand(e: Int): List<Triple<GraphPiece, Int, Boolean>> = unpack(e).map { (edge, fwd) -> Triple(base, edge, fwd) }

        /**
         * Shortcut [e] as the base edges it stands for, in travel order: (edge, forward) pairs of [base]'s
         * graph, found again by the same search that priced it.
         */
        fun unpack(e: Int): List<Pair<Int, Boolean>> {
            val c = cell!!
            val g = base.graph
            val a = c.baseNode[c.graph.edgeFrom[e]]
            val b = c.baseNode[c.graph.edgeTo[e]]
            val s = TileSearch(g, CostModel(TravelMode.DRIVE))
            s.run(a, setOf(b))
            val out = ArrayList<Pair<Int, Boolean>>()
            var v = b
            while (v != a) {
                val how = s.via[v]
                check(how >= 0) { "shortcut $e of overlay ${c.x}/${c.y} does not unpack" }
                val edge = how shr 1
                val fwd = how and 1 == 0
                out += edge to fwd
                v = if (fwd) g.edgeFrom[edge] else g.edgeTo[edge]
            }
            out.reverse()
            return out
        }
    }

    /**
     * T89: a level-2 piece's overlay: its shortcuts expand into the level-1 shortcuts of the graph tiles
     * inside ([level1] gives their pieces; [step] zooms between the levels), and those into base edges.
     */
    class Overlay2(val cell2: Cell2, private val step: Int, private val level1: (Int, Int) -> GraphPiece?) :
        Overlay(null, { error("a level-2 cell has no base tile") }) {
        override fun cost(e: Int): Double = cell2.cost[e]

        override fun expand(e: Int): List<Triple<GraphPiece, Int, Boolean>> {
            val c = cell2
            val subs = ArrayList<Cell>()
            val subPieces = ArrayList<GraphPiece>()
            for (dy in 0 until (1 shl step)) for (dx in 0 until (1 shl step)) {
                val p = level1((c.x shl step) + dx, (c.y shl step) + dy) ?: continue
                val cell = p.overlay?.cell ?: continue
                subs += cell
                subPieces += p
            }
            val union = Union(subs)
            fun unionNode(i: Int): Int {
                val ci = subs.indexOfFirst { it.x == c.subX[i] && it.y == c.subY[i] }
                check(ci >= 0) { "level-2 cell ${c.x}/${c.y}: graph tile ${c.subX[i]}/${c.subY[i]} has no level-1 cell" }
                return union.id(ci, c.subNode[i])
            }
            val a = unionNode(c.graph.edgeFrom[e])
            val bPos = union.pos(unionNode(c.graph.edgeTo[e]))
            // The first node settled at b's place is the cheapest (b may be reached through its twin in a neighbouring tile).
            var b = -1
            val s = union.search(a, 1) { u -> (union.pos(u) == bPos).also { if (it && b < 0) b = u } }
            check(b >= 0 && s.dist[b] < Double.POSITIVE_INFINITY) { "shortcut $e of level-2 cell ${c.x}/${c.y} does not unpack" }
            val chain = ArrayList<Pair<Int, Int>>() // (sub cell, level-1 shortcut), backwards
            var v = b
            while (v != a) {
                val how = s.via[v]
                check(how != Union.NONE) { "shortcut $e of level-2 cell ${c.x}/${c.y} does not unpack" }
                if (how >= 0) chain += union.cellOf(how) to union.shortcutOf(how)
                v = s.parent[v]
            }
            chain.reverse()
            val out = ArrayList<Triple<GraphPiece, Int, Boolean>>()
            for ((ci, sc) in chain) out += subPieces[ci].overlay!!.expand(sc)
            return out
        }
    }

    /**
     * T89: the level-1 cells inside a level-2 cell as one graph: node u is border node j of cell i; its
     * edges are that cell's shortcuts and zero-cost joins to the nodes at the same place and level in the
     * other cells.
     */
    private class Union(val subs: List<Cell>) {
        private val start = IntArray(subs.size + 1).also { s -> for (i in subs.indices) s[i + 1] = s[i] + subs[i].graph.nodeCount }
        val size = start[subs.size]
        private val edgeStart = IntArray(subs.size + 1).also { s -> for (i in subs.indices) s[i + 1] = s[i] + subs[i].graph.edgeCount }
        private val cellOfNode = IntArray(size).also { a -> for (i in subs.indices) for (u in start[i] until start[i + 1]) a[u] = i }
        private val positions = LongArray(size) { u ->
            val ci = cellOfNode[u]
            val j = u - start[ci]
            val g = subs[ci].graph
            (g.nodeLon[j].toLong() shl 32) xor (g.nodeLat[j].toLong() and 0xFFFFFFFFL) xor (g.nodeLevel[j].toLong() shl 62)
        }
        private val atPos = HashMap<Long, IntList>().also { m -> for (u in 0 until size) m.getOrPut(positions[u]) { IntList(2) }.add(u) }

        fun id(cell: Int, node: Int) = start[cell] + node
        fun at(u: Int): Pair<Int, Int> = cellOfNode[u] to u - start[cellOfNode[u]]
        fun pos(u: Int): Long = positions[u]
        fun cellOf(edgeId: Int): Int {
            var ci = 0
            while (edgeStart[ci + 1] <= edgeId) ci++
            return ci
        }
        fun shortcutOf(edgeId: Int): Int = edgeId - edgeStart[cellOf(edgeId)]

        class Result(val dist: DoubleArray, val len: DoubleArray, val parent: IntArray, val via: IntArray)

        /** Dijkstra from [source] until [want] settled nodes (other than the source) satisfied [isTarget], or everything is settled. */
        fun search(source: Int, want: Int, isTarget: (Int) -> Boolean): Result {
            val dist = DoubleArray(size) { Double.POSITIVE_INFINITY }
            val len = DoubleArray(size)
            val parent = IntArray(size) { -1 }
            val via = IntArray(size) { NONE }
            val closed = BooleanArray(size)
            dist[source] = 0.0
            val heap = MinHeap()
            heap.push(0.0, source)
            var left = want
            while (heap.size > 0 && left > 0) {
                val u = heap.pop()
                if (closed[u]) continue
                closed[u] = true
                if (u != source && isTarget(u)) left--
                if (left <= 0) break
                val ci = cellOfNode[u]
                val j = u - start[ci]
                val c = subs[ci]
                val g = c.graph
                for (i in g.adjStart[j] until g.adjStart[j + 1]) {
                    val a = g.adjEdge[i]
                    if (a and 1 != 0) continue // shortcuts are directed: taken from their from node only
                    val e = a shr 1
                    val v = start[ci] + g.edgeTo[e]
                    val d = dist[u] + c.cost[e]
                    if (!closed[v] && d < dist[v]) { dist[v] = d; len[v] = len[u] + g.edgeLength[e]; parent[v] = u; via[v] = edgeStart[ci] + e; heap.push(d, v) }
                }
                atPos[positions[u]]?.let { same ->
                    for (k in 0 until same.size) {
                        val v = same[k]
                        if (v == u || closed[v] || dist[u] >= dist[v]) continue
                        dist[v] = dist[u]; len[v] = len[u]; parent[v] = u; via[v] = JOIN; heap.push(dist[v], v)
                    }
                }
            }
            return Result(dist, len, parent, via)
        }

        companion object {
            const val NONE = -2
            const val JOIN = -1
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

        /** T89: level-2 cells are this many zooms above the graph tiles (z8 over z11: 64 graph tiles each). */
        const val LEVEL2_STEP = 3

        private const val E6 = 1e6

        /** A cell's shortcut graph: [lon], [lat], [level] per border node; directed shortcuts [from] -> [to]. */
        internal fun shortcutGraph(source: GraphSource, lon: IntArray, lat: IntArray, level: ByteArray, from: IntArray, to: IntArray, length: FloatArray): RoutingGraph {
            val m = from.size
            val k = lon.size
            return RoutingGraph(
                source, lon, lat, FloatArray(k) { Float.NaN }, from, to, length, FloatArray(m), FloatArray(m),
                ByteArray(m) { WayClass.MOTORWAY.code.toByte() }, ByteArray(m), ByteArray(m) { WayAttrs.ONEWAY.toByte() },
                IntArray(m + 1), IntArray(0), IntArray(0), FloatArray(0),
                nodeLevel = level,
            )
        }
    }
}

/**
 * T89: the drive overlay's cells of one graph file on disk, `<vector archive>-overlay.idx` next to the
 * graph file, so a restarted server reads them instead of building them. Cells are read on demand
 * through ranged reads, like graph tiles.
 *
 * ```
 * "DYSO" u8 version(=1) u32 headerLength
 * str sourceFile, str fingerprint, str demFingerprint, u8 sourceZoom, u64 builtAtMs   (the graph file's GraphSource)
 * u8 tileZoom, u8 level2Zoom, u8 minClass
 * varint cellCount, per cell: u8 level, varint x, varint y, varint bodyLength
 * u32 FNV-1a of every header byte before it
 * bodies in directory order, each: varint nodes, per node: (level 1: varint baseNode; level 2: varint
 *     subX - 8x, varint subY - 8y, varint subNode) zz dLonE6, zz dLatE6 (from the previous node), u8 level;
 *     varint shortcuts, per shortcut: varint from, varint to, u64 cost (IEEE double bits), u32 length (float bits);
 *     then u32 FNV-1a of the body
 * ```
 * A file lists every cell that exists for the graph file (a tile missing from it has no cell). It is
 * written after a full warm-up and is valid only for the [GraphSource] it names.
 */
class OverlayCellFile private constructor(
    val source: GraphSource,
    val tileZoom: Int,
    val level2Zoom: Int,
    private val dir: Map<Long, LongArray>, // key(level, x, y) -> [offset, length]
    val cellCount: Int,
    val fileBytes: Long,
    private val read: (Long, Int) -> ByteArray,
) {
    private class Shortcuts(val from: IntArray, val to: IntArray, val cost: DoubleArray, val length: FloatArray)

    /** The level-1 cell of graph tile ([x], [y]), or null (no cell there). Throws IllegalArgumentException when it is corrupt. */
    fun cell1(x: Int, y: Int): DriveOverlay.Cell? {
        val r = body(1, x, y) ?: return null
        return decoding(1, x, y) {
            val n = r.count()
            val baseNode = IntArray(n); val lon = IntArray(n); val lat = IntArray(n); val level = ByteArray(n)
            nodes(r, n, lon, lat, level) { i -> baseNode[i] = r.count() }
            val s = shortcuts(r, n)
            DriveOverlay.Cell(x, y, baseNode, s.cost, DriveOverlay.shortcutGraph(source, lon, lat, level, s.from, s.to, s.length), 0)
        }
    }

    /** The level-2 cell of tile ([x], [y]) at [level2Zoom], or null. Throws IllegalArgumentException when it is corrupt. */
    fun cell2(x: Int, y: Int): DriveOverlay.Cell2? {
        val r = body(2, x, y) ?: return null
        return decoding(2, x, y) {
            val n = r.count()
            val step = tileZoom - level2Zoom
            val subX = IntArray(n); val subY = IntArray(n); val subNode = IntArray(n)
            val lon = IntArray(n); val lat = IntArray(n); val level = ByteArray(n)
            nodes(r, n, lon, lat, level) { i -> subX[i] = (x shl step) + r.count(); subY[i] = (y shl step) + r.count(); subNode[i] = r.count() }
            val s = shortcuts(r, n)
            DriveOverlay.Cell2(x, y, subX, subY, subNode, s.cost, DriveOverlay.shortcutGraph(source, lon, lat, level, s.from, s.to, s.length), 0)
        }
    }

    private fun <T> decoding(level: Int, x: Int, y: Int, block: () -> T): T = try {
        block()
    } catch (e: IllegalStateException) {
        throw IllegalArgumentException("corrupt overlay cell $level/$x/$y: ${e.message}", e)
    }

    private fun body(level: Int, x: Int, y: Int): ByteReader? {
        val e = dir[key(level, x, y)] ?: return null
        val bytes = read(e[0], e[1].toInt())
        require(bytes.size.toLong() == e[1]) { "overlay cell $level/$x/$y truncated" }
        val stored = ByteReader(bytes, bytes.size - 4).u32().toInt()
        require(stored == fnv1a(bytes, bytes.size - 4)) { "overlay cell $level/$x/$y checksum mismatch" }
        return ByteReader(bytes.copyOfRange(0, bytes.size - 4))
    }

    private inline fun nodes(r: ByteReader, n: Int, lon: IntArray, lat: IntArray, level: ByteArray, ref: (Int) -> Unit) {
        var plon = 0
        var plat = 0
        for (i in 0 until n) {
            ref(i)
            plon += unzz(r.varint()); plat += unzz(r.varint())
            lon[i] = plon; lat[i] = plat
            level[i] = r.u8().toByte()
        }
    }

    private fun shortcuts(r: ByteReader, n: Int): Shortcuts {
        val m = r.count()
        val s = Shortcuts(IntArray(m), IntArray(m), DoubleArray(m), FloatArray(m))
        for (e in 0 until m) {
            s.from[e] = r.count().also { require(it < n) { "shortcut node out of range" } }
            s.to[e] = r.count().also { require(it < n) { "shortcut node out of range" } }
            s.cost[e] = Double.fromBits(r.u64())
            s.length[e] = Float.fromBits(r.u32().toInt())
        }
        return s
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "file" to source.file, "builtAtMs" to source.builtAtMs, "tileZoom" to tileZoom, "level2Zoom" to level2Zoom,
        "cells" to cellCount, "bytes" to fileBytes,
    )

    companion object {
        const val VERSION = 1
        const val SUFFIX = "-overlay.idx"
        private val MAGIC = byteArrayOf(0x44, 0x59, 0x53, 0x4F) // "DYSO"

        /** The cell file next to a vector archive: `<archive name>-overlay.idx` (beside `<archive name>-graph.idx`). */
        fun fileNameFor(vectorFileName: String): String = vectorFileName.substringBeforeLast('.') + SUFFIX

        private fun key(level: Int, x: Int, y: Int): Long = (level.toLong() shl 60) xor (x.toLong() shl 30) xor y.toLong()
        private fun zz(v: Int): Long = ((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL
        private fun unzz(v: Long): Int = ((v ushr 1) xor -(v and 1)).toInt()

        /** The cells of one graph file ([source]) as a cell file. */
        fun encode(source: GraphSource, tileZoom: Int, level2Zoom: Int, one: List<DriveOverlay.Cell>, two: List<DriveOverlay.Cell2>): ByteArray {
            class Entry(val level: Int, val x: Int, val y: Int, val body: ByteArray)
            fun body(nodes: Int, writeRef: ByteWriter.(Int) -> Unit, g: RoutingGraph, cost: DoubleArray): ByteArray {
                val w = ByteWriter(64 + nodes * 12 + g.edgeCount * 16)
                w.varint(nodes.toLong())
                var plon = 0
                var plat = 0
                for (i in 0 until nodes) {
                    w.writeRef(i)
                    w.varint(zz(g.nodeLon[i] - plon)); plon = g.nodeLon[i]
                    w.varint(zz(g.nodeLat[i] - plat)); plat = g.nodeLat[i]
                    w.u8(g.nodeLevel[i].toInt() and 0xFF)
                }
                w.varint(g.edgeCount.toLong())
                for (e in 0 until g.edgeCount) {
                    w.varint(g.edgeFrom[e].toLong()); w.varint(g.edgeTo[e].toLong())
                    w.u64(cost[e].toRawBits()); w.u32(g.edgeLength[e].toRawBits().toLong() and 0xFFFFFFFFL)
                }
                val b = w.toByteArray()
                return ByteWriter(b.size + 4).apply { bytes(b); u32(fnv1a(b, b.size).toLong() and 0xFFFFFFFFL) }.toByteArray()
            }
            val step = tileZoom - level2Zoom
            val entries = one.sortedWith(compareBy({ it.y }, { it.x })).map { c ->
                Entry(1, c.x, c.y, body(c.baseNode.size, { i -> varint(c.baseNode[i].toLong()) }, c.graph, c.cost))
            } + two.sortedWith(compareBy({ it.y }, { it.x })).map { c ->
                Entry(2, c.x, c.y, body(c.subNode.size, { i ->
                    varint((c.subX[i] - (c.x shl step)).toLong()); varint((c.subY[i] - (c.y shl step)).toLong()); varint(c.subNode[i].toLong())
                }, c.graph, c.cost))
            }
            val h = ByteWriter(256 + entries.size * 12)
            h.str(source.file); h.str(source.fingerprint); h.str(source.demFingerprint); h.u8(source.zoom); h.u64(source.builtAtMs)
            h.u8(tileZoom); h.u8(level2Zoom); h.u8(DriveOverlay.MIN_CLASS.code)
            h.varint(entries.size.toLong())
            for (e in entries) { h.u8(e.level); h.varint(e.x.toLong()); h.varint(e.y.toLong()); h.varint(e.body.size.toLong()) }
            val header = h.toByteArray()
            val head = ByteWriter(9 + header.size)
            head.bytes(MAGIC); head.u8(VERSION); head.u32(header.size.toLong() + 4); head.bytes(header)
            val headBytes = head.toByteArray()
            val out = ByteWriter(headBytes.size + 4 + entries.sumOf { it.body.size })
            out.bytes(headBytes)
            out.u32(fnv1a(headBytes, headBytes.size).toLong() and 0xFFFFFFFFL)
            for (e in entries) out.bytes(e.body)
            return out.toByteArray()
        }

        /**
         * Opens a cell file through [read] (offset, length) -> bytes, reading only its header. Throws
         * IllegalArgumentException for anything but a valid version 1 file.
         */
        fun open(read: (Long, Int) -> ByteArray): OverlayCellFile {
            val first = read(0, 9)
            require(first.size == 9 && first.copyOfRange(0, 4).contentEquals(MAGIC)) { "not an overlay cell file" }
            val version = first[4].toInt() and 0xFF
            require(version == VERSION) { "overlay cell file version $version, expected $VERSION" }
            val headerLength = ByteReader(first, 5).u32()
            require(headerLength in 4..(64L shl 20)) { "bad overlay cell file header length $headerLength" }
            val rest = read(9, headerLength.toInt())
            require(rest.size.toLong() == headerLength) { "overlay cell file header truncated" }
            val all = first + rest
            require(ByteReader(all, all.size - 4).u32().toInt() == fnv1a(all, all.size - 4)) { "overlay cell file header checksum mismatch" }
            try {
                val r = ByteReader(all.copyOfRange(0, all.size - 4), 9)
                val src = GraphSource(r.str(), r.str(), r.str(), r.u8(), r.u64(), drive = true)
                val tz = r.u8()
                val z2 = r.u8()
                r.u8() // minClass
                val n = r.count()
                var offset = all.size.toLong()
                val dir = HashMap<Long, LongArray>(n * 2)
                repeat(n) {
                    val level = r.u8(); val x = r.count(); val y = r.count(); val len = r.count()
                    require(level in 1..2 && len >= 4) { "bad overlay cell entry" }
                    dir[key(level, x, y)] = longArrayOf(offset, len.toLong())
                    offset += len
                }
                return OverlayCellFile(src, tz, z2, dir, n, offset, read)
            } catch (e: IllegalStateException) {
                throw IllegalArgumentException("corrupt overlay cell file header: ${e.message}", e)
            }
        }

        /** [open] over bytes in memory. */
        fun open(bytes: ByteArray): OverlayCellFile = open { offset, length ->
            bytes.copyOfRange(offset.coerceAtMost(bytes.size.toLong()).toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
        }
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
