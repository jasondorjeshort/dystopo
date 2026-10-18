#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// Test harness and debug interface for the hooks in this directory.
// Spec: docs/design/decisions/0002-hook-specs.md for the hooks; the harness, its seams and the
// fitness group F: docs/design/decisions/0002-hook-specs-usage.md, with ADR 0013 and ADR 0012's
// fitness contract. Each case runs a hook as a child process with JSON on stdin, exactly as
// Claude Code does, and checks exit code and stdout.
//
// Usage:
//   node .claude/hooks/test-hooks.js                  run every case; exit 1 on any failure
//   node .claude/hooks/test-hooks.js --only G0        run cases whose id starts with G0
//   node .claude/hooks/test-hooks.js --verbose        also print each hook's raw stdout
//   node .claude/hooks/test-hooks.js --keep           keep the scratch docs tree (path printed)
//   node .claude/hooks/test-hooks.js --input <file> <git-guard|post-edit|usage-gate|statusline>
//       run one hook (or the status line, ../statusline.js) on the raw contents of <file> and
//       print exit code, stdout, stderr. Use this to reproduce a bug: write the hook input JSON
//       to a file with the Write tool (a command line containing e.g. a force push would itself
//       trip git-guard).
//   ... --input <file> <usage-gate|statusline> --home <dir> [--config-dir <cdir>]
//       the same, with HOME and USERPROFILE set to <dir> and CLAUDE_CONFIG_DIR removed (or set
//       to <cdir>), so the script uses <dir>/.claude/usage-state/<session_id>.json, the grants
//       in <dir>/.claude/usage-gate-overrides/, and the account label from <dir>/.claude.json
//       (or <cdir>/.claude.json) instead of the real ones. Put session_id (and cwd, to test a
//       grant's scope) in <file>; the gate reads the file the status line wrote for that id.
//   node .claude/hooks/test-hooks.js --only deps [--verbose]
//       run only the fitness check deps on this repository (case F1; ADR 0013, and ADR 0012's
//       fitness contract); --verbose lists every file scanned and how each require was classed.
//       --only F runs F1 and the cases that test the check on scratch trees. An --only that
//       selects no case is a usage error (exit 2).
//
// Isolation: post-edit cases run against a scratch tree in the OS temp dir (deleted at
// the end); git-guard cases run in two scratch repositories (one on main, one on task/T1);
// usage-gate and statusline cases point HOME/USERPROFILE at a scratch dir and remove
// CLAUDE_CONFIG_DIR unless the case sets it to a scratch dir. Every case names
// its policy file through HOOK_POLICY_FILE: the Hatchery fixture, an example, none, or a scratch
// file. No file of the repository is read except to check that policy.json loads, in F1 the
// files .claude/hooks/fitness.json names, in O1 the tracked paths against ownership.json and in A1
// the files under .claude/agreement/ (both skipped where a hatchery remote exists, ADR 0016),
// policy.js and imports.js, copied into the O cases' scratch repositories, and in groups H, HS and SL
// .claude/settings.json, whose every command is run from a subfolder, and .claude/statusline.js;
// ~/.claude is never touched. Group GI covers git.integrate (pull, merge and rebase apart from commit).
// Groups IM (imports.js), CL (policy.js --claude-check) and O (ownership.js) cover ADR 0016's code;
// group Q covers queue-list.js and post-edit's marker switch (ADR 0017), on scratch trees, with a
// scratch copy of the hooks whose queue-list.js throws (Q13); group PV covers privacy-scan.js, rule
// 20 and the pre-push hook (ADR 0018) on scratch repositories with bare remotes, every guard run
// with HOME at an empty scratch dir (or the case's own, for the never and allow files); the line
// above the totals lists the groups that ran. Exit codes: 0 all pass (a
// SKIP is not a failure), 1 any failure, 2 usage error.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const HOOKS = __dirname;
const NODE = process.execPath;

// ---------- CLI ----------
const argv = process.argv.slice(2);
const opt = { only: null, verbose: false, keep: false, input: null, hook: null, home: null, configDir: null };
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--verbose') opt.verbose = true;
  else if (a === '--keep') opt.keep = true;
  else if (a === '--only' && argv[i + 1]) opt.only = argv[++i];
  else if (a === '--input' && argv[i + 1] && argv[i + 2]) { opt.input = argv[++i]; opt.hook = argv[++i]; }
  else if (a === '--home' && argv[i + 1]) opt.home = path.resolve(argv[++i]);
  else if (a === '--config-dir' && argv[i + 1]) opt.configDir = path.resolve(argv[++i]);
  else { process.stderr.write(`test-hooks: unknown or incomplete argument: ${a}\n`); process.exit(2); }
}

function run(hook, stdin, env) {
  const script = hook === 'statusline' ? path.join(HOOKS, '..', 'statusline.js') : path.join(HOOKS, `${hook}.js`);
  const r = spawnSync(NODE, [script], {
    input: stdin, encoding: 'utf8', timeout: 30000, env: env || process.env,
  });
  return { code: r.status, stdout: r.stdout || '', stderr: r.stderr || '', error: r.error };
}

if (opt.input) {
  if (!['git-guard', 'post-edit', 'usage-gate', 'statusline'].includes(opt.hook)) {
    process.stderr.write(`test-hooks: unknown hook ${opt.hook}\n`);
    process.exit(2);
  }
  let env = process.env;
  if (opt.home || opt.configDir) {
    env = Object.assign({}, process.env);
    if (opt.home) Object.assign(env, { HOME: opt.home, USERPROFILE: opt.home });
    if (opt.configDir) env.CLAUDE_CONFIG_DIR = opt.configDir; else delete env.CLAUDE_CONFIG_DIR;
  }
  const r = run(opt.hook, fs.readFileSync(opt.input, 'utf8'), env);
  process.stdout.write(`exit ${r.code}\nstdout: ${r.stdout}\nstderr: ${r.stderr}\n`);
  process.exit(0);
}

// ---------- result bookkeeping ----------
let pass = 0;
let fail = 0;
let skipped = 0;
// The case groups that ran (an id's leading letters), listed above the totals.
const groups = new Set();
const noteGroup = (id) => groups.add(id.replace(/\d.*$/, ''));
function report(id, label, ok, detail, stdout) {
  if (opt.only && !id.startsWith(opt.only)) return;
  noteGroup(id);
  if (ok) pass++; else fail++;
  process.stdout.write(`${ok ? 'PASS' : 'FAIL'} ${id} ${label}\n`);
  if (!ok) process.stdout.write(`     ${detail}\n`);
  if ((opt.verbose || !ok) && stdout !== undefined) process.stdout.write(`     stdout: ${stdout || '(empty)'}\n`);
}
const selected = (id) => !opt.only || id.startsWith(opt.only);

function parseDecision(stdout) {
  try {
    const o = JSON.parse(stdout);
    const h = o.hookSpecificOutput || {};
    return { event: h.hookEventName, decision: h.permissionDecision, reason: h.permissionDecisionReason, context: h.additionalContext };
  } catch (_) { return null; }
}

const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'hooks-test-'));
const fwd = (p) => p.split(path.sep).join('/');

// ---------- policies ----------
// Every hook case names the policy file it runs under (HOOK_POLICY_FILE): the Hatchery fixture
// below, the two examples, none at all (the defaults), or a scratch file written for the case. The
// repository's own policy.json is never a fixture, so the suite passes unchanged in a project that
// copied it and set its own policy; LIVE01 only checks that the project's file loads.
const HATCHERY_POLICY = {
  git: {
    commit: { subagents: ['runner'], protectedBranches: [] },
    push: { subagents: ['pusher'], branches: ['*'], mainSession: true, forceWithLease: [] },
    branchDelete: { branches: [], requireMergedPr: false },
    hooksPath: null,
  },
  usage: { hold: 50, deny: null, staleSeconds: 7200, whenStale: 'allow' },
  edit: { capExempt: [], anchorExempt: ['docs/design/proposals/*', 'docs/design/decisions/*', 'docs/findings/*', 'docs/hatchery/*'], formatters: {} },
  privacy: { allow: ['user'], identities: [] },
};
fs.writeFileSync(path.join(scratch, 'policy-hatchery.json'), JSON.stringify(HATCHERY_POLICY));
const POLICIES = {
  hatchery: path.join(scratch, 'policy-hatchery.json'),
  taskBranches: path.join(HOOKS, 'policy.examples', 'task-branches.json'),
  singleBranch: path.join(HOOKS, 'policy.examples', 'single-branch.json'),
  perMeter: path.join(HOOKS, 'policy.examples', 'per-meter.json'),
  none: path.join(scratch, 'no-such-policy.json'),
};
function scratchPolicy(name, obj) {
  const f = path.join(scratch, `policy-${name}.json`);
  fs.writeFileSync(f, typeof obj === 'string' ? obj : JSON.stringify(obj));
  return f;
}
const envWith = (extra) => {
  const env = Object.assign({}, process.env, extra);
  for (const k of Object.keys(env)) if (env[k] === undefined) delete env[k];
  return env;
};

// ---------- scratch git repositories (git-guard reads the current branch) ----------
function git(dir, ...args) {
  const r = spawnSync('git', ['-C', dir, '-c', 'user.name=hooks-test', '-c', 'user.email=hooks-test@example.invalid',
    '-c', 'commit.gpgsign=false', ...args], { encoding: 'utf8' });
  if (r.status !== 0) { process.stderr.write(`test-hooks: git ${args.join(' ')} failed: ${r.stderr}\n`); process.exit(2); }
}
const repoMain = path.join(scratch, 'repo-main');
const repoTask = path.join(scratch, 'repo-task');
for (const [dir, branch] of [[repoMain, 'main'], [repoTask, 'task/T1']]) {
  fs.mkdirSync(dir, { recursive: true });
  git(dir, 'init', '-q');
  git(dir, 'checkout', '-q', '-b', branch);
  git(dir, 'commit', '-q', '--allow-empty', '-m', 'init');
  git(dir, 'tag', 'v1.0'); // GP16 pushes it; rule 20 reads what a push sends, so it must exist
  git(dir, 'remote', 'add', 'origin', `${dir}-origin.git`); // rule 20 denies a push to a remote that is not configured
}
// HOME for every guard run: empty, so rule 20 reads no never or allow file of the machine.
const guardHome = path.join(scratch, 'guard-home');
fs.mkdirSync(guardHome, { recursive: true });

// ---------- git-guard ----------
const PREFIX = {
  1: 'force push',
  2: 'remote branch deletion',
  3: 'push not permitted for this caller',
  4: 'commit not permitted for this caller',
  5: 'destructive reset',
  6: 'git clean deletes untracked files',
  7: 'whole-tree checkout discards edits',
  8: 'whole-tree restore discards edits',
  9: 'forced branch delete',
  10: 'interactive rebase is unsupported here',
  11: 'stash drop/clear is unrecoverable',
  12: 'history rewrite',
  13: 'hooks bypass',
  14: 'hooks path override',
  15: 'git alias definition',
  16: 'forced checkout discards edits',
  17: 'forced worktree removal',
  18: 'forced branch reset',
  19: 'unexpanded argument',
  20: 'personal data in the outgoing commits',
  policy: 'policy file is malformed (',
};
const NOTE_MARK = 'git-guard reads the command line, not what runs';
const withNote = (d) => d.reason.includes(NOTE_MARK) || 'reason lacks the note about command text';
const noNote = (d) => !d.reason.includes(NOTE_MARK) || 'reason carries the note, but git is the command here';

// Runs git-guard once. o: { command, agent, tool, cwd, policy, raw, merged, home }. expect: 'allow',
// 'internal' (exit 2, reason on stderr), a rule number, or 'policy' (malformed policy deny). HOME
// is o.home (a PV case's never and allow files) or the empty guardHome.
function guardCase(id, o, expect, extra) {
  if (!selected(id)) return;
  const input = { hook_event_name: 'PreToolUse', tool_name: o.tool || 'Bash', cwd: o.cwd || repoMain, tool_input: { command: o.command } };
  if (o.agent !== null && o.agent !== undefined) input.agent_type = o.agent;
  const stdin = o.raw !== undefined && o.raw !== null ? o.raw : JSON.stringify(input);
  // task/T1 has a merged pull request, task/T2 does not (git-guard's GIT_GUARD_MERGED seam).
  const home = o.home || guardHome;
  const r = run('git-guard', stdin, envWith({ HOOK_POLICY_FILE: o.policy || POLICIES.hatchery, GIT_GUARD_MERGED: 'task/T1', HOME: home, USERPROFILE: home }));
  const shown = JSON.stringify(String(o.command).split(fwd(scratch)).join('<scratch>'));
  const where = `${o.tool === 'PowerShell' ? ' ps' : ''}${o.cwd === repoTask ? ' on task/T1' : ''}${o.policyName ? ` (${o.policyName})` : ''}`;
  const label = `${shown} [${o.agent == null ? '-' : JSON.stringify(o.agent)}]${where} -> ${typeof expect === 'number' ? `deny rule ${expect}` : expect}`;
  let ok = false;
  let detail = '';
  if (expect === 'internal') {
    // The guard's own failure exits 2 with the reason on stderr, which blocks even without onFailure.
    ok = r.code === 2 && r.stdout === '' && r.stderr.startsWith('git-guard: internal error (');
    detail = `expected exit 2 and an internal-error reason on stderr; got exit ${r.code}, stderr ${JSON.stringify(r.stderr)}`;
  } else if (r.code !== 0) detail = `exit ${r.code}, expected 0; stderr: ${r.stderr.trim()}`;
  else if (expect === 'allow') { ok = r.stdout === ''; detail = 'expected no stdout'; }
  else {
    const d = parseDecision(r.stdout);
    if (!d || d.event !== 'PreToolUse' || d.decision !== 'deny' || typeof d.reason !== 'string') detail = 'expected deny JSON';
    else {
      const want = expect === 'policy' ? `git-guard: ${PREFIX.policy}` : `git-guard: ${PREFIX[expect]}: \``;
      ok = d.reason.startsWith(want);
      detail = `reason should start with ${JSON.stringify(want)}; got ${JSON.stringify(d.reason)}`;
      if (ok && extra) { const e = extra(d); if (e !== true) { ok = false; detail = e; } }
    }
  }
  report(id, label, ok, detail, r.stdout);
}

// [id, command, agent_type or null, expect: 'allow' | rule number | 'internal', raw stdin override, extra check]
const G = [
  // The 25-row table from the spec, in order.
  ['G01', 'git status', null, 'allow'],
  ['G02', 'git push', null, 'allow'],
  ['G03', 'git push', 'pusher', 'allow'],
  ['G04', 'git push', 'runner', 3],
  ['G05', 'git push origin main --force', 'pusher', 1],
  ['G06', 'git push -uf origin main', null, 1],
  ['G07', 'git push origin :old', null, 2],
  ['G08', 'git commit -m x', 'coder', 4],
  ['G09', 'git commit -m x', 'runner', 'allow'],
  ['G10', 'git commit -m x', null, 'allow'],
  ['G11', 'cd sub && git reset --hard HEAD~1', null, 5],
  ['G12', 'git reset --soft HEAD~1', null, 'allow'],
  ['G13', 'git clean -fd', null, 6],
  ['G14', 'git clean -n', null, 'allow'],
  ['G15', 'git checkout -- .', null, 7],
  ['G16', 'git checkout -- docs/HANDOFF.md', null, 'allow'],
  ['G17', 'git restore --staged .', null, 'allow'],
  ['G18', 'git restore .', null, 8],
  ['G19', 'git branch -D topic', null, 9],
  ['G20', 'git branch -d topic', null, 'allow'],
  ['G21', 'git rebase -i HEAD~3', null, 10],
  ['G22', 'git stash list', null, 'allow'],
  ['G23', 'git stash drop', null, 11],
  ['G24', 'echo "git push --force"', null, 'allow'], // quoted text is data since the quote-aware parser
  ['G25', 'not json', null, 'internal', 'not json'],
  // Extra cases: implementation choices and edge cases beyond the spec table.
  ['GX01', 'git push origin +main', null, 1],
  ['GX02', 'git push --force-with-lease', 'pusher', 1],
  ['GX03', 'git -C sub push --force', null, 1],
  ['GX04', "git push origin 'a|b' -f", null, 1],
  ['GX05', 'git status\ngit push -f', null, 1],
  ['GX06', 'git push --prune origin', null, 2],
  ['GX07', 'git push origin --delete old', 'pusher', 2],
  ['GX08', 'git commit -m "fix; then push"', 'coder', 4],
  ['GX09', 'git log --grep commit', 'coder', 'allow'],
  ['GX10', 'git push origin main && echo --force', null, 'allow'],
  ['GX11', 'git reset --merge', null, 5],
  ['GX12', 'git clean -nfd', null, 'allow'],
  ['GX13', 'git checkout .', null, 7],
  ['GX14', 'git checkout main', null, 'allow'],
  ['GX15', 'git restore --staged --worktree .', null, 8],
  ['GX16', 'git restore -- :/', null, 8],
  ['GX17', 'git branch --delete --force topic', null, 9],
  ['GX18', 'git rebase --interactive main', null, 10],
  ['GX19', 'git stash clear', null, 11],
  ['GX20', 'git filter-branch --tree-filter x HEAD', null, 12],
  ['GX21', 'git push', '', 'allow'],
  ['GX22', 'git status', 'coder', 'allow'],
  ['GX23', 'ls .git', 'coder', 'allow'],
  ['GX24', '(missing tool_input.command)', null, 'allow', JSON.stringify({ tool_name: 'Bash', tool_input: {} })],
  ['GX25', '(empty stdin)', null, 'internal', ''],
];

for (const [id, command, agent, expect, raw, extra] of G) guardCase(id, { command, agent, raw }, expect, extra);

// New rules (13 to 17, and the additions to 12), under the Hatchery policy.
// [id, command, agent, expect, extra]
const GN = [
  ['GN01', 'git commit --no-verify -m x', null, 13],
  ['GN02', 'git commit -nm x', 'runner', 13],
  ['GN03', 'git push --no-verify', 'pusher', 13],
  ['GN04', 'git merge --no-verify topic', null, 13],
  ['GN05', 'git push -n', 'pusher', 'allow'], // push -n is --dry-run, not --no-verify
  ['GN06', 'git -c core.hooksPath=/dev/null commit -m x', null, 14],
  ['GN07', 'git --config-env=core.hooksPath=HP commit -m x', null, 14],
  ['GN08', 'git config --unset core.hooksPath', null, 14],
  ['GN09', 'git config core.hooksPath .git/hooks', null, 14],
  ['GN10', 'git config core.hooksPath', 'runner', 'allow'], // a read
  ['GN11', 'GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.hooksPath GIT_CONFIG_VALUE_0=x git commit -m x', null, 14],
  ['GN12', 'git -c alias.p=\'!git push -f\' p', null, 15],
  ['GN13', 'git config --global alias.p "push --force"', null, 15],
  ['GN14', 'git config --get alias.st', null, 'allow'],
  ['GN15', 'git checkout -f main', null, 16],
  ['GN16', 'git checkout --force -- src', null, 16],
  ['GN17', 'git switch --discard-changes main', null, 16],
  ['GN18', 'git switch -f main', null, 16],
  ['GN19', 'git switch main', null, 'allow'],
  ['GN20', 'git worktree remove --force ../wt', null, 17],
  ['GN21', 'git worktree remove -f ../wt', null, 17],
  ['GN22', 'git worktree remove ../wt', null, 'allow'],
  ['GN23', 'git update-ref -d refs/heads/topic', null, 12],
  ['GN24', 'git reflog expire --expire=now --all', null, 12],
  ['GN25', 'git reflog show', null, 'allow'],
  ['GN26', 'git push --force-with-lease origin main', null, 1], // lease is force unless the policy names branches
  ['GN27', 'git -C . -c x.y=z --no-pager push --force', null, 1],
  ['GN28', 'git --git-dir=.git push origin +main', null, 1],
];
for (const [id, command, agent, expect, extra] of GN) guardCase(id, { command, agent }, expect, extra);

// Quote-aware parsing: quoted text and heredoc bodies are data unless handed to a shell.
// [id, command, tool, agent, expect, extra]
const HEREDOC_MSG = "git commit -m \"$(cat <<'EOF'\nDon't git push --force (yet); git reset --hard\nEOF\n)\"";
const GQ = [
  ['GQ01', 'git commit -m "fix git push -n and --no-verify"', 'Bash', 'runner', 'allow'],
  ['GQ02', HEREDOC_MSG, 'Bash', null, 'allow'],
  ['GQ03', 'git commit -m "-n"', 'Bash', null, 'allow'],
  ['GQ04', 'git commit -mnote', 'Bash', null, 'allow'], // -m with its value attached
  ['GQ05', 'git commit -m x -n', 'Bash', null, 13, noNote],
  ['GQ06', "echo 'git reset --hard' > notes.txt", 'Bash', null, 'allow'],
  ['GQ07', 'cat > notes.md <<EOF\ngit push --force\nEOF', 'Bash', null, 'allow'],
  ['GQ08', 'git status # then git push --force', 'Bash', null, 'allow'],
  ['GQ09', 'echo git push -f', 'Bash', null, 1, withNote], // unquoted: still read as a command
  ['GQ10', 'bash -c "git reset --hard"', 'Bash', null, 5, withNote],
  ['GQ11', 'echo "git push -f" | sh', 'Bash', null, 1, withNote],
  ['GQ12', 'bash <<EOF\ngit clean -fdx\nEOF', 'Bash', null, 6, withNote],
  ['GQ13', 'x=$(git push -f)', 'Bash', null, 1],
  ['GQ14', 'echo `git stash drop`', 'Bash', null, 11],
  ['GQ15', 'cat <<EOF\n$(git reset --hard)\nEOF', 'Bash', null, 5], // an unquoted heredoc expands $(...)
  ['GQ16', 'cat <<\'EOF\'\n$(git reset --hard)\nEOF', 'Bash', null, 'allow'],
  ['GQ17', '{ git push --force; }', 'Bash', null, 1, noNote],
  ['GQ18', 'git push "--force"', 'Bash', null, 1],
  ['GQ19', 'git status 2>&1 && git push --force > out.txt', 'Bash', null, 1],
  ['GQ20', "git commit -m 'it''s git push -f'", 'PowerShell', null, 'allow'],
  ['GQ21', "& 'C:\\Program Files\\Git\\cmd\\git.exe' push --force", 'PowerShell', null, 1],
  ['GQ22', "git commit -m @'\ngit push --force\n'@", 'PowerShell', null, 'allow'],
  ['GQ23', 'Invoke-Command { git push -f } | Out-Null', 'PowerShell', null, 1],
  ['GQ24', 'powershell -NoProfile -Command "git reset --hard"', 'PowerShell', null, 5, withNote],
  ['GQ25', "$env:GIT_CONFIG_KEY_0 = 'core.hooksPath'; git commit -m x", 'PowerShell', null, 14],
  ['GQ26', 'git status; git push --force 2>&1', 'PowerShell', null, 1],
  ['GQ27', 'Write-Output "git push --force"', 'PowerShell', null, 'allow'],
  ['GQ28', 'git commit -m "$(git rev-parse HEAD) fix"', 'PowerShell', 'coder', 4],
  // A path ending in /git is git only in command position; as an argument it is a file (ADR 0018 review).
  ['GQ29', 'cat some/dir/git', 'Bash', 'coder', 'allow'],
  ['GQ30', 'ls -la "$R/.claude/hooks/git" "$X"', 'Bash', 'coder', 'allow'],
  ['GQ31', 'sudo /usr/bin/git push -f', 'Bash', null, 1],
  ['GQ32', "& 'C:\\Program Files\\Git\\cmd\\git.exe' reset --hard", 'PowerShell', null, 5],
  // ...and is git wherever it stands when a subcommand follows (review of 5f82800).
  ['GQ33', 'timeout 10 /usr/bin/git push --force origin main', 'Bash', null, 1],
  ['GQ34', 'nice -n 5 /usr/bin/git push -f', 'Bash', null, 1],
  ['GQ35', 'sudo -u root /usr/bin/git reset --hard', 'Bash', null, 5],
  ['GQ36', 'stdbuf -oL /usr/bin/git clean -fdx', 'Bash', null, 6],
  ['GQ37', 'watch -n 1 /usr/bin/git reset --hard', 'Bash', null, 5],
  ['GQ38', 'find . -maxdepth 0 -exec /usr/bin/git reset --hard \\;', 'Bash', null, 5],
  ['GQ39', '"$R/.claude/hooks/git" "$X"', 'Bash', 'coder', 'allow'],
  // One push handed to a shell is parsed in both dialects but is one git command, not a shared line.
  ['GQ40', 'bash -c "git push origin main"', 'Bash', null, 'allow'],
];
for (const [id, command, tool, agent, expect, extra] of GQ) guardCase(id, { command, tool, agent }, expect, extra);

// Shapes an independent review reproduced as bypasses (2026-10-13), and the cases it found
// untested. [id, command, tool, agent, expect, cwd, policy]
const mainNoPush = scratchPolicy('main-no-push', { git: { push: { mainSession: false } } });
const GR = [
  // Abbreviated long options read as the options they start.
  ['GR01', 'git reset --har HEAD~1', 'Bash', null, 5],
  ['GR02', 'git checkout --forc main', 'Bash', null, 16],
  ['GR03', 'git push --mirr origin', 'Bash', null, 2],
  ['GR04', 'git push --delet origin x', 'Bash', null, 2],
  ['GR05', 'git commit --no-verif -m x', 'Bash', null, 13],
  ['GR06', 'git switch --discard main', 'Bash', null, 16],
  ['GR07', 'git worktree remove --forc wt', 'Bash', null, 17],
  ['GR08', 'git clean --dry', 'Bash', null, 'allow'], // --dry is --dry-run
  ['GR09', 'git push --force-if-includes', 'Bash', 'pusher', 1],
  // Arguments the reader cannot see, on a subcommand that can do harm.
  ['GR10', 'X=--force; git push $X', 'Bash', null, 19],
  ['GR11', 'git push {-f,}', 'Bash', null, 19],
  ['GR12', 'git push ${F:---force}', 'Bash', null, 19],
  ['GR13', 'git push $(echo --force)', 'Bash', null, 19],
  ['GR14', 'G=git; $G push -f', 'Bash', null, 19],
  ['GR15', 'echo --force | xargs git push', 'Bash', null, 19],
  ['GR16', 'git checkout "$BRANCH"', 'Bash', null, 19],
  ['GR17', 'git reset --soft $(git commit-tree HEAD^{tree} -p HEAD -m x)', 'Bash', null, 19],
  ['GR18', 'git log --oneline $(git merge-base main HEAD)..HEAD', 'Bash', null, 'allow'],
  ['GR19', 'git commit -m "add $f" --file="$TMP"', 'Bash', 'runner', 'allow'], // values of options
  ['GR20', '$(git rev-parse --show-toplevel)/scripts/check.sh', 'Bash', null, 'allow'],
  ['GR21', 'git -C "$REPO" status && git diff {a,b}.txt', 'Bash', null, 'allow'],
  ['GR22', '$g="git"; & $g push -f', 'PowerShell', null, 19],
  ['GR23', '$a="push","-f"; git @a', 'PowerShell', null, 19],
  ['GR24', '$h = git rev-parse HEAD', 'PowerShell', null, 'allow'],
  // PowerShell launchers.
  ['GR25', 'Start-Process git -ArgumentList "push --force"', 'PowerShell', null, 1],
  ['GR26', 'Start-Process git -ArgumentList "push","--force"', 'PowerShell', null, 1],
  ['GR27', 'Start-Process -FilePath git -ArgumentList $rest', 'PowerShell', null, 19],
  ['GR28', '& ([scriptblock]::Create("git push -f"))', 'PowerShell', null, 1],
  ['GR29', 'iex "git reset --hard"', 'PowerShell', null, 5],
  ['GR30', 'cmd /c "git push -f"', 'PowerShell', null, 1],
  ['GR31', 'cmd /c git push -f', 'Bash', null, 1],
  ['GR32', 'echo "git push -f" | xargs -I{} sh -c "{}"', 'Bash', null, 1],
  ['GR33', 'sudo -u x bash -c "git reset --hard"', 'Bash', null, 5],
  ['GR34', "t 'git -C x push' '' PowerShell", 'Bash', 'coder', 'allow'], // a runner name as an argument runs nothing
  ['GR35', 'node -e "require(\'child_process\').execSync(\'git push -f\')"', 'Bash', null, 'allow'], // not covered, by design
  // Every command that makes a commit is rule 4's.
  ['GR36', 'git merge --no-ff feature', 'Bash', 'coder', 4],
  ['GR37', 'git cherry-pick abc', 'Bash', 'coder', 4],
  ['GR38', 'git revert HEAD', 'Bash', 'coder', 4],
  ['GR39', 'git am patch.mbox', 'Bash', 'coder', 4],
  ['GR40', 'git rebase main', 'Bash', 'coder', 4],
  ['GR41', 'git pull', 'Bash', 'coder', 4],
  ['GR42', 'git rebase --abort', 'Bash', 'coder', 'allow'],
  ['GR43', 'git merge feature', 'Bash', 'general-purpose', 'allow', repoMain, POLICIES.taskBranches], // the example integrates on main (git.integrate)
  ['GR44', 'git rebase origin/main', 'Bash', 'general-purpose', 'allow', repoTask, POLICIES.taskBranches],
  // Config that redirects hooks by another route.
  ['GR45', 'GIT_CONFIG_GLOBAL=/tmp/evil git commit -m x', 'Bash', null, 14],
  ['GR46', 'GIT_CONFIG_SYSTEM=/tmp/evil git status', 'Bash', null, 14],
  ['GR47', "GIT_CONFIG_PARAMETERS=\"'core.hooksPath'='x'\" git commit -m x", 'Bash', null, 14],
  ['GR48', 'git -c include.path=/tmp/evil commit -m x', 'Bash', null, 14],
  ['GR49', 'git config include.path /tmp/evil', 'Bash', null, 14],
  ['GR50', 'git config includeIf.gitdir:~/w/.path /tmp/evil', 'Bash', null, 14],
  ['GR51', "$env:GIT_CONFIG_GLOBAL = 'x'; git status", 'PowerShell', null, 14],
  ['GR52', 'git config --get include.path', 'Bash', null, 'allow'],
  // Forced branch resets, send-pack, and whole-tree spellings.
  ['GR53', 'git checkout -B main HEAD~3', 'Bash', null, 18], // main is the current branch
  ['GR54', 'git switch -C main HEAD~3', 'Bash', null, 18],
  ['GR55', 'git branch -f main HEAD~3', 'Bash', null, 'allow', repoTask], // not current, not protected
  ['GR56', 'git branch -f main HEAD~3', 'Bash', null, 18, repoTask, POLICIES.taskBranches],
  ['GR57', 'git checkout -B topic origin/topic', 'Bash', null, 'allow'],
  ['GR58', 'git send-pack --force origin main', 'Bash', null, 1],
  ['GR59', 'git checkout -- ./.', 'Bash', null, 7],
  ['GR60', 'git checkout -- src/..', 'Bash', null, 7],
  ['GR61', 'git restore -- ./docs/..', 'Bash', null, 8],
  ['GR62', 'git checkout -- ./src', 'Bash', null, 'allow'],
  // Untested before.
  ['GR63', 'git push', 'Bash', null, 3, null, mainNoPush],
  ['GR64', 'git reflog delete HEAD@{1}', 'Bash', null, 12],
];
for (const [id, command, tool, agent, expect, cwd, policy] of GR) {
  guardCase(id, { command, tool, agent, cwd: cwd || undefined, policy: policy || undefined }, expect);
}
// A reassembled Start-Process command carries the note: git ran from text, not as the command word.
guardCase('GR65', { command: 'Start-Process git -ArgumentList "push --force"', tool: 'PowerShell' }, 1, withNote);

