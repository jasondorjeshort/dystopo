package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.pmtiles.ByteReader
import io.github.jasondorjeshort.dystopo.pmtiles.ByteWriter
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph.Companion.count
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph.Companion.fnv1a
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph.Companion.str
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** The part of a routing graph that lies in one tile of the graph tile grid (T76). */
class GraphTile(val z: Int, val x: Int, val y: Int, val graph: RoutingGraph) {
    override fun toString() = "$z/$x/$y (${graph.nodeCount} nodes, ${graph.edgeCount} edges)"
}

/**
 * Cuts a [RoutingGraph] into graph tiles at a fixed zoom (T76, PLANNING › Routing). Every edge that
 * crosses a tile border is split there: the cut becomes a node, written into both tiles with the same
 * integer microdegree position, so the router steps from one tile into the next by matching positions
 * ([GraphPiece.twin]). Edges that stay inside one tile keep their length, ascent and descent exactly;
 * a cut edge's parts share its length, ascent and descent in proportion to their length, so each part
 * keeps the edge's average grade and the parts cost exactly what the edge did; the parts run in the
 * edge's direction, so a one-way (T73) stays one-way the same way (the router already
 * treats an edge as one even grade when a route starts or ends on it). Graphs built from a vector pack already end every line at the z14 tile borders
 * (the builder clips per tile), so at [ZOOM] there are normally no cuts at all.
 */
object GraphTiler {
    /**
     * The graph tile zoom. Measured on the Rainier outdoors pack (T76; whole graph 3.45 MB in memory with
     * its indexes): at z11 (tiles about 13 km wide there) the pack is 20 tiles and Paradise to Panorama
     * Point reads 2 of them, 0.61 MB; z10 is 9 tiles and the same route reads 2.2 MB; z12 is 56 tiles
     * and 0.35 MB, with more boundary work on longer routes. The file is the same size at every zoom.
     */
    const val ZOOM = 11

    fun split(g: RoutingGraph, zoom: Int = ZOOM): List<GraphTile> {
        require(zoom in 0..20) { "tile zoom $zoom out of range" }
        val nodeLon = IntList(g.nodeCount + 16).apply { for (n in 0 until g.nodeCount) add(g.nodeLon[n]) }
        val nodeLat = IntList(g.nodeCount + 16).apply { for (n in 0 until g.nodeCount) add(g.nodeLat[n]) }
        val nodeEle = FloatList(g.nodeCount + 16).apply { for (n in 0 until g.nodeCount) add(g.nodeEle[n]) }
        // T89: per node its level and component labels; a cut becomes a node of its edge's bridge or tunnel level, in the edge's components.
        val nodeExtra = IntList(g.nodeCount + 16).apply { for (n in 0 until g.nodeCount) add(n) } // the original node whose labels it shares
        val nodeLevel = IntList(g.nodeCount + 16).apply { for (n in 0 until g.nodeCount) add(g.nodeLevel[n].toInt()) }
        val parts = ArrayList<Part>(g.edgeCount)
        for (e in 0 until g.edgeCount) {
            val before = nodeLon.size
            cut(g, e, zoom, parts, nodeLon, nodeLat, nodeEle)
            for (i in before until nodeLon.size) { nodeExtra.add(g.edgeFrom[e]); nodeLevel.add(g.brunnel(e)) }
        }
        val byTile = parts.groupBy { (it.ty.toLong() shl 32) or it.tx.toLong() }
        return byTile.keys.sorted().map { key ->
            val list = byTile.getValue(key)
            val local = HashMap<Int, Int>()
            val nLon = IntList(); val nLat = IntList(); val nEle = FloatList(); val nOf = IntList()
            fun node(v: Int): Int = local.getOrPut(v) { nLon.add(nodeLon[v]); nLat.add(nodeLat[v]); nEle.add(nodeEle[v]); nOf.add(v); local.size }
            val m = list.size
            val from = IntArray(m); val to = IntArray(m)
            val len = FloatArray(m); val asc = FloatArray(m); val desc = FloatArray(m)
            val cls = ByteArray(m); val attrs = ByteArray(m); val flags = ByteArray(m); val speed = ByteArray(m)
            val gs = IntArray(m + 1)
            val gLon = IntList(); val gLat = IntList(); val gEle = FloatList()
            val names = ArrayList<String>()
            val nameIds = HashMap<String, Int>()
            val edgeName = IntArray(m)
            list.forEachIndexed { i, p ->
                from[i] = node(p.from); to[i] = node(p.to)
                len[i] = p.length; asc[i] = p.ascent; desc[i] = p.descent
                cls[i] = g.edgeClass[p.edge]; attrs[i] = g.edgeAttrs[p.edge]; flags[i] = g.edgeFlags[p.edge]; speed[i] = g.edgeSpeed[p.edge]
                for (k in p.lon.indices) { gLon.add(p.lon[k]); gLat.add(p.lat[k]); gEle.add(p.ele[k]) }
                gs[i + 1] = gLon.size
                edgeName[i] = g.name(p.edge)?.let { nm -> nameIds.getOrPut(nm) { names += nm; names.size } } ?: 0
            }
            val k = nOf.size
            GraphTile(
                zoom, (key and 0xFFFFFFFFL).toInt(), (key shr 32).toInt(),
                RoutingGraph(
                    g.source, nLon.toArray(), nLat.toArray(), nEle.toArray(), from, to, len, asc, desc, cls, attrs, flags,
                    gs, gLon.toArray(), gLat.toArray(), gEle.toArray(), edgeName, names, speed,
                    ByteArray(k) { nodeLevel[nOf[it]].toByte() },
                    g.footComponent?.let { c -> IntArray(k) { c[nodeExtra[nOf[it]]] } },
                    g.driveComponent?.let { c -> IntArray(k) { c[nodeExtra[nOf[it]]] } },
                ),
            )
        }
    }

