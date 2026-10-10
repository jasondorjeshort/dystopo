package io.github.jasondorjeshort.dystopo.search

import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.pmtiles.ByteReader
import io.github.jasondorjeshort.dystopo.pmtiles.ByteWriter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One searchable place. [rank] (0..255) is its importance before name match and distance; see [SearchIndexBuilder]. */
data class SearchEntry(
    val name: String,
    /** A second name (`name_en`, `name:latin`, ...) that also matches, or null. */
    val alt: String?,
    val kind: PlaceKind,
    val lon: Double,
    val lat: Double,
    val rank: Int,
    /** The lowest scanned zoom the place appeared at (a proxy for how prominent the map makes it). */
    val minZoom: Int,
    /** Elevation in metres (peaks, huts), or null. */
    val ele: Int?,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "name" to name, "alt" to alt, "kind" to kind.label, "lat" to round6(lat), "lon" to round6(lon),
        "rank" to rank, "minZoom" to minZoom, "ele" to ele, "zoom" to kind.zoom,
    )
}

/** What an index was built from, so a stale index (the pack changed) is rebuilt instead of used. */
data class SearchSource(
    val file: String,
    /** [SearchIndexBuilder.fingerprint] of the vector archive. */
    val fingerprint: String,
    val zooms: List<Int>,
    /** Build time, epoch milliseconds (from the caller; commonMain has no clock). */
    val builtAtMs: Long,
)

/** One search result. */
data class SearchHit(
    val entry: SearchEntry,
    val score: Double,
    /** Distance from the search centre in km, when a centre was given. */
    val distanceKm: Double?,
    /** How the name matched: exact, prefix (the name starts with the query), words, partial, fuzzy. */
    val match: String,
) {
    fun json(): Map<String, Any?> = entry.json() + linkedMapOf(
        "score" to (score * 10).roundToInt() / 10.0,
        "distanceKm" to distanceKm?.let { (it * 100).roundToInt() / 100.0 },
        "match" to match,
    )
}

/**
 * An offline place-name index: entries plus a sorted token table with postings, searched by token
 * prefix. Pure Kotlin; the file format ([encode]/[decode]) is ours:
 *
 * ```
 * "DYSI" u8 version(=1)
 * str sourceFile, str fingerprint, u8 zoomCount, u8 zooms..., u64 builtAtMs
 * varint entryCount, entries: str name, str alt ("" = none), u8 kind code, i32 lonE7, i32 latE7,
 *                             u8 rank, u8 minZoom, varint ele (0 = none, else zigzag(ele) + 1)
 * varint tokenCount, tokens in sorted order, front-coded: varint shared prefix length, str suffix,
 *                    varint postings count, varint entry-id deltas (ascending)
 * u32 FNV-1a of every byte before it
 * ```
 * Strings are varint byte length + UTF-8. Integers are little-endian.
 */
