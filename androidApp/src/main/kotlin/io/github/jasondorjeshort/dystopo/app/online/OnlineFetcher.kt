package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.areas.AreaPins
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/** One HTTP response: status, body (empty for errors) and (T74) the response headers the fetcher reads, lower-case names. */
class HttpReply(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

/** The server could not be reached at all (no network, refused, connect timeout): the whole server backs off. */
class ConnectFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A GET with short timeouts. Throws [ConnectFailed] when no connection was made, any other IOException after. */
fun interface HttpGet {
    fun get(url: String): HttpReply
}

/** [HttpGet] over [HttpURLConnection]. */
class UrlConnectionGet(private val connectTimeoutMs: Int = 3_000, private val readTimeoutMs: Int = 25_000) : HttpGet {
    override fun get(url: String): HttpReply {
        val conn = io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy.open(url) // T79: plain http only to home-network hosts
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        try {
            try {
                conn.connect()
            } catch (e: IOException) {
                throw ConnectFailed("${e.javaClass.simpleName}: ${e.message}", e)
            }
            val status = conn.responseCode
            val body = if (status in 200..299) conn.inputStream.use { it.readBytes() } else ByteArray(0)
            val headers = conn.getHeaderField(OnlineFetcher.VERSION_HEADER)?.let { mapOf(OnlineFetcher.VERSION_HEADER.lowercase() to it) } ?: emptyMap()
            return HttpReply(status, body, headers)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * T95: what the fetcher asks about pinned areas of interest (on the phone [io.github.jasondorjeshort.dystopo.app.areas.Areas]):
 * their covers with the content versions their tiles were fetched at ([AreaPins.versions], [AreaPins.holds]).
 */
interface VersionPins {
    fun pins(): AreaPins

    /** [source]'s version of content [name] moves on from [old] (a known version): pins that never recorded one for it take [old]. */
    fun adopt(source: String, name: String, old: String) {}

    object None : VersionPins {
        override fun pins() = AreaPins.NONE
    }
}

/**
 * What an online lookup found: [Hit] (bytes, from the disk cache or the network), [Missing] (the server
 * has no such tile: ocean, outside its zooms) or [Unavailable] (network or server failure; try later).
 */
sealed class Fetch {
    /** T78: [source] is the cache key of the source that answered (a server, the phone's readers, or a peer phone). */
    class Hit(val bytes: ByteArray, val cached: Boolean, val source: String? = null) : Fetch()
    object Missing : Fetch()
    class Unavailable(val reason: String) : Fetch()
}

/**
 * Fetches tiles from a list of sources (T74: Dystopo servers, `devtool serve --live` or anything with the same
 * routes; the first is the primary, and T78 adds peer phones) through a [TileCache], so a tile is downloaded once
 * and served from disk afterwards, also offline. A tile comes from the first source that has it: the cache of each
 * source in order, then each source over the network in order (one that is backing off or remembers a 404 for the
 * tile is skipped).
 *
 * - Short timeouts and at most [maxInFlight] requests at once; concurrent requests for one tile share a fetch.
 * - A negative cache per source: a 404 is remembered for [missingTtlMs], any other failure of one tile for
 *   [failureTtlMs]. When a source cannot be reached at all every lookup skips it for a backoff period
 *   (5 s doubling to 60 s), so a dead server or no network does not stall each tile on a timeout.
 * - T74, content versions: a source's `GET /version` names what each route's tiles depend on (`dem`, `osm`,
 *   `assets`); cache keys carry it (`<source>/dem@<version>/z/x/y.png`), so a server whose DEM changed is
 *   seen at the next check (at most every [versionCheckMs], on a tile request or the probe) or at once from a
 *   fetched tile's `X-Dystopo-Version` header, and its old tiles are dropped while pack-derived caches are
 *   untouched. A source without `/version` (an older server, the phone's own readers) keeps unversioned keys.
 *   Known versions are kept in [versionFile], so cached tiles are found again offline after a restart.
 * - T74: an empty 200 for a glyph range is a miss, not cached (MapLibre fails an empty range).
 * - [state] says online / offline for the indicator; [generation] counts offline-to-online transitions
 *   (the map refreshes then, so tiles left blank while offline load).
 * - T78, peer phones (`peer://<host:port>`, [isPeer]): over the network they are asked before the other sources (a
 *   phone on the same Wi-Fi answers from its packs in milliseconds and costs no data), and each source counts the
 *   tiles and bytes it delivered (`sources[].fetched`, `.bytes` in [json]), so a download says where its bytes came from.
 * - T95, pinned areas and versions ([pins]): a pinned area records the content versions its tiles were fetched at. A
 *   version change keeps the `dem` and `osm` tiles a pin holds at the old version (the tiles made on the phone from
 *   the old DEM still go: they are made again from the kept DEM), and a lookup that misses the current version tries
 *   the versions the pins hold before the network, so a pinned area is served at its own version, online or not,
 *   until it is refreshed: a download of the area or the prefetch ahead on the route asks with `fresh` (the current
 *   version only, else the network), and [dropUnheld] then deletes what no pin holds any more.
 */
class OnlineFetcher(
    val sources: List<String>,
    private val cache: TileCache,
    private val http: HttpGet = UrlConnectionGet(),
    private val maxInFlight: Int = 6,
    private val missingTtlMs: Long = 10 * 60_000L,
    private val failureTtlMs: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val versionFile: File? = null,
    private val versionCheckMs: Long = VERSION_CHECK_MS,
) {
    constructor(
        server: String, cache: TileCache, http: HttpGet = UrlConnectionGet(), maxInFlight: Int = 6,
        missingTtlMs: Long = 10 * 60_000L, failureTtlMs: Long = 30_000L, clock: () -> Long = System::currentTimeMillis,
        versionFile: File? = null, versionCheckMs: Long = VERSION_CHECK_MS,
    ) : this(listOf(server), cache, http, maxInFlight, missingTtlMs, failureTtlMs, clock, versionFile, versionCheckMs)

    init {
        require(sources.isNotEmpty()) { "at least one source" }
    }

    /** Called (on the thread that noticed it) when a source's content version changed: the map should reload. */
    @Volatile var onVersionChange: ((String) -> Unit)? = null

    /** T95: the pinned areas and the versions they hold (the phone's areas of interest by default; tests set their own). */
    @Volatile var pins: VersionPins = io.github.jasondorjeshort.dystopo.app.areas.Areas

    /** One source: its address, cache key, content versions, reachability and negative cache. */
    inner class Source(val url: String) {
        val key: String = keyFor(url)
        val versions = ConcurrentHashMap<String, String>()
        val missingUntil = ConcurrentHashMap<String, Long>()
        val failedUntil = ConcurrentHashMap<String, Long>()
        @Volatile var downUntil = 0L
        @Volatile var backoffMs = 0L
        @Volatile var reachable: Boolean? = null
        @Volatile var lastOkAt = 0L
        @Volatile var lastFailAt = 0L
        @Volatile var generation = 0
        @Volatile var versionCheckedAt = 0L
        /** False once `/version` answered 404 (asked again every [VERSION_UNSUPPORTED_RECHECK_MS]). */
        @Volatile var versionsSupported: Boolean? = null
        val versionChecking = AtomicBoolean()
        val versionChanges = AtomicLong()
        /** T78: tiles fetched from this source over the network, their bytes, and cache hits under its key. */
        val fetched = AtomicLong()
        val bytes = AtomicLong()
        val cacheHits = AtomicLong()
        val peer: Boolean get() = isPeer(url)

        fun base() = url.trimEnd('/')

        /** The cache key prefix of [route] (`<key>/<route>[@<version>]`). */
        fun prefix(route: String): String {
            val v = VERSION_OF[route]?.let { versions[it] }
            return if (v == null) "$key/$route" else "$key/$route@$v"
        }

        /** T95: the cache key prefix of [route] at content version [v] ("" for the keys from before versions were known). */
        fun prefixAt(route: String, v: String): String = if (v.isEmpty()) "$key/$route" else "$key/$route@$v"

        fun json(now: Long): Map<String, Any?> = linkedMapOf(
            "url" to url, "key" to key,
            "state" to when (reachable) { null -> "unknown"; true -> "online"; false -> "offline" },
            "versions" to versions.toSortedMap(), "versionsSupported" to versionsSupported,
            "msSinceVersionCheck" to if (versionCheckedAt == 0L) null else now - versionCheckedAt,
            "versionChanges" to versionChanges.get(),
            "backoffMs" to backoffMs, "retryInMs" to (downUntil - now).coerceAtLeast(0),
            "negativeCache" to mapOf("missing" to missingUntil.count { it.value > now }, "failed" to failedUntil.count { it.value > now }),
            "peer" to peer, "fetched" to fetched.get(), "bytes" to bytes.get(), "cacheHits" to cacheHits.get(), // T78
        )
    }

    val sourceList: List<Source> = sources.map { Source(it) }
    private val primary: Source get() = sourceList.first()

    /** T78: the order sources are asked over the network: peer phones first, then the rest in list order. */
    private val networkOrder: List<Source> = sourceList.sortedBy { if (it.peer) 0 else 1 }

    /** The primary source's address (the configured server; [DirectSources.SERVER] for the phone's own readers). */
    val server: String get() = primary.url

    /** Cache key prefix of the primary source: its address with path separators flattened. */
    val serverKey: String get() = primary.key

    private val permits = Semaphore(maxInFlight)
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Fetch>>()

    val lastOkAt: Long get() = sourceList.maxOf { it.lastOkAt }
    val lastFailAt: Long get() = sourceList.maxOf { it.lastFailAt }
    @Volatile var lastError: String? = null
        private set

    /** Offline-to-online transitions so far, over every source. */
    val generation: Int get() = sourceList.sumOf { it.generation }

    val requests = AtomicLong()
    val cacheHits = AtomicLong()
    val fetches = AtomicLong()
    val fetchedBytes = AtomicLong()
    val notFound = AtomicLong()
    val errors = AtomicLong()
    val negativeHits = AtomicLong()
    val backoffSkips = AtomicLong()
    val sharedFetches = AtomicLong()
    val emptyAsMissing = AtomicLong()
    val versionChecks = AtomicLong()
    /** T95: lookups answered at a version a pinned area holds; tiles kept at a version change; unreachable tiles dropped. */
    val pinnedHits = AtomicLong()
    val pinnedKept = AtomicLong()
    val unheldDropped = AtomicLong()
    private val fetchMs = AtomicLong()
    private val maxFetchMs = AtomicLong()
    private val recentErrors = ArrayDeque<String>()

    init {
        loadVersions()
    }

    /** `online` (some source answered), `offline` (none could be reached recently) or `unknown` (nothing tried yet). */
    val state: String
        get() = when {
            sourceList.any { it.reachable == true } -> "online"
            sourceList.all { it.reachable == null } -> "unknown"
            else -> "offline"
        }

    fun urlFor(route: String, path: String) = "${primary.base()}/$route/$path"

    /** The content version [name] (`dem`, `osm`, `assets`) of the primary source, when known. */
    fun version(name: String): String? = primary.versions[name]

    /** A short tag of the primary source's content versions, for URLs that must change with them (MapLibre's own cache). */
    val contentTag: String
        get() = primary.versions.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }.let { if (it.isEmpty()) "" else Integer.toHexString(it.hashCode()) }

    /** The cache key prefix for tiles made on the phone from the primary source's DEM (`<key>/<derived>[@<dem version>]`). */
    fun derivedPrefix(derived: String): String {
        maybeCheckVersions(primary) // so a tile made before the first tile fetch is not keyed without the version
        return primary.versions["dem"]?.let { "${primary.key}/$derived@$it" } ?: "${primary.key}/$derived"
    }

    /**
     * The tile at `<source>/<route>/<path>` (e.g. `dem`, `12/654/1431.png`): from the disk cache, else
     * fetched (unless the negative cache or the backoff says not to try now) and cached.
     *
     * T95: a miss at the current version tries the versions pinned areas hold before the network, unless [fresh]
     * (a download or the prefetch refreshing an area: the current version or nothing).
     */
    fun get(route: String, path: String, transform: (ByteArray) -> ByteArray = { it }, fresh: Boolean = false): Fetch {
        requests.incrementAndGet()
        for (s in sourceList) maybeCheckVersions(s)
        for (s in sourceList) {
            cache.get("${s.prefix(route)}/$path")?.let {
                if (!(it.isEmpty() && route in EMPTY_IS_MISS)) {
                    cacheHits.incrementAndGet()
                    s.cacheHits.incrementAndGet()
                    return Fetch.Hit(it, cached = true, source = s.key)
                }
            }
        }
        if (!fresh) pinnedVersion(route, path)?.let { return it }
        var unavailable: Fetch.Unavailable? = null
        for (s in networkOrder) {
            when (val f = getFrom(s, route, path, transform)) {
                is Fetch.Hit -> return f
                Fetch.Missing -> {}
                is Fetch.Unavailable -> if (unavailable == null) unavailable = f
            }
        }
        return unavailable ?: Fetch.Missing
    }

    private fun getFrom(s: Source, route: String, path: String, transform: (ByteArray) -> ByteArray): Fetch {
        val tile = "$route/$path"
        val now = clock()
        s.missingUntil[tile]?.let { if (now < it) { negativeHits.incrementAndGet(); return Fetch.Missing } else s.missingUntil.remove(tile) }
        s.failedUntil[tile]?.let { if (now < it) { negativeHits.incrementAndGet(); return Fetch.Unavailable("failed recently") } else s.failedUntil.remove(tile) }
        if (now < s.downUntil) {
            backoffSkips.incrementAndGet()
            return Fetch.Unavailable("server unreachable (retry in ${(s.downUntil - now) / 1000 + 1} s): $lastError")
        }
        val flightKey = "${s.key}/$tile"
        val mine = CompletableFuture<Fetch>()
        val running = inFlight.putIfAbsent(flightKey, mine)
        if (running != null) {
            sharedFetches.incrementAndGet()
            return try {
                running.get(60, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                Fetch.Unavailable(e.cause?.message ?: "fetch failed")
            } catch (e: Exception) {
                Fetch.Unavailable(e.message ?: e.javaClass.simpleName)
            }
        }
        try {
            val result = fetch(s, route, path, transform)
            mine.complete(result)
            return result
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(flightKey, mine)
        }
    }

    private fun fetch(s: Source, route: String, path: String, transform: (ByteArray) -> ByteArray): Fetch {
        val tile = "$route/$path"
        val url = "${s.base()}/$route/$path"
        permits.acquire()
        val started = System.nanoTime()
        try {
            // Another thread may have gone offline while this one waited for a permit.
            if (clock() < s.downUntil) {
                backoffSkips.incrementAndGet()
                return Fetch.Unavailable("server unreachable: $lastError")
            }
            val reply = http.get(url)
            noteReachable(s)
            return when (reply.status) {
                in 200..299 -> {
                    fetches.incrementAndGet()
                    fetchedBytes.addAndGet(reply.body.size.toLong())
                    // T74: a fetched tile names its route's content version; a new one drops the source's older tiles.
                    reply.headers[VERSION_HEADER.lowercase()]?.let { v -> VERSION_OF[route]?.let { name -> applyVersions(s, mapOf(name to v)) } }
                    if (reply.body.isEmpty() && route in EMPTY_IS_MISS) {
                        // T74: an empty 200 for a glyph range is a broken answer (MapLibre fails it), not a range: a miss, not cached.
                        emptyAsMissing.incrementAndGet()
                        s.missingUntil[tile] = clock() + missingTtlMs
                        return Fetch.Missing
                    }
                    val bytes = transform(reply.body)
                    cache.put("${s.prefix(route)}/$path", bytes)
                    s.fetched.incrementAndGet()
                    s.bytes.addAndGet(reply.body.size.toLong())
                    Fetch.Hit(bytes, cached = false, source = s.key)
                }
                404, 410 -> {
                    notFound.incrementAndGet()
                    s.missingUntil[tile] = clock() + missingTtlMs
                    Fetch.Missing
                }
                else -> {
                    noteError(s, "HTTP ${reply.status} for $url")
                    s.failedUntil[tile] = clock() + failureTtlMs
                    Fetch.Unavailable("HTTP ${reply.status}")
                }
            }
        } catch (e: ConnectFailed) {
            noteUnreachable(s, e.message ?: "connect failed")
            return Fetch.Unavailable("server unreachable: ${e.message}")
        } catch (e: IOException) {
            // Connected, then the read failed or timed out: this tile is the problem, not the whole server.
            noteError(s, "${e.javaClass.simpleName}: ${e.message} for $url")
            s.failedUntil[tile] = clock() + failureTtlMs
            return Fetch.Unavailable(e.message ?: e.javaClass.simpleName)
        } finally {
            val ms = (System.nanoTime() - started) / 1_000_000
            fetchMs.addAndGet(ms)
            maxFetchMs.accumulateAndGet(ms, ::maxOf)
            permits.release()
        }
    }

    /** T95: tile [path] of [route] under a version a pinned area holds (not the current one), or null. */
    private fun pinnedVersion(route: String, path: String): Fetch.Hit? {
        val name = VERSION_OF[route] ?: return null
        val p = pins.pins()
        if (p.isEmpty) return null
        for (s in sourceList) for (v in p.versions(s.key, name)) {
            if (v == (s.versions[name] ?: "")) continue
            val key = "${s.prefixAt(route, v)}/$path"
            if (!cache.contains(key)) continue
            val bytes = cache.get(key) ?: continue
            if (bytes.isEmpty() && route in EMPTY_IS_MISS) continue
            cacheHits.incrementAndGet()
            pinnedHits.incrementAndGet()
            s.cacheHits.incrementAndGet()
            return Fetch.Hit(bytes, cached = true, source = s.key)
        }
        return null
    }

    /** T95: every source's content versions of the tile routes (`dem`, `osm`) now, "" where it has none: what a pinned area records. */
    fun currentVersions(): Map<String, Map<String, String>> =
        sourceList.associate { s -> s.key to PINNED_NAMES.associateWith { s.versions[it] ?: "" } }

    /**
     * T95: deletes the cached tiles no lookup reaches any more: a source's `dem` and `osm` tiles of a version that is
     * neither its current one nor one a pin holds for the tile (an area refreshed, unpinned or removed), and tiles
     * made on the phone from a DEM version that is not current. Sources without versions are left alone. Returns how
     * many went.
     */
    fun dropUnheld(): Int {
        val p = pins.pins()
        var n = 0
        for (s in sourceList) {
            val current = HashMap(s.versions)
            if (current.isEmpty()) continue
            n += cache.clearWhere { k ->
                val parts = k.split('/', limit = 3)
                if (parts.size < 3 || parts[0] != s.key) return@clearWhere false
                val route = parts[1].substringBefore('@')
                val v = parts[1].substringAfter('@', "")
                val derived = DERIVED_FROM_DEM.any { route.startsWith(it) }
                val name = if (derived) "dem" else VERSION_OF[route]?.takeIf { it in PINNED_NAMES } ?: return@clearWhere false
                val cur = current[name] ?: return@clearWhere false
                when {
                    v == cur -> false
                    derived -> true
                    else -> AreaPins.tileOf(k)?.let { (z, x, y) -> !p.holds(s.key, name, v, z, x, y) } ?: true
                }
            }
        }
        unheldDropped.addAndGet(n.toLong())
        return n
    }

    /**
     * T95: deletes the tiles made on the phone (contours, shade) at each source's current DEM version whose tile
     * [inCover] holds: after an area was refreshed they may have been made from the older DEM a pin served.
     */
    fun dropDerived(inCover: (Int, Int, Int) -> Boolean): Int = sourceList.sumOf { s ->
        val cur = s.versions["dem"] ?: ""
        cache.clearWhere { k ->
            val parts = k.split('/', limit = 3)
            if (parts.size < 3 || parts[0] != s.key) return@clearWhere false
            val route = parts[1].substringBefore('@')
            DERIVED_FROM_DEM.any { route.startsWith(it) } && parts[1].substringAfter('@', "") == cur &&
                AreaPins.tileOf(k)?.let { (z, x, y) -> inCover(z, x, y) } == true
        }
    }

    // ---- T74: content versions ----

    /** Asks [s] for `/version` when the last answer is older than [versionCheckMs] (one thread at a time; others go on). */
    private fun maybeCheckVersions(s: Source, force: Boolean = false) {
        val every = if (s.versionsSupported == false) VERSION_UNSUPPORTED_RECHECK_MS else versionCheckMs
        if (!force && clock() - s.versionCheckedAt < every) return
        if (clock() < s.downUntil) return
        if (!s.versionChecking.compareAndSet(false, true)) return
        try {
            if (!force && clock() - s.versionCheckedAt < every) return
            s.versionCheckedAt = clock()
            versionChecks.incrementAndGet()
            val reply = http.get("${s.base()}/version")
            noteReachable(s)
            when (reply.status) {
                200 -> {
                    s.versionsSupported = true
                    val m = runCatching { MiniJson.parseObject(reply.body.decodeToString()) }.getOrNull() ?: return
                    applyVersions(s, m.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())
                }
                404, 410 -> s.versionsSupported = false
            }
        } catch (e: ConnectFailed) {
            noteUnreachable(s, "version: ${e.message}")
        } catch (_: IOException) {
            // A slow answer: keep the versions we have and ask again at the next interval.
        } finally {
            s.versionChecking.set(false)
        }
    }

    /** Asks every source for its versions now (the probe; `POST /online/retry`). */
    fun checkVersions() = sourceList.forEach { maybeCheckVersions(it, force = true) }

    /** Takes [found] versions of [s]; a changed one drops the source's tiles of the old version (and, for `dem`, what was derived from them). */
    private fun applyVersions(s: Source, found: Map<String, String>) {
        var changed = false
        synchronized(s) {
            for ((name, v) in found) {
                if (name !in VERSION_NAMES || !SAFE_VERSION.matches(v)) continue
                val old = s.versions.put(name, v)
                if (old == v) continue
                changed = true
                if (old != null) s.versionChanges.incrementAndGet()
                // T95: before the drop, so areas recorded before T95 keep their tiles (a first version learnt adopts nothing).
                if (name in PINNED_NAMES && old != null) runCatching { pins.adopt(s.key, name, old) }
                dropOld(s, name, old)
            }
        }
        if (changed) {
            saveVersions()
            onVersionChange?.invoke(s.url)
        }
    }

    /**
     * Deletes [s]'s cached tiles of content [name] at version [old] (null: the keys from before versions were
     * known): `<key>/<route>@<old>/...` for the routes that depend on it, and for `dem` also the tiles derived
     * on the phone (`<key>/contours-...@<old>/`, `<key>/shade-...@<old>/`). T95: except the fetched tiles a pinned
     * area holds at [old] ([AreaPins.holds]), which lookups find through the pins' versions until it is refreshed.
     */
    private fun dropOld(s: Source, name: String, old: String?) {
        val routes = VERSION_OF.filterValues { it == name }.keys
        val p = runCatching { pins.pins() }.getOrDefault(AreaPins.NONE)
        val oldV = old ?: ""
        var kept = 0L
        cache.clearWhere { k ->
            val parts = k.split('/', limit = 3)
            if (parts.size < 3 || parts[0] != s.key) return@clearWhere false
            val seg = parts[1]
            val route = seg.substringBefore('@')
            val fetched = route in routes
            val depends = fetched || (name == "dem" && DERIVED_FROM_DEM.any { route.startsWith(it) })
            if (!depends || seg.substringAfter('@', "") != oldV) return@clearWhere false
            if (fetched && AreaPins.tileOf(k)?.let { (z, x, y) -> p.holds(s.key, name, oldV, z, x, y) } == true) {
                kept++
                return@clearWhere false
            }
            true
        }
        pinnedKept.addAndGet(kept)
    }

    private fun loadVersions() {
        val f = versionFile ?: return
        val all = runCatching { MiniJson.parseObject(f.readText()) }.getOrNull() ?: return
        for (s in sourceList) {
            (all[s.key] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String && k in VERSION_NAMES && SAFE_VERSION.matches(v)) s.versions[k] = v }
        }
    }

    @Synchronized
    private fun saveVersions() {
        val f = versionFile ?: return
        try {
            val all = HashMap<String, Any?>(runCatching { MiniJson.parseObject(f.readText()) }.getOrNull().orEmpty())
            for (s in sourceList) all[s.key] = s.versions.toSortedMap()
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(MiniJson.encode(all))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) {
            // Only a convenience for offline restarts.
        }
    }

    /** A cheap request that only checks the primary source answers (`/live/info`); updates [state]. Returns the body or null. */
    fun probe(path: String = "live/info"): ByteArray? {
        val s = primary
        // No backoff check: the probe is what notices the network coming back while the map is quiet.
        return try {
            val reply = http.get("${s.base()}/$path")
            noteReachable(s)
            reply.body.takeIf { reply.status in 200..299 }
        } catch (e: IOException) {
            // `/live/info` is instant on a working server: any failure (refused, timeout) means unreachable.
            noteUnreachable(s, "probe: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * T41: forgets the negative cache entries (404s and failed tiles) at least [minAgeMs] old, so a view the
     * map moves to asks the server again instead of staying blank for up to ten minutes. Younger entries
     * stay, so the tiles of one view are not re-requested on every small pan. Returns how many went.
     */
    fun retryNegative(minAgeMs: Long = NEGATIVE_RETRY_AGE_MS): Int {
        val now = clock()
        fun sweep(map: ConcurrentHashMap<String, Long>, ttl: Long): Int {
            var n = 0
            val it = map.entries.iterator()
            while (it.hasNext()) {
                val until = it.next().value
                if (until <= now || now - (until - ttl) >= minAgeMs) { it.remove(); n++ }
            }
            return n
        }
        val n = sourceList.sumOf { sweep(it.missingUntil, missingTtlMs) + sweep(it.failedUntil, failureTtlMs) }
        negativeRetried.addAndGet(n.toLong())
        return n
    }

    val negativeRetried = AtomicLong()

    /** Lets the next lookup try the network at once (connectivity came back). */
    fun retryNow() {
        for (s in sourceList) {
            s.downUntil = 0L
            s.failedUntil.clear()
        }
    }

    private fun noteReachable(s: Source) {
        s.lastOkAt = clock()
        s.backoffMs = 0L
        s.downUntil = 0L
        if (s.reachable == false) {
            s.generation++
            s.failedUntil.clear()
        }
        s.reachable = true
    }

    private fun noteUnreachable(s: Source, message: String) {
        // Several requests failing together count once: the backoff grows per attempt, not per thread.
        val now = clock()
        if (now >= s.downUntil) {
            s.backoffMs = if (s.backoffMs == 0L) 5_000L else minOf(s.backoffMs * 2, 60_000L)
            s.downUntil = now + s.backoffMs
        }
        s.reachable = false
        noteError(s, "unreachable: $message")
    }

    private fun noteError(s: Source, message: String) {
        errors.incrementAndGet()
        s.lastFailAt = clock()
        lastError = message
        synchronized(recentErrors) {
            recentErrors.addLast(message)
            while (recentErrors.size > 8) recentErrors.removeFirst()
        }
    }

    fun json(): Map<String, Any?> {
        val now = clock()
        val n = fetches.get() + notFound.get()
        val p = primary
        return linkedMapOf(
            "server" to server, "state" to state, "generation" to generation,
            "sources" to sourceList.map { it.json(now) }, // T74
            "versions" to p.versions.toSortedMap(),
            "requests" to requests.get(), "cacheHits" to cacheHits.get(), "fetches" to fetches.get(),
            "fetchedBytes" to fetchedBytes.get(), "notFound" to notFound.get(), "errors" to errors.get(),
            "negativeCacheHits" to negativeHits.get(), "negativeRetried" to negativeRetried.get(), "backoffSkips" to backoffSkips.get(), "sharedFetches" to sharedFetches.get(),
            "emptyAnswersAsMissing" to emptyAsMissing.get(), "versionChecks" to versionChecks.get(), // T74
            "pinnedHits" to pinnedHits.get(), "pinnedKept" to pinnedKept.get(), "unheldDropped" to unheldDropped.get(), // T95
            "inFlight" to inFlight.size, "maxInFlight" to maxInFlight,
            "fetchMsAvg" to if (n == 0L) null else fetchMs.get() / n, "fetchMsMax" to maxFetchMs.get(),
            "negativeCache" to mapOf("missing" to sourceList.sumOf { s -> s.missingUntil.count { it.value > now } }, "failed" to sourceList.sumOf { s -> s.failedUntil.count { it.value > now } }),
            "backoffMs" to p.backoffMs, "retryInMs" to (p.downUntil - now).coerceAtLeast(0),
            "msSinceOk" to if (lastOkAt == 0L) null else now - lastOkAt,
            "msSinceFailure" to if (lastFailAt == 0L) null else now - lastFailAt,
            "lastError" to lastError,
            "recentErrors" to synchronized(recentErrors) { recentErrors.toList() },
        )
    }

    companion object {
        /** Negative entries at least this old are retried when the view changes (T41). */
        const val NEGATIVE_RETRY_AGE_MS = 15_000L

        /** T74: how often a tile request may ask a source for `/version`. */
        const val VERSION_CHECK_MS = 15_000L

        /** T74: a source without `/version` is asked again this rarely. */
        const val VERSION_UNSUPPORTED_RECHECK_MS = 10 * 60_000L

        /** T74: the response header with a tile route's content version (the server's `LiveTiles.VERSION_HEADER`). */
        const val VERSION_HEADER = "X-Dystopo-Version"

        /** T74: which content version a route's tiles depend on. */
        val VERSION_OF = mapOf("dem" to "dem", "osm" to "osm", "glyphs" to "assets")
        private val VERSION_NAMES = setOf("dem", "osm", "assets")

        /** T95: the content versions a pinned area records and keeps (the style assets are bundled in the app). */
        val PINNED_NAMES = listOf("dem", "osm")

        /** T74: cache directories made on the phone from a source's DEM (contours, shade): dropped with its old DEM version. */
        val DERIVED_FROM_DEM = listOf("contours-", "shade-")

        /** Routes where an empty 200 is a broken answer, not content (an empty vector tile is open ocean; an empty glyph range is an error). */
        private val EMPTY_IS_MISS = setOf("glyphs")

        private val SAFE_VERSION = Regex("[A-Za-z0-9._-]{1,64}")

        /** T78: a peer phone's source URL (`peer://<host:port>`, [io.github.jasondorjeshort.dystopo.app.share.PeerSources]). */
        fun isPeer(url: String) = url.startsWith("peer://")

        /** A path-safe cache directory name for [server]: `http://10.0.2.2:8765` becomes `10.0.2.2_8765`. */
        fun keyFor(server: String): String =
            server.substringAfter("://").trimEnd('/').map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }.joinToString("")
                .trim('.').ifEmpty { "server" }.take(80)

        /** Undoes gzip if [bytes] start with the gzip magic (vector tiles may arrive compressed). */
        fun gunzipIfNeeded(bytes: ByteArray): ByteArray =
            if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
    }
}