// Policy file: malformed denies every git command (and nothing else); absent means the defaults.
const badJson = scratchPolicy('badjson', '{ "git": ');
const badKey = scratchPolicy('badkey', { git: { push: { subagent: 'any' } } });
const badType = scratchPolicy('badtype', { usage: { hold: 'fifty' } });
guardCase('GF01', { command: 'git status', policy: badJson, policyName: 'not JSON' }, 'policy');
guardCase('GF02', { command: 'git status', policy: badKey, policyName: 'unknown key' }, 'policy');
guardCase('GF03', { command: 'git log', policy: badType, policyName: 'wrong type' }, 'policy');
guardCase('GF04', { command: 'ls -la', policy: badJson, policyName: 'not JSON' }, 'allow');
guardCase('GF05', { command: 'git commit -m x', agent: 'coder', policy: POLICIES.none, policyName: 'absent' }, 4);
guardCase('GF06', { command: 'git commit -m x', agent: 'runner', policy: POLICIES.none, policyName: 'absent' }, 'allow');
// git.integrate is validated like any other block: a wrong type or an unknown key denies every git command.
guardCase('GF07', { command: 'git status', policy: scratchPolicy('badintegrate', { git: { integrate: { subagents: 'all' } } }), policyName: 'integrate of the wrong type' }, 'policy');
guardCase('GF08', { command: 'git status', policy: scratchPolicy('badintegratekey', { git: { integrate: { branches: [] } } }), policyName: 'unknown key in integrate' }, 'policy');

// One command set under every policy. The absent file must behave as the Hatchery one.
// [id, command, agent, cwd, { hatchery, taskBranches, singleBranch }]
const H = 'hatchery';
const GP = [
  ['01', 'git push', 'pusher', repoMain, { hatchery: 'allow', taskBranches:3, singleBranch:'allow' }],
  ['02', 'git push', 'general-purpose', repoTask, { hatchery: 3, taskBranches:'allow', singleBranch:3 }],
  ['03', 'git push', null, repoMain, { hatchery: 'allow', taskBranches:'allow', singleBranch:'allow' }],
  ['04', 'git commit -m x', 'runner', repoMain, { hatchery: 'allow', taskBranches:4, singleBranch:'allow' }],
  ['05', 'git commit -m x', 'general-purpose', repoMain, { hatchery: 4, taskBranches:4, singleBranch:'allow' }],
  ['06', 'git commit -m x', 'general-purpose', repoTask, { hatchery: 4, taskBranches:'allow', singleBranch:'allow' }],
  ['07', 'git push --force-with-lease origin task/T1', 'general-purpose', repoTask, { hatchery: 1, taskBranches:'allow', singleBranch:1 }],
  ['08', 'git push --force-with-lease origin main', null, repoMain, { hatchery: 1, taskBranches:1, singleBranch:1 }],
  ['09', 'git branch -D task/T1', null, repoMain, { hatchery: 9, taskBranches:'allow', singleBranch:9 }],
  ['10', 'git branch -D task/T2', null, repoMain, { hatchery: 9, taskBranches:9, singleBranch:9 }],
  ['11', 'git push origin --delete task/T1', null, repoMain, { hatchery: 2, taskBranches:'allow', singleBranch:2 }],
  ['12', 'git push origin :task/T2', 'general-purpose', repoMain, { hatchery: 2, taskBranches:2, singleBranch:2 }],
  ['13', 'git config core.hooksPath tools/git-hooks', null, repoMain, { hatchery: 14, taskBranches:'allow', singleBranch:14 }],
  ['14', 'git push --tags', 'general-purpose', repoTask, { hatchery: 3, taskBranches:3, singleBranch:3 }],
  ['15', 'git push --tags', 'pusher', repoMain, { hatchery: 'allow', taskBranches:3, singleBranch:'allow' }],
  ['16', 'git push origin v1.0', null, repoMain, { hatchery: 'allow', taskBranches:'allow', singleBranch:'allow' }],
  ['17', 'git push origin HEAD:main', 'general-purpose', repoTask, { hatchery: 3, taskBranches:3, singleBranch:3 }],
  ['18', `git -C ${fwd(repoTask)} commit -m x`, 'general-purpose', repoMain, { hatchery: 4, taskBranches:'allow', singleBranch:'allow' }],
  ['19', `cd ${fwd(repoMain)} && git commit -m x`, 'general-purpose', repoTask, { hatchery: 4, taskBranches:4, singleBranch:'allow' }],
  ['20', 'git reset --hard', null, repoMain, { hatchery: 5, taskBranches:5, singleBranch:5 }],
  ['21', 'git commit --no-verify -m x', 'general-purpose', repoTask, { hatchery: 4, taskBranches:13, singleBranch:13 }],
  ['22', 'git pull', 'general-purpose', repoMain, { hatchery: 4, taskBranches:'allow', singleBranch:'allow' }],
  ['23', 'git cherry-pick abc', 'general-purpose', repoMain, { hatchery: 4, taskBranches:4, singleBranch:'allow' }],
];
for (const [n, command, agent, cwd, want] of GP) {
  for (const p of [H, 'none', 'taskBranches', 'singleBranch']) {
    const tag = { hatchery: 'H', none: 'N', taskBranches: 'T', singleBranch: 'S' }[p];
    guardCase(`GP${n}${tag}`, { command, agent, cwd, policy: POLICIES[p], policyName: p }, want[p === 'none' ? H : p]);
  }
}

// git.integrate (rule 4): pull, merge and rebase judged apart from commit. GI01 to GI16 under the
// task-branches example (any subagent integrates on main, none commits there); GI20 to GI23 with
// main protected and no integrate key, the base's behaviour and wording; GI30 to GI34 with one key
// of the entry set apart. [id, command, agent, cwd, policy, policyName, expect, extra]
{
  const BASE_ON_MAIN = 'Subagents do not make commits on main; work on your own branch.';
  const reasonIs = (want) => (d) => d.reason.endsWith(`\`. ${want}`) || `reason should end with ${JSON.stringify(want)}`;
  const reasonHas = (want) => (d) => d.reason.includes(want) || `reason should contain ${JSON.stringify(want)}`;
  const tb = POLICIES.taskBranches;
  const protectMain = scratchPolicy('protect-main', { git: { commit: { subagents: 'any', protectedBranches: ['main'] } } });
  const integrateWho = scratchPolicy('integrate-who', { git: { commit: { subagents: 'any', protectedBranches: ['main'] }, integrate: { subagents: ['runner'], protectedBranches: [] } } });
  const integrateBranch = scratchPolicy('integrate-branch', { git: { commit: { subagents: 'any', protectedBranches: [] }, integrate: { protectedBranches: ['main'] } } });
  const GI = [
    ['GI01', 'git pull', 'general-purpose', repoMain, tb, 'task-branches', 'allow'],
    ['GI02', 'git pull --rebase origin main', 'general-purpose', repoMain, tb, 'task-branches', 'allow'],
    ['GI03', 'git merge --ff-only origin/main', 'general-purpose', repoMain, tb, 'task-branches', 'allow'],
    ['GI04', 'git merge origin/main', 'general-purpose', repoMain, tb, 'task-branches', 'allow'],
    ['GI05', 'git rebase origin/main', 'general-purpose', repoMain, tb, 'task-branches', 'allow'],
    ['GI06', 'git merge --continue', 'general-purpose', repoMain, tb, 'task-branches', 'allow'],
    ['GI07', 'git rebase --continue', 'general-purpose', repoMain, tb, 'task-branches', 'allow'],
    ['GI08', 'git commit -m x', 'general-purpose', repoMain, tb, 'task-branches', 4,
      reasonIs(`${BASE_ON_MAIN} A merge or rebase in progress here is concluded with git merge --continue or git rebase --continue.`)],
    ['GI09', 'git cherry-pick abc', 'general-purpose', repoMain, tb, 'task-branches', 4],
    ['GI10', 'git revert HEAD', 'general-purpose', repoMain, tb, 'task-branches', 4],
    ['GI11', 'git am patch.mbox', 'general-purpose', repoMain, tb, 'task-branches', 4],
    ['GI12', `git -C ${fwd(repoMain)} pull --ff-only`, 'general-purpose', repoTask, tb, 'task-branches', 'allow'], // another checkout's main
    ['GI13', 'git rebase -i origin/main', 'general-purpose', repoMain, tb, 'task-branches', 10], // rule 10 untouched
    ['GI14', 'git pull --no-verify', 'general-purpose', repoMain, tb, 'task-branches', 13], // rule 13 untouched
    ['GI15', 'git commit -m x', 'general-purpose', repoTask, tb, 'task-branches', 'allow'],
    ['GI16', 'git cherry-pick abc', null, repoMain, tb, 'task-branches', 'allow'], // the main session
    ['GI20', 'git pull', 'general-purpose', repoMain, protectMain, 'main protected, no integrate key', 4, reasonIs(BASE_ON_MAIN)],
    ['GI21', 'git merge --ff-only origin/main', 'general-purpose', repoMain, protectMain, 'main protected, no integrate key', 4, reasonIs(BASE_ON_MAIN)],
    ['GI22', 'git rebase origin/main', 'general-purpose', repoMain, protectMain, 'main protected, no integrate key', 4, reasonIs(BASE_ON_MAIN)],
    ['GI23', 'git commit -m x', 'general-purpose', repoMain, protectMain, 'main protected, no integrate key', 4, reasonIs(BASE_ON_MAIN)],
    ['GI30', 'git pull', 'general-purpose', repoMain, integrateWho, 'integrate.subagents runner', 4,
      reasonHas('the subagents that pull, merge or rebase (git.integrate) are: runner.')],
    ['GI31', 'git pull', 'runner', repoMain, integrateWho, 'integrate.subagents runner', 'allow'],
    ['GI32', 'git commit -m x', 'general-purpose', repoTask, integrateWho, 'integrate.subagents runner', 'allow'],
    ['GI33', 'git rebase origin/main', 'general-purpose', repoMain, integrateBranch, 'integrate.protectedBranches main', 4,
      reasonIs('Subagents do not pull, merge or rebase on main (git.integrate); work on your own branch.')],
    ['GI34', 'git commit -m x', 'general-purpose', repoMain, integrateBranch, 'integrate.protectedBranches main', 'allow'],
  ];
  for (const [id, command, agent, cwd, policy, policyName, expect, extra] of GI) guardCase(id, { command, agent, cwd, policy, policyName }, expect, extra);
}

// ---------- the privacy scan: rule 20, the CLI, the pre-push hook (group PV; ADR 0018) ----------
// Sample values are built from pieces so no line of this file matches the scan that reads it when
// it is pushed. Each case: a scratch repository with a bare origin and one pushed base commit, then
// outgoing commits; a scratch policy listing the scratch identity; HOME a scratch dir.
{
  const AT = '@';
  const U = 'Us' + 'ers';
  const SEG = 'some' + 'one';
  const PV_ID = { name: 'hooks-test', email: `hooks-test${AT}example.invalid` };
  const OTHER = `${SEG}${AT}corp.internal`; // not a placeholder domain
  const NOREPLY = `12345+hooks-test${AT}users.noreply.github` + '.com';
  const PV_DIR = Symbol('directory');
  const pvGit = (dir, args, who, env) => {
    const id = who || PV_ID;
    const ident = ['-c', `user.name=${id.name}`, ...(id.email === null ? [] : ['-c', `user.email=${id.email}`])];
    const r = spawnSync('git', ['-C', dir, ...ident, '-c', 'commit.gpgsign=false', '-c', 'tag.gpgsign=false', ...args],
      { encoding: 'utf8', env: env || process.env });
    return r;
  };
  const must = (dir, args, who) => {
    const r = pvGit(dir, args, who);
    if (r.status !== 0) { process.stderr.write(`test-hooks: git ${args.join(' ')} failed: ${r.stderr}\n`); process.exit(2); }
    return (r.stdout || '').trim();
  };
  const pvCommit = (dir, c) => {
    for (const [rel, text] of Object.entries(c.files || {})) {
      const f = path.join(dir, ...rel.split('/'));
      if (text === null) fs.rmSync(f);
      else { fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, text); }
    }
    must(dir, ['add', '-A']);
    must(dir, ['commit', '-q', '--allow-empty', '-m', c.msg || 'change'], c.who);
    return must(dir, ['rev-parse', 'HEAD']);
  };
  const pvRepo = (name, commits) => {
    const dir = path.join(scratch, `pv-${name}`);
    fs.mkdirSync(dir, { recursive: true });
    must(scratch, ['init', '-q', '--bare', `${dir}-origin.git`]);
    must(dir, ['init', '-q']);
    must(dir, ['checkout', '-q', '-b', 'main']);
    fs.writeFileSync(path.join(dir, 'base.txt'), 'base\n');
    must(dir, ['add', '-A']);
    must(dir, ['commit', '-q', '-m', 'base']);
    must(dir, ['remote', 'add', 'origin', `${dir}-origin.git`]);
    must(dir, ['push', '-q', 'origin', 'main']);
    const shas = (commits || []).map((c) => pvCommit(dir, c));
    return { dir, shas };
  };
  const line = (rel, text) => ({ files: { [rel]: `${text}\n` } });
  const pvHome = (name, files) => {
    const h = path.join(scratch, `pv-home-${name}`);
    fs.mkdirSync(path.join(h, '.claude'), { recursive: true });
    for (const [f, t] of Object.entries(files || {})) {
      const p = path.join(h, '.claude', f);
      if (t === PV_DIR) fs.mkdirSync(p, { recursive: true }); else fs.writeFileSync(p, t);
    }
    return h;
  };
  const pvPolicy = (name, privacy) => scratchPolicy(`pv-${name}`,
    Object.assign({}, HATCHERY_POLICY, { privacy: Object.assign({ allow: [], identities: [PV_ID] }, privacy) }));
  const POL = pvPolicy('base');
  const says = (...parts) => (d) => parts.every((p) => d.reason.includes(p)) || `reason lacks one of ${JSON.stringify(parts)}: ${d.reason}`;
  const lacks = (...parts) => (d) => parts.every((p) => !d.reason.includes(p)) || `reason carries one of ${JSON.stringify(parts)}`;
  const pv = (id, command, repo, expect, o) => {
    const x = o || {};
    guardCase(id, { command, agent: x.agent === undefined ? 'pusher' : x.agent, cwd: repo.dir, policy: x.policy || POL,
      home: x.home, policyName: x.label }, expect, x.extra);
  };
  const cli = (repo, args, o) => {
    const x = o || {};
    const home = x.home || guardHome;
    const r = spawnSync(NODE, [path.join(HOOKS, 'privacy-scan.js'), ...args], {
      cwd: repo.dir || repo, input: x.input || '', encoding: 'utf8', timeout: 60000,
      env: envWith({ HOOK_POLICY_FILE: x.policy || POL, HOME: home, USERPROFILE: home }),
    });
    return { code: r.status, out: r.stdout || '', err: r.stderr || '' };
  };
  const cliCase = (id, label, check) => { if (selected(id)) { const p = check(); report(id, label, p === true, p); } };
  const json = (r) => { try { return JSON.parse(r.out); } catch (_) { return null; } };
  const PUSH = 'git push origin main';

  if (!opt.only || opt.only.startsWith('PV') || 'PV'.startsWith(opt.only)) {
    // PV01-PV04: home paths, slugs, placeholders, addresses.
    const homeBack = pvRepo('home-back', [line('docs/a.md', `see C:\\${U}\\${SEG}\\proj for it`)]);
    const homeFwd = pvRepo('home-fwd', [line('docs/a.md', `see C:/${U}/${SEG}/proj`)]);
    const homeBash = pvRepo('home-bash', [line('docs/a.md', `cd /c/${U}/${SEG}/proj`)]);
    pv('PV01a', PUSH, homeBack, 20, { extra: says('docs/a.md:1 (home: ') });
    pv('PV01b', PUSH, homeFwd, 20, { extra: says('docs/a.md:1 (home: ') });
    pv('PV01c', PUSH, homeBash, 20, { extra: says('docs/a.md:1 (home: ') });
    const slugDrive = pvRepo('slug-drive', [line('notes.txt', `path "C--${U}-${SEG}-proj"`)]);
    pv('PV02', PUSH, slugDrive, 20, { extra: says('notes.txt:1 (home: ') });
    const holders = pvRepo('holders', [{ files: { 'docs/h.md': [`C:\\${U}\\<name>\\x`, `C:/${U}/$USER/x`, `C:/${U}/~/x`,
      `C:\\${U}\\Public\\x`, `C:\\${U}\\Default User\\x`, `/home/<name>/x`].join('\n') + '\n' } }]);
    pv('PV03', PUSH, holders, 'allow');
    const mailOk = pvRepo('mail-ok', [line('a.txt', `write to ${SEG}${AT}example.invalid or ${SEG}${AT}example.org`)]);
    const mailBad = pvRepo('mail-bad', [line('a.txt', `write to ${OTHER}`)]);
    pv('PV04a', PUSH, mailOk, 'allow');
    pv('PV04b', PUSH, mailBad, 20, { extra: says('a.txt:1 (email: ') });

    // PV05-PV06: the repository identity is never an exemption; an empty list switches identity off.
    const stranger = pvRepo('stranger');
    must(stranger.dir, ['config', 'user.email', OTHER]);
    pvCommit(stranger.dir, { msg: 'change', who: { name: 'hooks-test', email: null } });
    pv('PV05', PUSH, stranger, 20, { extra: says('4 hits', 'author (email: ', 'author (identity: ', 'committer (email: ') });
    const empty = pvPolicy('no-ids', { identities: [] });
    cliCase('PV06a', 'CLI, identities empty, PV05 repository: no identity hit, the notice, the email hits stay', () => {
      const r = cli(stranger, ['--json'], { policy: empty });
      const o = json(r);
      if (!o) return `stdout not JSON: ${r.out}`;
      if (o.hits.some((h) => h.class === 'identity')) return 'an identity hit with the class off';
      if (!r.err.includes('privacy-scan: privacy.identities is empty')) return `no notice: ${r.err}`;
      return (r.code === 1 && o.hits.filter((h) => h.class === 'email').length === 2) || `exit ${r.code}, ${JSON.stringify(o.hits)}`;
    });
    const placeholderId = pvRepo('placeholder-id', [{ msg: 'change', who: { name: 'someone-else', email: `x${AT}example.invalid` } }]);
    pv('PV06b', PUSH, placeholderId, 20, { extra: says('(identity: ') });
    pv('PV06c', PUSH, placeholderId, 'allow', { policy: empty, label: 'identities empty' });

    // PV07-PV08, PV26: commit by commit; only the target remote and hatchery count as published.
    const netDiff = pvRepo('net-diff', [line('docs/n.md', 'clean'), line('docs/n.md', `see C:/${U}/${SEG}/x`), line('docs/n.md', 'clean again')]);
    pv('PV07', PUSH, netDiff, 20, { extra: says('1 hit: ', `${netDiff.shas[1].slice(0, 7)} docs/n.md:1 (home: `) });
    const onBase = pvRepo('on-base', [line('docs/b.md', `see C:/${U}/${SEG}/x`)]);
    must(scratch, ['init', '-q', '--bare', `${onBase.dir}-hatchery.git`]);
    must(onBase.dir, ['remote', 'add', 'hatchery', `${onBase.dir}-hatchery.git`]);
    must(onBase.dir, ['push', '-q', 'hatchery', 'main']);
    pvCommit(onBase.dir, line('docs/c.md', 'clean'));
    pv('PV08', PUSH, onBase, 'allow');
    const onBackup = pvRepo('on-backup', [line('docs/b.md', `see C:/${U}/${SEG}/x`)]);
    must(scratch, ['init', '-q', '--bare', `${onBackup.dir}-backup.git`]);
    must(onBackup.dir, ['remote', 'add', 'backup', `${onBackup.dir}-backup.git`]);
    must(onBackup.dir, ['push', '-q', 'backup', 'main']);
    pv('PV26', PUSH, onBackup, 20, { extra: says('docs/b.md:1 (home: ') });

    // PV09-PV16: the other classes.
    pv('PV09', PUSH, pvRepo('trailer', [{ msg: `change\n\n${'Claude-'}Session: https://example.invalid/x` }]), 20, { extra: says('message (session: ') });
    pv('PV10a', PUSH, pvRepo('claude-url', [line('a.txt', `link ${'https://'}${'claude'}.ai/code/x`)]), 20, { extra: says('a.txt:1 (session: ') });
    pv('PV10b', PUSH, pvRepo('claude-host', [line('a.txt', `plans on ${'https://'}${'claude'}.ai and claude.ai.`)]), 'allow');
    const never = pvHome('never', { 'privacy-never.txt': '# a comment\nzorblax\n' });
    pv('PV11a', PUSH, pvRepo('never-word', [line('a.txt', 'met Zorblax today')]), 20, { home: never, extra: says('a.txt:1 (word: Zorblax)') });
    pv('PV11b', PUSH, pvRepo('never-part', [line('a.txt', 'the zorblaxian way')]), 'allow', { home: never });
    let osUser = '';
    try { osUser = os.userInfo().username; } catch (_) { /* no user name: PV12 skips */ }
    if (osUser.length >= 3) {
      const own = pvRepo('os-user', [{ msg: `change by ${osUser} today` }]);
      pv('PV12a', PUSH, own, 20, { extra: says('message (word: ') });
      pv('PV12b', PUSH, own, 'allow', { home: pvHome('user-allow', { 'privacy-allow.txt': `${osUser}\n` }), label: 'user allow file' });
    } else if (selected('PV12')) { skipped++; noteGroup('PV12'); process.stdout.write('SKIP PV12 the OS user name is unreadable or shorter than 3 characters\n'); }
    pv('PV13', PUSH, pvRepo('host', [line('a.txt', `built on ${'DESK'}TOP-AB12 last week`)]), 20, { extra: says('a.txt:1 (host: ') });
    const tagged = pvRepo('tag');
    must(tagged.dir, ['tag', '-a', 'v1', '-m', `release, questions to ${OTHER}`]);
    pv('PV14', 'git push origin v1', tagged, 20, { extra: says('tag v1 message (email: ') });
    pv('PV15', PUSH, pvRepo('dotenv', [{ files: { '.env': 'X=1\n' } }]), 20, { extra: says('file .env (secret: ') });
    const tline = JSON.stringify({ parentUuid: null, sessionId: 'x', type: 'user' });
    pv('PV16', PUSH, pvRepo('transcript', [line('log.jsonl', tline)]), 20, { extra: says('log.jsonl:1 (transcript: ') });

    // PV17-PV19, PV27: every push form and every caller.
    pv('PV17', 'git push --dry-run origin main', homeFwd, 20);
    pv('PV18', PUSH, homeFwd, 20, { agent: null, extra: noNote });
    pv('PV19', 'git push nosuch main', homeFwd, 20, { extra: says('could not read what this push sends', 'nosuch (not a configured remote)') });
    const clean = pvRepo('clean', [line('a.txt', 'nothing personal')]);
    pv('PV27a', 'git add -A && git commit -m x && git push', clean, 20, { agent: null, extra: says('shares its line with another git command') });
    pv('PV27b', 'git push', clean, 'allow', { agent: null });

    // PV20-PV22, PV32: the CLI and the pre-push hook.
    cliCase('PV20', 'CLI --json on PV07: exit 1, commits 3, one hit with its commit', () => {
      const r = cli(netDiff, ['--json']);
      const o = json(r);
      if (!o) return `stdout not JSON: ${r.out}`;
      return (r.code === 1 && o.commits === 3 && o.error === null && o.hits.length === 1 && o.hits[0].commit === netDiff.shas[1] &&
        o.hits[0].class === 'home') || `exit ${r.code}, ${r.out}`;
    });
    cliCase('PV21', 'CLI --text, two classes: exit 1, two HIT lines with <file>:<n>', () => {
      const f = path.join(scratch, 'pv-text.txt');
      fs.writeFileSync(f, `plain\nmail ${OTHER}\npath C:/${U}/${SEG}/x\n`);
      const r = cli(scratch, ['--text', f]);
      const ls = r.out.trim().split('\n');
      return (r.code === 1 && ls.length === 3 && ls[0] === `HIT email ${f}:2: ${OTHER}` && ls[1] === `HIT home ${f}:3: C:/${U}/${SEG}` &&
        ls[2] === 'privacy-scan: 2 hits in 3 lines') || `exit ${r.code}, ${JSON.stringify(r.out)}`;
    });
    cliCase('PV22', 'pre-push hook through git push: refused on PV01, the remote unchanged; a clean push goes', () => {
      const p = [];
      for (const [repo, want] of [[pvRepo('hook-hit', [line('docs/a.md', `see C:/${U}/${SEG}/proj`)]), 'refused'], [pvRepo('hook-clean', [line('a.txt', 'ok')]), 'pushed']]) {
        const hooks = path.join(repo.dir, '.claude', 'hooks');
        fs.mkdirSync(path.join(hooks, 'git'), { recursive: true });
        for (const f of ['privacy-scan.js', 'policy.js']) fs.copyFileSync(path.join(HOOKS, f), path.join(hooks, f));
        fs.copyFileSync(path.join(HOOKS, 'git', 'pre-push'), path.join(hooks, 'git', 'pre-push'));
        fs.chmodSync(path.join(hooks, 'git', 'pre-push'), 0o755);
        must(repo.dir, ['config', 'core.hooksPath', '.claude/hooks/git']);
        const before = must(scratch, ['--git-dir', `${repo.dir}-origin.git`, 'rev-parse', 'main']);
        const r = pvGit(repo.dir, ['push', 'origin', 'main'], null, envWith({ HOOK_POLICY_FILE: POL, HOME: guardHome, USERPROFILE: guardHome }));
        const after = must(scratch, ['--git-dir', `${repo.dir}-origin.git`, 'rev-parse', 'main']);
        if (want === 'refused' && !(r.status !== 0 && after === before && r.stderr.includes('HIT home'))) p.push(`hit: exit ${r.status}, ${JSON.stringify(r.stderr)}`);
        if (want === 'pushed' && !(r.status === 0 && after === repo.shas[0])) p.push(`clean: exit ${r.status}, ${r.stderr.trim()}`);
      }
      return p.length === 0 || p.join('; ');
    });
    cliCase('PV32', 'CLI --pre-push: a remote sha bounds the range (a hit at it passes, one after it is denied)', () => {
      const two = pvRepo('pre-push-range', [line('docs/a.md', `see C:/${U}/${SEG}/a`), line('docs/b.md', `see C:/${U}/${SEG}/b`)]);
      const [a, b] = two.shas;
      const zero = '0'.repeat(40);
      const r1 = cli(two, ['--pre-push', 'origin', 'x'], { input: `refs/heads/main ${b} refs/heads/main ${a}\n` });
      const o1 = r1.out.trim().split('\n');
      const r2 = cli(two, ['--pre-push', 'origin', 'x'], { input: `refs/heads/main ${b} refs/heads/main ${b}\n` });
      const r3 = cli(two, ['--pre-push', 'origin', 'x'], { input: `(delete) ${zero} refs/heads/old ${a}\n` });
      if (!(r1.code === 1 && o1.length === 2 && o1[0].startsWith(`HIT home ${b.slice(0, 7)} docs/b.md:1`) && o1[1] === 'privacy-scan: 1 hits in 1 commits')) return `after a: exit ${r1.code}, ${r1.out}`;
      if (!(r2.code === 0 && r2.out === 'privacy-scan: clean, 0 commits\n')) return `at b: exit ${r2.code}, ${r2.out}`;
      return (r3.code === 0 && r3.out === 'privacy-scan: clean, 0 commits\n') || `deletion: exit ${r3.code}, ${r3.out}`;
    });

    // PV23: malformed privacy keys deny every git command, as any malformed policy does.
    guardCase('PV23a', { command: 'git status', policy: scratchPolicy('pv-glob', { privacy: { allow: ['a*'] } }), policyName: 'allow glob' }, 'policy');
    guardCase('PV23b', { command: 'git status', policy: scratchPolicy('pv-noemail', { privacy: { identities: [{ name: 'x' }] } }), policyName: 'identity without email' }, 'policy');

    // PV24-PV25: the slug spellings, and what is not one.
    pv('PV24a', PUSH, pvRepo('slug-home', [line('a.txt', `under -home-${SEG}-proj now`)]), 20, { extra: says('a.txt:1 (home: ') });
    pv('PV24b', PUSH, pvRepo('slug-mac', [line('a.txt', `-${U}-${SEG}-proj`)]), 20, { extra: says('a.txt:1 (home: ') });
    pv('PV25', PUSH, pvRepo('slug-not', [{ files: { 'a.txt': [`run --home-dir=${SEG}-x`, `xC--${U}-${SEG}-y`,
      `the slug is written C--${U}-<seg>- or -home-<seg>-`].join('\n') + '\n' } }]), 'allow');

    // PV28-PV29: identity addresses in content, and what each allow list lifts.
    const ids = pvPolicy('ids', { identities: [PV_ID, { name: 'hooks-test', email: OTHER }, { name: 'hooks-test', email: NOREPLY }] });
    pv('PV28a', PUSH, pvRepo('id-content', [line('a.txt', `mail ${OTHER}`)]), 20, { policy: ids, extra: says('a.txt:1 (email: ') });
    pv('PV28b', PUSH, pvRepo('id-committer', [{ msg: 'change', who: { name: 'hooks-test', email: OTHER } }]), 'allow', { policy: ids });
    pv('PV28c', PUSH, pvRepo('id-noreply', [line('a.txt', `mail ${NOREPLY}`)]), 'allow', { policy: ids });
    const mailHome = pvHome('mail-allow', { 'privacy-allow.txt': `${OTHER}\n` });
    cliCase('PV29a', 'user allow file holding an email: no lift, and a notice', () => {
      const r = cli(mailBad, [], { home: mailHome });
      return (r.code === 1 && r.out.startsWith('HIT email ') && r.err.includes('privacy-allow.txt lifts only word, host and home hits')) ||
        `exit ${r.code}, ${r.out} ${r.err}`;
    });
    pv('PV29b', PUSH, mailBad, 'allow', { policy: pvPolicy('mail-allow', { allow: [OTHER] }), label: 'privacy.allow' });

    // PV30: an unreadable never file denies the push, never other git commands.
    const brokenHome = pvHome('never-dir', { 'privacy-never.txt': PV_DIR });
    pv('PV30a', PUSH, clean, 20, { home: brokenHome, extra: says('could not read what this push sends', 'privacy-never.txt') });
    pv('PV30b', 'git status', clean, 'allow', { home: brokenHome });

    // PV31: secrets only in their full shapes.
    pv('PV31a', PUSH, pvRepo('akia', [line('a.txt', `key ${'AK'}IA${'ABCDEFGH'}IJKLMNOP end`)]), 20, { extra: (d) => (says('a.txt:1 (secret: ')(d) === true ? lacks('IJKLMNOP')(d) : says('a.txt:1 (secret: ')(d)) });
    pv('PV31b', PUSH, pvRepo('akia-prose', [line('a.txt', `the ${'AK'}IA prefix, and ${'tok'}en = "some text here"`)]), 'allow');

    // PV33: a \n escape in source text is not an address's user part; the address after one is still read.
    pv('PV33a', PUSH, pvRepo('escape-import', [line('a.js', 'const t = "Write\\n@x.md\\n";')]), 'allow');
    pv('PV33b', PUSH, pvRepo('escape-addr', [line('a.js', `const t = "to:\\n${OTHER}";`)]), 20, { extra: says(`(email: ${OTHER})`) });

    // PV34: more places a path starts (review of 5f82800): after a backslash, a comma or a >, and the WSL share.
    pv('PV34a', PUSH, pvRepo('slug-backslash', [line('a.txt', `~\\.claude\\projects\\C--${U}-${SEG}-x`)]), 20, { extra: says('a.txt:1 (home: ') });
    pv('PV34b', PUSH, pvRepo('wsl', [line('a.txt', `open \\\\wsl$\\Ubuntu\\home\\${SEG}\\x`)]), 20, { extra: says('a.txt:1 (home: ') });
    pv('PV34c', PUSH, pvRepo('comma', [line('a.txt', `a,/home/${SEG}/x`)]), 20, { extra: says('a.txt:1 (home: ') });
    pv('PV34d', PUSH, pvRepo('redirect', [line('a.txt', `echo x >/home/${SEG}/x`)]), 20, { extra: says('a.txt:1 (home: ') });
    // PV35: a path with a space prints without git's trailing tab.
    pv('PV35', PUSH, pvRepo('spaced', [line('my notes.md', `see C:/${U}/${SEG}/x`)]), 20, { extra: says(' my notes.md:1 (home: ') });
    // PV36: git push <remote> tag <name> sends refs/tags/<name>.
    const tagWord = pvRepo('tag-word');
    must(tagWord.dir, ['tag', '-a', 'v9', '-m', `release, questions to ${OTHER}`]);
    pv('PV36', 'git push origin tag v9', tagWord, 20, { extra: says('tag v9 message (email: ') });
    // PV37: a listed identity's address passes in an added line of .claude/hooks/policy.json, which
    // declares it, and in no other file (PV28a is plain content).
    const idLine = `{ "identities": [{ "name": "hooks-test", "email": "${OTHER}" }] }`;
    pv('PV37a', PUSH, pvRepo('id-policy', [line('.claude/hooks/policy.json', idLine)]), 'allow', { policy: ids });
    pv('PV37b', PUSH, pvRepo('id-policy-other', [line('.claude/policy.json', idLine)]), 20,
      { policy: ids, extra: says('.claude/policy.json:1 (email: ') });
  }
}

