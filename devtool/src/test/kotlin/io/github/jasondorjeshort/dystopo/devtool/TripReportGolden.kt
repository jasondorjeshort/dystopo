package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.Paper
import io.github.jasondorjeshort.dystopo.render.TripReport
import io.github.jasondorjeshort.dystopo.routing.PaceFit
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot

/**
 * T198: the trip report's golden: an A4 page at 60 dpi over the lake-cliff synthetic terrain (placed at 45 N, 0.5 E as
 * the sheet's golden), a synthetic recording from the lake's east shore up to the cliff top with an 8-minute and a
 * 3-minute stop, its heights the terrain's, a fix every 5 s; a fixed fit so the pace line reads "your pace", the print
 * date and the zone fixed.
 */
object TripReportGolden {
    const val FILE = "print/trip-report-lake-cliff-a4-60dpi.png"
    const val EPOCH_MS = PrintGolden.EPOCH_MS
    private const val LAT = 45.0
    private const val LON = 0.5

    /** The walker's fit: a little quicker than Tobler's on the flat. */
    val FIT = PaceFit(TravelMode.HIKE, 1.75, 3.5, -0.05, 6, 30_000.0)

    private val dLat = 1.0 / 111_320
    private val dLon = dLat / cos(Math.toRadians(LAT))

    /** The recording: terrain metres (x east, y south) at 1.1 m/s, stopping at the third and fifth corners. */
    fun track(): Track {
        val corners = listOf(-1500.0 to 1400.0, -900.0 to 1250.0, -300.0 to 900.0, 200.0 to 300.0, 700.0 to -400.0, 1200.0 to -900.0)
        val stops = mapOf(2 to 8 * 60, 4 to 3 * 60)
        val pts = ArrayList<TrackPoint>()
        var t = EPOCH_MS - 3 * 3600_000L
        fun add(x: Double, y: Double, speed: Double) {
            pts += TrackPoint(LAT - y * dLat, LON + x * dLon, SyntheticTerrain.LAKE_CLIFF.elevationAt(x, y), t, accuracyM = 5.0, speedMps = speed)
            t += 5_000
        }
        for (k in 0 until corners.size - 1) {
            val (x0, y0) = corners[k]
            val (x1, y1) = corners[k + 1]
            val n = (hypot(x1 - x0, y1 - y0) / (1.1 * 5)).toInt()
            for (i in 0 until n) add(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n, 1.1)
            stops[k + 1]?.let { s -> repeat(s / 5) { add(x1, y1, 0.0) } }
        }
        add(corners.last().first, corners.last().second, 1.1)
        return Track("golden", "Lake Cliff walk (synthetic)", listOf(TrackSegment(pts)), activity = Activity.FOOT)
    }

    fun render(): PrintedReport {
        val req = TripReportSheet.Request(
            track = track(), title = "Lake Cliff walk (synthetic)", paper = Paper.A4, units = ContourUnits.METERS, dpi = 60.0,
            fits = mapOf(TravelMode.HIKE to FIT), epochMs = EPOCH_MS, zone = ZoneOffset.UTC,
        )
        val summary = TripReport.summarize(req.track, ContourUnits.METERS, req.fits)
        val layout = TripReport.plan(summary, Paper.A4, landscape = false, dpi = 60.0)
        // The terrain centred on the frame's centre, in terrain metres from the origin.
        val (cLat, cLon) = layout.frameCenter
        val grid = SyntheticTerrain.LAKE_CLIFF.grid(
            layout.frameWidthPx, layout.frameHeightPx, layout.metersPerPixel, (cLon - LON) / dLon, -(cLat - LAT) / dLat,
        )
        return TripReportSheet.fromGrid(req, summary, layout, grid, "Synthetic terrain (test data).", emptyList())
    }

    /** Text and labels: compared loosely, as the sheet's golden. */
    fun mask(r: PrintedReport): BooleanArray {
        val w = r.page.width
        val h = r.page.height
        val mask = BooleanArray(w * h)
        val l = r.layout
        val map = Goldens.labelMask(r.mapLabels, l.frameWidthPx, l.frameHeightPx)
        for (y in 0 until l.frameHeightPx) for (x in 0 until l.frameWidthPx) if (map[y * l.frameWidthPx + x]) {
            val px = x + l.frameXPx
            val py = y + l.frameYPx
            if (px in 0 until w && py in 0 until h) mask[py * w + px] = true
        }
        for (b in r.textBoxes) {
            for (y in b.y.toInt().coerceAtLeast(0)..ceil(b.maxY).toInt().coerceAtMost(h - 1))
                for (x in b.x.toInt().coerceAtLeast(0)..ceil(b.maxX).toInt().coerceAtMost(w - 1)) mask[y * w + x] = true
        }
        return mask
    }
}
