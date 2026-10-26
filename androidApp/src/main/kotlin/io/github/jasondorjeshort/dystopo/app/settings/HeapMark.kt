package io.github.jasondorjeshort.dystopo.app.settings

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import java.util.concurrent.Executors

/**
 * T143: the heap's high-water mark kept across launches, so after a kill the Diagnostics row says what the previous
 * session reached. A small preferences file ([PREFS]) holds this session's mark: the peak Java heap, the peak native
 * heap and PSS where they were read, the last `onTrimMemory` level and its time, and when the session started.
 *
 * Event-driven, no timer (the heap research of wave 20): [offer] is called by `HeapGuard.sample` when the process's
 * peak rises (the render burst sampler, admissions, render ends, a trim signal, the row opening), [onSignal] by
 * `HeapGuard.onTrimMemory` at every level. The file is written only when [Rule.due] says so (the peak [Rule.RISE_BYTES]
 * above what was written, at most every [Rule.GAP_MS]), plus once at each `TRIM_MEMORY_UI_HIDDEN` (with the PSS, read
 * then). Raise-only: a write never lowers a peak this session wrote. Disk work runs on its own thread, never the caller's
 * (`onTrimMemory` arrives on the main thread).
 *
 * The file is read once per process, before this session's first write: that reading is [previous], the mark of the
 * session before (its `sessionStartedAt` says which), `GET /diagnostics` `persisted`. `POST /diagnostics/reset` ([reset])
 * clears it.
 */
object HeapMark {
    const val PREFS = "heap-mark"

    /** The write rule (pure, `DiagnosticsTest`). */
    object Rule {
        const val RISE_BYTES = 5L shl 20
        const val GAP_MS = 10_000L

        /** Whether a peak of [peak] is written now: [RISE_BYTES] above [writtenPeak] and [GAP_MS] since [lastWriteAt]. */
        fun due(peak: Long, writtenPeak: Long, now: Long, lastWriteAt: Long): Boolean =
            peak >= writtenPeak + RISE_BYTES && now - lastWriteAt >= GAP_MS

        /** What a write keeps: never below what this session wrote (raise-only), unknown values kept from before. */
        fun raise(old: Mark?, new: Mark): Mark = if (old == null || old.sessionStartedAt != new.sessionStartedAt) new else new.copy(
            peakHeapBytes = maxOf(old.peakHeapBytes, new.peakHeapBytes),
            peakNativeBytes = maxOrNull(old.peakNativeBytes, new.peakNativeBytes),
            peakPssKb = maxOrNull(old.peakPssKb, new.peakPssKb),
            lastSignalLevel = new.lastSignalLevel ?: old.lastSignalLevel,
            lastSignalAt = new.lastSignalAt ?: old.lastSignalAt,
        )

        private fun maxOrNull(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else maxOf(a, b)
    }

