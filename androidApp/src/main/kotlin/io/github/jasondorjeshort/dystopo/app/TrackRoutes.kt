package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.tracking.ReplayChecks
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
 * - `POST /tracks/record?name=&source=all|gps|injected`: start recording (stops the current track).
 * - `POST /tracks/stop`, `POST /tracks/pause`, `POST /tracks/resume`.
 * - `GET /tracks/settings`; `POST /tracks/settings?gapS=600`: the automatic new-segment gap in
 *   seconds (0 = never split on time), persisted.
 * - `GET /tracks/{id}` (summary, stats, waypoints), `GET /tracks/{id}.gpx` (the GPX export, ids in
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
 * - `POST /tracks/import` with a GPX body (`?name=` names a track the file leaves unnamed), or
 *   `POST /tracks/import?file=<name>` for a file in `files/tracks/`; `GET /tracks/files` lists those.
 *   Tracks keep their ids from `dystopo:id`; ids already stored are skipped.
 * - `POST /location/replay?rate=10[&record=name][&accuracy=5]` with a GPX body: replays its track
 *   points as injected fixes at `rate` × real time (optionally recording them into a new track; T57: 409
 *   with `record=` while a track is already recording,
 *   source `injected`); `GET /location/replay` progress; `DELETE /location/replay` cancels.
 *
 * `POST /location` (T10/T27) is observed, not handled: the fix also goes to the recorder's injected
 * source and [handle] returns null so [Routes] still moves the puck.
 */
object TrackRoutes {
    private const val UUID = "[0-9a-fA-F-]{36}"
    private val trackPath = Regex("^/tracks/($UUID|-)(\\.gpx|/waypoint|/export|/show|/profile)?$")
    private val waypointPath = Regex("^/tracks/waypoints/($UUID)(/select)?$")

    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/tracks" && !r.path.startsWith("/tracks/") && !r.path.startsWith("/location")) return null
        val t = Tracking.of(app)
        val p = params(r)
        when (r.method to r.path) {
            "POST" to "/location" -> {
                val lat = p.d("lat")
                val lon = p.d("lon")
                if (lat != null && lon != null) {
                    t.injected.publish(LocationFix(lat, lon, p.d("ele") ?: p.d("alt"), System.currentTimeMillis(), p.d("accuracy") ?: 10.0, "debug"))
                }
                return null
            }
            "GET" to "/tracks" -> return Response.json(overview(t))
            "POST" to "/tracks/record" -> {
                val mode = when (p.s("source")?.lowercase()) {
                    null, "all" -> Tracking.Mode.ALL
                    "gps" -> Tracking.Mode.GPS
                    "injected" -> Tracking.Mode.INJECTED
                    else -> return Response.json(mapOf("error" to "source must be all, gps or injected"), 400)
                }
                val s = t.start(p.s("name"), mode)
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
                ReplayChecks.refusal(rate, r.body.isEmpty(), p.s("record"), recording)?.let { (status, error) ->
                    return Response.json(if (status == 409) mapOf("error" to error, "recording" to recorderJson(t)) else mapOf("error" to error), status)
                }
                p.s("record")?.let { t.start(it, Tracking.Mode.INJECTED) }
                return try {
                    Response.json(t.replay.start(r.bodyText, rate, p.d("accuracy") ?: 5.0) + mapOf("recording" to t.recorder.activeId))
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
            "GET" to "" -> Response.json(trackJson(t, summary) + mapOf("waypoints" to t.call { t.store.waypoints(id) }.map(::waypointJson)))
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

    private fun recorderJson(t: Tracking): Map<String, Any?> = t.call {
        val rec = t.recorder
        mapOf(
            "id" to rec.activeId, "mode" to t.mode.name.lowercase(), "paused" to rec.paused, "service" to t.serviceState, "gps" to t.gpsState,
            "acceptedFixes" to rec.accepted, "rejectedAccuracy" to rec.rejectedAccuracy, "rejectedTime" to rec.rejectedTime,
            "skippedPaused" to rec.skippedPaused, "gapSplits" to rec.gapSplits, "gpsIgnored" to t.gpsIgnored,
            "liveStats" to rec.liveStats()?.let(::statsJson),
        )
    }

    private fun settingsJson(t: Tracking): Map<String, Any?> = mapOf("gapS" to t.gapSeconds)

    private fun overview(t: Tracking): Map<String, Any?> {
        val recording = if (t.recorder.recording) recorderJson(t) else null
        return t.call {
            val rec = t.recorder
            val tracks = t.store.list()
            val ui = t.ui
            mapOf(
                "recording" to recording,
                "lastFix" to rec.lastFix?.let { mapOf("lat" to it.lat, "lon" to it.lon, "ele" to it.ele, "time" to it.time, "source" to it.source) },
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
                "tracks" to tracks.map { s -> summaryJson(s) + mapOf("stats" to t.statsOf(s)?.let(::statsJson)) },
                "statsComputed" to t.store.statsComputed,
            )
        }
    }

    private fun trackJson(t: Tracking, s: TrackSummary): Map<String, Any?> =
        summaryJson(s) + mapOf("stats" to t.call { t.statsOf(s) }?.let(::statsJson))

    private fun summaryJson(s: TrackSummary): Map<String, Any?> = mapOf(
        "id" to s.id, "name" to s.name, "createdAt" to s.createdAt, "updatedAt" to s.updatedAt,
        "recording" to s.recording, "points" to s.points, "waypoints" to s.waypoints,
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
