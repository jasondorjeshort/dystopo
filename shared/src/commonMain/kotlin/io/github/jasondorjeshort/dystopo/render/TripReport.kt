package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.gpx.ProfileSample
import io.github.jasondorjeshort.dystopo.gpx.TrackDrawing
import io.github.jasondorjeshort.dystopo.gpx.TrackSmoothing
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.gpx.TrackStop
import io.github.jasondorjeshort.dystopo.gpx.TrackStops
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.routing.PaceFit
import io.github.jasondorjeshort.dystopo.routing.PaceFits
import io.github.jasondorjeshort.dystopo.routing.PaceRange
import io.github.jasondorjeshort.dystopo.routing.TrackMatch
import io.github.jasondorjeshort.dystopo.routing.TrackSnapper
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * T198: one figure of the trip report's margin: [key] for the JSON, [label] and [value] as printed, [large] for the
 * three the summaries lead with, [raw] in metres or seconds.
 */
data class TripFigure(val key: String, val label: String, val value: String, val large: Boolean, val raw: Double)

/**
 * T198: the walk's moving time against what a pace fit predicts for the same pieces ([PaceFits.wholeTrackRatios]
 * over the track's own [TrackMatch] pieces): [actualS] and [predictedS] over [metres] of pieces; [yours] when the fit is
 * the walker's own, false for the standard estimate ([PaceFit.TOBLER]).
 */
data class PaceAgainst(val actualS: Double, val predictedS: Double, val metres: Double, val yours: Boolean) {
    val deltaS: Double get() = actualS - predictedS

    /** "Time against your pace: 12 min faster" (ADR 0031: the owner's to adjust). */
    val line: String get() {
        val m = (abs(deltaS) / 60).roundToLong()
        val against = if (yours) "your pace" else "the standard estimate"
        val how = when {
            m == 0L -> "within a minute"
            deltaS < 0 -> "${TripReport.duration(m * 60.0)} faster"
            else -> "${TripReport.duration(m * 60.0)} slower"
        }
        return "Time against $against: $how"
    }
}

/**
 * T198: what the trip report shows of one track, worked out once: [stats] ([TrackStats] with the Tracks list's options),
 * the [stops] ([TrackStops.cut]) with where each falls along the [profile] ([stopAtM]), the drawn [line] (the map's
 * smoothed line, [TrackDrawing.line]), the margin's [figures] in the survey's order and the [pace] line (null with the
 * reason in [paceReason]: `yours`, `standard`, `noTimes`, `tooShort` under [PaceRange.MIN_TRACK_M] of pieces, `mode`
 * for a mode without a fit or a standard).
 */
class TripSummary(
    val name: String,
    val units: ContourUnits,
    val stats: TrackStats,
    val stops: List<TrackStop>,
    val stopAtM: List<Double>,
    val profile: List<ProfileSample>,
    val line: List<List<TrackPoint>>,
    val figures: List<TripFigure>,
    val pace: PaceAgainst?,
    val paceReason: String,
    val mode: TravelMode?,
) {
    /** The map's box: the line with [TripReport.MAP_MARGIN] round it, at least [TripReport.MIN_MAP_M] across. */
    val mapBox: BoundingBox? get() = TrackDrawing.fitBounds(
        listOf(Track("", "", line.map { io.github.jasondorjeshort.dystopo.model.TrackSegment(it) })), emptyList(), emptyList(),
        TripReport.MAP_MARGIN, TripReport.MIN_MAP_M,
    )

    /** The margin's lines under the rule: the pace line now; the earlier walks and water counts are follow-ups. */
    val extraLines: List<String> get() = listOfNotNull(pace?.line)

    fun json(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "units" to units.symbol,
        "figures" to figures.map { linkedMapOf("key" to it.key, "label" to it.label, "value" to it.value, "large" to it.large, "raw" to r1(it.raw)) },
        "extraLines" to extraLines,
        "pace" to linkedMapOf(
            "reason" to paceReason, "mode" to mode?.label,
            "actualMin" to pace?.let { r1(it.actualS / 60) }, "predictedMin" to pace?.let { r1(it.predictedS / 60) },
            "deltaMin" to pace?.let { r1(it.deltaS / 60) }, "pieceKm" to pace?.let { r1(it.metres / 1000) },
        ),
        "stops" to stops.mapIndexed { i, s -> linkedMapOf("atKm" to r1(stopAtM[i] / 1000, 3), "minutes" to r1(s.durationS / 60)) },
        "points" to stats.points,
        "segments" to stats.segments,
        "profileSamples" to profile.size,
    )

    private fun r1(v: Double, places: Int = 1): Double {
        val k = 10.0.pow(places)
        return (v * k).roundToLong() / k
    }
}

