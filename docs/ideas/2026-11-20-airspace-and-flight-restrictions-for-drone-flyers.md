---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md]
---

# Airspace and temporary flight restrictions for drone flyers

What: a map layer of controlled airspace, restricted zones and temporary flight restrictions (fire, event and VIP
TFRs), shown beside the aircraft so a drone flyer sees where flying is not allowed.

Why it suits Dystopo: an offline map with aircraft is a natural place for the airspace they fly in, and a TFR over a
wildfire matters to an outdoor user. Airspace boundaries are static geometry a pack could carry; TFRs are short-lived
and need a connection.

Data: FAA airspace and TFR data is United States public domain, so no attribution duty, but coverage ends at the
border and other countries differ (licences unchecked). Static airspace goes through the pack builder; TFRs are a new
data flow.

## Open
- A safety question: a stale or missing restriction can lead someone to fly where it is forbidden. The app would have to say it is not an authority and show the data's age; the owner decides whether to take that on.
- Which regions to support, and whether one global source exists with a permissive licence.
- Whether it belongs on the map layer list or in a separate drone mode.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.

Question: do you want the app to show airspace and temporary flight restrictions at all, given that a missing or stale
restriction can lead a drone flyer to fly where it is forbidden, and the data is US-only (FAA, public domain) with
other countries unchecked? Recommended: not now; it is a safety matter the project would have to stand behind, it
serves a narrow audience, and it would need a research pass on sources and a proposal. If yes, start with that
research pass (`benchmark.md`, technical variant) on regions and licences.

Answer:
