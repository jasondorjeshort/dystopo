# Critic review of proposal 0061: the launch as a staged state machine in `shared`

Date: 2026-11-09
Reviewed: `0061-the-launch-as-a-staged-state-machine.md` at `task/T245` 4abec898; code read at e184b663 in the main
checkout and the worktree (the same code). The wave 29 launch inventory was not on this machine, so section 8 of it
could not be compared; the proposal's stage table was checked against `DystopoApp.onCreate`, `MainActivity.onCreate`,
`MapScreen`, `MapController.attach`, `TileService`, `OnlineService`, `PackManager`, `PackDownloader`, `Tracking`,
`TileCache`, `StartupTrace` and the test resources instead. Already ruled out and not re-argued: moving nothing; the
snapshot-of-app-state idea.

Verdict in one line: the shape is right (one declared list, one readiness rule, the marks as the names), 0 blocking,
7 serious, 7 minor; the serious items are definitions the ADR must fix, not reasons to choose another option, and one
of them (where the runner lives) changes the recommendation's second half.

## Claims verified

| Claim | Found | Source |
|---|---|---|
| `MapLibre.getInstance` and `MapView` need the main thread | Partly as stated. The class and every `getInstance` overload carry `@UiThread`; `getInstance` calls `ThreadUtils.checkThread(TAG)`, which throws `CalledFromWorkerThreadException` **only in debuggable builds** (`if (debug) ... Looper.myLooper() != Looper.getMainLooper()`); in release builds it does nothing. `MapView` is an Android `View`, main thread by the platform. So the rule is right, but the run-time check is debug-only: a wrong move would pass on the owner's release build. The test's declaration (NATIVE on MAIN allowed for the named MapLibre stages) is therefore the real guard. | `MapLibre.java`, `utils/ThreadUtils.java` on maplibre-native `main` |
| `getSharedPreferences` loads on a thread of its own; a read blocks until loaded | Right in substance, wrong in detail. `startLoadFromDisk` sets `mLoaded = false` and submits `loadFromDisk` to a **static `sLoadExecutor`** (threads named `SharedPreferences`), not a thread of its own; `awaitLoadedLocked` loops on `mLock.wait()` until `mLoaded`; `getString`, `getInt`, `getLong`, `getFloat`, `getBoolean`, `contains` and `getAll` each call it. So `AppSettings.load` on a worker moves the wait and nothing else, as the proposal says; "last, if the trace shows it" is the right order. Also found: `AppSettings.load` **writes** on a migration (`loadCaches`: `p.edit().putLong(KEY_CACHE_BUDGET_MB, it).apply()`), and three more preferences files load on the main thread before the first frame (`LastFix.get`, `SavedView.get`, `Updater.start`'s `prefs().getString("acknowledgedTag")`, `Updater.kt:93`). | AOSP `core/java/android/app/SharedPreferencesImpl.java` (main); `AppSettings.kt:63-88, 506-518`; `MapViewport.kt:136-141`; `Updater.kt:89-94` |
| `StartupTrace.mark` ignores marks off the main thread | Verified: `if (Thread.currentThread() === mainThread) a.window.mark(...)`. | `StartupTrace.kt:127-130` |
| The first composition reads `app.tiles`, `FirstRun.noPack`, `OnlineService.get`; `startCamera` reads `tiles` through `packView` | Verified at the lines given (`MainActivity.kt:87`, `FirstRun.kt:109`, `FirstRunCard.kt:86`, `MainActivity.kt:123, 133`). **Incomplete:** the same `remember(tick)` block of `FirstRunCard` also calls `OnlineService.get(app).region` (`FirstRunCard.kt:75`) and `PackManager.get(app)` (`FirstRunCard.kt:77`), on the main thread at composition. `PackManager.get` is a lazily constructing singleton whose constructor does two `mkdirs()` (`PackManager.kt:31`, `PackDownloader.kt:69`). | the files named |
| `OnlineService` is built on the main thread through `TileService`'s `online`; `resumePending` reads the state files on the caller's thread | Verified: `TileService.kt:306-312` (`OnlineService.get()` inside the `online` initialiser, with three marks of its own); `PackDownloader.kt:273-277` (`stateDir.listFiles` and `f.readText()`). | the files named |
| `Deferred.getCompleted` is experimental and throws `IllegalStateException` when not complete; `kotlinx-coroutines-core` is Apache-2.0 and multiplatform | Verified: `@ExperimentalCoroutinesApi`, "throws IllegalStateException if this deferred value has not completed yet", "designed to be used from `invokeOnCompletion` handlers"; `LICENSE.txt` is Apache License 2.0. Multiplatform taken from the library's own documentation, not re-fetched. | kotlinlang.org API docs; `Kotlin/kotlinx.coroutines` `LICENSE.txt` |
| App Startup runs initializers on the main thread | **The page does not say.** It mentions no thread at all; it does say initializers run through one `InitializationProvider` content provider, that `dependencies()` orders them, that lint rules exist (`lintDebug`), and nothing about cycles. The belief holds by mechanism (a content provider's `onCreate` runs on the main thread during process bind), not by the page. The proposal's "unproven" is the honest reading; keep it. | developer.android.com/topic/libraries/app-startup |
| InitSpark passes no values between stages | Verified: `execute()` returns nothing; `needs` is a set of keys that only orders; MIT (2023, one author); 46 commits; JVM, Android, iOS from `commonMain`. | github.com/ktomek/initspark README |
| `shared` has no `kotlinx.coroutines`; its only commonMain dependency is the SQLDelight runtime | Verified: `commonMain.dependencies { api(libs.sqldelight.runtime) }` only. Targets are `jvm()` and `androidTarget` (when an SDK is present); **no iOS target exists**; the build file says "iOS comes later". | `shared/build.gradle.kts:6-30` |
| A runner can be written in pure commonMain with no new dependency | Qualified. Kotlin 2.2.0 here. `kotlin.concurrent.Volatile` (since 1.9, no opt-in) is already used in commonMain (`SourceIo`, `PaceFit`, `RemoteTiff`, `Geoids`, `AustriaEditions`); `kotlin.concurrent.atomics` (`AtomicInt`, `AtomicReference`) is `@ExperimentalAtomicApi` with `RequiresOptIn.Level.ERROR` in 2.2; there is no `synchronized` in commonMain. The project's pattern for a lock in commonMain is a lambda the platform supplies, `lock: (() -> Unit) -> Unit` ("on the JVM `{ block -> synchronized(obj) { block() } }`", `DriveOverlay.kt:50-60`, `TiledGraph.kt:185`). The proposal's `LaunchThreads` has a latch but no lock; see objection 7. | `gradle/libs.versions.toml:2`; `shared/src/commonMain/**`; kotlinlang.org stdlib docs |
| The T240 allow file holds 28 objects; `PackView.activatedAt` exists; `Tracking.WAIT_MS` is 30 s; T242's progress bound is 60 s | Verified: 28 non-comment lines in `androidApp/src/test/resources/compose-state-prepare-allow.txt`; `MapViewport.kt:185`; T229's merge body ("waits up to 30 s"); `TileCache.awaitReady`'s `WAIT_STALL_MS` and T242's body. | the files and the merge bodies 4690f260, 0e5ce834 |
| Every `file:line` the proposal cites | All checked resolve to the code described (`DystopoApp.kt:43-91`, `MainActivity.kt:54-105, 213-260, 69, 87, 123, 132-138, 235-236`, `Tracking.kt:906-923, 989-1038`, `TileCache.kt:66-69, 258, 273`, `StartupTrace.kt:126-130`). | read |

Not verified: the licences and claims of the prior-art rows other than those above (taken from the survey, which
itself marks most as "not stated").

## The stage table against the code

The table is a fair reading of `onCreate` with these differences, each carried into an objection below.

- **Names are not the marks, one to one.** Rows merge marks (`the Compose state's prepare` + `Tracking.prepare`;
  `MapView` + `mapView.onCreate`; `links and intents` + `setContent` + `permissions`; `startCamera` + `view from
  <source>`), one row splits a mark (`style-prime, watchdog, reports and cache budget start` becomes two rows), the
  `TileService` row covers four marks (`TileService`, `OnlineService.get`, `OnlineService.tilesFor`, `TileService's
  rest`) and the `OnlineService` row covers four inner ones (`OnlineService: TileCache`, `after TileCache`,
  `RegionDownload and Areas.attach`, `the rest`), and `ContourCache.collectGarbage` has no row.
- **The table stops at `permissions`, the first frame does not.** Between `MainActivity.onCreate` and the first frame
  the main thread runs `onStart` (`mapView.onStart`; `MapOrientation.setForeground(true)`; `startDeviceLocation`, which
  is `app.map.setForeground(true)`: the location manager, a binder call) and `onResume` (`mapView.onResume`,
  `MainWatchdog.resumed`), then the first traversal; `StartupTrace` marks all of them (`MainActivity.kt:151-168`,
  `StartupTrace.kt:156-179`). None is a stage.
- **The `beforeStyle` reader runs on the main thread.** `map.attach`'s `styleJson` lambda is called inside
  `getMapAsync { ... val style = styleJson() ... loadStyle(style) }` (`MapController.kt:192, 220-223`), a main-thread
  callback, and it is `{ app.tiles.style() }`.
- **`Updater.start` reads preferences** on the main thread (`Updater.kt:93`); the row says "starts its thread".
- Otherwise the rows match: `Tracking.start` is a thread from the main thread; `BundledAssets.install` is package
  info plus a marker read, and a full asset copy after an update (`BundledAssets.kt:26-54`); `PackSet.open` seeds the
  units (a preferences write, `AppSettings.seedUnits`) and `collectContourGarbage` catalogs again on the main thread
  then deletes on `contour-cache-gc`; `Reports.start` installs the crash handler and reads exits on a thread;
  `CacheBudget.start` runs on its executor, where it calls `OnlineService.get(app)` (a construction race today if
  `OnlineService` were not already built); `Routes(this)` and `LocalServer.start()` bind sockets on the main thread.

## The case against the recommendation

**For C.** Both freezes the owner met were found by T228's trace and fixed by one hand-moved step each; each fix's
readiness is sixty lines shaped to its work (a latch for a build, a progress bound for a walk). A generic slot
flattens that shape, a runner puts `Launch.run` frames into every future freeze report's main stack, and step 1 is a
re-plumbing that by its own measure changes nothing the owner can see. The wave 32 budget already gives the
regression guard by mark, and `StrictModeCounts` (debug builds, counts main-thread disk reads by app frame,
`debug/StrictModeCounts.kt`) attributed to the mark in force would give the disk-on-main guard without a runner. C
plus the budget plus StrictMode-per-mark is most of A's safety at a fraction of the cost and keeps one description of
the launch (`.claude/rules/docs.md`: "the code wins").

**For B.** `kotlinx.coroutines` is already on the app's classpath and in `MainActivity.kt` itself (`Dispatchers`,
`withContext`, lines 23-24). A coroutine graph in `androidApp` needs no runner, no `LaunchThreads`, no answer to the
commonMain concurrency question, and `runTest` proves an order with virtual time. The proposal's dependency argument
against B bites only if coroutines enter `shared`; kept in `androidApp` it costs nothing, and the only counter is "iOS
gets nothing", when there is no iOS target and `docs/PLANNING.md` puts iOS after a sync server and a desktop client.

**Where that leaves A.** A's real value is one declaration that a test, the trace and the budget all read; B's real
value is no runner to write. The proposal's last "What would change" bullet already names the shape that takes both:
the list and its validator in commonMain (pure data, no concurrency), the runner in `androidApp`. The survey
recommended the same ("A in a small form, with B as its engine"). The proposal should make that the default rather
than the fallback; see the recommendation.

## Hidden coupling

- **The launch budget's names.** Decided here, as the proposal says; but objection 1 shows the table renames and
  regroups the marks, so the budget's ceilings and the per-release table would key on names that break the before and
  after comparison the order of moves depends on. The budget idea also assumes the emulator's figure per mark; a
  stage that becomes `waiting for <stage>` on the main thread changes what that mark's ceiling means (a wait, not
  work).
