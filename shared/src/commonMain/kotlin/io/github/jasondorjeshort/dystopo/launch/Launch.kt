package io.github.jasondorjeshort.dystopo.launch

import io.github.jasondorjeshort.dystopo.launch.Gate.BEFORE_ACTIVITY
import io.github.jasondorjeshort.dystopo.launch.Gate.BEFORE_STYLE
import io.github.jasondorjeshort.dystopo.launch.Gate.FIRST_FRAME
import io.github.jasondorjeshort.dystopo.launch.Gate.LATER
import io.github.jasondorjeshort.dystopo.launch.Runs.WORKER
import io.github.jasondorjeshort.dystopo.launch.Waits.SCREEN
import io.github.jasondorjeshort.dystopo.launch.Work.BINDER
import io.github.jasondorjeshort.dystopo.launch.Work.DISK
import io.github.jasondorjeshort.dystopo.launch.Work.NATIVE
import io.github.jasondorjeshort.dystopo.launch.Work.SOCKET

/**
 * ADR 0061 step 1: the Android app's launch, every stage declared where it runs now (no move). The order of the list is
 * the order the main thread runs its stages in; a WORKER stage starts as soon as its needs are done. Every T228 mark
 * belongs to exactly one stage or is one of the trace's own [boundaries]. `LaunchCheck.problems(stages)` is empty
 * (`LaunchTest`); the app's runner binds a body to every stage it runs (`LaunchBindingsTest`).
 */
object Launch {
    // ---------- the values (one slot each in the app) ----------
    val TRACKER = Value<Any>("tracker", LATER, OnTimeout.NEXT_READER_BUILDS)
    val WORLD = Value<Any>("world", LATER)
    val ONLINE = Value<Any>("online", BEFORE_STYLE)
    val TILE_INDEX = Value<Any>("tileIndex", LATER)
    val TILES = Value<Any>("tiles", BEFORE_STYLE, OnTimeout.RELOAD_REPAIRS)
    /** Read by the first composition (the first-run card), so ready before the first frame while the card reads it (F2 lifts that). */
    val DOWNLOADS = Value<Any>("downloads", FIRST_FRAME)

    val values: List<Value<*>> = listOf(TRACKER, WORLD, ONLINE, TILE_INDEX, TILES, DOWNLOADS)

    private const val STEP_2 = "not moved yet: ADR 0061 step 2 moves it off the main thread"

    // ---------- DystopoApp.onCreate ----------
    val PREPARE = Stage(
        "prepare", listOf("the Compose state's prepare", "Tracking.prepare"), work = setOf(DISK), gate = BEFORE_ACTIVITY,
        allowed = "the scale bar's option reads its preference file while its state is made (StrictMode, T266)",
    )
    val MAPLIBRE = Stage(
        "maplibre", listOf("MapLibre.getInstance"), work = setOf(NATIVE, DISK), gate = BEFORE_ACTIVITY,
        allowed = "MapLibre's start is @UiThread and reads its own files (StrictMode, T266)",
    )
    val SETTINGS = Stage(
        "settings", listOf("AppSettings.load"), work = setOf(DISK), gate = BEFORE_ACTIVITY,
        allowed = "SharedPreferences load on the system's executor and every getter waits for it, so a move moves only the wait; last if the trace shows it",
    )
    val TRACKING = Stage(
        "tracking", listOf("Tracking.start"), needs = listOf("settings"), runs = WORKER, work = setOf(DISK, BINDER), gate = LATER,
        publishes = listOf(TRACKER),
    )
    /** T264: the world overview's copy out of the APK, off the main thread; T387: at the first screen (it reloads the tiles once copied). */
    val WORLD_COPY = Stage("world", runs = WORKER, work = setOf(DISK, BINDER), gate = LATER, publishes = listOf(WORLD), waits = SCREEN)
    val ASSETS = Stage("assets", listOf("BundledAssets.install"), work = setOf(DISK, BINDER), gate = BEFORE_STYLE, allowed = STEP_2)
    val PACKS = Stage(
        "packs", listOf("PackSet.open", "collectContourGarbage's identities", "ContourCache.collectGarbage"),
        needs = listOf("settings"), work = setOf(DISK), gate = BEFORE_STYLE, allowed = STEP_2,
    )
    val ONLINE_SERVICE = Stage(
        "online",
        listOf("OnlineService.get", "OnlineService: TileCache", "OnlineService: after TileCache", "OnlineService: RegionDownload and Areas.attach", "OnlineService: the rest"),
        needs = listOf("settings"), work = setOf(DISK), gate = BEFORE_STYLE, publishes = listOf(ONLINE), allowed = STEP_2,
    )
    /** T242, T267: the online cache's move and index walk, on the cache's own index thread; observed through `TileCache.whenReady`. */
    val CACHE_INDEX = Stage(
        "cacheIndex", needs = listOf("online"), runs = WORKER, work = setOf(DISK), gate = LATER, publishes = listOf(TILE_INDEX), bound = Bound.OBSERVED,
    )
    val TILES_SERVICE = Stage(
        "tiles", listOf("TileService", "OnlineService.tilesFor", "TileService's rest"),
        needs = listOf("packs", "online", "assets"), gate = BEFORE_STYLE, publishes = listOf(TILES),
    )
    val WATCHERS = Stage(
        "watchers", listOf("style-prime, watchdog and reports"),
        needs = listOf("tiles", "online"), work = setOf(DISK), gate = LATER, allowed = "starts threads; the crash handler's install reads its files",
    )
    val DOWNLOADS_STAGE = Stage(
        "downloads", listOf("PackManager.get"), needs = listOf("tiles"), work = setOf(DISK), gate = FIRST_FRAME,
        publishes = listOf(DOWNLOADS), allowed = STEP_2,
    )
    val SERVER = Stage(
        "server", listOf("Routes and the loopback servers"), needs = listOf("tiles", "settings"), work = setOf(SOCKET, DISK), gate = BEFORE_STYLE,
        allowed = STEP_2,
    )
    /** T83's update check and the restore of a verified download (it hashes the file); T387: at the first screen. */
    val UPDATER = Stage("updater", needs = listOf("settings"), runs = WORKER, work = setOf(DISK, SOCKET), gate = LATER, waits = SCREEN)
    /** T112's cache budget: its engine over the caches and its first run (which waits for the indexes); T387: at the first screen. */
    val BUDGET = Stage("budget", needs = listOf("online"), runs = WORKER, work = setOf(DISK), gate = LATER, waits = SCREEN)
    /** T258's memory sampler, a line a minute; T387: once work is allowed (a recording's notification covers it with the screen off). */
    val SAMPLER = Stage("sampler", needs = listOf("settings"), runs = WORKER, work = setOf(DISK), gate = LATER, waits = Waits.WORK)
    /**
     * T387: the pending map work: T29's interrupted pack downloads, T151's resume check of the areas (and its foreground
     * trigger) and T130's trip corridors. At the first screen; an area download's own job (`DownloadWorker`, under its
     * notification) runs the resume check by itself at a start without one.
     */
    val PENDING = Stage("pending", needs = listOf("downloads", "online"), runs = WORKER, work = setOf(DISK, SOCKET), gate = LATER, waits = SCREEN)

