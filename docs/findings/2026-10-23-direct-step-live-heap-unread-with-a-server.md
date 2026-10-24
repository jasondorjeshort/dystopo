---
date: 2026-10-23
status: resolved
area: android / device tour / sweep
promoted_to: docs/reference/android-online.md
---

# The tour's direct step fails to read the live heap when the app has a server

## Question

The wave 18 sweep (`out/sweep-20261023-2206`, `main` at dd0383f1) was green in 23 parts and red in
`device-tour`, both runs, on T127's new direct step: `direct 9/45.865/6.865 mont-blanc-z9: FAIL live
heap - of 192 MB (bound 154), sampled peak 32 MB, same process True, OOM lines 0`. T127's implementer
and verifier had the step green on four fresh AVDs each. Why does the sweep differ?

## What was tried

- `device/tour1.json` of the sweep: `directStep.views[0].live` is `{"liveBytes": null, "error": "no
  onTrimMemory trim recorded"}` and `app.memory` is null; no crash, the process unchanged, 10 of 10
  views and glyphs ok in both runs.
- The two invocations compared: the implementer and the verifier ran `tools/android-check.py` with
  `--server none`; the sweep runs it with `--server` set to its own `serve --live` (the default
  `-DeviceServer live`, `tools/sweep.ps1` lines 12 to 14 and 99).
- The code read: `live_heap` in `tools/android-check.py` sends `am send-trim-memory ... RUNNING_LOW`
  and then reads `GET /online` `direct.memory.recentTrims`; `direct_step` copies `app.memory` from the
  same `direct` object. `HeapGuard` is an application-wide object (`DystopoApp.onTrimMemory` calls it
  whatever the server setting), so the trim itself runs.

## Result

With a server configured the app's `GET /online` reply carries no `direct.memory` block (the
`direct` object describes the no-server path), so the script finds no trim and fails the step although
the heap is healthy (sampled peak 32 MB of 192). The step also measures nothing useful in that mode:
the Mont Blanc z9 view is served by the sweep's live server, not rendered on the phone. The failure is
deterministic and belongs to the harness and the route, not to memory.

## Next

Resolved by T135. `GET /online` `direct` now carries `memory` (the application-wide `HeapGuard` record) with a
server configured too, as `{direct: false, memory}`; the direct step switches the app to no server with
`POST /settings?server=` in the same process (the tour's caches stay), runs the view on the phone's own renders and
restores the server (`serverSwitch` in the JSON), records `skipped` with the reason when the switch does not take,
and prints `live.error` on its console line. On one fresh API 35 AVD (192 MB heap), four full tours, all 10 of 10
views ok and the direct step ok: with `--server` set to a `serve --live` the live heap was 67 and 66 MB (0.35 and
0.34 of the max; sampled peaks 0.76 and 0.82), 130 DEM tiles rendered on the phone, switched and restored both
times; with `--server none` 75 and 47 MB (0.39 and 0.24; peaks 0.65 and 0.62), 231 and 242 tiles. Bound 0.8 (154 MB)
unchanged. The sweep's `device-boot,device-tour` part: see T135's PR. Durable part in
`../reference/android-online.md` (The Java heap).
