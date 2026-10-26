package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.Polyline
import io.github.jasondorjeshort.dystopo.boundaries.Countries
import io.github.jasondorjeshort.dystopo.boundaries.Country
import io.github.jasondorjeshort.dystopo.boundaries.RingCodec
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.server.Caches
import io.github.jasondorjeshort.dystopo.server.reportJson
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.DriverManager
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The bundled country set (T141): `--build` writes `shared/src/commonMain/resources/boundaries/countries.txt` from
 * Natural Earth's 1:50m admin-0 countries (public domain, the `natural_earth_vector.sqlite.zip` that Planetiler
 * downloads into `cache/planetiler/`, opened with the same JDBC driver as `devtool borders`); without it the
 * command lists the bundled set, `--find` looks a name or code up and `--tiles <code>` sizes a whole country.
 *
 * Which rows are kept (the coordinator's ruling of 2026-10-25 on the 1:50m data, where `TYPE` Disputed and
 * Indeterminate hold whole de facto units, so hiding them would itself take a position):
 * - kept: every row whose `TYPE` is Sovereign country, Sovereignty (Natural Earth's spelling for a state that has
 *   a lease at 1:10m), Country, Dependency, Disputed or Indeterminate; Antarctica is one of the Indeterminate rows;
 * - dropped: Lease rows and any other `TYPE` ([KEPT_TYPES]); Siachen Glacier (`KAS`, a glacier, not a place to
 *   download; [DROPPED_CODES]); and the size floor, uninhabited rows (`POP_EST` 0) under [FLOOR_KM2] km2, which
 *   at 1:50m is Ashmore and Cartier Is. alone. The build's report lists every dropped row by name.
 *
 * Each ring: Natural Earth's own order read from the WKB (the first ring of each polygon outer, the rest holes),
 * each polygon of a multipolygon its own outer ring; a ring crossing the antimeridian split at 180 (none at
 * 1:50m) and a polar edge from 180 to -180 densified so no two consecutive longitudes are more than 180 apart;
 * a country over [budget] vertices simplified with `Polyline.simplify` at the smallest tolerance that meets it,
 * the tolerance stored being the measured largest distance of a source vertex from the stored rings; then
 * quantized to 1e-5 degrees, outer rings counter-clockwise and holes clockwise. Sovereignty is never written:
 * the fields are the codes, the two names, the continent, the box, the tolerance and the rings.
 */
class CountriesCommand : CliktCommand(name = "countries") {
    override fun help(context: Context) =
        "The bundled country set: list it, --find a name or code, --tiles <code> to size a whole country, or --build it from Natural Earth; prints a JSON report."

    private val build by option("--build", help = "write the resource from Natural Earth (1:50m admin-0 countries)").flag()
    private val naturalEarth by option("--natural-earth", help = "natural_earth_vector.sqlite.zip (or the extracted .sqlite)")
        .default(File(Caches.root, "planetiler/natural_earth_vector.sqlite.zip").path)
    private val out by option("--out", help = "the resource to write").default(RESOURCE)
    private val budget by option("--budget", help = "vertices per country before simplification (ADR 0041's writer budget)").int().default(BUDGET)
    private val floorKm2 by option("--floor-km2", help = "uninhabited rows smaller than this are left out").double().default(FLOOR_KM2)
    private val find by option("--find", help = "look a name or code up (prefix or substring, any case or accents)")
    private val tiles by option("--tiles", help = "a country code: tile counts and bytes over --zooms")
    private val zooms by option("--zooms", help = "zoom ranges for --tiles, comma-separated (DEM over the range, vector tiles up to z14)").default("8-13,8-15")
    private val margin by option("--margin", help = "tiles of margin around the cover, as area manifests use").int().default(1)

