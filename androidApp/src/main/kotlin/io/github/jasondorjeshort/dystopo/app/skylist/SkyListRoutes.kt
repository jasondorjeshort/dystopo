package io.github.jasondorjeshort.dystopo.app.skylist

import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonText
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaData
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaTargets
import io.github.jasondorjeshort.dystopo.app.panorama.PanoramaUi
import io.github.jasondorjeshort.dystopo.app.panorama.TargetKind
import io.github.jasondorjeshort.dystopo.horizon.SkyView

/**
 * T344: the List view's debug routes (debug builds, behind the loopback token). The switch's `POST /horizon/switch?to=list`
 * is `HorizonRoutes`'; the place, a pick, a scroll (`POST /horizon?scroll=`) and "Back to here" are the sky views' routes
 * too, which act on the one open state (`HorizonUi`).
 * - `GET /skylist`: `open` (the List showing), `showing` (`map`, `horizon`, `panorama` or `list`), `from` (the view the
 *   List was last opened from), `keepsAim` (whether the Panorama, shown again from the List, keeps its aim), `drawn` (what
 *   the List last drew: `landscape`, `windowDp`, `fontScale`, `night`, `layers`, `buttons`, `disabledButtons`, `point`,
 *   `placeLine`, `moved`, `backShown`, `rangeKm`, `steps`, `terrain`, `peaksLine`, `peaks` (the rows' names, closest
 *   first), `selected`, `flightsListed`, `flightCard`, `selectedFlight`, `sunNow` (whether the sun's line is a row: up),
 *   `satellitesListed`, `target`, `scrollPx`, `scrollMaxPx`, `switchRoomDp`), `listPx` (the list's viewport in the Compose
 *   root) and `switchPx` (the List's switch), `lastRow` (`kind:id` of the last row shown in the Panorama) and `rowsShown`.
 *   `wait=<ms>` waits that long for the Panorama's peaks at the List's place (or its reason for none).
 * - `POST /skylist`: `open=1` the Panorama's list button (from the Panorama; from elsewhere as the switch's "List");
 *   `row=peak:<name>|sun|satellite:<id>` a row's tap: the Panorama shows, turned to it (409 while the List is not open,
 *   404 for no such row); then answers `GET /skylist` (with `wait`).
 */
object SkyListRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/skylist") return null
        return when (r.method) {
            "GET" -> answer(app, r)
            "POST" -> post(app, r)
            else -> null
        }
    }

    private fun bad(msg: String) = Response.json(mapOf("error" to msg), 400)

    private fun post(app: DystopoApp, r: Request): Response {
        val q = r.query
        if (q["open"] == "1" || q["open"] == "true") {
            app.map.onMain { SkyList.openFromPanorama() }
            val until = SystemClock.elapsedRealtime() + 10_000
            while (!app.map.onMain { HorizonUi.open && HorizonUi.view == SkyView.LIST } && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
            Thread.sleep(600)
        }
        q["row"]?.let { v ->
            if (!app.map.onMain { HorizonUi.open && HorizonUi.view == SkyView.LIST }) return Response.json(mapOf("error" to "the List view is not open"), 409)
            val err = app.map.onMain { row(v) }
            if (err != null) return if (err.startsWith("no ")) Response.json(mapOf("error" to err), 404) else bad(err)
            Thread.sleep(PanoramaUi.TURN_MS + 800)
        }
        return answer(app, r)
    }

    /** A row's tap by its [v] (main thread): null when shown, else why not. */
    private fun row(v: String): String? {
        val kind = v.substringBefore(':')
        val id = v.substringAfter(':', "")
        return when (kind) {
            "peak" -> {
                val place = PanoramaData.kept.firstOrNull() ?: return "no place read yet"
                val p = place.peaks?.candidates?.firstOrNull { it.peak.name.equals(id, true) } ?: return "no peak row named $id"
                val key = HorizonUi.keyOf(p)
                SkyList.show(PanoramaTargets.PeakPicked(key, p.peak.name), TargetKind.PEAK, key)
                null
            }
            "sun" -> {
                SkyList.show(PanoramaTargets.SunTapped(true), TargetKind.SUN, PanoramaTargets.SUN_ID)
                null
            }
            "satellite" -> {
                val s = Gnss.satellites.firstOrNull { it.id == id || HorizonText.satelliteName(it).equals(id, true) } ?: return "no satellite row $id"
                SkyList.show(PanoramaTargets.SatelliteTapped(s.id, HorizonText.satelliteName(s)), TargetKind.SATELLITE, s.id)
                null
            }
            else -> "row must be peak:<name>, sun or satellite:<id>"
        }
    }

    private fun answer(app: DystopoApp, r: Request): Response {
        val wait = r.query["wait"]?.let { it.toLongOrNull()?.coerceIn(0, 300_000) ?: return bad("wait must be ms") } ?: 0L
        val until = SystemClock.elapsedRealtime() + wait
        while (wait > 0 && SystemClock.elapsedRealtime() < until) {
            val ready = app.map.onMain {
                val d = SkyList.drawn
                HorizonUi.open && HorizonUi.view == SkyView.LIST && d != null && (d["peaks"] != null || d["terrain"] == false && d["peaksLine"] != null &&
                    d["peaksLine"] != HorizonText.PEAKS_WORKING)
            }
            if (ready) break
            Thread.sleep(200)
        }
        return Response.json(app.map.onMain {
            linkedMapOf(
                "open" to (HorizonUi.open && HorizonUi.view == SkyView.LIST), "showing" to ViewSwitch.showing.key,
                "from" to SkyList.from?.key, "keepsAim" to SkyList.keepsAim(),
                "drawn" to SkyList.drawn, "listPx" to SkyList.listPx, "switchPx" to SkyList.switchPx,
                "lastRow" to SkyList.lastRow, "rowsShown" to SkyList.rowsShown,
            )
        })
    }
}
