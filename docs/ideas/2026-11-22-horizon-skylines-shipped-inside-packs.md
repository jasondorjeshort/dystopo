---
date: 2026-11-22
kind: feature
related: [2026-11-21-the-horizon-view-moves-with-you.md]
---

# Horizon skylines shipped inside packs (moonshot)

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for new ideas after the triage of 2026-11-22, and recorded on the owner's word). Anchors are from the session's reading, to be checked at triage.

Work out a 360-degree far-layer skyline every few hundred metres at pack build time and ship it in the pack, so the horizon is instant offline and costs the phone nothing but the near layer. The first measure is the size: skylines per km2 times bytes per skyline against the pack's size.

**What exists already (to be checked):** `tilebuild` builds packs; skylines are worked out on the phone only.

Question: measure first (a devtool pass that sweeps the far layer, `LayeredSkyline`, on a grid every 250 to 500 m over a public area such as Rainier and reports bytes per skyline, skylines per square km, the share of the pack's size and the build time), before any change to the pack format?
Recommendation: yes; shipping skylines is a pack format change and wants a proposal, and the size and build-time figures decide whether it beats sweeping on the phone or the route pre-sweep (`2026-11-22-skylines-along-a-planned-route-worked-out-at-the-start.md`), which would read the same kept-skyline store. Today `tilebuild` writes no skylines; they are swept on the phone only.
Options:
a) The measurement pass first, a proposal on its numbers (recommended).
b) Straight to a proposal for a skyline layer in packs.
c) Prefer the route pre-sweep and rest this.
d) Not worth building.

Answer: A, measure first, then a proposal on the numbers (the owner, 2026-11-22, multiple choice).
