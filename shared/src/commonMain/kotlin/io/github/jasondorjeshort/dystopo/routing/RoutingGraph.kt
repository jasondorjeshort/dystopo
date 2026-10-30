package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.pmtiles.ByteReader
import io.github.jasondorjeshort.dystopo.pmtiles.ByteWriter
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** What a graph was built from, so a stale graph (either pack changed) is rebuilt instead of used. */
data class GraphSource(
    val file: String,
    /** [RoutingGraphBuilder.fingerprint] of the vector archive. */
    val fingerprint: String,
    /** Fingerprint of the DEM archive elevations were sampled from; empty when there was none. */
    val demFingerprint: String,
    /** The zoom whose tiles the lines were read from. */
    val zoom: Int,
    /** Build time, epoch milliseconds (from the caller; commonMain has no clock). */
    val builtAtMs: Long,
    /**
     * T73: the vector archive carries what drive mode needs (one-ways, motor access, speed limits): the
     * outdoors profile from version [RoutingGraphBuilder.DRIVE_PROFILE_VERSION], or the OpenMapTiles
     * schema. Graphs of older packs refuse drive mode ([RouteFailure.DRIVE_UNSUPPORTED]).
     */
    val drive: Boolean = false,
)

/** Where a point snapped onto the network: edge, segment, and position along the edge. */
data class EdgeSnap(
    val edge: Int,
    /** Segment index within the edge's point list (point k to k+1). */
    val segment: Int,
    /** Fraction of the edge's length from its `from` node, 0..1. */
    val fraction: Double,
    val lat: Double,
    val lon: Double,
    /** Distance from the query point, meters. */
    val distanceM: Double,
    /** T76: the edge's way name and class, filled by [Router.nearest] (edge ids are local to a graph tile). */
    val name: String? = null,
    val wayClass: WayClass? = null,
    /** T164: the network's height at the snap, filled by [Router.nearest] ([TrackMatch.eleAt]); null without a DEM. */
    val ele: Double? = null,
)

/**
 * A routable network: nodes are junctions and line ends, edges the lines between them with their
 * intermediate vertices, length, class, surface, name (T49) and the ascent and descent from `from` to `to`.
 * Coordinates are stored as integer microdegrees (about 0.1 m), elevations in metres (NaN: no DEM)
 * rounded to decimetres, lengths, ascents and descents to decimetres, so a decoded graph is equal to
 * the one that was encoded. Pure Kotlin. A graph is one pack's whole network while it is built, one
 * graph tile once loaded from the graph file ([GraphTileFile], version 3 since T76: a directory and one
 * [writeBody] per tile), or a stitched whole ([GraphTileArchive.whole]). [encode]/[decode] keep the
 * single-graph layout, which was the graph file up to T76 (version 2; the file readers now refuse it
 * and rebuild):
 *
 * ```
 * "DYSG" u8 version(=2; 1 is read too, without names)
 * str sourceFile, str fingerprint, str demFingerprint, u8 zoom, u64 builtAtMs
 * varint nodeCount, nodes: zz dLonE6, zz dLatE6, ele        (deltas from the previous node)
 * varint edgeCount, edges: varint from, varint to, u8 class, u8 surface|sac<<2, u8 flags,
 *                          varint lengthDm, varint ascentDm, varint descentDm,
 *                          varint pointCount, points: zz dLonE6, zz dLatE6, ele   (deltas from the from node, then the previous point)
 * varint nameCount, names: str                              (version 2: way names, T49)
 * edges: varint name                                        (0 unnamed, else 1 + index into names)
 * u32 FNV-1a of every byte before it
 * ```
 * This single-graph layout has no speed limits and no drive flag (T73): only graph files keep those
 * ([GraphTileFile] version 4 adds `edges: u8 speedKmh` after the names of each tile body).
 * ```
 * ```
 * `ele` is a varint: 0 for no elevation, else zigzag(decimetres - previous decimetres) + 1. Strings
 * are varint byte length + UTF-8; integers little-endian.
 *
 * The adjacency lists, connected components and the spatial grid for nearest-edge lookups are not
 * stored: they are derived on first use (tens of milliseconds for a national-park pack).
 */
