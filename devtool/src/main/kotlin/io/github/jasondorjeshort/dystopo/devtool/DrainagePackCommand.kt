package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.dem.Drainage
import io.github.jasondorjeshort.dystopo.dem.DrainageLine
import io.github.jasondorjeshort.dystopo.dem.DrainageParams
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.FlowGrid
import io.github.jasondorjeshort.dystopo.dem.StreamClass
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileIds
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.render.Slope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Serializable
data class DrainagePackZoom(val zoom: Int, val tiles: Int, val features: Int, val vertices: Int, val layerBytes: Long)

@Serializable
data class DrainagePackSample(
    /** Rewritten tiles compared layer by layer with the input. */
    val tiles: Int,
    /** Source layers other than `drainage` in those tiles, and how many are byte-equal to the input's. */
    val layersCompared: Int,
    val layersByteEqual: Int,
    /** `drainage` features decoded back from the sampled tiles. */
    val drainageFeatures: Int,
    /** Tiles the pass did not touch, compared as stored (compressed) bytes. */
    val untouchedTiles: Int,
    val untouchedByteEqual: Int,
    /** Every tile of the input is addressed in the output. */
    val allInputTilesPresent: Boolean,
)

@Serializable
data class DrainagePackReport(
    val input: String,
    val output: String,
    val inputBytes: Long,
    val outputBytes: Long,
    val bbox: List<Double>,
    val gridZoom: Int,
    val widthPx: Int,
    val heightPx: Int,
    val cellSizeMeters: Double,
    /** The DEM pack the grid was read from, or the elevation source id it was fetched from. */
    val elevation: String,
    val noDataCells: Int,
    val iceMasked: Boolean,
    val maskedCells: Int,
    val thresholdKm2: Double,
    val smoothPasses: Int,
    val simplifyCells: Double,
    val simplifyMeters: Double,
    val lines: Int,
    /** Lines and km per class, after smoothing (km before smoothing in [stairKm]). */
    val byClass: Map<String, DrainageClassStats>,
    /** Total D8 length (the staircase) and the length drawn after smoothing, km. */
    val stairKm: Double,
    val drawnKm: Double,
    val verticesRaw: Int,
    val verticesSimplified: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val zooms: List<DrainagePackZoom>,
    val tilesIn: Int,
    val tilesOut: Int,
    val tilesRewritten: Int,
    /** Tiles past the pack's edge that a line's buffer reached: not written (the pack holds no map there). */
    val tilesOutsidePack: Int,
    val featuresOutsidePack: Int,
    /** `drainage` layers already in the input (a second run) that were replaced. */
    val layersReplaced: Int,
    val sample: DrainagePackSample,
    val timingsMs: Map<String, Long>,
    val provenance: List<String>,
)

/**
 * T174: the stream lines T165 derives from the elevation grid ([FlowGrid], [Drainage]) written into an existing
 * outdoors vector pack as one more source layer, `drainage` (`tilebuild/PROFILE.md`), by a post-pass over the
 * archive: no new archive, no pack-format change, so the phone and `serve --osm` draw it with the style rows alone.
 *
 * The grid covers the pack's bounds in one piece at [zoom] (the upslope area of a cell can reach anywhere upstream,
 * so no per-tile margin is enough), masked under `water` polygons and, by default, `landcover` ice read from the pack
 * itself. The D8 lines are staircases; they are smoothed and simplified ([DrainageShape]) before they are cut into
 * tiles. Each rewritten tile is the input tile's bytes, any old `drainage` layer taken out, with the new layer message
 * appended: every other layer stays byte for byte as it was, which the report's sample checks.
 */
class DrainagePackCommand : CliktCommand(name = "drainage-pack") {
    override fun help(context: Context) =
        "Write stream lines derived from the elevation grid into an outdoors vector pack as the `drainage` layer " +
            "(a copy: --out), every other layer kept byte for byte; JSON report with counts, sizes and a sampled byte check."

