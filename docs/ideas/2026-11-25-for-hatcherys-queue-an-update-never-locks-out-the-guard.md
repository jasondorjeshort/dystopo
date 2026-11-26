---
date: 2026-11-25
kind: chore
related: [2026-11-20-a-failing-hook-says-why.md, 2026-10-23-hatchery-ownership-check-in-project-trees.md]
---

# For Hatchery's queue: an update never locks out the guard

Source: coordinator (claude-fable-5-2), a spark during the update from the base of 2026-11-25, in the offer the owner asked for; offered and accepted by the owner; not sanity-checked.

The update's one command (`node .claude/hooks/ownership.js --command`, then `git apply --3way`) wrote conflict
markers into `.claude/hooks/policy.js` and `.claude/hooks/privacy-scan.js`. `git-guard.js` loads both, so every Bash
and PowerShell call was then refused ("git-guard: internal error (Unexpected token '<<')") until the two files were
repaired with the Edit tool. The skill (`.claude/skills/update-from-hatchery/SKILL.md`, step 3) names this only for a
policy that does not load, not for the other scripts the guard requires.

Wanted, in the base: after the apply, a conflicted `.claude/hooks/*.js` keeps the project's version in the working
tree (so the guard loads) with the base's and the merged text written beside it in the scratchpad, the path still
listed as unmerged for step 4; or the helper applies the hook scripts in a second command that does the same. Until
then, one sentence in step 3: a marker in any script the guard loads is cleared with the Edit tool first.

Lever: every project with a rowed hook meets this at each update. It outranks the ownership-check idea beside it,
which fails a check; this stops the session.

## Open

- Whether `git checkout --ours` on the hook scripts before the report keeps the unmerged entry on every git version.

Where it is built: in Hatchery, by a session rooted there, not in this tree (the owner, 2026-11-25, by multiple choice: "Split by owner"); building it here would add a departure row.
