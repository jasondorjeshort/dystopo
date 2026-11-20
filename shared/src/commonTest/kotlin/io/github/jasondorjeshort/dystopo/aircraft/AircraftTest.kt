package io.github.jasondorjeshort.dystopo.aircraft

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/** T311: the aircraft layer's pure parts. */
class AircraftTest {
    private fun near(want: Double, got: Double?, tol: Double, what: String = "") {
        assertNotNull(got, what)
        assertTrue(abs(want - got) <= tol, "$what: want $want ± $tol, got $got")
    }

    // --- the parser, on a response in adsb.lol's recorded form (synthetic aircraft; the live poll is in the report) ---

    @Test
    fun parsesAReadsbResponse() {
        val poll = ReadsbParser.parse(AircraftFixtures.RESPONSE, 1_000_000L)
        assertEquals(3, poll.aircraft.size, "the entry without a position is skipped")
        val a = poll.aircraft[0]
        assertEquals("a1b2c3", a.hex)
        assertEquals("ASA1670", a.callSign, "the space-padded flight trimmed")
        assertEquals("N000AA", a.registration)
        assertEquals("B739", a.type)
        near(17025.0, a.altGeomFt, 0.0)
        near(16950.0, a.altBaroFt, 0.0)
        near(118.4, a.trackDeg, 1e-9)
        near(420.3, a.groundSpeedKt, 1e-9)
        near(1792.0, a.verticalRateFpm, 0.0, "geom_rate first")
        near(0.7, a.seenPosS, 1e-9)
        val ground = poll.aircraft[1]
        assertTrue(ground.onGround)
        assertNull(ground.altBaroFt)
        assertNull(ground.callSign)
        near(270.0, ground.trackDeg, 0.0, "true_heading when there is no track")
        val third = poll.aircraft[2]
        near(-640.0, third.verticalRateFpm, 0.0, "baro_rate when there is no geom_rate")
        assertNull(third.altGeomFt)
    }

    // --- altitude: the phone's undulation, the barometric fallback ---

    @Test
    fun undulationIsAppliedAndBarometricIsMarked() {
        val r = ReadsbParser.parse(AircraftFixtures.RESPONSE, 0).aircraft[0]
        val geo = AircraftTracker.altitude(r, -21.5)
        assertEquals(AltitudeKind.GEOMETRIC, geo.kind)
        near(17025 * 0.3048 + 21.5, geo.meters, 1e-6, "ellipsoid height minus a negative undulation")
        val noFix = AircraftTracker.altitude(r, null)
        assertEquals(AltitudeKind.BAROMETRIC, noFix.kind, "no undulation: barometric, marked")
        near(16950 * 0.3048, noFix.meters, 1e-6)
        val noGeom = AircraftTracker.altitude(ReadsbParser.parse(AircraftFixtures.RESPONSE, 0).aircraft[2], -21.5)
        assertEquals(AltitudeKind.BAROMETRIC, noGeom.kind, "no geometric altitude: barometric")
        assertEquals(AltitudeKind.GROUND, AircraftTracker.altitude(ReadsbParser.parse(AircraftFixtures.RESPONSE, 0).aircraft[1], null).kind)
        // The card says so.
        assertEquals("16,950 ft (pressure altitude), climbing", AircraftText.altitudeLine(noFix.meters, noFix.kind, 1792.0, ContourUnits.FEET))
        assertEquals("5,196 m, level", AircraftText.altitudeLine(5196.2, AltitudeKind.GEOMETRIC, 100.0, ContourUnits.METERS))
    }

    // --- bearing and elevation angle, hand-computed ---