    private val pack by option("--pack", help = "Outdoors vector pack to read").file(mustExist = true, canBeDir = false).required()
    private val out by option("--out", help = "Rewritten pack to write (must differ from --pack)").file(canBeDir = false).required()
    private val dem by option("--dem", help = "DEM pack (Terrarium PMTiles) to read the grid from at --zoom; without it the grid is fetched from --source")
        .file(mustExist = true, canBeDir = false)
    private val source by option("--source", help = "Elevation source when no --dem is given").choice(*ElevationSource.ids.toTypedArray()).default("best")
    private val zoom by option("--zoom", help = "Zoom of the flow grid (13: about 13 m cells at 47 degrees)").int().default(13)
    private val minZoom by option("--min-zoom", help = "First tile zoom that gets the layer").int().default(12)
    private val maxZoomOption by option("--max-zoom", help = "Last tile zoom that gets the layer (default and at most the pack's maximum)").int()
    private val threshold by option("--threshold", help = "Channel-start threshold, km2").double().default(DrainageParams().thresholdKm2)
    private val keepIce by option("--keep-ice", help = "Keep channels on glaciers (by default `landcover` ice is masked like water)").flag()
    private val smoothPasses by option("--smooth", help = "Passes of the 1-2-1 smoothing over each line (ends fixed)").int().default(DrainageShape.SMOOTH_PASSES)
    private val simplifyCells by option("--simplify", help = "Douglas-Peucker tolerance after smoothing, in grid cells").double().default(DrainageShape.SIMPLIFY_CELLS)
    private val bufferPx by option("--buffer", help = "Tile buffer in pixels of a 256 px tile").int().default(4)
    private val sampleCount by option("--sample", help = "Rewritten tiles to compare layer by layer with the input").int().default(24)

