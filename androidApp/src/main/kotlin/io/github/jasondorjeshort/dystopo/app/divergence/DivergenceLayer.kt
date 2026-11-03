package io.github.jasondorjeshort.dystopo.app.divergence

import android.os.Handler
import android.os.Looper
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * T213: the stretches where the user's tracks leave the mapped trails ([DivergenceStore.shown]) on the map: each stretch
 * dashed in purple over a white casing, drawn over the mapped lines beside it, and a purple dot with a white ring at its
 * midpoint, which a long-press opens ([DivergenceCardLine]). Its own GeoJSON source and three layers, added on every
 * style load as [io.github.jasondorjeshort.dystopo.app.routing.RouteOverlay] adds its water markers; installed from
 * [io.github.jasondorjeshort.dystopo.app.MapController.attach]. Empty with the "Show trail disagreements" setting off.
 * [refresh] can be called from any thread.
 */
object DivergenceLayer {
    const val SOURCE = "dystopo-divergence"
    const val CASING = "dystopo-divergence-casing"
    const val LINE = "dystopo-divergence-line"
    const val DOTS = "dystopo-divergence-dots"
    const val COLOR = "#7b3294"

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var view: MapView? = null
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { install() }

    @Volatile private var data: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    @Volatile var stretches = 0
        private set
    @Volatile var installedOnStyle = false
        private set
    @Volatile var updates = 0L
        private set

    /** Draws on [mapView] from now on (main thread); loads the kept records the first time. */
    fun bind(mapView: MapView) {
        DivergenceStore.init(DystopoApp.instance)
        if (view === mapView) return
        view?.removeOnDidFinishLoadingStyleListener(styleListener)
        view = mapView
        installedOnStyle = false
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        refresh()
        install()
    }

    /** Rebuilds the features from [DivergenceStore.shown] and pushes them to the map. */
    fun refresh() {
        val shown = DivergenceStore.shown()
        val fs = ArrayList<Feature>()
        for (d in shown) {
            if (d.line.size < 2) continue
            fs += Feature.fromGeometry(LineString.fromLngLats(d.line.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addStringProperty("kind", "line"); addStringProperty("id", d.id)
            }
            val m = d.mid
            fs += Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
                addStringProperty("kind", "dot"); addStringProperty("id", d.id); addNumberProperty("tracks", d.tracks)
            }
        }
        data = FeatureCollection.fromFeatures(fs)
        stretches = shown.size
        main.post { apply() }
    }

    private fun install() {
        val v = view ?: return
        v.getMapAsync { map -> map.getStyle { style -> addTo(style) } }
    }

    private fun addTo(style: Style) {
        if (style.getSource(SOURCE) == null) {
            style.addSource(GeoJsonSource(SOURCE, data))
            val isLine = Expression.eq(Expression.get("kind"), Expression.literal("line"))
            val isDot = Expression.eq(Expression.get("kind"), Expression.literal("dot"))
            style.addLayer(
                LineLayer(CASING, SOURCE).withFilter(isLine).withProperties(
                    PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(6f), PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(LINE, SOURCE).withFilter(isLine).withProperties(
                    PropertyFactory.lineColor(COLOR), PropertyFactory.lineWidth(3.5f),
                    PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                CircleLayer(DOTS, SOURCE).withFilter(isDot).withProperties(
                    PropertyFactory.circleRadius(7f), PropertyFactory.circleColor(COLOR),
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
            (style.getSource(SOURCE) as? GeoJsonSource)?.setGeoJson(data)
            updates++
        }
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "installedOnStyle" to installedOnStyle, "stretches" to stretches, "updates" to updates,
        "source" to SOURCE, "layers" to listOf(CASING, LINE, DOTS),
    )
}
