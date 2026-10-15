---
name: wave
description: Run a wave of owned tasks as the coordinator - resume a session, launch implementers and verifiers, merge a verified PR (merge sequence, log entry, cleanup), and close the wave. Use when resuming a coordinator session, launching or merging task work, after a verifier reports, or when asked where the wave stands.
---

# Wave

The coordinator's procedures, moved here from `CLAUDE.md` (Coordinating a wave) in T84; the rules
they serve stay in `CLAUDE.md` (the coordinator's own hands, roles and models, hub and spoke, wave
size and the usage gate, reporting from the remote, privacy). What worked across waves 1 to 5 (24
tasks, 24 PRs, one coordinator) and has held since; keep it unless there is a reason to change.
Hatchery's proposal 0003 describes the same workflow for the base.

## Resume a session

1. Read `docs/HANDOFF.md` (already loaded) and the files its Resume line names, normally
   `docs/reference/machines.md` for this account's paths and ports.
2. Read `docs/TASKS.md` from the bottom: the first wave whose tasks lack a **Status:** line is the
   open wave; `gh pr list` shows tasks with open PRs. Owner actions the project is waiting on are
   listed at the top of `docs/TASKS.md`.
3. Have a Haiku agent start the live server from the main checkout (`./gradlew :tilebuild:installDist`
   once, then `./gradlew :devtool:run --args="serve --live --port 8765"`, PID recorded in
   `out/coordinator-pids.txt`) so agents and the owner have the viewer (`docs/reference/live-server.md`).
   AVDs go under `E:\dystopo-avd`.
4. Read the usage before launching (the status line, or `~/.claude/usage-state.json`); at 75% of either
   meter launch no new implementers. On the owner's word, and only then, write the override file
   (`~/.claude/usage-gate-override.json`, ADR 0021, Usage gate) with the meters and the end time
   the owner gave; delete it when the owner's period ends.

## Plan and launch

1. Write the wave in `docs/TASKS.md`: per task a Goal, an **Owns:** line, a **Done when:** line.
   Make the Owns lines disjoint; two tasks that must touch one file go in different waves, or one task
   owns the file and the other records a follow-up. A wave paragraph at the top states ownership
   resolved for the wave and the machine's paths when they differ from `docs/reference/machines.md`.
2. 5 to 8 implementers at once; when a wave would not fit the budget, launch it in halves with
   disjoint ownership.
3. Brief each implementer with the `brief` skill (`implementer.md`), its own worktree, ports and
   emulator; set the model explicitly (Opus-class).
4. A task that faces a major choice writes a proposal first (`proposal` skill).

## Verify

1. As each PR arrives, launch a verifier (Sonnet-class, `brief` skill, `verifier.md`). It rebases
   onto `main` if `main` moved, runs `check`, repeats the PR's key runs, views the PNGs and returns
   MERGE, MERGE WITH NOTES or FIX with specifics.
2. FIX goes back to the implementer (the verifier may message it directly for a small fix-up; scope
   changes go through the coordinator), then a verifier again on the delta.
3. Read the report, not the diff. Spot-check the delta after a fix round.

## Merge sequence (coordinator, main checkout)

1. Read the verifier report.
2. Scan the PR text: `gh pr view <n> --json title,body --jq '.title + "\n" + .body' | java tools/PrivacyCheck.java scan`
   (run `gh ... --jq` from Git Bash, not PowerShell). A hit means edit the description first;
   GitHub keeps PR-description edit history visible.
3. `gh pr merge <n> --squash` with a quote-free body (PowerShell 5.1 splits arguments on double
   quotes). Do not pass `--delete-branch`.
4. Confirm from the remote: `gh pr view <n> --json mergedAt` is set. Only then delete the remote
   branch (`git push origin --delete task/Tnn`; the git guard refuses it until GitHub shows the merge,
   ADR 0021) and fast-forward `main`; have a Haiku agent remove the worktree and delete the local
   branch (`brief` skill, `chore.md`; worktree removal on Windows: `docs/reference/windows-and-shells.md`:
   give the chore the `\\?\` delete and one git command per call, and launch it only after the
   verifier has reported its last check, or its emulator and Gradle daemon hold files in the folder).
   If a branch was deleted before the merge succeeded and the PR closed: recreate the branch at the
   recorded SHA and `gh pr reopen <n>`.
5. Write the dated log entry and the **Status:** line to two files and run
   `python -I tools/merge-log.py Tnn <entry> <status>` (it updates `docs/PLANNING.md`, `docs/TASKS.md`
   and `CHANGELOG.md` together); the entry carries the PR's measurements and findings, describing any
   personal area generically. Commit to `main` and push with `bash tools/push-verified.sh`; report the
   VERIFIED line.
6. Rewrite the handoff when Now or Next changed (`handoff` skill).

## Privacy at the coordinator

Log entries and PR bodies describe personal areas generically; the hooks catch names and coordinates,
not paraphrases. The git hooks never see PR text, so before merging run the PR title and body through
the scanner (merge sequence, step 2); a hit means edit the description first, and remember GitHub keeps
PR-description edit history visible. A place the owner asks about in chat (a screenshot, a demo) may be
a private term even if it is a famous summit; use only `areas.json` names in anything written down.
Local file names leak too: the live server lists every pack in `out/` by file name through `/catalog`
and `/route/packs`, and agents copy such listings into PR bodies and sweep summaries, so a local pack
for a private place gets an alias name (the owner's demo pack is `sdc`), never the place name. `out/`
and the caches are regenerable; the only files on the machine that are not are the signing key and
`devtool.local.json`.

## Close the wave

1. After the last merge: update the README status paragraph, and `docs/ARCHITECTURE.md` if the module
   map or formats changed.
2. Launch the post-wave sweep (`sweep` skill). Its findings become the next wave's sweep task.
3. When the sweep is green, publish an alpha (`release` skill); the owner need not be asked again
   (ADR 0024).
4. Rewrite the handoff (`handoff` skill) and commit it.
