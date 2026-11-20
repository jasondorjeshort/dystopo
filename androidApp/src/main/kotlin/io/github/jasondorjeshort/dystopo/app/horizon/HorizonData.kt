package io.github.jasondorjeshort.dystopo.app.horizon

import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.app.ClockFormat
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.horizon.HorizonSun
import io.github.jasondorjeshort.dystopo.horizon.PeakRim
import io.github.jasondorjeshort.dystopo.horizon.SunPath
import io.github.jasondorjeshort.dystopo.horizon.SunSample
import io.github.jasondorjeshort.dystopo.nav.Sun
import java.time.LocalDate
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * T308: what the Horizon view's Peaks and Sun layers show, worked out from the plot's own horizon ([Gnss.HorizonState]:
 * T307's `Horizon.sweep` at the position, with the terrain it was swept over) and never swept again.
 *
 * - **Peaks** ([peaksFor]): the named peaks of the active pack's `mountain_peak` layer within the profile's range
 *   ([Viewshed.readPeaks], as `ViewshedSession` reads them), each tested against that terrain and profile
 *   ([Viewshed.peaks]'s overload on a `HorizonProfile`); the skyline ones listed and the rim's names chosen by [PeakRim].
 *   Worked out once per horizon on a thread of its own.
 * - **Sun** ([sunFor]): [SunPath.of] on the profile's `angleAt` for the day shown (today at the point, or [HorizonUi.date]),
 *   so the terrain times are `Sun.onTerrain`'s on the same profile the turn-around clock reaches through
 *   `ShadeTiles.pointAnswer`; over the flat horizon while there is no profile. Kept until the horizon or the day changes.
 */
object HorizonData {
    private val app get() = DystopoApp.instance

    /** The peaks for one horizon: [reason] when there are none to show. */
    class Peaks(val horizon: Gnss.HorizonState, val all: List<PeakView>, val skyline: List<PeakView>, val labelled: List<PeakView>, val tiles: Int, val reason: String?, val ms: Long)

    @Volatile var peaks: Peaks? = null
        private set
    @Volatile private var peaksPending: Gnss.HorizonState? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-horizon-peaks").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    val peakRuns = AtomicLong()

    /** The peaks on [h] (starting their reading when not yet read), or null while they are read. */
    fun peaksFor(h: Gnss.HorizonState): Peaks? {
        peaks?.takeIf { it.horizon === h }?.let { return it }
        if (peaksPending !== h) {
            peaksPending = h
            worker.execute {
                val r = read(h)
                peaks = r
                if (peaksPending === h) peaksPending = null
            }
        }
        return null
    }

    /** Waits up to [ms] for [h]'s peaks (the debug route). */
    fun awaitPeaks(h: Gnss.HorizonState, ms: Long): Peaks? {
        val until = SystemClock.elapsedRealtime() + ms
        while (true) {
            peaksFor(h)?.let { return it }
            if (SystemClock.elapsedRealtime() > until) return null
            Thread.sleep(100)
        }
    }

    private fun read(h: Gnss.HorizonState): Peaks {
        val t0 = SystemClock.elapsedRealtime()
        peakRuns.incrementAndGet()
        fun none(why: String, tiles: Int = 0) = Peaks(h, emptyList(), emptyList(), emptyList(), tiles, why, SystemClock.elapsedRealtime() - t0)
        val profile = h.profile ?: return none(HorizonText.PEAKS_NO_TERRAIN)
        val terrain = h.terrain ?: return none(HorizonText.PEAKS_NO_TERRAIN)
        return try {
            var tiles = 0
            val found = Viewshed.readPeaks({ z, x, y ->
                app.tiles.packs.osmCandidates(z, x, y).firstNotNullOfOrNull { it.tile(z, x, y) }?.also { tiles++ }
            }, h.lat, h.lon, profile.rangeM)
            if (tiles == 0) return none(HorizonText.PEAKS_NO_MAP)
            val all = Viewshed.peaks(terrain, profile, found)
            val skyline = PeakRim.skyline(all)
            Peaks(h, all, skyline, PeakRim.labelled(all), tiles, if (skyline.isEmpty()) HorizonText.PEAKS_NONE else null, SystemClock.elapsedRealtime() - t0)
        } catch (e: Exception) {
            android.util.Log.w("Dystopo", "horizon peaks failed", e)
            none("${HorizonText.PEAKS_NO_MAP} (${e.javaClass.simpleName})")
        }
    }

    /** The sun's day at a point: [marks] the hour dots in the phone's zone. */
    class SunDay(val key: String, val sun: HorizonSun, val marks: List<SunSample>, val ms: Long)

    @Volatile private var sunDay: SunDay? = null

    /**
     * The sun's day at [lat], [lon] for [date] (null: today at the point, [Sun.dayAt]) over [h]'s profile (the flat
     * horizon without one). A few milliseconds; kept until the horizon, the point (100 m) or the day changes.
     */
    fun sunFor(h: Gnss.HorizonState?, lat: Double, lon: Double, date: LocalDate?, now: Long = System.currentTimeMillis()): SunDay {
        val profile = h?.profile
        val (y, m, d) = date?.let { Triple(it.year, it.monthValue, it.dayOfMonth) } ?: Sun.dayAt(lat, lon, now).let { Triple(it.year, it.month, it.day) }
        val zone = TimeZone.getDefault()
        val key = listOf(profile?.let { System.identityHashCode(it) } ?: "flat:${Math.round(lat * 1000)},${Math.round(lon * 1000)}", y, m, d, zone.id).joinToString("/")
        sunDay?.takeIf { it.key == key }?.let { return it }
        val t0 = SystemClock.elapsedRealtime()
        val fn = profile?.let { p -> { az: Double -> p.angleAt(az) } }
        val sLat = profile?.lat ?: lat
        val sLon = profile?.lon ?: lon
        val sun = SunPath.of(sLat, sLon, y, m, d, fn)
        val marks = SunPath.hourMarks(sun, sLat, sLon, zone.getOffset(sun.terrain.flat.noonMs).toLong(), fn)
        return SunDay(key, sun, marks, SystemClock.elapsedRealtime() - t0).also { sunDay = it }
    }

    // ---- JSON ----

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
    private fun r2(v: Double) = Math.round(v * 100) / 100.0
    private fun iso(ms: Long?): String? = ms?.let { java.time.Instant.ofEpochMilli(it).toString() }

    fun peaksJson(p: Peaks?): Map<String, Any?>? = p?.let {
        val named = it.labelled.map { v -> v.peak.name }.toSet()
        linkedMapOf(
            "reason" to it.reason, "ms" to it.ms, "tilesRead" to it.tiles, "tested" to it.all.size, "visible" to it.all.count { v -> v.visible },
            "skyline" to it.skyline.size, "named" to it.labelled.size, "sectorDeg" to PeakRim.SECTOR_DEG,
            "peaks" to it.skyline.map { v ->
                linkedMapOf(
                    "name" to v.peak.name, "eleM" to Math.round(v.eleM), "distanceM" to Math.round(v.distanceM), "bearingDeg" to r1(v.bearingDeg),
                    "compass" to v.compass, "angleDeg" to r2(v.angleDeg), "sector" to PeakRim.sector(v.bearingDeg), "onRim" to (v.peak.name in named),
                )
            },
        )
    }

    fun sunJson(s: SunDay): Map<String, Any?> {
        val t = s.sun.terrain
        val clock = { ms: Long? -> ms?.let { ClockFormat.text(it) } }
        return linkedMapOf(
            "date" to "%04d-%02d-%02d".format(s.sun.year, s.sun.month, s.sun.day), "onTerrain" to s.sun.onTerrain, "ms" to s.ms,
            "terrainSunriseMs" to t.sunriseMs, "terrainSunsetMs" to t.sunsetMs, "terrainSunriseUtc" to iso(t.sunriseMs), "terrainSunsetUtc" to iso(t.sunsetMs),
            "terrainSunrise" to clock(t.sunriseMs), "terrainSunset" to clock(t.sunsetMs),
            "flatSunriseMs" to t.flat.sunriseMs, "flatSunsetMs" to t.flat.sunsetMs, "flatSunrise" to clock(t.flat.sunriseMs), "flatSunset" to clock(t.flat.sunsetMs),
            "noonMs" to t.flat.noonMs, "sunMinutes" to Math.round(t.sunMinutes), "alwaysUp" to t.alwaysUp,
            "sunlit" to t.sunlit.map { listOf(it.startMs, it.endMs) },
            "gaps" to t.gaps.map { g -> linkedMapOf("fromMs" to g.span.startMs, "toMs" to g.span.endMs, "bearingDeg" to r1(g.bearingDeg), "horizonDeg" to r2(g.horizonDeg)) },
            "pathSamples" to s.sun.path.size, "pathClear" to s.sun.path.count { it.clear }, "stepMin" to SunPath.STEP_MS / 60_000,
            "highestDeg" to s.sun.path.maxOfOrNull { it.altitudeDeg }?.let(::r1),
            "hourMarks" to s.marks.map { m -> linkedMapOf("at" to ClockFormat.text(m.timeMs), "azimuthDeg" to r1(m.azimuthDeg), "altitudeDeg" to r1(m.altitudeDeg), "clear" to m.clear) },
        )
    }
}
