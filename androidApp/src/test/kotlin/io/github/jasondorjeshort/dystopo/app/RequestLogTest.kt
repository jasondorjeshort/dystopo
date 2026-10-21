package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RequestLogTest {
    @Test
    fun routesAreTheMapKindsElseThePath() {
        assertEquals("dem", RequestLog.routeOf("/dem/8/40/90.png"))
        assertEquals("osm", RequestLog.routeOf("/osm/12/654/1431.pbf"))
        assertEquals("contours", RequestLog.routeOf("/contours/12/654/1431.pbf"))
        assertEquals("shade", RequestLog.routeOf("/shade/12/654/1431.png"))
        assertEquals("glyphs", RequestLog.routeOf("/glyphs/Noto Sans Regular/0-255.pbf"))
        assertEquals("sprite", RequestLog.routeOf("/sprite.json"))
        assertEquals("style", RequestLog.routeOf("/style.json"))
        assertEquals("/shade", RequestLog.routeOf("/shade")) // GET /shade is a debug route, not a tile
        assertEquals("/stats/requests", RequestLog.routeOf("/stats/requests"))
    }

    @Test
    fun keepsTheNewestLinesInOrder() {
        var now = 1_000_000L
        val log = RequestLog(capacity = 3) { now }
        for (i in 1..5) {
            now += 10
            log.add("GET", "/dem/8/$i/90.png", 200, 100 * i, now - 4, pack = "rainier")
        }
        assertEquals(listOf("/dem/8/3/90.png", "/dem/8/4/90.png", "/dem/8/5/90.png"), log.entries().map { it.path })
        assertEquals(listOf("/dem/8/5/90.png"), log.entries(1).map { it.path })
        val j = log.json(limit = 2)
        assertEquals(5L, j["total"])
        assertEquals(3, j["kept"])
        assertEquals(2L, j["dropped"])
        assertEquals(2, (j["lines"] as List<*>).size)
        @Suppress("UNCHECKED_CAST")
        val dem = (j["perRoute"] as Map<String, Map<String, Any?>>)["dem"]!!
        assertEquals(3, dem["requests"])
        assertEquals(1200L, dem["bytes"])
        assertEquals(4L, dem["maxMs"])
        assertEquals(mapOf("200" to 3), dem["statuses"])
    }

    @Test
    fun jsonLinesReadAsTheServerLogThatCacheReplayReads() {
        val log = RequestLog { 1_760_000_000_500L }
        log.add("GET", "/osm/12/654/1431.pbf", 200, 2048, 1_760_000_000_000L, pack = "online:sources")
        log.add("POST", "/camera", 200, 80, 1_760_000_000_100L)
        val lines = log.jsonLines().trimEnd('\n').split('\n')
        assertEquals(2, lines.size)
        val first = MiniJson.parseObject(lines[0])
        assertEquals("request", first["event"])
        assertEquals("2025-10-09T08:53:20Z", first["ts"])
        assertEquals("GET", first["method"])
        assertEquals("/osm/12/654/1431.pbf", first["path"])
        assertEquals("osm", first["route"])
        assertEquals("online:sources", first["pack"])
        assertEquals(200, (first["status"] as Number).toInt())
        assertEquals(2048, (first["bytes"] as Number).toInt())
        assertEquals(500, (first["ms"] as Number).toInt())
        assertNull(MiniJson.parseObject(lines[1])["pack"])
    }

    @Test
    fun capacityMustBePositive() {
        assertFailsWith<IllegalArgumentException> { RequestLog(capacity = 0) }
    }
}
