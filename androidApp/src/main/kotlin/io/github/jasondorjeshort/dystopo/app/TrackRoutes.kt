package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.tracking.LongPressMenu
import io.github.jasondorjeshort.dystopo.app.tracking.ReplayChecks
import io.github.jasondorjeshort.dystopo.app.tracking.ReplayScript
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.store.LocationFix
import io.github.jasondorjeshort.dystopo.store.StoredWaypoint
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.TrackImport
import io.github.jasondorjeshort.dystopo.store.TrackSummary

/**
 * Debug routes for tracks (T31, T40), consulted by [Routes] before its own (debug builds only):
 * - `GET /tracks`: recorder state (mode, paused, accepted/rejected/paused fixes, gap splits, live
 *   stats), every stored track with its (store-cached) stats, the shown track, open sheet and
 *   profile, overlay, settings and store info (incl. `statsComputed`, the stats cache misses).
 *   T159: `state` (`idle`, `recording`, `waiting-for-gps`, `paused`, `auto-paused`, `stopped-by-system`), each track's
 *   `gaps` count, and in `recording`: `state` and `stateLine` (what the controls show), `pendingGap`, `silentS` (since the
 *   last GPS fix) and `lastAccuracyM`, `watchdog` (`count` of re-registrations, `timeoutMs` from the profile,
 *   `registrations` of the GPS listener), `fallback` (`provider`, `listening`, `fixes`, `ignored`), `notification`
 *   (`title`, `text`, `posts`, `dismissals`, `reposts`), the service (`serviceForeground`, `serviceDown`,
 *   `serviceRestarts` by the system, `tasksRemoved`), `flagged`, `gapsWritten` and `gaps` (the active track's gap list:
 *   `start`, `end`, `durationS`, `reason`). `lastFix` carries the accuracy, speed, course and vertical accuracy.
 * - `POST /tracks/record?name=&source=all|gps|injected[&activity=foot|bike|road|rail|boat|air]`: start recording (stops the
 *   current track); T172: with that activity's profile from the settings rows (default: the remembered activity, which
 *   this does not change). Tracks and the recording carry `activity`; the recording also `profile` (its four choices and the
 *   encoded `value`). The activity picker's and the battery card's routes: [io.github.jasondorjeshort.dystopo.app.tracking.ActivityPicker.handle],
 *   [io.github.jasondorjeshort.dystopo.app.tracking.BatteryExemption.handle].
 * - `POST /tracks/stop`, `POST /tracks/pause`, `POST /tracks/resume`.
 * - `GET /tracks/settings`; `POST /tracks/settings?gapS=600`: the automatic new-segment gap in
 *   seconds (0 = never split on time), persisted.
 * - `GET /tracks/{id}` (summary, stats, waypoints, T159 `gaps`), `GET /tracks/{id}.gpx` (the GPX export, ids in
 *   `dystopo:id` extensions), `POST /tracks/{id}/export` (write it to `files/tracks/`),
 *   `POST /tracks/{id}/show`, `DELETE /tracks/{id}`.
 * - `GET /tracks/{id}/profile?n=200`: elevation profile samples (T24's sampling);
 *   `POST /tracks/{id}/profile` opens the profile panel on the map; `POST /tracks/close` closes the
 *   profile panel and the waypoint sheet.
 * - `POST /tracks/{id}/waypoint?name=[&lat=&lon=&ele=]`: a waypoint at the given or the last known position
 *   (`/tracks/-/waypoint` for a free-standing one).
 * - `GET /tracks/waypoints/{wid}`; `POST /tracks/waypoints/{wid}?name=` renames;
 *   `DELETE /tracks/waypoints/{wid}`; `POST /tracks/waypoints/{wid}/select` opens the rename/delete
 *   sheet (what tapping the waypoint on the map does).
 * - T91, waypoints at any time: `POST /waypoint[?lat=&lon=][&name=]` is the map's Waypoint chip (shown
 *   folded or not, recording or not): a waypoint at the given point or at "here" (the recorder's last fix or
 *   the map's last device fix, whichever is newer), on the recording track if one records, else
 *   free-standing; it opens the waypoint sheet. 409 with no position. `GET /tracks` lists the
 *   free-standing ones (`freeWaypoints`, drawn whatever track is shown) and `lastDrop`.
 *   `POST /map/longpress?lat=&lon=` is a long-press on the map: it opens the long-press card (T114: "Route to
 *   here", "Waypoint here", "Route from here", Cancel), or while a route waits for its end (or, after Choose on map,
 *   its start) passes straight to the route; `POST /map/longpress/to|waypoint|route|cancel` taps the card's entries
 *   (`to` is Route to here, `route` Route from here); `GET /map/longpress` its state (`lastAction`, `actions`).
 * - `POST /tracks/import` with a GPX body (`?name=` names a track the file leaves unnamed), or
 *   `POST /tracks/import?file=<name>` for a file in `files/tracks/`; `GET /tracks/files` lists those.
 *   Tracks keep their ids from `dystopo:id`; ids already stored are skipped.
 * - `POST /location/replay?rate=10[&record=name][&accuracy=5]` with a GPX body: replays its track
 *   points as injected fixes at `rate` × real time (optionally recording them into a new track; T57: 409
 *   with `record=` while a track is already recording,
 *   source `injected`); `GET /location/replay` progress; `DELETE /location/replay` cancels.
 *   T146: `POST /location/replay?route=current&kmh=40[&rate=30]` with no body replays the current route as a timed
 *   track (one fix a second at `kmh`, the same fixes as `GET /route/gpx?track=1&kmh=`) built in the app, so the 1 MiB
 *   body limit does not bind (404 with no route; a body as well is a 400). `GET /location/replay` has `running`,
 *   `finished` (the last replay sent every fix), `cancelled`, `source` (`body` or `route`), `kmh`, `fixes`, `sent`,
 *   `rate`, `durationMs` and `elapsedMs`.
 *   T159 ([ReplayScript]): `accuracy=5,120@40-60,200@80` reports fixes 40 to 60 (numbered from 0) at 120 m and fix 80 at
 *   200 m over the default 5 m (a point's own accuracy otherwise); `stall=60@100` holds the replay for 60 s of real time
 *   before fix 100 (`stall=60`: before the middle fix), the later fixes stamped 60 s later, so the GPS goes silent;
 *   `GET /location/replay` gains `script` and `stalling`. A bad `accuracy=` or `stall=` is a 400 before anything starts.
 *
 * `POST /location` (T10/T27) is observed, not handled: the fix also goes to the recorder's injected
 * source and [handle] returns null so [Routes] still moves the puck. T159: `&provider=network` (or `passive`) gives the
 * fix that source, so it stands in for the recording's network fallback (taken only while the GPS is silent).
 */
