---
name: update-from-hatchery
description: Apply the base-owned changes of the Hatchery repository to a decanted or adopted project as one three-way patch from its recorded base line, resolving conflicts by the ownership map and the departure table. Ends with the brainstorm offer (the `brainstorm` skill). Use when asked to update from Hatchery, sync the base, or take base changes.
---

# Update from Hatchery

One procedure for every project, decanted or adopted: a three-way patch of the base-owned paths
from the project's recorded base line to the base's new commit (ADR 0016). Which paths are
base-owned is the ownership map `.claude/hooks/ownership.json` at the new commit; the helper
`.claude/hooks/ownership.js` prints every command and you run it verbatim. No merge, no joined
histories. Read the skill at `<sha>` first (step 2): this copy may predate the procedure.

Run every printed command in the Bash tool, never PowerShell: its pipe re-encodes the patch and
`git apply` fails every file with `patch does not apply`, leaving `git status` empty.

## Steps

1. Preconditions, stop at the first failure and say which:
   - `git status --porcelain` is empty (commit first; `git apply --3way` refuses a dirty base file).
   - The relation to Hatchery: a base line,
     `FROM=$(grep -rhE "^Hatchery base: [0-9a-f]{7,40}" docs/design/decisions/ | head -1 | cut -d' ' -f3)`,
     else (tested in step 2, after the fetch) a merge-base with `hatchery/main` (a project decanted
     before ADR 0016, once), else stop and name the README prompt "Adopt the Hatchery practice".
     The line wins over a merge-base.
   - `node --version` works.
2. `git remote add hatchery https://github.com/jasondorjeshort/hatchery.git` if missing; `git fetch
   hatchery`; `S=$(git rev-parse hatchery/main)`. Without a base line, `FROM=$(git merge-base HEAD
   $S)`; if that prints nothing, stop as step 1 says. From here name the sha literally in every
   command: `git show <sha>:<path>`, never the branch name before the colon, which MSYS rewrites in
   Git Bash. Read `git show <sha>:.claude/skills/update-from-hatchery/SKILL.md` and follow that
   copy if it differs. The incoming list is `git log --oneline <FROM>..<sha>`; it goes in the
   commit body. On a tree without `.claude/hooks/ownership.js`, write it to the scratchpad with
   `git show <sha>:.claude/hooks/ownership.js > <scratch>/ownership.js`; "the helper" is then
   `node <scratch>/ownership.js` from the project root until the apply brings the file.
3. `the helper --command --ref <sha>` (add `--exclude '<pattern>'` for a path the project removed
   and has not yet put in `update.exclude`; single-quote a pattern that contains `*`). Run the one line it prints. Outcomes:
   - Nothing on stdout, `ownership: nothing to apply` on stderr: no path in the set changed. Go to
     step 6, or stop if the base line already is `<sha>`.
   - Exit 0, changed paths in `git status --porcelain`: applied clean. Step 6.
   - Exit 1, `U`, `AA` or `DU` entries, markers in files: conflicts. Step 4.
   - Exit 1, `git status --porcelain` empty: whole failure (or it ran in PowerShell: rerun in Bash).
     Step 5.
   - Exit 128 `No valid patches in input`: nothing to apply. Step 6.
   - The helper prints one `ownership:` line and exits 1 (malformed policy or map, no base line,
     `<sha>` predates the map): do what the line says; a policy that does not load is fixed with
     the Edit tool, since git is denied until it loads.
