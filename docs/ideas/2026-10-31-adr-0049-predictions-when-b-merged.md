---
date: 2026-10-31
kind: chore
---

# ADR 0049's two "when B merges" predictions are due

`docs/design/decisions/0049-routing-graph-shipped-with-a-pack-and-built-by-partitions.md` predicts, to be checked when
B merges: (1) a v0.1.14 APK pointed at a server running B lists its packs, downloads both archives of a new pack and
builds its own graph with no error from the new fields, and a phone downloading the Rainier pack from that server routes
Paradise to Panorama Point with `source: shipped` and no build started; (2) a shipped graph is under 10 percent of its
pack as the user sees it (DEM plus vector) on the Rainier pack and on the largest server build of B's task. T189 merged
B on 2026-10-31 (merges 1fa0e30 and af77875) and neither line carries an Outcome. Found by the triage scan of 2026-10-31.

Plan: the coordinator (the most capable model) writes both Outcomes from the record, a Sonnet-class verifier supplying
any missing run. For (2) the T189 log entry already has the figures (the Rainier graph 0.3 percent of the pack with its
DEM, a 1 by 0.7 degree box 0.6 percent): held. For (1) the record shows the v0.1.14 parser fixtures reading the new
catalog and manifest unchanged and a shipped-graph route on a fresh emulator, but names neither a real v0.1.14 install
against a B server nor the Paradise to Panorama Point route: either a verifier installs the v0.1.14-alpha APK on an
emulator, points it at a `devtool serve` running main, downloads the Rainier pack and plans that route (reporting
`GET /route/status`), and runs the same route on a current debug build for `source: shipped`, or the Outcome says
"held by the fixtures, not tried on a real install". A finding if either failed; this file is deleted in the same commit.