    @Test
    fun elevationAngleWithTheEarthsCurvature() {
        // An aircraft at 10 km, 50 km away, seen from sea level. Flat earth: atan(10/50) = 11.310°. The curve drops the
        // aircraft by d²/2R = 196 m and tilts the line of sight: atan2((R+h)cos θ - R, (R+h) sin θ) = 11.0764°.
        near(11.0764, AircraftGeo.elevationAngleDeg(50_000.0, 0.0, 10_000.0), 1e-3, "10 km up, 50 km away")
        // A target on the surface 50 km off lies θ/2 below the horizontal: 50000 / R / 2 in degrees = 0.2248°.
        near(-0.22483, AircraftGeo.elevationAngleDeg(50_000.0, 0.0, 0.0), 1e-4, "on the surface, 50 km away")
        // From Paradise's height (1650 m) the same aircraft stands lower.
        near(9.2475, AircraftGeo.elevationAngleDeg(50_000.0, 1650.0, 10_000.0), 1e-3)
        // Straight up.
        near(90.0, AircraftGeo.elevationAngleDeg(0.0, 0.0, 10_000.0), 1e-9)
        // Bearings: due east along the equator, due north along a meridian, and Paradise to the summit (north-northeast).
        near(90.0, AircraftGeo.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-9)
        near(0.0, AircraftGeo.bearingDeg(46.0, -121.0, 47.0, -121.0), 1e-9)
        near(345.69, AircraftGeo.bearingDeg(46.786, -121.735, 46.853, -121.760), 0.01, "Paradise to the summit")
        // One degree of latitude: 111.2 km on the mean sphere.
        near(111_195.0, AircraftGeo.distanceM(46.0, -121.0, 47.0, -121.0), 1.0)
        // A point moved 50 km east and measured back.
        val (la, lo) = AircraftGeo.move(46.786, -121.735, 90.0, 50_000.0)
        near(50_000.0, AircraftGeo.distanceM(46.786, -121.735, la, lo), 0.01)
    }

    // --- the route table and plausibility ---

    private val table = RouteTable.parse(AircraftFixtures.table().encodeToByteArray())

    @Test
    fun theRouteTableRoundTrips() {
        assertEquals(5, table.airports.size)
        assertEquals(4, table.routeCount)
        val r = assertNotNull(table.lookup("ASA1670"))
        assertEquals(listOf("SEA", "DEN"), r.airports.map { it.code })
        assertEquals("Seattle", r.airports[0].city)
        assertEquals(listOf("PDX", "SEA", "CVG"), table.lookup("abx404")?.airports?.map { it.code }, "case ignored")
        assertNull(table.lookup("ASA167"), "a prefix of a number is another number")
        assertNull(table.lookup("ZZZ1"))
        assertEquals("built 2026-11-20", "built ${table.built}")
    }

    @Test
    fun anOnRouteLegIsAccepted() {
        val r = table.lookup("ASA1670")!!
        val sea = r.airports[0]
        val den = r.airports[1]
        // 95 km out of Seattle on the great circle to Denver, heading for it.
        val brg = AircraftGeo.bearingDeg(sea.lat, sea.lon, den.lat, den.lon)
        val (la, lo) = AircraftGeo.move(sea.lat, sea.lon, brg, 95_000.0)
        val m = RoutePlausibility.check(r, la, lo, AircraftGeo.bearingDeg(la, lo, den.lat, den.lon), 420.0, false)
        assertTrue(m.plausible, m.reason)
        assertEquals("SEA", m.origin?.code)
        assertEquals("DEN", m.destination?.code)
        assertEquals("SEA > DEN", AircraftText.routeLabel(m, AirportNaming.BOTH))
        assertEquals("SEA Seattle > DEN Denver", AircraftText.routeCard(m, AirportNaming.BOTH))
        assertEquals("Seattle > Denver", AircraftText.routeCard(m, AirportNaming.CITIES))
        assertEquals("SEA > DEN", AircraftText.routeCard(m, AirportNaming.CODES))
        // 30 km off the line still fits (tolerance: 15 % of the 1,640 km leg, 246 km); heading away from Denver does not.
        val (oLa, oLo) = AircraftGeo.move(la, lo, brg + 90, 30_000.0)
        assertTrue(RoutePlausibility.check(r, oLa, oLo, brg, 420.0, false).plausible)
        assertFalse(RoutePlausibility.check(r, la, lo, (brg + 180) % 360, 420.0, false).plausible, "flying back towards Seattle")
    }

