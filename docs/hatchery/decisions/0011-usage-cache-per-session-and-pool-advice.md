# 0011. The usage cache is per session, the account a label, the pool advice at HOLD

Date: 2026-10-16
Status: accepted

Record: proposal `../proposals/0011-usage-gate-under-account-switching.md` and
`../proposals/0011-critic-review.md`. Amended the same day, before the push, from the stage 1
review and the stage 2 build: the label-change rule, the old file left in place, last-good pool
readings, the organization in the pool join. Evidence: `docs/findings/2026-10-16-account-switcher-practices.md`;
facts: `docs/reference/claude-swap.md`. Amends ADR 0006 (the key table, which is living, and the
`resets_at` source in Writing a grant) and the cache paragraph of `0002-hook-specs.md` section 3.

## Context

The status line cached the meters Claude Code pipes to it in one file, `~/.claude/usage-state.json`,
and the gate judged that file. That assumed one account per machine. With an account switcher
(claude-swap, `cswap`) the login changes under a running session (`cswap switch`, `cswap auto`)
and sessions on different accounts run side by side (`cswap run`, which sets `CLAUDE_CONFIG_DIR`
per session). The shared file then held one account's readings and was judged for another, and
parallel sessions overwrote it. The owner also wanted to know, at HOLD, whether another account had
room, and whether rotation and HOLD fit together at all.

Verified (critic, 2026-10-16): the status-line payload carries `session_id` and `rate_limits`, no
account identity, and Claude Code's status-line docs say to key a per-session cache by
`session_id`; the identity is `oauthAccount` (`accountUuid`, `emailAddress`, `organizationUuid`)
in `.claude.json` under `CLAUDE_CONFIG_DIR` else home, with a legacy `.config.json` precedence;
hooks inherit `CLAUDE_CONFIG_DIR` unless `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1` strips it; the
Agent `tool_input` carries `subagent_type` and an optional `model`; `cswap list --json` has the
fields the reference lists and no account uuid; the usage endpoint has a per-account request
budget (one estimate, thirty an hour) that a polling status line exhausted upstream. Practice
found in the survey: switch at session boundaries (a switch cold-starts the prompt cache), let
the switcher hold the cross-account readings, pin parallel sessions to accounts.

## Decision

### The cache: one file per session

- `~/.claude/usage-state/<session_id>.json`, written by the session's status line from the
  `session_id` on stdin, read by the gate from the `session_id` in the PreToolUse payload. Always
  under the user's `~/.claude` (like the grants directory), never under `CLAUDE_CONFIG_DIR`, so
  the location depends on neither the variable nor the scrub. The directory is created on first
  write; files older than one day are deleted when a session's file is created. The old
  `~/.claude/usage-state.json` is neither read, written nor deleted: sibling projects on the older
  base still read it (stage 1 review, 2026-10-16; the deletion the first draft had is withdrawn).
  No `session_id`: the status line writes nothing, the gate takes the stale path.
