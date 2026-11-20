package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.aircraft.AdsbLolSource
import io.github.jasondorjeshort.dystopo.aircraft.AircraftFetch
import io.github.jasondorjeshort.dystopo.aircraft.AircraftHttp
import io.github.jasondorjeshort.dystopo.aircraft.AircraftHttpReply
import io.github.jasondorjeshort.dystopo.aircraft.AircraftJson
import io.github.jasondorjeshort.dystopo.aircraft.AircraftNoConnection
import io.github.jasondorjeshort.dystopo.aircraft.AircraftQuery
import io.github.jasondorjeshort.dystopo.aircraft.AircraftTracker
import io.github.jasondorjeshort.dystopo.aircraft.AirportNaming
import io.github.jasondorjeshort.dystopo.aircraft.Airport
import io.github.jasondorjeshort.dystopo.aircraft.Observer
import io.github.jasondorjeshort.dystopo.aircraft.ReplaySource
import io.github.jasondorjeshort.dystopo.aircraft.RouteTable
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.server.Caches
import io.github.jasondorjeshort.dystopo.server.reportJson
import io.github.jasondorjeshort.dystopo.sources.elevation.Geoids
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile

private fun printReport(cmd: CliktCommand, report: Map<String, Any?>) =
    cmd.echo(reportJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), reportJson.parseToJsonElement(JsonWriter.write(report))))

/** The route table the app ships (`androidApp/src/main/assets/aircraft/routes.gzip`). */
internal const val ROUTE_ASSET = "androidApp/src/main/assets/aircraft/routes.gzip"

/** Reads the shipped route table (gzip) into a [RouteTable]. */
internal fun readRouteTable(file: File): RouteTable = RouteTable.parse(GZIPInputStream(file.inputStream()).use { it.readBytes() })

/**
 * T311: `aircraft-routes` builds the route table the app ships from the VRS standing data's routes (CC0; the repository's
 * zip from GitHub) and OurAirports' `airports.csv` (public domain), both downloaded into `cache/aircraft/` (`--refresh`
 * downloads them again). Every route whose airports all resolve to an OurAirports row (by `icao_code`, then `ident`, then
 * `gps_code`) is kept; the airports kept are the ones a route names. Writes [ROUTE_ASSET] (gzip, best compression) and
 * prints its size, the counts and the build time. Over [MAX_GZ] compressed it writes the airports alone and says so
 * (`airportsOnly`). Refresh by hand: run it, view the report, commit the file.
 */
class AircraftRoutesCommand : CliktCommand(name = "aircraft-routes") {
    override fun help(context: Context) =
        "Build the Flights layer's route table (VRS standing data routes, CC0, and OurAirports airports, public domain) into $ROUTE_ASSET; prints a JSON report."

    private val vrs by option("--vrs", help = "the standing-data repository zip (downloaded when missing)")
        .default(File(Caches.root, "aircraft/vrs-standing-data.zip").path)
    private val airportsCsv by option("--airports", help = "OurAirports airports.csv (downloaded when missing)")
        .default(File(Caches.root, "aircraft/ourairports-airports.csv").path)
    private val out by option("--out", help = "the asset to write").default(ROUTE_ASSET)
    private val refresh by option("--refresh", help = "download both inputs again").flag()

