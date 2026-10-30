---
date: 2026-10-29
kind: feature
related: [2026-10-23-osm-trace-upload.md, 2026-10-25-track-activity-profiles-and-dropout-handling.md, 2026-10-29-trail-conditions-phone-to-phone.md]
---

# The map learns where its trails are wrong from your tracks

When recorded tracks keep leaving a mapped trail along the same line, or keep walking where no trail
is drawn, the map is probably wrong there: a reroute, a missing switchback, an unmapped spur. Flag
the spot, show the disagreement next to the mapped line, and offer to send the evidence to
OpenStreetMap so a mapper can fix it. The trace upload of ADR 0042 sends whole tracks; this idea
finds the few hundred metres worth looking at. Anchors: the track store and GPX
(`shared/.../gpx/`, `shared/.../store/`), the trail geometry in the outdoors vector tiles
(`shared/.../mvt/`, `routing/RoutingGraph.kt`), the trace upload (ADR 0042).

- **Detect:** match each track to the trail graph (map matching over `RoutingGraph`); a stretch
  that stays more than about 25 m off any trail for more than about 100 m is a divergence.
- **Confirm:** one track is noise (GPS drift under trees and in canyons reaches 20 to 50 m); flag
  only where two or more traversals on different days agree, or where one track has good accuracy
  readings the whole way.
- **Show:** a marker on the map ("Your tracks leave the mapped trail here"), the divergent stretch
  drawn over the mapped line, dismissable.
- **Send:** upload only the divergent stretch as a trace through the ADR 0042 path, with that path's
  confirmation and trimming, plus an optional note. Never edit OpenStreetMap directly: automated
  edits there need community discussion first, and a human mapper should judge the evidence.
- Agent-testable: a devtool command over a synthetic track pair and a fixture graph near Rainier
  reports the divergences as JSON.

## Open

- A new outward data flow beyond ADR 0042's single-track upload: the owner decides.
- Privacy: the stretches come from the user's own movements; keep the analysis on the phone, and
  the ADR 0042 rule about trimming near a home still applies to every stretch sent.
- Time on and off a trail is also what the live readout and activity profiles look at
  (`2026-10-25-track-activity-profiles-and-dropout-handling.md`); share the map matching.
- Roughly two waves: map matching and detection, then the marker and the send.
