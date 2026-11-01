package io.github.jasondorjeshort.dystopo.app.print

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.about.AppCredits
import io.github.jasondorjeshort.dystopo.app.routing.RoutePlanner
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.render.PageLayout
import io.github.jasondorjeshort.dystopo.render.Paper
import io.github.jasondorjeshort.dystopo.render.SheetCanvas
import io.github.jasondorjeshort.dystopo.render.SheetMargin
import io.github.jasondorjeshort.dystopo.render.TripReport
import io.github.jasondorjeshort.dystopo.render.TripReportPage
import io.github.jasondorjeshort.dystopo.routing.PaceFits
import io.github.jasondorjeshort.dystopo.routing.TrackMatch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * T183: the Settings row's choices (T167's survey): 1:10,000, 1:25,000 (the default, as printed topographic maps),
 * 1:50,000 and "Fit the view" (null: the scale that fits the map's view, printed as the exact ratio).
 */
val PRINT_SCALES: List<Pair<String, Double?>> = PageLayout.SCALES.map { PageLayout.ratio(it.toDouble()) to it.toDouble() } + ("Fit the view" to null)

/**
 * T183: hands a phone sheet to the system print dialog (Settings › Map › "Print a map sheet", `POST /print/ui`). The
 * dialog's paper and orientation choose the sheet's: A4, Letter and A3 are laid out for that paper (portrait or
 * landscape as picked); any other paper gets the regional default (Letter in the US, A4 elsewhere) and the print
 * service places it. The dialog opens on the regional paper, portrait (T167's survey). Its "Save as PDF" printer
 * keeps the PDF. T198: the trip report goes through the same dialog ([openReport]; the Tracks list's "Report").
 */
object SheetPrint {
    private const val TAG = "DystopoPrint"
    const val JOB_NAME = "Dystopo map sheet"
    const val REPORT_JOB_NAME = "Dystopo trip report"

    /** What the last print dialog asked for and how it went (`GET /print` › `ui`). */
    @Volatile var lastUi: Map<String, Any?>? = null
        private set

    /** Opens the print dialog for [spec] on [activity]; main thread. */
    fun open(activity: Activity, app: DystopoApp, spec: SheetSpec) {
        val paper = spec.paper ?: Paper.defaultAt(spec.centerLat, spec.centerLon)
        lastUi = linkedMapOf("opened" to System.currentTimeMillis(), "kind" to "sheet", "scale" to spec.scale, "paper" to paper.key, "landscape" to spec.landscape, "state" to "dialog")
        dialog(activity, JOB_NAME, "dystopo-sheet.pdf", paper, spec.landscape) { p, landscape ->
            PhoneSheet.make(app, spec.copy(paper = p ?: spec.paper, landscape = landscape)).pdf
        }
    }

    /**
     * T198: the print dialog for the trip report of track [spec]'s id on [activity], on the paper of the track's region
     * ([centerLat], [centerLon]: Letter in the US, A4 elsewhere) unless [spec] names one; main thread.
     */
    fun openReport(activity: Activity, app: DystopoApp, spec: TripReportPrint.Spec, centerLat: Double, centerLon: Double) {
        val paper = spec.paper ?: Paper.defaultAt(centerLat, centerLon)
        lastUi = linkedMapOf("opened" to System.currentTimeMillis(), "kind" to "report", "track" to spec.trackId, "paper" to paper.key, "landscape" to spec.landscape, "state" to "dialog")
        dialog(activity, REPORT_JOB_NAME, "dystopo-trip-report.pdf", paper, spec.landscape) { p, landscape ->
            TripReportPrint.make(app, spec.copy(paper = p ?: paper, landscape = landscape)).pdf
        }
    }

    private fun dialog(activity: Activity, job: String, docName: String, paper: Paper, landscape: Boolean, make: (Paper?, Boolean) -> ByteArray) {
        val media = mediaOf(paper).let { if (landscape) it.asLandscape() else it.asPortrait() }
        val attributes = PrintAttributes.Builder().setMediaSize(media).setColorMode(PrintAttributes.COLOR_MODE_COLOR).build()
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(job, Adapter(docName, paper, landscape, make), attributes)
    }

    /**
     * The Settings row's action: the map's current view at [scale] (null: fit the view) in the print dialog, the
     * other options as T167's survey set them. Main thread. Returns null, or why the dialog did not open.
     */
    fun printCurrentView(app: DystopoApp, scale: Double?): String? {
        val activity = app.map.activity ?: return "no activity"
        val spec = runCatching { PhoneSheet.currentView(app, scale) }.getOrElse { return it.message ?: it.toString() }
        return runCatching { open(activity, app, spec) }.exceptionOrNull()?.let { it.message ?: it.toString() }
    }

    /**
     * T198: the Tracks list's "Report": reads track [trackId]'s place off the main thread, then opens the print dialog on
     * the main thread. [done] gets null, or why the dialog did not open (on the main thread).
     */
    fun printReport(app: DystopoApp, trackId: String, done: (String?) -> Unit = {}) {
        val main = Handler(Looper.getMainLooper())
        thread(name = "dystopo-report", isDaemon = true) {
            val center = runCatching { TripReportPrint.centerOf(app, trackId) }.getOrNull()
            main.post {
                val activity = app.map.activity
                done(
                    when {
                        activity == null -> "no activity"
                        center == null -> "no such track, or it has no points"
                        else -> runCatching { openReport(activity, app, TripReportPrint.Spec(trackId), center.first, center.second) }
                            .exceptionOrNull()?.let { it.message ?: it.toString() }
                    },
                )
            }
        }
    }

    /** The print framework's paper for [paper]. */
    fun mediaOf(paper: Paper): PrintAttributes.MediaSize = when (paper) {
        Paper.A4 -> PrintAttributes.MediaSize.ISO_A4
        Paper.LETTER -> PrintAttributes.MediaSize.NA_LETTER
        Paper.A3 -> PrintAttributes.MediaSize.ISO_A3
    }

    /** The sheet's paper for the dialog's [media]: by id, else by size within 2 mm; null for any other paper. */
    fun paperOf(media: PrintAttributes.MediaSize?): Paper? {
        media ?: return null
        Paper.entries.firstOrNull { mediaOf(it).id == media.id }?.let { return it }
        val w = minOf(media.widthMils, media.heightMils) * 0.0254
        val h = maxOf(media.widthMils, media.heightMils) * 0.0254
        return Paper.entries.firstOrNull { abs(it.widthMm - w) <= 2 && abs(it.heightMm - h) <= 2 }
    }

    /** One page laid out for the dialog's paper and orientation by [make] (any other paper: [basePaper], placed by the service). */
    private class Adapter(
        private val docName: String,
        private val basePaper: Paper,
        baseLandscape: Boolean,
        private val make: (Paper?, Boolean) -> ByteArray,
    ) : PrintDocumentAdapter() {
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var choice: Pair<Paper?, Boolean> = basePaper to baseLandscape
        @Volatile private var made: Pair<Pair<Paper?, Boolean>, ByteArray>? = null

        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
            if (cancel.isCanceled) {
                callback.onLayoutCancelled()
                return
            }
            val media = new.mediaSize
            val next = (paperOf(media) ?: basePaper) to (media?.isPortrait == false)
            val changed = next != choice || made == null
            choice = next
            val info = PrintDocumentInfo.Builder(docName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            callback.onLayoutFinished(info, changed)
        }

        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback) {
            val s = choice
            thread(name = "dystopo-print", isDaemon = true) {
                try {
                    val pdf = made?.takeIf { it.first == s }?.second ?: make(s.first, s.second).also { made = s to it }
                    if (cancel.isCanceled) {
                        main.post { callback.onWriteCancelled() }
                        return@thread
                    }
                    FileOutputStream(destination.fileDescriptor).use { it.write(pdf) }
                    lastUi = (lastUi.orEmpty() + mapOf("state" to "written", "paper" to (s.first?.key), "landscape" to s.second, "pdfBytes" to pdf.size))
                    main.post { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                } catch (e: Exception) {
                    Log.w(TAG, "print failed", e)
                    lastUi = (lastUi.orEmpty() + mapOf("state" to "failed", "error" to e.toString()))
                    main.post { callback.onWriteFailed(e.message ?: e.toString()) }
                }
            }
        }

        override fun onFinish() {
            lastUi = (lastUi.orEmpty() + mapOf("finished" to System.currentTimeMillis()))
        }
    }
}

/**
 * T198: the trip report on the phone: `shared`'s [TripReport] works out the figures of a stored (or the recording)
 * track and plans the page, the map is a MapLibre snapshot of the frame as the sheet's ([PhoneSheet]: the day style, a
 * pixel ratio of dpi / [PageLayout.STYLE_DPI], one snapshot pixel one page pixel), and [TripReportPage] draws the track,
 * the profile strip and the margin on [AndroidSheetCanvas] with round track lines. The time against the pace uses the
 * fit the router uses ([PaceFits.active]; the standard estimate for a hike without one) with the pieces placed on the
 * trail network when one is ready near the track within [ROUTER_WAIT_MS]. Generated on demand, never stored. Off the
 * main thread; the snapshot itself runs on the main thread.
 */
object TripReportPrint {
    const val ROUTER_WAIT_MS = 3000L

    /** What a report shows: the track, the paper (null: by the track's region), the orientation, the page's dpi, the print date. */
    data class Spec(
        val trackId: String,
        val paper: Paper? = null,
        val landscape: Boolean = false,
        val dpi: Double = PhoneSheet.DPI,
        val epochMs: Long = System.currentTimeMillis(),
    )

    /** The report of the last one made (`GET /print` › `report.last`), the count since start and the last failure. */
    @Volatile var last: Map<String, Any?>? = null
        private set
    @Volatile var made = 0
        private set
    @Volatile var lastError: String? = null
        private set

    /** Track [id] as stored, or the recording's points while it records; null when there is no such track. */
    fun track(app: DystopoApp, id: String): Track? {
        val t = Tracking.of(app)
        return t.call {
            val stored = t.store.track(id)
            if (id == t.recorder.activeId) Track(id, stored?.name ?: "", t.recorder.livePoints().map { TrackSegment(it) }, activity = stored?.activity)
            else stored
        }
    }

    /** The middle of track [id]'s points (the dialog's regional paper), or null. */
    fun centerOf(app: DystopoApp, id: String): Pair<Double, Double>? {
        val pts = track(app, id)?.points ?: return null
        if (pts.isEmpty()) return null
        return (pts.minOf { it.lat } + pts.maxOf { it.lat }) / 2 to (pts.minOf { it.lon } + pts.maxOf { it.lon }) / 2
    }

    fun make(app: DystopoApp, spec: Spec): MadeSheet {
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

    private fun makeOrThrow(app: DystopoApp, spec: Spec): MadeSheet {
        check(Looper.myLooper() != Looper.getMainLooper()) { "TripReportPrint.make runs off the main thread" }
        val t0 = SystemClock.elapsedRealtime()
        val timings = LinkedHashMap<String, Long>()
        fun lap(name: String, since: Long): Long = SystemClock.elapsedRealtime().also { timings[name] = it - since }

        val track = track(app, spec.trackId) ?: throw IllegalArgumentException("no track ${spec.trackId}")
        require(track.points.size >= 2) { "the track has fewer than two points" }
        val units = AppSettings.units
        val first = track.points.first()
        val router = runCatching { RoutePlanner.router(ROUTER_WAIT_MS, listOf(first.lat to first.lon)) }.getOrNull()
        var t = lap("track", t0)
        val summary = TripReport.summarize(track, units, PaceFits.active, router?.let { TrackMatch.snapper(it) })
        val layout = TripReport.plan(summary, spec.paper, spec.landscape, spec.dpi)
        t = lap("summary", t)

        val ratio = layout.dpi / PageLayout.STYLE_DPI
        val zoom = layout.mapLibreZoom(ratio)
        val fw = layout.frameWidthPx
        val fh = layout.frameHeightPx
        val (cLat, cLon) = layout.frameCenter
        val styleJson = app.tiles.style(AppSettings.mapOptions.copy(night = false))
        val shot = snapshot(app, styleJson, ceil(fw / ratio).toInt(), ceil(fh / ratio).toInt(), ratio, cLat, cLon, zoom)
        t = lap("snapshot", t)

        val contour = PhoneSheet.contourInterval(styleJson, zoom, units)
        val b = layout.bbox
        val attribution = PhoneSheet.sourcesLine(AppCredits.compose(app, PackBounds(b.west, b.south, b.east, b.north)))
        val zone = ZoneId.systemDefault()
        val printed = Instant.ofEpochMilli(spec.epochMs).atZone(zone).toLocalDate().toString()
        val walked = summary.stats.startTime?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }
        val title = track.name.ifBlank { "Trip report" }
        val page = TripReportPage(layout, summary, title, walked, printed, attribution, SheetMargin.contourLine(contour, units.symbol))

        val doc = PdfDocument()
        val wPt = (layout.pageWidthMm / PageLayout.MM_PER_INCH * 72).roundToInt()
        val hPt = (layout.pageHeightMm / PageLayout.MM_PER_INCH * 72).roundToInt()
        val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, 1).create())
        val c = pdfPage.canvas
        val k = (72 / layout.dpi).toFloat()
        c.scale(k, k) // page pixels from here on
        val fx = layout.frameXPx.toFloat()
        val fy = layout.frameYPx.toFloat()
        val cropX = ((shot.width - fw) / 2).coerceAtLeast(0)
        val cropY = ((shot.height - fh) / 2).coerceAtLeast(0)
        c.drawBitmap(
            shot, Rect(cropX, cropY, (cropX + fw).coerceAtMost(shot.width), (cropY + fh).coerceAtMost(shot.height)),
            RectF(fx, fy, fx + fw, fy + fh), Paint(Paint.FILTER_BITMAP_FLAG),
        )
        val canvas = AndroidSheetCanvas(c, layout)
        page.paint(RoundLines(canvas, c))
        doc.finishPage(pdfPage)
        t = lap("draw", t)
        val out = ByteArrayOutputStream(4 shl 20)
        doc.writeTo(out)
        doc.close()
        val pdf = out.toByteArray()
        val bitmapSize = listOf(shot.width, shot.height)
        shot.recycle()
        lap("writePdf", t)
        timings["total"] = SystemClock.elapsedRealtime() - t0

        val r5 = { v: Double -> (v * 1e5).roundToLong() / 1e5 }
        val report = linkedMapOf<String, Any?>(
            "kind" to "report",
            "track" to spec.trackId,
            "title" to title,
            "walked" to walked,
            "scale" to layout.scale,
            "ratio" to layout.ratioText,
            "paper" to layout.paper.key,
            "landscape" to layout.landscape,
            "dpi" to (layout.dpi * 100).roundToLong() / 100.0,
            "pagePoints" to listOf(wPt, hPt),
            "pageWidthPx" to layout.pageWidthPx,
            "pageHeightPx" to layout.pageHeightPx,
            "frameMm" to listOf(layout.frameMm.x, layout.frameMm.y, layout.frameMm.w, layout.frameMm.h),
            "extent" to listOf(b.west, b.south, b.east, b.north).map(r5),
            "snapshot" to linkedMapOf("zoom" to (zoom * 1000).roundToLong() / 1000.0, "pixelRatio" to (ratio * 1000).roundToLong() / 1000.0, "bitmap" to bitmapSize),
            "contourInterval" to contour,
            "snapped" to (router != null),
        )
        report.putAll(summary.json())
        report["stopLabels"] = page.stopLabels
        report["margin"] = page.texts.toList()
        report["textsDrawn"] = canvas.textsDrawn
        report["printed"] = printed
        report["attribution"] = attribution
        report["pdfBytes"] = pdf.size
        report["pages"] = 1
        report["timingsMs"] = timings
        return MadeSheet(pdf, layout, report)
    }

    /** [AndroidSheetCanvas] with round caps and joins for [SheetCanvas.roundLine] (the track and the profile line). */
    private class RoundLines(base: AndroidSheetCanvas, private val canvas: Canvas) : SheetCanvas by base {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun roundLine(xy: DoubleArray, dx: Double, dy: Double, color: Int, width: Double) {
            if (xy.size < 4) return
            val path = Path()
            path.moveTo((dx + xy[0]).toFloat(), (dy + xy[1]).toFloat())
            for (i in 1 until xy.size / 2) path.lineTo((dx + xy[2 * i]).toFloat(), (dy + xy[2 * i + 1]).toFloat())
            paint.color = color
            paint.strokeWidth = width.toFloat()
            canvas.drawPath(path, paint)
        }
    }

    /** No logo or attribution drawn into the map: the footer's sources line credits every source. */
    private class ReportSnapshotter(context: Context, options: Options) : MapSnapshotter(context, options) {
        override fun addOverlay(snapshot: MapSnapshot) = Unit
    }

    /**
     * The frame as MapLibre draws it: a [logicalW] x [logicalH] snapshot at [ratio] centred on [lat], [lon] at [zoom],
     * bearing 0, as [PhoneSheet]'s (whose snapshot is private to that file, T195's this wave).
     */
    private fun snapshot(app: DystopoApp, styleJson: String, logicalW: Int, logicalH: Int, ratio: Double, lat: Double, lon: Double, zoom: Double): Bitmap {
        val result = AtomicReference<Bitmap?>()
        val failure = AtomicReference<String?>()
        val holder = AtomicReference<MapSnapshotter?>()
        val latch = CountDownLatch(1)
        app.map.onMain {
            val options = MapSnapshotter.Options(logicalW, logicalH)
                .withPixelRatio(ratio.toFloat())
                .withStyleJson(styleJson)
                .withCameraPosition(CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).bearing(0.0).tilt(0.0).build())
                .withLogo(false)
            val s = ReportSnapshotter(app, options)
            holder.set(s)
            s.start(
                object : MapSnapshotter.SnapshotReadyCallback {
                    override fun onSnapshotReady(snap: MapSnapshot) {
                        result.set(snap.bitmap)
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
        if (!latch.await(PhoneSheet.SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            runCatching { app.map.onMain { holder.get()?.cancel() } }
            throw IllegalStateException("the map snapshot took over ${PhoneSheet.SNAPSHOT_TIMEOUT_MS / 1000} s")
        }
        runCatching { app.map.onMain { holder.get()?.cancel() } } // releases the snapshotter's renderer
        failure.get()?.let { throw IllegalStateException("map snapshot: $it") }
        return result.get() ?: throw IllegalStateException("map snapshot returned nothing")
    }
}
