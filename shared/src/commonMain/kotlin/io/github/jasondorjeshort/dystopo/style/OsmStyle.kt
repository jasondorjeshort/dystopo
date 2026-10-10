package io.github.jasondorjeshort.dystopo.style

import io.github.jasondorjeshort.dystopo.mvt.GeometryType
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson

/**
 * A small declarative map style for OpenMapTiles-schema vector tiles. It mirrors the MapLibre
 * style concepts (ordered layers, `source-layer`, filters, zoom ranges, zoom-interpolated
 * numbers, line dashes in line-width units) in a much smaller JSON shape, so the same file can be
 * translated to a MapLibre style later and so users can edit it (see [DefaultOsmStyle] for the
 * built-in one and the format).
 *
 * Layers draw in list order, bottom to top. A layer of type `contours` marks where the contour
 * lines go; layers before it are under the contours, layers after it on top. All labels (symbol
 * layers and contour labels) draw above every line and fill. Symbol layers claim label space in
 * list order, so earlier symbol layers win collisions.
 */
class OsmStyle(val name: String, val layers: List<StyleLayer>) {
    init {
        val dup = layers.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "duplicate layer ids: $dup" }
    }

    /** Layer id -> layer. */
    val byId: Map<String, StyleLayer> = layers.associateBy { it.id }

    companion object {
        /** Parses the style JSON format described in [DefaultOsmStyle]. Throws with the layer id on errors. */
        fun parse(json: String): OsmStyle {
            val root = MiniJson.parseObject(json)
            val name = root["name"] as? String ?: "unnamed"
            val layers = (root["layers"] as? List<*> ?: throw IllegalArgumentException("style: 'layers' must be a list"))
                .mapIndexed { i, raw ->
                    val obj = raw.asObject("layer $i")
                    val id = obj["id"] as? String ?: throw IllegalArgumentException("style: layer $i has no id")
                    try {
                        parseLayer(id, obj)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("style layer '$id': ${e.message}", e)
                    }
                }
            return OsmStyle(name, layers)
        }

        private fun parseLayer(id: String, o: Map<String, Any?>): StyleLayer {
            val type = o["type"] as? String ?: throw IllegalArgumentException("missing type")
            if (type == "contours") return StyleLayer.Contours(id)
            val select = Selector(
                sourceLayer = o["source-layer"] as? String ?: throw IllegalArgumentException("missing source-layer"),
                filter = Filter.parse(o["filter"]),
                minZoom = (o["minzoom"] as? Number)?.toDouble() ?: 0.0,
                maxZoom = (o["maxzoom"] as? Number)?.toDouble() ?: 24.0,
                geometry = (o["geometry"] as? String)?.let { geometryType(it) },
            )
            return when (type) {
                "fill" -> StyleLayer.Fill(
                    id, select,
                    color = StyleColor.parse(o.string("color")),
                    opacity = ZoomValue.parse(o["opacity"], 1.0),
                )
                "line" -> StyleLayer.Line(
                    id, select,
                    color = StyleColor.parse(o.string("color")),
                    width = ZoomValue.parse(o["width"], 1.0),
                    opacity = ZoomValue.parse(o["opacity"], 1.0),
                    dash = (o["dash"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: throw IllegalArgumentException("dash must be numbers") },
                    casingColor = (o["casing-color"] as? String)?.let { StyleColor.parse(it) },
                    casingWidth = ZoomValue.parse(o["casing-width"], 0.0),
                    modifiers = (o["modifiers"] as? List<*>).orEmpty().map { raw ->
                        val m = raw.asObject("modifier")
                        LineModifier(
                            filter = Filter.parse(m["filter"] ?: throw IllegalArgumentException("modifier needs a filter")),
                            width = (m["width"] as? Number)?.toDouble() ?: 1.0,
                            opacity = (m["opacity"] as? Number)?.toDouble() ?: 1.0,
                        )
                    },
                    ticks = (o["ticks"] as? Map<*, *>)?.let { t ->
                        Ticks(
                            spacing = ZoomValue.parse(t["spacing"], 6.0),
                            length = ZoomValue.parse(t["length"], 4.0),
                            width = ZoomValue.parse(t["width"], 1.0),
                            side = when (val s = t["side"] as? String ?: "right") {
                                "right" -> TickSide.RIGHT
                                "left" -> TickSide.LEFT
                                "downhill" -> TickSide.DOWNHILL
                                else -> throw IllegalArgumentException("ticks side must be right, left or downhill, not '$s'")
                            },
                        )
                    },
                )
                "symbol" -> StyleLayer.Symbol(
                    id, select,
                    text = o.string("text"),
                    placement = when (val p = o["placement"] as? String ?: "point") {
                        "point" -> Placement.POINT
                        "line" -> Placement.LINE
                        else -> throw IllegalArgumentException("placement must be point or line, not '$p'")
                    },
                    size = ZoomValue.parse(o["size"], 12.0),
                    color = StyleColor.parse(o.string("color")),
                    haloColor = StyleColor.parse(o["halo-color"] as? String ?: "#f6f1e4cc"),
                    haloWidth = (o["halo-width"] as? Number)?.toDouble() ?: 1.5,
                    marker = (o["marker"] as? Map<*, *>)?.let { m ->
                        Marker(
                            shape = when (val s = m["shape"] as? String ?: "circle") {
                                "circle" -> MarkerShape.CIRCLE
                                "triangle" -> MarkerShape.TRIANGLE
                                "square" -> MarkerShape.SQUARE
                                "diamond" -> MarkerShape.DIAMOND
                                "house" -> MarkerShape.HOUSE
                                "star" -> MarkerShape.STAR
                                else -> throw IllegalArgumentException("marker shape must be one of ${MarkerShape.entries.map { it.name.lowercase() }}, not '$s'")
                            },
                            size = (m["size"] as? Number)?.toDouble() ?: 8.0,
                            color = StyleColor.parse(m["color"] as? String ?: "#333333"),
                            text = m["text"] as? String,
                            textColor = StyleColor.parse(m["text-color"] as? String ?: "#ffffff"),
                        )
                    },
                    spacing = (o["spacing"] as? Number)?.toDouble() ?: 400.0,
                    sortKey = o["sort"] as? String,
                    italic = o["italic"] as? Boolean ?: false,
                    textOptional = o["text-optional"] as? Boolean ?: false,
                )
                else -> throw IllegalArgumentException("unknown type '$type' (fill, line, symbol, contours)")
            }
        }

        private fun geometryType(s: String) = when (s) {
            "point" -> GeometryType.POINT
            "line" -> GeometryType.LINESTRING
            "polygon" -> GeometryType.POLYGON
            else -> throw IllegalArgumentException("geometry must be point, line or polygon, not '$s'")
        }

        private fun Any?.asObject(what: String): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            return this as? Map<String, Any?> ?: throw IllegalArgumentException("style: $what must be an object")
        }

        private fun Map<String, Any?>.string(key: String): String =
            this[key] as? String ?: throw IllegalArgumentException("missing $key")
    }
}

