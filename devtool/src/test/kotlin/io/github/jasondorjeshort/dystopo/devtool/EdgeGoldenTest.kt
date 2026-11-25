package io.github.jasondorjeshort.dystopo.devtool

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.fail

/**
 * T357: the edge join's goldens ([EdgeGolden], `goldens/edge/`) at the map goldens' tolerance. `./gradlew updateGoldens`
 * rewrites them; view the new images (a step on a tile boundary or a seam along the box is only visible in the image).
 */
class EdgeGoldenTest {
    private val tolerance = Tolerance(maxMeanAbs = 0.25, pixelThreshold = 12, maxDifferingFraction = 0.002)

    @Test
    fun edgeImagesMatchTheirGoldens() {
        val failures = ArrayList<String>()
        for (case in EdgeGolden.cases) {
            val actual = EdgeGolden.render(case)
            val expected = javaClass.getResourceAsStream("/goldens/${case.file}")?.use { ImageIO.read(it) }
            if (expected == null) {
                failures += "  ${case.file}: no golden; actual at ${write(case.file, actual, "actual")}"
                continue
            }
            if (expected.width != actual.width || expected.height != actual.height) {
                failures += "  ${case.file}: size ${actual.width}x${actual.height}, golden ${expected.width}x${expected.height}"
                continue
            }
            val diff = ImageDiff(expected, actual, BooleanArray(actual.width * actual.height), 12)
            if (!diff.outside.within(tolerance)) {
                failures += "  ${case.file}: ${diff.outside.describe(tolerance)}; ${write(case.file, actual, "actual")}, ${write(case.file, diff.diffImage(), "diff")}"
            }
        }
        if (failures.isNotEmpty()) {
            fail("edge goldens differ:\n" + failures.joinToString("\n") + "\nIf the change is intended, run ./gradlew updateGoldens, view the new images, and commit them.")
        }
    }

    private fun write(name: String, image: BufferedImage, kind: String): String {
        val f = File(Goldens.diffDir.absoluteFile, name.replace('/', '-').removeSuffix(".png") + "-$kind.png")
        f.parentFile.mkdirs()
        ImageIO.write(image, "png", f)
        return f.path
    }
}
