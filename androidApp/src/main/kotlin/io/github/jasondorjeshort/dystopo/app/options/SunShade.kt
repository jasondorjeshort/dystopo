package io.github.jasondorjeshort.dystopo.app.options

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.nav.Sun
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * T188: the rules of the sun-and-shade time control, pure so `SunClockTest` checks them.
 *
 * The slider covers one day at the view's centre, from an hour before the flat-horizon sunrise to an hour after the
 * sunset (the whole 24 hours around solar noon in polar day or night), in [STEP_MS] steps on an absolute five-minute
 * grid, so every phone asks for the same instants and the tiles keyed by the minute stay warm. It opens at now while
 * the sun is up today, else two hours before that day's sunset (the survey of 2026-10-30,
 * `out/wave23-research/sun-shade-viewshed.md`: Google Earth opens at now; no app documents another instant; one
 * behaviour, no setting), else at solar noon.
 */
object SunClock {
    /** The slider's step: five minutes. */
    const val STEP_MS = 5 * 60_000L

    /** The time chips' step: a quarter of an hour. */
    const val BUTTON_STEP_MS = 15 * 60_000L

    /** Away from today, or with the sun down, the slider opens this long before sunset. */
    const val BEFORE_SUNSET_MS = 2 * 3_600_000L

    /** The slider runs this long past the flat sunrise and sunset on each side. */
    const val MARGIN_MS = 3_600_000L

    /** One day's slider: [startMs] to [endMs], both on the step grid. */
    data class Range(val startMs: Long, val endMs: Long) {
        fun clamp(ms: Long): Long = ms.coerceIn(startMs, endMs)

        /** [ms] onto the nearest step, inside the range. */
        fun snap(ms: Long): Long = clamp(Math.floorDiv(ms + STEP_MS / 2, STEP_MS) * STEP_MS)

        /** Where [ms] sits along the slider, 0 to 1. */
        fun fraction(ms: Long): Double = if (endMs <= startMs) 0.0 else (clamp(ms) - startMs).toDouble() / (endMs - startMs)

        /** The instant at [fraction] along the slider, snapped. */
        fun at(fraction: Double): Long = snap(startMs + (fraction.coerceIn(0.0, 1.0) * (endMs - startMs)).toLong())
    }

    fun floorStep(ms: Long): Long = Math.floorDiv(ms, STEP_MS) * STEP_MS

    private fun ceilStep(ms: Long): Long = -Math.floorDiv(-ms, STEP_MS) * STEP_MS

    /** The slider for a day whose flat sunrise and sunset are [sunriseMs] and [sunsetMs] (null in polar day or night) around [noonMs]. */
    fun range(sunriseMs: Long?, sunsetMs: Long?, noonMs: Long): Range =
        if (sunriseMs != null && sunsetMs != null) {
            Range(floorStep(sunriseMs - MARGIN_MS), ceilStep(sunsetMs + MARGIN_MS))
        } else {
            Range(floorStep(noonMs - 12 * 3_600_000L), ceilStep(noonMs + 12 * 3_600_000L))
        }

    /**
     * Where the slider opens and why: `now` (floored to the step) when [today] and the sun is up at [nowMs] ([sunUpNow]);
     * else `beforeSunset`, two hours before [sunsetMs]; else (no sunset that day) `noon`.
     */
    fun opening(nowMs: Long, today: Boolean, sunUpNow: Boolean, sunsetMs: Long?, noonMs: Long): Pair<Long, String> = when {
        today && sunUpNow -> floorStep(nowMs) to "now"
        sunsetMs != null -> floorStep(sunsetMs - BEFORE_SUNSET_MS) to "beforeSunset"
        else -> floorStep(noonMs) to "noon"
    }

