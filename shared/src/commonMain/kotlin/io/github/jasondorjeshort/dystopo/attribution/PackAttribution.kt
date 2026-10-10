package io.github.jasondorjeshort.dystopo.attribution

import io.github.jasondorjeshort.dystopo.sources.DataSource
import io.github.jasondorjeshort.dystopo.sources.SourceKind
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry

/** HTML-to-display-text helpers for credits stored as HTML (PMTiles `attribution`, registry `attributionHtml`). */
object HtmlText {
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "middot" to "·",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bull" to "•",
    )
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});")
    private val TAG = Regex("<[^>]*>")
    private val SPACE = Regex("\\s+")

    /**
     * Decodes named (`&copy;`), decimal (`&#169;`) and hex (`&#xA9;`) character references in one pass,
     * so `&amp;copy;` becomes the literal text `&copy;`. Unknown names and invalid code points stay as written.
     */
    fun unescape(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { m ->
            val e = m.groupValues[1]
            val code = when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)
                e.startsWith("#") -> e.substring(1).toIntOrNull()
                else -> return@replace NAMED[e] ?: m.value
            }
            if (code == null || code == 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) m.value else codePointString(code)
        }
    }

    /** Tags removed, entities decoded, whitespace collapsed: what a plain-text UI shows for an HTML credit. */
    fun toPlain(html: String): String = unescape(html.replace(TAG, " ")).replace(SPACE, " ").trim()
        .replace(" )", ")").replace("( ", "(").replace(" ,", ",").replace(" .", ".")

    private fun codePointString(cp: Int): String = if (cp < 0x10000) {
        cp.toChar().toString()
    } else {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
}

/** One opened tile pack as the composer sees it: its role (`dem`, `osm`), file name and PMTiles metadata JSON. */
data class PackMetadata(val role: String, val file: String, val metadata: Map<String, Any?>)

/** What a pack says about itself, with its registry ids resolved. */
data class PackCredit(
    val role: String,
    val file: String,
    val name: String?,
    /** The pack's own `attribution`, as plain text (tags removed, entities decoded). */
    val attribution: String,
    val license: String?,
    /** Registry ids the pack's metadata names, in registry order (known ids only). */
    val registryIds: List<String>,
    /** Ids the metadata names that this app's registry doesn't know (a pack built by a newer tool). */
    val unknownIds: List<String>,
)

/** A registry source credited because a pack or the app itself uses it. */
data class CreditedSource(val source: DataSource, val usedBy: List<String>)

/** Everything the app credits: per pack, per source (registry order), and the compact line for the map. */
data class Credits(val packs: List<PackCredit>, val sources: List<CreditedSource>, val compact: String) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "compact" to compact,
        "packs" to packs.map {
            linkedMapOf(
                "role" to it.role, "file" to it.file, "name" to it.name, "attribution" to it.attribution,
                "license" to it.license, "registryIds" to it.registryIds, "unknownIds" to it.unknownIds,
            )
        },
        "sources" to sources.map { c ->
            val s = c.source
            linkedMapOf(
                "id" to s.id, "name" to s.name, "kind" to s.kind.json, "provider" to s.provider,
                "license" to s.license, "licenseName" to s.licenseName, "licenseUrl" to s.licenseUrl,
                "attribution" to s.attribution, "url" to s.url, "usedBy" to c.usedBy,
            )
        },
    )
}

/**
 * Composes the app's credits from the open packs' metadata and the data source registry
 * (docs/PLANNING.md › Licensing: the app shows attribution for every visible layer).
 *
 * Packs name the registry ids they were built from: DEM packs written by `devtool tiles` carry a
 * `provenance` array, vector packs from `tilebuild` a comma-separated `dystopo:provenance`. Both are
 * read; ids this registry doesn't know are reported, not dropped silently.
 */
object PackAttribution {
    /** Credit id for the label font; [compose] adds it as the app's own source when glyphs are installed. */
    const val GLYPH_FONT_ID = "noto-sans"

