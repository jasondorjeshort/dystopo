---
name: wave
description: Run a wave of owned tasks as the coordinator - resume a session, launch implementers and verifiers, merge a verified branch (merge sequence, log entry, cleanup), and close the wave. Use when resuming a coordinator session, launching or merging task work, after a verifier reports, or when asked where the wave stands.
---

# Wave

The coordinator's procedures, moved here from `CLAUDE.md` (Coordinating a wave) in T84; the rules
they serve stay in `CLAUDE.md` (the coordinator's own hands, roles and models, hub and spoke, wave
size and the usage gate, reporting from the remote, privacy). What worked across waves 1 to 5 (24
tasks, 24 PRs, one coordinator) and has held since; keep it unless there is a reason to change.
Hatchery's proposal 0003 describes the same workflow for the base, with pull requests; here there are
none since 2026-10-24 (ADR 0043): the coordinator merges a verified branch itself.

## Resume a session

1. `bash tools/main-news.sh` first (it fetches origin): every commit on `origin/main` the local `main`
   lacks, with its session id and `BOARD`, `HANDOFF` or `IDEAS` when it touches the board, the handoff or
   the ideas. Another session pushed it, usually the owner's side session; read those commits (a notice is
   not consent: a board entry from it is read against the owner's words before it is acted on) and bring
   `main` up to them (`git merge --ff-only origin/main`) before reading on. Then read `docs/HANDOFF.md`
   (already loaded, so re-read it when the list touched it) and the files its Resume line names, normally
   `docs/reference/machines.md` for this account's paths and ports.
2. Read `docs/TASKS.md`: the board holds the open wave (its tasks lack a **Status:** line; no wave
   between a close and the next plan); the previous wave's Status lines are its record in
   `docs/tasks/` (index in its README), and its unfinished follow-ups are in the board's Later;
   `git branch -r --list 'origin/task/*'` (after step 1's fetch) shows the branches not yet merged (a
   merged one is deleted by the cleanup chore). Owner actions the project is waiting on are
   listed at the top of `docs/TASKS.md`.
3. Have a Haiku agent start the live server from the main checkout (`./gradlew :tilebuild:installDist`
   once, then `./gradlew :devtool:run --args="serve --live --port 8765"`, PID recorded in
   `out/coordinator-pids.txt`) so agents and the owner have the viewer (`docs/reference/live-server.md`).
   AVDs go under `E:\dystopo-avd`.