    override fun run() {
        val t0 = System.nanoTime()
        val vrsFile = File(vrs)
        val apFile = File(airportsCsv)
        val downloads = linkedMapOf<String, Any?>()
        if (refresh || !vrsFile.isFile) downloads["vrs"] = download(VRS_URL, vrsFile)
        if (refresh || !apFile.isFile) downloads["airports"] = download(AIRPORTS_URL, apFile)
        val tRead = System.nanoTime()
        val byIcao = readAirports(apFile)
        val routes = readRoutes(vrsFile)
        val readMs = (System.nanoTime() - tRead) / 1_000_000

        val used = LinkedHashMap<String, Int>()
        val airports = ArrayList<Airport>()
        val seqIndex = HashMap<List<Int>, Int>()
        val sequences = ArrayList<IntArray>()
        val byCode = java.util.TreeMap<String, MutableList<Pair<String, Int>>>()
        var dropped = 0
        val missing = HashMap<String, Int>()
        for (r in routes) {
            val codes = r.airports
            if (codes.size < 2 || codes.any { it !in byIcao }) {
                dropped++
                codes.filter { it !in byIcao }.forEach { missing[it] = (missing[it] ?: 0) + 1 }
                continue
            }
            val idx = codes.map { c -> used.getOrPut(c) { airports += byIcao.getValue(c); airports.size - 1 } }
            val s = seqIndex.getOrPut(idx) { sequences += idx.toIntArray(); sequences.size - 1 }
            byCode.getOrPut(r.code) { ArrayList() } += r.number to s
        }
        for (l in byCode.values) l.sortWith(compareBy({ it.first.length }, { it.first }))
        val built = LocalDate.now(ZoneOffset.UTC).toString()
        val comments = listOf(
            "Flights layer route table (T311): call sign to airports flown, for the Dystopo app; internal format (RouteTable.kt)",
            "routes: VRS standing data (github.com/vradarserver/standing-data), CC0-1.0",
            "airports: OurAirports (ourairports.com), public domain",
            "built $built",
        )
        val text = RouteTable.write(airports, sequences, byCode, comments)
        var gz = gzip(text.encodeToByteArray())
        var airportsOnly = false
        if (gz.size > MAX_GZ) {
            airportsOnly = true
            gz = gzip(RouteTable.write(airports, emptyList(), emptyMap(), comments + "airports only: the routes made the file over 5 MB").encodeToByteArray())
        }
        val outFile = File(out)
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(gz)
        val tCheck = System.nanoTime()
        val back = readRouteTable(outFile)
        val parseMs = (System.nanoTime() - tCheck) / 1_000_000
        val samples = listOf("ASA1670", "UAL2271", "ABX404", "ASA1409", "BAW1").associateWith { cs -> back.lookup(cs)?.airports?.map { it.code } }
        printReport(this, linkedMapOf(
            "out" to outFile.path, "gzBytes" to gz.size, "gzMB" to Math.round(gz.size / 10_485.76) / 100.0, "textBytes" to text.length,
            "airportsOnly" to airportsOnly, "routesRead" to routes.size, "routesKept" to back.routeCount, "routesDropped" to dropped,
            "airportCodesMissing" to missing.size, "topMissing" to missing.entries.sortedByDescending { it.value }.take(8).map { "${it.key} (${it.value})" },
            "airports" to airports.size, "sequences" to sequences.size, "airlineCodes" to byCode.size,
            "downloads" to downloads.ifEmpty { null }, "readMs" to readMs, "parseBackMs" to parseMs, "samples" to samples,
            "ms" to (System.nanoTime() - t0) / 1_000_000,
        ))
    }

    private class VrsRoute(val code: String, val number: String, val airports: List<String>)

