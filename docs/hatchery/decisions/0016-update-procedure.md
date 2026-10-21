# 0016, sibling: the update procedure

Companion to [ADR 0016](0016-base-owned-and-project-owned-files.md), item 2 and item 4's checks
at the push. This is what the `update-from-hatchery` skill says after the tester rewrites it; the
skill carries the steps, this file is their specification. Paths are quoted from
[the map](0016-ownership-map.md); the helper prints every command, the agent runs it verbatim.
Corrected 2026-10-17 for the independent review (S1, S2, S5, S6, M2, M3, M5, M8) and the coder's
findings (empty diff, binaries, O5).

## Preconditions (step 1)

- A clean tree: `git status --porcelain` empty. `git apply --3way` refuses a base-owned file with
  uncommitted changes (`does not match index`), and the refusal is whole.
- The relation, not where the skill was read from: a base line
  (`grep -rhE "^Hatchery base: [0-9a-f]{7,40}" docs/design/decisions/`), else a merge-base with
  `hatchery/main` (a project decanted before this decision, once), else stop and name the adoption
  prompt in `README.md`. A tree with both uses the line (ADR 0010).
- `node --version` works; the `hatchery` remote exists (`git remote add hatchery <url>` if not).
- The agent running the skill may be any agent: the command below is allowed for every caller by
  the guard (`git diff ... | git apply --3way`, `git checkout <ref> -- <path>`, `git show
  <ref>:<path> > <path>`, a `for` loop over written-out paths). No `git merge`, no `xargs git
  checkout`.

## Fetch and name the base (steps 2 and 3)

`git fetch hatchery`; `S=$(git rev-parse hatchery/main)` and then every command names the sha
literally (`git show <sha>:<path>`, never `git show hatchery/main:<path>`, which MSYS rewrites on
Windows). The incoming list is `git log --oneline <FROM>..<sha>`; it goes in the commit body.
Read the skill at `<sha>` first (`git show <sha>:.claude/skills/update-from-hatchery/SKILL.md`),
since the tree's copy may predate this procedure. On a tree without `.claude/hooks/ownership.js`,
extract it: `git show <sha>:.claude/hooks/ownership.js > <scratch>/ownership.js`; below, "the
helper" means `node <scratch>/ownership.js` from the project root until the apply brings the
file, and `node .claude/hooks/ownership.js` after.

## The one command (step 4)

The helper's `--command --ref <sha> [--exclude <pattern>]...` prints one line; run it as printed,
in the Bash tool, never in PowerShell (there the pipe re-encodes the patch: every file fails with
`patch does not apply` and `git status` stays empty, which the table below would read as a whole
failure). `--exclude` is for a path the project knows it removed and that is not yet in
`update.exclude` (a first update under this procedure; see the order below). The pathspec is the
same for every tree; the base line only sets `FROM`.

Outcomes:

| Exit | `git status --porcelain` | Meaning | Next |
|---|---|---|---|
| helper 0, nothing on stdout, `ownership: nothing to apply` on stderr | empty | already up to date: no path in the set changed in `FROM..sha`; `git apply` never ran | step 6, or stop if the base line already is `<sha>` |
| `git apply` 0 | changed paths | applied clean | step 6 |
| 1 | `U`, `AA` or `DU` entries, markers in files; a base rename the project edited shows as `D <old>` with `UU <new>` | conflicts | step 5 |
| 1 | empty | whole failure: nothing written (or the command was run in PowerShell: rerun it in Bash first) | per path, below |
| 128, `No valid patches in input` | empty | an empty patch reached `git apply`: a hand-written command or an older helper | nothing to apply: step 6 |

## Conflicts (step 5)

`git diff --name-only --diff-filter=U` lists them. For each path, by the map's class:

- base-owned without a departure row: `git checkout <sha> -- <path>`.
- base-owned with a row in the departure table: `git checkout HEAD -- <path>`; the row stays. If
  the base's change is the project's own carried back, take the base's and delete the row.
