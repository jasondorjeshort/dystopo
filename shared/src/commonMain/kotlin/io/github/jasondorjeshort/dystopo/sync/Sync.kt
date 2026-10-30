package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson

/**
 * How a replica reaches another one (T44). A transport only moves [Bundle]s; [Sync.exchange] does the
 * protocol: pull what the peer has after our vector, merge it, push what the peer's vector lacks.
 */
interface SyncTransport {
    /** For reports: the URL or file. */
    val description: String

    /** The peer's operations after [since], with its vector. */
    fun pull(since: VersionVector): Bundle

    /**
     * One page of the peer's operations after [since] (T51; see [Bundle.more]). A transport (or a
     * peer) that cannot page returns everything, which is a valid last page.
     */
    fun pullPage(since: VersionVector, maxOps: Int, maxBytes: Int): Bundle = pull(since)

    /** Gives [bundle] to the peer; returns the peer's vector afterwards when it says. */
    fun push(bundle: Bundle): VersionVector?

    /** A file keeps the whole log, so a push writes every operation, not only what the peer lacked. */
    val keepsEverything: Boolean get() = false

    /** Bytes received and sent so far (on the wire, gzipped), for reports. */
    val bytesIn: Long get() = 0
    val bytesOut: Long get() = 0
}

data class SyncReport(
    val transport: String,
    val pulled: Int,
    val merged: ApplyResult,
    val pushed: Int,
    val peerVector: VersionVector?,
    val vector: VersionVector,
    val ms: Long,
    /** T51: pages pulled and pushed (1 each for an unpaged sync that had something to push). */
    val pullPages: Int = 1,
    val pushPages: Int = 0,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "transport" to transport, "pulled" to pulled, "merged" to merged.json(), "pushed" to pushed,
        "pullPages" to pullPages, "pushPages" to pushPages, "bytesIn" to bytesIn, "bytesOut" to bytesOut,
        "conflicts" to merged.conflicts.map { it.json() },
        "peerVector" to peerVector?.text, "vector" to vector.text, "ms" to ms,
    )
}

/**
 * T158 (ADR 0033 §3): a sync refused because the two sides run versions that cannot read each other's data, told
 * apart from other failures so the app can say so plainly and count it. A build reads only the operation types,
 * point fields, gap reasons and bundle versions it knows and refuses the whole bundle otherwise (nothing imported),
 * so a refusal is always one of these messages: in a [SyncFormatException] of our own (the peer is newer), or in
 * the error text of a peer that refused what we sent (the peer is older).
 */
object SyncRefusal {
    private val MARKERS = listOf("unknown operation type", "unknown point fields", "unknown point flags", "unknown gap reason", "bundle format version")

    /** The marker found in [text], or null. */
    fun marker(text: String?): String? = text?.let { t -> MARKERS.firstOrNull { it in t } }

    /** A sentence for the user when [e] is a format refusal, else null. */
    fun reason(e: Throwable): String? {
        val chain = generateSequence(e) { it.cause }.take(8).toList()
        val text = chain.mapNotNull { it.message }.joinToString(" ")
        if (marker(text) == null) return null
        val detail = Regex("""unknown operation type \d+|unknown point (fields|flags) [01]+|unknown gap reason "[^"]*"|bundle format version \d+""").find(text)?.value
        val suffix = detail?.let { " ($it)" } ?: ""
        return if (chain.any { it is SyncFormatException }) {
            "The other device runs a newer Dystopo whose tracks this version cannot read$suffix. Update this one; nothing was imported."
        } else {
            "The other device runs an older Dystopo that cannot read this one's tracks$suffix. Update it; nothing was sent."
        }
    }
}

object Sync {
    /** Default page size of a paged sync (T51): operations and raw bytes per page. */
    const val DEFAULT_PAGE_OPS = 2000
    const val DEFAULT_PAGE_BYTES = 1 shl 20

    /** A sync stops after this many pages each way (the next sync carries on). */
    const val MAX_PAGES = 100_000

