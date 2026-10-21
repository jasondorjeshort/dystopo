# 0016, sibling: the ownership map, its helper and harness group O

Companion to [ADR 0016](0016-base-owned-and-project-owned-files.md), item 1 and the parts of 2, 6
and 7 that the helper carries. Implementation spec; the ADR is the decision. Node built-ins only
(ADR 0013, group `F`). Corrected 2026-10-17 for the independent review (S1, S3, M2, M3, M8) and
the coder's findings (empty diff, binaries, O5).

## The file

`.claude/hooks/ownership.json`, base-owned, shipped by Hatchery and read from the base commit the
update comes from, never from the project's copy (below; `--check` is the one exception). Keys
starting with `$` are ignored; any other key outside the four lists makes the map malformed.

```
{
  "$comment": "Which paths an update from Hatchery patches. Schema: docs/design/decisions/0016-ownership-map.md",
  "baseOwned":    [".claude", "LICENSE", "LICENSES", "docs/hatchery"],
  "baseExcluded": [".claude/hooks/policy.json", ".claude/settings.local.json"],
  "mixed":        [".claude/settings.json", ".claude/hooks/fitness.json"],
  "projectOwned": ["CLAUDE.md", "README.md", ".gitignore", ".gitattributes", ".editorconfig",
                   ".claude/project", "docs/README.md", "docs/HANDOFF.md", "docs/handoff",
                   "docs/ideas", "docs/operator", "docs/findings", "docs/reference", "docs/design"]
}
```

The `$comment` cites this file where it is today; the migration's section 3.4 rewrites it to the
subtree path with the rest of the citation pass, and not before.

- Every entry is a literal repository-relative path, no wildcards, no leading `./` or `/`, no
  trailing `/`; a non-literal entry makes the map malformed. A directory entry covers its whole
  subtree. `projectOwned` names files and directories one by one; it never holds `docs` or a
  pattern. `docs/hatchery` is listed before it exists in any tree: a pathspec that matches nothing
  is harmless to `git diff`.
- The classes apply to paths the base ships, at `FROM` or at `<sha>`. Any other path is
  project-owned whatever entry covers its directory (a project's `.claude/rules/<lang>.md`, its
  own skills and agents), by the diff's construction: a diff between two base commits carries only
  base paths. A new base file with the same name as a project-added file is an `AA` conflict,
  named in the report; the project renames its file or takes the base's.
- Class of a base path: the longest entry that is the path or a parent of it, across the four
  lists. So `.claude/hooks/policy.json` is `baseExcluded` though `.claude` is `baseOwned`, and
  `.claude/project/x.md` is `projectOwned`. A base path matched by no entry is project-owned (the
  report lists it under `unclassified`). The same entry in two lists, a non-array, or a non-string
  entry make the map malformed.
- Meaning per class. Base-owned: patched three-way from the base line; a conflict is resolved base
  wins, unless the path has a departure row (project wins). Base-excluded: a base path that is the
  project's; never in the diff. Mixed: patched three-way; a conflict is hand-merged keeping both
  sides' entries (`settings.json`: the base's hook, `statusLine` and `permissions` entries and the
  project's; `fitness.json`: `deps` the base's, `skip` the project's). Project-owned: never in the
  diff; the base's copy is Hatchery's own state and never enters a project.
