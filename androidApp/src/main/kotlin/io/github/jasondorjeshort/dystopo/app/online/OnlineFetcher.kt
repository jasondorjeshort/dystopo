package io.github.jasondorjeshort.dystopo.app.online

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/** One HTTP response: status and body (empty for errors). */
class HttpReply(val status: Int, val body: ByteArray)

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
            return HttpReply(status, body)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * What an online lookup found: [Hit] (bytes, from the disk cache or the network), [Missing] (the server
 * has no such tile: ocean, outside its zooms) or [Unavailable] (network or server failure; try later).
 */
sealed class Fetch {
    class Hit(val bytes: ByteArray, val cached: Boolean) : Fetch()
    object Missing : Fetch()
    class Unavailable(val reason: String) : Fetch()
}

/**
 * Fetches tiles from one Dystopo server (`devtool serve --live` or anything with the same routes)
 * through a [TileCache], so a tile is downloaded once and served from disk afterwards, also offline.
 *
 * - Short timeouts and at most [maxInFlight] requests at once; concurrent requests for one tile share a fetch.
 * - A negative cache: a 404 is remembered for [missingTtlMs], any other failure of one tile for
 *   [failureTtlMs]. When the server cannot be reached at all every lookup fails fast for a backoff
 *   period (5 s doubling to 60 s), so a dead server or no network does not stall each tile on a timeout.
 * - [state] says online / offline for the indicator; [generation] counts offline-to-online transitions
 *   (the map refreshes then, so tiles left blank while offline load).
 */
