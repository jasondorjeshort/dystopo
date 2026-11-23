package io.github.jasondorjeshort.dystopo.app

import android.graphics.RectF
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.Holdings
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.math.floor

/**
 * T341, debug builds: why a view is sparse, beside `/stats` and `/features` ([Routes]).
 *
 * - `GET /why[?z=&lat=&lon=]` (default: the camera's zoom and centre): for that point and zoom, the vector tile (512 px, the
 *   map's zoom) and the DEM tile (256 px, one zoom deeper) it lies in, and
 *   - `maps`: each offline map on the phone, its vector and elevation zooms, whether its box holds the point and whether it
 *     answers the vector or the elevation tile; `off`, the maps switched off (T342), which answer nothing;
 *   - `overview`: the world overview's zooms and whether it holds the vector tile;
 *   - `online`: whether a source is set, its state, whether a fetch may run now (and why not), the setting `onlineDetail`
 *     with the vector source's stored maximum ([OnlineTiles.overviewJson]), [Holdings.vectorTile]'s answer for the tile
 *     (`vector`: who serves, whether the network fetches behind it), how the network path answered that tile last
 *     (`lastVector`: `online`, `cached`, `wider`, or null when it never did) and the elevation source of the view
 *     ([OnlineTiles.viewSource]) with the online elevation's first zoom;
 *   - `style`: each source's zooms in the style the map has now (the DEM's top capped at the deepest map's whenever a map
 *     has elevation: ADR 0066's build task 2 lifts it); `layers`, the style layers visible at the zoom with their minimum
 *     zooms and the features each rendered in the window (MapLibre's `queryRenderedFeatures`; the camera's view, whatever
 *     `lat` and `lon` say), and `belowMinZoom`, the layers a zoom deeper would add;
 *   - `settings`: the map options (Hillshade, slope, the contour interval, the layer switches);
 *   - `cause`, one word: `none` (a map or the network draws the view in full), `below-maps-floor` (inside a map's box above
 *     its first zoom, and nothing else draws the tile), `overview` (the world overview at its own zooms), `setting-off`
 *     (the overview where More detail from online is off), `online-fetching` (the overview while the network's tile is
 *     fetched behind it), `overview-offline` (the overview, no fetch allowed), `above-overview-top` (no map, the network
 *     not answering: the view cut from the overview's top zoom), `wider-zoom` (cut from a wider zoom the phone holds),
 *     `failed-fetch` (the network path, its fetch failed and nothing wider), `empty-tile` (the network path, nothing came),
 *     `no-source` (no map, no overview, no source), `relief-off` (drawn, but Hillshade is off).
 */
class WhyRoutes(private val app: DystopoApp) {
    fun handle(r: Request): Response {
        val cam = runCatching { app.map.onMain(5_000) { app.map.map?.cameraPosition } }.getOrNull()
        val z = r.query["z"]?.toDoubleOrNull() ?: cam?.zoom ?: return Response.json(mapOf("error" to "no camera; give z, lat and lon"), 400)
        val lat = r.query["lat"]?.toDoubleOrNull() ?: cam?.target?.latitude ?: return Response.json(mapOf("error" to "no camera; give lat"), 400)
        val lon = r.query["lon"]?.toDoubleOrNull() ?: cam?.target?.longitude ?: return Response.json(mapOf("error" to "no camera; give lon"), 400)
        return Response.json(why(z, lat, lon))
    }

    private fun tile(z: Int, lat: Double, lon: Double): Triple<Int, Int, Int> {
        val n = 1 shl z
        return Triple(z, WebMercator.lonToX(lon, z).toInt().coerceIn(0, n - 1), WebMercator.latToY(lat, z).toInt().coerceIn(0, n - 1))
    }

