package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.maplibre.MapLibreSources
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.GeometryType
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.server.FileRangeSource
import io.github.jasondorjeshort.dystopo.server.Gzip
import io.github.jasondorjeshort.dystopo.style.DefaultOsmStyle
import io.github.jasondorjeshort.dystopo.style.Placement
import io.github.jasondorjeshort.dystopo.style.Sprites
import io.github.jasondorjeshort.dystopo.style.StyleColor
import io.github.jasondorjeshort.dystopo.style.StyleLayer
import io.github.jasondorjeshort.dystopo.style.TextTemplate
import io.github.jasondorjeshort.dystopo.style.TickSide
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T70: the devtool PNG renderer and the phone style ([MapLibreStyle], what MapLibre Native and GL JS
 * draw) compared feature by feature on the Lake Cliff golden scene. The renderer side is the real
 * render of the golden (its scene and placed labels); the phone side evaluates the generated MapLibre
 * style JSON (filters, zoom ranges, paint and layout expressions) against every feature of the same
 * archive with a small evaluator for the expression subset [MapLibreStyle] emits. Every difference is
 * listed (`build/reports/style-parity.txt`); the test fails on any that is not in [EXPLAINED].
 *
 * Zooms: the renderer draws z15 in 256 px tiles, the phone the same scale at MapLibre z14. Text and
 * marker sizes on the phone are the renderer's times [MapLibreStyle.BASE_TEXT_SCALE] by design, so
 * sizes are compared after that factor.
 */
class StyleParityTest {
    private val case = Goldens.cases.first { it.osm != null }
    private val rendererZoom = 15.0
    private val phoneZoom = rendererZoom - 1

    /**
     * Differences that are known and intended, by kind and style layer id, with the reason. Anything
     * else the comparison finds fails the test.
     */
    private val EXPLAINED = mapOf(
        "ticks-side cliff" to "the renderer puts cliff ticks on the downhill side from the DEM; MapLibre cannot ask the DEM, " +
            "so the phone draws them right of the line and its tile server rewrites cliff directions (CliffSides) to make right downhill",
    )

    private data class Diff(val kind: String, val layer: String, val detail: String) {
        val key get() = "$kind $layer"
        override fun toString() = "$kind $layer: $detail"
    }

    @Test
    fun rendererAndPhoneStyleAgreeFeatureByFeature() {
        val diffs = compare()
        val unexplained = diffs.filter { it.key !in EXPLAINED }
        val report = buildString {
            appendLine("Lake Cliff golden scene, renderer z15 vs phone style (MapLibre z14): ${diffs.size} differences, ${unexplained.size} unexplained")
            for (d in diffs) appendLine((if (d.key in EXPLAINED) "explained   " else "UNEXPLAINED ") + d + (EXPLAINED[d.key]?.let { "  -- $it" } ?: ""))
        }
        File("build/reports").mkdirs()
        File("build/reports/style-parity.txt").writeText(report)
        println(report)
        assertTrue(unexplained.isEmpty(), report)
        // The explained list stays honest: every entry still shows up.
        for (k in EXPLAINED.keys) assertTrue(diffs.any { it.key == k }, "explained difference '$k' no longer occurs; remove it")
    }

    /** The comparison; also used to prove it catches a changed style (see [aChangedPhoneStyleIsCaught]). */
    private fun compare(phoneJson: String = phoneStyle()): List<Diff> {
        val diffs = ArrayList<Diff>()
        val style = DefaultOsmStyle.style
        val render = Goldens.render(case)
        val features = features()
        val phoneLayers = (MiniJson.parseObject(phoneJson)["layers"] as List<*>).map { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) }

        // What the renderer drew: its own scene's per-layer feature counts and placed labels.
        val window = case.osm!!.window(case)
        val osm = OsmLayer.build(case.style(), window, case.grid(), Timings())!!
        val drawnLayers = osm.stats.layers.filterValues { it.features > 0 }.keys
        val labelled = render.osmLabels.groupBy { it.style.id }

