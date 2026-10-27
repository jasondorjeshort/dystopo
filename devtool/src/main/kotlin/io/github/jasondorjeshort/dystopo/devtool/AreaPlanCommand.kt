package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.areas.AreaLimit
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.PolygonRings
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.server.LiveTiles
import io.github.jasondorjeshort.dystopo.server.reportJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * T140 (ADR 0041): plans an area of interest without the network: a polygon from `--ring` (repeat it for holes and
 * further parts, even-odd) or the area within `--radius` of `--around` (the one-point corridor up to 20 km, a 48-vertex
 * polygon circle beyond), and prints, per zoom range, the tile counts and estimated bytes of the manifest the server's
 * `POST /area` makes (DEM tiles from z6, vector tiles to z14, one tile of margin), with the outer rings' boxes' upper bound.
 * T152: every range is planned at any size (the tile sets are rows): the estimate (tiles times `AreaManifest.estimate`,
 * `estimated` true), the rows a download walks, and `pastHeapLimit` when the tiles pass what the phone's tile cache index
 * holds comfortably on a 576 MB heap (`AreaLimit`; said, never a refusal).
 *
 * ```
 * devtool area-plan --around 46.786,-121.735 --radius 5km
 * devtool area-plan --ring "46.77,-121.76;46.77,-121.71;46.80,-121.71;46.80,-121.76" --ranges 8-13,8-15
 * ```
 */
class AreaPlanCommand : CliktCommand(name = "area-plan") {
    override fun help(context: Context) =
        "Plan a drawn area (--ring) or the area around a point (--around, --radius): tile counts and bytes per zoom range, as JSON."

    private val rings by option("--ring", help = "a ring as lat,lon;lat,lon;lat,lon (repeat for holes and further parts)").multiple()
    private val around by option("--around", help = "lat,lon of the centre of a disc")
    private val radius by option("--radius", help = "the disc's radius: metres, or with km or mi (5km, 3mi)")
    private val buffer by option("--buffer", help = "a polygon's buffer in metres").double().default(0.0)
    private val ranges by option("--ranges", help = "zoom ranges to plan, min-max,min-max").default("8-13,8-15")
    private val demMinZoom by option("--dem-min-zoom", help = "the lowest DEM zoom (contours sample coarser zooms than they show)").int().default(LiveTiles.LIVE_MIN_ZOOM)
    private val margin by option("--margin", help = "tiles of margin at every zoom").int().default(1)
    private val name by option("--name", help = "the area's name")