/**
 * T198: the trip report, one page after a recording on T183's [PageLayout] and [SheetCanvas]: the map frame fitted to
 * the track ([plan]) with the track drawn, an elevation profile strip with the stops marked, and a margin of the day's
 * numbers ([TripReportPage]). Generated on demand from the stored track, never stored.
 *
 * The margin's order and labels are the survey's of 2026-10-31 (ADR 0039; T198's Research line): every summary leads
 * with distance, a time (moving time) and elevation gain, keeps descent and the highest and lowest points in a
 * secondary block, and shows no average pace on a hiking summary; our own lines (the time against the walker's pace)
 * come last under a rule. Distance, moving time and ascent are in larger type, as the three-figure share images.
 */
object TripReport {
    /** Round the map's box, as a share of its size, so the track never touches the frame. */
    const val MAP_MARGIN = 0.08
    const val MIN_MAP_M = 1000.0

    /** The scale is never larger than this (a short walk is not drawn at street scale). */
    const val MIN_SCALE = 5000.0

    /** Samples along the profile strip: one every 0.3 mm on a 125 mm strip. */
    const val PROFILE_SAMPLES = 400

    const val MARGIN_MM = PageLayout.MARGIN_MM
    const val TITLE_MM = 13.0
    const val GAP_MM = 4.0

    /**
     * The band under the map: the profile strip on the left, the figures on the right. 52 mm: the strip's plot is about
     * 38 mm high once its caption, stop labels and distance labels are in, enough for a 1000 m climb to read in 25 m
     * steps, and the seven figures, the rule and two lines fit beside it at 8 to 13 pt.
     */
    const val DATA_MM = 52.0

    /** The profile strip's share of the band's width; the figures take the rest. */
    const val PROFILE_SHARE = 0.62
    const val FOOTER_MM = 9.0

    /** The map frame of a report page: full width, under the title, over the data band and the footer. */
    fun frameMm(paper: Paper, landscape: Boolean): RectMm {
        val w = if (landscape) paper.heightMm else paper.widthMm
        val h = if (landscape) paper.widthMm else paper.heightMm
        val top = MARGIN_MM + TITLE_MM
        val bottom = GAP_MM + DATA_MM + 2.0 + FOOTER_MM + MARGIN_MM
        return RectMm(MARGIN_MM, top, w - 2 * MARGIN_MM, h - top - bottom)
    }

    /** The page for [summary] on [paper] (null: by region), its map at the next tidy scale that fits the track. */
    fun plan(summary: TripSummary, paper: Paper?, landscape: Boolean, dpi: Double): PageLayout {
        val box = requireNotNull(summary.mapBox) { "the track has no points" }
        val (lat, lon) = PageLayout.center(box)
        val p = paper ?: Paper.defaultAt(lat, lon)
        val frame = frameMm(p, landscape)
        val scale = tidyScale(PageLayout.fitScale(box, p, landscape, frame))
        return PageLayout.plan(p, landscape, scale, lat, lon, dpi, frame)
    }

    private val TIDY = doubleArrayOf(1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 7.5, 10.0)

