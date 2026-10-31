#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// Status line for Claude Code. Reads the JSON Claude pipes on stdin, prints the working directory (when known)
// and the session name (when set), then the meters line, and keeps this session's usage file,
// ~/.claude/usage-state/<session_id>.json, current for the usage-gate hook (.claude/hooks/usage-gate.js).
// Thresholds for the HOLD and STOP flags come from .claude/hooks/policy.json (usage block) and are
// shown per meter, "(hold 50)" or "(hold 50 stop 90)"; STOP shows only on a meter with a deny line,
// which the base does not set. "HOLD lifted" replaces HOLD when an override lifts it for this session.
// The bare email local part names the logged-in account (policy.readAccount) when it is known.
// Spec: docs/hatchery/decisions/0002-hook-specs-usage.md (section 3); the per-session file: ADR 0011.
//
// Fields: https://code.claude.com/docs/en/statusline. rate_limits appears only on claude.ai
// plans and only after the first API response of a session, and each window (five_hour,
// seven_day) may be absent on its own, dropped once its resets_at passes. So the cache is merged
// per window: a window present on stdin replaces the cached one, an absent window keeps the cached
// one, and nothing is written when neither is present. A cached reading is never replaced by null.
// The account is a label, never the key. When the cached and current labels both are known and
// differ, the payload may still repeat the previous account's rate_limits (after cswap switch,
// until the next response), so the change is refused while cost.total_api_duration_ms equals the
// file's apiMs: nothing is written and the meters read n/a. Any other value (greater: a response
// since; smaller: a resumed process counting afresh), no cost, or no apiMs accepts it, and every
// cached window is dropped (ADR 0011, The cache).
// File: { account: { uuid, organizationUuid, email } | null, five_hour: { used_percentage, resets_at },
// seven_day: {...}, apiMs, updatedAt } (epoch seconds). No session_id: nothing is read or written.
// The old single file ~/.claude/usage-state.json is neither read, written nor deleted.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');

const STATE_DIR = path.join(os.homedir(), '.claude', 'usage-state');
const WINDOWS = ['five_hour', 'seven_day'];
const PRUNE_SECONDS = 86400;
// A session id becomes a file name, so only plain ids are used; anything else counts as absent.
const SESSION_ID = /^[A-Za-z0-9_-]+$/;

function readState(file) {
  try {
    const j = JSON.parse(fs.readFileSync(file, 'utf8'));
    return j && typeof j === 'object' && !Array.isArray(j) ? j : {};
  } catch (_) { return {}; }
}

// Files in the directory older than a day, other than this session's, are deleted. Run when a
// session's file is created.
function prune(keep) {
  try {
    const cutoff = Date.now() - PRUNE_SECONDS * 1000;
    for (const name of fs.readdirSync(STATE_DIR)) {
      const f = path.join(STATE_DIR, name);
      if (f === keep || !(name.endsWith('.json') || name.endsWith('.tmp'))) continue;
      try {
        const st = fs.statSync(f);
        if (st.isFile() && st.mtimeMs < cutoff) fs.rmSync(f, { force: true });
      } catch (_) { /* vanished: nothing to do */ }
    }
  } catch (_) { /* no directory */ }
}

const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);

