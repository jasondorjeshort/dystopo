package io.github.jasondorjeshort.dystopo.app.packs

import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.FileRangeSource
import io.github.jasondorjeshort.dystopo.app.gunzip
import io.github.jasondorjeshort.dystopo.packs.DerivedFile
import io.github.jasondorjeshort.dystopo.packs.PackArchiveInfo
import io.github.jasondorjeshort.dystopo.packs.PackDownload
import io.github.jasondorjeshort.dystopo.packs.PackHash
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.packs.PackRole
import io.github.jasondorjeshort.dystopo.packs.PackSource
import io.github.jasondorjeshort.dystopo.packs.ShippedGraph
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.routing.GraphTileFile
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph
import io.github.jasondorjeshort.dystopo.routing.RoutingGraphBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloads pack pairs over HTTP(S) from a plain static host, one pack at a time, with resume.
 *
 * Each file streams into `<file>.part`; the part file's length is the resume point, so a download
 * survives a network error, an app kill or a reboot: the job is recorded in `packs/.downloads/<name>.json`
 * (URLs, sizes, validators) and [resumePending] picks it up when the app starts, asking for
 * `Range: bytes=<have>-` with `If-Range` so a file that changed on the host starts over instead of
 * being spliced. [PackDownload.plan] decides what each response means. A finished part file is checked
 * (PMTiles header readable, file as long as the header says) before it gets its final name, so a
 * pack never appears half-written. The vector archive is optional: a 404 for it is fine.
 *
 * T53: a download from a server's catalog ([startCatalog]) knows each archive's SHA-256 and size; the
 * finished part file must match ([PackHash.matches]) or it is deleted and the job fails with
 * `hash mismatch` (no resume: the bytes were wrong, not short). Catalog downloads keep verified files as
 * part files until every archive is ready, so a rejected archive installs none of the pack; an update
 * (`update = true`) fetches only the archives whose content changed, then closes the pack, swaps the
 * files in and reopens it, so the map never mixes old and new archives.
 *
 * T78: a URL `peer://<host:port>/peer/file/<file>` is a nearby phone's archive ([io.github.jasondorjeshort.dystopo.app.share.PeerFiles]):
 * the same loop asks it for the bytes from the part file's length on, over the encrypted LAN link in chunks, with
 * the same plan, resume and SHA-256 check; only the transport differs ([RangeConn]).
 *
 * T189 (ADR 0049's B): a catalog download may carry the routing graph the server shipped with the pack ([DerivedFile],
 * the catalog's `derived` or the manifest's `graph` item) as a role-less file job ([FileJob.role] null, `kind: graph`).
 * It is fetched after the archives verify, only when its `version`, tile zoom and both fingerprints match the archives
 * just verified (or, for an update, kept) ([ShippedGraph.mismatch]); it is checked by size, SHA-256 and its header,
 * then installed beside the vector archive (`<archive>-graph.idx`) once the archives are. A graph that does not match,
 * fails its hash or fails to download is left out with the reason (`graph` in the job's JSON): never a failed pack;
 * the phone builds its own (ADR 0033 §2). [startGraph] fetches a graph alone for a pack already on the device (the
 * route planner's third fetch point, [ShippedGraphs]).
 */
class PackDownloader(private val manager: PackManager, private val dir: File) {
    private val stateDir = File(dir, ".downloads").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-pack-download").apply { isDaemon = true } }
    private val jobs = LinkedHashMap<String, Job>()

    /**
     * One file of a job: an archive ([role]), or (T189) a role-less derived file ([role] null, [kind] `graph`), whose
     * label in the saved state and the JSON is [kind].
     */
    class FileJob(val role: PackRole?, val url: String, val target: File, val optional: Boolean, val kind: String = role?.label ?: "file") {
        val part = File(target.parentFile, target.name + ".part")
        /** T189: why a derived file was left out (`skipped`, `failed`), for the job's JSON and the planner's status. */
        @Volatile var reason: String? = null
        /** T53: the SHA-256 and size the catalog promises (null for a plain URL download). */
        @Volatile var expectSha256: String? = null
        @Volatile var expectBytes: Long? = null
        /** T53: the SHA-256 of the verified file. */
        @Volatile var sha256: String? = null
        @Volatile var total: Long? = null
        @Volatile var etag: String? = null
        @Volatile var lastModified: String? = null
        @Volatile var status = "pending" // pending, downloading, verifying, verified (update, not swapped in yet), done, missing, unchanged
        val done = AtomicLong()
        val responses = ArrayList<String>()
        val resumedFrom = ArrayList<Long>()

        fun json(): Map<String, Any?> = linkedMapOf(
            "role" to role?.label, "kind" to kind, "url" to url, "file" to target.name, "status" to status, "bytesDone" to done.get(),
            "bytesTotal" to total, "resumedFrom" to synchronized(resumedFrom) { resumedFrom.toList() },
            "expectSha256" to expectSha256, "sha256" to sha256, "reason" to reason,
            "responses" to synchronized(responses) { responses.takeLast(10) },
        )
    }

    inner class Job(
        val source: PackSource,
        val activate: Boolean,
        val createdAt: Long,
        /** T53: replace the installed pack of this name (only the archives listed in [files] with a URL). */
        val update: Boolean = false,
        /** T53: the catalog this pack came from: server, id and content hash (recorded once installed). */
        val catalog: Map<String, Any?>? = null,
        /** T189: the routing graph shipped with the pack (its entry and absolute URL), fetched after the archives when it matches. */
        val shipped: DerivedFile? = null,
        val shippedUrl: String? = null,
        /** T189: a job that fetches only the graph of a pack already on the device ([startGraph]), beside [graphTarget]. */
        val graphOnly: Boolean = false,
        graphTarget: File? = null,
    ) {
        val name get() = source.name
        // The (small, optional) vector archive first: the DEM getting its final name is what makes the
        // pack appear in the list, so it appears complete.
        val files = listOf(
            FileJob(PackRole.VECTOR, source.vectorUrl, File(dir, PackNames.fileName(source.name, PackRole.VECTOR)), optional = true),
            FileJob(PackRole.DEM, source.demUrl, File(dir, PackNames.fileName(source.name, PackRole.DEM)), optional = false),
        )
        /** T189: the shipped graph's file job (null when none is shipped): after the archives, never failing the job. */
        val graph: FileJob? = if (shipped == null || shippedUrl == null) null else FileJob(
            null, shippedUrl, graphTarget ?: File(dir, RoutingGraph.fileNameFor(PackNames.fileName(source.name, PackRole.VECTOR))), optional = true, kind = shipped.kind,
        ).also { it.expectSha256 = shipped.sha256; it.expectBytes = shipped.bytes }

        /** The archives and the graph: what the byte counts add up. */
        private val all get() = files + listOfNotNull(graph)

        @Volatile var state = "queued" // queued, running, done, failed, cancelled
        @Volatile var error: String? = null
        @Volatile var cancelled = false
        /** Times this job was picked up again after an app restart. */
        @Volatile var restarts = 0
        @Volatile var sessionStartedAt = 0L
        @Volatile var sessionStartBytes = 0L
        @Volatile var finishedAt: Long? = null

        val bytesDone get() = all.filter { it.status != "unchanged" && it.status != "skipped" }.sumOf { it.done.get() }
        val bytesTotal: Long? get() = all.filter { it.status != "missing" && it.status != "unchanged" && it.status != "skipped" && it.status != "failed" }
            .map { it.total ?: it.expectBytes }.let { t -> if (t.any { it == null }) null else t.sumOf { it!! } }

        fun json(): Map<String, Any?> {
            val done = bytesDone
            val total = bytesTotal
            val elapsed = if (sessionStartedAt == 0L) 0L else (finishedAt ?: SystemClock.elapsedRealtime()) - sessionStartedAt
            return linkedMapOf(
                "name" to name, "state" to state, "error" to error, "activate" to activate, "createdAt" to createdAt, // T96: the Share screen hides a peer's failure from before its state changed
                "update" to update, "catalog" to catalog,
                "bytesDone" to done, "bytesTotal" to total,
                "percent" to total?.takeIf { it > 0 }?.let { Math.round(done * 1000.0 / it) / 10.0 },
                "bytesPerSec" to if (elapsed > 0) (done - sessionStartBytes) * 1000 / elapsed else null,
                "restarts" to restarts,
                "files" to files.map { it.json() },
                "graph" to graph?.json(), "graphOnly" to graphOnly, // T189
            )
        }
    }

    /** Starts (or resumes) downloading the pack [url] names. */
    fun start(url: String, name: String? = null, activate: Boolean = false): Job {
        val source = PackDownload.resolve(url.trim(), name)
            ?: throw IllegalArgumentException("not a pack URL: '$url' (want http(s)://host/path/<name>-dem.pmtiles or .../<name>)")
        io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy.problem(url)?.let { throw IllegalArgumentException(it) } // T79: refuse at once, not after retries
        return enqueue(source) { Job(source, activate, System.currentTimeMillis()) }
    }

    /**
     * T53: downloads a catalog pack: [files] maps each archive to download to its URL, SHA-256 and size;
     * [unchanged] archives (update only) stay as installed; a role in neither has no archive on the server.
     */
    fun startCatalog(
        name: String,
        files: Map<PackRole, Triple<String, String, Long>>,
        unchanged: Set<PackRole>,
        update: Boolean,
        activate: Boolean,
        catalog: Map<String, Any?>,
        /** T189: the routing graph shipped with the pack and its absolute URL; fetched after the archives when it matches them. */
        shipped: DerivedFile? = null,
        shippedUrl: String? = null,
    ): Job {
        require(PackNames.isValidName(name)) { "invalid pack name '$name'" }
        // T65: a vector-only pack (a server's single-file pack) is map data drawn where the DEM comes from another pack or online.
        require(files.isNotEmpty() || (update && unchanged.isNotEmpty())) { "pack '$name' has no archive to download" }
        val source = PackSource(name, files[PackRole.DEM]?.first.orEmpty(), files[PackRole.VECTOR]?.first.orEmpty())
        return enqueue(source, update) {
            Job(source, activate, System.currentTimeMillis(), update, catalog, shipped, shippedUrl).also { job ->
                for (f in job.files) {
                    val e = files[f.role]
                    when {
                        e != null -> { f.expectSha256 = e.second; f.expectBytes = e.third }
                        f.role in unchanged -> f.status = "unchanged"
                        else -> f.status = "missing"
                    }
                }
            }
        }
    }

    /**
     * T189: fetches only the routing graph [shipped] (at [url]) of pack [name], already on the device with its vector
     * archive [vector] (and [dem]): the graph lands beside [vector] once its size, hash, header and fingerprints check out.
     * The planner's third fetch point ([ShippedGraphs]): a pack whose graph is missing, stale or refused, and a server
     * whose catalog offers one for these archives. A job already running for the pack is returned instead.
     */
    fun startGraph(name: String, vector: File, dem: File?, shipped: DerivedFile, url: String, catalog: Map<String, Any?>): Job {
        require(PackNames.isValidName(name)) { "invalid pack name '$name'" }
        val source = PackSource(name, dem?.path.orEmpty(), vector.path)
        return enqueue(source, update = true) {
            Job(source, false, System.currentTimeMillis(), update = true, catalog = catalog, shipped = shipped, shippedUrl = url, graphOnly = true,
                graphTarget = File(vector.absoluteFile.parentFile, RoutingGraph.fileNameFor(vector.name))).also { job ->
                job.files.forEach { it.status = "unchanged" }
            }
        }
    }

    /** T189: the job running or last finished for pack [name], or null. */
    fun job(name: String): Job? = synchronized(jobs) { jobs[name] }

    private fun enqueue(source: PackSource, update: Boolean = false, make: () -> Job): Job {
        synchronized(jobs) {
            jobs[source.name]?.let { if (it.state == "queued" || it.state == "running") return it }
            val existing = PackRole.entries.any { File(dir, PackNames.fileName(source.name, it)).exists() } // T65: or its vector archive alone
            require(update || !existing || File(stateDir, "${source.name}.json").exists()) { "pack '${source.name}' is already on the device; DELETE /packs/${source.name} first" }
            val job = make()
            jobs[source.name] = job
            save(job)
            executor.execute { run(job) }
            return job
        }
    }

    /** Re-queues the downloads recorded on disk (the app was killed or restarted mid-download). */
    fun resumePending() {
        val files = stateDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        for (f in files) {
            try {
                val m = MiniJson.parseObject(f.readText())
                val source = PackSource(m["name"] as String, m["demUrl"] as String, m["vectorUrl"] as String)
                @Suppress("UNCHECKED_CAST")
                val g = m["graph"] as? Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val shipped = (g?.get("entry") as? Map<String, Any?>)?.let(DerivedFile::parse)
                @Suppress("UNCHECKED_CAST")
                val job = Job(
                    source, m["activate"] == true, (m["createdAt"] as? Number)?.toLong() ?: 0L, m["update"] == true, m["catalog"] as? Map<String, Any?>,
                    shipped, g?.get("url") as? String, m["graphOnly"] == true, (g?.get("target") as? String)?.let(::File),
                )
                job.graph?.let { gj ->
                    gj.total = (g?.get("total") as? Number)?.toLong()
                    gj.etag = g?.get("etag") as? String
                    gj.lastModified = g?.get("lastModified") as? String
                }
                job.restarts = ((m["restarts"] as? Number)?.toInt() ?: 0) + 1
                @Suppress("UNCHECKED_CAST")
                for (fm in (m["files"] as? List<Map<String, Any?>>).orEmpty()) {
                    val fj = job.files.firstOrNull { it.role?.label == fm["role"] } ?: continue
                    fj.total = (fm["total"] as? Number)?.toLong()
                    fj.etag = fm["etag"] as? String
                    fj.lastModified = fm["lastModified"] as? String
                    if (fm["status"] == "missing" || fm["status"] == "unchanged") fj.status = fm["status"] as String
                    fj.expectSha256 = fm["expectSha256"] as? String
                    fj.expectBytes = (fm["expectBytes"] as? Number)?.toLong()
                }
                synchronized(jobs) {
                    if (jobs[source.name]?.state.let { it == "queued" || it == "running" }) return@synchronized
                    jobs[source.name] = job
                    save(job)
                    executor.execute { run(job) }
                }
                Log.i(PackManager.TAG, "resuming download of ${source.name} (restart ${job.restarts})")
            } catch (e: Exception) {
                Log.w(PackManager.TAG, "unreadable download state ${f.name}; dropped", e)
                f.delete()
            }
        }
    }

    /** Cancels [name]'s download and removes its partial files. True if there was one. */
    fun cancel(name: String): Boolean {
        val job = synchronized(jobs) { jobs.remove(name) }
        val state = File(stateDir, "$name.json")
        val had = job?.state.let { it == "queued" || it == "running" || it == "failed" } || state.exists()
        job?.let { it.cancelled = true; it.state = "cancelled" }
        state.delete()
        // The worker may still hold a part file open for a moment; delete what is there now and again after it stops.
        val parts = PackRole.entries.map { File(dir, PackNames.fileName(name, it) + ".part") } +
            listOfNotNull(File(dir, RoutingGraph.fileNameFor(PackNames.fileName(name, PackRole.VECTOR)) + ".part"), job?.graph?.part) // T189
        parts.forEach { it.delete() }
        if (job != null) executor.execute { parts.forEach { it.delete() } }
        return had
    }

    fun activeCount(): Int = synchronized(jobs) { jobs.values.count { it.state == "queued" || it.state == "running" } }

    fun snapshot(): List<Map<String, Any?>> = synchronized(jobs) { jobs.values.toList() }.map { it.json() }

    fun json(): Map<String, Any?> = linkedMapOf("active" to activeCount(), "jobs" to snapshot())

    private fun save(job: Job) {
        if (job.cancelled) return
        val m = linkedMapOf(
            "name" to job.name, "demUrl" to job.source.demUrl, "vectorUrl" to job.source.vectorUrl, "activate" to job.activate,
            "createdAt" to job.createdAt, "restarts" to job.restarts, "update" to job.update, "catalog" to job.catalog,
            "files" to job.files.map {
                mapOf(
                    "role" to it.role?.label, "total" to it.total, "etag" to it.etag, "lastModified" to it.lastModified, "status" to it.status,
                    "expectSha256" to it.expectSha256, "expectBytes" to it.expectBytes,
                )
            },
            "graphOnly" to job.graphOnly,
            // T189: the shipped graph: its entry, URL, target and resume validators (a build before T189 ignores the key).
            "graph" to job.graph?.let { g ->
                mapOf("entry" to job.shipped?.json(), "url" to g.url, "target" to g.target.path, "total" to g.total, "etag" to g.etag, "lastModified" to g.lastModified)
            },
        )
        val f = File(stateDir, "${job.name}.json")
        val tmp = File(stateDir, "${job.name}.json.tmp")
        tmp.writeText(MiniJson.encode(m))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private class Cancelled : Exception()

    /** T53: the downloaded bytes are not what the catalog promised. Not retried, not resumed. */
    class HashMismatch(message: String) : IOException(message)
    /** T79: a redirect the cleartext policy refuses, or too many redirects: not retried. */
    class RedirectRefused(message: String) : IOException(message)

    private fun run(job: Job) {
        if (job.cancelled) return
        job.state = "running"
        job.sessionStartedAt = SystemClock.elapsedRealtime()
        job.files.forEach { it.done.set(if (!job.update && it.target.isFile) it.target.length() else it.part.length()) }
        job.sessionStartBytes = job.bytesDone
        try {
            for (f in job.files) {
                if (f.status == "missing" || f.status == "unchanged") continue
                if (!job.update && f.expectSha256 == null && f.target.isFile && f.total == f.target.length()) { f.status = "done"; continue }
                fetch(job, f)
            }
            job.graph?.let { fetchGraph(job, it) } // T189: after the archives verify; never fails the job
            if (!job.graphOnly && (job.update || job.catalog != null)) {
                manager.installUpdate(job.name, job.files.filter { it.status == "verified" }.map { it.part to it.target }, job.files.filter { it.status == "missing" }.mapNotNull { it.role })
            }
            job.files.filter { it.status == "verified" }.forEach { it.status = "done" }
            job.graph?.takeIf { it.status == "verified" }?.let { installGraph(job, it) }
            job.finishedAt = SystemClock.elapsedRealtime()
            if (!job.graphOnly) job.catalog?.let { c -> manager.catalogClient.recordInstalled(job.name, c, job.files.filter { it.sha256 != null && it.role != null }.associate { it.role!! to (it.target to it.sha256!!) }) }
            job.state = "done"
            File(stateDir, "${job.name}.json").delete()
            Log.i(PackManager.TAG, "downloaded pack ${job.name}: ${job.bytesDone} bytes${if (job.update) " (update)" else ""}${job.graph?.let { ", graph ${it.status}${it.reason?.let { r -> " ($r)" } ?: ""}" } ?: ""}")
            when {
                job.graphOnly -> {}
                job.update -> manager.onUpdated(job.name)
                else -> manager.onDownloaded(job.name, job.activate)
            }
        } catch (_: Cancelled) {
            job.state = "cancelled"
        } catch (e: HashMismatch) {
            Log.w(PackManager.TAG, "download of ${job.name} rejected: ${e.message}")
            job.finishedAt = SystemClock.elapsedRealtime()
            job.error = e.message
            job.state = "failed"
            // Wrong bytes: nothing to resume. The parts of the other archives go too.
            File(stateDir, "${job.name}.json").delete()
            job.files.forEach { it.part.delete() }
            job.graph?.part?.delete()
        } catch (e: Exception) {
            Log.w(PackManager.TAG, "download of ${job.name} failed", e)
            job.finishedAt = SystemClock.elapsedRealtime()
            job.error = e.message ?: e.javaClass.simpleName
            job.state = "failed"
            // The state file stays, so the next app start (or a new POST) resumes from the part file.
        }
    }

    /** T78: one ranged GET as the download loop sees it: [HttpConn] for HTTP(S), a peer phone's sealed link for `peer://`. */
    interface RangeConn : java.io.Closeable {
        /** The status after redirects were looked at (reading it sends the request). */
        val status: Int
        fun header(name: String): String?
        val contentLength: Long?
        /** Where a redirect points (checked by the cleartext policy), else null. */
        val redirect: String?
        fun body(): java.io.InputStream
    }

    private class HttpConn(url: String, have: Long, f: FileJob) : RangeConn {
        val conn: HttpURLConnection = io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy.open(url).apply { // T79: plain http only to home-network hosts
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept-Encoding", "identity") // byte offsets must be file offsets
            if (have > 0) {
                setRequestProperty("Range", "bytes=$have-")
                (f.etag ?: f.lastModified)?.let { setRequestProperty("If-Range", it) }
            }
        }
        override val status: Int get() = conn.responseCode
        override fun header(name: String): String? = conn.getHeaderField(name)
        override val contentLength: Long? get() = conn.contentLengthLong.takeIf { it >= 0 }
        override val redirect: String? by lazy {
            try {
                io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy.redirectTarget(conn)
            } catch (e: IOException) {
                if (conn.responseCode in 300..399) throw RedirectRefused("${f.target.name}: ${e.message}") else throw e
            }
        }
        override fun body(): java.io.InputStream = conn.inputStream
        override fun close() = conn.disconnect()
    }

    private fun open(url: String, have: Long, f: FileJob): RangeConn =
        if (url.startsWith(io.github.jasondorjeshort.dystopo.app.share.PeerSources.SCHEME)) io.github.jasondorjeshort.dystopo.app.share.PeerFiles.open(url, have, f.expectSha256)
        else HttpConn(url, have, f)

    private fun fetch(job: Job, f: FileJob) {
        var failures = 0
        // T79: redirects are followed here, each Location checked by the cleartext policy (open() turns automatic ones off).
        var url = f.url
        var redirects = 0
        while (true) {
            if (job.cancelled) throw Cancelled()
            val have = if (f.part.isFile) f.part.length() else 0L
            f.done.set(have)
            f.status = "downloading"
            val conn = open(url, have, f) // T78: a peer's first request is sent when the status is read, inside the retries
            try {
                val next = conn.redirect
                if (next != null) {
                    if (++redirects > io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy.MAX_REDIRECTS) throw RedirectRefused("${f.target.name}: too many redirects")
                    synchronized(f.responses) { f.responses += "${conn.status} redirect to $next" }
                    url = next
                    continue
                }
                val status = conn.status
                val plan = PackDownload.plan(have, status, conn.header("Content-Range"), conn.contentLength, f.total)
                synchronized(f.responses) { f.responses += "$status from $have: ${plan.javaClass.simpleName}" }
                when (plan) {
                    is PackDownload.Plan.Append, is PackDownload.Plan.Restart -> {
                        val append = plan is PackDownload.Plan.Append
                        val total = if (plan is PackDownload.Plan.Append) plan.total else (plan as PackDownload.Plan.Restart).total
                        if (append && have > 0) synchronized(f.resumedFrom) { f.resumedFrom += have }
                        if (!append) {
                            f.etag = conn.header("ETag")
                            f.lastModified = conn.header("Last-Modified")
                        }
                        f.total = total
                        save(job)
                        copy(job, f, conn, append = append)
                        if (total != null && f.part.length() < total) continue // connection ended early: resume
                    }
                    PackDownload.Plan.Complete -> {}
                    is PackDownload.Plan.Discard -> {
                        f.part.delete()
                        f.total = null
                        f.etag = null
                        f.lastModified = null
                        if (++failures > 3) throw IOException("${f.target.name}: ${plan.reason}")
                        continue
                    }
                    PackDownload.Plan.Missing -> {
                        if (!f.optional) throw IOException("${f.url}: not found (HTTP $status)")
                        f.status = "missing"
                        save(job)
                        return
                    }
                    is PackDownload.Plan.Fail -> throw IOException("${f.url}: ${plan.reason}")
                }
            } catch (e: IOException) {
                if (e is HashMismatch || e is RedirectRefused) throw e
                if (job.cancelled) throw Cancelled()
                if (++failures > 5) throw e
                synchronized(f.responses) { f.responses += "error: ${e.message}" }
                Thread.sleep(2000L * failures)
                continue
            } finally {
                runCatching { conn.close() }
            }
            if (f.role == null) finishDerived(job, f) else finish(f, stage = job.update || job.catalog != null)
            return
        }
    }

    /**
     * T189: fetches the shipped graph [g] when it matches the archives this job verified (or kept): the vector archive's
     * and the DEM's fingerprints against the entry's `key` ([ShippedGraph.mismatch]). Never throws but for a cancel: a
     * mismatch is `skipped`, a failed download or hash `failed`, each with its [FileJob.reason].
     */
    private fun fetchGraph(job: Job, g: FileJob) {
        val entry = job.shipped ?: return
        fun archive(role: PackRole): File? = job.files.firstOrNull { it.role == role }?.let { f ->
            when (f.status) {
                "verified" -> f.part
                "done" -> f.target
                "unchanged" -> if (job.graphOnly) File(if (role == PackRole.VECTOR) job.source.vectorUrl else job.source.demUrl).takeIf { it.isFile }
                    else manager.catalog().firstOrNull { it.name == job.name }?.let { p -> if (role == PackRole.VECTOR) p.vector else p.dem }
                else -> null
            }
        }
        val vector = archive(PackRole.VECTOR)
        if (vector == null) { g.status = "skipped"; g.reason = "no vector archive"; return }
        val why = try {
            ShippedGraph.mismatch(entry, fingerprint(vector), archive(PackRole.DEM)?.let(::fingerprint).orEmpty())
        } catch (e: Exception) {
            "the archives could not be read (${e.message})"
        }
        if (why != null) {
            g.status = "skipped"
            g.reason = why
            Log.i(PackManager.TAG, "graph shipped with ${job.name} not taken: $why")
            return
        }
        try {
            fetch(job, g)
            if (g.status == "missing") g.reason = "not found on the server"
        } catch (e: Cancelled) {
            throw e
        } catch (e: Exception) {
            g.part.delete()
            g.status = "failed"
            g.reason = if (e is HashMismatch) "hash mismatch" else (e.message ?: e.javaClass.simpleName)
            Log.w(PackManager.TAG, "graph shipped with ${job.name} not taken: ${g.reason}")
        }
    }

    /**
     * T189: checks a downloaded derived file: the promised size and SHA-256 (a mismatch is [HashMismatch]: wrong bytes),
     * then, for a graph, its header ([GraphTileFile.open]: version and tile zoom) and that it names the fingerprints the
     * entry promised. Leaves it `verified`; [installGraph] moves it in after the archives.
     */
    private fun finishDerived(job: Job, f: FileJob) {
        f.status = "verifying"
        val size = f.part.length()
        f.expectBytes?.let { if (size != it) { f.part.delete(); throw HashMismatch("${f.target.name}: $size bytes, the catalog says $it; part file deleted") } }
        val actual = sha256(f.part)
        f.sha256 = actual
        if (!PackHash.matches(f.expectSha256, actual)) {
            f.part.delete()
            throw HashMismatch("${f.target.name}: hash mismatch, got sha256 $actual, the catalog says ${f.expectSha256}; part file deleted")
        }
        val entry = job.shipped
        if (f.kind == DerivedFile.KIND_GRAPH && entry != null) {
            val a = FileRangeSource(f.part).use { src -> GraphTileFile.open { offset, length -> src.read(offset, length) } }
            if (a.source.fingerprint != entry.fingerprint || a.source.demFingerprint != entry.demFingerprint.orEmpty()) {
                f.part.delete()
                throw IOException("${f.target.name}: its header names other archives than the catalog said; part file deleted")
            }
        }
        f.done.set(size)
        f.total = size
        f.status = "verified"
    }

    /** T189: moves a verified graph in beside its vector archive, drops the planner's open graph of the pack and records where it came from. */
    private fun installGraph(job: Job, g: FileJob) {
        g.target.delete()
        if (!g.part.renameTo(g.target)) {
            g.status = "failed"
            g.reason = "rename ${g.part.name} -> ${g.target.name} failed"
            g.part.delete()
            return
        }
        g.status = "done"
        io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner.forget(job.name) // reopened (from the new file) on the next use
        job.shipped?.let { manager.catalogClient.recordGraph(job.name, g.target, g.sha256 ?: it.sha256, it, job.catalog) }
    }

    private fun copy(job: Job, f: FileJob, conn: RangeConn, append: Boolean) {
        f.part.parentFile?.mkdirs()
        if (!append) f.done.set(0)
        conn.body().use { input ->
            FileOutputStream(f.part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (job.cancelled) throw Cancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    f.done.addAndGet(n.toLong())
                }
            }
        }
    }

    /**
     * Checks the part file is a whole PMTiles archive (and, from a catalog, has the promised SHA-256) and
     * gives it its final name; an update leaves it as a verified part file for [PackManager.installUpdate].
     */
    private fun finish(f: FileJob, stage: Boolean) {
        f.status = "verifying"
        val size = f.part.length()
        val info = try {
            FileRangeSource(f.part).use { src -> PackArchiveInfo.read(f.target.name, size, PmtilesReader.open(src, mapOf(Compression.GZIP to gunzip))) }
        } catch (e: Exception) {
            f.part.delete()
            throw IOException("${f.target.name}: not a PMTiles archive (${e.message}); part file deleted")
        }
        if (!info.complete) throw IOException("${f.target.name}: $size bytes, the header needs ${info.expectedBytes}")
        f.expectSha256?.let { expected ->
            val actual = sha256(f.part)
            if (!PackHash.matches(expected, actual)) {
                f.part.delete()
                f.sha256 = actual
                throw HashMismatch("${f.target.name}: hash mismatch, got sha256 $actual ($size bytes), the catalog says $expected (${f.expectBytes} bytes); part file deleted")
            }
            f.sha256 = actual
        }
        if (stage) {
            f.done.set(size)
            f.total = size
            f.status = "verified"
            return
        }
        f.target.delete()
        if (!f.part.renameTo(f.target)) throw IOException("rename ${f.part} -> ${f.target} failed")
        f.done.set(size)
        f.total = size
        f.status = "done"
    }

    companion object {
        /** T189: [RoutingGraphBuilder.fingerprint] of a PMTiles archive's header (what a graph names its archives by). */
        fun fingerprint(file: File): String =
            FileRangeSource(file).use { src -> RoutingGraphBuilder.fingerprint(PmtilesReader.open(src, mapOf(Compression.GZIP to gunzip)).header) }

        /** SHA-256 of [file] as 64 lowercase hex digits (the platform digest, much faster than the pure Kotlin one). */
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return io.github.jasondorjeshort.dystopo.packs.Sha256.toHex(md.digest())
        }
    }
}
