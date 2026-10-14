# Decision records

One file per decision: `NNNN-short-title.md`, written with the `adr` skill (`.claude/skills/adr/`).
Numbers start at 0020 here; 0001 to 0019 stay free for Hatchery's own ADRs (ADR 0020). A proposal
that becomes an ADR (`proposal` skill) shares its number and lives in `../proposals/`.

Status: `proposed` (waits for the owner: anything humans see or touch), `accepted`, or
`superseded by NNNN`.

| ADR | Decision | Date | Status |
|---|---|---|---|
| [0020-hatchery-alignment.md](0020-hatchery-alignment.md) | What this project takes from the Hatchery base and what stays its own; why `docs/TASKS.md`, `docs/PLANNING.md`, `CHANGELOG.md` and `docs/SERVER.md` stay as they are | 2026-10-12 | accepted |
| [0021-git-guard-exceptions.md](0021-git-guard-exceptions.md) | `git-guard` with the wave workflow's exceptions: agents push task branches, lease pushes onto task branches only, branch deletion only after a merged PR, no `--no-verify` | 2026-10-12 | accepted |
| [0022-units-are-a-preference.md](0022-units-are-a-preference.md) | Feet or metres is a user preference; region only seeds the default | 2026-10-10 | accepted |
| [0023-no-wifi-gates.md](0023-no-wifi-gates.md) | Nothing is limited to Wi-Fi; data use is steered by visible limits | 2026-10-11 | accepted |
| [0024-publishing-and-beta-path.md](0024-publishing-and-beta-path.md) | An alpha after every green sweep on GitHub Releases, verified from the draft; beta on Google Play | 2026-10-10 | accepted |
| [0025-design-goals.md](0025-design-goals.md) | One data path online and offline, keyed and bounded caches, areas of interest, tiled routing, peer sources | 2026-10-10 | accepted |
| [0026-coordinator-hands.md](0026-coordinator-hands.md) | The coordinator coordinates; everything that runs the project is delegated | 2026-10-12 | accepted |
| [0027-area-manifest.md](0027-area-manifest.md) | Areas of interest: versioned manifest, packs for boxes, pinned tile covers for corridors | 2026-10-12 | superseded by 0030 (in part) |
| [0028-long-distance-routing.md](0028-long-distance-routing.md) | Long drives through a partition overlay over our graph tiles, the server as a remote router | 2026-10-12 | superseded by 0029 (in part) |
| [0029-two-level-drive-overlay.md](0029-two-level-drive-overlay.md) | Long drives: a z8 overlay level, cells kept on disk, a settle budget, component labels, the phone going remote for packs that do not join | 2026-10-14 | accepted |
| [0030-pinned-tiles-keep-their-version.md](0030-pinned-tiles-keep-their-version.md) | Pinned tiles keep the content version they were fetched at until the area is refreshed; pinned bytes have their own budget; the Offline button shows the size first | 2026-10-14 | accepted |

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
