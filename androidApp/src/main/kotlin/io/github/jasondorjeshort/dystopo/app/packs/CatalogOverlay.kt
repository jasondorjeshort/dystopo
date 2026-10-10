package io.github.jasondorjeshort.dystopo.app.packs

import android.os.Handler
import android.os.Looper
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.packs.CatalogStatus
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Outlines of the server's catalog packs on the map (T53), drawn while the "From server" section of the
 * pack screen is open: a faint fill and a 2 px outline per pack box, coloured by status (blue available,
 * green installed, orange update available, purple downloading, grey checking), and the pack name with its
 * status at the box centre. Our own GeoJSON source and layers on top of the style, re-added after a style
 * reload; [show] can be called from any thread.
 */
object CatalogOverlay {
    const val SOURCE = "dystopo-catalog"
    const val FILL = "dystopo-catalog-fill"
    const val LINE = "dystopo-catalog-line"
    const val LABEL = "dystopo-catalog-label"

    private val main = Handler(Looper.getMainLooper())
    private var view: MapView? = null
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { install() }

    @Volatile private var data: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    @Volatile var visible = false
        private set
    @Volatile var outlines = 0
        private set
    @Volatile var installedOnStyle = false
        private set
    @Volatile private var names: List<String> = emptyList()

    fun json(): Map<String, Any?> = linkedMapOf("visible" to visible, "outlines" to outlines, "installedOnStyle" to installedOnStyle, "packs" to names)

    /** Main thread. */
    fun bind(mapView: MapView) {
        if (view === mapView) return
        view?.removeOnDidFinishLoadingStyleListener(styleListener)
        view = mapView
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        install()
    }

    /** Draws [rows] (or nothing when null). */
    fun show(rows: List<PackCatalogClient.Row>?) {
        val fs = ArrayList<Feature>()
        for (r in rows.orEmpty()) {
            val b = r.pack.bounds ?: continue
            val ring = listOf(
                Point.fromLngLat(b.minLon, b.minLat), Point.fromLngLat(b.maxLon, b.minLat), Point.fromLngLat(b.maxLon, b.maxLat),
                Point.fromLngLat(b.minLon, b.maxLat), Point.fromLngLat(b.minLon, b.minLat),
            )
            fs += Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
                addStringProperty("name", r.pack.name)
                addStringProperty("status", r.status.label)
            }
            fs += Feature.fromGeometry(Point.fromLngLat(b.centerLon, b.centerLat)).apply {
                addStringProperty("name", r.pack.name)
                addStringProperty("status", r.status.label)
                addStringProperty("label", r.pack.name + "\n" + statusText(r.status))
            }
        }
        visible = rows != null
        outlines = fs.size / 2
        names = rows.orEmpty().filter { it.pack.bounds != null }.map { it.pack.name }
        data = FeatureCollection.fromFeatures(fs)
        main.post { apply() }
    }

    fun statusText(s: CatalogStatus) = when (s) {
        CatalogStatus.AVAILABLE -> "available"
        CatalogStatus.CHECKING -> "checking"
        CatalogStatus.INSTALLED -> "installed"
        CatalogStatus.UPDATE -> "update available"
        CatalogStatus.DOWNLOADING -> "downloading"
    }

    private fun install() {
        val v = view ?: return
        v.getMapAsync { map -> map.getStyle { style -> addTo(style) } }
    }

    private fun color(): Expression = Expression.match(
        Expression.get("status"), Expression.color(0xFF2C7FB8.toInt()),
        Expression.stop("installed", Expression.color(0xFF1A9850.toInt())),
        Expression.stop("update", Expression.color(0xFFE66101.toInt())),
        Expression.stop("downloading", Expression.color(0xFF7B3294.toInt())),
        Expression.stop("checking", Expression.color(0xFF808080.toInt())),
    )

    private fun addTo(style: Style) {
        if (style.getSource(SOURCE) == null) {
            style.addSource(GeoJsonSource(SOURCE, data))
            val isBox = Expression.eq(Expression.geometryType(), Expression.literal("Polygon"))
            val isPoint = Expression.eq(Expression.geometryType(), Expression.literal("Point"))
            style.addLayer(FillLayer(FILL, SOURCE).withFilter(isBox).withProperties(PropertyFactory.fillColor(color()), PropertyFactory.fillOpacity(0.12f)))
            style.addLayer(
                LineLayer(LINE, SOURCE).withFilter(isBox).withProperties(
                    PropertyFactory.lineColor(color()), PropertyFactory.lineWidth(2.5f), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                SymbolLayer(LABEL, SOURCE).withFilter(isPoint).withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf(MapLibreStyle.FONT_MEDIUM)),
                    PropertyFactory.textSize((13.0 * AppSettings.textScale).toFloat()),
                    PropertyFactory.textColor(color()),
                    PropertyFactory.textHaloColor("#ffffff"),
                    PropertyFactory.textHaloWidth(2f),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textMaxWidth(14f),
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
            if (style.getSource(SOURCE) == null) return@getMapAsync addTo(style)
            (style.getSource(SOURCE) as? GeoJsonSource)?.setGeoJson(data)
        }
    }

    /** The union of the catalog boxes. */
    fun union(rows: List<PackCatalogClient.Row>): PackBounds? = rows.mapNotNull { it.pack.bounds }.reduceOrNull { a, b -> a.union(b) }

    /**
     * What the camera should frame when the catalog opens: every pack when they lie close together (the
     * union spans at most [MAX_UNION_DEG] degrees), else the pack nearest the current camera ([camera] is
     * `MapController.camera()`), so packs spread over the world do not leave the map at z3 showing none.
     */
    fun framing(rows: List<PackCatalogClient.Row>, camera: Map<String, Any?>?): PackBounds? {
        val all = union(rows) ?: return null
        if (all.maxLon - all.minLon <= MAX_UNION_DEG && all.maxLat - all.minLat <= MAX_UNION_DEG) return all
        val lat = (camera?.get("lat") as? Number)?.toDouble() ?: return all
        val lon = (camera["lon"] as? Number)?.toDouble() ?: return all
        return rows.mapNotNull { it.pack.bounds }.minByOrNull { b ->
            val dLat = b.centerLat - lat
            val dLon = (b.centerLon - lon) * kotlin.math.cos(lat * kotlin.math.PI / 180)
            dLat * dLat + dLon * dLon
        }
    }

    const val MAX_UNION_DEG = 8.0
}