/** Which features a layer draws: tile layer, filter, zoom range [minZoom, maxZoom), geometry type. */
data class Selector(
    val sourceLayer: String,
    val filter: Filter = Filter.ALL,
    val minZoom: Double = 0.0,
    val maxZoom: Double = 24.0,
    /** Only features of this geometry type; null takes the natural type for the layer kind. */
    val geometry: GeometryType? = null,
) {
    fun visibleAt(zoom: Double): Boolean = zoom >= minZoom && zoom < maxZoom
}

sealed class StyleLayer {
    abstract val id: String

    /** Polygon fill. Overlapping polygons of one layer are filled once (no double tint). */
    data class Fill(override val id: String, val select: Selector, val color: StyleColor, val opacity: ZoomValue) : StyleLayer()

    /**
     * Stroked lines (or polygon outlines). [dash] is on/off lengths in multiples of the line width,
     * like MapLibre's `line-dasharray`. A casing is a solid band [casingWidth] pixels wider on each
     * side, drawn under the line.
     */
    data class Line(
        override val id: String,
        val select: Selector,
        val color: StyleColor,
        val width: ZoomValue,
        val opacity: ZoomValue,
        val dash: List<Double>?,
        val casingColor: StyleColor?,
        val casingWidth: ZoomValue,
        /** Per-feature width and opacity factors; every modifier whose filter matches applies. */
        val modifiers: List<LineModifier> = emptyList(),
        /** Short strokes on one side of the line (cliffs), drawn in the line color. */
        val ticks: Ticks? = null,
    ) : StyleLayer()