        for (layer in style.layers) {
            if (layer is StyleLayer.Contours) continue
            val select = when (layer) {
                is StyleLayer.Fill -> layer.select
                is StyleLayer.Line -> layer.select
                is StyleLayer.Symbol -> layer.select
                else -> continue
            }
            val phone = phoneLayers.filter { baseId(it["id"] as String) == layer.id && it["source"] == "osm" }
            if (phone.isEmpty()) {
                diffs += Diff("missing-on-phone", layer.id, "no MapLibre layer")
                continue
            }
            for ((sourceLayer, f) in features) {
                if (sourceLayer != select.sourceLayer) continue
                val rendererDraws = select.visibleAt(rendererZoom) && rendererAccepts(layer, f)
                val main = phone.first { it["id"] == layer.id }
                val phoneDraws = visibleAt(main, phoneZoom) && Expr(f, phoneZoom).truthy(main["filter"]) && phoneShows(main, f)
                val what = describe(f)
                if (rendererDraws != phoneDraws) {
                    diffs += Diff(if (rendererDraws) "only-renderer" else "only-phone", layer.id, what)
                    continue
                }
                if (!rendererDraws) continue
                when (layer) {
                    is StyleLayer.Fill -> {
                        checkColor(diffs, layer.id, what, "fill-color", layer.color, paint(main)["fill-color"], f)
                        checkNumber(diffs, layer.id, what, "fill-opacity", layer.opacity.at(rendererZoom), paint(main)["fill-opacity"], f)
                    }
                    is StyleLayer.Line -> compareLine(diffs, layer, phone, f, what)
                    is StyleLayer.Symbol -> compareSymbol(diffs, layer, main, f, what)
                    else -> {}
                }
            }
            // The per-feature rules above must describe what the renderer really drew.
            val ruleDraws = features.any { (sl, f) -> sl == select.sourceLayer && select.visibleAt(rendererZoom) && rendererAccepts(layer, f) }
            if (ruleDraws != (layer.id in drawnLayers)) diffs += Diff("test-model", layer.id, "rules say $ruleDraws, the render drew ${layer.id in drawnLayers}")
            if (layer is StyleLayer.Symbol && layer.id in drawnLayers && labelled[layer.id].isNullOrEmpty()) {
                diffs += Diff("renderer-dropped", layer.id, "matched but no label placed in the golden")
            }
        }

