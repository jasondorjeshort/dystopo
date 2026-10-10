package io.github.jasondorjeshort.dystopo.devtool

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders each [Goldens.cases] entry through the full pipeline (hillshade, contours, labels) and
 * compares it with the approved image in `src/test/resources/goldens/`. On a mismatch the actual
 * and a diff image go to `build/golden-diffs/`. If the change is intended: `./gradlew updateGoldens`,
 * look at the new images, and commit them.
 *
 * Tolerances. Everything except text is plain arithmetic plus Java2D antialiasing, so it is
 * compared tightly. Labels are drawn with the logical SANS_SERIF font, which maps to different
 * font files per OS and JDK, so the label boxes (plus halo and a 3 px margin) get a loose
 * tolerance that still notices labels vanishing or moving. A changed hillshade azimuth, a changed
 * paper tone, a contour level shift or a lost contour all fail the tight part.
 */
class GoldenImageTest {
    private val pixelThreshold = 12
    private val strict = Tolerance(maxMeanAbs = 0.25, pixelThreshold = pixelThreshold, maxDifferingFraction = 0.002)
    private val labels = Tolerance(maxMeanAbs = 12.0, pixelThreshold = pixelThreshold, maxDifferingFraction = 0.30)

    @Test
    fun goldenImagesMatch() {
        val failures = ArrayList<String>()
        for (case in Goldens.cases) failures += check(case) ?: continue
        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of ${Goldens.cases.size} golden images differ:\n" + failures.joinToString("\n") +
                    "\nIf the change is intended, run ./gradlew updateGoldens, view the new images, and commit them."
            )
        }
    }

    /** Null when [case] matches its golden, else a description of the mismatch. */
    private fun check(case: GoldenCase): String? {
        val render = Goldens.render(case)
        val actual = render.image
        val stream = javaClass.getResourceAsStream("/goldens/${case.fileName}")
        if (stream == null) {
            val written = writeOut(case, actual, null)
            return "  ${case.name}: no golden goldens/${case.fileName}; actual image at $written"
        }
        val expected = stream.use { ImageIO.read(it) }
        if (expected.width != actual.width || expected.height != actual.height) {
            val written = writeOut(case, actual, null)
            return "  ${case.name}: size ${actual.width}x${actual.height}, golden ${expected.width}x${expected.height}; actual at $written"
        }
        val mask = Goldens.labelMask(render.labels, actual.width, actual.height)
        val diff = ImageDiff(expected, actual, mask, pixelThreshold)
        if (diff.outside.within(strict) && diff.inside.within(labels)) return null
        val written = writeOut(case, actual, diff)
        return "  ${case.name}:\n" +
            "    outside labels: ${diff.outside.describe(strict)}\n" +
            "    label regions:  ${diff.inside.describe(labels)}\n" +
            "    $written"
    }

    private fun writeOut(case: GoldenCase, actual: java.awt.image.BufferedImage, diff: ImageDiff?): String {
        val dir = Goldens.diffDir.absoluteFile
        dir.mkdirs()
        val actualFile = File(dir, "${case.name}-actual.png")
        ImageIO.write(actual, "png", actualFile)
        if (diff == null) return "actual ${actualFile.path}"
        val diffFile = File(dir, "${case.name}-diff.png")
        ImageIO.write(diff.diffImage(), "png", diffFile)
        return "actual ${actualFile.path}, diff ${diffFile.path}"
    }
}
