package io.github.jasondorjeshort.dystopo.app.print

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.render.PageLayout
import io.github.jasondorjeshort.dystopo.render.Paper
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

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
 * keeps the PDF.
 */
object SheetPrint {
    private const val TAG = "DystopoPrint"
    const val JOB_NAME = "Dystopo map sheet"

    /** What the last print dialog asked for and how it went (`GET /print` › `ui`). */
    @Volatile var lastUi: Map<String, Any?>? = null
        private set

    /** Opens the print dialog for [spec] on [activity]; main thread. */
    fun open(activity: Activity, app: DystopoApp, spec: SheetSpec) {
        val paper = spec.paper ?: Paper.defaultAt(spec.centerLat, spec.centerLon)
        val media = mediaOf(paper).let { if (spec.landscape) it.asLandscape() else it.asPortrait() }
        val attributes = PrintAttributes.Builder().setMediaSize(media).setColorMode(PrintAttributes.COLOR_MODE_COLOR).build()
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        lastUi = linkedMapOf("opened" to System.currentTimeMillis(), "scale" to spec.scale, "paper" to paper.key, "landscape" to spec.landscape, "state" to "dialog")
        pm.print(JOB_NAME, Adapter(app, spec), attributes)
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

    private class Adapter(private val app: DystopoApp, private val base: SheetSpec) : PrintDocumentAdapter() {
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var spec = base
        @Volatile private var made: Pair<SheetSpec, ByteArray>? = null

        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
            if (cancel.isCanceled) {
                callback.onLayoutCancelled()
                return
            }
            val media = new.mediaSize
            val next = base.copy(paper = paperOf(media) ?: base.paper, landscape = media?.isPortrait == false)
            val changed = next != spec || made == null
            spec = next
            val info = PrintDocumentInfo.Builder("dystopo-sheet.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            callback.onLayoutFinished(info, changed)
        }

        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback) {
            val s = spec
            thread(name = "dystopo-print", isDaemon = true) {
                try {
                    val pdf = made?.takeIf { it.first == s }?.second ?: PhoneSheet.make(app, s).pdf.also { made = s to it }
                    if (cancel.isCanceled) {
                        main.post { callback.onWriteCancelled() }
                        return@thread
                    }
                    FileOutputStream(destination.fileDescriptor).use { it.write(pdf) }
                    lastUi = (lastUi.orEmpty() + mapOf("state" to "written", "paper" to (s.paper?.key), "landscape" to s.landscape, "pdfBytes" to pdf.size))
                    main.post { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                } catch (e: Exception) {
                    Log.w(TAG, "print sheet failed", e)
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
