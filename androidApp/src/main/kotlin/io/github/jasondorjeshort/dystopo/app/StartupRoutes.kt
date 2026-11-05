package io.github.jasondorjeshort.dystopo.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/**
 * T228, the start trace's debug routes (debug builds only, on the debug port; [StartupTrace]):
 * - `GET /debug/startup`: `state` (`open`, `closed`, `none`), `open`, `window` (the open window, else the last one closed:
 *   `id`, `kind`, `kindSource`, `outcome`, `elapsedMs`, `firstFrameMs`, `styleLoadedMs`, `fullyLoadedMs`, `longestStallMs`,
 *   `mapNotDrawn`, `thresholdMs`, `reasons`, `marks` with `name`, `atMs`, `ms`, `samples`, `cpuMs`, `states`, `frame`; `stalls`
 *   with `atMs`, `ms`, `mark`, `frame`, `top`, `samples`, `cpuMs`, `states`, `ended`; `snapshots`; `lockDumps`), `head` (the header's phone
 *   state), `thresholds`, `tuning`, `pendingStall`, `history`, `traces`, `progress`.
 * - `POST /debug/startup/stall?at=appOnCreate|activityOnCreate|onStart|afterFirstFrame&ms=1..120000`: holds the main thread
 *   that long at that point of the next launch, then is forgotten (kept in a file, so a force-stopped process's next one sees
 *   it; the same process's next hot or warm start sees it too); the stack names [hold]. `&lock=1`: the main thread waits on a
 *   monitor a thread `startup-test-holder` holds for that long, instead of sleeping ([holdLock] on both stacks). `?clear=1` forgets it.
 * - `POST /debug/startup/thresholds?coldMs=&warmMs=&hotMs=&stallMs=&mapMs=`: overrides what counts as slow, kept across launches
 *   until `?clear=1`.
 */
object StartupRoutes {
    private const val FILE = ".startup-test"
    private val POINTS = setOf("appOnCreate", "activityOnCreate", "onStart", "afterFirstFrame")

    private class Pending(val at: String, val ms: Long, val lock: Boolean)

    @Volatile private var pending: Pending? = null
    private var loaded = false

    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/debug/startup" -> Response.json(StartupTrace.json())
        "POST" to "/debug/startup/stall" -> stall(app, r)
        "POST" to "/debug/startup/thresholds" -> thresholds(app, r)
        else -> null
    }

    private fun file(app: DystopoApp) = File(File(app.filesDir, MainWatchdog.REPORTS_DIR).also { it.mkdirs() }, FILE)

    private fun stall(app: DystopoApp, r: Request): Response {
        if (r.query["clear"] == "1") {
            pending = null
            save(file(app))
            return Response.json(mapOf("pendingStall" to null))
        }
        val at = r.query["at"]?.takeIf { it in POINTS } ?: return Response.json(mapOf("error" to "at must be one of $POINTS"), 400)
        val ms = r.query["ms"]?.toLongOrNull()?.takeIf { it in 1..120_000 } ?: return Response.json(mapOf("error" to "ms must be 1..120000"), 400)
        pending = Pending(at, ms, r.query["lock"] == "1")
        save(file(app))
        return Response.json(mapOf("pendingStall" to pendingJson()))
    }

    private fun thresholds(app: DystopoApp, r: Request): Response {
        if (r.query["clear"] == "1") {
            StartupTrace.thresholds = StartupThresholds()
        } else {
            val t = StartupTrace.thresholds
            fun of(key: String, was: Long) = r.query[key]?.toLongOrNull() ?: was
            StartupTrace.thresholds = StartupThresholds(of("coldMs", t.coldMs), of("warmMs", t.warmMs), of("hotMs", t.hotMs), of("stallMs", t.stallMs), of("mapMs", t.mapMs))
        }
        save(file(app))
        return Response.json(mapOf("thresholds" to StartupTrace.thresholds.json()))
    }

    private fun save(f: File) {
        val t = StartupTrace.thresholds
        val p = pending
        f.writeText("${t.coldMs} ${t.warmMs} ${t.hotMs} ${t.stallMs} ${t.mapMs}\n${p?.at ?: "-"} ${p?.ms ?: 0} ${p?.lock ?: false}\n")
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val f = file(DystopoApp.instance)
        val lines = runCatching { f.readLines() }.getOrNull() ?: return
        lines.getOrNull(0)?.split(' ')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 5 }?.let { StartupTrace.thresholds = StartupThresholds(it[0], it[1], it[2], it[3], it[4]) }
        lines.getOrNull(1)?.split(' ')?.takeIf { it.size == 3 && it[0] in POINTS }?.let { (at, ms, lock) -> pending = Pending(at, ms.toLongOrNull() ?: 0L, lock == "true") }
    }

    fun pendingJson(): Map<String, Any?>? = pending?.let { mapOf("at" to it.at, "ms" to it.ms, "lock" to it.lock) }

    /** A named point of the launch, on the main thread: the pending stall, if it is for here. Nothing in a release build. */
    fun stallPoint(at: String) {
        if (!BuildConfig.DEBUG_ENDPOINTS) return
        load()
        val p = pending?.takeIf { it.at == at } ?: return
        pending = null
        runCatching { save(file(DystopoApp.instance)) }
        val hold = { if (p.lock) lockHeld(p.ms) else hold(p.ms) }
        if (at == "afterFirstFrame") Handler(Looper.getMainLooper()).postDelayed({ hold() }, 200) else hold()
    }

    private val LOCK = Any()

    /** The main thread waits on a monitor another thread holds for [ms]. */
    private fun lockHeld(ms: Long) {
        val entered = java.util.concurrent.CountDownLatch(1)
        Thread({ holdLock(ms, entered) }, "startup-test-holder").start()
        entered.await()
        holdLock(0, null)
    }

    /** One lock, one method: the two stacks name it, as `Tracking.of`'s do. */
    private fun holdLock(ms: Long, entered: java.util.concurrent.CountDownLatch?) {
        synchronized(LOCK) {
            entered?.countDown()
            if (ms > 0) hold(ms)
        }
    }

    /** The injected stall: the report names this frame. */
    private fun hold(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (true) {
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) break
            Thread.sleep(left)
        }
    }
}
