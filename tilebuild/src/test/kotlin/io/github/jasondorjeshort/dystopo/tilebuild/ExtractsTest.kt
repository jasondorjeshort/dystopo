package io.github.jasondorjeshort.dystopo.tilebuild

import com.github.ajalt.clikt.core.UsageError
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ExtractsTest {
    private val cache = File("cache-test/osm")

    @Test
    fun bareNameResolvesThroughGeofabrikIndex() {
        val input = Extracts.resolve("liechtenstein", cache)
        assertEquals(ExtractKind.GEOFABRIK_NAME, input.kind)
        assertEquals("liechtenstein", input.label)
        assertEquals("geofabrik:liechtenstein", input.url)
        assertEquals(File(cache, "liechtenstein-latest.osm.pbf"), input.path)
    }

    @Test
    fun fullPathBuildsDirectUrl() {
        val input = Extracts.resolve("north-america/us/washington", cache)
        assertEquals(ExtractKind.GEOFABRIK_PATH, input.kind)
        assertEquals("washington", input.label)
        assertEquals("https://download.geofabrik.de/north-america/us/washington-latest.osm.pbf", input.url)
        assertEquals(File(cache, "north-america-us-washington-latest.osm.pbf"), input.path)
    }

    @Test
    fun pathIsNormalized() {
        val input = Extracts.resolve("/Europe/Liechtenstein/", cache)
        assertEquals("https://download.geofabrik.de/europe/liechtenstein-latest.osm.pbf", input.url)
        assertEquals("liechtenstein", input.label)
    }

    @Test
    fun localPbfIsNotDownloaded() {
        val input = Extracts.resolve("data/monaco.osm.pbf", cache)
        assertEquals(ExtractKind.LOCAL, input.kind)
        assertNull(input.url)
        assertEquals("monaco", input.label)
        assertEquals(File("data/monaco.osm.pbf"), input.path)
    }

    @Test
    fun rejectsGarbage() {
        assertFailsWith<UsageError> { Extracts.resolve("", cache) }
        assertFailsWith<UsageError> { Extracts.resolve("not a region!", cache) }
        assertFailsWith<UsageError> { Extracts.resolve("../escape", cache) }
    }
}