class OnlineFetcher(
    val server: String,
    private val cache: TileCache,
    private val http: HttpGet = UrlConnectionGet(),
    private val maxInFlight: Int = 6,
    private val missingTtlMs: Long = 10 * 60_000L,
    private val failureTtlMs: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Cache key prefix for this server: its address with path separators flattened. */
    val serverKey: String = keyFor(server)

    private val permits = Semaphore(maxInFlight)
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Fetch>>()
    private val missingUntil = ConcurrentHashMap<String, Long>()
    private val failedUntil = ConcurrentHashMap<String, Long>()

    @Volatile private var downUntil = 0L
    @Volatile private var backoffMs = 0L
    @Volatile var lastOkAt = 0L
        private set
    @Volatile var lastFailAt = 0L
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var reachable: Boolean? = null

    /** Offline-to-online transitions so far. */
    @Volatile var generation = 0
        private set

    val requests = AtomicLong()
    val cacheHits = AtomicLong()
    val fetches = AtomicLong()
    val fetchedBytes = AtomicLong()
    val notFound = AtomicLong()
    val errors = AtomicLong()
    val negativeHits = AtomicLong()
    val backoffSkips = AtomicLong()
    val sharedFetches = AtomicLong()
    private val fetchMs = AtomicLong()
    private val maxFetchMs = AtomicLong()
    private val recentErrors = ArrayDeque<String>()

    /** `online`, `offline` (the server could not be reached recently) or `unknown` (nothing tried yet). */
    val state: String
        get() = when (reachable) {
            null -> "unknown"
            true -> "online"
            false -> "offline"
        }

    fun urlFor(route: String, path: String) = "${server.trimEnd('/')}/$route/$path"

    /**
     * The tile at `<server>/<route>/<path>` (e.g. `dem`, `12/654/1431.png`): from the disk cache, else
     * fetched (unless the negative cache or the backoff says not to try now) and cached.
     */
    fun get(route: String, path: String, transform: (ByteArray) -> ByteArray = { it }): Fetch {
        requests.incrementAndGet()
        val key = "$serverKey/$route/$path"
        cache.get(key)?.let {
            cacheHits.incrementAndGet()
            return Fetch.Hit(it, cached = true)
        }
        val now = clock()
        missingUntil[key]?.let { if (now < it) { negativeHits.incrementAndGet(); return Fetch.Missing } else missingUntil.remove(key) }
        failedUntil[key]?.let { if (now < it) { negativeHits.incrementAndGet(); return Fetch.Unavailable("failed recently") } else failedUntil.remove(key) }
        if (now < downUntil) {
            backoffSkips.incrementAndGet()
            return Fetch.Unavailable("server unreachable (retry in ${(downUntil - now) / 1000 + 1} s): $lastError")
        }
        val mine = CompletableFuture<Fetch>()
        val running = inFlight.putIfAbsent(key, mine)
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
            val result = fetch(key, urlFor(route, path), transform)
            mine.complete(result)
            return result
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private fun fetch(key: String, url: String, transform: (ByteArray) -> ByteArray): Fetch {
        permits.acquire()
        val started = System.nanoTime()
        try {
            // Another thread may have gone offline while this one waited for a permit.
            if (clock() < downUntil) {
                backoffSkips.incrementAndGet()
                return Fetch.Unavailable("server unreachable: $lastError")
            }
            val reply = http.get(url)
            noteReachable()
            return when (reply.status) {
                in 200..299 -> {
                    fetches.incrementAndGet()
                    fetchedBytes.addAndGet(reply.body.size.toLong())
                    val bytes = transform(reply.body)
                    cache.put(key, bytes)
                    Fetch.Hit(bytes, cached = false)
                }
                404, 410 -> {
                    notFound.incrementAndGet()
                    missingUntil[key] = clock() + missingTtlMs
                    Fetch.Missing
                }
                else -> {
                    noteError("HTTP ${reply.status} for $url")
                    failedUntil[key] = clock() + failureTtlMs
                    Fetch.Unavailable("HTTP ${reply.status}")
                }
            }
        } catch (e: ConnectFailed) {
            noteUnreachable(e.message ?: "connect failed")
            return Fetch.Unavailable("server unreachable: ${e.message}")
        } catch (e: IOException) {
            // Connected, then the read failed or timed out: this tile is the problem, not the whole server.
            noteError("${e.javaClass.simpleName}: ${e.message} for $url")
            failedUntil[key] = clock() + failureTtlMs
            return Fetch.Unavailable(e.message ?: e.javaClass.simpleName)
        } finally {
            val ms = (System.nanoTime() - started) / 1_000_000
            fetchMs.addAndGet(ms)
            maxFetchMs.accumulateAndGet(ms, ::maxOf)
            permits.release()
        }
    }

    /** A cheap request that only checks the server answers (`/live/info`); updates [state]. Returns the body or null. */
    fun probe(path: String = "live/info"): ByteArray? {
        // No backoff check: the probe is what notices the network coming back while the map is quiet.
        return try {
            val reply = http.get("${server.trimEnd('/')}/$path")
            noteReachable()
            reply.body.takeIf { reply.status in 200..299 }
        } catch (e: IOException) {
            // `/live/info` is instant on a working server: any failure (refused, timeout) means unreachable.
            noteUnreachable("probe: ${e.javaClass.simpleName}: ${e.message}")
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
        val n = sweep(missingUntil, missingTtlMs) + sweep(failedUntil, failureTtlMs)
        negativeRetried.addAndGet(n.toLong())
        return n
    }

    val negativeRetried = AtomicLong()

    /** Lets the next lookup try the network at once (connectivity came back). */
    fun retryNow() {
        downUntil = 0L
        failedUntil.clear()
    }

    private fun noteReachable() {
        lastOkAt = clock()
        backoffMs = 0L
        downUntil = 0L
        if (reachable == false) {
            generation++
            failedUntil.clear()
        }
        reachable = true
    }

    private fun noteUnreachable(message: String) {
        // Several requests failing together count once: the backoff grows per attempt, not per thread.
        val now = clock()
        if (now >= downUntil) {
            backoffMs = if (backoffMs == 0L) 5_000L else minOf(backoffMs * 2, 60_000L)
            downUntil = now + backoffMs
        }
        reachable = false
        noteError("unreachable: $message")
    }

    private fun noteError(message: String) {
        errors.incrementAndGet()
        lastFailAt = clock()
        lastError = message
        synchronized(recentErrors) {
            recentErrors.addLast(message)
            while (recentErrors.size > 8) recentErrors.removeFirst()
        }
    }

    fun json(): Map<String, Any?> {
        val now = clock()
        val n = fetches.get() + notFound.get()
        return linkedMapOf(
            "server" to server, "state" to state, "generation" to generation,
            "requests" to requests.get(), "cacheHits" to cacheHits.get(), "fetches" to fetches.get(),
            "fetchedBytes" to fetchedBytes.get(), "notFound" to notFound.get(), "errors" to errors.get(),
            "negativeCacheHits" to negativeHits.get(), "negativeRetried" to negativeRetried.get(), "backoffSkips" to backoffSkips.get(), "sharedFetches" to sharedFetches.get(),
            "inFlight" to inFlight.size, "maxInFlight" to maxInFlight,
            "fetchMsAvg" to if (n == 0L) null else fetchMs.get() / n, "fetchMsMax" to maxFetchMs.get(),
            "negativeCache" to mapOf("missing" to missingUntil.count { it.value > now }, "failed" to failedUntil.count { it.value > now }),
            "backoffMs" to backoffMs, "retryInMs" to (downUntil - now).coerceAtLeast(0),
            "msSinceOk" to if (lastOkAt == 0L) null else now - lastOkAt,
            "msSinceFailure" to if (lastFailAt == 0L) null else now - lastFailAt,
            "lastError" to lastError,
            "recentErrors" to synchronized(recentErrors) { recentErrors.toList() },
        )
    }

    companion object {
        /** Negative entries at least this old are retried when the view changes (T41). */
        const val NEGATIVE_RETRY_AGE_MS = 15_000L

        /** A path-safe cache directory name for [server]: `http://10.0.2.2:8765` becomes `10.0.2.2_8765`. */
        fun keyFor(server: String): String =
            server.substringAfter("://").trimEnd('/').map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }.joinToString("")
                .trim('.').ifEmpty { "server" }.take(80)

        /** Undoes gzip if [bytes] start with the gzip magic (vector tiles may arrive compressed). */
        fun gunzipIfNeeded(bytes: ByteArray): ByteArray =
            if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
    }
}