// ---------- post-edit ----------
const write = (rel, text) => {
  const f = path.join(scratch, ...rel.split('/'));
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, text);
};
const lines = (n, prefix) => Array.from({ length: n }, (_, i) => `${prefix || 'line'} ${i + 1}\n`).join('');

write('docs/reference/README.md', '# Reference\n\n| `big.md` | `clean.md` | `noverified.md` |\n');
write('docs/reference/big.md', `---\nverified: 2026-10-11\nsource: observed\n---\n${lines(297)}`); // 301 newlines
write('docs/reference/clean.md', '---\nverified: 2026-10-11\nsource: observed\n---\n\n# Clean\n');
write('docs/reference/noverified.md', '---\nsource: observed\n---\n# No verified\n');
write('docs/reference/unlisted.md', '---\nverified: 2026-10-11\n---\n# Unlisted\n');
// 41 lines, synthetic: the suite reads no file of the repository it runs in.
write('docs/HANDOFF.md', `# Handoff\n\n${lines(39)}`);
write('docs/findings/README.md', '# Findings\n\n`2026-01-01-nofm.md` `2026-01-02-ok.md` `2026-01-03-gone.md`\n');
write('docs/findings/2026-01-01-nofm.md', '# A finding without frontmatter\n\n## Question\n');
write('docs/findings/2026-01-02-ok.md', '---\ndate: 2026-01-02\nstatus: open\narea: test\n---\n# OK\n');
write('docs/design/README.md', '# Design\n\n- [0009 Something](decisions/0009-something.md)\n');
write('docs/design/decisions/README.md', '# Decision records\n');
write('docs/design/decisions/0009-something.md', '# 0009. Something\n');
write('docs/design/proposals/README.md', '# Proposals\n\n`0009-long.md`\n');
write('docs/design/proposals/0009-long.md', lines(320));
write('src/app.txt', 'not a doc\n');
write('README.md', lines(400));
write('CLAUDE.md', lines(41));
write('src/CLAUDE.md', lines(41));
write('root40/CLAUDE.md', lines(40));

const absOf = (rel) => path.join(scratch, ...rel.split('/'));
const fmtOk = { '.txt': [NODE, '-e', 'process.exit(0)'] };
const fmtBad = { '.txt': [NODE, '-e', 'console.error("bad formatter output");process.exit(3)'] };

// [id, label, file_path (absolute unless noted), expected additionalContext or null, raw stdin override, formatter env,
//  policy file (the Hatchery fixture when omitted), project root (the scratch tree when omitted)]
const P = [
  // The four cases the spec asks for.
  ['P01', '301-line docs/reference file', absOf('docs/reference/big.md'),
    'post-edit: docs/reference/big.md is 301 lines; cap is 300 (.claude/rules/docs.md). Split by topic; the pusher will refuse to push.'],
  ['P02', '41-line docs/HANDOFF.md', absOf('docs/HANDOFF.md'),
    'post-edit: docs/HANDOFF.md is 41 lines; cap is 40. Move detail to docs/ideas/ or a finding.'],
  ['P03', 'findings file without frontmatter', absOf('docs/findings/2026-01-01-nofm.md'),
    'post-edit: docs/findings/2026-01-01-nofm.md lacks finding frontmatter (date, status, area). See the finding skill.'],
  ['P04', 'clean reference file', absOf('docs/reference/clean.md'), null],
  // Extra cases.
  ['PX01', 'reference file without verified:', absOf('docs/reference/noverified.md'),
    'post-edit: docs/reference/noverified.md lacks verified: frontmatter. See .claude/rules/docs.md.'],
  ['PX02', 'reference file missing from its index', absOf('docs/reference/unlisted.md'),
    'post-edit: docs/reference/unlisted.md is not listed in reference/README.md. Add one line.'],
  ['PX03', 'finding with full frontmatter', absOf('docs/findings/2026-01-02-ok.md'), null],
  ['PX04', 'ADR listed only in docs/design/README.md', absOf('docs/design/decisions/0009-something.md'), null],
  ['PX05', '320-line proposal (exempt from cap)', absOf('docs/design/proposals/0009-long.md'), null],
  ['PX06', 'relative file_path resolved against cwd', 'docs/reference/big.md',
    'post-edit: docs/reference/big.md is 301 lines; cap is 300 (.claude/rules/docs.md). Split by topic; the pusher will refuse to push.'],
  ['PX07', '400-line .md outside docs/', absOf('README.md'), null],
  ['PX08', 'non-doc file, empty formatter table', absOf('src/app.txt'), null],
  ['PX09', 'malformed stdin', '(n/a)', null, 'not json'],
  ['PX10', 'formatter that succeeds', absOf('src/app.txt'), null, null, fmtOk],
  ['PX12', '41-line CLAUDE.md (cap 40, ADR 0016)', absOf('CLAUDE.md'),
    'FAIL CLAUDE.md: 41 lines, cap 40; lift: edit.capExempt naming CLAUDE.md'],
  ['PX13', '40-line CLAUDE.md at another root', absOf('root40/CLAUDE.md'), null, null, null, null, absOf('root40')],
  ['PX14', '41-line CLAUDE.md below the root', absOf('src/CLAUDE.md'), null],
  ['PX11', 'formatter that fails', absOf('src/app.txt'),
    `post-edit: formatter ${NODE} failed on src/app.txt: bad formatter output`, null, fmtBad],
  // Path anchors: backtick-quoted repo paths must exist.
  ['PA01', 'doc naming a missing path', absOf('docs/notes/anchors.md'),
    'post-edit: docs/notes/anchors.md names paths that do not exist: `docs/reference/gone.md`, `src/old/`. Fix them, or mark the paragraph [no-check: <reason>] (.claude/rules/docs.md).'],
  ['PA02', 'missing path in a [no-check] paragraph', absOf('docs/notes/nocheck.md'), null],
  ['PA03', 'missing path in a fenced block', absOf('docs/notes/fenced.md'), null],
  ['PA04', 'placeholders, globs, refs, other repos', absOf('docs/notes/placeholders.md'), null],
  // Policy: cap exemptions and formatters come from the edit block.
  ['PC01', 'cap exemption by path', absOf('docs/reference/big.md'), null, null, null,
    scratchPolicy('capexempt', { edit: { capExempt: ['docs/reference/big.md'] } })],
  ['PC02', 'cap exemption by pattern', absOf('docs/reference/big.md'), null, null, null,
    scratchPolicy('capglob', { edit: { capExempt: ['docs/reference/*'] } })],
  ['PC03', 'formatter from the policy', absOf('src/app.txt'),
    `post-edit: formatter ${NODE} failed on src/app.txt: bad formatter output`, null, null,
    scratchPolicy('formatter', { edit: { formatters: fmtBad } })],
  ['PC04', 'malformed policy: base defaults', absOf('docs/reference/big.md'),
    'post-edit: docs/reference/big.md is 301 lines; cap is 300 (.claude/rules/docs.md). Split by topic; the pusher will refuse to push.',
    null, null, scratchPolicy('pe-bad', '{ nope')],
  ['PC05', 'task-branches example exempts docs/PLANNING.md', absOf('docs/PLANNING.md'), null, null, null, POLICIES.taskBranches],
  ['PC06', 'docs/PLANNING.md under the Hatchery policy', absOf('docs/PLANNING.md'),
    'post-edit: docs/PLANNING.md is 320 lines; cap is 300 (.claude/rules/docs.md). Split by topic; the pusher will refuse to push.'],
  // Closed records are exempt from the path check by default; list items scope [no-check:].
  ['PA05', 'a finding naming a gone path (closed record)', absOf('docs/findings/2026-01-03-gone.md'), null],
  ['PA06', 'the same finding with anchorExempt emptied', absOf('docs/findings/2026-01-03-gone.md'),
    'post-edit: docs/findings/2026-01-03-gone.md names paths that do not exist: `docs/reference/gone.md`. Fix them, or mark the paragraph [no-check: <reason>] (.claude/rules/docs.md).',
    null, null, scratchPolicy('anchor-all', { edit: { anchorExempt: [] } })],
  ['PA07', 'an indented fenced block', absOf('docs/notes/indented.md'), null],
  ['PA08', '[no-check:] covers its own list item only', absOf('docs/notes/list.md'),
    'post-edit: docs/notes/list.md names paths that do not exist: `docs/reference/also-gone.md`. Fix them, or mark the paragraph [no-check: <reason>] (.claude/rules/docs.md).'],
  // An elided path (a "..." segment) is not a path; a real missing one in the same board line still warns.
  ['PA09', 'elided paths in a board line next to a missing one', absOf('docs/TASKS.md'),
    'post-edit: docs/TASKS.md names paths that do not exist: `src/gone/Real.kt`. Fix them, or mark the paragraph [no-check: <reason>] (.claude/rules/docs.md).'],
];
write('docs/findings/2026-01-03-gone.md', '---\ndate: 2026-01-03\nstatus: resolved\narea: test\n---\n# Gone\n\nIt lived in `docs/reference/gone.md`.\n');
write('docs/notes/indented.md', '# Indented\n\n- step:\n\n    ```\n    cat `docs/reference/gone.md`\n    ```\n');
write('docs/notes/list.md', '# List\n\n- planned `docs/reference/gone.md` [no-check: not yet]\n- also `docs/reference/also-gone.md`\n- fine `docs/reference/clean.md`\n');
write('docs/notes/README.md', '# Notes\n\n`anchors.md` `nocheck.md` `fenced.md` `placeholders.md` `indented.md` `list.md`\n');
write('docs/notes/anchors.md', '# Anchors\n\nSee `docs/reference/clean.md`, `docs/reference/gone.md`, `src/app.txt` and `src/old/`.\n\nAgain `docs/reference/gone.md`.\n');
write('docs/notes/nocheck.md', '# No check\n\nPlanned: `docs/reference/gone.md`. [no-check: not written yet]\n');
write('docs/notes/fenced.md', '# Fenced\n\n```\nls `docs/reference/gone.md`\n```\n\nFine: `docs/reference/clean.md`.\n');
write('docs/notes/placeholders.md', '# Placeholders\n\n`docs/design/decisions/NNNN-title.md`, `docs/findings/<slug>.md`, `docs/*.md`,\n' +
  '`origin/main`, `../decisions/x.md`, `task/T1`, `docs/reference/clean.md#section`, `docs/reference/clean.md:3`.\n');
write('docs/PLANNING.md', lines(320));
write('docs/TASKS.md', '# Tasks\n\n### T1. A task\n**Owns:** `src/.../routing/Gone.kt`, `docs/.../gone.md`, `src/gone/Real.kt` and `src/app.txt`.\n');

for (const [id, label, filePath, expect, rawOverride, fmt, policy, root] of P) {
  if (!selected(id)) continue;
  const stdin = rawOverride != null ? rawOverride : JSON.stringify({
    hook_event_name: 'PostToolUse', tool_name: 'Write', cwd: root || scratch, tool_input: { file_path: filePath },
  });
  const env = envWith({ HOOK_POLICY_FILE: policy || POLICIES.hatchery, POST_EDIT_FORMATTERS_JSON: fmt ? JSON.stringify(fmt) : undefined });
  const r = run('post-edit', stdin, env);
  let ok = false;
  let detail = '';
  if (r.code !== 0) detail = `exit ${r.code}, expected 0; stderr: ${r.stderr.trim()}`;
  else if (expect === null) { ok = r.stdout === ''; detail = 'expected no stdout'; }
  else {
    const d = parseDecision(r.stdout);
    ok = !!d && d.event === 'PostToolUse' && d.context === expect;
    detail = `expected additionalContext ${JSON.stringify(expect)}`;
  }
  report(id, `${label} -> ${expect === null ? 'no output' : 'message'}`, ok, detail, r.stdout);
  if (ok && !opt.verbose) process.stdout.write(`     exit ${r.code}, stdout: ${r.stdout || '(empty)'}\n`);
}

// ---------- post-edit: the handoff paths (ADR 0014) ----------
// Each case builds its own scratch root (ph-<id>) so the P cases above keep their tree. A case is
// [id, label, files { relative path: contents }, edited file, expected messages (joined with \n) or null].
// Messages are checked as a whole, so a stray extra warning fails the case.
{
  const mapOf = (...imports) => `# Handoff\n\nCoordinator state.\n${imports.join('\n')}\n`;
  const PH = [
    ['PH01', '41-line docs/handoff stream warns with its path and 40',
      { 'docs/HANDOFF.md': mapOf('@handoff/x.md'), 'docs/handoff/x.md': lines(41) }, 'docs/handoff/x.md',
      'post-edit: docs/handoff/x.md is 41 lines; cap is 40. Move detail to docs/ideas/ or a finding.'],
    ['PH02', '40-line stream is silent',
      { 'docs/HANDOFF.md': mapOf('@handoff/x.md'), 'docs/handoff/x.md': lines(40) }, 'docs/handoff/x.md', null],
    ['PH03', 'map plus streams totalling 121 warns the total and 120',
      { 'docs/HANDOFF.md': mapOf('@handoff/a.md', '@handoff/b.md', '@handoff/c.md') + lines(34, 'm'), 'docs/handoff/a.md': lines(40), 'docs/handoff/b.md': lines(40), 'docs/handoff/c.md': lines(1) },
      'docs/HANDOFF.md',
      'post-edit: the handoff loads 121 lines in total (docs/HANDOFF.md plus docs/handoff/*.md); cap is 120. Close a stream or trim.'],
    ['PH04', 'map plus streams totalling 120 is silent',
      { 'docs/HANDOFF.md': mapOf('@handoff/a.md', '@handoff/b.md') + lines(35, 'm'), 'docs/handoff/a.md': lines(40), 'docs/handoff/b.md': lines(40) },
      'docs/HANDOFF.md', null],
    ['PH05', 'editing a stream also checks the total',
      { 'docs/HANDOFF.md': mapOf('@handoff/a.md', '@handoff/b.md', '@handoff/c.md') + lines(34, 'm'), 'docs/handoff/a.md': lines(40), 'docs/handoff/b.md': lines(40), 'docs/handoff/c.md': lines(1) },
      'docs/handoff/a.md',
      'post-edit: the handoff loads 121 lines in total (docs/HANDOFF.md plus docs/handoff/*.md); cap is 120. Close a stream or trim.'],
    ['PH06', 'a stream the map does not import warns',
      { 'docs/HANDOFF.md': mapOf('@handoff/y.md'), 'docs/handoff/x.md': lines(3), 'docs/handoff/y.md': lines(3) }, 'docs/handoff/x.md',
      'post-edit: docs/handoff/x.md is not imported by docs/HANDOFF.md. Add the line @handoff/x.md or delete the file.'],
    ['PH07', 'a stream the map imports is silent, with no README in the directory',
      { 'docs/HANDOFF.md': mapOf('@handoff/x.md'), 'docs/handoff/x.md': lines(3) }, 'docs/handoff/x.md', null],
    ['PH08', 'a map line @docs/handoff/x.md warns with the doubled path',
      { 'docs/HANDOFF.md': mapOf('@docs/handoff/x.md'), 'docs/handoff/x.md': lines(3) }, 'docs/HANDOFF.md',
      'post-edit: docs/HANDOFF.md imports @docs/handoff/x.md, which resolves to docs/docs/handoff/x.md and does not exist (an import resolves from the importing file\'s directory).'],
    ['PH09', 'a map line @handoff/x.md is silent',
      { 'docs/HANDOFF.md': mapOf('@handoff/x.md'), 'docs/handoff/x.md': lines(3) }, 'docs/HANDOFF.md', null],
    ['PH10', 'root CLAUDE.md importing an existing @docs/HANDOFF.md is silent',
      { 'CLAUDE.md': '# C\n\n@docs/HANDOFF.md\n', 'docs/HANDOFF.md': mapOf() }, 'CLAUDE.md', null],
    ['PH11', 'root CLAUDE.md importing an absent @docs/GONE.md warns',
      { 'CLAUDE.md': '# C\n\n@docs/GONE.md\n' }, 'CLAUDE.md',
      'FAIL CLAUDE.md: @docs/GONE.md resolves to docs/GONE.md, which does not exist'],
    ['PH12', 'an @ line inside a fenced block warns nothing',
      { 'docs/HANDOFF.md': '# Handoff\n\n```\n@docs/GONE.md\n```\n' }, 'docs/HANDOFF.md', null],
    ['PH13', 'an @ inside a code span warns nothing',
      { 'docs/HANDOFF.md': '# Handoff\n\nWrite `@docs/GONE.md` as @handoff/x.md in the map.\n', 'docs/handoff/x.md': lines(3) }, 'docs/HANDOFF.md', null],
    ['PH17', 'a mid-line @ outside a code span is an import (the loader\'s rule) and warns when absent',
      { 'docs/HANDOFF.md': '# Handoff\n\nSee @handoff/gone.md.\n' }, 'docs/HANDOFF.md',
      'post-edit: docs/HANDOFF.md imports @handoff/gone.md, which resolves to docs/handoff/gone.md and does not exist (an import resolves from the importing file\'s directory).'],
    ['PH14', 'a stream line @../x.md resolves from the stream directory',
      { 'docs/HANDOFF.md': mapOf('@handoff/x.md'), 'docs/handoff/x.md': '# X\n@../HANDOFF.md\n' }, 'docs/handoff/x.md', null],
    ['PH15', 'a map holding only @handoff/x.md.old does not list x.md',
      { 'docs/HANDOFF.md': mapOf('@handoff/x.md.old'), 'docs/handoff/x.md': lines(3), 'docs/handoff/x.md.old': lines(3) }, 'docs/handoff/x.md',
      'post-edit: docs/handoff/x.md is not imported by docs/HANDOFF.md. Add the line @handoff/x.md or delete the file.'],
    ['PH16', 'a map line @handoff/x.md inside a fence does not list x.md',
      { 'docs/HANDOFF.md': '# Handoff\n\n```\n@handoff/x.md\n```\n', 'docs/handoff/x.md': lines(3) }, 'docs/handoff/x.md',
      'post-edit: docs/handoff/x.md is not imported by docs/HANDOFF.md. Add the line @handoff/x.md or delete the file.'],
  ];
  for (const [id, label, files, edited, expect] of PH) {
    if (!selected(id)) continue;
    const root = path.join(scratch, `ph-${id}`);
    for (const [rel, text] of Object.entries(files)) {
      const f = path.join(root, ...rel.split('/'));
      fs.mkdirSync(path.dirname(f), { recursive: true });
      fs.writeFileSync(f, text);
    }
    const stdin = JSON.stringify({ hook_event_name: 'PostToolUse', tool_name: 'Write', cwd: root, tool_input: { file_path: path.join(root, ...edited.split('/')) } });
    const r = run('post-edit', stdin, envWith({ HOOK_POLICY_FILE: POLICIES.hatchery }));
    let ok = false;
    let detail = '';
    if (r.code !== 0) detail = `exit ${r.code}, expected 0; stderr: ${r.stderr.trim()}`;
    else if (expect === null) { ok = r.stdout === ''; detail = 'expected no stdout'; }
    else {
      const d = parseDecision(r.stdout);
      ok = !!d && d.event === 'PostToolUse' && d.context === expect;
      detail = `expected additionalContext ${JSON.stringify(expect)}`;
    }
    report(id, `${label} -> ${expect === null ? 'no output' : 'message'}`, ok, detail, r.stdout);
  }
}

