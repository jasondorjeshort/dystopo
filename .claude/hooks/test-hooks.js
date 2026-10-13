#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// Test harness and debug interface for the hooks in this directory. From the Hatchery base
// (its .claude/hooks/test-hooks.js); every base case is kept, and a case whose expectation
// differs here names the exception it tests (E1 to E5, A1, A2:
// docs/design/decisions/0021-git-guard-exceptions.md). The usage-gate cases test Dystopo's own
// gate (75%, ~/.claude/usage.json, DYSTOPO_GATE_OVERRIDE), not the base's.
// Each case runs a hook as a child process with JSON on stdin, exactly as Claude Code does,
// and checks exit code and stdout.
//
// Usage:
//   node .claude/hooks/test-hooks.js                  run every case; exit 1 on any failure
//   node .claude/hooks/test-hooks.js --only G0        run cases whose id starts with G0
//   node .claude/hooks/test-hooks.js --verbose        also print each hook's raw stdout
//   node .claude/hooks/test-hooks.js --keep           keep the scratch tree (path printed)
//   node .claude/hooks/test-hooks.js --input <file> <git-guard|post-edit|usage-gate>
//       run one hook on the raw contents of <file> and print exit code, stdout, stderr.
//       Use this to reproduce a bug: write the hook input JSON to a file with the Write
//       tool (a command line containing e.g. a force push would itself trip git-guard).
//
// Isolation: git-guard cases that depend on the current branch run against two scratch git
// repositories (one on main, one on task/T1); post-edit cases run against a scratch docs tree;
// usage-gate cases point HOME/USERPROFILE at a scratch dir. All in the OS temp dir, deleted at
// the end. The real docs/, the repository and ~/.claude are never touched.
// Exit codes: 0 all pass, 1 any failure, 2 usage error.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const HOOKS = __dirname;
const NODE = process.execPath;

// ---------- CLI ----------
const argv = process.argv.slice(2);
const opt = { only: null, verbose: false, keep: false, input: null, hook: null };
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--verbose') opt.verbose = true;
  else if (a === '--keep') opt.keep = true;
  else if (a === '--only' && argv[i + 1]) opt.only = argv[++i];
  else if (a === '--input' && argv[i + 1] && argv[i + 2]) { opt.input = argv[++i]; opt.hook = argv[++i]; }
  else { process.stderr.write(`test-hooks: unknown or incomplete argument: ${a}\n`); process.exit(2); }
}

function run(hook, stdin, env) {
  const r = spawnSync(NODE, [path.join(HOOKS, `${hook}.js`)], {
    input: stdin, encoding: 'utf8', timeout: 30000, env: env || process.env,
  });
  return { code: r.status, stdout: r.stdout || '', stderr: r.stderr || '', error: r.error };
}