- a base rename the project edited (`D <old>`, `UU <new>`): HEAD has no `<new>`, so `git checkout
  HEAD -- <new>` exits 1. With a row: `git show HEAD:<old> > <new>` then `git add <new>`; without:
  `git checkout <sha> -- <new>`. Either leaves `R <old> -> <new>` staged (verified 2026-10-17 on
  the review's fixture; harness case O5). A row names the new path from here on.
- mixed: hand-merge keeping both sides' entries, then `git add <path>`.
- `AA` (a base addition colliding with a project file): the report names it; the project renames
  its file or takes the base's with `git checkout <sha> -- <path>`.

A checkout clears the unmerged entry by itself (harness case O5, confirmed): no `git add` after
one. Then `grep -rn "^<<<<<<<" .claude docs/hatchery LICENSE LICENSES` must be empty.

## Whole failure: per path

Exit 1 with an empty `git status --porcelain` means `git apply` placed nothing. Run the helper's
`--per-path --ref <sha> [--exclude ...]` and run each printed line. A line that fails is reported
with its entry and this disposition; a rename's disposition names its new path, the one `git diff
--name-only` lists, so that the next update's diff is never failed by a path nobody recorded:

| Entry | `git apply` says | Disposition |
|---|---|---|
| `D` | `does not exist in index` | already done: the project deleted it earlier; nothing to do |
| `M` | `does not exist in index` | a base edit to a file the project deleted: add the path to `update.exclude` (after the policy step below), or take the base's file with `git checkout <sha> -- <path>` |
| `D` | onto a modified file (`patch does not apply`) | the project edited a file the base deleted: delete it (`git rm <path>`) unless it has a departure row, in which case it stays and the disposition is "kept: row"; never an exclude for a hook or skill (ADR 0016, item 6); the base's later diffs do not name a path it no longer has |
| `R` | `does not exist in index` (the old path) | the project deleted the file the base renamed: as the `M` row, on the new path: `update.exclude` gains `<new>`, or `git checkout <sha> -- <new>` |
| `R` | conflict markers, `D <old>` with `UU <new>` | step 5's rename form |
| any | `does not match index` | step 1 was skipped: stop, commit or stash, start over |
| any | conflict markers | a conflict: step 5 for that path |

A renamed base file arrives as a rename because the command and the per-path lines carry `-M`
and the `R` entry both paths; applying only the new path would strand the old one without the
project's edit.

## The report (`--report --ref <sha> [--exclude <pattern>]...`)

Run after the apply, before the commit, with the same `--exclude` arguments as the command; its
sections go in the update report and the ones below need an action:

- `unclassified`: a base path no list classifies changed in `FROM..sha`. It did not arrive (an
  unlisted path is project-owned). File it as a carry-back idea for Hatchery to classify; take it
  by hand with `git checkout <sha> -- <path>` only if the project wants it.
- `excluded`: a base change to a path in `baseExcluded`, `update.exclude` or `--exclude`. Seen,
  not applied; a guard fix to an excluded hook is the project's to take by hand.
- `replaced sections`: the base changed an agreement section this project replaces. Read the
  base's new text against the replacement file; update the replacement or drop it and restore the
  import line. The departure row stays while the replacement exists.
- `unimported`: an agreement file with no import and no replacement reached from `CLAUDE.md`,
  which `--claude-check` fails below. Add the line.
- `collisions`: the `AA` list, resolved in step 5.

## The first update under this procedure: the order

Every sibling runs an older `policy.js` and an older skill, so the first update is in this order,
and the skill says so:

1. The apply, whole or per path, with any excludes as `--exclude` arguments. It brings
   `.claude/hooks/policy.js`, `ownership.js`, `imports.js` and `.claude/agreement/` with the rest
   of `.claude/`.
2. `node .claude/hooks/policy.js` prints the policy with the new `policy.js` now in the tree. If it
   does not load, fix the file with the Edit tool before anything else; git is denied until it
   loads.
3. The migration edits in the same commit ([the migration](0016-migration.md), per sibling):
   `CLAUDE.md` to the project shape; a project decanted before this decision writes its
   provenance ADR now, from the template, with the base line left blank for step 5; the departure
   table in that ADR; the record copies removed and the citations rewritten where the migration's
   section 3.4 has shipped. `--claude-check` fails until `CLAUDE.md` is in shape.
4. `update.exclude` written now, never before step 2.
5. The base line written as `<sha>` in the provenance ADR (the one step 3 created, for a decanted
   project), only when every path the apply or a per-path line failed on has a disposition in the
   report (excluded, taken, already done, kept: row). A path without a disposition would drop out
   of every later diff and never be seen again.
6. Pusher check 7's lines by hand: `--cap-check`, the handoff total, and `--claude-check`, which
   replaces check 7's `^@` grep line; commit from the agent policy allows (the runner below HOLD;
   the main session at any level), subject `Update from Hatchery <FROM7>..<sha7>`, body the
   incoming list.

Later updates are the same steps without 3; a change to the agreement reported under `replaced
sections` is the one migration-like edit they carry.

## Checks at the edit and at the push

`node .claude/hooks/policy.js --claude-check` is a new line of pusher check 7, run on every push,
and `post-edit.js` prints the same lines as `additionalContext` on an edit to `CLAUDE.md`,
`.claude/agreement/*.md` or `.claude/project/*.md`. Both call `.claude/hooks/imports.js`:
`resolve(root, file)` returns every import reached from `file` to four hops as `{ file, line,
target, exists }`, an `@` counting when it is at line start or after whitespace, outside a code span
and outside a fenced block, its token running to the next whitespace with a trailing `.`, `,`,
`;`, `:` or `)` stripped (the sentinel procedure in the reference entry settles the token rule
against the loader; the parser follows the loader). Every check below counts over every file the
chain reaches at any of the four hops, not over `CLAUDE.md` alone. An import whose target is
outside the repository root (`../x`, `~/x`, an absolute path) is marked by the parser, never read,
not counted toward the 190 and never a `FAIL`: a project may keep a personal home import, and the
cap measures only what the repository ships; `--claude-check` lists such imports in one info line
(`info: <n> imports outside the repository, not counted: <paths>`). Lines, exit 1 on any `FAIL`, a
summary line `claude-check: <n> files, <m> FAIL` last:

- `FAIL CLAUDE.md: section <s> neither imported nor replaced` (an `.claude/agreement/<s>.md` exists
  and no file in the chain imports `.claude/agreement/<s>.md` or `.claude/project/<s>.md`).
- `FAIL CLAUDE.md: section <s> imported twice` (two lines anywhere in the chain resolve to the
  same agreement file, or one to the agreement file and one to its replacement).
- `FAIL .claude/project/<s>.md replaces no agreement section` (no `.claude/agreement/<s>.md`).
- `FAIL CLAUDE.md: <n> lines, cap 40; lift: edit.capExempt naming CLAUDE.md`.
- `FAIL CLAUDE.md: loads <n> lines, cap 190; lift: edit.capExempt naming CLAUDE.md` (the sum of
  `CLAUDE.md` and every file reached at any hop, each once, excluding `docs/HANDOFF.md` and
  everything reached through it, which keep ADR 0014's 120).
- `FAIL <file>: @<p> resolves to <path>, which does not exist` (or `which is a directory`, for a target that is a directory and so loads nothing; check 7's `^@` grep line today,
  now from the parser, for `CLAUDE.md`, the handoff files and every file they import; the grep
  line goes).

The two caps are constants in `policy.js` beside `docCap` (`CLAUDE_MD_CAP = 40`,
`CLAUDE_LOADED_CAP = 190`), lifted together, and only, by `edit.capExempt` containing the exact
string `CLAUDE.md`; a pattern never lifts them. `post-edit.js` loses its own 150 constant. The
guard's malformed-policy deny gains one sentence: a key from a newer base is one cause; remove it
with the Edit tool (git, this checkout included, is denied until the policy loads), run the
update, which brings the new `policy.js`, then put the key back.
