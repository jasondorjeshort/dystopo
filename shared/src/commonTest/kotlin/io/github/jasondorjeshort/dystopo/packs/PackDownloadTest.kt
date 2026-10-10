package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.packs.PackDownload.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PackDownloadTest {
    @Test
    fun resolvesBothFilesOfAPackFromEitherUrl() {
        val expected = PackSource(
            "rainier-small",
            "http://10.0.2.2:8687/out/rainier-small-dem.pmtiles",
            "http://10.0.2.2:8687/out/rainier-small-outdoors.pmtiles",
        )
        assertEquals(expected, PackDownload.resolve("http://10.0.2.2:8687/out/rainier-small-dem.pmtiles"))
        assertEquals(expected, PackDownload.resolve("http://10.0.2.2:8687/out/rainier-small-outdoors.pmtiles"))
        assertEquals(expected, PackDownload.resolve("http://10.0.2.2:8687/out/rainier-small-osm.pmtiles"))
        assertEquals(expected, PackDownload.resolve("http://10.0.2.2:8687/out/rainier-small"))
        // The query string (e.g. a throttle for testing, or a signed URL) carries over to the sibling.
        val q = PackDownload.resolve("https://example.org/packs/rainier-dem.pmtiles?kbps=200#frag")!!
        assertEquals("https://example.org/packs/rainier-outdoors.pmtiles?kbps=200", q.vectorUrl)
        assertEquals("https://example.org/packs/rainier-dem.pmtiles?kbps=200", q.demUrl)
        // A name override keeps the remote file names.
        val renamed = PackDownload.resolve("http://h/out/rainier-dem.pmtiles", name = "paradise")!!
        assertEquals("paradise", renamed.name)
        assertEquals("http://h/out/rainier-dem.pmtiles", renamed.demUrl)
    }

    @Test
    fun rejectsUrlsThatDoNotNameAPack() {
        assertNull(PackDownload.resolve("ftp://h/rainier-dem.pmtiles"))
        assertNull(PackDownload.resolve("rainier-dem.pmtiles"))
        assertNull(PackDownload.resolve("http://h"))
        assertNull(PackDownload.resolve("http://h/"))
        assertNull(PackDownload.resolve("http://h/dem.pmtiles"))
        assertNull(PackDownload.resolve("http://h/planet.pmtiles"))
        assertNull(PackDownload.resolve("http://h/rainier-dem.pmtiles", name = "../x"))
    }

    @Test
    fun parsesContentRange() {
        assertEquals(PackDownload.ContentRange(100, 199, 1000), PackDownload.parseContentRange("bytes 100-199/1000"))
        assertEquals(PackDownload.ContentRange(0, 9, null), PackDownload.parseContentRange("bytes 0-9/*"))
        assertNull(PackDownload.parseContentRange("bytes 10-9/100"))
        assertNull(PackDownload.parseContentRange("bytes 0-100/100"))
        assertNull(PackDownload.parseContentRange("items 0-1/2"))
        assertNull(PackDownload.parseContentRange(null))
        assertEquals(1000L, PackDownload.parseUnsatisfiedTotal("bytes */1000"))
    }

    @Test
    fun plansResumes() {
        // Fresh download.
        assertEquals(Plan.Restart(5000), PackDownload.plan(0, 200, null, 5000))
        // Resumed with a proper 206.
        assertEquals(Plan.Append(5000), PackDownload.plan(1200, 206, "bytes 1200-4999/5000", 3800, knownTotal = 5000))
        // Server ignored Range: start over (not append, which would corrupt the file).
        assertEquals(Plan.Restart(5000), PackDownload.plan(1200, 200, null, 5000, knownTotal = 5000))
        // Server resumed at the wrong offset, or the file changed size: discard the part file.
        assertIs<Plan.Discard>(PackDownload.plan(1200, 206, "bytes 0-4999/5000", 5000))
        assertIs<Plan.Discard>(PackDownload.plan(1200, 206, "bytes 1200-5999/6000", 4800, knownTotal = 5000))
        assertIs<Plan.Discard>(PackDownload.plan(1200, 206, null, 3800))
        // Asked past the end: complete if the size matches, else discard.
        assertEquals(Plan.Complete, PackDownload.plan(5000, 416, "bytes */5000", 0))
        assertIs<Plan.Discard>(PackDownload.plan(6000, 416, "bytes */5000", 0))
        assertEquals(Plan.Missing, PackDownload.plan(0, 404, null, 10))
        assertEquals(Plan.Fail("HTTP 500"), PackDownload.plan(0, 500, null, 10))
    }
}