class RoutingGraph(
    val source: GraphSource,
    val nodeLon: IntArray,
    val nodeLat: IntArray,
    val nodeEle: FloatArray,
    val edgeFrom: IntArray,
    val edgeTo: IntArray,
    val edgeLength: FloatArray,
    val edgeAscent: FloatArray,
    val edgeDescent: FloatArray,
    val edgeClass: ByteArray,
    val edgeAttrs: ByteArray,
    val edgeFlags: ByteArray,
    /** Edge e's intermediate points are geom*[geomStart[e] until geomStart[e + 1]]. */
    val geomStart: IntArray,
    val geomLon: IntArray,
    val geomLat: IntArray,
    val geomEle: FloatArray,
    /** T49: edge e's way name is names[edgeName[e] - 1]; 0 for none. */
    val edgeName: IntArray = IntArray(edgeFrom.size),
    val names: List<String> = emptyList(),
    /** T73: edge e's speed limit in km/h (unsigned byte), 0 when not tagged. */
    val edgeSpeed: ByteArray = ByteArray(edgeFrom.size),
    /**
     * T89: node n's level, 0 on the ground; [LEVEL_BRIDGE] or [LEVEL_TUNNEL] for a point where a bridge or
     * tunnel was cut at a tile border. Joins between tiles and packs ([GraphPiece.twin]) match only nodes of
     * the same level, so a bridge cut at a border is never stitched to the road it passes over.
     */
    val nodeLevel: ByteArray = ByteArray(nodeLon.size),
    /**
     * T89: the component summary, per node: `component * 2 + open` of the whole pack's network that hiking
     * and cycling may use ([footComponent]) and that cars may use, one-ways ignored ([driveComponent]);
     * `open` when the component reaches a border of the pack's coverage (it may continue in another pack).
     * Null when unknown (graphs built from lines, overlay cells).
     */
    val footComponent: IntArray? = null,
    val driveComponent: IntArray? = null,
) {
    val nodeCount: Int get() = nodeLon.size
    val edgeCount: Int get() = edgeFrom.size

    init {
        require(geomStart.size == edgeCount + 1) { "geomStart must have edgeCount + 1 entries" }
        require(edgeName.size == edgeCount) { "edgeName must have edgeCount entries" }
        require(edgeSpeed.size == edgeCount) { "edgeSpeed must have edgeCount entries" }
        require(nodeLevel.size == nodeCount) { "nodeLevel must have nodeCount entries" }
        require(footComponent == null || footComponent.size == nodeCount) { "footComponent must have nodeCount entries" }
        require(driveComponent == null || driveComponent.size == nodeCount) { "driveComponent must have nodeCount entries" }
    }

    /** T89: whether edge [e] is a bridge or a tunnel ([WayAttrs.brunnelOf] of its attributes). */
    fun brunnel(e: Int): Int = WayAttrs.brunnelOf(edgeAttrs[e].toInt())

    /** T89: the component labels (`component * 2 + open`) [mode] uses, or null when this graph has none. */
    fun components(mode: TravelMode): IntArray? = if (mode == TravelMode.DRIVE) driveComponent else footComponent

    /** T73: edge [e]'s tagged speed limit in km/h, 0 when not tagged. */
    fun speedKmh(e: Int): Int = edgeSpeed[e].toInt() and 0xFF

    /** The way name of edge [e] (T49), or null. */
    fun name(e: Int): String? = edgeName[e].takeIf { it > 0 }?.let { names[it - 1] }

    fun wayClass(e: Int): WayClass = WayClass.ofCode(edgeClass[e].toInt()) ?: WayClass.PATH
    fun surface(e: Int): Surface = WayAttrs.surfaceOf(edgeAttrs[e].toInt())
    fun sac(e: Int): Int = WayAttrs.sacOf(edgeAttrs[e].toInt())
    fun flags(e: Int): Int = edgeFlags[e].toInt()

    /** Points of edge [e] including both end nodes. */
    fun pointCount(e: Int): Int = geomStart[e + 1] - geomStart[e] + 2

    fun pointLonE6(e: Int, k: Int): Int = when (k) {
        0 -> nodeLon[edgeFrom[e]]
        pointCount(e) - 1 -> nodeLon[edgeTo[e]]
        else -> geomLon[geomStart[e] + k - 1]
    }

    fun pointLatE6(e: Int, k: Int): Int = when (k) {
        0 -> nodeLat[edgeFrom[e]]
        pointCount(e) - 1 -> nodeLat[edgeTo[e]]
        else -> geomLat[geomStart[e] + k - 1]
    }

    fun pointEle(e: Int, k: Int): Float = when (k) {
        0 -> nodeEle[edgeFrom[e]]
        pointCount(e) - 1 -> nodeEle[edgeTo[e]]
        else -> geomEle[geomStart[e] + k - 1]
    }

    /** Cumulative distance in metres to each point of edge [e] (first 0, last the edge's geometric length). */
    fun cumulative(e: Int): DoubleArray {
        val n = pointCount(e)
        val out = DoubleArray(n)
        for (k in 1 until n) {
            out[k] = out[k - 1] + TrackStats.distanceM(
                pointLatE6(e, k - 1) / E6, pointLonE6(e, k - 1) / E6, pointLatE6(e, k) / E6, pointLonE6(e, k) / E6,
            )
        }
        return out
    }

    // ---- Derived structures (lazy) ----

    /** Edges touching node n are adjEdge[adjStart[n] until adjStart[n + 1]], each `edge * 2 + (0 forward, 1 reverse)`. */
    val adjStart: IntArray get() = adjacency.first
    val adjEdge: IntArray get() = adjacency.second
    private val adjacency: Pair<IntArray, IntArray> by lazy { buildAdjacency().also { adjacencyDerived = true } }
    private var adjacencyDerived = false // for approxBytes only
    private var gridDerived = false

    private fun buildAdjacency(): Pair<IntArray, IntArray> {
        val deg = IntArray(nodeCount + 1)
        for (e in 0 until edgeCount) {
            deg[edgeFrom[e]]++
            deg[edgeTo[e]]++
        }
        val start = IntArray(nodeCount + 1)
        for (n in 0 until nodeCount) start[n + 1] = start[n] + deg[n]
        val fill = start.copyOf()
        val adj = IntArray(start[nodeCount])
        for (e in 0 until edgeCount) {
            adj[fill[edgeFrom[e]]++] = e * 2
            adj[fill[edgeTo[e]]++] = e * 2 + 1
        }
        return start to adj
    }

    /** Connected component of each node (ids by first node, dense from 0). */
    val component: IntArray by lazy {
        val parent = IntArray(nodeCount) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) { val n = parent[j]; parent[j] = r; j = n }
            return r
        }
        for (e in 0 until edgeCount) {
            val a = find(edgeFrom[e])
            val b = find(edgeTo[e])
            if (a != b) parent[max(a, b)] = minOf(a, b)
        }
        val ids = HashMap<Int, Int>()
        IntArray(nodeCount) { n -> ids.getOrPut(find(n)) { ids.size } }
    }

    /** Total edge length in metres per component. */
    val componentLengthM: DoubleArray by lazy {
        val n = (component.maxOrNull() ?: -1) + 1
        val out = DoubleArray(n)
        for (e in 0 until edgeCount) out[component[edgeFrom[e]]] += edgeLength[e].toDouble()
        out
    }

    private val grid: SegmentGrid by lazy { SegmentGrid(this).also { gridDerived = true } }

    /** The nearest point on any edge within [maxDistanceM] of ([lat], [lon]), or null. */
    fun nearest(lat: Double, lon: Double, maxDistanceM: Double): EdgeSnap? = grid.nearest(lat, lon, maxDistanceM, null)

    /** The nearest point within [maxDistanceM] on an edge [accept] takes (T73: drive mode snaps onto roads only), or null. */
    fun nearest(lat: Double, lon: Double, maxDistanceM: Double, accept: (Int) -> Boolean): EdgeSnap? = grid.nearest(lat, lon, maxDistanceM, accept)

    /** Distance to the nearest edge, searching up to [limitM]; null when none is that close. */
    fun nearestDistanceM(lat: Double, lon: Double, limitM: Double): Double? = grid.nearest(lat, lon, limitM, null)?.distanceM

    fun json(): Map<String, Any?> {
        val byClass = LinkedHashMap<String, Double>()
        for (c in WayClass.entries) byClass[c.label] = 0.0
        for (e in 0 until edgeCount) byClass[wayClass(e).label] = byClass.getValue(wayClass(e).label) + edgeLength[e]
        val comps = componentLengthM
        val total = comps.sum()
        return linkedMapOf(
            "file" to source.file, "fingerprint" to source.fingerprint, "demFingerprint" to source.demFingerprint.ifEmpty { null },
            "zoom" to source.zoom, "builtAtMs" to source.builtAtMs, "drive" to source.drive,
            "nodes" to nodeCount, "edges" to edgeCount, "points" to geomLon.size,
            "km" to (total / 100).roundToLong() / 10.0,
            "kmByClass" to byClass.filterValues { it > 0 }.mapValues { (it.value / 100).roundToLong() / 10.0 },
            "components" to comps.size,
            "largestComponentShare" to if (total > 0) ((comps.maxOrNull() ?: 0.0) / total * 1000).roundToInt() / 1000.0 else null,
            "withElevation" to (nodeEle.isEmpty() || !nodeEle.all { it.isNaN() }),
        )
    }

    fun encode(): ByteArray {
        val w = ByteWriter(64 + nodeCount * 6 + edgeCount * 12 + geomLon.size * 5)
        w.bytes(MAGIC)
        w.u8(VERSION)
        w.str(source.file)
        w.str(source.fingerprint)
        w.str(source.demFingerprint)
        w.u8(source.zoom)
        w.u64(source.builtAtMs)
        writeBody(w, withSpeeds = false)
        val body = w.toByteArray()
        val out = ByteWriter(body.size + 4)
        out.bytes(body)
        out.u32(fnv1a(body, body.size).toLong() and 0xFFFFFFFFL)
        return out.toByteArray()
    }

    /**
     * About how many bytes of heap this graph holds: its arrays, plus the adjacency lists and the
     * nearest-edge grid once they have been derived (T76: what a route keeps in memory per tile).
     */
    fun approxBytes(): Long {
        var b = 4L * (nodeLon.size + nodeLat.size + nodeEle.size) +
            4L * (edgeFrom.size + edgeTo.size + edgeLength.size + edgeAscent.size + edgeDescent.size + edgeName.size + geomStart.size) +
            4L * edgeCount + 4L * (geomLon.size + geomLat.size + geomEle.size) + names.sumOf { 40L + 2L * it.length } +
            nodeCount + 4L * ((footComponent?.size ?: 0) + (driveComponent?.size ?: 0))
        if (adjacencyDerived) b += 4L * (nodeCount + 1 + 2 * edgeCount)
        if (gridDerived) b += grid.approxBytes()
        return b
    }

    /**
     * Nodes, edges and names, without source or checksum: one tile's body in the graph file ([GraphTileFile]);
     * [withSpeeds] (graph file version 4, T73) adds a byte per edge with its speed limit; [withNodes] (version
     * 5, T89) then per node its level byte and its foot and drive component labels (varint label + 1, 0 unknown).
     */
    internal fun writeBody(w: ByteWriter, withSpeeds: Boolean = true, withNodes: Boolean = withSpeeds) {
        w.varint(nodeCount.toLong())
        var pLon = 0
        var pLat = 0
        var pEle = 0
        for (n in 0 until nodeCount) {
            w.varint(zz(nodeLon[n] - pLon)); pLon = nodeLon[n]
            w.varint(zz(nodeLat[n] - pLat)); pLat = nodeLat[n]
            pEle = w.ele(nodeEle[n], pEle)
        }
        w.varint(edgeCount.toLong())
        for (e in 0 until edgeCount) {
            w.varint(edgeFrom[e].toLong())
            w.varint(edgeTo[e].toLong())
            w.u8(edgeClass[e].toInt() and 0xFF)
            w.u8(edgeAttrs[e].toInt() and 0xFF)
            w.u8(edgeFlags[e].toInt() and 0xFF)
            w.varint(dm(edgeLength[e]))
            w.varint(dm(edgeAscent[e]))
            w.varint(dm(edgeDescent[e]))
            val g0 = geomStart[e]
            val g1 = geomStart[e + 1]
            w.varint((g1 - g0).toLong())
            var lon = nodeLon[edgeFrom[e]]
            var lat = nodeLat[edgeFrom[e]]
            var ele = eleDm(nodeEle[edgeFrom[e]]) ?: 0
            for (g in g0 until g1) {
                w.varint(zz(geomLon[g] - lon)); lon = geomLon[g]
                w.varint(zz(geomLat[g] - lat)); lat = geomLat[g]
                ele = w.ele(geomEle[g], ele)
            }
        }
        w.varint(names.size.toLong())
        for (s in names) w.str(s)
        for (e in 0 until edgeCount) w.varint(edgeName[e].toLong())
        if (withSpeeds) for (e in 0 until edgeCount) w.u8(edgeSpeed[e].toInt() and 0xFF)
        if (withNodes) for (n in 0 until nodeCount) {
            w.u8(nodeLevel[n].toInt() and 0xFF)
            w.varint(footComponent?.let { it[n].toLong() + 1 } ?: 0L)
            w.varint(driveComponent?.let { it[n].toLong() + 1 } ?: 0L)
        }
    }

    override fun equals(other: Any?): Boolean = other is RoutingGraph && source == other.source &&
        nodeLon.contentEquals(other.nodeLon) && nodeLat.contentEquals(other.nodeLat) && sameFloats(nodeEle, other.nodeEle) &&
        edgeFrom.contentEquals(other.edgeFrom) && edgeTo.contentEquals(other.edgeTo) &&
        edgeLength.contentEquals(other.edgeLength) && edgeAscent.contentEquals(other.edgeAscent) && edgeDescent.contentEquals(other.edgeDescent) &&
        edgeClass.contentEquals(other.edgeClass) && edgeAttrs.contentEquals(other.edgeAttrs) && edgeFlags.contentEquals(other.edgeFlags) &&
        geomStart.contentEquals(other.geomStart) && geomLon.contentEquals(other.geomLon) && geomLat.contentEquals(other.geomLat) &&
        sameFloats(geomEle, other.geomEle) && edgeName.contentEquals(other.edgeName) && names == other.names &&
        edgeSpeed.contentEquals(other.edgeSpeed) && nodeLevel.contentEquals(other.nodeLevel) // the component labels are derived (T89)

    override fun hashCode(): Int = source.hashCode() * 31 + nodeCount * 17 + edgeCount

    companion object {
        private val MAGIC = byteArrayOf(0x44, 0x59, 0x53, 0x47) // "DYSG"
        const val VERSION = 2
        const val SUFFIX = "-graph.idx"
        const val E6 = 1e6

        /** T89: [nodeLevel] of a bridge cut at a tile border. */
        const val LEVEL_BRIDGE: Byte = 1
        /** T89: [nodeLevel] of a tunnel cut at a tile border. */
        const val LEVEL_TUNNEL: Byte = 2

        /**
         * The graph file that sits next to a vector archive: `<archive name>-graph.idx` (T49: the whole archive
         * name, so `rainier-osm` and `rainier-outdoors` keep their own graphs instead of overwriting one).
         */
        fun fileNameFor(vectorFileName: String): String = vectorFileName.substringBeforeLast('.') + SUFFIX

        /** The name before T49, `<pack name>-graph.idx`; only for removing such files. */
        fun legacyFileNameFor(vectorFileName: String): String =
            (PackNames.parse(vectorFileName)?.pack ?: vectorFileName.substringBeforeLast('.')) + SUFFIX

        /** Rounds metres to the decimetres the file keeps (NaN stays NaN). */
        fun quantizeEle(m: Double): Float = if (m.isNaN()) Float.NaN else ((m * 10).roundToLong() / 10.0).toFloat()
        fun quantizeLength(m: Double): Float = ((m * 10).roundToLong().coerceAtLeast(0) / 10.0).toFloat()

        /** Reads a graph written by [encode]; throws IllegalArgumentException on a bad, truncated or newer file. */
        fun decode(bytes: ByteArray): RoutingGraph {
            require(bytes.size >= 9 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)) { "not a routing graph" }
            require(bytes[4].toInt() < 3) { "a tiled graph file (version ${bytes[4].toInt()}); read it with GraphTileFile" }
            val stored = ByteReader(bytes, bytes.size - 4).u32().toInt()
            require(stored == fnv1a(bytes, bytes.size - 4)) { "routing graph checksum mismatch (truncated or corrupt)" }
            val r = ByteReader(bytes.copyOfRange(0, bytes.size - 4), 4)
            try {
                val version = r.u8()
                require(version == VERSION || version == 1) {
                    if (version == GraphTileFile.VERSION) "a tiled graph file (version $version); read it with GraphTileFile" else "routing graph version $version, expected $VERSION"
                }
                val source = GraphSource(r.str(), r.str(), r.str(), r.u8(), r.u64())
                return readBody(r, source, withNames = version >= 2, withSpeeds = false)
            } catch (e: IllegalStateException) {
                throw IllegalArgumentException("corrupt routing graph: ${e.message}", e)
            }
        }

        /** Reads what [writeBody] wrote; throws IllegalStateException or IllegalArgumentException on bad data. */
        internal fun readBody(r: ByteReader, source: GraphSource, withNames: Boolean = true, withSpeeds: Boolean = true, withNodes: Boolean = withSpeeds): RoutingGraph {
            run {
                val n = r.count()
                val nodeLon = IntArray(n)
                val nodeLat = IntArray(n)
                val nodeEle = FloatArray(n)
                var lon = 0
                var lat = 0
                var ele = 0
                for (i in 0 until n) {
                    lon += unzz(r.varint()); nodeLon[i] = lon
                    lat += unzz(r.varint()); nodeLat[i] = lat
                    val code = r.varint()
                    if (code == 0L) nodeEle[i] = Float.NaN else { ele += unzz(code - 1); nodeEle[i] = ele / 10f }
                }
                val m = r.count()
                val from = IntArray(m)
                val to = IntArray(m)
                val len = FloatArray(m)
                val asc = FloatArray(m)
                val desc = FloatArray(m)
                val cls = ByteArray(m)
                val attrs = ByteArray(m)
                val flags = ByteArray(m)
                val geomStart = IntArray(m + 1)
                val gLon = IntList()
                val gLat = IntList()
                val gEle = FloatList()
                for (e in 0 until m) {
                    from[e] = r.count().also { require(it < n) { "edge node out of range" } }
                    to[e] = r.count().also { require(it < n) { "edge node out of range" } }
                    cls[e] = r.u8().toByte()
                    attrs[e] = r.u8().toByte()
                    flags[e] = r.u8().toByte()
                    len[e] = r.varint() / 10f
                    asc[e] = r.varint() / 10f
                    desc[e] = r.varint() / 10f
                    val pc = r.count()
                    var plon = nodeLon[from[e]]
                    var plat = nodeLat[from[e]]
                    var pele = eleDm(nodeEle[from[e]]) ?: 0
                    repeat(pc) {
                        plon += unzz(r.varint()); gLon.add(plon)
                        plat += unzz(r.varint()); gLat.add(plat)
                        val code = r.varint()
                        if (code == 0L) gEle.add(Float.NaN) else { pele += unzz(code - 1); gEle.add(pele / 10f) }
                    }
                    geomStart[e + 1] = gLon.size
                }
                // T49: way names (version 2).
                val names = ArrayList<String>()
                val edgeName = IntArray(m)
                if (withNames) {
                    repeat(r.count()) { names += r.str() }
                    for (e in 0 until m) edgeName[e] = r.count().also { require(it <= names.size) { "edge name out of range" } }
                }
                // T73: speed limits (graph file version 4).
                val speed = ByteArray(m)
                if (withSpeeds) for (e in 0 until m) speed[e] = r.u8().toByte()
                // T89: node levels and component labels (graph file version 5).
                val level = ByteArray(n)
                var foot: IntArray? = null
                var drive: IntArray? = null
                if (withNodes) {
                    val f = IntArray(n)
                    val d = IntArray(n)
                    var known = true
                    for (i in 0 until n) {
                        level[i] = r.u8().toByte()
                        val a = r.varint(); val b = r.varint()
                        require(a in 0..Int.MAX_VALUE.toLong() && b in 0..Int.MAX_VALUE.toLong()) { "bad component label" }
                        if (a == 0L || b == 0L) known = false
                        f[i] = (a - 1).toInt(); d[i] = (b - 1).toInt()
                    }
                    if (known) { foot = f; drive = d }
                }
                // Decimetre values were written from floats rounded to decimetres; divide the same way the builder rounds.
                return RoutingGraph(
                    source, nodeLon, nodeLat, nodeEle.requantize(), from, to, len.requantizeLen(), asc.requantizeLen(), desc.requantizeLen(),
                    cls, attrs, flags, geomStart, gLon.toArray(), gLat.toArray(), gEle.toArray().requantize(), edgeName, names, speed,
                    level, foot, drive,
                )
            }
        }

        private fun FloatArray.requantize(): FloatArray = FloatArray(size) { quantizeEle(this[it].toDouble()) }
        private fun FloatArray.requantizeLen(): FloatArray = FloatArray(size) { quantizeLength(this[it].toDouble()) }

        private fun eleDm(v: Float): Int? = if (v.isNaN()) null else (v * 10.0).roundToInt()
        private fun dm(v: Float): Long = (v * 10.0).roundToLong().coerceAtLeast(0)

        /** Writes elevation [v] relative to [prevDm]; returns the new previous value. */
        private fun ByteWriter.ele(v: Float, prevDm: Int): Int {
            val d = eleDm(v)
            if (d == null) {
                varint(0)
                return prevDm
            }
            varint(zz(d - prevDm) + 1)
            return d
        }

        private fun sameFloats(a: FloatArray, b: FloatArray): Boolean =
            a.size == b.size && a.indices.all { a[it] == b[it] || (a[it].isNaN() && b[it].isNaN()) }

        private fun zz(v: Int): Long = ((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL
        private fun unzz(v: Long): Int = ((v ushr 1) xor -(v and 1)).toInt()

        internal fun ByteWriter.str(s: String) {
            val b = s.encodeToByteArray()
            varint(b.size.toLong())
            bytes(b)
        }

        internal fun ByteReader.str(): String = bytes(count()).decodeToString()

        internal fun ByteReader.count(): Int {
            val v = varint()
            require(v in 0..Int.MAX_VALUE.toLong()) { "bad count $v" }
            return v.toInt()
        }

        internal fun fnv1a(b: ByteArray, n: Int): Int {
            var h = 0x811C9DC5.toInt()
            for (i in 0 until n) {
                h = h xor (b[i].toInt() and 0xFF)
                h *= 0x01000193
            }
            return h
        }
    }
}

