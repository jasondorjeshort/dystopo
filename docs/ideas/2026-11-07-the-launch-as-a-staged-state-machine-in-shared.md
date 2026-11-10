---
date: 2026-11-07
kind: feature
related: [2026-11-07-a-launch-budget-per-mark-enforced-by-the-sweep.md]
---

# The launch as a staged state machine in `shared`, with its dependency order as code

The owner, 2026-11-07: "This is really the solution; documenting the correct launch order lets us do everything
in different threads with the correct synchronization checks."

The launch inventory of wave 29 (section 8) already states a dependency order read from the code: what must
exist before the first frame (the Compose state's `prepare` calls, `MapLibre.getInstance`, `AppSettings.load`, a
`tiles` value, the camera), what only before the map style loads (the loopback server, the token, a complete
`TileService`, the bundled assets, the pack metadata), and what only later (reports, the cache budget, pending
pack downloads, the updater, the corridors, the contour cache's walk). Today that order is implicit in
`DystopoApp.onCreate`, `MainActivity.onCreate` and the first composition (`MainActivity.kt`, `MapScreen`), each
step on the main thread, and T229 moved one step by hand.

Wanted: a pure, tested `Launch` in `shared` (commonMain): named stages, each with the stages it needs and the
gate it satisfies (`beforeFirstFrame`, `beforeStyle`, `later`), the Android app scheduling each on a thread the
stage declares (main for Compose state and views, a worker for disk and binder work) and awaiting a gate where
it must; the JVM test proves that no stage marked as disk or binder work precedes the first-frame gate on the
main thread, that every main-thread reader of a value waits on its gate, and that the order is a DAG. T228's
marks become the stages' own, so the trace and the launch budget (sibling idea) read the same names. The
remaining inventory items (the online tile cache's directory walks, the bundled-assets copy, pack opening,
`OnlineService`'s construction) become stages moved by declaration, each with the gate it answers to. An iOS
app, when it comes, runs the same `Launch`.

## Open

- A design with a critic before building (the `proposal` skill): the stage shape, how a stage publishes a
  value the main thread reads (a placeholder "no packs yet" `tiles` value shows the first-run card briefly, a
  visible side effect to decide), and the `Tracking.of` rule T229 set (fail by name on the main thread before
  readiness) generalised to every gate.
- The order of moves after the design, each measured by the launch budget: the owner's phone trace names the
  first.
- A snapshot of app state for a faster restart is a different idea and is not this one: the owner notes it
  "can also lead to synchronization issues so we have to be careful".

Plan: decided in ADR 0061 (T245, 2026-11-09); build by its order of moves, step 1 first (the list, validator, runner
and bindings with no move, measured by the runner's overhead), and delete this file on step 1's branch.
