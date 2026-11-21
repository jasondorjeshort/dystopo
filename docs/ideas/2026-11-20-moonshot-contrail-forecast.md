---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md]
---

# Moonshot: a contrail forecast

What: tell when and where aircraft will leave persistent contrails, using the Schmidt-Appleman criterion on upper-air
temperature and humidity at cruise levels (cold and humid enough, a contrail forms and persists). Shown as a layer or
a card line on a flight ("will leave a contrail at this height").

Why it suits Dystopo: it explains what the user sees in the sky views, and the aircraft layer already has altitude. It
is the speculative end of the aircraft layer; nothing in the project reads weather models now.

Data: a gridded upper-air forecast (temperature and relative humidity at pressure levels). No source has been checked.
A permissive licence (CC0, CC-BY or equivalent) is needed before it can enter `sources.json`, and a forecast is
online-only, so a new data flow.

## Open
- Which weather source has a permissive licence, a usable grid and no key; a research pass first (`benchmark.md`, technical variant).
- Whether the answer is interesting at the resolution available.
- How a forecast is cached for offline use and for how long it stays true.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.

Question: is a contrail forecast wanted enough to pay for a research pass on a permissively licensed upper-air
forecast, the project's first weather data flow? Recommended: not now; it needs a new online source and its caching,
the payoff is a curiosity line, and no source is known to fit the licence rules. Park it until a weather source
enters the project for another reason.

Answer:
