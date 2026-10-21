# ADR 0002 companion: hook specifications, usage gate and status line

Section 3 of the hook specs (`0002-hook-specs.md`), moved out on 2026-10-16 when that file reached the 300-line cap. Same conventions and sources; section numbering is unchanged.

## 3. `usage-gate.js` (PreToolUse, matcher `Agent|Workflow`, no `onFailure`) and the status line

Cache: one file per session, `~/.claude/usage-state/<session_id>.json` under the user's `~/.claude`
(never `CLAUDE_CONFIG_DIR`); its shape and the merge's rule order are ADR 0011's. A `session_id`
counts only as a plain name (letters, digits, `_`, `-`); without one the status line reads and
writes no file and shows the payload's windows. No window on stdin writes nothing, so a refresh
without `rate_limits` never replaces numbers with nulls. The write creates the directory, goes to a
temporary file and is renamed; a failed rename removes the temporary file. A write that creates the
session's file deletes every `*.json` and `*.tmp` there modified over a day ago; the old
`~/.claude/usage-state.json` is left alone. Every write records `apiMs`, the payload's
`cost.total_api_duration_ms` (else the cached value). A label change with a window is refused while
that number equals the file's `apiMs` (nothing written, meters `n/a`); any other value, smaller
included, or either missing accepts it and drops every cached window. Labels differ by `uuid`, or by
`organizationUuid` when both are known. The label is `readAccount()` in `policy.js`, shared by both scripts:
`.config.json`, then `.claude.json`, in `CLAUDE_CONFIG_DIR` else the home directory, the first with
an `oauthAccount` whose `accountUuid` is a non-empty string, as `{ uuid, organizationUuid, email }`
(the last two may be `null`); else `null`. It never throws and is never the key.
`settings.json` sets `"refreshInterval": 60`, so the cache stays fresh while the user is idle and
agents run. Line 1 is the working directory exactly as received
(`workspace.current_dir`, else the older `cwd`; no shortening); when neither is a non-empty string the
output is the meters line alone, with no blank first line. The meters line, after line 1's newline
when there is one, shows model, context, `<email local part>` (the current label's, else the
file's; omitted when neither carries an email), both windows with their reset times, and ` HOLD` or ` STOP` by the same per-meter judgement as the gate (the
helper in `policy.js`; ` STOP` only on a meter with a deny line); a cached window past its
`resets_at` shows `n/a`, and so do both windows when the file's label and the current one are both
known and differ. Each window carries its thresholds from `meterThresholds`, `(hold 50)` or
`(hold 50 stop 90)`, also when it reads `n/a`, so a line reads
`Opus 5.5 | ctx 25% | jdorje | 5h 24% (hold 50) ->12:20 | 7d 72% (hold 50) ->Mon 06:00 HOLD`; a missing or malformed `policy.json`
falls back to the base values, as the gate does (ADR 0006), and only a `policy.js` that cannot be required drops
the parentheses and the flag. At HOLD the flag reads ` HOLD lifted` when the gate's grant rules would let a launch
through, judged on the readings the line shows; the status line does not judge staleness, the gate does, so on stale
data under `whenStale` `deny` the line can read ` HOLD lifted` while the gate denies (`usage.overrideEnv` set to `1`, or the grants that apply to the `session_id` and
`workspace.current_dir` (else `cwd`) on stdin lift every tripped meter; the grant logic is `policy.js`'s,
shared with the gate); a partial lift stays ` HOLD`, ` STOP` is never lifted, and a failure reading the
grants means not lifted.

