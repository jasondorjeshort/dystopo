package io.github.jasondorjeshort.dystopo.app.viewshed

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.dem.ViewshedLevel
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.sin

/**
 * T182: the viewshed on the map. Each level of the result ([ViewshedLevel], finest first) is one translucent image
 * ([Viewshed.overlayArgb]: visible ground green at 35 percent, hidden ground clear, no data hatched grey) in an
 * [ImageSource] placed on its Mercator window, drawn with nearest-cell resampling under the style's first label layer,
 * so names stay readable; the levels tile the disc exactly, so they never double the tint. A GeoJSON source adds the
 * range's dashed circle, the observer's dot and the peak a sheet row showed ([mark]: a dot with its name).
 *
 * Survives style reloads (night, map options): [bind] re-adds everything on "style loaded". Main thread.
 */
class ViewshedOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: MapView? = null
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { install() }

    private class Image(val id: Int, val quad: LatLngQuad, val bitmap: Bitmap, val visible: Int, val noData: Int, val width: Int, val height: Int)

    private var images: List<Image> = emptyList()
    private var shapes: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    private var peak: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    private var installedLevels = 0

    @Volatile var shows = 0
        private set
    @Volatile var renderMs = 0.0
        private set
    @Volatile var installedOnStyle = false
        private set

    fun bind(mapView: MapView) {
        if (view === mapView) return
        view?.removeOnDidFinishLoadingStyleListener(styleListener)
        view = mapView
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        install()
    }

    /** Draws [d] (main thread): one image per level, the circle and the observer. */
    fun show(d: ViewshedSession.Done) {
        val t0 = System.nanoTime()
        val r = d.result
        images = r.levels.mapIndexed { i, l -> image(i, l) }
        val ring = (0..180).map { k ->
            val a = k * 2 * Math.PI / 180
            val (lat, lon) = offset(r.lat, r.lon, r.rangeM * sin(a), r.rangeM * cos(a))
            Point.fromLngLat(lon, lat)
        }
        shapes = FeatureCollection.fromFeatures(
            listOf(
                Feature.fromGeometry(LineString.fromLngLats(ring)).apply { addStringProperty("kind", "range") },
                Feature.fromGeometry(Point.fromLngLat(r.lon, r.lat)).apply { addStringProperty("kind", "observer") },
            ),
        )
        peak = FeatureCollection.fromFeatures(emptyList())
        renderMs = (System.nanoTime() - t0) / 1e6
        shows++
        install()
    }

    /** Marks [p] on the map (a sheet row tapped). */
    fun mark(p: PeakView) {
        peak = FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(p.peak.lon, p.peak.lat)).apply { addStringProperty("name", p.peak.name) }))
        install()
    }

    fun clear() {
        images = emptyList()
        shapes = FeatureCollection.fromFeatures(emptyList())
        peak = FeatureCollection.fromFeatures(emptyList())
        install()
    }

    private fun image(index: Int, l: ViewshedLevel): Image {
        val w = l.window
        val argb = Viewshed.overlayArgb(l)
        val bmp = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(argb, 0, w.width, 0, 0, w.width, w.height)
        fun lon(px: Int) = WebMercator.xToLon(px.toDouble() / w.tileSize, w.zoom)
        fun lat(py: Int) = WebMercator.yToLat(py.toDouble() / w.tileSize, w.zoom)
        val west = lon(w.x0)
        val east = lon(w.x0 + w.width)
        val north = lat(w.y0)
        val south = lat(w.y0 + w.height)
        val quad = LatLngQuad(LatLng(north, west), LatLng(north, east), LatLng(south, east), LatLng(south, west))
        return Image(index, quad, bmp, l.count(Viewshed.VISIBLE), l.count(Viewshed.NO_DATA), w.width, w.height)
    }

    private fun install() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { install() }; return }
        val v = view ?: return
        v.getMapAsync { map -> map.getStyle { style -> addTo(style) } }
    }

    private fun addTo(style: Style) {
        if (!style.isFullyLoaded) return
        for (i in 0 until maxOf(installedLevels, MAX_LEVELS)) {
            style.getLayer(layerId(i))?.let { style.removeLayer(it) }
            style.getSource(sourceId(i))?.let { style.removeSource(it) }
        }
        val below = style.layers.firstOrNull { it is SymbolLayer }?.id
        for (img in images.asReversed()) { // the finest last, so it sits on top where two images meet
            style.addSource(ImageSource(sourceId(img.id), img.quad, img.bitmap))
            val layer = RasterLayer(layerId(img.id), sourceId(img.id)).withProperties(
                PropertyFactory.rasterOpacity(1f), PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_NEAREST),
                PropertyFactory.rasterFadeDuration(0f),
            )
            if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
        }
        installedLevels = images.size
        val shapeSource = style.getSourceAs<GeoJsonSource>(SHAPES)
        if (shapeSource == null) {
            style.addSource(GeoJsonSource(SHAPES, shapes))
            style.addLayer(
                LineLayer(RING, SHAPES).withFilter(Expression.eq(Expression.get("kind"), Expression.literal("range"))).withProperties(
                    PropertyFactory.lineColor("#2b2420"), PropertyFactory.lineOpacity(0.7f), PropertyFactory.lineWidth(1.5f),
                    PropertyFactory.lineDasharray(arrayOf(4f, 3f)),
                ),
            )
            style.addLayer(
                CircleLayer(OBSERVER, SHAPES).withFilter(Expression.eq(Expression.get("kind"), Expression.literal("observer"))).withProperties(
                    PropertyFactory.circleRadius(6f), PropertyFactory.circleColor("#12a150"),
                    PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2f),
                ),
            )
        } else {
            shapeSource.setGeoJson(shapes)
        }
        val peakSource = style.getSourceAs<GeoJsonSource>(PEAK)
        if (peakSource == null) {
            style.addSource(GeoJsonSource(PEAK, peak))
            style.addLayer(
                CircleLayer(PEAK_DOT, PEAK).withProperties(
                    PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#ffd400"),
                    PropertyFactory.circleStrokeColor("#2b2420"), PropertyFactory.circleStrokeWidth(2f),
                ),
            )
            style.addLayer(
                SymbolLayer(PEAK_NAME, PEAK).withProperties(
                    PropertyFactory.textField(Expression.get("name")), PropertyFactory.textFont(arrayOf(MapLibreStyle.FONT_MEDIUM)),
                    PropertyFactory.textSize(14f), PropertyFactory.textColor("#2b2420"), PropertyFactory.textHaloColor("#ffffff"),
                    PropertyFactory.textHaloWidth(2f), PropertyFactory.textOffset(arrayOf(0f, -1.4f)), PropertyFactory.textAllowOverlap(true),
                ),
            )
        } else {
            peakSource.setGeoJson(peak)
        }
        installedOnStyle = true
    }

    /** `GET /viewshed` › `overlay`. */
    fun json(): Map<String, Any?> = linkedMapOf(
        "installedOnStyle" to installedOnStyle, "shows" to shows, "renderMs" to Math.round(renderMs * 10) / 10.0,
        "images" to images.map { linkedMapOf("level" to it.id, "width" to it.width, "height" to it.height, "visibleCells" to it.visible, "noDataCells" to it.noData) },
        "marked" to peak.features()?.firstOrNull()?.getStringProperty("name"),
    )

    companion object {
        private const val MAX_LEVELS = 3
        private fun sourceId(i: Int) = "dystopo-viewshed-$i"
        private fun layerId(i: Int) = "dystopo-viewshed-layer-$i"
        private const val SHAPES = "dystopo-viewshed-shapes"
        private const val RING = "dystopo-viewshed-ring"
        private const val OBSERVER = "dystopo-viewshed-observer"
        private const val PEAK = "dystopo-viewshed-peak"
        private const val PEAK_DOT = "dystopo-viewshed-peak-dot"
        private const val PEAK_NAME = "dystopo-viewshed-peak-name"

        /** ([lat], [lon]) moved [east], [north] metres (the local scale; the circle's points). */
        fun offset(lat: Double, lon: Double, east: Double, north: Double): Pair<Double, Double> {
            val r = 6_371_000.0
            val dLat = north / r * 180 / Math.PI
            val dLon = east / (r * cos(lat * Math.PI / 180)) * 180 / Math.PI
            return (lat + dLat) to (lon + dLon)
        }
    }
}
