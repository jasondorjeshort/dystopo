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
