#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// PreToolUse hook for the Bash and PowerShell tools ("onFailure": "block" in .claude/settings.json).
// Denies destructive git commands and hook bypasses for every caller, and limits who commits,
// pushes and deletes branches by the project's policy (.claude/hooks/policy.json, read through
// policy.js). The main session (no agent_type) is limited only where the policy says so. Rule 20
// reads what every push sends through privacy-scan.js (ADR 0018), required inside that rule only.
// Spec: docs/hatchery/decisions/0002-hook-specs.md section 1; policy: ADR 0006.
// Test harness: node .claude/hooks/test-hooks.js
//
// Allow: exit 0, no stdout. Deny: exit 0 with permissionDecision "deny" JSON.
// Its own failure (unparseable input, an exception, a missing module): exit 2 with the reason on
// stderr, which blocks with or without onFailure. A malformed policy file denies every git command.
'use strict';

function fail(e) {
  const msg = e && e.message ? e.message : String(e);
  process.stderr.write(`git-guard: internal error (${msg}); command not run. Fix the hook or ask the user to run it.\n`);
  process.exit(2);
}
process.on('uncaughtException', fail);

const path = require('path');
const { spawnSync } = require('child_process');
const { parse, SUBST } = require('./shell-parse');
const { load, matches, integrateLimits } = require('./policy');

// git options that come before the subcommand and take a separate value.
const GLOBAL_OPTS_WITH_VALUE = new Set([
  '-C', '-c', '--git-dir', '--work-tree', '--namespace', '--config-env', '--super-prefix',
]);
// Per subcommand, the options that take a value: short letters, and long names taking the next
// word. Their values are dropped from args, so a message such as -m "-n" is not read as a flag.
const VALUE_OPTS = {
  commit: ['mFCct', ['--message', '--file', '--author', '--date', '--template', '--reuse-message',
    '--reedit-message', '--fixup', '--squash', '--cleanup', '--trailer', '--pathspec-from-file']],
  push: ['o', ['--push-option', '--repo', '--receive-pack', '--exec']],
  'send-pack': ['', ['--receive-pack', '--exec']],
  branch: ['u', ['--set-upstream-to', '--contains', '--no-contains', '--points-at', '--format', '--sort']],
  checkout: ['bB', ['--orphan']],
  switch: ['cC', ['--orphan', '--create', '--force-create']],
  restore: ['s', ['--source']],
  clean: ['e', ['--exclude']],
  stash: ['m', ['--message']],
  merge: ['msXF', ['--message', '--strategy', '--strategy-option', '--file']],
  rebase: ['xsX', ['--exec', '--onto', '--strategy', '--strategy-option']],
  worktree: ['bB', ['--reason']],
  config: ['f', ['--file', '--blob', '--type', '--default', '--comment']],
};
// Per subcommand, the long flags the rules look for. git accepts any unambiguous prefix of a long
// option (--har for --hard), so a prefix of four or more characters reads as every option it
// starts; an ambiguous one is an error in git, so over-matching costs nothing.
const GUARDED_LONG = {
  push: ['--force', '--force-with-lease', '--force-if-includes', '--delete', '--mirror', '--prune',
    '--no-verify', '--all', '--branches', '--tags', '--follow-tags', '--dry-run'],
  'send-pack': ['--force', '--mirror', '--all'],
  reset: ['--hard', '--merge', '--soft', '--mixed', '--keep'],
  clean: ['--dry-run', '--force'],
  checkout: ['--force'],
  switch: ['--force', '--discard-changes'],
  restore: ['--staged', '--worktree'],
  branch: ['--delete', '--force', '--move', '--copy'],
  rebase: ['--interactive', '--no-verify', '--abort', '--quit'],
  commit: ['--no-verify'],
  merge: ['--no-verify', '--abort', '--quit'],
  pull: ['--no-verify'],
  am: ['--no-verify', '--abort', '--quit'],
  'cherry-pick': ['--abort', '--quit'],
  revert: ['--abort', '--quit'],
  worktree: ['--force'],
  config: ['--unset', '--unset-all', '--remove-section', '--get', '--get-all', '--list'],
};
// Subcommands that create commits (rule 4), and those whose arguments must be visible (rule 19).
// Of them, the three that integrate another branch are judged by git.integrate (integrateLimits).
const COMMIT_SUBS = new Set(['commit', 'merge', 'pull', 'cherry-pick', 'revert', 'am', 'rebase']);
const INTEGRATE_SUBS = new Set(['pull', 'merge', 'rebase']);
const DESTRUCTIVE = new Set(['push', 'send-pack', 'reset', 'checkout', 'switch', 'restore', 'clean', 'branch',
  'worktree', 'stash', 'config', 'reflog', ...COMMIT_SUBS]);
// Words that run their arguments (or stdin) as a script. When one appears, quoted text in the same
// command line is read as commands too.
const RUNNERS = new Set(['sh', 'bash', 'zsh', 'dash', 'ksh', 'fish', 'pwsh', 'powershell', 'cmd', 'wsl',
  'eval', 'iex', 'invoke-expression', 'invoke-command', 'start-process', 'saps', 'xargs', 'source', '.']);
// Commands that run their arguments as another command; a runner after one of them still runs.
const WRAPPERS = new Set(['sudo', 'command', 'exec', 'time', 'nohup', 'env', 'nice', 'timeout', 'xargs',
  'start-process', 'saps', 'start', 'call', '!']);
// Words that may precede a command without making it an argument.
const PREFIX_WORDS = new Set(['sudo', 'command', 'exec', 'time', 'nohup', 'env', 'nice', '!', 'then', 'do',
  'else', 'if', 'elif', 'while', 'until']);
const NOTE = ' git-guard reads the command line, not what runs: it counted this git word as a command because' +
  ' it is unquoted or sits in text handed to a shell. If nothing here runs git, tell the user it is a false positive.';

