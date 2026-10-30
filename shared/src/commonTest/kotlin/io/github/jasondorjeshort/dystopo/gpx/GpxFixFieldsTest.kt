package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.GapReason
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackGap
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T158: a point's accuracy, speed, course, vertical accuracy and flags, and a track's activity, profile and gaps, in GPX. */
class GpxFixFieldsTest {
    private val id = "00000000-0000-4000-8000-0000000000a1"
    private val t0 = 1_790_000_000_000L

    private val track = Track(
        id, "Fields",
        listOf(
            TrackSegment(listOf(
                TrackPoint(46.85, -121.73, 1600.0, t0, null, 4.0, 1.3, 90.5, 6.0),
                TrackPoint(46.8501, -121.73, 1601.0, t0 + 1000, "<gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension>", 100.0, null, null, null, TrackPoint.INACCURATE),
            )),
            TrackSegment(listOf(TrackPoint(46.86, -121.73, 1610.0, t0 + 400_000, null, 0.5, 0.0, 359.99, null, TrackPoint.NETWORK))),
        ),
        activity = Activity.FOOT,
        profile = ActivityProfile.of(Activity.FOOT).copy(autoPause = true),
        gaps = listOf(TrackGap(t0 + 1000, t0 + 400_000, GapReason.NO_FIX), TrackGap(t0 + 1000, t0 + 400_000, GapReason.PAUSED)),
    )

    @Test
    fun roundTripsEveryField() {
        for (ids in listOf(true, false)) {
            val text = Gpx.write(GpxDocument(tracks = listOf(track)), ids = ids)
            assertTrue("xmlns:dystopo=\"${GpxExtensions.NS_DYSTOPO}\"" in text, "our namespace is declared")
            assertTrue("<dystopo:fix acc=\"4\" speed=\"1.3\" course=\"90.5\" vacc=\"6\"/>" in text, text)
            assertTrue("<dystopo:gap start=\"2026-09-21T14:13:21Z\" end=\"2026-09-21T14:20:00Z\" reason=\"no-fix\"/>" in text, text)
            val back = Gpx.parse(text).tracks.single()
            assertEquals(if (ids) track else track.copy(id = back.id), back)
            assertEquals(text, Gpx.write(GpxDocument(tracks = listOf(back)), ids = ids), "write, read, write")
        }
    }

    @Test
    fun aFileWithoutThemReadsAsBefore() {
        val plain = Gpx.write(GpxDocument(tracks = listOf(Track(id, "Plain", listOf(TrackSegment(listOf(TrackPoint(46.85, -121.73, 1600.0, t0))))))), ids = false)
        assertTrue("dystopo" !in plain)
        val back = Gpx.parse(plain).tracks.single()
        assertNull(back.activity)
        assertNull(back.profile)
        assertEquals(emptyList(), back.gaps)
        assertTrue(back.points.none { it.hasFixFields })
    }

    /** Import tolerates what other writers may do: another prefix, the long form, junk values, unknown codes, our names in a foreign namespace. */
    @Test
    fun importTolerates() {
        val text = """<?xml version="1.0"?>
            <gpx version="1.1" creator="x" xmlns="http://www.topografix.com/GPX/1/1" xmlns:d="${GpxExtensions.NS_DYSTOPO}" xmlns:o="urn:other">
              <trk><name>T</name><extensions><d:activity>hovercraft</d:activity><d:profile>activity=bike;acc=20</d:profile>
                <d:gap start="2026-09-21T14:13:21Z" end="2026-09-21T14:13:31Z" reason="no-fix"></d:gap><d:gap start="bad" end="2026-09-21T14:13:31Z" reason="no-fix"/>
                <d:gap start="2026-09-21T14:13:21Z" end="2026-09-21T14:13:31Z" reason="eclipse"/><o:keep>1</o:keep></extensions>
                <trkseg>
                  <trkpt lat="46.85" lon="-121.73"><extensions><d:fix acc='7' speed="fast" flags="7"/></extensions></trkpt>
                  <trkpt lat="46.85" lon="-121.73"><extensions><o:fix acc="7"/></extensions></trkpt>
                </trkseg></trk></gpx>"""
        val t = Gpx.parse(text).tracks.single()
        assertNull(t.activity, "an unknown activity is skipped")
        assertEquals(ActivityProfile.of(Activity.BIKE).copy(accuracyBoundM = 20.0), t.profile)
        assertEquals(listOf(TrackGap(1_790_000_001_000L, 1_790_000_011_000L, GapReason.NO_FIX)), t.gaps)
        assertEquals("<o:keep>1</o:keep>", t.extensions)
        val (p, q) = t.points
        assertEquals(7.0, p.accuracyM)
        assertNull(p.speedMps)
        assertEquals(TrackPoint.INACCURATE or TrackPoint.NETWORK, p.flags, "unknown flag bits dropped")
        assertNull(p.extensions)
        assertNull(q.accuracyM, "another namespace's fix is not ours")
        assertEquals("<o:fix acc=\"7\"/>", q.extensions)
    }
}