    /**
     * Labels, optionally with a marker. [text] is a template: `{key}` inserts a property, `{elevation}`
     * inserts the `ele` property (meters) in the map's units with the unit symbol, and `\n` splits
     * lines. A line whose placeholders are all missing is dropped; a label with no lines left is not drawn.
     * [size] is the text height in pixels at text scale 1 (the user's text scale multiplies it, the
     * marker size and [haloWidth], the halo's reach outside the glyphs). [spacing] is the minimum
     * distance in pixels between two labels with the same text in this layer (and the repeat
     * distance along a line). [sortKey] orders features within the layer: `rank` places low ranks
     * first, `-ele` places high values first. With [textOptional] (MapLibre's `text-optional`) a
     * feature with a [marker] still gets the marker when its text is empty or does not fit.
     */
    data class Symbol(
        override val id: String,
        val select: Selector,
        val text: String,
        val placement: Placement,
        val size: ZoomValue,
        val color: StyleColor,
        val haloColor: StyleColor,
        val haloWidth: Double,
        val marker: Marker?,
        val spacing: Double,
        val sortKey: String?,
        val italic: Boolean,
        val textOptional: Boolean = false,
    ) : StyleLayer()

    /** Where the contour lines draw relative to the other layers. */
    data class Contours(override val id: String) : StyleLayer()
}

enum class Placement { POINT, LINE }

enum class MarkerShape { CIRCLE, TRIANGLE, SQUARE, DIAMOND, HOUSE, STAR }

/**
 * A point marker drawn beside a point label; [size] in pixels before the text scale. [text] is an
 * optional short glyph (one or two characters, such as `P`) drawn inside it in [textColor].
 */
data class Marker(
    val shape: MarkerShape,
    val size: Double,
    val color: StyleColor,
    val text: String? = null,
    val textColor: StyleColor = StyleColor(255, 255, 255),
)

/** Multiplies a line's [width] and [opacity] for the features matching [filter] (data-driven styling). */
data class LineModifier(val filter: Filter, val width: Double, val opacity: Double)

enum class TickSide {
    /** Right of the line's direction: the OSM convention for `natural=cliff` (the drop is on the right). */
    RIGHT,
    LEFT,

    /** Toward lower ground, judged from the DEM by the renderer; [RIGHT] where it cannot tell. */
    DOWNHILL,
}

/** Tick hatching along a line: a tick every [spacing] pixels, [length] long and [width] wide, on [side]. */
data class Ticks(val spacing: ZoomValue, val length: ZoomValue, val width: ZoomValue, val side: TickSide)

/** An sRGB color with alpha, each channel 0..255. */
data class StyleColor(val r: Int, val g: Int, val b: Int, val a: Int = 255) {
    val argb: Int get() = (a shl 24) or (r shl 16) or (g shl 8) or b

    companion object {
        /** `#rgb`, `#rrggbb` or `#rrggbbaa`. */
        fun parse(s: String): StyleColor {
            require(s.startsWith("#")) { "color '$s' must start with #" }
            val hex = s.substring(1)
            fun h(i: Int, len: Int): Int {
                val v = hex.substring(i, i + len).toIntOrNull(16) ?: throw IllegalArgumentException("bad color '$s'")
                return if (len == 1) v * 17 else v
            }
            return when (hex.length) {
                3 -> StyleColor(h(0, 1), h(1, 1), h(2, 1))
                6 -> StyleColor(h(0, 2), h(2, 2), h(4, 2))
                8 -> StyleColor(h(0, 2), h(2, 2), h(4, 2), h(6, 2))
                else -> throw IllegalArgumentException("bad color '$s' (use #rgb, #rrggbb or #rrggbbaa)")
            }
        }
    }
}

/**
 * A number that may depend on the map zoom: either a constant, or stops `[[zoom, value], ...]`
 * interpolated linearly and clamped at the ends (MapLibre's `interpolate linear zoom`).
 */
class ZoomValue(val stops: List<Pair<Double, Double>>) {
    init {
        require(stops.isNotEmpty()) { "zoom value needs at least one stop" }
        require(stops.zipWithNext().all { (a, b) -> a.first < b.first }) { "zoom stops must increase" }
    }

