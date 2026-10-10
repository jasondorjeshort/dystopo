package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.style.Sprites
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpriteSheetsTest {
    @Test
    fun iconsAreDrawnInTheirMarkerColourWithAPaperHalo() {
        for (ratio in Sprites.RATIOS) {
            val sheet = Sprites.layout(pixelRatio = ratio)
            val img = SpriteSheets.render(sheet)
            assertEquals(sheet.width, img.width)
            assertEquals(sheet.height, img.height)
            for (p in sheet.icons) {
                val m = p.icon.marker
                val c = p.side / 2
                // Just below the centre the fill is the marker colour; with a glyph (the P on a square), the
                // square's lower right, clear of the letter.
                val r = Sprites.radius(m, ratio)
                val dx = if (m.text != null) (r * 0.75).toInt() else 0
                val dy = (r * if (m.text != null) 0.75 else 0.3).toInt()
                val argb = img.getRGB(p.x + c + dx, p.y + c + dy)
                val expect = (m.color.r shl 16) or (m.color.g shl 8) or m.color.b
                assertEquals(expect, argb and 0xffffff, "${p.icon.name} fill at ratio $ratio")
                assertEquals(255, argb ushr 24, "${p.icon.name} opaque")
                // The corners are transparent: icons do not bleed into each other.
                assertEquals(0, img.getRGB(p.x, p.y) ushr 24, "${p.icon.name} corner")
            }
            // Some paper-coloured halo pixels exist.
            val h = Sprites.HALO_COLOR
            val paper = (h.r shl 16) or (h.g shl 8) or h.b
            var halo = 0
            for (yy in 0 until img.height) for (xx in 0 until img.width) if (img.getRGB(xx, yy) and 0xffffff == paper) halo++
            assertTrue(halo > 50 * sheet.icons.size / 2, "halo pixels $halo")
        }
    }

    @Test
    fun routeWritesTheSheetOnFirstUseAndRefreshesAStaleOne() {
        val dir = Files.createTempDirectory("sprite").toFile()
        try {
            assertNull(SpriteSheets.route("/style.json", dir))
            val (json, type) = SpriteSheets.route("/sprite@2x.json", dir)!!
            assertEquals("application/json", type)
            for (f in Sprites.FILES) assertTrue(File(dir, f).isFile, f)
            val index = MiniJson.parseObject(json.decodeToString())
            assertEquals(Sprites.icons().map { it.name }.toSet(), index.keys)
            val png = SpriteSheets.route("/sprite.png", dir)!!
            assertEquals("image/png", png.second)
            assertEquals(Sprites.layout(pixelRatio = 1).width, ImageIO.read(png.first.inputStream()).width)
            // A sheet from an older marker table is replaced.
            File(dir, "sprite.json").writeText("{}")
            SpriteSheets.route("/sprite.json", dir)
            assertEquals(Sprites.layout(pixelRatio = 1).json(), File(dir, "sprite.json").readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
