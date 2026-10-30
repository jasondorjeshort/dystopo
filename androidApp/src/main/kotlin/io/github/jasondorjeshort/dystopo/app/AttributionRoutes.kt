package io.github.jasondorjeshort.dystopo.app

import android.content.Intent
import io.github.jasondorjeshort.dystopo.app.about.AboutActivity
import io.github.jasondorjeshort.dystopo.app.about.AppCredits

/**
 * Debug routes for attribution (registered from [Routes], debug builds only):
 * - `GET /attribution`: the composed credits JSON (compact line, per pack, per registry source). T110: `lineShown` (the
 *   text the credit line composed last, and `lineComposedMsAgo`), `lineMatchesView` (it equals `mapLine.compact` composed
 *   now; true when the line falls back to the packs' raw text) and `onlineSourceIds` (what the server or the phone's own
 *   reader reported for the view, `viewSourceIds` in `GET /online`).
 * - `POST /attribution/open`: opens the About screen (for screenshots with `adb exec-out screencap`).
 * - `POST /attribution/close`: closes it again.
 */
object AttributionRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        // T49: mapLine is what the credit line shows (packs overlapping the view in viewBounds).
        "GET" to "/attribution" -> {
            val line = AppCredits.mapLine(app)
            val expected = line.takeIf { it.packs.isNotEmpty() }?.compact
            val shown = io.github.jasondorjeshort.dystopo.app.about.CreditLine.shown
            Response.json(
                AppCredits.compose(app).json() + mapOf(
                    "mapLine" to line.json().let { mapOf("compact" to it["compact"], "packs" to it["packs"]) },
                    "viewBounds" to MapViewport.current?.let { listOf(it.minLon, it.minLat, it.maxLon, it.maxLat) },
                    "lineShown" to shown, // T110
                    "lineComposedMsAgo" to io.github.jasondorjeshort.dystopo.app.about.CreditLine.composedAt.takeIf { it > 0 }?.let { System.currentTimeMillis() - it },
                    "lineMatchesView" to (expected == null || shown == expected),
                    "onlineSourceIds" to runCatching { io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).viewSourceIds }.getOrNull(),
                ),
            )
        }
        "POST" to "/attribution/open" -> {
            app.startActivity(Intent(app, AboutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Response.json(mapOf("opened" to true))
        }
        "POST" to "/attribution/close" -> {
            val a = AboutActivity.current?.get()
            a?.runOnUiThread { a.finish() }
            Response.json(mapOf("closed" to (a != null)))
        }
        else -> null
    }
}
