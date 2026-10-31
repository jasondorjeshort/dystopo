package io.github.jasondorjeshort.dystopo.app.options

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.nav.Sun
import java.time.LocalDate
import java.util.TimeZone

/**
 * T188: the point answer of the long-press card: when the sun reaches the pressed point and leaves it on the real
 * terrain (T181's horizon sweep crossed with the day's sun path, [ShadeService.pointAnswer]), with the gaps when a peak
 * blocks it, as one line in the phone's zone with the zone named ([SunClock.line]). No surveyed app gives this line
 * (the survey of 2026-10-30 found only a year graph and flat-horizon times); it is the project's own. The day is
 * [SunShade.answerDate]: the date picked under Sun and shade while the shade is on, else today.
 */
object SunPoint {
    /**
     * One answer: [state] is `working`, `done`, `noData` (no elevation under the point) or `error`; [line] what the card
     * shows; [day] the terrain day behind it.
     */
    class Answer(
        val lat: Double,
        val lon: Double,
        val date: LocalDate,
        val state: String,
        val line: String,
        val day: Sun.TerrainDay? = null,
        val zone: TimeZone = TimeZone.getDefault(),
        val sweepMs: Double? = null,
        val totalMs: Double? = null,
        val horizonZoom: Int? = null,
    ) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "state" to state, "line" to line, "date" to date.toString(), "zone" to zone.id,
            "sunriseMs" to day?.sunriseMs, "sunsetMs" to day?.sunsetMs,
            "sunrise" to day?.sunriseMs?.let { SunClock.clock(it, zone) }, "sunset" to day?.sunsetMs?.let { SunClock.clock(it, zone) },
            "flatSunrise" to day?.flat?.sunriseMs?.let { SunClock.clock(it, zone) }, "flatSunset" to day?.flat?.sunsetMs?.let { SunClock.clock(it, zone) },
            "gaps" to day?.gaps?.map { g ->
                linkedMapOf(
                    "from" to SunClock.clock(g.span.startMs, zone), "to" to SunClock.clock(g.span.endMs, zone),
                    "startMs" to g.span.startMs, "endMs" to g.span.endMs, "minutes" to Math.round(g.span.minutes),
                    "bearingDeg" to Math.round(g.bearingDeg), "horizonDeg" to Math.round(g.horizonDeg * 10) / 10.0,
                )
            },
            "sunMinutes" to day?.sunMinutes?.let { Math.round(it) },
            "alwaysUp" to day?.alwaysUp,
            "horizonZoom" to horizonZoom, "sweepMs" to sweepMs, "totalMs" to totalMs,
        )
    }

    const val WORKING = "Sun: working out when it reaches here..."
    const val NO_DATA = "Sun: no elevation data here"
    const val FAILED = "Sun: could not work it out here"

    fun working(lat: Double, lon: Double, date: LocalDate) = Answer(lat, lon, date, "working", WORKING)

    /** Works the answer out (a background thread: the sweep reads the DEM around the point, a few hundred ms). */
    fun compute(lat: Double, lon: Double, date: LocalDate): Answer {
        val zone = TimeZone.getDefault()
        val t0 = System.nanoTime()
        return try {
            val a = DystopoApp.instance.tiles.shade.pointAnswer(lat, lon, SunClock.noonOf(date, zone))
                ?: return Answer(lat, lon, date, "noData", NO_DATA, zone = zone)
            val prefix = if (date == LocalDate.now(zone.toZoneId())) null else SunClock.dateLabel(date)
            Answer(
                lat, lon, date, "done", SunClock.line(a.day, zone, prefix), a.day, zone,
                sweepMs = a.sweepMs, totalMs = Math.round((System.nanoTime() - t0) / 1e5) / 10.0, horizonZoom = a.zoom,
            )
        } catch (e: Exception) {
            android.util.Log.w("Dystopo", "sun point answer failed", e)
            Answer(lat, lon, date, "error", FAILED, zone = zone)
        }
    }
}