    override fun run() {
        val started = System.nanoTime()
        val report: Map<String, Any?> = when {
            build -> build()
            find != null -> findReport(find!!)
            tiles != null -> tilesReport(tiles!!)
            else -> listReport()
        } + ("ms" to (System.nanoTime() - started) / 1_000_000)
        echo(reportJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), reportJson.parseToJsonElement(JsonWriter.write(report))))
    }

    private fun listReport(): Map<String, Any?> {
        val all = Countries.all
        val file = File(RESOURCE)
        return linkedMapOf(
            "countries" to all.size,
            "vertices" to all.sumOf { it.vertexCount },
            "rings" to all.sumOf { it.rings.size },
            "resourceBytes" to (if (file.isFile) file.length() else null),
            "list" to all.map { c ->
                linkedMapOf(
                    "code" to c.code, "name" to c.name, "continent" to c.continent, "areaKm2" to round1(areaKm2(c.rings)),
                    "vertices" to c.vertexCount, "rings" to c.rings.size, "toleranceM" to c.toleranceM,
                )
            },
        )
    }

    private fun findReport(text: String): Map<String, Any?> {
        val found = Countries.find(text)
        return linkedMapOf(
            "find" to text,
            "matches" to found.size,
            "countries" to found.map { c ->
                val parts = c.rings.filter { signedArea(it) > 0 }
                linkedMapOf(
                    "code" to c.code, "iso3" to c.iso3, "name" to c.name, "nameLong" to c.nameLong, "continent" to c.continent,
                    "bounds" to listOf(c.west, c.south, c.east, c.north), "crossesAntimeridian" to c.crossesAntimeridian,
                    "areaKm2" to round1(areaKm2(c.rings)), "vertices" to c.vertexCount, "rings" to c.rings.size,
                    "parts" to parts.size, "holes" to c.rings.size - parts.size, "toleranceM" to c.toleranceM,
                    "maxLonStep" to c.rings.maxOf { maxLonStep(it) },
                    "ringCrossing180" to c.rings.count { maxLonStep(it) > 180 },
                    "partBounds" to parts.map { r -> listOf(r.minOf { it.lon }, r.minOf { it.lat }, r.maxOf { it.lon }, r.maxOf { it.lat }) },
                )
            },
        )
    }

    private fun tilesReport(code: String): Map<String, Any?> {
        val c = Countries.byCode(code) ?: throw UsageError("no country $code in the bundled set")
        val ranges = zooms.split(',').map { r ->
            val p = r.trim().split('-').map { it.trim().toIntOrNull() ?: throw UsageError("--zooms takes ranges like 8-13") }
            if (p.size != 2 || p[0] > p[1] || p[0] < 0 || p[1] > 18) throw UsageError("--zooms takes ranges like 8-13")
            p[0]..p[1]
        }
        val perZoom = HashMap<Int, Long>()
        fun count(z: Int) = perZoom.getOrPut(z) { Cover.count(c.rings, c.toleranceM, z, margin) }
        return linkedMapOf(
            "code" to c.code, "name" to c.name, "areaKm2" to round1(areaKm2(c.rings)), "vertices" to c.vertexCount,
            "bufferM" to c.toleranceM, "margin" to margin,
            "cover" to "ADR 0041 §1: a tile is in when its square is within bufferM of a ring edge or its centre is inside by even-odd; DEM tiles over the range, vector tiles up to z${AreaManifest.OSM_MAX_ZOOM}; bytes from AreaManifest.estimate",
            "ranges" to ranges.map { range ->
                val dem = range.sumOf { count(it) }
                val osmZooms = range.first..min(range.last, AreaManifest.OSM_MAX_ZOOM)
                val osm = osmZooms.sumOf { count(it) }
                val bytes = range.sumOf { count(it) * AreaManifest.estimate(AreaManifest.ROUTE_DEM, it) } +
                    osmZooms.sumOf { count(it) * AreaManifest.estimate(AreaManifest.ROUTE_OSM, it) }
                linkedMapOf(
                    "zooms" to "${range.first}-${range.last}", "tiles" to dem + osm, "demTiles" to dem, "osmTiles" to osm,
                    "bytes" to bytes, "mb" to round1(bytes / 1e6), "tilesPerZoom" to range.associate { it.toString() to count(it) },
                )
            },
        )
    }

    private fun build(): Map<String, Any?> {
        val source = File(naturalEarth)
        if (!source.isFile) throw UsageError("no $source (any tilebuild run downloads it; or pass --natural-earth)")
        var tmp: File? = null
        var version: String? = null
        val db = if (source.name.endsWith(".zip")) {
            tmp = File.createTempFile("natural_earth", ".sqlite")
            ZipFile(source).use { z ->
                version = z.entries().asSequence().firstOrNull { it.name.endsWith("/VERSION") || it.name == "VERSION" }
                    ?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8).trim() } }
                val entry = z.entries().asSequence().firstOrNull { it.name.endsWith(".sqlite") } ?: throw UsageError("no .sqlite in $source")
                z.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            tmp
        } else source
        val rows = ArrayList<Row>()
        try {
            DriverManager.getConnection("jdbc:sqlite:" + db.absolutePath.replace('\\', '/')).use { c ->
                c.createStatement().use { st ->
                    val rs = st.executeQuery("SELECT adm0_a3, iso_a3_eh, name, name_long, continent, type, pop_est, GEOMETRY FROM $TABLE")
                    while (rs.next()) {
                        rows += Row(
                            code = rs.getString(1), iso3 = rs.getString(2)?.takeIf { it.length == 3 && it != "-99" },
                            name = rs.getString(3), nameLong = rs.getString(4), continent = rs.getString(5), type = rs.getString(6),
                            pop = rs.getDouble(7), polygons = wkbPolygons(rs.getBytes(8)),
                        )
                    }
                }
            }
        } finally {
            tmp?.delete()
        }
        val dropped = ArrayList<Map<String, Any?>>()
        val built = ArrayList<Built>()
        var verticesIn = 0
        var splits = 0
        var densified = 0
        for (row in rows) {
            val rawRings = row.polygons.flatMap { p -> p.mapIndexed { i, r -> RawRing(r, outer = i == 0) } }
            val area = rawRings.sumOf { (if (it.outer) 1 else -1) * abs(sphericalAreaKm2(it.points)) }
            val reason = when {
                row.type !in KEPT_TYPES -> "TYPE ${row.type}"
                row.code in DROPPED_CODES -> DROPPED_CODES.getValue(row.code)
                row.pop <= 0 && area < floorKm2 -> "uninhabited and under $floorKm2 km2"
                else -> null
            }
            if (reason != null) {
                dropped += linkedMapOf("code" to row.code, "name" to row.name, "type" to row.type, "areaKm2" to round1(area), "reason" to reason)
                continue
            }
            verticesIn += rawRings.sumOf { it.points.size - 1 }
            val rings = ArrayList<RawRing>()
            for (r in rawRings) {
                val d = densifyPolarEdges(r.points)
                if (d.size != r.points.size) densified++
                val pieces = splitAtAntimeridian(d)
                if (pieces.size > 1 || pieces[0] !== d) splits++
                pieces.forEach { rings += RawRing(it, r.outer) }
            }
            built += finish(row, rings, budget)
        }
        built.sortWith(compareBy({ io.github.jasondorjeshort.dystopo.boundaries.TextFold.fold(it.row.name) }, { it.row.code }))
        val text = StringBuilder()
        text.append("# Dystopo's bundled country set (T141), written by `devtool countries --build`; do not edit.\n")
        text.append("# Source: Natural Earth 1:50m Cultural Vectors, Admin 0 - Countries").append(version?.let { " v$it" } ?: "")
            .append(" (public domain; naturalearthdata.com), from the natural_earth_vector.sqlite that Planetiler uses.\n")
        text.append("# Fields, tab-separated: code (ADM0_A3), iso3 (ISO_A3_EH), name, nameLong, continent, west,south,east,north, toleranceM, rings (space-separated, RingCodec text).\n")
        for (b in built) {
            val r = b.row
            listOf(r.code, r.iso3.orEmpty(), r.name, r.nameLong, r.continent).forEach { require('\t' !in it && '\n' !in it) { "a field holds a tab or newline: $it" } }
            text.append(r.code).append('\t').append(r.iso3.orEmpty()).append('\t').append(r.name).append('\t').append(r.nameLong).append('\t')
                .append(r.continent).append('\t').append(b.bounds.joinToString(",") { num(it) }).append('\t').append(num(b.toleranceM)).append('\t')
                .append(b.rings.joinToString(" ") { RingCodec.encode(it) }).append('\n')
        }
        val file = File(out)
        file.parentFile?.mkdirs()
        file.writeText(text.toString(), Charsets.UTF_8)
        val parsed = Countries.parse(file.readText(Charsets.UTF_8))
        val problems = validate(parsed)
        val simplified = built.filter { it.toleranceM > 0 }
        return linkedMapOf(
            "out" to file.path,
            "naturalEarthVersion" to version,
            "table" to TABLE,
            "bytes" to file.length(),
            "rowsIn" to rows.size,
            "countries" to parsed.size,
            "byType" to built.groupingBy { it.row.type }.eachCount(),
            "dropped" to dropped,
            "verticesIn" to verticesIn,
            "verticesOut" to parsed.sumOf { it.vertexCount },
            "rings" to parsed.sumOf { it.rings.size },
            "budget" to budget,
            "ringsSplitAt180" to splits,
            "ringsDensifiedAtPole" to densified,
            "ringsDroppedDegenerate" to built.sumOf { it.droppedRings },
            "simplified" to simplified.map {
                linkedMapOf("code" to it.row.code, "name" to it.row.name, "verticesIn" to it.verticesIn, "verticesOut" to it.rings.sumOf { r -> r.size - 1 },
                    "parameterM" to num(it.parameterM), "toleranceM" to it.toleranceM)
            },
            "largest" to parsed.sortedByDescending { it.vertexCount }.take(5).map { "${it.code} ${it.vertexCount}" },
            "problems" to problems,
        )
    }

    data class Row(
        val code: String, val iso3: String?, val name: String, val nameLong: String, val continent: String, val type: String,
        val pop: Double, val polygons: List<List<List<AreaPoint>>>,
    )

    data class RawRing(val points: List<AreaPoint>, val outer: Boolean)

    class Built(val row: Row, val rings: List<List<AreaPoint>>, val bounds: List<Double>, val toleranceM: Double, val parameterM: Double, val verticesIn: Int, val droppedRings: Int)

    companion object {
        const val RESOURCE = "shared/src/commonMain/resources/boundaries/countries.txt"
        const val TABLE = "ne_50m_admin_0_countries"
        const val BUDGET = 2000
        const val FLOOR_KM2 = 10.0
        val KEPT_TYPES = setOf("Sovereign country", "Sovereignty", "Country", "Dependency", "Disputed", "Indeterminate")
        val DROPPED_CODES = mapOf("KAS" to "a glacier, not a place to download")
        private const val R_KM = 6371.0088

        /** The polygons of a WKB Polygon or MultiPolygon (either byte order): each a list of closed rings, the first outer. */
        fun wkbPolygons(wkb: ByteArray): List<List<List<AreaPoint>>> {
            val buf = ByteBuffer.wrap(wkb)
            fun polygon(): List<List<AreaPoint>> {
                buf.order(if (buf.get().toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                val type = buf.int
                require(type == 3) { "expected a WKB Polygon, got type $type" }
                return List(buf.int) {
                    val n = buf.int
                    List(n) { val lon = buf.double; val lat = buf.double; AreaPoint(lat, lon) }
                }
            }
            buf.order(if (buf.get(0).toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            return when (val type = buf.getInt(1)) {
                3 -> listOf(polygon())
                6 -> {
                    buf.position(5)
                    List(buf.int) { polygon() }
                }
                else -> error("expected a WKB Polygon or MultiPolygon, got type $type")
            }
        }

        /** An edge from one side of the antimeridian to the other along a parallel at +-180 (Antarctica's polar edge) gets a vertex every 90 degrees. */
        fun densifyPolarEdges(ring: List<AreaPoint>): List<AreaPoint> {
            val out = ArrayList<AreaPoint>(ring.size)
            for (i in ring.indices) {
                if (i > 0) {
                    val a = ring[i - 1]
                    val b = ring[i]
                    if (abs(a.lon) == 180.0 && b.lon == -a.lon && a.lat == b.lat) {
                        val step = if (b.lon > a.lon) 90.0 else -90.0
                        var lon = a.lon + step
                        while (abs(lon) < 180.0) { out += AreaPoint(a.lat, lon); lon += step }
                    }
                }
                out += ring[i]
            }
            return out
        }

        /** Vertices added on every edge longer than [maxStep] degrees of longitude, on the edge itself (the shape is unchanged). */
        fun densifyLongEdges(ring: List<AreaPoint>, maxStep: Double = 90.0): List<AreaPoint> {
            if (maxLonStep(ring) <= maxStep) return ring
            val out = ArrayList<AreaPoint>(ring.size + 8)
            for (i in ring.indices) {
                if (i > 0) {
                    val a = ring[i - 1]
                    val b = ring[i]
                    val k = ceil(abs(b.lon - a.lon) / maxStep).toInt()
                    for (j in 1 until k) out += AreaPoint(a.lat + (b.lat - a.lat) * j / k, a.lon + (b.lon - a.lon) * j / k)
                }
                out += ring[i]
            }
            return out
        }

        /**
         * [ring] itself when no edge jumps more than 180 degrees of longitude; else the ring unwrapped and cut along
         * the antimeridian into closed pieces, each shifted back into -180..180.
         */
        fun splitAtAntimeridian(ring: List<AreaPoint>): List<List<AreaPoint>> {
            if (maxLonStep(ring) <= 180.0) return listOf(ring)
            val un = ArrayList<AreaPoint>(ring.size)
            var shift = 0.0
            for (i in ring.indices) {
                if (i > 0) {
                    val d = ring[i].lon + shift - un[i - 1].lon
                    if (d > 180) shift -= 360 else if (d < -180) shift += 360
                }
                un += AreaPoint(ring[i].lat, ring[i].lon + shift)
            }
            val lo = floor((un.minOf { it.lon } + 180) / 360).toInt()
            val hi = floor((un.maxOf { it.lon } + 180) / 360 - 1e-12).toInt()
            val out = ArrayList<List<AreaPoint>>()
            for (k in lo..hi) {
                val west = -180.0 + 360 * k
                val piece = clipX(clipX(un, west, keepAbove = true), west + 360, keepAbove = false)
                if (piece.size >= 4) out += piece.map { AreaPoint(it.lat, (it.lon - 360 * k).coerceIn(-180.0, 180.0)) }
            }
            return out
        }

        /** Sutherland-Hodgman against the line lon = [x]; the result is closed. */
        private fun clipX(ring: List<AreaPoint>, x: Double, keepAbove: Boolean): List<AreaPoint> {
            fun inside(p: AreaPoint) = if (keepAbove) p.lon >= x else p.lon <= x
            val out = ArrayList<AreaPoint>()
            val open = if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring
            for (i in open.indices) {
                val a = open[i]
                val b = open[(i + 1) % open.size]
                if (inside(a)) out += a
                if (inside(a) != inside(b)) {
                    val t = (x - a.lon) / (b.lon - a.lon)
                    out += AreaPoint(a.lat + t * (b.lat - a.lat), x)
                }
            }
            if (out.isNotEmpty()) out += out[0]
            return out
        }

        fun maxLonStep(ring: List<AreaPoint>): Double = (1 until ring.size).maxOfOrNull { abs(ring[it].lon - ring[it - 1].lon) } ?: 0.0

        /** Shoelace with longitude as x and latitude as y: positive for a counter-clockwise ring. */
        fun signedArea(ring: List<AreaPoint>): Double {
            var s = 0.0
            for (i in 1 until ring.size) s += ring[i - 1].lon * ring[i].lat - ring[i].lon * ring[i - 1].lat
            return s / 2
        }

        /** Area on the sphere in km2, positive for a counter-clockwise ring. */
        fun sphericalAreaKm2(ring: List<AreaPoint>): Double {
            var s = 0.0
            for (i in 1 until ring.size) {
                val a = ring[i - 1]
                val b = ring[i]
                s += (b.lon - a.lon) * PI / 180 * (2 + sin(a.lat * PI / 180) + sin(b.lat * PI / 180))
            }
            return -s * R_KM * R_KM / 2
        }

        /** Even-odd area in km2: outer rings counter-clockwise add, holes clockwise subtract. */
        fun areaKm2(rings: List<List<AreaPoint>>): Double = rings.sumOf { sphericalAreaKm2(it) }

        /** Douglas-Peucker on a closed ring that keeps at least a triangle (a small island stays an island, within the tolerance measured afterwards). */
        fun simplifyRing(ring: List<AreaPoint>, toleranceM: Double): List<AreaPoint> {
            val s = Polyline.simplify(ring, toleranceM)
            if (s.size >= 4 || ring.size < 4) return s
            val frame = Frame(ring)
            val n = ring.size - 1
            val ib = (1 until n).maxBy { frame.dist(ring[0], ring[it]) }
            val ic = (1 until n).filter { it != ib }.maxBy { frame.segDist(ring[it], ring[0], ring[ib]) }
            return listOf(0, min(ib, ic), max(ib, ic)).map { ring[it] } + ring[0]
        }

        /** The largest distance in metres of a vertex of [ring] from the edge of [kept] (a subsequence of it, by identity) that replaced it. */
        fun deviationM(ring: List<AreaPoint>, kept: List<AreaPoint>): Double {
            var worst = 0.0
            var k = 0
            for (i in ring.indices) {
                if (k < kept.size && ring[i] === kept[k]) { k++; continue }
                val a = kept[(k - 1).coerceAtLeast(0)]
                val b = kept[k.coerceAtMost(kept.size - 1)]
                worst = max(worst, Frame.at(ring[i].lat).segDist(ring[i], a, b))
            }
            return worst
        }

        /** A local metric frame: metres east and north per degree at one latitude. */
        class Frame(val kx: Double, val ky: Double = AreaOfInterest.M_PER_DEG) {
            constructor(ring: List<AreaPoint>) : this(AreaOfInterest.M_PER_DEG * cos(ring.sumOf { it.lat } / ring.size * PI / 180))
            fun dist(a: AreaPoint, b: AreaPoint) = kotlin.math.hypot((a.lon - b.lon) * kx, (a.lat - b.lat) * ky)
            fun segDist(p: AreaPoint, a: AreaPoint, b: AreaPoint) = Polyline.segmentDistance(p.lon * kx, p.lat * ky, a.lon * kx, a.lat * ky, b.lon * kx, b.lat * ky)
            companion object {
                fun at(lat: Double) = Frame(AreaOfInterest.M_PER_DEG * cos(lat.coerceIn(-89.9, 89.9) * PI / 180))
            }
        }

        private fun simplifyAll(rings: List<RawRing>, t: Double) = rings.map { RawRing(simplifyRing(it.points, t), it.outer) }

        /** Simplifies a country to [budget] when over it, quantizes, orients and boxes it. */
        fun finish(row: Row, rings: List<RawRing>, budget: Int): Built {
            val verticesIn = rings.sumOf { it.points.size - 1 }
            var chosen = rings
            var parameter = 0.0
            var tolerance = 0.0
            if (verticesIn > budget) {
                var lo = 1.0
                var hi = 1.0
                while (hi < 1e6 && simplifyAll(rings, hi).sumOf { it.points.size - 1 } > budget) { lo = hi; hi *= 2 }
                repeat(24) {
                    val mid = (lo + hi) / 2
                    if (simplifyAll(rings, mid).sumOf { it.points.size - 1 } > budget) lo = mid else hi = mid
                }
                parameter = hi
                chosen = simplifyAll(rings, hi)
                val worst = rings.indices.maxOf { deviationM(rings[it].points, chosen[it].points) }
                // The stored rings are also rounded to 1e-5 degrees (at most about 0.8 m).
                tolerance = ceil(worst + 1.0)
            }
            var dropped = 0
            val out = ArrayList<List<AreaPoint>>()
            for (r in chosen) {
                val q = ArrayList<AreaPoint>()
                // Simplification can drop the vertices along a polar edge (Antarctica's -90 parallel) and leave one
                // edge from 180 to -180: points on the same straight edge keep every longitude step under 180.
                for (p in densifyLongEdges(r.points)) {
                    val v = AreaPoint(RingCodec.quantize(p.lat) / RingCodec.SCALE, RingCodec.quantize(p.lon) / RingCodec.SCALE)
                    if (q.isEmpty() || q.last() != v) q += v
                }
                if (q.first() != q.last()) q += q.first()
                val a = signedArea(q)
                if (q.size < 4 || a == 0.0) { dropped++; continue }
                out += if ((a > 0) == r.outer) q else q.asReversed().toList()
            }
            return Built(row, out, bounds(out), tolerance, parameter, verticesIn, dropped)
        }

        /** west, south, east, north; west greater than east when the smallest box runs across the antimeridian (RFC 7946 §5.2). */
        fun bounds(rings: List<List<AreaPoint>>): List<Double> {
            val spans = rings.map { r -> r.minOf { it.lon } to r.maxOf { it.lon } }.sortedBy { it.first }
            val merged = ArrayList<Pair<Double, Double>>()
            for (s in spans) {
                if (merged.isNotEmpty() && s.first <= merged.last().second) merged[merged.size - 1] = merged.last().first to max(merged.last().second, s.second)
                else merged += s
            }
            var west = merged.first().first
            var east = merged.last().second
            var gap = merged.first().first + 360 - merged.last().second
            for (i in 0 until merged.size - 1) {
                val g = merged[i + 1].first - merged[i].second
                if (g > gap) { gap = g; west = merged[i + 1].first; east = merged[i].second }
            }
            return listOf(west, rings.minOf { r -> r.minOf { it.lat } }, east, rings.maxOf { r -> r.maxOf { it.lat } })
        }

        /** What `check` also asserts on the bundled set; empty when every rule holds. */
        fun validate(countries: List<Country>): List<String> {
            val out = ArrayList<String>()
            for (c in countries) {
                if (c.rings.isEmpty()) out += "${c.code}: no rings"
                for ((i, r) in c.rings.withIndex()) {
                    if (r.size < 4 || r.first() != r.last()) out += "${c.code} ring $i: not closed"
                    if (r.any { abs(it.lat) > 90 || abs(it.lon) > 180 }) out += "${c.code} ring $i: outside the range"
                    if (maxLonStep(r) > 180) out += "${c.code} ring $i: crosses the antimeridian"
                    if (signedArea(r) == 0.0) out += "${c.code} ring $i: no area"
                }
            }
            return out
        }

        private fun num(v: Double): String = String.format(Locale.ROOT, "%.5f", v).trimEnd('0').trimEnd('.')
        private fun round1(v: Double): Double = Math.round(v * 10) / 10.0
    }

    /**
     * ADR 0041 §1's cover of a polygon, counted by tile rows: a tile is in when its square is within the buffer of a
     * ring edge (the corridor rule, with the edge's poleward latitude) or its centre is inside by even-odd; then
     * grown by the margin. A devtool copy for sizing whole countries until `AreaShape.Polygon` (T140) is on main.
     */
    object Cover {
        fun count(rings: List<List<AreaPoint>>, bufferM: Double, z: Int, margin: Int): Long {
            val n = 1 shl z
            val spans = arrayOfNulls<MutableList<IntArray>>(n)
            val crossings = arrayOfNulls<MutableList<Double>>(n)
            fun add(y: Int, x0: Int, x1: Int) {
                if (y !in 0 until n) return
                val a = x0.coerceIn(0, n - 1)
                val b = x1.coerceIn(0, n - 1)
                if (a <= b) (spans[y] ?: ArrayList<IntArray>().also { spans[y] = it }) += intArrayOf(a, b)
            }
            for (ring in rings) {
                for (i in 1 until ring.size) {
                    val p = ring[i - 1]
                    val q = ring[i]
                    val ax = WebMercator.lonToX(p.lon, z); val ay = WebMercator.latToY(p.lat, z)
                    val bx = WebMercator.lonToX(q.lon, z); val by = WebMercator.latToY(q.lat, z)
                    val lat = max(abs(p.lat), abs(q.lat)).coerceAtMost(AreaOfInterest.MAX_LAT)
                    val r = bufferM / (WebMercator.EARTH_CIRCUMFERENCE_M * cos(lat * PI / 180) / n)
                    // Edge tiles: per row band [ty - r, ty + 1 + r], the edge's x extent there, grown by r.
                    val y0 = floor(min(ay, by) - r).toInt()
                    val y1 = floor(max(ay, by) + r).toInt()
                    for (ty in max(y0, 0)..min(y1, n - 1)) {
                        val lo = ty - r
                        val hi = ty + 1 + r
                        val xs = if (ay == by) doubleArrayOf(ax, bx) else {
                            val t0 = ((lo - ay) / (by - ay)).coerceIn(0.0, 1.0)
                            val t1 = ((hi - ay) / (by - ay)).coerceIn(0.0, 1.0)
                            doubleArrayOf(ax + t0 * (bx - ax), ax + t1 * (bx - ax))
                        }
                        add(ty, floor(min(xs[0], xs[1]) - r).toInt(), floor(max(xs[0], xs[1]) + r).toInt())
                    }
                    // Interior: where the edge crosses each row's centre line.
                    if (ay != by) {
                        val first = ceil(min(ay, by) - 0.5).toInt()
                        val last = ceil(max(ay, by) - 0.5).toInt() - 1
                        for (ty in max(first, 0)..min(last, n - 1)) {
                            val yc = ty + 0.5
                            (crossings[ty] ?: ArrayList<Double>().also { crossings[ty] = it }) += ax + (yc - ay) / (by - ay) * (bx - ax)
                        }
                    }
                }
            }
            for (ty in 0 until n) {
                val xs = crossings[ty] ?: continue
                xs.sort()
                var i = 0
                while (i + 1 < xs.size) {
                    add(ty, ceil(xs[i] - 0.5).toInt(), floor(xs[i + 1] - 0.5).toInt())
                    i += 2
                }
            }
            fun merged(list: List<IntArray>): List<IntArray> {
                val s = list.sortedBy { it[0] }
                val out = ArrayList<IntArray>()
                for (a in s) {
                    if (out.isNotEmpty() && a[0] <= out.last()[1] + 1) out.last()[1] = max(out.last()[1], a[1]) else out += a.copyOf()
                }
                return out
            }
            var total = 0L
            for (ty in 0 until n) {
                val near = ArrayList<IntArray>()
                for (dy in -margin..margin) spans.getOrNull(ty + dy)?.let { l -> l.forEach { near += intArrayOf((it[0] - margin).coerceAtLeast(0), (it[1] + margin).coerceAtMost(n - 1)) } }
                if (near.isEmpty()) continue
                for (s in merged(near)) total += s[1] - s[0] + 1
            }
            return total
        }
    }
}