    /** One edge, or one piece of a cut edge, with its interior points. */
    private class Part(
        val edge: Int, val from: Int, val to: Int, val tx: Int, val ty: Int,
        val lon: IntArray, val lat: IntArray, val ele: FloatArray,
        val length: Float, val ascent: Float, val descent: Float,
    )

    private fun cut(g: RoutingGraph, e: Int, z: Int, out: MutableList<Part>, nodeLon: IntList, nodeLat: IntList, nodeEle: FloatList) {
        val n = g.pointCount(e)
        // The edge's points with a point added wherever a segment crosses a tile border.
        val lon = IntList(n + 4); val lat = IntList(n + 4); val ele = FloatList(n + 4)
        val mx = DoubleList(n + 4); val my = DoubleList(n + 4)
        fun add(lo: Int, la: Int, el: Float, x: Double, y: Double) { lon.add(lo); lat.add(la); ele.add(el); mx.add(x); my.add(y) }
        for (k in 0 until n) {
            val lo = g.pointLonE6(e, k); val la = g.pointLatE6(e, k); val el = g.pointEle(e, k)
            val x = WebMercator.lonToX(lo / E6, z); val y = WebMercator.latToY(la / E6, z)
            add(lo, la, el, x, y)
            if (k == n - 1) break
            val lo1 = g.pointLonE6(e, k + 1); val la1 = g.pointLatE6(e, k + 1); val el1 = g.pointEle(e, k + 1)
            val x1 = WebMercator.lonToX(lo1 / E6, z); val y1 = WebMercator.latToY(la1 / E6, z)
            val ts = ArrayList<Double>()
            // T89: a crossing within MIN_CUT_M of a point is no cut: the point itself, a hair from the border, is
            // where the tiles meet (it becomes the node in both when the tile changes there), so the route does
            // not gain a point centimetres from an existing one (the T76 "224 points" on the Rainier pack).
            val segM = TrackStats.distanceM(la / E6, lo / E6, la1 / E6, lo1 / E6)
            fun keep(t: Double) = t > 0 && t < 1 && t * segM >= MIN_CUT_M && (1 - t) * segM >= MIN_CUT_M
            for (c in ceil(minOf(x, x1)).toInt()..floor(maxOf(x, x1)).toInt()) {
                val t = (c - x) / (x1 - x)
                if (keep(t)) ts += t
            }
            for (c in ceil(minOf(y, y1)).toInt()..floor(maxOf(y, y1)).toInt()) {
                val t = (c - y) / (y1 - y)
                if (keep(t)) ts += t
            }
            if (ts.isEmpty()) continue
            ts.sort()
            var prev = -1.0
            for (t in ts) {
                if (t - prev < 1e-12) continue
                prev = t
                val px = x + (x1 - x) * t
                val py = y + (y1 - y) * t
                val pe = when {
                    el.isNaN() && el1.isNaN() -> Float.NaN
                    el.isNaN() -> el1
                    el1.isNaN() -> el
                    else -> RoutingGraph.quantizeEle(el + (el1 - el) * t)
                }
                add(
                    (WebMercator.xToLon(px, z) * E6).roundToInt(), (WebMercator.yToLat(py, z) * E6).roundToInt(), pe, px, py,
                )
            }
        }
        val cnt = lon.size
        fun tileOf(i: Int): Long {
            val tx = floor((mx[i] + mx[i + 1]) / 2).toLong()
            val ty = floor((my[i] + my[i + 1]) / 2).toLong()
            return (ty shl 32) or (tx and 0xFFFFFFFFL)
        }
        val cuts = IntList()
        for (i in 1 until cnt - 1) if (tileOf(i - 1) != tileOf(i)) cuts.add(i)
        if (cuts.size == 0) {
            // The common case: the edge as it is, with its interior points (no added point is kept).
            val t = tileOf(0)
            val g0 = g.geomStart[e]; val g1 = g.geomStart[e + 1]
            out += Part(
                e, g.edgeFrom[e], g.edgeTo[e], (t and 0xFFFFFFFFL).toInt(), (t shr 32).toInt(),
                g.geomLon.copyOfRange(g0, g1), g.geomLat.copyOfRange(g0, g1), g.geomEle.copyOfRange(g0, g1),
                g.edgeLength[e], g.edgeAscent[e], g.edgeDescent[e],
            )
            return
        }
        // Split at the cuts; each cut point becomes a node shared by the parts on both sides.
        val bounds = ArrayList<Int>().apply { add(0); for (i in 0 until cuts.size) add(cuts[i]); add(cnt - 1) }
        val segLen = DoubleArray(cnt - 1) { TrackStats.distanceM(lat[it] / E6, lon[it] / E6, lat[it + 1] / E6, lon[it + 1] / E6) }
        val totalLen = segLen.sum()
        var fromNode = g.edgeFrom[e]
        for (j in 0 until bounds.size - 1) {
            val a = bounds[j]; val b = bounds[j + 1]
            val toNode = if (b == cnt - 1) g.edgeTo[e] else {
                nodeLon.add(lon[b]); nodeLat.add(lat[b]); nodeEle.add(ele[b])
                nodeLon.size - 1
            }
            val l = (a until b).sumOf { segLen[it] }
            val share = if (totalLen > 0) l / totalLen else 1.0 / (bounds.size - 1)
            val t = tileOf(a)
            out += Part(
                e, fromNode, toNode, (t and 0xFFFFFFFFL).toInt(), (t shr 32).toInt(),
                IntArray(b - a - 1) { lon[a + 1 + it] }, IntArray(b - a - 1) { lat[a + 1 + it] }, FloatArray(b - a - 1) { ele[a + 1 + it] },
                RoutingGraph.quantizeLength(g.edgeLength[e] * share),
                RoutingGraph.quantizeLength(g.edgeAscent[e] * share),
                RoutingGraph.quantizeLength(g.edgeDescent[e] * share),
            )
            fromNode = toNode
        }
    }

