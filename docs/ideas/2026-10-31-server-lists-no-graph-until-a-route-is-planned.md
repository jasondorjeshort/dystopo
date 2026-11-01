---
date: 2026-10-31
kind: bug
---

# A fresh server lists no shipped graph until a route is planned on it

ADR 0049's B check (2026-10-31): a `devtool serve --live` of `main` with the Rainier pack pair in `out/`
listed the pack with no `derived` graph entry, because no `*-graph.idx` existed beside the pack; one
`devtool route` on the server wrote the file (312,356 bytes, 1 s) and the catalog then carried the
entry, and the phone downloaded it and routed with `source: shipped`. So a pack built by `tilebuild`
and copied to a server ships without its graph until someone plans a route there: the first phones
build their own, which is the slow path ADR 0049 set out to remove. Open: build the graph at pack
build time (`tilebuild` or the `tiles` command writing it beside the pack), or at server start for
every pack that lacks one, or both. Found by the Sonnet verifier of the ADR 0049 check.
