---
date: 2026-10-25
kind: feature
related: [2026-10-25-heap-diagnostics-page-in-release-builds.md, 2026-10-13-battery-efficiency.md]
---

# Track activity profiles, and a recorder that survives bad GPS

Prompted by a recorded flight whose track had a hole in it. The cause was never established: the
recorder keeps no record of why a fix was dropped or when the stream stopped. Today
`TrackRecorder.onFix` drops every fix over `maxAccuracyM` (50 m) and counts it in `rejectedAccuracy`,
which resets on each start and is readable only on a debug build (`GET /tracks`).
`GpsLocationSource` registers one listener and nothing watches it; `TrackingService` is
`START_NOT_STICKY`; nothing asks for a battery exemption. The owner's points (chat, 2026-10-25):

1. **Use poor fixes instead of discarding them.** Store the accuracy with every point and let the
   track algorithm weight by it (a bad fix still bounds where the walker was). Drawing, distance and
   elevation stats must not zigzag on 100 m fixes: smooth or weight them, keep the raw point.
2. **A notification while recording, and leaving the app must not stop it.** The foreground service
   and notification exist; make them hold: restart from a persisted "recording" flag (a force close
   may end it), ask for the battery exemption on the first record, say in the notification when the
   GPS is waiting ("no fix for 40 s, last accuracy 120 m").
3. **Failing GPS hardware** (the owner's phone is seven years old): a watchdog that re-registers the
   listener after N seconds with no fix, a fallback to the network or passive provider while the GPS
   is silent (flagged, low accuracy), and the stall shown in the notification and recorded.
4. **Saner handling overall:** one explicit state the UI and notification show (recording, waiting for
   GPS, paused, stopped by the system), gaps recorded as data on the track (start, length, last
   rejection reason) and survived across launches, so a dropout is explainable afterwards without
   a debug port. The in-app diagnostics page of the heap idea is the natural place to read them.
5. **Flying is a first-class use.** At 250 m/s a 300 s gap rule, a 50 m accuracy cap, a 1 s interval
   and a "moved less than 50 m" test all mean something different; altitude matters (GPS height is
   the only height).

Together: an **activity profile** per track (hiking, biking, driving, flying, custom) that sets the
defaults. Sketch of what a profile carries: max accuracy (and whether worse fixes are kept
down-weighted), fix interval and distance filter, gap and split rule, a speed sanity bound (to drop
teleports), watchdog timeout, whether to fall back to the network provider, elevation source. The
profile is chosen when recording starts (remembered per last use), stored on the track, and changes
only defaults, never behaviour a user cannot see. Not to be confused with `Tracking.Mode`
(`all`/`gps`/`injected`), which is the fix *source* and stays.

## Open

- A schema change: per-point accuracy and a per-track profile and gap list (`.claude/rules/trackdb.md`
  migration steps); GPX export should carry accuracy (`hdop`/an extension) and import tolerate it.
- Weighting method (a simple Kalman or accuracy-weighted smoothing on top of the raw points) and
  whether stats use the smoothed or the raw track; goldens and `TrackStats` tests.
- Whether the interval should relax for flying and driving (battery) and what Android honours.
- Agent-testable: a replay (`POST /location/replay`) with injected accuracy and a stalled stretch
  covers the recorder; the watchdog needs a fake `LocationSource`; the real-phone dropout is the
  owner's.
- The strings and the profile picker (ADR 0031: built in alpha, shown with screenshots).
- Prior art (ADR 0039): a survey of how established track-recording apps handle accuracy, gaps and
  activity types, before the defaults are fixed.

Plan: a benchmark survey first, then two tasks: (a) recorder core in `shared` (accuracy kept and
weighted, gap records, profiles as data, schema migration, tests, replay checks) and (b) the app side
(watchdog, fallback provider, restart and exemption, notification state, profile picker, diagnostics
rows). They share `Tracking.kt`, so different waves or one owner.