4. Conflicts: `git diff --name-only --diff-filter=U`. Per path, by class:
   - base-owned, no departure row: `git checkout <sha> -- <path>`. With a row in the project's
     departure table (its provenance ADR): `git checkout HEAD -- <path>`, row stays; if the base
     took the project's change, take the base's and delete the row. A row whose `Carry-back` cell
     is `carry-back/<slug> at <sha7>` is settled by the incoming commits, conflict or not
     (`git log <FROM>..<sha> --format='%h %s' --grep='carry-back/<slug> at <sha7>'`; the line is in
     the body, so the oneline list does not show it): a commit with subject `Carry-back: ...` and body line `Carry-back: <repo> carry-back/<slug> at <sha40>`
     is the base taking it (take the base's, delete the row); one with subject `Carry-back
     declined: <repo> carry-back/<slug> at <sha40>: <reason>` keeps the row and sets the cell to
     `none: <reason>` (`carry-back` skill, ADR 0019).
   - a base rename the project edited (`D <old>` with `UU <new>`): with a row,
     `git show HEAD:<old> > <new>` then `git add <new>`; without, `git checkout <sha> -- <new>`.
   - mixed (`settings.json`, `fitness.json`): hand-merge keeping both sides' entries, `git add`.
   - `AA` (a base addition colliding with a project file): report it; the project renames its
     file or takes the base's with `git checkout <sha> -- <path>`.
   A checkout clears the unmerged entry; no `git add` after it. Then
   `git grep -n "^<<<<<<<" -- .claude docs/hatchery LICENSE LICENSES` must be empty (it names tracked
   paths only, so an absent `docs/hatchery` is no error).
5. Whole failure: `the helper --per-path --ref <sha> [--exclude ...]`, run each printed line. A
   line that fails is reported with its entry and a disposition (a rename under its new path):
   - `D`, `does not exist in index`: already done, nothing to do.
   - `M` or `R`, `does not exist in index`: the project deleted the file; add the path to
     `update.exclude` (after step 7's policy check) or `git checkout <sha> -- <path>`.
   - `D` onto a modified file: `git rm <path>` unless it has a departure row ("kept: row"); never
     an exclude for a hook or skill.
   - `does not match index`: step 1 was skipped; stop, commit or stash, start over.
   - conflict markers: step 4 for that path.
6. `the helper --report --ref <sha> [--exclude ...]` with the same excludes; its sections go in the
   report, and these need an action:
   - `unclassified`: did not arrive; file an idea titled `For the base: ` (`brainstorm` skill) for
     Hatchery to classify.
   - `excluded`: seen, not applied; a guard fix to an excluded hook is the project's by hand.
   - `replaced sections`: read the base's new text against the project's replacement file under
     `.claude/project/`; update it or drop it and restore the import line.
   - `unimported`: add the import line to `CLAUDE.md`.
   - `collisions`: the `AA` list, resolved in step 4.
7. First update under this procedure (the tree ran an older skill), in this order: the apply
   (steps 3 to 5) brings `policy.js`, `ownership.js`, `imports.js` and `.claude/agreement/`;
   `node .claude/hooks/policy.js` must print the policy (else fix it with the Edit tool before
   anything); then in the same commit the migration edits in
   `git show $S:docs/hatchery/decisions/0016-migration.md` section 4 (`CLAUDE.md` to the project
   shape, the provenance ADR with its departure table); `update.exclude` only now, never earlier.
   Later updates skip this step.
8. Every update: set the `Hatchery base:` line in the provenance ADR to `<sha>`, only when every
   failed path has a disposition (excluded, taken, already done, kept: row); otherwise a path
   would drop out of every later diff unseen. On a first update the ADR is step 7's.
   Then the ideas this project leaves to the base (`brainstorm` skill, "After an update from the
   base"), in Bash from the root, with the sha written out:

   ```
   repo=$(basename -s .git "$(git remote get-url origin 2>/dev/null || pwd)"); E=$(mktemp)
   F=$(git log <sha> --format='%x01%h%n%B' | awk 'index($0, "\001") == 1 { h = substr($0, 2); next } /^Base-idea: / { sub(/[ \t\r]+$/, ""); print h "\t" $0 }')
   j=$(node .claude/hooks/queue-list.js ideas --json 2>"$E") || echo "base-idea: listing failed: $(head -1 "$E")"; rm -f "$E"
   [ -n "$j" ] && printf '%s' "$j" | node -e '
   let rows; try { rows = JSON.parse(require("fs").readFileSync(0, "utf8")); } catch (e) { console.log("failed -"); rows = []; }
   for (const r of rows) if (String(r.title).startsWith("For the base: ")) console.log(r.block + " " + r.file);
   ' | while read -r b f; do
     [ "$b" = failed ] && { echo "base-idea: listing failed: not JSON"; continue; }
     hit=$(printf '%s\n' "$F" | L="Base-idea: $repo docs/ideas/$f" awk -F'\t' '($2 "") == ENVIRON["L"] { print $1; exit }')
     n=$(grep -rlF -- "$f" docs/design/decisions/ | head -1)
     echo "base-idea: $f: ${hit:+filed }${hit:-kept} ($b${n:+, named in $n})"
   done
   ```

   `git rm` each file whose line ends `filed <sha7> (untriaged)`, and no other: the base holds it
   as its own idea, and the deletion rides in step 10's commit. `filed` is the whole `Base-idea:`
   line found as written in a commit of the base's history up to `<sha>`, compared as a string and
   never as a pattern. A line that ends `named in <path>)` is kept while that record names the file
   (a departure row's `Carry-back` cell, ADR 0019, would otherwise point at nothing); it goes at
   the first update after the record stops naming it. Every line that is not a deletion goes in
   the report as printed; `listing failed` or a block printed `?` means a file was not read, and
   nothing is deleted on its account.
9. Checks, each must pass or be reported verbatim: `node --version`;
   `node .claude/hooks/usage-gate.js < /dev/null` exits 0; the lines of pusher check 7 print no
   `FAIL`: `--cap-check` over the docs, the handoff total, and
   `node .claude/hooks/policy.js --claude-check`; every `.claude/skills/*/SKILL.md` and
   `.claude/agents/*.md` starts with frontmatter; `.claude/settings.json` parses and keeps the
   project's hook entries; the project's test and lint commands, if it defines them.
10. Commit from the agent policy allows (the `runner` below HOLD, the main session at any level),
   subject `Update from Hatchery <FROM7>..<sha7>`, body the incoming list. Do not push; pushing
   goes through the `commit` skill's Pushing section.
11. The brainstorm offer (`brainstorm` skill, "After an update from the base"), once step 10's
   commit exists: every update ends in one, at HOLD too and never at STOP, and it does not count as
   the session's prompted offer.
   Whoever ran steps 1 to 10 lists up to nine candidates, one line and an anchor each, each marked
   `base` (a friction or a gap in a base-owned file that this update showed) or `project` (what the
   incoming changes suggest for the project's own rules, policy, skills and docs); a subagent puts
   them in its report, since only the coordinator can ask the owner. A defect is not a candidate:
   it is filed as before. An update that stopped before step 10 makes no offer.

## Report

- `FROM`, `<sha>`, the incoming commits; paths applied cleanly, resolved by rule, kept by row.
- Every failed path with its disposition; departure rows written; excludes added.
- The `--report` sections, the actions taken for each, and check results (failures verbatim).
- What the project should act on: new skills, changed hooks or agreement text, new ADRs to read.
- The ideas left to the base: step 8's lines, and which files were deleted.
- Defects this update showed in a base-owned file, one line and an anchor each: the coordinator
  files each as before, without an offer, titled `For the base: ` unless the project is fixing it
  itself.
- Up to nine candidates for the offer, one line and an anchor each, marked `base` or `project`
  (step 11). The coordinator makes the offer in the turn in which it reports this update to the
  owner.

## Rules

- Never `--force`, never rewrite history, never resolve by discarding the patch silently.
- Never `git merge` and never join histories; the helper's command is the only way the base arrives.
- A conflict that fits no rule above: stop, leave the files in place, ask with both versions quoted.