const isGit = (w) => /(^|[\/\\])git(\.exe)?$/i.test(w);
const isEnvAssign = (w) => /^[A-Za-z_][A-Za-z0-9_]*=/.test(w);
const base = (w) => w.replace(/^.*[\/\\]/, '').replace(/\.exe$/i, '').toLowerCase();
// A git word that is a path (/usr/bin/git, <dir>/git) is read as git only when the word after it is
// an option or a git subcommand; otherwise it is a file name (cat <dir>/git, ls "$R/.claude/hooks/git"
// "$X"). Where it stands does not matter, so timeout 10 /usr/bin/git push -f is still git. A bare git
// word keeps counting anywhere, so unquoted text that names it is still read as a command.
const GIT_SUBCOMMANDS = new Set(('add am annotate apply archive bisect blame branch bugreport bundle cat-file check-attr ' +
  'check-ignore check-mailmap check-ref-format checkout checkout-index cherry cherry-pick citool clean clone column commit ' +
  'commit-graph commit-tree config count-objects credential describe diagnose diff diff-files diff-index diff-tree difftool ' +
  'fast-export fast-import fetch fetch-pack filter-branch filter-repo fmt-merge-msg for-each-ref for-each-repo format-patch ' +
  'fsck gc get-tar-commit-id grep gui hash-object help hook index-pack init instaweb interpret-trailers lfs log ls-files ' +
  'ls-remote ls-tree mailinfo mailsplit maintenance merge merge-base merge-file merge-index merge-tree mergetool mktag mktree ' +
  'multi-pack-index mv name-rev notes p4 pack-objects pack-redundant pack-refs patch-id prune prune-packed pull push ' +
  'range-diff read-tree rebase receive-pack reflog refs remote repack replace replay request-pull rerere reset restore ' +
  'rev-list rev-parse revert rm scalar send-email send-pack shortlog show show-branch show-index show-ref sparse-checkout ' +
  'stash status stripspace submodule svn switch symbolic-ref tag unpack-file unpack-objects update-index update-ref ' +
  'update-server-info upload-archive upload-pack var verify-commit verify-pack verify-tag version whatchanged worktree ' +
  'write-tree').split(' '));
const pathGitRuns = (next) => next !== undefined && (next.startsWith('-') || GIT_SUBCOMMANDS.has(next));
const isRunner = (w) => RUNNERS.has(base(w)) || /scriptblock\]::create/i.test(w);
const has = (args, ...flags) => args.some((a) => flags.includes(a));
const positionals = (args) => args.filter((a) => !a.startsWith('-'));
const refName = (r) => r.replace(/^refs\/heads\//, '');

function deny(reason) {
  process.stdout.write(JSON.stringify({
    hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: reason },
  }));
  process.exit(0);
}

// Normalises a subcommand's arguments: short clusters become single flags (-nm x is -n -m),
// abbreviated long options become the options they start, and option values are dropped (kept
// in values, keyed by option, the last one; every lists them all, so -x a -x b keeps both). opaque
// is true when a kept argument holds an expansion.
function normalize(sub, rest, opq) {
  const [shortV, longV] = VALUE_OPTS[sub] || ['', []];
  const known = (GUARDED_LONG[sub] || []).concat(longV);
  const args = [];
  const values = {};
  const every = {};
  const keep = (o, v) => { values[o] = v; (every[o] = every[o] || []).push(v); };
  let opaque = false;
  for (let k = 0; k < rest.length; k++) {
    const a = rest[k];
    if (a === '--') {
      args.push(...rest.slice(k));
      opaque = opaque || opq.slice(k).some(Boolean);
      break;
    }
    if (/^-[A-Za-z0-9]/.test(a)) {
      // A short cluster: letters until a value-taking one, whose value is the rest or the next word.
      let c = 1;
      for (; c < a.length && /[A-Za-z0-9]/.test(a[c]); c++) {
        args.push(`-${a[c]}`);
        if (shortV.includes(a[c])) {
          if (c === a.length - 1) { keep(`-${a[c]}`, rest[k + 1]); k++; } else keep(`-${a[c]}`, a.slice(c + 1));
          c = a.length + 1;
          break;
        }
      }
      if (c === a.length) continue;
      if (c < a.length) { args.push(a); opaque = opaque || opq[k]; }
      continue;
    }
    if (a.startsWith('--') && a.length > 2) {
      const at = k;
      const eq = a.indexOf('=');
      const name = eq < 0 ? a : a.slice(0, eq);
      const tail = eq < 0 ? '' : a.slice(eq);
      const full = known.includes(name) || name.length < 4 ? [name] : known.filter((o) => o.startsWith(name));
      const names = full.length > 0 ? full : [name];
      for (const f of names) args.push(f + tail);
      const takesValue = names.some((f) => longV.includes(f));
      if (takesValue) { keep(names[0], eq < 0 ? rest[k + 1] : tail.slice(1)); if (eq < 0) k++; }
      // An expansion in --name=value counts only when the option itself is hidden.
      if (opq[at] && !(eq > 0 && /^--[A-Za-z0-9-]+$/.test(name))) opaque = true;
      continue;
    }
    args.push(a);
    opaque = opaque || opq[k];
  }
  return { args, values, every, opaque };
}

// Resolves a cd or -C target against dir; Git Bash /c/... becomes C:/... on Windows.
function resolveDir(dir, rel) {
  if (!rel || rel.includes(SUBST)) return dir;
  const p = rel.replace(/^~(?=$|[\/\\])/, process.env.HOME || process.env.USERPROFILE || '~');
  return path.resolve(dir, process.platform === 'win32' ? p.replace(/^\/([a-zA-Z])(\/|$)/, '$1:/') : p);
}

// True when a pathspec covers the whole tree: ".", "./.", "*", ":/", ":(top)", "sub/..", "..".
function isWholeTree(p) {
  const q = p.replace(/\\/g, '/').replace(/^:(\/|\([^)]*\))/, '');
  const stack = [];
  for (const seg of q.split('/')) {
    if (seg === '' || seg === '.') continue;
    if (seg === '..') { if (stack.length === 0) return true; stack.pop(); } else stack.push(seg);
  }
  return stack.length === 0 || (stack.length === 1 && stack[0] === '*');
}

