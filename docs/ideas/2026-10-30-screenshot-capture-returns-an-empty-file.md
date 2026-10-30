---
date: 2026-10-30
kind: bug
---

# The device scripts' screenshot capture returns an empty file now and then

On the second PC the sweep's `recenter`, `update` and `upgrade` parts failed on 2026-10-30 with
`ValueError: not a PNG` at their first screenshot after a fresh install (a 0-byte
`01-install-previous-failed.png`), while the app itself was fine (`alive: ok`); the same parts
passed on a rerun, except `upgrade`, which hit it twice in the rerun sweep (`out/sweep-wave22-b`,
`out/sweep-wave22-c`) and had passed in the full sweep. T173's implementer saw a truncated
screenshot transfer (`IncompleteRead`) during a device tour while a `check` build ran beside it.
Anchor: `tools/android-ui.py` (`still()`, `dev.frame()`) and the capture helper the device tour
shares. Reproduce: a fresh API 35 AVD at 3 GB, install an APK, capture at once. Likely the capture
is read before the emulator has finished writing it, or the adb transfer is cut under load; the
fix is a retry inside the capture helper when the file is empty or not a PNG, and the part should
not fail on one empty frame.

Plan: one tools-only pass, no app change. The cause, read from the code:
- `Device.screencap` (`tools/android-firstrun.py`) writes whatever `adb exec-out screencap -p` returned, with no check of
  the exit code, stderr or bytes, so an empty transfer becomes a 0-byte PNG.
- `Device.frame` falls back from the raw capture to one PNG capture. An empty one raises `ValueError: not a PNG` in
  `android-check.py`'s `_decode_png_stdlib`, which fails the step.
- `Run.step` (`tools/android-ui.py`) takes its failure screenshot through the same unchecked `screencap`, which is how
  `01-install-previous-failed.png` came out empty.
- `Server._open` (`android-check.py`, the device tour's HTTP path to `/screenshot`) retries a 401 and a lost connection,
  not `http.client.IncompleteRead`.
Change: one capture helper on `Device` runs the capture and checks the exit code and the PNG signature (or, for a raw
capture, the header's width × height × 4 against the length). It retries up to four times after 0.5, 1, 2 and 4 s, logging the
code and stderr. `screencap`, `frame` and the parts' `still()` use it. When the retries run out it raises one error that names
the attempts, and it never writes a 0-byte file. Each part's report counts `captureRetries`. `Server._open` retries a GET once
on `IncompleteRead`; a POST is never repeated. Tests in `tools/test_android_ui.py`, with a fake adb that returns empty, then
truncated, then good bytes. Verify: the `recenter`, `update` and `upgrade` parts, three runs each, on a fresh API 35 AVD at 3 GB,
one of them with a `./gradlew check --no-daemon` beside it (the load under which T173 saw the truncation); the report says how
many retries fired. Out of scope: the app's `/screenshot` route.
