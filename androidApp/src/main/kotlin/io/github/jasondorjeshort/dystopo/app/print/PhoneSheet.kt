package io.github.jasondorjeshort.dystopo.app.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.MapViewport
import io.github.jasondorjeshort.dystopo.app.about.AppCredits
import io.github.jasondorjeshort.dystopo.app.routing.RouteSession
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.maplibre.MapOptions
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.ContourStyle
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.PageLayout
import io.github.jasondorjeshort.dystopo.render.Paper
import io.github.jasondorjeshort.dystopo.render.SheetGrid
import io.github.jasondorjeshort.dystopo.render.SheetMargin
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * T183: what a phone sheet shows: the centre, the scale ([scale] null: fit [fitBox]), the paper (null: by region,
 * Letter in the US and A4 elsewhere, ADR 0022), the orientation and the grid (T167's survey: 1:25,000, portrait,
 * UTM by default), the title (null: "Map sheet" and the centre), the page's dpi and the print date.
 */
data class SheetSpec(
    val centerLat: Double,
    val centerLon: Double,
    val scale: Double? = PageLayout.DEFAULT_SCALE,
    val fitBox: BoundingBox? = null,
    val paper: Paper? = null,
    val landscape: Boolean = false,
    val grid: SheetGrid = SheetGrid.UTM,
    val title: String? = null,
    val dpi: Double = PhoneSheet.DPI,
    val epochMs: Long = System.currentTimeMillis(),
)

/** T183: a made sheet: the one-page PDF, its layout and its report (`POST /print?format=json`). */
class MadeSheet(val pdf: ByteArray, val layout: PageLayout, val report: Map<String, Any?>)

/**
 * T183: the phone's printed sheet. The page is planned by `shared`'s [PageLayout] at [DPI]; the map is a MapLibre
 * snapshot of the frame ([MapSnapshotter] with the day style the map uses, `TileService.style`), at a pixel ratio
 * of dpi / [PageLayout.STYLE_DPI] (lines and labels as thick on paper as on a 150 dpi screen) and the camera zoom
 * [PageLayout.mapLibreZoom] gives, so one snapshot pixel is one page pixel and the scale is exact; the route
 * (RouteSession's plan), the shown or recording track and its waypoints are drawn over it as the map draws them;
 * the margins are [SheetMargin]'s, the same as devtool's sheet, on [AndroidSheetCanvas]. Written with
 * `android.graphics.pdf.PdfDocument` (no dependency): one page of the paper's size in points, the map an image at
 * the page's dpi, the margins vector text. Off the main thread; the snapshot itself runs on the main thread.
 */
object PhoneSheet {
    /** The page's nominal dpi: a Letter frame at 1:25,000 is about 1460 x 1570 px, a 9 MB bitmap. */
    const val DPI = 200.0

    /** The snapshot waits this long for every tile of the frame. */
    const val SNAPSHOT_TIMEOUT_MS = 90_000L

    /** The report of the last sheet made (`GET /print` › `last`). */
    @Volatile var last: Map<String, Any?>? = null
        private set

    /** Sheets made since start, and the last failure. */
    @Volatile var made = 0
        private set
    @Volatile var lastError: String? = null
        private set

    /** The sheet of the map's current view at [scale] (null: fit the view); main thread or any (reads through onMain). */
    fun currentView(app: DystopoApp, scale: Double?, paper: Paper? = null, landscape: Boolean = false, grid: SheetGrid = SheetGrid.UTM, title: String? = null): SheetSpec {
        val cam = app.map.camera()
        require(cam["ready"] == true) { "the map is not ready" }
        val view = MapViewport.current
        val box = view?.let { runCatching { BoundingBox(it.minLon, it.minLat, it.maxLon, it.maxLat) }.getOrNull() }
        return if (scale == null) {
            val b = requireNotNull(box) { "fit needs the map's view (no camera rest yet)" }
            val (lat, lon) = PageLayout.center(b)
            SheetSpec(lat, lon, null, b, paper, landscape, grid, title)
        } else {
            SheetSpec(cam["lat"] as Double, cam["lon"] as Double, scale, box, paper, landscape, grid, title)
        }
    }