- The set may hold binary files (a skill's asset); the command carries `--binary` so that one
  applies, and a binary conflict is resolved by checkout like any other.

## Promise (ADR 0013 row, listed in the ADR)

Adding a newly shipped base path, or moving a path toward project-owned, is free. Moving a path a
project may have written from project-owned (or unlisted) into `baseOwned` or `mixed` is a major
choice: proposal, critic, ADR, and the ADR names how each sibling migrates (its departure rows or
`update.exclude` entries for that path).

## `update.exclude`

A new policy key (ADR 0006's living table): `update.exclude`, an array of path patterns, default
`[]`, under the same matcher as the other policy patterns (whole name; `*` matches anything, `/`
included). For files the project removed or replaced for good (its own `LICENSE`, a skill it
deleted); never for a departure from a hook or a skill, which is a departure row so that base
changes keep arriving as conflicts. `policy.js` validates it like the other pattern keys; a key an
older `policy.js` does not know makes the file malformed, which is why the update writes it only
after the new `policy.js` is in the tree ([the procedure](0016-update-procedure.md), order).
`adopt-hatchery` writes `["LICENSE", "LICENSES"]` when the user declines the licence (its step 8),
so a base edit to `LICENSE` never fails the apply on a file the project does not hold.

## The helper: `node .claude/hooks/ownership.js`

Reads the map of a given base commit through `child_process` (`git show <sha>:<path>`; MSYS does
not rewrite an argument a Node child process passes). Reads the policy from the working tree
through the tree's own `.claude/hooks/policy.js`, whichever version the tree holds when the flag
runs: before the apply the old one, after it the one the apply brought (so `--report` after the
apply validates `update.exclude`); a tree with no `policy.js` has no excludes beyond
`baseExcluded` and `--exclude`. Never falls back to `DEFAULTS` or, except for `--check`, to the
working tree's map. `imports.js` (for `--report`) is the tree's, or `<sha>`'s when the tree has
none. Flags, exactly one per run, `--ref <sha>` required for all but `--check`; a usage error
prints to stderr and exits 2; every error line below goes to stderr:

| Flag | Prints | Exit |
|---|---|---|
| `--command --ref <sha> [--exclude <pattern>]...` | One line, the update command, with literal shas, `-M --binary`, every pathspec single-quoted: `git diff -M --binary <FROM> <sha> -- '<baseOwned entries>' '<mixed entries>' ':(exclude)<entry or pattern>'... \| git apply --3way`. Entries of `baseExcluded`, `update.exclude` and `--exclude` become excludes, each as plain `:(exclude)<text>`, never with `glob` magic: under the default magic `*` matches across `/`, as the policy matcher does, whereas `glob` stops it at `/`; a `?` or `[` in a pattern becomes a one-character class (`[?]`, `[[]`) in the printed pathspec, so git and the policy matcher read the pattern the same way; likewise `*`, `?` and `[` in a `--per-path` path (`[*]`, `[?]`, `[[]`). Not a backslash: Git for Windows reads it in a pathspec as a directory separator, so `':(exclude)d/x\[1].md'` excludes nothing, while `x[[]1].md` matches only `x[1].md`. Never a `$(...)`, a variable or an unquoted word with `*`, `?` or `[`. When `git diff --name-status` with the same pathspec is empty: nothing on stdout, `ownership: nothing to apply: <FROM>..<sha> touches no path in the set` on stderr, exit 0, so `git apply` never sees an empty patch. Stdout is the bare command; the usage text and one stderr line say it is run in the Bash tool, never PowerShell (below). | 0 |
| `--per-path --ref <sha> [--exclude <pattern>]...` | One line per entry of `git diff --name-status -M <FROM> <sha> -- <the same pathspecs>`: `git diff -M --binary <FROM> <sha> -- '<path>' \| git apply --3way`; an `R` entry carries both of its paths in one line (`'<old>' '<new>'`). Each line is preceded by a comment line `# <status> <path>` (`# R <old> -> <new>`) so the agent can report the disposition per entry, a rename under its new path. An empty diff: as `--command`. | 0 |
| `--report --ref <sha> [--exclude <pattern>]...` | One section per heading, empty sections printed as `<heading>: none`: `unclassified` (changed paths in `FROM..sha` that match no entry in any list); `excluded` (changed paths in `baseExcluded`, `update.exclude`, `--exclude`); `replaced sections` (for each `@.claude/project/<s>.md` line reached from `CLAUDE.md`, whether `.claude/agreement/<s>.md` changed in `FROM..sha`); `unimported` (agreement files at `<sha>` with neither `@.claude/agreement/<s>.md` nor `@.claude/project/<s>.md` reached from `CLAUDE.md`); `collisions` (paths added in `FROM..sha` that exist in `HEAD` with different content, the `AA` case). | 0 |
| `--check` | Reads the working tree's map. One line per tracked path (`git ls-files`) that matches no entry in any list; nothing when all are classified. For Hatchery's tree only (the harness skips it where a `hatchery` remote exists). | 0 clean, 1 otherwise |

`FROM`: the first match of `grep -rhE "^Hatchery base: [0-9a-f]{7,40}" docs/design/decisions/`
in the working tree; else `git merge-base HEAD <sha>`; else the helper prints
`ownership: no base line and no merge-base; this is not a Hatchery project; see the adoption prompt
in README.md` and exits 1. Two different base lines in the tree: exit 1 naming both files.

On a malformed map at `<sha>`, or a policy the working tree's `policy.js` rejects, every flag
prints no command and one line naming the fix, then exits 1: `ownership: .claude/hooks/policy.json
malformed: <policy.js error>; a key from a newer base is one cause: remove it with the Edit tool
(git is denied while the policy is malformed), run the update, which brings the new policy.js,
then put the key back`, or `ownership: ownership.json at <sha> malformed: <reason>`. `--ref`
naming a commit that has no `ownership.json` prints `ownership: <sha> predates the map; update
from a newer base` and exits 1.

The printed command is run in the Bash tool, never in PowerShell: in PowerShell 5.1 the pipe
re-encodes the patch, every file fails with `patch does not apply` and `git status` stays empty,
which reads as a whole failure. The helper's usage text and a stderr line beside the command say
so; the update skill and the Windows reference say so too (the migration's tester brief).

On a tree whose `.claude/hooks/` predates the helper, the update skill first writes
`git show <sha>:.claude/hooks/ownership.js > <scratch>/ownership.js` and runs that copy with the
project as cwd (`node <scratch>/ownership.js <flag>...`) for every flag until the apply brings the
file; the helper takes no path from its own location, only from cwd and `<sha>`.

## The departure table

In the project's provenance ADR, a living table the ADR says is living:

```
| Path | Reason | Carry-back |
|---|---|---|
| `.claude/hooks/git-guard.js` | rule 12 allows `git push --delete` for the release bot (ADR 0024) | `docs/ideas/2026-11-02-guard-release-bot.md` |
| `.claude/agreement/git.md` | replaced by `.claude/project/git.md`: `master`, direct commits (ADR 0021) | none: project rule |
```

One row per base-owned path the project edits and per agreement section it replaces. Carry-back
is `carry-back/<slug> at <sha7>` once the change is sent (ADR 0019, which amended this sentence on
2026-10-18), the Hatchery commit that took the change, `none` with the reason the base will not
want it, or an idea file in the project for a request with no diff. The helper does not read the
table; the agent resolving a conflict does:
`git checkout HEAD -- <path>` for a rowed path, `git checkout <sha> -- <path>` otherwise. A base
rename the project edited leaves `D <old>` and `UU <new>`, where `git checkout HEAD -- <new>`
exits 1 (HEAD has no such path); the rowed form there is `git show HEAD:<old> > <new>` then
`git add <new>`, the base-wins form `git checkout <sha> -- <new>`; either leaves `R <old> ->
<new>` staged and the index buildable (verified 2026-10-17 on the review's fixture). A row whose
change Hatchery has taken is deleted in the update that brings the change back.

## Harness group `O` in `.claude/hooks/test-hooks.js`

Lines follow the ADR 0013 contract: `PASS O1 ownership`, `SKIP O1 ownership: <reason>`, `FAIL O1
ownership: <detail>; lift: <what>`. Each case runs in the scratch tree like the post-edit cases,
except O1.

- O1 `--check` on the real tree prints nothing and exits 0; `SKIP` where a `hatchery` remote
  exists. `FAIL O1 ownership: <path> matches no list in .claude/hooks/ownership.json; lift:
  classify it`.
- O2 the `--command` output is shell-inert: no `*`, `?`, `[`, `$`, backtick or unquoted word
  outside a single-quoted pathspec, and every pathspec carrying a wildcard is a single-quoted
  `:(exclude)` pathspec (plain, no `glob`). Fixture: a policy with `update.exclude:
  ["docs/legacy/*"]` and an `--exclude 'LICENSES'` argument.
- O3 a fixture repository where one commit adds a path and its map entry together: the command
  printed with `--ref` at that commit carries the path; the command from the parent does not.
- O4 a malformed map at `<sha>` (an unknown key, a wildcard entry), and separately a policy with an
  unknown key, each print no command, name the fix on stderr and exit 1.
- O5 after `git apply --3way` leaves a conflict in a fixture, `git checkout <sha> -- <path>` and
  `git checkout HEAD -- <path>` each clear the unmerged entry with no `git add` (`git status
  --porcelain` shows no `U`; confirmed by the coder 2026-10-17, so the conflict step has no add);
  and on a renamed-and-edited file the two rename forms above leave `R <old> -> <new>` and no `U`.
- O6 `--per-path` on a fixture with an `R` entry prints one line carrying both paths, and running
  it leaves the renamed file with the project's edit and no stranded copy.
- O7 `--command --ref HEAD` in a tree whose base line is `HEAD` prints nothing on stdout, the
  `ownership: nothing to apply` line on stderr, and exits 0.

Case `A1`, in a group `A` of its own: the files under `.claude/agreement/` total at most 150
lines; `SKIP` where a `hatchery` remote exists; `FAIL A1 agreement: <n> lines, cap 150; lift: ADR
amendment (architect)`.
