package io.github.jasondorjeshort.dystopo.app.panorama

import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
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
 *   top, null off the view), `zoomButtonsPx` (in the Compose root, as `rectPx`)), `listPx` (the pane's scrolling list), `headingScrolls`, `drawn` (the
 *   layout, the place line, the layers, the steps wanted), `place` (`point`, `placeLine`), `range` (`viewRangeKm`),
 *   `names` (each placed: `name`, `x`, `y`, `markX`, `markY`, `dominant`, `distanceKm`, `bearingDeg`, `angleDeg`, `leader`),
 *   `leftOut` (`name`, `reason`: `behind the viewer`, `off the view`, `no room`), `dominant` and `closest` (the candidates'),
 *   `tape` (`tickStepDeg`, `labelStepDeg`, `labels`), `work` (the place's terrain: `tiles`, `bytes`, `network` (always
 *   false: offline maps only), `ms`, `demPacks`; `sweeps` done with `stepDeg`, `samples`, `ms`, `atMs`; `pending`; `peaks`),
 *   `kept` (the places kept, at most two), `sceneMs` (`last`, `max`, `builds`, `offMain`), `firstSkylineMs` (from the
 *   opening to the first skyline drawn), `gnss` (`held`). `wait=<ms>` waits until the plot has drawn the step the zoom
 *   asks for and the peaks are tested, or that long. T327: `layers` (what the Sun, Satellites and Flights drew through the
 *   lens: `sun` (`pathSamples`, `pathOnView`, `hourMarks`, `hoursOnView`, `nowAltDeg`, `nowAzDeg`, `nowDrawn`, `nowPx`),
 *   `satellites` (`listed`, `onView`, `ids`), `flights` (`listed`, `onView`, `names`, `hollow`, `emergency`, `tailPoints`,
 *   `credit`, `picked`, `aircraft`: hex, label, bearing and elevation of each listed); a layer that is off is absent), `buttons` and `disabledButtons` in `drawn`, `target` (`kind`,
 *   `id`, `name`, `elDeg`, `azDeg`, `onView`, `x`, `y` in the plot (px) while on the view, `third`: `left`, `middle` or
 *   `right` third of the plot's width), `arrow` (`side`, `degrees`, `text`, `x`, `y` (the edge point, plot px), `rectPx`
 *   in the Compose root), `turns` (`done`, `last`: the target, the turn's degrees and the aim it ended at), `sunAt` (the
 *   debug instant the Sun layer is drawn for, or null for the clock), `aircraftOwners` (the flights' poller's owners).
 * - `POST /panorama`: `open=1` (the switch's "Panorama", as a tap: from the map at the map's selection) or `open=0` (its
 *   "Map"); `bearing=`, `tilt=`, `fov=` set the aim (wrapped and held to its limits); `drag=dx,dy` (px) a drag through the
 *   aim, in `steps=` frames (default 1, at most 200) held as one gesture (the names keep their offsets until it ends; the
 *   scene's cost is counted from its start); `pinch=<factor>` about the plot's centre; `zoom=in|out` a button's press;
 *   `select=<peak name>|none` picks a candidate as a tap on its name does; then answers `GET /panorama` (with `wait`).
 *   T327: `target=<kind>:<name>` makes it the target as a tap on its row does and turns the view to it (`peak:<name>`,
 *   `sun`, `flight:<hex or label>`, `satellite:<id or "GPS 12">`); `target=none` lets the target and its pick go;
 *   `arrow=1` taps the edge arrow (409 when none stands); `back=1` presses Back once; `sunAt=<ISO instant, e.g.
 *   2026-06-21T20:00:00Z>|none` sets the instant the Sun layer is drawn for (the clock otherwise; `POST /horizon`'s `date=`
 *   sets only the day, and only the Horizon view's).
 * - T330 (Follow): `GET` adds `steering`, `following`, `attitude`, `resting`, `aimFollow`, `followButton`, `keepScreenOn`,
 *   `compass`, `frames`; `POST` adds `steering=drag|follow`, `attitude=`, `attitudeQ=`, `attitudeNoise=`, `attitudeRate=`
 *   ([attitude]).
 * - T331: `GET` adds `correctionDeg` (the heading correction, degrees, positive east; null: none), `correction` (`line`,
 *   `changes`, `rectPx` and `resetPx` of the line's plate and its Reset in the Compose root), `hint` (the weak-compass
 *   line: `shown`, `text`, `status` read, `simulatedStatus`, `lowForMs`, `rectPx`) and `arrow.clickable` (false in Follow:
 *   a sign). `POST` adds `correction=<deg>|none` (sets it while following, 409 in Drag; `none` is Reset),
 *   `compassStatus=<0..3>|none` (a simulated accuracy status for the weak-compass rule; `none` reads the sensor's again);
 *   in Follow `drag=` is the heading correction along the level line as a finger's, and `arrow=1` answers 409 (no tap).
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
                // T330: in Follow the phone aims; only the field of view is set.
                if (a.follow) return@steer a.copy(fovDeg = (fov ?: a.fovDeg).coerceIn(PanoramaAim.MIN_FOV_DEG, PanoramaAim.MAX_FOV_DEG))
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
                app.map.onMain {
                    // T331: in Follow a drag is the heading correction (choice (f)), as the finger's.
                    if (PanoramaUi.aim?.follow == true) PanoramaFollow.dragBy(dx / steps, dy / steps)
                    else PanoramaUi.steer { a, (w, _, _) -> a.drag(dx / steps, dy / steps, w) }
                }
                Thread.sleep(34) // about two frames: each step is drawn
            }
            app.map.onMain { PanoramaUi.gesturing = false }
        }
        q["sunAt"]?.let { v ->
            val ms = if (v == "none") null else runCatching { java.time.Instant.parse(v).toEpochMilli() }.getOrNull() ?: return bad("sunAt must be an ISO instant (2026-06-21T20:00:00Z) or none")
            app.map.onMain { PanoramaUi.sunAtMs = ms }
        }
        q["target"]?.let { v ->
            if (!waitAim(app)) return Response.json(mapOf("error" to "the Panorama is not open (or its plot has no size yet)"), 409)
            val err = app.map.onMain { target(v) }
            if (err != null) return bad(err)
            Thread.sleep(PanoramaUi.TURN_MS + 300)
        }
        if (q["arrow"] == "1") {
            if (!app.map.onMain { PanoramaUi.arrow != null }) return Response.json(mapOf("error" to "no edge arrow stands"), 409)
            // T331 (choice (i)): in Follow the arrow is a sign, with no tap.
            if (!app.map.onMain { PanoramaUi.arrowClickable }) return Response.json(mapOf("error" to "the edge arrow is a sign while following the phone (no tap)"), 409)
            app.map.onMain { PanoramaUi.turnToTarget() }
            Thread.sleep(PanoramaUi.TURN_MS + 300)
        }
        // T330
        q["steering"]?.let { v ->
            val to = PanoramaSteering.of(v) ?: return bad("steering must be drag or follow")
            SettingsRoutes(app).apply(SettingsRoutes.Parsed.Ok.of(AppSettings.PANORAMA_STEERING, to))
            app.map.onMain { io.github.jasondorjeshort.dystopo.app.SettingsUi.revision++ }
            Thread.sleep(300)
        }
        attitude(app, q)?.let { return it }
        // end T330
        // T331
        q["compassStatus"]?.let { v ->
            val s = if (v == "none") null else v.toIntOrNull()?.takeIf { it in 0..3 } ?: return bad("compassStatus must be 0 to 3 or none")
            app.map.onMain { PanoramaFollow.simulatedStatus = s; PanoramaFollow.evaluateHint() }
        }
        q["correction"]?.let { v ->
            val deg = if (v == "none") null else v.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return bad("correction must be degrees (positive east) or none")
            if (!waitAim(app)) return Response.json(mapOf("error" to "the Panorama is not open (or its plot has no size yet)"), 409)
            val ok = app.map.onMain {
                if (deg == null) { PanoramaFollow.correct(PanoramaCorrection.Reset); true }
                else if (!PanoramaFollow.following) false
                else { PanoramaFollow.correct(PanoramaCorrection.Set(deg)); true }
            }
            if (!ok) return Response.json(mapOf("error" to "a correction stands only while the view follows the phone (steering=follow)"), 409)
        }
        // end T331
        if (q["back"] == "1") app.map.onMain { (app.map.activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed() }
        q["select"]?.let { name ->
            app.map.onMain {
                val cands = PanoramaData.kept.firstOrNull()?.peaks?.candidates.orEmpty()
                HorizonUi.selected = if (name == "none") null else cands.firstOrNull { it.peak.name.equals(name, ignoreCase = true) }?.let { HorizonUi.keyOf(it) }
            }
        }
        Thread.sleep(400)
        return answer(app, r)
    }

    // T330
    /**
     * T330: `attitude=<bearing>,<tilt>,<roll>|none` (a simulated attitude, true bearing), `attitudeQ=<x,y,z,w>` (a raw
     * rotation vector, as the sensor gives it), `attitudeNoise=<deg>` (a jitter on each simulated sample; alone, it changes
     * the attitude simulated now) and `attitudeRate=<deg/s>` (a steady turn about the vertical). Null, or the refusal.
     */
    private fun attitude(app: DystopoApp, q: Map<String, String>): Response? {
        val noise = q["attitudeNoise"]?.let { it.toDoubleOrNull()?.takeIf { v -> v.isFinite() && v in 0.0..30.0 } ?: return bad("attitudeNoise must be 0 to 30 degrees") }
        val rate = q["attitudeRate"]?.let { it.toDoubleOrNull()?.takeIf { v -> v.isFinite() && kotlin.math.abs(v) <= 720 } ?: return bad("attitudeRate must be -720 to 720 degrees a second") }
        val now = PanoramaFollow.simulated
        val sim: PanoramaFollow.Sim? = when {
            q["attitude"] == "none" -> null
            q["attitude"] != null -> {
                val p = q["attitude"]!!.split(",").map { it.trim().toDoubleOrNull()?.takeIf { v -> v.isFinite() } }
                if (p.size != 3 || p.any { it == null }) return bad("attitude must be bearing,tilt,roll in degrees, or none")
                if (p[1]!! !in -90.0..90.0) return bad("attitude's tilt must be -90 to 90")
                PanoramaFollow.Sim(null, p[0]!!, p[1]!!, p[2]!!, noise ?: now?.noiseDeg ?: 0.0, rate ?: 0.0)
            }
            q["attitudeQ"] != null -> {
                val v = q["attitudeQ"]!!.split(",").map { it.trim().toDoubleOrNull() ?: Double.NaN }.toDoubleArray()
                val quat = io.github.jasondorjeshort.dystopo.horizon.PhoneAttitude.quat(v) ?: return bad("attitudeQ must be x,y,z[,w] making a rotation")
                PanoramaFollow.Sim(quat, 0.0, 0.0, 0.0, noise ?: now?.noiseDeg ?: 0.0, 0.0)
            }
            noise != null || rate != null -> now?.let {
                if (rate != null && it.q == null) {
                    // A steady turn starts from the bearing the simulation has reached.
                    val t = (SystemClock.elapsedRealtime() - it.startMs) / 1000.0
                    it.copy(bearingDeg = it.bearingDeg + it.rateDegS * t, rateDegS = rate, noiseDeg = noise ?: it.noiseDeg, startMs = SystemClock.elapsedRealtime())
                } else it.copy(noiseDeg = noise ?: it.noiseDeg)
            } ?: return bad("no simulated attitude to change: set attitude= or attitudeQ= first")
            else -> return null
        }
        app.map.onMain { PanoramaFollow.simulate(sim) }
        Thread.sleep(600) // the filter's settling (its still time constant is 300 ms)
        return null
    }
    // end T330

    /** T327: `target=<kind>:<name>` on the main thread: null, or why not. */
    private fun target(v: String): String? {
        if (v == "none") {
            PanoramaUi.apply(PanoramaTargets.LetGo)
            return null
        }
        val kind = TargetKind.of(v.substringBefore(':')) ?: return "target must be peak:<name>, sun, flight:<hex or label>, satellite:<id or name>, or none"
        val name = v.substringAfter(':', "").trim()
        when (kind) {
            TargetKind.PEAK -> {
                val p = PanoramaData.kept.firstOrNull()?.peaks?.candidates.orEmpty().firstOrNull { it.peak.name.equals(name, ignoreCase = true) } ?: return "no candidate peak named $name"
                // As a tap on its row: picked (the target follows the pick); a peak already picked is turned to again.
                if (PanoramaUi.targets.target?.id == HorizonUi.keyOf(p)) PanoramaUi.turnToTarget()
                else PanoramaUi.apply(PanoramaTargets.PeakPicked(HorizonUi.keyOf(p), p.peak.name))
            }
            TargetKind.SUN -> {
                val pos = HorizonPlace.source(HorizonUi.point, Gnss.position()?.latitude, Gnss.position()?.longitude) ?: return "no position"
                val s = io.github.jasondorjeshort.dystopo.nav.Sun.position(pos.first, pos.second, PanoramaUi.sunAtMs ?: System.currentTimeMillis())
                if (PanoramaUi.targets.target?.kind == TargetKind.SUN) PanoramaUi.turnToTarget()
                else PanoramaUi.apply(PanoramaTargets.SunTapped(io.github.jasondorjeshort.dystopo.app.horizon.HorizonText.sunUp(s.apparentAltitudeDeg)))
            }
            TargetKind.FLIGHT -> {
                val a = io.github.jasondorjeshort.dystopo.app.aircraft.AircraftPoller.view.value.aircraft.firstOrNull { it.hex.equals(name, ignoreCase = true) }
                val hex = a?.hex ?: PanoramaUi.flightMarks.firstOrNull { it.label.equals(name, ignoreCase = true) }?.hex ?: return "no flight $name"
                PanoramaUi.apply(PanoramaTargets.FlightPicked(hex, PanoramaUi.flightMarks.firstOrNull { it.hex == hex }?.label))
            }
            TargetKind.SATELLITE -> {
                val s = Gnss.satellites.firstOrNull { it.id.equals(name, ignoreCase = true) || io.github.jasondorjeshort.dystopo.app.horizon.HorizonText.satelliteName(it).equals(name, ignoreCase = true) } ?: return "no satellite $name"
                if (PanoramaUi.targets.target?.id == s.id) PanoramaUi.turnToTarget()
                else PanoramaUi.apply(PanoramaTargets.SatelliteTapped(s.id, io.github.jasondorjeshort.dystopo.app.horizon.HorizonText.satelliteName(s)))
            }
        }
        return null
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
                    place.rangeM == AppSettings.viewRangeKm * 1000.0 && place.skylines.isNotEmpty() &&
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
                    // In the Compose root, as rectPx and the other rectangles.
                    "zoomButtonsPx" to PanoramaUi.plotPx?.let { r ->
                        PanoramaScene.zoomButtons(w, h, dp).map { b -> listOf(b.left + r[0], b.top + r[1], b.right + r[0], b.bottom + r[1]).map { Math.round(it) } }
                    },
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
            // T327
            "layers" to PanoramaUi.layersDrawn,
            "target" to PanoramaUi.targets.target?.let { t ->
                val dir = PanoramaUi.targetDir
                val at = dir?.let { scene?.lens?.mark(it.first, it.second) }
                linkedMapOf(
                    "kind" to t.kind.key, "id" to t.id, "name" to t.name, "elDeg" to dir?.first?.let(::r2), "azDeg" to dir?.second?.let(::r1),
                    "onView" to (at != null), "x" to at?.let { r1(it.x) }, "y" to at?.let { r1(it.y) },
                    "third" to at?.let { p -> size?.let { (w, _, _) -> if (p.x < w / 3) "left" else if (p.x > 2 * w / 3) "right" else "middle" } },
                )
            },
            "picks" to linkedMapOf("peak" to PanoramaUi.targets.peak, "flight" to PanoramaUi.targets.flight),
            "arrow" to PanoramaUi.arrow?.let { a ->
                linkedMapOf(
                    "side" to a.side.word, "degrees" to r1(a.degrees), "text" to a.text, "x" to r1(a.x), "y" to r1(a.y), "rectPx" to PanoramaUi.arrowPx,
                    "clickable" to PanoramaUi.arrowClickable, // T331: false in Follow (a sign)
                )
            },
            "turns" to linkedMapOf("done" to PanoramaUi.turnsDone, "last" to PanoramaUi.lastTurn),
            "sunAt" to PanoramaUi.sunAtMs?.let { java.time.Instant.ofEpochMilli(it).toString() },
            "aircraftOwners" to io.github.jasondorjeshort.dystopo.app.aircraft.AircraftPoller.owners().sorted(),
            // T330
            "steering" to AppSettings.panoramaSteering.key, "following" to PanoramaFollow.following,
            "attitude" to PanoramaFollow.json(), "resting" to PanoramaFollow.state?.resting,
            "aimFollow" to aim?.let { a -> linkedMapOf("follow" to a.follow, "rollDeg" to r2(a.rollDeg)) },
            "followButton" to linkedMapOf(
                "shown" to (io.github.jasondorjeshort.dystopo.app.controls.Heading.attitudeAvailable && aim != null),
                "rectPx" to PanoramaUi.followPx,
            ),
            "keepScreenOn" to PanoramaUi.keepScreenOn,
            "compass" to io.github.jasondorjeshort.dystopo.app.controls.Heading.compassJson().let { c -> linkedMapOf("running" to c["running"], "gnssWanted" to c["gnssWanted"]) },
            "frames" to linkedMapOf(
                "drawMsLast" to r2(PanoramaUi.drawMsLast), "drawMsMax" to r2(PanoramaUi.drawMsMax), "draws" to PanoramaUi.draws,
                "drawsLastSecond" to PanoramaUi.drawsPerSecond(System.nanoTime()),
            ),
            // end T330
            // T331
            "correctionDeg" to PanoramaFollow.correctionDeg?.let(::r2),
            "correction" to linkedMapOf(
                "line" to PanoramaFollow.correctionDeg?.let { io.github.jasondorjeshort.dystopo.horizon.HeadingCorrection.line(it) },
                "changes" to PanoramaFollow.correctionChanges, "rectPx" to PanoramaUi.correctionPx, "resetPx" to PanoramaUi.resetPx,
            ),
            "hint" to linkedMapOf(
                "shown" to PanoramaFollow.hintShown, "text" to CompassHint.LINE.takeIf { PanoramaFollow.hintShown },
                "status" to PanoramaFollow.statusNow, "simulatedStatus" to PanoramaFollow.simulatedStatus,
                "lowForMs" to PanoramaFollow.hintState.lowSinceMs?.let { SystemClock.elapsedRealtime() - it }, "rectPx" to PanoramaUi.hintPx,
            ),
            // end T331
        )
    }
}