- Shape: `{ "account": { "uuid", "organizationUuid", "email" } | null, "five_hour": { "used_percentage",
  "resets_at" }, "seven_day": {...}, "apiMs", "updatedAt" }`, epoch seconds; `apiMs` is the
  payload's `cost.total_api_duration_ms` at the write. Merge per window as before: a window on
  stdin replaces the cached one, an absent one keeps it. Rule order on a render: no window on
  stdin, nothing is written, whatever the label did. A window present and the labels equal, or
  either unknown: merge, and write the current label when known, else keep the cached one (a torn
  read is a no-op). A window present and the labels both known and different: the payload repeats
  the last response's `rate_limits`, which after `cswap switch` are the previous account's until
  the next response (stage 1 review), so the change is refused while the payload's
  `cost.total_api_duration_ms` equals the file's `apiMs`: nothing is written, the line shows
  `n/a`, and the gate sees the mismatch as stale. Any other case accepts: a greater counter (a
  response has happened since the last write), a smaller one (a resumed process, whose
  `rate_limits` can only be its own responses'; stage 2 review), no `cost` in the payload, or no
  `apiMs` in the file. On acceptance every cached window is dropped and the payload's are taken
  under the new label. Labels differ when `uuid` differs, or `organizationUuid` differs
  with both known; a field unknown on either side does not differ.
- The label: `readAccount()` in `policy.js`, the one reader both scripts use: config home is
  `CLAUDE_CONFIG_DIR` when set else the home directory; `.config.json` in the config home, then
  `.claude.json` there, the first that parses with an `oauthAccount`; returns the three fields or
  `null`. It is display and consistency only, never the key.

### The status line

The segment `<email local part>` before the meters, omitted when the label is unknown:
`Opus 5.5 | ctx 25% | jdorje | 5h 24% (hold 50) ->12:20 | 7d 72% (hold 50) ->Mon 06:00`.
When the current label and the file's are both known and differ, the meters read `n/a`, as the
gate treats them. The owner reviewed the form on 2026-10-16 and chose the bare local part.

### The gate

- Reads the session's file. The detail names the file's email when there is one:
  `Usage gate (jdorje@example.com): 5h X%, 7d Y%. ...`. When the file's label and the current
  label are both known and differ, the file is no usage data (the stale path, `whenStale`), with
  the reason on stderr; the gate never judges another account's numbers.
- `usage.pool`: `null` (base) or `{ "command": [argv], "timeoutSeconds": 15 }` (an integer from
  1 to 60, under Claude Code's own hook timeout, so a hung command ends in a deny rather than a
  timed-out hook; a shell shim such as a `.cmd` is "not found" by a spawn without a shell, so the
  argv names a real executable). Run only when the
  gate is about to deny at HOLD after the grant check: a spawn without a shell, stdin closed,
  stdout parsed as the `cswap list --json` contract (`accounts[]`: `number`, `email`,
  `organizationUuid`, `disabled`, `usageStatus`, `usage.fiveHour.pct`, `usage.sevenDay.pct`,
  `usageAgeSeconds`, and for a row whose `usage` is null `lastGoodUsage` with
  `lastGoodAgeSeconds`; unknown fields ignored). A row's reading is `usage` when `usageStatus` is
  `ok`, else `lastGoodUsage` when `lastGoodAgeSeconds` is at most 3600 (cswap marks any row not
  fetched within five minutes `unavailable` and keeps the last reading; the age is in the text, so
  an hour-old reading is advice the owner can weigh), else none. Room: not `disabled`, a reading,
  not the session's account (every row whose email equals the label's, case-insensitively, and
  whose `organizationUuid` equals the label's when both are known, is excluded), 5h and 7d each
  below that meter's hold; the row with the largest minimum of hold minus pct is named, with its
  reading's age. Appended to the HOLD deny:
  ` Report to the owner: account <email> (slot <n>) had room <m> min ago: 5h a%, 7d b%. The next
  session is the owner's to start there (cswap switch <n>, or cswap run <n>).`; else ` No other
  account has room (pool readings up to <m> min old); an override grant is still the lift (ADR
  0006).`; on failure (absent program, timeout, non-zero exit, bad JSON) ` No pool reading
  (<reason>).`; with the label unknown the text adds ` (this session's account not excluded)`.
  Hatchery's `policy.json` sets `["cswap", "list", "--json"]`; a decanted project keeps `null`.
- Grants unchanged. ADR 0006, Writing a grant: the weekly `resets_at` comes from this session's file.

### The practice

`CLAUDE.md`, HOLD row: the report after the handoff names the account the gate reported, and the
next session is the owner's to start there; rotation happens at session boundaries. `cswap auto`
runs only when every coordinator session was started with `cswap run --require-session`, else not
at all. The `handoff` skill records the session's account and the one the gate named. Thresholds
unchanged.

### Harness

Group S: `session_id` present and absent; two sessions side by side; label present, absent, from
the legacy file, torn; a label change with a greater `cost.total_api_duration_ms` accepted and all
old windows dropped, with the same value refused (nothing written, `n/a`), and with a smaller
value, no `cost`, or a file without `apiMs` accepted; a no-window render after a label change writing nothing; the prune; a session grant
not lifting when the payload has no `session_id`. Group U: a file for another session only;
label mismatch as stale; every existing U, UX, UO case carries a `session_id`. Group UP: `pool`
null; a scratch policy whose command is `["node", "<stub>", "<fixture>"]` for room, no room,
disabled and non-ok rows, the session's email excluded, unknown label, a missing program, a stub
that sleeps past the timeout, non-zero exit, bad JSON. Never a stub on PATH.

### Out of scope and deferred

- The per-model meter: `docs/ideas/2026-10-13-fable-per-model-usage-gate.md`; revived by a grant
  that can name a model window and a reading the status line can show.
- Judging the pool instead of the account, switching accounts from the hook, a proxy: rejected in
  the proposal (options C, D, E).
- Unverified: a hook inside a subagent carries the parent's `session_id` (docs: "current session
  identifier"; inside a subagent `CLAUDE_CODE_SESSION_ID` equals the parent's, observed
  2026-10-16, but no hook payload was captured). If not, a nested launch takes the stale path,
  fail-open, said on stderr.
- Residuals accepted: a torn identity read on the first render after a switch writes the new
  windows under the old label, treated as stale until the next successful render; a fresh session
  whose first response launches an agent may run the gate before its status line has written, so
  the first launch takes the stale path (`whenStale: deny` for a project that would rather wait);
  the old shared file is left for the owner to delete once every project on the machine runs the
  new scripts.

## Consequences

- A switch under a session and parallel sessions are correct by construction; no identity is a
  key, and the scrub cannot break the cache. A new session reads `n/a` until its first response,
  which the gate never needed (it fires after a response).
- At HOLD the owner learns which account has room without running cswap; the gate pays one
  process spawn only on a refused launch, and nothing per render.
- Costs: a directory of small files with a prune rule; one more policy key; a pool parser bound to
  one tool's output shape (the base holds the mechanism, the policy the tool); harness groups to
  keep. `cswap run` on Windows cannot share history, so `--resume` does not cross accounts.
- Supersede when Claude Code's payload carries the account or per-model meters, or when the
  switcher ships a hook surface of its own.
