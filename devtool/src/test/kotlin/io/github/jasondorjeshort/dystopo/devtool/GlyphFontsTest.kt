package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The glyph route serves what tools/android-push-pack.ps1 downloads and pushes, so the script's font
 * list must cover every font the MapLibre style names; a missing one draws no text on the phone.
 */
class GlyphFontsTest {
    @Test
    fun pushPackScriptFetchesEveryStyleFont() {
        val script = File("../tools/android-push-pack.ps1").readText() // tests run in devtool/
        val line = assertNotNull(Regex("""\${'$'}fonts\s*=\s*@\(([^)]*)\)""").find(script), "\$fonts = @(...) in android-push-pack.ps1")
        val fonts = Regex("'([^']+)'").findAll(line.groupValues[1]).map { it.groupValues[1] }.toSet()
        assertEquals(MapLibreStyle.FONTS.toSet(), fonts)
    }
}