    /**
     * One sync with a peer: pull, merge, push. [now] is the local physical clock; [elapsed] measures
     * the exchange (pass a monotonic clock; the default reports 0).
     *
     * With [pageOps] > 0 (T51) both directions go in pages of at most [pageOps] operations and about
     * [pageBytes]: pull a page, merge it, pull again from the new vector until the peer says there is
     * no more; then push pages of what the peer's vector lacks. Every page is a complete bundle, so a
     * sync cut off after any page has lost nothing: the next one continues from the vectors.
     */
    fun exchange(
        store: SyncReplica,
        transport: SyncTransport,
        now: () -> Long,
        elapsed: () -> Long = { 0L },
        pageOps: Int = 0,
        pageBytes: Int = DEFAULT_PAGE_BYTES,
    ): SyncReport {
        val started = elapsed()
        var merged = ApplyResult.NONE
        var pulled = 0
        var pullPages = 0
        var last: Bundle
        while (true) {
            val before = store.vector()
            last = if (pageOps > 0) transport.pullPage(before, pageOps, pageBytes) else transport.pull(before)
            pullPages++
            pulled += last.ops.size
            merged += store.apply(last, now())
            if (!last.more || pullPages >= MAX_PAGES) break
            if (store.vector() == before) break // no progress (a gap): the next sync retries
        }
        var pushed = 0
        var pushPages = 0
        var peer: VersionVector? = last.vector
        if (transport.keepsEverything) {
            val out = store.bundle(VersionVector.EMPTY)
            peer = transport.push(out)
            pushed = out.ops.size
            pushPages = 1
        } else {
            var since = last.vector
            while (pushPages < MAX_PAGES) {
                val out = if (pageOps > 0) store.page(since, pageOps, pageBytes) else store.bundle(since)
                if (out.ops.isEmpty()) break
                peer = transport.push(out) ?: out.vector
                pushPages++
                pushed += out.ops.size
                if (!out.more) break
                since = out.vector
            }
        }
        return SyncReport(
            transport.description, pulled, merged, pushed, peer, store.vector(), elapsed() - started,
            pullPages, pushPages, transport.bytesIn, transport.bytesOut,
        )
    }

    /** A bundle as bytes on the wire or in a `.dsync` file: gzip of [Bundle.encode]. */
    fun pack(bundle: Bundle, gzip: (ByteArray) -> ByteArray): ByteArray = gzip(bundle.encode())

    /** Reads a bundle, gzipped or raw. Anything malformed (including a broken gzip stream) is a [SyncFormatException]. */
    fun unpack(bytes: ByteArray, gunzip: (ByteArray) -> ByteArray): Bundle {
        if (Bundle.isRaw(bytes)) return Bundle.decode(bytes)
        val raw = try {
            gunzip(bytes)
        } catch (e: Exception) {
            throw SyncFormatException("not a sync bundle (gzip: ${e::class.simpleName}: ${e.message})")
        }
        return Bundle.decode(raw)
    }
}

/**
 * Sync through a `.dsync` file (for USB, a shared folder, a messenger): the file holds one gzipped
 * bundle. Syncing with it merges what it holds and writes it back with everything this replica holds,
 * so passing one file back and forth between devices keeps them all in step. [read] returns null
 * when the file does not exist yet.
 */
class FileSyncTransport(
    override val description: String,
    private val read: () -> ByteArray?,
    private val write: (ByteArray) -> Unit,
    private val gzip: (ByteArray) -> ByteArray,
    private val gunzip: (ByteArray) -> ByteArray,
) : SyncTransport {
    override val keepsEverything: Boolean get() = true

    override var bytesIn = 0L
        private set
    override var bytesOut = 0L
        private set

    override fun pull(since: VersionVector): Bundle =
        read()?.let { bytesIn += it.size; Sync.unpack(it, gunzip) } ?: Bundle("file", VersionVector.EMPTY, VersionVector.EMPTY, emptyList())

    override fun push(bundle: Bundle): VersionVector {
        val bytes = Sync.pack(bundle, gzip)
        write(bytes)
        bytesOut += bytes.size
        return bundle.vector
    }
}

/**
 * Sync over HTTP with a peer that runs [SyncServer] (the devtool server; later any always-on peer).
 * [post] does a POST and returns the response body, throwing on a non-2xx status.
 */
class HttpSyncTransport(
    private val baseUrl: String,
    private val post: (url: String, body: ByteArray) -> ByteArray,
    private val gzip: (ByteArray) -> ByteArray,
    private val gunzip: (ByteArray) -> ByteArray,
) : SyncTransport {
    override val description: String get() = baseUrl

    override var bytesIn = 0L
        private set
    override var bytesOut = 0L
        private set

    private fun call(url: String, body: ByteArray): ByteArray {
        bytesOut += body.size
        return post(url, body).also { bytesIn += it.size }
    }

    override fun pull(since: VersionVector): Bundle =
        Sync.unpack(call("${baseUrl.trimEnd('/')}/sync/pull?since=${since.text}", ByteArray(0)), gunzip)

    override fun pullPage(since: VersionVector, maxOps: Int, maxBytes: Int): Bundle =
        Sync.unpack(call("${baseUrl.trimEnd('/')}/sync/pull?since=${since.text}&limit=$maxOps&maxBytes=$maxBytes", ByteArray(0)), gunzip)

    override fun push(bundle: Bundle): VersionVector? {
        val reply = call("${baseUrl.trimEnd('/')}/sync/push", Sync.pack(bundle, gzip)).decodeToString()
        return (MiniJson.parseObject(reply)["vector"] as? String)?.let(VersionVector::parse)
    }
}

