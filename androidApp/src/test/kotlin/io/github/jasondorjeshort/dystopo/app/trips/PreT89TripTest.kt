package io.github.jasondorjeshort.dystopo.app.trips

import io.github.jasondorjeshort.dystopo.model.LegKind
import io.github.jasondorjeshort.dystopo.model.RouteLeg
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.CostModel
import io.github.jasondorjeshort.dystopo.model.Base64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T106 (a T89 follow-up): a drive kept before T89 is stored as a version 1 `path` with one trail leg and no router
 * time, so its time is the flat 45 km/h estimate while every other surface shows the router's per-road time. Such a
 * trip is marked (the trip card says so and offers Re-plan; `untimedDriveLegs` in `GET /routes/{id}`).
 */
class PreT89TripTest {
    /**
     * The fixture: Paradise to Sunrise along the park road in seven points (public places), the `path` field value a
     * kept drive had on a build before T89 (version 1: no leg times).
     */
    private val storedBeforeT89 = FIXTURE

    @Test
    fun aDriveKeptBeforeT89IsMarked() {
        assertEquals(RoutePath.VERSION, Base64Url.decode(storedBeforeT89)[0].toInt(), "a version 1 value")
        val p = RoutePath.decode(storedBeforeT89)
        assertEquals("drive", p.mode)
        assertEquals(1, untimedDriveLegs(p))
        // Its time is the flat estimate: the leg's length at 45 km/h.
        val s = RouteSummary.of(io.github.jasondorjeshort.dystopo.model.RoutePlan("t", "Paradise to Sunrise", p, type = "drive"))
        assertEquals(s.distanceM / (CostModel.DRIVE_SUMMARY_KMH * 1000.0) * 3600, s.timeS, 1.0)
    }

    @Test
    fun aDrivePlannedSinceT89AndOtherModesAreNotMarked() {
        val p = RoutePath.decode(storedBeforeT89)
        val timed = p.copy(legs = p.legs.map { it.copy(timeS = 5138.0) })
        assertEquals(RoutePath.VERSION_TIMES, Base64Url.decode(timed.encode())[0].toInt())
        assertEquals(0, untimedDriveLegs(RoutePath.decode(timed.encode())))
        assertEquals(0, untimedDriveLegs(p.copy(mode = "hike")), "hike and bike model their time")
        // An imported line (a fixed leg) has no router time either, but nothing to re-plan: not marked.
        assertEquals(0, untimedDriveLegs(p.copy(legs = listOf(RouteLeg(LegKind.FIXED, p.legs[0].points)))))
        // A straight leg (no road there) is estimated by design.
        assertEquals(0, untimedDriveLegs(p.copy(legs = listOf(RouteLeg(LegKind.STRAIGHT, p.legs[0].points)))))
    }

    @Test
    fun theCardLineNamesTheFlatSpeedInTheUnitsSetting() {
        assertTrue(driveTimeEstimated(ContourUnits.METERS).contains("45 km/h"))
        assertTrue(driveTimeEstimated(ContourUnits.FEET).contains("28 mph"))
        assertTrue(driveTimeEstimated(ContourUnits.FEET).endsWith("Re-plan for the road time."))
    }

    companion object {
        const val FIXTURE = "AQVkcml2ZQLQtpi-A7-D-4gJkPGbAYChcgEBB9C2mL4Dv4P7iAnxixSvrhXfvAuQ5AGf4BSA1GHwogSAxBOgh4UBsOoBwIxkgMQTwbgCoJUtn-8PsecCgJohn9JswawG"
    }
}