    /** The next tidy denominator at or above [scale]: 1, 1.25, 1.5, 2, 2.5, 3, 4, 5, 6 or 7.5 times a power of ten, at least [MIN_SCALE]. */
    fun tidyScale(scale: Double): Double {
        val s = max(scale, MIN_SCALE)
        val p = 10.0.pow(floor(log10(s)))
        val f = TIDY.first { it * p >= s * (1 - 1e-9) }
        return f * p
    }

    /**
     * [track]'s report figures in [units]. [fits] are the walker's own (the phone's [PaceFits.active]); a hike without one
     * is held against the standard estimate. [snapper] places the pieces on the trail network as the fit's were (null:
     * the track's own heights).
     */
    fun summarize(track: Track, units: ContourUnits, fits: Map<TravelMode, PaceFit> = emptyMap(), snapper: TrackSnapper? = null): TripSummary {
        val raw = track.segments.map { it.points }
        val stats = TrackStats.of(raw)
        val cut = TrackStops.cut(raw)
        val profile = TrackStats.profile(raw, PROFILE_SAMPLES)
        val along = alongProfile(raw)
        val stopAt = cut.stops.map { along[it.segment][it.startIndex] }
        val figures = ArrayList<TripFigure>()
        figures += TripFigure("distance", "Distance", UnitFormat.distance(stats.distanceM, units), true, stats.distanceM)
        stats.movingTimeS?.let { figures += TripFigure("movingTime", "Moving time", duration(it), true, it) }
        stats.durationS?.let { figures += TripFigure("totalTime", "Total time", duration(it), false, it) }
        stats.ascentM?.let { figures += TripFigure("ascent", "Ascent", UnitFormat.elevation(it, units), true, it) }
        stats.descentM?.let { figures += TripFigure("descent", "Descent", UnitFormat.elevation(it, units), false, it) }
        stats.maxEleM?.let { figures += TripFigure("highest", "Highest point", UnitFormat.elevation(it, units), false, it) }
        stats.minEleM?.let { figures += TripFigure("lowest", "Lowest point", UnitFormat.elevation(it, units), false, it) }

        var pace: PaceAgainst? = null
        var reason: String
        var mode: TravelMode? = null
        if (stats.durationS == null) {
            reason = "noTimes"
        } else {
            val match = TrackMatch.match(track, snapper)
            mode = PaceFits.modeOf(track, match.medianMps)
            val own = mode?.let { fits[it] }
            val fit = own ?: if (mode == TravelMode.HIKE) PaceFit.TOBLER else null
            reason = if (fit == null) "mode" else if (own != null) "yours" else "standard"
            if (fit != null) {
                // The track's own pieces as one track (rank 0) against the fit: the whole-track statistic of T184.
                val ratio = PaceFits.wholeTrackRatios(match.pieces.map { it.copy(track = 0) }, fit).firstOrNull()
                if (ratio == null) reason = "tooShort" else pace = PaceAgainst(ratio.actualS, ratio.predictedS, ratio.metres, own != null)
            }
        }
        return TripSummary(track.name, units, stats, cut.stops, stopAt, profile, TrackDrawing.line(track), figures, pace, reason, mode)
    }

    /**
     * For each raw point of [segments], the distance along the profile ([TrackStats.profile]'s line: the smoothed points
     * without flagged fixes, no distance across a segment gap) at that point; a flagged point takes the last kept one's.
     */
    fun alongProfile(segments: List<List<TrackPoint>>): List<DoubleArray> {
        val smoothed = TrackSmoothing.smooth(segments)
        var total = 0.0
        return smoothed.map { s ->
            val kept = s.count(TrackSmoothing::counts)
            val all = kept == s.size || kept < 2
            val at = DoubleArray(s.size)
            var prev: TrackPoint? = null
            for ((i, p) in s.withIndex()) {
                if (all || TrackSmoothing.counts(p)) {
                    prev?.let { total += TrackStats.distanceM(it.lat, it.lon, p.lat, p.lon) }
                    prev = p
                }
                at[i] = total
            }
            at
        }
    }

