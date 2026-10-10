package io.github.jasondorjeshort.dystopo.maplibre

import io.github.jasondorjeshort.dystopo.mvt.GeometryType
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.style.DefaultOsmStyle
import io.github.jasondorjeshort.dystopo.style.Filter
import io.github.jasondorjeshort.dystopo.style.OsmStyle
import io.github.jasondorjeshort.dystopo.style.Placement
import io.github.jasondorjeshort.dystopo.style.Selector
import io.github.jasondorjeshort.dystopo.style.StyleColor
import io.github.jasondorjeshort.dystopo.style.StyleLayer
import io.github.jasondorjeshort.dystopo.style.ZoomValue

/** Where the style's sources live (URL templates with `{z}/{x}/{y}`) and their zoom ranges. */
data class MapLibreSources(
    val demTiles: String,
    val demMinZoom: Int,
    val demMaxZoom: Int,
    val contourTiles: String,
    val contourMinZoom: Int,
    val contourMaxZoom: Int,
    /** OSM vector tiles (an outdoors or OpenMapTiles archive); null draws terrain only. */
    val osmTiles: String?,
    val osmMinZoom: Int = 0,
    val osmMaxZoom: Int = 14,
    /** `{fontstack}/{range}` template for glyph PBFs. */
    val glyphs: String,
    val attribution: String = "",
)

/**
 * Translates the devtool's declarative OSM style ([OsmStyle], by default [DefaultOsmStyle]) into a
 * MapLibre GL style (spec v8), so the phone and the PNG renderer draw the same layer set with the
 * same colours, widths, dashes, filters and label order, over our hillshade (`raster-dem`) and our
 * contour tiles ([io.github.jasondorjeshort.dystopo.tiles.ContourTiles]: `ele`, `level`).
 *
 * Mapping, and where it is approximate:
 * - Zooms: the devtool's zoom counts 256-px tiles, MapLibre's 512-px tiles, so the same scale is one
 *   MapLibre zoom lower ([zoomOffset], default -1) for `minzoom`/`maxzoom` and zoom stops.
 * - Filters become expressions (`in`/`has`/comparisons, numbers matched as numbers or strings);
 *   a line layer without an explicit geometry draws only LineStrings, as in the devtool.
 * - Line casings become a wider line layer underneath; `modifiers` become data-driven width and
 *   opacity factors. Cliff `ticks` become a dashed band offset to the right of the line (the OSM
 *   drop side; MapLibre cannot ask the DEM for "downhill").
 * - Symbol `text` templates become `concat` expressions; `{elevation}` prints `ele` (metres in the
 *   tiles) in [units]. Markers are discs (a `circle` layer; MapLibre has no shapes without a sprite
 *   sheet) with the label below; a marker `text` glyph is drawn on the disc.
 * - Label priority: the devtool places symbol layers in list order (first wins); MapLibre places the
 *   topmost layer first, so symbol layers are emitted in reverse. Contour labels are the lowest.
 * - Sizes: text sizes, halos and marker sizes are multiplied by [textScale] (readable labels by
 *   default, docs/PLANNING.md product principle 6).
 */
class MapLibreStyle(
    private val sources: MapLibreSources,
    private val style: OsmStyle = DefaultOsmStyle.style,
    private val units: ContourUnits = ContourUnits.FEET,
    private val textScale: Double = 1.35,
    private val zoomOffset: Double = -1.0,
) {
    private val paper = "#f6f1e4"
    private val contourColor = "#8c5523"
    private val regular = listOf("Noto Sans Regular")
    private val italic = listOf("Noto Sans Italic")

    /** The style JSON. */
    fun json(): String = JsonWriter.write(document())

    fun document(): Map<String, Any?> {
        val lower = ArrayList<Map<String, Any?>>()   // fills and lines, bottom to top
        val markers = ArrayList<Map<String, Any?>>() // marker discs, above all lines
        val symbols = ArrayList<Map<String, Any?>>() // labels in the devtool's priority order (first = most important)
        lower += mapOf("id" to "background", "type" to "background", "paint" to mapOf("background-color" to paper))
        lower += mapOf(
            "id" to "hillshade", "type" to "hillshade", "source" to "dem",
            "paint" to mapOf(
                "hillshade-exaggeration" to 0.55,
                "hillshade-shadow-color" to "#4a3f36",
                "hillshade-highlight-color" to "#fffdf7",
                "hillshade-accent-color" to "#6b5a4d",
            ),
        )
        var contoursPlaced = false
        for (layer in style.layers) {
            when (layer) {
                is StyleLayer.Contours -> {
                    lower += contourLines()
                    contoursPlaced = true
                }
                is StyleLayer.Fill -> if (sources.osmTiles != null) lower += fill(layer)
                is StyleLayer.Line -> if (sources.osmTiles != null) lower += line(layer)
                is StyleLayer.Symbol -> if (sources.osmTiles != null) {
                    symbol(layer, markers, symbols)
                }
            }
        }
        if (!contoursPlaced) lower += contourLines()
        val layers = ArrayList<Map<String, Any?>>()
        layers += lower
        layers += markers
        layers += contourLabels()
        layers += symbols.asReversed()
        val src = LinkedHashMap<String, Any?>()
        src["dem"] = mapOf(
            "type" to "raster-dem", "tiles" to listOf(sources.demTiles), "tileSize" to 256, "encoding" to "terrarium",
            "minzoom" to sources.demMinZoom, "maxzoom" to sources.demMaxZoom,
        )
        src["contours"] = mapOf(
            "type" to "vector", "tiles" to listOf(sources.contourTiles),
            "minzoom" to sources.contourMinZoom, "maxzoom" to sources.contourMaxZoom,
        )
        if (sources.osmTiles != null) {
            src["osm"] = mapOf(
                "type" to "vector", "tiles" to listOf(sources.osmTiles),
                "minzoom" to sources.osmMinZoom, "maxzoom" to sources.osmMaxZoom, "attribution" to sources.attribution,
            )
        }
        return linkedMapOf(
            "version" to 8,
            "name" to "dystopo-${style.name}",
            "glyphs" to sources.glyphs,
            "sources" to src,
            "layers" to layers,
        )
    }

    // ---- contours ----

    private fun contourLines(): List<Map<String, Any?>> = listOf(
        mapOf(
            "id" to "contour-minor", "type" to "line", "source" to "contours", "source-layer" to "contours",
            "filter" to listOf("!=", listOf("get", "level"), 1),
            "layout" to mapOf("line-join" to "round", "line-cap" to "round"),
            "paint" to mapOf("line-color" to contourColor, "line-opacity" to 0.6, "line-width" to 0.9),
        ),
        mapOf(
            "id" to "contour-index", "type" to "line", "source" to "contours", "source-layer" to "contours",
            "filter" to listOf("==", listOf("get", "level"), 1),
            "layout" to mapOf("line-join" to "round", "line-cap" to "round"),
            "paint" to mapOf("line-color" to contourColor, "line-opacity" to 0.85, "line-width" to 1.8),
        ),
    )

    private fun contourLabels(): Map<String, Any?> = mapOf(
        "id" to "contour-labels", "type" to "symbol", "source" to "contours", "source-layer" to "contours",
        "filter" to listOf("==", listOf("get", "level"), 1),
        "layout" to mapOf(
            "symbol-placement" to "line",
            "text-field" to listOf("to-string", listOf("get", "ele")),
            "text-font" to regular,
            "text-size" to 11.0 * textScale,
            "symbol-spacing" to 260,
            "text-max-angle" to 45,
            "text-padding" to 4,
        ),
        "paint" to mapOf("text-color" to "#6b3f12", "text-halo-color" to paper, "text-halo-width" to 1.6),
    )

    // ---- OSM layers ----

    private fun base(id: String, type: String, select: Selector, extraFilter: List<Any?>? = null): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>("id" to id, "type" to type, "source" to "osm", "source-layer" to select.sourceLayer)
        if (select.minZoom > 0.0) m["minzoom"] = (select.minZoom + zoomOffset).coerceAtLeast(0.0)
        if (select.maxZoom < 24.0) m["maxzoom"] = (select.maxZoom + zoomOffset).coerceAtLeast(0.0)
        val parts = ArrayList<Any?>()
        parts.addAll(filter(select.filter))
        extraFilter?.let { parts.add(it) }
        if (parts.isNotEmpty()) m["filter"] = if (parts.size == 1) parts[0] else listOf("all") + parts
        return m
    }

    private fun geometryFilter(type: GeometryType?): List<Any?>? = when (type) {
        GeometryType.POINT -> listOf("==", listOf("geometry-type"), "Point")
        GeometryType.LINESTRING -> listOf("==", listOf("geometry-type"), "LineString")
        GeometryType.POLYGON -> listOf("==", listOf("geometry-type"), "Polygon")
        else -> null
    }

    private fun fill(l: StyleLayer.Fill): Map<String, Any?> = base(l.id, "fill", l.select).apply {
        this["paint"] = mapOf("fill-color" to color(l.color), "fill-opacity" to zoomValue(l.opacity, null))
    }

    private fun line(l: StyleLayer.Line): List<Map<String, Any?>> {
        val geometry = geometryFilter(l.select.geometry ?: GeometryType.LINESTRING)
        val widthFactor = modifierFactor(l.modifiers.filter { it.width != 1.0 }.map { it.filter to it.width })
        val opacityFactor = modifierFactor(l.modifiers.filter { it.opacity != 1.0 }.map { it.filter to it.opacity })
        val opacity = zoomValue(l.opacity, opacityFactor)
        val out = ArrayList<Map<String, Any?>>()
        val layout = mapOf("line-join" to "round", "line-cap" to if (l.dash == null) "round" else "butt")
        if (l.casingColor != null && l.casingWidth.stops.any { it.second > 0 }) {
            val casingWidth = combine(l.width, l.casingWidth) { w, c -> w + 2 * c }
            out += base("${l.id}-casing", "line", l.select, geometry).apply {
                this["layout"] = layout
                this["paint"] = mapOf(
                    "line-color" to color(l.casingColor),
                    "line-width" to zoomValue(casingWidth, widthFactor),
                    "line-opacity" to opacity,
                )
            }
        }
        l.ticks?.let { t ->
            val mid = 14.0 - zoomOffset
            val length = t.length.at(mid)
            val tickWidth = t.width.at(mid)
            out += base("${l.id}-ticks", "line", l.select, geometry).apply {
                this["layout"] = mapOf("line-join" to "round", "line-cap" to "butt")
                this["paint"] = mapOf(
                    "line-color" to color(l.color),
                    "line-width" to zoomValue(t.length, null),
                    "line-offset" to zoomValue(combine(l.width, t.length) { w, len -> (w + len) / 2 }, null),
                    "line-dasharray" to listOf(tickWidth / length, (t.spacing.at(mid) - tickWidth).coerceAtLeast(0.5) / length),
                    "line-opacity" to opacity,
                )
            }
        }
        out += base(l.id, "line", l.select, geometry).apply {
            this["layout"] = layout
            val paint = linkedMapOf<String, Any?>(
                "line-color" to color(l.color),
                "line-width" to zoomValue(l.width, widthFactor),
                "line-opacity" to opacity,
            )
            l.dash?.let { paint["line-dasharray"] = it }
            this["paint"] = paint
        }
        return out
    }

    private fun symbol(l: StyleLayer.Symbol, markers: MutableList<Map<String, Any?>>, symbols: MutableList<Map<String, Any?>>) {
        val geometry = geometryFilter(l.select.geometry ?: if (l.placement == Placement.LINE) GeometryType.LINESTRING else GeometryType.POINT)
        val marker = l.marker
        if (marker != null) {
            val radius = marker.size * textScale / 2
            markers += base("${l.id}-marker", "circle", l.select, geometry).apply {
                this["paint"] = mapOf(
                    "circle-color" to color(marker.color),
                    "circle-radius" to radius,
                    "circle-stroke-color" to "#fffdf7",
                    "circle-stroke-width" to 1.2,
                )
            }
            marker.text?.let { glyph ->
                symbols += base("${l.id}-glyph", "symbol", l.select, geometry).apply {
                    this["layout"] = mapOf(
                        "text-field" to glyph, "text-font" to listOf("Noto Sans Medium"), "text-size" to radius * 1.6,
                        "text-allow-overlap" to true, "text-ignore-placement" to true,
                    )
                    this["paint"] = mapOf("text-color" to color(marker.textColor))
                }
            }
        }
        if (l.text.isBlank()) return
        symbols += base(l.id, "symbol", l.select, geometry).apply {
            val layout = linkedMapOf<String, Any?>(
                "text-field" to textTemplate(l.text),
                "text-font" to if (l.italic) italic else regular,
                "text-size" to zoomValue(l.size, null, textScale),
                "text-max-width" to 9,
                "text-padding" to 3,
            )
            if (l.placement == Placement.LINE) {
                layout["symbol-placement"] = "line"
                layout["symbol-spacing"] = l.spacing
                layout["text-max-angle"] = 40
            } else if (marker != null) {
                layout["text-anchor"] = "top"
                layout["text-offset"] = listOf(0.0, marker.size * textScale / 2 / (l.size.at(14.0) * textScale) + 0.3)
            }
            when (l.sortKey) {
                "rank" -> layout["symbol-sort-key"] = listOf("coalesce", listOf("to-number", listOf("get", "rank")), 99)
                "-ele" -> layout["symbol-sort-key"] = listOf("-", 0, listOf("coalesce", listOf("to-number", listOf("get", "ele")), 0))
            }
            this["layout"] = layout
            this["paint"] = mapOf(
                "text-color" to color(l.color),
                "text-halo-color" to color(l.haloColor),
                "text-halo-width" to l.haloWidth * textScale,
            )
        }
    }

    // ---- expressions ----

    /** The filter's conditions as expressions (empty list for "everything"). */
    private fun filter(f: Filter): List<Any?> = f.conditions.map { c ->
        when (c) {
            is Filter.Condition.In -> {
                val values = ArrayList<Any?>()
                for (v in c.values) {
                    values.add(v)
                    v.toDoubleOrNull()?.let { values.add(it) }
                }
                val test = listOf("in", listOf("get", c.key), listOf("literal", values))
                if (c.negate) listOf("!", test) else test
            }
            is Filter.Condition.Has -> if (c.present) listOf("has", c.key) else listOf("!", listOf("has", c.key))
            is Filter.Condition.Compare -> listOf(
                "all", listOf("has", c.key),
                listOf(c.op, listOf("to-number", listOf("get", c.key), 0), c.value),
            )
        }
    }

    /** Product of `case` factors for the matching modifiers, or null when there are none. */
    private fun modifierFactor(mods: List<Pair<Filter, Double>>): Any? {
        if (mods.isEmpty()) return null
        val cases = mods.map { (f, k) ->
            val cond = filter(f).let { if (it.size == 1) it[0] else listOf("all") + it }
            listOf("case", cond, k, 1)
        }
        return if (cases.size == 1) cases[0] else listOf("*") + cases
    }

    private fun zoomValue(v: ZoomValue, factor: Any?, scale: Double = 1.0): Any? {
        fun value(x: Double): Any? = if (factor == null) x * scale else listOf("*", x * scale, factor)
        if (v.stops.size == 1) return value(v.stops[0].second)
        val expr = arrayListOf<Any?>("interpolate", listOf("linear"), listOf("zoom"))
        for ((z, x) in v.stops) {
            expr.add(z + zoomOffset)
            expr.add(value(x))
        }
        return expr
    }

    private fun combine(a: ZoomValue, b: ZoomValue, f: (Double, Double) -> Double): ZoomValue {
        val zooms = (a.stops.map { it.first } + b.stops.map { it.first }).distinct().sorted()
        return ZoomValue(zooms.map { it to f(a.at(it), b.at(it)) })
    }

    /** A devtool text template (`{key}`, `{elevation}`, newline-separated lines) as a MapLibre expression. */
    private fun textTemplate(template: String): Any? {
        val lines = template.split("\n").filter { it.isNotEmpty() }
        val parts = ArrayList<Any?>()
        lines.forEachIndexed { i, line ->
            val pieces = ArrayList<Any?>()
            val keys = ArrayList<String>()
            var pos = 0
            val re = Regex("\\{([^}]+)\\}")
            for (m in re.findAll(line)) {
                if (m.range.first > pos) pieces.add(line.substring(pos, m.range.first))
                val key = m.groupValues[1]
                if (key == "elevation") {
                    keys += "ele"
                    pieces.add(listOf(
                        "concat",
                        listOf("to-string", listOf("round", listOf("/", listOf("to-number", listOf("get", "ele"), 0), units.meters))),
                        " ${units.symbol}",
                    ))
                } else {
                    keys += key
                    pieces.add(listOf("to-string", listOf("coalesce", listOf("get", key), "")))
                }
                pos = m.range.last + 1
            }
            if (pos < line.length) pieces.add(line.substring(pos))
            val prefix = if (i > 0) listOf("\n") else emptyList()
            val body = listOf("concat") + prefix + pieces
            parts.add(if (keys.isEmpty()) body else listOf("case", anyHas(keys), body, ""))
        }
        return when (parts.size) {
            0 -> ""
            1 -> parts[0]
            else -> listOf("concat") + parts
        }
    }

    private fun anyHas(keys: List<String>): List<Any?> =
        if (keys.size == 1) listOf("has", keys[0]) else listOf("any") + keys.map { listOf("has", it) }

    private fun color(c: StyleColor): String =
        if (c.a == 255) "rgb(${c.r},${c.g},${c.b})" else "rgba(${c.r},${c.g},${c.b},${fmt(c.a / 255.0)})"

    private fun fmt(d: Double): String = ((d * 1000).toLong() / 1000.0).toString()
}
