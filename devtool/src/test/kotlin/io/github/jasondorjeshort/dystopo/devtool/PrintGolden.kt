package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.PageLayout
import io.github.jasondorjeshort.dystopo.render.Paper
import io.github.jasondorjeshort.dystopo.render.SheetGrid
import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import kotlin.math.ceil
import kotlin.math.cos

/**
 * T167: the printed sheet's golden: an A4 page at 1:25,000 over the lake-cliff synthetic terrain, at 60 dpi
 * (small; the real page is 300 dpi), with the UTM grid and the latitude and longitude ticks, a synthetic
 * route and one waypoint. The terrain is placed at 45 N, 0.5 E (as the OSM golden's window) and the print
 * date fixed, so the grid labels and the declination never move.
 */
object PrintGolden {
    const val FILE = "print/sheet-lake-cliff-a4-60dpi.png"

    /** 2026-01-01T12:00Z: noon, so the printed date is the same in every time zone. */
    const val EPOCH_MS = 1_767_268_800_000L

    fun render(): PrintedSheet {
        val lat = 45.0
        val lon = 0.5
        val layout = PageLayout.plan(Paper.A4, landscape = false, scale = 25_000.0, centerLat = lat, centerLon = lon, dpi = 60.0)
        val grid = SyntheticTerrain.LAKE_CLIFF.grid(layout.frameWidthPx, layout.frameHeightPx, layout.metersPerPixel, 0.0, 0.0)
        // A route past the lake's east shore to the cliff top and a waypoint on the cliff: terrain metres (y down) to degrees.
        val dLat = 1.0 / 111_320
        val dLon = dLat / cos(Math.toRadians(lat))
        val route = Route(
            "r1", "Shore",
            listOf(-1500.0 to 1400.0, -300.0 to 900.0, 200.0 to 300.0, 700.0 to -400.0, 1200.0 to -900.0)
                .map { (x, y) -> RoutePoint(lat - y * dLat, lon + x * dLon) },
        )
        val gpx = GpxDocument(waypoints = listOf(Waypoint("w1", "Camp", lat - 800 * dLat, lon + 900 * dLon)), routes = listOf(route))
        val req = SheetRequest(
            title = "Lake Cliff (synthetic)", centerLat = lat, centerLon = lon, paper = Paper.A4, grid = SheetGrid.BOTH,
            units = ContourUnits.METERS, dpi = 60.0, gpx = gpx, epochMs = EPOCH_MS,
        )
        return PrintSheet.fromGrid(req, layout, grid, "Synthetic terrain (test data).", emptyList())
    }

    /** Text and labels: compared loosely, as the map goldens' label boxes. */
    fun mask(sheet: PrintedSheet): BooleanArray {
        val w = sheet.page.width
        val h = sheet.page.height
        val mask = BooleanArray(w * h)
        val l = sheet.layout
        val map = Goldens.labelMask(sheet.mapLabels, l.frameWidthPx, l.frameHeightPx)
        for (y in 0 until l.frameHeightPx) for (x in 0 until l.frameWidthPx) if (map[y * l.frameWidthPx + x]) {
            val px = x + l.frameXPx
            val py = y + l.frameYPx
            if (px in 0 until w && py in 0 until h) mask[py * w + px] = true
        }
        for (r in sheet.textBoxes) {
            for (y in r.y.toInt().coerceAtLeast(0)..ceil(r.maxY).toInt().coerceAtMost(h - 1))
                for (x in r.x.toInt().coerceAtLeast(0)..ceil(r.maxX).toInt().coerceAtMost(w - 1)) mask[y * w + x] = true
        }
        return mask
    }
}
