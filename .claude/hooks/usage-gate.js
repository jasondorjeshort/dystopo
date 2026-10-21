#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// PreToolUse hook for the Agent and Workflow tools, the only calls it sees.
// Denies launching a subagent or workflow when plan usage (the higher of the 5-hour and 7-day
// windows) is at or above usage.hold in .claude/hooks/policy.json, unless an override grant that
// applies to this call lifts it, and at or above usage.deny, where a deny line is set, without
// exception (null, the base, means none: HOLD is then the only line). Other tools are never gated.
// Usage comes from this session's file, ~/.claude/usage-state/<session_id>.json, which
// .claude/statusline.js writes; session_id is the one in the PreToolUse payload.
// Spec: docs/hatchery/decisions/0002-hook-specs-usage.md (section 3); policy: ADR 0006; the per-session
// file and the account label: ADR 0011.
//
// Fails open: a crash (exit 1, no onFailure in settings.json) lets the launch through, because a
// broken gate must not stop all delegation. Stale or missing data allows with a note on stderr,
// or denies when the policy sets usage.whenStale to "deny". No session_id, no file for this
// session, and a file labelled with another account than the one logged in are all missing data:
// the gate never judges another account's numbers. A malformed policy falls back to the base
// defaults and says so on stderr. With usage.pool set, a HOLD deny (and only that) runs the pool
// command once and names another account with room, or says there is none (ADR 0011).
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const { load, usageVerdict, meterThresholds, readGrants, judgeGrants, holdCovered, readAccount, accountLabel, accountsDiffer } = require('./policy');

const STATE_DIR = path.join(os.homedir(), '.claude', 'usage-state');
// The status line uses the same rule: a session id becomes a file name, so only plain ids count.
const SESSION_ID = /^[A-Za-z0-9_-]+$/;
// Override grants (read and judged in policy.js, shared with the status line) lift HOLD, never the
// deny line, until "until" on their meters, for one session, one project or the user. Format and
// matching: ADR 0006, The HOLD override.
const POLICY_DOC = 'docs/hatchery/decisions/0006-hook-policy-file.md';

function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch (_) { return null; }
}
function deny(reason) {
  process.stdout.write(JSON.stringify({
    hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: reason },
  }));
  process.exit(0);
}
const note = (msg) => process.stderr.write(`usage-gate: ${msg}\n`);

// session_id and cwd from the PreToolUse payload; unparseable stdin is an empty payload.
function readPayload(raw) {
  let p = null;
  try { p = JSON.parse(raw); } catch (_) { /* empty payload */ }
  if (!p || typeof p !== 'object') p = {};
  const str = (v) => (typeof v === 'string' && v !== '' ? v : null);
  return { sessionId: str(p.session_id), cwd: str(p.cwd) };
}

const scopeText = (s) => (s.kind === 'user' ? 'user' : `${s.kind} ${s.value}`);

// One clause per malformed or out-of-scope grant; every expired grant shares one clause, placed where
// the first one falls, so leftover files do not lengthen each deny. The format lives in the ADR.
function clause(name, c) {
  if (c.status === 'malformed') return `An override file exists but is malformed (${name}; see ADR 0006, The HOLD override).`;
  return `An override exists for ${c.scope.kind} ${c.scope.value}, not this one.`;
}

