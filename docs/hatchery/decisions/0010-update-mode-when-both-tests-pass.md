# 0010. Update mode when both tests pass

Date: 2026-10-15
Status: accepted, superseded in part by 0016
Superseded in part by 0016 (`0016-base-owned-and-project-owned-files.md`), 2026-10-17: there is
one update mode, so the line decides `FROM` and nothing else; the out-of-scope item (the join
merge as a base option) is moot. The line deciding over `merge-base`, and the join merge as a
project's recorded departure, stand.

Record: no proposal (a procedure, not a major choice); owner approved reopening what ADR 0009 put
out of scope. Amends ADR 0009 where its Decision excludes the update procedure, and ADR 0004,
"Updates for adopted projects", where step 1 tests `merge-base` first; the rest of both stands.

## Context

ADR 0009 made the anchored `Hatchery base: <sha>` line decide ADR numbering when a tree passes both
tests (`git merge-base HEAD hatchery/main` succeeds and a line matching
`^Hatchery base: [0-9a-f]{7,40}` exists under `docs/design/decisions/`), and left the update
procedure alone. `update-from-hatchery` step 3 still tested `merge-base` first.

A downstream adopted project (ReGenesis, its ADR 0014) patches `.claude` and `CLAUDE.md` from its
base line and then joins histories with a no-tree-change `git merge -s ours --no-ff <base sha>`, so
`merge-base` succeeds there on every update. Step 3 as written would have chosen merge mode and
merged the base's `docs/`: a second ADR 0008, ADR 0009, Hatchery's handoff and ideas. Only that
project's own ADR stopped it. The skill was deciding the mode from the wrong fact: `merge-base`
says the histories touch, not how the project took the practice.

## Decision

- Mode is decided by the base line first. If `grep -rhE "^Hatchery base: [0-9a-f]{7,40}"
  docs/design/decisions/` matches, the update is patch mode from that sha, whatever `merge-base`
  says. Otherwise, `merge-base` succeeding means merge mode (a decanted project). Neither: stop and
  name the adoption prompt. The `update-from-hatchery` skill, step 3, carries the test in this order
  and cites this record; it is the same test as the `adr` skill, step 1, so one fact answers both
  questions.
- A join merge after the patch is a project's recorded departure, not base procedure. ADR 0004's
  rejection of joining histories as part of adoption stands; `adopt-hatchery` and patch mode never
  do it. A project that joins records why in its own ADR, and the base handles it only to the extent
  above: its base line still wins.
- Why the line and not the merge: the same reason as ADR 0009. The line records how the project
  took the practice and is bumped on every patch update; `merge-base` after a join merge is a
  bookkeeping artefact that would turn a patch project into a merge project the moment it joins.
- Out of scope: whether the base should offer the join merge as an option; trigger: a second
  project that joins for the same reason.

## Consequences

- An adopted project that joined histories gets the right mode from the base skill alone, with no
  project ADR needed to stop the wrong one. The only cost is one grep before `merge-base`.
- The `Hatchery base:` line now carries three things: the patch origin, the numbering case (ADR
  0009) and the update mode. A project that deletes it and has a merge-base silently becomes a
  merge project; the line is a record to keep, not a cache.
- A decanted project never has the line (ADR 0009, Context), so it is unchanged. A decanted project
  that adds one by mistake would start patching; the anchored regex and the `decisions/` path keep
  the phrase in prose from doing that.
- Supersede this record together with ADR 0009 if Hatchery changes how projects take updates.
