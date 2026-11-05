package io.github.jasondorjeshort.dystopo.app.packs

import io.github.jasondorjeshort.dystopo.app.packs.DownloadStatus.Action
import io.github.jasondorjeshort.dystopo.app.packs.DownloadStatus.Failure
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.packs.PackRole
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T222: what a download's row says and offers: the failure sentences, the paused state from what a kill leaves, the free-space check. */
class DownloadStatusTest {
    private val dir: File = Files.createTempDirectory("download-status").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aFailureSaysWhatHappenedAndOffersRetryAndRemove() {
        val row = DownloadStatus.of("failed", Failure.CONNECTION)
        assertEquals("stopped", row.word)
        assertEquals("Download stopped: the connection dropped.", row.sentence)
        assertEquals(listOf(Action.RETRY, Action.REMOVE), row.actions)
        assertEquals("Download stopped: the server is not answering.", DownloadStatus.of("failed", Failure.SERVER).sentence)
        assertEquals("Download stopped: the server no longer has this file.", DownloadStatus.of("failed", Failure.NOT_FOUND).sentence)
        assertEquals("Download stopped: the file arrived damaged.", DownloadStatus.of("failed", Failure.DAMAGED).sentence)
        assertEquals("Download stopped: the map could not be built.", DownloadStatus.of("failed", Failure.BUILD).sentence)
        assertEquals("Download stopped: something went wrong.", DownloadStatus.of("failed", Failure.OTHER).sentence)
        assertEquals("Download stopped.", DownloadStatus.of("failed").sentence)
    }

    @Test
    fun noSentenceCarriesARawError() {
        for (f in Failure.entries) {
            val s = DownloadStatus.stoppedSentence(f)
            assertTrue(!Regex("""HTTP|sha256|pmtiles|Exception|reset""", RegexOption.IGNORE_CASE).containsMatchIn(s), s)
        }
    }