// Merges the windows present in the payload d over the cached state and writes it when one was
// present. current is the logged-in account's label or null; differ(a, b) is policy.accountsDiffer,
// or a function returning false when policy.js did not load. Rule order (ADR 0011): no window on
// stdin, nothing is written; labels equal or either unknown, the merge, the current label written
// when known, else the cached one kept; labels known and different, nothing is written when the
// payload's cost.total_api_duration_ms equals the file's apiMs, and otherwise the change is
// accepted (every cached window dropped, the payload's taken under the new label). apiMs is the
// payload's number, else the cached one.
// The write goes to a temporary file and is renamed, so the gate never reads half a file.
function update(file, d, current, differ) {
  const state = file ? readState(file) : {};
  const rl = d.rate_limits;
  const present = WINDOWS.filter((w) => rl && rl[w] && typeof rl[w].used_percentage === 'number');
  if (!present.length) return state;
  const apiMs = num(d.cost && d.cost.total_api_duration_ms);
  const changed = differ(state.account, current);
  if (changed && apiMs !== null && num(state.apiMs) !== null && apiMs === state.apiMs) return state;
  const out = { account: current || (state.account && typeof state.account === 'object' ? state.account : null) };
  for (const w of WINDOWS) {
    const v = rl[w];
    if (present.includes(w)) {
      out[w] = { used_percentage: v.used_percentage };
      if (typeof v.resets_at === 'number') out[w].resets_at = v.resets_at;
    } else if (!changed && state[w]) out[w] = state[w];
  }
  out.apiMs = apiMs !== null ? apiMs : num(state.apiMs);
  out.updatedAt = Math.floor(Date.now() / 1000);
  if (!file) return out;
  const created = !fs.existsSync(file);
  const tmp = `${file}.${process.pid}.tmp`;
  try {
    fs.mkdirSync(STATE_DIR, { recursive: true });
    fs.writeFileSync(tmp, JSON.stringify(out));
    fs.renameSync(tmp, file);
    if (created) prune(file);
  } catch (_) {
    // The status line must never fail; do not leave a temporary file behind either.
    try { fs.rmSync(tmp, { force: true }); } catch (__) { /* nothing more to do */ }
  }
  return out;
}

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (c) => (raw += c));
process.stdin.on('end', () => {
  let d = {};
  try { d = JSON.parse(raw) || {}; } catch (_) { /* print what we can */ }
  let usage = null;
  let policy = null;
  try { policy = require('./hooks/policy'); usage = policy.load().policy.usage; } catch (_) { usage = null; /* no flags */ }

  const sid = typeof d.session_id === 'string' && SESSION_ID.test(d.session_id) ? d.session_id : null;
  const file = sid ? path.join(STATE_DIR, `${sid}.json`) : null;
  const current = policy && typeof policy.readAccount === 'function' ? policy.readAccount() : null;
  const differ = (a, b) => { try { return !!policy && policy.accountsDiffer(a, b); } catch (_) { return false; } };
  // Without a session id there is no file: the meters show this payload's windows only.
  const state = update(file, d, current, differ);
  // The file's numbers belong to another account than the one logged in: show n/a, as the gate
  // treats them (stale).
  const otherAccount = differ(state.account, current);
  const now = Math.floor(Date.now() / 1000);
  const live = (w) => (!otherAccount && w && typeof w.used_percentage === 'number' && !(typeof w.resets_at === 'number' && w.resets_at <= now) ? w : null);
  const fiveH = live(state.five_hour);
  const sevenD = live(state.seven_day);
  const pct = (v) => (typeof v === 'number' ? `${Math.round(v)}%` : 'n/a');
  // Same judgement as the gate: each meter against its own thresholds, the worse one wins.
  const verdict = usage ? policy.usageVerdict(usage, { '5h': fiveH ? fiveH.used_percentage : null, '7d': sevenD ? sevenD.used_percentage : null }) : { level: 0, hits: [] };
  // "HOLD lifted" exactly when the gate would let a launch through (policy.holdLifted); any error
  // reading the grants just means not lifted.
  let lifted = false;
  if (verdict.level === 1) {
    const str = (v) => (typeof v === 'string' && v !== '' ? v : null);
    const ctx = { sessionId: str(d.session_id), cwd: str((d.workspace && d.workspace.current_dir) || d.cwd) };
    try { lifted = policy.holdLifted(usage, verdict, ctx, now, process.env); } catch (_) { lifted = false; }
  }
  const flag = verdict.level === 2 ? ' STOP' : verdict.level === 1 ? (lifted ? ' HOLD lifted' : ' HOLD') : '';
  // Each meter's thresholds, "(hold 50)" or "(hold 50 stop 90)"; none when the policy did not load.
  const limits = (label) => {
    if (!usage) return '';
    const t = policy.meterThresholds(usage).find(([l]) => l === label)[1];
    return ` (hold ${t.hold}${t.deny !== null ? ` stop ${t.deny}` : ''})`;
  };

  // resets_at is epoch seconds. Show the clock time if the reset is within 24h, else the weekday.
  const resetAt = (w) => {
    if (!w || typeof w.resets_at !== 'number') return '';
    const t = new Date(w.resets_at * 1000);
    const hm = `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`;
    if ((t - Date.now()) / 3600000 < 24) return ` ->${hm}`;
    return ` ->${['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][t.getDay()]} ${hm}`;
  };

  // The account segment: the email local part on its own, from the logged-in label, else the
  // file's; omitted when neither is known.
  const label = current || (state.account && typeof state.account === 'object' ? state.account : null);
  const email = label && typeof label.email === 'string' ? label.email : '';
  const local = email.split('@')[0];

  const parts = [
    (d.model && d.model.display_name) || '?',
    `ctx ${pct(d.context_window && d.context_window.used_percentage)}`,
    ...(local ? [local] : []),
    `5h ${pct(fiveH && fiveH.used_percentage)}${limits('5h')}${resetAt(fiveH)}`,
    `7d ${pct(sevenD && sevenD.used_percentage)}${limits('7d')}${resetAt(sevenD)}${flag}`,
  ];
  // cost.total_cost_usd (API list-price estimate) is deliberately not shown: on a subscription the
  // meters and their reset times are what matter. Run /usage for the figure.
  // Line 1 is the working directory as received, so a wrong-checkout mistake shows; omitted (no blank
  // line) when the payload carries neither a directory (workspace.current_dir, else the older cwd)
  // nor a session name.
  const dir = [d.workspace && d.workspace.current_dir, d.cwd].find((v) => typeof v === 'string' && v !== '');
  // The session name (session_name, absent when the session has none) follows the directory on line 1.
  // Control characters become a space, so a newline or escape cannot break the two-line layout.
  const name = typeof d.session_name === 'string' ? d.session_name.replace(/[\x00-\x1f\x7f]+/g, ' ').trim() : '';
  const first = [dir, name].filter(Boolean).join(' | ');
  process.stdout.write((first ? first + '\n' : '') + parts.join(' | '));
});