    private const val E6 = 1e6

    /** T89: crossings closer than this to a point of the line are not cut (the point is the tile join). */
    const val MIN_CUT_M = 0.5
}

/**
 * The routing graph file, `<vector archive>-graph.idx` next to the pack (T76): the pack's graph cut
 * into tiles ([GraphTiler]) behind a small directory, so a reader loads only the tiles a route touches.
 *
 * ```
 * "DYSG" u8 version(=4) u32 headerLength          (bytes from here to the end of the header checksum)
 * str sourceFile, str fingerprint, str demFingerprint, u8 sourceZoom, u64 builtAtMs, u8 tileZoom,
 * u8 capabilities (1: drive, [GraphSource.drive]; version 4, T73)
 * varint tileCount, per tile (rows north to south, west to east):
 *     varint x, varint y, varint bodyLength, varint nodes, varint edges
 * u32 FNV-1a of every header byte before it (from the magic)
 * tile bodies, in directory order: RoutingGraph.writeBody (nodes, edges, names; the version 2 layout
 *     without its source fields; version 4: then u8 speedKmh per edge; version 5, T89: then per node
 *     u8 level, varint foot component + 1, varint drive component + 1) followed by u32 FNV-1a of the body
 * ```
 * Version 5 (T89) also marks bridges and tunnels in each edge's attribute byte (bits 5 and 6) and keeps
 * the bridge and tunnel points cut at tile borders as nodes of their own level, so joins never stitch a
 * bridge to the road below it; its component labels let the router refuse a route between unconnected
 * parts at once ([RoutingGraph.footComponent]).
 * Edge flags carry the T73 drive bits ([WayAttrs.ONEWAY], [WayAttrs.MOTOR_NO], ...) relative to the
 * edge's from -> to direction. Version 2 files (one graph per pack, before T76), version 3 files
 * (before T73: no speed limits, no drive flag) and version 4 files (before T89) are refused with a message saying so; the phone, the
 * server and `devtool route` then rebuild the file. The directory is all a reader keeps in memory
 * besides the tiles it loads; tile offsets are the sums of the body lengths before them.
 */
