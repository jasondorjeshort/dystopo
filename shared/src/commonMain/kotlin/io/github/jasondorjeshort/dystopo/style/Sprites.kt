package io.github.jasondorjeshort.dystopo.style

import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import kotlin.math.ceil

/**
 * The MapLibre sprite sheet for the style's point markers: one icon per distinct [Marker] in the style
 * (by default the marker table in [DefaultOsmStyle]), so the phone draws the same shapes as the devtool
 * renderer and MapLibre can collide icons with labels (circle layers never collide).
 *
 * This file is the spec: icon names, sizes and the sheet layout, plus the `sprite.json` index. Drawing
 * the pixels needs a raster library, so `devtool sprite` (SpriteCommand, java.awt, the renderer's own
 * marker shapes) writes `sprite.json` + `sprite.png` and `sprite@2x.json` + `sprite@2x.png` into `out/`;
 * `devtool serve` serves them from there and tools/android-push-pack.ps1 pushes them to the app's
 * `files/sprites/`.
 *
 * Sizes: an icon is [ICON_SCALE] logical pixels per marker-size pixel (plus the paper halo), so the
 * MapLibre style draws it with `icon-size` = its own text scale / [ICON_SCALE] and the bitmap is
 * scaled down, not up, for text scales up to [ICON_SCALE] / 1.35 (the 2x sheet stays sharp on phones).
 * T70: 4.0 (was 2.0, which blurred icons from text scale 1.5 up), so icons stay sharp to about 3.0, the
 * largest text scale the settings allow.
 */
object Sprites {
    /** Logical sprite pixels per marker-size pixel. */
    const val ICON_SCALE = 4.0

    /** Halo stroke width in marker pixels, as the devtool renderer's marker halo. */
    const val HALO = 2.5

    /** The paper colour the halo is drawn in (the labels' halo colour). */
    val HALO_COLOR = StyleColor(0xf6, 0xf1, 0xe4)

    /** File stem: `sprite.json`, `sprite.png`, `sprite@2x.json`, `sprite@2x.png`. */
    const val NAME = "sprite"

    /** The pixel ratios written (MapLibre asks for `@2x` on high-density screens, 1x otherwise). */
    val RATIOS = listOf(1, 2)

    /** The four file names of the sheet. */
    val FILES: List<String> = RATIOS.flatMap { r -> listOf("${fileStem(r)}.json", "${fileStem(r)}.png") }

    fun fileStem(pixelRatio: Int): String = if (pixelRatio == 1) NAME else "$NAME@${pixelRatio}x"

    /** One icon of the sheet. */
    data class Icon(val name: String, val marker: Marker)

    /** An icon placed in a sheet: a [side] x [side] square at ([x], [y]), physical pixels. */
    data class Placed(val icon: Icon, val x: Int, val y: Int, val side: Int)

    /** A laid-out sheet at one pixel ratio. */
    class Sheet(val pixelRatio: Int, val width: Int, val height: Int, val icons: List<Placed>) {
        /** The MapLibre sprite index (`sprite.json`). */
        fun json(): String = JsonWriter.write(
            icons.associate { p ->
                p.icon.name to linkedMapOf("x" to p.x, "y" to p.y, "width" to p.side, "height" to p.side, "pixelRatio" to pixelRatio)
            },
        )
    }

    /**
     * The icon name for a marker: shape, colour, size and glyph, e.g. `marker-house-6b3a1a-9` or
     * `marker-square-2b5fa8-8-P`. Identical markers share one icon. The browser demo builds the same
     * names from its marker table (tools/demo/index.html `iconName`; MarkerTableTest checks).
     */
    fun iconName(m: Marker): String {
        val hex = "${hex2(m.color.r)}${hex2(m.color.g)}${hex2(m.color.b)}"
        val size = if (m.size == kotlin.math.floor(m.size)) m.size.toLong().toString() else m.size.toString()
        return "marker-${m.shape.name.lowercase()}-$hex-$size" + (m.text?.let { "-$it" } ?: "")
    }

    /** The distinct marker icons of [style], in layer order. */
    fun icons(style: OsmStyle = DefaultOsmStyle.style): List<Icon> =
        style.layers.filterIsInstance<StyleLayer.Symbol>().mapNotNull { it.marker }
            .map { Icon(iconName(it), it) }.distinctBy { it.name }

    /** Square side of an icon in physical pixels: room for the star's points (1.2 r) and the halo. */
    fun side(m: Marker, pixelRatio: Int): Int = ceil((m.size * 1.3 + HALO) * ICON_SCALE * pixelRatio).toInt()

    /** Marker radius inside the icon, physical pixels (the devtool renderer's `markerPx / 2`). */
    fun radius(m: Marker, pixelRatio: Int): Double = m.size * ICON_SCALE * pixelRatio / 2

    /** Halo stroke width inside the icon, physical pixels. */
    fun haloWidth(pixelRatio: Int): Double = HALO * ICON_SCALE * pixelRatio

    /** Shelf-packs [icons] into rows at most [maxWidth] physical pixels wide, 1 px apart. */
    fun layout(icons: List<Icon> = icons(), pixelRatio: Int, maxWidth: Int = 256 * pixelRatio): Sheet {
        val placed = ArrayList<Placed>(icons.size)
        var x = 0
        var y = 0
        var rowHeight = 0
        var width = 0
        for (icon in icons) {
            val s = side(icon.marker, pixelRatio)
            if (x > 0 && x + s > maxWidth) {
                x = 0
                y += rowHeight + 1
                rowHeight = 0
            }
            placed += Placed(icon, x, y, s)
            x += s + 1
            rowHeight = maxOf(rowHeight, s)
            width = maxOf(width, x - 1)
        }
        return Sheet(pixelRatio, width.coerceAtLeast(1), (y + rowHeight).coerceAtLeast(1), placed)
    }

    private fun hex2(v: Int): String = v.toString(16).padStart(2, '0')
}
