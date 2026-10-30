---
date: 2026-10-30
kind: feature
---

# Pace and climb-rate zones with a tone when you leave them

The readout strip (`androidApp/.../app/readout/`) computes speed, pace and climb rate per activity
with per-entry settings rows. Add a target band per activity (a pace range on foot or by bike, a
climb rate on a long ascent) and one tone or spoken line when the smoothed value leaves the band
for more than a set time, reusing T161's spoken line and T163's alert plumbing. A training feature
for people who use the app for fitness as well as navigation. Open: whether the band is one more
row per entry or a separate "training" page; nothing in the hiking apps surveyed does this, so the
benchmark pass would look at running and cycling apps instead.

Question: do you want training features in a map and navigation app? A target band that beeps is the first feature whose
purpose is fitness rather than finding the way: a question about the app's identity, not just a row. Recommendation: yes,
small, and after the readout bar. Build it as one more option in the existing per-activity, per-entry readout values
(`readout/ReadoutSettings.kt`: `target=<lo>..<hi>` on speed and climb, off by default, synced through
`readout.<activity>.<entry>` as today), with no separate page. The check runs in `shared/.../readout/` on the estimator's
smoothed value: out of band for a set time (default from the running and cycling survey, ADR 0039), then one tone through
`nav/NavDevice.kt`'s tone and an optional spoken line, at most once per minute. `GET /readout` › `target` reports it, and
`devtool readout --gpx` tests it on the replay.
Answer:
