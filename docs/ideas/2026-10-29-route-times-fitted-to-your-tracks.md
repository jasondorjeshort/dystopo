---
date: 2026-10-29
kind: feature
related: [2026-10-13-routing-modes-crow-bushwhack.md, 2026-10-25-live-velocity-readout-by-travel-mode.md, 2026-10-25-track-activity-profiles-and-dropout-handling.md]
---

# Route times fitted to your own tracks

Today every route's time comes from one fixed model: a flat speed per mode and Naismith's climb cost
(`shared/.../routing/Router.kt`, `slopePenalty`, `edgeTimeS`). A fit hiker and a family with
children get the same estimate. Fit the model's few numbers (flat speed, cost per metre up, cost or
gain per metre down, the slope where descent slows) to the user's own recorded tracks, per travel
mode, so the route card and the navigation arrival time are the user's own. The bushwhack idea
needs the same fit for off-trail cost, which is why this file links to it; the live readout and
activity profiles split and clean the same tracks.

- **Fit:** for each recorded track, match it to the route graph, cut it into stretches by grade,
  drop stops (the activity-profile idea's stop detection), and fit speed against grade with a robust
  regression (a Tobler-shaped curve with the user's peak speed and width). Recent tracks weigh more.
- **Use:** the router's time function reads the fitted curve when there is enough data (for example
  20 km over 5 tracks in that mode), the fixed model before that; the route card says which ("based
  on your last 8 hikes").
- **Show and override:** a settings row with the fitted flat pace and climb rate, a reset, and a
  switch back to the standard estimate (ADR 0044: users split on this, so it is a setting).
- Agent-testable: a unit test fits a synthetic set of tracks generated from a known curve and gets
  the curve back; a devtool command prints the fit for a GPX directory as JSON.

## Open

- Synced: the fit is derived from synced tracks, so each device can compute it; or sync the result
  as a setting. Decide in the task.
- Group trips: a route planned for a group should use the slowest member's fit, or the standard
  model; out of scope for the first pass.
- Roughly one wave once the activity-profile stop detection exists; more if it has to be built here.
