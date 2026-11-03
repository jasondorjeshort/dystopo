---
date: 2026-11-02
kind: feature
related: [2026-10-31-wrist-companion.md]
---

# A glance screen: the readout's figures fill the phone in huge type

T204 made the readout bar fit at font scale 2.0 by dropping entries; the bar is still a strip beside the
scale bar, read with the phone held up and the glove off. A tap on the bar turns the screen into four
figures in the largest type that fits (elevation, climb, speed or pace, the time to the turn-around
deadline), high contrast, the map gone, touch locked but for the back gesture, the screen dimmed to the
minimum and the GPS interval unchanged. It is what a watch shows, on the phone in a chest pocket or on a
pack strap, for the long climb where the map is not needed. The wrist companion idea is the watch; this is
the phone doing the watch's job today.

## Open

- Which four figures, and whether the user picks them (ADR 0044: a survey of the watch faces).
- A barometer-only mode with the GPS off for the climb (the battery idea's territory).

Recorded 2026-11-02 at the close of wave 26 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.

2026-11-02 (triage): the readout has four entries (speed, climb, course, elevation; `readout/
ReadoutOptions.kt`), and the turn-around deadline is navigation's (`NavSession`, the way-back clock), not
a readout entry; only the elevation's pin mark is tappable today; the keep-screen-on flag is set during
navigation only. Nothing dims or locks touch anywhere in the app.

Plan: one pass, androidApp. A full-screen glance view opened by a tap on the readout bar (the pin mark
keeps its own tap): the readout's enabled entries in the largest type that fits, high contrast in both
themes, plus the time to the turn-around deadline as one more line while navigating; the screen stays on
as navigation does; the back gesture and a tap leave it. One research agent first (ADR 0044) surveys what
the hiking watches and the outdoor apps' big-figure screens show by default and whether users pick the
figures; build the surveyed default, and the figure choice as a setting only if the survey splits.
Brightness to minimum and a touch lock are out of scope (they trap a user who does not know the gesture;
a follow-up on the owner's word), as is a barometer-only mode (the battery idea). `GET /readout/glance`
reports the shown lines; the device tour takes its screenshot at font scale 1.0 and 2.0.
