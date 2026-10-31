package io.github.jasondorjeshort.dystopo.app.packs

import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.routing.RouteSession
import io.github.jasondorjeshort.dystopo.app.routing.ShippedGraphWait
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * T189 (ADR 0049's B): the routing graphs that come with a pack instead of being built on the phone, as the route
 * planner sees them ([ShippedGraphWait], read through [RouteSession.shippedGraphs]).
 *
 * Three fetch points put a server's graph beside a pack's vector archive: (1) a catalog or area download fetches it
 * after the archives verify ([PackDownloader.startCatalog]), (2) a pack update likewise, and (3) here, when the planner
 * finds a pack's graph missing, stale or refused and a server is set: [awaitShipped] asks the catalog client's cached
 * catalog for a graph matching the archives on the device ([PackCatalogClient.offeredGraph]) and fetches it alone
 * ([PackDownloader.startGraph]) before the planner builds. While a graph download progresses the planner waits; once it
 * stalls past [RouteSession.WAIT_MS] (no new bytes) the planner builds ([WaitRule]). Whatever is not taken (no server,
 * no graph offered, a version or fingerprint mismatch, `hash mismatch`, a stall) is kept as the reason [source] gives
 * with `source: built`; a graph a download installed says `shipped` with the server's `builtAtMs` while the file is
 * unchanged ([PackCatalogClient.shippedGraph]). Never refuses a pack: every failure ends in the phone's own build.
 */
object ShippedGraphs : ShippedGraphWait {
    /** Why the last graph of a pack was not taken from a server (by pack name), for [source]. */
    private val reasons = ConcurrentHashMap<String, String>()

    /** What [awaitShipped] did last per pack (`GET /route/status` through [source]). */
    private val lastWait = ConcurrentHashMap<String, Map<String, Any?>>()

    /** How often the wait looks at the download. */
    const val POLL_MS = 250L

    /**
     * The planner's wait as a pure rule: wait while the download is queued or progressing; build when it ended without
     * a graph or made no progress for [stallMs]; take the graph when it is in place.
     */
    object WaitRule {
        enum class Next { WAIT, TAKE, BUILD }

        /**
         * [status] is the graph file job's (`pending`, `downloading`, `verifying`, `verified`, `done`, `skipped`, `failed`,
         * `missing`), [jobState] the job's (`queued`, `running`, `done`, ...), [sinceProgressMs] the time since its byte
         * count last moved (or since the wait began).
         */
        fun next(status: String?, jobState: String?, sinceProgressMs: Long, stallMs: Long): Next = when {
            status == "done" -> Next.TAKE
            status == null || status in ENDED || jobState in setOf("done", "failed", "cancelled") -> Next.BUILD
            sinceProgressMs >= stallMs -> Next.BUILD
            else -> Next.WAIT
        }

        private val ENDED = setOf("skipped", "failed", "missing")
    }

    override fun awaitShipped(pack: String, vector: File, dem: File?, onProgress: (Map<String, Any?>) -> Unit): Boolean =
        awaitShipped(pack, vector, dem, RouteSession.WAIT_MS, onProgress)

    /** [awaitShipped] with the stall limit [stallMs] (tests). */
    fun awaitShipped(pack: String, vector: File, dem: File?, stallMs: Long, onProgress: (Map<String, Any?>) -> Unit = {}): Boolean {
        val manager = PackManager.get()
        val downloads = manager.downloads
        val target = File(vector.absoluteFile.parentFile, io.github.jasondorjeshort.dystopo.routing.RoutingGraph.fileNameFor(vector.name))
        var job = downloads.job(pack)?.takeIf { (it.state == "queued" || it.state == "running") && it.graph?.target?.absolutePath == target.absolutePath }
        if (job == null) {
            val offer = manager.catalogClient.offeredGraph(pack, vector, dem)
            if (offer.entry == null || offer.url == null) {
                reasons[pack] = offer.reason ?: "no graph offered"
                lastWait[pack] = linkedMapOf("action" to "build", "reason" to reasons[pack])
                return false
            }
            job = try {
                downloads.startGraph(pack, vector, dem, offer.entry, offer.url, linkedMapOf("server" to manager.catalogClient.server, "id" to pack))
            } catch (e: Exception) {
                reasons[pack] = "the download could not start (${e.message})"
                lastWait[pack] = linkedMapOf("action" to "build", "reason" to reasons[pack])
                return false
            }
            // A pack download already running for this pack was returned instead: wait only when it carries this graph.
            if (job.graph?.target?.absolutePath != target.absolutePath) {
                reasons[pack] = "a download of this pack without the graph is running"
                lastWait[pack] = linkedMapOf("action" to "build", "reason" to reasons[pack])
                return false
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        var lastBytes = -1L
        var lastMove = t0
        while (true) {
            val g = job.graph
            val bytes = job.bytesDone
            val now = SystemClock.elapsedRealtime()
            if (bytes != lastBytes) { lastBytes = bytes; lastMove = now }
            when (WaitRule.next(g?.status, job.state, now - lastMove, stallMs)) {
                WaitRule.Next.TAKE -> {
                    reasons.remove(pack)
                    lastWait[pack] = linkedMapOf("action" to "shipped", "waitedMs" to now - t0, "bytes" to g?.total)
                    return target.isFile
                }
                WaitRule.Next.BUILD -> {
                    val why = g?.reason?.let { if (it == "hash mismatch") "shipped: hash mismatch" else it }
                        ?: if (now - lastMove >= stallMs) "the download stalled" else "the download ended without the graph (${job.state}${job.error?.let { ": $it" } ?: ""})"
                    reasons[pack] = why
                    lastWait[pack] = linkedMapOf("action" to "build", "reason" to why, "waitedMs" to now - t0)
                    Log.i(PackManager.TAG, "graph for $pack built on the phone: $why")
                    return false
                }
                WaitRule.Next.WAIT -> onProgress(linkedMapOf("bytesDone" to bytes, "bytesTotal" to job.bytesTotal, "status" to g?.status, "waitedMs" to now - t0))
            }
            Thread.sleep(POLL_MS)
        }
    }

    override fun source(pack: String, graphFile: File): Map<String, Any?>? {
        if (!graphFile.isFile) return null
        val shipped = runCatching { PackManager.get().catalogClient.shippedGraph(pack, graphFile) }.getOrNull()
        return if (shipped != null) {
            linkedMapOf(
                "source" to "shipped", "builtAtMs" to shipped["builtAtMs"], "server" to shipped["server"], "via" to shipped["via"],
                "sha256" to shipped["sha256"], "installedAt" to shipped["installedAt"], "lastWait" to lastWait[pack],
            )
        } else {
            linkedMapOf("source" to "built", "reason" to (reasons[pack] ?: "no graph came with this pack"), "lastWait" to lastWait[pack])
        }
    }

    // ---- T189: a phone's peer catalog (T78) lists its own matching graph ----

    private class PeerEntry(val stamp: String, val entry: io.github.jasondorjeshort.dystopo.packs.DerivedFile?)

    private val peerEntries = ConcurrentHashMap<String, PeerEntry>()

    /** The graph file beside pack [files]' vector archive, or null without a vector archive. */
    fun graphFileOf(files: io.github.jasondorjeshort.dystopo.app.PackFiles): File? =
        files.vector?.let { v -> File(v.absoluteFile.parentFile, io.github.jasondorjeshort.dystopo.routing.RoutingGraph.fileNameFor(v.name)) }

    /**
     * T189: pack [files]' graph as a peer catalog `derived` entry (URL [urlPrefix] + file name), when it opens as a graph
     * this build reads and names the pack's archives' fingerprints; null otherwise. Hashed once per file version.
     */
    fun peerEntry(files: io.github.jasondorjeshort.dystopo.app.PackFiles, urlPrefix: String): io.github.jasondorjeshort.dystopo.packs.DerivedFile? {
        val g = graphFileOf(files)?.takeIf { it.isFile } ?: return null
        val vector = files.vector ?: return null
        val stamp = listOfNotNull(g, vector, files.dem).joinToString("|") { "${it.path}:${it.length()}:${it.lastModified()}" }
        peerEntries[g.path]?.takeIf { it.stamp == stamp }?.let { return it.entry }
        val entry = runCatching {
            val a = io.github.jasondorjeshort.dystopo.app.FileRangeSource(g).use { src -> io.github.jasondorjeshort.dystopo.routing.GraphTileFile.open { o, l -> src.read(o, l) } }
            val fp = PackDownloader.fingerprint(vector)
            val demFp = files.dem?.let { PackDownloader.fingerprint(it) }.orEmpty()
            val shown = io.github.jasondorjeshort.dystopo.packs.DerivedFile.graph(g.name, urlPrefix + g.name, g.length(), "0".repeat(64), a.source.fingerprint, a.source.demFingerprint, tileZoom = a.tileZoom, sidecar = null)
            if (io.github.jasondorjeshort.dystopo.packs.ShippedGraph.mismatch(shown, fp, demFp) != null) null
            else shown.copy(sha256 = PackDownloader.sha256(g))
        }.getOrNull()
        peerEntries[g.path] = PeerEntry(stamp, entry)
        return entry
    }

    /** Forgets what was said of pack [name] (deleted). */
    fun forget(name: String) {
        reasons.remove(name)
        lastWait.remove(name)
    }
}
