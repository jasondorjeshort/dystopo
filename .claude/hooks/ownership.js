#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// The update helper (ADR 0016; spec: docs/design/decisions/0016-ownership-map.md, The helper, and
// 0016-update-procedure.md). Prints the commands an update from Hatchery runs, built from the
// ownership map (.claude/hooks/ownership.json) as it is at the base commit, never the working
// tree's copy and never a built-in default. The project's update.exclude is read from the working
// tree's policy.json through the working tree's own policy.js. Every path comes from the current
// directory and <sha>, none from this file's location, so a copy extracted to a scratch directory
// (git show <sha>:.claude/hooks/ownership.js > <scratch>/ownership.js) works run from the project root.
//
// Usage, exactly one flag per run:
//   node .claude/hooks/ownership.js --command  --ref <sha> [--exclude <pattern>]...
//       one line: git diff -M --binary <FROM> <sha> -- <pathspecs> | git apply --3way
//   node .claude/hooks/ownership.js --per-path --ref <sha> [--exclude <pattern>]...
//       per changed entry, a "# <status> <path>" line ("# R <old> -> <new>" for a rename) and its
//       own diff | apply line
//   node .claude/hooks/ownership.js --report   --ref <sha> [--exclude <pattern>]...
//       the sections unclassified, excluded, replaced sections, unimported, collisions
//   node .claude/hooks/ownership.js --check
//       every tracked path that matches no list of the working tree's map; Hatchery's tree only
// Run the printed lines in the Bash tool, not PowerShell: PowerShell 5.1 re-encodes the patch in
// the pipe and git apply refuses it whole (a stderr line says so on every --command and --per-path).
// When nothing in the set changed in FROM..sha, --command and --per-path print nothing on stdout,
// "ownership: nothing to apply: <FROM>..<sha> touches no path in the set" on stderr, and exit 0.
// Exit 0 on success (--check: 0 clean, 1 a path unclassified); 1 with one "ownership: ..." line on
// stderr naming the fix (no base line and no merge-base, two base lines, a map malformed or absent
// at <sha>, a policy the tree's policy.js rejects); 2 on a usage error.
// FROM: the first "Hatchery base: <hex>" line under docs/design/decisions/, else
// git merge-base HEAD <sha>. Shas are printed literally and every pathspec single-quoted, so the
// line is shell-inert. Node built-ins only (ADR 0013). Harness: group O in test-hooks.js.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const MAP = '.claude/hooks/ownership.json';
const LISTS = ['baseOwned', 'baseExcluded', 'mixed', 'projectOwned'];
const cwd = process.cwd();
// PowerShell 5.1 re-encodes the patch in the pipe, so git apply refuses every file and the update
// reads as a whole failure; the printed lines are for the Bash tool only. Said on stderr, so stdout
// stays the bare lines.
const BASH_ONLY = 'ownership: run the printed line(s) in the Bash tool, not PowerShell (PowerShell re-encodes the patch in the pipe)';
const USAGE = 'usage: node .claude/hooks/ownership.js (--command | --per-path | --report) --ref <sha> [--exclude <pattern>]... | --check\n' +
  'Run the printed lines in the Bash tool, not PowerShell.';

class Stop extends Error {
  constructor(message, code) { super(message); this.code = code === undefined ? 1 : code; }
}