object GraphTileFile {
    const val VERSION = 5
    /** Header capability bit: the graph supports drive mode ([GraphSource.drive]). */
    private const val CAP_DRIVE = 1
    private val MAGIC = byteArrayOf(0x44, 0x59, 0x53, 0x47) // "DYSG", as RoutingGraph

    /** [graph] cut at [zoom] and written as a graph file. */
    fun encode(graph: RoutingGraph, zoom: Int = GraphTiler.ZOOM): ByteArray = encode(graph.source, zoom, GraphTiler.split(graph, zoom))

    fun encode(source: GraphSource, zoom: Int, tiles: List<GraphTile>): ByteArray {
        require(tiles.all { it.z == zoom }) { "tiles at zoom $zoom only" }
        val sorted = tiles.sortedWith(compareBy({ it.y }, { it.x }))
        require(sorted.zipWithNext().none { (a, b) -> a.x == b.x && a.y == b.y }) { "two tiles at one position" }
        val bodies = sorted.map { t ->
            val w = ByteWriter(64 + t.graph.nodeCount * 6 + t.graph.edgeCount * 12 + t.graph.geomLon.size * 5)
            t.graph.writeBody(w)
            val b = w.toByteArray()
            ByteWriter(b.size + 4).apply { bytes(b); u32(fnv1a(b, b.size).toLong() and 0xFFFFFFFFL) }.toByteArray()
        }
        val h = ByteWriter(256 + sorted.size * 12)
        h.str(source.file); h.str(source.fingerprint); h.str(source.demFingerprint); h.u8(source.zoom); h.u64(source.builtAtMs)
        h.u8(zoom)
        h.u8(if (source.drive) CAP_DRIVE else 0)
        h.varint(sorted.size.toLong())
        sorted.forEachIndexed { i, t ->
            h.varint(t.x.toLong()); h.varint(t.y.toLong()); h.varint(bodies[i].size.toLong())
            h.varint(t.graph.nodeCount.toLong()); h.varint(t.graph.edgeCount.toLong())
        }
        val header = h.toByteArray()
        val head = ByteWriter(9 + header.size)
        head.bytes(MAGIC); head.u8(VERSION); head.u32(header.size.toLong() + 4); head.bytes(header)
        val headBytes = head.toByteArray()
        val out = ByteWriter(headBytes.size + 4 + bodies.sumOf { it.size })
        out.bytes(headBytes)
        out.u32(fnv1a(headBytes, headBytes.size).toLong() and 0xFFFFFFFFL)
        for (b in bodies) out.bytes(b)
        return out.toByteArray()
    }

