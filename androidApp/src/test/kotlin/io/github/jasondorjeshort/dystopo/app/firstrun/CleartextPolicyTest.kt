package io.github.jasondorjeshort.dystopo.app.firstrun

import io.github.jasondorjeshort.dystopo.app.packs.PackManager
import io.github.jasondorjeshort.dystopo.packs.PackRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CleartextPolicyTest {
    @Test
    fun privateRangesAllowPlainHttp() {
        for (url in listOf(
            "http://10.0.2.2:8765", "http://10.255.255.255", "http://172.16.0.1:8080", "http://172.31.9.9", "http://192.168.12.186:8080/",
            "http://169.254.1.1", "http://127.0.0.1:8686/style.json", "http://localhost:8080", "http://nas.local:8080", "http://NAS.LOCAL.",
            "http://[::1]:8080", "http://[fd12:3456::1]:8080", "http://[fe80::1%25wlan0]:8080",
        )) assertNull(CleartextPolicy.problem(url), url)
    }

    @Test
    fun publicHostsNeedHttps() {
        for (url in listOf(
            "http://8.8.8.8", "http://172.15.0.1", "http://172.32.0.1", "http://192.169.0.1", "http://11.0.0.1", "http://example.org",
            "http://maps.example.org:8080", "http://local", "http://evil.local.example.org", "http://[2001:db8::1]", "http://10.0.0.256",
            "http://169.253.1.1",
        )) assertNotNull(CleartextPolicy.problem(url), url)
        assertTrue(CleartextPolicy.problem("http://example.org:8080")!!.contains("use https://example.org:8080"))
    }

    @Test
    fun httpsIsAlwaysAllowed() {
        assertNull(CleartextPolicy.problem("https://maps.example.org"))
        assertNull(CleartextPolicy.problem("https://8.8.8.8:8443/x"))
        assertNotNull(CleartextPolicy.problem("ftp://10.0.0.1"))
        assertNotNull(CleartextPolicy.problem("not a url"))
    }

    @Test
    fun redirectsAreNotFollowedPastThePolicy() {
        // A private http server answering 302 with a public http address: Java follows same-protocol redirects
        // by itself, so open() must turn that off, and redirectTarget() must refuse the Location.
        // A tiny HTTP/1.0 server on a plain socket (the JDK's com.sun.net.httpserver is not on the Android test classpath).
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
        val thread = Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use {
                    val path = it.getInputStream().bufferedReader().readLine().orEmpty().split(' ').getOrElse(1) { "/" }
                    val reply = when (path) {
                        "/public" -> "HTTP/1.0 302 Found\r\nLocation: http://8.8.8.8/x\r\nContent-Length: 0\r\n\r\n"
                        "/local" -> "HTTP/1.0 301 Moved\r\nLocation: /ok\r\nContent-Length: 0\r\n\r\n"
                        else -> "HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok"
                    }
                    it.getOutputStream().write(reply.toByteArray())
                    it.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val pub = CleartextPolicy.open("$base/public")
            assertFalse(pub.instanceFollowRedirects)
            assertEquals(302, pub.responseCode) // not followed
            val e = assertFailsWith<java.io.IOException> { CleartextPolicy.redirectTarget(pub) }
            assertTrue(e.message!!.contains("refused"), e.message)
            pub.disconnect()
            val local = CleartextPolicy.open("$base/local")
            assertEquals("$base/ok", CleartextPolicy.redirectTarget(local)) // relative Location, still private: allowed
            local.disconnect()
            val ok = CleartextPolicy.open("$base/ok")
            assertNull(CleartextPolicy.redirectTarget(ok))
            ok.disconnect()
        } finally {
            server.close()
            thread.join(2000)
        }
    }

    @Test
    fun leadingZeroOctetsAreRefused() {
        assertFalse(CleartextPolicy.isPrivateHost("010.0.0.1"))
        assertFalse(CleartextPolicy.isPrivateHost("192.168.01.1"))
        assertNotNull(CleartextPolicy.problem("http://010.0.0.1:8080"))
        assertTrue(CleartextPolicy.isPrivateHost("10.0.0.1"))
        assertTrue(CleartextPolicy.isPrivateHost("192.168.0.10"))
    }

    @Test
    fun hostChecks() {
        assertTrue(CleartextPolicy.isPrivateHost("192.168.0.1"))
        assertFalse(CleartextPolicy.isPrivateHost("192.168.0"))
        assertFalse(CleartextPolicy.isPrivateHost("1.2.3.4.5"))
        assertFalse(CleartextPolicy.isPrivateHost(""))
    }

    @Test
    fun importRoleFromNameAndTileType() {
        assertEquals(PackRole.DEM, PackManager.importRole("rainier-dem.pmtiles", "png"))
        assertEquals(PackRole.VECTOR, PackManager.importRole("rainier-outdoors.pmtiles", "mvt"))
        assertEquals(PackRole.VECTOR, PackManager.importRole("rainier-osm.pmtiles", "mvt"))
        assertEquals(PackRole.DEM, PackManager.importRole("terrain (1).pmtiles", "webp"))
        assertEquals(PackRole.VECTOR, PackManager.importRole("map.pmtiles", "mvt"))
        assertFailsWith<IllegalArgumentException> { PackManager.importRole("rainier-dem.pmtiles", "mvt") }
        assertFailsWith<IllegalArgumentException> { PackManager.importRole("x.pmtiles", "unknown") }
    }

    @Test
    fun importNames() {
        assertEquals("rainier", PackManager.importName("rainier-dem.pmtiles"))
        assertEquals("terrain-1", PackManager.importName("terrain (1).pmtiles"))
        assertEquals("imported", PackManager.importName("....pmtiles"))
    }

    @Test
    fun boxAroundIsAboutTheAskedSize() {
        val b = FirstRun.boxAround(46.786, -121.736, 8.0)
        assertEquals(16.0, (b.maxLat - b.minLat) * 110.57, 0.01)
        assertEquals(16.0, (b.maxLon - b.minLon) * 111.32 * kotlin.math.cos(46.786 * Math.PI / 180), 0.01)
        assertTrue(b.minLon < -121.736 && b.maxLon > -121.736)
    }
}
