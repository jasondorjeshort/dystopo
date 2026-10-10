package io.github.jasondorjeshort.dystopo.app.packs

import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.FileRangeSource
import io.github.jasondorjeshort.dystopo.app.gunzip
import io.github.jasondorjeshort.dystopo.packs.PackArchiveInfo
import io.github.jasondorjeshort.dystopo.packs.PackDownload
import io.github.jasondorjeshort.dystopo.packs.PackHash
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.packs.PackRole
import io.github.jasondorjeshort.dystopo.packs.PackSource
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
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
 */
class PackDownloader(private val manager: PackManager, private val dir: File) {
    private val stateDir = File(dir, ".downloads").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-pack-download").apply { isDaemon = true } }
    private val jobs = LinkedHashMap<String, Job>()

    class FileJob(val role: PackRole, val url: String, val target: File, val optional: Boolean) {
        val part = File(target.parentFile, target.name + ".part")
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
            "role" to role.label, "url" to url, "file" to target.name, "status" to status, "bytesDone" to done.get(),
            "bytesTotal" to total, "resumedFrom" to synchronized(resumedFrom) { resumedFrom.toList() },
            "expectSha256" to expectSha256, "sha256" to sha256,
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
    ) {
        val name get() = source.name
        // The (small, optional) vector archive first: the DEM getting its final name is what makes the
        // pack appear in the list, so it appears complete.
        val files = listOf(
            FileJob(PackRole.VECTOR, source.vectorUrl, File(dir, PackNames.fileName(source.name, PackRole.VECTOR)), optional = true),
            FileJob(PackRole.DEM, source.demUrl, File(dir, PackNames.fileName(source.name, PackRole.DEM)), optional = false),
        )
        @Volatile var state = "queued" // queued, running, done, failed, cancelled
        @Volatile var error: String? = null
        @Volatile var cancelled = false
        /** Times this job was picked up again after an app restart. */
        @Volatile var restarts = 0
        @Volatile var sessionStartedAt = 0L
        @Volatile var sessionStartBytes = 0L
        @Volatile var finishedAt: Long? = null

        val bytesDone get() = files.filter { it.status != "unchanged" }.sumOf { it.done.get() }
        val bytesTotal: Long? get() = files.filter { it.status != "missing" && it.status != "unchanged" }.map { it.total }.let { t -> if (t.any { it == null }) null else t.sumOf { it!! } }

        fun json(): Map<String, Any?> {
            val done = bytesDone
            val total = bytesTotal
            val elapsed = if (sessionStartedAt == 0L) 0L else (finishedAt ?: SystemClock.elapsedRealtime()) - sessionStartedAt
            return linkedMapOf(
                "name" to name, "state" to state, "error" to error, "activate" to activate,
                "update" to update, "catalog" to catalog,
                "bytesDone" to done, "bytesTotal" to total,
                "percent" to total?.takeIf { it > 0 }?.let { Math.round(done * 1000.0 / it) / 10.0 },
                "bytesPerSec" to if (elapsed > 0) (done - sessionStartBytes) * 1000 / elapsed else null,
                "restarts" to restarts,
                "files" to files.map { it.json() },
            )
        }
    }

