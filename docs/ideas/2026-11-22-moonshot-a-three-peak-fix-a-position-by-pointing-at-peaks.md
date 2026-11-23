---
date: 2026-11-22
kind: feature
related: [2026-11-02-horizon-match-fix-from-the-camera.md, 2026-11-22-fix-the-compass-and-position-from-the-camera-on-the-panorama.md, 2026-11-19-a-weak-gps-note-while-recording.md]
---

# Moonshot: a three-peak fix, a position from pointing the phone at named peaks

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

When the GPS is weak or wrong (the case `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/gnss/WeakSignalWatch.kt` now notices while recording), the user points the phone at three peaks they can name, taps each, and gets a position: the old resection, with the phone as the sighting compass. The trick that makes it better than a compass: it can use the angles **between** the peaks, which the gyroscope in the rotation vector measures well over a few seconds, so the compass's error (often 10 degrees or more) drops out; the peaks' elevation angles against the terrain give a check and a height.

Differs from the two camera ideas (`related:`): no camera and no image matching, only the attitude (`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/PhoneAttitude.kt`) and the pack's peaks; it could be built and tested long before the camera work, and its arithmetic is pure and testable on synthetic peaks.

## Open

- How good it is: the error from pointing by hand (a degree or two per sight?) against the peaks' distances; a devtool simulation would say before any phone work.
- Pointing needs a sight: the phone's edge, or the Panorama's centre mark in Follow with the drawing dragged onto the real peak (T331's heading correction is half of this gesture already).
- What the app does with the answer: show it beside the GPS fix with its error ellipse, never replace the fix silently.
- Two peaks and the barometer's height might do where three are not in view.

Question: start with a simulation pass only (a pure resection in `shared/.../horizon/` from the angles between named peaks plus their elevation angles, and a devtool command that places synthetic sights at public areas such as Rainier and Mont Blanc with 1 to 3 degrees of pointing error and reports the position error per geometry), before any sighting flow on the phone?
Recommendation: yes; the arithmetic is pure and testable on synthetic peaks, and its numbers decide whether the phone flow (a sight mark in Follow, an error ellipse beside the GPS fix, never replacing it) is worth a proposal. The phone flow is a new sensor flow and stays the owner's.
Options:
a) The simulation pass first, the phone flow on its numbers (recommended).
b) The simulation and the phone flow together as a proposal now.
c) Fold into the camera fix idea (`2026-11-22-fix-the-compass-and-position-from-the-camera-on-the-panorama.md`).
d) Not worth building.

Answer: A, a simulation pass first; the phone flow decided on its numbers (the owner, 2026-11-22, multiple choice).
