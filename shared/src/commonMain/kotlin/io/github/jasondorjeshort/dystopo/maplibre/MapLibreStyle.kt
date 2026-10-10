package io.github.jasondorjeshort.dystopo.maplibre

import io.github.jasondorjeshort.dystopo.mvt.GeometryType
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.style.DefaultOsmStyle
import io.github.jasondorjeshort.dystopo.style.Filter
import io.github.jasondorjeshort.dystopo.style.Marker
import io.github.jasondorjeshort.dystopo.style.OsmStyle
import io.github.jasondorjeshort.dystopo.style.Placement
import io.github.jasondorjeshort.dystopo.style.Selector
import io.github.jasondorjeshort.dystopo.style.Sprites
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
    /**
     * Sprite sheet URL without extension (MapLibre appends `.json`/`.png`, `@2x` on dense screens), as
     * written by `devtool sprite` ([Sprites]). With a sprite, markers are icons in the label layers, so
     * icons and labels collide; without one they fall back to circle discs that never collide.
     */
    val sprite: String? = null,
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
 *   drop side; MapLibre cannot ask the DEM for "downhill"). Tick length, width and spacing follow the
 *   style's zoom stops (the dash pattern steps at each stop, as MapLibre does not interpolate dashes).
 * - Contour labels ([contourLabels]): index lines only, lowest priority, spaced so every index line
 *   in view gets a label from z13 on (devtool zooms) without stacking; see [contourLabelSpacing].
 * - Symbol `text` templates become `concat` expressions; `{elevation}` prints `ele` (metres in the
 *   tiles) in [units], which should match the contour tiles' unit (a setting; its default follows
 *   [io.github.jasondorjeshort.dystopo.render.UnitRegions]). `{name}` is [LABEL_NAME]: the English or
 *   Latin name first, and a local name in another script on a second line. Markers: with a sprite sheet
 *   ([MapLibreSources.sprite], [Sprites]) the marker is
 *   the label's `icon-image` (the renderer's shape, colour and glyph), so icon and text are one symbol
 *   that collides as a whole; the text sits below the icon, or beside or above it when that is the only
 *   place it fits (`text-variable-anchor`), and a layer with `text-optional` keeps the icon when its
 *   text fits nowhere. Without a sprite, markers are discs (a `circle` layer, no collisions) with the
 *   label below and a marker `text` glyph drawn on the disc.
 * - Label priority: the devtool places symbol layers in list order (first wins); MapLibre places the
 *   topmost layer first, so symbol layers are emitted in reverse. Contour labels are the lowest.
 * - Sizes: text sizes, halos and marker sizes are multiplied by [BASE_TEXT_SCALE] (readable labels by
 *   default, docs/PLANNING.md product principle 6) times the user's [textScale] (a setting, 1.0 by
 *   default, clamped to [MIN_TEXT_SCALE]..[MAX_TEXT_SCALE]). Larger text also gets more room so it
 *   does not just pile up: label padding and line-label spacing grow with the scale, and MapLibre's
 *   collision detection (never `allow-overlap` for names) drops what no longer fits.
 * - Fonts: every symbol layer names exactly one font from [FONTS] (MapLibre Native requests a glyph
 *   range per font stack and draws nothing for a stack it cannot load; its default stack is not ours).
 */
