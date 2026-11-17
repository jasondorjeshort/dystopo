---
date: 2026-11-17
kind: bug
---

# A blank screenshot is retaken once, then fails, in every part

Wave 35's closing sweep passed `device/tour1-direct-0.png`, a plain cream frame where the cold direct view at Mont
Blanc z9 should be: the step's numbers were fine (130 terrain tiles rendered, no failure) but the app had not gone
idle in 123 s (one render took 29.6 s on a loaded machine), so the capture came before the map was drawn
(`directStep.views[0].idle` false in `device/tour1.json`). It showed only as an 82 % change on the before-and-after
sheet. A check in the shared capture code (`tools/android-check.py`'s screenshot path, `Device.screencap` in
`tools/android-firstrun.py`): a frame that is nearly one colour, or whose map area is, is retaken once after a wait
and then fails the step, naming the frame. A step whose `idle` is false could wait longer before its capture.

## Open

- The threshold: a real map of snowfield or sea at low zoom must still pass (the colour count, not brightness).
- Recorded from conversation, not triaged or checked against the code.