    /** Makes the sheet [spec] asks for. Not on the main thread (it waits for the snapshot there). */
    fun make(app: DystopoApp, spec: SheetSpec): MadeSheet {
        try {
            return makeOrThrow(app, spec).also {
                made++
                last = it.report
                lastError = null
            }
        } catch (e: Exception) {
            lastError = e.toString()
            throw e
        }
    }

    private fun makeOrThrow(app: DystopoApp, spec: SheetSpec): MadeSheet {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "PhoneSheet.make runs off the main thread" }
        val t0 = SystemClock.elapsedRealtime()
        val timings = LinkedHashMap<String, Long>()
        fun lap(name: String, since: Long): Long = SystemClock.elapsedRealtime().also { timings[name] = it - since }

        val paper = spec.paper ?: Paper.defaultAt(spec.centerLat, spec.centerLon)
        val scale = spec.scale ?: PageLayout.fitScale(requireNotNull(spec.fitBox) { "fit needs a box" }, paper, spec.landscape)
        val layout = PageLayout.plan(paper, spec.landscape, scale, spec.centerLat, spec.centerLon, spec.dpi)
        val ratio = layout.dpi / PageLayout.STYLE_DPI
        val zoom = layout.mapLibreZoom(ratio)
        val fw = layout.frameWidthPx
        val fh = layout.frameHeightPx
        val logicalW = ceil(fw / ratio).toInt()
        val logicalH = ceil(fh / ratio).toInt()
        val (cLat, cLon) = layout.frameCenter
        // The day style of the map as it is (layers, hillshade, labels, units, text size), never night on paper.
        val styleJson = app.tiles.style(AppSettings.mapOptions.copy(night = false))
        var t = lap("plan", t0)

        val shot = snapshot(app, styleJson, logicalW, logicalH, ratio, cLat, cLon, zoom, layout)
        t = lap("snapshot", t)

