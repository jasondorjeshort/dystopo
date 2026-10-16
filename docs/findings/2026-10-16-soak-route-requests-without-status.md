---
date: 2026-10-16
status: resolved
area: server / soak, route planning
promoted_to: docs/SERVER.md
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

T111: lined up each sample's `route` `none` count with the soak's events in that `soak.jsonl`; then two
one-hour soaks on ports 8790 and 8791 with every hourly item every 5 minutes (`-TripMinutes 5
-ShareMinutes 5 -BuildMinutes 5 -ProbeMinutes 5 -SampleMinutes 5`, so the full-GC histogram every 5
minutes too) and the server's request log on (`logRequests`, the default), one on the server as it was
and one with the fix below.

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

**Answer (T111): the hourly quick probe's own `short-body` case, cut by the JDK listener without an
answer.** The probe (`tools/server-probe.py`, run by the soak every `-ProbeMinutes`, 60) sends
`POST /route/edit` with `Content-Length: 50` and 6 bytes, then waits. The route waits for the rest; at
`http.maxRequestSeconds` (60 s) the JDK listener's request timer closes the connection, the route's read
fails, the gate's 500 cannot be sent, and `/metrics` counts the request under status `none` (the
numbers above are `/metrics`, which the soak samples, not `/stats`). Evidence:

- In T107's `soak.jsonl` every one of the 12 increments of `route` `none` falls in the first sample
  after an hourly `probe` event (00:44 probe, 00:47 sample, and so on to 11:46 and 11:47), together with
  9 more `route` 4xx (the probe's other route cases); no other group ever had a `none`. The trip runs
  every 10 minutes, not hourly, and `soak.jsonl` holds no `tripRoute` event at all: the script writes one
  only when a trip route fails, so "all say `ok: true`" above was the absence of failures.
- The 8790 soak (before the fix): 12 requests with no status in the hour, each one `POST /route/edit`,
  60,019 to 60,957 ms, `outcome: connectionLost` in the server log, each inside a probe run (11 quick,
  1 final). The trip's route (22 to 82 ms), bundle and push (all 200), the 11 share links (5 to 42 ms,
  all 200), the 11 builds and the full GC every 5 minutes never left a request unanswered. Its verdict:
  `FAIL unanswered: 11 requests never answered ... route 11 (slowest 60957 ms)` (the last sample comes
  before the final probe; the soak now counts once more after it).
- T92 had seen the same in its 6.3 hours (SERVER.md: "7 `/route/edit` requests of 60.9 s with no
  status (the probe's short-body case ...)") without a check that would fail on it.

The fix (the server's, since a client that sends too slowly gets no answer at all): the gate answers
`408` with `Connection: close` when a body is still arriving at `http.maxRequestSeconds` (a timer
thread; the route's own answer is refused after it), counts it as a 4xx and `requestTimeouts`, and logs
`outcome: requestTimeout`; the JDK listener's timer now runs 5 s later and still closes connections whose
headers are slow (slowloris: `/healthz` answered after 65.4 s instead of 60). `OperationsTest` covers a
short body, a stalled chunked body and a slow body that arrives in time; with the gate's timer off the
short body gets no answer. The 8791 soak (with the fix, the same load): the 12 probe short bodies answered 408 in 60,002 to
60,036 ms, 0 requests unanswered, `verdict: PASS`; 5,593 requests, 0 failures, live heap 185.8 MB at the
end (-1.2 MB/h over the second half). The soak's verdict has an `unanswered` check (`-MaxUnanswered`, 0)
with the count, the slowest request per route and where each count grew in `summary.json`.

Also found: since T107's seventh map option the soak paired `contourInterval=40` with metric units now
and then (8 client 400s in the hour, the server rightly refusing 40 m); fixed in the soak.
