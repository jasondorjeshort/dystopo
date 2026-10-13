---
name: finding
description: Record the result of an investigation (root cause, measurement, failed approach, environment quirk, an incident behind a new rule) in docs/findings/ with status, and promote it when resolved. Use when a root cause is found, when something did not work and the reason is known, when a sweep or verifier finds a defect left for later, or when a tool behaves unexpectedly.
---

# Finding

Findings are the evidence trail. They are dated and status-tagged so a reader knows whether to trust
them. A finding is written when the investigation produces something worth not repeating, even if the
answer is "unresolved, here is what was ruled out". From the Hatchery base. A merged task's
measurements stay in its log entry in `docs/PLANNING.md`; a finding is for what outlives the task.

## Record

1. File: `docs/findings/YYYY-MM-DD-<slug>.md`, written with the Write tool from `template.md` in this
   directory. Frontmatter: `date`, `status: open`, `area` (free text, e.g. `android / emulator`),
   optional `supersedes: <file>`.
2. Sections: Question (what was being asked), What was tried (commands, versions, environment),
   Result (what is known now, with evidence), Next (what would resolve it; omit when resolved).
3. Add a row to `docs/findings/README.md` with status and a one-line summary.
4. If status is open and the coordinator should act, add the path under Open findings in
   `docs/HANDOFF.md`; a defect for the next sweep task also goes to the board as a follow-up.

## Resolve

1. Set `status: resolved` and fill Result with the answer. Delete Next or leave what remains.
2. Promote the durable part: a fact about a tool, route or environment goes to
   `docs/reference/<topic>.md` (create or extend; set `verified:` to today); a decision goes through the
   `adr` skill; a rule goes to `CLAUDE.md` or `.claude/rules/`. Set `promoted_to: <path>`.
3. Update the index row and remove the entry from `HANDOFF.md`.

## Supersede

When a later finding replaces an earlier one, write the new file with `supersedes: <old file>`, set the
old to `status: superseded`, and update both index rows.

## Rules

- Cap 150 lines; link to logs rather than pasting them. Large evidence stays in `out/` (gitignored) or
  in the report.
- Never edit a finding to say something different from what was observed; write a new one.
- Public places only; describe a personal area generically (`CLAUDE.md`, Rules).
