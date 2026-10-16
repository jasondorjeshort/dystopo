#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// PreToolUse hook for the Bash tool ("onFailure": "block" in .claude/settings.json).
// From the Hatchery base (.claude/hooks/git-guard.js there, spec in its ADR 0002 companion
// "hook specifications", section 1), with Dystopo's exceptions for the wave workflow:
// docs/design/decisions/0021-git-guard-exceptions.md (E1 to E5, A1 and A2 below).
// Test harness: node .claude/hooks/test-hooks.js
//
// Denies destructive git commands for everyone, and limits what subagents may push and commit:
// a subagent pushes only task/* branches (E1) and never commits on main (E2). The main session
// (no agent_type) is the coordinator: it commits and pushes main. A lease-protected force push is
// allowed onto task/* only (E3). A task/* branch, remote or local, may be deleted once GitHub
// shows a merged pull request from it, and never before (E4, E5); the check runs
// `gh pr list --head <branch> --state merged` and fails closed.
// --no-verify and hook-path overrides are denied for everyone (A1, A2): the privacy hooks in
// tools/git-hooks must always run.
//
// Allow: exit 0, no stdout. Deny: exit 0 with permissionDecision "deny" JSON.
// Fails closed: unparseable input or an exception also produces a deny.
'use strict';
const path = require('path');
const { spawnSync } = require('child_process');

// git options that come before the subcommand and take a separate value.
const GLOBAL_OPTS_WITH_VALUE = new Set([
  '-C', '-c', '--git-dir', '--work-tree', '--namespace', '--config-env', '--super-prefix',
]);
// git push options that take a separate value.
const PUSH_OPTS_WITH_VALUE = new Set(['-o', '--push-option', '--repo', '--receive-pack', '--exec']);
const SEPARATORS = new Set(['&&', '||', ';', '|', '&']);
const WHOLE_TREE = new Set(['.', './', '*', ':/']);
const PROTECTED = new Set(['main', 'master']);
const isTaskRef = (ref) => /^task\/\S+$/.test(ref);
const HOOKS_PATH = 'tools/git-hooks';

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
function allow() { process.exit(0); }

