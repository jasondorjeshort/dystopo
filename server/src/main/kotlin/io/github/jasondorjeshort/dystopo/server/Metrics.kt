package io.github.jasondorjeshort.dystopo.server

import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * T82: what an operator watches on a long-running server, at `GET /metrics` (JSON; `?format=prometheus`
 * for the Prometheus text format). Requests per route group (count, status classes, a latency histogram
 * with p50/p95/p99 estimates), the JVM (heap, threads, GC, open files where the OS reports them, resident
 * memory on Linux), the cache and out directories on disk (scanned in the background every
 * [diskScanMinutes], per top-level directory, plus the volume's free space) and the log ([JsonLog] lines,
 * bytes, rotations). Counters are cumulative since the start, so a scraper diffs two samples.
 */
class Metrics(
    private val cacheDir: File,
    private val outDir: File,
    private val diskScanMinutes: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val startedAt = clock()

    /** One route group's counters: requests, status classes, and a latency histogram over [BUCKETS_MS]. */
    class Route {
        val count = AtomicLong()
        val status = AtomicLongArray(6) // index = status / 100 (0: no status sent)
        val bytes = AtomicLong()
        val msSum = AtomicLong() // microseconds
        val buckets = AtomicLongArray(BUCKETS_MS.size + 1)
        @Volatile var maxMs = 0.0

        fun add(statusCode: Int, ms: Double, sent: Long) {
            count.incrementAndGet()
            status.incrementAndGet((statusCode / 100).coerceIn(0, 5))
            if (sent > 0) bytes.addAndGet(sent)
            msSum.addAndGet((ms * 1000).toLong())
            var i = BUCKETS_MS.indexOfFirst { ms <= it }
            if (i < 0) i = BUCKETS_MS.size
            buckets.incrementAndGet(i)
            if (ms > maxMs) maxMs = ms
        }

        /** The latency below which a fraction [q] of requests fell, as the upper bound of its bucket. */
        fun quantile(q: Double): Double? {
            val n = count.get()
            if (n == 0L) return null
            val target = Math.ceil(q * n).toLong().coerceAtLeast(1)
            var seen = 0L
            for (i in 0 until buckets.length()) {
                seen += buckets.get(i)
                if (seen >= target) return if (i < BUCKETS_MS.size) BUCKETS_MS[i] else maxMs
            }
            return maxMs
        }

        fun json(): Map<String, Any?> = linkedMapOf(
            "count" to count.get(),
            "status" to linkedMapOf("1xx" to status.get(1), "2xx" to status.get(2), "3xx" to status.get(3), "4xx" to status.get(4), "5xx" to status.get(5), "none" to status.get(0)),
            "bytes" to bytes.get(),
            "avgMs" to count.get().takeIf { it > 0 }?.let { Math.round(msSum.get() / 1000.0 / it * 10) / 10.0 },
            "p50Ms" to quantile(0.5), "p95Ms" to quantile(0.95), "p99Ms" to quantile(0.99),
            "maxMs" to Math.round(maxMs * 10) / 10.0,
        )
    }

    private val routes = java.util.concurrent.ConcurrentHashMap<String, Route>()

    /** Called by the gate once per request (after the answer, or the failure, went out). */
    fun request(path: String, status: Int, ms: Double, bytes: Long) {
        routes.computeIfAbsent(group(path)) { Route() }.add(status, ms, bytes)
    }

    // ---- disk ----

    /** One directory's size: bytes and files, per top-level child. */
    data class DirSize(val bytes: Long, val files: Long, val children: Map<String, Pair<Long, Long>>, val errors: Long)

    @Volatile private var disk: Map<String, Any?> = mapOf("state" to "pending")
    private val scans = AtomicLong()
    private var scheduler: ScheduledExecutorService? = null

    fun start() {
        if (diskScanMinutes <= 0) { disk = mapOf("state" to "off"); return }
        scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "metrics-disk").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
            .also { it.scheduleWithFixedDelay({ runCatching { scanDisk() } }, 0, diskScanMinutes.toLong(), TimeUnit.MINUTES) }
    }

    fun stop() { scheduler?.shutdownNow() }

    /** Walks [cacheDir] and [outDir] once (the scheduler calls it; tests call it directly). */
    fun scanDisk() {
        val t0 = System.nanoTime()
        val cache = dirSize(cacheDir)
        val out = dirSize(outDir)
        scans.incrementAndGet()
        disk = linkedMapOf(
            "state" to "ready",
            "scannedAt" to java.time.Instant.ofEpochMilli(clock()).toString(),
            "scanMs" to (System.nanoTime() - t0) / 1_000_000,
            "scans" to scans.get(),
            "cacheDir" to cache.json(),
            "outDir" to out.json(),
            "freeBytes" to runCatching { Files.getFileStore((if (outDir.exists()) outDir else outDir.absoluteFile.parentFile).toPath()).usableSpace }.getOrNull(),
        )
    }

    private fun DirSize.json() = linkedMapOf(
        "bytes" to bytes, "files" to files, "errors" to errors,
        "children" to children.entries.sortedByDescending { it.value.first }.associate { (k, v) -> k to mapOf("bytes" to v.first, "files" to v.second) },
    )

    companion object {
        /** Latency histogram upper bounds, milliseconds (the last bucket is everything slower). */
        val BUCKETS_MS = doubleArrayOf(1.0, 2.5, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0, 30000.0, 60000.0)

        /** Route groups: the first path segment for known routes, `other` for anything else (so a scan of random paths does not grow the map). */
        private val GROUPS = setOf(
            "dem", "osm", "contours", "shade", "glyphs", "out", "build", "sync", "route", "routes", "catalog", "catalog.json",
            "healthz", "metrics", "stats", "s", "admin", "live", "phone", "style.json", "trips.js",
        )

        fun group(path: String): String {
            val seg = path.trimStart('/').substringBefore('/')
            return when {
                path == "/" -> "page"
                path == "/sync/relay" || path.startsWith("/sync/relay/") -> "relay"
                seg.startsWith("sprite") -> "sprite"
                seg == "catalog.json" -> "catalog"
                seg in GROUPS -> seg
                else -> "other"
            }
        }

        /** Size of [dir] by walking it; missing directories are zero. Symlinks are not followed. */
        fun dirSize(dir: File): DirSize {
            if (!dir.isDirectory) return DirSize(0, 0, emptyMap(), 0)
            var bytes = 0L
            var files = 0L
            var errors = 0L
            val children = LinkedHashMap<String, Pair<Long, Long>>()
            val root = dir.toPath()
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        bytes += attrs.size()
                        files++
                        val rel = root.relativize(file)
                        val key = if (rel.nameCount > 1) rel.getName(0).toString() + "/" else "(files)"
                        val (b, f) = children[key] ?: (0L to 0L)
                        children[key] = (b + attrs.size()) to (f + 1)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    errors++ // a file deleted while walking (a tile being replaced): skip it
                    return FileVisitResult.CONTINUE
                }
            })
            return DirSize(bytes, files, children, errors)
        }
    }

    // ---- process ----

    fun jvm(): Map<String, Any?> {
        val mem = ManagementFactory.getMemoryMXBean()
        val heap = mem.heapMemoryUsage
        val nonHeap = mem.nonHeapMemoryUsage
        val threads = ManagementFactory.getThreadMXBean()
        val gcs = ManagementFactory.getGarbageCollectorMXBeans()
        val os = ManagementFactory.getOperatingSystemMXBean()
        val unix = os as? com.sun.management.UnixOperatingSystemMXBean
        val sunOs = os as? com.sun.management.OperatingSystemMXBean
        // What the heap held right after the last collection of each pool: the leak signal (heapUsedBytes saws up
        // and down with allocation; this only climbs when live objects accumulate).
        val afterGc = ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == java.lang.management.MemoryType.HEAP }
            .mapNotNull { it.collectionUsage?.used }
        return linkedMapOf(
            "heapUsedBytes" to heap.used, "heapCommittedBytes" to heap.committed, "heapMaxBytes" to heap.max,
            "heapAfterGcBytes" to afterGc.takeIf { it.isNotEmpty() }?.sum(),
            "nonHeapUsedBytes" to nonHeap.used,
            "threads" to threads.threadCount, "peakThreads" to threads.peakThreadCount, "daemonThreads" to threads.daemonThreadCount,
            "gcCount" to gcs.sumOf { it.collectionCount.coerceAtLeast(0) }, "gcMs" to gcs.sumOf { it.collectionTime.coerceAtLeast(0) },
            "classesLoaded" to ManagementFactory.getClassLoadingMXBean().loadedClassCount,
            // Open file descriptors: Linux and macOS only (on Windows read the handle count from the OS).
            "openFiles" to unix?.openFileDescriptorCount, "maxFiles" to unix?.maxFileDescriptorCount,
            "residentBytes" to residentBytes(),
            "processCpuLoad" to sunOs?.processCpuLoad?.takeIf { it >= 0 }?.let { Math.round(it * 1000) / 1000.0 },
            "cpus" to Runtime.getRuntime().availableProcessors(),
        )
    }

    /** VmRSS from /proc (Linux); null elsewhere. */
    private fun residentBytes(): Long? = runCatching {
        File("/proc/self/status").takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("VmRSS:") }
            ?.removePrefix("VmRSS:")?.trim()?.removeSuffix("kB")?.trim()?.toLong()?.times(1024)
    }.getOrNull()

    /** The `/metrics` JSON. [extra] adds the server's own sections (log, live tiles, relay). */
    fun json(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = linkedMapOf<String, Any?>(
        "uptimeS" to (clock() - startedAt) / 1000,
        "requests" to routes.entries.sortedBy { it.key }.associate { it.key to it.value.json() },
        "jvm" to jvm(),
        "disk" to disk,
    ) + extra

    /** The Prometheus text exposition of the same numbers (gauges and counters, `dystopo_` prefix). */
    fun prometheus(extra: Map<String, Number?> = emptyMap()): String = buildString {
        fun line(name: String, value: Number?, labels: String = "") { if (value != null) append("dystopo_").append(name).append(labels).append(' ').append(value).append('\n') }
        fun type(name: String, t: String) { append("# TYPE dystopo_").append(name).append(' ').append(t).append('\n') }
        type("uptime_seconds", "gauge"); line("uptime_seconds", (clock() - startedAt) / 1000)
        type("requests_total", "counter")
        val sorted = routes.entries.sortedBy { it.key }
        for ((g, r) in sorted) for ((i, cls) in listOf("none", "1xx", "2xx", "3xx", "4xx", "5xx").withIndex()) {
            val n = r.status.get(i)
            if (n > 0) line("requests_total", n, "{route=\"$g\",status=\"$cls\"}")
        }
        type("request_duration_ms", "histogram")
        for ((g, r) in sorted) {
            var cum = 0L
            for (i in BUCKETS_MS.indices) { cum += r.buckets.get(i); line("request_duration_ms_bucket", cum, "{route=\"$g\",le=\"${BUCKETS_MS[i]}\"}") }
            cum += r.buckets.get(BUCKETS_MS.size)
            line("request_duration_ms_bucket", cum, "{route=\"$g\",le=\"+Inf\"}")
            line("request_duration_ms_sum", r.msSum.get() / 1000.0, "{route=\"$g\"}")
            line("request_duration_ms_count", r.count.get(), "{route=\"$g\"}")
        }
        type("response_bytes_total", "counter")
        for ((g, r) in sorted) line("response_bytes_total", r.bytes.get(), "{route=\"$g\"}")
        val j = jvm()
        for ((k, name) in listOf(
            "heapUsedBytes" to "jvm_heap_used_bytes", "heapAfterGcBytes" to "jvm_heap_after_gc_bytes", "heapCommittedBytes" to "jvm_heap_committed_bytes", "heapMaxBytes" to "jvm_heap_max_bytes",
            "nonHeapUsedBytes" to "jvm_nonheap_used_bytes", "threads" to "jvm_threads", "gcCount" to "jvm_gc_total", "gcMs" to "jvm_gc_ms_total",
            "openFiles" to "process_open_files", "maxFiles" to "process_max_files", "residentBytes" to "process_resident_bytes",
        )) { type(name, if (name.endsWith("_total")) "counter" else "gauge"); line(name, j[k] as Number?) }
        val d = disk
        if (d["state"] == "ready") {
            for ((key, dirName) in listOf("cacheDir" to "cache", "outDir" to "out")) {
                val m = d[key] as Map<*, *>
                type("disk_${dirName}_bytes", "gauge"); line("disk_${dirName}_bytes", m["bytes"] as Number)
                type("disk_${dirName}_files", "gauge"); line("disk_${dirName}_files", m["files"] as Number)
            }
            type("disk_free_bytes", "gauge"); line("disk_free_bytes", d["freeBytes"] as Number?)
        }
        for ((k, v) in extra) { type(k, if (k.endsWith("_total")) "counter" else "gauge"); line(k, v) }
    }
}