    override fun run() {
        require(pack.canonicalFile != out.canonicalFile) { "--out must differ from --pack" }
        val timings = Timings()
        val t0 = System.nanoTime()
        val reader = PmtilesReader.open(FileRangeSource(pack), Gzip.decompressors)
        val h = reader.header
        require(h.tileType == TileType.MVT) { "${pack.path} holds ${h.tileType} tiles, not vector tiles" }
        require(h.tileCompression == Compression.GZIP || h.tileCompression == Compression.NONE) { "tile compression ${h.tileCompression.label} is not supported" }
        val maxZoom = maxZoomOption ?: h.maxZoom
        require(maxZoom <= h.maxZoom) { "--max-zoom $maxZoom is past the pack's last zoom ${h.maxZoom}" }
        require(minZoom in 0..maxZoom) { "--min-zoom $minZoom must be at most --max-zoom $maxZoom" }
        val box = BoundingBox(h.minLon, h.minLat, h.maxLon, h.maxLat)
        val window = MercatorWindow.forBbox(box, zoom)

        // The grid over the pack's whole box, and the mask from the pack's own water and ice.
        val (grid, demProvenance, elevation) = timings.time("elevation") {
            dem?.let { readDemPack(it, window) } ?: ElevationSource.byId(source).fetch(window).let { Triple(it.grid, it.provenance, source) }
        }
        val mapped = timings.time("mask") { MappedWater.read(pack, window, grid, 30.0) }
        val mask = if (keepIce) mapped.water else BooleanArray(mapped.water.size) { mapped.water[it] || mapped.ice[it] }
        val rowArea = DoubleArray(grid.height) { j -> WebMercator.metersPerPixel(window.latAt(j), zoom, window.tileSize).let { it * it } }
        val flow = timings.time("flow") { FlowGrid.build(grid, rowArea) }
        val result = timings.time("extract") { Drainage.extract(flow, Slope.compute(grid), DrainageParams(thresholdKm2 = threshold), mask) }
        val shaped = timings.time("shape") {
            result.lines.map { it to DrainageShape.simplify(DrainageShape.smooth(it.points, smoothPasses), simplifyCells) }
        }

        // Cut into tiles, zoom by zoom.
        val byTile = HashMap<Long, MutableList<Feature>>()
        timings.time("cut") {
            for (z in minZoom..maxZoom) {
                val scale = 2.0.pow(z - zoom)
                for ((line, pts) in shaped) {
                    val props = properties(line)
                    for ((tile, parts) in DrainageShape.cut(pts, window.x0, window.y0, scale, z, bufferPx)) {
                        byTile.getOrPut(tile) { ArrayList() } += Feature(null, props, Geometry.Lines(parts))
                    }
                }
            }
        }

        // Rewrite: tiles without drainage are copied as stored; the others get the layer appended. Only tiles the
        // pack holds are written: a tile the buffer reaches past the pack's edge stays absent, so the pack never
        // claims ground it has no map for.
        val compress: (ByteArray) -> ByteArray = if (h.tileCompression == Compression.GZIP) Gzip.compress else { b -> b }
        val writer = PmtilesWriter(TileType.MVT, h.tileCompression, h.internalCompression, Gzip.compress)
        val perZoom = java.util.TreeMap<Int, LongArray>() // tiles, features, vertices, layer bytes
        var tilesIn = 0
        var replaced = 0
        val inputIds = ArrayList<Long>()
        timings.time("rewrite") {
            for (e in reader.tileEntries()) for (k in 0 until e.runLength) {
                val id = e.tileId + k
                inputIds += id
                tilesIn++
                val t = TileIds.toZxy(id)
                val stored = reader.tileStored(t.z, t.x, t.y)!!
                val features = byTile[id]
                if (features == null) {
                    writer.addTile(id, stored)
                    continue
                }
                val (kept, removed) = DrainageShape.withoutLayer(reader.decompress(h.tileCompression, stored), LAYER)
                replaced += removed
                val layer = VectorTile(listOf(Layer(LAYER, features))).encode()
                val zs = perZoom.getOrPut(t.z) { LongArray(4) }
                zs[0]++
                zs[1] += features.size.toLong()
                zs[2] += features.sumOf { f -> (f.geometry as Geometry.Lines).lines.sumOf { it.size } }.toLong()
                zs[3] += layer.size.toLong()
                writer.addTile(id, compress(kept + layer))
            }
            val metadata = metadata(reader, maxZoom, demProvenance)
            out.absoluteFile.parentFile.mkdirs()
            val tmp = File(out.absoluteFile.parentFile, out.name + ".part")
            tmp.outputStream().buffered().use { s ->
                writer.finish(
                    { s.write(it) }, metadataJson = MiniJson.encode(metadata),
                    minLon = h.minLon, minLat = h.minLat, maxLon = h.maxLon, maxLat = h.maxLat,
                    centerZoom = h.centerZoom, centerLon = h.centerLon, centerLat = h.centerLat,
                    minZoom = h.minZoom, maxZoom = h.maxZoom,
                )
            }
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val inInput = inputIds.toHashSet()
        val outside = byTile.filterKeys { it !in inInput }

        val sample = timings.time("verify") { verify(reader, inputIds, byTile.keys) }
        val outReader = PmtilesReader.open(FileRangeSource(out), Gzip.decompressors)
        timings.ms["total"] = (System.nanoTime() - t0) / 1_000_000

        val cellM = grid.cellSizeMeters
        fun km(cells: Double) = round3(cells * cellM / 1000)
        val report = DrainagePackReport(
            input = pack.path,
            output = out.path,
            inputBytes = pack.length(),
            outputBytes = out.length(),
            bbox = listOf(box.west, box.south, box.east, box.north),
            gridZoom = zoom,
            widthPx = grid.width,
            heightPx = grid.height,
            cellSizeMeters = round3(cellM),
            elevation = elevation,
            noDataCells = grid.values.count { it.isNaN() },
            iceMasked = !keepIce,
            maskedCells = result.stats.maskSuppressed,
            thresholdKm2 = threshold,
            smoothPasses = smoothPasses,
            simplifyCells = simplifyCells,
            simplifyMeters = round3(simplifyCells * cellM),
            lines = shaped.size,
            byClass = StreamClass.entries.associate { c ->
                val ls = shaped.filter { it.first.streamClass == c }
                c.key to DrainageClassStats(ls.size, km(ls.sumOf { DrainageShape.length(it.second) }))
            },
            stairKm = km(result.lines.sumOf { Drainage.lengthCells(it) }),
            drawnKm = km(shaped.sumOf { DrainageShape.length(it.second) }),
            verticesRaw = result.lines.sumOf { it.pointCount },
            verticesSimplified = shaped.sumOf { it.second.size / 2 },
            minZoom = minZoom,
            maxZoom = maxZoom,
            zooms = perZoom.map { (z, a) -> DrainagePackZoom(z, a[0].toInt(), a[1].toInt(), a[2].toInt(), a[3]) },
            tilesIn = tilesIn,
            tilesOut = outReader.header.addressedTiles.toInt(),
            tilesRewritten = perZoom.values.sumOf { it[0] }.toInt(),
            tilesOutsidePack = outside.size,
            featuresOutsidePack = outside.values.sumOf { it.size },
            layersReplaced = replaced,
            sample = sample,
            timingsMs = timings.ms,
            provenance = demProvenance,
        )
        echo(reportJson.encodeToString(report))
    }

    private fun properties(line: DrainageLine): Map<String, Value> = linkedMapOf(
        "class" to Value.StringValue(line.streamClass.key),
        "catchment_km2" to Value.DoubleValue((line.catchmentKm2 * 100).roundToLong() / 100.0),
    )

    /** The grid at [window] from a Terrarium DEM pack's tiles at the window's zoom; NaN where it has none. */
    private fun readDemPack(file: File, window: MercatorWindow): Triple<ElevationGrid, List<String>, String> {
        val r = PmtilesReader.open(FileRangeSource(file), Gzip.decompressors)
        require(r.header.tileType == TileType.PNG) { "${file.path} holds ${r.header.tileType} tiles, not Terrarium PNG" }
        require(window.zoom in r.header.minZoom..r.header.maxZoom) { "${file.path} has zooms ${r.header.minZoom}..${r.header.maxZoom}, not ${window.zoom}" }
        val meta = r.metadata()
        val noData = (meta["noDataMeters"] as? Number)?.toFloat()
        val w = window.width
        val hgt = window.height
        val ts = window.tileSize
        val values = FloatArray(w * hgt) { Float.NaN }
        for (ty in window.y0 / ts..(window.y0 + hgt - 1) / ts) for (tx in window.x0 / ts..(window.x0 + w - 1) / ts) {
            val bytes = r.tile(window.zoom, tx, ty) ?: continue
            val img = ImageIO.read(bytes.inputStream()) ?: error("tile ${window.zoom}/$tx/$ty is not a PNG")
            require(img.width == ts && img.height == ts) { "tile ${window.zoom}/$tx/$ty is ${img.width}x${img.height}, not $ts" }
            val argb = img.getRGB(0, 0, ts, ts, null, 0, ts)
            for (py in 0 until ts) {
                val gy = ty * ts + py - window.y0
                if (gy !in 0 until hgt) continue
                for (px in 0 until ts) {
                    val gx = tx * ts + px - window.x0
                    if (gx !in 0 until w) continue
                    val e = Terrarium.elevation(argb[py * ts + px])
                    values[gy * w + gx] = if (noData != null && e == noData) Float.NaN else e
                }
            }
        }
        val provenance = (meta["provenance"] as? List<*>)?.map { it.toString() }.orEmpty()
        return Triple(ElevationGrid(w, hgt, window.cellSizeMeters, values), provenance, file.path)
    }

    /** The input's metadata with the layer described (`vector_layers`, `dystopo:layers`) and the DEM's sources added. */
    private fun metadata(reader: PmtilesReader, maxZoom: Int, demProvenance: List<String>): Map<String, Any?> {
        val meta = LinkedHashMap(reader.metadata())
        val description = "Stream lines derived from the elevation grid (flow accumulation over a z$zoom grid of the pack's box), " +
            "masked under water and glaciers; not mapped data. Drawn under the mapped waterways."
        meta["vector_layers"] = (meta["vector_layers"] as? List<*>).orEmpty().filterNot { (it as? Map<*, *>)?.get("id") == LAYER } +
            linkedMapOf(
                "id" to LAYER, "description" to description,
                "fields" to linkedMapOf("class" to "String", "catchment_km2" to "Number"),
                "minzoom" to minZoom, "maxzoom" to maxZoom,
            )
        (meta["dystopo:layers"] as? String)?.let { text ->
            val list = (MiniJson.parse(text) as? List<*>).orEmpty().filterNot { (it as? Map<*, *>)?.get("name") == LAYER } +
                linkedMapOf(
                    "name" to LAYER, "geometry" to "line", "minZoom" to minZoom, "maxZoom" to maxZoom, "description" to description,
                    "attributes" to linkedMapOf("class" to CLASS_DOC, "catchment_km2" to CATCHMENT_DOC),
                )
            meta["dystopo:layers"] = MiniJson.encode(list)
        }
        val ids = LinkedHashSet<String>()
        (meta["dystopo:provenance"] as? String)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { ids += it }
        ids += demProvenance
        meta["dystopo:provenance"] = ids.joinToString(",")
        meta["dystopo:drainage"] = MiniJson.encode(
            linkedMapOf(
                "version" to 1, "gridZoom" to zoom, "thresholdKm2" to threshold, "iceMasked" to !keepIce,
                "smoothPasses" to smoothPasses, "simplifyCells" to simplifyCells, "demProvenance" to demProvenance,
            ),
        )
        return meta
    }

    /** Compares a spread of rewritten tiles layer by layer, and a spread of untouched ones as stored. */
    private fun verify(input: PmtilesReader, inputIds: List<Long>, rewrittenIds: Set<Long>): DrainagePackSample {
        val output = PmtilesReader.open(FileRangeSource(out), Gzip.decompressors)
        fun spread(ids: List<Long>, n: Int) = if (ids.size <= n) ids else List(n) { ids[(it.toLong() * ids.size / n).toInt()] }
        val inInput = inputIds.toHashSet()
        val rewritten = spread(rewrittenIds.filter { it in inInput }.sorted(), sampleCount)
        var compared = 0
        var equal = 0
        var features = 0
        for (id in rewritten) {
            val t = TileIds.toZxy(id)
            val before = DrainageShape.layers(input.tile(t.z, t.x, t.y)!!).filter { it.first != LAYER }
            val newBytes = output.tile(t.z, t.x, t.y)!!
            val after = DrainageShape.layers(newBytes).filter { it.first != LAYER }
            compared += before.size
            equal += before.indices.count { i -> i < after.size && before[i].first == after[i].first && before[i].second.contentEquals(after[i].second) }
            features += VectorTile.decode(newBytes).layer(LAYER)?.features?.size ?: 0
        }
        val untouched = spread(inputIds.filter { it !in rewrittenIds }, sampleCount)
        val untouchedEqual = untouched.count { id ->
            val t = TileIds.toZxy(id)
            input.tileStored(t.z, t.x, t.y)!!.contentEquals(output.tileStored(t.z, t.x, t.y) ?: return@count false)
        }
        val present = inputIds.all { output.findEntry(it) != null }
        return DrainagePackSample(rewritten.size, compared, equal, features, untouched.size, untouchedEqual, present)
    }

    private fun round3(d: Double) = (d * 1000).roundToLong() / 1000.0

    companion object {
        const val LAYER = "drainage"
        const val CLASS_DOC = "likely_running, seasonal, likely_dry (the project's own classes, from catchment area and mean slope; no climate or season)"
        const val CATCHMENT_DOC = "contributing area at the line's downstream end, km2 (a lower bound where the catchment reaches past the pack's box)"
    }
}

/**
 * T174: the geometry and byte work of [DrainagePackCommand], kept free of files so it is tested on its own.
 * Lines are interleaved x,y arrays in grid coordinates (cell (i, j) at x=i, y=j, as [DrainageLine.points]).
 */
internal object DrainageShape {
    /**
     * Two passes of the 1-2-1 filter: a D8 line steps straight and diagonally, a zigzag of period two to four
     * cells, which the first pass removes at period two and two passes damp to a quarter at period four. The ends
     * stay where they are, so a line still meets the line it flows into at the shared confluence cell.
     */
    const val SMOOTH_PASSES = 2