/**
 * Edge segments bucketed in a square grid of [CELL_M] metres in a local equirectangular frame
 * around the graph's centre, for nearest-edge queries.
 */
private class SegmentGrid(private val g: RoutingGraph) {
    private val lat0: Double
    private val lon0: Double
    private val mPerLon: Double
    private val mPerLat = 111_195.0
    private val cells = HashMap<Long, IntList>()
    private val segEdge: IntArray
    private val segK: IntArray
    private var refs = 0L

    /** Segment arrays, cell lists (with their slack) and about 100 bytes of map entry per cell. */
    fun approxBytes(): Long = 8L * segEdge.size + 8L * refs + 100L * cells.size

    init {
        lat0 = if (g.nodeCount == 0) 0.0 else (g.nodeLat.min().toDouble() + g.nodeLat.max()) / 2 / RoutingGraph.E6
        lon0 = if (g.nodeCount == 0) 0.0 else (g.nodeLon.min().toDouble() + g.nodeLon.max()) / 2 / RoutingGraph.E6
        mPerLon = mPerLat * cos(lat0 * PI / 180)
        val se = IntList()
        val sk = IntList()
        for (e in 0 until g.edgeCount) {
            for (k in 0 until g.pointCount(e) - 1) {
                val id = se.size
                se.add(e)
                sk.add(k)
                val x1 = x(g.pointLonE6(e, k)); val y1 = y(g.pointLatE6(e, k))
                val x2 = x(g.pointLonE6(e, k + 1)); val y2 = y(g.pointLatE6(e, k + 1))
                val cx0 = floor(minOf(x1, x2) / CELL_M).toInt()
                val cx1 = floor(maxOf(x1, x2) / CELL_M).toInt()
                val cy0 = floor(minOf(y1, y2) / CELL_M).toInt()
                val cy1 = floor(maxOf(y1, y2) / CELL_M).toInt()
                for (cy in cy0..cy1) for (cx in cx0..cx1) { cells.getOrPut(key(cx, cy)) { IntList(4) }.add(id); refs++ }
            }
        }
        segEdge = se.toArray()
        segK = sk.toArray()
    }