    /**
     * Opens a graph file through [read] (offset, length) -> bytes, reading only its header; tiles are
     * read by [GraphTileArchive.tile]. Throws IllegalArgumentException for anything but a valid
     * version 4 file (an older file says so, so callers can name the rebuild's reason).
     */
    fun open(read: (Long, Int) -> ByteArray): GraphTileArchive {
        val first = read(0, 9)
        require(first.size == 9 && first.copyOfRange(0, 4).contentEquals(MAGIC)) { "not a routing graph" }
        val version = first[4].toInt() and 0xFF
        require(version == VERSION) {
            if (version < 3) "routing graph version $version (one graph per pack, before T76), expected $VERSION: rebuild as tiles"
            else if (version < 4) "routing graph version $version (before T73's drive data), expected $VERSION: rebuild"
            else if (version < VERSION) "routing graph version $version (before T89's bridge levels and component summary), expected $VERSION: rebuild"
            else "routing graph version $version, expected $VERSION"
        }
        val headerLength = ByteReader(first, 5).u32()
        require(headerLength in 4..(64L shl 20)) { "bad routing graph header length $headerLength" }
        val rest = read(9, headerLength.toInt())
        require(rest.size.toLong() == headerLength) { "routing graph header truncated" }
        val all = first + rest
        val stored = ByteReader(all, all.size - 4).u32().toInt()
        require(stored == fnv1a(all, all.size - 4)) { "routing graph header checksum mismatch (truncated or corrupt)" }
        try {
            val r = ByteReader(all.copyOfRange(0, all.size - 4), 9)
            val base = GraphSource(r.str(), r.str(), r.str(), r.u8(), r.u64())
            val zoom = r.u8()
            val source = base.copy(drive = r.u8() and CAP_DRIVE != 0)
            val n = r.count()
            var offset = all.size.toLong()
            val entries = ArrayList<GraphTileArchive.Entry>(n)
            repeat(n) {
                val x = r.count(); val y = r.count(); val length = r.count(); val nodes = r.count(); val edges = r.count()
                require(length >= 4) { "bad tile length" }
                entries += GraphTileArchive.Entry(x, y, offset, length, nodes, edges)
                offset += length
            }
            return GraphTileArchive(source, zoom, entries, offset, read)
        } catch (e: IllegalStateException) {
            throw IllegalArgumentException("corrupt routing graph header: ${e.message}", e)
        }
    }

