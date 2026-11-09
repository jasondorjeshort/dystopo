# 0061. The launch as a staged state machine in `shared`

Date: 2026-11-09
Status: under review

Paths without a root are under `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/`. Every `file:line`
is read at commit e184b663. The launch inventory of wave 29 (`out/wave29-research/launch-inventory.md` [no-check:
gitignored]) was not on the machine this was written on; its findings are taken from T229's and T242's merge bodies,
the wave 30 record and the code, and the critic can check section 8 against the stage table below.

## Question

The launch's order is implicit: `DystopoApp.onCreate` (`DystopoApp.kt:43-91`), `MainActivity.onCreate`
(`MainActivity.kt:54-105`) and the first composition (`MapScreen`, `MainActivity.kt:213-260`) run their steps in
the order written, all on the main thread except the threads two of them start. Two freezes at launch on the owner's
phone (Android 12, a 576 MB large heap) came from that: the first composition raced a thread for the tracker (T229)
and the online tile cache walked tens of GB on the main thread (T242). Each fix moved one step by hand and invented
its own readiness rule (`TrackerReady`, `Tracking.kt:989-1038`; `TileCache.state`, `whenReady`, `awaitReady`,
`online/TileCache.kt:66-69, 258, 273`). The owner, 2026-11-07: "documenting the correct launch order lets us do
everything in different threads with the correct synchronization checks." What is decided: the shape of a `Launch`
in `shared` commonMain (named stages, what each needs, the gate it satisfies, the thread it declares), how a stage
publishes a value the main thread reads, T229's rule generalised to every gate, how the trace and the coming launch
budget read the same names, what a JVM test proves, and the order of the moves. In ten years: the launch is a list
anyone can read in one file, a move is a one-line change of a stage's thread or gate checked by a test, and an iOS
app runs the same list with its own runner.

## Constraints that matter here

- Platforms, licensing, offline first, agent-drivable, the design goals (ADR 0025): as in the template. commonMain
  is pure Kotlin, no `java.*` (`.claude/rules/kotlin.md`); `shared` has no `kotlinx.coroutines` today (its only
  commonMain dependency is the SQLDelight runtime, `shared/build.gradle.kts`); platform pieces come in through small
  interfaces or `expect`/`actual` (`IoPool`, `PerThread` in `shared/.../sources/elevation/Platform.kt`).
- The UI thread never takes a lock and never does disk or network I/O; it reads snapshots workers publish
  (`docs/reference/android-app.md`, The UI thread and the watchdog).
- Compose state is made on the main thread before the first composition (T226, T240's `ComposeStatePrepareTest` and
  its allow file of 28 objects): the prepare stages stay on the main thread and first.
- T229's rule: `Tracking.of` on the main thread before readiness is a `check` naming the caller, never a silent null;
  off it, a bounded wait (`Tracking.kt:906-923`). T242 used the same rule with a progress timeout (60 s without a new
  file). The generalisation keeps both.
- `StartupTrace.mark` counts only the main thread's steps (`StartupTrace.kt:126-130`); the trace's report names a
  freeze by its mark. A stage moved off the main thread must still show up by name, or the owner's next report goes
  blind exactly where the work went.
