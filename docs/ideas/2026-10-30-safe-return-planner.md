---
date: 2026-10-30
kind: feature
---

# A safe-return planner: the turn-around clock generalised to the graph

T163's `nav/TurnAround.kt` answers one question for one route: when to turn back. Generalise it: at
every junction of the routing graph within reach, the latest safe time to be there given the
deadline (sunset, twilight or typed), the fitted pace (T164), the battery mode and the barometer's
trend, with the shortest bail-out from there to any trailhead or road. On the nav card, "latest
safe time here" updates as you walk; one tap shows the bail-outs ranked by time to a road. Needs
the routing graph shipped with the pack (ADR 0049) so the whole graph is on the phone, and the sun
and shade idea for a horizon that is not flat. Open: how to show many times on a small screen (a
coloured graph overlay, or the next junction only); what counts as a trailhead in OSM data.
