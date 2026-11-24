---
date: 2026-11-24
kind: feature
---

# Tap the chip to turn an off map back on

Since wave 47, an offline map can be switched off without deleting it (T342). Where an off map is
the only one covering the view, the chip and the Download card say "a map here is off" (T343).
Turning it back on still means going to Offline maps and finding it there.

The idea: a tap on that sentence, or a button next to it, turns that map back on where the user
stands. Offline maps stays the place to manage maps.

Recorded from the post-wave brainstorm of wave 47 (the owner, 2026-11-24).

Plan: one implementer, after wave 48 (T359 and T360 own the chip's code in `app/online/OnlineOverlay.kt` this wave).
Where the card says `OFF_SENTENCE`, the sentence gains a "Turn on" action (a button beside it, 48dp, spoken label
naming the map) that calls `PackManager.setOff(name, false)` for the map that is off here (`HeldHere.Held.off`; with
more than one, the deepest that covers the view, and the sentence names it). The chip itself keeps its tap. The map
loads without a restart (T342's path) and the chip and card read again. A debug route or the existing `/packs` one
shows the change; a unit test for which map is chosen. Verify on the emulator: a Mount Rainier map off, the view
inside it, the card's sentence and button, one tap, the map drawn again; screenshots before and after, viewed.
The sentence's new wording is shown in the report (ADR 0031). Out of scope: Offline maps' own row.
