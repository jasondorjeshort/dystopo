package io.github.jasondorjeshort.dystopo.sources.elevation

import kotlin.concurrent.Volatile
import kotlin.jvm.Synchronized

/** One HTTP response: status and body. */
class HttpReply(val status: Int, val body: ByteArray)

/**
 * The network under the elevation sources: one attempt per call; [SourceIo] adds the retries, the
 * [SourceIo.beforeNetwork] hook and status checks, so every platform behaves the same.
 */
interface SourceHttp {
    /** Bytes [offset, offset + length) of [url] (fewer at the end of the file); the reply's status is checked by the caller. */
    fun range(url: String, offset: Long, length: Int): HttpReply

    /** GET [url] with a timeout of [timeoutMs]. */
    fun get(url: String, timeoutMs: Long): HttpReply
}

/** An HTTP error status. An [IllegalStateException], as `check` threw before, so existing handlers still match. */
class HttpStatusException(val status: Int, url: String) : IllegalStateException("HTTP $status for $url")

/**
 * Process-wide settings of the elevation sources (T80): where they cache what they download ([cacheRoot],
 * `DYSTOPO_CACHE` or `cache/` by default; the server sets it from its config, the phone to its cache dir),
 * the HTTP client ([http]) and the hook every request passes ([beforeNetwork]).
 */
object SourceIo {
    /** Root of the source caches; [dir] is one source's directory under it. */
    @Volatile var cacheRoot: String = environment("DYSTOPO_CACHE")?.takeIf { it.isNotBlank() } ?: "cache"

    fun dir(name: String): String = "$cacheRoot/$name"

    /** The client the sources fetch with; [defaultSourceHttp] unless a caller installed another. */
    @Volatile var http: SourceHttp = defaultSourceHttp()

    /**
     * Called with the URL before every network request of the sources; `tour --offline-ok` throws there to stay
     * cache-only, tests use it to count or forbid requests (the server's `Http.beforeNetwork` is this hook).
     */
    @Volatile var beforeNetwork: ((String) -> Unit)? = null

    /**
     * T80: open GeoTIFFs kept between windows ([RemoteTiff.open]), and their decoded blocks under one byte cap.
     * Null (the default, the server and devtool as before): every window opens its files afresh and keeps their
     * decoded blocks only while it renders. The phone sets both, so neighbouring tiles of a view decode each COG
     * block once instead of once per tile.
     */
    @Volatile var tiffPool: TiffPool? = null
    @Volatile var decodedBlocks: DecodedBlocks? = null

    private val priority = PerThread<IntArray>()

    /** T88: the priority of the reads this thread fans out ([parallelForEach]); 0 unless set by [withPriority]. */
    fun currentPriority(): Int = priority.get()?.get(0) ?: 0

    /**
     * T88: runs [block] with its parallel block reads queued at priority [p] (higher first; the phone puts a view's
     * coarse fallback tiles above its own and those above out-of-view ones). Reads already running are not affected.
     */
    fun <T> withPriority(p: Int, block: () -> T): T {
        val slot = priority.get() ?: IntArray(1).also { priority.set(it) }
        val before = slot[0]
        slot[0] = p
        try {
            return block()
        } finally {
            slot[0] = before
        }
    }

    /**
     * Runs [block] up to [attempts] times, sleeping [initialDelayMs] and doubling between tries, as long as each
     * failure is [transient]. A non-transient failure, or the last one, is rethrown.
     */
    fun <T> retry(attempts: Int, initialDelayMs: Long, transient: (Exception) -> Boolean, block: () -> T): T {
        require(attempts >= 1)
        var delay = initialDelayMs
        for (attempt in 1..attempts) {
            try {
                return block()
            } catch (e: Exception) {
                if (attempt == attempts || !transient(e)) throw e
                sleepMs(delay)
                delay *= 2
            }
        }
        error("unreachable")
    }

    /**
     * Retries dropped connections (EOF, reset), which happen under many parallel requests to one host. HTTP
     * error statuses are not retried.
     */
    private fun <T> withRetry(attempts: Int = 4, block: () -> T): T = retry(attempts, 300, { isIoException(it) }, block)

    /** A byte range of [url]; a status other than 200 or 206 is an [HttpStatusException]. */
    fun range(url: String, offset: Long, length: Int): ByteArray {
        beforeNetwork?.invoke(url)
        val reply = withRetry { http.range(url, offset, length) }
        if (reply.status != 206 && reply.status != 200) throw HttpStatusException(reply.status, url)
        return reply.body
    }

    /** The whole body of [url]; any status but 200 is an [HttpStatusException]. */
    fun download(url: String): ByteArray {
        beforeNetwork?.invoke(url)
        val reply = withRetry { http.get(url, 120_000) }
        if (reply.status != 200) throw HttpStatusException(reply.status, url)
        return reply.body
    }

    /** GET [url], retrying dropped connections up to [attempts] times; the reply whatever its status. */
    fun get(url: String, timeoutMs: Long = 60_000, attempts: Int = 3): HttpReply {
        beforeNetwork?.invoke(url)
        return retry(attempts, 500, { isIoException(it) }) { http.get(url, timeoutMs) }
    }
}

/** A thread-safe map for the sources' per-process memos (cell indexes, opened geoid grids). */
class SyncMap<K, V> {
    private val map = HashMap<K, V>()

    @Synchronized
    fun getOrPut(key: K, make: () -> V): V = map.getOrPut(key, make)

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    operator fun contains(key: K): Boolean = map.containsKey(key)

    @Synchronized
    fun remove(key: K): V? = map.remove(key)

    val size: Int @Synchronized get() = map.size
}

/** A thread-safe counter. */
class Counter {
    private var n = 0L

    @Synchronized
    fun increment(): Long = ++n

    @Synchronized
    fun add(k: Long): Long {
        n += k
        return n
    }

    fun get(): Long = synchronizedGet()

    @Synchronized
    private fun synchronizedGet(): Long = n
}
