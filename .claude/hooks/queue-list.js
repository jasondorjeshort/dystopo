#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// Lists a queue directory (docs/ideas/, docs/operator/, docs/findings/) from its files, so the
// READMEs keep no per-file index (ADR 0017). Contract: docs/hatchery/decisions/0017-queue-list.md,
// sections 1 to 3; the parts ADR 0013 promises are the path, the mode words, --status,
// --untriaged, --json, --check, the --json fields and the exit codes.
//
//   node .claude/hooks/queue-list.js <ideas|operator|findings> [--status <v>] [--untriaged] [--json | --markdown] [--check]
//
// Run from the repository root (the working directory is the root). One row per *.md directly in
// docs/<mode>/ except README.md, in byte order of the name. A value outside the grammar prints as
// ? with one stderr line naming the field and the allowed set. Exit 0 with the listing; 1 when
// --check is given and any file read has a problem; 2 on a usage error or an absent directory.
//
// read(mode, absFile) is the one reading of a file, required by post-edit.js (never spawned), so
// the listing and the edit-time warning never disagree. MARKER is the README line that switches
// post-edit's index check off for that directory (section 4).
// Node built-ins only (ADR 0013).
'use strict';
const fs = require('fs');
const path = require('path');

const MODES = ['ideas', 'operator', 'findings'];
const MARKER = /^Listed on demand: node \.claude\/hooks\/queue-list\.js (ideas|operator|findings)[ \t]*$/m;
const USAGE = 'node .claude/hooks/queue-list.js <ideas|operator|findings> [--status <v>] [--untriaged] [--json | --markdown] [--check]';

const ALLOWED = {
  status: 'open, resolved, superseded',
  kind: 'bug, feature, question, chore',
  title: 'a # heading or a first line',
  frontmatter: 'date, kind, related',
};
const STATUSES = new Set(['open', 'resolved', 'superseded']);
const KINDS = new Set(['bug', 'feature', 'question', 'chore']);
const IDEA_KEYS = new Set(['date', 'kind', 'related']);
// Columns between file and title, in --json field order.
const COLUMNS = { findings: ['status'], ideas: ['kind', 'block'], operator: [] };
const FRONTMATTER_LINES = 20; // the closing --- within the first 20 lines, as post-edit's hasFrontmatter

// Frontmatter: a first line --- and the next --- within the first 20 lines. A key is the text
// before the first : of a line between them (trimmed), its value the rest, trimmed; the first
// occurrence of a key wins. body is every line outside the block.
function split(text) {
  const lines = text.split(/\r?\n/);
  const keys = [];
  if (lines[0] === '---') {
    for (let i = 1; i < Math.min(lines.length, FRONTMATTER_LINES); i++) {
      if (lines[i] !== '---') continue;
      for (const l of lines.slice(1, i)) {
        const c = l.indexOf(':');
        if (c < 0) continue;
        const key = l.slice(0, c).trim();
        if (key && !keys.some(([k]) => k === key)) keys.push([key, l.slice(c + 1).trim()]);
      }
      return { keys, body: lines.slice(i + 1) };
    }
  }
  return { keys, body: lines };
}
const valueOf = (keys, key) => { const e = keys.find(([k]) => k === key); return e ? e[1] : null; };

// The first line starting "# " (its text), else the first non-blank line (whole), else null.
function titleOf(body) {
  const h = body.find((l) => l.startsWith('# '));
  if (h !== undefined) return h.slice(2).trim();
  const first = body.find((l) => l.trim() !== '');
  return first === undefined ? null : first.trim();
}

