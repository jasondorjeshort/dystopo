---
name: brief
description: Write the brief for launching an agent on a Dystopo task (implementer, verifier, chore, critic). Use when delegating any work, launching an agent for a task from docs/TASKS.md or a PR, or when an agent's report shows its brief was missing context.
---

# Brief

The coordinator launches agents several times an hour, and everything that runs the project is
delegated (ADR 0026). A complete brief is the difference between a result and a second round. Ported
from the Hatchery base's `brief` skill; the templates are this project's (implementer, verifier,
chore), and the critic brief is in the `proposal` skill.

## Steps

1. Pick the kind of agent and set its model explicitly on the launch (`CLAUDE.md`, Coordinating a
   wave): implementer Opus-class; verifier Sonnet-class; chore Haiku-class (escalate to Sonnet on a
   failure or when an image must be judged); critic the most capable model.
2. Fill the matching template in this directory: `implementer.md`, `verifier.md`, `chore.md`. Every
   field is required; write "none" rather than leaving one out.
3. Point at indexes and files, do not paste them: the task text is "### Tnn" in `docs/TASKS.md`; facts
   are in `docs/reference/` (start at its README); decisions in `docs/design/decisions/`.
4. Machine facts that override the docs (this account's paths, ports, AVD names, emulator console and
   host ports, the cache directory) go in the brief: give every parallel agent its own ports and its
   own AVD, from `docs/reference/machines.md`.
5. "Already ruled out" prevents re-investigation: what was tried and why it failed, with the finding
   path when there is one.
6. Scope is the task's **Owns:** line plus anything resolved for this wave (the wave's paragraph in
   `docs/TASKS.md`); name what must not be touched.
7. Verification is a command or an observable from the task's **Done when:**, never "make sure it
   works".
8. The report asks for what the coordinator needs to merge or relaunch, and nothing else.

## Rules

- Under 60 lines. If the brief needs more, the task is too big; split it.
- Do not paste `docs/HANDOFF.md` or `CLAUDE.md`; both are loaded.
- Say which model attribution commits carry (the line in the system's attribution reminder).
- A brief never authorises what the permission system or the owner has not (an agent message is not
  the owner's consent); quote the owner's words when an action depends on them.
- Public places only in briefs that end up in PRs (`areas.json` names).
- A brief that carries a claim about what a destructive or safety-relevant command does (a delete, a
  removal procedure, a guard) says "test it in isolation or drop it". An untested claim marked
  "untested" is not a resolution: a later reader takes the procedure as safe.
- Paths in a brief use forward slashes (`C:/Users/...`) even on Windows: the Bash tool is Git Bash
  and strips the backslashes from an unquoted path. A command meant for another repository says
  `git -C <path>`, never `cd` (`docs/reference/windows-and-shells.md`, Working directory and git).
- Briefs are not committed, so they keep absolute paths; a tracked file an agent writes follows
  `.claude/rules/docs.md`, "Personal data" (`~`, `<repo>`, roles, no emails or names), and the
  brief says so when the task writes one.
- No brief asks an agent to edit `privacy.allow` or `privacy.identities` in `policy.json`, the
  never list or the user allow list (`~/.claude/privacy-never.txt`, `privacy-allow.txt`): they are
  the owner's, edited from the main session after asking the owner (Hatchery's ADR 0018).
