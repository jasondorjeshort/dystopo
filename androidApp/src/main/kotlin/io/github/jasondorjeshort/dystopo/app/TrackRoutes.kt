package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.gpx.GpxException
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.store.LocationFix
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.TrackSummary
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson

/**
 * Debug routes for tracks (T31), consulted by [Routes] before its own (debug builds only):
 * - `GET /tracks`: recorder state (mode, accepted/rejected fixes, live stats), every stored track
 *   with T24 stats, the shown track, overlay and store info.
 * - `POST /tracks/record?name=&source=all|gps|injected`: start recording (stops the current track).
 * - `POST /tracks/stop`.
 * - `GET /tracks/{id}` (summary + stats), `GET /tracks/{id}.gpx` (the GPX export),
 *   `POST /tracks/{id}/export` (write it to `files/tracks/`), `POST /tracks/{id}/show`, `DELETE /tracks/{id}`.
 * - `POST /tracks/{id}/waypoint?name=[&lat=&lon=&ele=]`: a waypoint at the given or the last known position
 *   (`/tracks/-/waypoint` for a free-standing one).
 * - `POST /location/replay?rate=10[&record=name][&accuracy=5]` with a GPX body: replays its track
 *   points as injected fixes at `rate` × real time (optionally recording them into a new track,
 *   source `injected`); `GET /location/replay` progress; `DELETE /location/replay` cancels.
 *
 * `POST /location` (T10/T27) is observed, not handled: the fix also goes to the recorder's injected
 * source and [handle] returns null so [Routes] still moves the puck.
 */
object TrackRoutes {
    private val trackPath = Regex("^/tracks/([0-9a-fA-F-]{36}|-)(\\.gpx|/waypoint|/export|/show)?$")

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
            "GET" to "/location/replay" -> return Response.json(t.replay.json())
            "DELETE" to "/location/replay" -> {
                t.replay.cancel()
                return Response.json(t.replay.json())
            }
            "POST" to "/location/replay" -> {
                val rate = p.d("rate") ?: 1.0
                if (!(rate > 0 && rate <= 10_000)) return Response.json(mapOf("error" to "rate must be in (0, 10000]"), 400)
                if (r.body.isEmpty()) return Response.json(mapOf("error" to "POST a GPX file as the body"), 400)
                p.s("record")?.let { t.start(it, Tracking.Mode.INJECTED) }
                return try {
                    Response.json(t.replay.start(r.bodyText, rate, p.d("accuracy") ?: 5.0) + mapOf("recording" to t.recorder.activeId))
                } catch (e: GpxException) {
                    Response.json(mapOf("error" to "bad GPX: ${e.message}"), 400)
                } catch (e: IllegalArgumentException) {
                    Response.json(mapOf("error" to e.message), 400)
                }
            }
        }
        val m = trackPath.matchEntire(r.path) ?: return if (r.path.startsWith("/tracks")) Response.notFound() else null
        val id = m.groupValues[1]
        val suffix = m.groupValues[2]
        if (id == "-") {
            if (r.method == "POST" && suffix == "/waypoint") return waypoint(t, null, p)
            return Response.notFound()
        }
        val summary = t.call { t.store.summary(id) } ?: return Response.json(mapOf("error" to "no track $id"), 404)
        return when (r.method to suffix) {
            "GET" to "" -> Response.json(trackJson(t, summary))
            "DELETE" to "" -> Response.json(mapOf("deleted" to t.delete(id)))
            "GET" to ".gpx" -> {
                val gpx = t.call { TrackExport.gpx(t.store, id) }!!
                Response(200, "application/gpx+xml; charset=utf-8", gpx.toByteArray())
            }
            "POST" to "/export" -> {
                val f = t.export(id)!!
                Response.json(mapOf("path" to f.path, "bytes" to f.length(), "name" to TrackExport.fileName(summary)))
            }
            "POST" to "/show" -> Response.json(mapOf("shown" to t.show(id), "overlayFeatures" to t.overlay.features))
            "POST" to "/waypoint" -> waypoint(t, id, p)
            else -> Response.notFound()
        }
    }

    private fun waypoint(t: Tracking, trackId: String?, p: Params): Response {
        val w = t.call { t.recorder.waypoint(p.s("name"), trackId, p.d("lat"), p.d("lon"), p.d("ele")) }
            ?: return Response.json(mapOf("error" to "no position: pass lat and lon, or inject a fix first"), 409)
        t.refreshUi()
        t.refreshOverlay(force = true)
        return Response.json(mapOf("id" to w.id, "name" to w.name, "lat" to w.lat, "lon" to w.lon, "ele" to w.ele, "time" to w.time, "track" to trackId))
    }

    private fun overview(t: Tracking): Map<String, Any?> = t.call {
        val rec = t.recorder
        val tracks = t.store.list()
        mapOf(
            "recording" to rec.activeId?.let { id ->
                mapOf(
                    "id" to id, "mode" to t.mode.name.lowercase(), "service" to t.serviceState, "gps" to t.gpsState,
                    "acceptedFixes" to rec.accepted, "rejectedAccuracy" to rec.rejectedAccuracy, "rejectedTime" to rec.rejectedTime,
                    "liveStats" to rec.liveStats()?.let(::statsJson),
                )
            },
            "lastFix" to rec.lastFix?.let { mapOf("lat" to it.lat, "lon" to it.lon, "ele" to it.ele, "time" to it.time, "source" to it.source) },
            "shown" to t.shownId,
            "overlay" to mapOf("features" to t.overlay.features, "installed" to t.overlay.installedOnStyle),
            "replay" to t.replay.json(),
            "interruptedAtStartup" to t.interrupted,
            "store" to mapOf("type" to "sqldelight", "file" to t.dbFile.path, "bytes" to t.dbFile.length(), "tracks" to tracks.size),
            "tracks" to tracks.map { s -> summaryJson(s) + mapOf("stats" to t.statsOf(s)?.let(::statsJson)) },
        )
    }

    private fun trackJson(t: Tracking, s: TrackSummary): Map<String, Any?> =
        summaryJson(s) + mapOf("stats" to t.call { t.statsOf(s) }?.let(::statsJson))

    private fun summaryJson(s: TrackSummary): Map<String, Any?> = mapOf(
        "id" to s.id, "name" to s.name, "createdAt" to s.createdAt, "updatedAt" to s.updatedAt,
        "recording" to s.recording, "points" to s.points, "waypoints" to s.waypoints,
    )

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
