---
date: 2026-10-31
status: open
area: process / merge, wave ownership rules
---

# Appended blocks drop their closers on rebase

## Question

Wave 23 resolved the shared-registry ownership by having each task append its keys, rows and one enum entry "at the
end of the registry files' blocks under a comment naming the task, the second to merge rebasing" (`docs/TASKS.md` ›
Wave 23, "Ownership resolved for the wave"). Why did rebases over a sibling's appended block repeatedly leave
`SettingsPages.kt` uncompilable, and does the rule need to change?

## What was tried

- Read the repairs in git: `f803aad6` (T187's verifier rebasing over T178: "close the two blocks the rebase onto
  T178 left open"), `e033231d` (T182's implementer: "two braces the rebase's conflict blocks split", one `},` in the
  help list), and the merge body of `7b2724d` (T183's implementer rebasing over T187 and then T182: "both times the
  merge dropped their closing braces"). Four repairs in all across three rebases, all in one file.
- Read the T176 to T184 blocks of `SettingsPages.kt` on main: the `RowContent` `when` (rows for T176, T177, T178,
  T187, T182, T183, T184, each `ROW -> { ... Setting(...) { for (...) { Option(...) } } }`) and the `SETTINGS_HELP`
  list (`HelpText(...) { ... },` entries under the same task comments). Every row of the choice kind ends in the same
  lines: `                }`, `            }`, `        }`.
- Reproduced the merge with `git merge-file` on scratch files (no repository touched): a base with one block, and two
  sides each appending a differently named block of the same shape after it.
- Checked the other files the wave told tasks to append to: `NavSpeech.kt`, `HelpRegistryTest.kt`,
  `SettingsLayoutTest.kt` and the devtool `Main.kt` dispatch.

## Result

Mechanism, from the scratch merge (git's default conflict style, "zealous" simplification):

1. Two tasks append at the same anchor (after the last block, before `else ->` or the list's `)`). Git cannot merge
   two insertions at one point, so it reports a conflict, but because the two inserted texts have the same shape it
   aligns them line by line: lines the sides share are cut out of the conflict as common text. The conflict block
   holds only the differing head of each side (the task comment, the row name, the first lines of its body), and the
   shared suffix (the loop's `}`, the row's `}`, the help entry's `},`) sits once, below the conflict markers.
2. Resolving by keeping both sides' conflict contents, the usual move, yields side A's head, side B's head, then the
   one shared suffix. Each side owns its closers in the author's own branch, but after the merge only one set exists
   and it follows B. A's block is left open and B's closes it: the "two blocks left open" of `f803aad6`. When the
   shared suffix straddles a list element (`},` then the next `HelpText(`) the comma goes the same way.
3. The overlap is structural: every row is the same kind of block, so every pair of tasks that append at one anchor
   hits it, and a second sibling hit it again on the next rebase (T183 twice). No clean auto-merge dropped anything;
   the damage came in the resolution step, from conflict blocks that did not contain the closers they appeared to.
   The `--diff3` style shows the whole of both sides and is not affected (in the scratch run its blocks held complete
   rows). It is not git's default, and the markers an agent reads did not show the problem.

The other files:

- `NavSpeech.kt`: T178 and T187 each appended a function at the end of the object. Main is intact; T187's block opens
  with a blank line and a banner comment and the two functions' tails differ in text, so no resolution depended on
  a shared suffix. No repair commit touches it.
- `HelpRegistryTest.kt`, `SettingsLayoutTest.kt`: edited by most of the wave's tasks (history lists T177, T178, T182,
  T183, T184, T187, T188); main compiles and no repair commit names them. Their edits were one-line additions to
  lists and counts, which conflict whole, with no closers to share.
- `devtool/.../Main.kt`: spared. Each task added a comment line and one `.subcommands(XCommand())` line, a
  conflict with no shared suffix and nothing to close; keeping both sides is correct, and every line ends in `)` that
  is its own.

Cost: four repair commits and one failed verify cycle (T187's verifier). The failure was a compile error, so `check`
caught it every time; none shipped.

## Next

Rule change for the next wave's ownership paragraph. Options, with the trade-offs:

- (a) Each task's block ends with a unique sentinel comment line after its closers (`// end T184`). The sentinel
  differs between sides, so git cannot align the closers as common suffix: the whole block, closers included, falls
  inside the conflict and keeping both sides is right. Cheap, no structure change, and keeps one registry file. It
  relies on every implementer doing it; a missing sentinel brings the problem back silently, so a hook or test
  (a line count or brace check of task blocks) would be needed to enforce it.
- (b) Each task's rows live in its own file (`SettingsPagesT184.kt`) registering into a list. No two tasks touch one
  file, so there is nothing to conflict. It costs a registry indirection (the list, ordering of rows, a place in the
  settings layout tests) that the project would keep after the wave, and a structural change to settings code made
  for a merge problem; the one-line registration still collides.
- (c) Keep one block per task and add `:androidApp:compileDebugKotlin` to the coordinator's merge sequence before
  `push-verified.sh`. It catches the failure but does not prevent it: the repair is still by hand, in the verifier's
  or the coordinator's time, and Gradle runs belong to chores under the coordinator's rule, so it adds a launch per
  merge.

Recommendation: (a), plus the compile of (c) as the backstop the verifier already runs (`check`). The sentinel removes
the cause (the shared suffix) at the cost of one comment line per block; (b) is the right shape only if the registry
is to be split for its own sake. Also tell implementers resolving such a conflict to read the lines after the closing
marker, not only inside it, and to run the compile before continuing the rebase.