    @Test
    fun aReusedCallSignOnTheWrongLegIsRejected() {
        // ASA1409 is Portland to Seattle in the table; an aircraft with that call sign east of Rainier heading south-southeast
        // is 70 km off that leg (the tolerance is 40 km for a 210 km leg) and heading away from Seattle.
        val r = table.lookup("ASA1409")!!
        val m = RoutePlausibility.check(r, 46.62, -121.52, 160.0, 380.0, false)
        assertFalse(m.plausible, m.reason)
        assertNull(m.destination)
        assertTrue("off the leg" in m.reason, m.reason)
        assertEquals(AircraftText.ROUTE_UNKNOWN, AircraftText.routeCard(null, AirportNaming.BOTH))
        val report = AircraftReport("c0ffe4", "ASA1409", null, null, null, 46.62, -121.52, null, 31000.0, false, 160.0, 380.0, 0.0, 0.0)
        assertEquals("ASA1409", AircraftText.label(report, m, AirportNaming.BOTH, null), "the call sign alone")
    }

    @Test
    fun theNextLegIsChosenOnAMultiLegRoute() {
        val r = table.lookup("ABX404")!! // PDX - SEA - CVG
        val sea = r.airports[1]
        val cvg = r.airports[2]
        val brg = AircraftGeo.bearingDeg(sea.lat, sea.lon, cvg.lat, cvg.lon)
        val (la, lo) = AircraftGeo.move(sea.lat, sea.lon, brg, 70_000.0)
        val m = RoutePlausibility.check(r, la, lo, AircraftGeo.bearingDeg(la, lo, cvg.lat, cvg.lon), 440.0, false)
        assertTrue(m.plausible, m.reason)
        assertEquals(1, m.leg)
        assertEquals("SEA", m.origin?.code)
        assertEquals("CVG", m.destination?.code)
        // Between Portland and Seattle heading north: the first leg.
        val pdx = r.airports[0]
        val (la2, lo2) = AircraftGeo.move(pdx.lat, pdx.lon, AircraftGeo.bearingDeg(pdx.lat, pdx.lon, sea.lat, sea.lon), 100_000.0)
        val m2 = RoutePlausibility.check(r, la2, lo2, AircraftGeo.bearingDeg(la2, lo2, sea.lat, sea.lon), 300.0, false)
        assertEquals(0, m2.leg, m2.reason)
        assertEquals("SEA", m2.destination?.code)
        // On the ground at Seattle: plausible (taxiing).
        assertTrue(RoutePlausibility.check(r, sea.lat + 0.01, sea.lon, null, 10.0, true).plausible)
    }

    // --- moving up between polls, staleness, dropping ---

    private fun report(seenPos: Double = 0.0, track: Double? = 90.0, gs: Double? = 400.0) =
        AircraftReport("abc123", "ASA1670", null, "B739", null, 46.8, -121.7, 30000.0, 29900.0, false, track, gs, 0.0, seenPos)

    @Test
    fun aircraftMoveUpAlongTheirTrackCappedAt30s() {
        val r = report()
        val (la, lo) = AircraftTracker.moveUp(r, 10.0)
        near(400 * 0.514444 * 10, AircraftGeo.distanceM(r.lat, r.lon, la, lo), 0.5, "10 s at 400 kt")
        near(90.0, AircraftGeo.bearingDeg(r.lat, r.lon, la, lo), 0.1)
        val (la2, lo2) = AircraftTracker.moveUp(r, 45.0)
        near(400 * 0.514444 * 30, AircraftGeo.distanceM(r.lat, r.lon, la2, lo2), 0.5, "capped at 30 s")
        assertEquals(r.lat to r.lon, AircraftTracker.moveUp(report(track = null), 10.0), "no track: not moved")
        assertEquals(r.lat to r.lon, AircraftTracker.moveUp(report(gs = null), 10.0), "no speed: not moved")
        // Through the tracker: the poll's position is 5 s old when it arrives; 8 s later it is 13 s old.
        val t = AircraftTracker()
        t.onFetch(AircraftFetch.Ok(AircraftPoll(100_000, listOf(report(seenPos = 5.0))), 100, null), 100_000, null)
        val v = t.view(108_000, null, null).single()
        near(13.0, v.ageS, 1e-9)
        near(13.0, v.movedS, 1e-9)
        near(400 * 0.514444 * 13, AircraftGeo.distanceM(r.lat, r.lon, v.lat, v.lon), 0.5)
    }

