#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// Loads .claude/hooks/policy.json, the one file that holds a project's choices for the hooks
// (who commits and pushes, which branches may be deleted, usage thresholds, docs cap exemptions,
// formatters), and the helpers the usage gate and the status line share (thresholds, grants, the
// account label). The scripts hold mechanism only, so update-from-hatchery can patch them without
// touching a project's policy. Schema and fail modes: docs/design/decisions/0006-hook-policy-file.md.
//
// load() returns { policy, error, source }:
//   - file absent: policy = DEFAULTS (Hatchery's own behaviour), error = null.
//   - file present and valid: each key given replaces the default for that key.
//   - file unreadable, not JSON, an unknown key, or a value of the wrong type: policy = DEFAULTS,
//     error = a one-line description. Each hook decides what an error means (git-guard denies
//     every git command; the others fall back to DEFAULTS and say so).
// Keys starting with "$" (for example "$comment") are ignored at any level.
//
// Debug: node .claude/hooks/policy.js [file]  prints the effective policy, or the error and exit 1.
// Test seam: HOOK_POLICY_FILE names another file (test-hooks.js sets it; unset in normal use).
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');

const DEFAULTS = {
  git: {
    commit: { subagents: ['runner'], protectedBranches: [] },
    push: { subagents: ['pusher'], branches: ['*'], mainSession: true, forceWithLease: [] },
    branchDelete: { branches: [], requireMergedPr: false },
    hooksPath: null,
  },
  usage: {
    hold: 50,
    // null: no deny line, only HOLD, which the override file can lift up to and past a saturated
    // meter. A project that forbids overage sets a percent here as a ceiling no override lifts.
    deny: null,
    // Per-meter overrides; null inherits hold/deny, so a meter has no deny line when both are null. A project whose session meter strands
    // in-flight agents sooner than the weekly one runs down sets them apart (policy.examples/per-meter.json).
    fiveHour: { hold: null, deny: null },
    sevenDay: { hold: null, deny: null },
    staleSeconds: 7200,
    whenStale: 'allow',
    // Name of an environment variable that, set to "1" when the session starts, lifts HOLD on both
    // meters for that whole session (never the deny line); null for none. For a project that
    // relaunches in-flight work past HOLD from a session started for that purpose.
    overrideEnv: null,
    // The account pool (ADR 0011): null, or { command: argv, timeoutSeconds } run only when the
    // gate is about to deny at HOLD, its stdout read as the `cswap list --json` contract, to name
    // another account with room. timeoutSeconds defaults to 15 when the object omits it.
    pool: null,
    holdAdvice: 'Do not start new subagents. Let in-flight agents finish, run the handoff skill, commit from the main session, then report.',
    denyAdvice: 'Stop all work, run the handoff skill, commit WIP from the main session, end the turn.',
  },
  edit: {
    capExempt: [],
    anchorExempt: ['docs/design/proposals/*', 'docs/design/decisions/*', 'docs/findings/*'],
    formatters: {},
  },
};

const isStrings = (v) => Array.isArray(v) && v.every((s) => typeof s === 'string' && s !== '');
// Type checks for leaf values; each returns true when the value is acceptable.
const TYPES = {
  who: (v) => v === 'any' || v === 'none' || isStrings(v),
  patterns: isStrings,
  bool: (v) => typeof v === 'boolean',
  pathOrNull: (v) => v === null || (typeof v === 'string' && v !== ''),
  envNameOrNull: (v) => v === null || (typeof v === 'string' && /^[A-Za-z_][A-Za-z0-9_]*$/.test(v)),
  percent: (v) => typeof v === 'number' && v >= 0 && v <= 100,
  percentOrNull: (v) => v === null || TYPES.percent(v),
  seconds: (v) => Number.isInteger(v) && v > 0,
  stale: (v) => v === 'allow' || v === 'deny',
  text: (v) => typeof v === 'string' && v !== '',
  // null, or an object with a non-empty argv "command" and an optional "timeoutSeconds", an integer
  // from 1 to 60 (under Claude Code's own hook timeout, so a hung command ends in a deny); any
  // other key (other than "$..." comments) is wrong.
  pool: (v) => v === null || (typeof v === 'object' && !Array.isArray(v) &&
    Object.keys(v).every((k) => k.startsWith('$') || k === 'command' || k === 'timeoutSeconds') &&
    isStrings(v.command) && v.command.length > 0 &&
    (v.timeoutSeconds === undefined || (TYPES.seconds(v.timeoutSeconds) && v.timeoutSeconds <= 60))),
  formatters: (v) => v !== null && typeof v === 'object' && !Array.isArray(v) &&
    Object.keys(v).every((k) => /^\.[A-Za-z0-9]+$/.test(k) && isStrings(v[k]) && v[k].length > 0),
};
const SCHEMA = {
  git: {
    commit: { subagents: 'who', protectedBranches: 'patterns' },
    push: { subagents: 'who', branches: 'patterns', mainSession: 'bool', forceWithLease: 'patterns' },
    branchDelete: { branches: 'patterns', requireMergedPr: 'bool' },
    hooksPath: 'pathOrNull',
  },
  usage: {
    hold: 'percent', deny: 'percentOrNull',
    fiveHour: { hold: 'percentOrNull', deny: 'percentOrNull' },
    sevenDay: { hold: 'percentOrNull', deny: 'percentOrNull' },
    staleSeconds: 'seconds', whenStale: 'stale', overrideEnv: 'envNameOrNull', pool: 'pool', holdAdvice: 'text', denyAdvice: 'text',
  },
  edit: { capExempt: 'patterns', anchorExempt: 'patterns', formatters: 'formatters' },
};

