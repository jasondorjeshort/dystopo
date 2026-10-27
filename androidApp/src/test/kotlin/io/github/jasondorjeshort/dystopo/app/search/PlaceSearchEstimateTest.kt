package io.github.jasondorjeshort.dystopo.app.search

import kotlin.test.Test
import kotlin.test.assertTrue

/** T149: the search index build's heap estimate against the measured builds (the heap guard asks for it first). */
class PlaceSearchEstimateTest {
    /** Archive bytes and the heap's growth through the build on an API 35 emulator (used bytes, start to peak). */
    private val measured = listOf(
        Triple("rainier-outdoors", 2_630_059L, 5_107_760L), // 1,087 entries, 1.1 s
        Triple("washington 1 x 0.7 degree", 8_860_850L, 20_402_176L), // 5,228 entries, 3.0 s
        Triple("washington 3 degree", 124_125_123L, 132_567_040L), // 103,977 entries, 54.6 s
    )

    @Test
    fun theEstimateCoversEachMeasuredBuild() {
        // At least the measured growth, and at most five times it (the small packs pay the fixed part).
        for ((name, bytes, peak) in measured) {
            val ratio = PlaceSearch.estimateBytes(bytes).toDouble() / peak
            assertTrue(ratio in 1.0..5.0, "$name: estimate ${PlaceSearch.estimateBytes(bytes)} is ${ratio}x the measured $peak")
        }
    }
}
