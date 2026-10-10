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

/** Renders and writes sprite sheets; also answers the serve routes `/sprite.json`, `/sprite@2x.png`, ... */
object SpriteSheets {
    /** The sheet image at one pixel ratio. */
    fun render(sheet: Sprites.Sheet): BufferedImage {
        val img = BufferedImage(sheet.width, sheet.height, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val h = Sprites.HALO_COLOR
        val halo = Color(h.r, h.g, h.b, h.a)
        val r = sheet.pixelRatio
        for (p in sheet.icons) {
            val c = p.side / 2.0
            MarkerShapes.draw(
                g2, p.icon.marker, p.x + c, p.y + c, Sprites.radius(p.icon.marker, r) * 2, halo,
                Sprites.haloWidth(r).toFloat(),
            )
        }
        g2.dispose()
        return img
    }

    fun png(sheet: Sprites.Sheet): ByteArray = ByteArrayOutputStream().also { ImageIO.write(render(sheet), "png", it) }.toByteArray()

    /** Writes the sheet's files at every ratio into [dir]; returns each file with its sheet size. */
    fun write(dir: File, style: OsmStyle = DefaultOsmStyle.style): List<Pair<File, Map<String, Any?>>> {
        dir.mkdirs()
        val icons = Sprites.icons(style)
        return Sprites.RATIOS.flatMap { ratio ->
            val sheet = Sprites.layout(icons, ratio)
            val stem = Sprites.fileStem(ratio)
            val info = mapOf("pixelRatio" to ratio, "width" to sheet.width, "height" to sheet.height)
            val json = File(dir, "$stem.json").apply { writeText(sheet.json()) }
            val png = File(dir, "$stem.png").apply { writeBytes(png(sheet)) }
            listOf(json to info, png to info)
        }
    }

    private val lock = Any()

    /**
     * For a serve route: the bytes and content type of `/sprite.json`, `/sprite.png`, `/sprite@2x.json`
     * or `/sprite@2x.png` from [dir], writing the sheet there first when a file is missing or was made
     * from an older marker table (its index differs); null for any other path.
     */
    fun route(path: String, dir: File = File("out")): Pair<ByteArray, String>? {
        val name = path.removePrefix("/")
        if (name !in Sprites.FILES) return null
        val file = File(dir, name)
        synchronized(lock) {
            val index = File(dir, "${Sprites.fileStem(1)}.json")
            val current = index.isFile && index.readText() == Sprites.layout(Sprites.icons(), 1).json()
            if (!current || Sprites.FILES.any { !File(dir, it).isFile }) write(dir)
        }
        return file.readBytes() to (if (name.endsWith(".png")) "image/png" else "application/json")
    }
}