    /** One session's mark. Times are wall-clock milliseconds. */
    data class Mark(
        val peakHeapBytes: Long,
        val peakNativeBytes: Long?,
        val peakPssKb: Long?,
        val lastSignalLevel: Int?,
        val lastSignalAt: Long?,
        val heapMaxBytes: Long,
        val sessionStartedAt: Long,
        val writtenAt: Long,
        val version: String,
    ) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "peakHeapBytes" to peakHeapBytes, "peakNativeBytes" to peakNativeBytes, "peakPssKb" to peakPssKb,
            "lastSignalLevel" to lastSignalLevel, "lastSignalAt" to lastSignalAt, "heapMaxBytes" to heapMaxBytes,
            "sessionStartedAt" to sessionStartedAt, "writtenAt" to writtenAt, "version" to version,
        )
    }

    private val lock = Object()
    private val diskThread = Executors.newSingleThreadExecutor { r -> Thread(r, "heap-mark").apply { isDaemon = true } }

    // Under [lock]: what this session wrote last, and the file's mark from before this session (read once).
    private var loaded = false
    private var previousMark: Mark? = null
    private var written: Mark? = null
    @Volatile private var writtenPeak = 0L
    @Volatile private var lastWriteAt = 0L
    @Volatile private var pending = false
    @Volatile private var peakPssKb: Long? = null
    @Volatile private var peakNativeBytes: Long? = null
    val writes = java.util.concurrent.atomic.AtomicLong()

    /** When this process started, in wall-clock time (the mark's session). */
    val sessionStartedAt: Long by lazy { System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) }

    private fun prefs(): SharedPreferences? = runCatching { DystopoApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

    /** A new peak of the process's heap ([HeapGuard.sample]): cheap unless a write is due ([Rule.due]), which runs on its own thread. */
    fun offer(peak: Long) {
        val now = System.currentTimeMillis()
        if (pending || !Rule.due(peak, writtenPeak, now, lastWriteAt)) return
        pending = true
        lastWriteAt = now // the gap counts from the decision, so a burst queues one write
        diskThread.execute { try { write(now, null) } finally { pending = false } }
    }

    /** An `onTrimMemory` [level] at [at] (on the trim thread): kept for the next write; at `TRIM_MEMORY_UI_HIDDEN` written now, with the PSS. */
    fun onSignal(level: Int, at: Long, peak: Long) {
        signal = level to at
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) diskThread.execute {
            notePss(readPssKb())
            write(at, peak)
        }
    }

    @Volatile private var signal: Pair<Int, Long>? = null

    /** A PSS reading (the row's, or the UI_HIDDEN write's), kept as the session's peak where higher. */
    fun notePss(kb: Long?) {
        if (kb == null || kb <= 0) return
        val p = peakPssKb
        if (p == null || kb > p) peakPssKb = kb
    }

    private fun readPssKb(): Long? = runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong() }.getOrNull()

    private fun nativeNow(): Long? = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrNull()

    /** Writes this session's mark (raise-only); [peak] null reads the heap guard's. */
    private fun write(now: Long, peak: Long?) {
        val p = prefs() ?: return
        synchronized(lock) {
            load(p)
            nativeNow()?.let { n -> val o = peakNativeBytes; if (o == null || n > o) peakNativeBytes = n }
            val heapPeak = peak ?: io.github.jasondorjeshort.dystopo.app.online.HeapGuard.peakBytes
            val s = signal
            val mark = Rule.raise(written, Mark(heapPeak, peakNativeBytes, peakPssKb, s?.first, s?.second, Runtime.getRuntime().maxMemory(), sessionStartedAt, now, BuildConfig.VERSION_NAME))
            save(p, mark)
            written = mark
            writtenPeak = mark.peakHeapBytes
            lastWriteAt = now
            writes.incrementAndGet()
        }
    }

    private fun load(p: SharedPreferences) {
        if (loaded) return
        loaded = true
        previousMark = read(p)
    }

    /** The mark of the session before this one (read once per process, before this session's first write), or null. */
    fun previous(): Mark? {
        val p = prefs() ?: return null
        return synchronized(lock) { load(p); previousMark }
    }

    /** What this session has written so far, or null. */
    fun current(): Mark? = synchronized(lock) { written }

    /** `POST /diagnostics/reset`: the file and both marks cleared; this session writes again from its next rise. */
    fun reset() {
        val p = prefs()
        synchronized(lock) {
            p?.let { it.edit().clear().commit(); loaded = true }
            previousMark = null
            written = null
            writtenPeak = 0
            lastWriteAt = 0
            peakPssKb = null
            peakNativeBytes = null
        }
    }

    private fun save(p: SharedPreferences, m: Mark) {
        p.edit()
            .putLong("peakHeapBytes", m.peakHeapBytes)
            .putLong("peakNativeBytes", m.peakNativeBytes ?: -1)
            .putLong("peakPssKb", m.peakPssKb ?: -1)
            .putInt("lastSignalLevel", m.lastSignalLevel ?: -1)
            .putLong("lastSignalAt", m.lastSignalAt ?: -1)
            .putLong("heapMaxBytes", m.heapMaxBytes)
            .putLong("sessionStartedAt", m.sessionStartedAt)
            .putLong("writtenAt", m.writtenAt)
            .putString("version", m.version)
            .apply()
    }

    private fun read(p: SharedPreferences): Mark? = runCatching {
        if (!p.contains("peakHeapBytes")) return null
        fun l(k: String) = p.getLong(k, -1).takeIf { it >= 0 }
        Mark(
            p.getLong("peakHeapBytes", 0), l("peakNativeBytes"), l("peakPssKb"), p.getInt("lastSignalLevel", -1).takeIf { it >= 0 }, l("lastSignalAt"),
            p.getLong("heapMaxBytes", 0), p.getLong("sessionStartedAt", 0), p.getLong("writtenAt", 0), p.getString("version", "") ?: "",
        )
    }.onFailure { Log.w("DystopoHeap", "heap mark unreadable", it) }.getOrNull()
}