    @Test
    fun staleAt30sDroppedAfter60s() {
        val t = AircraftTracker()
        t.onFetch(AircraftFetch.Ok(AircraftPoll(0, listOf(report(seenPos = 2.0))), 100, null), 0, null)
        assertFalse(t.view(28_000, null, null).single().stale, "30 s old exactly is not yet stale")
        val stale = t.view(28_500, null, null).single()
        assertTrue(stale.stale)
        assertTrue(stale.label.endsWith("· 31 s ago") || stale.label.endsWith("· 30 s ago"), stale.label)
        assertEquals(1, t.view(58_000, null, null).size, "60 s old: still drawn")
        assertEquals(0, t.view(58_500, null, null).size, "past 60 s: dropped")
        assertEquals(0, t.count, "and forgotten")
        assertEquals(0, t.history("abc123").size, "nothing kept of a dropped aircraft")
    }

    @Test
    fun theTrackSinceTheLayerSawItIsKept() {
        val t = AircraftTracker()
        for (i in 0 until 5) {
            val r = report().copy(lat = 46.8 + i * 0.01)
            t.onFetch(AircraftFetch.Ok(AircraftPoll(i * 20_000L, listOf(r)), 100, null), i * 20_000L, null)
        }
        assertEquals(5, t.history("abc123").size)
        assertEquals(46.8, t.history("abc123").first().lat)
        val v = t.view(80_000, Observer(46.786, -121.735, 1650.0, null), null).single()
        assertEquals(6, v.trail.size, "the last 90 s of positions (5) and the moved-up head")
        assertNotNull(v.elevationDeg)
    }

    // --- the feed's states and the day's allowance ---

    @Test
    fun feedStates() {
        val t = AircraftTracker()
        assertEquals(FeedState.WAITING, t.state)
        t.onFetch(AircraftFetch.Offline("no network"), 0, null)
        assertEquals(FeedState.OFFLINE, t.state)
        assertEquals("No flight data: offline", t.state.message)
        t.onFetch(AircraftFetch.Ok(AircraftPoll(1000, emptyList()), 100, null), 1000, null)
        assertEquals(FeedState.LIVE, t.state)
        t.onFetch(AircraftFetch.Failed(502, "HTTP 502"), 21_000, null, 20)
        assertEquals(FeedState.LIVE, t.state, "one failure is not a stopped feed")
        t.onFetch(AircraftFetch.Failed(502, "HTTP 502"), 41_000, null, 20)
        t.onFetch(AircraftFetch.Failed(502, "HTTP 502"), 61_000, null, 20)
        assertEquals(FeedState.STOPPED, t.state)
        assertEquals("Flight data stopped", t.state.message)
        t.onFetch(AircraftFetch.Ok(AircraftPoll(70_000, emptyList()), 100, null), 70_000, null)
        t.checkStalled(129_000, 20)
        assertEquals(FeedState.LIVE, t.state, "59 s without an answer")
        t.checkStalled(131_000, 20)
        assertEquals(FeedState.STOPPED, t.state, "a poll that hangs past a minute")
        t.onFetch(AircraftFetch.Limited(3600), 140_000, null)
        assertEquals(FeedState.LIMITED, t.state)
        assertEquals("Daily flight data limit reached", t.state.message)
    }

