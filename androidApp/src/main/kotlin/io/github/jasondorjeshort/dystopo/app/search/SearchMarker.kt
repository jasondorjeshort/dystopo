package io.github.jasondorjeshort.dystopo.app.search

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The temporary marker for a chosen search result: our own GeoJSON source and two circle layers (a
 * white halo and a red dot) added on top of the style when first needed. A style reload (pack switch)
 * drops it, which is fine for a temporary marker. Main thread only.
 */
object SearchMarker {
    const val SOURCE = "dystopo-search-result"
    const val HALO = "dystopo-search-result-halo"
    const val DOT = "dystopo-search-result-dot"

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
        return src
    }

    fun show(style: Style, lat: Double, lon: Double) = source(style).setGeoJson(Point.fromLngLat(lon, lat))

    fun clear(style: Style) {
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    /** Whether the marker's layers are in [style] (for `/search/status`). */
    fun installed(style: Style?): Boolean = style?.getLayer(DOT) != null
}