// The pool advice appended to a HOLD deny (ADR 0011, The gate): runs usage.pool.command once,
// without a shell, stdin closed, killed after timeoutSeconds, and reads its stdout as the
// `cswap list --json` contract (accounts[]: number, email, organizationUuid, disabled,
// usageStatus, usage and usageAgeSeconds, lastGoodUsage and lastGoodAgeSeconds; unknown fields
// ignored). A row's reading is usage when usageStatus is "ok", else lastGoodUsage when
// lastGoodAgeSeconds is at most an hour, else none; either needs its age. Room: not disabled, a
// number, an email, a reading, not the session's account, each meter below its own hold; the row
// with the largest smallest margin wins, the first on a tie. The session's account is every row
// whose email equals session.email (case-insensitively) and whose organizationUuid equals
// session.org when both are known; session.email null means unknown, and nothing is excluded.
// Returns the text to append, starting with a space. Called only on the HOLD deny path.
const LAST_GOOD_SECONDS = 3600;
function poolAdvice(u, session) {
  const { command, timeoutSeconds } = u.pool;
  const r = spawnSync(command[0], command.slice(1), {
    input: '', encoding: 'utf8', timeout: Math.round(timeoutSeconds * 1000), windowsHide: true, shell: false,
    maxBuffer: 4 * 1024 * 1024,
  });
  let reason = null;
  if (r.error && r.error.code === 'ENOENT') reason = `${command[0]} not found`;
  else if (r.error && r.error.code === 'ETIMEDOUT') reason = `no answer within ${timeoutSeconds} s`;
  else if (r.error) reason = r.error.message;
  else if (r.status !== 0) reason = r.status === null ? `${command[0]} killed by ${r.signal}` : `${command[0]} exited ${r.status}`;
  let accounts = null;
  if (!reason) {
    try {
      const j = JSON.parse(r.stdout);
      if (j && Array.isArray(j.accounts)) accounts = j.accounts.filter((a) => a && typeof a === 'object');
    } catch (_) { /* reported below */ }
    if (!accounts) reason = 'output is not the list --json shape';
  }
  if (reason) return ` No pool reading (${reason}).`;

  const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
  const str = (v) => (typeof v === 'string' && v !== '' ? v : null);
  const mine = str(session.email) && session.email.toLowerCase();
  const myOrg = str(session.org);
  const isMine = (a) => mine !== null && typeof a.email === 'string' && a.email.toLowerCase() === mine &&
    !(myOrg !== null && str(a.organizationUuid) !== null && a.organizationUuid !== myOrg);
  // { five, seven, age } or null.
  const reading = (a) => {
    let usage = null;
    let age = null;
    if (a.usageStatus === 'ok') { usage = a.usage; age = num(a.usageAgeSeconds); }
    else if (num(a.lastGoodAgeSeconds) !== null && a.lastGoodAgeSeconds <= LAST_GOOD_SECONDS) { usage = a.lastGoodUsage; age = a.lastGoodAgeSeconds; }
    const pct = (w) => num(usage && typeof usage === 'object' && usage[w] && usage[w].pct);
    const five = pct('fiveHour');
    const seven = pct('sevenDay');
    return age === null || five === null || seven === null ? null : { five, seven, age };
  };
  const others = accounts.filter((a) => !isMine(a)).map((a) => ({ a, r: reading(a) }));
  const holds = Object.fromEntries(meterThresholds(u).map(([l, t]) => [l, t.hold]));
  const mins = (s) => Math.round(s / 60);
  const unknown = mine === null ? ' (this session\'s account not excluded)' : '';
  let best = null;
  for (const { a, r } of others) {
    if (!r || a.disabled === true || str(a.email) === null || num(a.number) === null || !(r.five < holds['5h'] && r.seven < holds['7d'])) continue;
    const margin = Math.min(holds['5h'] - r.five, holds['7d'] - r.seven);
    if (!best || margin > best.margin) best = { a, r, margin };
  }
  if (best) {
    const n = best.a.number;
    // The email reaches the deny text only when it looks like one; otherwise the slot alone.
    const who = /^\S+@\S+$/.test(best.a.email) ? `account ${best.a.email} (slot ${n})` : `slot ${n}`;
    return ` Report to the owner: ${who} had room ${mins(best.r.age)} min ago: 5h ${Math.round(best.r.five)}%, ` +
      `7d ${Math.round(best.r.seven)}%${unknown}. The next session is the owner's to start there (cswap switch ${n}, or cswap run ${n}).`;
  }
  const ages = others.filter((o) => o.r).map((o) => o.r.age);
  const readings = ages.length ? `pool readings up to ${mins(Math.max(...ages))} min old` : 'no pool readings';
  return ` No other account has room (${readings})${unknown}; an override grant is still the lift (ADR 0006).`;
}

