package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.MvtDecoder
import io.github.jasondorjeshort.dystopo.packs.PackPrecedence
import io.github.jasondorjeshort.dystopo.packs.VectorProfile
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesHeader
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.TileIds
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.search.LayerFilter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/** A routable line in lon/lat degrees (for building a graph from anything but a pack: tests, imports). */
class WayLine(val lons: DoubleArray, val lats: DoubleArray, val attrs: WayAttrs) {
    init {
        require(lons.size == lats.size && lons.size >= 2) { "a line needs two or more points" }
    }

    companion object {
        /** A line through [points], each (lat, lon). */
        fun of(attrs: WayAttrs, vararg points: Pair<Double, Double>) =
            WayLine(DoubleArray(points.size) { points[it].second }, DoubleArray(points.size) { points[it].first }, attrs)
    }
}

/** Counters from one graph build, for reports. */
data class GraphBuildStats(
    val zoom: Int,
    val tiles: Int,
    val tileErrors: Int,
    /** Routable `transportation` features read. */
    val features: Int,
    /** Line pieces after clipping to the tiles (one feature can give several). */
    val lines: Int,
    val inputVertices: Int,
    /** Distinct vertices after snapping within [RoutingGraphBuilder.SNAP_M]. */
    val vertices: Int,
    /** Junctions added where lines cross or touch without a shared vertex. */
    val crossings: Int,
    val nodes: Int,
    val edges: Int,
    val duplicateEdges: Int,
    val elevationSamples: Int,
    val timingsMs: Map<String, Long>,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "zoom" to zoom, "tiles" to tiles, "tileErrors" to tileErrors, "features" to features, "lines" to lines,
        "inputVertices" to inputVertices, "vertices" to vertices, "crossings" to crossings, "nodes" to nodes, "edges" to edges,
        "duplicateEdges" to duplicateEdges, "elevationSamples" to elevationSamples, "timingsMs" to timingsMs,
    )
}

/**
 * T149: the size of one vector archive's graph build, read before it starts ([RoutingGraphBuilder.measure]): the tiles at
 * the read zoom, and the routable line vertices of an even sample of them scaled to every tile ([vertices], what the
 * build's arrays grow with). [estimateBytes] is the heap the build may take at its peak, the margin included.
 */
data class GraphBuildSize(
    val zoom: Int,
    val tiles: Int,
    val archiveBytes: Long,
    val sampledTiles: Int,
    val sampledVertices: Long,
    /** Routable vertices in every tile at [zoom], scaled from the sample (the build's `inputVertices`, about). */
    val vertices: Long,
) {
    val fittedPeakBytes: Long get() = RoutingGraphBuilder.fittedPeakBytes(vertices)
    val estimateBytes: Long get() = RoutingGraphBuilder.estimatePeakBytes(vertices)

    fun json(): Map<String, Any?> = linkedMapOf(
        "zoom" to zoom, "tiles" to tiles, "archiveBytes" to archiveBytes, "sampledTiles" to sampledTiles,
        "sampledVertices" to sampledVertices, "vertices" to vertices, "fittedPeakBytes" to fittedPeakBytes, "estimateBytes" to estimateBytes,
    )
}

/**
 * Builds a [RoutingGraph] from the routable lines of a vector pack ([WayAttrs.of]: paths, footways,
 * cycleways, tracks, service and minor roads up to primary, and since T73 trunks and motorways for
 * drive mode; outdoors or OpenMapTiles schema):
 *
 * 1. Every tile at the archive's max zoom (capped at 14, where both schemas hold every line unmerged)
 *    is decoded; each line is clipped to its tile's exact square (tiles repeat a buffer around their
 *    edge), so the pieces of a line in neighbouring tiles meet end to end on the shared tile border.
 * 2. Vertices within [SNAP_M] of each other become one, which joins those pieces, shared OSM nodes
 *    and lines that merely end at the same spot. T89: a point where a bridge or tunnel was cut at a tile
 *    border snaps only to cut points of bridges (or tunnels) and becomes a node of that level
 *    ([RoutingGraph.nodeLevel]), so a road passing under a bridge at a tile border is not joined to it.
 * 3. Where two segments cross or touch without a shared vertex, a vertex is added to both, so the
 *    junction exists; not for bridges and tunnels, which pass over or under what they cross, and not
 *    between a trunk or motorway and anything but another road (T73: a trail crossing a highway does
 *    not join it, so hike and bike graphs keep their edges).
 * 4. Lines are split into edges at every vertex used more than once and at line ends. Edges run in
 *    the line's direction, so one-way flags (T73) keep their meaning; a line drawn twice (the same
 *    vertices) is one edge whose drive flags allow what either copy allowed.
 * 5. Elevation from [elevation] (when given) is sampled at every vertex, and the edge's ascent and
 *    descent from samples every [SAMPLE_M] along it.
 * 6. T89: the component summary ([RoutingGraph.footComponent], [RoutingGraph.driveComponent]): the
 *    connected parts of the network hiking and cycling may use and that cars may use (one-ways ignored),
 *    each marked open when it reaches a tile the pack does not have (it may go on in another pack), so
 *    the router refuses a route between two parts nothing joins without searching either.
 *
 * Coordinates are snapped in the integer frame of the read zoom (4096 units per tile).
 * [progress] is called after each tile with (done, total).
 */
