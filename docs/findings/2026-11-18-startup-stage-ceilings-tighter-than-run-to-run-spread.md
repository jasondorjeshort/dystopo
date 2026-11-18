---
date: 2026-11-18
status: resolved (T306)
area: tooling / sweep startup part, launch budget
promoted_to: docs/reference/android-emulator.md
---

# The startup part's red is noise: identical code spreads past several of its ceilings

## Question

The sweep of 2026-11-18 (`out/sweep-20261118-0901`, at c8d29ca7) had `startup` red: fully loaded median 7,127 ms
against the 6,680 ceiling, the third reading over it. Is main slower than v0.1.32-alpha, and from which merge?

## What was tried

- The app code between v0.1.32-alpha (19662337) and main (55323e9e) differs only by T303 (the debug build's own
  application id, label and tile port): `git diff --stat v0.1.32-alpha origin/main -- androidApp shared`.
- An A/B on one emulator in one sitting (API 35, 3 GB, `-gpu host`, no other emulator or Gradle build; the owner's
  phone and the coordinator's live viewer were the only other device work): debug builds of main and of the tag, both
  installed (their ids differ), the app's server set to none, and `android-startup.py` of each version with the
  sweep's arguments (5 cold launches, 52 seeded tracks, the Rainier packs, cache and divergences cleared, `--quiet
  240`, `--budget tools/launch-budget.json`), alternating main, old, main, old, main, old, a fresh boot before each.
- Three reruns of the sweep's own part from the T305 worktree: `-Only device-tour,startup,diagnostics,update`
  (startup after the tour, as in a sweep) and twice `-Only startup` (startup right after device-boot).

## Result

Medians of five cold launches, ms (fully loaded, first frame, and the stages that went over a ceiling):

| Run | Build | Fully loaded | First frame | tracking (174) | packs (87) | online (114) |
|---|---|---|---|---|---|---|
| sweep 0901 (busy PC) | main | 7,127 | 4,275 | 125 | 68 | 84 |
| rerun a (after the tour) | main | 5,566 | 4,064 | **177** | 56 | 89 |
| rerun b (after boot) | main | 6,472 | 4,049 | **201** | **132** | 73 |
| rerun c (after boot) | main | 6,403 | 4,114 | 159 | 68 | 78 |
| A/B 1 (first after install) | main | **7,511** | 4,324 | **234** | 65 | **126** |
| A/B 2 | v0.1.32 | 5,483 | 3,671 | 119 | 56 | **166** |
| A/B 3 | main | 5,550 | 3,639 | 130 | 61 | **116** |
| A/B 4 | v0.1.32 | 5,878 | 3,696 | 108 | 59 | 111 |
| A/B 5 | main | 5,593 | 3,742 | 130 | 62 | **122** |
| A/B 6 | v0.1.32 | 5,489 | 3,662 | **177** | **137** | 104 |

- **No regression.** A/B medians of the three runs: main 5,593 ms (5,550 to 7,511; 5,550 to 5,593 without the first
  run after the install), v0.1.32-alpha 5,489 ms (5,483 to 5,878). The same code, as the diff says.
- **The 7,127 was the machine**: the sweep ran beside the coordinator's session and a phone over wireless adb; the
  part alone read 5,566 to 6,472.
- **The stage ceilings sit inside the spread of identical code.** `tracking` read 108 to 234 ms and `packs` 56 to
  137 ms on both builds; their ceilings (174, 87) are the 2026-11-13 medians (three sweeps, 131 to 139 and 66 to 69)
  times 1.25. Of the four runs in sweep conditions, three were red, each on a different reading. The first launch
  after a fresh boot or install takes 8.5 to 10 s, and when the next ones are slow too the median follows. (`online`
  over 114 in the A/B is that setup's: no server set.)

## Resolution (T306)

The coordinator took the first and third of three choices (more reports; more launches with the first left out), not
a wider factor for the disk-reading stages, which would hide a regression in exactly those. The part now runs
`--warmup 1 --launches 9`: the first launch after the boot and seed is measured and left out of the medians (map
drawn 7,409 to 9,740 ms in nine runs, against 5,328 to 6,982 for every other launch). The budget was set again with
`--set-budget` from nine `-Only startup` reports on main (a fresh emulator each, nothing else heavy running), the
factor kept at 1.25; `--set-budget` no longer lowers a ceiling (the reports are of the part alone after a boot, and a
full sweep reads some stages slower).

- On those nine runs the old method (the warm-up plus four launches, the old budget) was red three times (`tracking`,
  once with `settings`); the nine-launch medians against the old budget were red once (`tracking` 186). Nine-launch
  medians: map drawn 5,633 to 6,300 ms, first frame 3,720 to 4,040, `tracking` 109 to 186, `packs` 54 to 67, `online`
  73 to 93 (single launches: `tracking` 96 to 241, `packs` 49 to 157, `online` 58 to 193).
- Raised: first frame 4,909 to 5,050, map drawn 6,680 to 7,875, `prepare` 300 to 360, `maplibre` 302 to 312,
  `tracking` 174 to 233, `online` 114 to 117, `composition` 1,458 to 1,599; none over 1.5 times its old value. The
  rest are kept (`kept` in `tools/launch-budget.json`).
- The table above holds five-launch medians with the first launch in. Against the new ceilings the sweep and reruns a
  and c read green; rerun b and A/B 6 are over on `packs` (132, 137), A/B 1 on `tracking` by 1 ms and on `online`, and
  A/B 2 and 5 on `online` (that setup had no server). No nine-launch median after a warm-up in T306's runs read
  `packs` over 67, so a `packs` red now is worth an A/B.
