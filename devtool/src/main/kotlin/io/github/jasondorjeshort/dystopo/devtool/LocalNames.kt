package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.server.LabelText
import io.github.jasondorjeshort.dystopo.style.OsmStyle
import io.github.jasondorjeshort.dystopo.style.Placement
import io.github.jasondorjeshort.dystopo.style.SourceTile
import io.github.jasondorjeshort.dystopo.style.StyleLayer
import io.github.jasondorjeshort.dystopo.style.TextTemplate

/**
 * T92: the second name line of the PNG renderer, the local name in another script under the English or Latin
 * one, by the phone style's rule (`MapLibreStyle.LABEL_NAME`): `name:nonlatin`, else `name` when it starts
 * outside the Latin blocks; shown only when it differs from the first line ([TextTemplate.name]) and the
 * bundled fonts can draw it ([LabelText.supported]; the phone asks `is-supported-script`).
 *
 * Done on the devtool side so the shared style and scene stay as they are: [withLocalNames] adds the line's text
 * to each feature as the property [KEY], and [style] turns `{name}` in point labels into `{name}` plus a `{KEY}`
 * line (a line whose placeholders are all missing is dropped, so features without one are unchanged). Line
 * labels (roads, rivers along their line) keep one line, as curved text, drawn from [LINE_KEY]: the same name in
 * visual order, since a curved label is laid out one character at a time from the left (a Hebrew river name read
 * backwards before; T107: Arabic letters are put in their joined forms first, LabelText.shapeArabic).
 */
object LocalNames {
    const val KEY = "dystopo:local"

    /** The first line of a line-placed (curved) label in visual order ([LabelText.visualOrder]); set on every named feature. */
    const val LINE_KEY = "dystopo:line"

    /** True when [name] starts with a letter outside the Latin blocks: U+0370 and up, except Latin Extended Additional (U+1E00..U+1EFF). */
    fun nonLatin(name: String): Boolean {
        val c = name.firstOrNull()?.code ?: return false
        return c >= 0x370 && (c < 0x1E00 || c >= 0x1F00)
    }

    /** The second line for a feature's properties, or null. */
    fun localLine(props: Map<String, Value>): String? {
        val primary = TextTemplate.name(props) ?: return null
        val local = props["name:nonlatin"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: props["name"]?.asText()?.trim()?.takeIf { it.isNotEmpty() && nonLatin(it) }
            ?: return null
        return local.takeIf { it != primary && LabelText.supported(it) }
    }

    /**
     * [tiles] with [KEY] on every feature that has a second line and [LINE_KEY] on every named feature; the rest
     * untouched. Returns the tiles and how many features got a second line.
     */
    fun withLocalNames(tiles: List<SourceTile>): Pair<List<SourceTile>, Int> {
        var n = 0
        val out = tiles.map { st ->
            var changed = false
            val layers = st.tile.layers.map { layer ->
                var touched = false
                val features = layer.features.map { f ->
                    val line = localLine(f.properties)
                    val visual = TextTemplate.name(f.properties)?.let(LabelText::visualOrder)
                    if (line == null && visual == null) f else {
                        if (line != null) n++
                        touched = true
                        var props = f.properties
                        if (line != null) props = props + (KEY to Value.StringValue(line))
                        if (visual != null) props = props + (LINE_KEY to Value.StringValue(visual))
                        Feature(f.id, props, f.geometry)
                    }
                }
                if (touched) { changed = true; layer.copy(features = features) } else layer
            }
            if (changed) st.copy(tile = st.tile.copy(layers = layers)) else st
        }
        return out to n
    }

    /** [style] with the second line after `{name}` in every point-placed label, and curved labels from [LINE_KEY]. */
    fun style(style: OsmStyle): OsmStyle = OsmStyle(
        style.name,
        style.layers.map { l ->
            when {
                l !is StyleLayer.Symbol || "{name}" !in l.text -> l
                l.placement == Placement.POINT && "{$KEY}" !in l.text -> l.copy(text = l.text.replace("{name}", "{name}\n{$KEY}"))
                l.placement == Placement.LINE -> l.copy(text = l.text.replace("{name}", "{$LINE_KEY}"))
                else -> l
            }
        },
    )
}
