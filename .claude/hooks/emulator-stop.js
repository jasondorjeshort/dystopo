#!/usr/bin/env node
// Emulator lock, SubagentStop half (T190): close on done. When a subagent ends, stop the emulators it
// booted through tools/android-emu.ps1 or tools/sweep.ps1 and remove their locks.
//
// Which agent: the hook input's agent_id (Claude Code 2.1.316 sends agent_id, agent_type and
// agent_transcript_path to SubagentStop; dry run of 2026-10-31). Which emulators: the AVDs
// emulator-lock.js recorded for that agent_id at PreToolUse (<lockDir>/agents/<agent_id>.json),
// together with the `-Avd <name>` of every android-emu.ps1 or sweep.ps1 command in the agent's own
// transcript (agent_transcript_path; without agent_id or that path, transcript_path). A lock in
// <lockDir> whose avd matches is stopped; any other lock is left alone.
//
// How: only by the PID in the lock, and only after checking that PID still runs an emulator image
// (qemu-system-*, emulator*): never by name or command line (CLAUDE.md; finding
// 2026-10-10-kill-by-command-line-substring). First `adb -s <serial> emu kill` when the console answers
// with the lock's AVD, then, if the PID still runs after 20 s, the PID is terminated; the lock's
// emulator.exe parent likewise. A dead lock (its PID gone or reused) is only removed. The AVD itself is
// kept (`android-emu.ps1 -Stop -Delete` removes it).
//
// Never blocks: exit 0 always; a line per stopped emulator goes to <lockDir>/stop.log and, when
// anything was stopped, to the user as systemMessage. Test seam: EMULATOR_STOP_ADB names the adb
// binary, or "none" to skip the console step (test-hooks.js sets it).
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const L = require('./emulator-lock.js');

function adbPath() {
  const seam = process.env.EMULATOR_STOP_ADB;
  if (seam === 'none') return null;
  if (seam) return seam;
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || path.join(os.homedir(), 'Android', 'sdk');
  const exe = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  return fs.existsSync(exe) ? exe : null;
}

function adb(args) {
  const exe = adbPath();
  if (!exe) return null;
  const r = spawnSync(exe, args, { encoding: 'utf8', timeout: 15000, windowsHide: true });
  return r.status === 0 ? (r.stdout || '') : null;
}

// Every claim for this ending agent: its PreToolUse claims and its transcript's script lines.
function claimsFor(input, lockDir) {
  const claims = [];
  const sources = [];
  const id = typeof input.agent_id === 'string' && input.agent_id !== '' ? input.agent_id : null;
  if (id) {
    const f = path.join(lockDir, 'agents', `${id.replace(/[^\w.-]/g, '_')}.json`);
    try {
      claims.push(...(JSON.parse(fs.readFileSync(f, 'utf8')).claims || []));
      sources.push('claims');
    } catch (_) { /* none recorded */ }
  }
  const transcript = typeof input.agent_transcript_path === 'string' && input.agent_transcript_path
    ? input.agent_transcript_path
    : (!id && typeof input.transcript_path === 'string' ? input.transcript_path : null);
  if (transcript) {
    let text = '';
    try { text = fs.readFileSync(transcript, 'utf8'); } catch (_) { /* absent */ }
    for (const line of text.split('\n')) {
      if (!/android-emu\.ps1|sweep\.ps1/i.test(line)) continue;
      let o;
      try { o = JSON.parse(line); } catch (_) { continue; }
      const content = o && o.message && Array.isArray(o.message.content) ? o.message.content : [];
      for (const c of content) {
        if (c && c.type === 'tool_use' && c.input && typeof c.input.command === 'string') {
          const found = L.claimsFrom(c.input.command, c.name);
          if (found.length) { claims.push(...found); if (!sources.includes('transcript')) sources.push('transcript'); }
        }
      }
    }
  }
  return { id, claims, sources };
}

function sleepMs(ms) { Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms); }

function terminate(pid) {
  if (!L.isEmulatorPid(pid)) return false;
  try { process.kill(pid, 'SIGKILL'); } catch (_) { return false; }
  for (let i = 0; i < 20 && L.imageOf(pid); i++) sleepMs(250);
  return !L.imageOf(pid);
}

