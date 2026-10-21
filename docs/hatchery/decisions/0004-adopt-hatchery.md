# 0004. Adopting the Hatchery practice into an existing project

Date: 2026-10-12
Status: accepted, superseded in part by 0016
Superseded in part by 0016 (`0016-base-owned-and-project-owned-files.md`), 2026-10-17: the owner's
2026-10-12 sentence below, "merging stays the path for projects that are based on Hatchery", is
superseded (one patch-only update for every project); so are the practice-set table (the ownership
map replaces its classes and the skeleton copies no record by name), the `CLAUDE.md` fold (the
agreement is imported from base-owned files) and step 1's two modes. Adoption as a recorded copy,
the adoption ADR with its base line, the licence choice and the preconditions stand.

Record: no proposal; the decision was taken against a real case (ReGenesis: 718 commits, Python,
branch `master`, its own 227-line `CLAUDE.md`, `docs/README.md` with four folders, `SessionStart`
and `Stop` hooks in `.claude/settings.json`, no `LICENSE`). Owner direction, 2026-10-12: adoption is
"not a git merge type of thing" but an "adopt this practice" kind of thing; merging stays the path
for projects that are based on Hatchery.

## Context

Hatchery has two entry points: `decant` (a fresh clone becomes a new project, so the project shares
history with Hatchery and `update-from-hatchery` is a plain merge) and `update-from-hatchery` (merge
`hatchery/main`). A project that already exists cannot use either: it has no shared history, so
`git merge hatchery/main` would be an unrelated-history merge, and it has no Hatchery skill to load.
The README's update prompt, pasted into ReGenesis, stopped for exactly those reasons without saying
what to do instead.

Three ways to bring the practice into such a project were considered:

1. Unrelated-history merge (`git merge --allow-unrelated-histories hatchery/main`), so later updates
   are ordinary merges. Rejected by the owner: it joins two histories into one and makes the project's
   log carry Hatchery's. It also forces a resolution of every add/add conflict inside one merge commit.
2. Copy the files once with no record of what was copied. Rejected: every later update is a blind
   re-copy that cannot tell Hatchery's changes from the project's edits.
3. Copy the files from `hatchery/main`, record the Hatchery commit they came from, and update later by
   applying the patch between that commit and the new `hatchery/main` three-way onto the project's
   tree. Chosen.

Verified 2026-10-12 in a scratch worktree of this repository at commit `16304e3` with a locally
edited `CLAUDE.md` (new title, added `## Project rules` section), staged: `git diff 16304e3 main --
.claude CLAUDE.md | git apply --3way` applied 29 files, left `CLAUDE.md` with the project's edits and
Hatchery's changes, and `git diff main -- .claude` was empty. With an edit on a line Hatchery also
changed, `git apply` reported "with conflicts", `git diff --name-only --diff-filter=U` listed the
file, and the file carried `<<<<<<< ours` / `>>>>>>> theirs` markers. Two requirements observed:
the working tree must match the index (`git apply` refused "does not match index" otherwise; a clean
tree satisfies it), and the base blobs must be in the object store (they are: the recorded base is an
ancestor of `hatchery/main`, which `git fetch hatchery` brings).

## Decision

### Adoption is a copy, recorded, with history untouched

The `adopt-hatchery` skill (new, tenth skill; a separate skill rather than a `decant` mode because it
runs in the project's clone, works in the opposite direction, needs user decisions at several steps,
and must be readable by path from the remote: `git show hatchery/main:.claude/skills/adopt-hatchery/SKILL.md`)
does the following. Transport is the `hatchery` remote and `git checkout hatchery/main -- <path>`;
no merge commit, no rebase, no rewrite of the project's history.

The practice set, three classes:

