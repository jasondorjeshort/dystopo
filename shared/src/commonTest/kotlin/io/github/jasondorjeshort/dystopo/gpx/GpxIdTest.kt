package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.Waypoint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Our ids through GPX (T40): the `dystopo:id` extension. */
class GpxIdTest {
    private val doc = GpxDocument(
        waypoints = listOf(Waypoint("0d4c1f6e-2a1b-4c3d-8e9f-001122334455", "Camp", 46.8, -121.7, extensions = "<x:note>keep</x:note>")),
        routes = listOf(Route("1d4c1f6e-2a1b-4c3d-8e9f-001122334455", "Plan", listOf(RoutePoint(46.8, -121.7), RoutePoint(46.81, -121.71)))),
        tracks = listOf(
            Track(
                "2d4c1f6e-2a1b-4c3d-8e9f-001122334455", "Walk",
                listOf(TrackSegment(listOf(TrackPoint(46.8, -121.7, 1600.0, 1_000), TrackPoint(46.801, -121.701, 1610.0, 2_000)))),
                extensions = "<x:color>red</x:color>",
            ),
        ),
        namespaces = mapOf("x" to "urn:example"),
    )

    @Test
    fun idsSurviveWriteAndRead() {
        val text = Gpx.write(doc)
        assertTrue("xmlns:dystopo=\"${GpxExtensions.NS_DYSTOPO}\"" in text)
        val back = Gpx.parse(text, UuidGenerator(Random(1)))
        assertEquals(doc, back.copy(metadata = null))
        // Stable: writing what was read gives the same text.
        assertEquals(text, Gpx.write(back))
    }

    @Test
    fun withoutIdsEveryReadIsNew() {
        val text = Gpx.write(doc, ids = false)
        assertFalse("dystopo" in text)
        val back = Gpx.parse(text, UuidGenerator(Random(1)))
        assertNotEquals(doc.tracks.single().id, back.tracks.single().id)
        assertEquals("<x:color>red</x:color>", back.tracks.single().extensions)
    }

    @Test
    fun anyPrefixBoundToOurNamespaceWorksAndOthersDont() {
        val id = "3d4c1f6e-2a1b-4c3d-8e9f-00112233445A"
        fun gpx(decl: String, prefix: String, value: String = id) =
            """<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" $decl><trk><name>t</name><extensions><$prefix:id>$value</$prefix:id></extensions></trk></gpx>"""
        val ours = Gpx.parse(gpx("xmlns:d=\"${GpxExtensions.NS_DYSTOPO}\"", "d")).tracks.single()
        assertEquals(id.lowercase(), ours.id)
        assertEquals(null, ours.extensions)
        // Same local name in another namespace: not ours, kept as an extension.
        val other = Gpx.parse(gpx("xmlns:d=\"urn:other\"", "d")).tracks.single()
        assertNotEquals(id.lowercase(), other.id)
        assertEquals("<d:id>$id</d:id>", other.extensions)
        // Not a UUID: ignored and kept.
        val bad = Gpx.parse(gpx("xmlns:d=\"${GpxExtensions.NS_DYSTOPO}\"", "d", "3d4c1f6e-2a1b-4c3d-8e9f-00112233445G")).tracks.single()
        assertTrue(bad.extensions!!.contains("00112233445G"))
    }
}
