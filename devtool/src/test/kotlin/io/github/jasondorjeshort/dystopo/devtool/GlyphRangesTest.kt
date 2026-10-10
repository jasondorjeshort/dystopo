package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** T70: tools/android-push-pack.ps1 pushes exactly the glyph ranges the style needs (MapLibreStyle.GLYPH_RANGES). */
class GlyphRangesTest {
    @Test
    fun pushScriptRangesMatchTheStyle() {
        val script = File("../tools/android-push-pack.ps1").readText()
        val line = Regex("""\${'$'}ranges = @\(([^)]*)\)""").find(script)!!.groupValues[1]
        val ranges = Regex("'([0-9]+-[0-9]+)'").findAll(line).map { it.groupValues[1] }.toList()
        assertEquals(MapLibreStyle.GLYPH_RANGES, ranges)
    }
}
