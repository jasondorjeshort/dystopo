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
 */
object StrictModeCounts {
    const val APP_PACKAGE = "io.github.jasondorjeshort.dystopo"
    private const val TAG = "DystopoStrictMode"

    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val exempt = AtomicLong()
    private val exemptKinds = ConcurrentHashMap<String, AtomicLong>()
    @Volatile var installed = false
        private set

    /** Main thread, from `DystopoApp.onCreate` in debug builds. */
    fun install() {
        if (Build.VERSION.SDK_INT < 28 || installed) return
        val direct = Executor { it.run() }
        val thread = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls().detectResourceMismatches().detectUnbufferedIo()
            .penaltyListener(direct) { v -> record(v.javaClass.simpleName, v.stackTrace) }
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
            "byFrame" to rows.map { (kind, frame, n) -> linkedMapOf("frame" to frame, "kind" to kind, "count" to n) },
        )
    }

    /** `POST /debug/strictmode?clear=1` and tests. */
    fun clear() {
        counts.clear()
        exempt.set(0)
        exemptKinds.clear()
    }
}