    fun at(zoom: Double): Double {
        if (zoom <= stops.first().first) return stops.first().second
        if (zoom >= stops.last().first) return stops.last().second
        val i = stops.indexOfLast { it.first <= zoom }
        val (z0, v0) = stops[i]
        val (z1, v1) = stops[i + 1]
        return v0 + (v1 - v0) * (zoom - z0) / (z1 - z0)
    }

    override fun toString() = if (stops.size == 1) stops[0].second.toString() else stops.toString()

    companion object {
        fun constant(v: Double) = ZoomValue(listOf(0.0 to v))

        fun parse(raw: Any?, default: Double): ZoomValue = when (raw) {
            null -> constant(default)
            is Number -> constant(raw.toDouble())
            is List<*> -> ZoomValue(raw.map { stop ->
                val pair = stop as? List<*>
                val z = (pair?.getOrNull(0) as? Number)?.toDouble()
                val v = (pair?.getOrNull(1) as? Number)?.toDouble()
                require(pair?.size == 2 && z != null && v != null) { "zoom stop must be [zoom, value], got $stop" }
                z to v
            })
            else -> throw IllegalArgumentException("expected a number or [[zoom, value], ...], got $raw")
        }
    }
}

/**
 * A feature filter: every condition must hold. In JSON it is an object keyed by property name:
 * - `"class": ["path", "track"]`: the property's value is one of these (numbers compare by value);
 * - `"!class": ["motorway"]`: the property is absent or not one of these;
 * - `"name": true` / `"name": false`: the property is present / absent;
 * - `"rank": {"<=": 3}`: numeric comparison (`<`, `<=`, `>`, `>=`); fails when absent or not numeric.
 */
class Filter(val conditions: List<Condition>) {
    fun matches(props: Map<String, Value>): Boolean = conditions.all { it.matches(props) }

    sealed class Condition {
        abstract fun matches(props: Map<String, Value>): Boolean

        data class In(val key: String, val values: Set<String>, val negate: Boolean) : Condition() {
            override fun matches(props: Map<String, Value>): Boolean {
                val v = props[key]?.let { valueKey(it) }
                val hit = v != null && v in values
                return hit != negate
            }
        }

        data class Has(val key: String, val present: Boolean) : Condition() {
            override fun matches(props: Map<String, Value>) = (key in props) == present
        }

        data class Compare(val key: String, val op: String, val value: Double) : Condition() {
            override fun matches(props: Map<String, Value>): Boolean {
                val v = props[key]?.asDouble() ?: return false
                return when (op) {
                    "<" -> v < value
                    "<=" -> v <= value
                    ">" -> v > value
                    ">=" -> v >= value
                    else -> false
                }
            }
        }
    }

    companion object {
        val ALL = Filter(emptyList())

        fun parse(raw: Any?): Filter {
            if (raw == null) return ALL
            val obj = raw as? Map<*, *> ?: throw IllegalArgumentException("filter must be an object")
            return Filter(obj.map { (k, v) ->
                val key = k as String
                val negate = key.startsWith("!")
                val name = key.removePrefix("!")
                when (v) {
                    is Boolean -> Condition.Has(name, v != negate)
                    is List<*> -> Condition.In(name, v.map { jsonKey(it) }.toSet(), negate)
                    is String, is Number -> Condition.In(name, setOf(jsonKey(v)), negate)
                    is Map<*, *> -> {
                        require(!negate && v.size == 1) { "comparison filter on '$key' takes one operator" }
                        val (op, num) = v.entries.first()
                        require(op in setOf("<", "<=", ">", ">=") && num is Number) { "bad comparison on '$key': $v" }
                        Condition.Compare(name, op as String, num.toDouble())
                    }
                    else -> throw IllegalArgumentException("bad filter value for '$key': $v")
                }
            })
        }

        private fun jsonKey(v: Any?): String = when (v) {
            is String -> v
            is Number -> numberKey(v.toDouble())
            is Boolean -> v.toString()
            else -> throw IllegalArgumentException("filter values must be strings, numbers or booleans, got $v")
        }

        /** Canonical text of a property value for equality tests: whole numbers print without a fraction. */
        fun valueKey(v: Value): String = v.asDouble()?.let { numberKey(it) } ?: v.asText()

        private fun numberKey(d: Double): String =
            if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }
}
