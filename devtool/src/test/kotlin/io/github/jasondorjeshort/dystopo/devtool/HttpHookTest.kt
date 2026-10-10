package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import java.io.File
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Every network path of the devtool calls [Http.beforeNetwork] first, so `tour --offline-ok` stays cache-only (T50). */
class HttpHookTest {
    private class Blocked(val url: String) : RuntimeException(url)

    @AfterTest
    fun reset() {
        Http.beforeNetwork = null
    }

    private fun blockAll(): MutableList<String> {
        val seen = mutableListOf<String>()
        Http.beforeNetwork = { url -> seen += url; throw Blocked(url) }
        return seen
    }

    @Test
    fun liveTilesGetGoesThroughTheHook() {
        val seen = blockAll()
        // OpenFreeMap tiles and the live sources' other GETs use LiveTiles.httpGet.
        val e = assertFailsWith<Blocked> { LiveTiles.httpGet("https://tiles.example.invalid/planet/1/2/3.pbf") }
        assertEquals("https://tiles.example.invalid/planet/1/2/3.pbf", e.url)
        assertEquals(listOf(e.url), seen)
    }

    @Test
    fun overpassAndHttpHelpersGoThroughTheHook() {
        val seen = blockAll()
        assertFailsWith<Blocked> { OverpassSource.httpPost("https://overpass.example.invalid/api/interpreter", "data=", Duration.ofSeconds(1), File("unused")) }
        assertFailsWith<Blocked> { Http.get("https://example.invalid/a") }
        assertFailsWith<Blocked> { Http.range("https://example.invalid/b", 0, 10) }
        assertFailsWith<Blocked> { Http.download("https://example.invalid/c", File("unused")) }
        assertEquals(4, seen.size)
    }
}
