#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// PreToolUse hook for the Bash and PowerShell tools ("onFailure": "block" in .claude/settings.json).
// Denies destructive git commands and hook bypasses for every caller, and limits who commits,
// pushes and deletes branches by the project's policy (.claude/hooks/policy.json, read through
// policy.js). The main session (no agent_type) is limited only where the policy says so.
// Spec: docs/design/decisions/0002-hook-specs.md section 1; policy: ADR 0006.
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
const { load, matches } = require('./policy');

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
const COMMIT_SUBS = new Set(['commit', 'merge', 'pull', 'cherry-pick', 'revert', 'am', 'rebase']);
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
// in values, keyed by option). opaque is true when a kept argument holds an expansion.
function normalize(sub, rest, opq) {
  const [shortV, longV] = VALUE_OPTS[sub] || ['', []];
  const known = (GUARDED_LONG[sub] || []).concat(longV);
  const args = [];
  const values = {};
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
          if (c === a.length - 1) { values[`-${a[c]}`] = rest[k + 1]; k++; } else values[`-${a[c]}`] = a.slice(c + 1);
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
      if (takesValue) { values[names[0]] = eq < 0 ? rest[k + 1] : tail.slice(1); if (eq < 0) k++; }
      // An expansion in --name=value counts only when the option itself is hidden.
      if (opq[at] && !(eq > 0 && /^--[A-Za-z0-9-]+$/.test(name))) opaque = true;
      continue;
    }
    args.push(a);
    opaque = opaque || opq[k];
  }
  return { args, values, opaque };
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

// The git invocations in one simple command: { sub, args, values, opaque, globals, dir, first, text }.
function invocations(cmd, dir) {
  const w = cmd.words;
  const o = cmd.opaque || w.map(() => false);
  const out = [];
  for (let i = 0; i < w.length; i++) {
    if (!isGit(w[i])) continue;
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
      if (viaXargs) out.push({ sub: SUBST, args: [], values: {}, opaque: true, globals, dir: d, text: cmd.text, first: false });
      continue;
    }
    const n = normalize(w[j], w.slice(j + 1), o.slice(j + 1));
    out.push({
      sub: o[j] ? SUBST : w[j], args: n.args, values: n.values, opaque: n.opaque || hidden || viaXargs,
      globals, dir: d, text: cmd.text,
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
const HOOKS_KEY = /^core\.hookspath(=|$)/i;
const INCLUDE_KEY = /^include(if\..*)?\.path(=|$)|^include\./i;
const ALIAS_KEY = /^alias\./i;
// Environment variables that point git at other config, so other hooks or aliases.
const CONFIG_ENV = /^GIT_CONFIG(_GLOBAL|_SYSTEM|_PARAMETERS)?$/i;

// Ordered rules. test(inv, ctx) returns false to pass, true to deny with the rule's advice, or a
// string to deny with that advice instead. ctx: { agent, policy, envConfig }.
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
    n: 4, prefix: 'commit not permitted for this caller',
    test: (inv, { agent, policy }) => {
      if (!COMMIT_SUBS.has(inv.sub) || agent === null || has(inv.args, '--abort', '--quit')) return false;
      const c = policy.git.commit;
      if (!mayAct(c.subagents, agent)) {
        return `Under .claude/hooks/policy.json the subagents that make commits (commit, merge, pull, cherry-pick, revert, am, rebase) are: ${listOf(c.subagents)}. Describe the change in your report so it can be committed.`;
      }
      if (c.protectedBranches.length === 0) return false;
      const b = currentBranch(inv.dir);
      return b !== null && matches(c.protectedBranches, b) ? `Subagents do not make commits on ${b}; work on your own branch.` : false;
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
    test: ({ sub, args }) => ['filter-branch', 'filter-repo', 'replace', 'update-ref'].includes(sub) ||
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
      if (inv.sub === SUBST) return 'The command runs git with a subcommand or arguments the guard cannot see (a variable, a substitution, xargs input, or a command word that is an expansion). Run git by name with its arguments written out.';
      if (!DESTRUCTIVE.has(inv.sub) || !inv.opaque) return false;
      return `The guard cannot see what a variable, substitution or brace expansion becomes, and git ${inv.sub} can discard work or bypass hooks. Run it with the values written out.`;
    },
  },
];

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
  const words = ['git', ...(argList || '').split(/[\s,]+/).filter(Boolean)];
  return { words, opaque: words.map((_, i) => i > 0 && opaque), text: c.text, stdin: [], fromData: true };
}

// Every command the line runs, with the directory each runs in. Quoted text and heredoc bodies
// are read as commands too when a runner word appears (sh -c, eval, | bash, powershell -Command,
// [scriptblock]::Create).
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
  let data = runner(cmds) ? top.data : [];
  for (let depth = 0; depth < 3 && data.length > 0; depth++) {
    const next = [];
    for (const text of data) {
      for (const d of ['bash', 'powershell']) {
        const p = parse(text, d);
        for (const c of p.commands) cmds.push(Object.assign(c, { fromData: true }));
        if (runner(p.commands)) next.push(...p.data);
      }
    }
    data = next;
  }
  for (const c of cmds.slice()) { const s = startProcessCommand(c); if (s) cmds.push(s); }
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
  return !(cmd.words[k].match(/[\/\\]/) && last && !/[$\0{}]/.test(last) && !isGit(last));
}

function decide(command, dialect, agent, cwd, loaded) {
  const cmds = commandsOf(command, dialect, cwd);
  const mentionsGit = /(^|[^A-Za-z0-9_-])git(\.exe)?([^A-Za-z0-9_-]|$)/i.test(command);
  const invs = [];
  for (const { cmd, dir } of cmds) {
    invs.push(...invocations(cmd, dir));
    if (opaqueCommand(cmd, mentionsGit)) invs.push({ sub: SUBST, args: [], values: {}, opaque: true, globals: [], dir, text: cmd.text, first: false });
  }
  if (invs.length === 0) return null;
  const clip = (t) => (t.length > 200 ? `${t.slice(0, 200)}...` : t);
  if (loaded.error) {
    return `git-guard: policy file is malformed (${loaded.error}): \`${clip(invs[0].text)}\`. Every git command is denied until it is fixed; ask the user.`;
  }
  // Config by environment (GIT_CONFIG_KEY_n=..., GIT_CONFIG_GLOBAL=..., $env:GIT_CONFIG_... = ...).
  const envConfig = [];
  for (const { cmd } of cmds) {
    cmd.words.forEach((w, k) => {
      const m = /^(?:\$env:)?(GIT_CONFIG(?:_KEY_\d+|_PARAMETERS|_GLOBAL|_SYSTEM)?)(?:=(.*))?$/i.exec(w);
      if (m) envConfig.push({ name: m[1], value: m[2] !== undefined ? m[2] : (cmd.words[k + 1] === '=' ? cmd.words[k + 2] || '' : '') });
    });
  }
  const ctx = { agent, policy: loaded.policy, envConfig };
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
