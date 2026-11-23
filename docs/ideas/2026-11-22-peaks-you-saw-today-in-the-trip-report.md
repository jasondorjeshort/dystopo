---
date: 2026-11-22
kind: feature
related: [2026-11-21-a-viewpoint-finder-where-a-peak-can-be-seen-from.md]
---

# Peaks you saw today, in the trip report

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for new ideas after the triage of 2026-11-22, and recorded on the owner's word). Anchors are from the session's reading, to be checked at triage.

After a recording, work out offline which named peaks were in view along the track (samples along it, the line-of-sight function per sample, run after the hike so cost does not matter much) and list them in the trip report with when and from where each was best seen.

**What exists already (to be checked):** Recordings and the trip report exist; the viewshed and peak placement exist; nothing runs them over a track.

Plan: after the line-of-sight primitive (`2026-11-22-one-line-of-sight-function-in-shared.md`) lands, one pass. Checked
at triage: the trip report is `render/TripReport.kt` (T198), made from `app/tracks/TracksScreen.kt` and `devtool print
--report`; nothing samples visibility along a track. Add `shared/.../horizon/PeaksAlong.kt`: samples every 500 m and at
each stop of `TrackStops`, the primitive's `from` peak test at each over the pack's `mountain_peak` peaks
(`Viewshed.readPeaks`), each peak kept with the sample where it stood highest above its skyline (time, distance along,
bearing). `TripReport` gains a "Peaks in view" block (up to ten, nearest first, the rest counted), worked out off the
main thread when the report is made. Verify: unit test on a synthetic track and ridge; `print --gpx
shared/src/commonTest/resources/gpx/paradise-out-and-back.gpx --report` lists Mount Rainier, with the sweeps' time in the
JSON and the PNG viewed; the phone's report screenshotted; strings in the report (ADR 0031). Out of scope: the live view.