// Strip shell quoting and grouping characters from the ends of a token.
function clean(tok) {
  return tok.replace(/^[\s'"`$(){}]+/, '').replace(/[\s'"`(){};]+$/, '');
}
const isGit = (tok) => /(^|[\/\\])git(\.exe)?$/i.test(tok);
const isEnvAssign = (tok) => /^[A-Za-z_][A-Za-z0-9_]*=/.test(tok);
// A single-dash cluster of short options (-uf, -nd), not a long option or a bare "-".
const isShortCluster = (a) => /^-[A-Za-z0-9]+$/.test(a);
const clusterHas = (a, ch) => isShortCluster(a) && a.includes(ch);

// Directory a git invocation runs in: the unit's base directory, then any `cd <dir>` earlier in
// the unit, then git's own -C. Relative paths resolve against the directory before them.
function resolveDir(base, rel) {
  if (!rel) return base;
  const p = rel.replace(/^~(?=$|[\/\\])/, process.env.HOME || process.env.USERPROFILE || '~');
  // Git Bash style /c/Users/... to C:/Users/... so Node on Windows resolves it.
  const win = process.platform === 'win32' ? p.replace(/^\/([a-zA-Z])(\/|$)/, '$1:/') : p;
  return path.resolve(base, win);
}

// Every git invocation in a unit: { sub, args, first, globals, dir }. args stop at a standalone
// separator token; first is true when git is the unit's command (not an argument).
function invocations(unit, baseDir) {
  const raw = unit.split(' ').filter(Boolean);
  const toks = raw.map(clean);
  const out = [];
  for (let i = 0; i < toks.length; i++) {
    if (!isGit(toks[i])) continue;
    const globals = [];
    let cdir = null;
    let j = i + 1;
    while (j < toks.length && toks[j].startsWith('-') && !SEPARATORS.has(raw[j])) {
      if (GLOBAL_OPTS_WITH_VALUE.has(toks[j])) {
        globals.push([toks[j], toks[j + 1] || '']);
        if (toks[j] === '-C') cdir = toks[j + 1] || null;
        j++;
      } else {
        globals.push([toks[j], null]);
      }
      j++;
    }
    if (j >= toks.length || SEPARATORS.has(raw[j])) continue;
    const sub = toks[j];
    const args = [];
    for (let k = j + 1; k < toks.length && !SEPARATORS.has(raw[k]); k++) {
      const rd = /^(\d*|&)(>>?|<)(.*)$/.exec(toks[k]); // shell redirection, not a git argument
      if (rd) { if (rd[3] === '') k++; continue; } // split form "2> file": the target is the next token
      args.push(toks[k]);
    }
    let lead = 0;
    while (lead < i && isEnvAssign(toks[lead])) lead++;
    let dir = baseDir;
    for (let k = 0; k + 1 < i; k++) if (toks[k] === 'cd' && !SEPARATORS.has(raw[k + 1])) dir = resolveDir(dir, toks[k + 1]);
    if (cdir) dir = resolveDir(dir, cdir);
    out.push({ sub, args, first: lead === i, globals, dir });
  }
  return out;
}

function pathspecs(args) {
  const dd = args.indexOf('--');
  return dd >= 0 ? args.slice(dd + 1) : args.filter((a) => !a.startsWith('-'));
}

// Current branch of the repository at dir, or null when it cannot be read (detached HEAD gives
// "HEAD"). Cached per directory; only called by the rules that need it.
const branchCache = new Map();
function currentBranch(dir) {
  if (branchCache.has(dir)) return branchCache.get(dir);
  let b = null;
  try {
    const r = spawnSync('git', ['-C', dir, 'rev-parse', '--abbrev-ref', 'HEAD'], { encoding: 'utf8', timeout: 5000 });
    if (!r.error && r.status === 0) b = r.stdout.trim() || null;
  } catch (_) { b = null; }
  branchCache.set(dir, b);
  return b;
}

// The branches a push updates on the remote: { refs: [...], all: bool, unknown: bool }.
// With no refspec the current branch is pushed (push.default simple or current). A refspec
// src:dst counts by its destination only (HEAD:task/T5 and T5-verify:task/T5 update task/T5); a bare
// name is its own destination. bareHead: a bare HEAD (destination left to git); deletes: a :dst.
function pushTargets(inv) {
  const words = [];
  let all = false;
  for (let k = 0; k < inv.args.length; k++) {
    const a = inv.args[k];
    if (PUSH_OPTS_WITH_VALUE.has(a)) { k++; continue; }
    if (a === '--all' || a === '--branches' || a === '--tags' || a === '--mirror' || a === '--follow-tags') { all = true; continue; }
    if (a.startsWith('-')) continue;
    words.push(a);
  }
  const specs = words.slice(1); // words[0] is the remote
  const refs = [];
  let unknown = false, bareHead = false, deletes = false;
  const here = () => {
    const b = currentBranch(inv.dir);
    if (!b || b === 'HEAD') { unknown = true; return null; }
    return b;
  };
  if (specs.length === 0) {
    const b = here();
    if (b) refs.push(b);
  }
  for (const s of specs) {
    const spec = s.replace(/^\+/, '');
    let dst = spec.includes(':') ? spec.slice(spec.indexOf(':') + 1) : spec;
    if (dst === '') { deletes = true; continue; } // deletion, rule 2
    if (spec === 'HEAD') bareHead = true;
    dst = dst.replace(/^refs\/heads\//, '');
    if (dst === 'HEAD') { const b = here(); if (b) refs.push(b); continue; }
    refs.push(dst);
  }
  return { refs, all, unknown, bareHead, deletes };
}
// Rule 1 is stricter: a bare HEAD or a deletion has no destination it can vouch for.
const onlyExplicitTaskRefs = (t) => !t.bareHead && !t.deletes && onlyTaskRefs(t);
const onlyTaskRefs = (t) => !t.all && !t.unknown && t.refs.length > 0 && t.refs.every(isTaskRef);

// Remote branches a push deletes (--delete args or :ref), as names.
function pushDeletions(inv) {
  const words = inv.args.filter((a) => !a.startsWith('-'));
  const del = inv.args.some((a) => a === '--delete' || a === '-d' || clusterHas(a, 'd'));
  const out = [];
  for (const w of words.slice(1)) {
    if (del) out.push(w.replace(/^:/, '').replace(/^refs\/heads\//, ''));
    else if (w.startsWith(':') && w.length > 1) out.push(w.slice(1).replace(/^refs\/heads\//, ''));
  }
  return out;
}

// True when GitHub shows a merged pull request whose head is this branch. Fails closed (false)
// when gh is missing, offline or not logged in. Test seam: GIT_GUARD_MERGED, a comma-separated
// list of branch names, replaces the gh call (set by test-hooks.js; unset in normal use).
const mergedCache = new Map();
function mergedOnGitHub(branch, dir) {
  if (process.env.GIT_GUARD_MERGED !== undefined) {
    return process.env.GIT_GUARD_MERGED.split(',').map((x) => x.trim()).includes(branch);
  }
  if (mergedCache.has(branch)) return mergedCache.get(branch);
  let merged = false;
  try {
    const r = spawnSync('gh', ['pr', 'list', '--head', branch, '--state', 'merged', '--json', 'number', '--limit', '1'],
      { cwd: dir, encoding: 'utf8', timeout: 20000 });
    if (!r.error && r.status === 0) {
      const list = JSON.parse(r.stdout || '[]');
      merged = Array.isArray(list) && list.length > 0;
    }
  } catch (_) { merged = false; }
  mergedCache.set(branch, merged);
  return merged;
}
const deletable = (names, dir) => names.length > 0 && names.every((b) => isTaskRef(b) && mergedOnGitHub(b, dir));

const isLease = (a) => a.startsWith('--force-with-lease') || a === '--force-if-includes';
const isPlainForce = (a) => (a.startsWith('--force') && !isLease(a)) || a === '-f' || clusterHas(a, 'f') ||
  (a.startsWith('+') && a.length > 1);
const isHooksPathKey = (s) => /^core\.hookspath(=|$)/i.test(s);

// Ordered rules. test(inv, agent) returns true when the invocation must be denied.
const RULES = [
  {
    n: 1, prefix: 'force push',
    advice: 'Never a plain force push. After a rebase, push your own task branch with --force-with-lease and an explicit destination (task/Tnn, or HEAD:task/Tnn from a detached worktree); anything else, ask the coordinator.',
    test: (inv) => {
      if (inv.sub !== 'push') return false;
      if (inv.args.some(isPlainForce)) return true;
      if (!inv.args.some((a) => a.startsWith('--force-with-lease'))) return false;
      return !onlyExplicitTaskRefs(pushTargets(inv)); // E3
    },
  },
  {
    n: 2, prefix: 'remote branch deletion',
    advice: 'Only a task/* branch is deleted, and only after its pull request shows as merged (gh pr list --head <branch> --state merged); a failed or pending merge keeps the branch. Never --mirror or --prune.',
    test: (inv) => {
      if (inv.sub !== 'push') return false;
      if (inv.args.some((a) => a === '--mirror' || a === '--prune')) return true;
      const dels = pushDeletions(inv);
      const hasDelete = dels.length > 0 || inv.args.some((a) => a === '--delete' || a === '-d' || clusterHas(a, 'd'));
      if (!hasDelete) return false;
      return !deletable(dels, inv.dir); // E4
    },
  },
  {
    n: 3, prefix: 'agents push only task/* branches',
    advice: 'Push your own branch by name (git push -u origin task/Tnn); main, tags and other branches are pushed by the coordinator.',
    test: (inv, agent) => inv.sub === 'push' && agent !== null && !onlyTaskRefs(pushTargets(inv)), // E1
  },
  {
    n: 4, prefix: 'agents do not commit on main',
    advice: 'Work in your task worktree on branch task/Tnn (CLAUDE.md, Working in parallel); the coordinator alone commits to main.',
    test: (inv, agent) => {
      if (inv.sub !== 'commit' || agent === null) return false;
      const b = currentBranch(inv.dir);
      return b !== null && PROTECTED.has(b); // E2
    },
  },
  {
    n: 5, prefix: 'destructive reset',
    advice: 'Use git reset --soft or --mixed, or git stash, so the work stays recoverable; ask the owner if a hard reset is needed.',
    test: ({ sub, args }) => sub === 'reset' && args.some((a) => a === '--hard' || a === '--merge'),
  },
  {
    n: 6, prefix: 'git clean deletes untracked files',
    advice: 'Run git clean -n to list what would go, then delete named files or ask the owner.',
    test: ({ sub, args }) => sub === 'clean' &&
      !args.some((a) => a === '-n' || a === '--dry-run' || clusterHas(a, 'n')),
  },
  {
    n: 7, prefix: 'whole-tree checkout discards edits',
    advice: 'Name the files to discard explicitly, or ask the owner.',
    test: ({ sub, args }) => {
      if (sub !== 'checkout') return false;
      const dd = args.indexOf('--');
      const specs = dd >= 0 ? args.slice(dd + 1) : args.slice(-1);
      return specs.some((a) => WHOLE_TREE.has(a));
    },
  },
  {
    n: 8, prefix: 'whole-tree restore discards edits',
    advice: 'Name the files to restore explicitly, or use git restore --staged to unstage only.',
    test: ({ sub, args }) => {
      if (sub !== 'restore') return false;
      if (!pathspecs(args).some((a) => WHOLE_TREE.has(a))) return false;
      const staged = args.some((a) => a === '--staged' || clusterHas(a, 'S'));
      const worktree = args.some((a) => a === '--worktree' || clusterHas(a, 'W'));
      return !(staged && !worktree);
    },
  },
  {
    n: 9, prefix: 'forced branch delete',
    advice: 'Use git branch -d, which refuses to delete unmerged work. A squash-merged task/* branch may be force-deleted once its pull request shows as merged (gh pr list --head <branch> --state merged).',
    test: ({ sub, args, dir }) => {
      if (sub !== 'branch') return false;
      const forced = args.some((a) => clusterHas(a, 'D')) || (
        args.some((a) => a === '--delete' || clusterHas(a, 'd')) &&
        args.some((a) => a === '--force' || clusterHas(a, 'f')));
      if (!forced) return false;
      return !deletable(args.filter((a) => !a.startsWith('-')), dir); // E5
    },
  },
  {
    n: 10, prefix: 'interactive rebase is unsupported here',
    advice: 'Interactive commands cannot run in this environment; use a non-interactive rebase or ask the owner.',
    test: ({ sub, args }) => sub === 'rebase' &&
      args.some((a) => a === '-i' || a === '--interactive' || a.startsWith('--interactive=')),
  },
  {
    n: 11, prefix: 'stash drop/clear is unrecoverable',
    advice: 'Use git stash pop or git stash apply, or ask the owner before discarding a stash.',
    test: ({ sub, args }) => {
      if (sub !== 'stash') return false;
      const action = args.find((a) => !a.startsWith('-'));
      return action === 'drop' || action === 'clear';
    },
  },
  {
    n: 12, prefix: 'history rewrite',
    advice: 'History rewrites are for the owner; ask first.',
    test: ({ sub }) => sub === 'filter-branch' || sub === 'filter-repo' || sub === 'replace',
  },
  {
    n: 13, prefix: 'hooks bypass',
    advice: 'Never --no-verify (CLAUDE.md, Rules). If the privacy or commit-msg hook fires, fix the content.',
    test: ({ sub, args }) => {
      if (args.includes('--no-verify')) return ['commit', 'push', 'merge', 'rebase', 'am', 'cherry-pick', 'revert'].includes(sub); // A1
      return sub === 'commit' && args.some((a) => clusterHas(a, 'n')); // git commit -n is --no-verify
    },
  },
  {
    n: 14, prefix: 'hooks path override',
    advice: `core.hooksPath stays ${HOOKS_PATH} (CLAUDE.md, Rules); the privacy hooks must run on every commit.`,
    test: ({ sub, args, globals }) => {
      // A2: git -c core.hooksPath=... <anything>
      if (globals.some(([o, v]) => o === '-c' && v !== null && isHooksPathKey(v))) return true;
      if (sub !== 'config') return false;
      const i = args.findIndex((a) => isHooksPathKey(a));
      if (i < 0) return false;
      if (args.some((a) => a === '--unset' || a === '--unset-all' || a === '--remove-section')) return true;
      const value = args[i].includes('=') ? args[i].slice(args[i].indexOf('=') + 1) : args[i + 1];
      if (value === undefined) return false; // a read: git config core.hooksPath
      return value.replace(/\\/g, '/').replace(/\/$/, '') !== HOOKS_PATH;
    },
  },
];

const FALSE_POSITIVE_NOTE = ' The guard matches command text, so it also fires when a git command is only quoted (echo, a message); if nothing here runs git, rephrase the text or write it with the Write tool.';

function decide(command, agent, cwd) {
  const norm = command.replace(/\r/g, '').replace(/[\t ]+/g, ' ');
  const segments = norm.split(/&&|\|\||;|\||\n/).map((s) => s.trim()).filter(Boolean);
  const whole = norm.replace(/\n/g, ' ; ').trim();
  // Segments first so the reported text is as narrow as possible. A segment that is a cd moves
  // the base directory of the segments after it.
  const units = [];
  let dir = cwd;
  for (const s of segments) {
    units.push({ text: s, invs: invocations(s, dir) });
    const m = /^cd\s+(\S+)$/.exec(s);
    if (m) dir = resolveDir(dir, clean(m[1]));
  }
  units.push({ text: whole, invs: invocations(whole, cwd) });
  for (const rule of RULES) {
    for (const unit of units) {
      for (const inv of unit.invs) {
        if (rule.test(inv, agent)) {
          let reason = `git-guard: ${rule.prefix}: \`${unit.text}\`. ${rule.advice}`;
          if (!inv.first) reason += FALSE_POSITIVE_NOTE;
          return { rule: rule.n, reason };
        }
      }
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
    if (typeof command !== 'string') return allow();
    const agent = typeof input.agent_type === 'string' && input.agent_type !== '' ? input.agent_type : null;
    const cwd = typeof input.cwd === 'string' && input.cwd ? input.cwd : process.cwd();
    verdict = decide(command, agent, cwd);
  } catch (e) {
    const msg = e && e.message ? e.message : String(e);
    return deny(`git-guard: internal error (${msg}); command not run. Fix the hook or ask the owner to run it.`);
  }
  if (verdict) return deny(verdict.reason);
  allow();
});