class RoutingGraphBuilder(
    private val elevation: ElevationSampler? = null,
    private val demFingerprint: String = "",
    private val progress: (Int, Int) -> Unit = { _, _ -> },
) {
    var stats: GraphBuildStats? = null
        private set

    private val timings = LinkedHashMap<String, Long>()
    private var clock: () -> Long = { 0L }

    /** A monotonic millisecond clock for [GraphBuildStats.timingsMs] (commonMain has none); optional. */
    fun withClock(nowMs: () -> Long): RoutingGraphBuilder = apply { clock = nowMs }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val t0 = clock()
        return block().also { timings[label] = (timings[label] ?: 0L) + (clock() - t0) }
    }

    /** Builds the graph of the vector archive [reader], the file [fileName]. */
    fun build(reader: PmtilesReader, fileName: String, builtAtMs: Long = 0L): RoutingGraph {
        val h = reader.header
        require(h.tileType == TileType.MVT) { "not a vector archive (tile type ${h.tileType.label})" }
        val z = min(h.maxZoom, MAX_ZOOM)
        val metadata = runCatching { reader.metadata() }.getOrDefault(emptyMap())
        val openMapTiles = PackPrecedence.vectorProfile(metadata) == VectorProfile.OPENMAPTILES
        val ids = timed("directory") { tileIdsAt(reader, z) }
        val acc = Accumulator(z, (h.minLat + h.maxLat) / 2)
        var errors = 0
        var features = 0
        timed("readTiles") {
            ids.forEachIndexed { i, id ->
                val t = TileIds.toZxy(id)
                try {
                    val bytes = reader.tile(t.z, t.x, t.y)
                    if (bytes != null) {
                        val tile = MvtDecoder.decode(LayerFilter.filter(bytes, LAYERS))
                        for (layer in tile.layers) {
                            if (layer.name != LAYER) continue
                            val scale = UNITS.toDouble() / layer.extent
                            for (f in layer.features) {
                                val g = f.geometry as? Geometry.Lines ?: continue
                                val attrs = WayAttrs.of(f.properties, openMapTiles) ?: continue
                                features++
                                for (line in g.lines) {
                                    val xs = DoubleArray(line.size) { t.x * UNITS + line[it].x * scale }
                                    val ys = DoubleArray(line.size) { t.y * UNITS + line[it].y * scale }
                                    acc.addClipped(xs, ys, attrs, t.x * UNITS.toDouble(), t.y * UNITS.toDouble(), (t.x + 1) * UNITS.toDouble(), (t.y + 1) * UNITS.toDouble())
                                }
                            }
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    errors++ // one bad tile must not sink the graph
                }
                progress(i + 1, ids.size)
            }
        }
        val present = HashSet<Long>(ids.size * 2)
        for (id in ids) { val t = TileIds.toZxy(id); present += tileKey(t.x, t.y) }
        return assemble(acc, GraphSource(fileName, fingerprint(h), demFingerprint, z, builtAtMs, drive = driveCapable(metadata)), ids.size, errors, features, present)
    }

    /** Builds a graph from lon/lat lines (tests; any non-pack source), snapping in the zoom-14 frame. */
    fun build(lines: List<WayLine>, source: GraphSource): RoutingGraph {
        val z = source.zoom
        val lat0 = if (lines.isEmpty()) 0.0 else lines.sumOf { it.lats.average() } / lines.size
        val acc = Accumulator(z, lat0)
        val world = UNITS.toDouble() * (1 shl z)
        for (l in lines) {
            acc.add(DoubleArray(l.lons.size) { (l.lons[it] + 180) / 360 * world }, DoubleArray(l.lats.size) { mercY(l.lats[it]) * world }, l.attrs)
        }
        progress(1, 1)
        return assemble(acc, source, 0, 0, lines.size, null)
    }

    /** Line pieces in world units of zoom [z] (4096 per tile). */
    private inner class Accumulator(val z: Int, lat0: Double) {
        val xs = ArrayList<DoubleArray>()
        val ys = ArrayList<DoubleArray>()
        val attrs = ArrayList<WayAttrs>()
        /** T89: per line, which of its points were made by clipping at the tile border (null: none, or not a bridge or tunnel). */
        val clips = ArrayList<BooleanArray?>()
        /** Metres per world unit at the pack's middle latitude. */
        val metersPerUnit = 40_075_016.686 * cos(lat0 * PI / 180) / (UNITS.toDouble() * (1 shl z))
        var inputVertices = 0

        fun add(x: DoubleArray, y: DoubleArray, a: WayAttrs, clip: BooleanArray? = null) {
            if (x.size < 2) return
            xs += x; ys += y; attrs += a; clips += clip
            inputVertices += x.size
        }

        /** Adds the parts of the line inside [x0, x1] x [y0, y1] (Liang-Barsky per segment). */
        fun addClipped(x: DoubleArray, y: DoubleArray, a: WayAttrs, x0: Double, y0: Double, x1: Double, y1: Double) {
            var px = DoubleList()
            var py = DoubleList()
            var pc = ArrayList<Boolean>()
            fun flush() {
                if (px.size >= 2) add(px.toArray(), py.toArray(), a, if (a.brunnel && pc.any { it }) pc.toBooleanArray() else null)
                px = DoubleList(); py = DoubleList(); pc = ArrayList()
            }
            for (i in 0 until x.size - 1) {
                val ax = x[i]; val ay = y[i]; val bx = x[i + 1]; val by = y[i + 1]
                val dx = bx - ax; val dy = by - ay
                var t0 = 0.0
                var t1 = 1.0
                var visible = true
                for ((p, q) in arrayOf(-dx to ax - x0, dx to x1 - ax, -dy to ay - y0, dy to y1 - ay)) {
                    if (p == 0.0) {
                        if (q < 0) { visible = false; break }
                    } else {
                        val r = q / p
                        if (p < 0) { if (r > t1) { visible = false; break }; if (r > t0) t0 = r }
                        else { if (r < t0) { visible = false; break }; if (r < t1) t1 = r }
                    }
                }
                if (!visible) { flush(); continue }
                val sx = ax + t0 * dx; val sy = ay + t0 * dy
                val ex = ax + t1 * dx; val ey = ay + t1 * dy
                if (px.size == 0 || px[px.size - 1] != sx || py[py.size - 1] != sy) {
                    flush()
                    px.add(sx); py.add(sy); pc += t0 > 0.0
                }
                px.add(ex); py.add(ey); pc += t1 < 1.0
                if (t1 < 1.0) flush() // left the tile
            }
            flush()
        }
    }

    /** Snapped vertices with a hash grid of cell [tol]; T89: a vertex snaps only to vertices of its own level. */
    private class Vertices(val tol: Double) {
        val x = DoubleList()
        val y = DoubleList()
        val level = ArrayList<Byte>()
        private val cells = HashMap<Long, IntList>()
        private fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xFFFFFFFFL)

        /** The vertex within [tol] of (px, py) nearest to it, or a new one there. */
        fun id(px: Double, py: Double, lv: Byte = 0): Int {
            val cx = floor(px / tol).toLong()
            val cy = floor(py / tol).toLong()
            var best = -1
            var bestD = tol * tol
            for (dy in -1L..1L) for (dx in -1L..1L) {
                val list = cells[key(cx + dx, cy + dy)] ?: continue
                for (i in 0 until list.size) {
                    val v = list[i]
                    if (level[v] != lv) continue
                    val ddx = x[v] - px; val ddy = y[v] - py
                    val d = ddx * ddx + ddy * ddy
                    if (d <= bestD) { bestD = d; best = v }
                }
            }
            if (best >= 0) return best
            val v = x.size
            x.add(px); y.add(py); level += lv
            cells.getOrPut(key(cx, cy)) { IntList(2) }.add(v)
            return v
        }
    }

    /**
     * [present]: the archive's tiles at the read zoom ([tileKey]), for the component summary's open flag (a
     * component with a node on the border of a tile the archive does not have may go on in another pack);
     * null without an archive (every component counts as open).
     */
    private fun assemble(acc: Accumulator, source: GraphSource, tiles: Int, errors: Int, features: Int, present: Set<Long>?): RoutingGraph {
        val tol = SNAP_M / acc.metersPerUnit
        val verts = Vertices(tol)
        // 2. Snap.
        val lines = timed("snap") {
            val out = ArrayList<IntArray>(acc.xs.size)
            for (i in acc.xs.indices) {
                val ids = IntList(acc.xs[i].size)
                val clip = acc.clips[i]
                val lv = acc.attrs[i].brunnelKind.toByte()
                for (k in acc.xs[i].indices) {
                    // T89: a bridge or tunnel cut at the tile border joins only its own other piece there.
                    val v = verts.id(acc.xs[i][k], acc.ys[i][k], if (clip != null && clip[k]) lv else 0)
                    if (ids.size == 0 || ids[ids.size - 1] != v) ids.add(v)
                }
                out += ids.toArray()
            }
            out
        }
        val vertexCountBeforeCrossings = verts.x.size
        // 3. Crossings.
        val crossed = timed("crossings") { addCrossings(lines, acc.attrs, verts, tol) }
        // 4. Split at junctions.
        val occ = IntArray(verts.x.size)
        val isEnd = BooleanArray(verts.x.size)
        for (l in crossed) {
            if (l.size < 2) continue
            for (v in l) occ[v]++
            isEnd[l[0]] = true
            isEnd[l[l.size - 1]] = true
        }
        val world = UNITS.toDouble() * (1 shl acc.z)
        fun lonE6(v: Int): Int = ((verts.x[v] / world * 360.0 - 180.0) * E6).roundToInt()
        fun latE6(v: Int): Int = (unmercY(verts.y[v] / world) * E6).roundToInt()

        val nodeOf = IntArray(verts.x.size) { -1 }
        val nodeVerts = IntList()
        fun node(v: Int): Int {
            if (nodeOf[v] < 0) { nodeOf[v] = nodeVerts.size; nodeVerts.add(v) }
            return nodeOf[v]
        }
        val eFrom = IntList(); val eTo = IntList(); val eCls = IntList(); val eAttrs = IntList(); val eFlags = IntList(); val eSpeed = IntList()
        val eName = IntList() // T49: 0 = unnamed, else 1 + index into names
        val names = ArrayList<String>()
        val nameIds = HashMap<String, Int>()
        val geomStart = IntList(); geomStart.add(0)
        val geomVerts = IntList()
        // Canonical vertex list -> (edge index, or -1 for a dropped zero-length edge; whether the edge runs the canonical way).
        val seen = HashMap<String, Pair<Int, Boolean>>()
        var duplicates = 0
        timed("split") {
            for ((li, l) in crossed.withIndex()) {
                if (l.size < 2) continue
                val a = acc.attrs[li]
                var start = 0
                for (k in 1 until l.size) {
                    val v = l[k]
                    if (k < l.size - 1 && occ[v] < 2 && !isEnd[v]) continue
                    // Edge from l[start] to l[k].
                    val from = l[start]
                    val mid = (start + 1 until k).map { l[it] }
                    val fwd = listOf(from) + mid + v
                    val canonical = fwd.first() < fwd.last() || (fwd.first() == fwd.last() && fwd.size > 2 && fwd[1] <= fwd[fwd.size - 2])
                    val key = (if (canonical) fwd else fwd.reversed()).joinToString(",")
                    val prev = seen[key]
                    if (prev != null) {
                        duplicates++
                        if (prev.first >= 0) eFlags[prev.first] = mergeDriveFlags(eFlags[prev.first], a.flags, sameDirection = prev.second == canonical)
                    } else if (from == v && mid.isEmpty()) {
                        seen[key] = -1 to canonical
                    } else {
                        seen[key] = eFrom.size to canonical
                        eFrom.add(node(from)); eTo.add(node(v))
                        eCls.add(a.cls.code); eAttrs.add(a.packed); eFlags.add(a.flags); eSpeed.add(a.maxspeedKmh)
                        eName.add(a.name?.let { nm -> nameIds.getOrPut(nm) { names += nm; names.size } } ?: 0)
                        for (m in mid) geomVerts.add(m)
                        geomStart.add(geomVerts.size)
                    }
                    start = k
                }
            }
        }
        // 5. Coordinates, elevation, lengths, ascent and descent.
        val n = nodeVerts.size
        val m = eFrom.size
        val nodeLon = IntArray(n) { lonE6(nodeVerts[it]) }
        val nodeLat = IntArray(n) { latE6(nodeVerts[it]) }
        val gs = geomStart.toArray()
        val gv = geomVerts.toArray()
        val gLon = IntArray(gv.size) { lonE6(gv[it]) }
        val gLat = IntArray(gv.size) { latE6(gv[it]) }
        var samples = 0
        val sampler = elevation
        val len = FloatArray(m)
        val asc = FloatArray(m)
        val desc = FloatArray(m)
        val (nodeEle, gEle) = timed("elevation") {
            // Pass 1: every point to sample (nodes, vertices, then points every SAMPLE_M along each segment).
            val sLat = DoubleList(n + gv.size * 2)
            val sLon = DoubleList(n + gv.size * 2)
            for (i in 0 until n) { sLat.add(nodeLat[i] / E6); sLon.add(nodeLon[i] / E6) }
            for (i in gv.indices) { sLat.add(gLat[i] / E6); sLon.add(gLon[i] / E6) }
            val segLen = DoubleList(gv.size + 2 * m)
            for (e in 0 until m) {
                val cnt = gs[e + 1] - gs[e] + 2
                fun lat(k: Int) = (if (k == 0) nodeLat[eFrom[e]] else if (k == cnt - 1) nodeLat[eTo[e]] else gLat[gs[e] + k - 1]) / E6
                fun lon(k: Int) = (if (k == 0) nodeLon[eFrom[e]] else if (k == cnt - 1) nodeLon[eTo[e]] else gLon[gs[e] + k - 1]) / E6
                for (k in 1 until cnt) {
                    val d = TrackStats.distanceM(lat(k - 1), lon(k - 1), lat(k), lon(k))
                    segLen.add(d)
                    if (sampler == null) continue
                    val steps = ceil(d / SAMPLE_M).toInt()
                    for (st in 1 until steps) {
                        val f = st.toDouble() / steps
                        sLat.add(lat(k - 1) + (lat(k) - lat(k - 1)) * f)
                        sLon.add(lon(k - 1) + (lon(k) - lon(k - 1)) * f)
                    }
                }
            }
            // Sample in DEM tile order, so each tile is decoded once however small the sampler's cache.
            val total = if (sampler == null) 0 else sLat.size
            val values = DoubleArray(total)
            if (sampler != null) {
                val order = LongArray(total) { i ->
                    val tx = ((sLon[i] + 180) / 360 * (1 shl SORT_ZOOM)).toLong().coerceIn(0, (1L shl SORT_ZOOM) - 1)
                    val ty = (mercY(sLat[i]) * (1 shl SORT_ZOOM)).toLong().coerceIn(0, (1L shl SORT_ZOOM) - 1)
                    (((ty shl SORT_ZOOM) or tx) shl 32) or i.toLong()
                }
                order.sort()
                for (o in order) {
                    val i = (o and 0xFFFFFFFFL).toInt()
                    values[i] = sampler.elevation(sLat[i], sLon[i])
                }
                samples = total
            }
            val nodeEle = FloatArray(n) { if (sampler == null) Float.NaN else RoutingGraph.quantizeEle(values[it]) }
            val gEle = FloatArray(gv.size) { if (sampler == null) Float.NaN else RoutingGraph.quantizeEle(values[n + it]) }
            // Pass 2: lengths, ascent and descent, reading the samples in the order pass 1 queued them.
            var next = n + gv.size
            var seg = 0
            for (e in 0 until m) {
                val cnt = gs[e + 1] - gs[e] + 2
                fun ele(k: Int) = (if (k == 0) nodeEle[eFrom[e]] else if (k == cnt - 1) nodeEle[eTo[e]] else gEle[gs[e] + k - 1]).toDouble()
                var length = 0.0
                var up = 0.0
                var down = 0.0
                var prev = ele(0)
                for (k in 1 until cnt) {
                    val d = segLen[seg++]
                    length += d
                    if (sampler == null) continue
                    val steps = ceil(d / SAMPLE_M).toInt()
                    for (st in 1..steps) {
                        val cur = if (st == steps) ele(k) else values[next++]
                        if (!cur.isNaN() && !prev.isNaN()) {
                            if (cur > prev) up += cur - prev else down += prev - cur
                        }
                        if (!cur.isNaN()) prev = cur
                    }
                }
                len[e] = RoutingGraph.quantizeLength(length)
                asc[e] = RoutingGraph.quantizeLength(up)
                desc[e] = RoutingGraph.quantizeLength(down)
            }
            nodeEle to gEle
        }
        val plain = RoutingGraph(
            source, nodeLon, nodeLat, nodeEle, eFrom.toArray(), eTo.toArray(), len, asc, desc,
            ByteArray(m) { eCls[it].toByte() }, ByteArray(m) { eAttrs[it].toByte() }, ByteArray(m) { eFlags[it].toByte() },
            gs, gLon, gLat, gEle, eName.toArray(), names, ByteArray(m) { eSpeed[it].toByte() },
            ByteArray(n) { verts.level[nodeVerts[it]] },
        )
        // 6. T89: the component summary.
        val graph = timed("components") {
            fun open(node: Int): Boolean {
                if (present == null) return true
                val vx = verts.x[nodeVerts[node]] / UNITS
                val vy = verts.y[nodeVerts[node]] / UNITS
                val d = tol * 2 / UNITS // within two snap tolerances of a tile border
                for (ox in doubleArrayOf(-d, d)) for (oy in doubleArrayOf(-d, d)) {
                    if (tileKey(floor(vx + ox).toInt(), floor(vy + oy).toInt()) !in present) return true
                }
                return false
            }
            val walk = CostModel(TravelMode.HIKE)
            val car = CostModel(TravelMode.DRIVE)
            fun labels(usable: (Int) -> Boolean): IntArray {
                val parent = IntArray(n) { it }
                fun find(i: Int): Int {
                    var r = i
                    while (parent[r] != r) r = parent[r]
                    var j = i
                    while (parent[j] != r) { val nx = parent[j]; parent[j] = r; j = nx }
                    return r
                }
                for (e in 0 until m) if (usable(e)) {
                    val a = find(plain.edgeFrom[e]); val b = find(plain.edgeTo[e])
                    if (a != b) parent[max(a, b)] = min(a, b)
                }
                val ids = HashMap<Int, Int>()
                val comp = IntArray(n) { ids.getOrPut(find(it)) { ids.size } }
                val isOpen = BooleanArray(ids.size)
                for (i in 0 until n) if (!isOpen[comp[i]] && open(i)) isOpen[comp[i]] = true
                return IntArray(n) { comp[it] * 2 + if (isOpen[comp[it]]) 1 else 0 }
            }
            val foot = labels { e -> walk.usable(plain, e) }
            val drive = labels { e -> source.drive && car.usable(plain, e) }
            RoutingGraph(
                source, plain.nodeLon, plain.nodeLat, plain.nodeEle, plain.edgeFrom, plain.edgeTo, plain.edgeLength, plain.edgeAscent, plain.edgeDescent,
                plain.edgeClass, plain.edgeAttrs, plain.edgeFlags, plain.geomStart, plain.geomLon, plain.geomLat, plain.geomEle, plain.edgeName, plain.names,
                plain.edgeSpeed, plain.nodeLevel, foot, drive,
            )
        }
        stats = GraphBuildStats(
            zoom = acc.z, tiles = tiles, tileErrors = errors, features = features, lines = acc.xs.size,
            inputVertices = acc.inputVertices, vertices = verts.x.size, crossings = verts.x.size - vertexCountBeforeCrossings + crossingsOnExisting,
            nodes = n, edges = m, duplicateEdges = duplicates, elevationSamples = samples, timingsMs = LinkedHashMap(timings),
        )
        return graph
    }

    private var crossingsOnExisting = 0

    /**
     * Adds a shared vertex where two segments cross or touch (within the snap tolerance) without
     * sharing one. Segments are bucketed in a grid; a pair is handled in the cell holding its
     * intersection point only, so each crossing is found once.
     */
    private fun addCrossings(lines: List<IntArray>, attrs: List<WayAttrs>, verts: Vertices, tol: Double): List<IntArray> {
        val cell = max(CROSSING_CELL_M / (SNAP_M / tol), tol * 4)
        val segLine = IntList()
        val segK = IntList()
        val grid = HashMap<Long, IntList>()
        fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xFFFFFFFFL)
        for ((li, l) in lines.withIndex()) {
            if (attrs[li].brunnel) continue
            for (k in 0 until l.size - 1) {
                val s = segLine.size
                segLine.add(li); segK.add(k)
                val ax = verts.x[l[k]]; val ay = verts.y[l[k]]; val bx = verts.x[l[k + 1]]; val by = verts.y[l[k + 1]]
                val cx0 = floor((min(ax, bx) - tol) / cell).toLong(); val cx1 = floor((max(ax, bx) + tol) / cell).toLong()
                val cy0 = floor((min(ay, by) - tol) / cell).toLong(); val cy1 = floor((max(ay, by) + tol) / cell).toLong()
                for (cy in cy0..cy1) for (cx in cx0..cx1) grid.getOrPut(key(cx, cy)) { IntList(4) }.add(s)
            }
        }
        // Inserted vertices per segment: (t along the segment, vertex).
        val inserts = HashMap<Int, MutableList<Pair<Double, Int>>>()
        var existing = 0
        for ((k, list) in grid) {
            val cx = k shr 32
            val cy = (k and 0xFFFFFFFFL).toInt().toLong()
            for (i in 0 until list.size) {
                val s1 = list[i]
                val l1 = lines[segLine[s1]]
                val a = l1[segK[s1]]; val b = l1[segK[s1] + 1]
                val ax = verts.x[a]; val ay = verts.y[a]; val bx = verts.x[b]; val by = verts.y[b]
                for (j in i + 1 until list.size) {
                    val s2 = list[j]
                    val l2 = lines[segLine[s2]]
                    val c = l2[segK[s2]]; val d = l2[segK[s2] + 1]
                    if (a == c || a == d || b == c || b == d) continue
                    if (!mayJoin(attrs[segLine[s1]].cls, attrs[segLine[s2]].cls)) continue
                    val cxx = verts.x[c]; val cyy = verts.y[c]; val dx = verts.x[d]; val dy = verts.y[d]
                    val rx = bx - ax; val ry = by - ay
                    val sx = dx - cxx; val sy = dy - cyy
                    val denom = rx * sy - ry * sx
                    val len1 = sqrt(rx * rx + ry * ry)
                    val len2 = sqrt(sx * sx + sy * sy)
                    if (len1 == 0.0 || len2 == 0.0) continue
                    if (abs(denom) < 1e-9 * len1 * len2) continue // parallel
                    val qx = cxx - ax; val qy = cyy - ay
                    val t = (qx * sy - qy * sx) / denom
                    val u = (qx * ry - qy * rx) / denom
                    val e1 = tol / len1
                    val e2 = tol / len2
                    if (t < -e1 || t > 1 + e1 || u < -e2 || u > 1 + e2) continue
                    val px = ax + t * rx
                    val py = ay + t * ry
                    if (floor(px / cell).toLong() != cx || floor(py / cell).toLong() != cy) continue
                    val before = verts.x.size
                    val v = verts.id(px, py)
                    if (v < before) existing++
                    if (v != a && v != b) inserts.getOrPut(s1) { ArrayList(1) } += t.coerceIn(0.0, 1.0) to v
                    if (v != c && v != d) inserts.getOrPut(s2) { ArrayList(1) } += u.coerceIn(0.0, 1.0) to v
                }
            }
        }
        crossingsOnExisting = existing
        if (inserts.isEmpty()) return lines
        // Rebuild the lines with the inserted vertices in order along each segment.
        val bySeg = HashMap<Long, List<Pair<Double, Int>>>()
        for ((s, list) in inserts) bySeg[(segLine[s].toLong() shl 32) or segK[s].toLong()] = list.sortedBy { it.first }
        return lines.mapIndexed { li, l ->
            if (attrs[li].brunnel) return@mapIndexed l
            val out = IntList(l.size + 2)
            for (k in l.indices) {
                if (out.size == 0 || out[out.size - 1] != l[k]) out.add(l[k])
                if (k == l.size - 1) break
                bySeg[(li.toLong() shl 32) or k.toLong()]?.forEach { (_, v) -> if (out[out.size - 1] != v) out.add(v) }
            }
            out.toArray()
        }
    }

    companion object {
        private fun tileKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

        /** T73: the first outdoors profile version whose packs carry one-ways, motor access and speed limits. */
        const val DRIVE_PROFILE_VERSION = 2

        /**
         * T73: whether a vector archive with [metadata] carries what drive mode needs: the outdoors profile
         * from [DRIVE_PROFILE_VERSION] (`dystopo:profile_version`, else `version`), or the OpenMapTiles schema
         * (`oneway` on roads and `access=no`; no speed limits, so class speeds).
         */
        fun driveCapable(metadata: Map<String, Any?>): Boolean = when (PackPrecedence.vectorProfile(metadata)) {
            VectorProfile.OPENMAPTILES -> true
            VectorProfile.OUTDOORS -> {
                val v = (metadata["dystopo:profile_version"] ?: metadata["version"])?.toString()?.trim()
                (v?.substringBefore('.')?.toIntOrNull() ?: 0) >= DRIVE_PROFILE_VERSION
            }
            VectorProfile.UNKNOWN -> false
        }

        /** T73: trunks and motorways join only other roads where they cross without a shared vertex. */
        private fun mayJoin(a: WayClass, b: WayClass): Boolean {
            val ha = a == WayClass.TRUNK || a == WayClass.MOTORWAY
            val hb = b == WayClass.TRUNK || b == WayClass.MOTORWAY
            return (!ha && !hb) || (a.code >= WayClass.SERVICE.code && b.code >= WayClass.SERVICE.code)
        }

        /**
         * T73: the drive flags of one edge drawn twice ([existing] kept, [dup] the other copy, [sameDirection]
         * when it runs the same way): it may be driven wherever either copy may.
         */
        internal fun mergeDriveFlags(existing: Int, dup: Int, sameDirection: Boolean): Int {
            fun dirs(f: Int, same: Boolean): Int { // bit 1: forward allowed, bit 2: backward allowed
                val fwd = f and WayAttrs.ONEWAY_REVERSE == 0
                val back = f and WayAttrs.ONEWAY == 0
                val x = if (same) fwd else back
                val y = if (same) back else fwd
                return (if (x) 1 else 0) or (if (y) 2 else 0)
            }
            val d = dirs(existing, true) or dirs(dup, sameDirection)
            var out = existing and WayAttrs.DRIVE_FLAGS.inv()
            if (d == 1) out = out or WayAttrs.ONEWAY
            if (d == 2) out = out or WayAttrs.ONEWAY_REVERSE
            val motor = minOf(motorRank(existing), motorRank(dup))
            if (motor == 1) out = out or WayAttrs.MOTOR_DESTINATION
            if (motor == 2) out = out or WayAttrs.MOTOR_NO
            return out
        }

        private fun motorRank(f: Int) = if (f and WayAttrs.MOTOR_NO != 0) 2 else if (f and WayAttrs.MOTOR_DESTINATION != 0) 1 else 0

        const val LAYER = "transportation"
        private val LAYERS = setOf(LAYER)
        /** Tile width in the snapping frame. */
        const val UNITS = 4096
        const val MAX_ZOOM = 14
        /** Vertices closer than this are one (line pieces across tile borders, shared OSM nodes). */
        const val SNAP_M = 1.0
        /** Elevation sample spacing along an edge for ascent and descent. */
        const val SAMPLE_M = 25.0
        /** Grid cell for the crossing search. */
        private const val CROSSING_CELL_M = 60.0
        /** Elevation samples are taken in the row-major order of this zoom's tiles. */
        private const val SORT_ZOOM = 13
        private const val E6 = 1e6

        /** Identifies the vector archive's content: the graph is rebuilt when this changes. */
        fun fingerprint(h: PmtilesHeader): String =
            "v1:${h.tileDataLength}:${h.addressedTiles}:${h.tileContents}:${h.rootDirectoryOffset}:${h.metadataLength}:${h.minZoom}-${h.maxZoom}"

        // ---- T149: the heap a build takes, estimated before it starts ----

        /** Tiles read for the estimate: an even spread over the archive's tile order (a Hilbert curve, so over its area too). */
        const val ESTIMATE_SAMPLE_TILES = 256

        /**
         * The fitted peak's fixed part: the reader, the DEM cache and the builder's own tables. T149 measured the heap's
         * growth through a build (used bytes, garbage included, from the build's start to its peak) on an API 35
         * emulator with a 576 MB heap: the Rainier outdoors pack (57,826 vertices by [measure]) 65.1 MB, a 1 x 0.7
         * degree box around Rainier from the Washington extract (346,512) 143.2 MB; the Washington 3 degree box
         * (7,910,526) does not fit that heap and needed a JVM heap of 2 to 2.25 GB (`GraphBuildEstimateTest`).
         */
        const val ESTIMATE_BASE_BYTES = 48L shl 20

        /** The fitted peak's part per routable input vertex (T149's measurement; see [ESTIMATE_BASE_BYTES]). */
        const val ESTIMATE_BYTES_PER_VERTEX = 300L

        /** What [estimatePeakBytes] adds on top of the fitted peak (the research's "about 1.5x"; T149). */
        const val ESTIMATE_MARGIN = 1.5

        /** The peak heap a build of [vertices] routable input vertices took in T149's measurement, fitted. Pure. */
        fun fittedPeakBytes(vertices: Long): Long = ESTIMATE_BASE_BYTES + ESTIMATE_BYTES_PER_VERTEX * vertices

        /** [fittedPeakBytes] with [ESTIMATE_MARGIN]: what a heap guard compares with the heap free now. Pure. */
        fun estimatePeakBytes(vertices: Long): Long = (fittedPeakBytes(vertices) * ESTIMATE_MARGIN).toLong()

        /**
         * T149: the size of a build of [reader] (the archive of [archiveBytes]) without building: the tiles at the read
         * zoom are counted from the directory, and the routable vertices of up to [sample] of them, spread evenly over
         * the tile order, are decoded and scaled to all. Reads [sample] tiles at most; holds no per-tile list.
         */
        fun measure(reader: PmtilesReader, archiveBytes: Long, sample: Int = ESTIMATE_SAMPLE_TILES): GraphBuildSize {
            val h = reader.header
            val z = min(h.maxZoom, MAX_ZOOM)
            val start = TileIds.zoomStart(z)
            val end = TileIds.zoomStart(z + 1)
            fun forEachTile(each: (Long) -> Boolean) {
                for (e in reader.tileEntries()) {
                    if (e.tileId >= end) break
                    if (e.tileId + e.runLength <= start) continue
                    for (k in 0 until e.runLength) {
                        val id = e.tileId + k
                        if (id in start until end && !each(id)) return
                    }
                }
            }
            var tiles = 0
            forEachTile { tiles++; true }
            val n = min(tiles, sample.coerceAtLeast(1))
            val openMapTiles = PackPrecedence.vectorProfile(runCatching { reader.metadata() }.getOrDefault(emptyMap())) == VectorProfile.OPENMAPTILES
            var picked = 0
            var index = 0
            var sampled = 0L
            if (n > 0) forEachTile { id ->
                // The picked-th of n sits at index picked * tiles / n.
                if (index.toLong() == picked.toLong() * tiles / n) {
                    sampled += routableVertices(reader, id, openMapTiles)
                    picked++
                }
                index++
                picked < n
            }
            val vertices = if (picked == 0) 0L else sampled * tiles / picked
            return GraphBuildSize(z, tiles, archiveBytes, picked, sampled, vertices)
        }

        /** The vertices of tile [id]'s routable lines, as [build] reads them (0 for a tile that does not decode). */
        private fun routableVertices(reader: PmtilesReader, id: Long, openMapTiles: Boolean): Long {
            val t = TileIds.toZxy(id)
            return try {
                val bytes = reader.tile(t.z, t.x, t.y) ?: return 0
                var n = 0L
                for (layer in MvtDecoder.decode(LayerFilter.filter(bytes, LAYERS)).layers) {
                    if (layer.name != LAYER) continue
                    for (f in layer.features) {
                        val g = f.geometry as? Geometry.Lines ?: continue
                        if (WayAttrs.of(f.properties, openMapTiles) == null) continue
                        for (line in g.lines) n += line.size
                    }
                }
                n
            } catch (e: IllegalArgumentException) {
                0
            }
        }

        /** Tile ids present at zoom [z] (run-length entries expanded), in id order. */
        fun tileIdsAt(reader: PmtilesReader, z: Int): List<Long> {
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

        internal fun mercY(lat: Double): Double {
            val r = lat.coerceIn(-85.051129, 85.051129) * PI / 180
            return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
        }

        internal fun unmercY(y: Double): Double {
            val v = PI * (1 - 2 * y)
            return atan((exp(v) - exp(-v)) / 2) * 180 / PI
        }
    }
}
