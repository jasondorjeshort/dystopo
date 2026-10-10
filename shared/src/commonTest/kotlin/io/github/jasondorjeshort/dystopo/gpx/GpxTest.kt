package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.Uuid
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxTest {
    private val sample = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="unit test" xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <metadata><name>Sample</name><time>2020-01-02T03:04:05Z</time><link href="https://example.com"/></metadata>
  <wpt lat="0.001" lon="-0.002">
    <ele>101.5</ele><time>2020-01-02T03:04:05Z</time><name>Hut &amp; spring</name>
    <cmt><![CDATA[water <usually>]]></cmt><desc>Desc</desc><sym>Flag, Blue</sym><type>water</type><fix>3d</fix>
    <extensions><x:note xmlns:x="urn:x">keep me</x:note></extensions>
  </wpt>
  <rte><name>Plan</name><rtept lat="0" lon="0"><name>A</name></rtept><rtept lat="0.01" lon="0.01"><ele>12</ele></rtept></rte>
  <trk>
    <name>Walk</name><type>hiking</type><number>1</number>
    <trkseg>
      <trkpt lat="0.0" lon="0.0"><ele>100</ele><time>2020-01-02T03:04:05Z</time>
        <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr><gpxtpx:cad>80</gpxtpx:cad></gpxtpx:TrackPointExtension></extensions>
      </trkpt>
      <trkpt lat="0.001" lon="0.0"><ele>110</ele><time>2020-01-02T03:05:05Z</time><hdop>1.2</hdop></trkpt>
      <trkpt lat="91" lon="0"/>
    </trkseg>
    <trkseg><trkpt lat="0.002" lon="0.0"/></trkseg>
  </trk>
  <extensions><app:settings xmlns:app="urn:app" color="red"/></extensions>
</gpx>"""

    @Test
    fun importsEverythingTheModelHolds() {
        val (doc, ignored) = Gpx.read(sample, UuidGenerator(Random(1)))
        assertEquals("unit test", doc.creator)
        assertEquals("Sample", doc.metadata?.name)
        assertEquals(GpxFormat.parseTime("2020-01-02T03:04:05Z"), doc.metadata?.time)
        assertEquals(mapOf("gpxtpx" to Gpx.NS_GARMIN_TPX1), doc.namespaces)

        val w = doc.waypoints.single()
        assertTrue(Uuid.isValid(w.id))
        assertEquals("Hut & spring", w.name)
        assertEquals(0.001, w.lat)
        assertEquals(-0.002, w.lon)
        assertEquals(101.5, w.ele)
        assertEquals("water <usually>", w.comment)
        assertEquals("Desc", w.description)
        assertEquals("Flag, Blue", w.symbol)
        assertEquals("water", w.type)
        assertEquals("<x:note xmlns:x=\"urn:x\">keep me</x:note>", w.extensions)

        val r = doc.routes.single()
        assertEquals("Plan", r.name)
        assertEquals(listOf("A", null), r.points.map { it.name })
        assertEquals(12.0, r.points[1].ele)

        val t = doc.tracks.single()
        assertEquals("Walk", t.name)
        assertEquals("hiking", t.type)
        assertEquals(listOf(2, 1), t.segments.map { it.points.size }) // lat 91 dropped
        val p = t.segments[0].points[0]
        assertEquals(120.0, GpxExtensions.heartRate(p.extensions))
        assertEquals(80.0, GpxExtensions.cadence(p.extensions))
        assertNull(GpxExtensions.temperature(p.extensions))
        assertEquals(60_000L, t.segments[0].points[1].time!! - p.time!!)
        assertNull(t.segments[1].points[0].ele)
        assertEquals("<app:settings xmlns:app=\"urn:app\" color=\"red\"/>", doc.extensions)

        assertEquals(mapOf("link" to 1, "fix" to 1, "number" to 1, "hdop" to 1, "trkpt (bad lat/lon)" to 1), ignored)
    }

    @Test
    fun roundTripsSample() {
        val doc = Gpx.parse(sample, UuidGenerator(Random(1)))
        val written = Gpx.write(doc)
        val again = Gpx.parse(written, UuidGenerator(Random(1)))
        assertEquals(doc, again)
        // and the written text is stable
        assertEquals(written, Gpx.write(again))
        assertTrue("xmlns:gpxtpx=\"${Gpx.NS_GARMIN_TPX1}\"" in written)
        assertTrue("<name>Hut &amp; spring</name>" in written)
    }

    @Test
    fun roundTripsSyntheticTrackWithSegmentsAndExtensions() {
        val terrain = SyntheticTerrain.CONE
        val a = SyntheticTracks.loop(terrain, 0.0, 0.0, 1200.0, 300)
        val b = SyntheticTracks.outAndBack(terrain, 2500.0, 0.0, 0.0, 0.0, 50)
        val ids = UuidGenerator(Random(11))
        val doc = GpxDocument(
            waypoints = listOf(
                Waypoint(ids.next(), "Summit <cone>", 0.0, 0.0, terrain.elevationAt(0.0, 0.0), 1_700_000_000_000L, symbol = "Summit"),
                Waypoint(ids.next(), "", 0.01, -0.01), // nameless
            ),
            routes = listOf(Route(ids.next(), "R", listOf(RoutePoint(0.0, 0.0), RoutePoint(1e-7, -1e-7, ele = 3.25, name = "tiny")))),
            tracks = listOf(
                SyntheticTracks.track("loop + spur", a, b).copy(id = ids.next(), description = "two segments"),
                SyntheticTracks.track("bare").copy(id = ids.next(), segments = listOf(TrackSegment(listOf(TrackPoint(1.0, 2.0))))),
            ),
            metadata = GpxMetadata(name = "synthetic", time = 1_700_000_000_123L),
        )
        val text = Gpx.write(doc)
        val back = Gpx.parse(text, UuidGenerator(Random(11)))
        // Ids are regenerated in document order (waypoints, routes, tracks), the same order as above.
        assertEquals(doc.copy(namespaces = mapOf("gpxtpx" to Gpx.NS_GARMIN_TPX1)), back)
        assertEquals(301 + 101, back.tracks[0].points.size)
        assertNull(Regex("\\d[eE][-+]?\\d").find(text), "no exponent notation")
    }

    @Test
    fun acceptsGpx10AndNoNamespace() {
        val gpx10 = """<gpx version="1.0" creator="old" xmlns="http://www.topografix.com/GPX/1/0">
            <name>Old file</name><time>2004-05-06T07:08:09Z</time>
            <trk><trkseg><trkpt lat="1" lon="2"><ele>3</ele><speed>1.5</speed><course>90</course></trkpt></trkseg></trk></gpx>"""
        val (doc, ignored) = Gpx.read(gpx10)
        assertEquals("Old file", doc.metadata?.name)
        assertEquals(GpxFormat.parseTime("2004-05-06T07:08:09Z"), doc.metadata?.time)
        assertEquals(TrackPoint(1.0, 2.0, 3.0), doc.tracks.single().points.single())
        assertEquals(mapOf("speed" to 1, "course" to 1), ignored)

        val bare = Gpx.parse("<gpx><wpt lat='1' lon='2'><name> padded </name></wpt></gpx>")
        assertEquals("padded", bare.waypoints.single().name)
        assertEquals(Gpx.CREATOR, bare.creator)

        val prefixed = Gpx.parse("<g:gpx xmlns:g='http://www.topografix.com/GPX/1/1'><g:wpt lat='1' lon='2'><g:name>P</g:name></g:wpt></g:gpx>")
        assertEquals("P", prefixed.waypoints.single().name)
    }

    @Test
    fun foreignElementsOutsideExtensionsAreSkipped() {
        val (doc, ignored) = Gpx.read(
            "<gpx xmlns='http://www.topografix.com/GPX/1/1' xmlns:o='urn:other'><wpt lat='1' lon='2'><o:name>not gpx</o:name><name>gpx</name></wpt></gpx>",
        )
        assertEquals("gpx", doc.waypoints.single().name)
        assertEquals(mapOf("o:name" to 1), ignored)
    }

    @Test
    fun rejectsNonGpx() {
        assertFailsWith<GpxException> { Gpx.parse("<kml/>") }
        assertFailsWith<GpxException> { Gpx.parse("<!-- nothing -->") }
        assertFailsWith<XmlException> { Gpx.parse("<gpx><wpt lat='1' lon='2'></gpx>") }
    }

    @Test
    fun byteOrderMarkAndEmptyExtensions() {
        val doc = Gpx.parse("﻿<gpx><trk><trkseg><trkpt lat='1' lon='2'><extensions>  </extensions></trkpt></trkseg></trk></gpx>")
        assertNull(doc.tracks.single().points.single().extensions)
    }
}