function main(raw) {
  const loaded = load();
  if (loaded.error) note(`${loaded.error}; using the base defaults.`);
  const u = loaded.policy.usage;
  const now = Math.floor(Date.now() / 1000);
  const ctx = readPayload(raw);

  const sid = ctx.sessionId && SESSION_ID.test(ctx.sessionId) ? ctx.sessionId : null;
  let state = sid ? readJson(path.join(STATE_DIR, `${sid}.json`)) : null;
  if (!state || typeof state !== 'object' || Array.isArray(state)) state = null;
  const fileLabel = state ? accountLabel(state.account) : null;
  const current = readAccount();
  const age = state && typeof state.updatedAt === 'number' ? now - state.updatedAt : null;
  const otherAccount = accountsDiffer(fileLabel, current);
  if (otherAccount || age === null || age > u.staleSeconds) {
    const who = (l) => l.email || l.uuid;
    let what;
    if (!sid) what = 'no usage data (no usable session_id in the hook input)';
    else if (otherAccount) what = `no usage data for this account (this session's file is for ${who(fileLabel)}, logged in as ${who(current)})`;
    else if (age === null) what = 'no usage data (the status line has not written any for this session)';
    else what = `usage data is ${Math.round(age / 60)} min old`;
    if (u.whenStale === 'deny') {
      deny(`Usage gate: ${what}, and the policy denies delegation without fresh data. Check usage with /usage in an interactive session; the status line refreshes the data while a session is open.`);
    }
    note(`${what}; allowing. Run /usage to confirm.`);
    return;
  }

  // A window whose reset time has passed has reset; its last reading no longer counts.
  const pct = (w) => (w && typeof w.used_percentage === 'number' && !(typeof w.resets_at === 'number' && w.resets_at <= now)
    ? w.used_percentage : null);
  const fiveH = pct(state.five_hour);
  const sevenD = pct(state.seven_day);
  const fmt = (v) => (v === null ? 'n/a' : `${Math.round(v)}%`);
  const detail = `5h ${fmt(fiveH)}, 7d ${fmt(sevenD)}`;
  // The deny names the account the numbers belong to when the file carries its email.
  const head = fileLabel && fileLabel.email ? `Usage gate (${fileLabel.email}):` : 'Usage gate:';
  // Each meter is judged by its own thresholds (usage.fiveHour / usage.sevenDay, else the shared
  // hold/deny) and the worse verdict wins; the reason names the meter that tripped.
  const v = usageVerdict(u, { '5h': fiveH, '7d': sevenD });
  const tripped = v.hits.map((h) => `${h.label} at or above ${h.threshold}%`).join(', ');

  if (v.level === 2) deny(`${head} ${detail}. ${tripped}: ${u.denyAdvice}`);
  if (v.level === 1) {
    if (u.overrideEnv && process.env[u.overrideEnv] === '1') {
      note(`HOLD (${detail}) lifted for this session by ${u.overrideEnv}=1.`);
      return;
    }
    const { grants, applying, lifted } = judgeGrants(readGrants(), now, ctx);
    const labels = v.hits.map((h) => h.label);
    if (holdCovered(lifted, v)) {
      // The reason of every grant used, that is every applying grant that lifts a tripped meter.
      for (const g of applying.filter((a) => a.c.meters.some((m) => labels.includes(m)))) {
        note(`HOLD (${detail}) lifted on ${g.c.meters.join(' and ')} by override ${g.name} (${scopeText(g.c.scope)}) until ${new Date(g.grant.until * 1000).toLocaleString()}: ${typeof g.grant.reason === 'string' && g.grant.reason ? g.grant.reason : 'no reason given'}`);
      }
      return;
    }
    const partial = lifted.length ? ` The override in force lifts only ${lifted.join(' and ')}.` : '';
    const expired = grants.filter((g) => g.c.status === 'expired');
    const unused = grants.filter((g) => g.c.status !== 'applies').map((g) => {
      if (g.c.status !== 'expired') return ` ${clause(g.name, g.c)}`;
      return g === expired[0] ? ` Expired override files: ${expired.map((e) => e.name).join(', ')}.` : '';
    }).join('');
    // The pool is asked only here, about to deny at HOLD: an allowed launch spawns nothing. A fault
    // in it must not turn the deny into a crash, which would fail open.
    let pool = '';
    if (u.pool) {
      // The session's account: the login's label, else the file's (both known means they agree here).
      const pick = (k) => (current && current[k]) || (fileLabel && fileLabel[k]) || null;
      try { pool = poolAdvice(u, { email: pick('email'), org: pick('organizationUuid') }); } catch (e) { pool = ` No pool reading (${e.message}).`; }
    }
    deny(`${head} ${detail}. ${tripped}: ${u.holdAdvice}${partial}${unused} Lifting this early is the user's decision (${POLICY_DOC}).${pool}`);
  }
}

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (c) => (raw += c));
process.stdin.on('end', () => {
  main(raw);
  process.exit(0);
});
