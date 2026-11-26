package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.UsageError
import io.github.jasondorjeshort.dystopo.aircraft.AircraftGeo
import io.github.jasondorjeshort.dystopo.aircraft.AircraftHttp
import io.github.jasondorjeshort.dystopo.aircraft.AircraftHttpReply
import io.github.jasondorjeshort.dystopo.aircraft.AircraftQuery
import io.github.jasondorjeshort.dystopo.aircraft.AircraftRules
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T365: the day's request count, the recorder without the network, and the report over a synthetic folder (no feed data). */
class FlightRecordTest {
    private fun tmp(): File = Files.createTempDirectory("t365").toFile().also { it.deleteOnExit() }
    private val day1 = Instant.parse("2026-11-25T10:00:00Z").toEpochMilli()
    private val day2 = Instant.parse("2026-11-26T00:00:01Z").toEpochMilli()

    @Test
    fun theDayCountRefusesPast240AndRollsAtTheUtcDay() {
        val d = RequestDay(File(tmp(), "aircraft/count.json"))
        assertEquals(0, d.read(day1).requests)
        repeat(236) { assertNotNull(d.spend(day1 + it)) }
        assertEquals(236, d.read(day1).requests)
        d.check(day1, 4) // 240: allowed
        val e = assertFailsWith<UsageError> { d.check(day1, 5) }
        assertTrue("236" in e.message!! && "240" in e.message!!, e.message)
        repeat(4) { assertNotNull(d.spend(day1)) }
        assertNull(d.spend(day1), "the 241st request of the day is not counted or sent")
        assertEquals(240, d.read(day1).requests)
        // The UTC day changes: a fresh count.
        assertEquals("2026-11-26", d.read(day2).utcDay)
        assertEquals(0, d.read(day2).requests)
        d.check(day2, 240)
        assertEquals(1, d.spend(day2)!!.requests)
        // Read from another instance on the same file (a second worktree): the same count.
        assertEquals(1, RequestDay(d.file).read(day2).requests)
    }

    private fun ac(hex: String, lat: Double, lon: Double, altFt: Double, gsMps: Double, track: Double, vr: Double = 0.0, extra: Map<String, Any?> = emptyMap()) =
        linkedMapOf<String, Any?>("hex" to hex, "type" to "adsb_icao", "lat" to lat, "lon" to lon, "alt_baro" to altFt, "gs" to gsMps / AircraftRules.KNOT_TO_MPS,
            "track" to track, "baro_rate" to vr, "seen_pos" to 1.0) + extra