// The triage block, read as the triage skill reads it: the last line outside a fenced block that
// starts Plan:, Question: or Waits: opens it, and it runs to the end of the file.
function blockOf(body) {
  let fence = null;
  let opener = -1;
  for (let i = 0; i < body.length; i++) {
    const l = body[i];
    const f = /^[ \t]{0,3}(`{3,}|~{3,})(.*)$/.exec(l);
    if (fence) {
      if (f && f[1][0] === fence[0] && f[1].length >= fence.length && f[2].trim() === '') fence = null;
      continue;
    }
    if (f && !(f[1][0] === '`' && f[2].includes('`'))) { fence = f[1]; continue; }
    if (/^(Plan|Question|Waits):/.test(l)) opener = i;
  }
  if (opener < 0) return 'untriaged';
  if (body[opener].startsWith('Plan:')) return 'plan';
  if (body[opener].startsWith('Waits:')) return 'waits';
  for (let j = opener + 1; j < body.length; j++) {
    if (!body[j].startsWith('Answer:')) continue;
    if (body[j].slice('Answer:'.length).trim() !== '') return 'answered';
    return body.slice(j + 1).some((l) => l.trim() !== '') ? 'answered' : 'question';
  }
  return 'question'; // no Answer: line at all is a blank answer
}

// read(mode, absFile) -> { file, title, ...columns, problems }. problems: [{ field, value, allowed }],
// value null when the field is missing. A problem puts ? in its field. findings rows also carry
// statusText, the whole status: value, for the --status whole-value match.
function read(mode, absFile) {
  const text = fs.readFileSync(absFile, 'utf8');
  const { keys, body } = split(text);
  const problems = [];
  const bad = (field, value) => { problems.push({ field, value, allowed: ALLOWED[field] }); return '?'; };
  const row = { file: path.basename(absFile) };
  const title = titleOf(body);
  row.title = title === null ? bad('title', null) : title;
  if (mode === 'findings') {
    const v = valueOf(keys, 'status');
    row.statusText = v;
    row.status = v !== null && STATUSES.has(v.split(/\s+/)[0]) ? v.split(/\s+/)[0] : bad('status', v);
  } else if (mode === 'ideas') {
    const k = valueOf(keys, 'kind');
    row.kind = k !== null && KINDS.has(k) ? k : bad('kind', k);
    const foreign = keys.find(([key]) => !IDEA_KEYS.has(key));
    row.block = foreign ? bad('frontmatter', foreign[0]) : blockOf(body);
  }
  row.problems = problems;
  return row;
}

function usage(reason) {
  process.stderr.write(`queue-list: usage: ${reason ? `${reason}; ` : ''}${USAGE}\n`);
  process.exit(2);
}

function parseArgs(argv) {
  const o = { mode: null, status: null, untriaged: false, json: false, markdown: false, check: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--status') {
      if (i + 1 >= argv.length || argv[i + 1].startsWith('--')) usage('--status needs a value');
      o.status = argv[++i];
    } else if (a === '--untriaged') o.untriaged = true;
    else if (a === '--json') o.json = true;
    else if (a === '--markdown') o.markdown = true;
    else if (a === '--check') o.check = true;
    else if (a.startsWith('-')) usage(`unknown flag ${a}`);
    else if (o.mode !== null) usage(`unexpected argument ${a}`);
    else if (!MODES.includes(a)) usage(`unknown mode ${a}`);
    else o.mode = a;
  }
  if (o.mode === null) usage('no mode');
  if (o.status !== null && o.mode !== 'findings') usage('--status applies to findings only');
  if (o.untriaged && o.mode !== 'ideas') usage('--untriaged applies to ideas only');
  if (o.json && o.markdown) usage('--json and --markdown are exclusive');
  return o;
}

function main(argv) {
  const o = parseArgs(argv);
  const dir = path.join(process.cwd(), 'docs', o.mode);
  let names;
  try {
    if (!fs.statSync(dir).isDirectory()) throw new Error('not a directory');
    names = fs.readdirSync(dir);
  } catch (_) {
    process.stderr.write(`queue-list: docs/${o.mode}/ does not exist\n`);
    process.exit(2);
  }
  const files = names
    .filter((n) => n.endsWith('.md') && n !== 'README.md')
    .filter((n) => { try { return fs.statSync(path.join(dir, n)).isFile(); } catch (_) { return false; } })
    .sort((a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b)));

  const rows = [];
  let unreadable = 0;
  for (const n of files) {
    try { rows.push(read(o.mode, path.join(dir, n))); } catch (e) {
      unreadable++;
      process.stderr.write(`queue-list: ${n}: unreadable (${e.code || e.message})
`);
    }
  }
  let problemCount = 0;
  for (const r of rows) {
    for (const p of r.problems) {
      problemCount++;
      process.stderr.write(p.value === null
        ? `queue-list: ${r.file}: ${p.field} missing; allowed: ${p.allowed}\n`
        : `queue-list: ${r.file}: ${p.field} is "${p.value}"; allowed: ${p.allowed}\n`);
    }
  }

  let shown = rows;
  if (o.status !== null) shown = shown.filter((r) => r.status === o.status || r.statusText === o.status);
  if (o.untriaged) shown = shown.filter((r) => r.block === 'untriaged' && r.problems.length === 0);

  const fields = ['file', ...COLUMNS[o.mode], 'title'];
  let out;
  if (o.json) {
    out = `${JSON.stringify(shown.map((r) => Object.fromEntries(fields.map((f) => [f, r[f]]))))}\n`;
  } else if (o.markdown) {
    const cell = (s) => String(s).replace(/\|/g, '\\|');
    const head = fields.map((f) => f[0].toUpperCase() + f.slice(1));
    out = `| ${head.join(' | ')} |\n|${fields.map(() => '---').join('|')}|\n` +
      shown.map((r) => `| \`${r.file}\` | ${[...COLUMNS[o.mode], 'title'].map((f) => cell(r[f])).join(' | ')} |\n`).join('');
  } else {
    out = shown.map((r) => `${fields.map((f) => r[f]).join('\t')}\n`).join('');
  }
  process.stdout.write(out);
  process.exitCode = unreadable > 0 ? 2 : o.check && problemCount > 0 ? 1 : 0;
}

module.exports = { read, MODES, MARKER };

if (require.main === module) main(process.argv.slice(2));