/**
 * The HTTP side of a sync peer, free of any server library (the devtool server adapts it; a phone
 * could serve it too):
 * - `POST /sync/pull?since=<vector>` (or the vector as the body): a gzipped [Bundle] of everything
 *   after it, with this peer's vector. T51: `&limit=N[&maxBytes=B]` returns one page instead (at
 *   most N operations, about B raw bytes, default [Sync.DEFAULT_PAGE_BYTES]); the client pulls again
 *   from its new vector while [Bundle.more] is set. Without `limit`, the whole bundle as before.
 * - `POST /sync/push` with a gzipped (or raw) bundle: merges it; JSON with the merge counts and the
 *   vector afterwards.
 * - `GET /sync/status`: device id, vector, operation count, synced state summary and digest, the
 *   clock-skew guard's log, request counts; T158: `refused`, pushes refused for a format this build cannot read
 *   (answered 400 with `refused: "format"`, the readable reason in `error` and the parser's message in `detail`).
 * Stores are not thread-safe: the caller runs one call at a time per store.
 */
class SyncServer(
    private val store: SyncReplica,
    private val now: () -> Long,
    private val gzip: (ByteArray) -> ByteArray,
    private val gunzip: (ByteArray) -> ByteArray,
) {
    class Reply(val status: Int, val contentType: String, val body: ByteArray)

    var pulls = 0L
        private set
    var pushes = 0L
        private set
    /** T51: pull requests answered with a page that had more to follow. */
    var partialPages = 0L
        private set
    /** T51: conflicts found in pushes since start. */
    var conflicts = 0L
        private set
    /** T158: pushes refused because their bundle uses a format this build cannot read ([SyncRefusal]), since start. */
    var refused = 0L
        private set

    private fun json(status: Int, m: Map<String, Any?>) = Reply(status, "application/json", (MiniJson.encode(m) + "\n").encodeToByteArray())

    private fun positive(query: Map<String, String>, key: String, max: Int): Int? {
        val raw = query[key] ?: return null
        val v = raw.trim().toLongOrNull() ?: throw IllegalArgumentException("$key must be a positive whole number")
        if (v < 1) throw IllegalArgumentException("$key must be a positive whole number")
        return minOf(v, max.toLong()).toInt()
    }

    /** Null when [path] is not a sync route. */
    fun handle(method: String, path: String, query: Map<String, String>, body: ByteArray): Reply? {
        if (path != "/sync" && !path.startsWith("/sync/")) return null
        return try {
            when (method to path) {
                "POST" to "/sync/pull", "GET" to "/sync/pull" -> {
                    val since = VersionVector.parse(query["since"] ?: body.decodeToString().trim().takeIf { it.isNotEmpty() })
                    val limit = positive(query, "limit", MAX_PAGE_OPS)
                    val bundle = if (limit == null) store.bundle(since) else store.page(since, limit, positive(query, "maxBytes", MAX_PAGE_BYTES) ?: Sync.DEFAULT_PAGE_BYTES)
                    pulls++
                    if (bundle.more) partialPages++
                    Reply(200, "application/octet-stream", Sync.pack(bundle, gzip))
                }
                "POST" to "/sync/push" -> {
                    if (body.isEmpty()) return json(400, mapOf("error" to "POST a bundle (gzipped or raw)"))
                    val r = store.apply(Sync.unpack(body, gunzip), now())
                    pushes++
                    conflicts += r.conflicts.size
                    json(200, r.json() + mapOf("vector" to store.vector().text))
                }
                "GET" to "/sync/status", "GET" to "/sync" -> json(
                    200,
                    status(store) + mapOf("pulls" to pulls, "pushes" to pushes, "partialPages" to partialPages, "conflictsSeen" to conflicts, "refused" to refused),
                )
                else -> json(405, mapOf("error" to "POST /sync/pull?since=[&limit=], POST /sync/push, GET /sync/status"))
            }
        } catch (e: IllegalArgumentException) {
            val reason = SyncRefusal.reason(e)
            if (reason != null) {
                // T158: the readable reason, the raw message for the other side's own SyncRefusal, nothing merged.
                refused++
                json(400, mapOf("error" to reason, "refused" to "format", "detail" to e.message))
            } else {
                json(400, mapOf("error" to (e.message ?: e.toString())))
            }
        }
    }

    companion object {
        /** Caps on what a client may ask for in one page. */
        const val MAX_PAGE_OPS = 100_000
        const val MAX_PAGE_BYTES = 64 shl 20

        /** A replica's state for `/sync/status` routes and reports. */
        fun status(store: SyncReplica): Map<String, Any?> {
            val s = store.snapshot()
            return linkedMapOf(
                "device" to store.deviceId,
                "vector" to store.vector().text,
                "ops" to store.opCount,
                "tracks" to s.tracks.size,
                "points" to s.tracks.sumOf { it.pointCount },
                "waypoints" to s.waypoints.size,
                "routes" to s.routes.size,
                "settings" to s.settings.size,
                "digest" to s.digest(),
                "clockSkew" to store.skewLog.json(),
            )
        }
    }
}