    /** 80 answers at 30 s: a straight cruiser, a terminal aircraft turning 1 degree a second, a straight climber. */
    private fun syntheticFolder(): File {
        val dir = tmp()
        val t0 = day1
        for (i in 0 until 80) {
            val t = i * 30.0 - 1.0 // each position is 1 s old
            val a = AircraftGeo.move(46.8, -121.9, 0.0, 240.0 * t) // meridians: straight lines whose bearing stays put
            var lat = 46.7; var lon = -121.6; var tr = 0.0
            for (s in 0 until t.toInt().coerceAtLeast(0)) { val p = AircraftGeo.move(lat, lon, tr, 120.0); lat = p.first; lon = p.second; tr = (tr + 1.0) % 360 }
            val c = AircraftGeo.move(46.9, -121.7, 180.0, 150.0 * t)
            val body = JsonWriter.write(linkedMapOf("ac" to listOf(
                ac("syn101", a.first, a.second, 36_000.0, 240.0, 0.0, extra = mapOf("track_rate" to 0.0)),
                ac("syn102", lat, lon, 7_000.0, 120.0, tr),
                ac("syn103", c.first, c.second, 12_000.0 + 33 * t, 150.0, 180.0, vr = 2_000.0),
            ), "now" to t0 + i * 30_000))
            val ms = t0 + i * 30_000L
            FlightFolder.pollFile(dir, ms).writeText(body)
            FlightFolder.appendIndex(dir, linkedMapOf("i" to i, "ms" to ms, "outcome" to "ok", "aircraft" to 3))
        }
        FlightFolder.appendIndex(dir, linkedMapOf("i" to 80, "ms" to t0 + 80 * 30_000L, "outcome" to "failed", "status" to 503))
        FlightFolder.writeRecord(dir, linkedMapOf("area" to "rainier", "intervalS" to 30))
        return dir
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.m(k: String) = this[k] as Map<String, Any?>

    @Test
    fun theReportOverASyntheticFolderLiftsTheCapToTheHorizon() {
        val r = FlightReport.report(syntheticFolder(), null, listOf(2, 4, 10))
        assertEquals(80, r["answers"])
        assertEquals(3, r["aircraft"])
        assertEquals(mapOf("ok" to 80, "failed" to 1), r["outcomes"])
        @Suppress("UNCHECKED_CAST") val rows = r["horizons"] as List<Map<String, Any?>>
        assertEquals(listOf(30.0, 120.0, 300.0, 600.0), rows.map { it["horizonS"] })
        for (row in rows) {
            val cruise = row.m("cruise")
            assertEquals(1, cruise["aircraft"])
            assertTrue((cruise["fixes"] as Int) > 50, "fixes behind the cell: $cruise")
            // Straight flight: the guess runs to the truth's time at every horizon (capped at 30 s, 600 s would read 137 km).
            assertTrue((cruise["p99M"] as Long) < 50, "cruise at ${row["horizonS"]}: $cruise")
            assertEquals(1, row.m("terminal")["aircraft"])
            assertEquals(1, row.m("climbOrDescent")["aircraft"])
        }
        val turning = rows.map { (it.m("terminal")["medianM"] as Long) }
        assertTrue(turning.zipWithNext().all { (a, b) -> b > a }, "a turning aircraft's error grows with the horizon: $turning")
        // The turn of 1 degree a second at 120 m/s keeps it on a circle of 6.9 km radius while the guess runs straight on: 72 km at 600 s.
        assertTrue(turning[1] > 3_000 && turning[3] > 55_000, "$turning")
        // The climb is straight, so its guess is good too (altitude is not compared).
        assertTrue((rows[3].m("climbOrDescent")["p99M"] as Long) < 50)
        @Suppress("UNCHECKED_CAST") val ks = r["everyK"] as List<Map<String, Any?>>
        assertEquals(listOf(60, 120, 300), ks.map { it["refreshS"] })
        val tk = ks.map { (it.m("terminal")["medianM"] as Long) }
        assertTrue(tk.zipWithNext().all { (a, b) -> b > a }, "slower polling draws the turning aircraft further off: $tk")
        for (k in ks) {
            assertEquals(0, k["missing"], "nothing dropped at ${k["refreshS"]} s")
            assertTrue((k.m("cruise")["p99M"] as Long) < 50)
        }
        val f = r.m("fields")
        assertEquals(240, f["entries"])
        assertEquals(80, f["trackRate"])
        assertEquals(0, f["roll"])
        assertEquals(240, f["track"])
        assertEquals(0, f["trueHeading"])
        assertEquals(240, f.m("airborne")["entries"])
        assertEquals(33.3, f.m("airborne")["trackRatePct"])
        // No aircraft is on the ground: that bin is empty and says so; one aircraft a phase is thin.
        assertEquals(0, rows[0].m("ground")["fixes"])
        assertTrue((rows[0].m("ground")["note"] as String).startsWith("empty"))
        assertTrue((rows[0].m("cruise")["note"] as String).startsWith("thin"))
        assertTrue("syn10" !in JsonWriter.write(r), "the report names no aircraft")
    }

    @Test
    fun theRecorderKeepsGoingPastAFailedPollAndCountsEveryRequest() {
        val dir = tmp()
        val d = RequestDay(File(dir, "cache/count.json"))
        var now = day1
        var n = 0
        val http = AircraftHttp { _, _ ->
            now += 300
            when (n++) {
                1 -> AircraftHttpReply(503, ByteArray(0))
                3 -> AircraftHttpReply(200, """{"ac":[],"now":$now}""".encodeToByteArray())
                else -> AircraftHttpReply(200, JsonWriter.write(linkedMapOf("ac" to listOf(ac("syn201", 46.8, -121.7, 30_000.0, 200.0, 90.0)), "now" to now)).encodeToByteArray())
            }
        }
        val folder = File(dir, "rec")
        val rec = FlightRecorder(folder, AircraftQuery(46.9, -121.8, 54), 30, 4, "rainier", d, http, clock = { now }, sleep = { now += it })
        assertEquals(true, rec.plan(now)["allowed"])
        val out = rec.run()
        assertEquals(4, out["requests"]); assertEquals(3, out["ok"]); assertEquals(1, out["empty"]); assertEquals(1, out["failed"])
        assertEquals(1, out["aircraftSeen"]); assertEquals(true, out["endedWell"]); assertEquals(4, out["dayCount"])
        assertEquals(4, File(folder, FlightFolder.INDEX).readLines().size)
        assertEquals(3, folder.listFiles { f -> f.name.startsWith("poll-") }!!.size)
        assertEquals(false, MiniJson.parseObject(File(folder, FlightFolder.RECORD).readText())["running"])
        // A second run the same UTC day that would pass 240 is refused with the count, and sends nothing.
        val again = FlightRecorder(File(dir, "rec2"), AircraftQuery(46.9, -121.8, 54), 30, 237, "rainier", d, http, clock = { now }, sleep = { now += it })
        val e = assertFailsWith<UsageError> { again.run() }
        assertTrue("has 4 feed requests" in e.message!!, e.message)
        assertEquals(4, n)
    }
}
