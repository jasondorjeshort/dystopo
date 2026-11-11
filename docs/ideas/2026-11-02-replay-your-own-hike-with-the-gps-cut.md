---
date: 2026-11-02
kind: feature
related: [2026-10-29-trail-corrections-from-tracks.md]
---

# A self-test for the terrain estimate: replay your own hike with the GPS cut and see how it would have done

T203's numbers come from the project's replays; the owner's trust in the estimate has to come from their
own ground. The phone has every recorded track with its barometer column (T159, T171): a row in the Tracks
list, "How well would the estimate have done here?", replays the track through the live source with the
fixes cut for ten minutes at a few places and draws the estimated path beside the real one, with one line
per cut ("within 15 m for 8 of the 10 minutes; gave up after 6 minutes on the flat stretch"). The replay
machinery exists (`tracking/Replay.kt`, `POST /location/terrain`); the measurement code is T166's
`TerrainFixReplay`. It turns the finding's six synthetic cuts into a figure for the user's own trails, and
the aggregate ("on your last 20 hikes") is the calibration the step-detector pass needs.

## Open

- Runs in the background (a minute of CPU per track); the T150 background rule.
- The result is a new line in the trip report (T198) or its own screen.

Recorded 2026-11-02 at the close of wave 26 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.

2026-11-02 (triage): `TerrainFixReplay.runLiveCut` (`nav/TerrainFix.kt`) already returns, for a line and a
cut, the errors, the spread, when the estimate showed and where it gave up: the numbers the idea wants.
But a stored track has no barometer, step or compass column, so the self-test simulates those sensors from
the track's geometry (a barometer from the elevation plus drift and noise, a compass from the bearing). It
would measure the filter against your trails and the trail graph, not your phone's sensors; the phone
also needs the graph and DEM for the track's area.

Question: still wanted knowing it tests the trails, not the sensors, until pressure is recorded with
tracks (the physical-limits idea's first pass)? Recommendation: yes, after that pass: a Tracks-list action
with its own result screen (one line per cut, the aggregate over recent tracks), recomputed on demand
and not stored, run as a foreground job with progress since a track takes about a minute; two passes.
Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
