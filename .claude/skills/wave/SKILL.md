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

1. Read `docs/HANDOFF.md` (already loaded) and the files its Resume line names, normally
   `docs/reference/machines.md` for this account's paths and ports.
2. Read `docs/TASKS.md`: the board holds the open wave (its tasks lack a **Status:** line; no wave
   between a close and the next plan); the previous wave's Status lines are its record in
   `docs/tasks/` (index in its README), and its unfinished follow-ups are in the board's Later;
   `git fetch && git branch -r --list 'origin/task/*'` shows the branches not yet merged (a
   merged one is deleted by the cleanup chore). Owner actions the project is waiting on are
   listed at the top of `docs/TASKS.md`.
3. Have a Haiku agent start the live server from the main checkout (`./gradlew :tilebuild:installDist`
   once, then `./gradlew :devtool:run --args="serve --live --port 8765"`, PID recorded in
   `out/coordinator-pids.txt`) so agents and the owner have the viewer (`docs/reference/live-server.md`).
   AVDs go under `E:\dystopo-avd`.
4. Read the usage before launching (the status line, or this session's file under
   `~/.claude/usage-state/`); at 50% of the 5-hour meter or the 7-day hold `policy.json` sets launch
   no new implementers. On the owner's word, and only then, write a grant file in `~/.claude/usage-gate-overrides/` (`{"until": <epoch seconds>,
   "reason": "<the owner's words>", "meters": ["7d"]}`, both meters when `meters` is absent, optionally
   `"session": "<id>"` or `"project": "<path>"`; ADR 0035) with the meters and the end time the owner
   gave; delete it when the owner's period ends.

## Plan and launch

1. Write the wave in `docs/TASKS.md`: per task a Goal, an **Owns:** line, a **Done when:** line
   and, where two tasks share a mechanism behind disjoint Owns lines (a hook, a cache, a format, an
   index, a join), an **Interacts with:** line on each naming the other task and the mechanism
   (ADR 0048). Make the Owns lines disjoint; two tasks that must touch one file go in different
   waves, or one task owns the file and the other records a follow-up. A wave paragraph at the top
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
   observable, premises checked against the code, major choices that need a proposal, follow-ups
   dropped silently, cross-task sequencing, behaviour shared across tasks that no Interacts with
   line names, choices left to an implementer's taste without research,
and settings added or missing against ADR 0044. Answer every objection by editing the plan or by writing
   why not in the wave paragraph; launch after the edits, re-plan on a blocking one.
3. Size the wave by its whole cost, not by whether its launch fits (ADR 0048): per task an
   implementer, a verifier, one fix round with its re-verify and the merge, plus the wave's fixed cost
   of research agents, the critic, the post-wave sweep and the release. Starting figures: 3 points of
   the 7-day meter per task and 5 per wave, read off waves 20 and 21 (two data points, not a model);
   revised at each close from the readings the allowance sentences collect. Launch only when the
   meter at planning plus that cost is at or under the 7-day hold `policy.json` sets, and otherwise
   plan a smaller wave or wait for the reset (the 5-hour meter keeps its own line, Resume step 4).
   Write the allowance into the wave paragraph as one sentence: the meter at planning, the task
   count, the cost assumed, the figure it adds up to. Still 5 to 8 implementers at once; a wave
   larger than the budget is not launched in halves on the same meter.
4. Brief each implementer with the `brief` skill (`implementer.md`), its own worktree, ports and
   emulator; set the model explicitly (Opus-class).
5. A task that faces a major choice writes a proposal first (`proposal` skill).

## Verify

1. As each implementer reports, launch a verifier (Sonnet-class, `brief` skill, `verifier.md`) with
   the report in its brief. It rebases onto `main` if `main` moved, runs `check`, repeats the report's
   key runs, views the PNGs and returns
   MERGE, MERGE WITH NOTES or FIX with specifics.
2. FIX goes back to the implementer (the verifier may message it directly for a small fix-up; scope
   changes go through the coordinator), then a verifier again on the delta.
3. Read the report, not the diff. Spot-check the delta after a fix round.

## Merge sequence (coordinator, main checkout; no pull requests, ADR 0043)

1. Read the verifier report.
2. Write the merge message to a file in the scratchpad: subject `Tnn: <title>`, a blank line, then the
   implementer's report (what was run and seen, edits outside Owns, follow-ups; public places only).
   Scan it: `java tools/PrivacyCheck.java scan < <file>`. A hit means rewrite the text first: it
   becomes a commit on `main`, and pushed history is public.
3. `git fetch origin`, then `git merge --no-ff origin/task/Tnn -F <file>`: a true merge, so the branch
   stays reachable from `main` and the implementer's commits keep their trailers. A conflict means
   `git merge --abort` and a verifier round that rebases the branch; the coordinator resolves nothing.
4. `bash tools/push-verified.sh`; report the VERIFIED line and nothing earlier. Only then have a Haiku
   agent clean up (`brief` skill, `chore.md`): `git branch -r --merged origin/main` must list
   `origin/task/Tnn`; then `git push origin --delete task/Tnn`, the worktree removal and
   `git branch -d task/Tnn` (never `-D` for a merged task; worktree removal on Windows:
   `docs/reference/windows-and-shells.md`: give the chore the `\\?\` delete and one git command per
   call, and launch it only after the verifier has reported its last check, or its emulator and Gradle
   daemon hold files in the folder).
5. Write the dated log entry and the **Status:** line to two files and run
   `python -I tools/merge-log.py Tnn <entry> <status>` (it updates `docs/PLANNING.md`, `docs/TASKS.md`
   and `CHANGELOG.md` together, or none of them when it refuses a task already logged or with a Status line;
   T154: the board's last task works whether or not the file ends in a blank line, `tools/test_merge_log.py`); the entry starts `**YYYY-MM-DD: Tnn merged: <title>.**` (the bold date is what the changelog step keys on: wave 22's entries began with a dash and the tool silently wrote no changelog line, fixed by hand at the close) and carries the report's
   measurements and findings, describing any personal area generically. Commit to `main` and push with
   `bash tools/push-verified.sh`; report the VERIFIED line.
6. Rewrite the handoff when Now or Next changed (`handoff` skill).

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
4. Rewrite the handoff (`handoff` skill) and commit it. Add the meter at the close to the wave
   paragraph's allowance sentence; when it is more than five points over the figure, raise the
   per-task figure in step 3 of Plan and launch in the same commit and say so in the handoff.
5. A Haiku chore moves the closed wave to `docs/tasks/wave-NN.md` by the recipe in that README and
   adds its index line (`brief` skill, `chore.md`; the chore works in the main checkout and commits
   nothing). The recipe first lifts the wave's follow-ups from its Status lines into the board's
   `## Later`, one line per task naming it; read that list, strike what is done or taken, then
   commit the board, the record and the index together and push with `bash tools/push-verified.sh`.
   A failed check in the recipe stops the chore; relaunch it Sonnet-class.