        val units = AppSettings.units
        val contour = contourInterval(styleJson, zoom, units)
        val overlay = overlayData(app)
        val b = layout.bbox
        val attribution = AppCredits.compose(app, PackBounds(b.west, b.south, b.east, b.north)).compact
        val north = layout.north(spec.epochMs)
        val date = Instant.ofEpochMilli(spec.epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val title = spec.title ?: defaultTitle(layout)
        val margin = SheetMargin(layout, title, spec.grid, units, contour, units.symbol, north, date, attribution)

        val doc = PdfDocument()
        val wPt = (layout.pageWidthMm / PageLayout.MM_PER_INCH * 72).roundToInt()
        val hPt = (layout.pageHeightMm / PageLayout.MM_PER_INCH * 72).roundToInt()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, 1).create())
        val c = page.canvas
        val k = (72 / layout.dpi).toFloat()
        c.scale(k, k) // page pixels from here on
        val fx = layout.frameXPx.toFloat()
        val fy = layout.frameYPx.toFloat()
        val cropX = ((shot.bitmap.width - fw) / 2).coerceAtLeast(0)
        val cropY = ((shot.bitmap.height - fh) / 2).coerceAtLeast(0)
        c.drawBitmap(
            shot.bitmap, Rect(cropX, cropY, (cropX + fw).coerceAtMost(shot.bitmap.width), (cropY + fh).coerceAtMost(shot.bitmap.height)),
            RectF(fx, fy, fx + fw, fy + fh), Paint(Paint.FILTER_BITMAP_FLAG),
        )
        val drawn = drawOverlay(c, layout, overlay)
        val canvas = AndroidSheetCanvas(c, layout)
        margin.paint(canvas)
        doc.finishPage(page)
        t = lap("draw", t)
        val out = ByteArrayOutputStream(4 shl 20)
        doc.writeTo(out)
        doc.close()
        val pdf = out.toByteArray()
        shot.bitmap.recycle()
        lap("writePdf", t)
        timings["total"] = SystemClock.elapsedRealtime() - t0

        val r6 = { v: Double -> (v * 1e5).roundToLong() / 1e5 }
        val report = linkedMapOf<String, Any?>(
            "title" to title,
            "scale" to layout.scale,
            "ratio" to layout.ratioText,
            "fit" to (spec.scale == null),
            "paper" to paper.key,
            "landscape" to spec.landscape,
            "dpi" to (layout.dpi * 100).roundToLong() / 100.0,
            "nominalDpi" to layout.nominalDpi,
            "pagePoints" to listOf(wPt, hPt),
            "pageWidthPx" to layout.pageWidthPx,
            "pageHeightPx" to layout.pageHeightPx,
            "frameMm" to listOf(layout.frameMm.x, layout.frameMm.y, layout.frameMm.w, layout.frameMm.h),
            "framePx" to listOf(fw, fh),
            "extent" to listOf(b.west, b.south, b.east, b.north).map(r6),
            "groundMeters" to listOf(layout.groundWidthM.roundToLong(), layout.groundHeightM.roundToLong()),
            "center" to listOf(r6(layout.centerLat), r6(layout.centerLon)),
            "metersPerPixel" to (layout.metersPerPixel * 1000).roundToLong() / 1000.0,
            "snapshot" to linkedMapOf(
                "zoom" to (zoom * 1000).roundToLong() / 1000.0,
                "pixelRatio" to (ratio * 1000).roundToLong() / 1000.0,
                "logical" to listOf(logicalW, logicalH),
                "bitmap" to listOf(shot.width, shot.height),
                "crop" to listOf(cropX, cropY),
                "placementErrorPx" to shot.placementErrorPx,
                "attributions" to shot.attributions,
                "ms" to timings["snapshot"],
            ),
            "grid" to spec.grid.key,
            "utmZone" to margin.utm?.zoneName,
            "gridSpacingM" to margin.utm?.spacingM,
            "gridLines" to (margin.utm?.let { it.eastings.size + it.northings.size } ?: 0),
            "graticuleArcSec" to margin.graticule?.spacingArcSec,
            "declinationDeg" to (north.declinationDeg * 10).roundToLong() / 10.0,
            "convergenceDeg" to (north.convergenceDeg * 100).roundToLong() / 100.0,
            "magneticFromGridDeg" to (north.magneticFromGridDeg * 10).roundToLong() / 10.0,
            "date" to date,
            "units" to units.symbol,
            "contourInterval" to contour,
            "attribution" to attribution,
            "overlay" to drawn,
            "margin" to margin.texts.toList(),
            "textsDrawn" to canvas.textsDrawn,
            "pdfBytes" to pdf.size,
            "pages" to 1,
            "timingsMs" to timings,
        )
        return MadeSheet(pdf, layout, report)
    }

    /** "Map sheet 46°47'10"N 121°44'09"W": the centre, as the grid labels' latitude and longitude read. */
    fun defaultTitle(layout: PageLayout): String =
        "Map sheet ${PageLayout.dms(layout.centerLat, lat = true)} ${PageLayout.dms(layout.centerLon, lat = false)}"

    /**
     * The contour interval the snapshot draws at [zoom]: MapLibre draws vector tiles of the floor of the zoom (up to
     * the contour source's `maxzoom`, overzoomed beyond), and contour tile z has the [ContourStyle] spec of DEM zoom
     * z + 1 or the user's fixed interval from [MapOptions.FIXED_INTERVAL_FROM_ZOOM] (`ContourTiles.build`).
     */
    fun contourInterval(styleJson: String, zoom: Double, units: ContourUnits): Double {
        @Suppress("UNCHECKED_CAST")
        val sources = MiniJson.parseObject(styleJson)["sources"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val maxZoom = ((sources?.get("contours") as? Map<String, Any?>)?.get("maxzoom") as? Number)?.toInt() ?: 22
        val tileZ = floor(zoom).toInt().coerceAtMost(maxZoom).coerceAtLeast(0)
        val opts = ContourTileOptions(units = units, interval = AppSettings.mapOptions.contourInterval, intervalFromZoom = MapOptions.FIXED_INTERVAL_FROM_ZOOM)
        return ContourStyle.resolve(ContourTiles.demZoom(tileZ, opts), units, opts.interval?.takeIf { tileZ >= opts.intervalFromZoom }).interval
    }

    /** What the map shows over the terrain: the planned route, the shown (or recording) track and its waypoints. */
    class Overlay(val route: List<Pair<Double, Double>>, val track: List<List<Pair<Double, Double>>>, val waypoints: List<Waypoint>)

    private fun overlayData(app: DystopoApp): Overlay {
        val route = runCatching { app.map.onMain { RouteSession.plan?.route?.points?.map { it.lat to it.lon } } }.getOrNull().orEmpty()
        val tracking = Tracking.of(app)
        val (track, waypoints) = runCatching {
            tracking.call {
                val id = tracking.shownId
                val segments = when {
                    id == null -> emptyList()
                    id == tracking.recorder.activeId -> tracking.recorder.livePoints().map { s -> s.map { it.lat to it.lon } }
                    else -> tracking.store.track(id)?.segments?.map { s -> s.points.map { it.lat to it.lon } }.orEmpty()
                }
                segments to ((id?.let { tracking.store.waypoints(it) } ?: emptyList()) + tracking.store.waypoints(null))
            }
        }.getOrDefault(emptyList<List<Pair<Double, Double>>>() to emptyList())
        return Overlay(route, track, waypoints)
    }

    /** Draws [o] in the frame, as the phone's overlays draw it (white casing, then the colour); returns the counts. */
    private fun drawOverlay(c: Canvas, layout: PageLayout, o: Overlay): Map<String, Any?> {
        val fx = layout.frameXPx.toDouble()
        val fy = layout.frameYPx.toDouble()
        val fw = layout.frameWidthPx.toDouble()
        val fh = layout.frameHeightPx.toDouble()
        val k = layout.styleScale.toFloat() // page pixels per style pixel
        c.save()
        c.clipRect(fx.toFloat(), fy.toFloat(), (fx + fw).toFloat(), (fy + fh).toFloat())
        val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = 0xE6FFFFFF.toInt(); strokeWidth = 7f * k
        }
        val ink = Paint(casing).apply { color = TRACK; strokeWidth = 4f * k }
        fun path(points: List<Pair<Double, Double>>): Path = Path().apply {
            points.forEachIndexed { i, (lat, lon) ->
                val x = (fx + layout.frameX(lon)).toFloat()
                val y = (fy + layout.frameY(lat)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        var trackPoints = 0
        for (s in o.track) if (s.size >= 2) {
            val p = path(s)
            c.drawPath(p, casing)
            c.drawPath(p, ink)
            trackPoints += s.size
        }
        if (o.route.size >= 2) {
            val p = path(o.route)
            c.drawPath(p, casing)
            c.drawPath(p, Paint(ink).apply { color = ROUTE; pathEffect = DashPathEffect(floatArrayOf(10f * k, 6f * k), 0f) })
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        fun marker(lat: Double, lon: Double, fill: Int, ring: Int, r: Float) {
            val x = (fx + layout.frameX(lon)).toFloat()
            val y = (fy + layout.frameY(lat)).toFloat()
            dot.style = Paint.Style.FILL; dot.color = ring; c.drawCircle(x, y, (r + 2f) * k, dot)
            dot.color = fill; c.drawCircle(x, y, r * k, dot)
        }
        o.route.firstOrNull()?.let { (lat, lon) -> marker(lat, lon, START, 0xFFFFFFFF.toInt(), 6f) }
        o.route.lastOrNull()?.takeIf { o.route.size >= 2 }?.let { (lat, lon) -> marker(lat, lon, END, 0xFFFFFFFF.toInt(), 6f) }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 11f * k
        }
        var inside = 0
        var outside = 0
        for (w in o.waypoints) {
            val x = layout.frameX(w.lon)
            val y = layout.frameY(w.lat)
            if (x < 0 || y < 0 || x >= fw || y >= fh) { outside++; continue }
            marker(w.lat, w.lon, WAYPOINT, 0xFF2B2420.toInt(), 7f)
            if (w.name.isNotBlank()) {
                val tx = (fx + x).toFloat() + 11f * k
                val ty = (fy + y).toFloat() + 4f * k
                label.style = Paint.Style.STROKE; label.strokeWidth = 3.6f * k; label.color = 0xFFFFFFFF.toInt()
                c.drawText(w.name, tx, ty, label)
                label.style = Paint.Style.FILL; label.color = 0xFF2B2420.toInt()
                c.drawText(w.name, tx, ty, label)
            }
            inside++
        }
        c.restore()
        return linkedMapOf("routePoints" to o.route.size, "trackSegments" to o.track.count { it.size >= 2 }, "trackPoints" to trackPoints, "waypointsDrawn" to inside, "waypointsOutside" to outside)
    }

    /** The overlays' colours (`RouteOverlay`, `TrackOverlay`). */
    private const val ROUTE = 0xFFE66101.toInt()
    private const val TRACK = 0xFFD01C8B.toInt()
    private const val START = 0xFF1A9850.toInt()
    private const val END = 0xFFD73027.toInt()
    private const val WAYPOINT = 0xFFFFD400.toInt()

    private class Shot(val bitmap: Bitmap, val width: Int, val height: Int, val placementErrorPx: Double?, val attributions: List<String>)

    /** No logo or attribution drawn into the map: the sheet's sources line credits every source. */
    private class SheetSnapshotter(context: Context, options: Options) : MapSnapshotter(context, options) {
        override fun addOverlay(snapshot: MapSnapshot) = Unit
    }

    /**
     * The frame as MapLibre draws it: a [logicalW] x [logicalH] snapshot at [ratio] centred on the frame's centre at
     * [zoom], bearing 0. Checks the placement: the snapshot's pixel of the frame's two far corners against where the
     * layout puts them (`placementErrorPx`, the larger miss in page pixels).
     */
    private fun snapshot(app: DystopoApp, styleJson: String, logicalW: Int, logicalH: Int, ratio: Double, lat: Double, lon: Double, zoom: Double, layout: PageLayout): Shot {
        val result = AtomicReference<Shot?>()
        val failure = AtomicReference<String?>()
        val holder = AtomicReference<MapSnapshotter?>()
        val latch = CountDownLatch(1)
        app.map.onMain {
            val options = MapSnapshotter.Options(logicalW, logicalH)
                .withPixelRatio(ratio.toFloat())
                .withStyleJson(styleJson)
                .withCameraPosition(CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).bearing(0.0).tilt(0.0).build())
                .withLogo(false)
            val s = SheetSnapshotter(app, options)
            holder.set(s)
            s.start(
                object : MapSnapshotter.SnapshotReadyCallback {
                    override fun onSnapshotReady(snap: MapSnapshot) {
                        try {
                            val bmp = snap.bitmap
                            val cropX = (bmp.width - layout.frameWidthPx) / 2.0
                            val cropY = (bmp.height - layout.frameHeightPx) / 2.0
                            fun miss(fxPx: Double, fyPx: Double): Double {
                                val p: PointF = snap.pixelForLatLng(LatLng(layout.latAt(fyPx), layout.lonAt(fxPx)))
                                val ex = (fxPx + cropX) / ratio
                                val ey = (fyPx + cropY) / ratio
                                return maxOf(kotlin.math.abs(p.x - ex), kotlin.math.abs(p.y - ey)) * ratio
                            }
                            val err = runCatching { maxOf(miss(0.0, 0.0), miss(layout.frameWidthPx.toDouble(), layout.frameHeightPx.toDouble())) }.getOrNull()
                            result.set(Shot(bmp, bmp.width, bmp.height, err?.let { (it * 100).roundToLong() / 100.0 }, snap.attributions.toList()))
                        } catch (e: Exception) {
                            failure.set(e.toString())
                        }
                        latch.countDown()
                    }
                },
                object : MapSnapshotter.ErrorHandler {
                    override fun onError(error: String) {
                        failure.set(error)
                        latch.countDown()
                    }
                },
            )
        }
        if (!latch.await(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            runCatching { app.map.onMain { holder.get()?.cancel() } }
            throw IllegalStateException("the map snapshot took over ${SNAPSHOT_TIMEOUT_MS / 1000} s")
        }
        runCatching { app.map.onMain { holder.get()?.cancel() } } // releases the snapshotter's renderer
        failure.get()?.let { throw IllegalStateException("map snapshot: $it") }
        return result.get() ?: throw IllegalStateException("map snapshot returned nothing")
    }
}
