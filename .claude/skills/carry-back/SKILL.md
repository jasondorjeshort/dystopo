---
name: carry-back
description: Send a project's built change to a base-owned path back to Hatchery as a carry-back/<slug> branch in the project's own repository (Send, run in the sibling), or land such a branch in Hatchery as one fresh reviewed commit, or decline it (Land, run in Hatchery). Use when a departure row or a base bug fix is ready to go back, when triage lists a pending carry-back, or when asked to carry back or land a change.
---

# Carry-back

How a sibling's change reaches the base (ADR 0019). The sibling pushes a `carry-back/<slug>` branch
cut from a named `hatchery/main` sha to its own `origin`; Hatchery fetches it by URL into
`FETCH_HEAD`, applies the diff, reviews it and commits it fresh under its own identity. No sibling
pushes into Hatchery's repository. A request without a diff, a question, or a change to a rule, an
agreement file, a decision or a promised surface (ADR 0013) is not a carry-back: Hatchery's
coordinator files it as an idea in Hatchery's `docs/ideas/` from the sibling's text.

One change per branch: one departure row or one bug, with the spec edits the docs rule requires.
Living text never names the sibling; the landing message does. Run every command in the Bash tool,
never PowerShell (its pipe re-encodes a patch). Write every sha literally in `git worktree`,
`git commit` and `git push`: the guard denies a variable there (rule 19).

## Send (in the sibling)

The coder runs steps 1 to 3 and 5; the agent the sibling's `git.commit.subagents` allows runs 4
and 7 (the `runner` under the base policy, since guard rule 4 denies a coder `git commit`); the
pusher runs step 6.

1. `git fetch hatchery`; `git rev-parse hatchery/main` prints `<B>`. Every later command names it.
2. `git worktree add ../<repo>-wt/carry-back-<slug> -b carry-back/<slug> <B>`. `<repo>` is the
   sibling's directory name; `<slug>` is kebab-case and names the path or the bug, never a person.
3. Make the change in that worktree, with its spec edit and harness cases;
   `node .claude/hooks/test-hooks.js` passes there.