// ---------- pusher check 7: policy.js --cap-check (docCap, shared with post-edit) ----------
// One scratch root (cap/) with a list file per case, run from that root as the pusher runs it from
// the repository's. A case is [id, label, listed paths, policy file, expected exit, expected stdout
// (a string, or a function of stdout returning true or a reason)]. Every run ends its stdout with
// the sentinel "cap-check: <N> files, <M> over cap" (N files checked against a cap, M FAIL lines);
// check 7 treats a run without it as a FAIL, which is how an older policy.js or a crash shows.
const PUSHER_CAP_LINE = 'l=$(mktemp); git -c core.quotePath=false ls-files -- \'docs/*.md\' > "$l"; node .claude/hooks/policy.js --cap-check "$l"; rm -f "$l"';
{
  const root = path.join(scratch, 'cap');
  const files = {
    'docs/reference/big.md': lines(301),
    'docs/reference/small.md': lines(300),
    'docs/design/proposals/0001-long.md': lines(320),
    'docs/HANDOFF.md': lines(41),
    'docs/handoff/x.md': lines(41),
    'docs/handoff/huge.md': lines(301),
  };
  for (const [rel, text] of Object.entries(files)) {
    const f = path.join(root, ...rel.split('/'));
    fs.mkdirSync(path.dirname(f), { recursive: true });
    fs.writeFileSync(f, text);
  }
  const BIG = 'FAIL docs/reference/big.md: 301 lines, cap 300\n';
  const CAP = [
    ['CAP01', 'exempt doc over 300', ['docs/reference/big.md', 'docs/reference/small.md'],
      scratchPolicy('cap-exempt', { edit: { capExempt: ['docs/reference/big.md'] } }), 0, 'cap-check: 1 files, 0 over cap\n'],
    ['CAP02', 'the same doc, not exempt', ['docs/reference/big.md', 'docs/reference/small.md'],
      POLICIES.hatchery, 1, `${BIG}cap-check: 2 files, 1 over cap\n`],
    ['CAP03', 'exempt by pattern, handoff files still capped at 40', ['docs/HANDOFF.md', 'docs/handoff/x.md', 'docs/reference/big.md'],
      scratchPolicy('cap-all', { edit: { capExempt: ['docs/*'] } }), 1,
      'FAIL docs/HANDOFF.md: 41 lines, cap 40\nFAIL docs/handoff/x.md: 41 lines, cap 40\ncap-check: 2 files, 2 over cap\n'],
    ['CAP04', 'proposal over 300, absent policy', ['docs/design/proposals/0001-long.md'], POLICIES.none, 0,
      'cap-check: 0 files, 0 over cap\n'],
    ['CAP05', 'handoff over 300: one FAIL, the 40', ['docs/handoff/huge.md'], POLICIES.hatchery, 1,
      'FAIL docs/handoff/huge.md: 301 lines, cap 40\ncap-check: 1 files, 1 over cap\n'],
    ['CAP06', 'malformed policy fails loudly', ['docs/reference/small.md'], scratchPolicy('cap-bad', '{ nope'), 1,
      (out) => /^FAIL policy: .+\ncap-check: 0 files, 1 over cap\n$/.test(out) || 'expected one "FAIL policy: ..." line and the sentinel'],
    ['CAP07', 'unknown policy key fails loudly', ['docs/reference/small.md'],
      scratchPolicy('cap-key', { edit: { capExemt: ['docs/*'] } }), 1,
      (out) => /^FAIL policy: .*unknown key edit\.capExemt\ncap-check: 0 files, 1 over cap\n$/.test(out) || 'expected a "FAIL policy: ... unknown key" line and the sentinel'],
    ['CAP08', 'listed file deleted in the working tree is skipped', ['docs/reference/gone.md', 'docs/reference/big.md'],
      POLICIES.hatchery, 1, `${BIG}cap-check: 1 files, 1 over cap\n`],
    ['CAP12', 'listed path unreadable (a directory)', ['docs/reference'], POLICIES.hatchery, 1,
      (out) => /^FAIL docs\/reference: cannot read \(E[A-Z]+\)\ncap-check: 0 files, 1 over cap\n$/.test(out) || 'expected one "FAIL docs/reference: cannot read" line and the sentinel'],
  ];
  for (const [id, label, listed, policy, code, expect] of CAP) {
    if (!selected(id)) continue;
    const list = path.join(root, `list-${id}.txt`);
    fs.writeFileSync(list, listed.map((p) => `${p}\n`).join(''));
    const r = spawnSync(NODE, [path.join(HOOKS, 'policy.js'), '--cap-check', list],
      { cwd: root, encoding: 'utf8', timeout: 30000, env: envWith({ HOOK_POLICY_FILE: policy }) });
    const out = r.stdout || '';
    let ok = r.status === code;
    let detail = `exit ${r.status}, expected ${code}; stderr: ${(r.stderr || '').trim()}`;
    if (ok) {
      const v = typeof expect === 'function' ? expect(out) : out === expect || `expected stdout ${JSON.stringify(expect)}`;
      ok = v === true;
      if (!ok) detail = v;
    }
    report(id, `--cap-check: ${label} -> exit ${code}`, ok, detail, out);
  }
  if (selected('CAP09')) {
    const r = spawnSync(NODE, [path.join(HOOKS, 'policy.js'), '--cap-check'], { encoding: 'utf8', env: envWith({ HOOK_POLICY_FILE: POLICIES.hatchery }) });
    report('CAP09', '--cap-check without a list file -> exit 2, usage line, a FAIL line and the sentinel',
      r.status === 2 && /^usage: /.test(r.stderr || '') && r.stdout === 'FAIL usage: --cap-check needs a list file\ncap-check: 0 files, 1 over cap\n',
      `exit ${r.status}, stderr ${JSON.stringify(r.stderr)}`, r.stdout);
  }
  // An older policy.js does not know the flag; an unknown flag stands in for it: it prints the
  // effective policy, exits 0 and never the sentinel, which check 7 must read as a FAIL.
  if (selected('CAP13')) {
    const list = path.join(root, 'list-CAP13.txt');
    fs.writeFileSync(list, 'docs/reference/big.md\n');
    const r = spawnSync(NODE, [path.join(HOOKS, 'policy.js'), '--cap-chek', list],
      { cwd: root, encoding: 'utf8', env: envWith({ HOOK_POLICY_FILE: POLICIES.hatchery }) });
    report('CAP13', 'an unknown flag (an older policy.js) prints no cap-check line', !/^cap-check: /m.test(r.stdout || ''),
      'the sentinel appeared', r.stdout);
  }
  // A non-ASCII file name over the cap, through the pusher's real line on a scratch git repository:
  // without core.quotePath=false git lists it quoted, as "docs/caf\303\251-big.md", and the file is skipped.
  if (selected('CAP14')) {
    const repo = path.join(scratch, 'capgit');
    fs.mkdirSync(path.join(repo, 'docs'), { recursive: true });
    git(repo, 'init', '-q');
    fs.writeFileSync(path.join(repo, 'docs', 'café-big.md'), lines(301));
    git(repo, 'add', '--', 'docs/café-big.md');
    const line = PUSHER_CAP_LINE.replace('.claude/hooks/policy.js', `"${path.join(HOOKS, 'policy.js').replace(/\\/g, '/')}"`);
    const r = spawnSync('bash', ['-c', line], { cwd: repo, encoding: 'utf8', timeout: 30000, env: envWith({ HOOK_POLICY_FILE: POLICIES.hatchery }) });
    const want = 'FAIL docs/café-big.md: 301 lines, cap 300\ncap-check: 1 files, 1 over cap\n';
    report('CAP14', 'a non-ASCII doc over 300 FAILs through the pusher line', r.stdout === want,
      `expected ${JSON.stringify(want)}; stderr ${(r.stderr || '').trim()}`, r.stdout);
  }
  // CAP10 and CAP11 run PUSHER_CAP_LINE; this case keeps it equal to the backticked command of
  // .claude/agents/pusher.md that holds --cap-check, so the copy above cannot drift.
  // A project that never took the base's agents has no pusher.md: the case does not run there.
  if (selected('CAP15') && fs.existsSync(path.join(__dirname, '..', 'agents', 'pusher.md'))) {
    const spans = fs.readFileSync(path.join(__dirname, '..', 'agents', 'pusher.md'), 'utf8').match(/`[^`\n]+`/g) || [];
    const hits = spans.map((x) => x.slice(1, -1)).filter((x) => x.includes('--cap-check'));
    report('CAP15', 'pusher.md check 7 holds exactly the line the harness tests', hits.length === 1 && hits[0] === PUSHER_CAP_LINE,
      `found ${JSON.stringify(hits)}`);
  }
  // The pusher's line must pass the guard, for the pusher and the main session.
  guardCase('CAP10', { command: PUSHER_CAP_LINE, agent: 'pusher' }, 'allow');
  guardCase('CAP11', { command: PUSHER_CAP_LINE }, 'allow');
}

// ---------- usage-gate (0002-hook-specs-usage.md, section 3) ----------
const home = path.join(scratch, 'home');
fs.mkdirSync(path.join(home, '.claude'), { recursive: true });
// One usage file per session (ADR 0011): every U, UX and UO case runs as session SID and writes its
// state to that session's file; UG cases write it for the session_id in their payload.
const SID = 'sess-gate';
const stateDir = path.join(home, '.claude', 'usage-state');
fs.mkdirSync(stateDir, { recursive: true });
const stateFileFor = (sid) => path.join(stateDir, `${sid}.json`);
const stateFile = stateFileFor(SID);
const gateInput = (extra) => JSON.stringify(Object.assign({ hook_event_name: 'PreToolUse', tool_name: 'Agent', tool_input: {}, session_id: SID }, extra));
// The scratch HOME, and no CLAUDE_CONFIG_DIR from the session running the suite, so the account
// label is read from the scratch home (none there unless a case writes one).
const gateEnv = (extra) => envWith(Object.assign({ HOME: home, USERPROFILE: home, CLAUDE_CONFIG_DIR: undefined }, extra));
const HOLD_MSG = 'Usage gate: 5h 55%, 7d 10%. 5h at or above 50%: Do not start new subagents. Let in-flight agents finish, run the handoff skill, commit from the main session, then report. Lifting this early is the user\'s decision (docs/design/decisions/0006-hook-policy-file.md).';
const STOP_MSG = 'Usage gate: 5h 85%, 7d 10%. 5h at or above 80%: Stop all work, run the handoff skill, commit WIP from the main session, end the turn.';
const HOLD_85 = HOLD_MSG.replace('5h 55%', '5h 85%');
// A HOLD deny names each grant that does not apply, one clause each, before "Lifting". The U and UO
// cases use the legacy file only, with no scope key: a user grant (ADR 0006, The HOLD override).
const withClause = (msg, clause) => msg.replace(' Lifting this early', ` ${clause} Lifting this early`);
const LEGACY = 'usage-gate-override.json';
const OV_EXPIRED = `Expired override files: ${LEGACY}.`;
const OV_MALFORMED = `An override file exists but is malformed (${LEGACY}; see ADR 0006, The HOLD override).`;
const nowSec = Math.floor(Date.now() / 1000);
// The base has no deny line, so the STOP cases run under the Hatchery fixture with deny at 80.
const deny80 = scratchPolicy('deny-80', Object.assign({}, HATCHERY_POLICY, { usage: Object.assign({}, HATCHERY_POLICY.usage, { deny: 80 }) }));
// [id, pct, expected deny reason or null for allow, override file contents or undefined, policy or
// undefined for the Hatchery fixture]
const U = [
  ['U01', 55, HOLD_MSG],
  ['U02', 85, STOP_MSG, undefined, deny80],
  ['U03', 20, null],
  ['U04', 55, null, { until: nowSec + 3600, reason: 'test: user instruction' }],
  ['U05', 55, withClause(HOLD_MSG, OV_EXPIRED), { until: nowSec - 1, reason: 'test: expired, and the deny says so' }],
  ['U06', 85, STOP_MSG, { until: nowSec + 3600, reason: 'test: override does not lift STOP' }, deny80],
  ['U07', 55, withClause(HOLD_MSG, OV_MALFORMED), 'not json'],
  ['U10', 55, withClause(HOLD_MSG, OV_MALFORMED), { reason: 'test: no until, malformed' }],
  ['U08', 85, HOLD_85],
  ['U09', 85, null, { until: nowSec + 3600, reason: 'test: no deny line, the override lifts 85' }],
];
for (const [id, pct, expect, override, policy] of U) {
  if (!selected(id)) continue;
  fs.writeFileSync(stateFile, JSON.stringify({
    updatedAt: Math.floor(Date.now() / 1000),
    five_hour: { used_percentage: pct },
    seven_day: { used_percentage: 10 },
  }));
  const ovPath = path.join(home, '.claude', 'usage-gate-override.json');
  if (override === undefined) fs.rmSync(ovPath, { force: true });
  else fs.writeFileSync(ovPath, typeof override === 'string' ? override : JSON.stringify(override));
  const env = gateEnv({ HOOK_POLICY_FILE: policy || POLICIES.hatchery });
  const r = run('usage-gate', gateInput(), env);
  let ok;
  let detail;
  if (expect === null) { ok = r.code === 0 && r.stdout === ''; detail = 'expected allow (exit 0, no stdout)'; }
  else {
    const d = parseDecision(r.stdout);
    ok = r.code === 0 && !!d && d.decision === 'deny' && d.reason === expect;
    detail = `expected reason ${JSON.stringify(expect)}`;
  }
  const under = policy ? ', deny line at 80' : ', no deny line';
  report(id, `usage ${pct}%${override === undefined ? '' : ', override'}${under} -> ${expect === null ? 'allow' : 'deny'}`, ok, detail, r.stdout);
}

// Staleness, reset windows and the example policies. state null means no file.
// [id, label, state, policy file, expected deny-reason prefix or null for allow, stderr must include]
const fresh = (five, seven, extra) => Object.assign({ updatedAt: nowSec, five_hour: { used_percentage: five }, seven_day: { used_percentage: seven } }, extra);
const staleDeny = scratchPolicy('stale-deny', { usage: { whenStale: 'deny' } });
const UX = [
  ['UX01', 'stale data, whenStale allow', fresh(95, 10, { updatedAt: nowSec - 3 * 3600 }), POLICIES.hatchery, null, '180 min old; allowing'],
  ['UX02', 'stale data, whenStale deny', fresh(10, 10, { updatedAt: nowSec - 3 * 3600 }), staleDeny,
    'Usage gate: usage data is 180 min old, and the policy denies delegation without fresh data. Check usage with /usage'],
  ['UX03', 'no data, whenStale deny', null, staleDeny, 'Usage gate: no usage data'],
  ['UX04', 'no data, whenStale allow', null, POLICIES.hatchery, null, 'no usage data'],
  ['UX05', 'a window past its reset no longer counts', fresh(95, 10, { five_hour: { used_percentage: 95, resets_at: nowSec - 60 } }), POLICIES.hatchery, null],
  ['UX06', 'a window before its reset counts (HOLD: the base has no deny line)', fresh(95, 10, { five_hour: { used_percentage: 95, resets_at: nowSec + 60 } }), POLICIES.hatchery, 'Usage gate: 5h 95%, 7d 10%. 5h at or above 50%'],
  ['UX07', 'single-branch example, 85%', fresh(85, 10), POLICIES.singleBranch, 'Usage gate: 5h 85%, 7d 10%. 5h at or above 80%: Do not start new subagents. Let in-flight agents finish and commit'],
  ['UX08', 'single-branch example, 92%', fresh(92, 10), POLICIES.singleBranch, 'Usage gate: 5h 92%, 7d 10%. 5h at or above 90%: Stop all work; commit what is green'],
  ['UX09', 'single-branch example, 60%', fresh(60, 10), POLICIES.singleBranch, null],
  ['UX10', 'task-branches example, 80%', fresh(10, 80), POLICIES.taskBranches, 'Usage gate: 5h 10%, 7d 80%. 7d at or above 75%: No new implementers'],
  ['UX11', 'malformed policy: base defaults', fresh(55, 10), scratchPolicy('ug-bad', '[]'), HOLD_MSG, 'using the base defaults'],
  ['UX12', 'pre-merge file with updated_at only', { updated_at: nowSec, five_hour: { used_percentage: 95 } }, POLICIES.hatchery, null, 'no usage data'],
  ['UX13', 'per-meter example: 5h judged by its own hold', fresh(65, 10), POLICIES.perMeter, 'Usage gate: 5h 65%, 7d 10%. 5h at or above 60%'],
  ['UX14', 'per-meter example: 7d judged by its own hold', fresh(10, 80), POLICIES.perMeter, 'Usage gate: 5h 10%, 7d 80%. 7d at or above 75%'],
  ['UX15', 'per-meter example: 7d 70 is under its own hold', fresh(10, 70), POLICIES.perMeter, null],
  ['UX16', 'per-meter example: 7d 91 is its own deny', fresh(10, 91), POLICIES.perMeter, 'Usage gate: 5h 10%, 7d 91%. 7d at or above 90%'],
  ['UX17', 'per-meter hold above deny is malformed', fresh(55, 10), scratchPolicy('ug-meter-bad', { usage: { fiveHour: { hold: 90, deny: 80 } } }), HOLD_MSG, 'using the base defaults'],
  ['UX18', 'a string deny is malformed', fresh(55, 10), scratchPolicy('ug-deny-string', { usage: { deny: 'off' } }), HOLD_MSG, 'using the base defaults'],
  ['UX19', 'a string per-meter deny is malformed', fresh(55, 10), scratchPolicy('ug-meter-deny-string', { usage: { sevenDay: { hold: null, deny: 'none' } } }), HOLD_MSG, 'using the base defaults'],
  ['UX20', 'hold 90 with no deny line is valid', fresh(55, 10), scratchPolicy('ug-hold-high', { usage: { hold: 90 } }), null],
  ['UX21', 'per-meter hold above the inherited deny is malformed', fresh(55, 10), scratchPolicy('ug-inherited-deny', { usage: { deny: 80, sevenDay: { hold: 90, deny: null } } }), HOLD_MSG, 'using the base defaults'],
];
fs.rmSync(path.join(home, '.claude', 'usage-gate-override.json'), { force: true });
for (const [id, label, state, policy, expect, stderrHas] of UX) {
  if (!selected(id)) continue;
  if (state === null) fs.rmSync(stateFile, { force: true }); else fs.writeFileSync(stateFile, JSON.stringify(state));
  const r = run('usage-gate', gateInput(), gateEnv({ HOOK_POLICY_FILE: policy }));
  let ok;
  let detail;
  if (expect === null) { ok = r.code === 0 && r.stdout === ''; detail = 'expected allow (exit 0, no stdout)'; }
  else {
    const d = parseDecision(r.stdout);
    ok = r.code === 0 && !!d && d.decision === 'deny' && d.reason.startsWith(expect);
    detail = `expected a reason starting ${JSON.stringify(expect)}`;
  }
  if (ok && stderrHas && !r.stderr.includes(stderrHas)) { ok = false; detail = `stderr lacks ${JSON.stringify(stderrHas)}: ${r.stderr}`; }
  report(id, `${label} -> ${expect === null ? 'allow' : 'deny'}`, ok, detail, r.stdout);
}

// The override per meter and the session variable. [id, label, state, override file contents or
// undefined, policy, extra env, expected deny-reason prefix or null for allow, stderr must include]
const ovFile = path.join(home, '.claude', 'usage-gate-override.json');
const envPolicy = scratchPolicy('ug-env', { usage: { hold: 75, deny: 100, overrideEnv: 'HOOK_TEST_GATE_OVERRIDE' } });
const HOLD_7D70_MALFORMED = withClause(HOLD_MSG.replace('5h 55%, 7d 10%. 5h at', '5h 10%, 7d 70%. 7d at'), OV_MALFORMED);
const meter5Deny = scratchPolicy('ug-5h-deny', { usage: { fiveHour: { hold: null, deny: 80 } } });
const ov = (meters, until) => Object.assign({ until: until || nowSec + 3600, reason: 'test: owner' }, meters === undefined ? {} : { meters });
const UO = [
  ['UO01', 'override with no meters lifts HOLD on both', fresh(60, 70), ov(), POLICIES.hatchery, {}, null, 'lifted on 5h and 7d'],
  ['UO02', 'override on 7d lifts a 7d HOLD', fresh(10, 70), ov(['7d']), POLICIES.hatchery, {}, null, 'lifted on 7d'],
  ['UO03', 'override on 7d keeps a 5h HOLD', fresh(60, 70), ov(['7d']), POLICIES.hatchery, {},
    'Usage gate: 5h 60%, 7d 70%. 5h at or above 50%, 7d at or above 50%: Do not start new subagents. Let in-flight agents finish, run the handoff skill, commit from the main session, then report. The override in force lifts only 7d. Lifting this early'],
  ['UO04', 'override on 5h keeps a 7d HOLD', fresh(10, 70), ov(['5h']), POLICIES.hatchery, {}, 'Usage gate: 5h 10%, 7d 70%. 7d at or above 50%'],
  ['UO05', 'an unknown meter name lifts nothing', fresh(10, 70), ov(['7d', 'weekly']), POLICIES.hatchery, {}, 'Usage gate: 5h 10%, 7d 70%. 7d at or above 50%'],
  ['UO06', 'meters not a list lifts nothing', fresh(10, 70), ov('7d'), POLICIES.hatchery, {}, 'Usage gate: 5h 10%, 7d 70%. 7d at or above 50%'],
  ['UO07', 'expired per-meter override lifts nothing', fresh(10, 70), ov(['7d'], nowSec - 1), POLICIES.hatchery, {}, 'Usage gate: 5h 10%, 7d 70%. 7d at or above 50%'],
  ['UO08', 'no deny line (task-branches, was deny 100): the override lifts both meters at 95%', fresh(90, 95), ov(), POLICIES.taskBranches, {}, null, 'lifted on 5h and 7d'],
  ['UO09', 'no deny line (task-branches, was deny 100): no override, 95% 7d is HOLD', fresh(10, 95), undefined, POLICIES.taskBranches, {}, 'Usage gate: 5h 10%, 7d 95%. 7d at or above 75%'],
  ['UO10', 'overrideEnv set to 1 lifts HOLD', fresh(80, 95), undefined, envPolicy, { HOOK_TEST_GATE_OVERRIDE: '1' }, null, 'lifted for this session by HOOK_TEST_GATE_OVERRIDE=1'],
  ['UO23', 'overrideEnv set to 1 with an expired override file: allow', fresh(80, 95), ov(undefined, nowSec - 1), envPolicy, { HOOK_TEST_GATE_OVERRIDE: '1' }, null, 'lifted for this session by HOOK_TEST_GATE_OVERRIDE=1'],
  ['UO11', 'overrideEnv unset: HOLD', fresh(80, 95), undefined, envPolicy, { HOOK_TEST_GATE_OVERRIDE: undefined }, 'Usage gate: 5h 80%, 7d 95%. 5h at or above 75%, 7d at or above 75%'],
  ['UO12', 'overrideEnv does not lift the deny line', fresh(10, 100), undefined, envPolicy, { HOOK_TEST_GATE_OVERRIDE: '1' }, 'Usage gate: 5h 10%, 7d 100%. 7d at or above 100%'],
  ['UO13', 'overrideEnv that is not a variable name is malformed', fresh(55, 10), undefined,
    scratchPolicy('ug-env-bad', { usage: { overrideEnv: 'A B' } }), {}, HOLD_MSG, 'using the base defaults'],
  ['UO14', 'no deny line: 7d at 100 is HOLD', fresh(10, 100), undefined, POLICIES.hatchery, {}, 'Usage gate: 5h 10%, 7d 100%. 7d at or above 50%: Do not start new subagents'],
  ['UO15', 'no deny line: the override on 7d lifts 7d at 100', fresh(10, 100), ov(['7d']), POLICIES.hatchery, {}, null, 'lifted on 7d'],
  ['UO16', 'no deny line: the override on 7d keeps a 5h HOLD at 100', fresh(100, 100), ov(['7d']), POLICIES.hatchery, {},
    'Usage gate: 5h 100%, 7d 100%. 5h at or above 50%, 7d at or above 50%'],
  ['UO17', 'deny on 5h only: 7d at 100 lifted by the override', fresh(10, 100), ov(['7d']), meter5Deny, {}, null, 'lifted on 7d'],
  ['UO18', 'deny on 5h only: 5h at 85 is STOP despite the override', fresh(85, 100), ov(), meter5Deny, {}, 'Usage gate: 5h 85%, 7d 100%. 5h at or above 80%: Stop all work'],
  ['UO19', 'meters null lifts nothing, and the deny says malformed', fresh(10, 70), ov(null), POLICIES.hatchery, {}, HOLD_7D70_MALFORMED],
  ['UO20', 'meters [] lifts nothing, and the deny says malformed', fresh(10, 70), ov([]), POLICIES.hatchery, {}, HOLD_7D70_MALFORMED],
  ['UO21', 'overrideEnv set to "true", not "1": HOLD stays', fresh(80, 95), undefined, envPolicy, { HOOK_TEST_GATE_OVERRIDE: 'true' }, 'Usage gate: 5h 80%, 7d 95%. 5h at or above 75%, 7d at or above 75%'],
  ['UO22', 'expired override: the deny says expired', fresh(10, 70), ov(['7d'], nowSec - 1), POLICIES.hatchery, {},
    withClause(HOLD_MSG.replace('5h 55%, 7d 10%. 5h at', '5h 10%, 7d 70%. 7d at'), OV_EXPIRED)],
];
for (const [id, label, state, override, policy, extraEnv, expect, stderrHas] of UO) {
  if (!selected(id)) continue;
  fs.writeFileSync(stateFile, JSON.stringify(state));
  if (override === undefined) fs.rmSync(ovFile, { force: true }); else fs.writeFileSync(ovFile, JSON.stringify(override));
  const r = run('usage-gate', gateInput(), gateEnv(Object.assign({ HOOK_POLICY_FILE: policy }, extraEnv)));
  let ok;
  let detail;
  if (expect === null) { ok = r.code === 0 && r.stdout === ''; detail = 'expected allow (exit 0, no stdout)'; }
  else {
    const d = parseDecision(r.stdout);
    ok = r.code === 0 && !!d && d.decision === 'deny' && d.reason.startsWith(expect);
    detail = `expected a reason starting ${JSON.stringify(expect)}`;
  }
  if (ok && stderrHas && !r.stderr.includes(stderrHas)) { ok = false; detail = `stderr lacks ${JSON.stringify(stderrHas)}: ${r.stderr}`; }
  report(id, `${label} -> ${expect === null ? 'allow' : 'deny'}`, ok, detail, r.stdout);
}
fs.rmSync(ovFile, { force: true });

// The per-session file and the account label (ADR 0011). A file for another session, no usable
// session_id, and a file labelled with another account than the one logged in are no usage data
// (the stale path); a matching or unknown label is judged, and the deny names the file's email.
// [id, label, state for this session or null, files { relative path under the scratch HOME (CFG/
// for the scratch CLAUDE_CONFIG_DIR): contents }, input overrides, policy or undefined for the
// Hatchery fixture, expected deny-reason prefix or null for allow, stderr must include, use CFG]
{
  const cfgDir = path.join(scratch, 'gate-config');
  const label = (a) => ({ uuid: a.uuid, organizationUuid: a.org, email: a.email });
  const login = (a) => JSON.stringify({ oauthAccount: { accountUuid: a.uuid, organizationUuid: a.org, emailAddress: a.email }, numStartups: 3 });
  const A = { uuid: 'uuid-a', org: 'org-1', email: 'a@example.com' };
  const B = { uuid: 'uuid-b', org: 'org-1', email: 'b@example.com' };
  const A2 = { uuid: 'uuid-a', org: 'org-2', email: 'a@example.com' };
  const withAcct = (a, five) => Object.assign(fresh(five, 10), { account: label(a) });
  const HOLD_A = HOLD_MSG.replace('Usage gate:', 'Usage gate (a@example.com):');
  const OTHER = 'no usage data for this account (this session\'s file is for a@example.com, logged in as b@example.com)';
  const UA = [
    ['U11', 'a file for another session only: no usage data', null, {}, {}, undefined, null,
      'no usage data (the status line has not written any for this session); allowing', 'other'],
    ['U12', 'a file for another session only, whenStale deny', null, {}, {}, staleDeny,
      'Usage gate: no usage data (the status line has not written any for this session), and the policy denies', undefined, 'other'],
    ['U13', 'no session_id in the input: no usage data', fresh(95, 10), {}, { session_id: undefined }, undefined, null,
      'no usage data (no usable session_id in the hook input)'],
    ['U14', 'a session_id that is not a plain name: no usage data', fresh(95, 10), {}, { session_id: `../${SID}` }, undefined, null,
      'no usable session_id'],
    ['U15', 'label matches the login: judged, the deny names the email', withAcct(A, 55), { '.claude.json': login(A) }, {}, undefined, HOLD_A],
    ['U16', 'label differs from the login: stale, allow, the reason on stderr', withAcct(A, 95), { '.claude.json': login(B) }, {}, undefined, null,
      `${OTHER}; allowing`],
    ['U17', 'label differs, whenStale deny: denied as no data', withAcct(A, 10), { '.claude.json': login(B) }, {}, staleDeny,
      `Usage gate: ${OTHER}, and the policy denies delegation without fresh data.`],
    ['U18', 'same account uuid, another organization: stale', withAcct(A, 95), { '.claude.json': login(A2) }, {}, undefined, null,
      'no usage data for this account'],
    ['U19', 'login unknown (no config file): the file is judged', withAcct(A, 55), {}, {}, undefined, HOLD_A],
    ['U20', 'file label unknown, login known: judged, no email', fresh(55, 10), { '.claude.json': login(B) }, {}, undefined, HOLD_MSG],
    ['U21', 'torn .claude.json: login unknown, the file is judged', withAcct(A, 55), { '.claude.json': login(B).slice(0, 30) }, {}, undefined, HOLD_A],
    ['U22', 'CLAUDE_CONFIG_DIR wins over the home directory for the label', withAcct(A, 95),
      { '.claude.json': login(A), 'CFG/.claude.json': login(B) }, {}, undefined, null, `${OTHER}; allowing`, undefined, true],
    ['U23', 'legacy .config.json in the config home wins over .claude.json', withAcct(A, 95),
      { '.claude.json': login(A), '.config.json': login(B) }, {}, undefined, null, `${OTHER}; allowing`],
    ['U24', 'a .config.json without oauthAccount falls through to .claude.json', withAcct(A, 55),
      { '.claude.json': login(A), '.config.json': '{"numStartups":1}' }, {}, undefined, HOLD_A],
  ];
  for (const [id, lbl, state, files, input, policy, expect, stderrHas, otherSession, useCfg] of UA) {
    if (!selected(id)) continue;
    fs.rmSync(stateDir, { recursive: true, force: true });
    fs.mkdirSync(stateDir, { recursive: true });
    fs.rmSync(cfgDir, { recursive: true, force: true });
    fs.mkdirSync(cfgDir, { recursive: true });
    for (const f of ['.claude.json', '.config.json']) fs.rmSync(path.join(home, f), { force: true });
    if (state) fs.writeFileSync(stateFile, JSON.stringify(state));
    if (otherSession) fs.writeFileSync(stateFileFor(otherSession), JSON.stringify(fresh(95, 10)));
    for (const [rel, contents] of Object.entries(files)) {
      fs.writeFileSync(rel.startsWith('CFG/') ? path.join(cfgDir, rel.slice(4)) : path.join(home, rel), contents);
    }
    const r = run('usage-gate', gateInput(input),
      gateEnv({ HOOK_POLICY_FILE: policy || POLICIES.hatchery, CLAUDE_CONFIG_DIR: useCfg ? cfgDir : undefined }));
    let ok;
    let detail;
    if (expect === null) { ok = r.code === 0 && r.stdout === ''; detail = 'expected allow (exit 0, no stdout)'; }
    else {
      const d = parseDecision(r.stdout);
      ok = r.code === 0 && !!d && d.decision === 'deny' && d.reason.startsWith(expect);
      detail = `expected a reason starting ${JSON.stringify(expect)}`;
    }
    if (ok && stderrHas && !r.stderr.includes(stderrHas)) { ok = false; detail = `stderr lacks ${JSON.stringify(stderrHas)}: ${r.stderr}`; }
    report(id, `${lbl} -> ${expect === null ? 'allow' : 'deny'}`, ok, detail, r.stdout);
  }
  for (const f of ['.claude.json', '.config.json']) fs.rmSync(path.join(home, f), { force: true });
  fs.rmSync(cfgDir, { recursive: true, force: true });
}

// The grant directory (ADR 0006, The HOLD override): scope by session, project or user, matched
// against session_id and cwd on stdin. All under the scratch HOME; ~/.claude is never read.
// [id, label, state, grant files { name: contents } (a string is written raw, DIR makes a
// subdirectory holding a user grant), legacy file contents or undefined, payload (an object merged
// into the PreToolUse input, or a raw string), expected exact deny reason or null for allow,
// stderr must include (string or list), policy or undefined for the Hatchery fixture]
const grantDir = path.join(home, '.claude', 'usage-gate-overrides');
const DIR = Symbol('subdirectory');
const PROJ = path.join(scratch, 'proj');
const back = (p) => p.replace(/\//g, '\\');
const g = (extra) => Object.assign({ until: nowSec + 3600, reason: 'test: owner' }, extra);
const at = { session_id: 'abc', cwd: PROJ };
const withClauses = (msg, ...clauses) => withClause(msg, clauses.join(' '));
const C = {
  session: (id) => `An override exists for session ${id}, not this one.`,
  project: (p) => `An override exists for project ${p}, not this one.`,
  expired: (...names) => `Expired override files: ${names.join(', ')}.`,
  malformed: (n) => `An override file exists but is malformed (${n}; see ADR 0006, The HOLD override).`,
};
const HOLD_BOTH = HOLD_MSG.replace('5h 55%, 7d 10%. 5h at or above 50%', '5h 60%, 7d 70%. 5h at or above 50%, 7d at or above 50%');
const isWin = process.platform === 'win32';
const UG = [
  ['UG01', 'session grant for this session lifts HOLD', fresh(55, 10), { 'session-abc.json': g({ session: 'abc' }) }, undefined, at, null,
    'lifted on 5h and 7d by override session-abc.json (session abc)'],
  ['UG02', 'session grant for another session: not this one', fresh(55, 10), { 'session-abc.json': g({ session: 'abc' }) }, undefined,
    { session_id: 'xyz', cwd: PROJ }, withClauses(HOLD_MSG, C.session('abc'))],
  ['UG03', 'project grant, cwd at the path', fresh(55, 10), { 'project-proj.json': g({ project: PROJ }) }, undefined, at, null, `(project ${PROJ})`],
  ['UG04', 'project grant, cwd under the path', fresh(55, 10), { 'project-proj.json': g({ project: PROJ }) }, undefined,
    { session_id: 'pq', cwd: path.join(PROJ, 'sub', 'deeper') }, null],
  ['UG05', 'project grant, cwd beside it with the path as a prefix', fresh(55, 10), { 'project-proj.json': g({ project: PROJ }) }, undefined,
    { session_id: 'pq', cwd: `${PROJ}-other` }, withClauses(HOLD_MSG, C.project(PROJ))],
  ['UG06', 'project written with / and a trailing /, cwd with \\', fresh(55, 10), { 'p.json': g({ project: `${fwd(PROJ)}/` }) }, undefined,
    { session_id: 'pq', cwd: `${back(PROJ)}\\sub` }, null],
  ['UG07', 'project written with \\, cwd with /', fresh(55, 10), { 'p.json': g({ project: back(PROJ) }) }, undefined,
    { session_id: 'pq', cwd: `${fwd(PROJ)}/sub` }, null],
  ['UG08', `project in another case: ${isWin ? 'applies on Windows' : 'out of scope off Windows'}`, fresh(55, 10),
    { 'p.json': g({ project: PROJ }) }, undefined, { session_id: 'pq', cwd: PROJ.toUpperCase() }, isWin ? null : withClauses(HOLD_MSG, C.project(PROJ))],
  ['UG09', 'user grant applies with any session and cwd', fresh(55, 10), { 'user.json': g() }, undefined,
    { session_id: 'xyz', cwd: path.join(scratch, 'elsewhere') }, null, 'by override user.json (user)'],
  ['UG10', 'legacy file beside a directory grant: the legacy user grant lifts', fresh(55, 10), { 'session-other.json': g({ session: 'other' }) }, g(),
    at, null, `by override ${LEGACY} (user)`],
  ['UG11', 'the legacy file is classified last, after a later-named directory file', fresh(55, 10),
    { 'zz-session.json': g({ session: 'other' }) }, g({ until: nowSec - 1 }), at, withClauses(HOLD_MSG, C.session('other'), C.expired(LEGACY))],
  ['UG12', 'both scope keys: malformed', fresh(55, 10), { 'both.json': g({ session: 'abc', project: PROJ }) }, undefined, at,
    withClauses(HOLD_MSG, C.malformed('both.json'))],
  ['UG13', 'a relative project: malformed', fresh(55, 10), { 'rel.json': g({ project: 'rel/proj' }) }, undefined, { session_id: 'pq', cwd: path.join(PROJ, 'rel', 'proj') },
    withClauses(HOLD_MSG, C.malformed('rel.json'))],
  ['UG14', 'an empty session and a numeric project: malformed', fresh(55, 10), { 'a.json': g({ session: '' }), 'b.json': g({ project: 5 }) }, undefined, at,
    withClauses(HOLD_MSG, C.malformed('a.json'), C.malformed('b.json'))],
  ['UG15', 'two files lifting one meter each lift both', fresh(60, 70),
    { 'a-5h.json': g({ meters: ['5h'], session: 'abc' }), 'b-7d.json': g({ meters: ['7d'], project: PROJ }) }, undefined, at, null,
    ['lifted on 5h by override a-5h.json (session abc)', `lifted on 7d by override b-7d.json (project ${PROJ})`]],
  ['UG16', 'one of two meter grants out of scope: the lift in force and the clause', fresh(60, 70),
    { 'a-5h.json': g({ meters: ['5h'], session: 'other' }), 'b-7d.json': g({ meters: ['7d'], session: 'abc' }) }, undefined, at,
    withClauses(HOLD_BOTH, 'The override in force lifts only 7d.', C.session('other'))],
  ['UG17', 'clauses in file-name order across out of scope, expired and malformed', fresh(55, 10),
    { 'a-session.json': g({ session: 'other' }), 'b-expired.json': g({ until: nowSec - 1 }), 'c-malformed.json': 'not json' }, undefined, at,
    withClauses(HOLD_MSG, C.session('other'), C.expired('b-expired.json'), C.malformed('c-malformed.json'))],
  ['UG18', 'each file classified once: malformed before expired, expired before out of scope', fresh(55, 10),
    { 'a.json': g({ until: nowSec - 1, session: 'abc', project: PROJ }), 'b.json': g({ until: nowSec - 1, session: 'other' }) }, undefined, at,
    withClauses(HOLD_MSG, C.malformed('a.json'), C.expired('b.json'))],
  // Without a session_id there is no usage file to judge (ADR 0011): the stale path, before any grant.
  ['UG19', 'stdin without session_id: no usage data, the stale path', fresh(55, 10), { 'session-abc.json': g({ session: 'abc' }) }, undefined,
    { cwd: PROJ }, null, 'no usage data (no usable session_id in the hook input); allowing'],
  ['UG20', 'stdin without cwd: no project grant applies', fresh(55, 10), { 'project-proj.json': g({ project: PROJ }) }, undefined,
    { session_id: 'abc' }, withClauses(HOLD_MSG, C.project(PROJ))],
  ['UG21', 'unparseable stdin: no usage data, the stale path', fresh(55, 10), { 'session-abc.json': g({ session: 'abc' }) }, undefined,
    'not json', null, 'no usage data (no usable session_id in the hook input); allowing'],
  ['UG22', 'unparseable stdin under whenStale deny: denied, no grant consulted', fresh(55, 10), { 'user.json': g() }, undefined, 'not json',
    'Usage gate: no usage data (no usable session_id in the hook input), and the policy denies delegation without fresh data. Check usage with /usage in an interactive session; the status line refreshes the data while a session is open.',
    undefined, staleDeny],
  ['UG23', 'other names and subdirectories in the directory are ignored', fresh(55, 10), { 'notes.txt': 'not json', 'nested.json': DIR }, undefined, at,
    HOLD_MSG],
  ['UG24', 'a session grant does not lift the deny line', fresh(85, 10), { 'session-abc.json': g({ session: 'abc' }) }, undefined, at, STOP_MSG,
    undefined, deny80],
  ['UG25', 'every expired file shares one clause, placed where the first falls', fresh(55, 10),
    { 'a-expired.json': g({ until: nowSec - 1 }), 'b-malformed.json': 'not json', 'c-expired.json': g({ until: nowSec - 1, session: 'abc' }) },
    g({ until: nowSec - 1 }), at, withClauses(HOLD_MSG, C.expired('a-expired.json', 'c-expired.json', LEGACY), C.malformed('b-malformed.json'))],
  ['UG26', 'a reason that is not a string: the lift still notes, with no reason given', fresh(55, 10), { 'user.json': g({ reason: 5 }) }, undefined, at, null,
    ['by override user.json (user) until', ': no reason given']],
];
for (const [id, label, state, files, legacy, payload, expect, stderrHas, policy] of UG) {
  if (!selected(id)) continue;
  // The state goes to the file of the payload's session; a payload without one has no file.
  fs.rmSync(stateDir, { recursive: true, force: true });
  fs.mkdirSync(stateDir, { recursive: true });
  if (typeof payload === 'object' && payload.session_id) fs.writeFileSync(stateFileFor(payload.session_id), JSON.stringify(state));
  fs.rmSync(grantDir, { recursive: true, force: true });
  fs.mkdirSync(grantDir, { recursive: true });
  for (const [name, contents] of Object.entries(files)) {
    if (contents === DIR) {
      fs.mkdirSync(path.join(grantDir, name));
      fs.writeFileSync(path.join(grantDir, name, 'user.json'), JSON.stringify(g()));
    } else fs.writeFileSync(path.join(grantDir, name), typeof contents === 'string' ? contents : JSON.stringify(contents));
  }
  if (legacy === undefined) fs.rmSync(ovFile, { force: true }); else fs.writeFileSync(ovFile, JSON.stringify(legacy));
  const stdin = typeof payload === 'string' ? payload
    : JSON.stringify(Object.assign({ hook_event_name: 'PreToolUse', tool_name: 'Agent', tool_input: {} }, payload));
  const r = run('usage-gate', stdin, gateEnv({ HOOK_POLICY_FILE: policy || POLICIES.hatchery }));
  let ok;
  let detail;
  if (expect === null) { ok = r.code === 0 && r.stdout === ''; detail = 'expected allow (exit 0, no stdout)'; }
  else {
    const d = parseDecision(r.stdout);
    ok = r.code === 0 && !!d && d.decision === 'deny' && d.reason === expect;
    detail = `expected reason ${JSON.stringify(expect)}`;
  }
  for (const want of [].concat(stderrHas || [])) {
    if (ok && !r.stderr.includes(want)) { ok = false; detail = `stderr lacks ${JSON.stringify(want)}: ${r.stderr}`; }
  }
  report(id, `${label} -> ${expect === null ? 'allow' : 'deny'}`, ok, detail, r.stdout);
}
fs.rmSync(grantDir, { recursive: true, force: true });
fs.rmSync(ovFile, { force: true });

// The account pool (ADR 0011, usage.pool): asked only when the gate is about to deny at HOLD. The
// command is [node, stub, fixture, ...flags] with the stub and fixtures in the scratch dir, never a
// program on PATH; the missing-program case names one that does not exist. The session is a@ (file
// label and login) unless the case makes the label unknown. A marker file shows whether the stub ran.
// [id, label, fixture (object, raw string, or null for no stub), stub flags or a whole argv
// (command), pool extra (timeoutSeconds), state five-hour %, label known, policy extra, expected exact
// deny reason or null for allow, marker expected (true/false/undefined for not checked)]
{
  const stub = path.join(scratch, 'pool-stub.js');
  fs.writeFileSync(stub, [
    '// SPDX-License-Identifier: GPL-3.0-or-later',
    '// Test stub for usage.pool: prints the fixture file; --exit N, --sleep MS, --marker FILE.',
    'const fs = require("fs");',
    'const a = process.argv.slice(2);',
    'const opt = (k) => { const i = a.indexOf(k); return i >= 0 ? a[i + 1] : null; };',
    'if (opt("--marker")) fs.writeFileSync(opt("--marker"), "ran");',
    'const out = fs.readFileSync(a[0], "utf8");',
    'const finish = () => { process.stdout.write(out); process.exit(Number(opt("--exit") || 0)); };',
    'if (opt("--sleep")) setTimeout(finish, Number(opt("--sleep"))); else finish();',
    '',
  ].join('\n'));
  const A_EMAIL = 'a@example.com';
  const row = (number, email, p5, p7, extra) => Object.assign({ number, email, active: number === 1, alias: null, disabled: false, usageStatus: 'ok',
    usage: { fiveHour: { pct: p5, resetsAt: '2026-10-16T12:00:00Z' }, sevenDay: { pct: p7, resetsAt: '2026-10-19T06:00:00Z', expectedPct: 40 } },
    usageFetchedAt: '2026-10-16T10:00:00Z', usageAgeSeconds: 120, loginExpiresAt: null, futureField: { x: 1 } }, extra);
  const list = (...rows) => ({ schemaVersion: 1, activeAccountNumber: 1, accounts: rows });
  const HOLD_A = HOLD_MSG.replace('Usage gate:', `Usage gate (${A_EMAIL}):`);
  const room = (email, n, m, p5, p7, unknown) => ` Report to the owner: account ${email} (slot ${n}) had room ${m} min ago: 5h ${p5}%, 7d ${p7}%` +
    `${unknown ? ' (this session\'s account not excluded)' : ''}. The next session is the owner's to start there (cswap switch ${n}, or cswap run ${n}).`;
  const noRoom = (readings, unknown) => ` No other account has room (${readings})${unknown ? ' (this session\'s account not excluded)' : ''}; an override grant is still the lift (ADR 0006).`;
  const MISSING = 'hatchery-no-such-program-0011';
  const UP = [
    ['UP01', 'pool null (the base): the HOLD deny has no pool text', null, [], {}, 55, true, {}, HOLD_A],
    ['UP02', 'room: the other account with the largest margin is named', list(row(1, A_EMAIL, 5, 5), row(2, 'b@example.com', 20, 30), row(3, 'c@example.com', 10, 45, { usageAgeSeconds: 600 })),
      [], {}, 55, true, {}, HOLD_A + room('b@example.com', 2, 2, 20, 30), true],
    ['UP03', 'no room: every other account at or above a hold', list(row(1, A_EMAIL, 5, 5), row(2, 'b@example.com', 60, 10, { usageAgeSeconds: 300 }), row(3, 'c@example.com', 10, 50, { usageAgeSeconds: 600 })),
      [], {}, 55, true, {}, HOLD_A + noRoom('pool readings up to 10 min old')],
    ['UP04', 'disabled and non-ok rows never have room', list(row(2, 'b@example.com', 5, 5, { disabled: true }), row(3, 'c@example.com', 5, 5, { usageStatus: 'token_expired' }),
      row(4, 'd@example.com', 30, 30, { usageAgeSeconds: 60 })), [], {}, 55, true, {}, HOLD_A + room('d@example.com', 4, 1, 30, 30)],
    ['UP05', 'the session\'s email is excluded, every row with it, in any case', list(row(1, A_EMAIL, 5, 5), row(5, 'A@Example.com', 1, 1, { usageAgeSeconds: 9000 }),
      row(2, 'b@example.com', 60, 60)), [], {}, 55, true, {}, HOLD_A + noRoom('pool readings up to 2 min old')],
    ['UP06', 'label unknown: the session\'s account is not excluded, and the text says so', list(row(1, A_EMAIL, 5, 5), row(2, 'b@example.com', 60, 60)),
      [], {}, 55, false, {}, HOLD_MSG + room(A_EMAIL, 1, 2, 5, 5, true)],
    ['UP07', 'label unknown, no room', list(row(2, 'b@example.com', 60, 60)), [], {}, 55, false, {}, HOLD_MSG + noRoom('pool readings up to 2 min old', true)],
    ['UP08', 'only the session\'s rows: no other account, no readings', list(row(1, A_EMAIL, 5, 5)), [], {}, 55, true, {}, HOLD_A + noRoom('no pool readings')],
    ['UP09', 'per-meter holds judge room (7d 70 is under its own hold of 75)', list(row(2, 'b@example.com', 10, 70)), [], {}, 65, true,
      { fiveHour: { hold: 60, deny: null }, sevenDay: { hold: 75, deny: null } },
      HOLD_A.replace('5h 55%, 7d 10%. 5h at or above 50%', '5h 65%, 7d 10%. 5h at or above 60%') + room('b@example.com', 2, 2, 10, 70)],
    ['UP10', 'a missing program: no pool reading', null, { command: [MISSING] }, {}, 55, true, {}, HOLD_A + ` No pool reading (${MISSING} not found).`],
    ['UP11', 'a stub that sleeps past the timeout: no pool reading', list(row(2, 'b@example.com', 5, 5)), ['--sleep', '10000'], { timeoutSeconds: 1 }, 55, true, {},
      HOLD_A + ' No pool reading (no answer within 1 s).'],
    ['UP12', 'a non-zero exit: no pool reading', list(row(2, 'b@example.com', 5, 5)), ['--exit', '3'], {}, 55, true, {}, HOLD_A + ` No pool reading (${NODE} exited 3).`],
    ['UP13', 'bad JSON: no pool reading', 'not json {', [], {}, 55, true, {}, HOLD_A + ' No pool reading (output is not the list --json shape).'],
    ['UP14', 'JSON without accounts[]: no pool reading', { schemaVersion: 1 }, [], {}, 55, true, {}, HOLD_A + ' No pool reading (output is not the list --json shape).'],
    ['UP21', 'a last-good reading within an hour has room, with its own age', list(row(2, 'b@example.com', null, null, { usageStatus: 'unavailable', usage: null,
      usageAgeSeconds: undefined, lastGoodUsage: { fiveHour: { pct: 10 }, sevenDay: { pct: 20 } }, lastGoodAgeSeconds: 1800 })), [], {}, 55, true, {},
      HOLD_A + room('b@example.com', 2, 30, 10, 20)],
    ['UP22', 'a last-good reading older than an hour has none', list(row(2, 'b@example.com', null, null, { usageStatus: 'unavailable', usage: null,
      usageAgeSeconds: undefined, lastGoodUsage: { fiveHour: { pct: 10 }, sevenDay: { pct: 20 } }, lastGoodAgeSeconds: 3700 })), [], {}, 55, true, {},
      HOLD_A + noRoom('no pool readings')],
    ['UP23', 'same email, another known organization: not the session\'s account', list(row(1, A_EMAIL, 5, 5, { organizationUuid: 'org-1' }),
      row(6, A_EMAIL, 10, 10, { organizationUuid: 'org-2' })), [], {}, 55, true, {}, HOLD_A + room(A_EMAIL, 6, 2, 10, 10)],
    ['UP24', 'same email, organization unknown on one side: excluded', list(row(1, A_EMAIL, 5, 5, { organizationUuid: 'org-1' }), row(6, A_EMAIL, 5, 5),
      row(2, 'b@example.com', 60, 60)), [], {}, 55, true, {}, HOLD_A + noRoom('pool readings up to 2 min old')],
    ['UP25', 'a room row whose email is not an address is named by its slot alone', list(row(7, 'not an\naddress', 10, 10)), [], {}, 55, true, {},
      HOLD_A + room('b@example.com', 7, 2, 10, 10).replace('account b@example.com (slot 7)', 'slot 7')],
    ['UP15', 'an allowed launch spawns nothing', list(row(2, 'b@example.com', 5, 5)), [], {}, 20, true, {}, null, false],
    ['UP16', 'a HOLD lifted by a grant spawns nothing', list(row(2, 'b@example.com', 5, 5)), [], {}, 55, true, {}, null, false, true],
    ['UP17', 'a deny line spawns nothing, and its text has no pool part', list(row(2, 'b@example.com', 5, 5)), [], {}, 85, true, { deny: 80 },
      STOP_MSG.replace('Usage gate:', `Usage gate (${A_EMAIL}):`), false],
  ];
  for (const [id, label, fixture, flagsOrCmd, poolExtra, five, known, usageExtra, expect, marker, grant] of UP) {
    if (!selected(id)) continue;
    const markerFile = path.join(scratch, `up-marker-${id}`);
    fs.rmSync(markerFile, { force: true });
    let usage = Object.assign({}, usageExtra);
    if (fixture !== null || !Array.isArray(flagsOrCmd)) {
      let command = flagsOrCmd.command;
      if (!command) {
        const fx = path.join(scratch, `up-fixture-${id}.json`);
        fs.writeFileSync(fx, typeof fixture === 'string' ? fixture : JSON.stringify(fixture));
        command = [NODE, stub, fx, ...flagsOrCmd, '--marker', markerFile];
      }
      usage.pool = Object.assign({ command }, poolExtra);
    }
    const policy = scratchPolicy(`up-${id}`, { usage });
    fs.rmSync(stateDir, { recursive: true, force: true });
    fs.mkdirSync(stateDir, { recursive: true });
    fs.writeFileSync(stateFile, JSON.stringify(Object.assign(fresh(five, 10), known ? { account: { uuid: 'uuid-a', organizationUuid: 'org-1', email: A_EMAIL } } : {})));
    const loginFile = path.join(home, '.claude.json');
    if (known) fs.writeFileSync(loginFile, JSON.stringify({ oauthAccount: { accountUuid: 'uuid-a', organizationUuid: 'org-1', emailAddress: A_EMAIL } }));
    else fs.rmSync(loginFile, { force: true });
    fs.rmSync(grantDir, { recursive: true, force: true });
    if (grant) { fs.mkdirSync(grantDir, { recursive: true }); fs.writeFileSync(path.join(grantDir, 'user.json'), JSON.stringify(g())); }
    const r = run('usage-gate', gateInput(), gateEnv({ HOOK_POLICY_FILE: policy }));
    let ok;
    let detail;
    if (expect === null) { ok = r.code === 0 && r.stdout === ''; detail = `expected allow (exit 0, no stdout); stderr ${r.stderr}`; }
    else {
      const d = parseDecision(r.stdout);
      ok = r.code === 0 && !!d && d.decision === 'deny' && d.reason === expect;
      detail = `expected reason ${JSON.stringify(expect)}; stderr ${r.stderr}`;
    }
    if (ok && marker !== undefined && fs.existsSync(markerFile) !== marker) { ok = false; detail = `the pool stub ${marker ? 'did not run' : 'ran'}`; }
    report(id, `${label} -> ${expect === null ? 'allow' : 'deny'}`, ok, detail, r.stdout);
  }
  fs.rmSync(path.join(home, '.claude.json'), { force: true });
  fs.rmSync(grantDir, { recursive: true, force: true });

  // The key itself: validated like the others, defaults filled in by the loader.
  const { load } = require('./policy');
  const loadUsage = (name, pool) => load(scratchPolicy(`up-load-${name}`, { usage: { pool } }));
  const KEYS = [
    ['UP18', 'pool without timeoutSeconds loads with 15', () => {
      const r = loadUsage('default', { command: ['cswap', 'list', '--json'], $comment: 'x' });
      return (r.error === null && JSON.stringify(r.policy.usage.pool) === '{"command":["cswap","list","--json"],"timeoutSeconds":15}') || `${r.error} ${JSON.stringify(r.policy.usage.pool)}`;
    }],
    ['UP19', 'pool null loads; the base default is null', () => {
      const r = loadUsage('null', null);
      return (r.error === null && r.policy.usage.pool === null && load(POLICIES.none).policy.usage.pool === null) || r.error;
    }],
    ['UP26', 'timeoutSeconds 1 and 60 load (the integer range ends)', () => {
      const r = [1, 60].map((t) => loadUsage(`t${t}`, { command: ['x'], timeoutSeconds: t }));
      return r.every((x) => x.error === null) || r.map((x) => x.error).join('; ');
    }],
    ['UP20', 'malformed pools: empty command, a string command, an empty word, timeoutSeconds 0, 0.5, 61, 600 or a string, an unknown key, an array', () => {
      const bad = [{ command: [] }, { command: 'cswap list' }, { command: ['cswap', ''] }, { command: ['x'], timeoutSeconds: 0 },
        { command: ['x'], timeoutSeconds: 0.5 }, { command: ['x'], timeoutSeconds: 61 }, { command: ['x'], timeoutSeconds: 600 },
        { command: ['x'], timeoutSeconds: '15' }, { command: ['x'], shell: true }, ['cswap'], {}];
      const loaded = bad.map((p, i) => loadUsage(`bad-${i}`, p));
      const accepted = loaded.map((r, i) => (r.error === null ? JSON.stringify(bad[i]) : null)).filter((x) => x);
      return accepted.length === 0 || `accepted: ${accepted.join(', ')}`;
    }],
  ];
  for (const [id, label, check] of KEYS) {
    if (!selected(id)) continue;
    let res;
    try { res = check(); } catch (e) { res = e.message; }
    report(id, label, res === true, res);
  }
}

// ---------- statusline ----------
// Runs .claude/statusline.js with a scratch HOME. The cache must merge per window and never lose
// numbers to a refresh that carries no rate_limits.
const slHome = path.join(scratch, 'sl-home');
fs.mkdirSync(path.join(slHome, '.claude'), { recursive: true });
// One file per session (ADR 0011): the S cases run as session SL_SID unless they say otherwise.
const SL_SID = 'sess-sl';
const slDir = path.join(slHome, '.claude', 'usage-state');
const slFile = path.join(slDir, `${SL_SID}.json`);
const readSl = () => { try { return fs.readFileSync(slFile, 'utf8'); } catch (_) { return null; } };
function statusline(stdin, policy, extraEnv) {
  const r = spawnSync(NODE, [path.join(HOOKS, '..', 'statusline.js')], {
    input: stdin, encoding: 'utf8', timeout: 30000,
    env: envWith(Object.assign({ HOME: slHome, USERPROFILE: slHome, CLAUDE_CONFIG_DIR: undefined, HOOK_POLICY_FILE: policy || POLICIES.hatchery }, extraEnv)),
  });
  return { code: r.status, stdout: r.stdout || '' };
}
const bare = (extra) => JSON.stringify(Object.assign({ model: { display_name: 'M' }, session_id: SL_SID }, extra));
const rl = (five, seven) => JSON.stringify({ model: { display_name: 'M' }, session_id: SL_SID, rate_limits: Object.assign({},
  five === null ? {} : { five_hour: { used_percentage: five, resets_at: nowSec + 3600 } },
  seven === null ? {} : { seven_day: { used_percentage: seven, resets_at: nowSec + 86400 * 3 } }) });
// [id, label, stdin, policy, check(result, fileBefore, fileAfter) -> true or a failure detail]
const S = [
  ['S01', 'both windows written', rl(55, 20), null, (r, b, a) => {
    const j = JSON.parse(a);
    return (j.five_hour.used_percentage === 55 && j.five_hour.resets_at === nowSec + 3600 && j.seven_day.used_percentage === 20 &&
      typeof j.updatedAt === 'number' && r.stdout.includes('5h 55%') && r.stdout.endsWith(' HOLD')) || `file ${a}, line ${r.stdout}`;
  }],
  ['S02', 'no rate_limits: file untouched, cached numbers shown', bare(), null,
    (r, b, a) => (b === a && r.stdout.includes('5h 55%') && r.stdout.includes('7d 20%')) || `before ${b}, after ${a}, line ${r.stdout}`],
  ['S03', 'one window: the other is kept', rl(null, 30), null, (r, b, a) => {
    const j = JSON.parse(a);
    return (j.five_hour.used_percentage === 55 && j.seven_day.used_percentage === 30) || `file ${a}`;
  }],
  ['S04', 'malformed stdin: prints, writes nothing', 'not json', null,
    (r, b, a) => (r.code === 0 && b === a && r.stdout.startsWith('? |')) || `exit ${r.code}, line ${r.stdout}`],
  ['S05', 'flags follow the policy (single-branch: 85 is HOLD)', rl(85, 10), POLICIES.singleBranch, (r) => r.stdout.endsWith(' HOLD') || r.stdout],
  ['S06', 'flags follow the policy (single-branch: 92 is STOP)', rl(92, 10), POLICIES.singleBranch, (r) => r.stdout.endsWith(' STOP') || r.stdout],
  ['S07', 'flags follow the policy (Hatchery, no deny line: 85 is HOLD, was STOP at deny 80)', rl(85, 10), null, (r) => r.stdout.endsWith(' HOLD') || r.stdout],
  ['S10', 'no deny line: both meters at 100 show HOLD, never STOP', rl(100, 100), null,
    (r) => (r.stdout.endsWith(' HOLD') && !r.stdout.includes('STOP')) || r.stdout],
  ['S11', 'a deny line set: 85 is STOP', rl(85, 10), deny80, (r) => r.stdout.endsWith(' STOP') || r.stdout],
  ['S12', 'workspace.current_dir prints as line 1, meters line unchanged on line 2', JSON.stringify({ model: { display_name: 'M' }, workspace: { current_dir: 'C:/work/repo' }, cwd: 'C:/old' }), null,
    (r) => r.stdout === 'C:/work/repo\n' + statusline(JSON.stringify({ model: { display_name: 'M' } })).stdout || r.stdout],
  ['S15', 'empty current_dir falls back to cwd', JSON.stringify({ model: { display_name: 'M' }, workspace: { current_dir: '' }, cwd: 'C:/old' }), null,
    (r) => r.stdout === 'C:/old\n' + statusline(JSON.stringify({ model: { display_name: 'M' } })).stdout || r.stdout],
  ['S16', 'null workspace and no cwd: the meters line alone', JSON.stringify({ model: { display_name: 'M' }, workspace: null }), null,
    (r) => r.stdout === statusline(JSON.stringify({ model: { display_name: 'M' } })).stdout || r.stdout],
  ['S13', 'only the older cwd field: it is line 1', JSON.stringify({ model: { display_name: 'M' }, cwd: 'C:/old' }), null,
    (r) => (r.stdout.split('\n')[0] === 'C:/old' && r.stdout.split('\n').length === 2 && r.stdout.split('\n')[1].startsWith('M | ctx')) || r.stdout],
  ['S14', 'neither directory field: the meters line alone, no blank first line', JSON.stringify({ model: { display_name: 'M' } }), null,
    (r) => (!r.stdout.includes('\n') && r.stdout.startsWith('M | ctx')) || r.stdout],
];
fs.rmSync(slFile, { force: true });
for (const [id, label, stdin, policy, check] of S) {
  if (!selected(id)) continue;
  const before = readSl();
  const r = statusline(stdin, policy);
  const after = readSl();
  let res;
  try { res = check(r, before, after); } catch (e) { res = e.message; }
  report(id, label, res === true, res, r.stdout);
}
// Thresholds beside each meter, and "HOLD lifted" when a grant for this session lifts every tripped
// meter. Grants live in the scratch HOME, as the gate's UG cases do.
{
  const slGrants = path.join(slHome, '.claude', 'usage-gate-overrides');
  const withSession = (five, seven, sid) => JSON.stringify(Object.assign(JSON.parse(rl(five, seven)), { session_id: sid }));
  const withDir = (five, seven, dir) => JSON.stringify(Object.assign(JSON.parse(rl(five, seven)), { workspace: { current_dir: dir } }));
  const envPolicy = scratchPolicy('sl-override-env', Object.assign({}, HATCHERY_POLICY, { usage: Object.assign({}, HATCHERY_POLICY.usage, { overrideEnv: 'SL_TEST_OVERRIDE' }) }));
  const proj = path.join(scratch, 'sl-project');
  const SL = [
    ['S17', 'base policy shows (hold 50) beside both meters, no stop part', rl(24, 72), null, {},
      (o) => (o.includes('5h 24% (hold 50) ->') && o.includes('7d 72% (hold 50) ->') && !o.includes('stop')) || o],
    ['S18', 'per-meter policy shows each meter its own hold and stop', rl(24, 72), POLICIES.perMeter, {},
      (o) => (o.includes('5h 24% (hold 60 stop 80) ->') && o.includes('7d 72% (hold 75 stop 90) ->')) || o],
    ['S19', 'HOLD with a grant for this session reads HOLD lifted', withSession(55, 20, 'x'), null, { 'sess.json': { until: nowSec + 3600, session: 'x' } },
      (o) => o.endsWith(' HOLD lifted') || o],
    ['S20', 'HOLD with a grant for another session reads HOLD', withSession(55, 20, 'x'), null, { 'sess.json': { until: nowSec + 3600, session: 'y' } },
      (o) => (o.endsWith(' HOLD') && !o.includes('lifted')) || o],
    ['S21', 'HOLD on 5h with a grant lifting only 7d stays HOLD', withSession(55, 20, 'x'), null, { 'sess.json': { until: nowSec + 3600, session: 'x', meters: ['7d'] } },
      (o) => (o.endsWith(' HOLD') && !o.includes('lifted')) || o],
    ['S22', 'STOP with a grant for this session stays STOP', withSession(85, 10, 'x'), deny80, { 'sess.json': { until: nowSec + 3600, session: 'x' } },
      (o) => (o.endsWith(' STOP') && !o.includes('lifted')) || o],
    ['S23', 'HOLD with a project grant matching workspace.current_dir reads HOLD lifted', withDir(55, 20, path.join(proj, 'sub')), null, { 'proj.json': { until: nowSec + 3600, project: proj } },
      (o) => o.endsWith(' HOLD lifted') || o],
    ['S24', 'HOLD with a project grant for another directory reads HOLD', withDir(55, 20, path.join(scratch, 'elsewhere')), null, { 'proj.json': { until: nowSec + 3600, project: proj } },
      (o) => (o.endsWith(' HOLD') && !o.includes('lifted')) || o],
    ['S25', 'HOLD with usage.overrideEnv set to 1 in the environment reads HOLD lifted', rl(55, 20), envPolicy, {},
      (o) => o.endsWith(' HOLD lifted') || o, { SL_TEST_OVERRIDE: '1' }],
    ['S42', 'no session_id: a session grant does not lift, the line reads HOLD', JSON.stringify(Object.assign(JSON.parse(rl(55, 20)), { session_id: undefined })), null,
      { 'sess.json': { until: nowSec + 3600, session: SL_SID } }, (o) => (o.endsWith(' HOLD') && !o.includes('lifted') && o.includes('5h 55%')) || o],
  ];
  for (const [id, label, stdin, policy, grants, check, extraEnv] of SL) {
    if (!selected(id)) continue;
    fs.rmSync(slGrants, { recursive: true, force: true });
    fs.mkdirSync(slGrants, { recursive: true });
    for (const [name, contents] of Object.entries(grants)) fs.writeFileSync(path.join(slGrants, name), JSON.stringify(contents));
    const r = statusline(stdin, policy, extraEnv);
    const res = check(r.stdout);
    report(id, label, res === true, res, r.stdout);
  }
  fs.rmSync(slGrants, { recursive: true, force: true });
}
// A cached window past its reset reads as n/a rather than its old number.
if (selected('S08')) {
  fs.mkdirSync(slDir, { recursive: true });
  fs.writeFileSync(slFile, JSON.stringify({ updatedAt: nowSec, five_hour: { used_percentage: 90, resets_at: nowSec - 1 }, seven_day: { used_percentage: 5 } }));
  const r = statusline(bare());
  report('S08', 'cached window past its reset shows n/a', r.stdout.includes('5h n/a') && !r.stdout.includes('STOP'), r.stdout, r.stdout);
}
// A rename that fails (the cache path is a directory here) leaves no temporary file behind.
if (selected('S09')) {
  fs.rmSync(slFile, { force: true });
  fs.mkdirSync(slFile, { recursive: true });
  const r = statusline(rl(40, 10));
  const left = fs.readdirSync(path.dirname(slFile)).filter((f) => f.endsWith('.tmp'));
  report('S09', 'failed rename: no .tmp left, line still prints', r.code === 0 && left.length === 0 && r.stdout.includes('5h 40%'),
    `exit ${r.code}, leftovers ${left.join(', ')}`, r.stdout);
  fs.rmSync(slFile, { recursive: true, force: true });
}
// The per-session file and the account label (ADR 0011). Each case gets its own empty scratch home
// (and config dir): setup(h, cfg) writes what it needs, the renders run in order through
// render(stdin, env), and check(ctx) returns true or a failure detail.
{
  // org undefined is org-1, null leaves organizationUuid out.
  const login = (uuid, email, org) => JSON.stringify({ oauthAccount: Object.assign({ accountUuid: uuid },
    org === null ? {} : { organizationUuid: org || 'org-1' }, email === null ? {} : { emailAddress: email }) });
  const stFile = (h, sid) => path.join(h, '.claude', 'usage-state', `${sid}.json`);
  const readJ = (f) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch (_) { return null; } };
  // apiMs given: the payload carries cost.total_api_duration_ms; omitted: no cost at all.
  const pay = (sid, five, seven, apiMs) => JSON.stringify(Object.assign({ model: { display_name: 'M' } }, sid ? { session_id: sid } : {},
    apiMs === undefined ? {} : { cost: { total_cost_usd: 0.1, total_api_duration_ms: apiMs } },
    five === null && seven === null ? {} : { rate_limits: Object.assign({},
      five === null ? {} : { five_hour: { used_percentage: five, resets_at: nowSec + 3600 } },
      seven === null ? {} : { seven_day: { used_percentage: seven, resets_at: nowSec + 86400 * 3 } }) }));
  const ago = (f, days) => { const t = nowSec - days * 86400; fs.utimesSync(f, t, t); };
  const SN = [
    ['S26', 'session_id present: writes usage-state/<id>.json, no label, no account segment', () => {}, (c) => {
      c.render(pay('s1', 55, 20));
      const j = readJ(stFile(c.h, 's1'));
      return (j && j.account === null && j.five_hour.used_percentage === 55 && j.seven_day.used_percentage === 20 && typeof j.updatedAt === 'number' &&
        c.out.startsWith('M | ctx n/a | 5h 55%')) || `file ${JSON.stringify(j)}, line ${c.out}`;
    }],
    ['S27', 'session_id absent: writes nothing, shows this payload\'s numbers', () => {}, (c) => {
      c.render(pay(null, 55, 20));
      const dir = path.join(c.h, '.claude', 'usage-state');
      const files = fs.existsSync(dir) ? fs.readdirSync(dir) : [];
      return (files.length === 0 && c.out.includes('5h 55%') && c.out.endsWith(' HOLD')) || `files ${files}, line ${c.out}`;
    }],
    ['S28', 'a session_id that is not a plain name: writes nothing', () => {}, (c) => {
      c.render(pay('../escape', 55, 20));
      const leaked = [path.join(c.h, '.claude', 'escape.json'), path.join(c.h, '.claude', 'usage-state')].filter((f) => fs.existsSync(f));
      return leaked.length === 0 || `written: ${leaked.join(', ')}`;
    }],
    ['S29', 'two sessions side by side keep their own numbers', () => {}, (c) => {
      c.render(pay('s1', 55, 20));
      c.render(pay('s2', 10, 5));
      c.render(pay('s1', null, null));
      const a = readJ(stFile(c.h, 's1'));
      const b = readJ(stFile(c.h, 's2'));
      return (a.five_hour.used_percentage === 55 && b.five_hour.used_percentage === 10 && c.out.includes('5h 55%') && c.out.includes('7d 20%')) ||
        `s1 ${JSON.stringify(a)}, s2 ${JSON.stringify(b)}, line ${c.out}`;
    }],
    ['S30', 'label present: written to the file, account segment after ctx', (h) => fs.writeFileSync(path.join(h, '.claude.json'), login('uuid-a', 'jdorje@example.com')), (c) => {
      c.render(pay('s1', 24, 72));
      const j = readJ(stFile(c.h, 's1'));
      return (j.account && j.account.uuid === 'uuid-a' && j.account.organizationUuid === 'org-1' && j.account.email === 'jdorje@example.com' &&
        c.out.startsWith('M | ctx n/a | jdorje | 5h 24% (hold 50) ->')) || `file ${JSON.stringify(j)}, line ${c.out}`;
    }],
    ['S31', 'label from CLAUDE_CONFIG_DIR, not the home directory', (h, cfg) => {
      fs.writeFileSync(path.join(h, '.claude.json'), login('uuid-a', 'a@example.com'));
      fs.writeFileSync(path.join(cfg, '.claude.json'), login('uuid-b', 'b@example.com'));
    }, (c) => {
      c.render(pay('s1', 24, 72), { CLAUDE_CONFIG_DIR: c.cfg });
      const j = readJ(stFile(c.h, 's1'));
      return (j.account.uuid === 'uuid-b' && c.out.includes('| b |')) || `file ${JSON.stringify(j)}, line ${c.out}`;
    }],
    ['S32', 'label from the legacy .config.json, which wins over .claude.json', (h) => {
      fs.writeFileSync(path.join(h, '.claude.json'), login('uuid-a', 'a@example.com'));
      fs.writeFileSync(path.join(h, '.config.json'), login('uuid-b', 'b@example.com'));
    }, (c) => {
      c.render(pay('s1', 24, 72));
      const j = readJ(stFile(c.h, 's1'));
      return (j.account.uuid === 'uuid-b' && c.out.includes('| b |')) || `file ${JSON.stringify(j)}, line ${c.out}`;
    }],
    ['S33', 'label torn: the cached label is kept, the windows still update', (h) => fs.writeFileSync(path.join(h, '.claude.json'), login('uuid-a', 'a@example.com')), (c) => {
      c.render(pay('s1', 24, 72));
      fs.writeFileSync(path.join(c.h, '.claude.json'), login('uuid-b', 'b@example.com').slice(0, 25));
      c.render(pay('s1', 30, 73));
      const j = readJ(stFile(c.h, 's1'));
      return (j.account.uuid === 'uuid-a' && j.five_hour.used_percentage === 30 && c.out.includes('| a |') && c.out.includes('5h 30%')) ||
        `file ${JSON.stringify(j)}, line ${c.out}`;
    }],
    ['S34', 'label change with a newer total_api_duration_ms: accepted, every old window dropped', (h) => fs.writeFileSync(path.join(h, '.claude.json'), login('uuid-a', 'a@example.com')), (c) => {
      c.render(pay('s1', 55, 20, 1000));
      fs.writeFileSync(path.join(c.h, '.claude.json'), login('uuid-b', 'b@example.com'));
      c.render(pay('s1', 30, null, 2500));
      const j = readJ(stFile(c.h, 's1'));
      return (j.account.uuid === 'uuid-b' && j.five_hour.used_percentage === 30 && !('seven_day' in j) && j.apiMs === 2500 &&
        c.out.includes('| b | 5h 30%') && c.out.includes('7d n/a')) || `file ${JSON.stringify(j)}, line ${c.out}`;
    }],
    ['S35', 'label change with the same total_api_duration_ms: refused, nothing written, n/a', (h) => fs.writeFileSync(path.join(h, '.claude.json'), login('uuid-a', 'a@example.com')), (c) => {
      c.render(pay('s1', 55, 20, 1000));
      const before = fs.readFileSync(stFile(c.h, 's1'), 'utf8');
      fs.writeFileSync(path.join(c.h, '.claude.json'), login('uuid-b', 'b@example.com'));
      c.render(pay('s1', 55, 20, 1000));
      const after = fs.readFileSync(stFile(c.h, 's1'), 'utf8');
      return (before === after && JSON.parse(after).apiMs === 1000 && c.out.includes('| b | 5h n/a') && c.out.includes('7d n/a') && !c.out.includes('HOLD')) ||
        `before ${before}, after ${after}, line ${c.out}`;
    }],
    ['S40', 'label change with no cost in the payload: accepted', (h) => fs.writeFileSync(path.join(h, '.claude.json'), login('uuid-a', 'a@example.com')), (c) => {
      c.render(pay('s1', 55, 20, 1000));
      fs.writeFileSync(path.join(c.h, '.claude.json'), login('uuid-b', 'b@example.com'));
      c.render(pay('s1', 30, null));
      const j = readJ(stFile(c.h, 's1'));
      return (j.account.uuid === 'uuid-b' && j.five_hour.used_percentage === 30 && !('seven_day' in j) && j.apiMs === 1000) ||
        `file ${JSON.stringify(j)}, line ${c.out}`;
    }],
    ['S43', 'label change with a smaller total_api_duration_ms (a resumed process): accepted, every old window dropped', (h) => fs.writeFileSync(path.join(h, '.claude.json'), login('uuid-a', 'a@example.com')), (c) => {
      c.render(pay('s1', 55, 20, 90000));
      fs.writeFileSync(path.join(c.h, '.claude.json'), login('uuid-b', 'b@example.com'));
      c.render(pay('s1', 30, null, 1200));
      const j = readJ(stFile(c.h, 's1'));
      return (j.account.uuid === 'uuid-b' && j.five_hour.used_percentage === 30 && !('seven_day' in j) && j.apiMs === 1200 &&
        c.out.includes('| b | 5h 30%')) || `file ${JSON.stringify(j)}, line ${c.out}`;
    }],
    ['S44', 'label change against a file without apiMs (written by the stage 1 script): accepted', (h) => {
      fs.writeFileSync(path.join(h, '.claude.json'), login('uuid-b', 'b@example.com'));
      fs.mkdirSync(path.join(h, '.claude', 'usage-state'), { recursive: true });
      fs.writeFileSync(stFile(h, 's1'), JSON.stringify({ account: { uuid: 'uuid-a', organizationUuid: 'org-1', email: 'a@example.com' },
        five_hour: { used_percentage: 55 }, seven_day: { used_percentage: 20 }, updatedAt: nowSec }));
    }, (c) => {
      c.render(pay('s1', 30, null, 1000));
      const j = readJ(stFile(c.h, 's1'));
      return (j.account.uuid === 'uuid-b' && j.five_hour.used_percentage === 30 && !('seven_day' in j) && j.apiMs === 1000) ||
        `file ${JSON.stringify(j)}, line ${c.out}`;
    }],
    ['S41', 'organization unknown on one side does not differ: a plain merge', (h) => fs.writeFileSync(path.join(h, '.claude.json'), login('uuid-a', 'a@example.com')), (c) => {
      c.render(pay('s1', 55, 20, 1000));
      fs.writeFileSync(path.join(c.h, '.claude.json'), login('uuid-a', 'a@example.com', null));
      c.render(pay('s1', 30, null, 1000));
      const j = readJ(stFile(c.h, 's1'));
      return (j.five_hour.used_percentage === 30 && j.seven_day.used_percentage === 20 && c.out.includes('5h 30%') && c.out.includes('7d 20%')) ||
        `file ${JSON.stringify(j)}, line ${c.out}`;
    }],
    ['S36', 'no-window render after a label change: nothing written, meters n/a', (h) => fs.writeFileSync(path.join(h, '.claude.json'), login('uuid-a', 'a@example.com')), (c) => {
      c.render(pay('s1', 55, 20));
      const before = fs.readFileSync(stFile(c.h, 's1'), 'utf8');
      fs.writeFileSync(path.join(c.h, '.claude.json'), login('uuid-b', 'b@example.com'));
      c.render(pay('s1', null, null));
      const after = fs.readFileSync(stFile(c.h, 's1'), 'utf8');
      return (before === after && c.out.includes('| b | 5h n/a') && c.out.includes('7d n/a') && !c.out.includes('HOLD')) ||
        `before ${before}, after ${after}, line ${c.out}`;
    }],
    ['S37', 'label without an email: written, no account segment', (h) => fs.writeFileSync(path.join(h, '.claude.json'), login('uuid-a', null)), (c) => {
      c.render(pay('s1', 24, 72));
      const j = readJ(stFile(c.h, 's1'));
      return (j.account.uuid === 'uuid-a' && j.account.email === null && c.out.startsWith('M | ctx n/a | 5h 24%')) || `file ${JSON.stringify(j)}, line ${c.out}`;
    }],
    ['S38', 'prune: a new session\'s file deletes files older than a day, keeps newer ones', (h) => {
      const dir = path.join(h, '.claude', 'usage-state');
      fs.mkdirSync(dir, { recursive: true });
      for (const n of ['old.json', 'old.json.123.tmp', 'recent.json', 'notes.txt']) fs.writeFileSync(path.join(dir, n), '{}');
      ago(path.join(dir, 'old.json'), 2);
      ago(path.join(dir, 'old.json.123.tmp'), 2);
      ago(path.join(dir, 'notes.txt'), 2);
      ago(path.join(dir, 'recent.json'), 0.5);
    }, (c) => {
      c.render(pay('s1', 24, 72));
      const dir = path.join(c.h, '.claude', 'usage-state');
      const after = fs.readdirSync(dir).sort().join(',');
      // A later file older than a day survives a render of an existing session: prune runs on creation only.
      fs.writeFileSync(path.join(dir, 'later.json'), '{}');
      ago(path.join(dir, 'later.json'), 2);
      c.render(pay('s1', 25, 72));
      const kept = fs.existsSync(path.join(dir, 'later.json'));
      return (after === 'notes.txt,recent.json,s1.json' && kept) || `after ${after}, later kept ${kept}`;
    }],
    ['S39', 'the old single cache file is neither read, written nor deleted', (h) => {
      fs.writeFileSync(path.join(h, '.claude', 'usage-state.json'), JSON.stringify(fresh(95, 95)));
    }, (c) => {
      const old = path.join(c.h, '.claude', 'usage-state.json');
      const before = fs.readFileSync(old, 'utf8');
      c.render(pay('s1', null, null));
      const unread = c.out.includes('5h n/a');
      c.render(pay('s1', 24, 72));
      const after = fs.existsSync(old) ? fs.readFileSync(old, 'utf8') : null;
      return (unread && after === before && c.out.includes('5h 24%')) || `unread ${unread}, old file after ${after}, line ${c.out}`;
    }],
  ];
  for (const [id, label, setup, check] of SN) {
    if (!selected(id)) continue;
    const h = path.join(scratch, `sl-${id}`);
    const cfg = path.join(scratch, `sl-${id}-config`);
    fs.mkdirSync(path.join(h, '.claude'), { recursive: true });
    fs.mkdirSync(cfg, { recursive: true });
    setup(h, cfg);
    const c = { h, cfg, out: '' };
    c.render = (stdin, extraEnv) => {
      const r = spawnSync(NODE, [path.join(HOOKS, '..', 'statusline.js')], {
        input: stdin, encoding: 'utf8', timeout: 30000,
        env: envWith(Object.assign({ HOME: h, USERPROFILE: h, CLAUDE_CONFIG_DIR: undefined, HOOK_POLICY_FILE: POLICIES.hatchery }, extraEnv)),
      });
      c.out = r.status === 0 ? r.stdout || '' : `exit ${r.status}: ${r.stderr}`;
      return c.out;
    };
    let res;
    try { res = check(c); } catch (e) { res = `${e.message}; line ${c.out}`; }
    report(id, label, res === true, res, c.out);
  }
}

