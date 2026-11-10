package io.github.jasondorjeshort.dystopo.app.reports

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.cachebudget.CacheBudget
import io.github.jasondorjeshort.dystopo.app.online.HeapGuard
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.reports.DumpFormat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * T258: the memory sampler behind Report a problem › Send diagnostics. Started from `DystopoApp.onCreate` ([start]: it
 * only schedules; T229's rule, nothing of it runs on the main thread), a thread of its own (`dystopo-sampler`) writes one
 * line ([DumpFormat.sampleLine]) [PERIOD_S] seconds apart, the first [FIRST_S] seconds after the process starts, while the
 * process lives (in the background too, so a recording with the screen off is covered; a frozen or sleeping process takes
 * no samples), to the ring file `files/diagnostics/samples.txt`: the newest [KEEP] lines, about a day; the file is cut
 * back to them once it holds [KEEP] + [SLACK] lines, so it is rewritten about once an hour.
 *
 * A line's keys (`docs/reference/diagnostic-dump.md`): `t` (epoch ms), `up` (seconds since the process started), `pid`,
 * `heapKb` (the Java heap in use), `nativeKb` (native allocated), `pssKb` (`Debug.getMemoryInfo`, this process only),
 * `threads` and `fds` (`/proc/self/task` and `/proc/self/fd`), the tile counters since the process started (`demServed`,
 * `osmServed`, `contourServed`, `tileKb`, `tileErrors`), `cacheRuns` (the cache budget's runs), `trims` (the heap guard's),
 * `fg` (1 while the map is on screen), `rec` (the recorder's state, `-` before the tracker is ready) and `cpuUs` (this
 * thread's CPU time for the sample, the file write left out). No setting: it is the dump's only memory history, it stays on
 * the phone, and its cost is measured in the T258 report.
 */
object MemorySampler {
    const val PERIOD_S = 60L
    const val FIRST_S = 15L
    const val KEEP = 1440
    const val SLACK = 60
    const val DIR = "diagnostics"
    const val FILE = "samples.txt"
    private const val TAG = "DystopoSampler"

    @Volatile private var exec: ScheduledExecutorService? = null
    @Volatile private var file: File? = null
    private val lock = Any()
    private var lineCount = -1 // under [lock]; -1 until the file is first read

    val samples = AtomicLong()
    val errors = AtomicLong()
    val rewrites = AtomicLong()
    private val cpuNsTotal = AtomicLong()
    @Volatile var lastCpuUs = 0L
        private set
    @Volatile var maxCpuUs = 0L
        private set
    @Volatile var lastWallMs = 0L
        private set
    @Volatile var lastAt = 0L
        private set

    /** Schedules the samples (a few microseconds on the caller's thread; the work runs on the sampler's). */
    @Synchronized
    fun start(app: DystopoApp) {
        if (exec != null) return
        file = File(app.filesDir, "$DIR/$FILE")
        exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dystopo-sampler").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
            .also { it.scheduleWithFixedDelay({ runCatching { sample(app) }.onFailure { e -> errors.incrementAndGet(); Log.w(TAG, "sample failed", e) } }, FIRST_S, PERIOD_S, TimeUnit.SECONDS) }
    }

    /** One sample now, on the sampler's thread, waited for (`POST /debug/dump/sample`); the line written. */
    fun sampleNow(app: DystopoApp): String? = exec?.submit<String?> { sample(app) }?.get(30, TimeUnit.SECONDS)

    /** The figures now, as a sample line. */
    fun line(app: DystopoApp): String {
        val cpu0 = Debug.threadCpuTimeNanos()
        val rt = Runtime.getRuntime()
        val pss = runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong() }.getOrNull()
        val c = app.tileCounters
        val tileBytes = c.dem.bytes.get() + c.osm.bytes.get() + c.contours.bytes.get()
        val values = linkedMapOf<String, Any?>(
            "t" to System.currentTimeMillis(),
            "up" to (SystemClock.elapsedRealtime() - app.startedAt) / 1000,
            "pid" to Process.myPid(),
            "heapKb" to (rt.totalMemory() - rt.freeMemory()) / 1024,
            "nativeKb" to Debug.getNativeHeapAllocatedSize() / 1024,
            "pssKb" to pss,
            "threads" to File("/proc/self/task").list()?.size,
            "fds" to File("/proc/self/fd").list()?.size,
            "demServed" to c.dem.served.get(), "osmServed" to c.osm.served.get(), "contourServed" to c.contours.served.get(),
            "tileKb" to tileBytes / 1024,
            "tileErrors" to c.dem.errors.get() + c.osm.errors.get() + c.contours.errors.get(),
            "cacheRuns" to CacheBudget.runs.get(),
            "trims" to HeapGuard.trims.get(),
            "fg" to if (runCatching { app.map.backgroundReason() }.getOrDefault("?") == null) 1 else 0,
            "rec" to (Tracking.readyOrNull?.state?.code ?: "-"),
        )
        val cpuUs = (Debug.threadCpuTimeNanos() - cpu0) / 1000
        values["cpuUs"] = cpuUs
        return DumpFormat.sampleLine(values)
    }

    private fun sample(app: DystopoApp): String {
        val cpu0 = Debug.threadCpuTimeNanos()
        val w0 = SystemClock.elapsedRealtime()
        val line = line(app)
        append(line)
        val cpu = Debug.threadCpuTimeNanos() - cpu0
        cpuNsTotal.addAndGet(cpu)
        lastCpuUs = cpu / 1000
        if (lastCpuUs > maxCpuUs) maxCpuUs = lastCpuUs
        lastWallMs = SystemClock.elapsedRealtime() - w0
        lastAt = System.currentTimeMillis()
        samples.incrementAndGet()
        return line
    }

    private fun append(line: String) = synchronized(lock) {
        val f = file ?: return@synchronized
        f.parentFile?.mkdirs()
        if (lineCount < 0) lineCount = runCatching { f.useLines { s -> s.count { it.isNotBlank() } } }.getOrDefault(0)
        f.appendText(line + "\n")
        lineCount++
        if (lineCount >= KEEP + SLACK) {
            val keep = f.readLines().filter { it.isNotBlank() }.takeLast(KEEP)
            Reports.write(f, keep.joinToString("\n") + "\n")
            lineCount = keep.size
            rewrites.incrementAndGet()
        }
    }

    /** The ring's lines, oldest first (any thread; waits for a sample being written). */
    fun lines(): List<String> = synchronized(lock) {
        val f = file ?: return emptyList()
        runCatching { f.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList()).takeLast(KEEP)
    }

    /** `GET /debug/dump/sampler`. */
    fun json(): Map<String, Any?> {
        val f = file
        val n = samples.get()
        return linkedMapOf(
            "started" to (exec != null), "periodS" to PERIOD_S, "firstS" to FIRST_S, "keep" to KEEP, "slack" to SLACK,
            "file" to f?.path, "fileBytes" to f?.length(), "fileLines" to synchronized(lock) { lineCount },
            "samples" to n, "errors" to errors.get(), "rewrites" to rewrites.get(),
            "cpuUsLast" to lastCpuUs, "cpuUsMax" to maxCpuUs, "cpuUsMean" to if (n > 0) cpuNsTotal.get() / n / 1000 else null,
            "wallMsLast" to lastWallMs, "lastAt" to lastAt.takeIf { it > 0 },
        )
    }
}
