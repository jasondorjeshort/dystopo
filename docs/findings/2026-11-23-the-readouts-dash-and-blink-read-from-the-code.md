---
date: 2026-11-23
status: open
area: android / readout bar
---

# The elevation readout's dash and blink, read from the code, and a rule for both

## Question

`../ideas/2026-11-22-the-elevation-readout-dashes-and-blinks-with-no-explanation.md` (the owner, v0.1.42-alpha,
priority high): what exactly turns the bar's elevation into a dash or makes it blink, and what should the rule be
when the estimator restarts and when fixes come in bursts?

## What was tried

One read-only agent (Opus-class) on `main` at 48bf56fe: the code read, nothing run; eight outside sources fetched
in the run. Not reproduced on a device. Paths: `shared/.../readout/Readout.kt`; `LiveReadout.kt` and `StripText.kt`
under `androidApp/.../app/readout/`.

## Result

**Every path to a dimmed or dashed elevation.**

| State | Trigger | Where | Lasts | Barometer | Test |
|---|---|---|---|---|---|
| Dimmed, caption "no fix N s" | no fix for `gapMs` (the larger of 5 s and 3 fix intervals, so 5 s) | `Readout.kt:542-543`, `:571`, `:673`; caption `StripText.kt:152` | from 5 s into the gap to the very next fix, no hold | not dimmed while the barometer is fused | `ReadoutEstimatorTest.gapDimsAndTheSpikeAfterItIsDropped`; the fused case has none |
| Dimmed, no caption | an activity or profile change (`setProfile` clears the fixes) | `Readout.kt:351-363`, `:543` | to the next fix | not dimmed while fused | none |
| Dash, "no height yet" | a cold start | `LiveReadout.kt:391`, `:229`; `Readout.kt:574` | to the first fix with a usable height | cannot help (no offset) | `StripTextTest` (drawing only) |
| Dash after a jump | a step over 2 km at more than the profile's speed bound drops the estimator (`jumpResets`) | `LiveReadout.kt:341-346` | to a fix with a usable height | cannot help: the offset dies with the filter | none (`LiveReadout` has no unit test) |
| Dash after a clock going back | an input over 60 s older than the latest recreates the filter (`clockResets`) | `Readout.kt:897-920`, `:979` | the same | cannot help | the counter and the climb only |
| Dash after `reset()` | `LiveReadout.reset()`, reached only from `POST /readout/reset` | `LiveReadout.kt:236-252` | the same | cannot help | none |

A usable height is a GNSS altitude with vertical accuracy of 50 m or better (1.5 times the horizontal accuracy
stands in when none is given, so 33 m or better), or on foot a DEM height (accuracy 30 m or better, 3 m/s or
slower, a gentle slope), or a known height set by hand (`Readout.kt:429`, `:859-875`, `:994`). A network fix
therefore keeps the dash up; a GPS fix with altitude ends it at once.

**Why it blinks.** The map asks every provider for a fix each second (`MapController.kt:780`) and a network fix is
dropped within 10 s of a GPS one, so under weak GPS the fixes arrive in bursts; each silence over 5 s dims every
entry until the next single fix. A calibrated barometer stops the elevation dimming at all, so the owner's blink
means the barometer was not fused then. The dim is drawn at two strengths: 0.45 on the bar
(`ReadoutStrip.kt:478`), 0.6 in the glance view (`GlanceView.kt:311`).

**Where the idea's reading was wrong.** The jump's own fix is fed to the new estimator, so a jump onto a GPS fix
with altitude shows no dash; only a jump onto a network or no-altitude fix does. `reset()` never runs on a release
phone. The gap already has a caption ("no fix 8 s"); the unexplained states are the activity-change dim, every
dash, and a dimmed elevation whose `reason` is null. `devtool readout` reads only at fix times, so it cannot show
the dim, and it has no jump logic.

**Reproducing on an emulator (debug build).** Location off (`adb shell cmd location set-location-enabled false`),
`POST /readout/reset`. Blink: `POST /location?lat=46.853&lon=-121.76&ele=1650&accuracy=8` at 1, 1, 8, 1 and 12 s
apart, `GET /readout` every 0.5 s (`bar.fields[elevation].dimmed`, `gap`). Dash: a fix, then within 1 s one 3 km
away with `provider=network` and no `ele` (`inputs.counts.jumpResets` rises, the elevation `hidden`); with `ele=`
no dash. A long gap: `POST /location/replay?stall=70@100`. The route lacks the time and cause of the last restart
and a count of dim toggles.

**Published practice** (fetched 2026-11-23): Android's `Location` reference says the epoch clock "should not be
used to order or compare locations. Prefer getElapsedRealtimeNanos()" (the app compares `fix.time` and the wall
clock, which is what makes "clock went back"); `LocationRequest.Builder` allows updates "grouped with following
updates"; two avionics manuals keep degraded values on screen and flag them (amber text, "DR") rather than blank
them, with no timeout stated; NASA-STD-3001 Vol 2: "There must be an indication when data are stale."; one
open-source map's issue thread asks for the age of the last fix to be shown early; WCAG 2.3.1's three flashes a
second is not the binding limit (the dim toggles once in 5 s or slower). Not established: any app's or handheld's
hold time in seconds.

**The rule recommended** (the coordinator's to take; the visible parts shown to the owner, ADR 0031):

1. A jump moves the position, not the height: restart the horizontal state (fixes, speed, course, the climb
   window) and keep the elevation filter and the barometer offset. No dash, no hold.
2. A clock going back: keep a snapshot of the last height, shown dimmed with reason `held` until the new filter
   has a height or 60 s pass (the climb's own hold and the barometer's suspect hold are 60 s; the figure is a
   guess), then "no height yet". A cold start keeps "no height yet".
3. The dim enters at `gapMs` as now and leaves only on the second fix of a run in which each fix came within
   `gapMs` of the one before, so bursts give a steady dim. Gaps are measured on elapsed realtime. One alpha for
   the bar and the glance view. No fade and no dwell.
4. Every dimmed elevation carries a reason code: `no fix` ("No GPS fix for 12 s; showing the last height."),
   `held` ("Position restarted; holding the last height until GPS gives one."), `no height yet` ("No height yet;
   waiting for a GPS fix with altitude.").
5. In `shared`: a pure latch for the dim, the jump check moved from `LiveReadout` into the estimator, a held
   height with its timeout; tests of bursty fixes (at most one toggle per burst run), steady fixes (never
   dimmed), a 21 s gap, a fused barometer, a 3 km jump on no-altitude fixes (height kept), a clock going back,
   `setProfile`.

## Next

Reproduce the owner's two cases on the emulator before the fix (the task's first step); tune the two-fix release
and the 60 s hold on a recorded weak-GPS trace; check that keeping the height across a jump never leaves a wrong
one (old filter and new GNSS height over 100 m apart: T204's gate and re-anchor should cover it). Which case the
owner saw needs `GET /readout` on the phone while it shows.
