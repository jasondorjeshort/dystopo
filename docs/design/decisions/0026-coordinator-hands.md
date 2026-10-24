# 0026. The coordinator's own hands: it coordinates; everything that runs the project is delegated

Date: 2026-10-12
Status: accepted (owner's rule)
2026-10-24: the merge item reads "merging a verified task branch" since ADR 0043 (no pull requests); the rest stands.

Record: commit `99a84e5` ("Rule: the coordinator's own hands; everything that runs the project is
delegated"), the first bullet of "Coordinating a wave" in `CLAUDE.md`; carried to Hatchery the same
day (its `CLAUDE.md` Delegation section, commit `89f63c8`).

## Context

On 2026-10-12, on a fresh account, the coordinator installed the Android SDK, ran `./gradlew check`
and started building packs itself. `CLAUDE.md` said only "agents do all coding" and in six places
told the coordinator to start the live server, run sweeps, build a release locally and clean up
worktrees by hand. Work done in the coordinator's own context costs the most expensive model's
tokens, fills the context that has to last the wave, and is not verified by anyone.

## Decision

- **The coordinator's own work:** reading, deciding, talking to the owner, writing `docs/TASKS.md`,
  the briefs (`brief` skill), `docs/HANDOFF.md` and the log (`tools/merge-log.py`), merging a
  verified PR, committing and pushing its own doc edits to `main` (`tools/push-verified.sh`), and
  deciding and announcing a release. Nothing else.
- **Always delegated, even when it is one command:** pulls and rebases, SDK and tool installs, any
  `./gradlew` task (`check`, `installDist`, a compile check), tilebuild and pack builds, servers (the
  8765 live server included), emulators, sweeps, release builds and draft checks, worktree and branch
  cleanup, and every fix however small.
- **Model per kind of delegated work:** Opus-class for code and fixes; Sonnet-class for
  verification, environment setup that may need judgment, and sweep failures; Haiku-class for pulls,
  builds, server starts, a sweep whose JSON summary suffices, and git cleanup.
- **At the usage gate** the coordinator writes the handoff and stops (`handoff` skill); it does not
  do the delegated work itself.
- The git guard follows this: the main session is not restricted by caller, but branch deletion is
  tied to a merged PR rather than to the main session, because cleanup is an agent's job (ADR 0021,
  E4 and E5).

## Consequences

- The procedures that told the coordinator to act (sweep, release, merge cleanup, starting servers)
  are written as briefs for agents in the `sweep`, `release` and `wave` skills.
- More launches per wave, each cheap; the coordinator's context lasts longer.
- A coordinator that finds itself about to run a build or a server launches an agent instead; when
  none can be launched, it stops.
