package io.github.jasondorjeshort.dystopo.sources.elevation

import kotlin.concurrent.Volatile
import kotlin.jvm.Synchronized

/**
 * Which BEV ALS DTM edition [AustriaSource] reads (T66): the newest one in BEV's INSPIRE ATOM download
 * service feed, with the pinned [AustriaSource.EDITION] as the fallback.
 *
 * The service feed ([FEED], about 2 MB) has one entry per tile and edition, titled
 * `ALS DTM CRS3035RES50000mN<north>E<east> Höhenraster 1m Stichtag DD.MM.YYYY`. The newest edition from
 * 2022 on (the earlier ones are in GHA heights, not EVRF2000 Austria) with at least [MIN_TILES] tiles
 * wins; its tile table comes from the same entries. The feed is cached under the source's cache dir for
 * [MAX_AGE_MS] and a stale copy is used when BEV is unreachable; with no copy at all (or a feed that does not
 * parse) the pinned edition and tile table are used, and [Edition.from] says which happened.
 */
object AustriaEditions {
    const val FEED = "https://data.bev.gv.at/geonetwork/srv/atom/describe/service?uuid=208cff7a-c8aa-42fe-bf4f-2b8156e37528"

    /** An edition must have this many of the 55 tiles to be chosen (a partly published edition is skipped). */
    const val MIN_TILES = 50

    /** Earliest edition in EVRF2000 Austria heights (the 2021 edition and earlier are GHA). */
    const val FIRST_EVRF = "20220101"

    /** Seven days. */
    const val MAX_AGE_MS: Long = 7L * 24 * 3600 * 1000

    /**
     * An edition: [id] as in the download path (`20250915`), its tiles as south-west corners in units of
     * 50 km (northing index to the easting indices present), and [from]: `atom`, `atom-cached` (a stale copy
     * of the feed) or `pinned`, with [problem] saying why the feed was not used.
     */
    class Edition(val id: String, val tiles: Map<Int, Set<Int>>, val from: String, val problem: String? = null) {
        val tileCount: Int get() = tiles.values.sumOf { it.size }

        fun has(n: Int, e: Int): Boolean = tiles[n]?.contains(e) == true
    }

    val pinned: Edition get() = Edition(AustriaSource.EDITION, AustriaSource.TILES.mapValues { (_, r) -> r.toSet() }, "pinned")

    @Volatile private var current: Pair<Long, Edition>? = null

    /** The default fetch: one GET of the feed with a 30 s timeout and 2 attempts. */
    val fetchFeed: (String) -> ByteArray = { url ->
        SourceIo.get(url, timeoutMs = 30_000, attempts = 2).let { r -> check(r.status == 200) { "HTTP ${r.status}" }; r.body }
    }

    /** The edition to read now: resolved once per [MAX_AGE_MS] per process (feed from [cacheDir] or BEV). */
    fun current(cacheDir: String, fetch: (String) -> ByteArray = fetchFeed): Edition {
        current?.let { (at, e) -> if (currentTimeMillis() - at < MAX_AGE_MS) return e }
        return resolveOnce(cacheDir, fetch)
    }

    @Synchronized
    private fun resolveOnce(cacheDir: String, fetch: (String) -> ByteArray): Edition {
        current?.let { (at, e) -> if (currentTimeMillis() - at < MAX_AGE_MS) return e }
        val e = resolve(cacheDir, fetch)
        current = currentTimeMillis() to e
        return e
    }

    /** [current] without the per-process memo. */
    fun resolve(cacheDir: String, fetch: (String) -> ByteArray): Edition {
        val file = "$cacheDir/atom/service.xml"
        val fresh = SourceFiles.exists(file) && currentTimeMillis() - SourceFiles.lastModifiedMs(file) < MAX_AGE_MS
        var problem: String? = null
        if (!fresh) {
            try {
                val bytes = fetch(FEED)
                val parsed = parse(bytes.decodeToString()) ?: error("no usable DTM edition in the feed")
                SourceFiles.replace(file, bytes)
                return Edition(parsed.id, parsed.tiles, "atom")
            } catch (e: Exception) {
                problem = "${e::class.simpleName}: ${e.message}"
            }
        }
        val cached = SourceFiles.read(file)
        if (cached != null) {
            parse(cached.decodeToString())?.let { return Edition(it.id, it.tiles, if (fresh) "atom" else "atom-cached", problem) }
            problem = (problem?.let { "$it; " } ?: "") + "cached feed has no usable edition"
        }
        val p = pinned
        return Edition(p.id, p.tiles, "pinned", problem)
    }

    private val TITLE = Regex("""<title>ALS DTM CRS3035RES50000mN(\d+)E(\d+) H\S*henraster 1m Stichtag (\d{2})\.(\d{2})\.(\d{4})</title>""")

    /** The newest EVRF2000 edition with at least [MIN_TILES] tiles in a service feed, or null. */
    fun parse(xml: String): Edition? {
        val byEdition = HashMap<String, MutableMap<Int, MutableSet<Int>>>()
        for (m in TITLE.findAll(xml)) {
            val (north, east, dd, mm, yyyy) = m.destructured
            val n = north.toLong()
            val e = east.toLong()
            if (n % AustriaSource.TILE_METERS != 0L || e % AustriaSource.TILE_METERS != 0L) continue
            val id = yyyy + mm + dd
            byEdition.getOrPut(id) { HashMap() }.getOrPut((n / AustriaSource.TILE_METERS).toInt()) { HashSet() } += (e / AustriaSource.TILE_METERS).toInt()
        }
        val best = byEdition.entries
            .filter { (id, tiles) -> id >= FIRST_EVRF && tiles.values.sumOf { it.size } >= MIN_TILES }
            .maxByOrNull { it.key } ?: return null
        return Edition(best.key, best.value.mapValues { it.value.toSet() }, "atom")
    }
}
