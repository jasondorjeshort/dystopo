#!/usr/bin/env node
// Emulator lock, PreToolUse half (T190; matcher Bash|PowerShell). Two jobs on every shell command:
//
// 1. A tripwire, not a wall: a raw `emulator -avd <name>` or `emulator @<name>` (emulator or
//    emulator.exe, by any path, also through Start-Process or a cmd/powershell/bash runner) is
//    denied with a pointer at tools/android-emu.ps1, which takes a lock and honours the cap. The
//    enforcement is the console port and the lock the script takes; this only catches the obvious
//    bypass. `emulator -list-avds`, `-accel-check` and the like pass.
// 2. Claims: a command from a subagent (agent_id in the hook input; Claude Code 2.1.316 sends it to
//    PreToolUse and SubagentStop alike, dry run of 2026-10-31) that runs tools/android-emu.ps1 or
//    tools/sweep.ps1 records the AVD names it boots in <lockDir>/agents/<agent_id>.json, so
//    emulator-stop.js (SubagentStop) can stop exactly that agent's emulators when it ends.
//
// The lock files themselves are written by tools/android-emu.ps1: one JSON file per emulator,
// <lockDir>/emulator-<console port>.json with { serial, consolePort, avd, owner, pid (the qemu process
// owning the console port), emulatorPid (its emulator.exe parent), hostPort, checkout, bootedBy (the
// script's PID while booting), startedAt, state }. lockDir and the cap come from the `emulators` block
// of policy.json (policy.js; default ~/.dystopo/emulators, max 2), one folder for the whole machine
// so a lock binds across worktrees. docs/reference/android-emulator.md, "The emulator cap".
//
// Allow: exit 0, no stdout. Deny: exit 0 with permissionDecision "deny". Any internal error allows
// (the tripwire must never block unrelated work). Debug: node .claude/hooks/emulator-lock.js --locks
// prints the lock folder and each lock with its liveness.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

// The images an emulator PID may run; anything else is a dead lock (the PID was reused) and is never killed.
const EMULATOR_IMAGE = /^(qemu-system-[\w.-]+|emulator\d*)(\.exe)?$/i;
const BOOTER_IMAGE = /^(powershell|pwsh)(\.exe)?$/i;
const RUNNERS = /^(cmd|powershell|pwsh|bash|sh|start-process|start|call)(\.exe)?$/i;

function policyEmulators() {
  let max = 2;
  let lockDir = '~/.dystopo/emulators';
  try {
    const r = require('./policy.js').load();
    if (r.policy && r.policy.emulators) { max = r.policy.emulators.max; lockDir = r.policy.emulators.lockDir; }
  } catch (_) { /* defaults */ }
  return { max, lockDir: expandHome(lockDir) };
}

function expandHome(p) {
  if (p === '~' || p.startsWith('~/') || p.startsWith('~\\')) return path.join(os.homedir(), p.slice(1));
  return path.resolve(p);
}

// The image name of a running PID, or null when no such process runs.
function imageOf(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return null;
  if (process.platform === 'win32') {
    const r = spawnSync('tasklist', ['/FI', `PID eq ${pid}`, '/FO', 'CSV', '/NH'], { encoding: 'utf8', timeout: 15000, windowsHide: true });
    const m = /^"([^"]+)","(\d+)"/m.exec(r.stdout || '');
    return m && Number(m[2]) === pid ? m[1] : null;
  }
  const r = spawnSync('ps', ['-p', String(pid), '-o', 'comm='], { encoding: 'utf8', timeout: 15000 });
  const name = (r.stdout || '').trim();
  return name ? path.basename(name) : null;
}

const isEmulatorPid = (pid) => { const img = imageOf(pid); return !!img && EMULATOR_IMAGE.test(img); };