    /** `routes/schema-01/<letter>/<code>-all.csv` of the zip: Callsign, Code, Number, AirlineCode, AirportCodes. */
    private fun readRoutes(zip: File): List<VrsRoute> {
        val out = ArrayList<VrsRoute>(700_000)
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !e.name.contains("/routes/schema-01/") || !e.name.endsWith(".csv")) continue
                z.getInputStream(e).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (raw in lines) {
                        val line = raw.removePrefix("﻿").trim()
                        if (line.isEmpty() || line.startsWith("Callsign,")) continue
                        val f = line.split(',')
                        if (f.size < 5) continue
                        val code = f[1].trim()
                        val number = f[2].trim()
                        if (code.isEmpty() || number.isEmpty() || f[0].trim() != code + number) continue
                        if (number.any { it == ' ' || it == '=' || it == '\t' }) continue
                        out += VrsRoute(code.uppercase(), number.uppercase(), f[4].trim().split('-').map { it.trim().uppercase() }.filter { it.isNotEmpty() })
                    }
                }
            }
        }
        if (out.isEmpty()) throw UsageError("no routes in $zip (routes/schema-01/*/*.csv)")
        return out
    }

    /** OurAirports' rows by ICAO code (`icao_code`, else `ident`, else `gps_code`). */
    private fun readAirports(csv: File): Map<String, Airport> {
        val rows = parseCsv(csv.readText(Charsets.UTF_8))
        val head = rows.first()
        fun col(n: String) = head.indexOf(n).also { if (it < 0) throw UsageError("$csv has no $n column") }
        val ident = col("ident"); val icao = col("icao_code"); val gps = col("gps_code"); val iata = col("iata_code")
        val name = col("name"); val city = col("municipality"); val lat = col("latitude_deg"); val lon = col("longitude_deg"); val type = col("type")
        val out = HashMap<String, Airport>()
        val rank = HashMap<String, Int>()
        for (r in rows.drop(1)) {
            if (r.size < head.size) continue
            if (r[type] == "closed") continue
            val la = r[lat].toDoubleOrNull() ?: continue
            val lo = r[lon].toDoubleOrNull() ?: continue
            val a = Airport(
                (r[icao].ifEmpty { r[ident] }).uppercase(), r[iata].trim().uppercase().ifEmpty { null }, la, lo, r[name].trim(), r[city].trim().ifEmpty { null },
            )
            for ((key, k) in listOf(r[icao] to 0, r[ident] to 1, r[gps] to 2)) {
                val c = key.trim().uppercase()
                if (c.isEmpty()) continue
                if ((rank[c] ?: 9) > k) { rank[c] = k; out[c] = a.copy(icao = c) }
            }
        }
        return out
    }

    /** RFC 4180 CSV (quotes, doubled quotes, commas and newlines in quotes). */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row += cell.toString(); cell.clear() }
                '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = ArrayList() }
                '\r' -> {}
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    private fun gzip(b: ByteArray): ByteArray {
        val bo = ByteArrayOutputStream()
        object : GZIPOutputStream(bo) { init { def.setLevel(java.util.zip.Deflater.BEST_COMPRESSION) } }.use { it.write(b) }
        return bo.toByteArray()
    }

    private fun download(url: String, to: File): Map<String, Any?> {
        val t = System.nanoTime()
        to.parentFile?.mkdirs()
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 120_000
        c.setRequestProperty("User-Agent", "Dystopo-devtool")
        c.instanceFollowRedirects = true
        if (c.responseCode !in 200..299) throw UsageError("$url: HTTP ${c.responseCode}")
        val tmp = File(to.path + ".part")
        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        if (to.exists()) to.delete()
        tmp.renameTo(to)
        return linkedMapOf("url" to url, "bytes" to to.length(), "ms" to (System.nanoTime() - t) / 1_000_000)
    }

    companion object {
        const val VRS_URL = "https://codeload.github.com/vradarserver/standing-data/zip/refs/heads/main"
        const val AIRPORTS_URL = "https://davidmegginson.github.io/ourairports-data/airports.csv"
        const val MAX_GZ = 5 * 1024 * 1024
    }
}

/**
 * T311: `aircraft` runs the Flights layer's core on the replay fixture (the default) at `--at` seconds and prints each
 * aircraft as the phone's `GET /aircraft` does: position moved up to now, staleness, altitude (EGM2008 from the
 * geometric altitude and `--undulation`, else EGM2008's own undulation at `--from`, which only the devtool reads; the
 * phone measures it from its fix), bearing, distance and elevation angle from `--from` (Paradise by default) and the
 * route checked against the shipped table. `--live` polls adsb.lol once around `--from` and prints only the request's
 * path, status, time and count (feed data is not printed or kept: ODbL, the finding).
 */
class AircraftCommand : CliktCommand(name = "aircraft") {
    override fun help(context: Context) =
        "The Flights layer on the replay fixture (or --live: one poll's path, time and count); prints a JSON report."

