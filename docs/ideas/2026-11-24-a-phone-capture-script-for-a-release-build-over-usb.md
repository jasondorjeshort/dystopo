---
date: 2026-11-24
kind: chore
related: [2026-11-22-at-the-phones-own-position-with-two-packs-the-panorama-is-blank-and-horizon-peaks-never-draw.md, 2026-11-10-real-phone-check-as-a-one-tap-self-test.md, 2026-11-08-the-owners-phone-in-the-sweep.md]
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