// Every lock file in dir, each with live (true/false) and why when dead. Same rule as android-emu.ps1:
// a lock with a pid is live while that PID runs an emulator image; a lock still booting (no pid yet) is
// live while the script that wrote it runs.
function readLocks(dir) {
  let names = [];
  try { names = fs.readdirSync(dir).filter((n) => /^emulator-\d+\.json$/.test(n)); } catch (_) { return []; }
  const out = [];
  for (const n of names) {
    const file = path.join(dir, n);
    let lock = null;
    try { lock = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^﻿/, '')); } catch (_) { /* torn or foreign */ }
    if (!lock || typeof lock !== 'object') { out.push({ file, lock: {}, live: false, why: 'unreadable' }); continue; }
    let live; let why = '';
    if (Number.isInteger(lock.pid) && lock.pid > 0) {
      const img = imageOf(lock.pid);
      live = !!img && EMULATOR_IMAGE.test(img);
      if (!live) why = img ? `pid ${lock.pid} now runs ${img}` : `pid ${lock.pid} gone`;
    } else {
      const img = imageOf(lock.bootedBy);
      live = !!img && BOOTER_IMAGE.test(img);
      if (!live) why = `booting, and its script (pid ${lock.bootedBy}) is gone`;
    }
    out.push({ file, lock, live, why });
  }
  return out;
}

// The words of each simple command in a command line, runner arguments re-read once.
function commandsOf(text, toolName) {
  const { parse } = require('./shell-parse.js');
  const dialect = toolName === 'PowerShell' ? 'powershell' : 'bash';
  const out = [];
  const visit = (src, dia, depth) => {
    let parsed;
    try { parsed = parse(src, dia); } catch (_) { return; }
    for (const c of parsed.commands) {
      const words = c.words.filter((w) => w !== '&');
      if (!words.length) continue;
      out.push(words);
      if (depth === 0 && RUNNERS.test(base(words[0]))) {
        const rest = words.slice(1).filter((w) => !/^[-/](c|k|s|d|command|noprofile|noninteractive|executionpolicy|bypass|filepath|argumentlist|wait|nonewwindow|windowstyle|hidden)$/i.test(w));
        const ps = /^(powershell|pwsh)/i.test(base(words[0]));
        visit(rest.join(' '), ps ? 'powershell' : 'bash', 1);
      }
    }
  };
  visit(text, dialect, 0);
  return out;
}
const base = (w) => String(w).replace(/^.*[\\/]/, '');

// The reason to deny a raw emulator boot, or null.
function rawBoot(words) {
  const isEmu = (w) => w !== undefined && /^emulator(\.exe)?$/i.test(base(w));
  let at = -1;
  if (isEmu(words[0])) at = 0;
  else if (/^(start-process|saps|start)$/i.test(words[0])) {
    const fp = words.findIndex((w) => /^-filepath$/i.test(w));
    if (fp >= 0 && isEmu(words[fp + 1])) at = fp + 1;
    else if (isEmu(words[1])) at = 1;
  }
  if (at < 0) return null;
  // Start-Process emulator -ArgumentList '-avd x' reaches here through the runner re-read, so a
  // following word that holds -avd or starts with @ is enough.
  const args = words.slice(at + 1);
  if (args.some((w) => /(^|\s)-{1,2}avd(\s|=|$)/i.test(w) || /^@[\w.-]+$/.test(w))) {
    return 'boot emulators through tools/android-emu.ps1 (it takes a lock under the machine-wide cap, emulators.max in ' +
      '.claude/hooks/policy.json, and the SubagentStop hook stops it when your agent ends): ' +
      'powershell -ExecutionPolicy Bypass -File tools\\android-emu.ps1 -Avd <avd> -EmuPort <port> -Port <host port> -Owner <task>. ' +
      'A raw `emulator -avd` or `emulator @name` is denied (T190; docs/reference/android-emulator.md).';
  }
  return null;
}

// Value after a PowerShell parameter (case-insensitive, -Name value or -Name:value).
function param(words, name) {
  for (let i = 0; i < words.length; i++) {
    const w = words[i];
    if (w.toLowerCase() === `-${name.toLowerCase()}` && i + 1 < words.length) return words[i + 1];
    const m = new RegExp(`^-${name}:(.+)$`, 'i').exec(w);
    if (m) return m[1];
  }
  return null;
}

