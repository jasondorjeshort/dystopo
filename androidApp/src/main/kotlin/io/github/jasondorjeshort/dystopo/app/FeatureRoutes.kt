package io.github.jasondorjeshort.dystopo.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

/**
 * Debug routes (debug builds) that say what the map drew, for `tools/android-check.py`:
 *
 * - `GET /features`: how many features each style layer rendered in the current viewport, from MapLibre's
 *   `queryRenderedFeatures`; the device counterpart of the per-layer counts `tools/demo-check.py` reads from
 *   MapLibre GL JS. Symbol layers count placed symbols.
 * - `GET /features/text`: whether placed labels actually reached the pixels. Takes a map snapshot, sets
 *   `text-opacity` 0 on every symbol layer, takes a second snapshot, restores the layers, takes a third, and
 *   counts the pixels that differ only in the second (`unstablePixels`: first and third differ, e.g. late tiles). Labels that are placed (non-zero counts above) but change no pixels were not drawn.
 */
class FeatureRoutes(private val app: DystopoApp) {
    fun handle(r: Request): Response =
        if (r.path == "/features/text") Response.json(textPixels(r.query["wait"]?.toLongOrNull() ?: 15_000L)) else Response.json(features())

    fun features(): Map<String, Any?> = app.map.onMain(10_000) {
        val view = app.map.mapView
        val map = app.map.map
        val style = map?.style
        if (view == null || map == null || style == null || !style.isFullyLoaded) return@onMain mapOf("ready" to false)
        val box = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        val started = System.nanoTime()
        val layers = LinkedHashMap<String, Int>()
        for (layer in style.layers) layers[layer.id] = map.queryRenderedFeatures(box, layer.id).size
        mapOf(
            "ready" to true, "width" to view.width, "height" to view.height, "layers" to layers,
            "queryMs" to round1((System.nanoTime() - started) / 1e6),
        )
    }

    fun textPixels(waitMs: Long = 15_000): Map<String, Any?> {
        val started = System.nanoTime()
        if (!app.map.awaitIdle(waitMs)) return mapOf("ready" to false, "error" to "map not idle")
        val with = snapshot()
        val saved = app.map.onMain {
            val style = app.map.map?.style ?: throw IllegalStateException("no style")
            style.layers.filterIsInstance<SymbolLayer>().map { layer ->
                val old = layer.textOpacity
                layer.setProperties(PropertyFactory.textOpacity(0f))
                layer to old
            }
        }
        val without = try {
            settle(waitMs)
            snapshot()
        } finally {
            app.map.onMain {
                for ((layer, old) in saved) layer.setProperties(if (old.value == null) PropertyFactory.textOpacity(1f) else old)
            }
        }
        settle(waitMs)
        val restored = snapshot()
        // Text pixels: equal before and after (stable), different while the text was hidden. Pixels that
        // differ between the first and last snapshot changed for another reason (late tiles) and don't count.
        val (text, unstable) = textDiff(with, without, restored)
        with.recycle()
        without.recycle()
        restored.recycle()
        return mapOf(
            "ready" to true, "symbolLayers" to saved.size, "changedPixels" to text, "unstablePixels" to unstable,
            "ms" to round1((System.nanoTime() - started) / 1e6),
        )
    }

    /**
     * After a paint change: force a repaint, wait until a new frame has actually rendered (the idle flag can
     * still be true from before the change), let the paint transition finish, then wait for idle.
     */
    private fun settle(waitMs: Long) {
        val before = app.map.framesRendered
        app.map.onMain { app.map.map?.triggerRepaint() }
        val deadline = System.currentTimeMillis() + 3_000
        while (app.map.framesRendered <= before && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(400) // paint property transitions default to 300 ms
        app.map.awaitIdle(waitMs)
    }

    private fun snapshot(timeoutMs: Long = 10_000): Bitmap {
        val result = AtomicReference<Bitmap?>()
        val latch = CountDownLatch(1)
        app.map.onMain {
            val m = app.map.map ?: throw IllegalStateException("map not ready")
            m.snapshot { bmp ->
                result.set(bmp)
                latch.countDown()
            }
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "snapshot timed out" }
        return result.get() ?: throw IllegalStateException("snapshot returned no bitmap")
    }

    /** A largest channel difference over 32 counts as a change (anti-aliasing noise stays below that). */
    private fun differs(p: Int, q: Int): Boolean =
        p != q && max(abs(Color.red(p) - Color.red(q)), max(abs(Color.green(p) - Color.green(q)), abs(Color.blue(p) - Color.blue(q)))) > 32

    /** (text pixels, unstable pixels) from snapshots with text, without text, and with text restored. */
    private fun textDiff(a: Bitmap, b: Bitmap, c: Bitmap): Pair<Int, Int> {
        if (a.width != b.width || a.height != b.height || a.width != c.width || a.height != c.height) return -1 to -1
        val w = a.width
        val ra = IntArray(w)
        val rb = IntArray(w)
        val rc = IntArray(w)
        var text = 0
        var unstable = 0
        for (y in 0 until a.height) {
            a.getPixels(ra, 0, w, 0, y, w, 1)
            b.getPixels(rb, 0, w, 0, y, w, 1)
            c.getPixels(rc, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (differs(ra[x], rc[x])) unstable++
                else if (differs(ra[x], rb[x]) && differs(rc[x], rb[x])) text++
            }
        }
        return text to unstable
    }
}
