package io.github.jasondorjeshort.dystopo.app.panorama

import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.horizon.HorizonPlace
import io.github.jasondorjeshort.dystopo.horizon.PanoramaAim
import io.github.jasondorjeshort.dystopo.horizon.PanoramaScene
import io.github.jasondorjeshort.dystopo.horizon.SkyView

/**
 * T326: the Panorama's debug routes (debug builds, behind the loopback token). The switch's `POST /horizon/switch?to=panorama`
 * is `HorizonRoutes`'; the place, a pick and "Back to here" are the Horizon view's routes too (`POST /horizon/ui?lat=&lon=`,
 * `back=1`; `POST /horizon?viewFrom=`), which act on the one open state (`HorizonUi`).
 * - `GET /panorama`: `open` (the Panorama showing), `showing` (`map`, `horizon` or `panorama`), `aim` (`bearingDeg`,
 *   `tiltDeg`, `fovDeg`, `opening`: where the opening's bearing came from, `peak`, `heading`, `heading-late` or `north`),
 *   `plot` (`rectPx` in the Compose root, `widthPx`, `heightPx`, `dp`, `widthDp`, `heightDp`, `levelY` (px from the plot's
 *   top, null off the view), `zoomButtonsPx`), `listPx` (the pane's scrolling list), `headingScrolls`, `drawn` (the
 *   layout, the place line, the layers, the steps wanted), `place` (`point`, `placeLine`), `range` (`viewRangeKm`),
 *   `names` (each placed: `name`, `x`, `y`, `markX`, `markY`, `dominant`, `distanceKm`, `bearingDeg`, `angleDeg`, `leader`),
 *   `leftOut` (`name`, `reason`: `behind the viewer`, `off the view`, `no room`), `dominant` and `closest` (the candidates'),
 *   `tape` (`tickStepDeg`, `labelStepDeg`, `labels`), `work` (the place's terrain: `tiles`, `bytes`, `network` (always
 *   false: offline maps only), `ms`, `demPacks`; `sweeps` done with `stepDeg`, `samples`, `ms`, `atMs`; `pending`; `peaks`),
 *   `kept` (the places kept, at most two), `sceneMs` (`last`, `max`, `builds`, `offMain`), `firstSkylineMs` (from the
 *   opening to the first skyline drawn), `gnss` (`held`). `wait=<ms>` waits until the plot has drawn the step the zoom
 *   asks for and the peaks are tested, or that long.
 * - `POST /panorama`: `open=1` (the switch's "Panorama", as a tap: from the map at the map's selection) or `open=0` (its
 *   "Map"); `bearing=`, `tilt=`, `fov=` set the aim (wrapped and held to its limits); `drag=dx,dy` (px) a drag through the
 *   aim, in `steps=` frames (default 1, at most 200) held as one gesture (the names keep their offsets until it ends; the
 *   scene's cost is counted from its start); `pinch=<factor>` about the plot's centre; `zoom=in|out` a button's press;
 *   `select=<peak name>|none` picks a candidate as a tap on its name does; then answers `GET /panorama` (with `wait`).
 */
object PanoramaRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/panorama") return null
        return when (r.method) {
            "GET" -> answer(app, r)
            "POST" -> post(app, r)
            else -> null
        }
    }

    private fun bad(msg: String) = Response.json(mapOf("error" to msg), 400)

    private fun post(app: DystopoApp, r: Request): Response {
        val q = r.query
        q["open"]?.let { v ->
            val to = when (v) {
                "1", "true" -> SkyView.PANORAMA
                "0", "false" -> SkyView.MAP
                else -> return bad("open must be 1 or 0")
            }
            app.map.onMain { ViewSwitch.select(to) }
            val until = SystemClock.elapsedRealtime() + 10_000
            while (to == SkyView.PANORAMA && !app.map.onMain { HorizonUi.open } && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
            Thread.sleep(600)
        }
        fun num(key: String): Double? = q[key]?.let { it.toDoubleOrNull()?.takeIf { v -> v.isFinite() } ?: return null }
        for (k in listOf("bearing", "tilt", "fov", "pinch")) if (q.containsKey(k) && num(k) == null) return bad("$k must be a number")
        val bearing = num("bearing")
        val tilt = num("tilt")
        val fov = num("fov")
        val pinch = num("pinch")?.also { if (it <= 0) return bad("pinch must be a positive factor") }
        val drag = q["drag"]?.let { s ->
            val p = s.split(",").map { it.trim().toDoubleOrNull() }
            if (p.size != 2 || p.any { it == null }) return bad("drag must be dx,dy in px")
            p[0]!! to p[1]!!
        }
        val steps = q["steps"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..200 } ?: return bad("steps must be 1 to 200") } ?: 1
        val zoom = q["zoom"]?.let {
            when (it) { "in" -> true; "out" -> false; else -> return bad("zoom must be in or out") }
        }
        if ((bearing != null || tilt != null || fov != null || drag != null || pinch != null || zoom != null) && !waitAim(app)) {
            return Response.json(mapOf("error" to "the Panorama is not open (or its plot has no size yet)"), 409)
        }
        if (bearing != null || tilt != null || fov != null) app.map.onMain {
            PanoramaUi.steer { a, _ ->
                PanoramaAim(
                    PanoramaAim.wrapDeg(bearing ?: a.bearingDeg), (tilt ?: a.tiltDeg).coerceIn(PanoramaAim.MIN_TILT_DEG, PanoramaAim.MAX_TILT_DEG),
                    (fov ?: a.fovDeg).coerceIn(PanoramaAim.MIN_FOV_DEG, PanoramaAim.MAX_FOV_DEG),
                )
            }
        }
        pinch?.let { f -> app.map.onMain { PanoramaUi.steer { a, (w, h, _) -> a.pinch(f, w / 2, h / 2, w, h) } } }
        zoom?.let { inward -> app.map.onMain { PanoramaUi.steer { a, (w, h, _) -> a.zoom(inward, w, h) } } }
        drag?.let { (dx, dy) ->
            app.map.onMain { PanoramaUi.resetSceneCost(); PanoramaUi.gesturing = true }
            for (i in 0 until steps) {
                app.map.onMain { PanoramaUi.steer { a, (w, _, _) -> a.drag(dx / steps, dy / steps, w) } }
                Thread.sleep(34) // about two frames: each step is drawn
            }
            app.map.onMain { PanoramaUi.gesturing = false }
        }
        q["select"]?.let { name ->
            app.map.onMain {
                val cands = PanoramaData.kept.firstOrNull()?.peaks?.candidates.orEmpty()
                HorizonUi.selected = if (name == "none") null else cands.firstOrNull { it.peak.name.equals(name, ignoreCase = true) }?.let { HorizonUi.keyOf(it) }
            }
        }
        Thread.sleep(400)
        return answer(app, r)
    }

    /** Waits (up to 5 s) for the aim at opening; false when the Panorama is not open. */
    private fun waitAim(app: DystopoApp): Boolean {
        val until = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < until) {
            if (app.map.onMain { HorizonUi.open && HorizonUi.view == SkyView.PANORAMA && PanoramaUi.aim != null }) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
    private fun r2(v: Double) = Math.round(v * 100) / 100.0

    private fun answer(app: DystopoApp, r: Request): Response {
        val wait = r.query["wait"]?.let { it.toLongOrNull()?.coerceIn(0, 300_000) ?: return bad("wait must be ms") } ?: 0L
        val until = SystemClock.elapsedRealtime() + wait
        while (wait > 0 && SystemClock.elapsedRealtime() < until) {
            val ready = app.map.onMain {
                val place = PanoramaData.kept.firstOrNull()
                val s = PanoramaUi.scene
                HorizonUi.open && HorizonUi.view == SkyView.PANORAMA && place != null && s != null && place.pending.isEmpty() &&
                    (place.peaks != null || place.reason != null) && PanoramaUi.wanted.all { place.skylines.containsKey(it) } &&
                    place.finest === place.skylines[place.skylines.keys.min()]
            }
            if (ready) {
                Thread.sleep(500) // the frame that draws the finest step and the names on it
                break
            }
            Thread.sleep(150)
        }
        return Response.json(app.map.onMain { json() })
    }

    private fun json(): Map<String, Any?> {
        val aim = PanoramaUi.aim
        val scene = PanoramaUi.scene
        val size = PanoramaUi.plotSize
        val place = PanoramaData.kept.firstOrNull()
        val fix = Gnss.position()
        val point = HorizonUi.point
        val line = HorizonPlace.line(point, fix?.latitude, fix?.longitude, AppSettings.units)
        val ranked = place?.peaks?.ranked.orEmpty()
        val open = HorizonUi.open && HorizonUi.view == SkyView.PANORAMA
        return linkedMapOf(
            "open" to open, "showing" to ViewSwitch.showing.key,
            "aim" to aim?.let { a -> linkedMapOf("bearingDeg" to r1(a.bearingDeg), "tiltDeg" to r2(a.tiltDeg), "fovDeg" to r2(a.fovDeg), "opening" to PanoramaUi.openingSource) },
            "plot" to size?.let { (w, h, dp) ->
                linkedMapOf(
                    "rectPx" to PanoramaUi.plotPx, "widthPx" to w, "heightPx" to h, "dp" to dp,
                    "widthDp" to Math.round(w / dp), "heightDp" to Math.round(h / dp),
                    "levelY" to scene?.level?.let { r1(it[1]) },
                    "zoomButtonsPx" to PanoramaScene.zoomButtons(w, h, dp).map { b -> listOf(b.left, b.top, b.right, b.bottom).map { Math.round(it) } },
                )
            },
            "listPx" to PanoramaUi.listPx, "switchPx" to PanoramaUi.switchPx, "headingScrolls" to PanoramaUi.headingScrolls,
            "drawn" to PanoramaUi.drawn,
            "place" to linkedMapOf(
                "point" to point?.let { p -> linkedMapOf("lat" to p.lat, "lon" to p.lon, "name" to p.name) },
                "placeLine" to line.text, "moved" to line.moved,
            ),
            "range" to linkedMapOf("viewRangeKm" to AppSettings.viewRangeKm),
            "selected" to HorizonUi.selected,
            "names" to scene?.names?.map { n ->
                val p = n.placed
                linkedMapOf(
                    "name" to p.label.name, "x" to r1(p.x), "y" to r1(p.y), "markX" to r1(n.markX), "markY" to r1(n.markY),
                    "dominant" to p.label.dominant, "distanceKm" to r1(p.label.distanceM / 1000), "bearingDeg" to r1(p.label.bearingDeg),
                    "angleDeg" to r2(p.label.angleDeg), "leader" to (p.leader != null),
                    "box" to listOf(p.box.left, p.box.top, p.box.right, p.box.bottom).map { r1(it) },
                )
            },
            "leftOut" to scene?.leftOut?.map { l -> linkedMapOf("name" to l.label.name, "reason" to l.reason, "distanceKm" to r1(l.label.distanceM / 1000)) },
            "dominant" to ranked.firstOrNull { it.label.dominant }?.label?.name,
            "closest" to ranked.minByOrNull { it.label.distanceM }?.label?.name,
            "candidates" to place?.peaks?.candidates?.size,
            "pickedOnPlot" to scene?.names?.firstOrNull { (it.label.ref as? PeakView)?.let { v -> HorizonUi.keyOf(v) } == HorizonUi.selected }?.label?.name,
            "tape" to scene?.tape?.let { t -> linkedMapOf("tickStepDeg" to t.tickStepDeg, "labelStepDeg" to t.labelStepDeg, "labels" to t.labels.map { it.text }) },
            "layersDrawn" to scene?.layers?.map { l -> linkedMapOf("index" to l.index, "rangeKm" to l.rangeM / 1000, "rings" to l.rings.size) },
            "stepDrawn" to place?.finest?.stepDeg, "stepsWanted" to PanoramaUi.wanted,
            "work" to place?.json(),
            "kept" to PanoramaData.kept.map { linkedMapOf("lat" to it.lat, "lon" to it.lon, "rangeKm" to it.rangeM / 1000, "fix" to it.isFix, "steps" to it.skylines.keys.sorted()) },
            "sweepsRun" to PanoramaData.sweepsRun.get(), "terrainReads" to PanoramaData.terrainReads.get(),
            "sceneMs" to linkedMapOf("last" to r2(PanoramaUi.sceneMsLast), "max" to r2(PanoramaUi.sceneMsMax), "builds" to PanoramaUi.sceneBuilds, "offMain" to PanoramaUi.sceneOffMain),
            "firstSkylineMs" to PanoramaUi.firstSkylineMs,
            "gnss" to linkedMapOf("held" to Gnss.held),
        )
    }
}
