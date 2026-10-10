package io.github.jasondorjeshort.dystopo.app.nav

import android.os.Handler
import android.os.Looper
import io.github.jasondorjeshort.dystopo.app.LocationPuck
import io.github.jasondorjeshort.dystopo.nav.NavPoint
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * What navigation draws on the map (T61): the line being followed (purple, white casing, so it stays
 * apart from the orange planned route and the magenta recorded track), and while off route a dashed
 * red line from the walker to the nearest point of the line with a ringed dot there: the "back on
 * route" hint. Layers go under the location puck; they survive style reloads. [show] from any thread.
 */
class NavMapLayer {
    private val main = Handler(Looper.getMainLooper())
    private var view: MapView? = null
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { install() }

    @Volatile private var path: List<NavPoint> = emptyList()
    @Volatile private var rejoin: Pair<NavPoint, NavPoint>? = null
    @Volatile var installedOnStyle = false
        private set
    @Volatile var updates = 0L
        private set

    fun bind(mapView: MapView) {
        if (view === mapView) return
        unbind()
        view = mapView
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        install()
    }

    fun unbind() {
        view?.removeOnDidFinishLoadingStyleListener(styleListener)
        view = null
        installedOnStyle = false
    }

    /** The followed line (empty to hide) and the rejoin segment from the walker to the line (null when on route). */
    fun show(line: List<NavPoint>, rejoinFrom: NavPoint?, rejoinTo: NavPoint?) {
        path = line
        rejoin = if (rejoinFrom != null && rejoinTo != null) rejoinFrom to rejoinTo else null
        main.post { apply() }
    }

    fun setRejoin(rejoinFrom: NavPoint?, rejoinTo: NavPoint?) = show(path, rejoinFrom, rejoinTo)

    private fun collection(): FeatureCollection {
        val fs = ArrayList<Feature>()
        if (path.size >= 2) {
            fs += Feature.fromGeometry(LineString.fromLngLats(path.map { Point.fromLngLat(it.lon, it.lat) })).apply { addStringProperty("kind", "path") }
        }
        rejoin?.let { (a, b) ->
            fs += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(a.lon, a.lat), Point.fromLngLat(b.lon, b.lat)))).apply { addStringProperty("kind", "rejoin") }
            fs += Feature.fromGeometry(Point.fromLngLat(b.lon, b.lat)).apply { addStringProperty("kind", "target") }
        }
        return FeatureCollection.fromFeatures(fs)
    }

    private fun install() {
        val v = view ?: return
        v.getMapAsync { map -> map.getStyle { style -> addTo(style) } }
    }

    private fun add(style: Style, layer: Layer) {
        if (style.getLayer(LocationPuck.ACCURACY) != null) style.addLayerBelow(layer, LocationPuck.ACCURACY) else style.addLayer(layer)
    }

    private fun addTo(style: Style) {
        if (style.getSource(SOURCE) == null) {
            style.addSource(GeoJsonSource(SOURCE, collection()))
            fun kind(k: String) = Expression.eq(Expression.get("kind"), Expression.literal(k))
            add(
                style,
                LineLayer(CASING, SOURCE).withFilter(kind("path")).withProperties(
                    PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(9f), PropertyFactory.lineOpacity(0.9f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            add(
                style,
                LineLayer(LINE, SOURCE).withFilter(kind("path")).withProperties(
                    PropertyFactory.lineColor(COLOR), PropertyFactory.lineWidth(5f), PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            add(
                style,
                LineLayer(REJOIN, SOURCE).withFilter(kind("rejoin")).withProperties(
                    PropertyFactory.lineColor(ALERT), PropertyFactory.lineWidth(3.5f), PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                ),
            )
            add(
                style,
                CircleLayer(TARGET, SOURCE).withFilter(kind("target")).withProperties(
                    PropertyFactory.circleRadius(7f), PropertyFactory.circleColor(ALERT),
                    PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2.5f),
                ),
            )
        }
        installedOnStyle = true
        apply()
    }

    private fun apply() {
        val v = view ?: return
        v.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            if (!style.isFullyLoaded) return@getMapAsync
            (style.getSource(SOURCE) as? GeoJsonSource)?.let {
                it.setGeoJson(collection())
                updates++
            }
        }
    }

    companion object {
        const val SOURCE = "dystopo-nav"
        const val CASING = "dystopo-nav-casing"
        const val LINE = "dystopo-nav-line"
        const val REJOIN = "dystopo-nav-rejoin"
        const val TARGET = "dystopo-nav-target"
        val LAYERS = listOf(CASING, LINE, REJOIN, TARGET)
        const val COLOR = "#7b3294"
        const val ALERT = "#d7191c"
    }
}
