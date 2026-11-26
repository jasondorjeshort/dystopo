#!/usr/bin/env node
// Per-agent cost, recorded by hooks (Dystopo T298; idea of 2026-11-10, finding 2026-11-16-research-for-pending-ideas).
// One tab-separated file per session, ~/.claude/usage-state/<session_id>.agents.tsv, never in the repository:
// - a `launch` line, appended by usage-gate.js (appendLaunch) when it lets an Agent call through: the time, the
//   call's tool_use_id, subagent_type, model and description, and the two meter readings of the status line's file;
// - a `stop` line, appended by this script as a SubagentStop hook: agent_id, agent_type, the tool_use_id, description
//   and model of the launch (from the agent-<id>.meta.json beside agent_transcript_path), the readings, and the
//   tokens summed from the agent's own transcript. A stop whose input carries a non-empty background_tasks is the
//   agent yielding (emulator-stop.js reads it the same way): its line says `yield` in the note;
// - a `resume` line, appended by usage-gate.js (appendResume) when it lets a SendMessage through at HOLD because the
//   target was launched in this session below both holds (launchedBelow; Dystopo T376).
// Columns, the join (tool_use_id, else order) and tools/agent-cost.py: docs/reference/agent-cost.md.
//
// Tokens: every assistant line of the transcript carries its request's usage, repeated once per content block
// (a request with thinking and a tool call writes two or more lines with one message id), so lines are deduplicated
// by message.id, the last one kept. Summed per column; `last_context` is the last request's input + cache write +
// cache read + output, which is the figure an agent's completion notice gives as subagent_tokens.
// The transcript is read in 1 MB chunks, never whole into memory, until DEADLINE_MS; past it the sums are partial
// and the note says how far the read came. A line longer than LINE_CAP is skipped.
//
// Never blocks and never throws: every path exits 0 with nothing on stdout; a fault becomes a note in the line, or
// no line when the session id is unusable. A hard timer ends the process after HARD_MS whatever stdin does.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');

const SESSION_ID = /^[A-Za-z0-9_-]+$/;
const DEADLINE_MS = Number(process.env.AGENT_METER_DEADLINE_MS) > 0 ? Number(process.env.AGENT_METER_DEADLINE_MS) : 600;
const HARD_MS = 2000;
const LINE_CAP = 64 * 1024 * 1024;
const COLUMNS = ['time', 'event', 'tool_use_id', 'agent_id', 'agent_type', 'model', 'description', 'five_hour', 'seven_day',
  'meter_age_s', 'requests', 'input', 'cache_write', 'cache_read', 'output', 'last_context', 'note'];

const stateDir = () => path.join(os.homedir(), '.claude', 'usage-state');
const str = (v) => (typeof v === 'string' && v !== '' ? v : null);
const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const cell = (v) => (v === null || v === undefined ? '' : String(v).replace(/[\t\r\n]+/g, ' ').slice(0, 300));

// The two readings of the status line's file for this session: a window past its reset counts as none.
function readings(sid, now) {
  let s = null;
  try { s = JSON.parse(fs.readFileSync(path.join(stateDir(), `${sid}.json`), 'utf8')); } catch (_) { /* none */ }
  if (!s || typeof s !== 'object') return { five: null, seven: null, age: null };
  const pct = (w) => (w && num(w.used_percentage) !== null && !(num(w.resets_at) !== null && w.resets_at <= now) ? w.used_percentage : null);
  return { five: pct(s.five_hour), seven: pct(s.seven_day), age: num(s.updatedAt) !== null ? now - s.updatedAt : null };
}

function append(sid, row) {
  const dir = stateDir();
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${sid}.agents.tsv`);
  const head = fs.existsSync(file) ? '' : COLUMNS.join('\t') + '\n';
  fs.appendFileSync(file, head + COLUMNS.map((c) => cell(row[c])).join('\t') + '\n');
  return file;
}

function base(sid, event, now) {
  const r = readings(sid, now);
  return { time: new Date(now * 1000).toISOString().replace(/\.\d+Z$/, 'Z'), event, five_hour: r.five, seven_day: r.seven, meter_age_s: r.age };
}

// Called by usage-gate.js on an allowed Agent call, inside its own try. Returns the file written or null.
function appendLaunch(raw) {
  let p = null;
  try { p = JSON.parse(raw); } catch (_) { return null; }
  if (!p || p.tool_name !== 'Agent' || !str(p.session_id) || !SESSION_ID.test(p.session_id)) return null;
  const t = p.tool_input && typeof p.tool_input === 'object' ? p.tool_input : {};
  const row = base(p.session_id, 'launch', Math.floor(Date.now() / 1000));
  Object.assign(row, { tool_use_id: str(p.tool_use_id), agent_type: str(t.subagent_type) || 'general-purpose', model: str(t.model),
    description: str(t.description), note: t.run_in_background === true ? 'background' : null });
  return append(p.session_id, row);
}

// Token sums from a transcript, read in chunks until the deadline. Never throws.
function tokens(file, deadline) {
  const out = { requests: 0, input: 0, cache_write: 0, cache_read: 0, output: 0, last_context: null, model: null, note: null };
  let fd = null;
  try {
    fd = fs.openSync(file, 'r');
    const st = fs.fstatSync(fd);
    if (!st.isFile()) throw Object.assign(new Error('not a file'), { code: 'ENOTFILE' });
    const size = st.size;
    const buf = Buffer.alloc(1 << 20);
    const byId = new Map();
    let parts = [];
    let partLen = 0;
    let skipping = false;
    let skipped = 0;
    let pos = 0;
    const take = (line) => {
      if (line.indexOf('"usage"') < 0 || line.indexOf('"assistant"') < 0) return;
      let o;
      try { o = JSON.parse(line.toString('utf8')); } catch (_) { return; }
      const m = o && o.type === 'assistant' && o.message;
      if (!m || !m.usage || typeof m.usage !== 'object') return;
      const key = str(m.id) || str(o.requestId) || `line${byId.size}`;
      byId.delete(key);
      byId.set(key, { u: m.usage, model: str(m.model) });
    };
    while (pos < size) {
      if (Date.now() > deadline) { out.note = `partial: ${Math.round(pos / 1048576)} of ${Math.round(size / 1048576)} MB read`; break; }
      const n = fs.readSync(fd, buf, 0, buf.length, pos);
      if (n <= 0) break;
      pos += n;
      const chunk = buf.subarray(0, n);
      let start = 0;
      for (let nl = chunk.indexOf(10, 0); nl >= 0; nl = chunk.indexOf(10, start)) {
        if (!skipping) {
          const piece = chunk.subarray(start, nl);
          take(parts.length ? Buffer.concat([...parts, piece]) : piece);
        }
        parts = []; partLen = 0; skipping = false; start = nl + 1;
      }
      if (start < n && !skipping) {
        partLen += n - start;
        if (partLen > LINE_CAP) { parts = []; skipping = true; skipped++; } else parts.push(Buffer.from(chunk.subarray(start)));
      }
    }
    if (parts.length && !skipping && pos >= size) take(Buffer.concat(parts));
    const v = (u, k) => num(u[k]) || 0;
    let last = null;
    for (const { u, model } of byId.values()) {
      out.requests++;
      out.input += v(u, 'input_tokens');
      out.cache_write += v(u, 'cache_creation_input_tokens');
      out.cache_read += v(u, 'cache_read_input_tokens');
      out.output += v(u, 'output_tokens');
      last = u;
      if (model) out.model = model;
    }
    if (last) out.last_context = v(last, 'input_tokens') + v(last, 'cache_creation_input_tokens') + v(last, 'cache_read_input_tokens') + v(last, 'output_tokens');
    if (skipped) out.note = [out.note, `${skipped} line(s) over ${LINE_CAP / 1048576} MB skipped`].filter(Boolean).join('; ');
  } catch (e) {
    out.note = `transcript unreadable (${e.code || e.message})`;
  } finally {
    if (fd !== null) { try { fs.closeSync(fd); } catch (_) { /* closed */ } }
  }
  return out;
}

// The launch's own record beside the transcript: agent-<id>.meta.json (toolUseId, description, model, agentType).
function meta(transcript) {
  try {
    const f = transcript.replace(/\.jsonl$/i, '.meta.json');
    if (f === transcript) return {};
    const j = JSON.parse(fs.readFileSync(f, 'utf8'));
    return j && typeof j === 'object' ? j : {};
  } catch (_) { return {}; }
}

function stopLine(raw, t0) {
  let p = null;
  try { p = JSON.parse(raw); } catch (_) { return null; }
  if (!p || typeof p !== 'object' || !str(p.session_id) || !SESSION_ID.test(p.session_id)) return null;
  const transcript = str(p.agent_transcript_path);
  const m = transcript ? meta(transcript) : {};
  const row = base(p.session_id, 'stop', Math.floor(Date.now() / 1000));
  const notes = [];
  if (Array.isArray(p.background_tasks) && p.background_tasks.length) notes.push('yield');
  let tk = { model: null };
  if (transcript) {
    tk = tokens(transcript, t0 + DEADLINE_MS);
    if (tk.note) notes.push(tk.note);
  } else notes.push('no agent_transcript_path');
  Object.assign(row, {
    tool_use_id: str(m.toolUseId), agent_id: str(p.agent_id), agent_type: str(p.agent_type) || str(m.agentType),
    model: tk.model || str(m.model), description: str(m.description),
    requests: tk.requests, input: tk.input, cache_write: tk.cache_write, cache_read: tk.cache_read, output: tk.output,
    last_context: tk.last_context, note: notes.join('; ') || null,
  });
  return append(p.session_id, row);
}

// ---- Dystopo T376: a SendMessage at HOLD may resume an agent this session launched below both holds ----
// The owner, 2026-11-26: "Resumes pass, new agents wait". usage-gate.js asks launchedBelow() only for a SendMessage
// at HOLD; every gap or fault below is a refusal (ok false), which the gate turns into today's deny.
// An agent id as Claude Code writes it (agent-<id>.meta.json, every one on this machine on 2026-11-26): checked before
// the id enters a path. A name is not an id and is refused: a resume addresses the agent by its id.
const AGENT_ID = /^a[0-9a-f]{16}$/;
const TOOL_USE_ID = /^toolu_[A-Za-z0-9]+$/;
const PERCENT = /^\d+(\.\d+)?$/;

// The session's subagents folder from the PreToolUse payload's transcript_path: the session's own transcript
// <project>/<sid>.jsonl, whose agents sit in <project>/<sid>/subagents/ (nested agents too), or a transcript already
// in that folder. The folder must carry this session's id; anything else is null.
function subagentsDir(sid, transcriptPath) {
  if (!str(transcriptPath) || !path.isAbsolute(transcriptPath)) return null;
  const dir = path.dirname(transcriptPath);
  if (path.basename(dir) === 'subagents' && path.basename(path.dirname(dir)) === sid) return dir;
  if (path.basename(transcriptPath) === `${sid}.jsonl`) return path.join(dir, sid, 'subagents');
  return null;
}

// The session's agents.tsv as rows keyed by column; throws on a missing file or a header other than COLUMNS.
function rowsOf(sid) {
  const lines = fs.readFileSync(path.join(stateDir(), `${sid}.agents.tsv`), 'utf8').split(/\r?\n/);
  if (lines[0] !== COLUMNS.join('\t')) throw new Error('agents.tsv header is not the known columns');
  const out = [];
  for (const l of lines.slice(1)) {
    const v = l.split('\t');
    if (v.length === COLUMNS.length) out.push(Object.fromEntries(COLUMNS.map((c, i) => [c, v[i]])));
  }
  return out;
}

// { ok: true, launch } when `to` is an agent of session sid whose one launch line has both readings under their
// holds ({ '5h': n, '7d': n }): a reading under its hold, or empty while the other is present (the status line had no
// live window for that meter, which the gate counts as no hit), and the meters no older than staleSeconds. Otherwise
// { ok: false, why }. Never throws.
function launchedBelow(sid, to, transcriptPath, holds, staleSeconds) {
  const no = (why) => ({ ok: false, why });
  try {
    if (!str(sid) || !SESSION_ID.test(sid)) return no('no usable session id');
    if (typeof to !== 'string' || !AGENT_ID.test(to)) return no('the target is not an agent id');
    const dir = subagentsDir(sid, transcriptPath);
    if (!dir) return no('no subagents folder of this session from transcript_path');
    let m;
    try { m = JSON.parse(fs.readFileSync(path.join(dir, `agent-${to}.meta.json`), 'utf8')); } catch (e) { return no(`no readable meta file (${e.code || 'not JSON'})`); }
    if (!m || typeof m !== 'object' || Array.isArray(m) || typeof m.toolUseId !== 'string' || !TOOL_USE_ID.test(m.toolUseId)) return no('the meta file has no launch id');
    let rows;
    try { rows = rowsOf(sid); } catch (e) { return no(`agents.tsv unreadable (${e.code || e.message})`); }
    const hits = rows.filter((r) => r.event === 'launch' && r.tool_use_id === m.toolUseId);
    if (hits.length !== 1) return no(hits.length ? `${hits.length} launch lines for ${m.toolUseId}` : `no launch line for ${m.toolUseId} in this session`);
    const l = hits[0];
    const pct = (c) => (c === '' ? null : PERCENT.test(c) ? Number(c) : undefined);
    const five = pct(l.five_hour);
    const seven = pct(l.seven_day);
    if (five === undefined || seven === undefined) return no('a reading on the launch line is not a number');
    if (five === null && seven === null) return no('the launch line has no reading');
    if (!/^\d+$/.test(l.meter_age_s) || typeof staleSeconds !== 'number' || Number(l.meter_age_s) > staleSeconds) return no('the launch\'s readings were stale or unknown');
    if (typeof holds['5h'] !== 'number' || typeof holds['7d'] !== 'number') return no('no hold to compare');
    if ((five !== null && five >= holds['5h']) || (seven !== null && seven >= holds['7d'])) return no('launched at or over a hold');
    return { ok: true, launch: l };
  } catch (e) { return no(`lookup failed (${e.message})`); }
}

// The `resume` line of an allowed resume: the launch's id, type, model and description, the agent id, today's
// readings, and the launch's time and readings in the note. Returns the file; throws when it cannot write.
function appendResume(sid, agentId, launch) {
  const row = base(sid, 'resume', Math.floor(Date.now() / 1000));
  const r = (c) => (c === '' ? 'n/a' : `${c}%`);
  Object.assign(row, { tool_use_id: launch.tool_use_id, agent_id: agentId, agent_type: str(launch.agent_type), model: str(launch.model),
    description: str(launch.description), note: `launched ${launch.time} at 5h ${r(launch.five_hour)}, 7d ${r(launch.seven_day)}` });
  return append(sid, row);
}

if (require.main === module) {
  const t0 = Date.now();
  const hard = setTimeout(() => process.exit(0), HARD_MS);
  let raw = '';
  try {
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (c) => { if (raw.length < 1 << 20) raw += c; });
    process.stdin.on('error', () => process.exit(0));
    process.stdin.on('end', () => {
      try { stopLine(raw, t0); } catch (_) { /* never throws */ }
      clearTimeout(hard);
      process.exit(0);
    });
  } catch (_) { process.exit(0); }
}

module.exports = { appendLaunch, stopLine, tokens, COLUMNS, launchedBelow, appendResume, subagentsDir };
