package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.PageLayout
import io.github.jasondorjeshort.dystopo.render.Paper
import io.github.jasondorjeshort.dystopo.render.SheetGrid
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * T167: the printed map sheet in the live viewer (`devtool serve --live` only: it renders through devtool's
 * [MapRenderer] and PDFBox, which `serverlib` cannot reach, so the standalone server has no such route and
 * answers 404, which hides the viewer's Print box).
 *
 * - `GET /print` with no place: 200, JSON of what the route takes (`scales`, `papers`, `grids`, `units`,
 *   `defaultScale`). The viewer probes it and shows its Print box only when it answers.
 * - `GET /print?lat=&lon=` (or `area=<name>` from areas.json, or `bbox=w,s,e,n`) with `scale=25000` (default;
 *   10000, 50000, any denominator from 500, or `fit` with `area` or `bbox`), `paper=a4|letter|a3` (default by
 *   region: Letter in the US, A4 elsewhere), `landscape=1`, `grid=utm|latlon|both|none` (default utm),
 *   `units=ft|m` (default by region), `title=`, `dpi=` (50 to 300, default 300) and `format=pdf|png|json`
 *   (default pdf): the sheet as `devtool print` makes it. The PDF is one page of the paper's size; `json` is
 *   the report (`devtool print`'s, with `pdfBytes` and `pdfPages`). The header `X-Dystopo-Print` carries
 *   `scale`, `paper`, `pages`, `dpi` and `ms`. A bad option is a 400. One sheet is made at a time; a second
 *   request waits.
 *
 * Trails, water and names come from the first vector pack in [outDir] that covers the centre; elsewhere the
 * sheet is terrain only (the live OpenFreeMap tiles are not drawn by the PNG renderer).
 */
class PrintRoutes(private val outDir: File = File("out")) : HttpHandler {
    private val lock = Any()

    override fun handle(ex: HttpExchange) {
        try {
            if (ex.requestURI.path != "/print") return reply(ex, 404, "text/plain", "not found\n".toByteArray())
            if (ex.requestMethod != "GET") return reply(ex, 405, "text/plain", "GET only\n".toByteArray())
            val q = query(ex)
            if (listOf("lat", "lon", "area", "bbox").none { it in q }) return reply(ex, 200, "application/json", options().toByteArray())
            val format = q["format"] ?: "pdf"
            require(format in listOf("pdf", "png", "json")) { "format must be pdf, png or json (got '$format')" }
            val (req, name) = request(q)
            val started = System.nanoTime()
            val timings = Timings()
            val sheet = synchronized(lock) { PrintSheet.make(req, timings) }
            val pdf = if (format == "png") null else timings.time("writePdf") { sheet.pdf() }
            val pages = pdf?.let { PrintSheet.pdfPages(it) } ?: 1
            val ms = (System.nanoTime() - started) / 1_000_000
            val stem = PrintSheet.stem(name, sheet.layout)
            ex.responseHeaders.add(
                "X-Dystopo-Print",
                "scale=${sheet.layout.scale.toLong()}; paper=${sheet.layout.paper.key}; pages=$pages; dpi=${sheet.report.dpi}; ms=$ms",
            )
            when (format) {
                "pdf" -> {
                    ex.responseHeaders.add("Content-Disposition", "inline; filename=\"$stem.pdf\"")
                    reply(ex, 200, "application/pdf", pdf!!)
                }
                "png" -> {
                    ex.responseHeaders.add("Content-Disposition", "inline; filename=\"$stem.png\"")
                    reply(ex, 200, "image/png", sheet.png())
                }
                else -> {
                    val report = sheet.report.copy(timingsMs = LinkedHashMap(timings.ms)).apply { pdfBytes = pdf!!.size.toLong(); pdfPages = pages }
                    reply(ex, 200, "application/json", reportJson.encodeToString(report).toByteArray())
                }
            }
        } catch (e: IllegalArgumentException) {
            reply(ex, 400, "text/plain", ((e.message ?: e.toString()) + "\n").toByteArray())
        } catch (e: Throwable) {
            reply(ex, 500, "text/plain", (e.toString() + "\n").toByteArray())
        }
    }

    private fun options(): String = JsonWriter.write(
        linkedMapOf(
            "print" to true,
            "scales" to PageLayout.SCALES + listOf("fit"),
            "defaultScale" to PageLayout.DEFAULT_SCALE.toLong(),
            "papers" to Paper.entries.map { it.key },
            "grids" to SheetGrid.entries.map { it.key },
            "units" to listOf("ft", "m"),
        ),
    )

    /** The sheet asked for, and the name its file gets. */
    private fun request(q: Map<String, String>): Pair<SheetRequest, String> {
        var name = "map"
        var title: String? = null
        var box: BoundingBox? = null
        val lat: Double
        val lon: Double
        val area = q["area"]
        when {
            area != null -> {
                val def = Areas.load()[area] ?: throw IllegalArgumentException(Areas.unknownAreaMessage(area, Areas.load()))
                box = def.boundingBox()
                name = area
                title = def.description.substringBefore('.').takeIf { it.isNotBlank() } ?: area
                PageLayout.center(box).let { (a, b) -> lat = a; lon = b }
            }
            q["bbox"] != null -> {
                val p = q.getValue("bbox").split(",").map { it.trim().toDoubleOrNull() }
                require(p.size == 4 && p.all { it != null && it.isFinite() }) { "bbox must be west,south,east,north" }
                box = BoundingBox.unwrapped(p[0]!!, p[1]!!, p[2]!!, p[3]!!)
                PageLayout.center(box).let { (a, b) -> lat = a; lon = b }
            }
            else -> {
                lat = q["lat"]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("lat and lon are needed")
                lon = q["lon"]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("lat and lon are needed")
            }
        }
        val scaleText = (q["scale"] ?: PageLayout.DEFAULT_SCALE.toLong().toString()).trim().lowercase()
        val scale = if (scaleText == "fit") null else scaleText.toDoubleOrNull()?.takeIf { it >= 500 && it.isFinite() }
            ?: throw IllegalArgumentException("scale must be a denominator of at least 500 or fit (got '$scaleText')")
        require(scale != null || box != null) { "scale=fit needs bbox or area" }
        val paper = q["paper"]?.let { Paper.parse(it) ?: throw IllegalArgumentException("paper must be a4, letter or a3") }
        val grid = q["grid"]?.let { SheetGrid.parse(it) ?: throw IllegalArgumentException("grid must be utm, latlon, both or none") } ?: SheetGrid.UTM
        val units = q["units"]?.let { u -> ContourUnits.entries.firstOrNull { it.symbol == u.trim() } ?: throw IllegalArgumentException("units must be ft or m") }
        val dpi = q["dpi"]?.let { d -> d.toDoubleOrNull()?.takeIf { it in 50.0..300.0 } ?: throw IllegalArgumentException("dpi must be 50 to 300") }
            ?: PageLayout.DEFAULT_DPI
        val landscape = q["landscape"]?.trim()?.lowercase() in listOf("1", "true", "on", "yes")
        q["title"]?.trim()?.takeIf { it.isNotEmpty() }?.let { title = it.take(80) }
        val packs = LiveTiles.findPacks(emptyList(), outDir).second
        val req = SheetRequest(
            title = title ?: "Map sheet", centerLat = lat, centerLon = lon, scale = scale, fitBox = box, paper = paper,
            landscape = landscape, grid = grid, units = units, dpi = dpi, osm = PrintSheet.osmPackAt(lat, lon, packs), area = area,
        )
        return req to name
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }

    private fun reply(ex: HttpExchange, status: Int, type: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.responseHeaders.add("Access-Control-Expose-Headers", "X-Dystopo-Print, Content-Disposition")
        ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
    }
}
