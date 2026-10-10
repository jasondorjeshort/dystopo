package io.github.jasondorjeshort.dystopo.style

import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.GeometryType
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import kotlin.math.hypot
import kotlin.math.roundToLong

/** The output image a scene is built for: a Web Mercator window (see `MercatorWindow`). */
data class SceneWindow(val zoom: Int, val x0: Int, val y0: Int, val width: Int, val height: Int, val tileSize: Int = 256)

/** A decoded source tile. */
data class SourceTile(val id: TileId, val tile: VectorTile)

/** Per style layer counters for reports. */
data class LayerCounts(
    /** Features that passed the filter, counted once per source tile they appear in. */
    var features: Int = 0,
    /** Drawn pieces (rings, lines after clipping and merging, or label candidates). */
    var pieces: Int = 0,
    /** Vertices after clipping. */
    var vertices: Int = 0,
)

/** A label to place: one or more text lines at a point, or along a polyline. */
class LabelRequest(
    val layer: StyleLayer.Symbol,
    val lines: List<String>,
    /** Point anchor (x, y), or null for a line label. */
    val x: Double = 0.0,
    val y: Double = 0.0,
    val path: DoubleArray? = null,
    val sort: Double = 0.0,
) {
    val text: String get() = lines.joinToString("\n")
}

/** Style layers resolved to image-pixel geometry, in draw order. */
sealed class SceneLayer {
    abstract val style: StyleLayer

    /** Rings (exteriors and holes, as in the tiles) to fill together with the non-zero rule. */
    class Fill(override val style: StyleLayer.Fill, val rings: List<DoubleArray>) : SceneLayer()

    /**
     * Lines of one style layer. A layer with modifiers becomes several of these, one per distinct
     * width and opacity factor ([widthScale], [opacityScale]), in style order of first appearance.
     */
    class Line(
        override val style: StyleLayer.Line,
        val lines: List<DoubleArray>,
        val widthScale: Double = 1.0,
        val opacityScale: Double = 1.0,
    ) : SceneLayer()

    class Contours(override val style: StyleLayer.Contours) : SceneLayer()
}

