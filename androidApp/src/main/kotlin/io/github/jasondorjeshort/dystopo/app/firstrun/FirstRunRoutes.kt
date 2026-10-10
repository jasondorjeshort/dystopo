package io.github.jasondorjeshort.dystopo.app.firstrun

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.packs.PackManager

/**
 * T79 debug routes (registered with one line in [io.github.jasondorjeshort.dystopo.app.Routes], before the
 * pack routes). The release build has none of these; there the same actions are taps (tools/android-firstrun.py).
 * - `GET /firstrun`: whether the first-run card shows (`noPack`, `dismissed`, `cardVisible`), the server, the
 *   card's message, the "around me" step (`aroundMe`, `fix`, `box`), the region download, the last import
 *   and the last server test.
 * - `POST /firstrun/download[?lat=&lon=]`: "Download the map around me" (the device's fix unless given).
 * - `POST /firstrun/test[?server=]`: Settings › Sources › Test (the saved server unless given; not saved):
 *   `state` ok with `packs`, or failed with `error`. 502 when it failed.
 * - `POST /firstrun/dismiss[?undo=1]`: "Not now" (or bring the card back).
 * - `POST /packs/import?dem=<path>[&osm=<path>][&name=]`: import a pack from files on the device (absolute, or
 *   relative to the app's external files dir), validated like a download, as the picker does. 400 with the
 *   reason when a file is not a whole PMTiles archive or the roles do not fit; 409 while another import runs.
 * - `GET /packs/import`: the running or last import. `POST /packs/import/pick`: open the system file picker.
 */
object FirstRunRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        val p = r.path
        if (p != "/firstrun" && !p.startsWith("/firstrun/") && p != "/packs/import" && !p.startsWith("/packs/import/")) return null
        return try {
            when (r.method to p) {
                "GET" to "/firstrun" -> Response.json(FirstRun.json())
                "POST" to "/firstrun/download" -> Response.json(FirstRun.downloadAroundMe(r.query["lat"]?.toDoubleOrNull(), r.query["lon"]?.toDoubleOrNull()), 202)
                "POST" to "/firstrun/test" -> {
                    val t = Sources.test(r.query["server"])
                    Response.json(t, if (t["state"] == "ok") 200 else 502)
                }
                "POST" to "/firstrun/dismiss" -> {
                    app.map.onMain { FirstRun.dismissed = r.query["undo"] != "1" }
                    Response.json(FirstRun.json())
                }
                "GET" to "/packs/import" -> Response.json(PackManager.get(app).importState)
                "POST" to "/packs/import" -> {
                    val dem = r.query["dem"] ?: return Response.json(mapOf("error" to "dem required (a path on the device)"), 400)
                    Response.json(ImportPicker.importPaths(dem, r.query["osm"]?.ifEmpty { null }, r.query["name"]))
                }
                "POST" to "/packs/import/pick" -> {
                    app.map.onMain { ImportPicker.request() }
                    Response.json(mapOf("requested" to true, "launches" to ImportPicker.launches))
                }
                else -> Response.notFound()
            }
        } catch (e: IllegalArgumentException) {
            Response.json(mapOf("error" to e.message), 400)
        } catch (e: IllegalStateException) {
            Response.json(mapOf("error" to e.message), 409)
        } catch (e: java.io.IOException) {
            Response.json(mapOf("error" to e.message), 500)
        }
    }
}
