---
date: 2026-11-20
kind: bug
related: [2026-11-15-a-first-launch-with-no-map-ignores-a-fix-after-four-seconds.md]
---

# "No map loaded" shown on a phone whose surroundings were bulk-downloaded

The owner, 2026-11-20: opened the map and it said there was no map loaded, though everything within 10 miles of the
phone had been downloaded with a bulk region-select. Not always reproducible. The map may have opened on a different
place than where the phone was (one that was not downloaded), but "it shouldn't be giving me that window, regardless":
a message that says there is no map must not appear when the phone holds maps, even if the view is somewhere they do
not cover.

Where the words come from (the owner did not say which message; candidates in the code):
- `NO_PACK`, "No offline map here. Download the map around you or choose one from your server...", in
  `app/MainActivity.kt` (line ~231), set where `app.tiles.packs.dem == null && app.tiles.online == null`. That test
  asks about the DEM pack and online mode, not whether any region is on the phone or whether the view is inside one;
  a phone with vector regions but no DEM pack, or one asked before the packs finish opening, reads as "no map".
- The first-run card, "No map on this phone yet" (`app/firstrun/FirstRunCard.kt`), shown when `FirstRun.noPack`
  (`packs.regions.isEmpty()`) and not dismissed: it should be false with a bulk download, unless the regions were
  not yet listed (a read at launch before the pack index loads, or an index that a bulk download had not registered).

Hypotheses to test, none verified: (1) a race at launch, the message drawn before the packs finish opening and not
withdrawn after; (2) the view opening elsewhere (the saved view, the world view) and the message testing the view's
coverage but wording itself as "no map on this phone"; (3) `dem == null` for a region download that carries no DEM.

Wanted: tell "nothing on this phone" apart from "nothing here". With maps on the phone and the view outside them,
say so differently and offer the way back ("Go to my downloaded area" or the Offline maps list) rather than the
download pitch; never show the first-run pitch to a phone that has regions. Cross-check with
`MapCoverage` (`app/search/PlaceSearch.kt`), which already separates TILES_ONLY from NO_MAP for search and routes.

Repro to try: bulk-download a region around a public area, force-stop, relaunch with the saved view (or the last
fix) outside it, and read `GET /firstrun` (`noPack`, `cardVisible`) and `/startup` right after launch and a few
seconds later; also relaunch repeatedly for the race. Add the timing to the report.
