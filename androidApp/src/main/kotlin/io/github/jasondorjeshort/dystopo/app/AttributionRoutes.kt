package io.github.jasondorjeshort.dystopo.app

import android.content.Intent
import io.github.jasondorjeshort.dystopo.app.about.AboutActivity
import io.github.jasondorjeshort.dystopo.app.about.AppCredits

/**
 * Debug routes for attribution (registered from [Routes], debug builds only):
 * - `GET /attribution`: the composed credits JSON (compact line, per pack, per registry source).
 * - `POST /attribution/open`: opens the About screen (for screenshots with `adb exec-out screencap`).
 * - `POST /attribution/close`: closes it again.
 */
object AttributionRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        // T49: mapLine is what the credit line shows (packs overlapping the view in viewBounds).
        "GET" to "/attribution" -> Response.json(
            AppCredits.compose(app).json() + mapOf(
                "mapLine" to AppCredits.mapLine(app).json().let { mapOf("compact" to it["compact"], "packs" to it["packs"]) },
                "viewBounds" to MapViewport.bounds?.let { listOf(it.minLon, it.minLat, it.maxLon, it.maxLat) },
            ),
        )
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
