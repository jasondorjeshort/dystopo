---
date: 2026-10-30
kind: feature
related: [2026-10-29-sun-and-shade-on-terrain.md]
---

# Season-aware routing: slope, aspect, sun and drainage in the cost model

The phone has slope and aspect from the DEM (the avalanche and steepness shadings), T163's
`nav/Sun.kt` for the sun's position on a date, and T174's drainage layer. Put them in the router's
`CostModel`: in winter, avoid or penalise slopes above 30 degrees on loaded aspects and shaded
crossings; in summer, prefer shaded ascents before noon and penalise long sun-baked traverses; mark
drainage crossings after a storm. A "when to go" view costs the same route at four dates and shows
the differences. Open: this is advice about hazard, so the wording and the defaults need care (not a
safety claim; a cost preference the user turns on); the cost model is one setting per factor or one
"season" switch (ADR 0044's research); it depends on the routing graph shipped with the pack (ADR
0049) for anything beyond a small box.

Question: do you want routing shaped by hazard and season? A route the app calls better around loaded slopes reads as a
judgement of the snowpack, whatever the help text says. Recommendation: not before ADR 0049's B (wave 23) and the
sun-and-shade task. After both, a proposal: the cost model is a routing-engine change, and today `CostModel`
(`routing/Router.kt`) is time only. Graph edges carry length and climb but no aspect, so aspect is a graph format step. The
proposal should start with the comfort factor (shade on summer ascents, an opt-in cost) and keep avalanche terrain as display
only (the slope shading and its "not an avalanche forecast" line), not a router preference. The research ADR 0044 asks for
then decides between one switch and one setting per factor.
Answer:
