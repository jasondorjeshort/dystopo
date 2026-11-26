---
date: 2026-11-24
kind: chore
related: [2026-11-07-search-and-routes-find-nothing-outside-a-pack.md, 2026-11-10-real-phone-check-as-a-one-tap-self-test.md, 2026-11-08-the-owners-phone-in-the-sweep.md]
---

# A phone capture script for a release build over USB

Source: coordinator (claude-opus-5-5), after the ideas triage of 2026-11-24; offered and accepted by the owner; not sanity-checked.

The blank Panorama on the owner's phone (v0.1.43-alpha, release build) waits on a USB capture, and each such capture
is a hand-typed set of `adb` commands. The idea: one script in `tools/` that, given the phone's serial, clears and
records `adb logcat -s Dystopo DystopoAssets` to a file, takes `adb exec-out screencap` frames on a key press while the
owner taps through the views, records the app's version (`dumpsys package`), and zips it all for an agent to read.

Research before recording (read-only agent, 2026-11-24): `run-as` does not work on a release build, so the script
cannot list the maps on the phone (the one-tap self-test's Plan covers that from inside the app);
`tools/android-ui.py` and `tools/android-check.py` already take `--serial`, and the sweep has `phone-*` parts. The
agent recommended a checklist instead; the owner chose to record it.

## Open

- A new script, or a `capture` subcommand of `tools/android-ui.py`.

Plan: one pass, a new script, since `tools/android-ui.py` drives the app by taps and this one only watches while the
owner taps: `tools/android-capture.py --serial <s> [--out <dir>]`. It records `dumpsys package` (versionName,
versionCode, the signer's digest), `adb logcat -c` then `adb logcat -s Dystopo DystopoAssets` into a file in the
background, one `adb exec-out screencap -p` frame per Enter (with a typed note, empty allowed) until `q`, then stops the
logcat by its own process handle and zips the folder under `out/capture-<stamp>/` (gitignored). The zip is scanned with
`java tools/PrivacyCheck.java scan` and the result printed, so the agent who reads it knows; nothing is sent anywhere.
Tests in `tools/test_android_capture.py` (argument parsing, the folder's shape with a fake adb), run by
`tools/run-tool-tests.py`; a line in `docs/reference/android-phone-over-adb.md`. Verify: on an emulator with a release
build installed from the published draft, three frames and the log captured, the zip read back. Out of scope: listing
the maps (needs `run-as`; the one-tap self-test's), driving the app, and a phone run (the owner's).
[no-check: the script and its test file are what the pass adds]
