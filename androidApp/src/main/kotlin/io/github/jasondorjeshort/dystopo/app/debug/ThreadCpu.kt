package io.github.jasondorjeshort.dystopo.app.debug

import android.os.SystemClock
import java.io.File

/**
 * T150: per-thread CPU ticks of this process, from `/proc/self/task/<tid>/stat`. The parsing and the delta are pure, tested.
 * In every build since T228 (the start trace samples the main thread's state and CPU; `GET /debug/cpu` is debug only).
 */
object ThreadCpu {
    /** [state] is the `stat` line's own letter (R running or runnable, S sleeping, D uninterruptible disk wait). */
    class Ticks(val tid: Int, val name: String, val utime: Long, val stime: Long, val main: Boolean = false, val state: Char = '?') {
        val ticks: Long get() = utime + stime
        fun json(): Map<String, Any?> = linkedMapOf("tid" to tid, "name" to name, "main" to main, "utime" to utime, "stime" to stime, "ticks" to ticks)
    }

    class Snapshot(val atMs: Long, val process: Ticks?, val threads: List<Ticks>)

    val clockTicks: Long by lazy { runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }.getOrDefault(100L).takeIf { it > 0 } ?: 100L }

    fun read(): Snapshot {
        val pid = android.os.Process.myPid()
        val threads = File("/proc/self/task").listFiles().orEmpty().mapNotNull { dir ->
            runCatching { parse(File(dir, "stat").readText(), pid) }.getOrNull()
        }
        val process = runCatching { parse(File("/proc/self/stat").readText(), -1) }.getOrNull()
        return Snapshot(SystemClock.elapsedRealtime(), process, threads)
    }

    /** T228: one thread's line (the main thread's id is the process id), or null. */
    fun readThread(tid: Int): Ticks? = runCatching { parse(File("/proc/self/task/$tid/stat").readText(), tid) }.getOrNull()

    /**
     * One `stat` line: `tid (comm) state ...` with `utime` and `stime` the 14th and 15th fields. The name sits between the
     * first `(` and the last `)` (a thread name may hold either). [mainTid]: the UI thread's id (the process id).
     */
    fun parse(line: String, mainTid: Int): Ticks? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close < open) return null
        val tid = line.substring(0, open).trim().toIntOrNull() ?: return null
        val rest = line.substring(close + 1).trim().split(' ')
        // rest[0] is field 3 (state), so field n is rest[n - 3].
        val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return Ticks(tid, line.substring(open + 1, close), utime, stime, tid == mainTid, rest[0].firstOrNull() ?: '?')
    }

    /** Ticks and percent of one core per thread between [a] and [b]: those that used any, plus the UI thread and the GC daemon. */
    fun delta(a: Snapshot, b: Snapshot, ticksPerSecond: Long): Map<String, Any?> {
        val ms = (b.atMs - a.atMs).coerceAtLeast(1)
        val before = a.threads.associateBy { it.tid }
        fun pct(t: Long) = Math.round(t * 1000.0 * 100.0 / ticksPerSecond / ms * 100) / 100.0
        val rows = b.threads.mapNotNull { t ->
            val d = t.ticks - (before[t.tid]?.ticks ?: 0L)
            if (d <= 0 && !t.main && t.name != "HeapTaskDaemon") null
            else linkedMapOf("tid" to t.tid, "name" to t.name, "main" to t.main, "ticks" to d, "percent" to pct(d), "new" to (t.tid !in before))
        }.sortedByDescending { it["ticks"] as Long }
        val proc = if (a.process != null && b.process != null) b.process.ticks - a.process.ticks else null
        return linkedMapOf(
            "ms" to ms,
            "processTicks" to proc, "processPercent" to proc?.let { pct(it) },
            "threads" to rows,
        )
    }
}
