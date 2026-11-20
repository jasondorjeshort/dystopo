---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md]
---

# Firefighting and search-and-rescue aircraft picked out by type or operator

What: mark air tankers, scoopers, helicopters on a rescue or fire task and medical flights with their own symbol and a
card line ("Air tanker, fire suppression"), with an optional toggle to show only those. For a hiker near a fire or a
rescue the question is "is that aircraft for this".

Why it suits Dystopo: an outdoor map is read in exactly the places where these aircraft work. The feed already carries
type (`t`), description (`desc`) and a flags field (`dbFlags`) from the readsb database, so a first version needs no
new call.

Data: the adsb.lol feed fields (ODbL, per the finding). Operator is the weaker half: the feed gives little beyond those
fields, and the finding left the FAA registry (public domain, US only) as an optional operator source. Many
public-safety aircraft turn transponders off or are filtered from public feeds, so the layer can never show them all.

## Open
- A list of type codes to treat as firefighting and its upkeep.
- Wording so an absent symbol is not read as an absent aircraft (a safety matter).
- Whether the FAA registry join is worth a shipped file.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.