// ---------- fitness checks (group F; ADR 0013, ADR 0012's fitness contract) ----------
// One check, deps: in every file deps.paths matches, and in each file of the optional deps.vendored
// list, each literal require call naming a module that is not relative (. or .., or starting ./ ../
// .\ ..\) must name a Node built-in (module.isBuiltin). The distinct other packages (the first path
// segment, or the first two for @scope/name), plus each vendored file (which must exist and carry an
// SPDX identifier, ADR 0012), may number at most deps.budget. The scan reads raw text: a require call
// inside a comment or a string counts too, by design (it errs towards a false block, never a false
// pass). A literal is a quoted or backtick string without an interpolation, after optional spaces,
// comments and extra parentheses, with an optional trailing comma. A skip entry for the check needs
// check, reason (no control characters), date (YYYY-MM-DD) and idea (docs/ideas/YYYY-MM-DD-<slug>.md,
// existing), else the check fails. Lines follow the contract with the case id after the status:
// PASS F1 deps, SKIP F1 deps: ..., FAIL F1 deps: ... F1 runs on this repository (selected by
// --only F1, --only F or --only deps); the other F cases run on scratch trees with the same layout
// and pass when the check gives the line they expect.
const FIT_CONFIG = '.claude/hooks/fitness.json';
const FIT_LIFT = 'lift: skip entry (coordinator, one push, named in the commit message) or ADR amendment (architect)';
const { isBuiltin: fitIsBuiltin } = require('module');
// Spaces, block comments and line comments, as the regex source of one item.
const FIT_GAP = String.raw`(?:\s|/\*[\s\S]*?\*/|//[^\n]*\n)`;
const FIT_REQUIRE = new RegExp(String.raw`\brequire\s*\((?:${FIT_GAP}|\()*` +
  String.raw`(?:'((?:[^'\\\n]|\\.)*)'|"((?:[^"\\\n]|\\.)*)"|` + '`' + String.raw`((?:[^` + '`' + String.raw`\\$]|\\.|\$(?!\{))*)` + '`' + ')' +
  String.raw`${FIT_GAP}*,?${FIT_GAP}*\)`, 'g');