    /** Registry ids named by a pack's metadata, in first-seen order without duplicates. */
    fun registryIds(metadata: Map<String, Any?>): List<String> {
        val ids = LinkedHashSet<String>()
        fun add(v: Any?) {
            when (v) {
                is List<*> -> v.forEach { add(it) }
                is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { ids += it }
            }
        }
        add(metadata["provenance"])
        add(metadata["dystopo:provenance"])
        return ids.toList()
    }

    /**
     * Credits for [packs] plus the app's own sources ([appSourceIds], e.g. [GLYPH_FONT_ID]; credited as
     * used by `app`). The compact line keeps every pack's own credit (OSM's ODbL notice must stay
     * visible) and names elevation providers briefly; the full texts are on the About screen.
     */
    fun compose(packs: List<PackMetadata>, appSourceIds: List<String> = emptyList(), registry: SourceRegistry = SourceRegistry.builtIn): Credits {
        val usedBy = LinkedHashMap<String, MutableList<String>>()
        val packCredits = packs.map { p ->
            val named = registryIds(p.metadata)
            val (known, unknown) = named.partition { it in registry }
            known.forEach { usedBy.getOrPut(it) { ArrayList() } += p.role }
            PackCredit(
                role = p.role,
                file = p.file,
                name = p.metadata["name"] as? String,
                attribution = HtmlText.toPlain((p.metadata["attribution"] as? String).orEmpty()),
                license = (p.metadata["dystopo:license"] as? String) ?: (p.metadata["license"] as? String),
                registryIds = registry.ordered(known),
                unknownIds = unknown,
            )
        }
        appSourceIds.filter { it in registry }.forEach { usedBy.getOrPut(it) { ArrayList() } += "app" }
        val sources = registry.ordered(usedBy.keys).map { CreditedSource(registry.getValue(it), usedBy.getValue(it).distinct()) }
        return Credits(packCredits, sources, compactLine(packCredits, sources))
    }

    private fun compactLine(packs: List<PackCredit>, sources: List<CreditedSource>): String {
        val kinds = sources.associate { it.source.id to it.source.kind }
        val elevation = setOf(SourceKind.DEM, SourceKind.GEOID)
        val texts = ArrayList<String>()
        for (p in packs) {
            // A pack credited only with elevation sources (DEM + geoids) is summarised as "Elevation: ...".
            if (p.registryIds.isNotEmpty() && p.registryIds.all { kinds[it] in elevation }) continue
            if (p.attribution.isNotEmpty()) texts += p.attribution
        }
        // T49: several vector packs (or a pack and the server) repeat the same notices; each once.
        val parts = ArrayList(mergeCredits(texts))
        val dems = sources.filter { it.source.kind == SourceKind.DEM }.map { it.source }
        if (dems.isNotEmpty()) parts += "Elevation: " + dems.map { shortName(it) }.distinct().joinToString(", ")
        return (parts.distinct() + "Sources").joinToString(" · ")
    }

    /**
     * T49: the credit notices of several texts, each once: a text is split before every `©`, and a piece
     * that extends another (`© OpenStreetMap contributors (ODbL 1.0) OpenFreeMap` over `© OpenStreetMap
     * contributors (ODbL 1.0)`) replaces it. A text's pieces stay together in its order.
     */
    fun mergeCredits(texts: List<String>): List<String> {
        val out = ArrayList<MutableList<String>>()
        val seen = ArrayList<Pair<Int, Int>>() // (text index in out, piece index) of every kept piece
        fun pieceAt(at: Pair<Int, Int>) = out[at.first][at.second]
        for (t in texts) {
            val mine = ArrayList<String>()
            for (p in t.split(Regex("(?=\u00a9)")).map { it.trim() }.filter { it.isNotEmpty() }) {
                val i = seen.indexOfFirst { pieceAt(it).startsWith(p) || p.startsWith(pieceAt(it)) }
                if (i < 0) mine += p else if (p.length > pieceAt(seen[i]).length) out[seen[i].first][seen[i].second] = p
            }
            if (mine.isEmpty()) continue
            out += mine
            mine.indices.forEach { seen += (out.size - 1) to it }
        }
        return out.map { it.joinToString(" ") }
    }

    /** First word of the source name: `USGS`, `Copernicus`, `Mapzen`. */
    private fun shortName(s: DataSource): String = s.name.substringBefore(' ')
}
