---
date: 2026-11-22
kind: bug
related: [2026-11-22-sky-from-here-in-colorado-says-no-elevation-data-with-a-pack-and-the-internet.md]
---

# At the phone's own position, two overlapping packs: Panorama says "No elevation data here", Horizon shows no peaks

Priority: **top for the next wave** (the owner: "even bigger issues"; the Horizon peaks are "something I've never
seen before", so likely a regression in the current release, v0.1.42-alpha; the Panorama half is the same fault as
the far-place idea below, now at the phone's own place).

Seen by the owner (2026-11-22, the current alpha): centre the map on the current location, which lies in **two
overlapping offline downloads, one high resolution**; open the Horizon view, then the Panorama. The Panorama is a
blank screen with "No elevation data here: ..." (`ViewshedText.NO_DEM`). In the Horizon view, switching **Peaks** on
now shows no peaks, which the owner has never seen before.

This widens the related idea: that one was a far-away place and could be put down to pack edges and the online
fall-through; this is the phone's own place with packs covering it, so the pack lookup itself is in doubt (which of
two overlapping packs a reader asks, and whether it steps down to the coarser one).

Anchors: `androidApp/.../panorama/PanoramaData.kt` (`readTerrain`), the Horizon view's terrain and peak reads
(`horizon/HorizonData.kt`, `HorizonScreen.kt`), `PackPrecedence`, `BundledAssets.reloadWhenUp`.

## Open

- Which release introduced it (the Horizon peaks worked before): diff v0.1.41-alpha to v0.1.42-alpha on the Horizon,
  Panorama, pack-precedence and peak-source paths.
- Whether it follows the pack choice (high-resolution alone, coarse alone, both) and a cold start.
- Whether the peaks come from the offline pack's names or another source, and why the toggle draws nothing.

## Investigation (read-only agent, 2026-11-22; code and git read at 466612eb, nothing run; V = checked in code, I = inference)

**No regression by diff [V].** v0.1.41-alpha to v0.1.42-alpha touches none of the Horizon, Panorama, pack, tile
or peak reads (only `Gnss.kt`'s position hold, T333; the Panorama UI, T329-T331; `FirstRun` T332; SightLine T334).
The peak read last changed in T326 (v0.1.41), behaviour kept. So "never seen before" most likely follows the new
state on the phone (two large packs, a map chosen in Offline maps), not a code change.

**Best-supported cause [I, strong]: the packs were not in the open `PackSet` at that moment.** Three readers on the
owner's phone agree: the "no map on this phone yet" card shows only when `packs.regions` is empty; the Horizon
terrain said "from the online" (`packs.demFor` returned null, `Gnss.kt:376`); the peaks said "No offline map with
peak names here" (`tiles == 0`, `HorizonData.kt:87`). At the phone's own place the same pair follows: Horizon
terrain still works through the online pyramid, peaks read packs only, the Panorama reads packs only.

**Design gaps that hold whatever the cause [V]:**
- The Panorama never reads the online source (`PanoramaData.kt:91`, `:153-165`); the Horizon terrain reads one
  pack (primary first, `PackPrecedence.ORDER`) per point and never steps down along a ray (`Gnss.kt:369-387`,
  `ShadeService.kt:291-293`).
- A "none" is kept: Panorama per place for the process (`PanoramaData.kt:125`), Horizon ±100 m (`Gnss.kt:50`,
  `:325-357`), peaks as long as the horizon is kept (`HorizonData.kt:49-67`).
- Background readers hold no lease on the tile service: `DystopoApp.reload()` (`:211-226`, from `PackManager.activate`
  and `BundledAssets.reloadWhenUp`) closes packs under them, a `ClosedChannelException` follows and the "none" sticks.
- The peak read takes the first archive that has a tile, even one without `mountain_peak`, with no fallback to the
  other pack (`HorizonData.kt:99-104`); no online vector fallback.
- `PackSet.open` drops a pack with no log when its DEM fails to open or is shorter than its header (`Packs.kt:202-218`).

**Hypotheses and the check that settles each:** H1 packs not open (`GET /packs`, `/firstrun` `mapHolds.regions`,
`/startup`; `adb logcat -s Dystopo DystopoAssets`; `GET /horizon` `peaks.tilesRead == 0` with terrain "online"); H2
sticky "none" from one bad moment (change the view range and reopen the Panorama: terrain appearing confirms); H3
`ClosedChannelException` from a reload mid-read (logcat "panorama sweep failed"); H4 first-candidate tile lacks
`mountain_peak` (`tilesRead > 0, tested == 0`); H5 wrong or coarse primary gives zero candidates; H6 the phone's
place lies outside both packs. Could not determine: which pack is primary, whether either is in `regions`, the
packs' bounds and zooms, the exact peaks text the owner saw.

**Task breakdown for the next wave** (wave 47's T339/T340/T342/T350 already cover much of this; amend rather than
add): T352 new chore (no Gradle): the owner-side capture (view-range test, a cold start, one logcat) written into a
finding. T339 plus two fixture rows: coarse versus fine as the primary (`POST /packs/activate`), and an activate
during an open Panorama. T340 amended: one terrain read per cell (deepest open map first, then coarser, then
online under the fetcher's rules); invalidate the three caches when the tile service changes or the network
returns; background reads lease the tile service or retry once on `ClosedChannelException`; the peak read steps
to the next candidate when a tile has no `mountain_peak`; a loading state and the Horizon view's own sentences.
T342 folds in `PackSet.open` reporting each pack it did not open (`GET /packs` `notOpened`). T350 adds the peak
reader and the lease rule to its list of readers. One Gradle agent per slot: T339 then T340.

## Owner's test (2026-11-22): changing View range does not fix it

Changing Settings > Map > View range (`viewRange`) and reopening the Panorama did not bring terrain back. That
drops the Panorama's cached "none" (`PanoramaData.kt:109`), so **H2 (a sticky "none" from one bad moment) is not
the cause, or not the whole cause**; H1 (the packs are not in the open set, or a reader finds no data in the packs it
asks), H5 and H6 remain, and the owner's earlier cold restarts also did not clear the far-place case. The next check
is the debug build's `GET /packs`, `GET /firstrun` `mapHolds.regions` and `GET /panorama` `reason` at the moment of
the blank, or one `adb logcat -s Dystopo DystopoAssets` capture. (The other `skyRange` setting is the flights'
range, not this one.)
