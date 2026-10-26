---
date: 2026-10-26
kind: feature
related: [2026-10-26-interrupted-area-downloads-resume-themselves.md]
---

# Downloads without a size cap: an estimate past the heap limit, a wide-view "Download this region", a middle detail tier

The owner (2026-10-26): "I want to be able to download 100 GB if I want to." Three things get in the way.

1. **No size when full detail is refused.** `AreaDownload.plan` in `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/areas/AreaDownload.kt` returns an empty plan when `AreaLimit.refusal` (`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/areas/AreaLimit.kt`) trips, so `fullDetailLine` in `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/areas/AreaPicker.kt` prints "Full detail" with no figure. The limit is heap / 4 / 256 B, about 576,000 tiles on a 576 MB heap: roughly 100 GB of elevation tiles at the manifest's estimate. The storage budget is not the cause (it goes to 1 TB and its refusal names the size).
2. **The top "Download this region" button refuses wide views.** `RegionDownload.start` in `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/online/RegionDownload.kt` refuses a view over 3 degrees a side (`MAX_SPAN_DEG`) and lowers the top zoom until the box is within 20,000 tiles (`TILE_LIMIT`), else "zoom in". It builds a pack, not an area, so it never offers less detail or the picker's size card.
3. **Less detail drops too much and too little.** z8 to z13 drops elevation z14 and z15 (nearly all the bytes) and also vector z14, where sidewalks, crossings, driveways and several points of interest exist only (`tilebuild/PROFILE.md`); the routing graph reads z14.

## Wanted

- Past the heap limit the plan carries an estimate (tiles times `AreaManifest.estimate`), marked as an estimate, instead of nothing.
- A tile cover held as rows (`[y, x0, x1]`) rather than a per-tile set, so the heap limit stops being a size limit; the budget stays the steer in bytes. This changes ADR 0041 §3.
- The top button goes through the picker's size-first card for the visible box: no span cap, a detail level chosen for the view, the estimate shown.
- A middle tier: elevation to z13 with vector to z14.

## Sizes (hand arithmetic from `AreaManifest.estimate`, not run)

| Area | Elevation z13, vector z13 | Elevation z13, vector z14 | Full (z15) |
|---|---|---|---|
| Lower-48 US | about 130 GB | about 150 GB | about 1.9 TB |
| World, land only | about 2.3 TB | about 2.7 TB | about 35 TB |

## Open

- Direct mode renders elevation tiles on the phone from the sources' ranges (`androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/online/DirectSources.kt`, default deepest rendered zoom 12, deeper tiles synthesized), so the bytes fetched differ from the stored PNG bytes the size line counts. What should the line promise there?
- The 160 kB per elevation tile is the mountain upper end; plains and ocean need a measured figure.
- Offline routing with vector tiles only to z13 is unverified.
- The 3 degree and 20,000 tile limits mirror the server's build limits (`DYSTOPO_BUILD_MAX_SPAN_DEG`, `DYSTOPO_BUILD_MAX_TILES`); a server-built pack needs them lifted too, or the button moves to the area path.
- Past the estimate, should Download proceed (the cover as rows) or only show the size? The owner's words suggest proceed.