const fitUnescape = (s) => s.replace(/\\(.)/g, '$1');
const fitRelative = (m) => m === '.' || m === '..' || /^\.\.?[/\\]/.test(m);
const fitPackage = (m) => m.split('/').slice(0, m.startsWith('@') ? 2 : 1).join('/');
const FIT_CONTROL = /[\u0000-\u001f\u007f]/;
const fitEscape = (s) => s.replace(/[.+^$(){}|[\]\\]/g, '\\$&');

// Expands one pattern relative to root: / separates segments, * and ? match within one segment.
function fitGlob(root, pattern) {
  let found = [''];
  for (const seg of pattern.split('/')) {
    const next = [];
    for (const rel of found) {
      if (!/[*?]/.test(seg)) { next.push(rel ? `${rel}/${seg}` : seg); continue; }
      const re = new RegExp(`^${fitEscape(seg).replace(/\*/g, '[^/]*').replace(/\?/g, '[^/]')}$`);
      let names = [];
      try { names = fs.readdirSync(path.join(root, rel)).sort(); } catch (_) { /* no such dir: no match */ }
      for (const n of names) if (re.test(n)) next.push(rel ? `${rel}/${n}` : n);
    }
    found = next;
  }
  return found.filter((rel) => { try { return fs.statSync(path.join(root, rel)).isFile(); } catch (_) { return false; } });
}

// Runs the deps check on the tree at root. Returns { status, rest, log }: the contract line is
// `${status} ${rest}`, and log holds the --verbose lines (what was scanned and how it was classed).
function fitnessDeps(root) {
  const log = [];
  const done = (status, rest) => ({ status, rest, log });
  const failed = (detail, key) => done('FAIL', `deps: ${detail}; from ${FIT_CONFIG}${key ? ` ${key}` : ''}; ${FIT_LIFT}`);
  let cfg;
  try {
    cfg = JSON.parse(fs.readFileSync(path.join(root, ...FIT_CONFIG.split('/')), 'utf8').replace(/^﻿/, ''));
  } catch (e) { return failed(`config unreadable (${e.code || e.message})`); }
  if (!cfg || typeof cfg !== 'object' || Array.isArray(cfg)) return failed('config malformed (not an object)');

  // Skip entries first: a malformed one fails the check even when the check would pass.
  const skip = cfg.skip === undefined ? [] : cfg.skip;
  if (!Array.isArray(skip)) return failed('skip entry malformed', 'skip');
  let use = null;
  for (const e of skip) {
    const ours = !e || typeof e !== 'object' || typeof e.check !== 'string' || e.check === 'deps';
    if (!ours) continue;
    let why = null;
    if (!['check', 'reason', 'date', 'idea'].every((k) => typeof e[k] === 'string' && e[k].trim() !== '')) why = 'a field is missing or empty';
    else if (!/^\d{4}-\d{2}-\d{2}$/.test(e.date)) why = 'date is not YYYY-MM-DD';
    else if (FIT_CONTROL.test(e.reason) || FIT_CONTROL.test(e.idea)) why = 'reason or idea holds a control character';
    else if (!/^docs\/ideas\/\d{4}-\d{2}-\d{2}-[^/]+\.md$/.test(e.idea)) why = 'idea is not docs/ideas/YYYY-MM-DD-<slug>.md';
    else {
      let exists = false;
      try { exists = fs.statSync(path.join(root, e.idea)).isFile(); } catch (_) { /* absent */ }
      if (!exists) why = 'idea file does not exist';
    }
    log.push(`skip entry ${JSON.stringify(e)}: ${why || 'well formed'}`);
    if (why) return failed('skip entry malformed', 'skip');
    if (!use) use = e;
  }
  if (use) return done('SKIP', `deps: ${use.reason} (${use.idea})`);

  const d = cfg.deps;
  const strings = (a) => Array.isArray(a) && a.every((s) => typeof s === 'string' && s !== '');
  if (!d || typeof d !== 'object') return failed('config malformed (deps missing)', 'deps');
  if (!Number.isInteger(d.budget) || d.budget < 0) return failed('config malformed (budget is not a whole number)', 'deps.budget');
  if (!strings(d.paths) || d.paths.length === 0 || d.paths.some((p) => p.includes('**'))) {
    return failed('config malformed (paths is not a list of patterns without **)', 'deps.paths');
  }
  if (d.vendored !== undefined && !strings(d.vendored)) return failed('config malformed (vendored is not a list of paths)', 'deps.vendored');
  const vendored = [...new Set(d.vendored || [])];
  log.push(`config ${FIT_CONFIG}: budget ${d.budget}; paths ${d.paths.join(', ')}; vendored ${vendored.length ? vendored.join(', ') : 'none'}`);

  const files = [];
  for (const p of d.paths) {
    const matched = fitGlob(root, p);
    if (matched.length === 0) return failed(`${p} matches no file`, 'deps.paths');
    for (const f of matched) if (!files.includes(f)) files.push(f);
  }
  for (const v of vendored) {
    let text = null;
    try { text = fs.readFileSync(path.join(root, v), 'utf8'); } catch (_) { /* reported below */ }
    if (text === null) return failed(`vendored ${v} does not exist`, 'deps.vendored');
    if (!/SPDX-License-Identifier:\s*\S/.test(text)) return failed(`vendored ${v} has no SPDX-License-Identifier`, 'deps.vendored');
    log.push(`${v}: vendored, counted`);
    if (!files.includes(v)) files.push(v);
  }
  const others = new Set();
  const offenders = [];
  for (const f of files) {
    const text = fs.readFileSync(path.join(root, f), 'utf8');
    let n = 0;
    for (const m of text.matchAll(FIT_REQUIRE)) {
      n++;
      const mod = fitUnescape(m[1] !== undefined ? m[1] : m[2] !== undefined ? m[2] : m[3]);
      const line = text.slice(0, m.index).split('\n').length;
      let why;
      if (fitRelative(mod)) why = 'relative path, not counted';
      else if (fitIsBuiltin(mod)) why = 'built-in';
      else { why = `not built-in, counted as ${fitPackage(mod)}`; others.add(fitPackage(mod)); offenders.push(`${f}:${line} requires ${mod}`); }
      log.push(`${f}:${line} ${mod}: ${why}`);
    }
    if (n === 0) log.push(`${f}: no literal require`);
  }
  const count = others.size + vendored.length;
  log.push(`dependencies ${count} (${[...others, ...vendored].join(', ') || 'none'}), budget ${d.budget}`);
  if (count > d.budget) return failed([...offenders, ...vendored.map((v) => `${v} is vendored`)].join(', '), 'deps.budget');
  return done('PASS', 'deps');
}

{
  if (selected('F1') || opt.only === 'deps') {
    const r = fitnessDeps(path.resolve(HOOKS, '..', '..'));
    process.stdout.write(`${r.status} F1 ${r.rest}\n`);
    if (opt.verbose) for (const l of r.log) process.stdout.write(`     ${l}\n`);
    if (r.status === 'FAIL') fail++; else if (r.status === 'SKIP') skipped++; else pass++;
    noteGroup('F1');
  }

  // Scratch trees. The call name is split so that this file does not itself require the fixtures.
  const R = 'req' + 'uire';
  const req = (m) => `${R}('${m}')`;
  const IDEA = 'docs/ideas/2026-01-01-left-pad.md';
  const SKIP_LP = { check: 'deps', reason: 'left-pad until its copy lands', date: '2026-01-01', idea: IDEA };
  const A_JS = `const fs = ${req('fs')};\nconst p = ${req('node:path')};\nconst b = ${req('./b')};\nconst lp = ${req('left-pad')};\n`;
  const CLEAN_JS = `const fs = ${req('fs')};\n`;
  const VENDOR = '// SPDX-License-Identifier: MIT\n// Origin: https://example.invalid/pad.js, 1.0.0, 2026-01-01\n';
  const failLine = (detail, key) => `FAIL deps: ${detail}; from ${FIT_CONFIG} ${key}; ${FIT_LIFT}`;
  const SHAPES_JS = [
    `const a = ${R} ('pkg-a');`,
    `const b = ${R}(\`pkg-b\`);`,
    `const c = ${R}('pkg-c',);`,
    `const d = ${R}(('pkg-d'));`,
    `const e = ${R}(/* why */ 'pkg-e');`,
    `const f = ${R}(\`pkg-\${x}\`);`,
    `const g = ${R}(\n  // reason\n  'pkg-g'\n);`,
  ].join('\n') + '\n';
  const IDEA_FILE = { [IDEA]: '---\ndate: 2026-01-01\nkind: chore\n---\n# Left-pad\n' };
  const HOOKS_ONLY = { budget: 0, paths: ['.claude/hooks/*.js'] };
  // [id, label, deps block, skip list, files { rel: text }, expected line, { bom, logHas: [...] }]
  const F = [
    ['F2', 'a scratch file requires left-pad: fails, with the lift', { budget: 0, paths: ['.claude/hooks/*.js'] }, [],
      { '.claude/hooks/a.js': A_JS }, failLine('.claude/hooks/a.js:4 requires left-pad', 'deps.budget')],
    ['F3', 'the same with a skip entry whose idea file exists: skips, passes', { budget: 0, paths: ['.claude/hooks/*.js'] }, [SKIP_LP],
      { '.claude/hooks/a.js': A_JS, [IDEA]: '---\ndate: 2026-01-01\nkind: chore\n---\n# Left-pad\n' },
      `SKIP deps: ${SKIP_LP.reason} (${IDEA})`],
    ['F4', 'the same skip entry with its idea file absent: fails, skip entry malformed', { budget: 0, paths: ['.claude/hooks/*.js'] }, [SKIP_LP],
      { '.claude/hooks/a.js': A_JS }, failLine('skip entry malformed', 'skip')],
    ['F5', 'a vendored file with its licence counts against a budget of 0: fails', { budget: 0, paths: ['.claude/hooks/*.js'], vendored: ['vendor/pad.js'] }, [],
      { '.claude/hooks/a.js': CLEAN_JS, 'vendor/pad.js': VENDOR }, failLine('vendor/pad.js is vendored', 'deps.budget')],
    ['F6', 'a vendored file without an SPDX identifier fails within budget', { budget: 1, paths: ['.claude/hooks/*.js'], vendored: ['vendor/pad.js'] }, [],
      { '.claude/hooks/a.js': CLEAN_JS, 'vendor/pad.js': '// no licence line\n' }, failLine('vendored vendor/pad.js has no SPDX-License-Identifier', 'deps.vendored')],
    ['F7', 'require shapes: space before the paren, backtick, trailing comma, extra parens, comments', HOOKS_ONLY, [],
      { '.claude/hooks/a.js': SHAPES_JS },
      failLine(['1 requires pkg-a', '2 requires pkg-b', '3 requires pkg-c', '4 requires pkg-d', '5 requires pkg-e', '7 requires pkg-g']
        .map((s) => `.claude/hooks/a.js:${s}`).join(', '), 'deps.budget'), { logHas: ['dependencies 6 '] }],
    ['F8', 'node:test and fs/promises are built-in, node:left-pad is not', HOOKS_ONLY, [],
      { '.claude/hooks/a.js': [req('node:test'), req('fs/promises'), req('node:left-pad')].join(';\n') + ';\n' },
      failLine('.claude/hooks/a.js:3 requires node:left-pad', 'deps.budget')],
    ['F9', 'the relative forms . .. .\\ ..\\ are not counted', HOOKS_ONLY, [],
      { '.claude/hooks/a.js': [req('.'), req('..'), req('.\\\\b'), req('..\\\\c')].join(';\n') + ';\n' }, 'PASS deps',
      { logHas: ['a.js:3 .\\b: relative path', 'a.js:4 ..\\c: relative path', 'dependencies 0 '] }],
    ['F10', 'subpaths count once per package, @scope/name as one', { budget: 2, paths: ['.claude/hooks/*.js'] }, [],
      { '.claude/hooks/a.js': [req('lodash/fp'), req('lodash/get'), req('@scope/pkg/x'), req('@scope/pkg')].join(';\n') + ';\n' }, 'PASS deps',
      { logHas: ['dependencies 2 (lodash, @scope/pkg), budget 2'] }],
    ['F11', 'a vendored path listed twice counts once, and its own requires are scanned', { budget: 1, paths: ['.claude/hooks/*.js'], vendored: ['vendor/pad.js', 'vendor/pad.js'] }, [],
      { '.claude/hooks/a.js': CLEAN_JS, 'vendor/pad.js': `${VENDOR}${req('fs')};\n` }, 'PASS deps',
      { logHas: ['vendor/pad.js:3 fs: built-in', 'dependencies 1 (vendor/pad.js), budget 1'] }],
    ['F12', 'a require in a vendored file counts against the budget', { budget: 1, paths: ['.claude/hooks/*.js'], vendored: ['vendor/pad.js'] }, [],
      { '.claude/hooks/a.js': CLEAN_JS, 'vendor/pad.js': `${VENDOR}${req('left-pad')};\n` },
      failLine('vendor/pad.js:3 requires left-pad, vendor/pad.js is vendored', 'deps.budget')],
    ['F13', 'a skip entry whose date is not YYYY-MM-DD is malformed', HOOKS_ONLY, [Object.assign({}, SKIP_LP, { date: '1 Jan 2026' })],
      Object.assign({ '.claude/hooks/a.js': A_JS }, IDEA_FILE), failLine('skip entry malformed', 'skip')],
    ['F14', 'a skip entry whose idea is an existing file outside docs/ideas is malformed', HOOKS_ONLY, [Object.assign({}, SKIP_LP, { idea: '.claude/hooks/a.js' })],
      { '.claude/hooks/a.js': A_JS }, failLine('skip entry malformed', 'skip')],
    ['F15', 'a skip entry whose reason holds a newline is malformed', HOOKS_ONLY, [Object.assign({}, SKIP_LP, { reason: 'two\nlines' })],
      Object.assign({ '.claude/hooks/a.js': A_JS }, IDEA_FILE), failLine('skip entry malformed', 'skip')],
    ['F16', 'a config with a UTF-8 byte order mark is read', HOOKS_ONLY, [], { '.claude/hooks/a.js': CLEAN_JS }, 'PASS deps', { bom: true }],
  ];
  for (const [id, label, deps, skip, files, want, extra] of F) {
    if (!selected(id)) continue;
    const x = extra || {};
    const root = path.join(scratch, `fit-${id}`);
    const all = Object.assign({ [FIT_CONFIG]: `${x.bom ? '﻿' : ''}${JSON.stringify({ deps, skip })}` }, files);
    for (const [rel, text] of Object.entries(all)) {
      const f = path.join(root, ...rel.split('/'));
      fs.mkdirSync(path.dirname(f), { recursive: true });
      fs.writeFileSync(f, text);
    }
    const r = fitnessDeps(root);
    const got = `${r.status} ${r.rest}`;
    const missing = (x.logHas || []).filter((s) => !r.log.some((l) => l.includes(s)));
    report(id, label, got === want && missing.length === 0,
      got !== want ? `expected ${JSON.stringify(want)}; got ${JSON.stringify(got)}` : `verbose log lacks ${JSON.stringify(missing)}`);
    if (opt.verbose) for (const l of [`check: ${got}`, ...r.log]) process.stdout.write(`     ${l}\n`);
  }
}

// ---------- imports.js: the one @ import parser (group IM; ADR 0016, item 4) ----------
{
  const { parse, resolve } = require('./imports');
  const tokens = (text) => parse(text).map((i) => i.token);
  const IM = [
    ['IM01', 'an @ at line start', '@a.md\n', ['a.md']],
    ['IM02', 'an @ mid-line after whitespace (See @README)', 'See @README for more.\n', ['README']],
    ['IM03', 'an @ in a code span, single or double backtick, is skipped', 'Write `@x.md` or ``a ` @y.md`` here.\n', []],
    ['IM04', 'an @ in a fenced block (``` or ~~~, indented too) is skipped', '```\n@x.md\n```\n~~~~\n@y.md\n~~~~\n  ```sh\n  @z.md\n  ```\n@after.md\n', ['after.md']],
    ['IM05', 'trailing . , ; : ) are stripped', '(see @a.md). @b.md, @c.md; @d.md:\n', ['a.md', 'b.md', 'c.md', 'd.md']],
    ['IM06', 'an @ inside a word (an address) is not an import', 'mail me@example.com or x@y\n', []],
    ['IM07', 'an @ right after a code span is not after whitespace', '`x`@a.md\n', []],
    ['IM10', 'a ``` line whose info string holds a backtick is not a fence', '``` `x`\n@a.md\n', ['a.md']],
    ['IM11', 'a code span crossing a line break is skipped; a blank line ends it', 'Write `foo\n@bar.md` here.\n\n@baz.md\n', ['baz.md']],
  ];
  for (const [id, label, text, want] of IM) {
    if (!selected(id)) continue;
    const got = tokens(text);
    report(id, `parse: ${label}`, JSON.stringify(got) === JSON.stringify(want), `expected ${JSON.stringify(want)}, got ${JSON.stringify(got)}`);
  }
  const root = path.join(scratch, 'imports');
  const put = (rel, text) => { const f = path.join(root, ...rel.split('/')); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, text); };
  put('docs/HANDOFF.md', '# H\n@handoff/x.md\n');
  put('docs/handoff/x.md', '# X\n@../HANDOFF.md\n');
  for (let k = 0; k <= 5; k++) put(`chain/c${k}.md`, `# c${k}\n@c${k + 1}.md\n`);
  if (selected('IM08')) {
    const r = resolve(root, 'docs/HANDOFF.md');
    const want = [{ file: 'docs/HANDOFF.md', line: 2, token: 'handoff/x.md', target: 'docs/handoff/x.md', outside: false, exists: true, isDir: false, hop: 1 },
      { file: 'docs/handoff/x.md', line: 2, token: '../HANDOFF.md', target: 'docs/HANDOFF.md', outside: false, exists: true, isDir: false, hop: 2 }];
    report('IM08', 'resolve: each import from its own file; a cycle is read once', JSON.stringify(r) === JSON.stringify(want), `got ${JSON.stringify(r)}`);
  }
  if (selected('IM09')) {
    const r = resolve(root, 'chain/c0.md').map((i) => `${i.hop}:${i.target}`);
    const want = ['1:chain/c1.md', '2:chain/c2.md', '3:chain/c3.md', '4:chain/c4.md'];
    report('IM09', 'resolve: four hops; the imports of a hop-4 file are not followed', JSON.stringify(r) === JSON.stringify(want), `got ${JSON.stringify(r)}`);
  }
  if (selected('IM12')) {
    put('dirs/a.md', '@../chain\n');
    const r = resolve(root, 'dirs/a.md');
    report('IM12', 'resolve: a directory target is not a file (exists false, isDir true) and is not followed',
      r.length === 1 && r[0].target === 'chain' && r[0].exists === false && r[0].isDir === true, `got ${JSON.stringify(r)}`);
  }
  if (selected('IM13')) {
    put('out/a.md', '@~/.claude/mine.md @/etc/hosts @C:/x.md @../../up.md\n');
    const r = resolve(root, 'out/a.md').map((i) => [i.target, i.outside, i.exists]);
    const want = [['~/.claude/mine.md', true, null], ['/etc/hosts', true, null], ['C:/x.md', true, null], ['../up.md', true, null]];
    report('IM13', 'resolve: targets outside the root are marked outside, never stat\'ed (exists null) or followed', JSON.stringify(r) === JSON.stringify(want), `got ${JSON.stringify(r)}`);
  }
}

// ---------- policy.js --claude-check and post-edit on the agreement files (group CL; ADR 0016) ----------
// Each case is its own scratch root; [id, label, files { rel: text }, policy, expected exit, expected stdout].
{
  const AG = (s) => `## ${s}\n\nRule text.\n`;
  const FIX = 'FAIL CLAUDE.md: section';
  const CL = [
    ['CL01', 'today\'s shape: CLAUDE.md importing only @docs/HANDOFF.md, no .claude/agreement/', { 'CLAUDE.md': '# T\n\n@docs/HANDOFF.md\n', 'docs/HANDOFF.md': '# H\n' },
      POLICIES.hatchery, 0, 'claude-check: 2 files, 0 FAIL\n'],
    ['CL02', 'a section neither imported nor replaced', { 'CLAUDE.md': '# T\n@.claude/agreement/a.md\n', '.claude/agreement/a.md': AG('a'), '.claude/agreement/b.md': AG('b') },
      POLICIES.hatchery, 1, `${FIX} b neither imported nor replaced\nclaude-check: 2 files, 1 FAIL\n`],
    ['CL03', 'a section imported and replaced', { 'CLAUDE.md': '# T\n@.claude/agreement/a.md\n@.claude/project/a.md\n', '.claude/agreement/a.md': AG('a'), '.claude/project/a.md': 'Replaces `.claude/agreement/a.md`.\n' },
      POLICIES.hatchery, 1, `${FIX} a imported twice\nclaude-check: 3 files, 1 FAIL\n`],
    ['CL04', 'a replacement that itself imports the section it replaces (any hop counts)', { 'CLAUDE.md': '# T\n@.claude/project/a.md\n', '.claude/agreement/a.md': AG('a'), '.claude/project/a.md': 'Replaces it.\n@../agreement/a.md\n' },
      POLICIES.hatchery, 1, `${FIX} a imported twice\nclaude-check: 3 files, 1 FAIL\n`],
    ['CL05', 'a replacement file with no agreement section', { 'CLAUDE.md': '# T\n@.claude/agreement/a.md\n', '.claude/agreement/a.md': AG('a'), '.claude/project/z.md': 'Replaces nothing.\n' },
      POLICIES.hatchery, 1, 'FAIL .claude/project/z.md replaces no agreement section\nclaude-check: 2 files, 1 FAIL\n'],
    ['CL06', '41-line CLAUDE.md', { 'CLAUDE.md': lines(41) }, POLICIES.hatchery, 1,
      'FAIL CLAUDE.md: 41 lines, cap 40; lift: edit.capExempt naming CLAUDE.md\nclaude-check: 1 files, 1 FAIL\n'],
    ['CL07', 'the same, edit.capExempt naming CLAUDE.md lifts it', { 'CLAUDE.md': lines(41) },
      scratchPolicy('cl-lift', { edit: { capExempt: ['CLAUDE.md'] } }), 0, 'claude-check: 1 files, 0 FAIL\n'],
    ['CL08', 'the same, a pattern matching CLAUDE.md does not lift it', { 'CLAUDE.md': lines(41) },
      scratchPolicy('cl-glob', { edit: { capExempt: ['*'] } }), 1,
      'FAIL CLAUDE.md: 41 lines, cap 40; lift: edit.capExempt naming CLAUDE.md\nclaude-check: 1 files, 1 FAIL\n'],
    ['CL09', 'loads 191 lines; the handoff chain does not count', {
      'CLAUDE.md': `@docs/HANDOFF.md\n@.claude/agreement/a.md\n${lines(8)}`, '.claude/agreement/a.md': lines(181), 'docs/HANDOFF.md': `@handoff/s.md\n${lines(39)}`, 'docs/handoff/s.md': lines(40) },
      POLICIES.hatchery, 1, 'FAIL CLAUDE.md: loads 191 lines, cap 190; lift: edit.capExempt naming CLAUDE.md\nclaude-check: 4 files, 1 FAIL\n'],
    ['CL10', 'loads 190 lines is silent', { 'CLAUDE.md': `@.claude/agreement/a.md\n${lines(9)}`, '.claude/agreement/a.md': lines(180) },
      POLICIES.hatchery, 0, 'claude-check: 2 files, 0 FAIL\n'],
    ['CL11', 'unresolved imports: mid-line in CLAUDE.md, at hop 2, and in a stream the map does not import', {
      'CLAUDE.md': '# T\nSee @docs/GONE.md.\n@docs/HANDOFF.md\n', 'docs/HANDOFF.md': '# H\n@handoff/a.md\n', 'docs/handoff/a.md': '@missing.md\n', 'docs/handoff/b.md': '@../nope.md\n' },
      POLICIES.hatchery, 1, 'FAIL CLAUDE.md: @docs/GONE.md resolves to docs/GONE.md, which does not exist\n' +
        'FAIL docs/handoff/a.md: @missing.md resolves to docs/handoff/missing.md, which does not exist\n' +
        'FAIL docs/handoff/b.md: @../nope.md resolves to docs/nope.md, which does not exist\nclaude-check: 4 files, 3 FAIL\n'],
    ['CL15', 'imports outside the root: one info line, not counted toward 190, no FAIL', {
      'CLAUDE.md': `@~/.claude/mine.md\nSee @../sibling/x.md.\n@.claude/agreement/a.md\n`, '.claude/agreement/a.md': lines(187) },
      POLICIES.hatchery, 0, 'info: 2 imports outside the repository, not counted: ~/.claude/mine.md, ../sibling/x.md\nclaude-check: 2 files, 0 FAIL\n'],
    ['CL16', 'a directory target is a FAIL (it loads nothing)', { 'CLAUDE.md': '# T\n@docs\n', 'docs/x.md': 'x\n' },
      POLICIES.hatchery, 1, 'FAIL CLAUDE.md: @docs resolves to docs, which is a directory\nclaude-check: 1 files, 1 FAIL\n'],
    ['CL12', 'a malformed policy fails loudly', { 'CLAUDE.md': '# T\n' }, scratchPolicy('cl-bad', '{ nope'), 1,
      (out) => /^FAIL policy: .+\nclaude-check: 0 files, 1 FAIL\n$/.test(out) || 'expected one "FAIL policy: ..." line and the sentinel'],
  ];
  for (const [id, label, files, policy, code, expect] of CL) {
    if (!selected(id)) continue;
    const root = path.join(scratch, `cl-${id}`);
    for (const [rel, text] of Object.entries(files)) {
      const f = path.join(root, ...rel.split('/'));
      fs.mkdirSync(path.dirname(f), { recursive: true });
      fs.writeFileSync(f, text);
    }
    const r = spawnSync(NODE, [path.join(HOOKS, 'policy.js'), '--claude-check'], { cwd: root, encoding: 'utf8', timeout: 30000, env: envWith({ HOOK_POLICY_FILE: policy }) });
    const out = r.stdout || '';
    let ok = r.status === code;
    let detail = `exit ${r.status}, expected ${code}; stderr: ${(r.stderr || '').trim()}`;
    if (ok) {
      const v = typeof expect === 'function' ? expect(out) : out === expect || `expected stdout ${JSON.stringify(expect)}`;
      ok = v === true;
      if (!ok) detail = v;
    }
    report(id, `--claude-check: ${label} -> exit ${code}`, ok, detail, out);
  }
  // post-edit prints the same FAIL lines on an edit to an agreement or replacement file.
  for (const [id, label, edited, want] of [
    ['CL13', 'post-edit on .claude/agreement/b.md prints the FAIL line', '.claude/agreement/b.md', `${FIX} b neither imported nor replaced`],
    ['CL14', 'post-edit on .claude/project/z.md prints the FAIL line', '.claude/project/z.md', 'FAIL .claude/project/z.md replaces no agreement section']]) {
    if (!selected(id)) continue;
    const root = path.join(scratch, `cl-${id}`);
    for (const [rel, text] of Object.entries({ 'CLAUDE.md': '# T\n@.claude/agreement/a.md\n', '.claude/agreement/a.md': AG('a'), [edited]: 'x\n' })) {
      const f = path.join(root, ...rel.split('/'));
      fs.mkdirSync(path.dirname(f), { recursive: true });
      fs.writeFileSync(f, text);
    }
    const stdin = JSON.stringify({ hook_event_name: 'PostToolUse', tool_name: 'Write', cwd: root, tool_input: { file_path: path.join(root, ...edited.split('/')) } });
    const r = run('post-edit', stdin, envWith({ HOOK_POLICY_FILE: POLICIES.hatchery }));
    const d = parseDecision(r.stdout);
    report(id, label, r.code === 0 && !!d && d.context === want, `expected additionalContext ${JSON.stringify(want)}`, r.stdout);
  }
}