    /** A clock time in [zone], 24-hour like the turn-around clock ("9:12", "16:40"). */
    fun clock(ms: Long, zone: TimeZone): String {
        val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = ms }
        return String.format(Locale.US, "%d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    /** [zone]'s short name at [ms] ("PDT", "PST"; "GMT+02:00" where the locale has no abbreviation). */
    fun zoneName(zone: TimeZone, ms: Long, locale: Locale = Locale.getDefault()): String =
        zone.getDisplayName(zone.inDaylightTime(java.util.Date(ms)), TimeZone.SHORT, locale)

    /** A date as the controls show it ("Fri 30 Oct"). */
    fun dateLabel(date: LocalDate, locale: Locale = Locale.getDefault()): String = date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))

    /** Solar noon's neighbourhood of [date] in [zone]: the instant the point answer's day is asked for (local noon). */
    fun noonOf(date: LocalDate, zone: TimeZone): Long = date.atTime(12, 0).atZone(zone.toZoneId()).toInstant().toEpochMilli()

    /**
     * The point answer as the long-press card's one line, times in [zone] with the zone named once at the end:
     * "Sun reaches here 9:12, leaves 16:40 PDT", with each gap when a peak blocks the sun between them
     * ("Sun reaches here 9:12, hidden 11:05 to 11:40, leaves 16:40 PDT"). [datePrefix] (a day other than today) starts
     * the line ("Sat 2 Nov: sun reaches here ...").
     */
    fun line(day: Sun.TerrainDay, zone: TimeZone, datePrefix: String? = null, locale: Locale = Locale.getDefault()): String {
        val z = zoneName(zone, day.flat.noonMs, locale)
        val body = when {
            day.alwaysUp -> "sun on this spot all day"
            day.sunlit.isEmpty() -> "no direct sun reaches here"
            else -> {
                val parts = ArrayList<String>()
                parts += day.sunriseMs?.let { "sun reaches here ${clock(it, zone)}" } ?: "sun here from the night before"
                for (g in day.gaps) parts += "hidden ${clock(g.span.startMs, zone)} to ${clock(g.span.endMs, zone)}"
                parts += day.sunsetMs?.let { "leaves ${clock(it, zone)}" } ?: "still up at midnight"
                parts.joinToString(", ") + " $z"
            }
        }
        return if (datePrefix != null) "$datePrefix: $body" else body.replaceFirstChar { it.uppercase() }
    }
}

/**
 * T188: sun and shade on the map (the phone half of the sun-and-shade idea of 2026-10-29; T181 built the tiles and the
 * point answer in `shared`). Off by default and off again at each launch (the shade of one instant is a question asked
 * now, not a look kept): Settings › Map options › Shade turns it on, which picks today at the view's centre and
 * opens the time at now while the sun is up, else two hours before sunset ([SunClock.opening]); a date can be picked,
 * and the slider moves the instant over that day. The map draws T181's tiles (`GET /shade/sun/{z}/{x}/{y}.png?m=<epoch
 * minute>`, [ShadeService.sunTile]) as a raster layer of its own under the contours ([SunShadeLayer]), a cool blue at
 * [io.github.jasondorjeshort.dystopo.tiles.ShadeTiles.SUN_SHADE_OPACITY] where the terrain hides the sun, all of it at
 * night. The long-press card's sun line asks for the same day ([answerDate]).
 *
 * Compose state, written on the main thread; `GET /shade` `sun` and `POST /shade/sun` drive it ([json]).
 */
object SunShade {
    var on by mutableStateOf(false)
        private set

    /** The day shown, in the phone's zone. */
    var date by mutableStateOf<LocalDate>(LocalDate.now())
        private set

    /** The instant the tiles are for (on the slider's step). */
    var instantMs by mutableStateOf(0L)
        private set

    /** What set [instantMs]: `now`, `beforeSunset`, `noon` (the opening rule), `slider`, `step` or `route`. */
    var source by mutableStateOf("now")
        private set

    var range by mutableStateOf(SunClock.Range(0, 0))
        private set

    /** The flat-horizon day at [centre] that set [range]. */
    var day: Sun.Day? = null
        private set

    /** Where the day was worked out (the camera's centre when the shade was turned on or the date picked). */
    var centre: Pair<Double, Double>? = null
        private set

    @Volatile var changes = 0
        private set

    fun zone(): TimeZone = TimeZone.getDefault()

    fun today(): LocalDate = LocalDate.now(zone().toZoneId())

    /** The day the long-press card's sun line answers for: the picked date while the shade is on, else today. */
    fun answerDate(): LocalDate = if (on) date else today()

    /** Turns the shade on (today, the opening instant) or off. Main thread. */
    fun turn(v: Boolean) {
        if (v == on) return
        on = v
        changes++
        if (v) open(today())
        SunShadeLayer.refresh()
        io.github.jasondorjeshort.dystopo.app.tracking.LongPressMenu.onSunDay()
    }