    private val replay by option("--replay", help = "the replay file").default(REPLAY_FIXTURE)
    private val at by option("--at", help = "seconds into the replay").double().default(60.0)
    private val from by option("--from", help = "the observer, lat,lon").default("46.786,-121.735")
    private val height by option("--height", help = "the observer's height above EGM2008, metres").double().default(1650.0)
    private val undulation by option("--undulation", help = "the geoid undulation at the observer, metres (default: EGM2008's)").double()
    private val barometric by option("--barometric", help = "as with no fix: pressure altitudes, marked").flag()
    private val routes by option("--routes", help = "the route table").default(ROUTE_ASSET)
    private val naming by option("--naming", help = "both, codes or cities").default("both")
    private val live by option("--live", help = "poll adsb.lol once around --from").flag()
    private val radius by option("--radius", help = "nautical miles for --live").int().default(50)
    private val polls by option("--polls", help = "saved readsb answers (files or a folder of *.json): route lookups and plausibility before and after round 3, counts only")
    private val airline by option("--airline", help = "with --polls: also the counts for call signs starting so (e.g. UAL)")

    override fun run() {
        val t0 = System.nanoTime()
        val p = from.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        if (p.size != 2) throw UsageError("--from takes lat,lon")
        val (lat, lon) = p
        if (live) return printReport(this, livePoll(lat, lon) + ("ms" to (System.nanoTime() - t0) / 1_000_000))
        polls?.let { return printReport(this, pollsReport(it) + ("ms" to (System.nanoTime() - t0) / 1_000_000)) }
        val names = AirportNaming.of(naming) ?: throw UsageError("--naming takes both, codes or cities")
        val tTable = System.nanoTime()
        val table = File(routes).takeIf { it.isFile }?.let { readRouteTable(it) }
        val tableMs = (System.nanoTime() - tTable) / 1_000_000
        val und = if (barometric) null else undulation ?: runCatching { Geoids.egm2008.undulation(lat, lon) }.getOrNull()
        val src = ReplaySource(ReplaySource.Replay.parse(File(replay).readText()), 0L)
        val tracker = AircraftTracker()
        // Polls every 20 s up to --at, as the phone would, so trails and the kept track are built.
        var t = 0.0
        while (t <= at) {
            val ms = (t * 1000).toLong()
            tracker.onFetch(src.poll(AircraftQuery(lat, lon, 50), ms), ms, und)
            t += 20.0
        }
        val now = (at * 1000).toLong()
        val view = tracker.view(now, Observer(lat, lon, height, und), table, names).sortedBy { it.distanceM ?: 0.0 }
        printReport(this, linkedMapOf(
            "replay" to replay, "atS" to at, "from" to listOf(lat, lon), "heightM" to height,
            "undulationM" to und?.let { Math.round(it * 100) / 100.0 },
            "undulationSource" to when { barometric -> "none (--barometric)"; undulation != null -> "--undulation"; und != null -> "egm2008 (devtool only; the phone measures it from its fix)"; else -> "none" },
            "routeTable" to table?.json(), "routeTableMs" to tableMs, "state" to tracker.state.json, "polls" to tracker.polls,
            "count" to view.size, "stale" to view.count { it.stale }, "plausibleRoutes" to view.count { it.route?.plausible == true },
            "aircraft" to view.map { AircraftJson.now(it) + ("history" to tracker.history(it.hex).size) },
            "ms" to (System.nanoTime() - t0) / 1_000_000,
        ))
    }