// The AVDs a command line boots through the scripts: [{ avd, prefix }] where prefix means "this name
// and any name it starts" (the sweep boots <Avd>, <Avd>-b and <Avd>-m<i>).
function claimsFrom(text, toolName) {
  const claims = [];
  for (const words of commandsOf(text, toolName)) {
    for (let i = 0; i < words.length; i++) {
      const w = base(words[i]);
      const rest = words.slice(i + 1);
      // "tools" glued on: Bash reads an unquoted tools\x.ps1 as toolsx.ps1 (the line fails, but the transcript has it).
      if (/^(tools)?android-emu\.ps1$/i.test(w)) {
        const avd = param(rest, 'Avd') || 'dystopo35';
        if (/^[\w.-]+$/.test(avd)) claims.push({ avd, prefix: false });
      } else if (/^(tools)?sweep\.ps1$/i.test(w)) {
        const avd = param(rest, 'Avd') || 'dystopo35-sweep-';
        if (/^[\w.-]+$/.test(avd)) claims.push({ avd, prefix: true });
      }
    }
  }
  return claims;
}

const claimMatches = (claims, avd) => typeof avd === 'string' && claims.some((c) => (c.prefix ? avd.startsWith(c.avd) : avd === c.avd));

function addClaims(dir, agentId, claims) {
  const agents = path.join(dir, 'agents');
  fs.mkdirSync(agents, { recursive: true });
  const file = path.join(agents, `${agentId.replace(/[^\w.-]/g, '_')}.json`);
  let have = [];
  try { have = JSON.parse(fs.readFileSync(file, 'utf8')).claims || []; } catch (_) { /* first claim */ }
  for (const c of claims) if (!have.some((h) => h.avd === c.avd && h.prefix === c.prefix)) have.push(c);
  fs.writeFileSync(file, JSON.stringify({ agentId, claims: have, updatedAt: new Date().toISOString() }) + '\n');
  // Claims of agents that never ended (a crash, a killed session) go after a week.
  try {
    for (const n of fs.readdirSync(agents)) {
      const f = path.join(agents, n);
      if (Date.now() - fs.statSync(f).mtimeMs > 7 * 86400e3) fs.rmSync(f, { force: true });
    }
  } catch (_) { /* best effort */ }
}

function main(input) {
  const cmd = input && input.tool_input && input.tool_input.command;
  if (typeof cmd !== 'string' || cmd === '') return null;
  const tool = input.tool_name;
  for (const words of commandsOf(cmd, tool)) {
    const why = rawBoot(words);
    if (why) return why;
  }
  if (typeof input.agent_id === 'string' && input.agent_id !== '') {
    const claims = claimsFrom(cmd, tool);
    if (claims.length) {
      try { addClaims(policyEmulators().lockDir, input.agent_id, claims); } catch (_) { /* a claim never blocks */ }
    }
  }
  return null;
}

module.exports = { policyEmulators, expandHome, imageOf, isEmulatorPid, readLocks, claimsFrom, claimMatches, commandsOf, EMULATOR_IMAGE };

if (require.main === module) {
  if (process.argv[2] === '--locks') {
    const p = policyEmulators();
    process.stdout.write(`lockDir ${p.lockDir}, max ${p.max}\n`);
    for (const l of readLocks(p.lockDir)) process.stdout.write(`${l.live ? 'live' : 'dead'} ${path.basename(l.file)} ${JSON.stringify(l.lock)}${l.why ? ` (${l.why})` : ''}\n`);
    process.exit(0);
  }
  let s = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (d) => { s += d; });
  process.stdin.on('end', () => {
    let reason = null;
    try { reason = main(JSON.parse(s)); } catch (_) { reason = null; }
    if (reason) {
      process.stdout.write(JSON.stringify({ hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: reason } }));
    }
    process.exit(0);
  });
}
