---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md]
---

# Moonshot: the owner's own ADS-B feeder, for a higher quota and for the home site

What: the finding's recommendation 6. Run a receiver at the owner's home and feed adsb.lol; its feeder tier is
documented at 7,200 requests a day against 500 without a key, and the home site could serve aircraft positions to
phones, so they share the site's quota instead of each using their own. A feeder key might be obtainable for the app.

Why it suits Dystopo: the first release's biggest risk is the 500-a-day quota and an archived API repository; a feeder
gives a way out and a contribution back. The same hardware leads to the offline-receiver idea.

Data: the receiver is the source (its software, readsb, is GPL, so it runs as a separate program, never linked). What
adsb.lol returns stays ODbL; share-alike applies to a derived database, which a cached track pack could be.

## Open
- A site-served feed puts every phone behind one IP and one quota unless a feeder key covers it, and each phone then sends its area to the owner's server (a new data flow, the owner's call).
- A home antenna sees only its own surroundings, so the benefit is quota, not coverage.
- Hardware, placement and upkeep are the owner's; whether he wants a feeder at all.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.