// Walks value against schema, merging over defaults. Throws on the first problem.
function merge(schema, value, defaults, where) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${where || 'top level'} must be an object`);
  const out = {};
  for (const k of Object.keys(schema)) out[k] = defaults[k];
  for (const k of Object.keys(value)) {
    if (k.startsWith('$')) continue;
    const at = where ? `${where}.${k}` : k;
    if (!(k in schema)) throw new Error(`unknown key ${at}`);
    if (typeof schema[k] === 'string') {
      if (!TYPES[schema[k]](value[k])) throw new Error(`${at} has the wrong type or value (${JSON.stringify(value[k])})`);
      out[k] = value[k];
    } else {
      out[k] = merge(schema[k], value[k], defaults[k], at);
    }
  }
  return out;
}

function policyFile() {
  return process.env.HOOK_POLICY_FILE || path.join(__dirname, 'policy.json');
}

function load(file) {
  const source = file || policyFile();
  let text;
  try { text = fs.readFileSync(source, 'utf8'); } catch (e) {
    if (e && e.code === 'ENOENT') return { policy: DEFAULTS, error: null, source: null };
    return { policy: DEFAULTS, error: `cannot read ${source}: ${e.message}`, source };
  }
  try {
    const policy = merge(SCHEMA, JSON.parse(text.charCodeAt(0) === 0xfeff ? text.slice(1) : text), DEFAULTS, '');
    for (const [label, t] of meterThresholds(policy.usage)) {
      if (t.deny !== null && t.hold > t.deny) throw new Error(`usage hold is above deny for the ${label} meter`);
    }
    // The pool's defaults filled in, "$" keys dropped, so callers see { command, timeoutSeconds }.
    const pool = policy.usage.pool;
    if (pool) {
      policy.usage = Object.assign({}, policy.usage, {
        pool: { command: pool.command.slice(), timeoutSeconds: pool.timeoutSeconds === undefined ? 15 : pool.timeoutSeconds },
      });
    }
    return { policy, error: null, source };
  } catch (e) {
    return { policy: DEFAULTS, error: `${source}: ${e.message}`, source };
  }
}

// The effective [label, {hold, deny}] pair per meter: the per-meter value where set, else the
// shared one; deny is null when the meter has no deny line. Keys match the windows the status
// line caches.
function meterThresholds(usage) {
  const pick = (m) => ({ hold: m.hold === null ? usage.hold : m.hold, deny: m.deny === null ? usage.deny : m.deny });
  return [['5h', pick(usage.fiveHour)], ['7d', pick(usage.sevenDay)]];
}

// Judges readings { '5h': percent|null, '7d': percent|null } against the policy. Returns
// { level, hits }: level 2 when any meter is at or above its deny line (a meter without one never
// is), 1 when any is at or above its hold line, else 0; hits lists the meters that tripped at that
// level, as { label, pct, threshold }. The worse meter wins, each judged by its own thresholds.
function usageVerdict(usage, readings) {
  const out = { level: 0, hits: [] };
  for (const key of ['deny', 'hold']) {
    const level = key === 'deny' ? 2 : 1;
    const hits = [];
    for (const [label, t] of meterThresholds(usage)) {
      const pct = readings[label];
      if (typeof pct === 'number' && t[key] !== null && pct >= t[key]) hits.push({ label, pct, threshold: t[key] });
    }
    if (hits.length) return { level, hits };
  }
  return out;
}

// A HOLD override grant (ADR 0006, The HOLD override), classified for one hook call. grant is the
// parsed file (null when unreadable or not JSON), now is epoch seconds, ctx is { sessionId, cwd }
// from the PreToolUse payload, each a non-empty string or null. Returns { status, meters, scope }:
// status is checked in order "malformed", "expired", "out" (out of scope), else "applies"; meters
// is the list it lifts ("5h", "7d"; both when "meters" is absent); scope is { kind: "user" },
// { kind: "session", value } or { kind: "project", value }, value as written in the grant.
// Malformed: not an object, no numeric "until", an unknown meter name or a "meters" that is not a
// non-empty list (a typo lifts nothing rather than something the user did not ask for), both scope
// keys, a scope value that is not a non-empty string, or a "project" that is not absolute.
const METERS = ['5h', '7d'];
// Paths are compared with every "\" made "/", one trailing "/" dropped, and on Windows lower-cased.
// Symlinks and junctions are not resolved.
function normScopePath(p) {
  let s = p.replace(/\\/g, '/');
  if (s.endsWith('/')) s = s.slice(0, -1);
  return process.platform === 'win32' ? s.toLowerCase() : s;
}
function classifyGrant(grant, now, ctx) {
  const malformed = { status: 'malformed', meters: [], scope: null };
  if (!grant || typeof grant !== 'object' || Array.isArray(grant) || typeof grant.until !== 'number') return malformed;
  let meters = METERS.slice();
  if (grant.meters !== undefined) {
    if (!Array.isArray(grant.meters) || grant.meters.length === 0 || !grant.meters.every((m) => METERS.includes(m))) return malformed;
    meters = METERS.filter((m) => grant.meters.includes(m));
  }
  const has = (k) => Object.prototype.hasOwnProperty.call(grant, k);
  if (has('session') && has('project')) return malformed;
  let scope = { kind: 'user' };
  for (const kind of ['session', 'project']) {
    if (!has(kind)) continue;
    const value = grant[kind];
    if (typeof value !== 'string' || value === '') return malformed;
    // Judged by the host's own rules after the separator swap: off Windows, path.posix.isAbsolute
    // rejects a drive path such as C:/a/b, so a Windows project grant read there is malformed and
    // lifts nothing (fails closed).
    if (kind === 'project' && !path.isAbsolute(value.replace(/\\/g, '/'))) return malformed;
    scope = { kind, value };
  }
  if (grant.until <= now) return { status: 'expired', meters, scope };
  let inScope = true;
  if (scope.kind === 'session') inScope = !!ctx.sessionId && ctx.sessionId === scope.value;
  if (scope.kind === 'project') {
    const p = normScopePath(scope.value);
    const c = ctx.cwd ? normScopePath(ctx.cwd) : null;
    inScope = c !== null && (c === p || c.startsWith(`${p}/`));
  }
  return { status: inScope ? 'applies' : 'out', meters, scope };
}

// Override grants, written only on the owner's word and never written or deleted by a hook: every
// *.json file directly in ~/.claude/usage-gate-overrides/, then the legacy single file. Resolved at
// call time from the home directory, so a test's scratch HOME applies.
function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch (_) { return null; }
}
// [{ name, grant }] in file-name order, the legacy file last. Other names and subdirectories are
// ignored; an absent directory is no grants. An unreadable file has grant null (malformed).
function readGrants() {
  const dir = path.join(os.homedir(), '.claude', 'usage-gate-overrides');
  const legacy = path.join(os.homedir(), '.claude', 'usage-gate-override.json');
  const out = [];
  let names = [];
  try { names = fs.readdirSync(dir).filter((n) => n.endsWith('.json')).sort(); } catch (_) { /* no directory */ }
  for (const name of names) {
    const file = path.join(dir, name);
    let isFile = false;
    try { isFile = fs.statSync(file).isFile(); } catch (_) { /* vanished or a broken link */ }
    if (isFile) out.push({ name, grant: readJson(file) });
  }
  if (fs.existsSync(legacy)) out.push({ name: path.basename(legacy), grant: readJson(legacy) });
  return out;
}

// Classifies each grant from readGrants for this call. Returns { grants, applying, lifted }: grants
// with a .c (classifyGrant result) added, those whose status is "applies", and the meters ("5h",
// "7d") the applying grants lift between them.
function judgeGrants(grants, now, ctx) {
  const judged = grants.map((g) => Object.assign(g, { c: classifyGrant(g.grant, now, ctx) }));
  const applying = judged.filter((g) => g.c.status === 'applies');
  const lifted = METERS.filter((m) => applying.some((g) => g.c.meters.includes(m)));
  return { grants: judged, applying, lifted };
}

// True when the lifted meters cover every meter a HOLD verdict tripped (never for STOP: the caller
// asks only at level 1).
function holdCovered(lifted, verdict) {
  const labels = verdict.hits.map((h) => h.label);
  return lifted.length > 0 && labels.every((l) => lifted.includes(l));
}

// Would the gate let a launch through at this HOLD verdict: usage.overrideEnv names a variable in env
// set to "1", or the grants that apply lift every tripped meter. Level 2 is never lifted. Reads the
// grant files; throws only if they cannot be classified, so a caller that must not fail wraps it.
function holdLifted(usage, verdict, ctx, now, env) {
  if (verdict.level !== 1) return false;
  if (usage.overrideEnv && (env || process.env)[usage.overrideEnv] === '1') return true;
  return holdCovered(judgeGrants(readGrants(), now, ctx).lifted, verdict);
}

// The logged-in account, as a label for the per-session usage cache (ADR 0011): display and
// consistency only, never a key. The config home is CLAUDE_CONFIG_DIR when set, else the home
// directory; .config.json there (legacy), then .claude.json there, the first that parses with an
// oauthAccount carrying a non-empty accountUuid. Returns { uuid, organizationUuid, email } (the
// last two a non-empty string or null), or null. Never throws: a torn or absent file is null.
function readAccount() {
  try {
    const dir = typeof process.env.CLAUDE_CONFIG_DIR === 'string' && process.env.CLAUDE_CONFIG_DIR !== ''
      ? process.env.CLAUDE_CONFIG_DIR : os.homedir();
    for (const name of ['.config.json', '.claude.json']) {
      let j = null;
      try {
        const text = fs.readFileSync(path.join(dir, name), 'utf8');
        j = JSON.parse(text.charCodeAt(0) === 0xfeff ? text.slice(1) : text);
      } catch (_) { continue; }
      const o = j && typeof j === 'object' && j.oauthAccount && typeof j.oauthAccount === 'object' ? j.oauthAccount : null;
      const a = o ? accountLabel({ uuid: o.accountUuid, organizationUuid: o.organizationUuid, email: o.emailAddress }) : null;
      if (a) return a;
    }
  } catch (_) { /* never throw */ }
  return null;
}
// A label as stored in a usage file, normalised: an object with a non-empty string uuid, else null.
function accountLabel(a) {
  if (!a || typeof a !== 'object' || typeof a.uuid !== 'string' || a.uuid === '') return null;
  const str = (v) => (typeof v === 'string' && v !== '' ? v : null);
  return { uuid: a.uuid, organizationUuid: str(a.organizationUuid), email: str(a.email) };
}
// True when both labels are known and name different accounts: the uuids (known on both sides,
// since a label has one) differ, or both organizationUuids are known and differ. A field unknown on
// either side does not differ (ADR 0011).
function accountsDiffer(a, b) {
  const x = accountLabel(a);
  const y = accountLabel(b);
  if (!x || !y) return false;
  return x.uuid !== y.uuid || (x.organizationUuid !== null && y.organizationUuid !== null && x.organizationUuid !== y.organizationUuid);
}

// True when name matches one of the patterns; "*" matches any run of characters, "/" included.
function matches(patterns, name) {
  return patterns.some((p) => new RegExp(`^${p.split('*').map((s) => s.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*')}$`).test(name));
}

module.exports = { load, matches, usageVerdict, meterThresholds, classifyGrant, readGrants, judgeGrants, holdCovered, holdLifted, readAccount, accountLabel, accountsDiffer, DEFAULTS };

if (require.main === module) {
  const r = load(process.argv[2]);
  if (r.error) { process.stderr.write(`policy: ${r.error}\n`); process.exit(1); }
  process.stdout.write(`source: ${r.source || '(none; defaults)'}\n${JSON.stringify(r.policy, null, 2)}\n`);
}