// ---------- ownership.js, the update helper (group O) and the agreement cap (group A; ADR 0016) ----------
// Lines follow the ADR 0013 contract for O1 and A1, which read this repository (like F1); O2 to O7
// run on scratch repositories whose commits stand in for base commits. The helper reads the map at
// --ref through git and the policy through the scratch tree's own copy of policy.js.
{
  const ROOT = path.resolve(HOOKS, '..', '..');
  const contract = (id, status, rest) => {
    noteGroup(id);
    process.stdout.write(`${status} ${id} ${rest}\n`);
    if (status === 'FAIL') fail++; else if (status === 'SKIP') skipped++; else pass++;
  };
  const remotes = spawnSync('git', ['-C', ROOT, 'remote'], { encoding: 'utf8' });
  const hasHatcheryRemote = (remotes.stdout || '').split(/\r?\n/).includes('hatchery');
  const helper = (cwd, args, policy) => {
    const r = spawnSync(NODE, [path.join(HOOKS, 'ownership.js'), ...args],
      { cwd, encoding: 'utf8', timeout: 60000, env: envWith({ HOOK_POLICY_FILE: policy || POLICIES.hatchery }) });
    return { code: r.status, out: r.stdout || '', err: r.stderr || '' };
  };
  if (selected('O1')) {
    if (hasHatcheryRemote) contract('O1', 'SKIP', 'ownership: a hatchery remote exists; --check classifies Hatchery\'s own tree');
    else {
      const r = helper(ROOT, ['--check'], path.join(HOOKS, 'policy.json'));
      if (r.code === 0 && r.out === '') contract('O1', 'PASS', 'ownership');
      else contract('O1', 'FAIL', `ownership: ${r.out.trim().split(/\r?\n/).join(', ') || r.err.trim()} matches no list in .claude/hooks/ownership.json; lift: classify it`);
    }
  }
  if (selected('A1')) {
    if (hasHatcheryRemote) contract('A1', 'SKIP', 'agreement: a hatchery remote exists; the cap is Hatchery\'s');
    else {
      const files = [];
      const walk = (d) => { let es = []; try { es = fs.readdirSync(d, { withFileTypes: true }); } catch (_) { return; } for (const e of es) { const p = path.join(d, e.name); if (e.isDirectory()) walk(p); else if (e.isFile()) files.push(p); } };
      walk(path.join(ROOT, '.claude', 'agreement'));
      const n = files.reduce((t, f) => t + (fs.readFileSync(f, 'utf8').match(/\n/g) || []).length, 0);
      if (n <= 150) contract('A1', 'PASS', 'agreement'); else contract('A1', 'FAIL', `agreement: ${n} lines, cap 150; lift: ADR amendment (architect)`);
    }
  }

  // Scratch repositories. og runs git there and returns stdout; a failure stops the harness (exit 2).
  const og = (dir, ...args) => {
    const r = spawnSync('git', ['-C', dir, '-c', 'user.name=hooks-test', '-c', 'user.email=hooks-test@example.invalid',
      '-c', 'commit.gpgsign=false', ...args], { encoding: 'utf8' });
    if (r.status !== 0) { process.stderr.write(`test-hooks: git ${args.join(' ')} failed: ${r.stderr}\n`); process.exit(2); }
    return (r.stdout || '').trim();
  };
  const O_MAP = {
    baseOwned: ['.claude', 'LICENSE', 'LICENSES', 'docs/hatchery'],
    baseExcluded: ['.claude/hooks/policy.json', '.claude/settings.local.json'],
    mixed: ['.claude/settings.json', '.claude/hooks/fitness.json'],
    projectOwned: ['CLAUDE.md', 'README.md', '.claude/project', 'docs/README.md', 'docs/HANDOFF.md', 'docs/design'],
  };
  const HOOK_FILES = { '.claude/hooks/policy.js': fs.readFileSync(path.join(HOOKS, 'policy.js'), 'utf8'),
    '.claude/hooks/imports.js': fs.readFileSync(path.join(HOOKS, 'imports.js'), 'utf8') };
  const putIn = (dir, files) => {
    for (const [rel, text] of Object.entries(files)) {
      const f = path.join(dir, ...rel.split('/'));
      if (text === null) { fs.rmSync(f, { force: true }); continue; }
      fs.mkdirSync(path.dirname(f), { recursive: true });
      fs.writeFileSync(f, text);
    }
  };
  const commit = (dir, files, msg) => { putIn(dir, files); og(dir, 'add', '-A'); og(dir, 'commit', '-q', '--allow-empty', '-m', msg); return og(dir, 'rev-parse', 'HEAD'); };
  const newRepo = (name, map) => {
    const dir = path.join(scratch, `own-${name}`);
    fs.mkdirSync(dir, { recursive: true });
    og(dir, 'init', '-q');
    og(dir, 'config', 'core.autocrlf', 'false');
    og(dir, 'checkout', '-q', '-b', 'main');
    const base = commit(dir, Object.assign({ '.claude/hooks/ownership.json': typeof map === 'string' ? map : JSON.stringify(map || O_MAP) }, HOOK_FILES), 'base 0');
    return { dir, base };
  };
  const bash = (dir, line) => spawnSync('bash', ['-c', line], { cwd: dir, encoding: 'utf8', timeout: 60000 });
  const unmerged = (dir) => og(dir, 'status', '--porcelain').split('\n').filter((l) => /^(U.|.U|AA|DD) /.test(l));
  const lineRe = /^git diff -M --binary ([0-9a-f]{40}) ([0-9a-f]{40}) -- (.*) \| git apply --3way\n$/;
  const BASH_LINE = 'ownership: run the printed line(s) in the Bash tool, not PowerShell (PowerShell re-encodes the patch in the pipe)\n';
  // A base commit with one change in the set, and HEAD (branch proj) at its parent, so FROM..sha
  // is not empty. Returns { dir, base, next }.
  const changedRepo = (name, files) => {
    const { dir, base } = newRepo(name);
    const next = commit(dir, files || { '.claude/x.md': 'x\n' }, 'base 1');
    og(dir, 'checkout', '-q', '-b', 'proj', base);
    return { dir, base, next };
  };
  // The printed command's diff, with --name-only and without the apply: the paths it would carry.
  const carried = (dir, line) => {
    const r = bash(dir, line.trim().replace('git diff -M --binary', 'git diff --name-only -M').replace(/ \| git apply --3way$/, ''));
    return r.status === 0 ? r.stdout.split('\n').filter((l) => l !== '') : [`(exit ${r.status}: ${r.stderr.trim()})`];
  };

  // O2: shell-inert. Outside single quotes no * ? [ $ or backtick; every word after -- up to | is
  // single-quoted; every quoted word holding a wildcard is pathspec magic (starts with ":(").
  let o2 = null;
  if (selected('O2') || selected('O9') || selected('O10')) {
    const { dir, base, next } = changedRepo('O2');
    const r = helper(dir, ['--command', '--ref', next, '--exclude', 'LICENSES'], scratchPolicy('o2', { update: { exclude: ['docs/legacy/*'] } }));
    o2 = r;
    const problems = [];
    const m = lineRe.exec(r.out);
    if (r.code !== 0 || !m) problems.push(`exit ${r.code}, not one command line; stderr ${r.err.trim()}`);
    else {
      const words = [];
      let unquoted = '';
      for (const w of m[3].match(/'(?:[^']|'\\'')*'|\S+/g)) {
        if (!w.startsWith('\'')) { unquoted += w; problems.push(`unquoted pathspec ${w}`); continue; }
        words.push(w.slice(1, -1));
      }
      const outside = r.out.replace(/'[^']*'/g, '');
      if (/[*?[$`]/.test(outside + unquoted)) problems.push('a shell-active character outside single quotes');
      for (const w of words) if (/[*?[]/.test(w) && !w.startsWith(':(')) problems.push(`wildcard pathspec without magic: ${w}`);
      for (const want of [':(exclude)docs/legacy/*', ':(exclude)LICENSES', ':(exclude).claude/hooks/policy.json', '.claude', 'docs/hatchery']) {
        if (!words.includes(want)) problems.push(`no pathspec ${want}`);
      }
      if (m[1] !== base || m[2] !== next) problems.push('FROM and sha are not the literal shas');
    }
    if (selected('O2')) report('O2', 'ownership --command is shell-inert, with update.exclude and --exclude as plain :(exclude) pathspecs', problems.length === 0, problems.join('; '), r.out);
  }
  // O9: the printed command passes git-guard for a subagent and the main session.
  if (o2 && o2.out) {
    guardCase('O9a', { command: o2.out.trim(), agent: 'coder' }, 'allow');
    guardCase('O9b', { command: o2.out.trim() }, 'allow');
  }
  // O10: stdout is the bare command (one line, no comment line); the Bash-only note is on stderr.
  if (o2 && selected('O10')) {
    const rows = o2.out.split('\n').filter((l) => l !== '');
    report('O10', '--command: stdout is one bare line, no comment; stderr says to run it in the Bash tool, not PowerShell',
      rows.length === 1 && !rows[0].startsWith('#') && o2.err === BASH_LINE, `stdout ${JSON.stringify(o2.out)}; stderr ${JSON.stringify(o2.err)}`);
  }

  // O3: a commit adding a path and its map entry together: the command at that commit carries it,
  // the command at its parent does not.
  if (selected('O3')) {
    const { dir, base } = newRepo('O3');
    const next = commit(dir, { '.claude/x.md': 'x\n' }, 'base 1');
    const third = commit(dir, { 'tools/x.sh': 'echo x\n', '.claude/hooks/ownership.json': JSON.stringify(Object.assign({}, O_MAP, { baseOwned: [...O_MAP.baseOwned, 'tools'] })) }, 'base 2');
    og(dir, 'checkout', '-q', '--detach', base);
    const at = helper(dir, ['--command', '--ref', third]);
    const before = helper(dir, ['--command', '--ref', next]);
    report('O3', 'a path added with its map entry is in the command at that commit, not at its parent',
      at.code === 0 && at.out.includes(' \'tools\' ') && before.code === 0 && lineRe.test(before.out) && !before.out.includes('\'tools\''),
      `at ${JSON.stringify(at.out || at.err)}; parent ${JSON.stringify(before.out || before.err)}`);
  }

  // O7: --command and --per-path with a base line naming HEAD: nothing to apply, nothing on stdout,
  // exit 0 (an empty patch would make git apply exit 128).
  if (selected('O7')) {
    const { dir, base } = newRepo('O7');
    putIn(dir, { 'docs/design/decisions/0001-decanted-from-hatchery.md': `# 0001\n\nHatchery base: ${base}\n` });
    const want = `ownership: nothing to apply: ${base}..${base} touches no path in the set\n`;
    const rc = helper(dir, ['--command', '--ref', 'HEAD']);
    const rp = helper(dir, ['--per-path', '--ref', 'HEAD']);
    report('O7', 'a base line at HEAD: --command and --per-path print nothing, say nothing to apply, exit 0',
      [rc, rp].every((r) => r.code === 0 && r.out === '' && r.err === want), `command ${JSON.stringify(rc)}; per-path ${JSON.stringify(rp)}`);
  }

  // O11: ? and [ in a pattern are literal, as in the policy matcher: x[1].md is excluded, x1.md not.
  if (selected('O11')) {
    const { dir, next } = changedRepo('O11', { 'docs/hatchery/x[1].md': 'a\n', 'docs/hatchery/x1.md': 'b\n', 'docs/hatchery/qa.md': 'c\n' });
    const r = helper(dir, ['--command', '--ref', next, '--exclude', 'docs/hatchery/x[1].md', '--exclude', 'docs/hatchery/q?.md']);
    const paths = r.out ? carried(dir, r.out) : [];
    report('O11', '? and [ in an exclude are escaped as [?] and [[] and match only themselves',
      r.out.includes('\':(exclude)docs/hatchery/x[[]1].md\'') && r.out.includes('\':(exclude)docs/hatchery/q[?].md\'') &&
      JSON.stringify(paths) === JSON.stringify(['docs/hatchery/qa.md', 'docs/hatchery/x1.md']),
      `carried ${JSON.stringify(paths)}; stderr ${r.err.trim()}`, r.out);
  }

  // O12: an exclude pattern holding a space and a quote is quoted so that git reads it whole, and
  // the line passes the guard for the tester.
  let o12 = null;
  if (selected('O12')) {
    const { dir, next } = changedRepo('O12', { 'docs/hatchery/it\'s here/a.md': 'a\n', 'docs/hatchery/keep.md': 'k\n' });
    o12 = helper(dir, ['--command', '--ref', next, '--exclude', 'docs/hatchery/it\'s here/*']);
    const paths = o12.out ? carried(dir, o12.out) : [];
    report('O12', 'an exclude with a space and a quote excludes its file and nothing else',
      o12.out.includes('\':(exclude)docs/hatchery/it\'\\\'\'s here/*\'') && JSON.stringify(paths) === JSON.stringify(['docs/hatchery/keep.md']),
      `carried ${JSON.stringify(paths)}; stderr ${o12.err.trim()}`, o12.out);
  }
  if (o12 && o12.out) guardCase('O12a', { command: o12.out.trim(), agent: 'tester' }, 'allow');

  // O13 and O14: git-guard and post-edit with imports.js gone and a malformed ownership.json beside
  // them: the guard still allows git status, post-edit still exits 0 and keeps its other messages.
  if (selected('O13') || selected('O14')) {
    const min = path.join(scratch, 'hooks-min');
    fs.mkdirSync(min, { recursive: true });
    for (const f of ['git-guard.js', 'policy.js', 'shell-parse.js', 'post-edit.js']) fs.copyFileSync(path.join(HOOKS, f), path.join(min, f));
    fs.writeFileSync(path.join(min, 'ownership.json'), '{ nope');
    const runMin = (hook, stdin) => {
      const r = spawnSync(NODE, [path.join(min, `${hook}.js`)], { input: stdin, encoding: 'utf8', timeout: 30000, env: envWith({ HOOK_POLICY_FILE: POLICIES.hatchery }) });
      return { code: r.status, out: r.stdout || '', err: r.stderr || '' };
    };
    if (selected('O13')) {
      const r = runMin('git-guard', JSON.stringify({ hook_event_name: 'PreToolUse', tool_name: 'Bash', cwd: repoMain, tool_input: { command: 'git status' } }));
      report('O13', 'git-guard allows git status with imports.js deleted and ownership.json malformed', r.code === 0 && r.out === '', `exit ${r.code}; stderr ${r.err.trim()}`, r.out);
    }
    if (selected('O14')) {
      const root = path.join(scratch, 'pe-min');
      putIn(root, { 'CLAUDE.md': lines(41), 'docs/HANDOFF.md': `@handoff/gone.md\n${lines(40)}` });
      const pe = (rel) => runMin('post-edit', JSON.stringify({ hook_event_name: 'PostToolUse', tool_name: 'Write', cwd: root, tool_input: { file_path: path.join(root, ...rel.split('/')) } }));
      const a = pe('CLAUDE.md');
      const b = pe('docs/HANDOFF.md');
      const d = parseDecision(b.out);
      report('O14', 'post-edit exits 0 with imports.js deleted; the import checks drop, the handoff cap still warns',
        a.code === 0 && a.out === '' && b.code === 0 && !!d && d.context === 'post-edit: docs/HANDOFF.md is 41 lines; cap is 40. Move detail to docs/ideas/ or a finding.',
        `CLAUDE.md: exit ${a.code} ${JSON.stringify(a.out)}; HANDOFF: exit ${b.code} ${JSON.stringify(b.out)}`);
    }
  }

  // O4: a malformed map at <sha>, a policy with an unknown key, and a commit without a map: no
  // command, one line naming the fix, exit 1.
  if (selected('O4')) {
    const problems = [];
    const a = newRepo('O4a', JSON.stringify(Object.assign({}, O_MAP, { mixed: '.claude/settings.json' })));
    const ra = helper(a.dir, ['--command', '--ref', a.base]);
    if (!(ra.code === 1 && ra.out === '' && new RegExp(`^ownership: ownership\\.json at ${a.base} malformed: mixed is not an array\\n$`).test(ra.err))) problems.push(`malformed map: exit ${ra.code}, ${JSON.stringify(ra.out + ra.err)}`);
    for (const [name, map, reason] of [['O4k', Object.assign({ basOwned: [] }, O_MAP), 'unknown key basOwned'],
      ['O4w', Object.assign({}, O_MAP, { baseOwned: ['.claude', 'docs/*'] }), 'baseOwned entry "docs/*" is not a literal repository-relative path']]) {
      const x = newRepo(name, JSON.stringify(map));
      const rx = helper(x.dir, ['--command', '--ref', x.base]);
      if (!(rx.code === 1 && rx.out === '' && rx.err === `ownership: ownership.json at ${x.base} malformed: ${reason}\n`)) problems.push(`${reason}: exit ${rx.code}, ${JSON.stringify(rx.out + rx.err)}`);
    }
    const b = newRepo('O4b');
    const rb = helper(b.dir, ['--per-path', '--ref', b.base], scratchPolicy('o4', { update: { exclud: [] } }));
    if (!(rb.code === 1 && rb.out === '' && /^ownership: \.claude\/hooks\/policy\.json malformed: .*unknown key update\.exclud; .*the Edit tool/.test(rb.err))) problems.push(`unknown key: exit ${rb.code}, ${JSON.stringify(rb.out + rb.err)}`);
    const pre = commit(b.dir, { '.claude/hooks/ownership.json': null }, 'no map');
    const rc = helper(b.dir, ['--report', '--ref', pre]);
    if (!(rc.code === 1 && rc.out === '' && rc.err === `ownership: ${pre} predates the map; update from a newer base\n`)) problems.push(`no map: exit ${rc.code}, ${JSON.stringify(rc.out + rc.err)}`);
    report('O4', 'a malformed map, an unknown policy key, a commit before the map: no command, the fix named, exit 1', problems.length === 0, problems.join('; '));
  }

  // O5: after a conflicted apply, git checkout <sha> -- <path> and git checkout HEAD -- <path> each
  // clear the unmerged entry.
  if (selected('O5')) {
    const { dir, base } = newRepo('O5');
    commit(dir, { '.claude/f.txt': 'a\nb\nc\n' }, 'base f');
    const b0 = og(dir, 'rev-parse', 'HEAD');
    const b1 = commit(dir, { '.claude/f.txt': 'a\nB\nc\n' }, 'base edit');
    og(dir, 'checkout', '-q', '-b', 'proj', b0);
    commit(dir, { '.claude/f.txt': 'a\nP\nc\n' }, 'project edit');
    const line = helper(dir, ['--command', '--ref', b1]).out;
    const problems = [];
    for (const [ref, want] of [[b1, 'a\nB\nc\n'], ['HEAD', 'a\nP\nc\n']]) {
      const ap = bash(dir, line);
      if (ap.status !== 1 || unmerged(dir).length === 0) { problems.push(`apply did not conflict (exit ${ap.status}, ${ap.stderr.trim()})`); break; }
      og(dir, 'checkout', ref, '--', '.claude/f.txt');
      const left = unmerged(dir);
      const text = fs.readFileSync(path.join(dir, '.claude', 'f.txt'), 'utf8');
      if (left.length) problems.push(`git checkout ${ref === 'HEAD' ? 'HEAD' : '<sha>'} -- <path> left ${JSON.stringify(left)}`);
      if (text !== want) problems.push(`after checkout ${ref} the file holds ${JSON.stringify(text)}`);
      og(dir, 'checkout', 'HEAD', '--', '.claude/f.txt');
    }
    void base;
    // A base rename the project edited on the same line: D <old>, UU <new>. With a departure row,
    // git show HEAD:<old> > <new> then git add <new>; without, git checkout <sha> -- <new>. Each
    // leaves R <old> -> <new> staged, no U, with the side's text.
    const body = Array.from({ length: 12 }, (_, i) => `line ${i + 1}\n`).join('');
    for (const [name, fix, want] of [['O5row', 'git show HEAD:.claude/old.md > .claude/new.md && git add .claude/new.md', 'line 2, project\n'],
      ['O5base', 'git checkout <sha> -- .claude/new.md', 'line 2, base\n']]) {
      const x = newRepo(name);
      const r0 = commit(x.dir, { '.claude/old.md': body }, 'base old');
      const r1 = commit(x.dir, { '.claude/old.md': null, '.claude/new.md': body.replace('line 2\n', 'line 2, base\n') }, 'base rename');
      og(x.dir, 'checkout', '-q', '-b', 'proj', r0);
      commit(x.dir, { '.claude/old.md': body.replace('line 2\n', 'line 2, project\n') }, 'project edit');
      const ap = bash(x.dir, helper(x.dir, ['--command', '--ref', r1]).out);
      const st = og(x.dir, 'status', '--porcelain');
      if (ap.status !== 1 || !/^D  \.claude\/old\.md$/m.test(st) || !/^UU \.claude\/new\.md$/m.test(st)) { problems.push(`${name}: apply gave exit ${ap.status}, status ${JSON.stringify(st)}`); continue; }
      const fx = bash(x.dir, fix.replace('<sha>', r1));
      const after = og(x.dir, 'status', '--porcelain');
      const text = fs.readFileSync(path.join(x.dir, '.claude', 'new.md'), 'utf8');
      if (fx.status !== 0 || after !== 'R  .claude/old.md -> .claude/new.md' || !text.includes(want)) {
        problems.push(`${name}: after "${fix}" exit ${fx.status}, status ${JSON.stringify(after)}, new.md ${text.includes(want) ? 'right' : 'wrong'}`);
      }
    }
    report('O5', 'a conflict is cleared by git checkout <sha> -- <path> or git checkout HEAD -- <path>; a renamed-and-edited file by its two rename forms', problems.length === 0, problems.join('; '));
  }

  // O6: --per-path on a base rename: one line carries both paths, and running it leaves the renamed
  // file with the project's edit and no stranded copy.
  if (selected('O6')) {
    const { dir } = newRepo('O6');
    const body = Array.from({ length: 20 }, (_, i) => `line ${i + 1}\n`);
    const b0 = commit(dir, { 'docs/hatchery/old.md': body.join('') }, 'base old');
    const moved = body.slice(); moved[1] = 'line 2, base\n';
    const b1 = commit(dir, { 'docs/hatchery/old.md': null, 'docs/hatchery/new.md': moved.join('') }, 'base rename');
    og(dir, 'checkout', '-q', '-b', 'proj', b0);
    const mine = body.slice(); mine[17] = 'line 18, project\n';
    commit(dir, { 'docs/hatchery/old.md': mine.join('') }, 'project edit');
    const r = helper(dir, ['--per-path', '--ref', b1]);
    const rows = r.out.split('\n').filter((l) => l !== '');
    const cmds = rows.filter((l) => !l.startsWith('#'));
    const problems = [];
    if (!(rows.length === 2 && rows[0] === '# R docs/hatchery/old.md -> docs/hatchery/new.md' && cmds.length === 1 &&
      cmds[0].includes(' -- \'docs/hatchery/old.md\' \'docs/hatchery/new.md\' | git apply --3way'))) problems.push(`output ${JSON.stringify(r.out || r.err)}`);
    else {
      const ap = bash(dir, cmds[0]);
      const now = (() => { try { return fs.readFileSync(path.join(dir, 'docs', 'hatchery', 'new.md'), 'utf8'); } catch (_) { return null; } })();
      if (ap.status !== 0) problems.push(`apply exit ${ap.status}: ${ap.stderr.trim()}`);
      if (fs.existsSync(path.join(dir, 'docs', 'hatchery', 'old.md'))) problems.push('old.md stranded');
      if (!now || !now.includes('line 2, base\n') || !now.includes('line 18, project\n')) problems.push(`new.md is ${JSON.stringify(now)}`);
    }
    report('O6', '--per-path carries both paths of a rename; the apply keeps the project\'s edit and strands nothing', problems.length === 0, problems.join('; '), r.out);
  }

  // O8: --report on a fixture with one entry per section.
  if (selected('O8')) {
    const { dir } = newRepo('O8');
    const b0 = commit(dir, { '.claude/agreement/a.md': AG0('a'), '.claude/agreement/b.md': AG0('b'), '.claude/hooks/policy.json': '{}\n' }, 'base sections');
    const b1 = commit(dir, { '.claude/agreement/a.md': `${AG0('a')}More.\n`, '.claude/agreement/c.md': AG0('c'), 'tools/new.sh': 'x\n',
      '.claude/hooks/policy.json': '{ "$comment": "base" }\n', 'LICENSE': 'base licence\n', '.claude/skills/x.md': 'base x\n' }, 'base changes');
    og(dir, 'checkout', '-q', '-b', 'proj', b0);
    commit(dir, { 'CLAUDE.md': '# P\n@.claude/project/a.md\n@.claude/agreement/b.md\n', '.claude/project/a.md': 'Replaces `.claude/agreement/a.md`.\n',
      '.claude/skills/x.md': 'project x\n' }, 'project');
    const r = helper(dir, ['--report', '--ref', b1, '--exclude', 'LICENSE']);
    const want = 'unclassified:\n  tools/new.sh\nexcluded:\n  .claude/hooks/policy.json\n  LICENSE\nreplaced sections:\n' +
      `  a: .claude/agreement/a.md changed in ${b0.slice(0, 7)}..${b1.slice(0, 7)}\nunimported:\n  .claude/agreement/c.md\ncollisions:\n  .claude/skills/x.md\n`;
    report('O8', '--report: unclassified, excluded (map and --exclude), replaced sections, unimported, collisions', r.code === 0 && r.out === want,
      `exit ${r.code}; expected ${JSON.stringify(want)}; stderr ${r.err.trim()}`, r.out);
  }
}
function AG0(s) { return `## ${s}\n\nRule text.\n`; }

// ---------- queue-list.js and the post-edit marker switch (group Q; ADR 0017, 0017-queue-list.md section 5) ----------
// The script runs as a child process from a scratch root (its working directory is the root); the
// hook cases run post-edit on the same trees. Each case checks exit code, stdout and stderr whole.
{
  const QL = path.join(HOOKS, 'queue-list.js');
  const putQ = (root, files) => {
    for (const [rel, text] of Object.entries(files)) {
      const f = path.join(root, ...rel.split('/'));
      fs.mkdirSync(path.dirname(f), { recursive: true });
      fs.writeFileSync(f, text);
    }
  };
  const ql = (root, args) => {
    const r = spawnSync(NODE, [QL, ...args], { cwd: root, encoding: 'utf8', timeout: 30000 });
    return { code: r.status, out: r.stdout || '', err: r.stderr || '' };
  };
  const qcase = (id, label, problems, out) => report(id, label, problems.length === 0, problems.join('; '), out);
  // expect { code, out, err }: each compared whole when present.
  const expectRun = (r, want, tag) => {
    const p = [];
    for (const k of ['code', 'out', 'err']) {
      if (k in want && r[k] !== want[k]) p.push(`${tag ? `${tag}: ` : ''}${k} ${JSON.stringify(r[k])}, expected ${JSON.stringify(want[k])}`);
    }
    return p;
  };
  const fm = (keys) => `---\n${keys}\n---\n`;
  const MK = (dir) => `Listed on demand: node .claude/hooks/queue-list.js ${dir}`;
  const FINDINGS = {
    'docs/findings/README.md': `# Findings\n\nFormat rules.\n\n${MK('findings')}\n`,
    'docs/findings/2026-01-01-open.md': `${fm('date: 2026-01-01\nstatus: open\narea: t')}# Open one\n`,
    'docs/findings/2026-01-02-resolved.md': `${fm('date: 2026-01-02\nstatus: resolved (ADR 0011, 2026-10-14)\narea: t')}# Resolved one\n`,
    'docs/findings/2026-01-03-superseded.md': `${fm('date: 2026-01-03\nstatus: superseded\narea: t')}\n# Superseded one\n`,
    'docs/findings/2026-01-05-done.md': `${fm('date: 2026-01-05\nstatus: done\narea: t')}# Done one\n`,
    // Byte order puts N (0x4e) before d (0x64), where a locale sort would not.
    'docs/findings/2026-01-05-Nofm.md': '# No frontmatter\n\n## Question\n',
    'docs/findings/notes.txt': 'not a finding\n',
    'docs/findings/sub/2026-01-09-deeper.md': '# Deeper, not read\n',
  };
  const IDEAS = {
    'docs/ideas/README.md': `# Ideas\n\n${MK('ideas')}\n`,
    'docs/ideas/2026-02-01-plan.md': `${fm('date: 2026-02-01\nkind: feature')}\n# Plan one\n\nBody.\n\nPlan: build it in one pass.\n`,
    'docs/ideas/2026-02-02-question.md': `${fm('date: 2026-02-02\nkind: question')}\n# Question one\n\nQuestion: which shape?\nAnswer:\n\n`,
    'docs/ideas/2026-02-03-answered.md': `${fm('date: 2026-02-03\nkind: question')}\n# Answered one\n\nQuestion: which shape?\nAnswer:\nThe first.\n`,
    'docs/ideas/2026-02-04-waits.md': `${fm('date: 2026-02-04\nkind: chore\nrelated: [2026-02-01-plan.md]')}\n# Waits one\n\nWaits: a second contributor.\n`,
    'docs/ideas/2026-02-05-untriaged.md': `${fm('date: 2026-02-05\nkind: bug')}\n# Untriaged one\n\n\`\`\`\nPlan: inside a fence, not a block\n\`\`\`\n`,
    'docs/ideas/2026-02-06-kind-idea.md': `${fm('date: 2026-02-06\nkind: idea')}\n# Kind idea\n`,
    'docs/ideas/2026-02-07-foreign.md': `${fm('date: 2026-02-07\nstatus: new\nkind: feature')}\n# Foreign\n\nPlan: x\n`,
    'docs/ideas/2026-02-08-empty.md': '',
  };
  const OPERATOR = {
    'docs/operator/README.md': '# Steps only the owner can take\n\nNo marker here.\n',
    'docs/operator/2026-03-01-h1.md': '\n**Opening line.**\n\n# Run the thing\n',
    'docs/operator/2026-03-02-first-line.md': '\n**Install the key.** It waits on the owner.\n\nMore.\n',
    'docs/operator/2026-03-03-empty.md': '',
  };
  const qRoot = path.join(scratch, 'queue');
  putQ(qRoot, Object.assign({}, FINDINGS, IDEAS, OPERATOR));
  const cleanRoot = path.join(scratch, 'queue-clean');
  putQ(cleanRoot, {
    'docs/findings/2026-01-01-open.md': FINDINGS['docs/findings/2026-01-01-open.md'],
    'docs/ideas/2026-02-01-plan.md': IDEAS['docs/ideas/2026-02-01-plan.md'],
    'docs/operator/2026-03-01-h1.md': OPERATOR['docs/operator/2026-03-01-h1.md'],
  });
  const emptyRoot = path.join(scratch, 'queue-none');
  fs.mkdirSync(emptyRoot, { recursive: true });

  const F_ERR = 'queue-list: 2026-01-05-Nofm.md: status missing; allowed: open, resolved, superseded\n' +
    'queue-list: 2026-01-05-done.md: status is "done"; allowed: open, resolved, superseded\n';
  const F_ROWS = {
    open: '2026-01-01-open.md\topen\tOpen one\n',
    resolved: '2026-01-02-resolved.md\tresolved\tResolved one\n',
    superseded: '2026-01-03-superseded.md\tsuperseded\tSuperseded one\n',
    nofm: '2026-01-05-Nofm.md\t?\tNo frontmatter\n',
    done: '2026-01-05-done.md\t?\tDone one\n',
  };
  const I_ERR = 'queue-list: 2026-02-06-kind-idea.md: kind is "idea"; allowed: bug, feature, question, chore\n' +
    'queue-list: 2026-02-07-foreign.md: frontmatter is "status"; allowed: date, kind, related\n' +
    'queue-list: 2026-02-08-empty.md: title missing; allowed: a # heading or a first line\n' +
    'queue-list: 2026-02-08-empty.md: kind missing; allowed: bug, feature, question, chore\n';
  const I_UNTRIAGED = '2026-02-05-untriaged.md\tbug\tuntriaged\tUntriaged one\n';
  const O_ERR = 'queue-list: 2026-03-03-empty.md: title missing; allowed: a # heading or a first line\n';

  if (selected('Q01')) {
    const r = ql(qRoot, ['findings']);
    qcase('Q01', 'findings text: three columns, byte order, resolved (...) reads resolved, ? rows, one stderr line per problem',
      expectRun(r, { code: 0, out: Object.values(F_ROWS).join(''), err: F_ERR }), r.out);
  }
  if (selected('Q02')) {
    const a = ql(qRoot, ['findings', '--status', 'open']);
    const b = ql(qRoot, ['--status', 'resolved (ADR 0011, 2026-10-14)', 'findings']);
    qcase('Q02', '--status open (column) and --status "resolved (ADR 0011, 2026-10-14)" (whole value) keep their file; stderr unchanged',
      [...expectRun(a, { code: 0, out: F_ROWS.open, err: F_ERR }, 'open'), ...expectRun(b, { code: 0, out: F_ROWS.resolved, err: F_ERR }, 'whole')], a.out + b.out);
  }
  if (selected('Q03')) {
    const r = ql(qRoot, ['findings', '--json']);
    const p = expectRun(r, { code: 0, err: F_ERR });
    const want = [['2026-01-01-open.md', 'open', 'Open one'], ['2026-01-02-resolved.md', 'resolved', 'Resolved one'],
      ['2026-01-03-superseded.md', 'superseded', 'Superseded one'], ['2026-01-05-Nofm.md', '?', 'No frontmatter'], ['2026-01-05-done.md', '?', 'Done one']]
      .map(([file, status, title]) => ({ file, status, title }));
    let got = null;
    try { got = JSON.parse(r.out); } catch (e) { p.push(`stdout is not JSON: ${e.message}`); }
    if (got !== null && JSON.stringify(got) !== JSON.stringify(want)) p.push(`array ${JSON.stringify(got)}, expected ${JSON.stringify(want)}`);
    qcase('Q03', 'findings --json: an array of { file, status, title } in file order, ? as "?"', p, r.out);
  }
  if (selected('Q04')) {
    const r = ql(qRoot, ['ideas']);
    const out = '2026-02-01-plan.md\tfeature\tplan\tPlan one\n' +
      '2026-02-02-question.md\tquestion\tquestion\tQuestion one\n' +
      '2026-02-03-answered.md\tquestion\tanswered\tAnswered one\n' +
      '2026-02-04-waits.md\tchore\twaits\tWaits one\n' +
      I_UNTRIAGED +
      '2026-02-06-kind-idea.md\t?\tuntriaged\tKind idea\n' +
      '2026-02-07-foreign.md\tfeature\t?\tForeign\n' +
      '2026-02-08-empty.md\t?\tuntriaged\t?\n';
    qcase('Q04', 'ideas text: plan, question, answered, waits, untriaged (a fenced Plan: is no block); kind: idea is ?; a foreign key makes block ?; empty file ? in title and kind',
      expectRun(r, { code: 0, out, err: I_ERR }), r.out);
  }
  if (selected('Q05')) {
    const r = ql(qRoot, ['ideas', '--untriaged']);
    qcase('Q05', 'ideas --untriaged: the untriaged file only, never a file with a ?; stderr unchanged', expectRun(r, { code: 0, out: I_UNTRIAGED, err: I_ERR }), r.out);
  }
  if (selected('Q06')) {
    const p = [];
    for (const mode of ['findings', 'ideas', 'operator']) {
      const bad = ql(qRoot, [mode, '--check']);
      if (bad.code !== 1 || bad.out === '') p.push(`${mode} with problems: exit ${bad.code}, stdout ${JSON.stringify(bad.out)}`);
      const good = ql(cleanRoot, [mode, '--check']);
      if (good.code !== 0 || good.out === '' || good.err !== '') p.push(`${mode} clean: exit ${good.code}, stdout ${JSON.stringify(good.out)}, stderr ${JSON.stringify(good.err)}`);
    }
    const hidden = ql(qRoot, ['findings', '--status', 'open', '--check']);
    p.push(...expectRun(hidden, { code: 1, out: F_ROWS.open }, 'a filter hides the ? rows'));
    qcase('Q06', '--check: exit 1 on each directory with a problem (a filter notwithstanding), 0 on a clean copy; stdout printed in both', p);
  }
  if (selected('Q07')) {
    const r = ql(qRoot, ['operator']);
    const out = '2026-03-01-h1.md\tRun the thing\n2026-03-02-first-line.md\t**Install the key.** It waits on the owner.\n2026-03-03-empty.md\t?\n';
    qcase('Q07', 'operator: the H1 title, the first-line title, ? for the empty file', expectRun(r, { code: 0, out, err: O_ERR }), r.out);
  }
  if (selected('Q08')) {
    const r = ql(qRoot, ['findings', '--markdown']);
    const out = '| File | Status | Title |\n|---|---|---|\n' +
      '| `2026-01-01-open.md` | open | Open one |\n| `2026-01-02-resolved.md` | resolved | Resolved one |\n' +
      '| `2026-01-03-superseded.md` | superseded | Superseded one |\n| `2026-01-05-Nofm.md` | ? | No frontmatter |\n' +
      '| `2026-01-05-done.md` | ? | Done one |\n';
    qcase('Q08', '--markdown: a header row and one | `<file>` | row per file', expectRun(r, { code: 0, out, err: F_ERR }), r.out);
  }
  if (selected('Q09')) {
    const p = [];
    for (const args of [[], ['tasks'], ['--untriaged', 'findings'], ['--status', 'open', 'ideas'], ['findings', '--json', '--markdown'],
      ['findings', '--status'], ['findings', '--bogus'], ['findings', 'ideas']]) {
      const r = ql(qRoot, args);
      if (r.code !== 2 || r.out !== '' || !/^queue-list: usage: [^\n]*\n$/.test(r.err)) p.push(`[${args.join(' ')}]: exit ${r.code}, stdout ${JSON.stringify(r.out)}, stderr ${JSON.stringify(r.err)}`);
    }
    p.push(...expectRun(ql(emptyRoot, ['findings']), { code: 2, out: '', err: 'queue-list: docs/findings/ does not exist\n' }, 'absent directory'));
    qcase('Q09', 'usage errors exit 2 with nothing on stdout and one queue-list: usage: line; an absent directory exits 2', p);
  }

  // Hook cases: post-edit on the trees above, under the Hatchery policy fixture.
  const peQ = (root, rel, script) => {
    const stdin = JSON.stringify({ hook_event_name: 'PostToolUse', tool_name: 'Write', cwd: root, tool_input: { file_path: path.join(root, ...rel.split('/')) } });
    const env = envWith({ HOOK_POLICY_FILE: POLICIES.hatchery });
    let x;
    if (script) {
      const r = spawnSync(NODE, [script], { input: stdin, encoding: 'utf8', timeout: 30000, env });
      x = { code: r.status, stdout: r.stdout || '' };
    } else x = run('post-edit', stdin, env);
    const d = x.stdout === '' ? null : parseDecision(x.stdout);
    return { code: x.code, out: x.stdout, ctx: d && d.event === 'PostToolUse' ? d.context : (x.stdout === '' ? null : `unparsed: ${x.stdout}`) };
  };
  const expectHook = (root, rel, want, script) => {
    const r = peQ(root, rel, script);
    return r.code === 0 && r.ctx === want ? [] : [`${rel}: exit ${r.code}, context ${JSON.stringify(r.ctx)}, expected ${JSON.stringify(want)}`];
  };
  const wouldList = (rel, what, dir) => `post-edit: ${rel} would list as ? (${what}). See docs/${dir}/README.md.`;
  const malformed = (dir) => `post-edit: docs/${dir}/README.md carries a Listed on demand: line that is not "Listed on demand: node .claude/hooks/queue-list.js ${dir}"; the directory counts as indexed.`;

  if (selected('Q10')) {
    const nofm = 'docs/findings/2026-01-05-Nofm.md';
    qcase('Q10', 'hook, valid marker: no index warning; the ? warning for a bad field (after the frontmatter warning); a clean file gets nothing', [
      ...expectHook(qRoot, 'docs/findings/2026-01-05-done.md', wouldList('docs/findings/2026-01-05-done.md', 'status is "done"; allowed: open, resolved, superseded', 'findings')),
      ...expectHook(qRoot, nofm, `post-edit: ${nofm} lacks finding frontmatter (date, status, area). See the finding skill.\n${wouldList(nofm, 'status missing; allowed: open, resolved, superseded', 'findings')}`),
      ...expectHook(qRoot, 'docs/ideas/2026-02-07-foreign.md', wouldList('docs/ideas/2026-02-07-foreign.md', 'frontmatter is "status"; allowed: date, kind, related', 'ideas')),
      ...expectHook(qRoot, 'docs/findings/2026-01-01-open.md', null),
      ...expectHook(qRoot, 'docs/ideas/2026-02-05-untriaged.md', null),
      ...expectHook(qRoot, 'docs/findings/README.md', null),
    ]);
  }
  if (selected('Q11')) {
    qcase('Q11', 'hook, no marker: the index warning as today, no ? warning', [
      ...expectHook(qRoot, 'docs/operator/2026-03-01-h1.md', 'post-edit: docs/operator/2026-03-01-h1.md is not listed in operator/README.md. Add one line.'),
      ...expectHook(qRoot, 'docs/operator/2026-03-03-empty.md', 'post-edit: docs/operator/2026-03-03-empty.md is not listed in operator/README.md. Add one line.'),
    ]);
  }
  if (selected('Q12')) {
    const root = path.join(scratch, 'queue-badmarker');
    putQ(root, {
      'docs/ideas/README.md': `# Ideas\n\n${MK('findings')}\n`,
      'docs/ideas/2026-02-01-x.md': IDEAS['docs/ideas/2026-02-07-foreign.md'],
      'docs/findings/README.md': '# Findings\r\n\r\nListed on demand: node queue-list.js findings\r\n',
      'docs/findings/2026-01-01-x.md': FINDINGS['docs/findings/2026-01-05-done.md'],
      'docs/operator/README.md': `# Operator\r\n\r\n${MK('operator')} \t\r\n`,
      'docs/operator/sub/README.md': '# Deeper\n\nListed on demand: anything\n',
    });
    qcase('Q12', 'hook: a wrong-directory marker and a malformed line each warn on the README edit; files under them get the index warning; a CRLF marker with trailing blanks is valid; a deeper README is not read', [
      ...expectHook(root, 'docs/ideas/README.md', malformed('ideas')),
      ...expectHook(root, 'docs/ideas/2026-02-01-x.md', 'post-edit: docs/ideas/2026-02-01-x.md is not listed in ideas/README.md. Add one line.'),
      ...expectHook(root, 'docs/findings/README.md', malformed('findings')),
      ...expectHook(root, 'docs/findings/2026-01-01-x.md', 'post-edit: docs/findings/2026-01-01-x.md is not listed in findings/README.md. Add one line.'),
      ...expectHook(root, 'docs/operator/README.md', null),
      ...expectHook(root, 'docs/operator/sub/README.md', null),
    ]);
  }
  if (selected('Q13')) {
    // A scratch copy of the hooks whose queue-list.js throws on load.
    const dir = path.join(scratch, 'hooks-q13');
    fs.mkdirSync(dir, { recursive: true });
    for (const f of ['post-edit.js', 'policy.js', 'imports.js']) fs.copyFileSync(path.join(HOOKS, f), path.join(dir, f));
    fs.writeFileSync(path.join(dir, 'queue-list.js'), "'use strict';\nthrow new Error('broken on purpose');\n");
    const rel = 'docs/findings/2026-01-05-done.md';
    qcase('Q13', 'hook, valid marker, queue-list.js unloadable: the did-not-load line, no index warning', [
      ...expectHook(qRoot, rel, `post-edit: queue-list.js did not load; ${rel} was not checked.`, path.join(dir, 'post-edit.js')),
      ...expectHook(qRoot, 'docs/operator/2026-03-01-h1.md', 'post-edit: docs/operator/2026-03-01-h1.md is not listed in operator/README.md. Add one line.', path.join(dir, 'post-edit.js')),
    ]);
  }
  if (selected('Q14')) {
    // A directory cannot be made unreadable portably, so the wrapper stubs fs.readFileSync for one file.
    const root = path.join(scratch, 'queue-unreadable');
    putQ(root, {
      'docs/findings/2026-01-01-open.md': FINDINGS['docs/findings/2026-01-01-open.md'],
      'docs/findings/2026-01-02-locked.md': FINDINGS['docs/findings/2026-01-02-resolved.md'],
    });
    const wrap = path.join(scratch, 'q14-wrap.js');
    fs.writeFileSync(wrap, "'use strict';\nconst fs = require('fs');\nconst real = fs.readFileSync;\n" +
      "fs.readFileSync = function (p, ...a) {\n  if (String(p).endsWith('locked.md')) { const e = new Error('denied'); e.code = 'EACCES'; throw e; }\n  return real.call(this, p, ...a);\n};\n" +
      "process.argv.splice(1, 1, process.argv[2]);\nprocess.argv.splice(2, 1);\nrequire('module')._load(process.argv[1], null, true);\n");
    const s = spawnSync(NODE, [wrap, QL, 'findings'], { cwd: root, encoding: 'utf8', timeout: 30000 });
    qcase('Q14', 'an unreadable file: the others still listed, one unreadable line on stderr, exit 2, no stack trace',
      expectRun({ code: s.status, out: s.stdout || '', err: s.stderr || '' }, { code: 2, out: '2026-01-01-open.md\topen\tOpen one\n', err: 'queue-list: 2026-01-02-locked.md: unreadable (EACCES)\n' }), s.stdout);
  }
  if (selected('Q15')) {
    const root = path.join(scratch, 'queue-crlf');
    putQ(root, {
      'docs/findings/README.md': '# Findings\r\n\r\n' + MK('findings') + '\r\n',
      'docs/findings/2026-01-05-done.md': FINDINGS['docs/findings/2026-01-05-done.md'],
    });
    const rel = 'docs/findings/2026-01-05-done.md';
    qcase('Q15', 'hook, CRLF marker README: the marker is valid, the ? warning shows and no index warning',
      expectHook(root, rel, wouldList(rel, 'status is "done"; allowed: open, resolved, superseded', 'findings')));
  }
  if (selected('Q16')) {
    // A scratch copy of the hooks with no queue-list.js at all.
    const dir = path.join(scratch, 'hooks-q16');
    fs.mkdirSync(dir, { recursive: true });
    for (const f of ['post-edit.js', 'policy.js', 'imports.js']) fs.copyFileSync(path.join(HOOKS, f), path.join(dir, f));
    const rel = 'docs/findings/2026-01-05-done.md';
    qcase('Q16', 'hook, valid marker, queue-list.js deleted: the did-not-load line, no index warning',
      expectHook(qRoot, rel, "post-edit: queue-list.js did not load; " + rel + " was not checked.", path.join(dir, 'post-edit.js')));
  }
  if (selected('Q17')) {
    const root = path.join(scratch, 'queue-answer');
    putQ(root, {
      'docs/ideas/2026-02-01-same-line.md': fm('date: 2026-02-01\nkind: question') + '\n# Same line\n\nQuestion: which? Answer: the first.\n',
      'docs/ideas/2026-02-02-no-answer.md': fm('date: 2026-02-02\nkind: question') + '\n# No answer\n\nQuestion: which shape?\n',
    });
    const r = ql(root, ['ideas']);
    qcase('Q17', 'ideas: Answer: text on the Question: line is no answer, and a Question: with no Answer: line, both read question',
      expectRun(r, { code: 0, out: '2026-02-01-same-line.md\tquestion\tquestion\tSame line\n2026-02-02-no-answer.md\tquestion\tquestion\tNo answer\n', err: '' }), r.out);
  }
}

// ---------- policy files themselves ----------
// The repository's own policy.json (whatever the project chose) and the examples must load; the
// Hatchery fixture must equal the loader's defaults, since an absent file means those.
{
  const { load, DEFAULTS } = require('./policy');
  const files = [['LIVE01', 'this repository\'s policy.json loads (or is absent)', path.join(HOOKS, 'policy.json')],
    ['LIVE02', 'policy.examples/task-branches.json loads', POLICIES.taskBranches],
    ['LIVE03', 'policy.examples/single-branch.json loads', POLICIES.singleBranch],
    ['LIVE04', 'policy.examples/per-meter.json loads', POLICIES.perMeter]];
  for (const [id, label, file] of files) {
    if (!selected(id)) continue;
    const r = load(file);
    report(id, label, r.error === null, r.error);
  }
  if (selected('LIVE04')) {
    const merged = load(POLICIES.hatchery).policy;
    const strip = (p) => JSON.stringify(Object.assign({}, p, { usage: Object.assign({}, p.usage, { holdAdvice: '', denyAdvice: '' }) }));
    report('LIVE04', 'the Hatchery fixture equals the loader defaults', strip(merged) === strip(DEFAULTS), `fixture ${strip(merged)} vs defaults ${strip(DEFAULTS)}`);
  }
  // The debug CLI prints git.integrate: null keys when absent (they take git.commit's), the example's values when set.
  for (const [id, file, want] of [['LIVE05', POLICIES.none, { subagents: null, protectedBranches: null }],
    ['LIVE06', POLICIES.taskBranches, { subagents: 'any', protectedBranches: [] }]]) {
    if (!selected(id)) continue;
    const r = spawnSync(NODE, [path.join(HOOKS, 'policy.js'), file], { encoding: 'utf8', timeout: 30000 });
    let got = null;
    try { got = JSON.parse(r.stdout.slice(r.stdout.indexOf('\n') + 1)).git.integrate; } catch (_) { /* not printed */ }
    report(id, `policy.js prints git.integrate for ${id === 'LIVE05' ? 'an absent file' : 'policy.examples/task-branches.json'}`,
      r.status === 0 && JSON.stringify(got) === JSON.stringify(want), `exit ${r.status}, integrate ${JSON.stringify(got)}, want ${JSON.stringify(want)}`, r.stdout);
  }
}

// ---------- settings.json: every command finds its script from any directory (groups H, HS, SL) ----------
// Claude Code runs a command hook and the status line in the session's current directory, which
// follows `cd`; a command such as `node .claude/hooks/git-guard.js` then fails with MODULE_NOT_FOUND
// from a subfolder, and git-guard's onFailure "block" stops every later shell call (seen downstream on
// 2026-10-14). So every command names its script through ${CLAUDE_PROJECT_DIR}, the directory the
// session started in, in either form Claude Code knows (https://code.claude.com/docs/en/hooks):
// exec form ("command" and "args": no shell, the placeholder substituted into each as plain text) or
// shell form (the string run by Git Bash on Windows, by PowerShell when Git Bash is absent or the
// hook's "shell" is "powershell", by sh -c elsewhere, with CLAUDE_PROJECT_DIR in the environment).
// These cases read the repository's own .claude/settings.json and .claude/statusline.js and run each
// command the way Claude Code does, from a subfolder that has no .claude/: against this checkout
// (H01..) and against a copy of the hooks under a project path with a space in it (HS..). H00 is the
// control: the relative form from the same subfolder does not find its script. H99: the base's three
// hooks are all named. A repository without a readable .claude/settings.json skips the group.
{
  const ROOT = path.resolve(HOOKS, '..', '..');
  let settings = null;
  try { settings = JSON.parse(fs.readFileSync(path.join(ROOT, '.claude', 'settings.json'), 'utf8')); } catch (_) { /* absent or unreadable */ }
  if (settings === null) {
    if (selected('H')) { skipped++; noteGroup('H00'); process.stdout.write('SKIP H .claude/settings.json is absent or not JSON\n'); }
  } else {
    const sub = path.join(scratch, 'elsewhere', 'out', 'sub');
    fs.mkdirSync(sub, { recursive: true });
    const hHome = path.join(scratch, 'hook-home');
    fs.mkdirSync(path.join(hHome, '.claude'), { recursive: true });
    const spaced = path.join(scratch, 'project dir');
    fs.mkdirSync(path.join(spaced, '.claude'), { recursive: true });
    fs.cpSync(HOOKS, path.join(spaced, '.claude', 'hooks'), { recursive: true });
    // The shells a command string can run in, the one Claude Code picks by default first. Git Bash gets
    // the directory with forward slashes; under PowerShell Claude Code rewrites ${CLAUDE_PROJECT_DIR} to
    // ${env:CLAUDE_PROJECT_DIR}.
    const shells = [];
    if (process.platform === 'win32') {
      const bash = [process.env.CLAUDE_CODE_GIT_BASH_PATH, 'C:\\Program Files\\Git\\bin\\bash.exe', 'C:\\Program Files (x86)\\Git\\bin\\bash.exe']
        .find((p) => p && fs.existsSync(p));
      if (bash) shells.push({ name: 'Git Bash', exe: bash, argv: (c) => ['-c', c], dir: (d) => d.replace(/\\/g, '/'), cmd: (c) => c });
      shells.push({ name: 'PowerShell', exe: 'powershell.exe', argv: (c) => ['-NoProfile', '-NonInteractive', '-Command', c], dir: (d) => d,
        cmd: (c) => c.split('${CLAUDE_PROJECT_DIR}').join('${env:CLAUDE_PROJECT_DIR}') });
    } else {
      shells.push({ name: 'sh', exe: '/bin/sh', argv: (c) => ['-c', c], dir: (d) => d, cmd: (c) => c });
    }
    const shellFor = (h) => (h.shell === 'powershell' && shells.find((s) => s.name === 'PowerShell')) || shells[0];
    const hooks = [];
    for (const [event, groups] of Object.entries(settings.hooks || {})) {
      for (const g of groups) for (const h of g.hooks || []) if (h.type === 'command') hooks.push({ event, matcher: g.matcher || '', h });
    }
    const inputFor = (event, matcher, projectDir) => {
      const tool = matcher.split('|')[0] || 'Bash';
      if (event === 'PostToolUse') {
        return { hook_event_name: event, tool_name: tool, cwd: sub, tool_input: { file_path: path.join(sub, 'note.txt') } };
      }
      if (tool === 'Bash' || tool === 'PowerShell') {
        return { hook_event_name: event, tool_name: tool, cwd: repoTask, tool_input: { command: 'git status' } };
      }
      return { hook_event_name: event, tool_name: tool, cwd: projectDir, tool_input: {} };
    };
    const subst = (s, projectDir) => String(s).split('${CLAUDE_PROJECT_DIR}').join(projectDir);
    // One hook command as Claude Code runs it, from cwd: exec form spawned with the placeholder
    // substituted, shell form through its shell with CLAUDE_PROJECT_DIR in the environment.
    function runHook(h, stdin, projectDir, cwd) {
      const exec = Array.isArray(h.args);
      const shell = exec ? null : shellFor(h);
      const env = envWith({ HOME: hHome, USERPROFILE: hHome, CLAUDE_PROJECT_DIR: shell ? shell.dir(projectDir) : projectDir,
        GIT_GUARD_MERGED: 'task/T1', HOOK_POLICY_FILE: undefined, POST_EDIT_FORMATTERS_JSON: undefined });
      const r = exec
        ? spawnSync(subst(h.command, projectDir), h.args.map((a) => subst(a, projectDir)), { input: stdin, encoding: 'utf8', timeout: 30000, cwd, env })
        : spawnSync(shell.exe, shell.argv(shell.cmd(h.command)), { input: stdin, encoding: 'utf8', timeout: 30000, cwd, env });
      return { code: r.status, stdout: r.stdout || '', stderr: r.stderr || (r.error ? String(r.error) : ''), form: exec ? 'exec form' : shell.name };
    }
    const missing = (r) => /MODULE_NOT_FOUND|Cannot find module/.test(r.stderr);
    const PLACEHOLDER = /\$\{?CLAUDE_PROJECT_DIR\b/;
    if (selected('H00')) {
      const r = runHook({ command: NODE, args: ['.claude/hooks/git-guard.js'] }, JSON.stringify(inputFor('PreToolUse', 'Bash', ROOT)), ROOT, sub);
      report('H00', 'control: the relative form `node .claude/hooks/git-guard.js` from a subfolder -> MODULE_NOT_FOUND', r.code !== 0 && missing(r),
        `exit ${r.code}, stderr ${r.stderr.trim().slice(0, 200)}`);
    }
    let n = 0;
    for (const { event, matcher, h } of hooks) {
      n++;
      const words = [h.command, ...(Array.isArray(h.args) ? h.args : [])];
      const script = words.join(' ').replace(/["']/g, '').replace(/^.*[\\/]/, '');
      for (const [prefix, projectDir, where] of [['H', ROOT, 'this checkout'], ['HS', spaced, 'a project path with a space']]) {
        const id = `${prefix}${String(n).padStart(2, '0')}`;
        if (!selected(id)) continue;
        const r = runHook(h, JSON.stringify(inputFor(event, matcher, projectDir)), projectDir, sub);
        const placeholder = words.some((w) => PLACEHOLDER.test(String(w)));
        const ok = r.code === 0 && !missing(r) && r.stdout === '' && placeholder;
        report(id, `settings.json ${event} ${matcher} ${script} (${r.form}) run from a subfolder (${where}) finds its script and allows`, ok,
          (placeholder ? '' : 'the command does not name its script through ${CLAUDE_PROJECT_DIR}; ') +
            `exit ${r.code}, stderr ${r.stderr.trim().slice(0, 300)}`, r.stdout);
      }
    }
    if (selected('H99')) {
      const named = hooks.map(({ h }) => [h.command, ...(Array.isArray(h.args) ? h.args : [])].join(' ').replace(/\\/g, '/'));
      const absent = ['usage-gate.js', 'git-guard.js', 'post-edit.js'].filter((s) => !named.some((c) => c.includes(`.claude/hooks/${s}`)));
      report('H99', 'settings.json runs the base\'s three hooks (usage-gate, git-guard, post-edit)', absent.length === 0, `not named: ${absent.join(', ')}`);
    }

    // The status line. Its settings have no exec form (type, command, padding, refreshInterval), so the
    // command runs through a shell in the session's current directory, with CLAUDE_PROJECT_DIR in its
    // environment. SL00 is the control (the relative command from a subfolder writes nothing); SL01..
    // run settings.json's command from a subfolder, with a captured stdin, through each shell present,
    // here and under a path with a space: the line is printed and the session file under usage-state/
    // written (ADR 0011). A settings.json without a command status line skips SL01...
    const slCommand = settings.statusLine && settings.statusLine.type === 'command' ? settings.statusLine.command || '' : '';
    if (fs.existsSync(path.join(ROOT, '.claude', 'statusline.js'))) {
      fs.copyFileSync(path.join(ROOT, '.claude', 'statusline.js'), path.join(spaced, '.claude', 'statusline.js'));
    }
    let slRuns = 0;
    function runStatusLine(command, shell, projectDir) {
      const home = path.join(scratch, `sl-shell-home-${++slRuns}`);
      fs.mkdirSync(path.join(home, '.claude'), { recursive: true });
      const r = spawnSync(shell.exe, shell.argv(shell.cmd(command)), { input: rl(22, 33), encoding: 'utf8', timeout: 60000, cwd: sub,
        env: envWith({ HOME: home, USERPROFILE: home, HOOK_POLICY_FILE: POLICIES.hatchery, CLAUDE_PROJECT_DIR: shell.dir(projectDir) }) });
      let file = null;
      try { file = JSON.parse(fs.readFileSync(path.join(home, '.claude', 'usage-state', `${SL_SID}.json`), 'utf8')); } catch (_) { /* not written */ }
      return { code: r.status, stdout: (r.stdout || '').trim(), stderr: r.stderr || (r.error ? String(r.error) : ''), file };
    }
    if (selected('SL00')) {
      const r = runStatusLine('node .claude/statusline.js', shells[0], ROOT);
      report('SL00', `control: the relative status line \`node .claude/statusline.js\` from a subfolder (${shells[0].name}) writes nothing`,
        r.file === null && r.stdout === '', `exit ${r.code}, file ${JSON.stringify(r.file)}, line ${r.stdout}`);
    }
    if (slCommand === '') {
      if (selected('SL01')) { skipped++; noteGroup('SL01'); process.stdout.write('SKIP SL01 settings.json has no command status line\n'); }
    } else {
      let k = 0;
      for (const shell of shells) {
        for (const [projectDir, where] of [[ROOT, 'this checkout'], [spaced, 'a project path with a space']]) {
          const id = `SL${String(++k).padStart(2, '0')}`;
          if (!selected(id)) continue;
          const r = runStatusLine(slCommand, shell, projectDir);
          const named = slCommand.includes('${CLAUDE_PROJECT_DIR}');
          const ok = named && r.code === 0 && r.stdout.includes('5h 22%') && !!r.file && r.file.five_hour.used_percentage === 22;
          report(id, `settings.json status line from a subfolder through ${shell.name} (${where}) prints and writes usage-state/<session_id>.json`, ok,
            (named ? '' : 'the command does not name its script as "${CLAUDE_PROJECT_DIR}/...", the form both shells read; ') +
              `exit ${r.code}, file ${JSON.stringify(r.file)}, stderr ${r.stderr.trim().slice(0, 300)}`, r.stdout);
        }
      }
    }
  }
}

// ---------- the harness's own command line ----------
if (selected('CLI01')) {
  const r = spawnSync(NODE, [__filename, '--only', 'nosuch'], { encoding: 'utf8', timeout: 60000 });
  report('CLI01', '--only that selects no case -> exit 2, usage line', r.status === 2 && r.stderr === 'test-hooks: --only nosuch selects no case\n',
    `exit ${r.status}, stderr ${JSON.stringify(r.stderr)}`, r.stdout);
}

if (opt.keep) process.stdout.write(`scratch tree kept at ${scratch}\n`);
else fs.rmSync(scratch, { recursive: true, force: true });

if (opt.only && pass + fail + skipped === 0) {
  process.stderr.write(`test-hooks: --only ${opt.only} selects no case\n`);
  process.exit(2);
}

process.stdout.write(`\ngroups: ${[...groups].join(' ')}\n${pass} passed, ${fail} failed, ${skipped ? `${skipped} skipped, ` : ''}${pass + fail + skipped} total\n`);
process.exit(fail > 0 ? 1 : 0);
