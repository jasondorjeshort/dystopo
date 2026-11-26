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