    override fun run() {
        val zoomRanges = ranges.split(',').filter { it.isNotBlank() }.map { r ->
            val p = r.trim().split('-').map { it.trim().toIntOrNull() }
            if (p.size != 2 || p.any { it == null } || p[0]!! > p[1]!!) throw UsageError("--ranges must be min-max,min-max (got '$r')")
            p[0]!!..p[1]!!
        }
        if (zoomRanges.isEmpty()) throw UsageError("--ranges needs at least one min-max")
        val base = try {
            shape(zoomRanges.first())
        } catch (e: IllegalArgumentException) {
            throw UsageError(e.message ?: "bad area")
        }
        val heap = Runtime.getRuntime().maxMemory()
        val plans = zoomRanges.map { zs ->
            val area = base.copy(minZoom = zs.first, maxZoom = zs.last)
            val dem = minOf(demMinZoom, zs.last)..minOf(zs.last, LiveTiles.LIVE_MAX_ZOOM)
            val osm = (zs.first..minOf(zs.last, LiveTiles.OSM_MAX_ZOOM)).takeIf { !it.isEmpty() }
            val bound = AreaLimit.upperBound(area, dem, osm, margin)
            val row = linkedMapOf<String, Any?>("zooms" to listOf(zs.first, zs.last), "demZooms" to listOf(dem.first, dem.last), "osmZooms" to osm?.let { listOf(it.first, it.last) })
            row["upperBound"] = bound
            val t0 = System.nanoTime()
            val items = AreaManifest.tileItems(area, dem, osm, margin)
            val bytes = items.sumOf { it.bytes }
            val tiles = items.sumOf { it.count.toLong() }
            row["fillMs"] = (System.nanoTime() - t0) / 1_000_000
            row["tiles"] = tiles
            row["rows"] = items.sumOf { i -> i.tiles.values.sumOf { it.rowCount } }
            row["bytes"] = bytes
            row["estimated"] = true
            row["mb"] = Math.round(bytes / 1e5) / 10.0
            row["mib"] = Math.round(bytes / 104_857.6) / 10.0
            row["gib"] = Math.round(bytes / 107_374_182.4) / 10.0
            row["pastHeapLimit"] = AreaLimit.pastHeap(tiles, AreaLimit.maxTiles(PHONE_HEAP))
            row["perRoute"] = items.associate { it.route to linkedMapOf("tiles" to it.count, "bytes" to it.bytes, "perZoom" to it.tiles.toSortedMap().mapKeys { (z, _) -> z.toString() }.mapValues { (_, s) -> s.size }) }
            row
        }
        val s = base.shape
        val report = linkedMapOf(
            "area" to linkedMapOf(
                "kind" to base.kind, "shape" to if (s is AreaShape.Corridor && s.points.size == 1) "disc" else base.kind, "id" to base.id,
                "manifestVersion" to base.manifestVersion, "bounds" to base.bounds.toList(),
                "rings" to (s as? AreaShape.Polygon)?.rings?.size, "vertices" to (s as? AreaShape.Polygon)?.rings?.sumOf { it.size },
                "areaKm2" to (s as? AreaShape.Polygon)?.let { Math.round(PolygonRings.areaM2(it.rings) / 1e4) / 100.0 },
                "radiusM" to (s as? AreaShape.Corridor)?.bufferM, "json" to base.json(),
            ),
            "plans" to plans,
            "estimate" to "bytes per tile as AreaManifest.estimate (DEM 160 kB; vector by zoom); mb is 10^6 bytes, mib 2^20",
            // T152: the phone's tile cache index (the one per-tile structure left); said in `pastHeapLimit`, never a refusal.
            "heapLimit" to linkedMapOf(
                "what" to "tiles the phone's tile cache index holds in a quarter of its heap at ${AreaLimit.BYTES_PER_TILE} B a tile; a plan past it proceeds",
                "maxTilesAt576MbHeap" to AreaLimit.maxTiles(PHONE_HEAP), "maxTilesAt256MbHeap" to AreaLimit.maxTiles(256L shl 20), "thisJvmHeapMb" to heap / 1_048_576,
            ),
        )
        echo(reportJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(MiniJson.encode(report))))
    }

    private fun shape(zs: IntRange): AreaOfInterest {
        if (rings.isNotEmpty() && around != null) throw UsageError("use --ring or --around, not both")
        if (around != null) {
            val c = around!!.split(',').map { it.trim().toDoubleOrNull() }
            if (c.size != 2 || c.any { it == null }) throw UsageError("--around must be lat,lon")
            val r = radius ?: throw UsageError("--around needs --radius")
            return AreaOfInterest.around(AreaPoint(c[0]!!, c[1]!!), radiusM(r), zs.first, zs.last, name)
        }
        if (rings.isEmpty()) throw UsageError("give --ring lat,lon;lat,lon;lat,lon (repeatable) or --around lat,lon --radius 5km")
        return AreaOfInterest.fromQuery(buildMap {
            put("ring", rings.joinToString("|"))
            put("bufferM", buffer.toString())
            put("minZoom", zs.first.toString())
            put("maxZoom", zs.last.toString())
            name?.let { put("name", it) }
        })
    }

    companion object {
        /** T152: the emulator's and a typical phone's large heap (`largeHeap`), for `pastHeapLimit`. */
        const val PHONE_HEAP = 576L shl 20

        /** "5km", "3mi", "800m" or "5000" (metres). */
        fun radiusM(s: String): Double {
            val t = s.trim().lowercase()
            val (num, unit) = when {
                t.endsWith("km") -> t.dropLast(2) to 1000.0
                t.endsWith("mi") -> t.dropLast(2) to 1609.344
                t.endsWith("m") -> t.dropLast(1) to 1.0
                else -> t to 1.0
            }
            return (num.trim().toDoubleOrNull() ?: throw UsageError("--radius must be a distance like 5km, 3mi or 5000 (got '$s')")) * unit
        }
    }
}
