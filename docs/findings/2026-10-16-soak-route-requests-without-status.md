---
date: 2026-10-16
status: open
area: server / soak, route planning
---

# The 11.4-hour soak was flat except one `/route` request an hour that ended with no status (up to 60.9 s)

## Question

T107 ran the standalone server's 12-hour soak (`tools/server-soak.ps1`, port 8784, the Rainier
packs, a user token) after wave 14; the coordinator's restart cut it at 11.4 h. Did any series climb,
and were the route requests recorded with no status real?

## What was tried

Read from `~\Documents\src\dystopo-soak-T107\out\soak` (copied to `out\soak-T107\` in the
main checkout before the folder was removed): `summary.json`, `soak.jsonl` (70 samples, 12 hourly
probes, 12 trip builds), `soak.svg`, `probe-quick.json`, the two class histograms.

## Result

- 97,366 requests, 0 failures, statuses 200/204/206 only; every hourly probe green (76 probes,
  7 lenient); the server alive at the last sample.
- Flat after the first half hour: live heap 187 to 200 MB (102 MB of it `float[]`, the DEM cache),
  working set 0.96 to 1.06 GB, threads 141 to 159, handles 1,303 to 1,430. Growth over the second
  half 1.1 MB/h heap, within the script's 5 MB/h verdict. Latencies: pack tiles p95 58 ms, live p95
  1.6 to 2.9 s, other p95 30 ms.
- The one oddity: the server's own `/stats` counts 12 `route` requests with status `none` out of 914,
  one more each hour (1 at 00:57, 12 at 11:57), with `maxMs` 60,914. A status of `none` is a request
  the handler never answered (the client went away first); 60.9 s is a client-side timeout. The soak
  makes a route every third round and one trip route an hour (`-TripMinutes`), so the hourly trip route
  (`/route?from=Paradise&to=Panorama` with the user token, then `/route/bundle`) is the suspect, but
  `tripRoute` events in `soak.jsonl` all say `ok: true`, so which request hangs is not yet known.

## Next

T111 (wave 15): find which hourly request takes over 60 s (the soak's curl timeout, the hourly share
link, the full-GC histogram at every sample, the trip bundle), reproduce on a short soak with request
logging, fix it if it is the server's, and have the soak report `none` statuses per route as a
verdict line.
