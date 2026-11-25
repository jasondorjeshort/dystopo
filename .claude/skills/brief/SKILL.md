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
   wave): implementer Opus-class; verifier Sonnet-class, Haiku-class when the task's Done when is
   wholly in its **Checks:** block (the `wave` skill, Verify); chore Haiku-class (escalate to Sonnet on a
   failure or when an image must be judged); benchmark Sonnet-class with web search (the most
   capable model when it must weigh competing designs rather than report them; ADR 0039, and its
   technical variant for a technical choice in doubt, ADR 0044); critic
   the most capable model.
2. Fill the matching template in this directory: `implementer.md`, `verifier.md`, `chore.md`,
   `benchmark.md`. Every field is required; write "none" rather than leaving one out.
3. Point at indexes and files, do not paste them: the task text is "### Tnn" in `docs/TASKS.md`; facts
   are in `docs/reference/` (start at its README); decisions in `docs/design/decisions/`.
4. Machine facts that override the docs (this account's paths, ports, AVD names, emulator console and
   host ports, the cache directory) go in the brief: give every parallel agent its own ports and its
   own AVD, from `docs/reference/machines.md`.
5. "Already ruled out" prevents re-investigation: what was tried and why it failed, with the finding
   path when there is one.
6. Scope is the task's **Owns:** line plus anything resolved for this wave (the wave's paragraph in
   `docs/TASKS.md`); name what must not be touched; an **Interacts with:** line is copied into both
   the implementer's and the verifier's brief.
7. Verification is a command or an observable from the task's **Done when:**, never "make sure it
   works".
8. The report asks for what the coordinator needs to merge or relaunch, and nothing else.

## Reports by path (a change that gets a review)

- The brief names a file for each report, in one directory under the scratchpad for that change:
  `writer-<n>.md`, `review-<n>.md`, `response-<n>.md`, `<n>` being the round. The harness
  refuses a subagent's Write of a `.md` file whose name starts with `report`, `summary`,
  `findings` or `analysis`, in any case.
- The agent returns the path and a few lines (the reviewer: the count per severity, the ids of
  blocking and serious findings, its verdict and its two closing lines). A fix goes back by
  resuming the writer (SendMessage) with the review file's path and a response file to write;
  when the writer cannot be resumed, a fresh writer gets the same two paths. The coordinator never
  rewrites a finding into a new brief.
- The coordinator reads the review file itself when it has to decide: a declined finding, an
  override, a verdict of rethink. These files are not the record; the commit and its message are.

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
