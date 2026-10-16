---
name: update-from-hatchery
description: Pull improvements from the Hatchery base repository into a project that was decanted from it (merge) or adopted it (patch), resolving conflicts by a fixed rule. Use when asked to update from Hatchery, sync the base, or merge hatchery/main.
---

# Update from Hatchery

The base evolves (agents, skills, hooks, rules, working agreement). A project takes those changes
and keeps its own text where the two overlap. Two modes, chosen in step 3: merge mode for a
decanted project (shared history), patch mode for an adopted project (ADR 0004 in Hatchery; the
project's own `NNNN-adopt-hatchery.md` records the base commit).

## Steps

1. Preconditions, stop at the first failure and say which: this skill exists in the project at
   `.claude/skills/update-from-hatchery/SKILL.md` (if you are reading it from `hatchery/main`, the
   project was neither decanted nor adopted: stop and point the user at the README prompt "Adopt
   the Hatchery practice"); `git status --porcelain` is empty (commit first; do not merge onto a
   dirty tree); `git rev-parse --abbrev-ref HEAD` is the project's working branch (the branch named
   in the project's `CLAUDE.md` Git section, or the one the user named; never assume `main`).
2. Remote: `git remote -v`. If `hatchery` is missing,
   `git remote add hatchery https://github.com/jasondorjeshort/hatchery.git`. `git fetch hatchery`.
3. Mode (ADR 0010). First the anchored base line, the same test as the `adr` skill's step 1:
   `FROM=$(grep -rhE "^Hatchery base: [0-9a-f]{7,40}" docs/design/decisions/ | head -1 | cut -d' ' -f3)`;
   non-empty means patch mode, even when `git merge-base HEAD hatchery/main` succeeds (a project
   that joined histories by its own recorded decision has a merge-base too, and merge mode would
   bring the base's docs into it; the line wins, ADR 0010). Else, if `git merge-base HEAD
   hatchery/main` succeeds: merge mode, `FROM=$(git merge-base HEAD hatchery/main)`. Neither:
   stop, the project has no relation to Hatchery; name the adoption prompt. Record
   `git log --oneline $FROM..hatchery/main` as the incoming base commits; if empty, report
   "already up to date" and stop.
4. Bring the changes in.
   - Merge mode: `git merge --no-ff hatchery/main -m "Merge hatchery/main into <project>"`.
     `git merge` counts as a commit under `git.commit.subagents` (ADR 0006), so under the base
     policy only the `runner` may run it: the coordinator briefs the runner with
     `git merge --no-ff --no-commit hatchery/main`, the agent running this skill resolves the
     conflicts (step 5), and the runner commits. Step 3's `merge-base` is only meaningful after
     step 2's fetch; a sha the project has never fetched fails it even when the histories are joined.
   - Patch mode: `git diff $FROM hatchery/main -- .claude CLAUDE.md LICENSES | git apply --3way`
     (drop `LICENSES` if the project has none). Needs a clean tree; conflicts are reported by
     `git apply` as "with conflicts" and show up in step 5 the same way.
   If no conflicts, go to step 6, but in merge mode first apply step 5's bullet on clean adds
   (the merge is already staged under `--no-commit`).
5. Conflicts. `git diff --name-only --diff-filter=U`. Resolve each file by this rule, then
   `git add` it:
   - Base wins: anything under `.claude/` (agents, skills, hooks, rules, `settings.json`) and
     `LICENSES/`. Exception: the project has recorded a reason to differ (an ADR, or a comment in
     the file naming one; an adopted project's `NNNN-adopt-hatchery.md` lists its overrides and the
     hook entries it keeps); then keep the project version and note it in the report.
   - Project wins, always: `.claude/hooks/policy.json`, the project's choices for the hooks (ADR
     0006). If the base added a key, the project may add it by hand; `node .claude/hooks/policy.js`
     must then print the policy, not an error.
   - Project wins: `README.md`; the title and project-specific sections of `CLAUDE.md`
     (phase, platforms, routing, branch name, `## Project rules`, anything the project
     added); `docs/HANDOFF.md`; the files under `docs/ideas/` and `docs/operator/` (their `README.md`s are the base's, since the block grammar must stay identical across projects, ADR 0007); everything under `docs/design/`, `docs/findings/`,
     `docs/reference/`. Exception, merge mode (ADR 0008): ADRs `0001` to `0019` under
     `docs/design/decisions/` are Hatchery's records and the base wins there, except the project's
     own lines, which are merged by hand as a mixed file: a `Status: superseded by NNNN` line and
     its pointer, and rows the project added to a table the ADR calls living (ADR 0001's
     components table). The "Decisions so far" list in `docs/design/README.md` takes both sides'
     lines, Hatchery's new rows and the project's.
   - Merge mode, clean adds too (they never reach this step, so look for them): files the base
     added under `docs/ideas/`, `docs/operator/`, `docs/findings/`, `docs/design/proposals/` and
     `docs/reference/` (except `claude-code-on-windows.md`) are Hatchery's own state. `git rm`
     them and drop their index rows, the same set decant steps 7 to 9 delete: `git diff
     --name-only --diff-filter=A HEAD...MERGE_HEAD -- <those directories>` lists them. Base ADRs stay (ADR 0008).
   - `CLAUDE.md` rule sections (This agreement, Where things live, Delegation, Debug interface,
     Major decisions, Usage gating, Git, Licensing): base wins unless the project has an ADR for
     the difference.
   - Mixed files: take the base version, then re-apply the project's lines by hand. Never leave
     conflict markers; `grep -rn "^<<<<<<<\|^>>>>>>>" .` must be empty before continuing.
   Merge mode finishes with `git commit` (the message is prepared). Patch mode: set the
   `Hatchery base:` line in the project's adoption ADR to `git rev-parse hatchery/main`, then commit
   via the runner (`commit` skill), subject `Update from Hatchery <old short sha>..<new short sha>`.
6. Post-merge checks, each must pass or be reported as a failure:
   - `node --version`; `node .claude/hooks/usage-gate.js < /dev/null` exits 0.
   - `wc -l CLAUDE.md docs/HANDOFF.md`: at most 150 and 40.
   - Every `.claude/skills/*/SKILL.md` and `.claude/agents/*.md` starts with frontmatter.
   - `.claude/settings.json` parses, and (adopted projects) still contains every project hook entry
     the adoption ADR lists.
   - If the project defines test and lint commands (`CLAUDE.md`, `README.md`, manifest), run them.
7. Do not push. The user decides; pushing goes through the `commit` skill's Pushing section.

## Report

- Mode, `FROM`, and the incoming base commits (the list from step 3).
- Files merged or patched cleanly, files resolved by rule (which rule), files kept as project
  version with the recorded reason.
- Post-merge check results, verbatim where they failed.
- Anything in the base that the project should act on: new skills to learn, changed hook
  behaviour, changed working-agreement rules, new ADRs to read. One line each.

## Rules

- Never `--force`, never rewrite history, never resolve by discarding the merge or patch silently.
- Patch mode never creates a merge commit and never joins the histories.
- If a conflict does not fit the rule above, stop, leave the merge or the conflicted files in place,
  and ask the user with both versions quoted.
