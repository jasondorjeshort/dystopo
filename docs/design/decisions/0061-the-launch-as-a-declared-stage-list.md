# 0061. The launch as a declared stage list in `shared`, run by a coroutine runner in the app

Date: 2026-11-09
Status: accepted

Record: proposal `../proposals/0061-the-launch-as-a-staged-state-machine.md` and
`../proposals/0061-critic-review.md` (0 blocking, 7 serious, 7 minor, all taken). The first frame's visible choice
(F2 below) is the owner's and is not decided here.

## Context

The launch's order is implicit in `DystopoApp.onCreate`, `MainActivity.onCreate`, `onStart`, `onResume` and the first
composition, all on the main thread except the threads some steps start. The owner's phone (Android 12) froze at
launch twice: the first composition raced a thread for the tracker (T229) and the online tile cache walked tens of GB
on the main thread (T242). Each fix moved one step by hand with its own readiness rule (`TrackerReady` in
`tracking/Tracking.kt`; `TileCache.state`, `whenReady`, `awaitReady`). The owner, 2026-11-07: "documenting the correct
launch order lets us do everything in different threads with the correct synchronization checks."

Verified (the proposal's Claims to verify, checked by the critic at e184b663): `StartupTrace.mark` counts only the
main thread's marks (`StartupTrace.kt:127-130`); the style's JSON is read on the main thread in the `getMapAsync`
callback (`MapController.kt:219-222`); the first composition reads `app.tiles`, `OnlineService` and its `region`,
and `PackManager` (`MainActivity.kt:87`, `firstrun/FirstRunCard.kt:75, 77, 86`); `OnlineService` is built on the main
thread through `TileService` (`TileService.kt:306-312`); `SharedPreferences` loads on the system's load executor and
every getter blocks until loaded, so moving `AppSettings.load` moves only the wait; MapLibre's `checkThread` throws
only in debuggable builds. `shared` has no `kotlinx.coroutines` and no iOS target; commonMain has no `synchronized`
and its atomics need an error-level opt-in in Kotlin 2.2; the Android app has `kotlinx.coroutines` already.
Prior art (survey of 2026-11-09, in the proposal): startup graphs declare a name, needs and a body returning a
value, a thread flag per stage and a separate bounded main-thread gate; no library combines threads, a first-frame
gate, value passing and commonMain, so none is adopted.

## Decision

**Where the parts live.**
- `shared` commonMain, pure data (package `launch`): `Stage(name, marks, needs, runs = MAIN|WORKER, work =
  setOf(DISK, BINDER, NATIVE, SOCKET), gate = BEFORE_ACTIVITY|BEFORE_STYLE|LATER)`; the values as typed keys
  `Value<T>(name, gate, onTimeout = NEXT_READER_BUILDS|RELOAD_REPAIRS|NONE)`; the list (`Launch.stages`); a pure
  validator. No concurrency in commonMain.
- `androidApp`: the runner (`app/launch/`) on `kotlinx.coroutines`: each bound stage starts when its needs are done,
  on the main thread or a worker dispatcher as declared; each value is a slot, a `CompletableDeferred` behind three
  reads. `DystopoApp.onCreate` binds a body to each stage name; the bindings are today's lines.
- The runner moves into commonMain only when an iOS target exists in `shared` (then on `kotlinx.coroutines` there,
  with the dependency checklist the proposal answers).

**Stages and marks.** A stage owns one or more T228 marks; the marks stay exactly as they are (the trace's timeline,
report titles, `.startup-history` and the launch budget's ceilings key on marks). Every mark belongs to exactly one
stage. The stage table of the proposal (19 stages, `onStart` and `onResume` included) is the starting list. A mark
renamed later keeps its old name as an alias in the budget's file.

**Gates.**
- `BEFORE_ACTIVITY`: the main thread's one wait, in `MainActivity.onCreate` before `startCamera`, bounded and traced
  (`waiting for <stage>`); on expiry it proceeds, traced.
- `BEFORE_STYLE`: never a main-thread wait. The `tiles` stage's worker builds the style and posts it, as
  `DystopoApp.showStyle` does for a reload; `style-prime` folds into it; `MapController.attach` takes a style-ready
  callback instead of a lambda it calls.
- `LATER`: nothing waits.
- The first frame is the platform's, measured, never waited for.

**The readiness rule (T229, generalised, split by build).** `peek()` never waits and may be null (the UI, the
routes). `get()` on the main thread before the value's gate has passed: in debug builds a failed `check` naming the
value, its gate and the caller; in release builds a bounded, traced wait with the new Startup report reason
`mainThreadRead` naming the caller, so the app still draws and the next report names the site. This departs from
T229's ruling (a `check` in every build), which was for one value with known readers; for every slot, a missed
Compose reader would be a release crash at launch with no lift. `await()` off the main thread waits bounded: a fixed
bound (30 s, as `Tracking.WAIT_MS`) or a progress bound (60 s without progress, as T242's), the timeout naming the
caller. `onTimeout`: `NEXT_READER_BUILDS` (the tracker), `RELOAD_REPAIRS` (`tiles`: the map stays paper until the
slot publishes and the style is posted, a `reload` rebuilds; a timeout is a report reason, no error shown), `NONE`.
A stage that throws publishes its failure; `await` rethrows it naming the stage.

**One constructor per value.** A bound stage's body is the only place its value is built; `OnlineService.get` and
`PackManager.get` become slot reads, so no thread builds one outside its stage.

**The trace.** `StartupTrace` records a span per stage from any thread (thread, ready-to-start, start, end, the main
thread's wait); while a `waiting for <stage>` mark is in force the samples dump the awaited worker's stack (under
`MAX_LOCK_DUMPS`), not the latch. `StrictModeCounts` (debug builds) attributes each main-thread violation to the
stage in force.

**Tests.** A commonTest over the list: a DAG, unique names, every need declared, every mark in one stage, gates
monotone along needs, no `DISK` or `BINDER` stage on `MAIN` before the first frame unless allowed with a reason
(MapLibre's `NATIVE` stages, `settings` while it stays, `start`'s binder call), every reader needs its writer, and a
seeded simulation that never starts a stage before its needs. An Android unit test: every bound name is declared and
every declared stage bound. The device tour fails on a MAIN stage whose StrictMode count its declared work does not
allow. T240's shape (a source-reading lint with an allow file) stays as the backstop for calls no declaration names.

**Debug routes.** `GET /debug/launch` (each stage's thread, needs, gate, marks, times, wait, failure, StrictMode
count; each gate's time; the runner's own overhead); `POST /debug/launch/delay?stage=&ms=` holds one stage at the
next launch; `POST /debug/tracking/delay` and `POST /debug/cache/hold` stay as aliases; the two wait bounds are
settable in debug builds through `POST /debug/startup/thresholds`.

**No setting** (ADR 0044): readiness is not a preference.

**The order of moves**, each one task measured by the launch budget:
1. Wave 32, first: the list, validator and test, the runner and bindings with every stage declared where it runs now
   (no move); `Tracking`'s and `TileCache`'s readiness become slots; the spans, the routes, StrictMode per stage.
   Measured by the runner's own overhead (sum over stages of start minus ready-to-start, plus the runner's main-thread
   time before the gate), median under 20 ms over the budget's launches; the first-frame median secondary.
2. The moves, in the order the owner's phone trace gives (a Startup freeze report or "Report the last start" on a
   release carrying step 1), else the emulator budget's: `online`, `packs` and `tiles` (F1 first), `assets`,
   `downloads`, the contour cache's second catalog; `settings` last, if the trace shows it.
3. T240's 28 unprepared objects join the prepare stage as they are touched.

**Waiting on the owner (ADR 0031): the first frame without `tiles`.** The move of `packs` and `tiles` is built as
**F1** (the main thread waits for them at `BEFORE_ACTIVITY`; nothing visible changes). **F2** (until `tiles`,
`online` and `downloads` publish, the first composition shows no first-run card, no no-pack line, no position button
and no scale bar; the card appears if there is no pack; `startCamera` reads the primary pack's box from a new
derived preference, ADR 0033) is the proposal's recommendation and is built only on the owner's word. F3 (a
placeholder "no packs" value, the card flashing every launch) and F4 (a directory listing before the gate) are
recorded in the proposal.

Out of scope: a snapshot of app state for a faster restart (the owner's caveat); T242's on-disk index.

## Consequences

Easier: the launch is one list a test reads; a move is a change of a stage's `runs` or `gate`; the trace, the budget
and the report name stages and marks consistently; a missed reader is a named failure in debug and a named wait in
release. Harder: bindings and list can drift (the Android unit test and StrictMode per stage are the guards); runner
frames appear in freeze stacks; the release rule no longer crashes at a misplaced read, so such a read is found only
through reports or debug runs. Cost: about a task for step 1, then a task per move. Maintained: the list, the allow
lines, the aliases of any renamed mark.

Rejected: A as proposed (the runner in commonMain over a platform latch and lock: concurrency code written by hand
where the app has a library, for an iOS target that does not exist); B alone (the order lives in code no test reads
without a second list); C alone (a deny list sees only the calls it names, nothing about order or readers).

Reverse or revisit when: the owner's phone trace on a release with T242 shows the first frame under 5 s cold with no
step over a second (then C with the budget and StrictMode per mark suffices); an iOS target is added (the runner moves
into commonMain); the list passes about 40 stages or needs stages made at run time (a library); the runner's overhead
passes 20 ms median (bindings back to straight calls, the list kept for the test).

Predictions (the trigger scan reads these). The outcome goes on the prediction's own line, which keeps its start.
- Prediction, check when step 1 merges: the runner's own overhead from `GET /debug/launch` is under 20 ms median over
  the budget's cold launches on the sweep's emulator, and the debug build's `get()` check fires at no unlisted
  main-thread read in the device tour after the reader list is completed.
- Prediction, check by 2027-02-28: after the first two moves reach a release, no Startup freeze report or "Report the
  last start" from the owner's phone shows a moved stage's mark over 1 s on the main thread.
