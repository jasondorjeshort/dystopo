# 0030. Pinned tiles keep the content version they were fetched at; pinned bytes have their own budget

Date: 2026-10-14
Status: accepted

Record: the critic review of ADR 0027 (`../proposals/0027-critic-review.md`, 2026-10-13); PLANNING ›
Design goals, "Pinned tiles and content versions (T95)"; the T91 and T95 log entries (2026-10-14, PRs
#95 and #99). Supersedes ADR 0027 in part: how a pinned corridor's tiles relate to a source's content
version (0027 said one spatial rule keeps them, which a version change bypassed), the rejected
alternative 5 (pinned bytes counted against the online cap; T91 gave them their own budget), and the
Consequences line "pinned bytes are not budgeted separately". What still stands from 0027: areas as
boxes or corridors with a geometry-and-zoom id, the versioned manifest and its items, a box as a
region pack, a corridor as tiles pinned in the cache by a spatial predicate, the prefetch ahead on
the route, and rejected alternatives 1 to 4.

## Context

The critic's blocking objection: when a source's content version changed (any `/build` changes the
server's `dem` version; a new OpenFreeMap planet changes its `osm` version), `OnlineFetcher.dropOld`
deleted every old-version tile through `TileCache.clearWhere`, which skips the pin check, and lookups
used the current version anyway, so a pinned corridor did not survive a server rebuild. T95 measured
it on main's build: a 240-tile Paradise to Panorama Point corridor kept 33 tiles after one online view
past a `/build`, and offline the route start showed no trails at z15 and no data at z12. Its serious
objection: the trip's Offline button downloaded z8 to z15 and 500 m for every mode with no size shown
and no budget check. T91 had meanwhile given pinned tiles their own budget (`pinnedCacheMb`, 2 GB by
default) and per-mode corridor zooms.

## Decision

- **A pinned area records its versions.** Per source, the content versions its tiles were fetched at
  (`dem`, `osm` as the source's `GET /version` names them; empty for a source without versions):
  `Areas.Record.versions` on the phone, `versions` in the manifest (optional; older manifests read).
- **A version change keeps what pins hold.** The online fetcher keeps the `dem` and `osm` tiles a pin
  holds at its recorded version (`AreaPins.holds`: the pin's cover, as for eviction, at that version)
  and drops the rest as before. Contour and shade tiles made on the phone from the old DEM are dropped
  and made again from the kept DEM.
- **Lookups.** A lookup that misses the current version tries the versions the pins hold before the
  network, online as well as offline, so an area shows one version throughout and costs no data until
  it is refreshed.
- **Refresh is explicit or on the route.** The area card's Update, or the prefetch ahead on the route
  while navigating online, fetch the current version only; an area whose recorded version differs
  from what its source serves says so on its card and on the Storage screen. A download that
  completes whole with no version change on the way records the versions it holds; then the derived
  tiles in its cover and the old version's tiles no pin holds are deleted
  (`OnlineFetcher.dropUnheld`, also after Unpin and Remove). Areas recorded before T95 take the old
  version at the first change.
- **Pinned bytes have their own budget** (`pinnedCacheMb`, T91), checked before an area downloads;
  the online cap counts unpinned tiles only.
- **The Offline button shows the size first.** It works out the corridor's manifest and shows its
  size, or the size and the budget reason with no Download when the pinned budget would be passed;
  nothing is recorded until Download. Corridor zooms follow the mode: z8 to z13 for a drive, z8 to z15
  otherwise.

Rejected: re-fetching a stale area in the background as soon as its source moves on (data the user
did not ask for, and an area half old and half new when the network drops); trying every older
version of a missing tile (needs an index of the cache by z/x/y, where the pins already name the
versions that matter); keeping old-version contour and shade tiles under the pins (the map looks them
up at the current version only).

Deferred, each with its trigger: pinned bytes per source in `GET /areas` (a source switch leaves the
old source's pinned tiles unreachable until Unpin), when a user switches servers with areas pinned;
calling an area older when the phone now uses another server, at the same trigger; the critic's
objections 3 and 4 on ADR 0027, at their own triggers in the review.

## Consequences

- A pinned route survives a server rebuild or a planet update offline and online, at the cost of
  holding two versions of the same place while an area is older than its source.
- Seeing new data in a pinned area takes a visible Update or a navigated drive while online; nothing
  refreshes behind the user's back.
- The new wording the user sees (the offline size line, "Older than the source", the Storage note) is
  the owner's to sign off before a release.
- Supersede when areas are fetched from more than one source at once, or when graph tiles become a
  manifest kind.