| Class | Paths | At adoption | On update (patch mode) |
|---|---|---|---|
| Verbatim, base wins | `.claude/agents/`, `.claude/skills/`, `.claude/hooks/`, `.claude/statusline.js`, `.claude/rules/README.md`, `.claude/rules/docs.md` | copied | patched; project edits only with a recorded reason |
| Merged, both survive | `.claude/settings.json` (Hatchery `statusLine`, `permissions`, hook entries added; the project's own hook entries kept), `CLAUDE.md` (fold, below), `.gitignore` (`.claude/settings.local.json` line added) | merged by hand | `settings.json` and `CLAUDE.md` patched three-way; `.gitignore` not patched |
| Skeleton, only where absent | `docs/HANDOFF.md`, `docs/backlog.md`, `docs/findings/README.md`, `docs/reference/README.md`, `docs/design/README.md`, `docs/design/proposals/README.md`, `docs/design/decisions/README.md`, ADRs `0001`, `0002`, `0002-hook-specs.md` | copied, indexes emptied | never patched; ADRs are records |

Not copied: `README.md`, `.editorconfig`, `.gitattributes`, Hatchery's findings, reference files,
proposals, and this ADR (the skill carries the procedure; the project's own record links here).
`LICENSE` and `LICENSES/` only after the user decides (below).

The project's existing `docs/` tree is not restructured. Its `docs/README.md` gains one row per
added path and keeps its own folders; `docs/HANDOFF.md` links into whatever the project already uses
for current work. If the project has no `docs/design/README.md`, the skill writes one that indexes
the existing files (one line each) and adds "Decisions so far"; if it has one, only the list is added.

### CLAUDE.md fold

The adopted `CLAUDE.md` is Hatchery's file with the project in the project-specific slots:

- Project-specific slots: the title; the provenance sentence ("adopted from Hatchery at `<sha>`, ADR
  NNNN"); `## Phase` (the project's actual phase; a project with code is not "design first"); the usage
  thresholds; the model-routing table; the branch name in `## Git`; `## Licensing` per the license
  decision; a new `## Project rules` section holding the project's every-turn rules that do not
  contradict a rule section.
- Rule sections, Hatchery text verbatim: This agreement is part of the work, Where things live,
  Delegation, Debug interface, Major decisions, Usage gating (table), Model routing (structure), Git
  (except branch name), Licensing (structure).
- Material that is path-scoped (language conventions, test commands) goes to `.claude/rules/<name>.md`
  with `paths:`; procedures go to a skill or stay in the project's existing docs with a pointer; facts
  go to `docs/reference/`. The result is under 130 lines.
- A contradiction (a project rule that forbids or mandates what a rule section mandates or forbids) is
  never decided by the model: the skill lists each pair with both texts quoted, asks the user, and
  waits. Where the user keeps the project rule, the project's text replaces Hatchery's lines and the
  project's adoption ADR records it as the reason to differ, which the update rule already honours.

### The project's adoption ADR and the recorded base

The skill writes `docs/design/decisions/NNNN-adopt-hatchery.md` in the project from
`.claude/skills/adopt-hatchery/template-adr.md` (next free number in the project's tree). It carries
one living line, `Hatchery base: <full sha>`, the commit of `hatchery/main` the files came from; the
`update-from-hatchery` skill reads it with
`grep -rho "Hatchery base: [0-9a-f]\{7,40\}" docs/design/decisions/` and bumps it after each update.
The ADR also records: the license decision; which project hook entries were kept and why; each rule
section the project overrides and the user's decision; existing docs over the 300-line cap and what
was done (split, or exempted by a recorded edit to pusher check 7 and `post-edit.js`); whether SPDX
headers are added to existing files or only to new ones.

### License

`LICENSE` absent: ask the user. Yes: copy `LICENSE` and `LICENSES/`, keep ADR 0001, add the SPDX
rule for new files, put the retrofit of existing files in `docs/backlog.md`. No: copy nothing, write
`## Licensing` as one line stating the project's terms, and have the adoption ADR supersede 0001.
`LICENSE` present and different: keep it, do not copy `LICENSES/`, adoption ADR supersedes 0001.

### Updates for adopted projects: patch mode of `update-from-hatchery`

One update skill, two modes, chosen by step 1: `git merge-base HEAD hatchery/main` succeeds, merge
mode (decanted project, unchanged); it fails and a `Hatchery base:` line exists, patch mode; neither,
stop and name the adoption prompt. Amended by ADR 0010: the base line is tested first and wins
over `merge-base`. Patch mode: `git log --oneline <base>..hatchery/main` is the
incoming list; `git diff <base> hatchery/main -- .claude CLAUDE.md` (plus `LICENSES` when present) is
piped to `git apply --3way`; conflicts are resolved by the same rule as merge mode; the base line is
bumped; the runner commits. Why not re-run `adopt-hatchery`: adoption makes one-time decisions with
the user (license, contradictions, hooks, docs map) and cannot tell what changed in Hatchery; the
patch is exactly "Hatchery's changes since the recorded base", which is the question merge mode
answers with the merge-base. One README prompt then updates either kind of project.

### Preconditions, stated where they fail

Each README prompt is self-contained and states its preconditions and what to do when one fails: the
adoption prompt reads the skill from `hatchery/main` because the project has no skill to load; the
update prompt stops when the skill is absent locally (neither decanted nor adopted) and points at the
adoption prompt; `update-from-hatchery` step 1 checks the remote and the merge-base or base line and
names the adoption prompt. The default branch is whatever the project uses; nothing says `main`
literally.

Deferred: ADR numbering collision between Hatchery and its projects (a project's next ADR and
Hatchery's next ADR can both be `0003`); trigger: the first merge-mode update that brings an ADR whose
number the project already used. Candidate fix: Hatchery reserves `0001`-`0019`. Resolved by ADR
0008: Hatchery uses `0001` to `0019`, a decanted project starts at `0020`, an adopted project
keeps its own numbering.

## Consequences

- An adopted project's history stays its own; the cost is one living line and a patch step instead of
  a merge. `git apply --3way` needs a clean tree, which the skill already requires.
- `.claude/` is base-owned in adopted projects too, so the project's hooks must live as entries beside
  Hatchery's in `settings.json`, not as edits to Hatchery's scripts.
- Hooks, agents, and skills load at session start: the adoption commit is made by the main session,
  then Claude Code is restarted before the practice is in force.
- A project that overrides a rule section conflicts on every update that touches those lines; the
  adoption ADR makes the resolution mechanical.
- ADR 0002's skill count is ten; its Skills section carries a pointer here. Supersede this ADR if
  Hatchery ever ships as a package or template rather than a repository to copy from.
