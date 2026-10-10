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

    /** Gives [bundle] to the peer; returns the peer's vector afterwards when it says. */
    fun push(bundle: Bundle): VersionVector?

    /** A file keeps the whole log, so a push writes every operation, not only what the peer lacked. */
    val keepsEverything: Boolean get() = false
}

data class SyncReport(
    val transport: String,
    val pulled: Int,
    val merged: ApplyResult,
    val pushed: Int,
    val peerVector: VersionVector?,
    val vector: VersionVector,
    val ms: Long,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "transport" to transport, "pulled" to pulled, "merged" to merged.json(), "pushed" to pushed,
        "peerVector" to peerVector?.text, "vector" to vector.text, "ms" to ms,
    )
}

object Sync {
    /**
     * One sync with a peer: pull, merge, push. [now] is the local physical clock; [elapsed] measures
     * the exchange (pass a monotonic clock; the default reports 0).
     */
    fun exchange(store: SyncReplica, transport: SyncTransport, now: () -> Long, elapsed: () -> Long = { 0L }): SyncReport {
        val started = elapsed()
        val pulled = transport.pull(store.vector())
        val merged = store.apply(pulled, now())
        val out = store.bundle(if (transport.keepsEverything) VersionVector.EMPTY else pulled.vector)
        val peer = if (out.ops.isNotEmpty() || transport.keepsEverything) transport.push(out) else pulled.vector
        return SyncReport(transport.description, pulled.ops.size, merged, out.ops.size, peer, store.vector(), elapsed() - started)
    }

    /** A bundle as bytes on the wire or in a `.dsync` file: gzip of [Bundle.encode]. */
    fun pack(bundle: Bundle, gzip: (ByteArray) -> ByteArray): ByteArray = gzip(bundle.encode())

    /** Reads a bundle, gzipped or raw. */
    fun unpack(bytes: ByteArray, gunzip: (ByteArray) -> ByteArray): Bundle =
        Bundle.decode(if (Bundle.isRaw(bytes)) bytes else gunzip(bytes))
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

    override fun pull(since: VersionVector): Bundle =
        read()?.let { Sync.unpack(it, gunzip) } ?: Bundle("file", VersionVector.EMPTY, VersionVector.EMPTY, emptyList())

    override fun push(bundle: Bundle): VersionVector {
        write(Sync.pack(bundle, gzip))
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

    override fun pull(since: VersionVector): Bundle =
        Sync.unpack(post("${baseUrl.trimEnd('/')}/sync/pull?since=${since.text}", ByteArray(0)), gunzip)

    override fun push(bundle: Bundle): VersionVector? {
        val reply = post("${baseUrl.trimEnd('/')}/sync/push", Sync.pack(bundle, gzip)).decodeToString()
        return (MiniJson.parseObject(reply)["vector"] as? String)?.let(VersionVector::parse)
    }
}

/**
 * The HTTP side of a sync peer, free of any server library (the devtool server adapts it; a phone
 * could serve it too):
 * - `POST /sync/pull?since=<vector>` (or the vector as the body): a gzipped [Bundle] of everything
 *   after it, with this peer's vector.
 * - `POST /sync/push` with a gzipped (or raw) bundle: merges it; JSON with the merge counts and the
 *   vector afterwards.
 * - `GET /sync/status`: device id, vector, operation count, synced state summary and digest.
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

    private fun json(status: Int, m: Map<String, Any?>) = Reply(status, "application/json", (MiniJson.encode(m) + "\n").encodeToByteArray())

    /** Null when [path] is not a sync route. */
    fun handle(method: String, path: String, query: Map<String, String>, body: ByteArray): Reply? {
        if (path != "/sync" && !path.startsWith("/sync/")) return null
        return try {
            when (method to path) {
                "POST" to "/sync/pull", "GET" to "/sync/pull" -> {
                    val since = VersionVector.parse(query["since"] ?: body.decodeToString().trim().takeIf { it.isNotEmpty() })
                    pulls++
                    Reply(200, "application/octet-stream", Sync.pack(store.bundle(since), gzip))
                }
                "POST" to "/sync/push" -> {
                    if (body.isEmpty()) return json(400, mapOf("error" to "POST a bundle (gzipped or raw)"))
                    val r = store.apply(Sync.unpack(body, gunzip), now())
                    pushes++
                    json(200, r.json() + mapOf("vector" to store.vector().text))
                }
                "GET" to "/sync/status", "GET" to "/sync" -> json(200, status(store) + mapOf("pulls" to pulls, "pushes" to pushes))
                else -> json(405, mapOf("error" to "POST /sync/pull?since=, POST /sync/push, GET /sync/status"))
            }
        } catch (e: IllegalArgumentException) {
            json(400, mapOf("error" to (e.message ?: e.toString())))
        }
    }

    companion object {
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
            )
        }
    }
}
