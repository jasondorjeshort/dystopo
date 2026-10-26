package io.github.jasondorjeshort.dystopo.app.settings

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.online.HeapGuard
import io.github.jasondorjeshort.dystopo.reports.Scrub
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * T143: what Settings › About and advanced › Diagnostics shows ([DiagnosticsRow]), `GET /diagnostics` returns and
 * Copy report puts on the clipboard, on every build. Read-only; nothing leaves the phone unless the user copies it.
 *
 * [read] takes one [Snapshot] (on a worker thread: the PSS walks the process's maps, tens of ms, timed in `pssReadMs`)
 * when the row opens and on Refresh; never polled. The numbers are the ones `GET /health` `memory` reports (the Java
 * heap from `Runtime`, native allocated and PSS from `Debug`, the phone's `availMem` and low-memory flag), the heap
 * guard's counters ([HeapGuard]), the previous process from `ApplicationExitInfo` (API 30 and up: the reason, and RSS
 * and PSS at the system's last sample, not at its death) and the persisted mark of the session before ([HeapMark]).
 * [lines] and [report] are pure (`DiagnosticsTest`); the report adds the app version, build type, Android version,
 * device model, `memoryClass`, `largeMemoryClass` and the large-heap flag, and passes through [Scrub.text].
 */
object Diagnostics {
    /** The previous process's end, from `ApplicationExitInfo`. Sizes in kB as the system gives them (0: not sampled). */
    data class Exit(val reason: Int, val reasonName: String, val at: Long, val rssKb: Long, val pssKb: Long, val description: String?)

    data class Snapshot(
        val at: Long,
        val heapMaxBytes: Long,
        val heapUsedBytes: Long,
        val peakHeapBytes: Long,
        val nativeAllocatedBytes: Long,
        val pssKb: Long?,
        val pssReadMs: Long?,
        val availMemBytes: Long,
        val lowMemory: Boolean,
        val rendersAdmitted: Long,
        val rendersWaited: Long,
        val trims: Long,
        val droppedBytes: Long,
        val lastTrimAt: Long?,
        val signals: Long,
        val lastSignalLevel: Int?,
        val lastSignalAt: Long?,
        /** Null below API 30 ([exitSupported] false) or when the system kept no record. */
        val exit: Exit?,
        val exitSupported: Boolean,
        val previous: HeapMark.Mark?,
        val current: HeapMark.Mark?,
        val version: String,
        val versionCode: Long,
        val buildType: String,
        val android: String,
        val sdk: Int,
        val device: String,
        val memoryClassMb: Int,
        val largeMemoryClassMb: Int,
        val largeHeap: Boolean,
    )

    /** A snapshot now (a worker thread: not the UI thread). Also a heap sample, so the peak counts this reading. */
    fun read(context: Context): Snapshot {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        HeapGuard.sample(used)
        val t0 = System.nanoTime()
        val pss = runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong() }.getOrNull()
        val pssMs = (System.nanoTime() - t0) / 1_000_000
        HeapMark.notePss(pss)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val sys = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Snapshot(
            at = System.currentTimeMillis(),
            heapMaxBytes = rt.maxMemory(), heapUsedBytes = used, peakHeapBytes = HeapGuard.peakBytes,
            nativeAllocatedBytes = Debug.getNativeHeapAllocatedSize(), pssKb = pss, pssReadMs = pssMs,
            availMemBytes = sys.availMem, lowMemory = sys.lowMemory,
            rendersAdmitted = HeapGuard.rendersAdmitted.get(), rendersWaited = HeapGuard.rendersWaited.get(),
            trims = HeapGuard.trims.get(), droppedBytes = HeapGuard.droppedBytes.get(), lastTrimAt = HeapGuard.lastTrimAt.takeIf { it > 0 },
            signals = HeapGuard.signals.get(), lastSignalLevel = HeapGuard.lastSignalLevel.takeIf { it >= 0 }, lastSignalAt = HeapGuard.lastSignalAt.takeIf { it > 0 },
            exit = previousExit(context, am), exitSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            previous = HeapMark.previous(), current = HeapMark.current(),
            version = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE.toLong(), buildType = BuildConfig.BUILD_TYPE,
            android = Build.VERSION.RELEASE ?: "", sdk = Build.VERSION.SDK_INT, device = deviceName(),
            memoryClassMb = am.memoryClass, largeMemoryClassMb = am.largeMemoryClass,
            largeHeap = (context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0,
        )
    }

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return if (maker.isEmpty() || model.startsWith(maker, ignoreCase = true)) model else "${maker.replaceFirstChar { it.uppercase() }} $model"
    }

    /** The last record the system keeps of this package's processes (API 30 and up; the running one is not among them). */
    private fun previousExit(context: Context, am: ActivityManager): Exit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val e = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull() ?: return null
        return Exit(e.reason, reasonName(e.reason), e.timestamp, e.rss, e.pss, e.description)
    }

    /** `ApplicationExitInfo.REASON_*` in words (the constants' values, so this compiles and reads on every API). */
    fun reasonName(reason: Int): String = when (reason) {
        1 -> "the app exited"
        2 -> "stopped by a signal"
        3 -> "closed by the system for memory"
        4 -> "crashed"
        5 -> "crashed in native code"
        6 -> "not responding (ANR)"
        7 -> "failed to start"
        8 -> "a permission changed"
        9 -> "used too many resources"
        10 -> "force-stopped"
        11 -> "stopped by the user"
        12 -> "a process it depends on stopped"
        13 -> "other"
        14 -> "frozen by the system"
        15 -> "the app was disabled or changed"
        16 -> "the app was updated"
        else -> "unknown"
    }

    /** `ComponentCallbacks2.TRIM_MEMORY_*` in words. */
    fun levelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "running moderate"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "running low"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "running critical"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "app hidden"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "background"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "moderate"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "complete"
        else -> "other"
    }

    fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    private fun kbMb(kb: Long): String = mb(kb * 1024)
    fun time(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    /** One line of the row: its label and value, drawn and copied as "label: value". */
    data class Line(val label: String, val value: String) {
        override fun toString() = "$label: $value"
    }

    /** The row's lines now: the live numbers and the counters. */
    fun nowLines(s: Snapshot): List<Line> = listOf(
        Line("Max heap", mb(s.heapMaxBytes)),
        Line("Heap used now", mb(s.heapUsedBytes)),
        Line("Peak since launch", mb(s.peakHeapBytes)),
        Line("Native heap", mb(s.nativeAllocatedBytes)),
        Line("PSS", s.pssKb?.let { kbMb(it) } ?: "not read"),
        Line("Phone memory free", mb(s.availMemBytes) + if (s.lowMemory) " (low)" else ""),
        Line("Map renders", "${s.rendersAdmitted} started, ${s.rendersWaited} waited for memory"),
        Line("Trims", "${s.trims}, ${mb(s.droppedBytes)} dropped" + (s.lastTrimAt?.let { ", last at ${time(it)}" } ?: "")),
        Line("Last memory signal", s.lastSignalLevel?.let { l -> "level $l (${levelName(l)}) at ${time(s.lastSignalAt ?: 0)}" } ?: "none"),
    )

    /** The row's lines about the session before: how it ended and the mark it left. */
    fun lastSessionLines(s: Snapshot): List<Line> {
        val out = ArrayList<Line>()
        out += when {
            !s.exitSupported -> Line("Ended", "not reported before Android 11")
            s.exit == null -> Line("Ended", "no record")
            else -> Line("Ended", "${s.exit.reasonName} at ${time(s.exit.at)}")
        }
        if (s.exit != null && (s.exit.rssKb > 0 || s.exit.pssKb > 0)) {
            out += Line("Memory at the last system sample", "RSS ${kbMb(s.exit.rssKb)}, PSS ${kbMb(s.exit.pssKb)}")
        }
        val m = s.previous
        if (m == null) out += Line("Peak heap", "no record")
        else {
            out += Line("Peak heap", "${mb(m.peakHeapBytes)} of ${mb(m.heapMaxBytes)}")
            m.peakNativeBytes?.let { out += Line("Peak native heap", mb(it)) }
            m.peakPssKb?.let { out += Line("Peak PSS", kbMb(it)) }
            out += Line("Last memory signal", m.lastSignalLevel?.let { l -> "level $l (${levelName(l)}) at ${time(m.lastSignalAt ?: 0)}" } ?: "none")
            out += Line("Session", "started ${time(m.sessionStartedAt)}, recorded ${time(m.writtenAt)}, version ${m.version}")
        }
        return out
    }

    /** The heading above [lastSessionLines]. */
    const val LAST_SESSION = "Last session"

    /** Copy report: the same lines with the build and the phone, plain text, through [Scrub.text]. */
    fun report(s: Snapshot): String {
        val text = buildString {
            append("Dystopo diagnostics, ").append(time(s.at)).append('\n')
            append("App: ").append(s.version).append(" (").append(s.versionCode).append("), ").append(s.buildType).append('\n')
            append("Android ").append(s.android).append(" (API ").append(s.sdk).append("), ").append(s.device).append('\n')
            append("Memory class: ").append(s.memoryClassMb).append(" MB, large memory class: ").append(s.largeMemoryClassMb)
                .append(" MB, large heap: ").append(if (s.largeHeap) "yes" else "no").append('\n')
            for (l in nowLines(s)) append(l).append('\n')
            append(LAST_SESSION).append('\n')
            for (l in lastSessionLines(s)) append(l).append('\n')
        }
        return Scrub.text(text)
    }

    /** `GET /diagnostics`: the snapshot's numbers, the row's text and the report as Copy report puts it. */
    fun json(s: Snapshot): Map<String, Any?> = linkedMapOf(
        "at" to s.at,
        "heapMaxBytes" to s.heapMaxBytes, "heapUsedBytes" to s.heapUsedBytes, "peakHeapBytes" to s.peakHeapBytes,
        "nativeAllocatedBytes" to s.nativeAllocatedBytes, "pssKb" to s.pssKb, "pssReadMs" to s.pssReadMs,
        "availMemBytes" to s.availMemBytes, "lowMemory" to s.lowMemory,
        "rendersAdmitted" to s.rendersAdmitted, "rendersWaited" to s.rendersWaited,
        "trims" to s.trims, "droppedBytes" to s.droppedBytes, "lastTrimAt" to s.lastTrimAt,
        "signals" to s.signals, "lastSignalLevel" to s.lastSignalLevel, "lastSignalName" to s.lastSignalLevel?.let { levelName(it) }, "lastSignalAt" to s.lastSignalAt,
        "exitSupported" to s.exitSupported,
        "exit" to s.exit?.let { linkedMapOf("reason" to it.reason, "reasonName" to it.reasonName, "at" to it.at, "rssKb" to it.rssKb, "pssKb" to it.pssKb, "description" to it.description?.let { d -> Scrub.text(d) }) },
        "persisted" to s.previous?.json(),
        "markNow" to s.current?.json(),
        "markWrites" to HeapMark.writes.get(),
        "system" to linkedMapOf(
            "version" to s.version, "versionCode" to s.versionCode, "buildType" to s.buildType, "android" to s.android, "sdk" to s.sdk,
            "device" to s.device, "memoryClassMb" to s.memoryClassMb, "largeMemoryClassMb" to s.largeMemoryClassMb, "largeHeap" to s.largeHeap,
        ),
        "lines" to nowLines(s).map { it.toString() },
        "lastSession" to lastSessionLines(s).map { it.toString() },
        "report" to report(s),
    )
}