    /** Shows [d] at the opening instant ([SunClock.opening]); turns the shade on. Main thread. */
    fun pickDate(d: LocalDate) {
        on = true
        changes++
        open(d)
        SunShadeLayer.refresh()
        io.github.jasondorjeshort.dystopo.app.tracking.LongPressMenu.onSunDay()
    }

    /** Moves the instant to [ms] (snapped, inside the day). Main thread. */
    fun setInstant(ms: Long, why: String) {
        if (!on) return
        val t = range.snap(ms)
        source = why
        if (t == instantMs) return
        instantMs = t
        changes++
        SunShadeLayer.refresh()
    }

    /** [steps] quarter hours later (earlier when negative). Main thread. */
    fun step(steps: Int) = setInstant(instantMs + steps * SunClock.BUTTON_STEP_MS, "step")

    private fun open(d: LocalDate) {
        val c = cameraCentre() ?: centre ?: DEFAULT_CENTRE
        centre = c
        val (lat, lon) = c
        val sd = Sun.day(lat, lon, d.year, d.monthValue, d.dayOfMonth)
        day = sd
        date = d
        val now = System.currentTimeMillis()
        val up = Sun.position(lat, lon, now).let { it.apparentAltitudeDeg + Sun.SEMI_DIAMETER_DEG > 0 }
        val r = SunClock.range(sd.sunriseMs, sd.sunsetMs, sd.noonMs)
        range = r
        val (at, why) = SunClock.opening(now, d == today(), up, sd.sunsetMs, sd.noonMs)
        instantMs = r.clamp(at)
        source = why
    }

    private fun cameraCentre(): Pair<Double, Double>? =
        runCatching { DystopoApp.instance.map.map?.cameraPosition?.target?.let { it.latitude to it.longitude } }.getOrNull()

    /** The tiles' URL template for the instant shown, with the loopback token; null while off. */
    fun tileUrl(): String? {
        if (!on) return null
        val app = DystopoApp.instance
        val v = app.tiles.shade.sunUrlVersion()
        return app.token.url("http://127.0.0.1:${BuildConfig.SERVER_PORT}/shade/sun/{z}/{x}/{y}.png?m=${Math.floorDiv(instantMs, 60_000L)}&v=$v")
    }

    /** `GET /shade` `sun`: the state, the instant and the slider as drawn. */
    fun json(): Map<String, Any?> {
        val z = zone()
        val r = range
        return linkedMapOf(
            "on" to on,
            "date" to date.toString(),
            "today" to (date == today()),
            "dateLabel" to SunClock.dateLabel(date),
            "instantMs" to if (on) instantMs else null,
            "instantUtc" to if (on) java.time.Instant.ofEpochMilli(instantMs).toString() else null,
            "instantLocal" to if (on) SunClock.clock(instantMs, z) else null,
            "zone" to z.id,
            "zoneName" to SunClock.zoneName(z, if (on) instantMs else System.currentTimeMillis()),
            "source" to source,
            "centre" to centre?.let { mapOf("lat" to it.first, "lon" to it.second) },
            "sun" to centre?.takeIf { on }?.let { (lat, lon) ->
                Sun.position(lat, lon, instantMs).let { p -> mapOf("azimuthDeg" to round1(p.azimuthDeg), "altitudeDeg" to round1(p.apparentAltitudeDeg)) }
            },
            "flatDay" to day?.let { d -> mapOf("sunriseMs" to d.sunriseMs, "sunsetMs" to d.sunsetMs, "noonMs" to d.noonMs, "sunrise" to d.sunriseMs?.let { SunClock.clock(it, z) }, "sunset" to d.sunsetMs?.let { SunClock.clock(it, z) }) },
            "slider" to linkedMapOf(
                "startMs" to r.startMs, "endMs" to r.endMs, "valueMs" to if (on) instantMs else null,
                "fraction" to if (on) round3(r.fraction(instantMs)) else null,
                "start" to SunClock.clock(r.startMs, z), "end" to SunClock.clock(r.endMs, z),
                "stepMin" to SunClock.STEP_MS / 60_000, "buttonStepMin" to SunClock.BUTTON_STEP_MS / 60_000,
            ),
            "tileUrl" to tileUrl()?.substringBefore("&t="),
            "layer" to SunShadeLayer.json(),
            "changes" to changes,
        )
    }

    private fun round1(d: Double) = Math.round(d * 10) / 10.0
    private fun round3(d: Double) = Math.round(d * 1000) / 1000.0

