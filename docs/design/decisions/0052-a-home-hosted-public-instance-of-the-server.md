# 0052. A home-hosted public instance of the server, run by the owner, map first

Date: 2026-11-01
Status: accepted

Record: the owner's words in chat on 2026-11-01 while planning wave 25 (quoted in the wave paragraph
of `docs/TASKS.md`, later `docs/tasks/wave-25.md`); no proposal (the server, its proxy recipe and its
page exist, `docs/SERVER.md`; this decides how one instance is run and what it exposes).

## Context

The server (`server/`, `serverlib/`) has been deployable since T58 and T82 (Docker, systemd, a
reverse proxy with TLS, `/healthz`, a bearer token, request caps, a soak). It has only ever run for
sweeps and on the coordinator's ports. The owner wants one instance running all the time on the
development PC, reachable under a dynamic-DNS name, started with the PC, "just something I can run for
myself and keep up"; not publicised; the point is to see the project as a site and to collect the
ideas that come from using it. Three questions were put to the owner and answered the same day.

## Decision

- **The instance is separate from development.** Its distribution is a snapshot copied out of the
  build tree, its config, cache and `out/` live under `~/.dystopo/site/`, it binds `127.0.0.1` behind
  a TLS proxy, and it starts as Windows scheduled tasks at logon with restart on failure (T199,
  `tools/site/`, `docs/reference/home-site.md`). A Gradle run in any checkout never touches it.
- **Public data only.** Its `out/` holds no pack of a private place and starts empty; the owner
  builds packs for public areas from the page with the token. The dynamic-DNS host name resolves to
  the owner's home and is a host name under `.claude/rules/docs.md` › Personal data: templates carry
  a placeholder; the name lives only in the untracked `~/.dystopo/site/` and in the owner's chat.
- **Live rendering for any visitor stays on**, with no per-client limit beyond the server's existing
  caps (owner: "we'll approach the problem later. I don't intend to publicize it"). Revived by the
  first sign of load the owner did not cause (`/stats` upstream counts, the log) or by publicising
  the site.
- **The map is the front page.** `/` is the map and nothing else: no landing text, attribution in
  the map's own control. The page opens at the calling browser's location (browser geolocation, which
  stays in the browser; denied or unavailable, the last view this browser saved, else the Rainier
  default; a hash in the URL wins), and fits a phone's screen (T200).
- **Out of scope here:** the cross-platform definition of controls and settings the owner raised
  (`docs/ideas/2026-11-01-one-definition-of-controls-and-settings-across-platforms.md`; a proposal
  when wanted), per-client rate limits, pre-built public packs, IP-based location (a new data flow
  and a new dataset; the owner is asked before it is built).

## Consequences

- One more thing the owner keeps up: the PC must stay awake and forwarded; `tools/site/status.ps1`
  says in one run whether it is up. The token and the host live in one untracked folder.
- Visitors can start renders that download from USGS, Copernicus and OpenFreeMap on the owner's
  connection; acceptable while nobody is told the address.
- The site shows the web page to the owner daily, which is the intended source of ideas; the
  parity question becomes concrete rather than abstract.

- Prediction, check by 2026-12-01: the site has run a month with `status.ps1` green on each check and
  no per-client limit needed (no upstream count in `/stats` the owner did not cause).
- Prediction, check when the owner first shares the address: a rate limit and the pre-built packs
  are wanted within a week of that.