- The launch budget (`docs/ideas/2026-11-07-a-launch-budget-per-mark-enforced-by-the-sweep.md`, a wave 32 task) sets
  a ceiling per mark: it reads the stage names this design gives.
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
| Jetpack App Startup ([docs](https://developer.android.com/topic/libraries/app-startup)) | `Initializer<T>`: `create()`, `dependencies()`; no thread or gate (runs on the caller, the main thread, from a content provider) | `create()`'s return, cached by `AppInitializer` | lint for the manifest; cycles not documented | Apache-2.0 |
| Macrobenchmark, `reportFullyDrawn` ([launch time](https://developer.android.com/topic/performance/issues/launch-time), [metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)) | not a graph: two system gates, first frame (TTID) and fully drawn (TTFD); `FullyDrawnReporter` is a counting gate | none | `StartupTimingMetric`, `TraceSectionMetric` over named trace sections | Apache-2.0 |
| Firebase Performance ([docs](https://firebase.google.com/docs/perf-mon/automatic)) | an `_app_start` trace plus custom traces; measurement only | none | none | proprietary SDK |
| Alibaba Alpha ([repo](https://github.com/alibaba/alpha)) | task with predecessors, async by default, a main-thread flag, priorities | not stated | cycles not stated | not shown on the page |
| Anchors ([repo](https://github.com/YummyLau/Anchors)) | task name, `dependOn`, sync on main or async on a pool; an "anchor" task blocks the caller until done | not stated | not stated | Apache-2.0 |
| android-startup ([repo](https://github.com/idisfkj/android-startup)) | `dependenciesByName()`, `callCreateOnMainThread()`, `waitOnMainThread()` (the gate), an executor, priority | `create(): T?` | topological sort with per-task cost logged; the main wait bounded (10 s default) | Apache-2.0 |
| Bolt App Startup ([repo](https://github.com/apkelly/bolt-app-startup)) | App Startup-like initializers on coroutines, breadth-first levels | not stated | not stated | Apache-2.0 |
| InitSpark ([repo](https://github.com/ktomek/initspark)), the one KMP library | key, `needs`, a `CoroutineContext`, CRITICAL or OPTIONAL | no values between stages; readiness as a `StateFlow`, events as a flow | cycle detection and testing support claimed, not shown | MIT |
| `CompletableDeferred` as the gate ([kotlinlang thread](https://slack-chats.kotlinlang.org/t/488868/so-i-have-a-list-of-tasks-that-i-need-to-run-based-on-their-)) | the deferred made early, awaited later, completed when ready | the deferred itself | `runTest` with virtual time | Apache-2.0 |

**Common practice:** a stage is a name, a list of needs and a body that returns a value; a scheduler sorts them
topologically; the thread is a per-stage flag; the gate is a separate concept ("the main thread waits for this"),
bounded by a timeout; measurement is by named trace sections plus the platform's first-frame and fully-drawn marks.
**Outliers:** App Startup orders but has no threads; InitSpark is multiplatform but passes no values and is young
(one author). **None** combines threads, a first-frame gate, value passing and commonMain, so no option adopts a
library: each is under a page of the project's own code, and the patterns (not code) are taken. The survey
recommends a small declared graph with `CompletableDeferred`-like slots, the main-thread read failing by name, and the
source lint kept as a backstop. No behaviour is a user setting.

## The stages, read from the code

What the launch does today, as the stages the design would declare. Thread: M main, W a worker. Work: what the
stage touches besides memory (disk, binder, native, socket). Gate: what must be done before (`beforeFirstFrame`: the
activity's `setContent` returns a first frame; `beforeStyle`: `map.loadStyle` gets its JSON; `later`: nothing waits).
"Now" is where it runs at e184b663; "Then" is this design's target. The name is the T228 mark it already has.

| Stage (mark) | Needs | Work | Now | Then | Gate | Publishes |
|---|---|---|---|---|---|---|
| `the Compose state's prepare`, `Tracking.prepare` | none | none | M | M | beforeFirstFrame | the state objects |
| `MapLibre.getInstance` | none | native load | M | M | beforeFirstFrame | (MapLibre requires the main thread) |
| `AppSettings.load` | none | disk (prefs) | M | W | beforeFirstFrame | `settings` |
| `Tracking.start` | settings | disk, binder | W since T229 | W | later | `tracking` (`TrackingUiState.tracking`) |
| `BundledAssets.install` | none | disk, binder (package info); a full copy after an update | M | W | beforeStyle | `assets` |
| `PackSet.open` (with the units seed) | settings | disk (directory lists, PMTiles headers) | M | W | beforeStyle | `packs` |
| `collectContourGarbage's identities` | packs | disk (a second catalog) | M (the delete on its own thread) | W | later | none |
| `OnlineService: TileCache` and its rest | settings | disk (the walk off M since T242), threads, `Areas.attach` | M | W | beforeStyle | `online` |
| `TileService` | packs, online | memory | M | W | beforeStyle | `tiles` |
| `style-prime` | tiles | disk (pack metadata) | W | W | later | none |
| `MainWatchdog.start`, `Reports.start`, `CacheBudget.start` | none; budget: tiles | threads; reports' exit records on W | M (starts) | M (starts) | later | none |
| `PackManager.get`, `resumePending` | tiles | disk (state files read on M) | M | W | later | `downloads` |
| `Routes and the loopback servers` | tiles, settings | sockets | M | W | beforeStyle | `server` |
| `Updater.start` | settings | starts its thread | M | M | later | none |
| `MapViewport.prepare` | none | none | M | M | beforeFirstFrame | state |
| `startCamera` (`view from <source>`) | settings, the pack view | prefs; `packView` reads `tiles` | M | M | beforeFirstFrame | the camera |
| `MapView`, `mapView.onCreate`, `map.attach` | MapLibre | native | M | M | beforeFirstFrame | the map view |
| `links and intents`, `setContent`, `permissions` | map view | none | M | M | beforeFirstFrame | none |
| the first composition (`compose <name>`) | state; reads `tiles` (`MainActivity.kt:87`), `OnlineService` (`firstrun/FirstRunCard.kt:86`) | none if it reads published values | M | M | (the first frame) | none |

Three things in the table decide the design: (1) `startCamera`'s pack view (`MainActivity.kt:123, 132-138`) and the
first composition's no-pack test (`firstrun/FirstRun.kt:109`, `MainActivity.kt:87`) read `tiles` before the first
frame, so moving `PackSet.open` off the main thread needs an answer for both (below, "The first frame without
`tiles`"); (2) `map.attach`'s style lambda (`MainActivity.kt:69`) is the one reader at `beforeStyle`, and it already
runs late; (3) `AppSettings.load` is read everywhere, so it stays a `beforeFirstFrame` need the main thread waits for,
bounded and traced as a wait, not as main-thread disk work.

## What every option must do (the common contract)

- **Names.** Each stage's name is its T228 mark, so the trace, the report title and the launch budget read one
  list; the launch budget's ceilings key on these names.
- **The trace off the main thread.** `StartupTrace` gains a stage span (thread, start, end, the main thread's wait on
  it) recorded from any thread; a main-thread wait on a stage is a mark of its own, `waiting for <stage>`, so a slow
  worker stage is named in a report exactly as a main-thread step is now.
- **The readiness rule, generalised (T229).** A published value has three reads. `peek()` never waits and may be
  null (the UI and the routes). `get()` on the main thread before the value's gate has passed is a programming error:
  a `check` naming the value, its gate and the caller, never a null; once its gate has passed it answers. `await()`
  off the main thread waits, bounded: a fixed bound (30 s, `Tracking.WAIT_MS`) or a progress bound (60 s without
  progress, T242's), the timeout naming the caller. The main thread waits only at a gate, only for a stage that gate
  needs, bounded, and the wait is traced.
- **Failure.** A stage that throws publishes its failure; `await` rethrows it naming the stage; a gate whose stage
  failed passes with the failure recorded (the app must still draw; T229's fallback, "the next caller builds it", is
  the per-stage retry where one exists).
- **The debug surface.** `GET /debug/launch`: each stage's thread, needs, gate, start, end, wait and failure, and
  each gate's time; `POST /debug/launch/delay?stage=&ms=` holds one stage at the next launch, generalising T229's
  `POST /debug/tracking/delay` and T242's `POST /debug/cache/hold` (both kept as aliases).
- **No setting** (ADR 0044): readiness is not a preference.

## Options

### A. A declarative stage list in commonMain, run by one small runner

What it is. `shared/.../launch/Launch.kt` (commonMain) declares the list: `Stage(name, needs, runs = MAIN|WORKER,
work = setOf(DISK, BINDER, NATIVE, SOCKET), gate = BEFORE_FIRST_FRAME|BEFORE_STYLE|LATER)`, the values as typed keys
(`Value<T>(name, gate)`), and a pure validator. A runner (commonMain, about 200 lines) starts each stage when its
needs are done, on the thread it declares, through a small interface the platform supplies (`LaunchThreads`:
`main(block)`, `worker(name, block)`, `isMain()`, and a latch with a timeout). The Android app binds a body to each
name (`launch.bind(PACKS) { PackSet.open(...) }`) from `DystopoApp.onCreate`, then `MainActivity.onCreate` calls
`launch.awaitGate(BEFORE_FIRST_FRAME)` before `startCamera`. A value is a `Slot<T>` (`TrackerReady` generalised, with
the three reads above). `Tracking`'s and `TileCache`'s own rules become two slots.

The JVM test (`commonTest`, no device): the list is a DAG with unique names and every need declared; gates are
monotone along needs (a `beforeFirstFrame` stage needs nothing later); no stage with `DISK` or `BINDER` runs on
`MAIN` before `BEFORE_FIRST_FRAME` (`NATIVE` on `MAIN` is allowed only for the named MapLibre stages, each with a
reason, as T240's allow file does); every value's reader in the list needs its writer; a seeded simulation with a
fake `LaunchThreads` (random stage lengths, many seeds) never starts a stage before its needs and never puts a disk
stage on the main thread's timeline before the first frame. An Android unit test checks that every bound name is
declared and every declared stage is bound. What the test cannot see: an unlisted read of a value in Compose code;
`get()`'s check catches that at run time on the first launch that hits it, and the device tour hits the launch.

Constraints. commonMain stays pure (no `java.*`; the latch is the platform's, through the interface); no new
dependency; iOS writes `LaunchThreads` (a dispatch queue and a semaphore) and binds its own bodies to the same names.
Cost: the runner and the slot (about 300 lines with tests) and a re-plumbing of `DystopoApp.onCreate` into bindings
in one task with no move (every stage declared where it runs now). Risks: a runner bug stalls the launch for
everyone (the bounded waits and `GET /debug/launch` name it); the indirection makes the launch harder to step
through than straight code. Who else: android-startup and Anchors declare the same three things per task; App
Startup the needs only.
Exit cost: the bindings are the old `onCreate` lines with a name each; going back is pasting them in order, the
slots kept as plain objects (a day, no data).
Moving parts: one runner, one interface per platform, one debug route pair, the trace's spans.

### B. An explicit coroutine graph with `CompletableDeferred` gates

What it is. The launch is one suspend function: `val settings = async(io) { AppSettings.load(...) }`, `val packs =
async(io) { settings.await(); PackSet.open(...) }`, each published value a `CompletableDeferred<T>` in a holder, the
gates as deferreds the activity awaits (`runBlocking` with a timeout on the main thread at `beforeFirstFrame`, the
one place the main thread blocks). The order is the code; the declaration is the `await` calls. The main-thread read
before completion is `getCompleted()` wrapped to name the caller (`getCompleted` is marked experimental in
kotlinx.coroutines).

The JVM test: `runTest` with fakes for each body proves a chosen order and that a reader waits; it cannot list "every
disk stage" or "gates are monotone" without a declaration beside the code, so the test either re-states the graph
(two lists to keep equal) or reads the source (C's lint). In commonMain it needs `kotlinx-coroutines-core` added to
`shared` (Apache-2.0, multiplatform; already on the Android app's classpath through Compose), a dependency the whole
launch leans on, so it answers `.claude/skills/proposal/dependency.md`: (1) structured cancellation and dispatchers,
more than a page; (2) Apache-2.0, permissive; (3) to (5) JetBrains, released several times a year, a closure of the
core and atomicfu; (6) its tests do not run here; (7) no untrusted input; (8) the coordinator, with the Kotlin
version; (9) replaced by A's runner, a few days; (10) a stuck coroutine shows as a parked thread, not by stage name,
unless the stage name is passed as a `CoroutineName`. Kept in `androidApp` instead, no dependency is added and iOS
gets nothing.
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
invents its readiness again; the trace's marks and the budget's names stay hand-kept; iOS starts from nothing. Who
else: this project's own `ComposeStatePrepareTest`; Android's StrictMode (`debug/StrictModeCounts.kt` counts disk
reads at run time in debug builds already).
Exit cost: none (a test to delete).
Moving parts: one test, one allow file.

## The first frame without `tiles` (visible; the owner decides, ADR 0031)

Moving `PackSet.open` and `TileService` off the main thread leaves the first composition and `startCamera` without a
`tiles` value for a moment. Today `shown`'s first value and `FirstRun.noPack` read the published service; a
placeholder "no packs" service would make `noPack` true, so the first-run card ("Download the map around me", …) and
the no-pack line would show for the pack opening's length on every launch of a phone that has maps, then vanish: the
flash the idea names. The same holds in all three options; the options differ only in where the answer is written.

- **F1. Wait.** `tiles` stays a `beforeFirstFrame` need: the main thread waits for the worker at the gate. No flash;
  the first frame pays the pack opening (traced as `waiting for PackSet.open`). Nothing visible changes.
- **F2. Unknown until known (recommended).** `tiles` is a slot; before it publishes, the first composition reads
  `peek() == null` as "not known yet": no card, no no-pack line, and the position button and scale bar (hidden today
  when there is no pack, `MainActivity.kt:235-236`) also wait. When it publishes the card appears if there is no
  pack. On a phone with maps nothing flashes; the controls appear with the map (the style also waits for `tiles`, so
  the map is paper until then anyway). On a first run the card appears a moment after the first frame.
- **F3. A placeholder "no packs" value.** The flash on every launch; recorded, not recommended.
- **F4. A cheap listing first.** A `beforeFirstFrame` stage lists the pack directories (no file opened) and answers
  "is there a pack"; the open follows on a worker. No flash and a shorter wait than F1, but still a main-thread wait
  on disk, and two readers of the pack set that can disagree (a file listed but unreadable).

`startCamera`'s pack view needs the primary pack's box and activation time (`MainActivity.kt:132-138`). Under F2 it
reads both from preferences: `PackView` already stores the activation time (`PackView.activatedAt`); it gains the
box, written when a pack is activated and at the first `tiles` publish after the update (a new stored key, derived,
ADR 0033: a launch with no stored box waits for `tiles` once, F1's way). Under F1 nothing changes.

## Recommendation

**A**, with F2 for the first frame, and C's lint kept as a backstop for the calls no declaration names. A is the one
option whose test proves the three claims the task asks for (no disk stage before the first-frame gate on the main
thread, every main-thread reader waits on its gate, the order is a DAG) from one list, without a device, and the only
one an iOS app can run without a new dependency in `shared`. B gives the same run-time behaviour, but its order lives
in code no test can read without a second list; C keeps today's way of finding the next freeze, which is the owner's
phone. F2 is the side effect's recommendation and waits for the owner (ADR 0031); until the owner chooses, the move of
`PackSet.open` is built behind F1 (no visible change) and F2 is shown in its report with screenshots.

**The order of moves**, each one task with the launch budget's figures before and after:

1. Wave 32, first: `Launch`, `Slot`, the runner and the Android bindings with **every stage declared where it runs
   now** (no move); `Tracking`'s and `TileCache`'s readiness become slots; the trace's stage spans and
   `GET /debug/launch`; the test. Its measure: the first frame's median unchanged within the emulator's spread. The
   launch budget task follows and keys its ceilings on these names.
2. Then the moves, in the order **the owner's phone trace** gives (the next Startup freeze report or "Report the last
   start" on a release carrying step 1); without one, in the order of the emulator budget's figures. The candidates,
   from the table: `OnlineService`'s construction (`beforeStyle`; `Areas.attach` and the threads off the main
   thread), `PackSet.open` with `TileService` (F1 first, then F2 on the owner's word), `BundledAssets.install` (an
   update's full copy is the long case), `PackManager.get` and `resumePending`, `collectContourGarbage`'s second
   catalog; `AppSettings.load` last, if the trace shows it.
3. The 28 unprepared objects of T240's allow file become prepare stages as they are touched.

## What would change this recommendation

- The owner's phone trace on a release with T242 shows the first frame under the cold limit (5 s) with no step over a
  second: the moves are not worth a runner, and C alone is enough.
- `kotlinx.coroutines` enters `shared` commonMain for another reason (sync, routing): B's dependency cost falls to zero,
  and A's runner is better written on it (a coroutine runner over the same list, A's test unchanged).
- The list passes about 40 stages or needs stages made at run time (a plugin, per-pack stages): a library with a
  thread pool and priorities (android-startup's shape) becomes worth its dependency.
- The runner's own cost shows in the budget (over 20 ms on the emulator's first frame): bindings go back to straight
  calls with A's list kept only as the test's declaration (C plus the list).
- The owner rejects F2 and F1's wait shows in the trace: F4.
- No iOS app is planned: A's runner may live in `androidApp`, the list and its test still in commonMain.

## Claims to verify

- `MapLibre.getInstance` and `MapView` must be called on the main thread (MapLibre Native Android's docs and
  `MapLibre.java`).
- `getSharedPreferences` starts its load on a thread of its own and a read blocks until it is loaded (AOSP
  `SharedPreferencesImpl.startLoadFromDisk`, `awaitLoadedLocked`), so `AppSettings.load` on a worker only moves the
  wait.
- `StartupTrace.mark` ignores marks off the main thread (`StartupTrace.kt:126-130`).
- The first composition reads `app.tiles` (`MainActivity.kt:87`), `FirstRun.noPack` (`firstrun/FirstRun.kt:109`) and
  `OnlineService.get` (`firstrun/FirstRunCard.kt:86`); `startCamera` reads `tiles` through `packView`
  (`MainActivity.kt:123, 133`).
- `OnlineService` is built on the main thread through `TileService`'s `online` (`TileService.kt:308-312`), and
  `resumePending` reads the state files on the caller's thread (`packs/PackDownloader.kt:273-277`).
- `Deferred.getCompleted` is experimental and throws `IllegalStateException` when not complete (kotlinx.coroutines
  API docs); `kotlinx-coroutines-core` is Apache-2.0 and multiplatform.
- App Startup runs initializers on the main thread (its docs do not say; the survey marked it unproven).
- InitSpark passes no values between stages (its README).

## Critic's objections and responses

(none yet)
