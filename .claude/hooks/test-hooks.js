#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// Test harness and debug interface for the hooks in this directory.
// Spec: docs/design/decisions/0002-hook-specs.md. Each case runs a hook as a child
// process with JSON on stdin, exactly as Claude Code does, and checks exit code and stdout.
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
//
// Isolation: post-edit cases run against a scratch tree in the OS temp dir (deleted at
// the end); git-guard cases run in two scratch repositories (one on main, one on task/T1);
// usage-gate and statusline cases point HOME/USERPROFILE at a scratch dir and remove
// CLAUDE_CONFIG_DIR unless the case sets it to a scratch dir. Every case names
// its policy file through HOOK_POLICY_FILE: the Hatchery fixture, an example, none, or a scratch
// file. No file of the repository is read except to check that policy.json loads, and ~/.claude is never touched. Exit codes: 0 all pass, 1 any
// failure, 2 usage error.
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
function report(id, label, ok, detail, stdout) {
  if (opt.only && !id.startsWith(opt.only)) return;
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
  edit: { capExempt: [], anchorExempt: ['docs/design/proposals/*', 'docs/design/decisions/*', 'docs/findings/*'], formatters: {} },
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
}

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
  policy: 'policy file is malformed (',
};
const NOTE_MARK = 'git-guard reads the command line, not what runs';
const withNote = (d) => d.reason.includes(NOTE_MARK) || 'reason lacks the note about command text';
const noNote = (d) => !d.reason.includes(NOTE_MARK) || 'reason carries the note, but git is the command here';