    /**
     * Douglas-Peucker after smoothing, in grid cells: half a cell (about 6.5 m on a z13 grid at 47 degrees) is one
     * screen pixel at the layer's deepest pack zoom (z14) and a quarter of one at z12, below what the eye sees on a
     * thin line once smoothed, while the straight runs of a smoothed staircase lose most of their vertices.
     */
    const val SIMPLIFY_CELLS = 0.5

    fun smooth(points: DoubleArray, passes: Int): DoubleArray {
        var p = points
        val n = p.size / 2
        if (n < 3) return p.copyOf()
        repeat(passes) {
            val q = p.copyOf()
            for (k in 1 until n - 1) {
                q[2 * k] = 0.25 * p[2 * k - 2] + 0.5 * p[2 * k] + 0.25 * p[2 * k + 2]
                q[2 * k + 1] = 0.25 * p[2 * k - 1] + 0.5 * p[2 * k + 1] + 0.25 * p[2 * k + 3]
            }
            p = q
        }
        return p
    }

    /** Douglas-Peucker with [tolerance] in the points' units; the ends are always kept. */
    fun simplify(points: DoubleArray, tolerance: Double): DoubleArray {
        val n = points.size / 2
        if (n < 3 || tolerance <= 0) return points.copyOf()
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val ax = points[2 * a]
            val ay = points[2 * a + 1]
            val dx = points[2 * b] - ax
            val dy = points[2 * b + 1] - ay
            val len = hypot(dx, dy)
            var worst = -1.0
            var at = -1
            for (k in a + 1 until b) {
                val px = points[2 * k] - ax
                val py = points[2 * k + 1] - ay
                val d = if (len == 0.0) hypot(px, py) else kotlin.math.abs(px * dy - py * dx) / len
                if (d > worst) {
                    worst = d
                    at = k
                }
            }
            if (worst > tolerance) {
                keep[at] = true
                stack.addLast(a to at)
                stack.addLast(at to b)
            }
        }
        val out = DoubleArray(keep.count { it } * 2)
        var j = 0
        for (k in 0 until n) if (keep[k]) {
            out[j++] = points[2 * k]
            out[j++] = points[2 * k + 1]
        }
        return out
    }