    fun why(zoom: Double, lat: Double, lon: Double): Map<String, Any?> {
        val t = app.tiles
        val packs = t.packs
        val o = t.online
        val (vz, vx, vy) = tile(floor(zoom).toInt().coerceAtLeast(0), lat, lon)
        val (dz, dx, dy) = tile((floor(zoom).toInt() + 1).coerceIn(0, OnlineTiles.DEM_MAX_ZOOM), lat, lon)
        val vectorPack = packs.osmFor(vz, vx, vy)
        val demPack = packs.demFor(dz, dx, dy)
        val maps = packs.regions.map { reg ->
            val h = (reg.osm ?: reg.dem)!!.header
            val holds = lon >= h.minLon && lon <= h.maxLon && lat >= h.minLat && lat <= h.maxLat
            linkedMapOf(
                "name" to reg.name, "primary" to (packs.primary === reg),
                "vectorZooms" to reg.osm?.header?.let { listOf(it.minZoom, it.maxZoom) }, "demZooms" to reg.dem?.header?.let { listOf(it.minZoom, it.maxZoom) },
                "holdsPoint" to holds, "answersVector" to (reg.osm != null && vectorPack === reg.osm), "answersDem" to (reg.dem != null && demPack === reg.dem),
            )
        }
        val floorMap = maps.filter { it["holdsPoint"] == true && (it["vectorZooms"] as? List<*>)?.let { zs -> vz < (zs[0] as Int) } == true }
        val ov = packs.world?.header
        val overviewHolds = packs.worldAnswers(vz, vx, vy)
        val answer = o?.vectorAnswer(vz, vx, vy)
        val last = o?.vectorSource("$vz/$vx/$vy.pbf")
        val member = o?.onlineMember()
        val demSource = o?.viewSource(lat, lon, zoom)?.first ?: if (demPack != null) OnlineTiles.PACK else OnlineTiles.NONE
        val options = AppSettings.mapOptions
        val drawCause = when {
            vectorPack != null && vectorPack !== packs.world -> "none"
            answer == null -> when {
                overviewHolds -> "overview"
                floorMap.isNotEmpty() -> "below-maps-floor"
                else -> "no-source"
            }
            answer.serves == Holdings.Source.CACHE -> "none"
            answer.serves == Holdings.Source.OVERVIEW -> when (answer.cause) {
                "online-fetching" -> "online-fetching"
                "overview-online-off" -> "setting-off"
                "overview-not-allowed" -> "overview-offline"
                else -> "overview"
            }
            answer.serves == Holdings.Source.NETWORK -> when (last) {
                OnlineService.ONLINE, OnlineService.CACHED -> "none"
                OnlineTiles.WIDER -> o?.vectorWiderFrom("$vz/$vx/$vy.pbf").let { from ->
                    if (ov != null && from != null && from <= ov.maxZoom && packs.worldAnswers(from, vx shr (vz - from), vy shr (vz - from))) "above-overview-top" else "wider-zoom"
                }
                else -> if (floorMap.isNotEmpty()) "below-maps-floor" else if (member?.allowed == false) "failed-fetch" else "empty-tile"
            }
            else -> "no-source"
        }
        val cause = if (drawCause == "none" && options.hillshade == io.github.jasondorjeshort.dystopo.tiles.HillshadeMode.OFF) "relief-off" else drawCause
        return linkedMapOf(
            "zoom" to zoom, "lat" to lat, "lon" to lon, "vectorTile" to "$vz/$vx/$vy", "demTile" to "$dz/$dx/$dy",
            "cause" to cause,
            "maps" to maps, "off" to packs.off,
            "overview" to linkedMapOf("zooms" to ov?.let { listOf(it.minZoom, it.maxZoom) }, "holdsVectorTile" to overviewHolds),
            "online" to if (o == null) linkedMapOf("set" to false) else linkedMapOf(
                "set" to true, "server" to o.fetcher.server, "state" to o.fetcher.state,
                "vector" to answer?.json(), "lastVector" to last, "lastVectorFromZoom" to o.vectorWiderFrom("$vz/$vx/$vy.pbf"), "elevation" to demSource, "elevationFromZoom" to o.demMinZoom,
            ) + o.overviewJson(),
            "style" to styleZooms(),
            "layers" to layers(zoom),
            "settings" to options.json(AppSettings.units),
        )
    }

    /** The style's sources and their zooms, as [TileService.style] makes them now. */
    private fun styleZooms(): Map<String, Any?> = runCatching {
        val sources = MiniJson.parseObject(app.tiles.style())["sources"] as? Map<*, *> ?: return emptyMap()
        sources.entries.associate { (k, v) -> k.toString() to (v as? Map<*, *>)?.let { s -> listOf(s["minzoom"], s["maxzoom"]) } }
    }.getOrElse { mapOf("error" to it.message) }

    /** The style's layers visible at [zoom] (minimum zoom, features rendered in the window) and those that start deeper. */
    private fun layers(zoom: Double): Map<String, Any?> = runCatching {
        // The zooms from the style's JSON (MapLibre Native's layer getters read NaN for a zoom the style leaves out).
        val zooms = (MiniJson.parseObject(app.tiles.style())["layers"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            .associate { l -> l["id"].toString() to Pair((l["minzoom"] as? Number)?.toDouble() ?: 0.0, (l["maxzoom"] as? Number)?.toDouble() ?: 24.0) }
        app.map.onMain(10_000) {
            val view = app.map.mapView
            val map = app.map.map
            val style = map?.style
            if (view == null || map == null || style == null || !style.isFullyLoaded) return@onMain mapOf("ready" to false)
            val box = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
            val visible = ArrayList<Map<String, Any?>>()
            val deeper = ArrayList<Map<String, Any?>>()
            for (layer in style.layers) {
                if (layer.visibility.value == "none") continue
                val (min, max) = zooms[layer.id] ?: continue // the app's own overlays (location, tracks) are not the style's
                if (zoom < min) {
                    deeper += linkedMapOf("id" to layer.id, "minZoom" to min)
                    continue
                }
                if (zoom >= max) continue
                visible += linkedMapOf("id" to layer.id, "minZoom" to min, "rendered" to map.queryRenderedFeatures(box, layer.id).size)
            }
            linkedMapOf("ready" to true, "visible" to visible, "rendering" to visible.count { (it["rendered"] as Int) > 0 }, "belowMinZoom" to deeper.sortedBy { it["minZoom"] as Double })
        }
    }.getOrElse { mapOf("error" to it.message) }
}
