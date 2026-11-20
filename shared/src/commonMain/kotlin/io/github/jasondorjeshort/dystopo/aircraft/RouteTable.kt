package io.github.jasondorjeshort.dystopo.aircraft

/** T311: an airport of the route table (OurAirports, public domain): ICAO code, IATA code when it has one, name, city. */
data class Airport(val icao: String, val iata: String?, val lat: Double, val lon: Double, val name: String, val city: String?) {
    /** The code a label shows: IATA where there is one ("SEA"), else ICAO. */
    val code: String get() = iata ?: icao

    fun json(): Map<String, Any?> = linkedMapOf("icao" to icao, "iata" to iata, "name" to name, "city" to city, "lat" to lat, "lon" to lon)
}

/** T311: a call sign's route, the airports in the order flown (VRS standing data, CC0). */
data class FlightRoute(val callSign: String, val airports: List<Airport>)

/**
 * T311: the route table the app ships (`androidApp/src/main/assets/aircraft/routes.gzip`, made by `devtool aircraft-routes`
 * from the VRS standing data's routes, CC0, and OurAirports' airports, public domain). Looked up on the phone, so no call
 * sign leaves it. The format is internal to the app (the home site's refresh of it would take an ADR first); UTF-8 text,
 * gzip-compressed in the asset (the platform inflates it; named `.gzip`, since the Android build unpacks an asset named
 * `.gz` into the APK):
 *
 * ```
 * dystopo-aircraft-routes 1
 * # comment lines (sources, build date)
 * A <n>                                    the airports, index 0 to n-1
 * ICAO \t IATA (or empty) \t lat \t lon \t name \t city (or empty)
 * S <n>                                    the airport sequences flown, index 0 to n-1
 * <airport index> <airport index> [...]
 * C <n>                                    one line per airline code
 * CODE \t <number>=<sequence index> <number>=<sequence index> ...
 * ```
 *
 * A call sign is the airline code then the number (`ASA330` is `ASA` and `330`); every VRS route row is so. The `C`
 * section stays as bytes and a line is scanned only when a call sign of its code is asked for; answers are cached.
 */
