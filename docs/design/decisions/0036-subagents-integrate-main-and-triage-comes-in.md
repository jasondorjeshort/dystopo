# 0036. A subagent may integrate `main`; the ideas folder takes the base's grammar and `triage`

Date: 2026-10-17
Status: accepted

Record: the owner's words of 2026-10-17 on ADR 0035's two open questions ("we don't need to deny
git pull. When conflicts are involved, have a sub agent do it. Rarely should the coordinator pull.
Lets adopt the triage concept"); ADR 0035 (superseded by this record for those two points; its
other sections stand); ADR 0026 (the coordinator's own hands); Hatchery's ADR 0017 (queue
directories list on demand) and its `triage` skill and `docs/ideas/README.md` at `987265c` and
after.

## Context

ADR 0035 took the base's hook scripts and tabled one behaviour change it had not chosen: the base's
guard rule 4 counts `pull`, `merge`, `rebase`, `cherry-pick`, `revert` and `am` as commits, so a
subagent's `git pull` on `main` became denied, and the coordinator's own session was the only thing
that could refresh the main checkout. That contradicts ADR 0026, under which the coordinator runs
nothing and delegates even one command; it also left no one to resolve a conflict between the
coordinator's local doc commits and `origin/main`. ADR 0035 also left the base's `triage` skill out
because `docs/ideas/` here carried `status` and `pipeline` frontmatter and a hand-kept index, and
said the owner would align or keep that.

Verified: the guard's rule 4 reads one list, `git.commit.subagents`, and one `protectedBranches`
for all seven subcommands, so "pull yes, commit no" needs a policy entry the base does not have.
The base's ideas README (ADR 0017) now lists on demand through `queue-list.js` and ends each file in
at most one block (`Plan:`, `Question:` with a blank `Answer:`, `Waits:`); its `triage` skill reads
the untriaged files against their anchors and writes one block each, deleting only an idea the code
already has. Of the ideas here, eleven are `done` and eight live; the route-to idea sat four waves
because its one ready piece was bundled with four open questions (`docs/TASKS.md`, wave 16).

## Decision

1. **Integration is not commit.** A subagent may run `git pull`, `git merge` and `git rebase` on a
   protected branch (`main`, `master`), including the coordinator's main checkout, and resolves
   conflicts there; a plain `git commit`, `cherry-pick`, `revert` or `am` on a protected branch by a
   subagent stays refused, and pushing `main` stays the main session's (`push-verified.sh`). The
   policy grows an entry for integration separate from commit (shape in T117; the default keeps
   the base's behaviour so Hatchery can take the change unchanged). The coordinator refreshes its
   checkout by asking an agent, and pulls itself only when no agent can be launched (ADR 0026).
   Rule 13 (no `--no-verify`) and the interactive-rebase denial are untouched.
2. **The ideas folder takes the base's grammar and the `triage` skill.** `docs/ideas/README.md`,
   `queue-list.js` and `.claude/skills/triage/` come from the base verbatim (this project's privacy
   line kept). An idea is `date`, `kind`, optional `related`, text, and at most one closing block;
   no `status`, no `pipeline`, no hand-kept index. An idea whose work has landed is deleted in the
   PR that lands it (git has it; the log entry carries the substance). The scan step of `triage`
   (Deferred items, predictions, fitness lines) is inert here until those practices arrive.
3. **An idea's ready part is split out, not held hostage.** When an idea bundles a piece that needs
   no decision with pieces that do, triage writes a `Plan:` for the ready piece in its own file (or
   a task entry) and a `Question:` for the rest; the route-to idea is the first case (T114).

Out of scope: letting a subagent push `main`; a subagent merging pull requests (the coordinator's
merge sequence in the `wave` skill); the base's ADR 0016 split of `CLAUDE.md`, which arrives with
the next `update-from-hatchery` and is decided then.

## Consequences

- The coordinator's main checkout can be refreshed and un-conflicted by a Haiku chore; the one case
  that needed the coordinator's own hands is gone. The cost is one more policy entry to carry and
  to send back to the base (T117's pull request), and a harness group for it.
- The entry is `git.integrate` (T117): `subagents` and `protectedBranches`, each `null` by default
  and then `git.commit`'s; this project sets `"any"` and `[]`. A merge or rebase in progress on `main`
  ends with `git merge --continue` or `git rebase --continue`, which the commit refusal there names.
- Eleven idea files are deleted and the index table goes; a listing is `node
  .claude/hooks/queue-list.js ideas`. The `status: done` trail is lost from the folder and lives in
  the planning log and the ADRs the files named.
- Triage runs at wave planning and writes blocks, so the board is planned from `Plan:` blocks and
  the owner answers `Question:` blocks when they get to them; an idea with no block is visible as
  untriaged instead of silently `new`.
- ADR 0035 is superseded for its "Pulls of `main`" paragraph and its `triage` line; everything else
  in it (the scripts verbatim, `policy.json` the project's, the behaviour table's other rows, the
  update rule) stands. Supersede this record when the base takes the integrate entry as its own
  default or when the scan practices of Hatchery's ADR 0012 are adopted here.