// The git invocations in one simple command: { sub, args, values, every, opaque, globals, dir, first,
// text }.
function invocations(cmd, dir) {
  const w = cmd.words;
  const o = cmd.opaque || w.map(() => false);
  const out = [];
  for (let i = 0; i < w.length; i++) {
    if (!isGit(w[i]) || (/[\/\\]/.test(w[i]) && !pathGitRuns(w[i + 1]))) continue;
    const globals = [];
    let d = dir;
    let hidden = false; // a -c or --config-env value the reader cannot see
    let j = i + 1;
    while (j < w.length && w[j].startsWith('-')) {
      if (GLOBAL_OPTS_WITH_VALUE.has(w[j])) {
        globals.push([w[j], w[j + 1] || '']);
        if (w[j] === '-C') d = resolveDir(d, w[j + 1]);
        if ((w[j] === '-c' || w[j] === '--config-env') && o[j + 1]) hidden = true;
        j += 2;
      } else {
        const eq = w[j].indexOf('=');
        globals.push(eq > 0 ? [w[j].slice(0, eq), w[j].slice(eq + 1)] : [w[j], null]);
        if (w[j].startsWith('--config-env') && o[j]) hidden = true;
        j++;
      }
    }
    const lead = w.slice(0, i);
    const viaXargs = lead.some((x) => base(x) === 'xargs'); // xargs appends arguments the reader never sees
    if (j >= w.length) {
      if (viaXargs) out.push({ sub: SUBST, args: [], values: {}, every: {}, opaque: true, globals, dir: d, text: cmd.text, first: false });
      continue;
    }
    const n = normalize(w[j], w.slice(j + 1), o.slice(j + 1));
    // Variables prefixed to this command (rule 20 passes the repository and config ones to its git).
    const leadEnv = {};
    lead.forEach((x, k) => { const m = /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/s.exec(x); if (m) leadEnv[m[1].toUpperCase()] = o[k] ? SUBST : m[2]; });
    out.push({
      sub: o[j] ? SUBST : w[j], args: n.args, values: n.values, every: n.every, opaque: n.opaque || hidden || viaXargs,
      globals, dir: d, text: cmd.text, leadEnv,
      first: !cmd.fromData && lead.every((x) => isEnvAssign(x) || PREFIX_WORDS.has(x)),
    });
  }
  return out;
}

// Current branch of the repository at dir, or null when unreadable ("HEAD" when detached).
const branchCache = new Map();
function currentBranch(dir) {
  if (!branchCache.has(dir)) {
    const r = spawnSync('git', ['-C', dir, 'rev-parse', '--abbrev-ref', 'HEAD'], { encoding: 'utf8', timeout: 5000 });
    branchCache.set(dir, !r.error && r.status === 0 ? r.stdout.trim() || null : null);
  }
  return branchCache.get(dir);
}

const isPush = (sub) => sub === 'push' || sub === 'send-pack';
// The branches a push updates: { refs, all, unknown }. No refspec pushes the current branch.
function pushTargets(inv) {
  const words = [];
  let all = false;
  for (const a of inv.args) {
    if (has([a], '--all', '--branches', '--tags', '--mirror', '--follow-tags')) all = true;
    else if (!a.startsWith('-')) words.push(a);
  }
  const refs = [];
  let unknown = false;
  const here = () => {
    const b = currentBranch(inv.dir);
    if (!b || b === 'HEAD') unknown = true; else refs.push(b);
  };
  if (words.length <= 1) here();
  for (const s of words.slice(1)) {
    const spec = s.replace(/^\+/, '');
    const dst = spec.includes(':') ? spec.slice(spec.indexOf(':') + 1) : spec;
    if (dst === '') continue; // a deletion, rule 2
    if (dst === 'HEAD') here(); else refs.push(refName(dst));
  }
  return { refs, all, unknown };
}
const within = (t, patterns) => !t.all && !t.unknown && t.refs.length > 0 && t.refs.every((r) => matches(patterns, r));

// Remote branches a push deletes (--delete arguments or :ref).
function pushDeletions(inv) {
  const del = has(inv.args, '--delete', '-d');
  return positionals(inv.args).slice(1)
    .filter((w) => del || (w.startsWith(':') && w.length > 1))
    .map((w) => refName(w.replace(/^:/, '')));
}

// True when GitHub shows a merged pull request from this branch. Fails closed (false) when gh is
// missing, offline or not logged in. Test seam: GIT_GUARD_MERGED, comma-separated branch names,
// replaces the gh call (test-hooks.js sets it; unset in normal use).
const mergedCache = new Map();
function mergedOnGitHub(branch, dir) {
  if (process.env.GIT_GUARD_MERGED !== undefined) return process.env.GIT_GUARD_MERGED.split(',').map((x) => x.trim()).includes(branch);
  if (!mergedCache.has(branch)) {
    let merged = false;
    try {
      const r = spawnSync('gh', ['pr', 'list', '--head', branch, '--state', 'merged', '--json', 'number', '--limit', '1'],
        { cwd: dir, encoding: 'utf8', timeout: 20000 });
      if (!r.error && r.status === 0) { const l = JSON.parse(r.stdout || '[]'); merged = Array.isArray(l) && l.length > 0; }
    } catch (_) { merged = false; }
    mergedCache.set(branch, merged);
  }
  return mergedCache.get(branch);
}

// Rules 2 and 9: deletion is allowed only when every branch matches git.branchDelete.branches
// and, when the policy asks, GitHub shows its pull request merged. Returns false (allow) or a deny.
function deletable(names, dir, { policy }) {
  const d = policy.git.branchDelete;
  if (d.branches.length === 0) return true;
  if (names.length > 0 && names.every((b) => matches(d.branches, b) && (!d.requireMergedPr || mergedOnGitHub(b, dir)))) return false;
  return `Only branches matching ${d.branches.join(', ')} are deleted` +
    `${d.requireMergedPr ? ', and only after GitHub shows their pull request merged; a failed or pending merge keeps the branch' : ''}.`;
}

// Rule 20 (ADR 0018). Environment variables a push's own repository and config come from, passed
// to the scan's git.
const REPO_ENV = /^(GIT_DIR|GIT_WORK_TREE|GIT_CONFIG_COUNT|GIT_CONFIG_KEY_\d+|GIT_CONFIG_VALUE_\d+)$/;

