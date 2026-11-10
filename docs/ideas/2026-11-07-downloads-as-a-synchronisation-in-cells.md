---
date: 2026-11-07
kind: feature
related: [2026-11-04-areas-update-themselves-by-how-you-use-them.md, 2026-11-02-pack-updates-as-deltas.md, 2026-11-01-federated-instances-announce-their-packs.md]
---

# Downloads as a synchronisation in cells over one content-addressed store, modelled on a game downloader

The owner, 2026-11-07, with a large area download running during a release: "We want this to behave sanely: a
synchronization rather than a monolithic pull. Where we record the areas that are downloaded and pull them cell
by cell when they're not down or are sufficiently out of date." And: "I don't think it should really be per tile
(tiles are tiny) but per some reasonable download unit which I called a cell." "The ability to interrupt
downloads, via a full phone reboot after connection is interrupted, a fairly common scenario in the back country,
is pretty essential." On the model: "looking at a downloader tool like Steam and modelling ourselves after that
could be more useful than trying to copy other map tools, since they all do it badly. It's something to do
research on."

What the code does today (read 2026-11-07): an area download is already a sync over the tile cache: the record
and its pin are written before the first tile (`androidApp/.../areas/AreaDownload.kt`), tiles already on disk are
cache hits (`online/OnlineFetcher.kt`), a cursor is saved every 2 s and the walk resumes from it, WorkManager's
boot receiver restarts the chain after a reboot once the network is back (`areas/DownloadWorker.kt`,
`areas/ResumeCheck.kt`). Staleness is per source version, not per unit; nothing refreshes without a tap; a re-run
walks the whole manifest; a pack download is a whole-file byte resume with no background worker
(`packs/PackDownloader.kt`). The progress unit is a whole manifest row at one zoom: a run that dies before one
row completes counts as a failure, and five such stop the download with Retry (`ResumeCheck.MAX_FAILURES`), which
on a country at z14 can give up with most tiles cached. The owner's cell is that unit, made smaller and given a
fetched-at time.

The owner on the two wider shapes, 2026-11-07: one content-addressed store for everything downloaded, so the tile
cache, packs and the phone-to-phone transport (ADR 0051) speak one model and a phone syncs missing cells from a
server, a friend's phone or any instance through one protocol, with a delta update being the cells whose hash
changed: "Absolutely we should do this - downloading is a major pain with the sizes that topo stuff has and it
needs to just work in the background." And an always-on helper that keeps a phone's wanted cells fresh so the
phone only ever catches up: "an excellent plan", for any instance the user chose or a peer, never a built-in
address of the owner's (ADR 0059: the application is distributed, with no central server).

## Open

- The research question (ADR 0044, technical variant, before the design): how a well-regarded downloader
  (Steam's depot and chunk model, and the like) records what is wanted, what is present and what is stale, resumes
  after a kill or a reboot, verifies, and updates in place; which parts carry over to tiles in a cache plus a
  record of wanted areas. Map apps are not the benchmark here, on the owner's word.
- The cell: its size (a bounded run of tiles, or a fixed tile count), its fetched-at time, the "sufficiently out
  of date" test, and whether the failure count moves with it.
- Whether a pack download joins the model (chunks with hashes) or stays a byte resume; whether the content
  address is per cell or per tile with cells as the transfer unit; how the phone-to-phone transport and a
  federated instance announce what they hold.
- The always-on helper: what an instance keeps built for a phone that told it its wanted areas, how the phone
  catches up within the visible data limits (ADR 0023), and the sweep's own server as the test instance.
- The real-phone checks still owed from T151 and T156: a long download with the app closed and the screen off,
  one over a service loss, and one over a reboot without the network.

Plan: decided in ADR 0060 (T244, 2026-11-09); build by its build order, one task per step from step 2 (the move),
and delete this file on the branch of the last step built (H2, after the owner allows it).
