package io.github.jasondorjeshort.dystopo.sources

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceRegistryTest {
    private val registry = SourceRegistry.builtIn

    @Test
    fun builtInRegistryListsEverySourceWeUse() {
        for (id in listOf(
            "copernicus", "3dep", "3dep-1m", "terrarium", "geoid18", "geoid18-prvi", "geoid12b-alaska", "geoid12b-hawaii", "egm2008",
            "osm", "osm-water-polygons", "osm-lake-centerlines", "natural-earth", "openmaptiles",
        )) {
            val s = assertNotNull(registry[id], "missing $id")
            assertTrue(s.license.isNotBlank() && s.attribution.isNotBlank() && s.attributionHtml.isNotBlank(), id)
        }
        assertEquals(registry.ids.size, registry.ids.toSet().size)
    }

    @Test
    fun builtInLicensesMatchProviders() {
        assertEquals("ODbL-1.0", registry.getValue("osm").license)
        assertEquals("BSD-3-Clause AND CC-BY-4.0", registry.getValue("openmaptiles").license)
        assertEquals(SourceKind.GEOID, registry.getValue("egm2008").kind)
        assertEquals("EGM2008", registry.getValue("copernicus").verticalDatum)
        assertEquals("NAVD88 (CONUS); other datums elsewhere", registry.getValue("3dep").verticalDatum)
        assertEquals("NAVD88 (CONUS); other datums elsewhere", registry.getValue("3dep-1m").verticalDatum)
        assertTrue(registry.getValue("copernicus").licenseUrl.endsWith("License-COPDEM-30.pdf"))
        assertTrue("MapTiler" in registry.getValue("osm-lake-centerlines").provider)
        val cop = registry.getValue("copernicus").attribution
        assertTrue("© DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved" in cop)
        assertTrue("© OpenStreetMap contributors" in registry.getValue("osm").attribution)
    }

    @Test
    fun composedAttributionIsDeduplicatedAndInRegistryOrder() {
        val a = registry.attribution(listOf("egm2008", "3dep", "copernicus", "3dep", "geoid18"))
        val b = registry.attribution(setOf("geoid18", "copernicus", "egm2008", "3dep"))
        assertEquals(a, b)
        assertTrue(a.indexOf("Copernicus") < a.indexOf("USGS"))
        assertTrue(a.indexOf("GEOID18") < a.indexOf("EGM2008"))
        assertEquals(1, Regex("USGS 3D Elevation").findAll(a).count())
        assertEquals(listOf("copernicus", "3dep", "geoid18", "egm2008"), registry.ordered(listOf("egm2008", "3dep", "copernicus", "geoid18", "3dep")))
    }

    @Test
    fun identicalCreditsAppearOnce() {
        val text = registry.attribution(listOf("osm", "osm-water-polygons", "osm-lake-centerlines", "openmaptiles"))
        assertEquals("© OpenStreetMap contributors (ODbL 1.0) © OpenMapTiles", text)
        val html = registry.attributionHtml(listOf("openmaptiles", "osm", "osm-water-polygons"))
        assertEquals(1, Regex("OpenStreetMap contributors").findAll(html).count())
        assertTrue(html.indexOf("OpenStreetMap") < html.indexOf("OpenMapTiles"))
    }

    @Test
    fun unknownIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> { registry.attribution(listOf("copernicus", "nope")) }
        assertFalse("nope" in registry)
    }

    private fun entry(id: String, vararg overrides: Pair<String, Any?>): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to id, "name" to "N", "kind" to "dem", "provider" to "P", "coverage" to mapOf("summary" to "S"),
        "resolution" to "1 m", "verticalDatum" to "EGM2008", "license" to "CC0-1.0", "licenseName" to "CC0",
        "licenseUrl" to "https://example.org/l", "attribution" to "A $id", "attributionHtml" to "<b>A $id</b>",
        "allowedUses" to "any", "url" to "https://example.org/{z}", "updateCadence" to "never", "notes" to "",
    ).apply { putAll(overrides) }

    private fun registryJson(vararg entries: Map<String, Any?>) = MiniJson.encode(mapOf("sources" to entries.toList()))

    @Test
    fun validRegistryParses() {
        val r = SourceRegistry.parse(registryJson(entry("a"), entry("b", "coverage" to mapOf("summary" to "S", "bbox" to listOf(-10, -5, 10, 5)))))
        assertEquals(listOf("a", "b"), r.ids)
        assertEquals(listOf(-10.0, -5.0, 10.0, 5.0), r.getValue("b").coverage.bbox)
        assertEquals("A a A b", r.attribution(listOf("b", "a")))
    }

    @Test
    fun validationRejectsDuplicatesAndMissingLicenseOrAttribution() {
        fun problem(vararg entries: Map<String, Any?>): String =
            assertFailsWith<IllegalArgumentException> { SourceRegistry.parse(registryJson(*entries)) }.message!!
        assertTrue("duplicate id 'a'" in problem(entry("a"), entry("a")))
        assertTrue("empty license" in problem(entry("a", "license" to "")))
        assertTrue("empty attribution" in problem(entry("a", "attribution" to "  ")))
        assertTrue("empty attributionHtml" in problem(entry("a", "attributionHtml" to null)))
        assertTrue("unknown kind" in problem(entry("a", "kind" to "raster")))
        assertTrue("verticalDatum" in problem(entry("a", "verticalDatum" to null)))
        assertTrue("bbox" in problem(entry("a", "coverage" to mapOf("summary" to "S", "bbox" to listOf(10, 0, -10, 5)))))
        assertTrue("SPDX" in problem(entry("a", "license" to "public domain")))
        // Every problem is reported, not just the first.
        val many = problem(entry("a", "license" to ""), entry("b", "attribution" to ""))
        assertTrue("a: empty license" in many && "b: empty attribution" in many)
        assertFailsWith<IllegalArgumentException> { SourceRegistry.parse("{}") }
    }

    @Test
    fun licenseValidatorAcceptsSpdxExpressions() {
        val ok = listOf(
            "MIT", "ODbL-1.0", "LicenseRef-Copernicus-DEM", "BSD-3-Clause AND CC-BY-4.0", "MIT OR Apache-2.0",
            "GPL-2.0-only WITH Classpath-exception-2.0", "(MIT OR Apache-2.0) AND CC0-1.0", "((MIT))", "MIT  AND  ISC",
        )
        for (l in ok) assertTrue(SourceRegistry.isSpdxExpression(l), l)
        val bad = listOf(
            "", "public domain", "MIT and ISC", "MIT AND", "AND MIT", "MIT ISC", "(MIT", "MIT)", "()", "MIT AND OR ISC",
            "MIT, ISC", "MIT/ISC", "(MIT) (ISC)",
        )
        for (l in bad) assertFalse(SourceRegistry.isSpdxExpression(l), l)
        // Through the registry validator too.
        SourceRegistry.parse(registryJson(entry("a", "license" to "BSD-3-Clause AND CC-BY-4.0")))
        val e = assertFailsWith<IllegalArgumentException> { SourceRegistry.parse(registryJson(entry("a", "license" to "MIT and ISC"))) }
        assertTrue("SPDX" in e.message!!)
    }
}
