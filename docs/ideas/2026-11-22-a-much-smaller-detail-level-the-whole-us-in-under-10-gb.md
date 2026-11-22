---
date: 2026-11-22
kind: feature
related: [2026-11-22-sky-from-here-in-colorado-says-no-elevation-data-with-a-pack-and-the-internet.md]
---

# A much smaller detail level: the whole United States in under 10 GB

From the owner (2026-11-22): the details are Less, Medium and Full, but Less is almost as large as Medium. They want a
level small enough that **the whole US fits in under 10 GB**, whether that is bought by thinner terrain, thinner road
and map content, or both, "either way being able to move between and connect packs is essential".

**Where it stands.** `AreaDetail` has three levels (`shared/.../areas`, ADR 0047, PLANNING 2026-10-28, T157): Full is
zoom 8 to 15, Medium zoom 8 to 14 with elevation to 13, Less zoom 8 to 13. The measured figure that matters: a 5 km disc
at Medium was 274 tiles (23.8 MiB) against 202 at Less and 538 at Full, so Less saves about a quarter of Medium, not
the order of magnitude wanted. The owner's current packs are a 30 GB low-resolution US map and an 8 GB Colorado map, so
the whole US is a real target and 30 GB is the number to beat by three.

**The owner's card (Offline maps, 2026-11-22, v0.1.41-alpha).** "US", 30.4 GB, "Map only", zooms z8-14 with
elevation to z13 (so **Medium**), bounds 33.40, -106.68 to 44.94, -72.85, "Area of 1,022,278 sq mi", "30.4 GB
downloaded (about 49.0 GB planned) · 99% on this phone", "From direct://sources". What it shows:
- **It is not the whole US.** The box spans roughly 11.5° of latitude and 34° of longitude: the middle and east of
  the country from northern Texas to the Canadian line's south, not the west or the deep south or the far north. The
  contiguous states are about 3.1 million sq mi, so the whole country at this level is about **three times** this pack,
  near 90 GB, and 10 GB is a **nine to ten times** cut, not the threefold one the first note of this idea assumed.
- **The card's own figures disagree.** 30.4 GB downloaded against about 49.0 GB planned reads as 62%, yet it says 99%
  on this phone; the plan may overcount (ocean and empty tiles, a ceiling not an estimate) or "99%" counts something
  else (tiles that exist on the phone, whatever pack they came from). One of the two numbers is wrong or unlabelled.
- **Density is far below the disc's.** 30.4 GB over 1.02 M sq mi is about 0.03 MB per sq mi; the 5 km Rainier disc at
  Medium (23.8 MiB over about 30 sq mi) is about 0.8 MB per sq mi, 25 times denser (mountains, ancestors counted once
  per small area). The disc cannot be used to extrapolate a country; the pack's own figures can.
- **Wording:** "From direct://sources" shows an internal scheme to the user; "Map only" and "Counts in the budget" are
  fine but the three facts (size, planned, percent) are three different measures on two lines.
- **Where the bytes are** is the number that decides the cut: at Medium the top zoom (z14, vector) normally holds most of
  a pack, a quarter-fold per zoom below; z12 vector with elevation to z10 would be about 16 times fewer tiles by that
  rule, so a whole-US level under 10 GB looks reachable by zoom alone, to be confirmed on a measured sample.

**A second measurement (the owner's download card, 2026-11-22).** For one view of about 1,000 tiles the card gives Full
93 MB (with the trail graph), Medium 46 MB and Less 43 MB: Less is **93 percent of Medium**, and Medium half of Full.
The trail graph and search data are a floor under every level of a pack path; the map-only area path is the one a
whole-country level would use, so the measure that matters is its own, not this card's.

**What would shrink it (to be measured, not chosen).**
- A top zoom of 11 or 12 for the vector and 10 or 11 for elevation: each zoom below the top roughly quarters the tile
  count, so one or two zooms down is the lever (and the tile profile in `tilebuild/PROFILE.md` decides what the low zooms
  hold).
- A thinner profile at the lowest level: main roads and trails only, fewer place and water classes, no building or
  minor-road layers; elevation without the contour or shade tiles that are made from it (`docs/reference/android-app.md`
  on the online store).
- Coarser elevation (a lower elevation zoom than the vector's), as Medium already does by one step.

**Constraints.**
- **Packs must connect.** A coarse US pack and a fine regional pack overlap by design (the Colorado case in the idea
  above); the rule is that every reader looks up the finest pack at a point and steps down on leaving it. A level this
  coarse makes the step visible, so the map should say at a view's edge that it is showing the coarse level.
- Format: a new `AreaDetail` value is an area-object and id change like T157's (a new id suffix, a manifest version, ADR
  0033), so older builds read it as opaque; the registry and `devtool` pack builds need the same name (`docs/reference/devtool-commands.md`).
- A country card lists sizes per level today ("Less 29 MB, Medium 30 MB, Full 99 MB" for a small country); the new
  level shows on the same rows, and a whole-US row would be the headline case.

**What more we would want to see.**
1. The measured size of the whole US at each of the three levels now (the owner's 30 GB pack is one figure; is it Less
   or Medium, and which zoom range?), and the per-zoom split of that pack: how many GB the top one or two zooms hold.
2. Which content the owner would give up first: terrain detail (elevation), road and trail density, or the top zoom
   level. This is a benchmark question (ADR 0039): how the established offline-map apps size their "lowest" pack.
3. The use the level is for: a whole-country backstop for planning and a failing network, with a fine pack on top where
   one hikes. If so, the ordered lookup above is the requirement, and 10 GB is the budget.

Question: is the new level "Lowest" under the same three-level row (a fourth), or does it replace Less, which is almost
Medium? Recommended: replace Less's range with the new smaller one only if the measurement shows nobody gains from Less
as it is; otherwise add a fourth, named for what it holds ("Overview"), after the size of the whole US at it is known.

Answer: decide after measuring; the owner leans to three levels being right, with Less and Medium today far too close together, so the measurement should show a Less that is much smaller than Medium (the owner, 2026-11-22, by multiple choice at triage).
