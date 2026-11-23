---
date: 2026-11-22
kind: feature
related: [2026-11-19-a-gps-coverage-forecast-along-a-route.md, 2026-11-22-can-those-two-places-see-each-other.md]
---

# Moonshot: where might I get a signal? The nearest ground with a line of sight to a mast or a town

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

In the mountains a phone gets a signal mostly where it can see a tower. With a sight line between two points (`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/SightLine.kt`) the app can say, offline: "nearest place on your route with a view of a mast: 0.8 mi ahead, at the saddle", or shade the stretches of a route where no mapped mast or town is in view. For someone who needs to make a call, that is the most useful thing a map can say.

## Open

- **The data.** Communications masts are mapped unevenly in OpenStreetMap (`man_made=mast`, `tower:type=communication`); open cell databases exist with their own licences. A registry entry and a licence check come first (`CLAUDE.md`, Rules: data sources), and a research pass on how good the coverage of each is. A town or a main road in view is a cruder stand-in that needs no new source.
- It is a likelihood, never a promise: distance, the band and the carrier all matter; the wording must say "might".
- Cost: many sight lines per route; the viewshed code tests peaks from one fix, this wants one target from many places (the same shape as `docs/ideas/2026-11-21-a-viewpoint-finder-where-a-peak-can-be-seen-from.md`).

Question: a "where might I get a signal" line needs masts the packs do not carry today (`tilebuild/PROFILE.md` has no
mast or tower layer; an open cell database is a new source whose licence must be checked before a `sources.json` entry)
and many sight lines per route; is it wanted, and on which data?
Recommendation: a read-only research pass first on OpenStreetMap's mast coverage in the public areas against an open
cell database's terms and coverage, then a proposal; the town-or-road stand-in is cheap but likely too crude to trust.
Options:
A. Research pass on mast data and licences, then a proposal (recommended)
B. Build the stand-in now: the nearest point on the route in view of a town or main road, after the line-of-sight primitive
C. Masts from OSM only: a mast layer in the tile profile, then the route line
D. Not worth building; drop it

Answer: A, a research pass on mast data and licences, then a proposal (the owner, 2026-11-22, multiple choice).
