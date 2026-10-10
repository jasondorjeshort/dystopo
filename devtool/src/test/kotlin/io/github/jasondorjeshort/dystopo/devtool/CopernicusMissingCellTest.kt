package io.github.jasondorjeshort.dystopo.devtool

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Open-ocean Copernicus cells (no file in the bucket) are asked for once, then remembered on disk. */
class CopernicusMissingCellTest {
    private val dir: File = Files.createTempDirectory("copernicus-missing").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun missingCellsAreRememberedOnDisk() {
        var opens = 0
        val name = "Copernicus_DSM_COG_10_N00_00_W140_00_DEM"
        val missing: () -> String = { opens++; throw HttpStatusException(404, "https://example.invalid/$name.tif") }
        repeat(5) { assertNull(CopernicusSource.openCell(dir, name, missing)) }
        assertEquals(1, opens)
        assertTrue(CopernicusSource.missingMarker(dir, name).isFile)

        // A second cache directory (a different worktree or a cleared run) asks once more and keeps its own marker.
        val other = File(dir, "other")
        assertNull(CopernicusSource.openCell(other, name, missing))
        assertEquals(2, opens)
    }

    @Test
    fun otherFailuresAreRetried() {
        var opens = 0
        val name = "Copernicus_DSM_COG_10_N46_00_E006_00_DEM"
        val flaky: () -> String = { opens++; throw HttpStatusException(503, "https://example.invalid/$name.tif") }
        repeat(3) { assertNull(CopernicusSource.openCell(dir, name, flaky)) }
        assertEquals(3, opens)
        assertTrue(!CopernicusSource.missingMarker(dir, name).exists())
        assertEquals("cell", CopernicusSource.openCell(dir, name) { "cell" })
    }
}