    @Test
    fun exceptionsReadAsTheirCause() {
        for (e in listOf(SocketException("Connection reset"), ConnectException("failed to connect"), SocketTimeoutException("timeout"), UnknownHostException("no host"), EOFException("eof"))) {
            assertEquals(Failure.CONNECTION, DownloadStatus.classify(e), e.toString())
        }
        assertEquals(Failure.CONNECTION, DownloadStatus.classify(IOException("wrapped", SocketException("Software caused connection abort"))))
        assertEquals(Failure.NOT_FOUND, DownloadStatus.classify(IOException("http://h/x-dem.pmtiles: not found (HTTP 404)")))
        assertEquals(Failure.SERVER, DownloadStatus.classify(IOException("http://h/x-dem.pmtiles: HTTP 503")))
        assertEquals(Failure.BUILD, DownloadStatus.classify(IOException("server build failed: no data")))
        assertEquals(Failure.BUILD, DownloadStatus.classify(IOException("server refused the area (HTTP 400): too large")))
        assertEquals(Failure.DAMAGED, DownloadStatus.classify(PackDownloader.HashMismatch("x: hash mismatch")))
        assertEquals(Failure.DAMAGED, DownloadStatus.classify(IOException("x-dem.pmtiles: not a PMTiles archive (bad magic); part file deleted")))
        assertEquals(Failure.REFUSED, DownloadStatus.classify(PackDownloader.RedirectRefused("x: too many redirects")))
        assertEquals(Failure.SPACE, DownloadStatus.classify(PackDownloader.NotEnoughSpace(1)))
        assertEquals(Failure.SPACE, DownloadStatus.classify(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(Failure.OTHER, DownloadStatus.classify(IllegalStateException("what")))
    }

    @Test
    fun theFreeSpaceCheckSaysHowMuchToFreeAndOffersStorage() {
        assertNull(DownloadStatus.shortBy(100, 100), "just fits")
        assertNull(DownloadStatus.shortBy(0, 0), "nothing needed")
        assertEquals(1L, DownloadStatus.shortBy(101, 100))
        val row = DownloadStatus.of("failed", Failure.SPACE, 300L * 1_048_576)
        assertEquals("stopped", row.word)
        assertEquals("Not enough space. Free 300 MB or remove a download.", row.sentence)
        assertEquals(listOf(Action.STORAGE, Action.RETRY, Action.REMOVE), row.actions)
        // Never less than what is short, in a figure a person reads.
        assertEquals("Not enough space. Free 300 MB or remove a download.", DownloadStatus.spaceSentence(299L * 1_048_576 + 1))
        assertEquals("Not enough space. Free 1.3 GB or remove a download.", DownloadStatus.spaceSentence(1_300L * 1_048_576))
        assertEquals("Not enough space. Free some space or remove a download.", DownloadStatus.spaceSentence(null))
        assertEquals("Not enough space. Free 1 MB or remove a download.", PackDownloader.NotEnoughSpace(1).message)
    }

    private fun stateFile(update: Boolean = false, demTotal: Long? = 800, vectorTotal: Long? = 200): String {
        fun t(n: Long?) = n?.toString() ?: "null"
        return """{"name":"rainier","demUrl":"http://h/out/rainier-dem.pmtiles","vectorUrl":"http://h/out/rainier-outdoors.pmtiles","activate":true,
            "createdAt":1700000000000,"restarts":2,"update":$update,"catalog":{"server":"http://h","id":"rainier"},
            "files":[{"role":"${PackRole.VECTOR.label}","total":${t(vectorTotal)},"etag":"v","lastModified":null,"status":"downloading","expectSha256":null,"expectBytes":null},
            {"role":"${PackRole.DEM.label}","total":${t(demTotal)},"etag":"d","lastModified":null,"status":"pending","expectSha256":null,"expectBytes":null}],
            "graphOnly":false,
            "region":{"area":{"kind":"box","bbox":[-121.8,46.8,-121.7,46.9],"minZoom":8,"maxZoom":14,"name":"rainier"},"source":"http://h","startedAt":1700000000000,"estimateBytes":1000}}"""
    }

    @Test
    fun aStateFileAndAPartialFileMakeThePausedRow() {
        File(dir, PackNames.fileName("rainier", PackRole.VECTOR) + ".part").writeBytes(ByteArray(200))
        File(dir, PackNames.fileName("rainier", PackRole.DEM) + ".part").writeBytes(ByteArray(200))
        val saved = DownloadStatus.Saved.parse(stateFile())
        assertEquals("rainier", saved.name)
        assertEquals(2, saved.restarts)
        assertEquals(400L to 1000L, saved.progress(dir))
        val row = DownloadStatus.of("paused", percent = saved.percent(dir))
        assertEquals("paused", row.word)
        assertEquals("Paused at 40%. Continues when you are online", row.sentence)
        assertEquals(listOf(Action.RESUME_NOW, Action.REMOVE), row.actions)
        assertEquals("http://h", saved.region?.get("source"), "the wrap-up rode through the kill")
    }

    @Test
    fun aPausedRowWithoutEverySizeHasNoPercent() {
        val saved = DownloadStatus.Saved.parse(stateFile(demTotal = null))
        assertNull(saved.percent(dir))
        assertEquals("Paused. Continues when you are online", DownloadStatus.of("paused", percent = saved.percent(dir)).sentence)
    }

    @Test
    fun aJobPickedUpAtLaunchReadsAsPausedUntilTheBytesFlow() {
        assertEquals("paused", DownloadStatus.of("running", percent = 40.0, awaiting = true).word)
        assertEquals("paused", DownloadStatus.of("queued", percent = 40.0, awaiting = true).word)
        assertEquals("downloading", DownloadStatus.of("running", percent = 40.0, awaiting = false).word)
        assertEquals(emptyList(), DownloadStatus.of("running").actions)
        assertEquals("waiting", DownloadStatus.of("queued").word)
        // Never reads as finished before it is.
        assertEquals("Paused at 99%. Continues when you are online", DownloadStatus.pausedSentence(99.97))
    }

    @Test
    fun aRowHasOneWordAndCountsHaveASingular() {
        assertEquals("PRIMARY", DownloadStatus.packWord(primary = true, onMap = true, usable = true, hasDem = true, hasVector = true))
        assertEquals("ON MAP", DownloadStatus.packWord(primary = false, onMap = true, usable = true, hasDem = true, hasVector = true))
        assertEquals("INCOMPLETE", DownloadStatus.packWord(primary = false, onMap = false, usable = false, hasDem = true, hasVector = true))
        assertEquals("MAP ONLY", DownloadStatus.packWord(primary = false, onMap = false, usable = false, hasDem = false, hasVector = true))
        assertEquals("1 pack", DownloadStatus.count(1, "pack"))
        assertEquals("0 packs", DownloadStatus.count(0, "pack"))
        assertEquals("3 packs", DownloadStatus.count(3, "pack"))
        assertEquals("3.4 MB", DownloadStatus.sizeText(3_565_158))
        assertEquals("212 MB", DownloadStatus.sizeText(212L * 1_048_576))
        assertEquals("1.2 GB", DownloadStatus.sizeText(1229L * 1_048_576))
    }
}
