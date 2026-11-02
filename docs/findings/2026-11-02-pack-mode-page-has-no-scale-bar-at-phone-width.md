---
date: 2026-11-02
status: resolved (T207)
area: tools / demo-check, devtool serve pack mode
---

# The pack-mode page has no scale bar at phone width, because it has no scale control at all

## Question

The post-wave sweep of 2026-11-02 failed one of 25 parts' views: `demo-serve`'s `phone-paradise-z13`
(390x844 against `devtool serve` in pack mode) reported T205's `scaleClear` as "no scale bar", with the
report's `units` and `scale` null, while the same view against `demo-server.py` had the bar at
[10,812,59,834]. Was the page's scale control created late, did T200's units re-seed wait on something that
never came in pack mode, or did the panel collapse path break?

## What was tried

- Built `devtool` and ran `serve --port 8698` (pack mode, the Rainier pair, the sweep's arguments), then
  `demo-check.py --views-file` with only the tour's `phone-paradise-z13` line: it failed the same way, so the
  failure is deterministic, not timing.
- Fetched `/` from that server: 718 bytes, titled "Dystopo phone style" (`PackServer.kt`), a bare MapLibre
  map over `/style.json`: no panel, no `window.dystopoUnits`, no `window.dystopoScale`, no scale control, no
  console or page errors. It is the stand-in for the phone's map, not the viewer page.
- Read `tools/demo/index.html`: the units seed, the geolocation start and the panel collapse (T200) and the
  `ScaleBarControl` all live in the viewer page, which `devtool serve` in pack mode never serves (`/tools/demo/`
  is a 404 there). So none of the candidates in the task text applied; the hypotheses were about the wrong page.
- `units` and `scale` were null for the reason they always are on that page: `demo-check.py` reads
  `window.dystopoUnits` and `window.dystopoScale`, which only the viewer defines.

## Result

Cause: T205 made `scaleClear` run on every view of 520 px or less, and the tour's `phone-paradise-z13` line
is the only such view; it passed against the viewer page (`demo-static`) and could never pass against the
pack-mode stand-in, which has no scale bar to find. The viewer page was correct all along (bar at bottom
left, the collapsed panel's title button at top left, the compact credits).

Fix, in `tools/demo-check.py` (a check fix, not a page fix; the page was not at fault and waiting would not
help): `scaleClear` runs where the page has a scale control (`window.dystopoScaleBar`, which the viewer
exports for the check) and reports `{"ok": true, "skipped": ...}` on a page without one. The check keeps
failing a viewer page whose bar is missing or covered. After it, the full `tools/demo-tour.txt` passes both
ways: 21 of 21 views against `devtool serve` (pack mode) and against `demo-server.py` (bar box [10,812,59,834]).

Not done: giving the pack-mode stand-in a scale bar or the panel. It is the phone style's reference page, and
the phone's own scale bar is checked by the device tour; the task's wish for a bar there was built on the
wrong premise and is a product choice if the owner wants it.