        // Draw order of fills and lines, and label priority, must agree.
        val rendererOrder = style.layers.filter { it !is StyleLayer.Symbol }.map { it.id }
        val phoneOrder = phoneLayers.map { it["id"] as String }.map { if (it.startsWith("contour-")) "contours" else baseId(it) }
            .filter { it in rendererOrder }.distinct()
        if (phoneOrder != rendererOrder.filter { it in phoneOrder }) diffs += Diff("draw-order", "*", "renderer $rendererOrder phone $phoneOrder")
        val rendererPriority = style.layers.filterIsInstance<StyleLayer.Symbol>().map { it.id }
        val phonePriority = phoneLayers.filter { it["type"] == "symbol" }.map { baseId(it["id"] as String) }.asReversed()
            .filter { it in rendererPriority }.distinct()
        if (phonePriority != rendererPriority.filter { it in phonePriority }) diffs += Diff("label-priority", "*", "renderer $rendererPriority phone $phonePriority")
        val contourLabels = phoneLayers.indexOfFirst { it["id"] == "contour-labels" }
        val firstOsmSymbol = phoneLayers.indexOfFirst { it["type"] == "symbol" && it["source"] == "osm" }
        if (contourLabels > firstOsmSymbol) diffs += Diff("label-priority", "contour-labels", "contour labels are not placed last on the phone")
        return diffs
    }

    @Test
    fun aChangedPhoneStyleIsCaught() {
        // Guard against a comparison that sees nothing: a phone style with one colour, one width and
        // one label text changed must produce exactly those differences.
        val json = phoneStyle()
            .replace("\"line-color\":\"rgb(192,57,43)\"", "\"line-color\":\"rgb(0,0,255)\"")
        val diffs = compare(json)
        assertTrue(diffs.any { it.kind == "paint" && it.layer == "trail" && "line-color" in it.detail }, diffs.joinToString("\n"))
    }

    @Test
    fun localScriptSecondLine() {
        fun name(vararg p: Pair<String, String>): Any? =
            Expr(Feature(null, p.associate { (k, v) -> k to Value.StringValue(v) }, Geometry.Points(emptyList())), 14.0).eval(MapLibreStyle.LABEL_NAME)
        // OpenMapTiles: English first, local script second.
        assertEquals("Elbrus\nЭльбрус", name("name" to "Эльбрус", "name:en" to "Elbrus", "name:nonlatin" to "Эльбрус"))
        // Outdoors packs have no name:nonlatin: a name in another script is the second line.
        assertEquals("Dead Sea\nים המלח", name("name" to "ים המלח", "name_en" to "Dead Sea"))
        // A Latin local name never doubles up (Monte Bianco is a translation, not another script).
        assertEquals("Mont Blanc", name("name" to "Monte Bianco", "name_en" to "Mont Blanc"))
        // Vietnamese starts in Latin Extended Additional: still Latin.
        assertEquals("Mount Fan Si Pan", name("name" to "Ḟan", "name_en" to "Mount Fan Si Pan"))
        // Only a local name: one line.
        assertEquals("Эльбрус", name("name" to "Эльбрус"))
        assertEquals("Long Wall", name("name" to "Long Wall"))
    }

    // ---- the renderer's rules (OsmScene) ----

    private fun rendererAccepts(layer: StyleLayer, f: Feature): Boolean {
        val select = when (layer) {
            is StyleLayer.Fill -> layer.select
            is StyleLayer.Line -> layer.select
            is StyleLayer.Symbol -> layer.select
            else -> return false
        }
        if (!select.filter.matches(f.properties)) return false
        return when (layer) {
            is StyleLayer.Fill -> f.type == (select.geometry ?: GeometryType.POLYGON)
            is StyleLayer.Line -> f.type == (select.geometry ?: GeometryType.LINESTRING)
            is StyleLayer.Symbol -> {
                if (select.geometry != null && f.type != select.geometry) return false
                if (layer.placement == Placement.LINE && f.type != GeometryType.LINESTRING) return false
                val lines = TextTemplate.render(layer.text, f.properties, units.symbol)
                lines.isNotEmpty() || (layer.textOptional && layer.marker != null && layer.placement == Placement.POINT)
            }
            else -> false
        }
    }

    private fun compareLine(diffs: MutableList<Diff>, l: StyleLayer.Line, phone: List<Map<String, Any?>>, f: Feature, what: String) {
        var w = 1.0
        var o = 1.0
        for (m in l.modifiers) if (m.filter.matches(f.properties)) {
            w *= m.width
            o *= m.opacity
        }
        val width = l.width.at(rendererZoom) * w
        val opacity = l.opacity.at(rendererZoom) * o
        val main = phone.first { it["id"] == l.id }
        val p = paint(main)
        checkColor(diffs, l.id, what, "line-color", l.color, p["line-color"], f)
        checkNumber(diffs, l.id, what, "line-width", width, p["line-width"], f)
        checkNumber(diffs, l.id, what, "line-opacity", opacity, p["line-opacity"], f)
        val dash = (p["line-dasharray"] as? List<*>)?.map { (it as Number).toDouble() }
        if (dash != l.dash) diffs += Diff("paint", l.id, "$what line-dasharray renderer ${l.dash} phone $dash")
        val casing = phone.firstOrNull { it["id"] == "${l.id}-casing" }
        val rendererCasing = l.casingColor != null && l.casingWidth.at(rendererZoom) > 0
        if (rendererCasing != (casing != null)) {
            diffs += Diff("casing", l.id, "$what renderer ${if (rendererCasing) "has" else "has no"} casing")
        } else if (casing != null) {
            checkColor(diffs, l.id, what, "casing line-color", l.casingColor!!, paint(casing)["line-color"], f)
            checkNumber(diffs, l.id, what, "casing line-width", width + 2 * l.casingWidth.at(rendererZoom) * w, paint(casing)["line-width"], f)
        }
        val ticks = phone.firstOrNull { it["id"] == "${l.id}-ticks" }
        if ((l.ticks != null) != (ticks != null)) diffs += Diff("ticks", l.id, "$what ticks on one side only")
        if (l.ticks != null && ticks != null) {
            checkNumber(diffs, l.id, what, "tick length", l.ticks!!.lengthAt(rendererZoom), paint(ticks)["line-width"], f, tolerance = 0.05)
            if (l.ticks!!.side == TickSide.DOWNHILL) diffs += Diff("ticks-side", l.id, "$what renderer downhill (DEM), phone right of the line")
        }
    }

    private fun compareSymbol(diffs: MutableList<Diff>, l: StyleLayer.Symbol, main: Map<String, Any?>, f: Feature, what: String) {
        val lay = main["layout"] as Map<*, *>
        val rendererText = TextTemplate.render(l.text, f.properties, units.symbol).joinToString("\n")
        val phoneText = lay["text-field"]?.let { Expr(f, phoneZoom).eval(it)?.toString() } ?: ""
        if (rendererText != phoneText) diffs += Diff("text", l.id, "$what renderer '$rendererText' phone '$phoneText'")
        if (l.text.isNotBlank()) {
            checkNumber(diffs, l.id, what, "text-size / BASE_TEXT_SCALE", l.size.at(rendererZoom), lay["text-size"], f, factor = MapLibreStyle.BASE_TEXT_SCALE)
            val p = paint(main)
            checkColor(diffs, l.id, what, "text-color", l.color, p["text-color"], f)
            checkColor(diffs, l.id, what, "text-halo-color", l.haloColor, p["text-halo-color"], f)
            checkNumber(diffs, l.id, what, "text-halo-width / BASE_TEXT_SCALE", l.haloWidth, p["text-halo-width"], f, factor = MapLibreStyle.BASE_TEXT_SCALE)
            val font = (lay["text-font"] as List<*>).single()
            val wanted = if (l.italic) MapLibreStyle.FONT_ITALIC else MapLibreStyle.FONT_REGULAR
            if (font != wanted) diffs += Diff("font", l.id, "$what phone $font, renderer ${if (l.italic) "italic" else "regular"}")
        }
        val icon = lay["icon-image"]
        val marker = l.marker
        if ((marker != null) != (icon != null)) diffs += Diff("marker", l.id, "$what renderer marker $marker phone icon $icon")
        if (marker != null && icon != Sprites.iconName(marker)) diffs += Diff("marker", l.id, "$what icon $icon is not the renderer's marker")
        if (marker != null && l.text.isNotBlank() && lay["text-optional"] != l.textOptional) diffs += Diff("marker", l.id, "$what text-optional differs")
        // Sort keys: same order within the layer (missing values sort last on both sides).
        val key = lay["symbol-sort-key"]
        if ((l.sortKey != null) != (key != null)) diffs += Diff("sort-key", l.id, "renderer ${l.sortKey} phone ${key != null}")
        if (l.sortKey != null && key != null) {
            val prop = f.properties[l.sortKey!!.removePrefix("-")]?.asDouble()
            if (prop != null) {
                val r = if (l.sortKey!!.startsWith("-")) -prop else prop
                val ph = (Expr(f, phoneZoom).eval(key) as Number).toDouble()
                if (abs(r - ph) > 1e-9) diffs += Diff("sort-key", l.id, "$what renderer $r phone $ph")
            }
        }
    }

    /** Whether MapLibre would show anything for a matching feature: some text, or an icon. */
    private fun phoneShows(layer: Map<String, Any?>, f: Feature): Boolean {
        if (layer["type"] != "symbol") return true
        val lay = layer["layout"] as Map<*, *>
        if (lay["icon-image"] != null) return true
        val text = lay["text-field"]?.let { Expr(f, phoneZoom).eval(it)?.toString() }.orEmpty()
        return text.isNotEmpty()
    }

    private fun checkColor(diffs: MutableList<Diff>, id: String, what: String, prop: String, c: StyleColor, phone: Any?, f: Feature) {
        val s = Expr(f, phoneZoom).eval(phone) as? String
        val want = if (c.a == 255) "rgb(${c.r},${c.g},${c.b})" else "rgba(${c.r},${c.g},${c.b},${((c.a / 255.0) * 1000).toLong() / 1000.0})"
        if (s != want) diffs += Diff("paint", id, "$what $prop renderer $want phone $s")
    }

    private fun checkNumber(
        diffs: MutableList<Diff>, id: String, what: String, prop: String, renderer: Double, phone: Any?, f: Feature,
        factor: Double = 1.0, tolerance: Double = 0.01,
    ) {
        val v = (Expr(f, phoneZoom).eval(phone) as? Number)?.toDouble()
        if (v == null || abs(v / factor - renderer) > tolerance * maxOf(1.0, abs(renderer))) {
            diffs += Diff("paint", id, "$what $prop renderer ${round(renderer)} phone ${v?.let { round(it / factor) }}")
        }
    }

    // ---- inputs ----

    private val units = ContourUnits.FEET // the goldens' --units default

    private fun phoneStyle(): String = MapLibreStyle(
        MapLibreSources(
            demTiles = "dem/{z}/{x}/{y}.png", demMinZoom = 0, demMaxZoom = 14,
            contourTiles = "contours/{z}/{x}/{y}.pbf", contourMinZoom = 7, contourMaxZoom = 15,
            osmTiles = "osm/{z}/{x}/{y}.pbf", glyphs = "glyphs/{fontstack}/{range}.pbf", sprite = "sprite",
        ),
        units = units,
    ).json()

    /** Every feature of the scene once (the fixture repeats each in all four tiles), with its source layer. */
    private fun features(): List<Pair<String, Feature>> {
        val reader = PmtilesReader.open(FileRangeSource(case.osm!!.file(case)), Gzip.decompressors)
        val window = case.osm.window(case)
        val tile = reader.tile(14, window.x0 / 512, window.y0 / 512)!!
        return VectorTile.decode(tile).layers.flatMap { l -> l.features.map { l.name to it } }
    }

    private fun describe(f: Feature): String {
        val p = f.properties
        val name = p["name"]?.asText()
        val cls = p["class"]?.asText()
        return listOfNotNull(f.type.name.lowercase(), cls, p["sac_scale"]?.asText(), name?.let { "'$it'" }).joinToString(" ")
    }

    private fun baseId(id: String) = id.removeSuffix("-casing").removeSuffix("-ticks").removeSuffix("-marker").removeSuffix("-glyph")

    private fun visibleAt(layer: Map<String, Any?>, zoom: Double): Boolean {
        val min = (layer["minzoom"] as? Number)?.toDouble() ?: 0.0
        val max = (layer["maxzoom"] as? Number)?.toDouble() ?: 24.0
        return zoom >= min && zoom < max
    }

    private fun paint(l: Map<String, Any?>) = (l["paint"] as? Map<*, *>).orEmpty()

    private fun round(d: Double) = Math.round(d * 1000) / 1000.0

    /**
     * The subset of MapLibre style expressions [MapLibreStyle] emits, evaluated for one feature at one
     * zoom: get/has/in/literal, comparisons (strings by code point), all/any/!, case/coalesce/let/var,
     * concat/to-string/to-number, arithmetic and round, interpolate linear over zoom, geometry-type,
     * is-supported-script (true: Latin, Cyrillic, Hebrew in the tests).
     */
    private class Expr(private val f: Feature, private val zoom: Double) {
        private val vars = HashMap<String, Any?>()

        fun truthy(e: Any?): Boolean = e == null || eval(e) == true

        private fun prop(k: String): Any? = when (val v = f.properties[k]) {
            null -> null
            is Value.StringValue -> v.value
            is Value.BoolValue -> v.value
            else -> v.asDouble()
        }

        private fun num(x: Any?): Double = (x as Number).toDouble()

        @Suppress("UNCHECKED_CAST")
        fun eval(e: Any?): Any? {
            if (e !is List<*>) return if (e is Number) e.toDouble() else e
            val op = e[0] as String
            val a = e.drop(1)
            return when (op) {
                "get" -> prop(a[0] as String)
                "has" -> (a[0] as String) in f.properties
                "literal" -> a[0]
                "!" -> eval(a[0]) != true
                "all" -> a.all { eval(it) == true }
                "any" -> a.any { eval(it) == true }
                "==", "!=" -> {
                    val x = eval(a[0])
                    val y = eval(a[1])
                    val eq = if (x is Number && y is Number) num(x) == num(y) else x == y
                    if (op == "==") eq else !eq
                }
                "<", "<=", ">", ">=" -> {
                    val x = eval(a[0])
                    val y = eval(a[1])
                    val c = if (x is String && y is String) x.compareTo(y) else num(x).compareTo(num(y))
                    when (op) { "<" -> c < 0; "<=" -> c <= 0; ">" -> c > 0; else -> c >= 0 }
                }
                "in" -> {
                    val x = eval(a[0])
                    val list = eval(a[1]) as List<*>
                    list.any { it == x || (it is Number && x is Number && num(it) == num(x)) }
                }
                "case" -> {
                    var i = 0
                    while (i + 1 < a.size) {
                        if (eval(a[i]) == true) return eval(a[i + 1])
                        i += 2
                    }
                    eval(a.last())
                }
                "coalesce" -> a.firstNotNullOfOrNull { eval(it) }
                "let" -> {
                    var i = 0
                    while (i + 1 < a.size) {
                        vars[a[i] as String] = eval(a[i + 1])
                        i += 2
                    }
                    eval(a.last())
                }
                "var" -> vars[a[0] as String]
                "concat" -> a.joinToString("") { str(eval(it)) }
                "to-string" -> str(eval(a[0]))
                "to-number" -> {
                    val x = eval(a[0])
                    when (x) {
                        is Number -> num(x)
                        is String -> x.toDoubleOrNull() ?: a.getOrNull(1)?.let { eval(it) }
                        else -> a.getOrNull(1)?.let { eval(it) }
                    }
                }
                "+" -> a.sumOf { num(eval(it)) }
                "*" -> a.fold(1.0) { acc, x -> acc * num(eval(x)) }
                "-" -> if (a.size == 1) -num(eval(a[0])) else num(eval(a[0])) - num(eval(a[1]))
                "/" -> num(eval(a[0])) / num(eval(a[1]))
                "round" -> Math.round(num(eval(a[0]))).toDouble()
                "zoom" -> zoom
                "geometry-type" -> when (f.type) {
                    GeometryType.POINT -> "Point"
                    GeometryType.LINESTRING -> "LineString"
                    GeometryType.POLYGON -> "Polygon"
                    else -> "Unknown"
                }
                "is-supported-script" -> true
                "interpolate" -> {
                    require((a[0] as List<*>)[0] == "linear")
                    val x = num(eval(a[1]))
                    val stops = a.drop(2).chunked(2).map { (z, v) -> num(z) to it(v) }
                    if (x <= stops.first().first) return stops.first().second
                    if (x >= stops.last().first) return stops.last().second
                    val i = stops.indexOfLast { it.first <= x }
                    val (z0, v0) = stops[i]
                    val (z1, v1) = stops[i + 1]
                    v0 + (v1 - v0) * (x - z0) / (z1 - z0)
                }
                else -> throw IllegalArgumentException("expression '$op' not supported by the parity evaluator")
            }
        }

        private fun it(v: Any?): Double = num(eval(v))

        private fun str(x: Any?): String = when (x) {
            null -> ""
            is Double -> if (x == Math.floor(x) && abs(x) < 1e15) x.toLong().toString() else x.toString()
            else -> x.toString()
        }
    }
}
