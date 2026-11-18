## Usage gating (hard rule)

Plan limits are the 5-hour session meter and the weekly meter (`/usage`). The status line shows
both and flags HOLD and STOP at the thresholds in `.claude/hooks/policy.json` (`node
.claude/hooks/policy.js` prints them; a project sets its own there, never in the JS); the
`usage-gate` hook refuses new subagent launches, and a `SendMessage` that would resume or message
an agent, from HOLD, whoever calls, except a report to `main` or a message-less notify, which
always pass. The gate blocks launches and sends only, never Bash. How the meters reach the hook, staleness, and the owner's override: ADR 0006 and
`docs/hatchery/decisions/0002-hook-specs.md`.

| Usage (the worse meter, each judged against its own thresholds) | Behavior |
|---|---|
| below HOLD | Normal operation. |
| HOLD on the session meter, the weekly meter below HOLD or its HOLD lifted by an override | No new subagents or tasks; let in-flight agents finish, run the `handoff` skill and commit from the main session; report to the owner first, quoting the gate's deny line, pool advice included (ADR 0011), so the owner can switch accounts instead of waiting; then schedule one wake-up for the session window's reset time (`five_hour.resets_at` in the session's usage file `~/.claude/usage-state/<session_id>.json`, ADR 0011; `/usage` prints the same time) with a one-shot `CronCreate` set two minutes after `five_hour.resets_at` (a wake-up on the exact minute can fire early and be refused), do nothing until it fires, and on waking run `/usage` and resume from the handoff. A session without a scheduler stops as the weekly row says. The override grant (ADR 0006) lifts the HOLD without waiting. |
| HOLD on the weekly meter, below any STOP (when the policy sets one) | No new work, subagents or tasks, unless the owner's override grant lifts HOLD (ADR 0006). Otherwise let in-flight agents finish, run the `handoff` skill, commit from the main session, then stop and report. When the gate's deny named another account with room (`usage.pool`, ADR 0011), the report says so; the next session is the owner's to start there. Accounts change at session boundaries, never under a running session. |
| STOP and above (when the policy sets one) | Stop. Run the `handoff` skill, commit in-progress work from the main session with a clear WIP message, end the turn. |

Meters show only on Pro and Max plans, after the first response; on `n/a`, run `/usage` first.