class OsmScene(
    val window: SceneWindow,
    /** Fills, lines and the contour marker, bottom to top. */
    val layers: List<SceneLayer>,
    /** Label requests in placement priority order (style order, then each layer's sort key). */
    val labels: List<LabelRequest>,
    val counts: Map<String, LayerCounts>,
) {
    companion object {
        /**
         * Resolves [style] against [tiles] for [window]. Geometry is moved to image pixels and clipped
         * to each tile's own square (the tile buffer would otherwise draw everything near a seam
         * twice), and to the image plus [margin] pixels. Lines cut at tile seams are joined back up.
         * Points belong to the tile whose square contains them (half-open), so buffered copies are
         * dropped. [elevationUnits] is "ft" or "m" for the `{elevation}` placeholder.
         */
        fun build(style: OsmStyle, tiles: List<SourceTile>, window: SceneWindow, elevationUnits: String = "m", margin: Double = 16.0): OsmScene {
            val zoom = window.zoom.toDouble()
            val image = PixelRect(-margin, -margin, window.width + margin, window.height + margin)
            val layers = ArrayList<SceneLayer>()
            val labels = ArrayList<LabelRequest>()
            val counts = LinkedHashMap<String, LayerCounts>()
            for (layer in style.layers) {
                val select = when (layer) {
                    is StyleLayer.Fill -> layer.select
                    is StyleLayer.Line -> layer.select
                    is StyleLayer.Symbol -> layer.select
                    is StyleLayer.Contours -> null
                }
                if (select != null && !select.visibleAt(zoom)) continue
                val count = LayerCounts()
                when (layer) {
                    is StyleLayer.Contours -> {
                        layers += SceneLayer.Contours(layer)
                        continue
                    }
                    is StyleLayer.Fill -> {
                        val rings = ArrayList<DoubleArray>()
                        forEachFeature(tiles, window, image, layer.select, GeometryType.POLYGON, count) { f, place, clip ->
                            for (poly in (f.geometry as Geometry.Polygons).polygons) {
                                for (ring in listOf(poly.exterior) + poly.interiors) {
                                    Clip.ring(place.transform(ring), clip)?.let { rings += it }
                                }
                            }
                        }
                        count.pieces = rings.size
                        count.vertices = rings.sumOf { it.size / 2 }
                        if (rings.isNotEmpty()) layers += SceneLayer.Fill(layer, rings)
                    }
                    is StyleLayer.Line -> {
                        // Pieces grouped by modifier factors (width, opacity); plain layers have one group.
                        val groups = LinkedHashMap<Pair<Double, Double>, MutableList<DoubleArray>>()
                        forEachFeature(tiles, window, image, layer.select, GeometryType.LINESTRING, count) { f, place, clip ->
                            var w = 1.0
                            var o = 1.0
                            for (m in layer.modifiers) if (m.filter.matches(f.properties)) {
                                w *= m.width
                                o *= m.opacity
                            }
                            val pieces = groups.getOrPut(w to o) { ArrayList() }
                            for (line in outlines(f.geometry, place)) pieces += Clip.polyline(line, clip)
                        }
                        for ((factors, pieces) in groups) {
                            val merged = LineMerge.merge(pieces)
                            count.pieces += merged.size
                            count.vertices += merged.sumOf { it.size / 2 }
                            if (merged.isNotEmpty()) layers += SceneLayer.Line(layer, merged, factors.first, factors.second)
                        }
                    }
                    is StyleLayer.Symbol -> {
                        val requests = symbolRequests(layer, tiles, window, image, elevationUnits, count)
                        count.pieces = requests.size
                        labels += requests
                    }
                }
                counts[layer.id] = count
            }
            return OsmScene(window, layers, labels, counts)
        }

        /** Line geometry, or polygon rings as closed polylines (so outlines can be stroked). */
        private fun outlines(g: Geometry, place: TilePlacement): List<DoubleArray> = when (g) {
            is Geometry.Lines -> g.lines.map { place.transform(it) }
            is Geometry.Polygons -> g.polygons.flatMap { p -> (listOf(p.exterior) + p.interiors).map { Clip.closeRing(place.transform(it)) } }
            else -> emptyList()
        }

        private inline fun forEachFeature(
            tiles: List<SourceTile>,
            window: SceneWindow,
            image: PixelRect,
            select: Selector,
            natural: GeometryType,
            count: LayerCounts,
            action: (Feature, TilePlacement, PixelRect) -> Unit,
        ) {
            for (st in tiles) {
                val layer = st.tile.layer(select.sourceLayer) ?: continue
                val place = TilePlacement(st.id, layer.extent, window.zoom, window.x0, window.y0, window.tileSize)
                val clip = place.rect.intersect(image)
                if (clip.isEmpty) continue
                for (f in layer.features) {
                    if (!accepts(select, natural, f.type)) continue
                    if (!select.filter.matches(f.properties)) continue
                    count.features++
                    action(f, place, clip)
                }
            }
        }

        /**
         * Geometry types a layer kind accepts. Fills take polygons; lines take lines and, when the
         * selector asks for `polygon`, polygon outlines; symbols take points and lines (and
         * polygons when asked).
         */
        private fun accepts(select: Selector, natural: GeometryType, type: GeometryType): Boolean =
            if (select.geometry != null) type == select.geometry else type == natural

        private fun symbolRequests(
            layer: StyleLayer.Symbol,
            tiles: List<SourceTile>,
            window: SceneWindow,
            image: PixelRect,
            units: String,
            count: LayerCounts,
        ): List<LabelRequest> {
            val points = ArrayList<LabelRequest>()
            // Line labels: pieces grouped by text, merged across tile seams before placement.
            val linePieces = LinkedHashMap<List<String>, Pair<Double, MutableList<DoubleArray>>>()
            for (st in tiles) {
                val tl = st.tile.layer(layer.select.sourceLayer) ?: continue
                val place = TilePlacement(st.id, tl.extent, window.zoom, window.x0, window.y0, window.tileSize)
                val clip = place.rect.intersect(image)
                if (clip.isEmpty) continue
                for (f in tl.features) {
                    if (layer.select.geometry != null && f.type != layer.select.geometry) continue
                    if (!layer.select.filter.matches(f.properties)) continue
                    val lines = TextTemplate.render(layer.text, f.properties, units)
                    // An empty text still places the marker when the text is optional.
                    if (lines.isEmpty() && !(layer.textOptional && layer.marker != null && layer.placement == Placement.POINT)) continue
                    val sort = sortValue(layer.sortKey, f)
                    when (val g = f.geometry) {
                        is Geometry.Points -> if (layer.placement == Placement.POINT) {
                            for (p in g.points) {
                                val x = place.x(p)
                                val y = place.y(p)
                                if (!place.rect.containsHalfOpen(x, y) || !image.containsHalfOpen(x, y)) continue
                                count.features++
                                count.vertices++
                                points += LabelRequest(layer, lines, x, y, sort = sort)
                            }
                        }
                        is Geometry.Lines -> {
                            val clipped = g.lines.flatMap { Clip.polyline(place.transform(it), clip) }
                            if (clipped.isEmpty()) continue
                            count.features++
                            count.vertices += clipped.sumOf { it.size / 2 }
                            if (layer.placement == Placement.LINE) {
                                val entry = linePieces.getOrPut(lines) { sort to ArrayList() }
                                entry.second += clipped
                                if (sort < entry.first) linePieces[lines] = sort to entry.second
                            } else {
                                // A point label on a line goes at the middle of its longest piece.
                                val longest = clipped.maxBy { length(it) }
                                val (mx, my) = midpoint(longest)
                                if (place.rect.containsHalfOpen(mx, my)) points += LabelRequest(layer, lines, mx, my, sort = sort)
                            }
                        }
                        is Geometry.Polygons -> if (layer.placement == Placement.POINT) {
                            // Rough anchor: the centroid of the exterior's vertices, if this tile owns it.
                            val ext = g.polygons.maxBy { it.exterior.size }.exterior
                            val x = ext.sumOf { place.x(it) } / ext.size
                            val y = ext.sumOf { place.y(it) } / ext.size
                            if (place.rect.containsHalfOpen(x, y) && image.containsHalfOpen(x, y)) {
                                count.features++
                                points += LabelRequest(layer, lines, x, y, sort = sort)
                            }
                        }
                        else -> {}
                    }
                }
            }
            val out = ArrayList<LabelRequest>(points)
            for ((lines, entry) in linePieces) {
                for (path in LineMerge.merge(entry.second, 2.0)) {
                    out += LabelRequest(layer, lines, path = path, sort = entry.first - length(path) * 1e-6)
                }
            }
            // Stable: equal keys keep tile order. Longer lines first among equals.
            return out.sortedBy { it.sort }
        }

        private fun sortValue(key: String?, f: Feature): Double {
            if (key == null) return 0.0
            val desc = key.startsWith("-")
            val v = f.properties[key.removePrefix("-")]?.asDouble() ?: return Double.MAX_VALUE
            return if (desc) -v else v
        }

        internal fun length(pts: DoubleArray): Double {
            var s = 0.0
            for (i in 1 until pts.size / 2) s += hypot(pts[2 * i] - pts[2 * i - 2], pts[2 * i + 1] - pts[2 * i - 1])
            return s
        }

        private fun midpoint(pts: DoubleArray): Pair<Double, Double> {
            val half = length(pts) / 2
            var s = 0.0
            for (i in 1 until pts.size / 2) {
                val d = hypot(pts[2 * i] - pts[2 * i - 2], pts[2 * i + 1] - pts[2 * i - 1])
                if (s + d >= half && d > 0) {
                    val t = (half - s) / d
                    return (pts[2 * i - 2] + (pts[2 * i] - pts[2 * i - 2]) * t) to (pts[2 * i - 1] + (pts[2 * i + 1] - pts[2 * i - 1]) * t)
                }
                s += d
            }
            return pts[0] to pts[1]
        }
    }
}

