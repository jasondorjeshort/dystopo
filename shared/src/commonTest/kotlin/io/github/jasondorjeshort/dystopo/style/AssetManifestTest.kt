package io.github.jasondorjeshort.dystopo.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AssetManifestTest {
    private val h1 = "a".repeat(64)
    private val h2 = "b".repeat(64)

    @Test
    fun versionFollowsEveryEntryAndIgnoresOrder() {
        val a = AssetManifest.Entry("sprites/sprite.png", "/sprite.png", h1, 10)
        val b = AssetManifest.Entry("glyphs/Noto Sans Regular/0-255.pbf", "/glyphs/Noto%20Sans%20Regular/0-255.pbf", h2, 20)
        val m = AssetManifest.of(listOf(a, b))
        assertEquals(m.version, AssetManifest.of(listOf(b, a)).version)
        assertEquals(16, m.version.length)
        assertNotEquals(m.version, AssetManifest.of(listOf(AssetManifest.Entry(a.path, a.url, h2, 10), b)).version)
        val back = AssetManifest.parse(m.json())
        assertEquals(m.version, back.version)
        assertEquals(listOf(b.path, a.path), back.files.map { it.path })
    }

    @Test
    fun onlySafePathsUnderTheAssetDirectories() {
        assertTrue(AssetManifest.safePath("glyphs/Noto Sans Regular/0-255.pbf"))
        assertTrue(AssetManifest.safePath("sprites/sprite@2x.png"))
        for (bad in listOf("../x", "glyphs/../../x.pbf", "/sprites/a", "sprites/a/b", "glyphs/a.pbf", "packs/x.pmtiles", "sprites\\a","glyphs/f/x.txt", "sprites/.", "c:/x")) {
            assertFalse(AssetManifest.safePath(bad), bad)
        }
        assertFailsWith<IllegalArgumentException> {
            AssetManifest.parse("""{"version":"x","files":[{"path":"../evil","url":"/x","sha256":"$h1"}]}""")
        }
        assertFailsWith<IllegalArgumentException> {
            AssetManifest.parse("""{"version":"x","files":[{"path":"sprites/a","url":"http://elsewhere/a","sha256":"$h1"}]}""")
        }
        assertFailsWith<IllegalArgumentException> {
            AssetManifest.parse("""{"version":"x","files":[{"path":"sprites/a","url":"/a","sha256":"nothex"}]}""")
        }
    }
}
