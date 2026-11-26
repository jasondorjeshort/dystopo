---
date: 2026-11-26
kind: bug
---

# The map's layout flips at the largest font with the track bar unfolded and the turn-back card up

At font scale 2.0, with the track bar's panel unfolded and the turn-back card up, the track bar jumps between two
positions every second or two with no input, the drawing lags the layout, and a tap on "Hide" as drawn misses about
half the time. Nothing freezes. Found behind a11y lines that came and went (T373, wave 48); the evidence, the
verifier's table and what is not yet known are in
`../findings/2026-11-25-the-map-layout-flips-at-font-2-with-the-bar-unfolded-and-the-turn-back-card.md`.

The owner, 2026-11-26, asked when to fix it, by multiple choice: "Wave 49, release now". Wave 48 releases with it;
it is older than that wave.

What a pass would do, from the finding's Next: name which control's size feeds back into the layout rule; make the
rule settle by construction, as T371 did for the readout strip's measure, with a pure test over the font scales with
the bar unfolded and each bottom card up; give the sweep an a11y screen that meets the state again and fails when the
layout does not hold; make the a11y tool's `unsteady` mark a line of the part's summary; try it on a phone at the
system's largest font.

Plan: one pass, T371's shape, on the emulator. Files: `androidApp/.../app/MapLayout.kt` (`ruleNow`, the latch it
passes to `OverlayLayout.place`), `shared/.../ui/OverlayLayout.kt` and `OverlayLayoutTest.kt`,
`androidApp/src/test/.../app/MapLayoutTest.kt`, and as the cause demands `app/readout/ReadoutStrip.kt`,
`app/tracking/TrackControls.kt` (the bar and its panel) or `app/nav/WayBackClock.kt` (the turn-back card).
(1) Name the feedback: in the finding's scene (font 2.0, a recording whose last fix is stale, the bar unfolded, the
turn-back card up) log what `ruleNow` reads and what `place` answers for ten seconds, and find the input that differs
between the two arrangements; the commit message states it. (2) Fix it by construction: what the rule reads must not
depend on the arrangement it last chose, or the exchange is bounded and ends on one answer; a pure test drives the rule
to a fixed point over font 1.0 to 2.0, upright and on its side, with the bar unfolded and each bottom card up.
(3) `tools/android-a11y.py` gains a screen with that state at 2.0 that fails when the layout has not held for some
seconds, its lines in `tools/a11y-baseline.json`; `tools/sweep-summary.py` prints the part's `unsteady` screens as a
line. Verify: the finding's 60-second probe repeated, 0 changes of "Hide"'s left edge and 0 frames that disagree in
each of its six states; five taps on "Hide" as drawn all fold the panel; `check` green; the a11y part green with the new
screen. Out of scope: the activity picker's overlap (`2026-11-24-the-activity-picker-overlaps-the-right-hand-controls.md`,
next on these files) and step 4 of the finding (a phone at the largest font: the owner's, listed in the report).