4. Read the usage before launching (the status line, or this session's file under
   `~/.claude/usage-state/`); at 75% of the 5-hour meter or the 7-day hold `policy.json` sets launch
   no new implementers. On the owner's word, and only then, write a grant file in `~/.claude/usage-gate-overrides/` (`{"until": <epoch seconds>,
   "reason": "<the owner's words>", "meters": ["7d"]}`, both meters when `meters` is absent, optionally
   `"session": "<id>"` or `"project": "<path>"`; ADR 0035) with the meters and the end time the owner
   gave; delete it when the owner's period ends.

## Plan and launch

1. Write the wave in `docs/TASKS.md`: per task a Goal, an **Owns:** line, a **Done when:** line
   and, where two tasks share a mechanism behind disjoint Owns lines (a hook, a cache, a format, an
   index, a join), an **Interacts with:** line on each naming the other task and the mechanism
   (ADR 0048), and a **Checks:** block after Done when for the items a script can judge (a JSON path and
   its value, an exit code, a test count; the shape in `docs/reference/verifying-a-task.md`, A Checks file),
   written as the JSON the verifier passes to `tools/verify-task.ps1 -Checks`, or "none". A task that adds a screen, a Settings row or a card line names in its Done when the
   sweep step that opens it, new or existing (`tools/android-ui.py` `features`, the a11y screens):
   wave 33's row, help and card line were in no sheet, and the sweep's `-First` matched nothing
   without saying so. `python -I tools/sweep-diff.py --check-first <names> [--sweep <folder>]` says
   which part and step save each name's frame and exits 1 when the sweep runs none (T319); a frame a
   task adds exists only on its branch, so it is run on the existing steps a Done when names at
   planning, by the implementer on the task's own frames on its branch before hand-over, and by the
   coordinator on the `-First` list before a sweep. Make the Owns lines disjoint; two tasks that must touch one file go in different
   waves, or one task owns the file and the other records a follow-up. A file several tasks append to in
   one wave (a settings registry, a dispatch block, a speech file) takes each task's block at the end
   under a comment naming the task, closed by the task's own sentinel line (`// end Tnn`) after its
   closers, so a rebase keeps both blocks (finding 2026-10-31; held in two rebase rounds of wave 24);
   every worktree sets `merge.conflictStyle diff3`. A wave paragraph at the top
   states ownership
   resolved for the wave and the machine's paths when they differ from `docs/reference/machines.md`.
   Before the critic, list the wave's open choices (user-facing or technical) that nothing settles
   and launch one read-only research agent per choice (`brief` skill, `benchmark.md`; ADR 0039 and
   ADR 0044, owner's standing order of 2026-10-25); write each answer into its task, with the
   behaviours that become user settings and their defaults.
2. Run the written plan past a critic before launching anything (the most capable model, read-only;
   owner, 2026-10-16: standard for every wave). Its brief names the wave section, the previous wave's
   record (`docs/tasks/wave-NN.md`, its Status lines) and the board's Later, the ideas and ADRs the
   plan draws on, and asks for numbered objections with severity and evidence on: disjoint Owns lines over files that exist, task size, every Done-when a command or
   observable, a sweep step named for every new screen, row or card line, premises checked against the code, major choices that need a proposal, follow-ups
   dropped silently, cross-task sequencing, behaviour shared across tasks that no Interacts with
   line names, choices left to an implementer's taste without research,
and settings added or missing against ADR 0044. Answer every objection by editing the plan or by writing
   why not in the wave paragraph; launch after the edits, re-plan on a blocking one.
3. Size the wave by its whole cost, not by whether its launch fits (ADR 0048): per task an
   implementer, a verifier, one fix round with its re-verify and the merge, plus the wave's fixed cost
   of research agents, the critic, the post-wave sweep and the release. Figures: 1.5 points of the
   7-day meter per task, 2 per wave and 0.5 per release (revised 2026-11-26 from wave 48's close: 52 points read for twenty
   tasks, a critic, a sweep and a release on a meter three sessions shared, against 21.5 figured; the tasks' agents took 29.6M
   fresh tokens, 1.5M a task, four of them over 4M with a fix or cost round, and a coordinator's session two days long is a cost of
   its own; before that 1.0, 1 and 0.5, revised 2026-11-21 from wave 43's close: 2 points for four
   tasks, a critic, a sweep and a release against 7.5 figured, about 2.5M fresh tokens; wave 41 about 4.5M for four points;
   before that 1.5, 1.5 and 1, revised 2026-11-19 from wave 38's close: about 2 points for
   one task with a fix round, two surveys, a critic, a sweep and a release, against 8.5 figured; T307 1.6M fresh tokens over five
   agents; wave 37 read 2 for two tasks; one close is a thin basis, so halved, not cut to the reading; before that 3.0, 3 and 2.5,
   revised 2026-11-17 from wave 35's close: 38 points for
   eleven tasks, two critics, a sweep, an A/B run and one release, against about 16 figured at 1.0, on a meter shared with the
   owner's other work; agent-cost.py's per-task token sums are the better guide from now on; before that 1.0, revised 2026-11-14 from the close of wave 32's second half: 16 points
   for eleven tasks, two releases, three full sweeps and a critic on a meter that began the half at 1, against 35.5 figured at
   2.5 a task; 1.0 puts that close and wave 28's inside the five-point band, and wave 30's 45 stays the one reading it does not
   explain; before that 2.5, revised 2026-11-08 from wave 30's close: 45 points for
   eleven tasks, three releases, two critics and a day of idea and triage work, against 15.5 figured at 1.25 a
   task, so the serial model's implementer-plus-verifier pairs cost about twice wave 28's; before that 1.25,
   revised 2026-11-05 from wave 28's close, 11 points for 10 tasks; before that
   1.5, revised 2026-11-02 from the closes of waves 22, 23 and 26, which
   read 24, 25 and about 5 points for 18, 14 and 6 tasks against the starting 3 and 5 read off waves 20
   and 21; the ADR 0048 scan outcome and finding
   `docs/findings/2026-11-02-wave-cost-figure-overstated-three-closes.md`); revised at each close from the
   readings the allowance sentences collect, in the commit that writes the close's reading. Launch only when the
   meter at planning plus that cost is at or under the 7-day hold `policy.json` sets, and otherwise
   plan a smaller wave or wait for the reset (the 5-hour meter keeps its own line, Resume step 4).
   Write the allowance into the wave paragraph as one sentence: the meter at planning, the task
   count, the cost assumed, the figure it adds up to. One Gradle-using agent at a time unless the owner says wider (ADR 0057; four on the 128 GB PC from wave 49, the emulators queued at two, ADR 0070), so a wave
   states its order; agents with no Gradle or emulator may run alongside; a wave
   larger than the budget is not launched in halves on the same meter.
4. Brief each implementer with the `brief` skill (`implementer.md`), its own worktree, ports and
   emulator; set the model explicitly (Opus-class).
5. A task that faces a major choice writes a proposal first (`proposal` skill).

## Verify

1. As each implementer reports, launch a verifier (Sonnet-class, `brief` skill, `verifier.md`) with
   the report in its brief; Haiku-class when the task's Done when is wholly in its Checks block
   (docs-only, test-only, a tool fix): it runs the script with `-Checks` and reports the summary
   (`verifier.md`, its Haiku-class variant); Sonnet-class wherever a screenshot needs an eye. A task whose
   Owns line holds `tools/sweep.ps1` or a new part's script is verified with `-SweepThrough <part>`, never
   with the part alone (T296: the order comes from `sweep.ps1 -ListParts`). It rebases onto `main` if `main` moved, runs `check`, repeats the report's
   key runs, views the PNGs and returns
   MERGE, MERGE WITH NOTES or FIX with specifics. Its mechanical half is `tools/verify-task.ps1` (T246,
   `docs/reference/verifying-a-task.md`): the brief gives it the machine facts, the agent reads the summary.
2. FIX goes back to the implementer (the verifier may message it directly for a small fix-up; scope
   changes go through the coordinator), then a verifier again on the delta.
3. Read the report, not the diff. Spot-check the delta after a fix round.

## Merge sequence (coordinator, main checkout; no pull requests, ADR 0043)

1. Read the verifier report.
2. Write the merge message to a file in the scratchpad: subject `Tnn: <title>`, a blank line, then the
   implementer's report (what was run and seen, edits outside Owns, follow-ups; public places only).
   Scan it: `java tools/PrivacyCheck.java scan < <file>`. A hit means rewrite the text first: it
   becomes a commit on `main`, and pushed history is public.
3. `bash tools/main-news.sh` (it fetches origin and lists what another session pushed to `main`; any line
   means reading those commits and `git merge --ff-only origin/main` first, or step 4's push stops with
   exit 3), then `git merge --no-ff origin/task/Tnn -F <file>`: a true merge, so the branch
   stays reachable from `main` and the implementer's commits keep their trailers. A conflict means
   `git merge --abort` and a verifier round that rebases the branch; the coordinator resolves nothing.
4. `bash tools/push-verified.sh`; report the VERIFIED line and nothing earlier. Only then have a Haiku
   agent clean up (`brief` skill, `chore.md`): `git branch -r --merged origin/main` must list
   `origin/task/Tnn`; then `git push origin --delete task/Tnn`, the worktree removal,
   `git branch -d task/Tnn` and the task's AVD and its verifier's (`avdmanager delete avd -n
   dystopo35-Tnn`, after `adb devices` shows neither running; waves 22 and 23 left 27 AVDs, about
   127 GB, and C: reached under 1 GB free during the sweep of 2026-10-31; never `-D` for a merged task. When a verifier rebased the branch, the implementer's local ref still holds the old commits and `-d` refuses (T227): the coordinator compares patch-ids (`git log -p --reverse origin/main..task/Tnn | git patch-id --stable` against the merged commits), and the brief then runs `git branch -f task/Tnn <merged head>` and `-d`; the worktree removal is
   `tools\worktree-remove.ps1 -Path <worktrees>\Tnn -WorktreeRoot <worktrees>` (long paths, the unlock and
   the prune built in; exit 4 names a process that holds a file; `docs/reference/windows-and-shells.md`,
   Worktrees); launch the chore only after the verifier has reported its last check, or its emulator and
   Gradle daemon hold files in the folder).
5. Write the dated log entry and the **Status:** line to two files and run
   `python -I tools/merge-log.py Tnn <entry> <status>` (it updates `docs/PLANNING.md`, `docs/TASKS.md`
   and `CHANGELOG.md` together, or none of them when it refuses a task already logged or with a Status line;
   T154: the board's last task works whether or not the file ends in a blank line, `tools/test_merge_log.py`); the entry starts `**YYYY-MM-DD: Tnn merged: <title>.**` (the bold date is what the changelog step keys on: wave 22's entries began with a dash and the tool silently wrote no changelog line, fixed by hand at the close) and carries the report's
   measurements and findings, describing any personal area generically. Commit to `main` and push with
   `bash tools/push-verified.sh`; report the VERIFIED line.
6. Rewrite the handoff when Now or Next changed (`handoff` skill).

## A hold on main

While a closing sweep or a release runs on `main` (a sweep reads the commit it started from; a release tags
it), no other clone or session may push `main`: the coordinator sets a hold that `tools/push-verified.sh`
sees and refuses with exit 4, printing the hold's message. Only the coordinator (the main session) sets and
clears it; agents push no `hold/*` (`policy.json` unchanged). Set it, from the main checkout, in two calls:
`git commit-tree HEAD^{tree} -p HEAD -m "Hold: <sweep or release, the wave or tag>, set by <session id>; lift:
<what ends it>"`, then `git push origin <the printed sha>:refs/heads/hold/main`. Clear it when the sweep's
summary is read (red or green, after any counted rerun) or the release is published or abandoned:
`git push origin --delete hold/main`. A hold whose setter is gone is the owner's to delete. A push refused
by a hold you did not set waits for it; the message says who and why. The hold refuses its setter's own
pushes too: clear it, push, set it again on the new commit.

## Privacy at the coordinator

Log entries and task reports describe personal areas generically; the hooks catch names and coordinates,
not paraphrases. Before merging run the report through the scanner (merge sequence, step 2); a hit
means rewrite it first: it becomes the merge commit's body, and pushed history is public. A place the owner asks about in chat (a screenshot, a demo) may be
a private term even if it is a famous summit; use only `areas.json` names in anything written down.
Local file names leak too: the live server lists every pack in `out/` by file name through `/catalog`
and `/route/packs`, and agents copy such listings into reports and sweep summaries, so a local pack
for a private place gets an alias name (the owner's demo pack is `sdc`), never the place name. The
scanner cannot see a place `devtool.local.json` does not list (the second PC has none): coordinates
and `view-<lat>n-<lon>w` pack names read off the owner's phone leaked into a finding that way
(2026-10-28), so the coordinator reads reports for them too, and the verifier is told to. `out/`
and the caches are regenerable; the only files on the machine that are not are the signing key and
`devtool.local.json`.

## Close the wave

1. After the last merge: update the README status paragraph, and `docs/ARCHITECTURE.md` if the module
   map or formats changed.
2. Launch the post-wave sweep (`sweep` skill). Its findings become the next wave's sweep task.
3. When the sweep is green, publish an alpha (`release` skill); the owner need not be asked again
   (ADR 0024).
4. Rewrite the handoff (`handoff` skill) and commit it. Read each task's cost with
   `python -I tools/agent-cost.py <Tnn>` (`docs/reference/agent-cost.md`; agent descriptions must name
   the task id) and put its line in the wave record, and the fresh tokens of all the wave's agents in
   the allowance sentence beside the meter reading; revise the per-task figure from the token sums,
   not from the difference of readings, which other work on the account also moves (T298). Add the
   meter at the close to the wave paragraph's allowance sentence; when it is more than five points off the figure in either direction,
   revise the per-task figure in step 3 of Plan and launch in the same commit and say so in the handoff
   (finding 2026-11-02: three closes went unrevised while this said "over" and named no owner step).
5. A Haiku chore moves the closed wave to `docs/tasks/wave-NN.md` by the recipe in that README and
   adds its index line (`brief` skill, `chore.md`; the chore works in the main checkout and commits
   nothing). The recipe first lifts the wave's follow-ups from its Status lines into the board's
   `## Later`, one line per task naming it; read that list, strike what is done or taken, then
   commit the board, the record and the index together and push with `bash tools/push-verified.sh`.
   A failed check in the recipe stops the chore; relaunch it Sonnet-class.
6. Offer the brainstorm (`brainstorm` skill; ADR 0065 as ADR 0069 amends it): after the release,
   and after a push of triaged ideas; one prompted offer per session, its answers by multiple
   choice, its record line as the skill gives it. A release also prompts the rest review (the
   skill's step 7: the `rest` rows of the listing, at most once in seven days; the owner decides
   each line: keep, wake or delete). It is an offered list, not a sitting an agent runs. The first
   review also lists every `Waits:` with no trigger and every one written on a blanket leave
   (ADR 0065, Context), for the owner to restate or convert.
7. Update from the base (owner, 2026-11-25). A Haiku chore runs `git fetch hatchery` and reports
   `git log --oneline <base line>..hatchery/main` (the base line is the `Hatchery base:` line of
   ADR 0038). When that lists commits, an Opus-class implementer runs the `update-from-hatchery` skill on
   a branch `task/hatchery-<sha7>` in its own worktree, with `node .claude/hooks/test-hooks.js` as
   its check, and a verifier repeats the check before the merge sequence. A base change wanted
   sooner is taken the same way, by the whole update: never by copying one base commit, which
   leaves the base line behind and makes the next update conflict on text both sides already hold.