class SearchIndex private constructor(
    val entries: List<SearchEntry>,
    val source: SearchSource,
    private val tokens: Array<String>,
    private val postings: Array<IntArray>,
) {
    val tokenCount: Int get() = tokens.size
    val postingCount: Int get() = postings.sumOf { it.size }

    /**
     * Places whose names match [query]: every query token must be a prefix of a token of the name (or
     * the alternate name). When a token matches nothing, tokens one edit away (4+ letters) are tried.
     * Ranked by match quality, [SearchEntry.rank] and distance to ([lat], [lon]) when given.
     */
    fun search(query: String, lat: Double? = null, lon: Double? = null, limit: Int = 20, kinds: Set<PlaceKind>? = null): List<SearchHit> {
        val qTokens = TextFold.tokens(query).distinct()
        if (qTokens.isEmpty() || limit <= 0) return emptyList()
        var fuzzy = false
        var candidates: IntArray? = null
        for (t in qTokens) {
            var ids = prefixPostings(t)
            if (ids.isEmpty() && t.length >= 4) {
                ids = fuzzyPostings(t)
                if (ids.isNotEmpty()) fuzzy = true
            }
            candidates = if (candidates == null) ids else intersect(candidates, ids)
            if (candidates.isEmpty()) return emptyList()
        }
        val qNorm = TextFold.normalized(query)
        val hits = ArrayList<SearchHit>()
        for (id in candidates!!) {
            val e = entries[id]
            if (kinds != null && e.kind !in kinds) continue
            val (quality, match) = if (fuzzy) 25.0 to "fuzzy" else matchQuality(qNorm, qTokens, e)
            val dist = if (lat != null && lon != null) distanceKm(lat, lon, e.lat, e.lon) else null
            val lengthPenalty = min(10.0, 0.25 * (TextFold.normalized(e.name).length - qNorm.length).coerceAtLeast(0))
            val distPenalty = dist?.let { 10 * log2(1 + it / 5) } ?: 0.0
            hits += SearchHit(e, quality + e.rank - lengthPenalty - distPenalty, dist, match)
        }
        hits.sortWith(compareByDescending<SearchHit> { it.score }.thenBy { it.entry.name })
        return if (hits.size > limit) hits.subList(0, limit).toList() else hits
    }

    private fun matchQuality(qNorm: String, qTokens: List<String>, e: SearchEntry): Pair<Double, String> {
        var best = 0.0 to "partial"
        for (name in listOfNotNull(e.name, e.alt)) {
            val n = TextFold.normalized(name)
            val q = when {
                n == qNorm -> 100.0 to "exact"
                n.startsWith(qNorm) -> 80.0 to "prefix"
                else -> {
                    val nameTokens = TextFold.tokens(name).let { t -> t + t.mapNotNull { TextFold.SYNONYMS[it] } }
                    val whole = qTokens.dropLast(1).all { it in nameTokens }
                    val last = qTokens.last()
                    val lastWhole = nameTokens.any { it == last }
                    when {
                        whole && lastWhole -> 65.0 to "words"
                        whole && nameTokens.any { it.startsWith(last) } -> 55.0 to "words"
                        else -> 45.0 to "partial"
                    }
                }
            }
            if (q.first > best.first) best = q
        }
        return best
    }

    /** Sorted, distinct entry ids of every token starting with [prefix]. */
    private fun prefixPostings(prefix: String): IntArray {
        var i = lowerBound(prefix)
        val lists = ArrayList<IntArray>()
        while (i < tokens.size && tokens[i].startsWith(prefix)) lists += postings[i++]
        return union(lists)
    }

    /** Entry ids of tokens whose start is within one edit of [t] (substitution, insertion, deletion or swap). */
    private fun fuzzyPostings(t: String): IntArray {
        val lists = ArrayList<IntArray>()
        for (i in tokens.indices) {
            val tok = tokens[i]
            if (tok.isEmpty() || tok[0] != t[0] && (tok.length < 2 || t.length < 2 || tok[1] != t[1])) continue
            if (withinOneEdit(t, tok)) lists += postings[i]
        }
        return union(lists)
    }

    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = tokens.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tokens[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun encode(): ByteArray {
        val w = ByteWriter(64 + entries.size * 32 + tokens.size * 12)
        w.bytes(MAGIC)
        w.u8(VERSION)
        w.str(source.file)
        w.str(source.fingerprint)
        w.u8(source.zooms.size)
        source.zooms.forEach { w.u8(it) }
        w.u64(source.builtAtMs)
        w.varint(entries.size.toLong())
        for (e in entries) {
            w.str(e.name)
            w.str(e.alt.orEmpty())
            w.u8(e.kind.code)
            w.i32((e.lon * 1e7).roundToInt())
            w.i32((e.lat * 1e7).roundToInt())
            w.u8(e.rank.coerceIn(0, 255))
            w.u8(e.minZoom.coerceIn(0, 255))
            w.varint(e.ele?.let { zigzag(it) + 1 } ?: 0L)
        }
        w.varint(tokens.size.toLong())
        var prev = ""
        for (i in tokens.indices) {
            val t = tokens[i]
            val shared = commonPrefix(prev, t)
            w.varint(shared.toLong())
            w.str(t.substring(shared))
            val p = postings[i]
            w.varint(p.size.toLong())
            var last = 0
            for (id in p) { w.varint((id - last).toLong()); last = id }
            prev = t
        }
        val body = w.toByteArray()
        val out = ByteWriter(body.size + 4)
        out.bytes(body)
        out.u32(fnv1a(body, body.size).toLong() and 0xFFFFFFFFL)
        return out.toByteArray()
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "file" to source.file, "fingerprint" to source.fingerprint, "zooms" to source.zooms, "builtAtMs" to source.builtAtMs,
        "entries" to entries.size, "tokens" to tokens.size, "postings" to postingCount,
        "kinds" to entries.groupingBy { it.kind.label }.eachCount().entries.sortedByDescending { it.value }.associate { it.key to it.value },
    )

    companion object {
        private val MAGIC = byteArrayOf(0x44, 0x59, 0x53, 0x49) // "DYSI"
        const val VERSION = 1
        const val SUFFIX = "-search.idx"

        /** The index file that sits next to a pack's vector archive: `<pack name>-search.idx`. */
        fun fileNameFor(vectorFileName: String): String {
            val pack = PackNames.parse(vectorFileName)?.pack ?: vectorFileName.substringBeforeLast('.')
            return pack + SUFFIX
        }

        /** Builds the token table for [entries]. */
        fun of(entries: List<SearchEntry>, source: SearchSource): SearchIndex {
            val map = HashMap<String, MutableList<Int>>()
            entries.forEachIndexed { id, e ->
                val toks = LinkedHashSet<String>()
                for (n in listOfNotNull(e.name, e.alt)) for (t in TextFold.tokens(n)) {
                    toks += t
                    TextFold.SYNONYMS[t]?.let { toks += it }
                }
                for (t in toks) map.getOrPut(t) { ArrayList(2) } += id
            }
            val sorted = map.keys.sorted().toTypedArray()
            return SearchIndex(entries, source, sorted, Array(sorted.size) { map.getValue(sorted[it]).toIntArray() })
        }

        /** Reads an index written by [encode]; throws IllegalArgumentException on a bad, truncated or newer file. */
        fun decode(bytes: ByteArray): SearchIndex {
            require(bytes.size >= 9 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)) { "not a search index" }
            val stored = ByteReader(bytes, bytes.size - 4).u32().toInt()
            require(stored == fnv1a(bytes, bytes.size - 4)) { "search index checksum mismatch (truncated or corrupt)" }
            val r = ByteReader(bytes.copyOfRange(0, bytes.size - 4), 4)
            try {
                val version = r.u8()
                require(version == VERSION) { "search index version $version, expected $VERSION" }
                val file = r.str()
                val fingerprint = r.str()
                val zooms = List(r.u8()) { r.u8() }
                val builtAt = r.u64()
                val n = r.count()
                val entries = ArrayList<SearchEntry>(n)
                repeat(n) {
                    val name = r.str()
                    val alt = r.str().ifEmpty { null }
                    val code = r.u8()
                    val kind = PlaceKind.ofCode(code) ?: PlaceKind.POI // a kind from a newer build: still findable
                    val lon = r.i32() / 1e7
                    val lat = r.i32() / 1e7
                    val rank = r.u8()
                    val minZoom = r.u8()
                    val ele = r.varint().let { if (it == 0L) null else unzigzag(it - 1) }
                    entries += SearchEntry(name, alt, kind, lon, lat, rank, minZoom, ele)
                }
                val tn = r.count()
                val tokens = arrayOfNulls<String>(tn)
                val postings = arrayOfNulls<IntArray>(tn)
                var prev = ""
                for (i in 0 until tn) {
                    val shared = r.count()
                    require(shared <= prev.length) { "bad token prefix" }
                    val t = prev.substring(0, shared) + r.str()
                    val pc = r.count()
                    var id = 0
                    postings[i] = IntArray(pc) {
                        id += r.count()
                        require(id < n) { "posting out of range" }
                        id
                    }
                    tokens[i] = t
                    prev = t
                }
                @Suppress("UNCHECKED_CAST")
                return SearchIndex(entries, SearchSource(file, fingerprint, zooms, builtAt), tokens as Array<String>, postings as Array<IntArray>)
            } catch (e: IllegalStateException) {
                throw IllegalArgumentException("corrupt search index: ${e.message}", e)
            }
        }

        private fun ByteWriter.str(s: String) {
            val b = s.encodeToByteArray()
            varint(b.size.toLong())
            bytes(b)
        }

        private fun ByteReader.str(): String {
            val n = count()
            return bytes(n).decodeToString()
        }

        private fun ByteReader.count(): Int {
            val v = varint()
            require(v in 0..Int.MAX_VALUE.toLong()) { "bad count $v" }
            return v.toInt()
        }

        private fun zigzag(v: Int): Long = ((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL
        private fun unzigzag(v: Long): Int = ((v ushr 1) xor -(v and 1)).toInt()

        private fun fnv1a(b: ByteArray, n: Int): Int {
            var h = 0x811C9DC5.toInt()
            for (i in 0 until n) {
                h = h xor (b[i].toInt() and 0xFF)
                h *= 0x01000193
            }
            return h
        }

        private fun commonPrefix(a: String, b: String): Int {
            val n = min(a.length, b.length)
            var i = 0
            while (i < n && a[i] == b[i]) i++
            return i
        }

        private fun union(lists: List<IntArray>): IntArray = when (lists.size) {
            0 -> IntArray(0)
            1 -> lists[0]
            else -> lists.flatMap { it.asIterable() }.distinct().sorted().toIntArray()
        }

        private fun intersect(a: IntArray, b: IntArray): IntArray {
            val out = ArrayList<Int>(min(a.size, b.size))
            var i = 0
            var j = 0
            while (i < a.size && j < b.size) {
                when {
                    a[i] < b[j] -> i++
                    a[i] > b[j] -> j++
                    else -> { out += a[i]; i++; j++ }
                }
            }
            return out.toIntArray()
        }

        /** True when some prefix of [token] (of length |q|-1..|q|+1) is within one edit of [q]. */
        internal fun withinOneEdit(q: String, token: String): Boolean {
            for (len in q.length - 1..q.length + 1) {
                if (len < 1 || len > token.length) continue
                if (editDistanceAtMostOne(q, token.substring(0, len))) return true
            }
            return false
        }

        private fun editDistanceAtMostOne(a: String, b: String): Boolean {
            if (abs(a.length - b.length) > 1) return false
            var i = 0
            var j = 0
            var edits = 0
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) { i++; j++; continue }
                if (++edits > 1) return false
                when {
                    a.length > b.length -> i++
                    a.length < b.length -> j++
                    // Two swapped letters count as one edit (Damerau): "paradsie" for "paradise".
                    i + 1 < a.length && a[i] == b[j + 1] && a[i + 1] == b[j] -> { i += 2; j += 2 }
                    else -> { i++; j++ }
                }
            }
            return edits + (a.length - i) + (b.length - j) <= 1
        }

        /** Great-circle distance in km (equirectangular; plenty for ranking within a pack). */
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val k = PI / 180
            val x = (lon2 - lon1) * k * cos((lat1 + lat2) / 2 * k)
            val y = (lat2 - lat1) * k
            return 6371.0 * sqrt(x * x + y * y)
        }
    }
}

private fun round6(v: Double) = (v * 1e6).roundToInt() / 1e6
