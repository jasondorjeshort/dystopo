---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md]
---

# Height above terrain under each aircraft, on the card

What: the aircraft card (T311) shows altitude above sea level; Dystopo also holds the elevation model, so it can say
what an altimeter cannot: "4,200 m above ground, 1,100 m above the ridge". Ground height is the DEM at the aircraft's
position (EGM2008 metres); the ridge figure is the highest terrain along the line from the phone toward the aircraft,
which the Horizon view's horizon profile already has. Barometric altitude from the feed is pressure altitude, so the
figure needs the local pressure correction or is stated as approximate.

Why it suits Dystopo: no flight app does this, and it uses data the app has offline. It adds no data flow: positions
come from the layer's existing feed.

Data: the layer's adsb.lol feed (ODbL, per the finding) and the shipped elevation data (licences in `sources.json`).
Where no tile is downloaded the card says nothing rather than guessing.

## Open
- Pressure against geometric altitude (the feed carries both for most aircraft): which to show and how to word it.
- Over sea or no-DEM ground, whether the card shows height above sea only.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.
