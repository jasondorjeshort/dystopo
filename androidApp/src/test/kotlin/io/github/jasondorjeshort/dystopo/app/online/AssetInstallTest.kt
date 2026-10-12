package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.style.AssetManifest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T74: a changed sprite sheet or glyph range reaches a device that already has the old files. */
class AssetInstallTest {
    private val root: File = Files.createTempDirectory("assets").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun onlyChangedFilesAreFetchedAndCheckedAgainstTheManifest() {
        val oldSprite = "old sheet".toByteArray()
        val newSprite = "new sheet".toByteArray()
        val glyph = "glyph range".toByteArray()
        File(root, "sprites").mkdirs()
        File(root, "sprites/sprite.png").writeBytes(oldSprite)
        File(root, "glyphs/Noto Sans Regular").mkdirs()
        File(root, "glyphs/Noto Sans Regular/0-255.pbf").writeBytes(glyph)
        val served = mapOf("/sprite.png" to newSprite, "/glyphs/Noto%20Sans%20Regular/0-255.pbf" to glyph, "/sprite.json" to "{}".toByteArray())
        val asked = ArrayList<String>()
        val http = HttpGet { url -> val p = url.removePrefix("http://h:1"); asked += p; served[p]?.let { HttpReply(200, it) } ?: HttpReply(404, ByteArray(0)) }
        val m = AssetManifest.of(served.map { (url, bytes) ->
            val path = if (url.startsWith("/glyphs/")) "glyphs/Noto Sans Regular/0-255.pbf" else "sprites" + url
            AssetManifest.Entry(path, url, OnlineService.sha256(bytes), bytes.size.toLong())
        })
        val r = OnlineService.installFromManifest("http://h:1", m, http, root)
        assertEquals(2, r["installed"])
        assertEquals(1, r["replaced"])
        assertEquals(1, r["present"])
        assertEquals(listOf("/sprite.json", "/sprite.png"), asked.sorted(), "the unchanged glyph range is not fetched")
        assertContentEquals(newSprite, File(root, "sprites/sprite.png").readBytes())
        // A body that does not match its hash is refused and the old file stays.
        val lying = AssetManifest.of(listOf(AssetManifest.Entry("sprites/sprite.png", "/sprite.json", OnlineService.sha256("other".toByteArray()), 5)))
        val bad = OnlineService.installFromManifest("http://h:1", lying, http, root)
        assertEquals(0, bad["installed"])
        assertTrue((bad["failed"] as List<*>).single().toString().contains("hash"))
        assertContentEquals(newSprite, File(root, "sprites/sprite.png").readBytes())
    }
}