// The repository and config a push invocation acts on, as the scan's context { dir, pre, env, key },
// or { error } (a reason that reads after "cannot "). pre carries its --git-dir, --work-tree, -c and
// --config-env; env the REPO_ENV variables (and those a --config-env names) exported earlier in the
// line (inv.shellEnv) or prefixed to it (inv.leadEnv).
function gitContext(inv) {
  if (inv.envUnresolved) return { error: 'follow how the line sets GIT_DIR or GIT_WORK_TREE' };
  const pre = [];
  const named = new Set();
  for (const [o, v] of inv.globals) {
    if (!['--git-dir', '--work-tree', '-c', '--config-env'].includes(o)) continue;
    if (!v || v.includes(SUBST)) return { error: `see the value of ${o}` };
    if (o === '-c') pre.push('-c', v); else pre.push(`${o}=${v}`);
    if (o === '--config-env' && v.includes('=')) named.add(v.slice(v.indexOf('=') + 1).toUpperCase());
  }
  const extra = {};
  for (const [k, v] of Object.entries(Object.assign({}, inv.shellEnv || {}, inv.leadEnv || {}))) {
    if (!REPO_ENV.test(k) && !named.has(k)) continue;
    if (v.includes(SUBST)) return { error: `see the value of ${k}` };
    extra[k] = v;
  }
  const env = Object.keys(extra).length ? Object.assign({}, process.env, extra) : undefined;
  return { dir: inv.dir, pre, env, key: JSON.stringify([inv.dir, pre, extra]) };
}

// What the scan finds in what this push sends: { hits } or { error }. scan is the required module.
function privacyCheck(scan, inv, policy) {
  const g = gitContext(inv);
  if (g.error) return g;
  const p = {
    words: positionals(inv.args), repo: inv.values['--repo'], del: has(inv.args, '--delete', '-d'),
    all: has(inv.args, '--all', '--branches'), tags: has(inv.args, '--tags'), followTags: has(inv.args, '--follow-tags'),
  };
  let src;
  try { src = scan.pushSources(g, p); } catch (e) {
    if (e instanceof scan.ScanError) return { error: e.message };
    throw e;
  }
  return scan.scanPush(g, src.refs, src.remote, policy.privacy);
}
const SHARED_LINE = 'The push shares its line with another git command; the scan reads only commits that exist when it runs. ' +
  'Run the push on a line of its own.';
const scanFailed = (reason) => `The scan could not read what this push sends (${reason}); no push leaves without it. ` +
  'Run node .claude/hooks/privacy-scan.js by hand and read its cannot line; fix the repository state (a commit on the ' +
  'branch, a configured remote, a readable never or allow file) or ask the user. ADR 0018.';

const isLease = (a) => a.startsWith('--force-with-lease') || a.startsWith('--force-if-includes');
const isPlainForce = (a) => a === '--force' || a.startsWith('--force=') || a === '-f' || (a.startsWith('+') && a.length > 1);
const listOf = (who) => (who === 'any' ? 'any subagent' : who === 'none' ? 'none' : who.join(', '));
const mayAct = (who, agent) => who === 'any' || (Array.isArray(who) && who.includes(agent));

// Config keys an invocation sets through -c, --config-env, or GIT_CONFIG_KEY_n in the command line.
function configKeys(inv, ctx) {
  const keys = [];
  for (const [o, v] of inv.globals) if ((o === '-c' || o === '--config-env') && v) keys.push(v);
  for (const e of ctx.envConfig) if (/^GIT_CONFIG_KEY_\d+$/i.test(e.name)) keys.push(e.value);
  return keys;
}
// For git config: { unset, value } of the first positional key matching re, or null.
function configWrite(inv, re) {
  const pos = positionals(inv.args);
  if (pos[0] === 'unset') return re.test(pos[1] || '') ? { unset: true } : null;
  if (pos[0] === 'set') pos.shift();
  const k = pos.findIndex((a) => re.test(a));
  if (k < 0) return null;
  const unset = has(inv.args, '--unset', '--unset-all', '--remove-section');
  return { unset, value: pos[k].includes('=') ? pos[k].slice(pos[k].indexOf('=') + 1) : pos[k + 1] };
}
// git replace -l / --list with at most one pattern only lists replace refs (rule 12 allows it).
function listsReplaceRefs(args, opaque) {
  const rest = args.filter((a) => a !== '-l' && a !== '--list');
  return !opaque && args.length - rest.length === 1 && rest.length <= 1 && positionals(rest).length === rest.length;
}
const HOOKS_KEY = /^core\.hookspath(=|$)/i;
const INCLUDE_KEY = /^include(if\..*)?\.path(=|$)|^include\./i;
const ALIAS_KEY = /^alias\./i;
// Environment variables that point git at other config, so other hooks or aliases.
const CONFIG_ENV = /^GIT_CONFIG(_GLOBAL|_SYSTEM|_PARAMETERS)?$/i;

