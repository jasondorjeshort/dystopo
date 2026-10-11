#!/usr/bin/env node
// PreToolUse hook for the Agent and Workflow tools (adapted from the Hatchery base, which keeps
// the canonical version at .claude/hooks/usage-gate.js there).
// Refuses to launch a new subagent when plan usage (max of 5-hour and 7-day) is at or above
// HOLD. Usage comes from ~/.claude/usage.json, which this machine's status line writes
// (fields: ts, five_hour.used_percentage, seven_day.used_percentage).
//
// Dystopo policy (CLAUDE.md > Coordinating a wave > Wave size): stop launching new implementers
// at 75%; verifiers and merges of work already in flight may finish. The hook cannot tell a
// verifier from an implementer, so above HOLD it denies and names the owner's rule: the
// coordinator relaunches a verifier with DYSTOPO_GATE_OVERRIDE=1 in the environment only for
// work already launched before the line was crossed.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');

const STATE_FILE = path.join(os.homedir(), '.claude', 'usage.json');
const HOLD = 75;
const STALE_SECONDS = 2 * 60 * 60;

function allow() { process.exit(0); }
function deny(reason) {
  process.stdout.write(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason: reason,
    },
  }));
  process.exit(0);
}

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (c) => (raw += c));
process.stdin.on('end', () => {
  if (process.env.DYSTOPO_GATE_OVERRIDE === '1') return allow();
  // The status line rewrites the file on every refresh, so a read can land mid-write: try twice.
  let state = null;
  for (let attempt = 0; attempt < 2 && !state; attempt++) {
    try { state = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); } catch (_) {
      const until = Date.now() + 150;
      while (Date.now() < until) { /* short wait before the second read */ }
    }
  }
  if (!state || typeof state.ts !== 'number') {
    process.stderr.write('usage-gate: no usage data (status line has not run); allowing.\n');
    return allow();
  }
  const age = Math.floor(Date.now() / 1000) - state.ts;
  if (age > STALE_SECONDS) {
    process.stderr.write(`usage-gate: usage data is ${Math.round(age / 60)} min old; allowing. Check /usage.\n`);
    return allow();
  }
  const f = state.five_hour && typeof state.five_hour.used_percentage === 'number' ? state.five_hour.used_percentage : 0;
  const s = state.seven_day && typeof state.seven_day.used_percentage === 'number' ? state.seven_day.used_percentage : 0;
  const worst = Math.max(f, s);
  if (worst >= HOLD) {
    return deny(`Usage gate: 5h ${Math.round(f)}%, 7d ${Math.round(s)}%. At or above ${HOLD}%: no new implementers (CLAUDE.md, Wave size). Finish work already in flight; a verifier for an already-launched task may be relaunched with DYSTOPO_GATE_OVERRIDE=1. Otherwise wait for the reset or the owner's word.`);
  }
  allow();
});