function git(args) {
  const r = spawnSync('git', ['-c', 'core.quotePath=false', ...args], { cwd, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
  if (r.error) throw new Stop(`ownership: cannot run git (${r.error.message})`);
  return { code: r.status, out: r.stdout || '', err: (r.stderr || '').trim() };
}
function gitOk(args) {
  const r = git(args);
  if (r.code !== 0) throw new Stop(`ownership: git ${args.join(' ')} failed: ${r.err}`);
  return r.out;
}
const zsplit = (s) => s.split('\0').filter((x) => x !== '');

// Validates the map text. Returns the four lists, or throws a reason string.
function parseMap(text) {
  let m;
  try { m = JSON.parse(text.charCodeAt(0) === 0xfeff ? text.slice(1) : text); } catch (e) { throw `not JSON (${e.message})`; }
  if (!m || typeof m !== 'object' || Array.isArray(m)) throw 'not an object';
  for (const k of Object.keys(m)) if (!k.startsWith('$') && !LISTS.includes(k)) throw `unknown key ${k}`;
  const seen = new Map();
  const lists = {};
  for (const name of LISTS) {
    const list = m[name];
    if (!Array.isArray(list)) throw `${name} is not an array`;
    for (const e of list) {
      if (typeof e !== 'string' || e === '') throw `${name} holds a non-string or empty entry`;
      if (/[*?[\\]/.test(e) || e.startsWith('./') || e.startsWith('/') || e.endsWith('/') || e.split('/').some((s) => s === '' || s === '.' || s === '..')) {
        throw `${name} entry ${JSON.stringify(e)} is not a literal repository-relative path`;
      }
      if (seen.has(e) && seen.get(e) !== name) throw `${JSON.stringify(e)} is in both ${seen.get(e)} and ${name}`;
      seen.set(e, name);
    }
    lists[name] = [...new Set(list)];
  }
  return lists;
}

// The class of a repository path: the list holding the longest entry that is the path or a parent
// of it; null when no entry matches (project-owned, and "unclassified" in the report).
function classOf(lists, p) {
  let best = null;
  let len = -1;
  for (const name of LISTS) {
    for (const e of lists[name]) {
      if ((p === e || p.startsWith(`${e}/`)) && e.length > len) { best = name; len = e.length; }
    }
  }
  return best;
}

function parseArgs(argv) {
  const o = { mode: null, ref: null, exclude: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (['--command', '--per-path', '--report', '--check'].includes(a)) {
      if (o.mode) throw new Stop(`ownership: one flag per run (${o.mode} and ${a})\n${USAGE}`, 2);
      o.mode = a;
    } else if (a === '--ref' && argv[i + 1] !== undefined) o.ref = argv[++i];
    else if (a === '--exclude' && argv[i + 1] !== undefined) {
      const p = argv[++i];
      if (p === '' || /[\r\n]/.test(p)) throw new Stop(`ownership: --exclude needs a one-line pattern\n${USAGE}`, 2);
      o.exclude.push(p);
    } else throw new Stop(`ownership: unknown or incomplete argument ${a}\n${USAGE}`, 2);
  }
  if (!o.mode) throw new Stop(USAGE, 2);
  if (o.mode === '--check' && (o.ref || o.exclude.length)) throw new Stop(`ownership: --check takes no --ref or --exclude\n${USAGE}`, 2);
  if (o.mode !== '--check' && !o.ref) throw new Stop(`ownership: ${o.mode} needs --ref <sha>\n${USAGE}`, 2);
  return o;
}

function resolveRef(ref) {
  const r = git(['rev-parse', '--verify', '--quiet', `${ref}^{commit}`]);
  if (r.code !== 0) throw new Stop(`ownership: --ref ${ref} names no commit here; git fetch hatchery first`);
  return r.out.trim();
}

function mapAt(sha) {
  if (gitOk(['ls-tree', '--name-only', sha, '--', MAP]).trim() === '') throw new Stop(`ownership: ${sha} predates the map; update from a newer base`);
  try { return parseMap(gitOk(['show', `${sha}:${MAP}`])); } catch (e) {
    if (e instanceof Stop) throw e;
    throw new Stop(`ownership: ownership.json at ${sha} malformed: ${e}`);
  }
}

// A module of the working tree's .claude/hooks/, else the base's copy at sha in a scratch directory.
let tmpDir = null;
function hookModule(name, sha) {
  const own = path.join(cwd, '.claude', 'hooks', `${name}.js`);
  if (fs.existsSync(own)) return require(own);
  const text = git(['show', `${sha}:.claude/hooks/${name}.js`]);
  if (text.code !== 0) throw new Stop(`ownership: neither the working tree nor ${sha} has .claude/hooks/${name}.js`);
  if (!tmpDir) tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ownership-'));
  const f = path.join(tmpDir, `${name}.js`);
  fs.writeFileSync(f, text.out);
  return require(f);
}

// update.exclude from the working tree's policy, validated by the working tree's policy.js (a tree
// without one has no policy file to honour, so no excludes).
function policyExcludes(sha) {
  const own = path.join(cwd, '.claude', 'hooks', 'policy.js');
  if (!fs.existsSync(own)) return [];
  let r;
  try { r = require(own).load(process.env.HOOK_POLICY_FILE || path.join(cwd, '.claude', 'hooks', 'policy.json')); } catch (e) {
    throw new Stop(`ownership: .claude/hooks/policy.js does not load (${e.message}); fix it before the update`);
  }
  if (r.error) {
    // The guard denies every git command while the policy is malformed, so the key goes first.
    throw new Stop(`ownership: .claude/hooks/policy.json malformed: ${r.error}; a key from a newer base is one cause: remove it with the Edit tool (git is denied while the policy is malformed), run the update, which brings the new policy.js, then put the key back`);
  }
  return (r.policy.update && r.policy.update.exclude) || [];
}

function walk(dir, out) {
  let entries = [];
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (_) { return out; }
  for (const e of entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out); else if (e.isFile()) out.push(p);
  }
  return out;
}

function findFrom(sha) {
  const lines = [];
  for (const f of walk(path.join(cwd, 'docs', 'design', 'decisions'), [])) {
    let text = '';
    try { text = fs.readFileSync(f, 'utf8'); } catch (_) { continue; }
    for (const m of text.matchAll(/^Hatchery base: ([0-9a-f]{7,40})/gm)) {
      lines.push({ hex: m[1], file: path.relative(cwd, f).split(path.sep).join('/') });
    }
  }
  if (lines.length > 0) {
    const first = lines[0];
    const other = lines.find((l) => !l.hex.startsWith(first.hex) && !first.hex.startsWith(l.hex));
    if (other) throw new Stop(`ownership: two base lines, ${first.hex} in ${first.file} and ${other.hex} in ${other.file}; keep one`);
    const r = git(['rev-parse', '--verify', '--quiet', `${first.hex}^{commit}`]);
    if (r.code !== 0) throw new Stop(`ownership: the base line ${first.hex} in ${first.file} names no commit here; git fetch hatchery first`);
    return r.out.trim();
  }
  const r = git(['merge-base', 'HEAD', sha]);
  if (r.code !== 0 || r.out.trim() === '') {
    throw new Stop('ownership: no base line and no merge-base; this is not a Hatchery project; see the adoption prompt in README.md');
  }
  return r.out.trim();
}

const quote = (s) => `'${s.replace(/'/g, `'\\''`)}'`;
// A policy pattern as a git pathspec: * stays the wildcard (a plain pathspec's * crosses "/", as the
// policy matcher's does); ? and [ are literal in the matcher, so each becomes a one-character class
// ([?], [[]). Not a backslash: Git for Windows reads \ in a pathspec as a directory separator, so
// x\[1].md would match nothing there (tested 2026-10-17, git 2.56.0.windows.2).
const patternSpec = (p) => p.replace(/[?[]/g, '[$&]');
// A path git listed, as a pathspec matching only itself.
const literalSpec = (p) => p.replace(/[*?[]/g, '[$&]');

// The pathspecs: base-owned and mixed entries, then excludes: base-excluded entries, project-owned
// entries lying under an included entry (".claude/project" under ".claude": the class rule), and
// every update.exclude and --exclude pattern. Map entries are literal paths without wildcards
// (parseMap), so they need no escaping.
function pathspecs(lists, patterns) {
  const include = [...lists.baseOwned, ...lists.mixed];
  const under = (e) => include.some((i) => e.startsWith(`${i}/`));
  const exclude = [...lists.baseExcluded, ...lists.projectOwned.filter(under), ...patterns.map(patternSpec)];
  return { include, all: [...include, ...[...new Set(exclude)].map((p) => `:(exclude)${p}`)] };
}

// [{ status, paths }] of git diff --name-status -M between from and sha, limited to specs.
function nameStatus(from, sha, specs) {
  const t = zsplit(gitOk(['diff', '--name-status', '-z', '-M', from, sha, '--', ...specs]));
  const out = [];
  for (let i = 0; i < t.length;) {
    const status = t[i++];
    const n = /^[RC]/.test(status) ? 2 : 1;
    out.push({ status, paths: t.slice(i, i + n) });
    i += n;
  }
  return out;
}

function blobs(ref, paths) {
  const m = new Map();
  if (paths.length === 0) return m;
  for (const row of zsplit(gitOk(['ls-tree', '-z', ref, '--', ...paths]))) {
    const tab = row.indexOf('\t');
    m.set(row.slice(tab + 1), row.slice(0, tab).split(' ')[2]);
  }
  return m;
}

function section(heading, items) {
  return items.length === 0 ? `${heading}: none\n` : `${heading}:\n${items.map((i) => `  ${i}\n`).join('')}`;
}

function report(lists, from, sha, specs) {
  const all = nameStatus(from, sha, []);
  const changed = [...new Set(all.flatMap((e) => e.paths))].sort();
  const unclassified = changed.filter((p) => classOf(lists, p) === null);
  const kept = new Set(nameStatus(from, sha, specs.all).flatMap((e) => e.paths));
  const excluded = [...new Set(nameStatus(from, sha, specs.include).flatMap((e) => e.paths))]
    .filter((p) => !kept.has(p) && classOf(lists, p) !== 'projectOwned').sort();

  const { resolve } = hookModule('imports', sha);
  const targets = fs.existsSync(path.join(cwd, 'CLAUDE.md')) ? resolve(cwd, 'CLAUDE.md').map((i) => i.target) : [];
  const sectionOf = (dir, t) => (t.startsWith(`${dir}/`) && t.endsWith('.md') && !t.slice(dir.length + 1).includes('/') ? t.slice(dir.length + 1, -3) : null);
  const replaced = [...new Set(targets.map((t) => sectionOf('.claude/project', t)).filter((s) => s !== null))].sort()
    .map((s) => (changed.includes(`.claude/agreement/${s}.md`) ? `${s}: .claude/agreement/${s}.md changed in ${from.slice(0, 7)}..${sha.slice(0, 7)}` : `${s}: unchanged`));
  const shipped = zsplit(gitOk(['ls-tree', '-z', '--name-only', sha, '--', '.claude/agreement/']))
    .map((t) => sectionOf('.claude/agreement', t)).filter((s) => s !== null);
  const unimported = shipped.filter((s) => !targets.includes(`.claude/agreement/${s}.md`) && !targets.includes(`.claude/project/${s}.md`))
    .map((s) => `.claude/agreement/${s}.md`);
  const added = nameStatus(from, sha, specs.all).filter((e) => /^[AR]/.test(e.status)).map((e) => e.paths[e.paths.length - 1]);
  const ours = blobs('HEAD', added);
  const theirs = blobs(sha, added);
  const collisions = added.filter((p) => ours.has(p) && ours.get(p) !== theirs.get(p)).sort();

  return section('unclassified', unclassified) + section('excluded', excluded) + section('replaced sections', replaced) +
    section('unimported', unimported) + section('collisions', collisions);
}

function check() {
  let text;
  try { text = fs.readFileSync(path.join(cwd, ...MAP.split('/')), 'utf8'); } catch (e) {
    throw new Stop(`ownership: cannot read ${MAP} in the working tree (${e.code || e.message})`);
  }
  let lists;
  try { lists = parseMap(text); } catch (e) { throw new Stop(`ownership: ${MAP} in the working tree malformed: ${e}`); }
  const loose = zsplit(gitOk(['ls-files', '-z'])).filter((p) => classOf(lists, p) === null);
  return { out: loose.map((p) => `${p}\n`).join(''), code: loose.length ? 1 : 0 };
}

function main(argv) {
  const o = parseArgs(argv);
  if (o.mode === '--check') return check();
  const sha = resolveRef(o.ref);
  const lists = mapAt(sha);
  const specs = pathspecs(lists, [...policyExcludes(sha), ...o.exclude]);
  const from = findFrom(sha);
  if (o.mode === '--command' || o.mode === '--per-path') {
    // Nothing in the set changed: an empty patch would make git apply exit 128, so say so instead.
    const entries = nameStatus(from, sha, specs.all);
    if (entries.length === 0) return { out: '', err: `ownership: nothing to apply: ${from}..${sha} touches no path in the set\n`, code: 0 };
    // --binary: the set may hold binary files (a skill's asset), which a plain diff cannot carry.
    const out = o.mode === '--command'
      ? `git diff -M --binary ${from} ${sha} -- ${specs.all.map(quote).join(' ')} | git apply --3way\n`
      : entries.map((e) => `${e.paths.length === 2 ? `# ${e.status[0]} ${e.paths[0]} -> ${e.paths[1]}` : `# ${e.status} ${e.paths[0]}`}\n` +
        `git diff -M --binary ${from} ${sha} -- ${e.paths.map((p) => quote(literalSpec(p))).join(' ')} | git apply --3way\n`).join('');
    return { out, err: `${BASH_ONLY}\n`, code: 0 };
  }
  return { out: report(lists, from, sha, specs), code: 0 };
}

module.exports = { parseMap, classOf, pathspecs, quote, patternSpec, literalSpec };

if (require.main === module) {
  let r;
  try { r = main(process.argv.slice(2)); } catch (e) {
    if (!(e instanceof Stop)) throw e;
    r = { out: '', err: `${e.message}\n`, code: e.code };
  }
  if (tmpDir) fs.rmSync(tmpDir, { recursive: true, force: true });
  if (r.out) process.stdout.write(r.out);
  if (r.err) process.stderr.write(r.err);
  process.exitCode = r.code; // not process.exit: a write to a pipe may still be in flight
}
