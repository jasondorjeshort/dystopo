## Usage gating (hard rule)

Plan limits are the 5-hour session meter and the weekly meter (`/usage`). The status line shows
both and flags HOLD and STOP at the thresholds in `.claude/hooks/policy.json` (`node
.claude/hooks/policy.js` prints them; a project sets its own there, never in the JS); the
`usage-gate` hook refuses new subagent launches from HOLD. The gate blocks launches only, never
Bash. How the meters reach the hook, staleness, and the owner's override: ADR 0006 and
`docs/design/decisions/0002-hook-specs.md`.

| Usage (the worse meter, each judged against its own thresholds) | Behavior |
|---|---|
| below HOLD | Normal operation. |
| HOLD and above, below any STOP (when the policy sets one) | No new work, subagents or tasks, unless the owner's override grant lifts HOLD (ADR 0006). Otherwise let in-flight agents finish, run the `handoff` skill, commit from the main session, then stop and report. When the gate's deny named another account with room (`usage.pool`, ADR 0011), the report says so; the next session is the owner's to start there. Accounts change at session boundaries, never under a running session. |
| STOP and above (when the policy sets one) | Stop. Run the `handoff` skill, commit in-progress work from the main session with a clear WIP message, end the turn. |

Meters show only on Pro and Max plans, after the first response; on `n/a`, run `/usage` first.
