package io.github.jasondorjeshort.dystopo.app.debug

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.MainWatchdog
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import java.io.File

/**
 * The `/debug/...` routes (debug builds only, on the debug server; moved out of `Routes.kt` in T150). `POST /debug/crash` is
 * the problem reports' (`reports/ReportRoutes.kt`).
 *
 * - T144, the UI-thread watchdog ([MainWatchdog]): `GET /debug/watchdog` (thresholds, armed or why not, heartbeats,
 *   freezes, the dumps in `files/reports/` with their headers), `POST /debug/watchdog?beatMs=&freezeMs=` (override the
 *   1 s and 3 s for this process), `POST /debug/freeze?ms=1..60000` (holds the UI thread for that long, posted; answers at
 *   once; the dump's `blockingFrame` is [freezeUi]).
 * - T150 `GET /debug/cpu`: CPU ticks per thread of this process from `/proc/self/task/<tid>/stat` (`threads`: `tid`, `name`,
 *   `main` for the UI thread, `utime`, `stime`, `ticks`; `process` the same for the whole process; `clockTicksPerSecond`;
 *   `foreground` the app's foreground flag and the paused loops, [Foreground.json]). Each read is kept, and the next one adds
 *   `sincePrevious`: `ms` between the two and per thread `ticks` and `percent` of one core over that time (threads that used
 *   any, plus the UI thread and `HeapTaskDaemon` always), most first. Two reads 60 s apart after HOME are the background
 *   check of the routing-graph finding.
 * - T150 `GET /debug/strictmode`: the StrictMode violations counted by app frame ([StrictModeCounts.json]);
 *   `POST /debug/strictmode?clear=1` forgets them; `POST /debug/strictmode/violate` reads the APK's first byte on the UI
 *   thread (a disk read the thread policy reports) and answers with the frame it was counted under.
 * - T229 `POST /debug/tracking/delay?ms=0..120000`: holds the tracker's construction ([Tracking.start]) that long in the
 *   next launch, once (a file, so a force-stop keeps it; `ms=0` forgets it), so the window before `GET /tracks` reads
 *   `ready: true` can be seen: the chips drawn without the tracker, "Starting" on the Record chip. Answers `pendingDelayMs`.
 * - T242 `GET /debug/cache[?gc=1]`: the two tile caches' indexes ([io.github.jasondorjeshort.dystopo.app.online.TileCache.json]
 *   and its `index`: `state` indexing or ready, `partial`, `entries`, `bytes`, `walked`, `walkMs`, `heapBytes` (the index's
 *   heap, estimated from its arrays) and `heapBytesPerEntry`), the Java heap in use (`gc=1` collects first), and the last
 *   fill. `POST /debug/cache/fill?files=1..400000&bytes=16..65536[&clear=1]` writes that many synthetic tiles straight
 *   into the online cache's directory, in the real key shape (`fill.example_8770/dem@f1/<z>/<x>/<y>.png` and a fifth as
 *   `osm@f1/.../<y>.pbf`, columns of 256 around Rainier, file times spread over the last 1000 minutes), on a thread of its
 *   own; the running index does not count them, the next launch's walk does (that is what it is for). `clear=1` deletes
 *   them instead (through the cache, once its index is whole). Answers at once; `GET /debug/cache`'s `fill` follows it.
 */
object DebugRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (!r.path.startsWith("/debug/")) return null
        return when (r.method to r.path) {
            "GET" to "/debug/watchdog" -> Response.json(MainWatchdog.json()) // T144
            "POST" to "/debug/watchdog" -> { // T144: the thresholds, for tests
                MainWatchdog.configure(r.query["beatMs"]?.let { it.toLongOrNull() ?: return Response.json(mapOf("error" to "beatMs must be a number"), 400) },
                    r.query["freezeMs"]?.let { it.toLongOrNull() ?: return Response.json(mapOf("error" to "freezeMs must be a number"), 400) })
                Response.json(MainWatchdog.json())
            }
            "POST" to "/debug/freeze" -> { // T144: blocks the UI thread on purpose (the watchdog's test); answers at once
                val ms = r.query["ms"]?.toLongOrNull()?.takeIf { it in 1..60_000 } ?: return Response.json(mapOf("error" to "ms must be 1..60000"), 400)
                Handler(Looper.getMainLooper()).post { freezeUi(ms) }
                Response.json(mapOf("posted" to true, "ms" to ms))
            }
            "POST" to "/debug/tracking/delay" -> { // T229
                val ms = r.query["ms"]?.toLongOrNull()?.takeIf { it in 0..120_000 } ?: return Response.json(mapOf("error" to "ms must be 0..120000"), 400)
                io.github.jasondorjeshort.dystopo.app.tracking.Tracking.delayNextStart(app, ms)
                Response.json(mapOf("pendingDelayMs" to io.github.jasondorjeshort.dystopo.app.tracking.Tracking.pendingDelayMs(app)))
            }
            "GET" to "/debug/cache" -> Response.json(cacheJson(app, gc = r.query["gc"] == "1")) // T242
            "POST" to "/debug/cache/fill" -> { // T242
                val clear = r.query["clear"] == "1"
                val files = r.query["files"]?.toIntOrNull()?.takeIf { it in 1..400_000 } ?: if (clear) 0 else return Response.json(mapOf("error" to "files must be 1..400000"), 400)
                val bytes = r.query["bytes"]?.toIntOrNull()?.takeIf { it in 16..65_536 } ?: if (clear) 0 else return Response.json(mapOf("error" to "bytes must be 16..65536"), 400)
                if (fill?.get("running") == true) return Response.json(mapOf("error" to "a fill is running", "fill" to fill), 409)
                startFill(app, files, bytes, clear)
                Response.json(mapOf("started" to true, "files" to files, "bytes" to bytes, "clear" to clear))
            }
            "GET" to "/debug/cpu" -> Response.json(cpu())
            "GET" to "/debug/strictmode" -> Response.json(StrictModeCounts.json())
            "POST" to "/debug/strictmode" -> {
                if (r.query["clear"] == "1") StrictModeCounts.clear()
                Response.json(StrictModeCounts.json())
            }
            "POST" to "/debug/strictmode/violate" -> {
                val before = (StrictModeCounts.json()["counted"] as Long)
                app.map.onMain { violate(app) }
                // The thread policy's listener runs after the read's message returns; give it a moment.
                val until = SystemClock.elapsedRealtime() + 2_000
                while ((StrictModeCounts.json()["counted"] as Long) == before && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
                Response.json(linkedMapOf("read" to true, "countedBefore" to before) + StrictModeCounts.json())
            }
            else -> null
        }
    }

    /** T144: `POST /debug/freeze`: holds the main thread for [ms]; the watchdog's dump names this frame. */
    private fun freezeUi(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (true) {
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) break
            Thread.sleep(left)
        }
    }

    /** T150: a disk read on the UI thread, on purpose (`POST /debug/strictmode/violate`). */
    private fun violate(app: DystopoApp) {
        File(app.applicationInfo.sourceDir).inputStream().use { it.read() }
    }

    @Volatile private var previous: ThreadCpu.Snapshot? = null

    /** T242: the last `POST /debug/cache/fill` (its progress while it runs). */
    @Volatile private var fill: Map<String, Any?>? = null

    /** T242: the server key the fill writes under, so `clear=1` finds its tiles and nothing else. */
    private const val FILL_SERVER = "fill.example_8770"

    private fun cacheJson(app: DystopoApp, gc: Boolean): Map<String, Any?> {
        val rt = Runtime.getRuntime()
        if (gc) rt.gc()
        val online = io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).cache
        return linkedMapOf(
            "online" to online.json(),
            "contours" to io.github.jasondorjeshort.dystopo.app.ContourCache.get(app).json(),
            "javaHeap" to linkedMapOf("usedBytes" to rt.totalMemory() - rt.freeMemory(), "maxBytes" to rt.maxMemory(), "gc" to gc),
            "fill" to fill,
        )
    }

    /**
     * T242: `POST /debug/cache/fill`: [files] tiles of [bytes] each written straight into the online cache's directory, in
     * columns of 256 (one leaf directory each) at zooms 16 down to 9 around Rainier, four in five DEM tiles and one in five
     * vector tiles, file times spread over the last 1000 minutes; or, [clear], those tiles deleted through the cache.
     */
    private fun startFill(app: DystopoApp, files: Int, bytes: Int, clear: Boolean) {
        val cache = io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).cache
        val t0 = SystemClock.elapsedRealtime()
        fill = linkedMapOf("running" to true, "files" to files, "bytes" to bytes, "clear" to clear, "done" to 0)
        Thread({
            var done = 0
            var error: String? = null
            try {
                if (clear) {
                    done = cache.clear("$FILL_SERVER/")
                    File(cache.dir, FILL_SERVER).deleteRecursively() // what the index never saw
                } else {
                    val body = ByteArray(bytes) { (it * 31).toByte() }
                    val now = System.currentTimeMillis()
                    var z = 16
                    while (done < files && z >= 9) {
                        val n = 1 shl z
                        val cx = ((-121.76 + 180.0) / 360.0 * n).toInt()
                        val lat = Math.toRadians(46.85)
                        val cy = ((1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2 * n).toInt()
                        val side = minOf(n, 256)
                        var col = 0
                        while (done < files && col < side) {
                            val x = (cx - side / 2 + col).coerceIn(0, n - 1)
                            val osm = col % 5 == 4
                            val dir = File(cache.dir, "$FILL_SERVER/${if (osm) "osm" else "dem"}@f1/$z/$x").apply { mkdirs() }
                            for (row in 0 until side) {
                                if (done >= files) break
                                val y = (cy - side / 2 + row).coerceIn(0, n - 1)
                                val f = File(dir, "$y.${if (osm) "pbf" else "png"}")
                                f.writeBytes(body)
                                f.setLastModified(now - (done % 1000) * 60_000L)
                                done++
                                if (done % 1000 == 0) fill = fill.orEmpty() + mapOf("done" to done, "ms" to SystemClock.elapsedRealtime() - t0)
                            }
                            col++
                        }
                        z--
                    }
                }
            } catch (e: Throwable) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            }
            fill = linkedMapOf("running" to false, "files" to files, "bytes" to bytes, "clear" to clear, "done" to done, "ms" to SystemClock.elapsedRealtime() - t0, "error" to error)
        }, "debug-cache-fill").apply { isDaemon = true }.start()
    }

    private fun cpu(): Map<String, Any?> {
        val now = ThreadCpu.read()
        val prev = previous
        previous = now
        return linkedMapOf(
            "atMs" to now.atMs, "clockTicksPerSecond" to ThreadCpu.clockTicks, "foreground" to Foreground.json(),
            "process" to now.process?.json(),
            "threads" to now.threads.sortedByDescending { it.ticks }.map { it.json() },
            "sincePrevious" to prev?.let { ThreadCpu.delta(it, now, ThreadCpu.clockTicks) },
        )
    }
}
