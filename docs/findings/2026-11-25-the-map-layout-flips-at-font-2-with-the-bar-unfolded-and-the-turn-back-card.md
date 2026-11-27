---
date: 2026-11-25
status: resolved (T381)
area: android / map layout, the track bar and the cards at large font
---

# The map's layout flips between two arrangements at font 2.0 with the track bar unfolded and the turn-back card up

## Question

Why did the sweep's a11y part report two contrast findings ("Hide" and "Waypoint") on `recording-stale:2.0` in some
runs and not in others, on branches that touch none of that screen (T373, wave 48)?

## What was tried

- T373's implementer, on `main` at a8b4e22f, emulator, font scale 2.0, the a11y tool's `recording-stale` scene (a
  recording whose last fix reads three days old, the turn-back chip on, the track bar's panel unfolded): reads of
  `GET /semantics` against frames by `adb exec-out screencap`, with and without a fix, a fix feed, a server set and
  the readout bar; a tap where "Hide" was drawn.
- T373's verifier, independently, same build: one `GET /semantics` read a second and a frame every 2 s for 60 s in
  each of six states, then five taps placed on the "Hide" chip as drawn.
- The closing sweep of wave 47 (`sweep-20261124-w47-rerun2`, the build released as v0.1.43-alpha), its
  `recording-stale:2.0` frame and that entry's lines.

## Result

A fault of the app, not of the test. At font scale 2.0, with the track bar's panel unfolded and the turn-back card
up, the map's layout does not settle: with no input it goes back and forth between two arrangements, the bar as a
row at the left ("Hide" 93 dp from the left) and the bar as a column at the right ("Hide" at 350 dp), and the readout
strip drops and restores an entry with each change. The verifier's table, 60 s each:

| State | Changes of "Hide"'s left edge | Frames that disagree with the semantics |
|---|---|---|
| font 2.0, unfolded, turn-back card up, a server set | 21 | 14 of 28 |
| the same with no server set | 28 | 14 of 29 |
| font 2.0, unfolded, no turn-back card (a plain recording) | 0 | 0 |
| font 2.0, folded, turn-back card up | none to track | 0 of 29 |
| font 1.0, unfolded, turn-back card up | 0 | 0 of 28 |
| font 2.0, unfolded, turn-back card up, on its side | 3 slow moves | 0 of 28 |

The drawing lags the layout: in half the frames the picture shows one arrangement while two agreeing semantics reads
give the other, so a control is not where it is drawn. A tap placed on "Hide" as drawn folded the panel 3 times in 5
(the implementer: 2 of 4 missed). The interface stays responsive; no freeze report is written. The online column is
not the cause (the flip is the same with no server set and the column hidden); the implementer's first reading named
it.

This is the kind of exchange T371 fixed for the readout strip's measure (wave 48, e11467fb): something the layout
rule reads changes with the arrangement it chose. Here it changes the arrangement itself, each second or two, and
does not hang the main thread. Which control's size feeds back (the turn-back card's height, the strip's entries, the
bar's own wrap) is not yet known.

It is older than wave 48: wave 47's closing sweep already shows the other arrangement on this screen (the bar a
column at the right, the panel's "On foot" line 50 dp from the baseline's place). That the released v0.1.43-alpha
does it on a phone is likely and not proven: that sweep's frame carries no semantics for "Hide".

Since T373 the a11y screen `recording-stale` keeps the bar folded, and no screen of the sweep meets the faulty state
any more (`recording` and `recording-weak` unfold the bar at 2.0 without the turn-back card, where the layout
holds). The tool's new frame check marks a scene that never settles as `unsteady` and fails nothing, so the sweep
will not report this fault again by itself.

## Next

For wave 49 (the owner, 2026-11-26, by multiple choice: "Wave 49, release now"); wave 48 releases with it, as
v0.1.43-alpha very likely has it. The queue's entry:
`../ideas/2026-11-26-the-map-layout-flips-at-the-largest-font-with-the-bar-unfolded.md`.

1. Name the feedback: at font 2.0 with the scene above, log what `MapLayout.ruleNow` reads and what
   `OverlayLayout.place` answers over ten seconds, and find the input that differs between two passes (the scripts of
   the verifier's probe are the starting point).
2. Fix it by construction, as T371 did: what the rule reads must not depend on the arrangement it last chose, or the
   exchange is bounded and ends on one answer; a pure test drives the rule until it settles over the widths and
   scales of font 1.0 to 2.0 with the bar unfolded and each bottom card up.
3. Give the sweep the state back: an a11y screen with the bar unfolded and the turn-back card up at font 2.0 that
   fails when the layout does not hold for some seconds, and make `unsteady` a line of the part's summary.
4. On a phone at the system's largest font, with a recording older than its last fix and the panel open: does the bar
   move by itself?

## Resolution (T381, 2026-11-26)

The input that fed back, logged from `MapLayout.ruleNow` in the scene (about a hundred rule passes a second, not one flip
every second or two: the semantics reads only sampled it): the track bar's row. `TrackControls.kt` capped the bar's
`FlowRow` at `TopChips.onlineRightPx`, the online column's drawn right edge. Shown, the column narrowed the row to
179 dp, which wrapped to 180 dp tall; the bar's column (297 dp wide, the recording panel's width) then reached the online
column, which had no room for the chips under it and was hidden; hidden, its edge went to 0, the row took the window
(339 by 92 dp), the column fitted above the bar again and was shown. With no server set the column still draws its chip
and "Already on this phone", which is why the flip did not need a server: the first reading that the online column was
not the cause was wrong.

Fixed by construction: the rule flows the row itself (`OverlayLayout.placeWithRow`) from the chips' own sizes and the
online column's own width, both measured whatever arrangement was chosen, and the bar's column draws the row at the
rule's width (`android-app.md`, The map screen's layout). On the emulator at Paradise, `GET /semantics` read each second
for 60 s with a frame every 2 s, then five taps on "Hide" as drawn: font 2.0 unfolded with the turn-back card and a
server, the same with no server, a plain recording unfolded, font 1.0 unfolded: 0 changes of "Hide"'s left edge, 0
frames that disagree, 5 of 5 taps folded the panel; folded: 0 frames that disagree; on its side at 2.0 with both cards
the rule hides the bar for want of room, steadily (no "Hide" to tap). A pure test drives the old exchange round and the
new one to rest (`OverlayLayoutTest`), and the a11y part's `recording-stale-unfolded` screen fails unless the layout holds
8 s. Left to the owner: step 4, a phone at the system's largest font.
