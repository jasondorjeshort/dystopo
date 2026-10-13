#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// Status line for Claude Code. Reads the JSON Claude pipes on stdin, prints one line, and keeps
// ~/.claude/usage-state.json current for the usage-gate hook (.claude/hooks/usage-gate.js).
// Thresholds for the HOLD and STOP flags come from .claude/hooks/policy.json (usage block).
// Spec: docs/design/decisions/0002-hook-specs.md section 3.
//
// Fields: https://code.claude.com/docs/en/statusline. rate_limits appears only on claude.ai
// plans and only after the first API response of a session, and each window (five_hour,
// seven_day) may be absent on its own, dropped once its resets_at passes. So the cache is merged
// per window: a window present on stdin replaces the cached one, an absent window keeps the cached
// one, and nothing is written when neither is present. A cached reading is never replaced by null.
// File: { five_hour: { used_percentage, resets_at }, seven_day: {...}, updatedAt } (epoch seconds).
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');

const STATE_FILE = path.join(os.homedir(), '.claude', 'usage-state.json');
const WINDOWS = ['five_hour', 'seven_day'];

function readState() {
  try { return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')) || {}; } catch (_) { return {}; }
}

// Merges the windows present in rl over the cached state; writes only when one was present.
// The write goes to a temporary file and is renamed, so the gate never reads half a file.
function update(rl) {
  const state = readState();
  const out = {};
  for (const w of WINDOWS) if (state[w]) out[w] = state[w];
  let fresh = false;
  for (const w of WINDOWS) {
    const v = rl && rl[w];
    if (v && typeof v.used_percentage === 'number') {
      out[w] = { used_percentage: v.used_percentage };
      if (typeof v.resets_at === 'number') out[w].resets_at = v.resets_at;
      fresh = true;
    }
  }
  if (!fresh) return state;
  out.updatedAt = Math.floor(Date.now() / 1000);
  const tmp = `${STATE_FILE}.${process.pid}.tmp`;
  try {
    fs.writeFileSync(tmp, JSON.stringify(out));
    fs.renameSync(tmp, STATE_FILE);
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
  let usageVerdict = null;
  try { ({ usageVerdict } = require('./hooks/policy')); usage = require('./hooks/policy').load().policy.usage; } catch (_) { /* no flags */ }

  const state = update(d.rate_limits);
  const now = Math.floor(Date.now() / 1000);
  const live = (w) => (w && typeof w.used_percentage === 'number' && !(typeof w.resets_at === 'number' && w.resets_at <= now) ? w : null);
  const fiveH = live(state.five_hour);
  const sevenD = live(state.seven_day);
  const pct = (v) => (typeof v === 'number' ? `${Math.round(v)}%` : 'n/a');
  // Same judgement as the gate: each meter against its own thresholds, the worse one wins.
  const level = usage ? usageVerdict(usage, { '5h': fiveH ? fiveH.used_percentage : null, '7d': sevenD ? sevenD.used_percentage : null }).level : 0;
  const flag = level === 2 ? ' STOP' : level === 1 ? ' HOLD' : '';

  // resets_at is epoch seconds. Show the clock time if the reset is within 24h, else the weekday.
  const resetAt = (w) => {
    if (!w || typeof w.resets_at !== 'number') return '';
    const t = new Date(w.resets_at * 1000);
    const hm = `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`;
    if ((t - Date.now()) / 3600000 < 24) return ` ->${hm}`;
    return ` ->${['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][t.getDay()]} ${hm}`;
  };

  const parts = [
    (d.model && d.model.display_name) || '?',
    `ctx ${pct(d.context_window && d.context_window.used_percentage)}`,
    `5h ${pct(fiveH && fiveH.used_percentage)}${resetAt(fiveH)}`,
    `7d ${pct(sevenD && sevenD.used_percentage)}${resetAt(sevenD)}${flag}`,
  ];
  // cost.total_cost_usd (API list-price estimate) is deliberately not shown: on a subscription the
  // meters and their reset times are what matter. Run /usage for the figure.
  process.stdout.write(parts.join(' | '));
});
