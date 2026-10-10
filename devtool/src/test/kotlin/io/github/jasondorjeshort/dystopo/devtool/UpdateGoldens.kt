package io.github.jasondorjeshort.dystopo.devtool

import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Regenerates the approved golden images: `./gradlew updateGoldens`. Writes a file only when its
 * bytes change, and prints one JSON object listing what changed.
 */
fun main(args: Array<String>) {
    val dir = args.firstOrNull()?.let(::File) ?: Goldens.sourceDir
    dir.mkdirs()
    val changed = ArrayList<String>()
    val unchanged = ArrayList<String>()
    var totalBytes = 0L
    for (case in Goldens.cases) {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(Goldens.render(case).image, "png", it) }.toByteArray()
        val file = File(dir, case.fileName)
        totalBytes += bytes.size
        if (file.isFile && file.readBytes().contentEquals(bytes)) {
            unchanged += case.fileName
        } else {
            file.writeBytes(bytes)
            changed += case.fileName
        }
    }
    // Images no longer in the case list would linger as unused goldens; report them.
    val stale = dir.listFiles { f -> f.name.endsWith(".png") && Goldens.cases.none { it.fileName == f.name } }
        ?.map { it.name }.orEmpty()
    fun list(xs: List<String>) = xs.joinToString(",", "[", "]") { "\"$it\"" }
    println("""{"dir":"${dir.path.replace('\\', '/')}","changed":${list(changed)},"unchanged":${list(unchanged)},"stale":${list(stale)},"totalBytes":$totalBytes}""")
}