    // ---------- MainActivity ----------
    val VIEWPORT = Stage("viewport", listOf("MapViewport.prepare"), gate = BEFORE_ACTIVITY)
    val CAMERA = Stage(
        "camera", listOf("startCamera", "view from *"), needs = listOf("settings", "tiles", "viewport"), work = setOf(DISK), gate = FIRST_FRAME,
        reads = listOf(TILES), allowed = "the last fix and the saved view are preferences, loaded with settings",
    )
    val MAPVIEW = Stage(
        "mapview", listOf("MapView", "mapView.onCreate", "map.attach", "map.attach: *"), needs = listOf("maplibre", "camera"), work = setOf(NATIVE, DISK),
        gate = FIRST_FRAME, allowed = "MapLibre's view reads its resources (StrictMode, T266; the trail-disagreement store's read left the attach in T347)",
    )
    val ACTIVITY = Stage(
        "activity", listOf("links and intents", "setContent", "permissions"), needs = listOf("mapview"), work = setOf(BINDER), gate = FIRST_FRAME,
        allowed = "the permission check is a binder call to the package manager",
    )
    val START = Stage(
        "start", listOf("mapView.onStart", "MapOrientation.setForeground", "startDeviceLocation"), needs = listOf("activity"),
        work = setOf(BINDER, NATIVE), gate = FIRST_FRAME, allowed = "the location manager and the sensors are binder calls",
    )
    val RESUME = Stage("resume", listOf("mapView.onResume"), needs = listOf("start"), work = setOf(NATIVE), gate = FIRST_FRAME)
    /** Compose's first composition; observed from its marks. */
    val COMPOSITION = Stage(
        "composition", listOf("first composition begins", "compose *", "composition applied: layout and draw"), needs = listOf("resume", "downloads"),
        gate = FIRST_FRAME, reads = listOf(TILES, ONLINE, DOWNLOADS), bound = Bound.OBSERVED,
    )
    /** The map's style callback (`MapController`); observed from its marks. */
    val STYLE = Stage(
        "style", listOf("map ready: *"), needs = listOf("mapview", "tiles"), work = setOf(NATIVE, DISK), gate = LATER, reads = listOf(TILES),
        bound = Bound.OBSERVED, allowed = "the style's JSON is made on the main thread, which looks for the glyph and sprite files; $STEP_2 (BEFORE_STYLE)",
    )