class RouteTable private constructor(
    val airports: List<Airport>,
    private val sequences: List<IntArray>,
    private val bytes: ByteArray,
    private val lines: Map<String, IntRange>,
    val built: String?,
) {
    val routeCount: Int get() = counted
    private var counted = 0
    private val cache = LinkedHashMap<String, FlightRoute?>()
    val airlineCodes: Int get() = lines.size
    val sequenceCount: Int get() = sequences.size

    /** The route of [callSign], or null when the table has none. */
    fun lookup(callSign: String): FlightRoute? = lookup(callSign, normalise = true)

    /** [lookup], or without round 3's variants of the number ([normalise] false: the devtool's before-and-after). */
    fun lookup(callSign: String, normalise: Boolean): FlightRoute? {
        val cs = callSign.trim().uppercase()
        if (cs.length < 3) return null
        if (normalise) synchronized(cache) { if (cache.containsKey(cs)) return cache[cs] }
        var found: FlightRoute? = null
        // Round 3: on a miss, the number without its leading zeros (SJX032 as SJX32), then without one trailing letter
        // (UAL719C as UAL719): the feed's call sign and the table's row differ so.
        search@ for (variant in 0..(if (normalise) 2 else 0)) for (n in intArrayOf(3, 2, 4)) {
            if (cs.length <= n) continue
            val range = lines[cs.substring(0, n)] ?: continue
            val number = numberVariant(cs.substring(n), variant) ?: continue
            val seq = find(range, number) ?: continue
            found = FlightRoute(cs, sequences[seq].map { airports[it] })
            break@search
        }
        if (normalise) synchronized(cache) {
            if (cache.size > 2000) cache.remove(cache.keys.first())
            cache[cs] = found
        }
        return found
    }

    /** [number] as asked (0), without leading zeros (1), without one trailing letter and leading zeros (2); null when no different. */
    private fun numberVariant(number: String, variant: Int): String? = when (variant) {
        0 -> number
        1 -> number.trimStart('0').takeIf { it.isNotEmpty() && it != number }
        else -> if (number.length > 1 && number.last().isLetter() && number[number.length - 2].isDigit()) number.dropLast(1).trimStart('0').ifEmpty { null } else null
    }

    /** The sequence index of [number] on the line [range] (after the code and the tab), or null. */
    private fun find(range: IntRange, number: String): Int? {
        val nb = number.encodeToByteArray()
        var i = range.first
        val end = range.last + 1
        while (i < end) {
            // A token "number=seq" starts at i; compare the number part.
            var j = 0
            var k = i
            while (k < end && j < nb.size && bytes[k] == nb[j]) { k++; j++ }
            if (j == nb.size && k < end && bytes[k] == '='.code.toByte()) {
                var v = 0
                k++
                while (k < end && bytes[k] in '0'.code.toByte()..'9'.code.toByte()) { v = v * 10 + (bytes[k] - '0'.code.toByte()); k++ }
                return v
            }
            while (i < end && bytes[i] != ' '.code.toByte()) i++
            i++
        }
        return null
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "airports" to airports.size, "sequences" to sequences.size, "airlineCodes" to lines.size, "routes" to counted,
        "bytes" to bytes.size, "built" to built,
    )

    companion object {
        const val MAGIC = "dystopo-aircraft-routes 1"

        /** Parses the inflated table; throws IllegalArgumentException on a file of another format. */
        fun parse(bytes: ByteArray): RouteTable {
            var pos = 0
            fun line(): String? {
                if (pos >= bytes.size) return null
                var e = pos
                while (e < bytes.size && bytes[e] != '\n'.code.toByte()) e++
                val s = bytes.decodeToString(pos, e).trimEnd('\r')
                pos = e + 1
                return s
            }
            val magic = line()
            require(magic == MAGIC) { "not a route table ($magic)" }
            var built: String? = null
            var head = line()
            while (head != null && head.startsWith("#")) {
                if (head.startsWith("# built ")) built = head.removePrefix("# built ").trim()
                head = line()
            }
            require(head != null && head.startsWith("A ")) { "route table: no airport section" }
            val na = head.substring(2).trim().toInt()
            val airports = ArrayList<Airport>(na)
            repeat(na) {
                val f = (line() ?: error("route table: short airport section")).split('\t')
                require(f.size >= 6) { "route table: airport line ${airports.size}" }
                airports += Airport(f[0], f[1].ifEmpty { null }, f[2].toDouble(), f[3].toDouble(), f[4], f[5].ifEmpty { null })
            }
            val sh = line()
            require(sh != null && sh.startsWith("S ")) { "route table: no sequence section" }
            val ns = sh.substring(2).trim().toInt()
            val sequences = ArrayList<IntArray>(ns)
            repeat(ns) {
                val s = line() ?: error("route table: short sequence section")
                sequences += s.split(' ').map { it.toInt() }.toIntArray().also { a -> require(a.all { it in 0 until na }) { "route table: sequence ${sequences.size}" } }
            }
            val ch = line()
            require(ch != null && ch.startsWith("C ")) { "route table: no call sign section" }
            val nc = ch.substring(2).trim().toInt()
            val lines = HashMap<String, IntRange>(nc * 2)
            var routes = 0
            repeat(nc) {
                val start = pos
                var e = pos
                while (e < bytes.size && bytes[e] != '\n'.code.toByte()) e++
                var tab = start
                while (tab < e && bytes[tab] != '\t'.code.toByte()) tab++
                require(tab < e) { "route table: call sign line ${lines.size}" }
                var lineEnd = e
                if (lineEnd > start && bytes[lineEnd - 1] == '\r'.code.toByte()) lineEnd--
                lines[bytes.decodeToString(start, tab)] = (tab + 1) until lineEnd
                for (i in tab + 1 until lineEnd) if (bytes[i] == '='.code.toByte()) routes++
                pos = e + 1
            }
            return RouteTable(airports, sequences, bytes, lines, built).also { it.counted = routes }
        }

        /**
         * Writes a table (the devtool command's output, before compression): [airports] in index order, [sequences] of
         * airport indices, and per airline code its numbers with their sequence index, codes and numbers in the given order.
         */
        fun write(airports: List<Airport>, sequences: List<IntArray>, byCode: Map<String, List<Pair<String, Int>>>, comments: List<String>): String {
            val sb = StringBuilder()
            sb.append(MAGIC).append('\n')
            for (c in comments) sb.append("# ").append(c).append('\n')
            sb.append("A ").append(airports.size).append('\n')
            for (a in airports) {
                sb.append(a.icao).append('\t').append(a.iata ?: "").append('\t').append(coord(a.lat)).append('\t').append(coord(a.lon))
                    .append('\t').append(clean(a.name)).append('\t').append(clean(a.city ?: "")).append('\n')
            }
            sb.append("S ").append(sequences.size).append('\n')
            for (s in sequences) sb.append(s.joinToString(" ")).append('\n')
            sb.append("C ").append(byCode.size).append('\n')
            for ((code, list) in byCode) {
                sb.append(code).append('\t')
                list.forEachIndexed { i, (n, s) -> if (i > 0) sb.append(' '); sb.append(n).append('=').append(s) }
                sb.append('\n')
            }
            return sb.toString()
        }

        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

        /** Four decimals (about 11 m), the precision a route line needs. */
        private fun coord(v: Double): String = io.github.jasondorjeshort.dystopo.render.UnitFormat.fixed(v, 4)
    }
}