4. One commit by the committing agent, from the coder's report: subject `Carry-back: <what
   changed>`, body: the reason, then the line `Carry-back-base: <B>` (the full sha), then the
   `Co-Authored-By` line of the model that wrote the change.
5. The send check, in the worktree; stop at the first refusal and report it verbatim:
   - `git rev-list carry-back/<slug> --not --remotes=hatchery` lists only the step 4 commit. Else:
     `carry-back: the branch is not cut from hatchery/main; rebuild: git worktree remove ../<repo>-wt/carry-back-<slug> && git branch -m carry-back/<slug> carry-back/<slug>-wrong-base && git worktree add ../<repo>-wt/carry-back-<slug> -b carry-back/<slug> <B>`.
     After the rebuild, bring the change over by its own commit `<c>`
     (`git diff <c>~1 <c> | git apply --3way` in the new worktree) and repeat steps 3 to 5. The
     `-wrong-base` branch is never pushed; deleting it is the owner's.
   - The branch is cut from `<B>` itself, not an older sha: `git merge-base --is-ancestor <B>
     carry-back/<slug>` exits 0 and
     `for c in $(git rev-list carry-back/<slug> --not <B>); do git log -1 --format=%b "$c" | grep -qx "Carry-back-base: <B>" || echo "$c"; done`
     prints nothing. Else: `carry-back: the branch is not cut from its Carry-back-base <B>`, and
     the rebuild above.
   - The path check, on every entry of `git diff --name-status -M <B> carry-back/<slug>` against
     the map `ownership.json` at `<B>`. An entry passes as (a), a base change, when its path is in
     `baseOwned` or `mixed` (an `R` entry: both paths); or as (b), a record, when it is an `M`
     entry under `docs/design/decisions/` or `docs/reference/` (a branch cut from a base older than
     the move of Hatchery's records to `docs/hatchery/`, ADR 0016 migration 3.4; from a newer base the
     records are base-owned, so an edit to one is (a)). From a newer base (b) still lets through an
     `M` on `docs/reference/claude-swap.md`, `docs/reference/README.md` and
     `docs/design/decisions/README.md`, which exist at that base and are project-owned; Land step 4
     (the review) refuses them.
     At least one entry must be (a). An entry that is neither, which includes any `A`, `D` or `R`
     under the record directories (a sibling never adds, removes or renames a Hatchery record):
     `carry-back: <path> is project-owned; a base change touches only the base-owned set and the record it updates`.
     Every entry (b), none (a): `carry-back: <path> is a record; a record moves only with the base change it describes`.
     The command, run from the worktree root (it loads that tree's `ownership.js`):

     ```
     git -c core.quotePath=false diff --name-status -M <B> carry-back/<slug> | node -e '
     const o = require("./.claude/hooks/ownership.js");
     const map = require("child_process").execFileSync("git", ["show", process.argv[1] + ":.claude/hooks/ownership.json"], { encoding: "utf8" });
     const l = o.parseMap(map);
     const base = (p) => ["baseOwned", "mixed"].includes(o.classOf(l, p));
     const rec = /^docs\/(design\/decisions|reference)\//;
     const a = [], b = [], bad = [];
     for (const line of require("fs").readFileSync(0, "utf8").split(/\r?\n/).filter(Boolean)) {
       const [st, ...ps] = line.split("\t");
       if (ps.every(base)) a.push(...ps);
       else if (st === "M" && rec.test(ps[0])) b.push(ps[0]);
       else bad.push(...ps.filter((p) => !base(p)));
     }
     for (const p of bad) console.log("carry-back: " + p + " is project-owned; a base change touches only the base-owned set and the record it updates");
     if (bad.length === 0 && a.length === 0) for (const p of b) console.log("carry-back: " + p + " is a record; a record moves only with the base change it describes");
     if (bad.length > 0 || a.length === 0) process.exitCode = 1;
     ' <B>
     ```

     No output and exit 0 passes; each line printed is a refusal (an empty branch exits 1 silently).
6. Pusher: `git push -u origin carry-back/<slug>` under its own checks. Rule 20 and check 5 scan
   it under the sibling's lists; a hit stops the send. The lift is the sibling owner's
   (`privacy.allow`, the never and user allow lists, edited from the sibling's main session after
   asking the user, ADR 0018), never this skill's. `git.push.branches` must match `carry-back/*`
   (the base default `["*"]` does; a project with a narrower list adds it).
7. The departure row: its `Carry-back` cell becomes `carry-back/<slug> at <sha7>` (the step 4
   commit), on the sibling's own `main`, in the commit that made the change there or in its next
   commit. A bug fixed only on the branch gets a row too (`Path` the fixed path, `Reason` the bug),
   so that Hatchery's triage sees it.

## Land (in Hatchery)

1. Coordinator: `git status --porcelain` is empty; one pending branch per brief (the `triage` step
   lists them); `U=$(git -C ../<dir> remote get-url origin)`. The brief names `<dir>`, `<slug>`,
   the URL and the `docs/ideas/` files the change completes.
2. Coder: `git fetch "$U" carry-back/<slug>` (writes only `FETCH_HEAD`; never merge it);
   `B=$(git log -1 --format=%b FETCH_HEAD | sed -n 's/^Carry-back-base: //p')`. Refuse, and
   report the line for the sibling, when any of these holds:
   - `B` is empty, or `git merge-base --is-ancestor "$B" main` fails, or a commit of
     `git rev-list FETCH_HEAD --not "$B"` lacks the line: `for c in $(git rev-list FETCH_HEAD --not "$B"); do git log -1 --format=%b "$c" | grep -qx "Carry-back-base: $B" || echo "$c"; done`
     prints a sha. Refusal: `carry-back: the range is not one carry-back's own`.
   - `git merge-base --is-ancestor "$B" FETCH_HEAD` fails: the branch was cut from another sha
     than the one it names, and its diff from `$B` would revert what `main` did since. Refusal:
     `carry-back: the branch is not cut from its Carry-back-base <B>`.
   - The path check of Send step 5 refuses, with the map at `$B`: its command from Hatchery's
     root, with `"$B" FETCH_HEAD` for the range and `"$B"` as the last argument. The refusals are
     the same two texts.
3. Coder, in Bash: `git diff -M --binary "$B" FETCH_HEAD | git apply --3way`.
   - Exit 0: applied and staged.
   - Exit 1 with `U` entries: resolve per file as the update procedure's conflicts (ADR 0016): the
     sibling's hunk where it is the carried change, `main`'s where `main` moved on; `git add`.
   - Exit 1 with an empty `git status --porcelain` (a file `main` deleted, `does not exist in
     index`): apply per path. For each entry of `git diff --name-status -M "$B" FETCH_HEAD`, run
     `git diff -M --binary "$B" FETCH_HEAD -- '<path>' | git apply --3way` (a rename names both
     paths). Name each path that does not apply, with why, for the landing body.
4. Reviewer: the one independent review, of `git diff --cached` and the fetched messages
   (`git log FETCH_HEAD --not main`), for Hatchery's constraints: ADR 0013 surfaces, sibling
   assumptions in fixtures and thresholds, the docs rule (a sibling's name in living text), the
   claims in the message; a change under `.claude/agreement/` or `.claude/rules/` is a rule
   change, refused at review (the path check passes it as base-owned) and filed as an idea in
   `docs/ideas/` instead; so is a branch whose only entries are records under `docs/hatchery/` (ADR 0019
   item 1: a record moves only with the base change it describes); blocking by rule as the agent file says. Fixes go to the coder's
   working tree. Nobody pushes to or force-pushes a sibling's branch.
5. Runner: `node .claude/hooks/test-hooks.js` passes; delete the `docs/ideas/` files the brief
   names; one commit from a message file (`git commit -F <file>`):

   ```
   <the branch commit's subject>

   <the branch commit's body above its trailers, Carry-back-base line included>
   Dropped: <path> (<why>)                       (one per path, only when any)
   Carry-back: <repo> carry-back/<slug> at <sha40 of FETCH_HEAD>

   Co-Authored-By: <each of the branch's lines>
   Co-Authored-By: <the coder's, when the review changed the tree>
   ```

   The message must keep `carry-back/<slug> at <sha>` and the `Carry-back:` subject prefix: the
   triage step finds the landing by `git log --grep` on them.
6. Pusher: `git push origin main`. Rule 20 and check 5 scan the landing under Hatchery's policy
   like any commit; that is why the landing is a fresh commit and the sibling's author is never
   kept. A declined carry-back (a refusal in step 2, or a blocking finding the coordinator does
   not route back) is one empty commit by the runner instead of a landing, pushed the same way:
   `git commit --allow-empty -m "Carry-back declined: <repo> carry-back/<slug> at <sha40>: <reason>"`.
7. The sibling's next update (the `update-from-hatchery` skill, conflicts step) finds the landing
   or decline commit in its incoming log: on a landing it takes the base's version on the rowed
   path and deletes the row; on a decline it sets the cell to `none: <reason>`. Nothing is relayed
   by hand.

PR 1, this once: steps 2 to 6 with `git fetch origin task/T117-integrate-and-elision` and
`B=$(git rev-parse 749377e)`; its commit predates the `Carry-back-base:` line, so that check is
waived for it alone, and it lands as one commit whose `Carry-back:` line names the sibling the
coordinator's brief gives and `task/T117-integrate-and-elision at <sha40>`. Then the pusher writes
the closing comment, naming the landing sha, to a file;
`node .claude/hooks/privacy-scan.js --text <file>` must exit 0; then
`gh pr comment 1 --body-file <file>` and `gh pr close 1`. Deleting the branch is the owner's
(guard rule 2). Any later text that leaves through `gh` goes the same way: a file, `--text`, then
`gh` (ADR 0018 as amended; `gh` is outside the guard).

## Rules

- Never `git merge` or `git pull` a carry-back; `FETCH_HEAD` is applied as a diff only.
- Never keep the sibling's author or commit: the landing is Hatchery's own commit.
- A branch carrying several changes is landed as several commits or declined.
