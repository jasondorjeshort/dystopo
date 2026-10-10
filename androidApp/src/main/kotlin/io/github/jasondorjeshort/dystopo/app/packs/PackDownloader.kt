package io.github.jasondorjeshort.dystopo.app.packs

import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.FileRangeSource
import io.github.jasondorjeshort.dystopo.app.gunzip
import io.github.jasondorjeshort.dystopo.packs.PackArchiveInfo
import io.github.jasondorjeshort.dystopo.packs.PackDownload
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
 */
class PackDownloader(private val manager: PackManager, private val dir: File) {
    private val stateDir = File(dir, ".downloads").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-pack-download").apply { isDaemon = true } }
    private val jobs = LinkedHashMap<String, Job>()

    class FileJob(val role: PackRole, val url: String, val target: File, val optional: Boolean) {
        val part = File(target.parentFile, target.name + ".part")
        @Volatile var total: Long? = null
        @Volatile var etag: String? = null
        @Volatile var lastModified: String? = null
        @Volatile var status = "pending" // pending, downloading, verifying, done, missing
        val done = AtomicLong()
        val responses = ArrayList<String>()
        val resumedFrom = ArrayList<Long>()

        fun json(): Map<String, Any?> = linkedMapOf(
            "role" to role.label, "url" to url, "file" to target.name, "status" to status, "bytesDone" to done.get(),
            "bytesTotal" to total, "resumedFrom" to synchronized(resumedFrom) { resumedFrom.toList() },
            "responses" to synchronized(responses) { responses.takeLast(10) },
        )
    }

    inner class Job(val source: PackSource, val activate: Boolean, val createdAt: Long) {
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

        val bytesDone get() = files.sumOf { it.done.get() }
        val bytesTotal: Long? get() = files.filter { it.status != "missing" }.map { it.total }.let { t -> if (t.any { it == null }) null else t.sumOf { it!! } }

        fun json(): Map<String, Any?> {
            val done = bytesDone
            val total = bytesTotal
            val elapsed = if (sessionStartedAt == 0L) 0L else (finishedAt ?: SystemClock.elapsedRealtime()) - sessionStartedAt
            return linkedMapOf(
                "name" to name, "state" to state, "error" to error, "activate" to activate,
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
        synchronized(jobs) {
            jobs[source.name]?.let { if (it.state == "queued" || it.state == "running") return it }
            val existing = File(dir, PackNames.fileName(source.name, PackRole.DEM))
            require(!existing.exists() || File(stateDir, "${source.name}.json").exists()) { "pack '${source.name}' is already on the device; DELETE /packs/${source.name} first" }
            val job = Job(source, activate, System.currentTimeMillis())
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
                val job = Job(source, m["activate"] == true, (m["createdAt"] as? Number)?.toLong() ?: 0L)
                job.restarts = ((m["restarts"] as? Number)?.toInt() ?: 0) + 1
                @Suppress("UNCHECKED_CAST")
                for (fm in (m["files"] as? List<Map<String, Any?>>).orEmpty()) {
                    val fj = job.files.firstOrNull { it.role.label == fm["role"] } ?: continue
                    fj.total = (fm["total"] as? Number)?.toLong()
                    fj.etag = fm["etag"] as? String
                    fj.lastModified = fm["lastModified"] as? String
                    if (fm["status"] == "missing") fj.status = "missing"
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
            "createdAt" to job.createdAt, "restarts" to job.restarts,
            "files" to job.files.map { mapOf("role" to it.role.label, "total" to it.total, "etag" to it.etag, "lastModified" to it.lastModified, "status" to it.status) },
        )
        val f = File(stateDir, "${job.name}.json")
        val tmp = File(stateDir, "${job.name}.json.tmp")
        tmp.writeText(MiniJson.encode(m))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private class Cancelled : Exception()

    private fun run(job: Job) {
        if (job.cancelled) return
        job.state = "running"
        job.sessionStartedAt = SystemClock.elapsedRealtime()
        job.files.forEach { it.done.set(if (it.target.isFile) it.target.length() else it.part.length()) }
        job.sessionStartBytes = job.bytesDone
        try {
            for (f in job.files) {
                if (f.status == "missing") continue
                if (f.target.isFile && f.total == f.target.length()) { f.status = "done"; continue }
                fetch(job, f)
            }
            job.finishedAt = SystemClock.elapsedRealtime()
            job.state = "done"
            File(stateDir, "${job.name}.json").delete()
            Log.i(PackManager.TAG, "downloaded pack ${job.name}: ${job.bytesDone} bytes")
            manager.onDownloaded(job.name, job.activate)
        } catch (_: Cancelled) {
            job.state = "cancelled"
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
                if (job.cancelled) throw Cancelled()
                if (++failures > 5) throw e
                synchronized(f.responses) { f.responses += "error: ${e.message}" }
                Thread.sleep(2000L * failures)
                continue
            } finally {
                conn.disconnect()
            }
            finish(f)
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

    /** Checks the part file is a whole PMTiles archive and gives it its final name. */
    private fun finish(f: FileJob) {
        f.status = "verifying"
        val size = f.part.length()
        val info = try {
            FileRangeSource(f.part).use { src -> PackArchiveInfo.read(f.target.name, size, PmtilesReader.open(src, mapOf(Compression.GZIP to gunzip))) }
        } catch (e: Exception) {
            f.part.delete()
            throw IOException("${f.target.name}: not a PMTiles archive (${e.message}); part file deleted")
        }
        if (!info.complete) throw IOException("${f.target.name}: $size bytes, the header needs ${info.expectedBytes}")
        f.target.delete()
        if (!f.part.renameTo(f.target)) throw IOException("rename ${f.part} -> ${f.target} failed")
        f.done.set(size)
        f.total = size
        f.status = "done"
    }
}
