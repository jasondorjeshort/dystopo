---
date: 2026-11-08
kind: chore
---

# Cheaper verification: a verifier script for machine-readable Done-whens, and a Haiku-class verifier where it suffices

The owner, 2026-11-08, on the idea: "Definitely. If we can make test scripts or test haiku prompts that can help."

Wave 30 cost about 2.5 points of the 7-day meter per task under the serial model (ADR 0057), twice wave 28's; the
`wave` skill's figure was revised at the close. A verifier (Sonnet-class) spends most of its run re-running the same
things on every task: the worktree, `check`, the emulator boot, the tour, the report's scenario, the privacy scan,
then an eye on the screenshots. The first five are scripts whose output is JSON; only the last needs judgement.

## Open

- A verifier script (`tools/verify-task.ps1` or Python [no-check: the script this idea proposes]): given a branch, it makes the detached worktree, copies the
  packs, runs `check --no-daemon`, boots the emulator through `android-emu.ps1` with the guard limit the brief gives,
  runs the tour and any named tool parts, reads the guard log's lowest free and peak private, scans the commit
  messages and the report, stops and deletes the emulator, and writes one JSON summary; the verifier agent reads
  the summary, repeats only the report's scenario, and looks at the screenshots.
- A machine-readable Done-when: tasks state their checks as commands with expected outputs where they can (the
  tour's `ok`, a route's field, a test's count), so the script runs them and the agent's judgement is reserved for
  what a screenshot shows.
- Haiku-class verifiers for tasks whose Done-when is wholly machine-readable (a docs-only task, a test-only task like
  T240, a tool fix), Sonnet where an eye is needed; the `wave` skill's roles say which, and the brief template
  carries the script.
- The measurement: the next wave's close against 2.5 a task.

2026-11-10 (triage): ~~the verifier script~~: built by T246 (`tools/verify-task.ps1`, `verify-summary.json`,
`.claude/skills/brief/verifier.md`, `docs/reference/verifying-a-task.md`). Left: the machine-readable Done-when,
the Haiku-class verifier, and the measurement (wave 31's close reads T246's saving).

Plan: one no-Gradle pass after wave 31's close has read the per-task figure. `tools/verify-task.ps1` gains
`-Checks <file>`: a small JSON list of commands with the expected value (a JSON path and its value, an exit code, a
test count), run after the tour, each a step in the summary; `tools/test_verify_task.py` covers it from a fixture.
The `brief` skill's task template gets a "Checks:" block beside Done when; `.claude/skills/wave/SKILL.md`'s roles and
`CLAUDE.md`'s model line say a task whose Done-when is wholly in Checks (docs-only, test-only, a tool fix) gets a
Haiku-class verifier that runs the script and reports its summary, Sonnet-class where a screenshot needs an eye;
`docs/reference/verifying-a-task.md` updated. Done when: the script's test green; a dry run over a past task's
Checks written by hand passes; the rule's lines in place. Measured at wave 32's close against wave 31's figure.
