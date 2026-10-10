package io.github.jasondorjeshort.dystopo.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchIndexTest {
    // Public places around Mount Rainier.
    private val paradise = SearchEntry("Paradise", null, PlaceKind.HAMLET, -121.7353, 46.7865, 55, 12, null)
    private val river = SearchEntry("Paradise River", null, PlaceKind.RIVER, -121.7400, 46.7700, 42, 10, null)
    private val panorama = SearchEntry("Panorama Point", null, PlaceKind.PEAK, -121.7270, 46.8020, 80, 12, 2097)
    private val rainier = SearchEntry("Mount Rainier", "Tahoma", PlaceKind.VOLCANO, -121.7603, 46.8529, 109, 10, 4392)
    private val packwood = SearchEntry("Packwood", null, PlaceKind.VILLAGE, -121.6737, 46.6074, 80, 10, null)
    private val accented = SearchEntry("Pointe Hélène", null, PlaceKind.PEAK, -121.70, 46.80, 60, 14, -12)
    private val source = SearchSource("rainier-outdoors.pmtiles", "v1:1:2:3", listOf(10, 12, 14), 1_700_000_000_000L)
    private val index = SearchIndex.of(listOf(paradise, river, panorama, rainier, packwood, accented), source)

    private fun names(hits: List<SearchHit>) = hits.map { it.entry.name }

    @Test
    fun prefixSearchNeedsEveryToken() {
        assertEquals(setOf("Paradise", "Paradise River"), names(index.search("para")).toSet())
        assertEquals(listOf("Paradise River"), names(index.search("paradise riv")))
        assertEquals(emptyList(), index.search("paradise lake"))
        assertEquals(emptyList(), index.search("   "))
    }

    @Test
    fun shortPrefixFindsAllThreeParadiseAreaNames() {
        val hits = names(index.search("pa", lat = 46.786, lon = -121.735))
        assertTrue(hits.containsAll(listOf("Paradise", "Panorama Point", "Paradise River")), "$hits")
    }

    @Test
    fun rankingPrefersCloseImportantExactMatches() {
        val hits = index.search("paradise", lat = 46.786, lon = -121.735)
        assertEquals("Paradise", hits[0].entry.name)
        assertEquals("exact", hits[0].match)
        assertEquals("prefix", hits[1].match)
        // From 100 km away the same names come first, with lower scores.
        val far = index.search("paradise", lat = 47.7, lon = -121.735)
        assertTrue(far[0].score < hits[0].score)
        assertTrue(far[0].distanceKm!! > 90)
    }

    @Test
    fun accentsAltNamesAndAbbreviations() {
        assertEquals("Pointe Hélène", index.search("helene").single().entry.name)
        assertEquals("Pointe Hélène", index.search("HÉLÈ").single().entry.name)
        assertEquals("Mount Rainier", index.search("tahoma").single().entry.name)
        assertEquals("Mount Rainier", index.search("mt rainier").single().entry.name)
    }

    @Test
    fun fuzzyFallbackOnlyWhenNothingMatches() {
        val hits = index.search("paradsie")
        assertTrue(hits.isNotEmpty() && hits.all { it.match == "fuzzy" }, "$hits")
        assertEquals("Paradise", hits[0].entry.name)
        assertTrue(SearchIndex.withinOneEdit("ranier", "rainier"))
        assertTrue(!SearchIndex.withinOneEdit("pano", "paradise"))
        assertEquals(emptyList(), index.search("xyzzy"))
    }

    @Test
    fun kindFilterAndLimit() {
        assertEquals(listOf("Panorama Point"), names(index.search("p", kinds = setOf(PlaceKind.PEAK), limit = 1)))
        assertEquals(2, index.search("p", limit = 2).size)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val bytes = index.encode()
        val back = SearchIndex.decode(bytes)
        assertEquals(source, back.source)
        assertEquals(index.entries.size, back.entries.size)
        for ((a, b) in index.entries.zip(back.entries)) {
            assertEquals(a.name, b.name)
            assertEquals(a.alt, b.alt)
            assertEquals(a.kind, b.kind)
            assertEquals(a.rank, b.rank)
            assertEquals(a.minZoom, b.minZoom)
            assertEquals(a.ele, b.ele)
            assertEquals(a.lat, b.lat, 1e-7)
            assertEquals(a.lon, b.lon, 1e-7)
        }
        assertNull(back.entries.first { it.name == "Paradise" }.ele)
        assertEquals(-12, back.entries.first { it.name == "Pointe Hélène" }.ele)
        assertEquals(index.tokenCount, back.tokenCount)
        assertEquals(names(index.search("pa", 46.78, -121.73)), names(back.search("pa", 46.78, -121.73)))
    }

    @Test
    fun rejectsCorruptTruncatedAndForeignFiles() {
        val bytes = index.encode()
        assertFailsWith<IllegalArgumentException> { SearchIndex.decode(bytes.copyOf(bytes.size - 7)) }
        val flipped = bytes.copyOf().also { it[40] = (it[40].toInt() xor 0x55).toByte() }
        assertFailsWith<IllegalArgumentException> { SearchIndex.decode(flipped) }
        assertFailsWith<IllegalArgumentException> { SearchIndex.decode("PMTiles!".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { SearchIndex.decode(ByteArray(0)) }
    }

    @Test
    fun indexFileSitsNextToThePack() {
        assertEquals("rainier-search.idx", SearchIndex.fileNameFor("rainier-outdoors.pmtiles"))
        assertEquals("rainier-search.idx", SearchIndex.fileNameFor("rainier-osm.pmtiles"))
        assertEquals("default-search.idx", SearchIndex.fileNameFor("osm.pmtiles"))
        assertEquals("extract-search.idx", SearchIndex.fileNameFor("extract.pmtiles"))
    }
}