    /** Length of a line in its own units. */
    fun length(points: DoubleArray): Double {
        var len = 0.0
        for (k in 1 until points.size / 2) len += hypot(points[2 * k] - points[2 * k - 2], points[2 * k + 1] - points[2 * k - 1])
        return len
    }

    /**
     * A line in grid coordinates of a window at ([x0], [y0]) cut into the tiles of zoom [z] it touches, [scale]
     * being 2^(z - window zoom): tile id to the parts inside the tile plus [bufferPx] (of 256), in 4096-unit tile
     * coordinates. Cell centres sit at global pixel (x0 + i + 0.5).
     */
    fun cut(points: DoubleArray, x0: Int, y0: Int, scale: Double, z: Int, bufferPx: Int): Map<Long, List<List<TilePoint>>> {
        val n = points.size / 2
        if (n < 2) return emptyMap()
        val gx = DoubleArray(n) { (x0 + points[2 * it] + 0.5) * scale }
        val gy = DoubleArray(n) { (y0 + points[2 * it + 1] + 0.5) * scale }
        val b = bufferPx.toDouble()
        val max = (1 shl z) - 1
        val tx0 = floor((gx.min() - b) / 256).toInt().coerceIn(0, max)
        val tx1 = floor((gx.max() + b) / 256).toInt().coerceIn(0, max)
        val ty0 = floor((gy.min() - b) / 256).toInt().coerceIn(0, max)
        val ty1 = floor((gy.max() + b) / 256).toInt().coerceIn(0, max)
        val out = LinkedHashMap<Long, List<List<TilePoint>>>()
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val ox = tx * 256.0
            val oy = ty * 256.0
            val parts = clip(gx, gy, ox - b, oy - b, ox + 256 + b, oy + 256 + b).mapNotNull { part ->
                val pts = ArrayList<TilePoint>(part.size / 2)
                for (k in 0 until part.size / 2) {
                    val p = TilePoint(((part[2 * k] - ox) * 16).roundToInt(), ((part[2 * k + 1] - oy) * 16).roundToInt())
                    if (pts.isEmpty() || pts.last() != p) pts += p
                }
                pts.takeIf { it.size >= 2 }
            }
            if (parts.isNotEmpty()) out[TileIds.fromZxy(z, tx, ty)] = parts
        }
        return out
    }

    /** The polyline ([xs], [ys]) clipped to a rectangle (Liang-Barsky per segment), as runs of interleaved x,y. */
    fun clip(xs: DoubleArray, ys: DoubleArray, xMin: Double, yMin: Double, xMax: Double, yMax: Double): List<DoubleArray> {
        val runs = ArrayList<DoubleArray>()
        var run = ArrayList<Double>()
        fun flush() {
            if (run.size >= 4) runs += run.toDoubleArray()
            run = ArrayList()
        }
        for (k in 1 until xs.size) {
            val ax = xs[k - 1]
            val ay = ys[k - 1]
            val dx = xs[k] - ax
            val dy = ys[k] - ay
            var t0 = 0.0
            var t1 = 1.0
            var inside = true
            for ((p, q) in listOf(-dx to ax - xMin, dx to xMax - ax, -dy to ay - yMin, dy to yMax - ay)) {
                if (p == 0.0) {
                    if (q < 0) inside = false
                } else {
                    val r = q / p
                    if (p < 0) { if (r > t1) inside = false else if (r > t0) t0 = r } else { if (r < t0) inside = false else if (r < t1) t1 = r }
                }
                if (!inside) break
            }
            if (!inside) {
                flush()
                continue
            }
            val sx = ax + t0 * dx
            val sy = ay + t0 * dy
            if (t0 > 0.0 || run.isEmpty()) {
                flush()
                run += sx
                run += sy
            }
            run += ax + t1 * dx
            run += ay + t1 * dy
            if (t1 < 1.0) flush()
        }
        flush()
        return runs
    }

    /** The top-level layer messages of an MVT tile: (layer name, the message's bytes including its key and length). */
    fun layers(tile: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var pos = 0
        while (pos < tile.size) {
            val start = pos
            val (key, a) = varint(tile, pos)
            pos = a
            when ((key and 7L).toInt()) {
                0 -> pos = varint(tile, pos).second
                1 -> pos += 8
                5 -> pos += 4
                2 -> {
                    val (len, b) = varint(tile, pos)
                    val end = b + len.toInt()
                    require(end <= tile.size) { "truncated tile" }
                    if ((key ushr 3) == 3L) out += layerName(tile, b, end) to tile.copyOfRange(start, end)
                    pos = end
                }
                else -> throw IllegalArgumentException("unsupported wire type in tile")
            }
        }
        return out
    }

    /**
     * [tile] with every top-level layer named [name] taken out and everything else kept byte for byte, and how many
     * were taken out.
     */
    fun withoutLayer(tile: ByteArray, name: String): Pair<ByteArray, Int> {
        val all = layers(tile)
        val drop = all.filter { it.first == name }
        if (drop.isEmpty()) return tile to 0
        val out = java.io.ByteArrayOutputStream(tile.size)
        // Walk again so fields that are not layers (none in practice) keep their place too.
        var pos = 0
        while (pos < tile.size) {
            val start = pos
            val (key, a) = varint(tile, pos)
            pos = a
            var isDropped = false
            when ((key and 7L).toInt()) {
                0 -> pos = varint(tile, pos).second
                1 -> pos += 8
                5 -> pos += 4
                2 -> {
                    val (len, b) = varint(tile, pos)
                    val end = b + len.toInt()
                    isDropped = (key ushr 3) == 3L && layerName(tile, b, end) == name
                    pos = end
                }
                else -> throw IllegalArgumentException("unsupported wire type in tile")
            }
            if (!isDropped) out.write(tile, start, pos - start)
        }
        return out.toByteArray() to drop.size
    }

    private fun layerName(buf: ByteArray, from: Int, end: Int): String {
        var pos = from
        while (pos < end) {
            val (key, a) = varint(buf, pos)
            pos = a
            when ((key and 7L).toInt()) {
                0 -> pos = varint(buf, pos).second
                1 -> pos += 8
                5 -> pos += 4
                2 -> {
                    val (len, b) = varint(buf, pos)
                    if ((key ushr 3) == 1L) return buf.decodeToString(b, b + len.toInt())
                    pos = b + len.toInt()
                }
                else -> return ""
            }
        }
        return ""
    }

    private fun varint(buf: ByteArray, from: Int): Pair<Long, Int> {
        var pos = from
        var shift = 0
        var v = 0L
        while (true) {
            require(pos < buf.size) { "truncated varint" }
            val b = buf[pos++].toInt() and 0xFF
            v = v or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return v to pos
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }
}
