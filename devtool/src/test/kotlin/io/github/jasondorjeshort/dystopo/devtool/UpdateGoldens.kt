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
    fun write(name: String, bytes: ByteArray) {
        val file = File(dir, name)
        totalBytes += bytes.size
        if (file.isFile && file.readBytes().contentEquals(bytes)) {
            unchanged += name
        } else {
            file.writeBytes(bytes)
            changed += name
        }
    }
    for (case in Goldens.cases) {
        val render = Goldens.render(case)
        write(case.fileName, ByteArrayOutputStream().also { ImageIO.write(render.image, "png", it) }.toByteArray())
        write(case.labelsFileName, Goldens.formatLabels(render.labels).encodeToByteArray())
    }
    // Files no longer in the case list would linger as unused goldens; report them.
    val stale = dir.listFiles { f ->
        (f.name.endsWith(".png") && Goldens.cases.none { it.fileName == f.name }) ||
            (f.name.endsWith(".labels.txt") && Goldens.cases.none { it.labelsFileName == f.name })
    }?.map { it.name }.orEmpty()
    fun list(xs: List<String>) = xs.joinToString(",", "[", "]") { "\"$it\"" }
    println("""{"dir":"${dir.path.replace('\\', '/')}","changed":${list(changed)},"unchanged":${list(unchanged)},"stale":${list(stale)},"totalBytes":$totalBytes}""")
}