if (opt.input) {
  if (!['git-guard', 'post-edit', 'usage-gate'].includes(opt.hook)) {
    process.stderr.write(`test-hooks: unknown hook ${opt.hook}\n`);
    process.exit(2);
  }
  const r = run(opt.hook, fs.readFileSync(opt.input, 'utf8'));
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

// ---------- scratch git repositories ----------
function git(dir, ...args) {
  const r = spawnSync('git', ['-C', dir, '-c', 'user.name=hooks-test', '-c', 'user.email=hooks-test@example.invalid',
    '-c', 'core.hooksPath=' + path.join(scratch, 'no-hooks'), ...args], { encoding: 'utf8' });
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
const fwd = (p) => p.split(path.sep).join('/');

// ---------- git-guard ----------
const PREFIX = {
  1: 'force push',
  2: 'remote branch deletion',
  3: 'agents push only task/* branches',
  4: 'agents do not commit on main',
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
};

// [id, command, agent_type or null, expect: 'allow' | rule number | 'internal', raw stdin override, extra check, cwd]
// cwd defaults to the scratch repository on main. A comment names the exception when a base case
// expects something different here.
const G = [
  // The base's 25-row table, in order.
  ['G01', 'git status', null, 'allow'],
  ['G02', 'git push', null, 'allow'],
  ['G03', 'git push', 'pusher', 3], // E1: base allows the pusher; here no agent pushes main
  ['G04', 'git push', 'runner', 3],
  ['G05', 'git push origin main --force', 'pusher', 1],
  ['G06', 'git push -uf origin main', null, 1],
  ['G07', 'git push origin :old', null, 2],
  ['G08', 'git commit -m x', 'coder', 4],
  ['G09', 'git commit -m x', 'runner', 4], // E2: base allows the runner; here no agent commits on main
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
  ['G24', 'echo "git push --force"', null, 1, null, (d) => d.reason.includes('only quoted') || 'reason lacks the false-positive note'],
  ['G25', 'not json', null, 'internal', 'not json'],
  // The base's extra cases.
  ['GX01', 'git push origin +main', null, 1],
  ['GX02', 'git push --force-with-lease', 'pusher', 1], // E3 applies only to task/*; this repo is on main
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
  // Dystopo's exceptions (ADR 0021).
  ['D01', 'git push -u origin task/T1', 'general-purpose', 'allow'], // E1
  ['D02', 'git push', 'general-purpose', 'allow', null, null, repoTask], // E1, current branch task/T1
  ['D03', 'git push origin HEAD:main', 'general-purpose', 3, null, null, repoTask],
  ['D04', 'git push origin HEAD', 'general-purpose', 'allow', null, null, repoTask],
  ['D05', 'git push --force-with-lease origin task/T1', 'general-purpose', 'allow'], // E3
  ['D06', 'git push --force-with-lease', 'general-purpose', 'allow', null, null, repoTask], // E3
  ['D07', 'git push --force-with-lease origin main', null, 1],
  ['D08', 'git push --force origin task/T1', 'general-purpose', 1],
  ['D09', 'git push origin +task/T1', 'general-purpose', 1],
  ['D10', 'git commit -m x', 'general-purpose', 'allow', null, null, repoTask], // E2
  ['D11', 'git push origin --delete task/T1', null, 'allow'], // E4, merged
  ['D12', 'git push origin --delete task/T1', 'general-purpose', 'allow'], // E4, merged; a cleanup agent
  ['D12b', 'git push origin --delete task/T2', null, 2], // E4: not merged
  ['D13', 'git push origin :task/T1', null, 'allow'], // E4
  ['D13b', 'git push origin :task/T2', 'general-purpose', 2],
  ['D14', 'git push origin --delete main', null, 2],
  ['D15', 'git branch -D task/T1', null, 'allow'], // E5, merged
  ['D16', 'git branch -D task/T1', 'general-purpose', 'allow'], // E5
  ['D16b', 'git branch -D task/T2', null, 9], // E5: not merged
  ['D17', 'git branch -D task/T1 topic', null, 9],
  ['D17b', 'git branch -D task/T1 task/T2', null, 9],
  ['D18', 'git commit --no-verify -m x', null, 13], // A1
  ['D19', 'git commit -nm x', null, 13], // A1
  ['D20', 'git push --no-verify origin task/T1', 'general-purpose', 13], // A1
  ['D21', 'git -c core.hooksPath=/dev/null commit -m x', null, 14], // A2
  ['D22', 'git config core.hooksPath tools/git-hooks', null, 'allow'],
  ['D23', 'git config --unset core.hooksPath', null, 14], // A2
  ['D24', 'git config core.hooksPath .git/hooks', null, 14], // A2
  ['D25', 'git config core.hooksPath', 'general-purpose', 'allow'],
  ['D26', 'git push --tags', 'general-purpose', 3],
  ['D27', 'git push origin v0.1.5-alpha', null, 'allow'],
  ['D28', `cd ${fwd(repoTask)} && git push`, 'general-purpose', 'allow'],
  ['D29', `git -C ${fwd(repoTask)} commit -m x`, 'general-purpose', 'allow'],
  ['D30', `git -C ${fwd(repoMain)} commit -m x`, 'general-purpose', 4, null, null, repoTask],
  ['D31', 'git rebase origin/main', 'general-purpose', 'allow'],
  ['D32', 'git push origin task/T1:main', 'general-purpose', 3],
  ['D33', 'gh pr merge 84 --squash', null, 'allow'],
];

for (const [id, command, agent, expect, rawOverride, extra, cwd] of G) {
  if (!selected(id)) continue;
  const input = { hook_event_name: 'PreToolUse', tool_name: 'Bash', cwd: cwd || repoMain, tool_input: { command } };
  if (agent !== null) input.agent_type = agent;
  const stdin = rawOverride !== undefined && rawOverride !== null ? rawOverride : JSON.stringify(input);
  // task/T1 has a merged pull request, task/T2 does not (git-guard's GIT_GUARD_MERGED seam).
  const r = run('git-guard', stdin, Object.assign({}, process.env, { GIT_GUARD_MERGED: 'task/T1' }));
  const where = cwd === repoTask ? ' on task/T1' : '';
  const label = `${JSON.stringify(command.replace(scratch.split(path.sep).join('/'), '<scratch>'))} [${agent === null ? '-' : JSON.stringify(agent)}]${where} -> ${expect === 'allow' || expect === 'internal' ? expect : `deny rule ${expect}`}`;
  let ok = false;
  let detail = '';
  if (r.code !== 0) detail = `exit ${r.code}, expected 0; stderr: ${r.stderr.trim()}`;
  else if (expect === 'allow') { ok = r.stdout === ''; detail = 'expected no stdout'; }
  else {
    const d = parseDecision(r.stdout);
    if (!d || d.event !== 'PreToolUse' || d.decision !== 'deny' || typeof d.reason !== 'string') detail = 'expected deny JSON';
    else {
      const want = expect === 'internal' ? 'git-guard: internal error (' : `git-guard: ${PREFIX[expect]}: \``;
      ok = d.reason.startsWith(want);
      detail = `reason should start with ${JSON.stringify(want)}; got ${JSON.stringify(d.reason)}`;
      if (ok && extra) { const e = extra(d); if (e !== true) { ok = false; detail = e; } }
    }
  }
  report(id, label, ok, detail, r.stdout);
}

// ---------- post-edit ----------
const docs = path.join(scratch, 'tree');
const write = (rel, text) => {
  const f = path.join(docs, ...rel.split('/'));
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, text);
};
const lines = (n, prefix) => Array.from({ length: n }, (_, i) => `${prefix || 'line'} ${i + 1}\n`).join('');

write('docs/reference/README.md', '# Reference\n\n| `big.md` | `clean.md` | `noverified.md` |\n');
write('docs/reference/big.md', `---\nverified: 2026-10-11\nsource: observed\n---\n${lines(297)}`); // 301 newlines
write('docs/reference/clean.md', '---\nverified: 2026-10-11\nsource: observed\n---\n\n# Clean\n');
write('docs/reference/noverified.md', '---\nsource: observed\n---\n# No verified\n');
write('docs/reference/unlisted.md', '---\nverified: 2026-10-11\n---\n# Unlisted\n');
const realHandoff = fs.readFileSync(path.join(HOOKS, '..', '..', 'docs', 'HANDOFF.md'), 'utf8');
const handoffLines = realHandoff.split('\n').filter((_, i, a) => i < a.length - 1 || a[i] !== '');
while (handoffLines.length < 41) handoffLines.push('padding');
write('docs/HANDOFF.md', handoffLines.slice(0, 41).join('\n') + '\n'); // 41-line copy of the real one
write('docs/findings/README.md', '# Findings\n\n`2026-01-01-nofm.md` `2026-01-02-ok.md`\n');
write('docs/findings/2026-01-01-nofm.md', '# A finding without frontmatter\n\n## Question\n');
write('docs/findings/2026-01-02-ok.md', '---\ndate: 2026-01-02\nstatus: open\narea: test\n---\n# OK\n');
write('docs/design/README.md', '# Design\n\n- [0009 Something](decisions/0009-something.md)\n');
write('docs/design/decisions/README.md', '# Decision records\n');
write('docs/design/decisions/0009-something.md', '# 0009. Something\n');
write('docs/design/proposals/README.md', '# Proposals\n\n`0009-long.md`\n');
write('docs/design/proposals/0009-long.md', lines(320));
write('docs/PLANNING.md', lines(620));
write('docs/TASKS.md', lines(580));
write('docs/SERVER.md', lines(870));
write('docs/ARCHITECTURE.md', lines(301));
write('src/app.txt', 'not a doc\n');
write('README.md', lines(400));

const absOf = (rel) => path.join(docs, ...rel.split('/'));
const fmtOk = { '.txt': [NODE, '-e', 'process.exit(0)'] };
const fmtBad = { '.txt': [NODE, '-e', 'console.error("bad formatter output");process.exit(3)'] };
const CAP_MSG = (rel, n) => `post-edit: ${rel} is ${n} lines; cap is 300 (.claude/rules/docs.md). Split by topic before the PR.`;

// [id, label, file_path (absolute unless noted), expected additionalContext or null, raw stdin override, formatter env]
const P = [
  // The four cases the base spec asks for.
  ['P01', '301-line docs/reference file', absOf('docs/reference/big.md'), CAP_MSG('docs/reference/big.md', 301)],
  ['P02', '41-line docs/HANDOFF.md copy', absOf('docs/HANDOFF.md'),
    'post-edit: docs/HANDOFF.md is 41 lines; cap is 40. Move detail to a finding or docs/TASKS.md.'],
  ['P03', 'findings file without frontmatter', absOf('docs/findings/2026-01-01-nofm.md'),
    'post-edit: docs/findings/2026-01-01-nofm.md lacks finding frontmatter (date, status, area). See the finding skill.'],
  ['P04', 'clean reference file', absOf('docs/reference/clean.md'), null],
  // The base's extra cases.
  ['PX01', 'reference file without verified:', absOf('docs/reference/noverified.md'),
    'post-edit: docs/reference/noverified.md lacks verified: frontmatter. See .claude/rules/docs.md.'],
  ['PX02', 'reference file missing from its index', absOf('docs/reference/unlisted.md'),
    'post-edit: docs/reference/unlisted.md is not listed in reference/README.md. Add one line.'],
  ['PX03', 'finding with full frontmatter', absOf('docs/findings/2026-01-02-ok.md'), null],
  ['PX04', 'ADR listed only in docs/design/README.md', absOf('docs/design/decisions/0009-something.md'), null],
  ['PX05', '320-line proposal (exempt from cap)', absOf('docs/design/proposals/0009-long.md'), null],
  ['PX06', 'relative file_path resolved against cwd', 'docs/reference/big.md', CAP_MSG('docs/reference/big.md', 301)],
  ['PX07', '400-line .md outside docs/', absOf('README.md'), null],
  ['PX08', 'non-doc file, empty formatter table', absOf('src/app.txt'), null],
  ['PX09', 'malformed stdin', '(n/a)', null, 'not json'],
  ['PX10', 'formatter that succeeds', absOf('src/app.txt'), null, null, fmtOk],
  ['PX11', 'formatter that fails', absOf('src/app.txt'),
    `post-edit: formatter ${NODE} failed on src/app.txt: bad formatter output`, null, fmtBad],
  // Dystopo: the record files keep their length (ADR 0020); other top-level docs do not.
  ['PD01', '620-line docs/PLANNING.md (exempt)', absOf('docs/PLANNING.md'), null],
  ['PD02', '580-line docs/TASKS.md (exempt)', absOf('docs/TASKS.md'), null],
  ['PD03', '870-line docs/SERVER.md (exempt)', absOf('docs/SERVER.md'), null],
  ['PD04', '301-line docs/ARCHITECTURE.md (not exempt)', absOf('docs/ARCHITECTURE.md'), CAP_MSG('docs/ARCHITECTURE.md', 301)],
];

for (const [id, label, filePath, expect, rawOverride, fmt] of P) {
  if (!selected(id)) continue;
  const stdin = rawOverride != null ? rawOverride : JSON.stringify({
    hook_event_name: 'PostToolUse', tool_name: 'Write', cwd: docs, tool_input: { file_path: filePath },
  });
  const env = Object.assign({}, process.env);
  delete env.POST_EDIT_FORMATTERS_JSON;
  if (fmt) env.POST_EDIT_FORMATTERS_JSON = JSON.stringify(fmt);
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

// ---------- usage-gate (Dystopo's gate: 75%, ~/.claude/usage.json, DYSTOPO_GATE_OVERRIDE) ----------
const home = path.join(scratch, 'home');
fs.mkdirSync(path.join(home, '.claude'), { recursive: true });
const HOLD_MSG = (f) => `Usage gate: 5h ${f}%, 7d 10%. At or above 75%: no new implementers (CLAUDE.md, Wave size). Finish work already in flight; a verifier for an already-launched task may be relaunched with DYSTOPO_GATE_OVERRIDE=1. Otherwise wait for the reset or the owner's word.`;
const nowSec = Math.floor(Date.now() / 1000);
// [id, label, usage.json contents (object, string, or undefined for no file), expected deny reason or null, extra env]
const U = [
  ['U01', '80% denies', { ts: nowSec, five_hour: { used_percentage: 80 }, seven_day: { used_percentage: 10 } }, HOLD_MSG(80)],
  ['U02', '75% denies', { ts: nowSec, five_hour: { used_percentage: 75 }, seven_day: { used_percentage: 10 } }, HOLD_MSG(75)],
  ['U03', '74% allows', { ts: nowSec, five_hour: { used_percentage: 74 }, seven_day: { used_percentage: 10 } }, null],
  ['U04', 'override allows at 90%', { ts: nowSec, five_hour: { used_percentage: 90 }, seven_day: { used_percentage: 10 } }, null, { DYSTOPO_GATE_OVERRIDE: '1' }],
  ['U05', 'data 3 h old allows', { ts: nowSec - 3 * 3600, five_hour: { used_percentage: 90 }, seven_day: { used_percentage: 10 } }, null],
  ['U06', 'no usage file allows', undefined, null],
  ['U07', 'unparseable file allows', 'not json', null],
];
for (const [id, label, state, expect, extraEnv] of U) {
  if (!selected(id)) continue;
  const file = path.join(home, '.claude', 'usage.json');
  if (state === undefined) fs.rmSync(file, { force: true });
  else fs.writeFileSync(file, typeof state === 'string' ? state : JSON.stringify(state));
  const env = Object.assign({}, process.env, { HOME: home, USERPROFILE: home }, extraEnv || {});
  if (!extraEnv) delete env.DYSTOPO_GATE_OVERRIDE;
  const r = run('usage-gate', JSON.stringify({ hook_event_name: 'PreToolUse', tool_name: 'Agent', tool_input: {} }), env);
  let ok;
  let detail;
  if (expect === null) { ok = r.code === 0 && r.stdout === ''; detail = 'expected allow (exit 0, no stdout)'; }
  else {
    const d = parseDecision(r.stdout);
    ok = r.code === 0 && !!d && d.decision === 'deny' && d.reason === expect;
    detail = `expected reason ${JSON.stringify(expect)}`;
  }
  report(id, `usage gate: ${label} -> ${expect === null ? 'allow' : 'deny'}`, ok, detail, r.stdout);
}

if (opt.keep) process.stdout.write(`scratch tree kept at ${scratch}\n`);
else fs.rmSync(scratch, { recursive: true, force: true });

process.stdout.write(`\n${pass} passed, ${fail} failed, ${pass + fail} total\n`);
process.exit(fail > 0 ? 1 : 0);