class MapLibreStyle(
    private val sources: MapLibreSources,
    private val style: OsmStyle = DefaultOsmStyle.style,
    private val units: ContourUnits = ContourUnits.FEET,
    textScale: Double = 1.0,
    private val zoomOffset: Double = -1.0,
) {
    /** The user's multiplier after clamping. */
    val textScale: Double = clampTextScale(textScale)
    private val scale = BASE_TEXT_SCALE * this.textScale
    private val paper = "#f6f1e4"
    private val contourColor = "#8c5523"
    private val regular = listOf(FONT_REGULAR)
    private val italic = listOf(FONT_ITALIC)

    companion object {
        /** Built-in enlargement over the devtool's pixel sizes (phone labels were too small at 1.0). */
        const val BASE_TEXT_SCALE = 1.35
        const val MIN_TEXT_SCALE = 0.5
        const val MAX_TEXT_SCALE = 3.0
        /**
         * Halo of contour labels, in devtool pixels. The label sits on its own contour line; with a 1.6 px halo
         * the line showed through the gap after a narrow "1" (Noto Sans has a wide side bearing there), so
         * "13800" read as "1·3800" on the phone. 2.6 px closes that gap.
         */
        const val CONTOUR_LABEL_HALO = 2.6
        const val FONT_REGULAR = "Noto Sans Regular"
        const val FONT_ITALIC = "Noto Sans Italic"
        const val FONT_MEDIUM = "Noto Sans Medium"

        /** Largest bend (degrees) a contour label may follow; contours wiggle more than roads. */
        const val CONTOUR_LABEL_MAX_ANGLE = 65

        /** The fonts the style uses; the glyph route (and tools/android-push-pack.ps1) must provide each. */
        val FONTS = listOf(FONT_REGULAR, FONT_ITALIC, FONT_MEDIUM)

        /**
         * The name a label shows. OpenMapTiles tiles (OpenFreeMap, our packs) carry `name` in the local
         * language plus `name:en` / `name_en` / `name:latin`, and `name:nonlatin` when the local name is
         * in a non-Latin script. The first line is the English name, else the Latin transliteration,
         * else the local name; a non-Latin local name follows on a second line, but only when the
         * renderer can shape that script (`is-supported-script`; MapLibre cannot shape Devanagari, for
         * example), so the fallback is always readable text and never a row of broken glyphs.
         */
        val LABEL_NAME: List<Any?> = listOf(
            "let", "primary",
            listOf("coalesce", listOf("get", "name:en"), listOf("get", "name_en"), listOf("get", "name:latin"), listOf("get", "name"), ""),
            listOf(
                "case",
                listOf(
                    "all", listOf("has", "name:nonlatin"),
                    listOf("!=", listOf("get", "name:nonlatin"), listOf("var", "primary")),
                    listOf("is-supported-script", listOf("get", "name:nonlatin")),
                ),
                listOf("concat", listOf("var", "primary"), "\n", listOf("get", "name:nonlatin")),
                listOf("var", "primary"),
            ),
        )

        /** Clamps a user text scale; NaN or infinite gives 1.0. */
        fun clampTextScale(s: Double): Double = if (s.isNaN() || s.isInfinite()) 1.0 else s.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
    }

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
        val doc = linkedMapOf<String, Any?>("version" to 8, "name" to "dystopo-${style.name}")
        sources.sprite?.let { doc["sprite"] = it }
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
        doc["glyphs"] = sources.glyphs
        doc["sources"] = src
        doc["layers"] = layers
        return doc
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
            "text-size" to 11.0 * contourTextScale,
            "symbol-spacing" to contourLabelSpacing(),
            "text-max-angle" to CONTOUR_LABEL_MAX_ANGLE,
            "text-padding" to round1(4 * textScale),
        ),
        "paint" to mapOf("text-color" to "#6b3f12", "text-halo-color" to paper, "text-halo-width" to round1(CONTOUR_LABEL_HALO * scale / BASE_TEXT_SCALE)),
    )

    /**
     * Contour labels grow with the square root of the user's text scale, not linearly: a line label
     * must fit a stretch of line that bends less than [CONTOUR_LABEL_MAX_ANGLE], and contours bend a
     * lot, so every extra pixel of label length costs labels. Measured in a 412x915 phone viewport
     * (MapLibre GL JS over `devtool serve`, z13 on Rainier's south flank, 16 index lines in view):
     * 14.9 px labels (text scale 1.0) labelled 11, 18 px 7 and 24 px 5, other layers hidden or not.
     * Elevation numbers are short and secondary; names keep the full scale.
     */
    private val contourTextScale = BASE_TEXT_SCALE * kotlin.math.sqrt(textScale)

    /**
     * Contour label spacing (screen px between labels on one line) by MapLibre zoom.
     *
     * MapLibre places line labels per tile: a line piece gets anchors only every `symbol-spacing`
     * along it, starting half a spacing in, so a 512-px tile cut into short pieces got no label at the
     * old 260 px. Measured over Rainier views in a 412x915 phone viewport (MapLibre GL JS, `devtool
     * serve`): at 260 px and 45 degrees, z13.5 near Paradise labelled 1 of 7 index lines and z13 on the
     * south flank 5 of 16; at 120 px and 65 degrees, 6 of 7 and 12 of 16, and the remaining lines show
     * under about 160 px of line on screen (MapLibre's own minimum length: the label must fit within
     * the angle limit) or zigzag across glaciers. Collision (padding, which grows with the text scale;
     * lowest priority) keeps neighbours from stacking, so the closer spacing labels more lines rather
     * than repeating one; the spacing does not grow with the text scale, which only cost labels
     * (1.6 at 192 px: 2 of 16). Below z11 (index lines far apart and long) the spacing opens up again.
     */
    private fun contourLabelSpacing(): Any? =
        listOf("interpolate", listOf("linear"), listOf("zoom"), 10, 260, 12, 120)

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
        if (marker != null && sources.sprite != null) {
            symbols += iconSymbol(l, marker, geometry)
            return
        }
        if (marker != null) {
            val radius = marker.size * scale / 2
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
                        "text-field" to glyph, "text-font" to listOf(FONT_MEDIUM), "text-size" to radius * 1.6,
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
                "text-size" to zoomValue(l.size, null, scale),
                "text-max-width" to 9,
                "text-padding" to round1(3 * textScale),
            )
            if (l.placement == Placement.LINE) {
                layout["symbol-placement"] = "line"
                layout["symbol-spacing"] = round1(l.spacing * textScale.coerceAtLeast(1.0))
                layout["text-max-angle"] = 40
            } else if (marker != null) {
                layout["text-anchor"] = "top"
                layout["text-offset"] = listOf(0.0, marker.size * scale / 2 / (l.size.at(14.0) * scale) + 0.3)
            }
            sortKey(l)?.let { layout["symbol-sort-key"] = it }
            this["layout"] = layout
            this["paint"] = textPaint(l)
        }
    }

    /**
     * A marker layer with a sprite: the icon and the (optional) text in one symbol, so MapLibre places
     * or drops them together and neither overlaps another label. The text prefers the spot under the
     * icon and moves beside or above it when that collides.
     */
    private fun iconSymbol(l: StyleLayer.Symbol, marker: Marker,geometry: List<Any?>?): Map<String, Any?> =
        base(l.id, "symbol", l.select, geometry).apply {
            val layout = linkedMapOf<String, Any?>(
                "icon-image" to Sprites.iconName(marker),
                "icon-size" to round3(scale / Sprites.ICON_SCALE),
                "icon-padding" to round1(1 * textScale),
            )
            if (l.text.isNotBlank()) {
                val textSize = l.size.at(14.0) * scale
                // Clearance between the icon's edge and the text, in ems of the text.
                val offset = round3((marker.size * scale / 2 + 1.5) / textSize)
                layout["text-field"] = textTemplate(l.text)
                layout["text-font"] = if (l.italic) italic else regular
                layout["text-size"] = zoomValue(l.size, null, scale)
                layout["text-max-width"] = 9
                layout["text-padding"] = round1(3 * textScale)
                layout["text-variable-anchor"] = listOf("top", "right", "left", "bottom")
                layout["text-radial-offset"] = offset
                layout["text-justify"] = "auto"
                layout["text-optional"] = l.textOptional
            }
            sortKey(l)?.let { layout["symbol-sort-key"] = it }
            this["layout"] = layout
            if (l.text.isNotBlank()) this["paint"] = textPaint(l)
        }

    private fun sortKey(l: StyleLayer.Symbol): Any? = when (l.sortKey) {
        "rank" -> listOf("coalesce", listOf("to-number", listOf("get", "rank")), 99)
        "-ele" -> listOf("-", 0, listOf("coalesce", listOf("to-number", listOf("get", "ele")), 0))
        else -> null
    }

    private fun textPaint(l: StyleLayer.Symbol): Map<String, Any?> = mapOf(
        "text-color" to color(l.color),
        "text-halo-color" to color(l.haloColor),
        "text-halo-width" to l.haloWidth * scale,
    )

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
                if (key == "name") {
                    keys += "name"
                    pieces.add(LABEL_NAME)
                } else if (key == "elevation") {
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

    private fun round1(d: Double): Double = kotlin.math.round(d * 10) / 10

    private fun round3(d: Double): Double = kotlin.math.round(d * 1000) / 1000

    private fun fmt(d: Double): String = ((d * 1000).toLong() / 1000.0).toString()
}