// Runs git-guard once. o: { command, agent, tool, cwd, policy, raw, merged }. expect: 'allow',
// 'internal' (exit 2, reason on stderr), a rule number, or 'policy' (malformed policy deny).
function guardCase(id, o, expect, extra) {
  if (!selected(id)) return;
  const input = { hook_event_name: 'PreToolUse', tool_name: o.tool || 'Bash', cwd: o.cwd || repoMain, tool_input: { command: o.command } };
  if (o.agent !== null && o.agent !== undefined) input.agent_type = o.agent;
  const stdin = o.raw !== undefined && o.raw !== null ? o.raw : JSON.stringify(input);
  // task/T1 has a merged pull request, task/T2 does not (git-guard's GIT_GUARD_MERGED seam).
  const r = run('git-guard', stdin, envWith({ HOOK_POLICY_FILE: o.policy || POLICIES.hatchery, GIT_GUARD_MERGED: 'task/T1' }));
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
  ['GR43', 'git merge feature', 'Bash', 'general-purpose', 4, repoMain, POLICIES.taskBranches],
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
];
for (const [n, command, agent, cwd, want] of GP) {
  for (const p of [H, 'none', 'taskBranches', 'singleBranch']) {
    const tag = { hatchery: 'H', none: 'N', taskBranches: 'T', singleBranch: 'S' }[p];
    guardCase(`GP${n}${tag}`, { command, agent, cwd, policy: POLICIES[p], policyName: p }, want[p === 'none' ? H : p]);
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
write('CLAUDE.md', lines(151));
write('src/CLAUDE.md', lines(151));
write('root150/CLAUDE.md', lines(150));

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
  ['PX12', '151-line CLAUDE.md', absOf('CLAUDE.md'),
    'post-edit: CLAUDE.md is 151 lines; cap is 150 (.claude/rules/docs.md). Move facts to docs/reference/ and path-scoped rules to .claude/rules/.'],
  ['PX13', '150-line CLAUDE.md at another root', absOf('root150/CLAUDE.md'), null, null, null, null, absOf('root150')],
  ['PX14', '151-line CLAUDE.md below the root', absOf('src/CLAUDE.md'), null],
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

// ---------- usage-gate (section 3 text) ----------
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
}

// ---------- this project: settings.json: every hook command finds its script from any directory (T99) ----------
// Claude Code runs a hook in the shell's current directory, which follows `cd`; a command such as
// `node .claude/hooks/git-guard.js` then fails with MODULE_NOT_FOUND in a subfolder, and git-guard's
// onFailure "block" stops every later shell call (2026-10-14). The hooks therefore run in exec form,
// `"command": "node", "args": ["${CLAUDE_PROJECT_DIR}/.claude/hooks/<name>.js"]`: no shell, and Claude
// Code substitutes the placeholder with the directory the session started in (the main checkout or a
// worktree; https://code.claude.com/docs/en/hooks, "Reference scripts by path"). These cases run each
// command from settings.json the way Claude Code does, from a subfolder that has no .claude/, against
// this checkout (H01..) and against a copy of the hooks under a project path with a space in it (HS..).
// H00 is the control: the old relative form, run from the same subfolder, does not find its script.
{
  const ROOT = path.resolve(HOOKS, '..', '..');
  const settings = JSON.parse(fs.readFileSync(path.join(ROOT, '.claude', 'settings.json'), 'utf8'));
  const sub = path.join(scratch, 'elsewhere', 'out', 'sub');
  fs.mkdirSync(sub, { recursive: true });
  const hHome = path.join(scratch, 'hook-home');
  fs.mkdirSync(path.join(hHome, '.claude'), { recursive: true });
  const spaced = path.join(scratch, 'project dir');
  fs.mkdirSync(path.join(spaced, '.claude'), { recursive: true });
  fs.cpSync(HOOKS, path.join(spaced, '.claude', 'hooks'), { recursive: true });
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
  // Exec form as Claude Code runs it: the placeholder substituted as plain text, no shell, cwd as given.
  const subst = (s, projectDir) => String(s).split('${CLAUDE_PROJECT_DIR}').join(projectDir);
  function runHook(h, stdin, projectDir, cwd) {
    const env = envWith({ HOME: hHome, USERPROFILE: hHome, CLAUDE_PROJECT_DIR: projectDir, GIT_GUARD_MERGED: 'task/T1',
      HOOK_POLICY_FILE: undefined, POST_EDIT_FORMATTERS_JSON: undefined });
    const r = Array.isArray(h.args)
      ? spawnSync(subst(h.command, projectDir), h.args.map((a) => subst(a, projectDir)), { input: stdin, encoding: 'utf8', timeout: 30000, cwd, env })
      : spawnSync(h.command, { input: stdin, encoding: 'utf8', timeout: 30000, cwd, env, shell: true });
    return { code: r.status, stdout: r.stdout || '', stderr: r.stderr || (r.error ? String(r.error) : '') };
  }
  const missing = (r) => /MODULE_NOT_FOUND|Cannot find module/.test(r.stderr);
  if (selected('H00')) {
    const r = runHook({ command: NODE, args: ['.claude/hooks/git-guard.js'] }, JSON.stringify(inputFor('PreToolUse', 'Bash', ROOT)), ROOT, sub);
    report('H00', 'control: the old form `node .claude/hooks/git-guard.js` from a subfolder -> MODULE_NOT_FOUND', r.code !== 0 && missing(r),
      `exit ${r.code}, stderr ${r.stderr.trim().slice(0, 200)}`);
  }
  let n = 0;
  for (const { event, matcher, h } of hooks) {
    n++;
    const script = (Array.isArray(h.args) ? h.args.join(' ') : h.command).replace(/^.*[\\/]/, '');
    for (const [prefix, projectDir, where] of [['H', ROOT, 'this checkout'], ['HS', spaced, 'a project path with a space']]) {
      const id = `${prefix}${String(n).padStart(2, '0')}`;
      if (!selected(id)) continue;
      const r = runHook(h, JSON.stringify(inputFor(event, matcher, projectDir)), projectDir, sub);
      const placeholder = Array.isArray(h.args) && h.args.some((a) => a.includes('${CLAUDE_PROJECT_DIR}'));
      const ok = r.code === 0 && !missing(r) && r.stdout === '' && placeholder;
      report(id, `settings.json ${event} ${matcher} ${script} run from a subfolder (${where}) finds its script and allows`, ok,
        (placeholder ? '' : 'the command does not name its script through ${CLAUDE_PROJECT_DIR} in exec form (args); ') +
          `exit ${r.code}, stderr ${r.stderr.trim().slice(0, 300)}`, r.stdout);
    }
  }
  if (selected('H99')) report('H99', 'settings.json has the three command hooks', n === 3, `found ${n}`);

  // T105: the status line. Its settings have no exec form (type, command, padding, refreshInterval), so the command runs
  // through a shell (Git Bash on Windows, else PowerShell) in the session's current directory, with CLAUDE_PROJECT_DIR in
  // its environment (forward slashes under Git Bash; under PowerShell Claude Code rewrites ${CLAUDE_PROJECT_DIR} to
  // ${env:CLAUDE_PROJECT_DIR}). SL00 is the control (the old relative command from a subfolder writes nothing); SL01..
  // run settings.json's command from a subfolder, with a captured stdin, through each such shell present, here and
  // under a path with a space: the line is printed and the session file under usage-state/ written (ADR 0011 in the base).
  fs.copyFileSync(path.join(ROOT, '.claude', 'statusline.js'), path.join(spaced, '.claude', 'statusline.js'));
  const slCommand = (settings.statusLine || {}).command || '';
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
    report('SL00', `control: the old status line \`node .claude/statusline.js\` from a subfolder (${shells[0].name}) writes nothing`,
      r.file === null && r.stdout === '', `exit ${r.code}, file ${JSON.stringify(r.file)}, line ${r.stdout}`);
  }
  let k = 0;
  for (const shell of shells) {
    for (const [projectDir, where] of [[ROOT, 'this checkout'], [spaced, 'a project path with a space']]) {
      const id = `SL${String(++k).padStart(2, '0')}`;
      if (!selected(id)) continue;
      const r = runStatusLine(slCommand, shell, projectDir);
      const named = slCommand.includes('${CLAUDE_PROJECT_DIR}');
      const ok = named && r.code === 0 && r.stdout.includes('5h 22%') && !!r.file && r.file.five_hour.used_percentage === 22;
      report(id, `settings.json status line from a subfolder through ${shell.name} (${where}) prints and writes usage-state/<session_id>.json`, ok,
        (named ? '' : 'the command does not name its script through ${CLAUDE_PROJECT_DIR}; ') +
          `exit ${r.code}, file ${JSON.stringify(r.file)}, stderr ${r.stderr.trim().slice(0, 300)}`, r.stdout);
    }
  }
}

if (opt.keep) process.stdout.write(`scratch tree kept at ${scratch}\n`);
else fs.rmSync(scratch, { recursive: true, force: true });

process.stdout.write(`\n${pass} passed, ${fail} failed, ${pass + fail} total\n`);
process.exit(fail > 0 ? 1 : 0);
