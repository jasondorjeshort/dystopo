package io.github.jasondorjeshort.dystopo.app.debug

import android.os.Build
import android.os.StrictMode
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * T150: StrictMode in debug builds only (deferred from wave 20 with its bounded rule; release builds never call [install]).
 * The thread policy (the main thread: disk reads and writes, network, slow calls, resource mismatches, unbuffered I/O) and
 * the VM policy (leaked closables, SQLite objects and registrations, activity leaks, file URIs, content URIs without
 * permission, credential-protected storage while locked, and from Android 12 incorrect context use and unsafe intent
 * launches) report to one penalty listener and nothing else: no log flood, no dialog, no crash. Untagged sockets and
 * cleartext traffic are not detected: the loopback servers and LAN sharing are plain HTTP on purpose.
 *
 * The listener counts a violation by the topmost frame of its stack in the app's own package ([appFrame]) and its kind;
 * a violation with no such frame (MapLibre's, Compose's or the platform's own) is exempt, counted only as a total.
 * `GET /debug/strictmode` is [json]; `POST /debug/strictmode/violate` reads a file on the main thread for the check.
 * The listener needs Android 9 (API 28); below that nothing is installed (`installed` false).
 *
 * T266 (ADR 0061): each thread-policy violation (the main thread's) is also counted under the launch stage it happened in
 * ([stageOf], `AppLaunch.stageOf`), exempt or not, by kind: `GET /debug/launch` gives each stage's counts and the kinds its
 * declared work does not allow; [json] gives them as `byStage`. The platform hands a main-thread violation to the listener
 * only after the message it happened in (StrictMode times it first), so the stage is read from the violation's own stack
 * (the binding's body, the frame right above the runner's), not from what runs when the listener is called.
 */
object StrictModeCounts {
    const val APP_PACKAGE = "io.github.jasondorjeshort.dystopo"
    private const val TAG = "DystopoStrictMode"

    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val exempt = AtomicLong()
    private val exemptKinds = ConcurrentHashMap<String, AtomicLong>()
    @Volatile var installed = false
        private set

    /** T266: the launch stage in force on the main thread (set by `AppLaunch.install`); null leaves violations unattributed. */
    @Volatile var stageOf: (Array<StackTraceElement>) -> String? = { null }
    private val stages = ConcurrentHashMap<String, AtomicLong>()

    /** Main thread, from `DystopoApp.onCreate` in debug builds. */
    fun install() {
        if (Build.VERSION.SDK_INT < 28 || installed) return
        val direct = Executor { it.run() }
        val thread = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls().detectResourceMismatches().detectUnbufferedIo()
            .penaltyListener(direct) { v -> recordStage(v.javaClass.simpleName, v.stackTrace); record(v.javaClass.simpleName, v.stackTrace) }
            .build()
        val vm = StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects().detectLeakedSqlLiteObjects().detectLeakedRegistrationObjects().detectActivityLeaks()
            .detectFileUriExposure().detectContentUriWithoutPermission()
            .apply { if (Build.VERSION.SDK_INT >= 29) detectCredentialProtectedWhileLocked() }
            .apply { if (Build.VERSION.SDK_INT >= 31) { detectIncorrectContextUse(); detectUnsafeIntentLaunch() } }
            .penaltyListener(direct) { v -> record(v.javaClass.simpleName, v.stackTrace) }
            .build()
        StrictMode.setThreadPolicy(thread)
        StrictMode.setVmPolicy(vm)
        installed = true
        Log.i(TAG, "StrictMode installed (penalty listener only)")
    }

    /** Counts one violation of [kind] with [stack]; returns the frame it was counted under, or null when exempt. */
    fun record(kind: String, stack: Array<StackTraceElement>): String? {
        val frame = appFrame(stack.map { it.toString() })
        if (frame == null) {
            exempt.incrementAndGet()
            exemptKinds.getOrPut(kind) { AtomicLong() }.incrementAndGet()
            return null
        }
        counts.getOrPut("$kind\u0000$frame") { AtomicLong() }.incrementAndGet()
        return frame
    }

    /** T266: one main-thread violation of [kind] under the stage in force. */
    fun recordStage(kind: String, stack: Array<StackTraceElement>) {
        val stage = runCatching { stageOf(stack) }.getOrNull() ?: return
        stages.getOrPut("$stage|$kind") { AtomicLong() }.incrementAndGet()
    }

    /** T266: the main thread's violations by launch stage, then kind. */
    fun byStage(): Map<String, Map<String, Long>> {
        val out = LinkedHashMap<String, MutableMap<String, Long>>()
        for ((k, n) in stages.entries.sortedBy { it.key }) {
            val stage = k.substringBeforeLast('|'); val kind = k.substringAfterLast('|')
            out.getOrPut(stage) { LinkedHashMap() }[kind] = n.get()
        }
        return out
    }

    /** The topmost frame (first line) of [stack] in the app's own package, or null. Pure, tested. */
    fun appFrame(stack: List<String>): String? = stack.firstOrNull { it.startsWith("$APP_PACKAGE.") }

    /** `GET /debug/strictmode`: counted violations by kind and app frame (most first), the exempt total and its kinds. */
    fun json(): Map<String, Any?> {
        val rows = counts.entries.map { (k, n) -> k.split('\u0000').let { (kind, frame) -> Triple(kind, frame, n.get()) } }
            .sortedWith(compareByDescending<Triple<String, String, Long>> { it.third }.thenBy { it.second })
        return linkedMapOf(
            "installed" to installed,
            "counted" to rows.sumOf { it.third },
            "exempt" to exempt.get(),
            "exemptByKind" to exemptKinds.mapValues { it.value.get() }.toSortedMap(),
            "byStage" to byStage(), // T266
            "byFrame" to rows.map { (kind, frame, n) -> linkedMapOf("frame" to frame, "kind" to kind, "count" to n) },
        )
    }

    /** `POST /debug/strictmode?clear=1` and tests. */
    fun clear() {
        counts.clear()
        exempt.set(0)
        exemptKinds.clear()
        stages.clear()
    }
}