    /** [open] over bytes in memory. */
    fun open(bytes: ByteArray): GraphTileArchive = open { offset, length ->
        bytes.copyOfRange(offset.coerceAtMost(bytes.size.toLong()).toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
    }
}

/**
 * An open graph file ([GraphTileFile]): its [source], the directory, and tiles read and decoded on
 * request. Holds no tiles itself; [GraphTileCache] keeps the decoded ones. Thread-safe as long as
 * [read] is.
 */
class GraphTileArchive internal constructor(
    val source: GraphSource,
    val tileZoom: Int,
    val entries: List<Entry>,
    /** File size the directory implies. */
    val fileBytes: Long,
    private val read: (Long, Int) -> ByteArray,
) {
    class Entry(val x: Int, val y: Int, val offset: Long, val length: Int, val nodes: Int, val edges: Int)

    private val byKey: Map<Long, Entry> = entries.associateBy { key(it.x, it.y) }

    fun has(x: Int, y: Int): Boolean = key(x, y) in byKey

    /** Reads and decodes tile ([x], [y]); null when the archive has none there. Throws IllegalArgumentException when it is corrupt. */
    fun tile(x: Int, y: Int): RoutingGraph? {
        val e = byKey[key(x, y)] ?: return null
        val bytes = read(e.offset, e.length)
        require(bytes.size == e.length) { "routing graph tile $x/$y truncated" }
        val stored = ByteReader(bytes, bytes.size - 4).u32().toInt()
        require(stored == fnv1a(bytes, bytes.size - 4)) { "routing graph tile $x/$y checksum mismatch" }
        try {
            return RoutingGraph.readBody(ByteReader(bytes.copyOfRange(0, bytes.size - 4)), source)
        } catch (ex: IllegalStateException) {
            throw IllegalArgumentException("corrupt routing graph tile $x/$y: ${ex.message}", ex)
        }
    }

    val nodeCount: Int get() = entries.sumOf { it.nodes }
    val edgeCount: Int get() = entries.sumOf { it.edges }

    /**
     * Every tile stitched back into one graph: nodes of different tiles at the same position become one
     * (the cuts). For tools and tests that want the whole network (`devtool route`'s network report).
     */
    fun whole(): RoutingGraph {
        val nLon = IntList(); val nLat = IntList(); val nEle = FloatList()
        val nLevel = IntList(); val nFoot = IntList(); val nDrive = IntList()
        var labelled = true
        val at = HashMap<Long, Pair<Int, Int>>() // position and level -> (node, tile index)
        val from = IntList(); val to = IntList()
        val len = FloatList(); val asc = FloatList(); val desc = FloatList()
        val cls = IntList(); val attrs = IntList(); val flags = IntList(); val speed = IntList()
        val gs = IntList().apply { add(0) }
        val gLon = IntList(); val gLat = IntList(); val gEle = FloatList()
        val names = ArrayList<String>(); val nameIds = HashMap<String, Int>(); val edgeName = IntList()
        entries.forEachIndexed { ti, en ->
            val g = tile(en.x, en.y) ?: return@forEachIndexed
            if (g.footComponent == null || g.driveComponent == null) labelled = false
            val map = IntArray(g.nodeCount) { n ->
                val k = ((g.nodeLon[n].toLong() shl 32) xor (g.nodeLat[n].toLong() and 0xFFFFFFFFL)) xor (g.nodeLevel[n].toLong() shl 62)
                val prev = at[k]
                if (prev != null && prev.second != ti) prev.first else {
                    nLon.add(g.nodeLon[n]); nLat.add(g.nodeLat[n]); nEle.add(g.nodeEle[n])
                    nLevel.add(g.nodeLevel[n].toInt()); nFoot.add(g.footComponent?.get(n) ?: 0); nDrive.add(g.driveComponent?.get(n) ?: 0)
                    (nLon.size - 1).also { if (prev == null) at[k] = it to ti }
                }
            }
            for (e in 0 until g.edgeCount) {
                from.add(map[g.edgeFrom[e]]); to.add(map[g.edgeTo[e]])
                len.add(g.edgeLength[e]); asc.add(g.edgeAscent[e]); desc.add(g.edgeDescent[e])
                cls.add(g.edgeClass[e].toInt()); attrs.add(g.edgeAttrs[e].toInt()); flags.add(g.edgeFlags[e].toInt()); speed.add(g.edgeSpeed[e].toInt())
                for (k in g.geomStart[e] until g.geomStart[e + 1]) { gLon.add(g.geomLon[k]); gLat.add(g.geomLat[k]); gEle.add(g.geomEle[k]) }
                gs.add(gLon.size)
                edgeName.add(g.name(e)?.let { nm -> nameIds.getOrPut(nm) { names += nm; names.size } } ?: 0)
            }
        }
        val m = from.size
        return RoutingGraph(
            source, nLon.toArray(), nLat.toArray(), nEle.toArray(), from.toArray(), to.toArray(), len.toArray(), asc.toArray(), desc.toArray(),
            ByteArray(m) { cls[it].toByte() }, ByteArray(m) { attrs[it].toByte() }, ByteArray(m) { flags[it].toByte() },
            gs.toArray(), gLon.toArray(), gLat.toArray(), gEle.toArray(), edgeName.toArray(), names, ByteArray(m) { speed[it].toByte() },
            ByteArray(nLevel.size) { nLevel[it].toByte() }, if (labelled) nFoot.toArray() else null, if (labelled) nDrive.toArray() else null,
        )
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "file" to source.file, "fingerprint" to source.fingerprint, "demFingerprint" to source.demFingerprint.ifEmpty { null },
        "sourceZoom" to source.zoom, "builtAtMs" to source.builtAtMs, "drive" to source.drive, "tileZoom" to tileZoom, "tiles" to entries.size,
        "nodes" to nodeCount, "edges" to edgeCount, "bytes" to fileBytes,
    )

    companion object {
        fun key(x: Int, y: Int): Long = (y.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)
    }
}