Gate. Each window is judged against its own thresholds (`usage.fiveHour`, `usage.sevenDay`, else the shared `usage.hold` and `usage.deny`) and the worse verdict wins; a window past its `resets_at` has reset and does not
count. The gate reads the file of the `session_id` in the PreToolUse payload. No usable `session_id`,
no file for this session, no `updatedAt`, a file whose label and the current `readAccount()` label are
both known and differ (the gate never judges another account's numbers), or data older than
`usage.staleSeconds` is stale: with `whenStale`
`allow` the gate allows with a note on stderr naming which (for an account mismatch, both emails);
with `deny` it denies with `Usage gate: <what>, and
the policy denies delegation without fresh data. Check usage with /usage in an interactive session;
the status line refreshes the data while a session is open.` The hook never runs `claude -p`.

The deny texts below begin `Usage gate (<email>):` instead of `Usage gate:` when the session's file
carries an email. At or above a deny line, where one is set: `Usage gate: 5h X%, 7d Y%. <meter> at or above <deny>%: <denyAdvice>`.
`usage.deny` `null`, the base, sets none, so a meter at 100% is a HOLD like any other. At or above
`usage.hold`: `Usage gate: 5h X%, 7d Y%. <meter> at or above <hold>%: <holdAdvice>[ The override in force
lifts only <meters>.][ An override exists for session <id>, not this one.| An override exists for
project <path>, not this one.| Expired override files: <name>[, <name>]*.| An override file
exists but is malformed (<name>; see ADR 0006, The HOLD override).]* Lifting this early is the
user's decision (docs/design/decisions/0006-hook-policy-file.md).` Grants are every `*.json` in
`~/.claude/usage-gate-overrides/` plus the legacy `~/.claude/usage-gate-override.json`, matched
against `session_id` and `cwd` from the PreToolUse payload on stdin by the rules in ADR 0006, The
HOLD override. The first bracket appears when the grants that apply lift some meters but not every
one that tripped; the starred group holds one clause per malformed or out-of-scope grant file, in
file-name order with the legacy file last, each file classified once as malformed, else expired,
else out of scope, `<id>` and `<path>` as written in the grant and `<name>` the file's basename;
every expired file shares one `Expired override files:` clause, its names in the same order, placed
where the first expired file falls. A lift notes each grant used on stderr with its `reason`, or
`no reason given` when `reason` is not a non-empty string.
There is no HOLD deny when the grants that apply together lift every meter that tripped, or
`usage.overrideEnv` names a variable set to exactly `1` (ADR 0006). A crash exits 1, which does not
block: the gate fails open.

Pool (ADR 0011). With `usage.pool` set, and only on the HOLD deny path (never on an allow, a lift or
a deny line), the gate runs `command` once without a shell, stdin empty, killed after
`timeoutSeconds` (an integer from 1 to 60), and appends one of: ` Report to the owner: account <email> (slot <n>) had room <m> min ago: 5h a%, 7d b%[U]. The next session is the owner's to start there (cswap switch <n>, or cswap run <n>).`
(`slot <n>` alone in place of `account <email> (slot <n>)` when the email does not match `^\S+@\S+$`);
` No other account has room (pool readings up to <m> min old|no pool readings)[U]; an override grant is still the lift (ADR 0006).`;
` No pool reading (<reason>).`, reasons such as `<program> not found` (a `.cmd` shim too: no shell), `no answer within <t> s`,
`<program> exited <code>`, `killed by <signal>`, `output is not the list --json shape`, a spawn error (`spawnSync ... ENOBUFS`).
`[U]` is ` (this session's account not excluded)`, added when the session's email (login, else file) is
unknown. A row's reading is `usage` with `usageAgeSeconds` when `usageStatus` is `ok`, else
`lastGoodUsage` with `lastGoodAgeSeconds` when that is at most 3600, else none. A row has room when
it is not `disabled`, has a `number`, an email and a reading with both `pct`, is not the session's
account (email equal case-insensitively, and `organizationUuid` equal when both are known), and
each `pct` is below that meter's hold; the largest smallest margin wins, the first on a tie. `<m>` is
the reading's age in whole minutes, rounded; the no-room age is the oldest reading among the others.

Every U, UX and UO case runs as one session with its file; UG cases write their payload session's.
The ADR 0011 additions to U, S and UP follow its Harness list, plus a `session_id` that is not a
plain name, a label without an email or organization, and the pool key's validation; the pool
command is a stub in the scratch dir, never one on PATH. Harness cases U (thresholds and the legacy
override file, with and without a deny line), UO (the
override per meter, no deny line on one meter or both, `usage.overrideEnv`), UG (the grant
directory: a session grant for this session and for another; a project grant with `cwd` at the
path, under it, and beside it with the path as a prefix, with either separator and, on Windows,
differing case; a user grant; the legacy file beside a directory grant; both scope keys; a relative
`project`; two files lifting one meter each; the clause order across a malformed, an expired and an
out-of-scope file; several expired files in one clause; a `reason` that is not a string; stdin
without `session_id` and unparseable, which take the stale path, and without `cwd`), UX (staleness both
ways, a reset window, the example policies, per-meter thresholds, a malformed policy including a
string `deny`, a pre-merge cache file), UP (the pool, with per-meter holds and no spawn on an allow,
a lift or a deny line), and S (the status line's merge, the untouched file without
`rate_limits`, malformed stdin, the directory line from `current_dir`, from `cwd` alone, and absent, policy flags, no STOP without a deny line, the thresholds beside each meter, `HOLD lifted` for a grant for this session and `HOLD` for another session's, a partial lift or STOP, a failed rename).