/** Fills `{key}` placeholders in label templates; see [StyleLayer.Symbol]. */
object TextTemplate {
    /** The template's lines with placeholders filled; lines whose placeholders are all missing are dropped. */
    fun render(template: String, props: Map<String, io.github.jasondorjeshort.dystopo.mvt.Value>, units: String): List<String> =
        template.split("\n").mapNotNull { line -> renderLine(line, props, units) }.filter { it.isNotBlank() }

    private fun renderLine(line: String, props: Map<String, io.github.jasondorjeshort.dystopo.mvt.Value>, units: String): String? {
        val sb = StringBuilder()
        var placeholders = 0
        var filled = 0
        var i = 0
        while (i < line.length) {
            val open = line.indexOf('{', i)
            if (open < 0) {
                sb.append(line, i, line.length)
                break
            }
            val close = line.indexOf('}', open)
            if (close < 0) {
                sb.append(line, i, line.length)
                break
            }
            sb.append(line, i, open)
            val key = line.substring(open + 1, close)
            placeholders++
            val value = when (key) {
                "elevation" -> elevation(props, units)
                "name" -> name(props)
                else -> props[key]?.asText()?.trim()
            }
            if (!value.isNullOrEmpty()) {
                filled++
                sb.append(value)
            }
            i = close + 1
        }
        return if (placeholders > 0 && filled == 0) null else sb.toString().trim()
    }

    /**
     * The name a label shows for `{name}`, as the phone style's `LABEL_NAME` picks it: English
     * (`name:en`, `name_en`), else the Latin transliteration (`name:latin`), else the local `name`.
     * The phone adds the local name in another script on a second line (`name:nonlatin`, or a `name`
     * starting outside the Latin blocks, where MapLibre can shape it); the PNG renderer does not (its
     * fonts would draw boxes for many scripts). The goldens are Latin-only, so StyleParityTest sees no
     * difference there.
     */
    fun name(props: Map<String, io.github.jasondorjeshort.dystopo.mvt.Value>): String? =
        NAME_KEYS.firstNotNullOfOrNull { k -> props[k]?.asText()?.trim()?.takeIf { it.isNotEmpty() } }

    /** Property keys tried in order for `{name}`. */
    val NAME_KEYS = listOf("name:en", "name_en", "name:latin", "name")

    /** The `ele` property (meters) in [units] ("ft" or "m"), rounded, with the unit symbol. */
    fun elevation(props: Map<String, io.github.jasondorjeshort.dystopo.mvt.Value>, units: String): String? {
        val m = props["ele"]?.asDouble() ?: return null
        return if (units == "ft") "${(m / 0.3048).roundToLong()} ft" else "${m.roundToLong()} m"
    }
}
