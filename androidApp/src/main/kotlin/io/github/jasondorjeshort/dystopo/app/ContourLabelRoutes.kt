package io.github.jasondorjeshort.dystopo.app

import android.graphics.PointF
import android.graphics.RectF
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * `GET /contour-labels` (debug): does every index contour in view carry a label? Lists the elevations
 * of the index lines drawn in the view (`contour-index`), those with at least one placed label
 * (`contour-labels`), and for each unlabelled one its visible length and longest unbroken on-screen
 * run in pixels (MapLibre cannot label a line shorter than the label within the angle limit).
 * `labelledLong`/`indexLong` count only lines with a run of at least `minRunPx` (default 200 px).
 */
class ContourLabelRoutes(private val app: DystopoApp) {
    fun handle(r: Request): Response? {
        if (r.method != "GET" || r.path != "/contour-labels") return null
        val minRun = r.query["minRunPx"]?.toDoubleOrNull() ?: 200.0
        return Response.json(
            app.map.onMain {
                val m = app.map.map ?: return@onMain mapOf("ready" to false)
                val view = app.map.mapView ?: return@onMain mapOf("ready" to false)
                val w = view.width.toFloat()
                val h = view.height.toFloat()
                val box = RectF(0f, 0f, w, h)
                val labelled = m.queryRenderedFeatures(box, "contour-labels").mapNotNull { it.getNumberProperty("ele")?.toInt() }
                val visible = HashMap<Int, Double>()
                val longest = HashMap<Int, Double>()
                for (f in m.queryRenderedFeatures(box, "contour-index")) {
                    val ele = f.getNumberProperty("ele")?.toInt() ?: continue
                    val lines: List<List<Point>> = when (val g = f.geometry()) {
                        is LineString -> listOf(g.coordinates())
                        is MultiLineString -> g.coordinates()
                        else -> emptyList()
                    }
                    for (line in lines) {
                        var run = 0.0
                        var prev: PointF? = null
                        for (p in line) {
                            val s = m.projection.toScreenLocation(LatLng(p.latitude(), p.longitude()))
                            val inside = s.x in 0f..w && s.y in 0f..h
                            val a = prev
                            if (inside && a != null) {
                                val d = hypot((s.x - a.x).toDouble(), (s.y - a.y).toDouble())
                                visible[ele] = (visible[ele] ?: 0.0) + d
                                run += d
                                longest[ele] = maxOf(longest[ele] ?: 0.0, run)
                            } else {
                                run = 0.0
                            }
                            prev = if (inside) s else null
                        }
                    }
                }
                val index = visible.keys.sorted()
                val lab = labelled.toSet()
                val long = index.filter { (longest[it] ?: 0.0) >= minRun }
                mapOf(
                    "ready" to true,
                    "zoom" to m.cameraPosition.zoom,
                    "textScale" to AppSettings.textScale,
                    "index" to index.size,
                    "labelled" to index.count { it in lab },
                    "labels" to labelled.size,
                    "minRunPx" to minRun,
                    "indexLong" to long.size,
                    "labelledLong" to long.count { it in lab },
                    "missing" to index.filter { it !in lab }.map {
                        mapOf("ele" to it, "visiblePx" to (visible[it] ?: 0.0).roundToInt(), "longestRunPx" to (longest[it] ?: 0.0).roundToInt())
                    },
                )
            },
        )
    }
}