    /**
     * Round 3: every aircraft with a call sign in the saved answers (deduplicated by call sign, the last seen kept), looked
     * up and checked as the phone does now (lookup variants, the terminal exemption) and as it did before (neither):
     * found, plausible, and the rejections by reason. Counts only; the answers are not printed.
     */
    private fun pollsReport(spec: String): Map<String, Any?> {
        val table = readRouteTable(File(routes))
        val files = spec.split(',').map { File(it.trim()) }.flatMap { f -> if (f.isDirectory) f.listFiles { x -> x.name.endsWith(".json") }!!.sorted() else listOf(f) }
        val byCs = LinkedHashMap<String, io.github.jasondorjeshort.dystopo.aircraft.AircraftReport>()
        for (f in files) for (a in io.github.jasondorjeshort.dystopo.aircraft.ReadsbParser.parse(f.readText(), 0).aircraft) a.callSign?.let { byCs[it] = a }
        fun tally(list: Collection<io.github.jasondorjeshort.dystopo.aircraft.AircraftReport>, after: Boolean): Map<String, Any?> {
            var found = 0; var plausible = 0
            val reasons = java.util.TreeMap<String, Int>()
            for (a in list) {
                val r = table.lookup(a.callSign!!, normalise = after) ?: continue
                found++
                val m = io.github.jasondorjeshort.dystopo.aircraft.RoutePlausibility.check(r, a.lat, a.lon, a.trackDeg, a.groundSpeedKt, a.onGround, a.verticalRateFpm, terminal = after)
                if (m.plausible) plausible++ else {
                    val k = when { "heading away" in m.reason -> "heading"; "off the leg" in m.reason -> "offLeg"; "beyond its ends" in m.reason -> "beyondEnds"; "ground" in m.reason -> "ground"; else -> "other" }
                    reasons[k] = (reasons[k] ?: 0) + 1
                }
            }
            return linkedMapOf("withCallSign" to list.size, "found" to found, "plausible" to plausible, "rejected" to reasons)
        }
        val sel = airline?.uppercase()?.let { p -> byCs.values.filter { it.callSign!!.startsWith(p) } }
        return linkedMapOf(
            "files" to files.size, "aircraft" to byCs.size, "routeTable" to table.json(),
            "before" to tally(byCs.values, false), "after" to tally(byCs.values, true),
            "airline" to airline, "airlineBefore" to sel?.let { tally(it, false) }, "airlineAfter" to sel?.let { tally(it, true) },
            "kept" to "nothing: counts only",
        )
    }

    private fun livePoll(lat: Double, lon: Double): Map<String, Any?> {
        val q = AircraftQuery(AircraftQuery.round(lat), AircraftQuery.round(lon), radius.coerceIn(1, 250))
        var status: Int? = null
        val http = AircraftHttp { url, headers ->
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 20_000
            for ((k, v) in headers) c.setRequestProperty(k, v)
            try {
                c.connect()
            } catch (e: IOException) {
                throw AircraftNoConnection("${e.javaClass.simpleName}: ${e.message}")
            }
            status = c.responseCode
            val body = if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else ByteArray(0)
            AircraftHttpReply(c.responseCode, body, c.getHeaderField("Retry-After"))
        }
        val src = AdsbLolSource(http, "devtool", { System.currentTimeMillis() })
        val f = src.poll(q, System.currentTimeMillis())
        return linkedMapOf(
            "query" to q.json(), "url" to src.base + q.path, "status" to status,
            "outcome" to f::class.simpleName,
            "responseMs" to (f as? AircraftFetch.Ok)?.ms,
            "count" to (f as? AircraftFetch.Ok)?.poll?.aircraft?.size,
            "withCallSign" to (f as? AircraftFetch.Ok)?.poll?.aircraft?.count { it.callSign != null },
            "withGeometricAltitude" to (f as? AircraftFetch.Ok)?.poll?.aircraft?.count { it.altGeomFt != null },
            "bytes" to (f as? AircraftFetch.Ok)?.poll?.bytes,
            "error" to when (f) { is AircraftFetch.Failed -> f.reason; is AircraftFetch.Offline -> f.reason; is AircraftFetch.Limited -> "429, retry after ${f.retryAfterS} s"; else -> null },
            "kept" to "nothing: counts only (ODbL feed data stays in memory)",
        )
    }

    companion object {
        const val REPLAY_FIXTURE = "shared/src/commonTest/resources/aircraft/rainier-replay.json"
    }
}