- **The trace format.** `dystopo-report 1`, `.startup-history` and `topFreezeMark` carry the current names on the
  owner's phone; spans are additive, renames are not. A `waiting for <stage>` mark would be sampled as a `WAITING`
  main thread on a latch; the lock-dump path (`wantsLockDump`, `StartupText.likelyHolder`) fires only on `BLOCKED`,
  so the report would name the latch, not what the worker was doing (objection 12).
- **The first-run card's F1 to F4.** F2 fixes a tri-state for `shown.message` and a derived stored key (the primary
  pack's box) that the `MapViewport` and `PackView` code then carry for every pack activation; F1 first then F2 means
  two tasks touch the first composition's `shown`. Acceptable, and the proposal says so.
- **iOS.** `LaunchThreads` as the platform seam is right; the gates `BEFORE_FIRST_FRAME` and `BEFORE_STYLE` are the
  app's, not Android's, and have iOS equivalents. No objection beyond objection 7 (which primitives the runner uses).
- **The 28 unprepared objects (T240).** "Become prepare stages as they are touched" keeps them on the main thread
  before the gate, consistent with the UI-thread rule. Fine.

## Refusals and lifts

| Mechanism | Who lifts, from where, while running | Message says so? |
|---|---|---|
| `get()` on the main thread before the gate: a `check` fails | Nobody at run time. In a debug build the device tour finds it; in a **release build it is a crash at launch** that only an app update lifts. T229 accepted this for one value with four known readers; the generalisation puts it on every slot, including the Compose reads the proposal admits the test cannot see. | It names the value, gate and caller; it does not say how to proceed, and in release there is nothing to do. Objection 4. |
| `await()` off the main thread: 30 s fixed or 60 s without progress, a `TimeoutException` | No route changes the bounds (`POST /debug/startup/thresholds` covers the trace's thresholds only); nothing in release. After it "the gate passes with the failure recorded" and "the per-stage retry where one exists" is undefined, so what the user sees (a paper map with no tiles? for how long? does `reload` repair it?) is unstated. | Names the caller and the ms; no lift. Objection 5. |
| `awaitGate(BEFORE_FIRST_FRAME)` on the main thread, bounded | Same bounds; on timeout the main thread proceeds without the value, and the first composition's `get()` would then fail the check above: a timeout becomes a crash. | Objection 5. |
| The JVM test's deny rule (DISK or BINDER on MAIN before the gate) | The allow file with a reason per line, as T240's; documented in the proposal. | Yes. No objection. |
| `POST /debug/launch/delay` | Debug builds only, by design; T229's and T242's routes kept as aliases. | Yes. No objection. |

## Pre-mortem (2036)

The list in `Launch.kt` still says nineteen stages and the app has sixty initialisation sites. Bodies grew inside
their bindings (the `PACKS` binding also seeds the units, writes preferences and may trigger a reload), new work
arrived as `LaunchedEffect`s and lazily constructed singletons (`OnlineService.get`, `PackManager.get`,
`ContourCache.get`) built by whichever thread touched them first, outside any stage, and the JVM test stayed green
because it reads declarations, not code; `StrictModeCounts` was never tied to the stage in force, so nobody saw the
declarations drift. Freeze reports from the field named `waiting for tiles` over a latch's stack, so each one still
needed a session to find the slow pack. The iOS runner was never written, and the commonMain placement cost every
change an `expect`/`actual` round. The lines that should have caught it: "What the test cannot see: an unlisted read
of a value in Compose code; `get()`'s check catches that at run time", which concedes that the run-time check is the
real guard and gives it no observable form in the build the owner runs; and "every stage declared where it runs now
(no move)", which has no rule that work outside a stage is a failure.

## Objections

Blocking: none. Serious: 1 to 7. Minor: 8 to 14.

1. **Serious. The names are not the marks, and the budget keys on them.** Evidence: the stage table above (merged,
   split and missing marks) against the "Names" line of the common contract and the `StartupTrace.mark` calls in
   `DystopoApp.kt`, `MainActivity.kt`, `TileService.kt:308-312`, `OnlineService.kt:40-53`. Resolve: a stage owns one
   or more marks (`Stage(name, marks = listOf(...))`), the marks are left exactly as they are, the budget's ceilings
   and the trace's timeline stay keyed on marks, and the stage spans are a second table in `GET /debug/launch` and
   the report. Any rename is listed in the ADR with the old name kept as an alias in the budget's file.
2. **Serious. The first-frame gate is misdefined and the table stops short of it.** The proposal defines
   `beforeFirstFrame` as "the activity's `setContent` returns a first frame"; `setContent` returning draws nothing,
   the first frame comes after `onStart`, `onResume` and the first traversal (`StartupTrace.resumed`, the
   Choreographer callback), and `onStart` makes a binder call (`startDeviceLocation`). Resolve: define two things
   apart: the gate the main thread waits at (`BEFORE_ACTIVITY`, before `startCamera`, where `awaitGate` is called) and
   the platform's first frame (measured, never waited for); add the `onStart` and `onResume` marks to the table as
   MAIN stages with their work (`startDeviceLocation`: BINDER), so the budget and the test see them.
3. **Serious. The `beforeStyle` reader is on the main thread, so the gate as written is a main-thread wait on disk.**
   Evidence: `MapController.kt:220-223` runs `styleJson()` in the `getMapAsync` callback; `MainActivity.kt:69` passes
   `{ app.tiles.style() }`. Under the contract ("the main thread waits only at a gate, only for a stage that gate
   needs") the pack opening moved off the main thread returns as a wait at the style, a few seconds later. Resolve:
   state that `BEFORE_STYLE` is never a main-thread wait: the style is built on the `tiles` stage's worker when it
   completes and posted, exactly as `DystopoApp.showStyle` does for a reload (`DystopoApp.kt:183-186`); `style-prime`
   then folds into that stage; `MapController.attach` takes a style-ready callback instead of a lambda it calls.
4. **Serious. The generalised `check` is a launch crash in release builds with no lift.** Evidence: the refusals table;
   T229's `TrackerReady.await` throws from `check` in every build; the proposal's own admission that an unlisted
   Compose read is caught "at run time on the first launch that hits it", which on the owner's phone is a crash at
   the first frame. Resolve: keep T229's rule in substance (never a silent null) but split it by build: debug builds
   fail the check (the device tour and the sweep catch it); release builds make the read a bounded, traced wait
   (`waiting for <stage>` with a new report reason, `mainThreadRead`, naming the caller), so the owner's next Startup
   report names the site and the app still draws. Say in the ADR that this departs from T229's one-site ruling and why.
5. **Serious. The wait bounds have no lift and the timeout's outcome is unstated.** Evidence: the refusals table;
   "the per-stage retry where one exists". A phone opening many PMTiles headers on slow storage can legitimately pass
   30 s. Resolve: per slot, the list declares `onTimeout = NEXT_READER_BUILDS | RELOAD_REPAIRS | NONE` and the ADR
   says what the user sees for each (`tiles`: the map draws, the style loads when the slot publishes, no error; a
   timeout is a report reason, not a failure); the main-thread gate wait at `BEFORE_ACTIVITY` is bounded only to keep
   the watchdog honest and its expiry proceeds, traced; `POST /debug/startup/thresholds` gains the two bounds for
   debug builds.
6. **Serious. Step 1's measure cannot fail.** "The first frame's median unchanged within the emulator's spread": T229
   measured medians 5456 against 5519 ms with spreads of 4652 to 6364 ms on one boot, and T242's branch read 3841 to
   7461 ms at one file count; a 100 ms runner would vanish in that. Resolve: measure the runner directly from
   `GET /debug/launch`: the sum over stages of (started minus ready-to-start) and the sum of main-thread stage time
   before the gate, over the budget's launches, bounded by the proposal's own 20 ms; the first-frame median stays
   as the secondary, as T229's brief put it.
7. **Serious. The commonMain runner's concurrency primitives are unstated, and the cost rests on them.** Evidence:
   the verification row above (no `synchronized` in commonMain, common atomics experimental with an error-level
   opt-in in Kotlin 2.2, `@Volatile` and a lock-as-lambda the only patterns in use); `LaunchThreads` has a latch but
   no lock, and the slot's three reads plus the runner's done-set and failure record need one. Resolve: either add
   `lock: (() -> Unit) -> Unit` to `LaunchThreads` (the `DriveOverlay` pattern) and say the slot is a `@Volatile`
   value plus the platform latch, or, the recommendation below, keep the list and validator in commonMain as pure
   data and put the runner in `androidApp` on the coroutines the app already has, which also deletes the
   "bounded wait" and "latch" code the proposal would otherwise write by hand.
8. **Minor. The declared `work` set is unverified, and the run-time verifier already exists.** `StrictModeCounts`
   counts main-thread disk reads by app frame in debug builds. Resolve: attribute each violation to the stage in force
   (`GET /debug/launch` gives per-stage counts); the device tour fails on a MAIN stage before the gate with a count
   over zero. This is the answer to "what the test cannot see" and is cheaper than C's lint, which stays as the
   source-level backstop.
9. **Minor. The first composition's reader list is incomplete.** `FirstRunCard.kt:75, 77` read `OnlineService.region`
   and `PackManager.get(app)` at composition; `PackManager.get` constructs on first touch (`mkdirs` twice). Resolve:
   list `downloads` (or a `PackManager` slot) among the first composition's reads; under F2 the card reads `peek()`.
10. **Minor. Two rows understate their work.** `Updater.start` reads preferences (`Updater.kt:93`); `AppSettings.load`
    also writes on a migration and runs `PaceSettings.activate()`. Resolve: the rows say so; the SharedPreferences
    claim's wording changes from "a thread of its own" to "the system's load executor".
11. **Minor. The `OnlineService` and `CacheBudget` singleton races.** `OnlineService.get()` and `PackManager.get()` are
    build-on-first-touch singletons (`CacheBudget.start`'s executor calls `OnlineService.get(app)`); once their
    stages move to workers, a reader on another thread may build them instead, outside the stage and its span.
    Resolve: a bound stage's body is the only constructor; the singletons' `get` becomes the slot's `await()`.
12. **Minor. A main-thread wait is sampled as a latch, not as the worker's work.** The lock-dump path fires only on
    `BLOCKED`; a `waiting for <stage>` mark would show `LockSupport.park`. Resolve: while a wait mark is in force,
    the trace's sample dumps the awaited stage's worker thread (the span names it), under the existing
    `MAX_LOCK_DUMPS`.
13. **Minor. The iOS argument carries more weight than the facts give it.** No iOS target exists and PLANNING places
    iOS after two other clients. Resolve: the ADR justifies the commonMain list by the JVM test and the one
    declaration, with iOS a side benefit, and takes the proposal's own "no iOS app is planned" bullet as the default
    for the runner's home.
14. **Minor. App Startup's thread.** The docs page says nothing about threads; the proposal's "unproven" is right and
    should stay; the row's "runs on the caller, the main thread, from a content provider" is by mechanism, not by the
    page.

Not objections: the exit-cost and moving-parts lines are specific to this design (the bindings are the old lines;
one runner, one interface, one route pair, the spans), the "What would change" bullets are falsifiable (5 s and one
second on the phone trace; 20 ms of runner cost; about 40 stages), and the no-setting line follows ADR 0044 and T229.

## Recommendation

Take A's declaration and B's engine, which the proposal's last fallback bullet and the survey both describe: the
stage list, the slots' typed keys and the validator in `shared` commonMain as pure data with the JVM test over
them; the runner in `androidApp` on `kotlinx.coroutines` (already on its classpath), each slot a `CompletableDeferred`
behind the three reads, the main thread's one wait at `BEFORE_ACTIVITY` before `startCamera`, `BEFORE_STYLE` a
worker-side completion that posts the style, and T229's rule split by build (a failed check in debug, a traced wait
and a report reason in release). Keep the marks exactly as they are and let a stage own several; add the `onStart`
and `onResume` steps to the table; declare `onTimeout` per slot; measure step 1 by the runner's own overhead; tie
`StrictModeCounts` to the stage in force and keep C's lint as the source backstop. F1 first and F2 on the owner's word
is right. Confidence: high that the serious items must be answered before the ADR (each is a definition the code
contradicts today); moderate (about seven in ten) that the runner belongs in `androidApp` rather than commonMain,
the one point on which the proposal's recommendation and this review differ, and which the proposal already lists as
the condition "no iOS app is planned". Residual risks if all of this is taken: declarations drift from bodies
(objection 8's StrictMode tie is the only guard); the runner's frames in freeze reports; and the ten-year gain rests
on wave 32's budget actually failing the sweep when a mark grows, which is that task's to prove.
