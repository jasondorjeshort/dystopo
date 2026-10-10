package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class TourOfflineTest {
    @Test
    fun findsCacheMissThroughWrappers() {
        val miss = TourCommand.OfflineCacheMiss("https://example.invalid/cell.tif")
        assertSame(miss, TourCommand.offlineMiss(miss))
        assertSame(miss, TourCommand.offlineMiss(ExecutionException(RuntimeException("wrapped", miss))))
    }

    @Test
    fun otherFailuresAreNotSkips() {
        assertNull(TourCommand.offlineMiss(java.io.IOException("connection reset")))
        assertNull(TourCommand.offlineMiss(HttpStatusException(500, "https://example.invalid/")))
        assertNull(TourCommand.offlineMiss(null))
    }
}
