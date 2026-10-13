#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// PreToolUse hook for the Agent and Workflow tools, the only calls it sees.
// Denies launching a subagent or workflow when plan usage (the higher of the 5-hour and 7-day
// windows) is at or above usage.hold in .claude/hooks/policy.json, unless the owner's override
// lifts it, and at or above usage.deny without exception. Other tools are never gated here.
// Usage comes from ~/.claude/usage-state.json, which .claude/statusline.js writes.
// Spec: docs/design/decisions/0002-hook-specs.md section 3; policy: ADR 0006.
// Dystopo: the Hatchery base's script (https://github.com/jasondorjeshort/hatchery, the ADRs above
// are its own) with one line changed, POLICY_DOC, since its ADR 0006 is not copied here; this
// project's numbers are in policy.json and the override is described in ADR 0021 (Usage gate).
//
// Fails open: a crash (exit 1, no onFailure in settings.json) lets the launch through, because a
// broken gate must not stop all delegation. Stale or missing data allows with a note on stderr,
// or denies when the policy sets usage.whenStale to "deny". A malformed policy falls back to the
// base defaults and says so on stderr.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { load, usageVerdict, overrideMeters } = require('./policy');

const STATE_FILE = path.join(os.homedir(), '.claude', 'usage-state.json');
// Lifts HOLD (not the deny line) until `until`, epoch seconds, on the meters in `meters` ("5h",
// "7d"; both when absent); written only on the user's word. Format: ADR 0006, The HOLD override.
const OVERRIDE_FILE = path.join(os.homedir(), '.claude', 'usage-gate-override.json');
const POLICY_DOC = 'docs/design/decisions/0021-git-guard-exceptions.md, Usage gate';

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

function main() {
  const loaded = load();
  if (loaded.error) note(`${loaded.error}; using the base defaults.`);
  const u = loaded.policy.usage;
  const now = Math.floor(Date.now() / 1000);

  const state = readJson(STATE_FILE);
  const age = state && typeof state.updatedAt === 'number' ? now - state.updatedAt : null;
  if (age === null || age > u.staleSeconds) {
    const what = age === null ? 'no usage data (the status line has not written any)' : `usage data is ${Math.round(age / 60)} min old`;
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
  // Each meter is judged by its own thresholds (usage.fiveHour / usage.sevenDay, else the shared
  // hold/deny) and the worse verdict wins; the reason names the meter that tripped.
  const v = usageVerdict(u, { '5h': fiveH, '7d': sevenD });
  const tripped = v.hits.map((h) => `${h.label} at or above ${h.threshold}%`).join(', ');

  if (v.level === 2) deny(`Usage gate: ${detail}. ${tripped}: ${u.denyAdvice}`);
  if (v.level === 1) {
    if (u.overrideEnv && process.env[u.overrideEnv] === '1') {
      note(`HOLD (${detail}) lifted for this session by ${u.overrideEnv}=1.`);
      return;
    }
    const o = readJson(OVERRIDE_FILE);
    const lifted = overrideMeters(o, now);
    if (lifted.length && v.hits.every((h) => lifted.includes(h.label))) {
      note(`HOLD (${detail}) lifted on ${lifted.join(' and ')} by override until ${new Date(o.until * 1000).toLocaleString()}: ${o.reason || 'no reason given'}`);
      return;
    }
    const partial = lifted.length ? ` The override in force lifts only ${lifted.join(' and ')}.` : '';
    deny(`Usage gate: ${detail}. ${tripped}: ${u.holdAdvice}${partial} Lifting this early is the user's decision (${POLICY_DOC}).`);
  }
}

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (c) => (raw += c));
process.stdin.on('end', () => {
  main();
  process.exit(0);
});
