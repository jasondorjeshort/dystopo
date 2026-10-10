package io.github.jasondorjeshort.dystopo.app.search

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The temporary marker for a chosen search result: our own GeoJSON source, two circle layers (a white
 * halo and a red dot) and the place's name beside them (T41), added on top of the style when first
 * needed. The label always shows (it may overlap) and the map's own labels that would collide with it make way. A style
 * reload (pack switch) drops it, which is fine for a temporary marker. Main thread only.
 */
object SearchMarker {
    const val SOURCE = "dystopo-search-result"
    const val HALO = "dystopo-search-result-halo"
    const val DOT = "dystopo-search-result-dot"
    const val LABEL = "dystopo-search-result-label"

    private fun source(style: Style): GeoJsonSource {
        style.getSourceAs<GeoJsonSource>(SOURCE)?.let { return it }
        val src = GeoJsonSource(SOURCE)
        style.addSource(src)
        style.addLayer(
            CircleLayer(HALO, SOURCE).withProperties(
                PropertyFactory.circleRadius(13f),
                PropertyFactory.circleColor("#ffffff"),
                PropertyFactory.circleOpacity(0.85f),
                PropertyFactory.circleStrokeColor("rgba(0,0,0,0.4)"),
                PropertyFactory.circleStrokeWidth(1.5f),
            ),
        )
        style.addLayer(
            CircleLayer(DOT, SOURCE).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor("#d0352b"),
            ),
        )
        style.addLayer(
            SymbolLayer(LABEL, SOURCE).withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf(MapLibreStyle.FONT_MEDIUM)),
                PropertyFactory.textSize((15.0 * AppSettings.textScale).toFloat()),
                PropertyFactory.textColor("#8a1f17"),
                PropertyFactory.textHaloColor("#ffffff"),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
                PropertyFactory.textOffset(arrayOf(1.1f, 0f)),
                PropertyFactory.textMaxWidth(12f),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(false), // map labels that would collide make way
            ),
        )
        return src
    }

    fun show(style: Style, lat: Double, lon: Double, name: String? = null) {
        val f = Feature.fromGeometry(Point.fromLngLat(lon, lat))
        if (name != null) f.addStringProperty("name", name)
        source(style).setGeoJson(f)
    }

    fun clear(style: Style) {
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    /** Whether the marker's layers are in [style] (for `/search/status`). */
    fun installed(style: Style?): Boolean = style?.getLayer(DOT) != null

    /** Whether the name label layer is there too (T41). */
    fun labelInstalled(style: Style?): Boolean = style?.getLayer(LABEL) != null
}
