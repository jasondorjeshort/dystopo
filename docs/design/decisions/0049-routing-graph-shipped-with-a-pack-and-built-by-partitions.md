# 0049. The routing graph ships with a server-built pack; the phone builds by partitions when nothing ships

Date: 2026-10-30
Status: accepted (the technical decision after the critic; C's launch waits on the owner's answer below)

Record: proposal `../proposals/0049-routing-graph-in-packs.md` (T169, wave 22) and
`../proposals/0049-critic-review.md` (0 blocking, 7 serious, 10 minor; all answered in the proposal).
Prior art: wave 21's T149 research (BRouter, OsmAnd, Organic Maps and Mapbox all deliver a prebuilt
routing structure the phone reads in pieces; none builds on the phone from raw data).

## Context

Packs carry no routing section: the phone builds the routing graph from the vector archive in one
pass on first use, and T149 (wave 21) bounded that build with a heap guard (48 MiB + 300 B per
vertex, x1.5) after it ran out of memory on the owner's phone. The guard refuses a z10 city pack
(1.23 million vertices, 630 MB estimated against 475 MB free) and tops out near a 1 degree rural box
on a 576 MB heap. The server already writes the same version 6 graph file for its own routes
(`RouteRoutes.openOrBuild`), and the measured series shows the build is a heap artefact on the
server (489 s at 2.75 GB against 1,127 s at 2.25 GB for the 3 degree box; 49 to 70 s for the
statewide pack on a desktop heap). The field ships prebuilt graphs; this project is the outlier
because packs reach the phone without a server (the no-server build, imports, peers).

## Decision

**D of the proposal, built as B then C.**

- **B (first, one task of wave 23):** the server's pack build gains a `graph` phase after `publish`,
  with today's one-pass builder and encoder, writing `<name>-outdoors-graph.idx` and its SHA-256
  sidecar; a failed phase is a note on the job and the catalog omits the graph (never a failed
  pack). The graph is **derived data, never a pack file**: a `derived` list on the catalog entry
  (kept out of `files`, so the pack's hash and identity do not move when only the graph changes) and
  extra fields on the manifest's existing `graph` item. The phone fetches it with the archives when
  announced, through a role-less file job; `GET /route/status` says `source: shipped | built`;
  version skew or a fingerprint mismatch falls back to building on the phone, never refuses the
  pack (ADR 0033 §2 for derived data). v0.1.14 readers ignore both announcements and build as today
  (the regression fixtures are B's). Its report carries the graph's bytes against the vector archive
  and against the whole pack for the Rainier pack and the largest server build it runs.
- **C (second; its launch conditional):** `RoutingGraphBuilder` builds one z11 graph tile at a time
  for every caller and writes each body as it goes, admitted per partition by the guard on the
  exact routable-vertex count; z11 border twins kept exact by a border table in the `SNAP_M` band
  with levels; a staging file with a cursor so a refused or interrupted build resumes; the user's
  sentence names the part reached and says a smaller area or a server-built pack lifts it;
  `POST /route/build?onepass=1` kept for one wave for measurement. Its task first measures the
  densest z11 tile of T149's city pack and the 3 degree box and chooses merged z11 bodies or z12
  files on that number. **C launches only when the owner answers that the z10 refusal bites on
  packs built on the phone with no server** (`docs/TASKS.md` › Owner actions pending); otherwise it
  waits until a no-server user or a server too small for its packs meets the refusal.
- **Kept:** the phone builder for the no-server path, imports, peers and older servers.
- **Rejected, for the record:** A (today's split: leaves the refusal in place), E (a third PMTiles
  archive: a pack-format change every reader and the hash must learn), F (graph tiles served one by
  one from a server-wide graph: online routing, out of scope until corridors route offline), G (the
  graph inside the vector archive, or a memory-mapped one-pass build).
- **Out of scope:** routing across packs, corridor and polygon graphs (ADR 0030, 0041, 0047's
  supersede triggers), a graph format change (version 6 stays; a tolerant-join capability bit moves
  it to 7 only if the border twins cannot be made exact).

## Consequences

- Every server-built and catalog pack routes without a build on the phone; the owner's packs
  included. The server pays the build once per pack with its own heap.
- Two code paths build graphs until C lands (the server's one-pass, the phone's one-pass) and three
  for one wave after it; the one-pass path is removed the wave after C ships.
- The `derived` list is a second place a pack's files are listed; the peer catalog, the import flow
  and `graphFiles`' cleanup must all learn it (the proposal's moving parts), and a server-side
  rebuild must rewrite the sidecar or the phone silently falls back to building.
- C pins z11 as the read unit; its first task measures whether the densest tile fits before that pin
  is final.
- What would supersede this: shipped graphs over a quarter of the pack as the user sees it (B's
  download then waits for the owner's view); corridors and polygons routing offline (F follows D).

Predictions (the trigger scan reads these):
- Prediction, check when B merges: a v0.1.14 APK pointed at a server running B lists its packs,
  downloads both archives of a new pack and builds its own graph with no error from the new fields;
  a phone downloading the Rainier pack from that server routes Paradise to Panorama Point with
  `source: shipped` and no build started.
- Prediction, check when B merges: a shipped graph is under 10 percent of its pack as the user sees
  it (DEM plus vector) on the Rainier pack and on the largest server build of B's task.
- Prediction, check when C merges: T149's z10 city pack builds by partitions on the 576 MB emulator
  heap with a measured peak growth under 200 MB, and the Rainier golden route is identical to the
  one-pass build's (2,953.3 m, +416.4 m, 223 points).
- Prediction, check by 2027-01-31: the owner has answered the z10 question on the board, and C has
  either launched or a dated line here says why it still waits.
