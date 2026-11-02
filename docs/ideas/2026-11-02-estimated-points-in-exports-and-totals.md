---
date: 2026-11-02
kind: question
related: [2026-10-23-osm-trace-upload.md]
---

# What happens to estimated points in exports, totals and uploads

T203 records the terrain estimate's points in the track flagged ESTIMATED and counts no distance for them
until the next good fix. The survey behind it called the next step the owner's: whether those points
enter a GPX export (as ordinary points, with an extension that marks them, or not at all), the track's
distance and climb totals once a good fix confirms the path, the trip report's profile, the paper sheet,
and any upload to a public trace service (ADR 0042 waits on the owner too). A mapper fixing a trail from
a trace should never take an estimated point for a measured one; a hiker reading their own totals wants
the gap filled. Today: the flag is in the store and the sync bundle (older builds refuse it, ADR 0033),
the export and the totals ignore the points.

Recorded 2026-11-02 at the close of wave 26 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.