// Ordered rules. test(inv, ctx) returns false to pass, true to deny with the rule's advice, or a
// string to deny with that advice instead. ctx: { agent, policy, envConfig, gitCount } (gitCount: the
// git invocations in the line, for rule 20).
const RULES = [
  {
    n: 1, prefix: 'force push',
    advice: 'The project does not force-push; integrate the remote changes and push normally, or ask the user.',
    test: (inv, { policy }) => {
      if (!isPush(inv.sub)) return false;
      if (inv.args.some(isPlainForce)) return true;
      if (!inv.args.some(isLease)) return false;
      const ok = policy.git.push.forceWithLease;
      if (ok.length === 0 || inv.sub !== 'push') return true;
      return within(pushTargets(inv), ok) ? false : `--force-with-lease is allowed only onto branches matching ${ok.join(', ')}; ask the user for anything else.`;
    },
  },
  {
    n: 2, prefix: 'remote branch deletion',
    advice: 'Deleting or mirroring remote refs is for the user; ask the user to do it.',
    test: (inv, ctx) => {
      if (!isPush(inv.sub)) return false;
      if (has(inv.args, '--mirror', '--prune')) return true;
      const dels = pushDeletions(inv);
      if (dels.length === 0 && !has(inv.args, '--delete', '-d')) return false;
      return deletable(dels, inv.dir, ctx);
    },
  },
  {
    n: 3, prefix: 'push not permitted for this caller',
    test: (inv, { agent, policy }) => {
      if (!isPush(inv.sub)) return false;
      const p = policy.git.push;
      if (agent === null) return p.mainSession ? false : 'Under .claude/hooks/policy.json the main session does not push; ask the user.';
      if (!mayAct(p.subagents, agent)) return `Under .claude/hooks/policy.json the subagents that push are: ${listOf(p.subagents)}. Leave the work committed and report.`;
      if (p.branches.includes('*') || within(pushTargets(inv), p.branches)) return false;
      return `Subagents push only branches matching ${p.branches.join(', ')}; tags and other branches go through the main session.`;
    },
  },
  {
    // Tried right after rule 3, so a caller who may not push is told that first. The scan is
    // required here, not at the top, so no other rule or command depends on it; any throw is this
    // rule's deny.
    n: 20, prefix: 'personal data in the outgoing commits',
    test: (inv, ctx) => {
      // An opaque push is rule 19's: it is denied there, with the reason that fits.
      if (!isPush(inv.sub) || inv.opaque) return false;
      if (ctx.gitCount > 1) return SHARED_LINE;
      let s;
      try {
        s = privacyCheck(require('./privacy-scan'), inv, ctx.policy);
      } catch (e) {
        return scanFailed(`the scan failed: ${String(e && e.message ? e.message : e).split('\n')[0]}`);
      }
      if (s.error) return scanFailed(`cannot ${s.error}`);
      if (s.hits.length === 0) return false;
      const n = s.hits.length;
      const shown = s.hits.slice(0, 3).map((h) => `${h.where} (${h.class}: ${h.match})`).join('; ');
      return `${n} hit${n === 1 ? '' : 's'}${n > 3 ? ', the first three' : ''}: ${shown}. Fix: write ~, <repo>, a role or a ` +
        'placeholder and fold the edit into the unpushed commit (amend, or reset --soft and one new commit). A false ' +
        'positive is the owner\'s: ask the user, then an entry in privacy.allow (a non-personal literal) or ' +
        '~/.claude/privacy-allow.txt (the owner\'s own value), from the main session. Nothing in a brief lifts this. ADR 0018.';
    },
  },
  {
    n: 4, prefix: 'commit not permitted for this caller',
    test: (inv, { agent, policy }) => {
      if (!COMMIT_SUBS.has(inv.sub) || agent === null || has(inv.args, '--abort', '--quit')) return false;
      // pull, merge and rebase are judged by git.integrate, each key it leaves null by git.commit's;
      // the wording is the base's until the project sets the deciding key apart.
      const g = policy.git;
      const integrating = INTEGRATE_SUBS.has(inv.sub);
      const limits = integrateLimits(g);
      const c = integrating ? limits : g.commit;
      const whoApart = g.integrate.subagents !== null;
      if (!mayAct(c.subagents, agent)) {
        if (integrating && whoApart) {
          return `Under .claude/hooks/policy.json the subagents that pull, merge or rebase (git.integrate) are: ${listOf(c.subagents)}. Leave the integration to the main session and report.`;
        }
        return `Under .claude/hooks/policy.json the subagents that make commits (${whoApart ? 'commit, cherry-pick, revert, am' : 'commit, merge, pull, cherry-pick, revert, am, rebase'}) are: ${listOf(c.subagents)}. Describe the change in your report so it can be committed.`;
      }
      if (c.protectedBranches.length === 0) return false;
      const b = currentBranch(inv.dir);
      if (b === null || !matches(c.protectedBranches, b)) return false;
      if (integrating && g.integrate.protectedBranches !== null) return `Subagents do not pull, merge or rebase on ${b} (git.integrate); work on your own branch.`;
      if (integrating) return `Subagents do not make commits on ${b}; work on your own branch.`;
      // A commit on a branch the agent may integrate: a merge or rebase in progress there ends with its own --continue.
      const integrable = mayAct(limits.subagents, agent) && !matches(limits.protectedBranches, b);
      return `Subagents do not make commits on ${b}; work on your own branch.${integrable ? ' A merge or rebase in progress here is concluded with git merge --continue or git rebase --continue.' : ''}`;
    },
  },
  {
    n: 5, prefix: 'destructive reset',
    advice: 'Use git reset --soft or --mixed, or git stash, so the work stays recoverable; ask the user if a hard reset is needed.',
    test: ({ sub, args }) => sub === 'reset' && has(args, '--hard', '--merge'),
  },
  {
    n: 6, prefix: 'git clean deletes untracked files',
    advice: 'List what would go with a dry run, then delete named files or ask the user.',
    test: ({ sub, args }) => sub === 'clean' && !has(args, '-n', '--dry-run'),
  },
  {
    n: 7, prefix: 'whole-tree checkout discards edits',
    advice: 'Name the files to discard explicitly, or ask the user.',
    test: ({ sub, args }) => {
      if (sub !== 'checkout') return false;
      const dd = args.indexOf('--');
      return (dd >= 0 ? args.slice(dd + 1) : args.slice(-1)).some(isWholeTree);
    },
  },
  {
    n: 8, prefix: 'whole-tree restore discards edits',
    advice: 'Name the files to restore explicitly, or use git restore --staged to unstage only.',
    test: ({ sub, args }) => {
      if (sub !== 'restore') return false;
      const dd = args.indexOf('--');
      if (!(dd >= 0 ? args.slice(dd + 1) : positionals(args)).some(isWholeTree)) return false;
      return !(has(args, '--staged', '-S') && !has(args, '--worktree', '-W'));
    },
  },
  {
    n: 9, prefix: 'forced branch delete',
    advice: 'Use git branch -d, which refuses to delete unmerged work, or ask the user.',
    test: (inv, ctx) => {
      if (inv.sub !== 'branch') return false;
      const forced = has(inv.args, '-D') || (has(inv.args, '--delete', '-d') && has(inv.args, '--force', '-f'));
      return forced ? deletable(positionals(inv.args), inv.dir, ctx) : false;
    },
  },
  {
    n: 10, prefix: 'interactive rebase is unsupported here',
    advice: 'Interactive commands cannot run in this environment; use a non-interactive rebase or ask the user.',
    test: ({ sub, args }) => sub === 'rebase' && args.some((a) => a === '-i' || a === '--interactive' || a.startsWith('--interactive=')),
  },
  {
    n: 11, prefix: 'stash drop/clear is unrecoverable',
    advice: 'Use git stash pop or git stash apply, or ask the user before discarding a stash.',
    test: ({ sub, args }) => sub === 'stash' && ['drop', 'clear'].includes(positionals(args)[0]),
  },
  {
    n: 12, prefix: 'history rewrite',
    advice: 'History rewrites and ref surgery are for the user; ask first.',
    test: ({ sub, args, opaque }) => ['filter-branch', 'filter-repo', 'update-ref'].includes(sub) ||
      (sub === 'replace' && !listsReplaceRefs(args, opaque)) ||
      (sub === 'reflog' && ['expire', 'delete'].includes(positionals(args)[0])),
  },
  {
    n: 13, prefix: 'hooks bypass',
    advice: 'Never --no-verify. If a git hook fires, fix what it reports.',
    test: ({ sub, args }) => (has(args, '--no-verify') &&
      ['commit', 'push', 'merge', 'pull', 'rebase', 'am', 'cherry-pick', 'revert'].includes(sub)) ||
      (sub === 'commit' && has(args, '-n')),
  },
  {
    n: 14, prefix: 'hooks path override',
    test: (inv, ctx) => {
      const want = ctx.policy.git.hooksPath;
      const advice = 'Config that can redirect the git hooks (core.hooksPath, include files, GIT_CONFIG_GLOBAL, ' +
        `GIT_CONFIG_SYSTEM, GIT_CONFIG_PARAMETERS) is not changed here${want ? `; core.hooksPath stays ${want}` : ''}.`;
      if (ctx.envConfig.some((e) => CONFIG_ENV.test(e.name))) return advice;
      if (configKeys(inv, ctx).some((k) => HOOKS_KEY.test(k) || INCLUDE_KEY.test(k))) return advice;
      if (inv.sub !== 'config') return false;
      const inc = configWrite(inv, INCLUDE_KEY);
      if (inc && !inc.unset && inc.value !== undefined) return advice;
      const c = configWrite(inv, HOOKS_KEY);
      if (!c || (!c.unset && c.value === undefined)) return false; // a read
      if (!c.unset && want && c.value.replace(/\\/g, '/').replace(/\/$/, '') === want) return false;
      return advice;
    },
  },
  {
    n: 15, prefix: 'git alias definition',
    advice: 'An alias can wrap any command, so agents do not define one; run the git command itself.',
    test: (inv, ctx) => {
      if (configKeys(inv, ctx).some((k) => ALIAS_KEY.test(k))) return true;
      if (inv.sub !== 'config') return false;
      const c = configWrite(inv, ALIAS_KEY);
      return !!c && !c.unset && c.value !== undefined;
    },
  },
  {
    n: 16, prefix: 'forced checkout discards edits',
    advice: 'Commit or stash the edits first, or ask the user.',
    test: ({ sub, args }) => (sub === 'checkout' && has(args, '-f', '--force')) ||
      (sub === 'switch' && has(args, '-f', '--force', '--discard-changes')),
  },
  {
    n: 17, prefix: 'forced worktree removal',
    advice: 'Remove a clean worktree without --force, or ask the user; a forced removal deletes uncommitted work.',
    test: ({ sub, args }) => sub === 'worktree' && positionals(args)[0] === 'remove' && has(args, '-f', '--force'),
  },
  {
    n: 18, prefix: 'forced branch reset',
    test: (inv, { policy }) => {
      let target;
      if (inv.sub === 'checkout') target = inv.values['-B'];
      else if (inv.sub === 'switch') target = inv.values['-C'] || inv.values['--force-create'];
      else if (inv.sub === 'branch' && has(inv.args, '-f', '--force') &&
        !has(inv.args, '-d', '-D', '--delete', '-m', '-M', '--move', '-c', '-C', '--copy')) target = positionals(inv.args)[0];
      if (!target) return false;
      const prot = policy.git.commit.protectedBranches;
      if (!matches(prot, target) && target !== currentBranch(inv.dir)) return false;
      return `Resetting ${target} to another commit drops the commits on it; create a new branch instead, or ask the user.`;
    },
  },
  {
    n: 19, prefix: 'unexpanded argument',
    test: (inv) => {
      if (inv.tooDeep) return 'Shells nest deeper here (sh -c, eval, rebase --exec) than the guard reads, three levels, so it cannot see what runs inside. Run the inner command on a line of its own.';
      if (inv.sub === SUBST) return 'The command runs git with a subcommand or arguments the guard cannot see (a variable, a substitution, xargs input, or a command word that is an expansion). Run git by name with its arguments written out.';
      if (!DESTRUCTIVE.has(inv.sub) || !inv.opaque) return false;
      return `The guard cannot see what a variable, substitution or brace expansion becomes, and git ${inv.sub} can discard work or bypass hooks. Run it with the values written out.`;
    },
  },
];

