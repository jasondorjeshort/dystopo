package io.github.jasondorjeshort.dystopo.app.horizon

import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.aircraft.AircraftText
import io.github.jasondorjeshort.dystopo.aircraft.FeedState
import io.github.jasondorjeshort.dystopo.app.ClockFormat
import io.github.jasondorjeshort.dystopo.app.aircraft.AircraftPoller
import io.github.jasondorjeshort.dystopo.horizon.HorizonFlights
import io.github.jasondorjeshort.dystopo.horizon.SkyFlight
import io.github.jasondorjeshort.dystopo.horizon.SkyRange
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.horizon.HorizonSun
import io.github.jasondorjeshort.dystopo.horizon.HorizonLabels
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
 *   ([Viewshed.peaks]'s overload on a `HorizonProfile`); T309: the candidates (in sight or just out of sight, [PeakRim])
 *   listed closest first and ranked for the plot's names ([HorizonLabels.rank]; the screen places them).
 *   Worked out once per horizon on a thread of its own.
 * - **Sun** ([sunFor]): [SunPath.of] on the profile's `angleAt` for the day shown (today at the point, or [HorizonUi.date]),
 *   so the terrain times are `Sun.onTerrain`'s on the same profile the turn-around clock reaches through
 *   `ShadeTiles.pointAnswer`; over the flat horizon while there is no profile. Kept until the horizon or the day changes.
 * - T317: both work on whatever horizon they are given: the fix's, or a chosen point's ([pointFor]: "Sky from here").
 */
object HorizonData {
    private val app get() = DystopoApp.instance

    /**
     * The peaks for one horizon: [reason] when there are none to show. T352: [maps] the offline maps whose tiles gave the
     * peak names (in the order they first did; the peaks line names them).
     */
    class Peaks(val horizon: Gnss.HorizonState, val all: List<PeakView>, val candidates: List<PeakView>, val ranked: List<HorizonLabels.Scored>, val tiles: Int, val reason: String?, val ms: Long, val maps: List<String> = emptyList()) {
        /** A candidate's score entry (null when not a candidate). */
        fun scoreOf(p: PeakView): HorizonLabels.Scored? = ranked.firstOrNull { it.label.ref === p }
    }

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
            val read = readPeaksAt(h.lat, h.lon, profile.rangeM, null)
            if (read.tiles == 0) return none(HorizonText.PEAKS_NO_MAP)
            peaksOn(h, terrain, profile, read.found, read.tiles, t0, read.maps)
        } catch (e: Exception) {
            android.util.Log.w("Dystopo", "horizon peaks failed", e)
            none("${HorizonText.PEAKS_NO_MAP} (${e.javaClass.simpleName})")
        }
    }

    // ---- T352: the peak read steps past a map with none ----

    /** One vector map the peak read may ask: its [name], deepest zoom [maxZoom], box's [area] (degrees²) and its tile read. */
    class PeakMap(val name: String, val maxZoom: Int, val area: Double, val tile: (Int, Int, Int) -> ByteArray?)

    /**
     * What a peak read found: [found] the peaks, [tiles] the tile places some map answered (0: no offline map with peak
     * names here), [maps] the maps whose tiles held peaks, in the order they first did.
     */
    data class PeakRead(val found: List<io.github.jasondorjeshort.dystopo.dem.Peak>, val tiles: Int, val maps: List<String>)

    /**
     * The order the maps are asked in for one tile: T340's terrain order (`MergedTerrain.ORDER`): the deepest first, then the
     * smaller box, then the name; which map is primary plays no part.
     */
    val PEAK_ORDER: Comparator<PeakMap> = compareBy<PeakMap>({ -it.maxZoom }, { it.area }, { it.name })

    /**
     * A tile read over [candidates] (the maps that may hold tile z/x/y): each map in [PEAK_ORDER] until one whose tile holds
     * a named feature of [classes] in its `mountain_peak` layer; a map whose tile has none is stepped past (a fine map with
     * its peaks thinned or left out must not hide a coarser map's). Counts into [tiles] each place some map answered and into
     * [maps] the map that gave the peaks.
     */
    class PeakTiles(private val candidates: (Int, Int, Int) -> List<PeakMap>, private val classes: Set<String>) : (Int, Int, Int) -> ByteArray? {
        var tiles = 0
            private set
        val maps = LinkedHashSet<String>()

        override fun invoke(z: Int, x: Int, y: Int): ByteArray? {
            var answered = false
            for (m in candidates(z, x, y).sortedWith(PEAK_ORDER)) {
                val b = m.tile(z, x, y) ?: continue
                answered = true
                if (hasPeaks(b, classes)) {
                    tiles++
                    maps += m.name
                    return b
                }
            }
            if (answered) tiles++
            return null
        }
    }

    /** Whether vector tile [bytes] holds a named feature of [classes] in its `mountain_peak` layer ([Viewshed.readPeaks]'s test). */
    fun hasPeaks(bytes: ByteArray, classes: Set<String>): Boolean {
        val layer = runCatching {
            io.github.jasondorjeshort.dystopo.mvt.MvtDecoder.decode(io.github.jasondorjeshort.dystopo.search.LayerFilter.filter(bytes, setOf("mountain_peak"))).layer("mountain_peak")
        }.getOrNull() ?: return false
        return layer.features.any { f -> f.properties["class"]?.asText() in classes && !f.properties["name"]?.asText().isNullOrBlank() }
    }

    /** The named peaks within [rangeM] of [lat], [lon] at [zoom] (null: the round plot's default) over [candidates]. */
    fun readPeaks(candidates: (Int, Int, Int) -> List<PeakMap>, lat: Double, lon: Double, rangeM: Double, zoom: Int?, classes: Set<String> = Viewshed.PEAK_CLASSES): PeakRead {
        val read = PeakTiles(candidates, classes)
        val found = if (zoom == null) Viewshed.readPeaks(read, lat, lon, rangeM, classes = classes) else Viewshed.readPeaks(read, lat, lon, rangeM, zoom, classes)
        return PeakRead(found, read.tiles, read.maps.toList())
    }

    /**
     * The vector maps of the tile service held on this thread ([DystopoApp.leaseTiles]) that may hold tile z/x/y
     * (`PackSet.osmCandidates`: the world overview among them where its zooms reach), as [PeakMap]s.
     */
    private fun candidatesOf(packs: io.github.jasondorjeshort.dystopo.app.PackSet): (Int, Int, Int) -> List<PeakMap> {
        val made = HashMap<io.github.jasondorjeshort.dystopo.app.Pack, PeakMap>()
        return { z, x, y ->
            packs.osmCandidates(z, x, y).map { pk ->
                made.getOrPut(pk) {
                    val h = pk.header
                    PeakMap(packs.packOf(pk) ?: pk.name, h.maxZoom, (h.maxLon - h.minLon) * (h.maxLat - h.minLat)) { tz, tx, ty -> pk.tile(tz, tx, ty) }
                }
            }
        }
    }

    /**
     * The peaks line's last sentence, naming the maps that gave the names ([Peaks.maps]; ADR 0031: built in alpha): "Names
     * from the offline map wide.", "Names from the offline maps fine and wide.", "Names from 3 offline maps."; null for none.
     */
    fun namesFrom(maps: List<String>): String? = when (maps.size) {
        0 -> null
        1 -> "Names from the offline map ${maps[0]}."
        2 -> "Names from the offline maps ${maps[0]} and ${maps[1]}."
        else -> "Names from ${maps.size} offline maps."
    }

    /** [block] on the maps of the tile service, held for the read so a map opening or closing meanwhile closes nothing under it. */
    private fun <T> withMaps(block: ((Int, Int, Int) -> List<PeakMap>) -> T): T = app.leaseTiles().use { block(candidatesOf(app.tiles.packs)) }

    /**
     * T326: the named peaks of the open maps within [rangeM] of [lat], [lon] (at [zoom], else the round plot's default),
     * and the tiles read: the Panorama reads them once per place and range (its `viewRange`, to 100 km; [Viewshed.peakZoom]).
     * T352: through [PeakTiles] (deepest map first, stepping past a tile with no peaks) under the tile service's lease.
     */
    fun readPeaksAt(lat: Double, lon: Double, rangeM: Double, zoom: Int?): PeakRead = withMaps { readPeaks(it, lat, lon, rangeM, zoom) }

    /**
     * T326: [found] tested on [terrain] and [profile] (the Panorama's farthest skyline, as devtool `panorama` tests them),
     * as the Peaks section and the names read them: the candidates closest first and ranked. [h] is the horizon they belong to.
     * T352: [maps] the maps that gave them.
     */
    fun peaksOn(h: Gnss.HorizonState, terrain: io.github.jasondorjeshort.dystopo.dem.Terrain, profile: io.github.jasondorjeshort.dystopo.dem.HorizonProfile, found: List<io.github.jasondorjeshort.dystopo.dem.Peak>, tiles: Int, t0: Long = SystemClock.elapsedRealtime(), maps: List<String> = emptyList()): Peaks {
        if (tiles == 0) return Peaks(h, emptyList(), emptyList(), emptyList(), 0, HorizonText.PEAKS_NO_MAP, SystemClock.elapsedRealtime() - t0)
        val all = Viewshed.peaks(terrain, profile, found)
        val labels = PeakRim.labels(all)
        val candidates = labels.map { it.ref as PeakView }
        return Peaks(h, all, candidates, HorizonLabels.rank(labels), tiles, if (candidates.isEmpty()) HorizonText.PEAKS_NONE else null, SystemClock.elapsedRealtime() - t0, maps)
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

    // ---- T317: a chosen point ----

    /** A long-press within this of a named feature (metres, or a fingertip at the map's zoom when wider) is on it: T197's 100 m. */
    const val FEATURE_PRESS_M = io.github.jasondorjeshort.dystopo.readout.KnownHeights.NEAR_FIX_M

    /**
     * The named feature (a peak, a saddle, a pass: the active pack's `mountain_peak` features of
     * [Viewshed.KNOWN_HEIGHT_CLASSES], at z13 then z12 as T197 reads them) nearest to [lat], [lon] within [radiusM], or null
     * (none, or no offline map). Off the main thread: a few tiles read.
     */
    fun featureAt(lat: Double, lon: Double, radiusM: Double): io.github.jasondorjeshort.dystopo.dem.Peak? = try {
        withMaps { maps -> // T352: the peak read's order and step, under the lease
            val tiles = PeakTiles(maps, Viewshed.KNOWN_HEIGHT_CLASSES)
            io.github.jasondorjeshort.dystopo.readout.KnownHeights.READ_ZOOMS
                .flatMap { z -> Viewshed.readPeaks(tiles, lat, lon, radiusM, zoom = z, classes = Viewshed.KNOWN_HEIGHT_CLASSES) }
                .map { it to io.github.jasondorjeshort.dystopo.gpx.TrackStats.distanceM(lat, lon, it.lat, it.lon) }
                .filter { it.second <= radiusM }.minByOrNull { it.second }?.first
        }
    } catch (e: Exception) {
        android.util.Log.w("Dystopo", "horizon feature lookup failed", e)
        null
    }

    /** The view's point for a press at [lat], [lon]: on the named feature (its own place and name) when within [radiusM], else the press. */
    fun pointFor(lat: Double, lon: Double, radiusM: Double): io.github.jasondorjeshort.dystopo.horizon.HorizonPoint =
        featureAt(lat, lon, radiusM)?.let { io.github.jasondorjeshort.dystopo.horizon.HorizonPoint(it.lat, it.lon, it.name) }
            ?: io.github.jasondorjeshort.dystopo.horizon.HorizonPoint(lat, lon)

    // ---- T312: Flights ----

    /** The poller's owner name while the Horizon view asks for flights. */
    const val FLIGHTS_OWNER = "horizon"

    /**
     * The query's radius (nautical miles) for a sky range: Overhead 15 (45° up at 12 km of altitude is 12 km out), Near 30
     * (its 50 km and a margin), All 100 (185 km: a cruising aircraft there stands about 1° above a flat horizon, near what
     * an eye picks out; the feed allows 250).
     */
    fun radiusNm(range: SkyRange): Int = when (range) {
        SkyRange.OVERHEAD -> 15
        SkyRange.NEAR -> 30
        SkyRange.ALL -> 100
    }

    /**
     * The Flights layer now: [list] the poller's aircraft on the plot ([HorizonFlights.of] from the profile's eye, the
     * phone's fix without one; closest first), [state] and [message] the feed's, [total] the aircraft the poller holds,
     * [onGround] and [unplaced] those left out (on the ground; no altitude), [credit] the line the view carries.
     */
    class Flights(
        val list: List<SkyFlight>, val state: FeedState, val message: String?, val total: Int, val onGround: Int, val unplaced: Int,
        val range: SkyRange, val credit: String, val atMs: Long, val owners: Set<String>,
    )

    fun flightsFor(h: Gnss.HorizonState?, pos: android.location.Location?, range: SkyRange, now: Long = System.currentTimeMillis()): Flights {
        val v = AircraftPoller.view.value
        val profile = h?.profile
        val eye = profile?.let { HorizonFlights.Eye.of(it) } ?: pos?.let { HorizonFlights.Eye(it.latitude, it.longitude, if (it.hasAltitude()) it.altitude else 0.0) }
        val list = HorizonFlights.of(v.aircraft, eye, profile, range, now)
        return Flights(
            list, v.state, v.message, v.aircraft.size, v.aircraft.count { HorizonFlights.onGround(it) }, HorizonFlights.unplaced(v.aircraft),
            range, AircraftPoller.credit, v.atMs, v.owners,
        )
    }

    /** The row's altitude in the user's units, with the flight level (T311's formatter). */
    fun altitudeText(f: SkyFlight, units: io.github.jasondorjeshort.dystopo.render.ContourUnits): String =
        AircraftText.altitudeLine(f.aircraft.altitudeM, f.aircraft.altitudeKind, null, units, f.aircraft.report.altBaroFt)

    fun flightsJson(f: Flights?, drawn: Collection<String>?): Map<String, Any?>? = f?.let {
        linkedMapOf(
            "state" to it.state.json, "message" to it.message, "range" to it.range.key, "credit" to it.credit, "owners" to it.owners.toList(),
            "held" to it.total, "listed" to it.list.size, "onGround" to it.onGround, "noAltitude" to it.unplaced,
            "hollow" to it.list.count { s -> s.hollow }, "named" to drawn?.size,
            "aircraft" to it.list.map { s ->
                linkedMapOf(
                    "hex" to s.hex, "label" to s.label, "kind" to s.aircraft.report.kind.json, "emergency" to s.aircraft.report.emergency?.json,
                    "bearingDeg" to r1(s.bearingDeg), "elevationDeg" to r2(s.elevationDeg), "distanceM" to Math.round(s.distanceM),
                    "altitudeM" to s.aircraft.altitudeM?.let { a -> Math.round(a) }, "altitudeKind" to s.aircraft.altitudeKind.json,
                    "horizonDeg" to s.horizonDeg?.let(::r2), "hidden" to s.hidden, "stale" to s.stale, "hollow" to s.hollow,
                    "ageS" to r1(s.aircraft.ageS), "tailPoints" to s.tail.size, "tailS" to r1(s.tailS),
                    "named" to drawn?.let { d -> s.hex in d },
                )
            },
        )
    }

    // ---- JSON ----

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
    private fun r2(v: Double) = Math.round(v * 100) / 100.0
    private fun iso(ms: Long?): String? = ms?.let { java.time.Instant.ofEpochMilli(it).toString() }

    /** T309: [named] the keys of the peaks the plot last named ([HorizonUi.names]); null when it has not drawn them. */
    fun peaksJson(p: Peaks?, named: Set<String>?): Map<String, Any?>? = p?.let {
        val rank = it.ranked.withIndex().associate { (i, s) -> s.label.ref to i + 1 }
        linkedMapOf(
            "reason" to it.reason, "ms" to it.ms, "tilesRead" to it.tiles, "tested" to it.all.size, "visible" to it.all.count { v -> v.visible },
            "skyline" to it.all.count { v -> v.skyline }, "candidates" to it.candidates.size, "outOfSight" to it.candidates.count { v -> !PeakRim.inSight(v) },
            "hiddenM" to PeakRim.HIDDEN_M, "named" to named?.size, "maps" to it.maps,
            "peaks" to it.candidates.map { v ->
                val s = it.scoreOf(v)
                linkedMapOf(
                    "name" to v.peak.name, "eleM" to Math.round(v.eleM), "distanceM" to Math.round(v.distanceM), "bearingDeg" to r1(v.bearingDeg),
                    "compass" to v.compass, "angleDeg" to r2(v.angleDeg), "clearanceDeg" to r2(v.clearanceDeg), "inSight" to PeakRim.inSight(v),
                    "skyline" to v.skyline, "dominant" to (s?.label?.dominant == true), "promM" to s?.label?.promM?.let { m -> Math.round(m) },
                    "score" to s?.let { x -> r2(x.score) }, "rank" to rank[v], "named" to named?.let { n -> HorizonUi.keyOf(v) in n },
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
