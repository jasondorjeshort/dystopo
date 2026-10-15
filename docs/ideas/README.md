# Ideas

Raw ideas, bugs and missing features recorded for later review, one file per idea. An idea is not a
task: nothing here is scheduled or decided. The coordinator triages the folder when planning a wave
(see Triage) and moves each idea to a task, a finding, a proposal or the bin.

Files are named `YYYY-MM-DD-<slug>.md` and carry frontmatter:

```
---
date: 2026-10-13
kind: bug | feature | question | chore
status: new | planned | done | dropped
pipeline: T## or "none"     # the TASKS.md entry that covers it, if any
---
```

Optional frontmatter: `related: [<file>, ...]` for overlapping ideas that stay separate, and
`merged_into: <file>` on a folded duplicate (with `status: dropped`).

Body: a title, then what you saw or want, and why, in a few lines. Add steps to reproduce for a bug.
Keep it short; a long investigation becomes a finding (`docs/findings/`), a major choice a proposal.
A vague idea is fine: write what is known and put the gaps under `## Open` for implementation time.
Brainstorming goes under `## Brainstorm` in the same file (options, trade-offs, questions), dated
and short; when it settles into a choice, it becomes a proposal or a task, not a longer idea.

## Before adding: look for overlap

Duplicates are expected, so every new idea starts with a search, not a new file:

1. `grep -ril` the keywords across `docs/ideas/`, `docs/TASKS.md` (including its Follow-ups lines),
   `docs/findings/` and `docs/HANDOFF.md`.
2. Same idea as an existing file: add the new wording or detail to that file (a dated line) and do
   not create another.
3. Overlapping but distinct (shared cause, shared code, one is a subset): create the file and link
   both ways with `related:`; say in one line how they differ.
4. Already covered by a task or finding: still record it if it adds something, with `pipeline:` set;
   otherwise tell the owner it is covered and add nothing.
5. A later duplicate that slipped in: fold it into the older file, set `merged_into:` and
   `status: dropped` on it (or delete it if nothing else links to it), and fix the index.

Ideas that touch the same files are also a signal for the coordinator to put them in one task.

## Triage

At wave planning, for each `new` idea: set `pipeline:` if a task already covers it (`planned`), or
write a `docs/TASKS.md` entry and link it (`planned`), or set `dropped` with a one-line reason under
the body so it does not come back. When the covering task is merged, set `done`. Update the table in
the same edit.

Public places only (names from `areas.json`); never a personal area, its coordinates or nearby
names (`CLAUDE.md`, Rules).

## Index

| File | Kind | Status | One line |
|---|---|---|---|
| `2026-10-13-recenter-button.md` | feature | planned | Recenter button cycling north-up, free and facing; the plain button is T93 (wave 13), the cycling T100 (wave 14). |
| `2026-10-13-map-scale-bar.md` | feature | done | Scale bar on the phone map, own units by default, optional both (T101, wave 14). |
| `2026-10-13-large-distance-units.md` | feature | done | Imperial distances switch to miles at large scales (T101, wave 14); inches someday. |
| `2026-10-13-driving-is-in-scope.md` | question | done | Doctrine: hike first, driving a full second mode; ADR 0032 (2026-10-14); T102 rewrites the T73 wording. |
| `2026-10-13-cannot-create-waypoints-android.md` | bug | done | A waypoint can be dropped on the phone at the fix or at a long-press, recording or not (T91, wave 13). |
| `2026-10-13-turn-directions.md` | feature | planned | "Turn left in 30 feet onto Trail F" in every mode (T102, wave 14); optional voice later. |
| `2026-10-13-auto-download-when-zoomed-in.md` | feature | new | Keep topo and map data automatically when dwelling zoomed in (T75 is manual). |
| `2026-10-13-routing-modes-crow-bushwhack.md` | feature | new | Crow (straight line) and bushwhack (trails, then gentlest cross-country) modes. |
| `2026-10-13-avalanche-slope-shading.md` | feature | done | Separate avalanche-window and plain-steepness slope shadings (T104: slope off, avalanche or steepness, a legend; docs/PLANNING.md › Slope shading). |
| `2026-10-13-no-other-product-names.md` | chore | done | Rule: our user-visible text does not name other mapping software (CLAUDE.md, 2026-10-14); T104 renamed the two hits and added `tools/user-text-check.py` (in `./gradlew check`). |
| `2026-10-13-route-waypoints-and-reroute.md` | feature | new | Route to a destination with waypoints dropped when passed, rerouting, all modes. |
| `2026-10-13-cache-budgets.md` | feature | new | One total cache budget plus per-kind budgets, LRU or LFU (T74 has per-store caps). |
| `2026-10-13-settings-tabs.md` | feature | planned | Settings sheet grouped into thematic tabs (T103, wave 14). |
| `2026-10-13-s-pen-features.md` | feature | new | Niche: useful S Pen behaviour. |
| `2026-10-13-battery-efficiency.md` | feature | new | Battery doctrine, a measurement mode and an automatic low-battery mode. |
| `2026-10-13-external-integrations.md` | feature | new | Import, export and sync with outside services (route services, cloud drive, OSM, fitness). |
| `2026-10-13-download-by-region.md` | feature | new | Download topo by county, state, park, drawn region, or distance around a point or route. |
| `2026-10-13-help-popups.md` | feature | new | Help text pop-ups behind a question-mark icon. |
| `2026-10-13-alpha-interface-freedom.md` | question | done | Doctrine: interface changes are fine while in alpha; ADR 0031 (2026-10-14) and the CLAUDE.md Decisions line. |
