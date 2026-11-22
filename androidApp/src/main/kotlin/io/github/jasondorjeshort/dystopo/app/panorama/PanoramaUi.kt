package io.github.jasondorjeshort.dystopo.app.panorama

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.horizon.PanoramaAim
import io.github.jasondorjeshort.dystopo.horizon.PanoramaScene

/**
 * T326: the Panorama's own state (wave 45's choice (i): which sky view is open, the place and a pick are `HorizonUi`'s; the
 * aim is this view's). [aim] is null from an opening until the plot knows its size and the opening's bearing
 * ([opened]; choice (c)); a "View from here" hop and "Back to here" keep it, and it is not kept from one opening to the
 * next. Steering ([steer]) is the plot's drag, pinch and zoom buttons, the accessibility actions and `POST /panorama`.
 */
object PanoramaUi {
    /** Where the lens looks, or null while the aim at opening is pending. */
    var aim by mutableStateOf<PanoramaAim?>(null)

    /** Counts the openings (the plot sets the aim at opening once per count). */
    var openings by mutableStateOf(0)
        private set

    /** True while a finger drags or pinches: the names keep their offsets from their marks (choice (g)). */
    var gesturing by mutableStateOf(false)

    /** When the view last opened (elapsed realtime, ms). */
    @Volatile var openedAtMs = 0L
        private set

    /** True once the user steered since the opening: the compass's first reading no longer turns the view. */
    @Volatile var steered = false
        private set

    /** What the opening's bearing came from: `peak`, `heading`, `north`, or `heading-late` (the compass's first reading within 1.5 s). */
    @Volatile var openingSource: String? = null

    /** From the opening to the first skyline drawn, ms (null until drawn). */
    @Volatile var firstSkylineMs: Long? = null

    /** The plot as last laid out: its size (px) and px per dp; its rectangle and the list's in the Compose root (px). */
    @Volatile var plotSize: Triple<Double, Double, Double>? = null
    @Volatile var plotPx: List<Int>? = null
    @Volatile var listPx: List<Int>? = null
    @Volatile var switchPx: List<Int>? = null

    /** Whether the heading and a picked peak's line scroll with the list (choice (e): under 96 dp left for the list). */
    @Volatile var headingScrolls = false

    /** The scene as last drawn, its build time (ms) last and most since the opening, and the builds counted. */
    @Volatile var scene: PanoramaScene? = null
    @Volatile var sceneMsLast = 0.0
    @Volatile var sceneMsMax = 0.0
    @Volatile var sceneBuilds = 0
    @Volatile var sceneOffMain = false

    /** The sweep steps the zoom last asked for ([io.github.jasondorjeshort.dystopo.horizon.PanoramaSweep.steps]). */
    @Volatile var wanted: List<Double> = emptyList()

    /** What the view last drew (`GET /panorama` `drawn`). */
    @Volatile var drawn: Map<String, Any?>? = null

    /** Makes the Compose state on the main thread (`MainActivity.onCreate`), before a debug route's thread reads it (T226). */
    fun prepare() {
        aim; openings; gesturing
    }

    /** An opening (the Panorama segment from the map or from the Horizon view; main thread): the aim at opening follows. */
    fun opened() {
        aim = null
        openings++
        openedAtMs = SystemClock.elapsedRealtime()
        steered = false
        openingSource = null
        firstSkylineMs = null
        gesturing = false
        resetSceneCost()
    }

    fun resetSceneCost() {
        sceneMsLast = 0.0
        sceneMsMax = 0.0
        sceneBuilds = 0
    }

    /** A scene built in [ms] (the plot's draw path; `GET /panorama` `sceneMs`). */
    fun sceneBuilt(s: PanoramaScene, ms: Double) {
        scene = s
        sceneMsLast = ms
        if (ms > sceneMsMax) sceneMsMax = ms
        sceneBuilds++
    }

    /** Steers the aim by [f] (main thread); a no-op while the aim at opening is pending. [byUser] stops a late compass reading. */
    fun steer(byUser: Boolean = true, f: (PanoramaAim, Triple<Double, Double, Double>) -> PanoramaAim) {
        val a = aim ?: return
        val size = plotSize ?: return
        if (byUser) steered = true
        aim = f(a, size)
    }

    /**
     * T327's seam: turns the view to the direction ([elDeg], [azDeg]) by [PanoramaAim.turnTo] (the shorter way round, the
     * tilt changed only when it would be off the view under the tape). Main thread. Returns the turn, or null before the aim.
     */
    fun turnTo(elDeg: Double, azDeg: Double): PanoramaAim.Turn? {
        val a = aim ?: return null
        val (w, h, dp) = plotSize ?: return null
        val t = a.turnTo(elDeg, azDeg, w, h, PanoramaScene.TAPE_DP * dp)
        steered = true
        aim = t.aim
        return t
    }

    /** The compass's first reading came within the opening's wait and nobody steered: the view turns to it once. */
    internal fun lateHeading(deg: Double) {
        val a = aim ?: return
        if (steered) return
        aim = a.copy(bearingDeg = PanoramaAim.wrapDeg(deg))
        openingSource = "heading-late"
    }

    /** One turn of the accessibility actions and the debug route: half the field of view. */
    fun turnStepDeg(a: PanoramaAim): Double = a.fovDeg / 2

    /** One look up or down of the accessibility actions: 10 degrees. */
    const val TILT_STEP_DEG = 10.0

    /** The compass's first reading is waited for this long at an opening at the fix (choice (c)). */
    const val HEADING_WAIT_MS = 1500L
}
