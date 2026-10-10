package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.double
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.DriverManager
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Writes a [BorderMask] resource from Natural Earth's 1:10m admin-0 countries (public domain, in the
 * `natural_earth_vector.sqlite.zip` that Planetiler downloads into `cache/planetiler/` for tilebuild):
 * the chosen countries' polygons, clipped to a box, as the even-odd rings [BorderMask] reads (T66).
 *
 * ```
 * devtool borders --countries SWE,FIN,RUS --clip 3.5,57.5,35,72 --out serverlib/src/main/resources/borders/norway-neighbours.json
 * ```
 */
class BordersCommand : CliktCommand(name = "borders") {
    override fun help(context: Context) =
        "Write a border mask (Natural Earth admin-0 countries clipped to a box) for a national elevation source; prints a JSON report."

    private val naturalEarth by option("--natural-earth", help = "natural_earth_vector.sqlite.zip (or the extracted .sqlite)")
        .default(File(Caches.root, "planetiler/natural_earth_vector.sqlite.zip").path)
    private val countries by option("--countries", help = "ADM0_A3 codes, comma-separated").split(",").default(listOf("SWE", "FIN", "RUS"))
    private val clip by option("--clip", help = "west,south,east,north to keep").default("3.5,57.5,35,72")
    private val name by option("--name", help = "mask name").default("norway-neighbours")
    private val tolerance by option("--tolerance", help = "drop vertices closer than this to their neighbours' line, degrees (0 keeps all)").double().default(0.0005)
    private val out by option("--out", help = "output JSON").default("serverlib/src/main/resources/borders/norway-neighbours.json")

    override fun run() {
        val box = clip.split(',').map { it.trim().toDoubleOrNull() ?: throw UsageError("--clip must be west,south,east,north") }
        if (box.size != 4 || box[0] >= box[2] || box[1] >= box[3]) throw UsageError("--clip must be west,south,east,north")
        val started = System.nanoTime()
        val source = File(naturalEarth)
        if (!source.isFile) throw UsageError("no $source (any tilebuild run downloads it; or pass --natural-earth)")
        var tmp: File? = null
        val db = if (source.name.endsWith(".zip")) {
            tmp = File.createTempFile("natural_earth", ".sqlite")
            ZipFile(source).use { z ->
                val entry = z.entries().asSequence().firstOrNull { it.name.endsWith(".sqlite") } ?: throw UsageError("no .sqlite in $source")
                z.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            tmp
        } else source
        val rings = ArrayList<DoubleArray>()
        val perCountry = LinkedHashMap<String, Int>()
        var verticesIn = 0
        try {
            DriverManager.getConnection("jdbc:sqlite:" + db.absolutePath.replace('\\', '/')).use { c ->
                c.prepareStatement("SELECT adm0_a3, GEOMETRY FROM ne_10m_admin_0_countries WHERE adm0_a3 = ?").use { st ->
                    for (code in countries) {
                        st.setString(1, code.trim().uppercase())
                        val rs = st.executeQuery()
                        if (!rs.next()) throw UsageError("no country $code in ne_10m_admin_0_countries")
                        var kept = 0
                        for (ring in wkbRings(rs.getBytes(2))) {
                            verticesIn += ring.size / 2
                            val clipped = BorderMask.clipRing(ring, box[0], box[1], box[2], box[3]) ?: continue
                            val simple = simplify(clipped, tolerance)
                            if (simple.size >= 6) {
                                rings += simple
                                kept++
                            }
                        }
                        perCountry[code] = kept
                    }
                }
            }
        } finally {
            tmp?.delete()
        }
        val file = File(out)
        file.parentFile?.mkdirs()
        val head = linkedMapOf(
            "name" to name,
            "source" to "Natural Earth 1:10m Cultural Vectors, Admin 0 - Countries (public domain; naturalearthdata.com), " +
                "from the natural_earth_vector.sqlite that Planetiler uses; written by devtool borders",
            "countries" to countries,
            "clip" to box,
            "tolerance" to tolerance,
        )
        // One ring per line, five decimals (about 1 m), so a regenerated file diffs by ring.
        val text = StringBuilder(JsonWriter.write(head).dropLast(1)).append(",\n\"rings\": [\n")
        rings.forEachIndexed { i, r ->
            text.append('[').append((r.indices).joinToString(",") { String.format(Locale.ROOT, "%.5f", r[it]).trimEnd('0').trimEnd('.') }).append(']')
            text.append(if (i < rings.size - 1) ",\n" else "\n")
        }
        text.append("]}\n")
        file.writeText(text.toString())
        val mask = BorderMask.parse(file.readText())
        echo(reportJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), reportJson.parseToJsonElement(JsonWriter.write(linkedMapOf(
            "out" to file.path,
            "bytes" to file.length(),
            "rings" to mask.rings.size,
            "ringsPerCountry" to perCountry,
            "verticesIn" to verticesIn,
            "verticesOut" to mask.vertices,
            "bounds" to mask.bounds.map { String.format(Locale.ROOT, "%.4f", it).toDouble() },
            "ms" to (System.nanoTime() - started) / 1_000_000,
        )))))
    }

    companion object {
        /** Every ring of a WKB Polygon or MultiPolygon (either byte order), as interleaved lon, lat. */
        fun wkbRings(wkb: ByteArray): List<DoubleArray> {
            val buf = ByteBuffer.wrap(wkb)
            val out = ArrayList<DoubleArray>()
            fun polygon() {
                buf.order(if (buf.get().toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                val type = buf.int
                require(type == 3) { "expected a WKB Polygon, got type $type" }
                repeat(buf.int) {
                    val n = buf.int
                    out += DoubleArray(n * 2) { buf.double }
                }
            }
            buf.order(if (buf.get(0).toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            when (val type = buf.getInt(1)) {
                3 -> polygon()
                6 -> {
                    buf.position(5)
                    repeat(buf.int) { polygon() }
                }
                else -> error("expected a WKB Polygon or MultiPolygon, got type $type")
            }
            return out
        }

        /** Douglas-Peucker on a ring of interleaved lon, lat; [tol] in degrees, 0 keeps every vertex. */
        fun simplify(ring: DoubleArray, tol: Double): DoubleArray {
            val n = ring.size / 2
            if (tol <= 0 || n <= 4) return ring
            val keep = BooleanArray(n)
            keep[0] = true
            keep[n - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(0 to n - 1)
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                if (b <= a + 1) continue
                val ax = ring[2 * a]; val ay = ring[2 * a + 1]
                val bx = ring[2 * b]; val by = ring[2 * b + 1]
                val dx = bx - ax; val dy = by - ay
                val len = Math.hypot(dx, dy)
                var worst = -1.0
                var at = -1
                for (k in a + 1 until b) {
                    val px = ring[2 * k]; val py = ring[2 * k + 1]
                    val d = if (len == 0.0) Math.hypot(px - ax, py - ay) else Math.abs(dy * px - dx * py + bx * ay - by * ax) / len
                    if (d > worst) { worst = d; at = k }
                }
                if (worst > tol) {
                    keep[at] = true
                    stack.addLast(a to at)
                    stack.addLast(at to b)
                }
            }
            val idx = (0 until n).filter { keep[it] }
            return DoubleArray(idx.size * 2) { ring[2 * idx[it / 2] + it % 2] }
        }
    }
}
