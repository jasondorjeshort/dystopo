package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [ElevationSource.Result.provenance] must say what contributed whenever there is data to credit. */
class ProvenanceTest {
    private val withData = ElevationGrid(2, 1, 10.0, floatArrayOf(Float.NaN, 100f))
    private val noData = ElevationGrid(1, 1, 10.0, floatArrayOf(Float.NaN))

    @Test
    fun dataWithoutProvenanceIsRejected() {
        assertFailsWith<IllegalArgumentException> { ElevationSource.Result(withData, emptyMap(), emptyList()) }
    }

    @Test
    fun unknownProvenanceIdIsRejected() {
        assertFailsWith<IllegalArgumentException> { ElevationSource.Result(withData, emptyMap(), listOf("not-a-source")) }
    }

    @Test
    fun emptyProvenanceIsFineWhenNothingContributed() {
        assertEquals(emptyList(), ElevationSource.Result(noData, emptyMap(), emptyList()).provenance)
        val ids = ElevationSource.provenanceIfData(withData, listOf("copernicus"))
        assertEquals(listOf("copernicus"), ElevationSource.Result(withData, emptyMap(), ids).provenance)
    }
}