// The words of an -ArgumentList string as the started process gets them: whitespace and commas
// separate, double quotes group and are dropped ('rebase -x "git push -f" main' is four words).
const argWords = (s) => (s.match(/(?:"[^"]*"?|[^\s,"]+)+/g) || []).map((w) => w.replace(/"/g, ''));

// Start-Process git -ArgumentList "push --force" (or "push","--force") as the git command it runs.
function startProcessCommand(c) {
  if (!['start-process', 'saps', 'start'].includes(base(c.words[0]))) return null;
  let file = null;
  let argList = null;
  let opaque = false;
  for (let k = 1; k < c.words.length; k++) {
    const w = c.words[k];
    if (/^-(f|filepath)$/i.test(w)) { file = c.words[++k]; continue; }
    if (/^-arg/i.test(w)) { argList = c.words[k + 1] || ''; opaque = !!(c.opaque && c.opaque[k + 1]); k++; continue; }
    if (!w.startsWith('-') && file === null) file = w;
  }
  if (!file || !isGit(file)) return null;
  const words = ['git', ...argWords(argList || '')];
  return { words, opaque: words.map((_, i) => i > 0 && opaque), text: c.text, stdin: [], fromData: true };
}

// The commands a git rebase in c hands to sh after each commit it replays (--exec, -x, repeats kept).
const rebaseExecs = (c) => invocations(c, '.').filter((i) => i.sub === 'rebase')
  .flatMap((i) => [...(i.every['-x'] || []), ...(i.every['--exec'] || [])]).filter((t) => typeof t === 'string');

// Every command the line runs, with the directory each runs in. Quoted text and heredoc bodies
// are read as commands too when a runner word appears (sh -c, eval, | bash, powershell -Command,
// [scriptblock]::Create), and the command of every git rebase --exec or -x always. Text still
// unread past the depth limit becomes one command marked tooDeep, which decide denies (rule 19).
function commandsOf(command, dialect, cwd) {
  const top = parse(command, dialect);
  const cmds = top.commands.slice();
  // A runner counts where it runs: as the command word, or after a wrapper that runs its arguments
  // (sudo bash -c, xargs sh -c, Start-Process powershell). As a plain argument it is only a word.
  const runner = (list) => list.some((c) => {
    let k = 0;
    while (k < c.words.length && isEnvAssign(c.words[k])) k++;
    if (k >= c.words.length) return false;
    if (isRunner(c.words[k])) return true;
    return WRAPPERS.has(base(c.words[k])) && c.words.slice(k + 1).some(isRunner);
  });
  // Text to read as a command line, three levels deep: { text, dialects, hidden }. Data is read in
  // both dialects; a rebase command in Bash, as git runs it with sh. hidden: the text is a word of a
  // line already read, so a SUBST in it is an expansion of that line and opaque. A Start-Process
  // command is reassembled here, so the rebase command of one is read too.
  let pending = [];
  const queue = (p, hidden) => {
    if (runner(p.commands)) for (const text of p.data) pending.push({ text, dialects: ['bash', 'powershell'], hidden });
    const started = p.commands.map(startProcessCommand).filter(Boolean);
    cmds.push(...started);
    for (const c of p.commands.concat(started)) {
      for (const text of rebaseExecs(c)) pending.push({ text, dialects: ['bash'], hidden: true });
    }
  };
  queue(top, false);
  for (let depth = 0; depth < 3 && pending.length > 0; depth++) {
    const now = pending;
    pending = [];
    for (const t of now) {
      for (const d of t.dialects) {
        const p = parse(t.text, d);
        for (const c of p.commands) {
          if (t.hidden) c.opaque = c.words.map((w, k) => c.opaque[k] || w.includes(SUBST));
          cmds.push(Object.assign(c, { fromData: true }));
        }
        queue(p, t.hidden);
      }
    }
  }
  // Fails closed: what the limit leaves unread could run anything.
  if (pending.length > 0) cmds.push({ words: [SUBST], opaque: [true], text: pending[0].text, stdin: [], fromData: true, tooDeep: true });
  let dir = cwd;
  return cmds.map((c) => {
    const at = dir;
    if (!c.fromData && ['cd', 'set-location', 'sl', 'chdir', 'pushd'].includes(c.words[0].toLowerCase())) dir = resolveDir(dir, c.words[1]);
    return { cmd: c, dir: at };
  });
}

// A command whose command word is an expansion ($G push -f, & $g push -f) in a line that names
// git: what it runs cannot be seen. A PowerShell assignment ($x = git ...) and a word ending in a
// literal file name other than git ($(dirname "$0")/tool.sh) are not this case.
function opaqueCommand(cmd, mentionsGit) {
  if (!mentionsGit) return false;
  let k = 0;
  while (k < cmd.words.length && isEnvAssign(cmd.words[k])) k++;
  if (k >= cmd.words.length || !(cmd.opaque && cmd.opaque[k])) return false;
  if (cmd.words[k + 1] === '=' || /^\$[A-Za-z_:]+=/.test(cmd.words[k])) return false;
  const last = cmd.words[k].split(/[\/\\]/).pop();
  // A path ending in git is git only when what follows reads as git ("$R/.claude/hooks/git" "$X" is not).
  if (cmd.words[k].match(/[\/\\]/) && isGit(last) && !pathGitRuns(cmd.words[k + 1])) return false;
  return !(cmd.words[k].match(/[\/\\]/) && last && !/[$\0{}]/.test(last) && !isGit(last));
}

// Variables exported for the rest of the line, into env (names upper-cased; an expansion in a
// value recorded as SUBST); vars holds Bash shell variables not yet exported. Bash: `export N=v`,
// `export N` after `N=v`, `declare -x` / `typeset -x`, `unset N`, and `N=v` on its own (which also
// updates an exported N). PowerShell: `$env:N = v`, `Set-Item [-Path] env:N [-Value] v` (or si),
// `Remove-Item [-Path] env:N` (or ri, del, rm). Returns the indices of the words it resolved, so
// decide can tell a GIT_DIR word it followed from one it did not (`set -a`, `read`, .NET calls).
// Rule 20 reads the result; no other rule does.
const ALLEXPORT = '\0allexport';
function noteExports(cmd, env, vars) {
  const w = cmd.words;
  const o = cmd.opaque || [];
  const used = new Set();
  if (cmd.fromData || w.length === 0) return used;
  const head = w[0].toLowerCase();
  const set = (n, v, op) => { env[n.toUpperCase()] = op ? SUBST : v; };
  if (head === 'set' && w.slice(1).some((x) => /^-[a-z]*a/.test(x) || x === 'allexport')) vars[ALLEXPORT] = true;
  if (head === 'export' || ((head === 'declare' || head === 'typeset') && w.slice(1).some((x) => /^-[a-z]*x/.test(x)))) {
    for (let k = 1; k < w.length; k++) {
      const m = /^([A-Za-z_][A-Za-z0-9_]*)(?:=(.*))?$/s.exec(w[k]);
      if (!m) continue;
      if (m[2] !== undefined) { set(m[1], m[2], o[k]); used.add(k); }
      else if (vars[m[1].toUpperCase()] !== undefined) { env[m[1].toUpperCase()] = vars[m[1].toUpperCase()]; used.add(k); }
    }
    return used;
  }
  if (head === 'unset') {
    w.slice(1).forEach((x, k) => { delete env[x.toUpperCase()]; delete vars[x.toUpperCase()]; used.add(k + 1); });
    return used;
  }
  if (w.every(isEnvAssign)) {
    w.forEach((x, k) => {
      const n = x.slice(0, x.indexOf('=')).toUpperCase();
      vars[n] = o[k] ? SUBST : x.slice(x.indexOf('=') + 1);
      if (n in env) env[n] = vars[n];
      if (!vars[ALLEXPORT]) used.add(k); // under set -a it is exported too: not followed
    });
    return used;
  }
  const ps = /^\$env:([A-Za-z_][A-Za-z0-9_]*)(?:=(.*))?$/is.exec(w[0]);
  if (ps) {
    if (ps[2] !== undefined) { set(ps[1], ps[2], o[0]); used.add(0); }
    else if (w[1] === '=' && w[2] !== undefined) { set(ps[1], w[2], o[2]); used.add(0); }
    return used;
  }
  const isSet = head === 'set-item' || head === 'si';
  if (!isSet && !['remove-item', 'ri', 'del', 'rm'].includes(head)) return used;
  let target = null;
  let targetK = -1;
  let value = null;
  let vop = false;
  const pos = [];
  for (let k = 1; k < w.length; k++) {
    if (/^-(path|literalpath)$/i.test(w[k])) { target = w[++k]; targetK = k; continue; }
    if (/^-value$/i.test(w[k])) { value = w[++k]; vop = !!o[k]; continue; }
    if (!w[k].startsWith('-')) pos.push([w[k], !!o[k], k]);
  }
  if (target === null && pos.length) [target, , targetK] = pos.shift();
  if (isSet && value === null && pos.length) [value, vop] = pos.shift();
  const m = /^env:\\?([A-Za-z_][A-Za-z0-9_]*)$/i.exec(target || '');
  if (!m) return used;
  if (isSet) { if (value !== null) { set(m[1], value, vop); used.add(targetK); } } else { delete env[m[1].toUpperCase()]; used.add(targetK); }
  return used;
}

// A word that names GIT_DIR or GIT_WORK_TREE as a token (no whitespace in it, so a commit message
// that mentions one is not counted).
const REPO_ENV_WORD = /(^|[^A-Za-z0-9_])(GIT_DIR|GIT_WORK_TREE)([^A-Za-z0-9_]|$)/i;

function decide(command, dialect, agent, cwd, loaded) {
  const cmds = commandsOf(command, dialect, cwd);
  const mentionsGit = /(^|[^A-Za-z0-9_-])git(\.exe)?([^A-Za-z0-9_-]|$)/i.test(command);
  const invs = [];
  const shellEnv = {};
  const shellVars = {};
  let envUnresolved = false;
  for (const { cmd, dir } of cmds) {
    const used = noteExports(cmd, shellEnv, shellVars);
    const firstGit = cmd.words.findIndex(isGit);
    cmd.words.forEach((w, k) => {
      if (/\s/.test(w) || !REPO_ENV_WORD.test(w) || used.has(k)) return;
      if (firstGit > k && cmd.words.slice(0, firstGit).every((x) => isEnvAssign(x) || PREFIX_WORDS.has(x))) return; // a prefix, in leadEnv
      envUnresolved = true;
    });
    for (const inv of invocations(cmd, dir)) invs.push(Object.assign(inv, { shellEnv: Object.assign({}, shellEnv) }));
    if (cmd.tooDeep) invs.push({ sub: SUBST, args: [], values: {}, every: {}, opaque: true, globals: [], dir, text: cmd.text, first: false, tooDeep: true });
    else if (opaqueCommand(cmd, mentionsGit)) invs.push({ sub: SUBST, args: [], values: {}, every: {}, opaque: true, globals: [], dir, text: cmd.text, first: false });
  }
  if (envUnresolved) for (const inv of invs) inv.envUnresolved = true;
  if (invs.length === 0) return null;
  const clip = (t) => (t.length > 200 ? `${t.slice(0, 200)}...` : t);
  if (loaded.error) {
    return `git-guard: policy file is malformed (${loaded.error}): \`${clip(invs[0].text)}\`. Every git command is denied until it is fixed; ask the user. A key from a newer base is one cause: remove it with the Edit tool (git, this checkout included, is denied until the policy loads), run the update, which brings the new policy.js, then put the key back.`;
  }
  // Config by environment (GIT_CONFIG_KEY_n=..., GIT_CONFIG_GLOBAL=..., $env:GIT_CONFIG_... = ...).
  const envConfig = [];
  for (const { cmd } of cmds) {
    cmd.words.forEach((w, k) => {
      const m = /^(?:\$env:)?(GIT_CONFIG(?:_KEY_\d+|_PARAMETERS|_GLOBAL|_SYSTEM)?)(?:=(.*))?$/i.exec(w);
      if (m) envConfig.push({ name: m[1], value: m[2] !== undefined ? m[2] : (cmd.words[k + 1] === '=' ? cmd.words[k + 2] || '' : '') });
    });
  }
  // Quoted text handed to a shell is parsed in both dialects, so the same command can appear twice:
  // each (text, subcommand) pair counts once.
  const gitCount = new Set(invs.map((i) => `${i.text}\0${i.sub}`)).size;
  const ctx = { agent, policy: loaded.policy, envConfig, gitCount };
  for (const rule of RULES) {
    for (const inv of invs) {
      const r = rule.test(inv, ctx);
      if (r) return `git-guard: ${rule.prefix}: \`${clip(inv.text)}\`. ${typeof r === 'string' ? r : rule.advice}${inv.first ? '' : NOTE}`;
    }
  }
  return null;
}

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (c) => (raw += c));
process.stdin.on('end', () => {
  let verdict;
  try {
    const input = JSON.parse(raw);
    const command = input && input.tool_input && input.tool_input.command;
    if (typeof command !== 'string') process.exit(0);
    const agent = typeof input.agent_type === 'string' && input.agent_type !== '' ? input.agent_type : null;
    const cwd = typeof input.cwd === 'string' && input.cwd ? input.cwd : process.cwd();
    const dialect = input.tool_name === 'PowerShell' ? 'powershell' : 'bash';
    verdict = decide(command, dialect, agent, cwd, load());
  } catch (e) {
    fail(e);
  }
  if (verdict) deny(verdict);
  process.exit(0);
});
