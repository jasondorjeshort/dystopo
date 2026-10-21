# Dystopo — notes for agents

Where work stands right now (coordinator state; subagents follow their brief, not this):

@docs/HANDOFF.md

Open-source, offline-first topographic maps. This file holds the rules; facts, procedures and decisions are one pointer away.
**Process base:** the working method is shared with the Hatchery base repository (https://github.com/jasondorjeshort/hatchery), which distils it for new projects; Dystopo is its first build-phase test. What this project takes from the base and what differs: ADR 0020. When a rule proves wrong in practice, fix it in the same sitting, say why in the commit message, and carry the fix back to Hatchery.
Adopted from Hatchery by hand; provenance, the base line and departures: `docs/design/decisions/0038-adopted-from-hatchery-after-t121.md`. The base's agreement sections (`.claude/agreement/`) are each replaced by a one-line pointer at this file's own section:

Branch: main

@.claude/project/this-agreement.md
@.claude/project/where-things-live.md
@.claude/project/delegation.md
@.claude/project/debug-interface.md
@.claude/project/major-decisions.md
@.claude/project/usage-gating.md
@.claude/project/model-routing.md
@.claude/project/one-review.md
@.claude/project/git.md
@.claude/project/licensing.md

## Where things live
- System map and where every topic is documented: `docs/ARCHITECTURE.md` (its last section is the doc index). Read a directory's `README.md` before its files.
- Facts (commands, debug routes, emulators, this machine's paths and ports, Windows quirks): `docs/reference/`. Start with `machines.md` for paths and ports.
- Decisions: ADRs in `docs/design/decisions/`. Design detail, measurements and the dated log: `docs/PLANNING.md` (read it before architectural changes). Tasks: `docs/TASKS.md`.
- Investigation results: `docs/findings/`. Procedures: skills in `.claude/skills/` (`brief`, `wave`, `sweep`, `release`, `handoff`, `proposal`, `adr`, `finding`). Path-scoped conventions: `.claude/rules/` (docs, Kotlin, the track database).
- Hooks: `.claude/hooks/` (usage gate, `git-guard` with the base's push scan, `post-edit`; `node .claude/hooks/test-hooks.js`; policy in `policy.json`, ADR 0035) and the git hooks in `tools/git-hooks`. Updates from the base: the `update-from-hatchery` skill (ADR 0037).

## Layout (detail: `docs/reference/code-layout.md`)
- `shared/` — Kotlin Multiplatform core (JVM and Android; iOS later). `commonMain` stays pure Kotlin, **no `java.*` imports**; platform pieces come in through small interfaces or `expect`/`actual` (`.claude/rules/kotlin.md`).
- `devtool/` — JVM command-line tool that runs shared-core features and prints JSON reports; JVM-only code (image I/O, PNG rendering) lives here.
- `serverlib/` and `server/` — the server pieces shared by `devtool serve` and the standalone server (`docs/SERVER.md`).
- `tilebuild/` — OSM extract → PMTiles via Planetiler; heavy and LGPL dependencies stay here.
- `androidApp/` — Compose + MapLibre Native + `shared`, a loopback tile server and debug routes (`docs/reference/android-app.md`).
- `tools/` — repo tooling (privacy check and git hooks, demo page and checkers, emulator, sweep and release scripts). `areas.json` — public named test areas; personal ones go in the gitignored `devtool.local.json`.

## Conventions (detail: `.claude/rules/kotlin.md`)
- Every raster goes through `MercatorWindow`; elevation is `Float` metres in **EGM2008** with `NaN` for no data; coverage edges are feathered with `Composite.over`.
- Commands print one JSON object to stdout and write PNGs to `out/`; add counters and timings to reports rather than printing logs. **View the PNGs and screenshots**: several bugs were only visible in the image.
- Tests in `shared/src/commonTest` run offline and fast; network checks are devtool commands. Rendering is guarded by goldens (`./gradlew updateGoldens` when a change is intended; look at the new PNGs) and `StyleParityTest`.

## Commands (every command: `docs/reference/devtool-commands.md`)
JDK 21 is required (`JAVA_HOME` path in `docs/reference/machines.md`). `devtool` runs with the repo root as working directory; downloads are cached in `cache/` (or `$DYSTOPO_CACHE`), output goes to `out/`.
```
./gradlew check                                           # all tests, goldens, licence check (no device needed)
./gradlew :devtool:run --args="render --area rainier"     # hillshade + contours -> out/rainier-best-z13.png + JSON
./gradlew :devtool:run --args="tour --zooms 9,12"          # world sanity sweep over areas.json
./gradlew :devtool:run --args="serve --live --port 8687"  # the viewer, live world mode (docs/reference/live-server.md)
python -I tools/demo-check.py --url http://localhost:8687/ --views 12.5/46.853/-121.76   # headless browser check
./gradlew :androidApp:assembleDebug                        # Android (SDK and emulators: docs/reference/android-emulator.md)
python -I tools/android-check.py --port <p> --serial emulator-<n> --views-file tools/android-tour.txt   # device tour
node .claude/hooks/test-hooks.js                           # hook tests
```

## Android (detail: `docs/reference/android-*.md`, `release-realism.md`)
- Every request to the phone's loopback server carries the per-launch token (`?t=` or `X-Dystopo-Token`); debug routes exist only in debug builds, on the debug port announced in logcat (`android-app.md`). Each `*Routes.kt` file's KDoc documents its routes.
- Drive the app through the debug routes and scripts, and view the screenshots (`/screenshot`, contact sheets). Release builds are driven by taps only (`tools/android-firstrun.py`, `tools/android-ui.py`).
- **No Wi-Fi gates** (owner's rule, ADR 0023): nothing is limited to Wi-Fi; data use is steered by visible limits. **Units** are a user preference, seeded once by region (ADR 0022).
- Publishing is the `release` skill (policy ADR 0024); the release key never changes and never enters the repo.

## Debug before you push
The implementer debugs the change; the reviewer confirms. Before opening a PR:
1. `./gradlew check` green (unit tests, goldens, license check).
2. Exercise the change end to end with the real tooling: render the affected areas at the zooms that matter and look at the PNGs; run `tiles --verify` for anything touching tiles; run `tools/demo-check.py` for anything touching the viewer; run `tilebuild` for anything touching the profile; the device tour for anything on the phone. Fix what you find, then re-run.
3. The PR body lists exactly what was run and what was seen. A verifier agent then repeats the key runs; "the reviewer will catch it" is not a plan.
4. **Documentation is part of done.** A task updates the docs its change touches in the same PR: the command, endpoint and setup lines in `docs/reference/`, `docs/SERVER.md` for server behaviour and config, the design section of `docs/PLANNING.md` (and an ADR) for a design that changed, `tilebuild/PROFILE.md` for layers, the registry (`sources.json`) for data sources, the KDoc at the top of a `*Routes.kt` file for its routes, `docs/ARCHITECTURE.md` when a module or format is added, and this file only for a rule. Measurements and findings go in the PR body; the coordinator carries them into the dated log, and what outlives the task into `docs/findings/`.

## Working in parallel (several agents at once)
- One task from `docs/TASKS.md` per agent, in its own git worktree on branch `task/<task-id>` (paths per account: `docs/reference/machines.md`). Work only in that worktree and stay inside the files the task's **Owns:** line gives it. Nobody commits to `main` while agents are active except the coordinator's own doc edits.
- Set `DYSTOPO_CACHE` to the main checkout's `cache` so worktrees share downloaded data instead of re-fetching it from USGS and AWS.
- Before pushing: `./gradlew check`, then `git fetch && git rebase origin/main`. Push the branch and open a PR (`gh pr create --fill`) with measurements, timings and what you saw in the PNGs. Don't commit PNGs (goldens excepted).
- Don't edit the Phase 0 log in `docs/PLANNING.md` from a task branch; the coordinator adds the entry when merging. Edit other docs only where the task says so (and for the docs your change touches, below).
- The privacy hooks run in worktrees too. Use only public areas from `areas.json` in anything committed or written up.
- **Stop only your own processes, by PID or by emulator serial.** Never match processes by name or command-line substring: once that killed every other agent's emulator and the coordinator's live server (finding `docs/findings/2026-10-10-kill-by-command-line-substring.md`). The same goes for `./gradlew --stop`: it stops every Gradle daemon of the account, including a server another agent runs through `:devtool:run` (it killed the coordinator's live server on 2026-10-12); run your own Gradle with `--no-daemon` if you must, and never stop daemons you did not start.
- **Never create a junction or symlink inside a worktree that points at the main checkout**: removing the worktree follows the link and deletes the target (it wiped the main `out/` once). Read shared data by absolute path, copy the few files you need, or generate into your own `out/`.
- Model per kind of work: the most capable model for planning, docs, review decisions and merges; Opus-class for implementing a task and its tests; Sonnet-class for verifying a PR and small fix-ups; Haiku-class for mechanical git work, server starts and sweeps whose JSON summary suffices. Set the model explicitly on every launch; the default inherits the launcher's, usually the most expensive.

## Coordinating a wave (procedures: `wave`, `sweep`, `release`, `brief`, `handoff` skills)
- **The coordinator's own hands** (ADR 0026). The coordinator reads, decides, talks to the owner, writes `docs/TASKS.md`, the briefs, `docs/HANDOFF.md` and the log (`merge-log.py`), merges a verified PR, commits and pushes its own doc edits to `main` (`push-verified.sh`), and decides and announces a release; nothing else. Everything that runs the project is delegated, even one command: pulls and rebases, installs, any `./gradlew` task, pack builds, servers (8765 included), emulators, sweeps, release builds and draft checks, worktree and branch cleanup, every fix. When no agent can be launched, it writes the handoff and stops.
- **Unit of work is a task, not a folder.** Owns lines partition the files so agents never collide (a feature usually spans `shared`, `devtool` and tests, so a folder-owner would need a handoff for every feature); two tasks that must touch the same file go in different waves, or one owns it and the other records a follow-up.
- **Roles.** Coordinator (most capable model); implementer per task (Opus-class): builds, tests, debugs end to end, opens the PR; verifier per PR (Sonnet-class): rebases if `main` moved, runs `check`, repeats the key runs, views the PNGs, returns MERGE / MERGE WITH NOTES / FIX; chores (Haiku-class), escalated to Sonnet on a failure or when a sheet must be viewed.
- **Hub and spoke.** Agents report to the coordinator; a verifier may message its implementer for a small fix-up, but scope or ownership changes go through the coordinator. Nobody edits another task's files.
- **Wave size and the usage gate.** 5 to 8 implementers at once. Stop launching new implementers at 50% of the 5-hour budget or 75% of the 7-day budget (owner, 2026-10-17; this session's file under `~/.claude/usage-state/`, written by `.claude/statusline.js`; the `usage-gate` hook enforces it, numbers in `.claude/hooks/policy.json`); verifiers and merges of work already launched may run past the line to close the wave (owner, 2026-10-10). Only the owner lifts the line: a grant file in `~/.claude/usage-gate-overrides/` (one meter or both, until a time, for a session, a project or the user; ADR 0035), or a session started with `DYSTOPO_GATE_OVERRIDE=1`. Say the reading when crossing it.
- **Merge only after a verifier's MERGE**, by the `wave` skill's merge sequence; delete a task branch only after the merge shows on GitHub (`git-guard` refuses earlier). **Publish after a green post-wave sweep** without asking again (ADR 0024).
- **Report outcomes from the remote, never from the command.** A push, merge, tag or release is reported only after an independent read shows it (`bash tools/push-verified.sh`, `gh pr view --json mergedAt`, `gh release view`, the artifact downloaded from the draft); agents' reports are checked the same way before they are relayed. A command meant for another repository uses `git -C <path>` and prints `git rev-parse --show-toplevel` first (finding `docs/findings/2026-10-11-reported-push-that-never-happened.md`).
- **Privacy at the coordinator too.** Log entries and PR bodies describe personal areas generically; the hooks catch names and coordinates, not paraphrases, and never see PR text, so the PR title and body go through `PrivacyCheck` before merging (`wave` skill). A place the owner asks about in chat may be a private term even if it is a famous summit; use only `areas.json` names in anything written down. A local pack for a private place gets an alias name (the owner's demo pack is `sdc`): the live server lists `out/` by file name. `out/` and the caches are regenerable; the signing key and `devtool.local.json` are not.
- **Handoff.** Rewrite `docs/HANDOFF.md` (`handoff` skill) at the end of every coordinator session and before any stop, so a fresh session resumes from it alone.

## Decisions
- A major choice (a format other tools or phones read, a protocol or API, a routing or rendering engine, a build system, a dependency the whole project leans on) goes through a proposal, a critic and an ADR before building (`proposal` and `adr` skills). A decision made in prose gets an ADR.
- Technical decisions are the coordinator's after the critic, reported to the owner. **What humans see and touch** (the front end, flows, wording, visuals): in alpha, a change that an idea or a task entry describes is built without asking and shown in the PR with screenshots and its new strings; the owner adjusts it afterwards (ADR 0031). Data format changes are built the same way in alpha, listed in the PR, user data migrated (ADR 0033). A removed flow, the app's identity and new data flows are still flagged, not guessed.
- **No other product names in user-visible text** (owner, 2026-10-13): labels, options, help, errors and `--help` output name no other mapping software; code, KDoc, docs and the log may.

## Rules
- **Never commit personal locations.** No home/travel areas, real GPS tracks, or coordinates that reveal where the developer lives or travels, in code, tests, fixtures, docs, planning logs, PR descriptions, or commit messages. That includes indirect hints: nearby place names, lidar project names, DEM cell/tile names. When logging results from a personal area, describe it generically. Use public places (Rainier, Mont Blanc, Glacier/Waterton, Bonneville) or synthetic data in anything committed.
  - Git hooks in `tools/git-hooks` enforce this against `devtool.local.json` (area names, `privateTerms`, bbox coordinates, DEM cell names), and the pre-push hook chains the base's personal-data scan of every outgoing commit (ADR 0037). Enable once per clone: `git config core.hooksPath tools/git-hooks`. They need Java (`JAVA_HOME` or `java` on PATH) and Node.
  - **Never bypass with `--no-verify`** (`git-guard` refuses it). If the check fires, fix the content.
- **Every feature must be agent-testable:** unit tests in `shared`, plus a devtool command or debug route that triggers it and reports results as JSON or PNG.
- **Dependencies must be permissively licensed** (MIT, BSD, Apache-2.0, EPL/EDL). No GPL or AGPL dependencies anywhere; they'd block relicensing. LGPL is tolerated only in build-side tooling modules (`tilebuild`), never in `shared`, `devtool`, or app modules. `./gradlew check` enforces this (`licenseCheck`; config in `gradle/licenses/`, reports in `<module>/build/reports/licenses/`). An artifact whose POM has no license needs a line in `gradle/licenses/artifact-overrides.txt` with its real license. Don't copy code from other projects.
- **Data sources need license and attribution recorded** in `shared/src/commonMain/resources/sources.json` (the registry; see `docs/PLANNING.md` › Licensing). A new source gets a registry entry first; devtool sources declare `registryIds` and set `provenance` on results so reports and tile metadata credit exactly what contributed.
- Commit as you go with descriptive messages. Don't amend pushed commits. The coordinator's own doc edits go straight to `main`; everything else goes through branches and PRs (above).
