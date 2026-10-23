# Decision records

One file per decision: `NNNN-short-title.md`, written with the `adr` skill (`.claude/skills/adr/`).
Numbers start at 0020 here; 0001 to 0019 stay free for Hatchery's own ADRs (ADR 0020). A proposal
that becomes an ADR (`proposal` skill) shares its number and lives in `../proposals/`.

Status: `proposed` (waits for the owner: anything humans see or touch), `accepted`, or
`superseded by NNNN`.

| ADR | Decision | Date | Status |
|---|---|---|---|
| [0020-hatchery-alignment.md](0020-hatchery-alignment.md) | What this project takes from the Hatchery base and what stays its own; why `docs/TASKS.md`, `docs/PLANNING.md`, `CHANGELOG.md` and `docs/SERVER.md` stay as they are | 2026-10-12 | accepted |
| [0021-git-guard-exceptions.md](0021-git-guard-exceptions.md) | `git-guard` with the wave workflow's exceptions: agents push task branches, lease pushes onto task branches only, branch deletion only after a merged PR, no `--no-verify` | 2026-10-12 | superseded by 0035 |
| [0022-units-are-a-preference.md](0022-units-are-a-preference.md) | Feet or metres is a user preference; region only seeds the default | 2026-10-10 | accepted |
| [0023-no-wifi-gates.md](0023-no-wifi-gates.md) | Nothing is limited to Wi-Fi; data use is steered by visible limits | 2026-10-11 | accepted |
| [0024-publishing-and-beta-path.md](0024-publishing-and-beta-path.md) | An alpha after every green sweep on GitHub Releases, verified from the draft; beta on Google Play | 2026-10-10 | accepted |
| [0025-design-goals.md](0025-design-goals.md) | One data path online and offline, keyed and bounded caches, areas of interest, tiled routing, peer sources | 2026-10-10 | accepted (principle 3 amended by 0034) |
| [0026-coordinator-hands.md](0026-coordinator-hands.md) | The coordinator coordinates; everything that runs the project is delegated | 2026-10-12 | accepted |
| [0027-area-manifest.md](0027-area-manifest.md) | Areas of interest: versioned manifest, packs for boxes, pinned tile covers for corridors | 2026-10-12 | superseded by 0030 (in part) |
| [0028-long-distance-routing.md](0028-long-distance-routing.md) | Long drives through a partition overlay over our graph tiles, the server as a remote router | 2026-10-12 | superseded by 0029 (in part) |
| [0029-two-level-drive-overlay.md](0029-two-level-drive-overlay.md) | Long drives: a z8 overlay level, cells kept on disk, a settle budget, component labels, the phone going remote for packs that do not join | 2026-10-14 | accepted |
| [0030-pinned-tiles-keep-their-version.md](0030-pinned-tiles-keep-their-version.md) | Pinned tiles keep the content version they were fetched at until the area is refreshed; pinned bytes have their own budget; the Offline button shows the size first | 2026-10-14 | accepted |
| [0031-interface-changes-in-alpha.md](0031-interface-changes-in-alpha.md) | In alpha, an interface change an idea or a task describes is built without prior sign-off and adjusted after the owner sees it; removed flows, identity and data formats are still flagged | 2026-10-14 | superseded by 0033 (in part) |
| [0032-driving-is-a-full-second-mode.md](0032-driving-is-a-full-second-mode.md) | Hiking first; driving a full second mode across routing, directions and navigation, saying concretely what it does not do | 2026-10-14 | accepted |
| [0033-data-format-changes-in-alpha.md](0033-data-format-changes-in-alpha.md) | In alpha, data format changes are built without prior sign-off and listed in the PR; user data still migrates, derived data rebuilds, mismatched versions refuse plainly | 2026-10-16 | accepted |
| [0034-one-cache-budget.md](0034-one-cache-budget.md) | One cache budget over the phone's caches: presets, floors per kind, derived data first, least recently used within a kind, never the view or the route ahead; frequency scoring and an index database rejected on replayed numbers | 2026-10-16 | accepted |
| [0035-hooks-from-the-base-under-project-policy.md](0035-hooks-from-the-base-under-project-policy.md) | The hook scripts, harness and status line are the Hatchery base's verbatim and read this project's `policy.json`; what stays this project's on an update and why; the behaviour that changed (quoted git text, per-session usage cache and grants, a subagent's pull on `main` denied) | 2026-10-16 | superseded by 0036 (the pull denial, `triage`) and 0037 (the update rule); the rest stands |
| [0036-subagents-integrate-main-and-triage-comes-in.md](0036-subagents-integrate-main-and-triage-comes-in.md) | A subagent may pull, merge or rebase `main` and resolve conflicts (commit there stays refused; the coordinator rarely pulls); `docs/ideas/` takes the base's block grammar, listing on demand and the `triage` skill; an idea's ready part is split out | 2026-10-17 | accepted |
| [0037-adopted-from-hatchery.md](0037-adopted-from-hatchery.md) | The provenance record under the base's ADR 0016: the base line and the departure table live here; updates patch the base-owned set by the ownership map; the ten agreement sections are replaced by one-line pointers in `.claude/project/`; what is excluded and why | 2026-10-17 | superseded by 0038 |
| [0038-adopted-from-hatchery-after-t121.md](0038-adopted-from-hatchery-after-t121.md) | The provenance record after T121: base line `f7f41af`; `docs/hatchery/` base-owned and never edited, this project's records staying in `docs/design/`; every pushed commit carries the owner's real identity, attribution trailers allowed (T120, carried back as Hatchery's ADR 0020); the departure table and the agreement pointers carried forward | 2026-10-21 | accepted |
| [0039-benchmark-other-apps-before-deciding.md](0039-benchmark-other-apps-before-deciding.md) | When a user-facing default, wording or flow is in doubt, a read-only agent first surveys how the established topo and navigation apps do it (the `benchmark.md` brief); proposals carry it under Prior art, PRs cite it and state the departures; ADR 0031 unchanged | 2026-10-23 | accepted |
| [0040-stops-and-reroute.md](0040-stops-and-reroute.md) | A plan of stops as a keyed `stops` field beside the unchanged `path` (one-transaction writer, older builds degrade a plan to a frozen route, folded into a path version at the first beta); a plan replans asynchronously to the next stop on T61's off-route event with a 30 s limit and a "Replan now" lift, a frozen route never does; one reach rule for stops and waypoints; the owner's positions listed for veto; bushwhacking needs no path version, `LegKind` 4 reserved | 2026-10-23 | accepted |
| [0042-osm-trace-upload.md](0042-osm-trace-upload.md) | Uploading a recorded track to OpenStreetMap as a GPS trace: a public OAuth 2 client with PKCE in the system browser, an exported no-UI callback activity with persisted state, an https App Link fallback, one multipart upload, a local-only trace id, the ends cut within 500 m by default, trackable by default, a destination kind in the registry, a mock and debug routes; the owner confirms the account, the sheet, the default and the exception strings | 2026-10-23 | proposed |

Template (also in the `adr` skill):

```
# NNNN. Title

Date: YYYY-MM-DD
Status: proposed | accepted | superseded by NNNN

Record: <proposal, log entry, or PLANNING section it comes from>

## Context
## Decision
## Consequences
```
