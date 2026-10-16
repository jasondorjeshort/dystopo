package io.github.jasondorjeshort.dystopo.app.storage

import io.github.jasondorjeshort.dystopo.app.cachebudget.CacheBudget
import io.github.jasondorjeshort.dystopo.app.online.TileCache
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T112: the Storage rows follow the budget's kinds: the contour and shade tiles made from downloaded DEM tiles sit in the
 * online cache's directory but count (and clear) with Contours and shading, not with Online tiles.
 */
class StorageKindsTest {
    private val dir: File = Files.createTempDirectory("storage-kinds").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun onlineContoursAndShadeCountWithTheDerivedRow() {
        val online = TileCache(dir, 1L shl 24) { 1_000_000L }
        online.put("10.0.2.2_8765/dem@v.1/12/655/1431.png", ByteArray(1000))
        online.put("10.0.2.2_8765/osm@o.1/12/655/1431.pbf", ByteArray(300))
        online.put("10.0.2.2_8765/contours-v2-ft-512@v.1/12/655/1431.pbf", ByteArray(100))
        online.put("10.0.2.2_8765/shade-multi_1@v.1/12/655/1431.png", ByteArray(50))
        assertTrue(CacheBudget.onlineDerived("10.0.2.2_8765/contours-v2-ft-512@v.1/12/655/1431.pbf"))
        assertFalse(CacheBudget.onlineDerived("10.0.2.2_8765/dem@v.1/12/655/1431.png"))
        assertFalse(CacheBudget.onlineDerived("direct/dem/12/655/1431.png"))
        val packMade = mapOf("contours" to mapOf("tiles" to 3L, "bytes" to 900L), "cliffs" to mapOf("tiles" to 1L, "bytes" to 10L))
        val (fetched, made) = Storage.splitHolds(Storage.onlineBreakdown(online, pinned = false), packMade)
        assertEquals(setOf("dem", "osm"), fetched.keys)
        assertEquals(mapOf("tiles" to 4L, "bytes" to 1000L), made["contours"], "pack and online contours together")
        assertEquals(mapOf("tiles" to 1L, "bytes" to 50L), made["shade"])
        assertEquals(10L, made.getValue("cliffs")["bytes"])
        assertEquals(150L, online.bytesWhere(matches = CacheBudget::onlineDerived))
        // Clear on the online row: the downloaded tiles go, what was made from them stays (the derived row's own Clear).
        assertEquals(2, online.clearWhere { k -> !CacheBudget.onlineDerived(k) && !online.keep(k) })
        assertEquals(150L, online.bytes)
    }
}