    /** Before the map has a camera: Paradise, Mount Rainier (only the slider's ends depend on it). */
    private val DEFAULT_CENTRE = 46.786 to -121.735
}

/**
 * T188: the sun-and-shade raster layer, added to the style at run time (the style itself, `MapLibreStyle` in `shared`,
 * is unchanged, so the shade off leaves the map exactly as before): a `raster` source over [SunShade.tileUrl] and its
 * layer under the contours (where the hillshade tiles sit), replaced whenever the instant changes and added again after
 * every style reload. Bound to the map view with the track overlay (`TrackControls`). Main thread.
 */
object SunShadeLayer {
    const val SOURCE = "sun-shade"
    const val LAYER = "sun-shade"

    /**
     * Tiles are made to this zoom; MapLibre stretches the deepest past it. On the emulator a z12 to z14 tile took 0.46 to
     * 0.94 s with the sun 10 to 29 degrees up, 1.1 to 1.3 s at 3 degrees (the march's range grows as the sun drops, at
     * every zoom alike, so no zoom cap helps there).
     */
    const val MAX_ZOOM = 14

    /**
     * No shade below map zoom 10: a z9 tile took 1.3 s on the emulator at 10 degrees (3.7 million ray samples against
     * about 0.3 million at z10 to z14, the coarse layer too coarse for block skipping), and the shadows of a 300 m pixel
     * mean little.
     */
    const val MIN_ZOOM = 10

    private val main = Handler(Looper.getMainLooper())
    private var view: MapView? = null
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { apply() }

    @Volatile private var installedUrl: String? = null
    @Volatile var installs = 0
        private set
    @Volatile var removals = 0
        private set

    fun bind(mapView: MapView) {
        if (view === mapView) return
        unbind()
        view = mapView
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        apply()
    }

    fun unbind() {
        view?.removeOnDidFinishLoadingStyleListener(styleListener)
        view = null
        installedUrl = null
    }

    /** Brings the layer in line with [SunShade] (any thread). */
    fun refresh() {
        main.post { apply() }
    }

    private fun apply() {
        val v = view ?: return
        v.getMapAsync { map -> map.getStyle { style -> applyTo(style) } }
    }

    private fun applyTo(style: Style) {
        val url = runCatching { SunShade.tileUrl() }.getOrNull()
        val present = style.getLayer(LAYER) != null
        if (url == installedUrl && present == (url != null)) return
        style.getLayer(LAYER)?.let { style.removeLayer(it); removals++ }
        style.getSource(SOURCE)?.let { style.removeSource(it) }
        installedUrl = null
        if (url == null) return
        val tiles = TileSet("2.1.0", url).apply {
            setMinZoom(MIN_ZOOM.toFloat())
            setMaxZoom(MAX_ZOOM.toFloat())
        }
        // Each 256 px tile drawn over 512 px of screen: MapLibre asks for the view's own zoom, not one deeper, so a screen
        // takes a quarter of the tiles. At 256 a z13 view asked for some 30 z14 tiles, each 1.4 to 3 s in the app beside
        // the contours, and MapLibre gave up on the last ones (a request times out), leaving holes in the shade.
        // No coarser tiles fetched ahead as stand-ins (MapLibre's default reaches four zooms out): each costs as much as a
        // tile of the view, and a z10 one took 2.3 to 3.1 s beside a z13 view's six.
        style.addSource(RasterSource(SOURCE, tiles, TILE_SIZE_DP).apply { setPrefetchZoomDelta(0) })
        val layer = RasterLayer(LAYER, SOURCE).withProperties(
            PropertyFactory.rasterFadeDuration(0f),
            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
        )
        if (style.getLayer(BELOW) != null) style.addLayerBelow(layer, BELOW) else style.addLayer(layer)
        installedUrl = url
        installs++
    }

    /** The size MapLibre draws a tile at (see [applyTo]). */
    const val TILE_SIZE_DP = 512

    /** The contour lines' bottom layer: the shade goes right under it, over the land cover and the hillshade. */
    private const val BELOW = "contour-minor"

    fun json(): Map<String, Any?> = linkedMapOf(
        "bound" to (view != null), "installed" to (installedUrl != null), "installs" to installs, "removals" to removals,
        "minZoom" to MIN_ZOOM, "maxZoom" to MAX_ZOOM, "tileSize" to TILE_SIZE_DP,
    )
}
