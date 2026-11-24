---
date: 2026-11-22
kind: feature
related: [2026-11-07-blue-dots-on-the-map-unexplained.md, ../findings/2026-11-23-audit-of-values-that-dash-dim-or-hide.md]
---

# A policy: a state the user can see changing has a "?" that says why

Priority: **medium** (the elevation case above is the first; the policy is the wider fix).

The owner asks whether the app should have many more "?" buttons. Anything drawn as a dash, a "?" or a
dimmed value, or tinted by a state, gets a tap that says in one line what the state means and what would end
it. The words can come from fields that exist: `ReadoutValue.reason` (`Readout.kt:79`) already says "still",
"no fix", "contradiction", "no height yet", "silent", "not given" and is reported by `GET /readout`.

Candidates to survey (each its own task, not one sweep): the bar's entries (elevation, climb, speed,
course, accuracy, pressure), the Glance lines (`GlanceView.kt` dims too), the pressure card, the location
puck's states, the blue dots. Under ADR 0039 a read-only survey of how established topo and navigation apps
explain unavailable values comes before the wording and shape.

## Open

- One shared "explain this" affordance (a tap on the value, a "?" beside it, or both) versus a per-screen one.
- A test that fails when a state is added with no reason text (a lint in the spirit of `LocationListenerTest`).

## Owner's word (2026-11-22)

The owner agrees a "?" affordance is wanted. Shape of the work they expect, in order:

1. **Research** (read-only agent, `benchmark.md`, ADR 0039 and 0044): how established topo and navigation apps
   explain a dash, a dimmed value or a stale reading, and the best form of the affordance. May run now or later.
2. **Audit** (reviewer work, a lot of it): every screen and strip value in the app that can dash, dim, tint or
   hide, listed with the reason it can and whether anything explains it today.
3. **Build** per place, from the survey's default.
4. **Click-through on emulators** (the device tour, debug builds and release builds by taps) to see that every
   state is explained; the audit's list is the checklist.

## Survey result (read-only agent, 2026-11-22; a first pass, not yet checked by hand)

Evidence is thin: the web search found almost nothing on how topo apps explain a dash or a dim. Google Maps,
Garmin handhelds and the G1000 use a state mark (grey, X, banner) plus help-centre text, none a tap on the
value; the other topo apps were not verified and need a hands-on pass before an ADR cites them (ADR 0039).

Recommended default (the agent's, the coordinator's to confirm):

- Reuse `HelpIcon` / `HelpHost` / `HelpRegistry` (`androidApp/.../help/HelpIcon.kt`); no second affordance.
- On the bar: the whole value column taps to a one-line reason pop-up ("Elevation: no height yet. Waiting for
  the first GPS fix."); a visible "?" only while the value is non-normal, so a healthy bar stays quiet.
- Wording: one `ReasonText` table keyed by (entry, state, reason code), registered in `HelpRegistry`, with a unit
  test that fails when a reason code has no text (the lint the first Open item asks for).
- Flicker: dim only after the gap passes `gapMs`; un-dim after 2 fixes or 3 s; 3 s minimum dwell; 300 ms alpha
  ease; at most three toggles per second (WCAG 2.3.1). Numbers are guesses; pick them on bursty fixes.
- No user setting (one clear practice); the hold length is a tuning constant with a debug route.
- Accessibility: `stateDescription` on each strip column ("elevation, 1650 metres, out of date"), 48dp target.

Places to audit (file:line from the agent's read; reasons exist only as codes in `ReadoutValue.reason` unless
noted): strip entries dimmed or dashed (`ReadoutStrip.kt:278,300`, `StripText.kt:138-145`); every entry's
HIDDEN and DASH states in `Readout.kt:552-620` (climb alone uses "?"); the dim rule (`Readout.kt:541,673`); Glance
lines dimmed (`GlanceView.kt:276`); location puck grey for lost and stale (`LocationPuck.kt`, has `TerrainText.lost`);
recenter button disabled tint (`RecenterButton.kt:212`); GNSS screen dashes per row (`GnssScreen.kt:163-175,331`);
"?" for an unknown size in downloads and storage (`OfflineMapList.kt:241`, `PacksScreen.kt:468`,
`StorageScreen.kt:447`). Already explained: nav fix-lost and off-route banners, the weak-GPS line, the no-map messages.

## Open (from the survey)

- After a jump reset, clear the height or hold the last one dimmed?
- Bar "?" always visible on a non-normal value, or revealed by the tap only?
- The puck is a MapLibre layer: explain it by its own tap, or through the bar?
- "?" and dash distinct everywhere (as the climb does: "?" too little data, dash contradiction), or one mark?

## Audit (2026-11-23, planning wave 48)

The owner's step 2 is done by a read-only agent: `docs/findings/2026-11-23-audit-of-values-that-dash-dim-or-hide.md`
(every state per surface, what explains it, how to reach it on an emulator, eight build tasks with disjoint files,
eight behaviours to fix rather than explain). It corrects the survey above: a tap on the bar already opens the
glance view, so a tap on a value cannot open its reason; the puck is already explained; Settings help already
explains the speed dash and the climb's marks.

The owner, 2026-11-23, by multiple choice: the reason shows **in the glance view**, one line under the value, and
the bar's value carries a small "?" while it is not normal; the bar's tap stays as it is. And, in the owner's
words the same day: "On the gps/pressure glance tap we have a question mark, but we could easily fit in data about
recent errors here. If there's a gps drop or etc in the last 60 seconds we can trivially fit that on the screen."
So the glance view also lists what went wrong in the last 60 seconds, even when the values are normal again.

Plan: built per surface, the bar first, each its own pass with the finding's file lists. First pass (wave 48):
the reason table beside the help registry with a test that every (entry, state, reason code) has a sentence, the
readout's reason codes as a closed set in `shared`, the "?" mark on the bar, the reason lines in the glance view,
state words in the bar's accessibility text, and a11y screens for the bar's states and the glance view so the
click-through can open them. Later passes: map controls (the position button's dead tap), the pressure card's way
down, the GPS view, downloads and storage, lists; then the click-through on debug and release builds with the
finding's table as the checklist.

2026-11-24, T363 (wave 48): the first pass is built: the readout's reasons as a closed set in `shared` (`ReadoutReason`,
`ReadoutReasons`), one sentence per (entry, reason) beside the help registry (`app/help/ReasonText.kt`, tested both ways),
the bar's small "?" on a value missing or stale, the sentence under each value in the glance view with what the bar leaves
out and what went wrong in the last minute, the bar's state words as its `stateDescription`, and the a11y screens
`readout-states` and `glance`. Left for later passes, as the Plan says: map controls (the position button's dead tap), the
pressure card's way down, the GPS view, downloads and storage, lists; then the click-through on debug and release builds.
The idea stays open for those.
