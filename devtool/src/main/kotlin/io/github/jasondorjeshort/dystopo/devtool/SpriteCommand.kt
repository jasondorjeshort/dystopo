package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.style.DefaultOsmStyle
import io.github.jasondorjeshort.dystopo.style.OsmStyle
import io.github.jasondorjeshort.dystopo.style.Sprites
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * `devtool sprite`: writes the MapLibre sprite sheet for the style's point markers ([Sprites]) at 1x
 * and 2x into `out/` (sprite.json, sprite.png, sprite@2x.json, sprite@2x.png), drawn with the PNG
 * renderer's own marker shapes ([MarkerShapes]). `devtool serve` serves the files (making them on
 * first use), tools/android-push-pack.ps1 pushes them to the app.
 */
class SpriteCommand : CliktCommand(name = "sprite") {
    override fun help(context: Context) =
        "Write the MapLibre sprite sheet (marker icons from the style's marker table) at 1x and 2x into --out"

    private val out by option("--out", help = "output directory").default("out")
    private val styleFile by option("--style", help = "OSM style JSON (default: the built-in DefaultOsmStyle)")

    override fun run() {
        val style = styleFile?.let { OsmStyle.parse(File(it).readText()) } ?: DefaultOsmStyle.style
        val started = System.nanoTime()
        val files = SpriteSheets.write(File(out), style)
        val icons = Sprites.icons(style)
        println(
            JsonWriter.write(
                linkedMapOf(
                    "icons" to icons.size,
                    "names" to icons.map { it.name },
                    "files" to files.map { (f, info) -> linkedMapOf("path" to f.path, "bytes" to f.length()) + info },
                    "ms" to (System.nanoTime() - started) / 1_000_000,
                ),
            ),
        )
    }
}
