# .claude/skills/ — procedures

A skill is loaded with the Skill tool when its description fits the moment; only the descriptions are
always in context. Procedures live here, not in `CLAUDE.md` or `docs/` (ADR 0020). Ported from the
Hatchery base: `brief`, `handoff`, `adr`, `proposal`, `finding`, `triage`, `update-from-hatchery`.
This project's own: `wave`, `release`, `sweep`.

| Skill | Use it to |
|---|---|
| `brief` | Write the brief for an implementer, verifier, chore or critic agent (templates in the directory) |
| `wave` | Resume a coordinator session, launch and verify tasks, run the merge sequence, close a wave |
| `sweep` | Launch the post-wave sweep and read its summary |
| `release` | Publish an alpha from main, verified from the draft; the local fallback build |
| `handoff` | Rewrite `docs/HANDOFF.md` at the end of a session and before any stop |
| `proposal` | Proposal, critic and revision for a major choice (with `brief-critic.md`) |
| `adr` | Write, promote or supersede an ADR in `docs/design/decisions/` |
| `finding` | Record, resolve or supersede a finding in `docs/findings/` |
| `triage` | Read the untriaged files in `docs/ideas/` and write one block each (`Plan:`, `Question:`, `Waits:`); the base's skill verbatim (ADR 0036) |
| `update-from-hatchery` | Patch the base-owned set in from the `Hatchery base:` line of ADR 0038 by the ownership map and bump it (ADR 0038 holds the departure table) |
