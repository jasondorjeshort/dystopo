---
date: 2026-11-08
kind: chore
related: [2026-11-07-blue-dots-on-the-map-unexplained.md]
---

# A touch-target and accessibility sweep: every node's bounds match its drawing, and a tap at its centre does what it says

The owner, 2026-11-08, on the idea: "Yes, again."

T243's defect was a control whose accessibility node did not match its drawing: the long-press card's "Route to
here" node sat 46 px above its text after a modifier-order change, and nothing caught it until the sweep's tool
tapped the node's centre and nothing happened. T243 added `semanticsAway` to `GET /layout` for the elements the
layout rule places, and a check inside that one card. The app has never had an accessibility pass: TalkBack
traversal, touch targets under 48 dp (T243's follow-up: the card's actions are 28.5 dp), labels missing on icons,
contrast.

## Open

- A sweep part that walks every reachable screen's semantics tree (`uiautomator dump`, or the debug route reading
  Compose semantics), compares each clickable node's bounds with its drawn pixels (a screencap and the node's text
  or icon position), taps each at its centre and asserts the effect the node promises (a route planned, a sheet
  opened, a toggle flipped) through the debug routes, and lists nodes under 48 dp, without a content description,
  or overlapping another clickable node; the list is the part's report, a new failure a red part.
- The same walk with TalkBack's reading order, so the app can be used by ear; the findings become tasks.
- Which screens: the map with each card up, the Offline maps list, Settings and its pages, the recording panel,
  the share screen, the first-run card.

2026-11-14 (triage): the report-only part is built by T270 (`tools/android-a11y.py`, the debug-only
`GET /semantics`, `tools/a11y-baseline.json`; no control under 48 dp of touch area). Left: the baseline's findings
(T270's follow-ups, on the board) and the reading order.

2026-11-26 (triage, taking in the coordinator's `New:` of that day): wave 48's layout fault
(`../findings/2026-11-25-the-map-layout-flips-at-font-2-with-the-bar-unfolded-and-the-turn-back-card.md`) is a node
whose bounds do not match its drawing because the layout does not hold still. T373 already reads each screen across
frame tries and marks one that never settles `unsteady` without failing it; the layout-flip idea
(`2026-11-26-the-map-layout-flips-at-the-largest-font-with-the-bar-unfolded.md`) makes `unsteady` a summary line and
adds the held-state screen.

Plan: one pass, after the baseline's screen-reader findings are fixed (the map's controls reachable under a full
screen or a sheet, the search field's label) and after the layout-flip pass (both edit `tools/android-a11y.py`).
`tools/android-a11y.py` gains the traversal order of each screen from `GET /semantics` and lists: a control the order
never reaches, an order that leaves a card and comes back to it, a control reached before its own label; and every
screen is read twice some seconds apart with no input, a screen whose nodes moved becoming a line of kind `unsteady`
like the others. Each is red only on a line the baseline lacks, as today. One screen is walked with TalkBack on the
emulator, as the check that the order read from the semantics is the order heard. Done when: the part green on `main`
with the new lines in the baseline, its test (`tools/test_android_a11y.py`) green, the TalkBack walk's notes in the
report. Out of scope: fixing what it lists (each a task) and contrast (T270's finding, a task of its own).
