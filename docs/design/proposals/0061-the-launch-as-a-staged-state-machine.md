# 0061. The launch as a staged state machine in `shared`

Date: 2026-11-09
Status: promoted to ADR 0061

Paths without a root are under `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/`. Every `file:line`
is read at commit e184b663. The launch inventory of wave 29 (`out/wave29-research/launch-inventory.md` [no-check:
gitignored]) was not on the machine this was written on; its findings are taken from T229's and T242's merge bodies,
the wave 30 record and the code, and the critic checked the stage table against the code instead
(`0061-critic-review.md`). Revised on the critic's 14 objections; the body is corrected in place, the options kept.

## Question

The launch's order is implicit: `DystopoApp.onCreate` (`DystopoApp.kt:43-91`), `MainActivity.onCreate`, `onStart`
and `onResume` (`MainActivity.kt:54-168`) and the first composition (`MapScreen`, `MainActivity.kt:213-260`) run
their steps in the order written, all on the main thread except the threads some of them start. Two freezes at
launch on the owner's phone (Android 12, a 576 MB large heap) came from that: the first composition raced a thread
for the tracker (T229) and the online tile cache walked tens of GB on the main thread (T242). Each fix moved one step
by hand and invented its own readiness rule (`TrackerReady`, `Tracking.kt:989-1038`; `TileCache.state`, `whenReady`,
`awaitReady`, `online/TileCache.kt:66-69, 258, 273`). The owner, 2026-11-07: "documenting the correct launch order
lets us do everything in different threads with the correct synchronization checks." What is decided: the shape of a
`Launch` (named stages, what each needs, the gate it satisfies, the thread it declares) and where its parts live, how
a stage publishes a value the main thread reads, T229's rule generalised to every gate, how the trace and the coming
launch budget read the same names, what a JVM test proves, and the order of the moves. In ten years: the launch is a
list anyone can read in one file, and a move is a one-line change of a stage's thread or gate checked by a test.

## Constraints that matter here

- Platforms, licensing, offline first, agent-drivable, the design goals (ADR 0025): as in the template. commonMain
  is pure Kotlin, no `java.*` (`.claude/rules/kotlin.md`); `shared` has no `kotlinx.coroutines` today (its only
  commonMain dependency is the SQLDelight runtime, `shared/build.gradle.kts`) and no iOS target (the build file says
  "iOS comes later"; `docs/PLANNING.md` puts it after a sync server and a desktop client). commonMain has no
  `synchronized`; `kotlin.concurrent.atomics` is experimental with an error-level opt-in in Kotlin 2.2; the pattern in
  use for a lock is a lambda the platform supplies (`DriveOverlay.kt:50-60`). `kotlinx.coroutines` is already on the
  Android app's classpath and in `MainActivity.kt:23-24`.
- The UI thread never takes a lock and never does disk or network I/O; it reads snapshots workers publish
  (`docs/reference/android-app.md`, The UI thread and the watchdog).
- Compose state is made on the main thread before the first composition (T226, T240's `ComposeStatePrepareTest` and
  its allow file of 28 objects): the prepare stages stay on the main thread and first.
- T229's rule: `Tracking.of` on the main thread before readiness is a `check` naming the caller, never a silent null;
  off it, a bounded wait (`Tracking.kt:906-923`). T242 used the same rule with a progress timeout (60 s without a new
  file). The generalisation keeps the substance (never a silent null) and splits the run-time outcome by build
  (objection 4).
- `StartupTrace.mark` counts only the main thread's steps (`StartupTrace.kt:127-130`); the trace's report names a
  freeze by its mark, and the owner's reports and `.startup-history` carry today's mark names. A stage moved off the
  main thread must still show up by name, and no mark is renamed.
- The launch budget (`docs/ideas/2026-11-07-a-launch-budget-per-mark-enforced-by-the-sweep.md`, a wave 32 task) sets
  a ceiling per mark: it keys on the marks, which stay as they are.
