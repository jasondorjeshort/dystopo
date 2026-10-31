package io.github.jasondorjeshort.dystopo.app.routing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.jasondorjeshort.dystopo.routing.WaterClass
import io.github.jasondorjeshort.dystopo.routing.WaterItem
import io.github.jasondorjeshort.dystopo.routing.WaterKind
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * T187: water along a route as map markers, one symbol per kind (the survey: springs, taps and streams are separate
 * icons): a drop for a stream crossing, full and dark for "likely flowing", half full for "may run dry", empty for a
 * gully; a ringed dot for a spring, a tap for a tap, waves for a lake. Drawn into the style as images (no glyphs needed),
 * in its own GeoJSON source and symbol layer above the owner's layers ([prefix] keeps the route's and the trip's apart).
 * [RouteOverlay] and [io.github.jasondorjeshort.dystopo.app.trips.TripOverlay] each hold one.
 */
class WaterMarkers(private val prefix: String) {
    val source = "$prefix-water"
    val layer = "$prefix-water-icons"

    @Volatile var data: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
        private set
    @Volatile var count = 0
        private set

    /** The markers of [items] (null or empty: none). */
    fun set(items: List<WaterItem>?) {
        val fs = items.orEmpty().mapIndexed { i, it ->
            Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply {
                addStringProperty("icon", iconOf(it))
                addNumberProperty("n", i + 1)
            }
        }
        data = FeatureCollection.fromFeatures(fs)
        count = fs.size
    }

    /** Adds the images, the source and the layer to [style] (once per style); call from the owner's install. */
    fun addTo(style: Style, density: Float) {
        for (id in ICONS) if (style.getImage(id) == null) style.addImage(id, draw(id, density))
        if (style.getSource(source) == null) {
            style.addSource(GeoJsonSource(source, data))
            style.addLayer(
                SymbolLayer(layer, source).withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )
        }
    }

    /** Pushes [data] to the style's source (main thread, a loaded style). */
    fun apply(style: Style) {
        (style.getSource(source) as? GeoJsonSource)?.setGeoJson(data)
    }

    companion object {
        const val STREAM_FLOWING = "dystopo-water-stream-flowing"
        const val STREAM_MAY_DRY = "dystopo-water-stream-maydry"
        const val STREAM_DRY = "dystopo-water-stream-dry"
        const val SPRING = "dystopo-water-spring"
        const val TAP = "dystopo-water-tap"
        const val LAKE = "dystopo-water-lake"
        val ICONS = listOf(STREAM_FLOWING, STREAM_MAY_DRY, STREAM_DRY, SPRING, TAP, LAKE)

        fun iconOf(i: WaterItem): String = when (i.kind) {
            WaterKind.STREAM -> when (i.waterClass) {
                WaterClass.LIKELY_FLOWING -> STREAM_FLOWING
                WaterClass.MAY_RUN_DRY -> STREAM_MAY_DRY
                else -> STREAM_DRY
            }
            WaterKind.SPRING -> SPRING
            WaterKind.TAP -> TAP
            WaterKind.LAKE -> LAKE
        }

        private const val BLUE = 0xFF1F78B4.toInt()
        private const val PALE = 0xFFA6CEE3.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        /** One icon, 22 dp, with a white edge so it reads on the hillshade. */
        private fun draw(id: String, density: Float): Bitmap {
            val s = (22 * density).toInt().coerceAtLeast(16)
            val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val w = s.toFloat()
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.09f; color = WHITE; strokeJoin = Paint.Join.ROUND }
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.08f; color = BLUE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            when (id) {
                STREAM_FLOWING, STREAM_MAY_DRY, STREAM_DRY -> {
                    val drop = Path().apply {
                        moveTo(w * 0.5f, w * 0.06f)
                        cubicTo(w * 0.62f, w * 0.28f, w * 0.84f, w * 0.46f, w * 0.84f, w * 0.64f)
                        cubicTo(w * 0.84f, w * 0.83f, w * 0.68f, w * 0.95f, w * 0.5f, w * 0.95f)
                        cubicTo(w * 0.32f, w * 0.95f, w * 0.16f, w * 0.83f, w * 0.16f, w * 0.64f)
                        cubicTo(w * 0.16f, w * 0.46f, w * 0.38f, w * 0.28f, w * 0.5f, w * 0.06f)
                        close()
                    }
                    c.drawPath(drop, edge)
                    fill.color = if (id == STREAM_FLOWING) BLUE else WHITE
                    c.drawPath(drop, fill)
                    if (id == STREAM_MAY_DRY) { // half full
                        c.save()
                        c.clipRect(0f, w * 0.6f, w, w)
                        fill.color = PALE
                        c.drawPath(drop, fill)
                        c.restore()
                    }
                    c.drawPath(drop, ink.apply { strokeWidth = w * 0.07f })
                }
                SPRING -> {
                    fill.color = BLUE
                    c.drawCircle(w / 2, w / 2, w * 0.44f, edge)
                    c.drawCircle(w / 2, w / 2, w * 0.42f, fill)
                    c.drawCircle(w / 2, w / 2, w * 0.24f, Paint(edge).apply { strokeWidth = w * 0.07f })
                    fill.color = WHITE
                    c.drawCircle(w / 2, w / 2, w * 0.08f, fill)
                }
                TAP -> {
                    fill.color = BLUE
                    val r = RectF(w * 0.06f, w * 0.06f, w * 0.94f, w * 0.94f)
                    c.drawRoundRect(r, w * 0.16f, w * 0.16f, edge)
                    c.drawRoundRect(r, w * 0.16f, w * 0.16f, fill)
                    fill.color = WHITE
                    // A tap: the handle, the body and the spout, with a drop under it.
                    c.drawRect(w * 0.36f, w * 0.2f, w * 0.64f, w * 0.27f, fill)
                    c.drawRect(w * 0.46f, w * 0.24f, w * 0.54f, w * 0.38f, fill)
                    c.drawRect(w * 0.22f, w * 0.38f, w * 0.72f, w * 0.5f, fill)
                    c.drawRect(w * 0.6f, w * 0.38f, w * 0.72f, w * 0.62f, fill)
                    c.drawCircle(w * 0.66f, w * 0.77f, w * 0.06f, fill)
                }
                LAKE -> {
                    fill.color = BLUE
                    val r = RectF(w * 0.04f, w * 0.16f, w * 0.96f, w * 0.84f)
                    c.drawOval(r, edge)
                    c.drawOval(r, fill)
                    val wave = Paint(edge).apply { strokeWidth = w * 0.07f; strokeCap = Paint.Cap.ROUND }
                    for (y in listOf(0.42f, 0.6f)) {
                        val p = Path().apply {
                            moveTo(w * 0.24f, w * y)
                            quadTo(w * 0.37f, w * (y - 0.08f), w * 0.5f, w * y)
                            quadTo(w * 0.63f, w * (y + 0.08f), w * 0.76f, w * y)
                        }
                        c.drawPath(p, wave)
                    }
                }
            }
            return b
        }
    }
}