    /** "4 h 10 min", "45 min", "0 min": a time to the minute. */
    fun duration(s: Double): String {
        val m = (s / 60).roundToLong()
        return if (m < 60) "$m min" else "${m / 60} h ${(m % 60).toString().padStart(2, '0')} min"
    }
}

/**
 * T198: the trip report's page around its map: the track over the map (white casing, the track colour, green start and
 * red end), a scale bar and north arrow in the map's lower-left corner, the title band (the track's name, the day and
 * the ratio), the elevation profile strip with the stops marked, the figures, and the footer (heights, stops, contour
 * interval, print date, sources). devtool draws it over its rendered map, the phone over a MapLibre snapshot; [texts]
 * lists every text in drawing order.
 *
 * The strip marks each stop as a dark dot on the profile line with a hairline up to its minutes at the strip's top,
 * a label skipped where it would touch the one before: a dot reads on a black-and-white print where a colour would not,
 * and the minutes in their own row never sit on the line.
 */
class TripReportPage(
    val layout: PageLayout,
    val summary: TripSummary,
    val title: String,
    /** The walk's day as printed ("2026-10-31"), null when the track has no times. */
    val walked: String?,
    /** The print date, YYYY-MM-DD. */
    val printed: String,
    val attribution: String,
    /** "Contour interval 40 feet", or null when the map's interval is not known. */
    val contourText: String? = null,
) {
    val texts = ArrayList<String>()

    /** Where things went, page pixels: the JSON reports them and the golden checks them. */
    var profileBox: BoxPx? = null
        private set
    var figuresBox: BoxPx? = null
        private set
    var stopLabels = 0
        private set

    private fun mm(v: Double) = layout.px(v)
    private fun pt(v: Double) = v * layout.dpi / 72
    private val units get() = summary.units

    fun paint(c: SheetCanvas) {
        texts.clear()
        stopLabels = 0
        val fx = layout.frameXPx.toDouble()
        val fy = layout.frameYPx.toDouble()
        val fw = layout.frameWidthPx.toDouble()
        val fh = layout.frameHeightPx.toDouble()

        track(c, fx, fy, fw, fh)
        scaleAndNorth(c, fx, fy, fh)
        c.rect(fx, fy, fw, fh, null, INK, mm(0.35))

        // Title band: the name, then the day; the ratio on the right.
        val top = mm(TripReport.MARGIN_MM)
        text(c, title, SheetFont(14.0, true), fx, top + pt(14.0) * 0.75)
        val sub = listOfNotNull("Trip report", walked).joinToString(", ")
        text(c, sub, SheetFont(8.0, false), fx, top + pt(14.0) * 0.75 + pt(8.0) * 1.35)
        text(c, layout.ratioText, SheetFont(11.0, true), fx + fw, top + pt(14.0) * 0.75, align = 1.0)

        val bandTop = fy + fh + mm(TripReport.GAP_MM)
        val bandH = mm(TripReport.DATA_MM)
        val profileW = fw * TripReport.PROFILE_SHARE
        profile(c, BoxPx(fx, bandTop, profileW, bandH))
        figures(c, BoxPx(fx + profileW + mm(6.0), bandTop, fw - profileW - mm(6.0), bandH))
        footer(c, fx, bandTop + bandH + mm(2.0) + pt(5.5) * 0.75, fw)
    }

    /** The track over the map, clipped to the frame: a white casing, the track colour, the start and end dots. */
    private fun track(c: SheetCanvas, fx: Double, fy: Double, fw: Double, fh: Double) {
        c.clip(fx, fy, fw, fh)
        val lines = summary.line.filter { it.size >= 2 }.map { seg ->
            DoubleArray(seg.size * 2).also { xy -> seg.forEachIndexed { i, p -> xy[2 * i] = layout.frameX(p.lon); xy[2 * i + 1] = layout.frameY(p.lat) } }
        }
        for (xy in lines) c.roundLine(xy, fx, fy, CASING, mm(1.5))
        for (xy in lines) c.roundLine(xy, fx, fy, TRACK, mm(0.8))
        summary.line.firstOrNull { it.isNotEmpty() }?.first()?.let { dot(c, fx + layout.frameX(it.lon), fy + layout.frameY(it.lat), START) }
        summary.line.lastOrNull { it.isNotEmpty() }?.last()?.let { dot(c, fx + layout.frameX(it.lon), fy + layout.frameY(it.lat), END) }
        c.unclip()
    }

    private fun dot(c: SheetCanvas, x: Double, y: Double, color: Int) {
        c.polygon(circle(x, y, mm(1.6)), WHITE)
        c.polygon(circle(x, y, mm(1.1)), color)
    }

    private fun circle(x: Double, y: Double, r: Double, n: Int = 20): DoubleArray {
        val xy = DoubleArray(2 * n)
        for (i in 0 until n) {
            val a = 2 * kotlin.math.PI * i / n
            xy[2 * i] = x + r * kotlin.math.cos(a)
            xy[2 * i + 1] = y + r * kotlin.math.sin(a)
        }
        return xy
    }

    /** A scale bar in the walker's units and a north arrow, on a white box in the map's lower-left corner. */
    private fun scaleAndNorth(c: SheetCanvas, fx: Double, fy: Double, fh: Double) {
        val bar = UnitFormat.scaleBar(layout.scale / 1000, 40.0, units) ?: return
        val len = mm(bar.px)
        val font = SheetFont(6.5, false)
        val boxW = len + mm(16.0)
        val boxH = mm(8.5)
        val bx = fx + mm(2.0)
        val by = fy + fh - boxH - mm(2.0)
        c.rect(bx, by, boxW, boxH, PANEL, null, 0.0)
        val x0 = bx + mm(2.5)
        val y0 = by + mm(2.0)
        val h = mm(1.3)
        for (i in 0 until 4) c.rect(x0 + len * i / 4, y0, len / 4, h, if (i % 2 == 0) INK else WHITE, INK, mm(0.15))
        val base = y0 + h + mm(0.8) + pt(6.5) * 0.72
        text(c, "0", font, x0, base, align = 0.5)
        text(c, bar.label, font, x0 + len, base, align = 0.5)
        // North: a filled arrow with N under it (Web Mercator keeps north straight up).
        val ax = bx + boxW - mm(4.5)
        val tip = by + mm(1.2)
        c.polygon(doubleArrayOf(ax, tip, ax + mm(1.4), tip + mm(4.0), ax, tip + mm(3.1), ax - mm(1.4), tip + mm(4.0)), INK)
        text(c, "N", SheetFont(6.5, true), ax, by + boxH - mm(0.9), align = 0.5)
    }

    /** The elevation profile strip: a caption, the stop labels' row, the plot with its heights and distances. */
    private fun profile(c: SheetCanvas, box: BoxPx) {
        profileBox = box
        val samples = summary.profile.filter { it.eleM != null }
        val captionFont = SheetFont(7.0, true)
        val small = SheetFont(6.0, false)
        text(c, "Elevation profile" + (if (summary.stops.isNotEmpty()) ", stops marked in minutes" else ""), captionFont, box.x, box.y + pt(7.0) * 0.75)
        if (samples.size < 2) {
            text(c, "No heights in this track.", small, box.x, box.y + mm(10.0))
            return
        }
        val axisW = mm(11.0)
        val plot = BoxPx(box.x + axisW, box.y + mm(8.5), box.w - axisW, box.h - mm(8.5) - mm(5.0))
        val lo0 = samples.minOf { it.eleM!! }
        val hi0 = samples.maxOf { it.eleM!! }
        // Height ticks: a tidy step in the walker's units giving three to five lines.
        val u = units.meters
        val stepU = niceStep((hi0 - lo0) / u / 3.0).coerceAtLeast(if (units == ContourUnits.FEET) 20.0 else 5.0)
        val lo = floor(lo0 / u / stepU) * stepU * u
        val hi = max(ceil(hi0 / u / stepU) * stepU * u, lo + stepU * u)
        val total = summary.profile.last().distanceM.coerceAtLeast(1.0)
        fun x(d: Double) = plot.x + d / total * plot.w
        fun y(e: Double) = plot.maxY - (e - lo) / (hi - lo) * plot.h
        var t = lo
        while (t <= hi + 1e-6) {
            val yy = y(t)
            c.line(plot.x, yy, plot.maxX, yy, GRID, mm(0.12))
            text(c, UnitFormat.elevation(t, units), small, plot.x - mm(1.2), yy + pt(6.0) * 0.35, align = 1.0)
            t += stepU * u
        }
        // The area and the line, one run per segment (a gap, not a step, where the track broke).
        val runs = ArrayList<List<ProfileSample>>()
        var run = ArrayList<ProfileSample>()
        for (s in summary.profile) {
            if (s.eleM == null) continue
            if (run.isNotEmpty() && run.last().segment != s.segment) { runs += run; run = ArrayList() }
            run += s
        }
        if (run.isNotEmpty()) runs += run
        for (r in runs) if (r.size >= 2) {
            val area = DoubleArray(2 * (r.size + 2))
            r.forEachIndexed { i, s -> area[2 * i] = x(s.distanceM); area[2 * i + 1] = y(s.eleM!!) }
            area[2 * r.size] = x(r.last().distanceM); area[2 * r.size + 1] = plot.maxY
            area[2 * r.size + 2] = x(r.first().distanceM); area[2 * r.size + 3] = plot.maxY
            c.polygon(area, AREA)
            val xy = DoubleArray(2 * r.size)
            r.forEachIndexed { i, s -> xy[2 * i] = x(s.distanceM); xy[2 * i + 1] = y(s.eleM!!) }
            c.roundLine(xy, 0.0, 0.0, TRACK, mm(0.35))
        }
        c.line(plot.x, plot.maxY, plot.maxX, plot.maxY, INK, mm(0.2))
        // Distance ticks under the plot.
        val unit = UnitFormat.distanceUnit(total, units).let { if (it == DistanceUnit.METERS) DistanceUnit.KILOMETERS else if (it == DistanceUnit.FEET) DistanceUnit.MILES else it }
        val stepD = niceStep(total / unit.meters / 5.0)
        var d = 0.0
        var lastRight = -Double.MAX_VALUE
        while (d * unit.meters <= total + 1e-6) {
            val xx = x(d * unit.meters)
            c.line(xx, plot.maxY, xx, plot.maxY + mm(1.0), INK, mm(0.15))
            val label = if (d == 0.0) "0" else "${trim(d)} ${unit.symbol}"
            val b = c.measure(label, small, xx, plot.maxY + mm(1.6) + pt(6.0) * 0.72, 0.5, false)
            if (b.x > lastRight + mm(1.0) && b.maxX <= box.maxX + mm(2.0)) {
                text(c, label, small, xx, plot.maxY + mm(1.6) + pt(6.0) * 0.72, align = 0.5)
                lastRight = b.maxX
            }
            d += stepD
        }
        // Stops: a dot on the line, a hairline up, the minutes in the row above the plot.
        val labelY = plot.y - mm(1.2)
        var lastLabel: BoxPx? = null
        for ((i, s) in summary.stops.withIndex()) {
            val at = summary.stopAtM[i]
            val e = eleAt(at) ?: continue
            val xx = x(at)
            val yy = y(e)
            c.line(xx, plot.y, xx, yy, STOP_LINE, mm(0.12))
            c.polygon(circle(xx, yy, mm(0.95)), WHITE)
            c.polygon(circle(xx, yy, mm(0.65)), INK)
            val label = "${(s.durationS / 60).roundToLong()}"
            val b = c.measure(label, small, xx, labelY, 0.5, false)
            if (lastLabel == null || b.x > lastLabel.maxX + mm(0.8)) {
                lastLabel = c.text(label, small, xx, labelY, 0.5, false, INK)
                stopLabels++
            }
        }
    }

    /** The profile's height at [d] metres along, linear between samples. */
    private fun eleAt(d: Double): Double? {
        val p = summary.profile
        var prev: ProfileSample? = null
        for (s in p) {
            val e = s.eleM ?: continue
            if (s.distanceM >= d) {
                val a = prev ?: return e
                val span = s.distanceM - a.distanceM
                return if (span <= 0) e else a.eleM!! + (e - a.eleM) * ((d - a.distanceM) / span)
            }
            prev = s
        }
        return prev?.eleM
    }

    /** The figures, label left and value right, the three lead ones larger; the rule and our lines under them. */
    private fun figures(c: SheetCanvas, box: BoxPx) {
        figuresBox = box
        val label = SheetFont(8.0, false)
        var y = box.y + mm(1.0)
        for (f in summary.figures) {
            val vf = SheetFont(if (f.large) 13.0 else 9.0, true)
            y += if (f.large) mm(6.4) else mm(4.6)
            c.text(f.label, label, box.x, y, 0.0, false, INK)
            c.text(f.value, vf, box.maxX, y, 1.0, false, INK)
            texts += "${f.label}: ${f.value}"
        }
        val extra = summary.extraLines
        if (extra.isNotEmpty()) {
            y += mm(2.6)
            c.line(box.x, y, box.maxX, y, INK, mm(0.2))
            y += mm(1.2) + pt(8.0) * 0.75
            for (l in extra) y = paragraph(c, l, SheetFont(8.0, false), box.x, y, box.w)
        }
    }

    /** Heights, the stop rule, the contour interval, the print date and the sources, wrapped across the page. */
    private fun footer(c: SheetCanvas, x: Double, y: Double, width: Double) {
        val heights = "Heights in ${if (units == ContourUnits.FEET) "feet" else "metres"} above sea level (EGM2008)."
        val stops = "Stops are stays of over a minute."
        val parts = listOfNotNull(heights, stops, contourText?.let { "$it." }, "Printed $printed.", "Sources: $attribution")
        paragraph(c, parts.joinToString(" "), SheetFont(5.5, false), x, y, width)
    }

    private fun paragraph(c: SheetCanvas, text: String, font: SheetFont, x: Double, y: Double, width: Double): Double {
        val lineH = c.fontPx(font) * 1.3
        var line = ""
        var base = y
        for (word in text.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && c.advance(next, font) > width) {
                c.text(line, font, x, base, 0.0, false, INK)
                base += lineH
                line = word
            } else {
                line = next
            }
        }
        if (line.isNotEmpty()) { c.text(line, font, x, base, 0.0, false, INK); base += lineH }
        texts += text
        return base
    }

    private fun text(c: SheetCanvas, s: String, font: SheetFont, x: Double, y: Double, align: Double = 0.0): BoxPx {
        texts += s
        return c.text(s, font, x, y, align, false, INK)
    }

    companion object {
        const val INK = SheetMargin.INK
        const val WHITE = SheetMargin.WHITE
        /** The phone's track colour (`TrackOverlay`). */
        const val TRACK = 0xFFD01C8B.toInt()
        const val CASING = 0xE6FFFFFF.toInt()
        const val AREA = 0x33D01C8B
        const val START = 0xFF1A9850.toInt()
        const val END = 0xFFD73027.toInt()
        const val GRID = 0x55101010
        const val STOP_LINE = 0x99101010.toInt()
        const val PANEL = 0xE6FFFFFF.toInt()

        /** The largest 1, 2 or 5 times a power of ten at most [x] (positive), else 1. */
        fun niceStep(x: Double): Double = if (x > 0 && x.isFinite()) UnitFormat.niceFloor(x) else 1.0

        private fun trim(v: Double): String = if (v == v.roundToLong().toDouble()) v.roundToLong().toString() else UnitFormat.fixed(v, if (v < 1) (if ((v * 10) == (v * 10).roundToLong().toDouble()) 1 else 2) else 1)
    }
}
