package io.github.jasondorjeshort.dystopo.style

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpritesTest {
    @Test
    fun oneIconPerDistinctMarker() {
        val icons = Sprites.icons()
        val markers = DefaultOsmStyle.style.layers.filterIsInstance<StyleLayer.Symbol>().mapNotNull { it.marker }
        assertEquals(markers.toSet().size, icons.size, "identical markers share an icon")
        assertEquals(icons.size, icons.map { it.name }.toSet().size, "unique names")
        for (m in markers) assertTrue(icons.any { it.name == Sprites.iconName(m) && it.marker == m })
    }

    @Test
    fun namesSpellShapeColourSizeAndGlyph() {
        assertEquals("marker-house-6b3a1a-9", Sprites.iconName(Marker(MarkerShape.HOUSE, 9.0, StyleColor.parse("#6b3a1a"))))
        assertEquals("marker-square-2b5fa8-8-P", Sprites.iconName(Marker(MarkerShape.SQUARE, 8.0, StyleColor.parse("#2b5fa8"), text = "P")))
        assertEquals("marker-circle-1f78c8-5.5", Sprites.iconName(Marker(MarkerShape.CIRCLE, 5.5, StyleColor.parse("#1f78c8"))))
    }

    @Test
    fun layoutPacksWithoutOverlapAndIndexMatches() {
        for (ratio in Sprites.RATIOS) {
            val sheet = Sprites.layout(pixelRatio = ratio)
            val boxes = sheet.icons
            assertEquals(Sprites.icons().size, boxes.size)
            for (b in boxes) {
                assertTrue(b.x >= 0 && b.y >= 0 && b.x + b.side <= sheet.width && b.y + b.side <= sheet.height, "${b.icon.name} inside")
                // The star reaches 1.2 r from the centre, plus half the halo stroke.
                assertTrue(b.side / 2.0 >= 1.2 * Sprites.radius(b.icon.marker, ratio) + Sprites.haloWidth(ratio) / 2, "${b.icon.name} room")
            }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                val a = boxes[i]
                val c = boxes[j]
                val apart = a.x + a.side <= c.x || c.x + c.side <= a.x || a.y + a.side <= c.y || c.y + c.side <= a.y
                assertTrue(apart, "${a.icon.name} overlaps ${c.icon.name}")
            }
            val index = MiniJson.parseObject(sheet.json())
            assertEquals(boxes.map { it.icon.name }.toSet(), index.keys)
            @Suppress("UNCHECKED_CAST")
            val first = index.getValue(boxes[0].icon.name) as Map<String, Any?>
            assertEquals(ratio.toDouble(), (first["pixelRatio"] as Number).toDouble())
            assertEquals(boxes[0].side.toDouble(), (first["width"] as Number).toDouble())
        }
        // The 2x sheet is the 1x sheet at twice the pixels (MapLibre picks one by screen density).
        val one = Sprites.layout(pixelRatio = 1).icons.associate { it.icon.name to it.side }
        for (p in Sprites.layout(pixelRatio = 2).icons) assertTrue(p.side in 2 * one.getValue(p.icon.name) - 1..2 * one.getValue(p.icon.name) + 1)
        assertEquals(listOf("sprite.json", "sprite.png", "sprite@2x.json", "sprite@2x.png"), Sprites.FILES)
    }
}
