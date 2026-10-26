package io.github.jasondorjeshort.dystopo.boundaries

import io.github.jasondorjeshort.dystopo.areas.AreaPoint

/**
 * One country of the bundled set (Natural Earth admin-0 at 1:50m; which rows are kept: `devtool countries`).
 *
 * - [code]: Natural Earth's `ADM0_A3`, the key (never -99); [iso3]: `ISO_A3_EH` when it has one, a lookup only.
 * - [name] (`NAME`) is the one text a user sees in a list; [nameLong] (`NAME_LONG`) is for a detail line.
 * - [west], [south], [east], [north]: the bounding box of every ring. When the country has parts either side of
 *   the antimeridian (Fiji, Russia, the United States), [west] is greater than [east] (RFC 7946 §5.2: the box
 *   runs east from [west] across 180 to [east]); [crossesAntimeridian] says so.
 * - [rings]: closed rings of [AreaPoint] in GeoJSON order, inside by the even-odd rule: an outer ring is
 *   counter-clockwise (positive area with longitude as x), a hole clockwise; every part of a multipolygon is its
 *   own outer ring; no ring crosses the antimeridian (parts end at -180 or 180). Decoded on first use.
 * - [toleranceM]: how far the stored rings may lie from Natural Earth's, in metres (0 when not simplified). A
 *   writer of an area manifest adds it to `bufferM` so the area still covers the source polygon (ADR 0041).
 */
class Country internal constructor(
    val code: String,
    val iso3: String?,
    val name: String,
    val nameLong: String,
    val continent: String,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val toleranceM: Double,
    private val encodedRings: List<String>,
) {
    val crossesAntimeridian: Boolean get() = west > east

    val rings: List<List<AreaPoint>> by lazy { encodedRings.map(RingCodec::decode) }

    /** Vertices over all rings, closing points not counted. */
    val vertexCount: Int get() = rings.sumOf { it.size - 1 }

    internal val folded: List<String> by lazy { listOfNotNull(TextFold.fold(name), TextFold.fold(nameLong), code.lowercase(), iso3?.lowercase()) }

    override fun toString(): String = "Country($code, $name)"
}

/**
 * The countries bundled with the app (`resources/boundaries/countries.txt`, written by `devtool countries --build`,
 * embedded by `:shared:generateSourcesJson`). Credit: the registry's `natural-earth` entry ([REGISTRY_ID]).
 *
 * The text: lines starting with `#` are comments; every other line is one country, tab-separated:
 * `code`, `iso3` (empty when none), `name`, `nameLong`, `continent`, `west,south,east,north`, `toleranceM`, then
 * the rings separated by spaces, each in [RingCodec]'s text.
 */
object Countries {
    const val REGISTRY_ID = "natural-earth"

    /** Every bundled country, in the file's order (alphabetical by [Country.name]). */
    val all: List<Country> by lazy { parse(CountriesData.TEXT) }

    private val byCodes: Map<String, Country> by lazy {
        val m = HashMap<String, Country>()
        for (c in all) c.iso3?.let { m[it] = c }
        for (c in all) m[c.code] = c
        m
    }

    /** The country whose `ADM0_A3` (or failing that `ISO_A3_EH`) is [code], any case; null when none. */
    fun byCode(code: String): Country? = byCodes[code.trim().uppercase()]

    /**
     * Countries matching [text] in [Country.name], [Country.nameLong] or a code, ignoring case and accents
     * ([TextFold]): an exact code first, then names starting with the text, then names containing it, each group
     * alphabetical. Blank text finds nothing.
     */
    fun find(text: String, among: List<Country> = all): List<Country> {
        val q = TextFold.fold(text.trim())
        if (q.isEmpty()) return emptyList()
        fun rank(c: Country): Int = when {
            c.code.lowercase() == q || c.iso3?.lowercase() == q -> 0
            c.folded.any { it.startsWith(q) } -> 1
            c.folded.any { q in it } -> 2
            else -> 3
        }
        return among.map { it to rank(it) }.filter { it.second < 3 }.sortedWith(compareBy({ it.second }, { it.first.name })).map { it.first }
    }

    /** Reads the text form (see the object comment). */
    fun parse(text: String): List<Country> {
        val out = ArrayList<Country>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) continue
            val f = line.split('\t')
            require(f.size == 8) { "a country line has 8 fields, got ${f.size}: ${line.take(40)}" }
            val box = f[5].split(',').map { it.toDouble() }
            require(box.size == 4) { "bounds are west,south,east,north: ${f[5]}" }
            out += Country(
                code = f[0], iso3 = f[1].ifEmpty { null }, name = f[2], nameLong = f[3], continent = f[4],
                west = box[0], south = box[1], east = box[2], north = box[3], toleranceM = f[6].toDouble(),
                encodedRings = f[7].split(' ').filter { it.isNotEmpty() },
            )
        }
        return out
    }
}
