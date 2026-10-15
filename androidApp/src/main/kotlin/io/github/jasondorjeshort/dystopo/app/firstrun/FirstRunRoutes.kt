package io.github.jasondorjeshort.dystopo.app.firstrun

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.online.DirectPrefs
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.packs.PackManager

/**
 * T79 debug routes (registered with one line in [io.github.jasondorjeshort.dystopo.app.Routes], before the
 * pack routes). The release build has none of these; there the same actions are taps (tools/android-firstrun.py).
 * - `GET /firstrun`: whether the first-run card shows (`noPack`, `dismissed`, `cardVisible`), the server, the
 *   card's message, the "around me" step (`aroundMe`, `fix`, `box`), the region download, the last import
 *   and the last server test.
 * - `POST /firstrun/download[?lat=&lon=]`: "Download the map around me" (the device's fix unless given).
 * - T80 `POST /firstrun/show[?lat=&lon=]`: "Show the map around me" (camera to the fix, card aside, nothing saved).
 * - T80 `GET /firstrun/direct`: the phone's own source reading (`enabled`, `active`, session bytes and cap, requests,
 *   DEM tiles rendered and their ms, OpenFreeMap tiles, the source cache). T88: `rangeStalls` (range reads that got no
 *   byte for 4 s, dropped and retried), `stages` (ms summed over threads: `renderWaitMs` waiting for a render permit,
 *   `coarseFirstHeldMs` the part held for coarse tiles, `renderFetchMs` inside `best`, `renderEncodeMs`,
 *   `contourDemWaitMs` contour tiles waiting for their DEM, and `sources`: `rangeReads`, `rangeMs`, `rangeShared`,
 *   `diskHits`, `blocksDecoded`, `decodeMs`, `blockWaits`, `geoidMs`, `resampleMs`) and `timeline` (`startElapsedMs`,
 *   `events`, `lines`: the first 800 events in ms from the first; `dem?`, `dem>`, `dem. <ms>` a render queued, started,
 *   done; `read <file> <KB> <ms>`; `d?`, `d200`, `d503` the map's DEM tiles; `c?`, `c200`, `c200p`, `c503` contour
 *   tiles; `refresh` a style reload; release builds write them to logcat, tag `DystopoDirect`, T106: without tile
 *   columns, rows and file names, `DirectTrace.redact`). `POST /firstrun/direct?on=0|1&sessionMb=`:
 *   turn it off (no server: offline only) or on, set the per-session download budget, `maxZoom` the deepest DEM zoom rendered (8..15; deeper is synthesized). `POST /firstrun/direct/trim`: trim
 *   `cache/sources/` to its cap now.
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
                "POST" to "/firstrun/show" -> Response.json(FirstRun.showAroundMe(r.query["lat"]?.toDoubleOrNull(), r.query["lon"]?.toDoubleOrNull()), 202)
                "GET" to "/firstrun/direct" -> Response.json(directJson(app))
                "POST" to "/firstrun/direct" -> {
                    val on = r.query["on"]?.let { it == "1" || it == "true" }
                    val mb = r.query["sessionMb"]?.let { it.toLongOrNull()?.takeIf { v -> v in 1..100_000 } ?: throw IllegalArgumentException("sessionMb must be 1..100000") }
                    val maxZoom = r.query["maxZoom"]?.let { it.toIntOrNull()?.takeIf { v -> v in 8..15 } ?: throw IllegalArgumentException("maxZoom must be 8..15") }
                    DirectPrefs.set(app, on, mb, maxZoom)
                    val online = OnlineService.get(app)
                    if (mb != null && online.isDirect) online.direct.sessionCapBytes = mb * 1024 * 1024
                    if (maxZoom != null && online.isDirect) {
                        online.direct.maxZoom = maxZoom
                        online.cache.clear("${io.github.jasondorjeshort.dystopo.app.online.DirectSources.KEY}/") // tiles made at the old depth
                    }
                    if (on != null) {
                        online.configure(io.github.jasondorjeshort.dystopo.app.AppSettings.server)
                        app.reload()
                    }
                    Response.json(directJson(app))
                }
                "POST" to "/firstrun/direct/trim" -> Response.json(OnlineService.get(app).direct.trimSourceCache())
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
    private fun directJson(app: DystopoApp): Map<String, Any?> {
        val online = OnlineService.get(app)
        return linkedMapOf<String, Any?>("enabled" to DirectPrefs.enabled(app), "active" to online.isDirect, "sessionMb" to DirectPrefs.sessionMb(app), "maxZoomSetting" to DirectPrefs.maxZoom(app)) +
            (if (online.isDirect) online.direct.json() else emptyMap())
    }
}