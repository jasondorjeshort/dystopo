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

2026-11-02 (triage): the export does not ignore them: `Gpx.write` emits every point, an estimated one with
`<dystopo:fix flags="5"/>` (`gpx/GpxExtensions.kt`), which another tool reads as an ordinary point plus an
extension it does not know. The totals, the profile and the trip report count no distance or climb for a
flagged point and nothing fills the gap after a confirming fix (`gpx/TrackStats.kt`, `TrackSmoothing`).
Any upload sits in ADR 0042, which waits on you.

Question: in a GPX export, should estimated points be kept as they are (ordinary points, the flag only
in our extension), dropped, or kept with a marker other tools show (a `<type>` or a comment per point)?
And should your own totals fill the gap once a good fix confirms the path? Recommendation: drop them from
the export by default with an "Include estimated points" switch on the export, since a mapper must never
take one for a measured point and no outside tool reads our flag; fill the totals and the profile (the
walk happened), with the filled part drawn dashed. Each is one pass once answered; the export rule is an
ADR 0033 line, not a format change.
Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