    /** Starts (or resumes) downloading the pack [url] names. */
    fun start(url: String, name: String? = null, activate: Boolean = false): Job {
        val source = PackDownload.resolve(url.trim(), name)
            ?: throw IllegalArgumentException("not a pack URL: '$url' (want http(s)://host/path/<name>-dem.pmtiles or .../<name>)")
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
    ): Job {
        require(PackNames.isValidName(name)) { "invalid pack name '$name'" }
        require(PackRole.DEM in files || (update && PackRole.DEM in unchanged)) { "pack '$name' has no DEM archive" }
        val source = PackSource(name, files[PackRole.DEM]?.first.orEmpty(), files[PackRole.VECTOR]?.first.orEmpty())
        return enqueue(source, update) {
            Job(source, activate, System.currentTimeMillis(), update, catalog).also { job ->
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

    private fun enqueue(source: PackSource, update: Boolean = false, make: () -> Job): Job {
        synchronized(jobs) {
            jobs[source.name]?.let { if (it.state == "queued" || it.state == "running") return it }
            val existing = File(dir, PackNames.fileName(source.name, PackRole.DEM))
            require(update || !existing.exists() || File(stateDir, "${source.name}.json").exists()) { "pack '${source.name}' is already on the device; DELETE /packs/${source.name} first" }
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
                val job = Job(source, m["activate"] == true, (m["createdAt"] as? Number)?.toLong() ?: 0L, m["update"] == true, m["catalog"] as? Map<String, Any?>)
                job.restarts = ((m["restarts"] as? Number)?.toInt() ?: 0) + 1
                @Suppress("UNCHECKED_CAST")
                for (fm in (m["files"] as? List<Map<String, Any?>>).orEmpty()) {
                    val fj = job.files.firstOrNull { it.role.label == fm["role"] } ?: continue
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
        val parts = PackRole.entries.map { File(dir, PackNames.fileName(name, it) + ".part") }
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
                    "role" to it.role.label, "total" to it.total, "etag" to it.etag, "lastModified" to it.lastModified, "status" to it.status,
                    "expectSha256" to it.expectSha256, "expectBytes" to it.expectBytes,
                )
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
            if (job.update || job.catalog != null) manager.installUpdate(job.name, job.files.filter { it.status == "verified" }.map { it.part to it.target }, job.files.filter { it.status == "missing" }.map { it.role })
            job.files.filter { it.status == "verified" }.forEach { it.status = "done" }
            job.finishedAt = SystemClock.elapsedRealtime()
            job.catalog?.let { c -> manager.catalogClient.recordInstalled(job.name, c, job.files.filter { it.sha256 != null }.associate { it.role to (it.target to it.sha256!!) }) }
            job.state = "done"
            File(stateDir, "${job.name}.json").delete()
            Log.i(PackManager.TAG, "downloaded pack ${job.name}: ${job.bytesDone} bytes${if (job.update) " (update)" else ""}")
            if (job.update) manager.onUpdated(job.name) else manager.onDownloaded(job.name, job.activate)
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
        } catch (e: Exception) {
            Log.w(PackManager.TAG, "download of ${job.name} failed", e)
            job.finishedAt = SystemClock.elapsedRealtime()
            job.error = e.message ?: e.javaClass.simpleName
            job.state = "failed"
            // The state file stays, so the next app start (or a new POST) resumes from the part file.
        }
    }

    private fun fetch(job: Job, f: FileJob) {
        var failures = 0
        while (true) {
            if (job.cancelled) throw Cancelled()
            val have = if (f.part.isFile) f.part.length() else 0L
            f.done.set(have)
            f.status = "downloading"
            val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept-Encoding", "identity") // byte offsets must be file offsets
                if (have > 0) {
                    setRequestProperty("Range", "bytes=$have-")
                    (f.etag ?: f.lastModified)?.let { setRequestProperty("If-Range", it) }
                }
            }
            try {
                val status = conn.responseCode
                val plan = PackDownload.plan(have, status, conn.getHeaderField("Content-Range"), conn.contentLengthLong.takeIf { it >= 0 }, f.total)
                synchronized(f.responses) { f.responses += "$status from $have: ${plan.javaClass.simpleName}" }
                when (plan) {
                    is PackDownload.Plan.Append, is PackDownload.Plan.Restart -> {
                        val append = plan is PackDownload.Plan.Append
                        val total = if (plan is PackDownload.Plan.Append) plan.total else (plan as PackDownload.Plan.Restart).total
                        if (append && have > 0) synchronized(f.resumedFrom) { f.resumedFrom += have }
                        if (!append) {
                            f.etag = conn.getHeaderField("ETag")
                            f.lastModified = conn.getHeaderField("Last-Modified")
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
                if (e is HashMismatch) throw e
                if (job.cancelled) throw Cancelled()
                if (++failures > 5) throw e
                synchronized(f.responses) { f.responses += "error: ${e.message}" }
                Thread.sleep(2000L * failures)
                continue
            } finally {
                conn.disconnect()
            }
            finish(f, stage = job.update || job.catalog != null)
            return
        }
    }

    private fun copy(job: Job, f: FileJob, conn: HttpURLConnection, append: Boolean) {
        f.part.parentFile?.mkdirs()
        if (!append) f.done.set(0)
        conn.inputStream.use { input ->
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