object TrackRoutes {
    private const val UUID = "[0-9a-fA-F-]{36}"
    private val trackPath = Regex("^/tracks/($UUID|-)(\\.gpx|/waypoint|/export|/show|/profile)?$")
    private val waypointPath = Regex("^/tracks/waypoints/($UUID)(/select)?$")

    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/tracks" && !r.path.startsWith("/tracks/") && !r.path.startsWith("/location") && r.path != "/waypoint" && !r.path.startsWith("/map/longpress")) return null
        io.github.jasondorjeshort.dystopo.app.tracking.ActivityPicker.handle(app, r)?.let { return it } // T172: the picker, a track's activity
        io.github.jasondorjeshort.dystopo.app.tracking.BatteryExemption.handle(app, r)?.let { return it } // T172: the battery card
        val t = Tracking.of(app)
        val p = params(r)
        when (r.method to r.path) {
            "POST" to "/location" -> {
                val lat = p.d("lat")
                val lon = p.d("lon")
                if (lat != null && lon != null) {
                    // T159: provider=network (or passive) stands in for the recording's network fallback.
                    val source = when (p.s("provider")?.lowercase()) { "network" -> "network"; "passive" -> "passive"; else -> "debug" }
                    t.injected.publish(LocationFix(lat, lon, p.d("ele") ?: p.d("alt"), System.currentTimeMillis(), p.d("accuracy") ?: 10.0, source))
                }
                return null
            }
            "GET" to "/tracks" -> return Response.json(overview(t))
            "POST" to "/waypoint" -> {
                val lat = p.d("lat")
                val lon = p.d("lon")
                if ((lat == null) != (lon == null)) return Response.json(mapOf("error" to "pass both lat and lon, or neither"), 400)
                val w = t.dropWaypoint(lat, lon, p.s("name"), via = "route")
                    ?: return Response.json(mapOf("error" to "no position yet: pass lat and lon, or inject a fix first"), 409)
                return Response.json(storedJson(w) + mapOf("sheetOpen" to (t.selectedWaypointId == w.waypoint.id), "drop" to t.lastDrop))
            }
            "GET" to "/map/longpress" -> return Response.json(app.map.onMain { LongPressMenu.json() })
            "POST" to "/map/longpress" -> {
                val lat = p.d("lat") ?: return Response.json(mapOf("error" to "lat and lon required"), 400)
                val lon = p.d("lon") ?: return Response.json(mapOf("error" to "lat and lon required"), 400)
                return Response.json(app.map.onMain { LongPressMenu.longPress(lat, lon); LongPressMenu.json() })
            }
            "POST" to "/map/longpress/to", "POST" to "/map/longpress/waypoint", "POST" to "/map/longpress/route", "POST" to "/map/longpress/cancel" -> {
                val action = r.path.substringAfterLast('/')
                val ok = app.map.onMain {
                    val open = LongPressMenu.point != null
                    when (action) {
                        "to" -> LongPressMenu.routeTo() // T114
                        "waypoint" -> LongPressMenu.waypoint()
                        "route" -> LongPressMenu.route()
                        else -> LongPressMenu.cancel()
                    }
                    open
                }
                if (!ok) return Response.json(mapOf("error" to "the long-press card is not open"), 409)
                return Response.json(app.map.onMain { LongPressMenu.json() })
            }
            "POST" to "/tracks/record" -> {
                val mode = when (p.s("source")?.lowercase()) {
                    null, "all" -> Tracking.Mode.ALL
                    "gps" -> Tracking.Mode.GPS
                    "injected" -> Tracking.Mode.INJECTED
                    else -> return Response.json(mapOf("error" to "source must be all, gps or injected"), 400)
                }
                val activity = p.s("activity")?.let { io.github.jasondorjeshort.dystopo.model.Activity.of(it.lowercase()) ?: return Response.json(mapOf("error" to "activity must be foot, bike, road, rail, boat or air"), 400) } // T172
                val s = t.start(p.s("name"), mode, activity)
                return Response.json(mapOf("started" to summaryJson(s), "service" to t.serviceState))
            }
            "POST" to "/tracks/stop" -> {
                val s = t.stop() ?: return Response.json(mapOf("error" to "not recording"), 409)
                return Response.json(trackJson(t, s))
            }
            "POST" to "/tracks/pause" -> {
                if (!t.pause()) return Response.json(mapOf("error" to "not recording"), 409)
                return Response.json(recorderJson(t))
            }
            "POST" to "/tracks/resume" -> {
                if (!t.resume()) return Response.json(mapOf("error" to "not recording"), 409)
                return Response.json(recorderJson(t))
            }
            "GET" to "/tracks/settings" -> return Response.json(settingsJson(t))
            "POST" to "/tracks/settings" -> {
                p.s("gapS")?.let { raw ->
                    val v = raw.toLongOrNull()
                    if (v == null || v < 0 || v > 86_400) return Response.json(mapOf("error" to "gapS must be 0 (never split) to 86400 seconds"), 400)
                    t.setGapSeconds(v)
                }
                return Response.json(settingsJson(t))
            }
            "POST" to "/tracks/close" -> {
                t.selectWaypoint(null)
                t.showProfile(null)
                return Response.json(mapOf("closed" to true))
            }
            "GET" to "/tracks/files" -> return Response.json(
                mapOf("dir" to t.filesDir.path, "files" to t.importableFiles().map { mapOf("name" to it.name, "bytes" to it.length(), "modified" to it.lastModified()) }),
            )
            "POST" to "/tracks/import" -> return import(t, r, p)
            "GET" to "/location/replay" -> return Response.json(t.replay.json())
            "DELETE" to "/location/replay" -> {
                t.replay.cancel()
                return Response.json(t.replay.json())
            }
            "POST" to "/location/replay" -> {
                val rate = p.d("rate") ?: 1.0
                // T57: record= while a track is recording would leave that one behind (empty when it had just
                // started) beside the replay's own: refuse, and say how to get either. T65: the rules are
                // ReplayChecks.refusal, unit-tested.
                val recording = p.s("record") != null && t.call { t.recorder.recording }
                // T146: `route=current&kmh=` replays the current route timed in the app, no body.
                val route = p.s("route")
                val kmh = p.d("kmh")
                val plan = if (route == "current") io.github.jasondorjeshort.dystopo.app.routing.RouteSession.plan else null
                ReplayChecks.refusal(rate, r.body.isEmpty(), p.s("record"), recording, route, kmh, plan != null, TimedTrack.MAX_KMH)?.let { (status, error) ->
                    return Response.json(if (status == 409) mapOf("error" to error, "recording" to recorderJson(t)) else mapOf("error" to error), status)
                }
                // T159: accuracy= may carry ranges (120@40-60) and stall= a silence; checked before anything starts.
                val accuracy = try {
                    ReplayScript.defaultAccuracy(p.s("accuracy")) ?: 5.0
                } catch (e: IllegalArgumentException) {
                    return Response.json(mapOf("error" to e.message), 400)
                }
                var scriptError: String? = null
                val script = { fixes: Int ->
                    try {
                        ReplayScript.parse(p.s("accuracy"), p.s("stall"), fixes)
                    } catch (e: IllegalArgumentException) {
                        scriptError = e.message
                        throw ScriptRefused()
                    }
                }
                return try {
                    if (plan == null) io.github.jasondorjeshort.dystopo.gpx.Gpx.parse(r.bodyText).tracks.flatMap { it.points }.size.let(script)
                    p.s("record")?.let { t.start(it, Tracking.Mode.INJECTED) }
                    val started = if (plan != null && kmh != null) {
                        val track = TimedTrack.track(plan.route, kmh, System.currentTimeMillis() / 1000 * 1000)
                        t.replay.startTracks(listOf(track), rate, accuracy, "route", kmh, script)
                    } else t.replay.start(r.bodyText, rate, accuracy, script)
                    Response.json(started + mapOf("recording" to t.recorder.activeId))
                } catch (e: ScriptRefused) {
                    Response.json(mapOf("error" to scriptError), 400)
                } catch (e: IllegalArgumentException) {
                    // GpxException and XmlException are IllegalArgumentExceptions too.
                    Response.json(mapOf("error" to "bad GPX: ${e.message}"), 400)
                }
            }
        }
        waypointPath.matchEntire(r.path)?.let { return waypointRoute(t, r, p, it.groupValues[1], it.groupValues[2]) }
        val m = trackPath.matchEntire(r.path) ?: return if (r.path.startsWith("/tracks")) Response.notFound() else null
        val id = m.groupValues[1]
        val suffix = m.groupValues[2]
        if (id == "-") {
            if (r.method == "POST" && suffix == "/waypoint") return waypoint(t, null, p)
            return Response.notFound()
        }
        val summary = t.call { t.store.summary(id) } ?: return Response.json(mapOf("error" to "no track $id"), 404)
        return when (r.method to suffix) {
            "GET" to "" -> Response.json(
                trackJson(t, summary) + mapOf("waypoints" to t.call { t.store.waypoints(id) }.map(::waypointJson), "gaps" to t.call { t.store.gaps(id) }.map(::gapJson)),
            )
            "DELETE" to "" -> Response.json(mapOf("deleted" to t.delete(id)))
            "GET" to ".gpx" -> {
                val gpx = t.call { TrackExport.gpx(t.store, id) }!!
                Response(200, "application/gpx+xml; charset=utf-8", gpx.toByteArray())
            }
            "POST" to "/export" -> {
                val f = t.export(id)!!
                Response.json(mapOf("path" to f.path, "bytes" to f.length(), "name" to f.name))
            }
            "POST" to "/show" -> Response.json(mapOf("shown" to t.show(id), "overlayFeatures" to t.overlay.features))
            "POST" to "/waypoint" -> waypoint(t, id, p)
            "GET" to "/profile" -> {
                val n = p.d("n")?.toInt() ?: Tracking.PROFILE_SAMPLES
                if (n !in 2..5000) return Response.json(mapOf("error" to "n must be 2 to 5000"), 400)
                val samples = t.call { t.profileOf(id, n) }.orEmpty()
                Response.json(
                    mapOf(
                        "id" to id, "n" to samples.size, "distanceM" to samples.lastOrNull()?.distanceM,
                        "minEleM" to samples.mapNotNull { it.eleM }.minOrNull(), "maxEleM" to samples.mapNotNull { it.eleM }.maxOrNull(),
                        "samples" to samples.map { listOf(it.distanceM, it.eleM, it.lat, it.lon) },
                    ),
                )
            }
            "POST" to "/profile" -> Response.json(mapOf("profile" to t.showProfile(id), "shown" to t.shownId))
            else -> Response.notFound()
        }
    }

    private fun waypointRoute(t: Tracking, r: Request, p: Params, wid: String, suffix: String): Response {
        val w = t.call { t.store.waypoint(wid) } ?: return Response.json(mapOf("error" to "no waypoint $wid"), 404)
        return when (r.method to suffix) {
            "GET" to "" -> Response.json(storedJson(w))
            "POST" to "" -> {
                val name = p.s("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return Response.json(mapOf("error" to "pass name="), 400)
                t.renameWaypoint(wid, name)
                Response.json(storedJson(t.call { t.store.waypoint(wid) }!!))
            }
            "DELETE" to "" -> Response.json(mapOf("deleted" to t.deleteWaypoint(wid)))
            "POST" to "/select" -> {
                w.trackId?.let { if (t.shownId != it) t.show(it) }
                Response.json(mapOf("selected" to t.selectWaypoint(wid)) + storedJson(w))
            }
            else -> Response.notFound()
        }
    }

    private fun import(t: Tracking, r: Request, p: Params): Response {
        return try {
            val file = p.s("file")
            val res: TrackImport.Result = when {
                file != null -> t.importFile(file)
                r.body.isNotEmpty() && !r.bodyText.trimStart().startsWith("{") -> t.importGpx(r.bodyText, p.s("name") ?: "Imported track")
                else -> return Response.json(mapOf("error" to "POST a GPX body, or ?file=<name> from ${t.filesDir.path}"), 400)
            }
            Response.json(
                mapOf(
                    "imported" to res.imported.map(::summaryJson), "skippedTracks" to res.skippedTracks,
                    "waypoints" to res.waypoints, "skippedWaypoints" to res.skippedWaypoints,
                    "routesIgnored" to res.routesIgnored, "ignored" to res.ignored, "shown" to t.shownId,
                ),
            )
        } catch (e: IllegalArgumentException) {
            Response.json(mapOf("error" to "import failed: ${e.message}"), 400)
        }
    }

    private fun waypoint(t: Tracking, trackId: String?, p: Params): Response {
        val w = t.call { t.recorder.waypoint(p.s("name"), trackId, p.d("lat"), p.d("lon"), p.d("ele")) }
            ?: return Response.json(mapOf("error" to "no position: pass lat and lon, or inject a fix first"), 409)
        t.refreshUi()
        t.refreshOverlay(force = true)
        return Response.json(waypointJson(w) + mapOf("track" to trackId))
    }

    private class ScriptRefused : RuntimeException()

    private fun recorderJson(t: Tracking): Map<String, Any?> = t.call {
        val rec = t.recorder
        val now = android.os.SystemClock.elapsedRealtime()
        val w = t.watch
        linkedMapOf(
            "id" to rec.activeId, "mode" to t.mode.name.lowercase(), "paused" to rec.paused, "service" to t.serviceState, "gps" to t.gpsState,
            "activity" to rec.profile.activity.code, "profile" to io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles.json(rec.profile), // T172
            // T159: the one state, the line the controls show, and what drives it.
            "state" to t.stateNow().code, "stateLine" to t.ui.stateLine, "autoPaused" to rec.autoPaused, "pendingGap" to rec.pendingGap?.code,
            "serviceForeground" to t.serviceForeground, "serviceDown" to t.serviceDown, "serviceRestarts" to t.serviceRestarts, "tasksRemoved" to t.tasksRemoved,
            "stoppedBySystemAt" to t.stoppedBySystemAt,
            "silentS" to w.silentMs(now) / 1000.0, "lastAccuracyM" to w.lastPrimaryAccuracyM,
            "watchdog" to linkedMapOf(
                "count" to w.watchdogRestarts, "timeoutMs" to rec.profile.watchdogMs, "registrations" to t.gps.registrations,
                "lastAgoS" to if (w.watchdogRestarts == 0L) null else (now - w.lastWatchdogAt) / 1000.0,
            ),
            "fallback" to linkedMapOf(
                "enabled" to rec.profile.networkFallback, "provider" to t.fallback.name, "listening" to t.fallback.listening,
                "fixes" to w.fallbackFixes, "ignored" to t.fallbackIgnored, "lastAccuracyM" to w.lastFallbackAccuracyM,
                "afterS" to w.waitingAfterMs / 1000,
            ),
            "notification" to t.notification.json(),
            "acceptedFixes" to rec.accepted, "flagged" to rec.flagged, "rejectedSpeed" to rec.rejectedSpeed, "rejectedTime" to rec.rejectedTime, // T158
            "skippedPaused" to rec.skippedPaused, "skippedAutoPaused" to rec.skippedAutoPaused, "gapSplits" to rec.gapSplits, "gapsWritten" to rec.gapsWritten,
            "gpsIgnored" to t.gpsIgnored,
            "gaps" to rec.activeId?.let { id -> t.store.gaps(id).map(::gapJson) },
            "liveStats" to rec.liveStats()?.let(::statsJson),
        )
    }

    private fun gapJson(g: io.github.jasondorjeshort.dystopo.model.TrackGap): Map<String, Any?> =
        linkedMapOf("start" to g.start, "end" to g.end, "durationS" to g.durationMs / 1000.0, "reason" to g.reason.code)

    private fun settingsJson(t: Tracking): Map<String, Any?> = mapOf("gapS" to t.gapSeconds)

    private fun overview(t: Tracking): Map<String, Any?> {
        val recording = if (t.recorder.recording) recorderJson(t) else null
        return t.call {
            val rec = t.recorder
            val tracks = t.store.list()
            val ui = t.ui
            mapOf(
                "state" to t.state.code, // T159
                "recording" to recording,
                "freeWaypoints" to t.store.waypoints(null).map(::waypointJson), // T91
                "lastDrop" to t.lastDrop,
                "lastFix" to rec.lastFix?.let {
                    mapOf(
                        "lat" to it.lat, "lon" to it.lon, "ele" to it.ele, "time" to it.time, "source" to it.source,
                        "accuracyM" to it.accuracyM, "speedMps" to it.speedMps, "courseDeg" to it.courseDeg, "verticalAccuracyM" to it.verticalAccuracyM, // T159
                    )
                },
                "shown" to t.shownId,
                "selectedWaypoint" to t.selectedWaypointId,
                "profile" to t.profileId,
                "ui" to mapOf("paused" to ui.paused, "sheetOpen" to (ui.selectedWaypoint != null), "profileSamples" to ui.profile?.samples?.size),
                "overlay" to mapOf("features" to t.overlay.features, "labels" to t.overlay.labels, "installed" to t.overlay.installedOnStyle),
                "replay" to t.replay.json(),
                "settings" to settingsJson(t),
                "interruptedAtStartup" to t.interrupted,
                "store" to mapOf(
                    "type" to "sqldelight", "file" to t.dbFile.path, "bytes" to t.dbFile.length(), "tracks" to tracks.size,
                    "schemaVersion" to io.github.jasondorjeshort.dystopo.store.db.TrackDb.Schema.version,
                ),
                "tracks" to tracks.map { s -> summaryJson(s) + mapOf("stats" to t.statsOf(s)?.let(::statsJson), "gaps" to t.store.gaps(s.id).size) },
                "statsComputed" to t.store.statsComputed,
            )
        }
    }

    private fun trackJson(t: Tracking, s: TrackSummary): Map<String, Any?> =
        summaryJson(s) + mapOf("stats" to t.call { t.statsOf(s) }?.let(::statsJson))

    private fun summaryJson(s: TrackSummary): Map<String, Any?> = mapOf(
        "id" to s.id, "name" to s.name, "createdAt" to s.createdAt, "updatedAt" to s.updatedAt,
        "recording" to s.recording, "points" to s.points, "waypoints" to s.waypoints,
        "activity" to s.activity?.code, // T172
    )

    private fun waypointJson(w: Waypoint): Map<String, Any?> =
        mapOf("id" to w.id, "name" to w.name, "lat" to w.lat, "lon" to w.lon, "ele" to w.ele, "time" to w.time)

    private fun storedJson(w: StoredWaypoint): Map<String, Any?> = waypointJson(w.waypoint) + mapOf("track" to w.trackId)

    private fun statsJson(s: TrackStats): Map<String, Any?> = mapOf(
        "segments" to s.segments, "points" to s.points, "distanceM" to s.distanceM,
        "ascentM" to s.ascentM, "descentM" to s.descentM, "minEleM" to s.minEleM, "maxEleM" to s.maxEleM,
        "startTime" to s.startTime, "endTime" to s.endTime, "durationS" to s.durationS, "movingTimeS" to s.movingTimeS,
        "movingSpeedMps" to s.movingSpeedMps, "movingPaceSPerKm" to s.movingPaceSPerKm,
        "bounds" to s.bounds?.let { listOf(it.west, it.south, it.east, it.north) },
    )

    private class Params(val map: Map<String, Any?>) {
        fun d(key: String): Double? = when (val v = map[key]) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() }

        fun s(key: String): String? = map[key]?.toString()?.takeIf { it.isNotEmpty() }
    }

    /** Query parameters, overlaid by a JSON object body when there is one (a GPX body is left alone). */
    private fun params(r: Request): Params {
        val m = HashMap<String, Any?>(r.query)
        val body = r.bodyText.trim()
        if (body.startsWith("{")) runCatching { m.putAll(MiniJson.parseObject(body)) }
        return Params(m)
    }
}