    val stages: List<Stage> = listOf(
        PREPARE, MAPLIBRE, SETTINGS, TRACKING, WORLD_COPY, ASSETS, PACKS, ONLINE_SERVICE, CACHE_INDEX, TILES_SERVICE, WATCHERS, DOWNLOADS_STAGE,
        SERVER, UPDATER, BUDGET, SAMPLER, PENDING, VIEWPORT, CAMERA, MAPVIEW, ACTIVITY, START, RESUME, COMPOSITION, STYLE,
    )

    /** The trace's own marks at the lifecycle's edges (`StartupTrace`): no stage's work, so no stage owns them. */
    val boundaries: List<String> = listOf(
        "attachBaseContext and content providers", "DystopoApp.onCreate", "DystopoApp.onCreate done", "MainActivity.onCreate",
        "onStart", "onResume", "first traversal: attach and Compose's setup", "first frame", "style loaded", "map fully loaded",
        "waiting for *",
    )

    /** The stage owning [mark], or null for a boundary or an unknown mark. */
    fun stageOf(mark: String): Stage? = stages.firstOrNull { s -> s.marks.any { markMatches(it, mark) } }

    fun stage(name: String): Stage? = stages.firstOrNull { it.name == name }
}

/**
 * T387 (ADR 0071, decisions 1 and 2): what a launch stage waits for besides its needs. A process started by a preload, a job
 * or a broadcast runs only what that start needs; the rest waits here. Ordered: a stage waits for at least what each of its
 * needs waits for ([LaunchGate.problems]).
 */
enum class Waits {
    /** Every start runs it. */
    NOTHING,

    /** Once work is allowed: an activity on screen, or a job under an ongoing notification of its own (a recording, an area download). */
    WORK,

    /** Once an activity of the app has started in this process (it stays open after that: Home pauses the work itself, not the stage). */
    SCREEN,
}

/** T387: a stage's state as `GET /debug/launch` reports it ([LaunchGate.state]). */
enum class StageState(val json: String) {
    /** Not reached in this process: an activity's stage before any activity. */
    NOT_REACHED("not reached"),

    /** Bound; a stage it needs has not ended. */
    WAITING("waiting"),

    /** Its needs are done and it waits for its [Waits] (a start without a screen). */
    DEFERRED("deferred"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
}

/**
 * T387 (ADR 0071): the launch's gate as a pure rule. [opens] says whether a stage's wait is over; [state] reads a stage's
 * figures as one word; [problems] is the declaration's rule, read by `LaunchTest` beside [LaunchCheck]:
 * - a stage that waits runs on a worker, is bound by the runner and has the gate [Gate.LATER] (the main thread never waits
 *   for a screen, and nothing earlier reads what it publishes);
 * - a stage waits for at least what each of its needs waits for, so no stage at a start without a screen waits for one
 *   that is deferred.
 */
object LaunchGate {
    /** Whether [waits] is over: [screenSeen], an activity has started in this process; [workAllowed], on screen or a notified job now. */
    fun opens(waits: Waits, screenSeen: Boolean, workAllowed: Boolean): Boolean = when (waits) {
        Waits.NOTHING -> true
        Waits.WORK -> workAllowed || screenSeen
        Waits.SCREEN -> screenSeen
    }

    /**
     * One stage's state from its figures: [bound] (the runner or its owner reached it), [needsDone], [started], [ended],
     * [failed]; [waits] with [screenSeen] and [workAllowed] for a stage whose needs are done and that has not started.
     */
    fun state(
        bound: Boolean, needsDone: Boolean, started: Boolean, ended: Boolean, failed: Boolean,
        waits: Waits, screenSeen: Boolean, workAllowed: Boolean,
    ): StageState = when {
        failed -> StageState.FAILED
        ended -> StageState.DONE
        started -> StageState.RUNNING
        !bound -> StageState.NOT_REACHED
        !needsDone -> StageState.WAITING
        !opens(waits, screenSeen, workAllowed) -> StageState.DEFERRED
        else -> StageState.RUNNING // its wait is over and its thread is starting it
    }

    /** The stages a start without a screen and without a notified job leaves deferred. */
    fun deferredWithoutScreen(stages: List<Stage>): List<Stage> = stages.filter { !opens(it.waits, screenSeen = false, workAllowed = false) }

    fun problems(stages: List<Stage>): List<String> {
        val out = ArrayList<String>()
        val byName = stages.associateBy { it.name }
        for (s in stages) {
            if (s.waits != Waits.NOTHING) {
                if (s.runs != Runs.WORKER) out += "${s.name}: waits for ${s.waits} on the main thread"
                if (s.bound != Bound.RUNNER) out += "${s.name}: waits for ${s.waits} but the runner does not run it"
                if (s.gate != Gate.LATER) out += "${s.name}: waits for ${s.waits} with gate ${s.gate}, not LATER"
            }
            for (n in s.needs) {
                val need = byName[n] ?: continue // LaunchCheck names an undeclared need
                if (need.waits > s.waits) out += "${s.name}: waits for ${s.waits} but needs $n, which waits for ${need.waits}"
            }
        }
        return out
    }
}
