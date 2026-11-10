package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.app.gpxFileName
import io.github.jasondorjeshort.dystopo.app.routing.ImportText
import io.github.jasondorjeshort.dystopo.app.saveFailed
import io.github.jasondorjeshort.dystopo.app.saved
import io.github.jasondorjeshort.dystopo.app.sync.SYNC_NO_PEER
import io.github.jasondorjeshort.dystopo.app.sync.syncFailedSentence
import io.github.jasondorjeshort.dystopo.app.sync.syncedSentence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T219: what the Tracks sheet (T262: the Tracks and routes screen) says: its title and row, import, export and sync (ADR 0031's strings, pure). */
class TrackSentencesTest {
    @Test
    fun theListsRowLine() { // T262: the Tracks and routes screen's track line (T219's sheet row before)
        assertEquals("On foot · Nov 3 · 312 ft", io.github.jasondorjeshort.dystopo.app.tracks.trackLine("On foot", "Nov 3", "312 ft", false))
        assertEquals("Track · Nov 3 · 0.2 mi · recording", io.github.jasondorjeshort.dystopo.app.tracks.trackLine(null, "Nov 3", "0.2 mi", true))
        assertEquals("Track · Nov 3", io.github.jasondorjeshort.dystopo.app.tracks.trackLine(null, "Nov 3", null, false))
    }

    @Test
    fun theImportListHasNoPrivatePath() = assertFalse("/" in IMPORT_LIST_EMPTY)

    @Test
    fun aRouteImportsSentences() {
        assertEquals("Importing walk.gpx", ImportText.start("walk.gpx"))
        assertEquals("Added 1 route from walk.gpx", ImportText.routes(1, 0, "walk.gpx"))
        assertEquals("Added 2 routes from walk.gpx", ImportText.routes(2, 1, "walk.gpx"))
        assertEquals("Already added", ImportText.routes(0, 1, "walk.gpx"))
        assertEquals("That file has no route or track", ImportText.routes(0, 0, "walk.gpx"))
        assertEquals("Could not read walk.gpx", ImportText.failed("walk.gpx"))
    }

    @Test
    fun aTrackImportsSentences() {
        assertEquals("Added 1 track from walk.gpx", ImportText.tracks(1, 0, 0, "walk.gpx"))
        assertEquals("Added 2 tracks and 1 waypoint from walk.gpx", ImportText.tracks(2, 0, 1, "walk.gpx"))
        assertEquals("Added 3 waypoints from walk.gpx", ImportText.tracks(0, 0, 3, "walk.gpx"))
        assertEquals("Already added", ImportText.tracks(0, 1, 0, "walk.gpx"))
        assertEquals("Already added", ImportText.tracks(0, 0, 0, "walk.gpx"))
    }

    @Test
    fun nothingToImportIsFoundBehindTheStoresThread() {
        // Tracking.call hands a GpxException on as the cause (it read "Could not read" on the phone until this looked there).
        val bad = io.github.jasondorjeshort.dystopo.gpx.GpxException("no tracks or waypoints")
        assertTrue(ImportText.nothingToImport(bad))
        assertTrue(ImportText.nothingToImport(RuntimeException(java.util.concurrent.ExecutionException(bad))))
        assertFalse(ImportText.nothingToImport(java.io.IOException("cannot open")))
    }

    @Test
    fun whatAnExportSays() {
        assertEquals("Saved Paradise to Panorama Point as a GPX file", saved("Paradise to Panorama Point"))
        assertEquals("Could not save Walk: no room", saveFailed("Walk", "no room"))
    }

    @Test
    fun theSaveDialogsFileName() {
        assertEquals("Paradise to Panorama Point.gpx", gpxFileName("Paradise to Panorama Point"))
        assertEquals("Track 2026-11-03 04-39.gpx", gpxFileName("Track 2026-11-03 04:39"))
        assertEquals("a-b-c.gpx", gpxFileName("a/b\\c"))
        assertEquals("route.gpx", gpxFileName(" ... "))
        assertEquals(84, gpxFileName("x".repeat(200)).length)
    }

    @Test
    fun theSyncSentenceBeginsSyncedAndNamesNoAddress() {
        assertEquals("Synced with your server: 14 sent, nothing new received", syncedSentence("your server", 0, 14))
        assertEquals("Synced with Pixel: nothing sent, 3 received", syncedSentence("Pixel", 3, 0))
        assertEquals("Synced with Pixel: nothing sent, nothing new received", syncedSentence("Pixel", 0, 0))
        assertEquals("Synced with Pixel: 2 sent, 3 received, 1 edited on two devices", syncedSentence("Pixel", 3, 2, 1))
        assertTrue(syncedSentence("your server", 1, 1).startsWith("Synced"))
    }

    @Test
    fun aFailedSyncKeepsTheAddressOut() {
        assertEquals("Sync failed: could not reach your server", syncFailedSentence("your server", java.net.ConnectException("failed to connect to /192.0.2.1 (port 8984)")))
        assertEquals("Sync failed: your server answered with an error", syncFailedSentence("your server", IllegalStateException("POST http://192.0.2.1:8984/sync: HTTP 500")))
        assertTrue(SYNC_NO_PEER.endsWith("to sync"))
    }
}
