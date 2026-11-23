---
date: 2026-11-22
kind: feature
related: [2026-11-22-the-elevation-readout-dashes-and-blinks-with-no-explanation.md, 2026-11-07-blue-dots-on-the-map-unexplained.md]
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