// The PID listening on a local TCP port (Windows netstat), or null.
function listenerPid(port) {
  if (!Number.isInteger(port) || process.platform !== 'win32') return null;
  const r = spawnSync('netstat', ['-ano', '-p', 'TCP'], { encoding: 'utf8', timeout: 15000, windowsHide: true });
  for (const line of (r.stdout || '').split(/\r?\n/)) {
    const m = /^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$/.exec(line);
    if (m && Number(m[1]) === port) return Number(m[2]);
  }
  return null;
}

// Stops one live lock's emulator; returns a line describing what happened.
function stopLock(l) {
  const lock = l.lock;
  const who = `${lock.serial} (${lock.avd}, owner ${lock.owner || '?'})`;
  if (!l.live) {
    fs.rmSync(l.file, { force: true });
    return `removed the dead lock of ${who}: ${l.why}`;
  }
  let how = '';
  if (Number.isInteger(lock.pid) && typeof lock.serial === 'string' && /^emulator-\d+$/.test(lock.serial)) {
    const name = adb(['-s', lock.serial, 'emu', 'avd', 'name']);
    if (name !== null && name.split(/\r?\n/).map((s) => s.trim()).includes(lock.avd)) {
      adb(['-s', lock.serial, 'emu', 'kill']);
      for (let i = 0; i < 80 && L.imageOf(lock.pid); i++) sleepMs(250);
      if (!L.imageOf(lock.pid)) how = 'console kill';
    }
  }
  if (!how && Number.isInteger(lock.pid)) how = terminate(lock.pid) ? `pid ${lock.pid} terminated` : '';
  if (!how && !Number.isInteger(lock.pid)) {
    // Still booting: the qemu PID is not in the lock yet, so it is read from the console port's listener.
    const pid = listenerPid(lock.consolePort);
    how = pid && terminate(pid) ? `pid ${pid} (the console port's listener) terminated while booting` : 'still booting, no emulator on its console port yet';
  }
  // emulator.exe, the wrapper qemu ran under, normally exits with it; if it lingers it goes too.
  if (Number.isInteger(lock.emulatorPid) && L.isEmulatorPid(lock.emulatorPid)) {
    for (let i = 0; i < 20 && L.imageOf(lock.emulatorPid); i++) sleepMs(250);
    if (L.isEmulatorPid(lock.emulatorPid)) terminate(lock.emulatorPid);
  }
  const stillRuns = Number.isInteger(lock.pid) && L.isEmulatorPid(lock.pid);
  if (!stillRuns) fs.rmSync(l.file, { force: true });
  return stillRuns ? `could not stop ${who}: pid ${lock.pid} still runs; lock kept` : `stopped ${who} by ${how || 'pid'}; lock removed`;
}

function main(input) {
  const { lockDir } = L.policyEmulators();
  const { id, claims, sources } = claimsFor(input || {}, lockDir);
  if (id) fs.rmSync(path.join(lockDir, 'agents', `${id.replace(/[^\w.-]/g, '_')}.json`), { force: true });
  if (!claims.length) return [];
  const lines = [];
  for (const l of L.readLocks(lockDir)) {
    if (!L.claimMatches(claims, l.lock.avd)) continue;
    lines.push(stopLock(l));
  }
  if (lines.length) {
    const stamp = new Date().toISOString();
    const tag = `agent ${id || '?'} (${input.agent_type || '?'}; by ${sources.join(' and ')})`;
    try { fs.appendFileSync(path.join(lockDir, 'stop.log'), lines.map((s) => `${stamp} ${tag}: ${s}\n`).join('')); } catch (_) { /* best effort */ }
  }
  return lines;
}

module.exports = { main, claimsFor };

if (require.main === module) {
  let s = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (d) => { s += d; });
  process.stdin.on('end', () => {
    let lines = [];
    try { lines = main(JSON.parse(s)); } catch (_) { lines = []; }
    if (lines.length) process.stdout.write(JSON.stringify({ systemMessage: `emulator-stop: ${lines.join('; ')}` }));
    process.exit(0);
  });
}
