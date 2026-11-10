package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.app.nav.wayBackCardLabel
import io.github.jasondorjeshort.dystopo.app.nav.wayBackChipText
import io.github.jasondorjeshort.dystopo.app.savedFiles
import io.github.jasondorjeshort.dystopo.app.savedSome
import io.github.jasondorjeshort.dystopo.app.shareFailed
import io.github.jasondorjeshort.dystopo.app.shareSubject
import io.github.jasondorjeshort.dystopo.app.shareTitle
import io.github.jasondorjeshort.dystopo.app.uniqueNames
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T272: the notification's actions, the waypoint as the return point, and the share sheet's words (pure). */
class T272SentencesTest {
    @Test
    fun theNotificationFollowsThePanelsTwoSteps() {
        assertEquals(listOf("Pause"), notificationActions(RecState.RECORDING, serviceDown = false), "recording: Pause only, no Stop")
        assertEquals(listOf("Pause"), notificationActions(RecState.WAITING_FOR_GPS, serviceDown = false))
        assertEquals(listOf("Pause"), notificationActions(RecState.AUTO_PAUSED, serviceDown = false), "auto-paused is still recording")
        assertEquals(listOf("Resume", "Finish"), notificationActions(RecState.PAUSED, serviceDown = false))
        assertEquals(listOf("Finish"), notificationActions(RecState.STOPPED_BY_SYSTEM, serviceDown = true), "the system stopped it: Finish saves")
        assertEquals(listOf("Pause"), notificationActions(RecState.STOPPED_BY_SYSTEM, serviceDown = false), "resumed after a process death")
        assertEquals(emptyList(), notificationActions(RecState.IDLE, serviceDown = false))
        for (s in RecState.entries) for (d in listOf(true, false)) assertTrue("Stop" !in notificationActions(s, d), "no Stop in $s")
        assertEquals(recordGroup(recording = true, paused = true, starting = false), notificationActions(RecState.PAUSED, false), "the panel's words")
    }

    private fun p(lat: Double, lon: Double, t: Long?) = TrackPoint(lat, lon, time = t)

    @Test
    fun aWaypointResolvesToTheNearestPointRecordedBeforeItWasChosen() {
        // Out along a line north, then back south past the waypoint.
        val out = (0..10).map { p(46.80 + it * 0.001, -121.73, 1_000L * it) }
        val back = out.reversed().mapIndexed { k, q -> q.copy(time = 11_000L + 1_000L * k) }
        val pts = out + back
        // Chosen at 10 s (the walker at the top): the nearest point on the way out.
        assertEquals(4, nearestIndex(pts, 46.804, -121.7301, upToMs = 10_000L))
        // Chosen later, the way back counts too; ties go to the earliest.
        assertEquals(4, nearestIndex(pts, out[4].lat, out[4].lon, upToMs = 21_000L), "the earliest of two equal points")
        // Moved: resolved again, still bounded by the choice's time.
        assertEquals(9, nearestIndex(pts, 46.8092, -121.73, upToMs = 10_000L))
        // No point before the choice.
        assertNull(nearestIndex(pts, 46.804, -121.73, upToMs = -1L))
        assertEquals(0, nearestIndex(listOf(p(46.8, -121.7, null)), 0.0, 0.0, 0L), "a point with no time counts")
        assertEquals(111.2, metresBetween(46.8, -121.73, 46.801, -121.73), 0.5)
    }

    @Test
    fun theCardTheChipAndTheToastNameTheWaypoint() {
        assertEquals("Back to Camp. Sunset 19:12, 30 min margin", wayBackCardLabel("Sunset 19:12, 30 min margin", "Camp"))
        assertEquals("Sunset 19:12", wayBackCardLabel("Sunset 19:12", null))
        assertEquals("Turn-back time to Camp", wayBackChipText("Camp"))
        assertEquals("Turn-back time", wayBackChipText(null))
        assertEquals("The way back now goes to Camp", wayBackToWaypoint("Camp"))
        assertEquals("The way back now goes to the waypoint", wayBackToWaypoint(" "))
    }

    @Test
    fun theShareSheetsWords() {
        assertEquals("Share GPX", shareTitle(1))
        assertEquals("Share 3 GPX files", shareTitle(3))
        assertEquals("Morning walk", shareSubject(listOf("Morning walk")))
        assertEquals("2 tracks and routes", shareSubject(listOf("a", "b")))
        assertEquals("Could not share: disk full", shareFailed("disk full"))
        assertEquals("Saved 1 GPX file", savedFiles(1))
        assertEquals("Saved 4 GPX files", savedFiles(4))
        assertEquals("Saved 2 of 3 GPX files", savedSome(2, 3))
        assertEquals(listOf("walk.gpx", "Walk (2).gpx", "lake.gpx", "walk (3).gpx"), uniqueNames(listOf("walk.gpx", "Walk.gpx", "lake.gpx", "walk.gpx")))
    }
}