    @Test
    fun theDaysRequestsAreCountedByTheUtcDay() {
        val beforeMidnight = 20_000L * AircraftQuota.DAY_MS - 60_000 // 23:59 UTC
        val q = AircraftQuota()
        repeat(3) { q.spend(beforeMidnight) }
        assertEquals(497, q.left(beforeMidnight))
        assertEquals(500, q.left(beforeMidnight + 120_000), "a new UTC day starts a new count")
        q.spend(beforeMidnight + 120_000)
        assertEquals(499, q.left(beforeMidnight + 130_000))
        val back = AircraftQuota.decode(q.encode())
        assertEquals(499, back.left(beforeMidnight + 130_000), "kept across a restart")
        assertEquals(500, AircraftQuota.decode("garbage").left(0))
        // A 429 with Retry-After: no request until it passes; without one, until the next UTC midnight.
        q.limited(beforeMidnight, 120)
        assertTrue(q.isLimited(beforeMidnight + 119_000))
        assertFalse(q.isLimited(beforeMidnight + 120_000))
        q.limited(beforeMidnight, null)
        assertTrue(q.isLimited(beforeMidnight + 59_000))
        assertFalse(q.isLimited(beforeMidnight + 60_000), "midnight UTC")
        assertEquals(120L, AircraftQuota.retryAfter(" 120 "))
        assertNull(AircraftQuota.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun aLiveClientAnswers429AsLimitedAndNoConnectionAsOffline() {
        var asked: Pair<String, Map<String, String>>? = null
        val ok = AdsbLolSource({ u, h -> asked = u to h; AircraftHttpReply(200, AircraftFixtures.RESPONSE.encodeToByteArray()) }, "0.1.36", { 5L })
        val q = AircraftQuery.forView(46.853, -121.760, -122.2, 46.6, -121.3, 47.1)
        val f = assertIs<AircraftFetch.Ok>(ok.poll(q, 0))
        assertEquals(3, f.poll.aircraft.size)
        assertEquals("https://api.adsb.lol/v2/lat/46.9/lon/-121.8/dist/${q.radiusNm}", asked!!.first)
        assertEquals(mapOf("User-Agent" to "Dystopo/0.1.36", "Accept" to "application/json"), asked!!.second, "no key, no device id")
        val limited = AdsbLolSource({ _, _ -> AircraftHttpReply(429, ByteArray(0), "300") }, "x", { 0L }).poll(q, 0)
        assertEquals(AircraftFetch.Limited(300), limited)
        val off = AdsbLolSource({ _, _ -> throw AircraftNoConnection("unreachable") }, "x", { 0L }).poll(q, 0)
        assertIs<AircraftFetch.Offline>(off)
        assertIs<AircraftFetch.Failed>(AdsbLolSource({ _, _ -> AircraftHttpReply(200, "not json".encodeToByteArray()) }, "x", { 0L }).poll(q, 0))
    }

    @Test
    fun theQueryIsTheRoundedViewCentre() {
        val q = AircraftQuery.forView(46.8537, -121.7604, -122.0, 46.7, -121.5, 47.0)
        assertEquals(46.9, q.lat)
        assertEquals(-121.8, q.lon)
        // The farthest corner (46.7, -121.5: 17.2 nm) from the rounded centre, in whole nautical miles up.
        assertEquals(18, q.radiusNm)
        assertEquals("/v2/lat/46.9/lon/-121.8/dist/18", q.path)
        assertEquals(10, AircraftQuery.forView(46.85, -121.76, -121.77, 46.84, -121.75, 46.86).radiusNm, "at least 10 nm")
        assertEquals(250, AircraftQuery.forView(46.0, -120.0, -130.0, 40.0, -110.0, 52.0).radiusNm, "at most 250 nm")
        assertEquals("-0.1", AircraftQuery.fmt(-0.06))
        assertEquals("0.0", AircraftQuery.fmt(-0.04))
    }

    @Test
    fun theReplayPlaysFramesOnThePhonesClock() {
        val replay = ReplaySource.Replay.parse(AircraftFixtures.REPLAY)
        val src = ReplaySource(replay, 1_000_000)
        val q = AircraftQuery(46.9, -121.8, 50)
        assertEquals(1, assertIs<AircraftFetch.Ok>(src.poll(q, 1_004_000)).poll.aircraft.size)
        assertEquals(2, assertIs<AircraftFetch.Ok>(src.poll(q, 1_012_000)).poll.aircraft.size)
        assertEquals(1, assertIs<AircraftFetch.Ok>(src.poll(q, 1_021_000)).poll.aircraft.size, "looped after 20 s")
    }

    @Test
    fun theCardReadsInOrder() {
        val t = AircraftTracker()
        val r = table.lookup("ASA1670")!!
        val sea = r.airports[0]
        val den = r.airports[1]
        val brg = AircraftGeo.bearingDeg(sea.lat, sea.lon, den.lat, den.lon)
        val (la, lo) = AircraftGeo.move(sea.lat, sea.lon, brg, 95_000.0)
        val rep = AircraftReport("abc123", "ASA1670", "N000AA", "B739", null, la, lo, 17025.0, 16950.0, false, brg + 0.3, 420.0, 1800.0, 1.0)
        t.onFetch(AircraftFetch.Ok(AircraftPoll(0, listOf(rep)), 100, null), 0, -20.0)
        val a = t.view(0, Observer(46.786, -121.735, 1650.0, -20.0), table).single()
        val lines = AircraftText.card(a, ContourUnits.FEET, AirportNaming.BOTH, AircraftText.CREDIT)
        assertEquals("SEA Seattle > DEN Denver", lines[0])
        assertEquals(AircraftText.ROUTE_HEDGE, lines[1])
        assertEquals("B739 · N000AA", lines[2])
        assertEquals("17,091 ft, climbing", lines[3])
        assertEquals("483 mph", lines[4])
        assertTrue(lines[5].matches(Regex("\\d+(\\.\\d)? mi away, to the \\w+ \\(\\d+°\\), \\d+(\\.\\d)?° above the horizon")), lines[5])
        assertEquals("Position 1 s ago", lines[6])
        assertEquals("ADSB.lol contributors (ODbL)", lines.last())
        assertEquals("ASA1670 SEA > DEN", a.label)
    }

    // --- the owner's additions of 2026-11-20: flight level, emergencies, kinds ---

    @Test
    fun altitudeInBothUnitsWithTheFlightLevelAtCruise() {
        val m = 37_000 * 0.3048
        assertEquals("37,000 ft · FL370, level", AircraftText.altitudeLine(m, AltitudeKind.GEOMETRIC, 0.0, ContourUnits.FEET, 37_000.0))
        assertEquals("11,278 m · FL370, level", AircraftText.altitudeLine(m, AltitudeKind.GEOMETRIC, 0.0, ContourUnits.METERS, 37_000.0))
        assertEquals("11,278 m (pressure altitude) · FL370", AircraftText.altitudeLine(m, AltitudeKind.BAROMETRIC, null, ContourUnits.METERS, 37_000.0))
        assertEquals("5,486 m, climbing", AircraftText.altitudeLine(5486.4, AltitudeKind.GEOMETRIC, 900.0, ContourUnits.METERS, 18_000.0), "at the transition altitude: no flight level")
        assertEquals("FL185", AircraftText.flightLevel(18_490.0))
        assertNull(AircraftText.flightLevel(null))
        assertEquals("On the ground", AircraftText.altitudeLine(null, AltitudeKind.GROUND, null, ContourUnits.FEET, 37_000.0))
        assertEquals("483 mph", AircraftText.speedLine(420.0, ContourUnits.FEET))
        assertEquals("778 km/h", AircraftText.speedLine(420.0, ContourUnits.METERS))
    }

    @Test
    fun emergencySquawksAndKindsAreRead() {
        val poll = ReadsbParser.parse(AircraftFixtures.RESPONSE, 0)
        val a = poll.aircraft[0]
        assertEquals("1234", a.squawk)
        assertEquals("A3", a.category)
        assertEquals(AircraftKind.AIRLINER, a.kind)
        assertNull(a.emergency)
        assertEquals(AircraftKind.GENERIC, poll.aircraft[1].kind, "no category: the generic silhouette")
        assertEquals("Airliner · B739 · N000AA", AircraftText.typeLine(a), "round 2: the kind first on the card")
        assertEquals("Helicopter · EC35", AircraftText.typeLine(a.copy(category = "A7", type = "EC35", registration = null)))
        assertEquals(Emergency.GENERAL, Emergency.of("7700", null))
        assertEquals(Emergency.RADIO, Emergency.of("7600", "none"))
        assertEquals(Emergency.HIJACK, Emergency.of(null, "unlawful"))
        assertNull(Emergency.of("1200", "none"))
        assertEquals(mapOf("A1" to AircraftKind.LIGHT, "A5" to AircraftKind.HEAVY, "A7" to AircraftKind.ROTORCRAFT, "B1" to AircraftKind.GLIDER,
            "B2" to AircraftKind.BALLOON, "B6" to AircraftKind.UAV, "C1" to AircraftKind.GENERIC),
            listOf("A1", "A5", "A7", "B1", "B2", "B6", "C1").associateWith { AircraftKind.of(it) })
        // The card's first line names the emergency.
        val r = report().copy(squawk = "7600")
        val t = AircraftTracker()
        t.onFetch(AircraftFetch.Ok(AircraftPoll(0, listOf(r)), 100, null), 0, null)
        assertEquals("Radio failure (squawk 7600)", AircraftText.card(t.view(0, null, null).single(), ContourUnits.FEET, AirportNaming.BOTH, AircraftText.CREDIT).first())
    }
}