    private fun x(lonE6: Int) = (lonE6 / RoutingGraph.E6 - lon0) * mPerLon
    private fun y(latE6: Int) = (latE6 / RoutingGraph.E6 - lat0) * mPerLat
    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)

    fun nearest(lat: Double, lon: Double, maxM: Double, accept: ((Int) -> Boolean)?): EdgeSnap? {
        val px = (lon - lon0) * mPerLon
        val py = (lat - lat0) * mPerLat
        val cx = floor(px / CELL_M).toInt()
        val cy = floor(py / CELL_M).toInt()
        val rings = ceil(maxM / CELL_M).toInt() + 1
        var best = Double.MAX_VALUE
        var bestSeg = -1
        var bestT = 0.0
        for (r in 0..rings) {
            // Everything in ring r is at least (r - 1) cells away.
            if (bestSeg >= 0 && sqrt(best) <= (r - 1) * CELL_M) break
            for (dy in -r..r) for (dx in -r..r) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                val list = cells[key(cx + dx, cy + dy)] ?: continue
                for (i in 0 until list.size) {
                    val s = list[i]
                    val e = segEdge[s]
                    if (accept != null && !accept(e)) continue
                    val k = segK[s]
                    val ax = x(g.pointLonE6(e, k)); val ay = y(g.pointLatE6(e, k))
                    val bx = x(g.pointLonE6(e, k + 1)); val by = y(g.pointLatE6(e, k + 1))
                    val vx = bx - ax; val vy = by - ay
                    val l2 = vx * vx + vy * vy
                    val t = if (l2 > 0) (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0.0, 1.0) else 0.0
                    val qx = ax + t * vx - px; val qy = ay + t * vy - py
                    val d2 = qx * qx + qy * qy
                    if (d2 < best) { best = d2; bestSeg = s; bestT = t }
                }
            }
        }
        if (bestSeg < 0 || sqrt(best) > maxM) return null
        val e = segEdge[bestSeg]
        val k = segK[bestSeg]
        val cum = g.cumulative(e)
        val total = cum.last()
        val along = cum[k] + bestT * (cum[k + 1] - cum[k])
        val sLat = (g.pointLatE6(e, k) + bestT * (g.pointLatE6(e, k + 1) - g.pointLatE6(e, k))) / RoutingGraph.E6
        val sLon = (g.pointLonE6(e, k) + bestT * (g.pointLonE6(e, k + 1) - g.pointLonE6(e, k))) / RoutingGraph.E6
        return EdgeSnap(e, k, if (total > 0) (along / total).coerceIn(0.0, 1.0) else 0.0, sLat, sLon, TrackStats.distanceM(lat, lon, sLat, sLon))
    }

    companion object {
        const val CELL_M = 200.0
    }
}

/** A growable IntArray. */
internal class IntList(capacity: Int = 16) {
    private var a = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(maxOf(8, a.size * 2))
        a[size++] = v
    }

    operator fun get(i: Int): Int = a[i]
    operator fun set(i: Int, v: Int) { a[i] = v }
    fun toArray(): IntArray = a.copyOf(size)
    fun clear() { size = 0 }
}

/** A growable FloatArray. */
internal class FloatList(capacity: Int = 16) {
    private var a = FloatArray(capacity)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(maxOf(8, a.size * 2))
        a[size++] = v
    }

    operator fun get(i: Int): Float = a[i]
    fun toArray(): FloatArray = a.copyOf(size)
}

/** A growable DoubleArray. */
internal class DoubleList(capacity: Int = 16) {
    private var a = DoubleArray(capacity)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == a.size) a = a.copyOf(maxOf(8, a.size * 2))
        a[size++] = v
    }

    operator fun get(i: Int): Double = a[i]
    fun toArray(): DoubleArray = a.copyOf(size)
}