- ADR 0031: what humans see is built in alpha and adjusted by the owner; a proposal for it writes the options and the
  decision waits for the owner. ADR 0033: a stored key added is listed and migrated. ADR 0044: no option here makes a
  behaviour a setting (readiness is not a preference, T229's ruling); each option says so.
- Not this proposal: a snapshot of app state for a faster restart (the owner: it "can also lead to synchronization
  issues so we have to be careful"); T242's on-disk index (c) is that kind of thing and stays its own follow-up.

## Prior art

One survey of 2026-11-09, technical variant (`out/wave31-research/startup-graphs.md` [no-check: gitignored]). The
company engineering posts (Uber, Airbnb, Pinterest, LinkedIn, Lyft, Facebook) were not fetched, so nothing is
claimed about them.

| Source | Stage, thread, gate | How a value is published | Order tested | Licence |
|---|---|---|---|---|
| Jetpack App Startup ([docs](https://developer.android.com/topic/libraries/app-startup)) | `Initializer<T>`: `create()`, `dependencies()`; no thread or gate. The page names no thread; by mechanism (one `InitializationProvider` content provider, whose `onCreate` runs on the main thread at process bind) they run there, unproven by the page | `create()`'s return, cached by `AppInitializer` | lint rules; cycles not documented | Apache-2.0 |
| Macrobenchmark, `reportFullyDrawn` ([launch time](https://developer.android.com/topic/performance/issues/launch-time), [metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)) | not a graph: two system gates, first frame (TTID) and fully drawn (TTFD); `FullyDrawnReporter` is a counting gate | none | `StartupTimingMetric`, `TraceSectionMetric` over named trace sections | Apache-2.0 |
| Firebase Performance ([docs](https://firebase.google.com/docs/perf-mon/automatic)) | an `_app_start` trace plus custom traces; measurement only | none | none | proprietary SDK |
| Alibaba Alpha ([repo](https://github.com/alibaba/alpha)) | task with predecessors, async by default, a main-thread flag, priorities | not stated | cycles not stated | not shown on the page |
| Anchors ([repo](https://github.com/YummyLau/Anchors)) | task name, `dependOn`, sync on main or async on a pool; an "anchor" task blocks the caller until done | not stated | not stated | Apache-2.0 |
| android-startup ([repo](https://github.com/idisfkj/android-startup)) | `dependenciesByName()`, `callCreateOnMainThread()`, `waitOnMainThread()` (the gate), an executor, priority | `create(): T?` | topological sort with per-task cost logged; the main wait bounded (10 s default) | Apache-2.0 |
| Bolt App Startup ([repo](https://github.com/apkelly/bolt-app-startup)) | App Startup-like initializers on coroutines, breadth-first levels | not stated | not stated | Apache-2.0 |
| InitSpark ([repo](https://github.com/ktomek/initspark)), the one KMP library | key, `needs`, a `CoroutineContext`, CRITICAL or OPTIONAL | none: `execute()` returns nothing; readiness as a `StateFlow` | cycle detection and testing support claimed, not shown | MIT |
| `CompletableDeferred` as the gate ([kotlinlang thread](https://slack-chats.kotlinlang.org/t/488868/so-i-have-a-list-of-tasks-that-i-need-to-run-based-on-their-)) | the deferred made early, awaited later, completed when ready | the deferred itself | `runTest` with virtual time | Apache-2.0 |

**Common practice:** a stage is a name, a list of needs and a body that returns a value; a scheduler sorts them
topologically; the thread is a per-stage flag; the gate is a separate concept ("the main thread waits for this"),
bounded by a timeout; measurement is by named trace sections plus the platform's first-frame and fully-drawn marks.
**Outliers:** App Startup orders but has no threads; InitSpark is multiplatform but passes no values and is young
(one author). **None** combines threads, a first-frame gate, value passing and commonMain, so no option adopts a
library: each is under a page of the project's own code, and the patterns (not code) are taken. The survey
recommends a small declared graph with `CompletableDeferred` slots ("A in a small form, with B as its engine"), the
main-thread read failing by name, and the source lint kept as a backstop. No behaviour is a user setting.

## The stages, read from the code

What the launch does today, as the stages the design would declare. A stage **owns one or more T228 marks**, which
stay exactly as they are (objection 1). Thread: M main, W a worker. Work: what the stage touches besides memory.
Gates: `BEFORE_ACTIVITY` is where the main thread waits, once, in `MainActivity.onCreate` before `startCamera`;
`BEFORE_STYLE` is a worker-side completion that builds the style and posts it (never a main-thread wait); `LATER`
nothing waits for. The first frame is the platform's (after `onStart`, `onResume` and the first traversal), measured,
never waited for (objection 2).

| Stage | Its marks | Needs | Work | Now | Then | Gate | Publishes |
|---|---|---|---|---|---|---|---|
| prepare | `the Compose state's prepare`, `Tracking.prepare` | none | none | M | M | BEFORE_ACTIVITY | the state objects |
| maplibre | `MapLibre.getInstance` | none | native load | M | M | BEFORE_ACTIVITY | (`@UiThread`; its `checkThread` throws only in debuggable builds) |
| settings | `AppSettings.load` | none | disk (prefs; a write on a migration; `PaceSettings.activate`) | M | M, or W last | BEFORE_ACTIVITY | `settings` |
| tracking | `Tracking.start` | settings | disk, binder | W since T229 | W | LATER | `tracking` |
| assets | `BundledAssets.install` | none | disk, binder (package info); a full copy after an update | M | W | BEFORE_STYLE | `assets` |
| packs | `PackSet.open`, `collectContourGarbage's identities`, `ContourCache.collectGarbage` | settings | disk (lists, PMTiles headers, a second catalog), a prefs write (`seedUnits`) | M (the delete on W) | W | BEFORE_STYLE | `packs` |
| online | `OnlineService.get`, `OnlineService: TileCache`, `OnlineService: after TileCache`, `OnlineService: RegionDownload and Areas.attach`, `OnlineService: the rest` | settings | disk (the walk off M since T242), threads | M | W | BEFORE_STYLE | `online` |
| tiles | `TileService`, `OnlineService.tilesFor`, `TileService's rest`, and the style part of `style-prime, watchdog, reports and cache budget start` | packs, online, assets | memory; the style's pack metadata | M (style-prime on W) | W | BEFORE_STYLE | `tiles`, then the style posted |
| watchers | the rest of `style-prime, watchdog, reports and cache budget start` | none; the budget: online | threads; the exit records on W | M (starts) | M (starts) | LATER | none |
| downloads | `PackManager.get`, `resumePending` | tiles | disk (two `mkdirs`, the state files read) | M | W | LATER | `downloads` |
| server | `Routes and the loopback servers` | tiles, settings | sockets | M | W | BEFORE_STYLE | `server` |
| updater | `Updater.start` | settings | a prefs read (`Updater.kt:93`), then its thread | M | M | LATER | none |
| viewport | `MapViewport.prepare` | none | none | M | M | BEFORE_ACTIVITY | state |
| camera | `startCamera`, `view from <source>` | settings, the pack view | prefs (`LastFix`, `SavedView`); `packView` reads `tiles` | M | M | (after the gate) | the camera |
| mapview | `MapView`, `mapView.onCreate`, `map.attach` | maplibre | native | M | M | (after the gate) | the map view |
| activity | `links and intents`, `setContent`, `permissions` | mapview | none | M | M | (after the gate) | none |
| start | `mapView.onStart`, `MapOrientation.setForeground`, `startDeviceLocation` | mapview | binder (the location manager) | M | M | (before the first frame) | none |
| resume | `mapView.onResume` | start | none | M | M | (before the first frame) | none |
| composition | `compose <name>` to `composition applied: layout and draw` | state; reads `tiles` (`MainActivity.kt:87`), `OnlineService` and its `region`, `PackManager` (`firstrun/FirstRunCard.kt:75, 77, 86`) | none if it reads published values | M | M | (the first frame) | none |

Four things in the table decide the design: (1) `startCamera`'s pack view (`MainActivity.kt:123, 132-138`), the
first composition's no-pack test (`firstrun/FirstRun.kt:109`, `MainActivity.kt:87`) and the card
(`FirstRunCard.kt:75-86`) read `tiles`, `OnlineService` and `PackManager` before the first frame, so moving them off
the main thread needs an answer for each (below, "The first frame without `tiles`"); (2) the style's reader,
`map.attach`'s `styleJson` lambda, runs on the main thread in the `getMapAsync` callback (`MapController.kt:192,
219-222`), so `BEFORE_STYLE` is met by the worker building the style and posting it, as `DystopoApp.showStyle`
already does for a reload (`DystopoApp.kt:183-186`), and `MapController.attach` takes a style-ready callback;
(3) `AppSettings.load` is read everywhere, and `SharedPreferences` loads on the system's load executor with every
getter blocking until it is loaded, so moving it moves only the wait: it stays a `BEFORE_ACTIVITY` need, moved last
if at all; (4) `onStart` makes a binder call before the first frame (`startDeviceLocation`), a declared MAIN stage
the budget and the test see.

## What every option must do (the common contract)

- **Names.** A stage owns one or more T228 marks, which stay exactly as they are: the trace's timeline, the report
  title and the budget's ceilings key on marks; the stage spans are a second table in `GET /debug/launch` and the
  report. A mark renamed later is listed with the old name kept as an alias in the budget's file.
- **The trace off the main thread.** `StartupTrace` gains a stage span (thread, start, end, the main thread's wait on
  it) recorded from any thread; a main-thread wait on a stage is a mark of its own, `waiting for <stage>`, and while
  one is in force the trace's samples dump the awaited stage's worker thread (under the existing `MAX_LOCK_DUMPS`),
  so a report names what the worker was doing, not the latch (objection 12).
- **The readiness rule, generalised (T229), split by build.** A published value has three reads. `peek()` never
  waits and may be null (the UI and the routes). `get()` on the main thread before the value's gate has passed: in
  **debug builds** a failed `check` naming the value, its gate and the caller (the device tour and the sweep find
  it); in **release builds** a bounded, traced wait (`waiting for <stage>`) with a new report reason,
  `mainThreadRead`, naming the caller, so the owner's next Startup report names the site and the app still draws
  (objection 4; this departs from T229's one-site ruling, which crashed in every build). `await()` off the main
  thread waits, bounded: a fixed bound (30 s, `Tracking.WAIT_MS`) or a progress bound (60 s without progress,
  T242's), the timeout naming the caller. The main thread waits only at `BEFORE_ACTIVITY`, bounded, traced.
- **Timeouts and failure (objection 5).** Each slot declares `onTimeout`: `NEXT_READER_BUILDS` (T229's fallback:
  `tracking`), `RELOAD_REPAIRS` (`tiles`: the map draws on paper, the style is posted when the slot publishes, a
  `reload` rebuilds it; a timeout is a report reason, not an error shown) or `NONE` (the value is late, nothing else).
  A stage that throws publishes its failure; `await` rethrows it naming the stage. The `BEFORE_ACTIVITY` wait's
  expiry proceeds, traced; a later `get()` in release waits again (bounded), never crashes. Debug builds set both
  bounds through `POST /debug/startup/thresholds`.
- **One constructor per value (objection 11).** A bound stage's body is the only place its value is built; the
  build-on-first-touch singletons it replaces (`OnlineService.get`, `PackManager.get`) become the slot's reads, so no
  other thread builds one outside the stage and its span.
- **The run-time verifier (objection 8).** `StrictModeCounts` (debug builds) attributes each main-thread violation to
  the stage in force; `GET /debug/launch` gives the counts per stage; the device tour fails on a MAIN stage before the
  first frame with a count its declared work does not allow. C's source lint stays as the backstop.
- **The debug surface.** `GET /debug/launch`: each stage's thread, needs, gate, marks, start, ready-to-start, end,
  wait, failure and StrictMode count, each gate's time, and the runner's own overhead (below);
  `POST /debug/launch/delay?stage=&ms=` holds one stage at the next launch, generalising T229's
  `POST /debug/tracking/delay` and T242's `POST /debug/cache/hold` (both kept as aliases).
- **No setting** (ADR 0044): readiness is not a preference.

## Options

### A. A declarative stage list in commonMain, run by one small runner

What it is. `shared/.../launch/Launch.kt` (commonMain) declares the list: `Stage(name, marks, needs, runs =
MAIN|WORKER, work = setOf(DISK, BINDER, NATIVE, SOCKET), gate = BEFORE_ACTIVITY|BEFORE_STYLE|LATER)`, the values as
typed keys (`Value<T>(name, gate, onTimeout)`), and a pure validator. A runner (commonMain, about 200 lines) starts
each stage when its needs are done, on the thread it declares, through a small interface the platform supplies
(`LaunchThreads`: `main(block)`, `worker(name, block)`, `isMain()`, a latch with a timeout and, since commonMain has
no `synchronized` and its atomics need an error-level opt-in in Kotlin 2.2, a lock as a lambda, the `DriveOverlay`
pattern; the slot is a `@Volatile` value plus the platform latch; objection 7). The Android app binds a body to each
name (`launch.bind(PACKS) { PackSet.open(...) }`) from `DystopoApp.onCreate`, then `MainActivity.onCreate` calls
`launch.awaitGate(BEFORE_ACTIVITY)` before `startCamera`. A value is a `Slot<T>` (`TrackerReady` generalised, with
the three reads above). `Tracking`'s and `TileCache`'s own rules become two slots.

The JVM test (`commonTest`, no device): the list is a DAG with unique names and every need declared; every mark
belongs to exactly one stage; gates are monotone along needs (a `BEFORE_ACTIVITY` stage needs nothing later); no
stage with `DISK` or `BINDER` runs on `MAIN` before the first frame unless allowed with a reason (`NATIVE` on `MAIN`
for the named MapLibre stages; `settings` while it stays; `start`'s binder call), as T240's allow file does; every
value's reader in the list needs its writer; a seeded simulation with a fake `LaunchThreads` (random stage lengths,
many seeds) never starts a stage before its needs and never puts a disk stage on the main thread's timeline before
the gate. An Android unit test checks that every bound name is declared and every declared stage is bound. What the
test cannot see: an unlisted read of a value in Compose code, or work outside any stage; the build-split `get()` and
the StrictMode tie (the contract) catch those at run time and name them in a report.

Constraints. commonMain stays pure; no new dependency; iOS, if it comes, writes `LaunchThreads` and binds its own
bodies to the same names. Cost: the runner, the slot and the lock seam (about 300 lines with tests) and a
re-plumbing of `DystopoApp.onCreate` into bindings in one task with no move. Risks: a runner bug stalls the launch
for everyone (the bounded waits and `GET /debug/launch` name it); the indirection puts runner frames into freeze
reports' stacks; the concurrency code is written by hand where the app already has a library for it. Who else:
android-startup and Anchors declare the same three things per task; App Startup the needs only.
Exit cost: the bindings are the old `onCreate` lines with a name each; going back is pasting them in order, the
slots kept as plain objects (a day, no data).
Moving parts: one runner, one interface per platform, one debug route pair, the trace's spans.

### B. An explicit coroutine graph with `CompletableDeferred` gates

What it is. The launch is one suspend function: `val settings = async(io) { AppSettings.load(...) }`, `val packs =
async(io) { settings.await(); PackSet.open(...) }`, each published value a `CompletableDeferred<T>` in a holder, the
gates as deferreds the activity awaits (`runBlocking` with a timeout on the main thread at `BEFORE_ACTIVITY`, the
one place the main thread blocks). The order is the code; the declaration is the `await` calls. The main-thread read
before completion is `getCompleted()` (experimental, throws `IllegalStateException` when not complete, meant for
`invokeOnCompletion` handlers) wrapped to name the caller.

The JVM test: `runTest` with fakes for each body proves a chosen order and that a reader waits; it cannot list "every
disk stage" or "gates are monotone" without a declaration beside the code, so the test either re-states the graph
(two lists to keep equal) or reads the source (C's lint). In commonMain it needs `kotlinx-coroutines-core` added to
`shared` (Apache-2.0, multiplatform), a dependency the whole launch leans on, so it answers
`.claude/skills/proposal/dependency.md`: (1) structured cancellation and dispatchers, more than a page; (2)
Apache-2.0, permissive; (3) to (5) JetBrains, released several times a year, a closure of the core and atomicfu; (6)
its tests do not run here; (7) no untrusted input; (8) the coordinator, with the Kotlin version; (9) replaced by A's
runner, a few days; (10) a stuck coroutine shows as a parked thread, not by stage name, unless the stage name is
passed as a `CoroutineName`. Kept in `androidApp` instead, no dependency is added (it is on the app's classpath
already, through Compose, and in `MainActivity.kt`) and iOS gets nothing.
Cost: smaller than A at first (no runner); the first move needs no indirection. Risks: the knowledge of which stage
is disk work lives in nobody's declaration; a dispatcher change is a review item, not a test failure; blocking the
main thread through `runBlocking` is the pattern the coroutine docs warn against, so the gate wait needs the same
care as A's. Who else: Bolt App Startup; the kotlinlang thread's pattern.
Exit cost: low inside the app (the deferreds become slots); a commonMain dependency once others lean on it is hard to
take out.
Moving parts: kotlinx.coroutines in `shared` (or not), one launch scope, the trace's spans.

### C. The hand-moved steps kept, with a source-reading lint (T240's shape)

What it is. No `Launch`: each move stays a hand edit like T229 and T242, each with its own ready flag in the shape
the code already has (`TrackerReady` reused, not generalised). A JVM test reads `DystopoApp.onCreate`,
`MainActivity.onCreate` and `MapScreen` and fails on a call from a deny list (`File(`, `readText`, `listFiles`,
`getSharedPreferences`, `PackSet.open`, `assets.open`, `getPackageInfo`, the constructors known to do disk work) that
is not inside a `Thread {` or a named off-main helper, with an allow file `path | call | reason` for each that stays.
Cost: the smallest (a test of about 200 lines; the allow file lists today's dozen sites, each a follow-up). Risks: the
deny list sees only calls it names (a helper two calls deep is invisible: `OnlineService.get()` was), and it says
nothing about order or about which reader waits for which writer, the two bugs the owner's phone met; every move
invents its readiness again; iOS starts from nothing. With the budget and StrictMode tied to the mark in force it
gives most of the disk-on-main guard (the critic's case for C), but not the order. Who else: this project's own
`ComposeStatePrepareTest`; Android's StrictMode (`debug/StrictModeCounts.kt` counts disk reads at run time in debug
builds already).
Exit cost: none (a test to delete).
Moving parts: one test, one allow file.

### A+B. The list in commonMain as pure data, the runner in `androidApp` on coroutines (the critic's shape)

What it is. A's declaration, validator and JVM test in `shared` commonMain, with no concurrency in commonMain at all;
B's engine in `androidApp`: the runner (`app/launch/LaunchRunner.kt`) starts each bound stage on the main thread or a
worker dispatcher as the list declares, each slot a `CompletableDeferred` behind the three reads, the
`BEFORE_ACTIVITY` wait a bounded, traced block before `startCamera`, `BEFORE_STYLE` the `tiles` stage's worker
building the style and posting it. Constraints: commonMain pure data; no new dependency anywhere; the test reads the
one list. Cost: A's list and test, B's runner without a hand-written latch and lock (smaller than A). Risks: the list
and the bindings can drift (the Android unit test checks both ways; StrictMode per stage checks the declared work);
an iOS app would write its own runner over the same list. Who else: the survey's recommendation; android-startup's
declarations on an executor. Exit cost: as A's. Moving parts: one runner in the app, the list in `shared`, the route
pair, the trace's spans.

## The first frame without `tiles` (visible; the owner decides, ADR 0031)

Moving `packs`, `online` and `tiles` off the main thread leaves the first composition and `startCamera` without a
`tiles` value for a moment. Today `shown`'s first value and `FirstRun.noPack` read the published service, and
`FirstRunCard`'s `remember(tick)` reads `OnlineService.get(app).region` and `PackManager.get(app)`
(`FirstRunCard.kt:75, 77`); a placeholder "no packs" service would make `noPack` true, so the first-run card
("Download the map around me", …) and the no-pack line would show for the pack opening's length on every launch of
a phone that has maps, then vanish: the flash the idea names. The same holds in every option.

- **F1. Wait.** `tiles` stays a `BEFORE_ACTIVITY` need: the main thread waits for the worker at the gate. No flash;
  the first frame pays the pack opening (traced as `waiting for packs`). Nothing visible changes.
- **F2. Unknown until known.** `tiles`, `online` and `downloads` are read by `peek()`; before they publish, the first
  composition reads null as "not known yet": no card, no no-pack line, and the position button and scale bar
  (hidden today when there is no pack, `MainActivity.kt:235-236`) also wait. When `tiles` publishes the card appears
  if there is no pack. On a phone with maps nothing flashes; the controls appear with the map (the style also waits
  for `tiles`, so the map is paper until then anyway). On a first run the card appears a moment after the first frame.
- **F3. A placeholder "no packs" value.** The flash on every launch; recorded, not recommended.
- **F4. A cheap listing first.** A `BEFORE_ACTIVITY` stage lists the pack directories (no file opened) and answers
  "is there a pack"; the open follows on a worker. No flash and a shorter wait than F1, but still a main-thread wait
  on disk, and two readers of the pack set that can disagree (a file listed but unreadable).

`startCamera`'s pack view needs the primary pack's box and activation time (`MainActivity.kt:132-138`). Under F2 it
reads both from preferences: `PackView` already stores the activation time (`PackView.activatedAt`,
`MapViewport.kt:185`); it gains the box, written when a pack is activated and at the first `tiles` publish after the
update (a new stored key, derived, ADR 0033: a launch with no stored box waits for `tiles` once, F1's way). Under F1
nothing changes.

## Recommendation

**A+B** (revised from A on the critic's objections 7 and 13; the coordinator's decision after the critic): the stage
list, the slots' typed keys and the validator in `shared` commonMain as pure data with the JVM test over them; the
runner in `androidApp` on `kotlinx.coroutines`, each slot a `CompletableDeferred` behind the three reads; the main
thread's one wait at `BEFORE_ACTIVITY` before `startCamera`; `BEFORE_STYLE` a worker-side completion that posts the
style; T229's rule split by build; `onTimeout` per slot; the marks unchanged, a stage owning one or several;
`StrictModeCounts` tied to the stage in force; C's lint kept as the source backstop. It is the one shape whose test
proves the three claims the task asks for (no disk stage on the main thread before the gate, every main-thread reader
waits on its gate, the order is a DAG) from one list, without a device, and without writing concurrency code the app
already has in a library. The runner moves into commonMain only when an iOS target exists. **F1 first**; F2 waits for
the owner (ADR 0031) and is shown in the move's report with screenshots.

**The order of moves**, each one task with the launch budget's figures before and after:

1. Wave 32, first: the list, the validator and its test, the runner and the Android bindings with **every stage
   declared where it runs now** (no move); `Tracking`'s and `TileCache`'s readiness become slots; the trace's stage
   spans, `GET /debug/launch`, StrictMode per stage. Its measure (objection 6): the runner's own overhead from
   `GET /debug/launch`, the sum over stages of start minus ready-to-start plus the runner's main-thread time before
   the gate, under 20 ms median over the budget's launches; the first-frame median is the secondary. The launch
   budget task follows and keys its ceilings on the marks.
2. Then the moves, in the order **the owner's phone trace** gives (the next Startup freeze report or "Report the last
   start" on a release carrying step 1); without one, in the order of the emulator budget's figures. The candidates:
   `online` (`OnlineService`'s construction; `Areas.attach` and the threads off the main thread), `packs` and `tiles`
   (F1 first, then F2 on the owner's word), `assets` (an update's full copy is the long case), `downloads`,
   `collectContourGarbage`'s second catalog; `settings` last, if the trace shows it.
3. The 28 unprepared objects of T240's allow file join the prepare stage as they are touched.

## What would change this recommendation

- The owner's phone trace on a release with T242 shows the first frame under the cold limit (5 s) with no step over a
  second: the moves are not worth a runner, and C with the budget and StrictMode per mark is enough.
- An iOS target is added to `shared`: the runner moves into commonMain (on `kotlinx.coroutines` there, which then
  answers the dependency checklist above), the list and test unchanged.
- The list passes about 40 stages or needs stages made at run time (a plugin, per-pack stages): a library with a
  thread pool and priorities (android-startup's shape) becomes worth its dependency.
- The runner's own overhead passes 20 ms median on the emulator: bindings go back to straight calls with the list kept
  only as the test's declaration (C plus the list).
- The owner rejects F2 and F1's wait shows in the trace: F4.

## Claims to verify

- `MapLibre.getInstance` and `MapView` must be called on the main thread: verified by the critic; `@UiThread`, and
  `ThreadUtils.checkThread` throws only in debuggable builds, so the list's declaration is the real guard.
- `getSharedPreferences` loads on the system's load executor (`sLoadExecutor`, threads named `SharedPreferences`) and
  every getter blocks until it is loaded (AOSP `SharedPreferencesImpl`): verified by the critic; `AppSettings.load`
  also writes on a migration.
- `StartupTrace.mark` ignores marks off the main thread (`StartupTrace.kt:127-130`): verified.
- The first composition reads `app.tiles` (`MainActivity.kt:87`), `FirstRun.noPack` (`firstrun/FirstRun.kt:109`),
  `OnlineService.get` and its `region`, and `PackManager.get` (`firstrun/FirstRunCard.kt:75, 77, 86`); `startCamera`
  reads `tiles` through `packView` (`MainActivity.kt:123, 133`): verified, the list completed.
- `OnlineService` is built on the main thread through `TileService`'s `online` (`TileService.kt:306-312`), and
  `resumePending` reads the state files on the caller's thread (`packs/PackDownloader.kt:273-277`): verified.
- `Deferred.getCompleted` is experimental and throws `IllegalStateException` when not complete; `kotlinx-coroutines-core`
  is Apache-2.0: verified.
- App Startup runs initializers on the main thread: the page does not say; true by mechanism. InitSpark passes no
  values: verified.

## Critic's objections and responses

The review: `0061-critic-review.md` (0 blocking, 7 serious, 7 minor). Every objection is taken; the body above is
corrected in place.

1. **Serious, names are not the marks.** Response: right; the table merged, split and omitted marks. Resolution:
   accepted. A stage owns one or more marks, which stay exactly as they are; the table has a marks column
   (`ContourCache.collectGarbage` included); stage spans are a second table; a later rename keeps an alias.
2. **Serious, the first-frame gate misdefined, `onStart` and `onResume` missing.** Response: right; `setContent`
   returning draws nothing. Resolution: accepted. `BEFORE_ACTIVITY` is the one main-thread wait, before `startCamera`;
   the first frame is measured, never waited for; `start` (with `startDeviceLocation`'s binder call) and `resume` are
   MAIN stages in the table and the test's allow list.
3. **Serious, `beforeStyle` was a main-thread wait.** Response: right; `styleJson()` runs in the `getMapAsync`
   callback on the main thread (`MapController.kt:219-222`). Resolution: accepted. `BEFORE_STYLE` is the `tiles`
   worker building the style and posting it as `showStyle` does; `style-prime` folds into it; `attach` takes a
   style-ready callback.
4. **Serious, the `check` is a release-build crash with no lift.** Response: right; T229 accepted that for one value
   with known readers, not for every slot. Resolution: accepted. Debug fails by name; release waits bounded and traced
   with the report reason `mainThreadRead`; the ADR states the departure from T229.
5. **Serious, wait bounds without a lift, the timeout's outcome unstated.** Resolution: accepted. `onTimeout` per slot
   (`NEXT_READER_BUILDS`, `RELOAD_REPAIRS`, `NONE`) with what the user sees; the gate's expiry proceeds, traced; the
   bounds settable in debug through `POST /debug/startup/thresholds`.
6. **Serious, step 1's measure cannot fail.** Response: right; the emulator's spread is seconds. Resolution: accepted.
   The runner's own overhead from `GET /debug/launch`, under 20 ms median; the first-frame median secondary.
7. **Serious, commonMain concurrency primitives unstated.** Response: right; no `synchronized`, atomics behind an
   error-level opt-in. Resolution: accepted with change, the critic's second form: the runner leaves commonMain for
   `androidApp` on coroutines (A+B; the critic's confidence here was moderate, the coordinator's decision settles it);
   A's text now names the lock seam it would have needed, for the record.
8. **Minor, the declared work unverified.** Resolution: accepted. `StrictModeCounts` per stage in force, the tour fails
   on an undeclared count; C's lint stays as the source backstop.
9. **Minor, the reader list incomplete.** Resolution: accepted. `FirstRunCard.kt:75, 77` added; under F2 the card reads
   `peek()` of `online` and `downloads` too.
10. **Minor, two rows understated.** Resolution: accepted. `updater` reads prefs; `settings` writes on a migration
    and activates the pace settings; the SharedPreferences wording corrected.
11. **Minor, singleton races.** Resolution: accepted. A stage's body is the only constructor; `OnlineService.get` and
    `PackManager.get` become slot reads.
12. **Minor, a wait sampled as a latch.** Resolution: accepted. While a `waiting for <stage>` mark is in force the
    samples dump the awaited worker, under `MAX_LOCK_DUMPS`.
13. **Minor, the iOS argument overweighted.** Resolution: accepted. The commonMain list is justified by the JVM test
    and the one declaration; the runner's home is `androidApp` until an iOS target exists (the reversal condition).
14. **Minor, App Startup's thread.** Resolution: accepted. The row says "by mechanism, unproven by the page".
