#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// PostToolUse hook for the Write and Edit tools. Non-blocking: it never exits non-zero
// and never denies. It returns hookSpecificOutput.additionalContext when an edited
// docs/**/*.md file breaks a size cap, lacks required frontmatter, or is missing from
// its directory index, and it runs the formatter for the file's extension if one is set.
// From the Hatchery base (spec: its ADR 0002 companion, section 2). Dystopo difference, recorded
// in docs/design/decisions/0020-hatchery-alignment.md: the dated record files in CAP_EXEMPT keep
// their length (the task board, the planning log, the server manual).
// Test harness: node .claude/hooks/test-hooks.js
'use strict';
const fs = require('fs');
const path = require('path');
const childProcess = require('child_process');

// Formatter slot, keyed by file extension (lower case, with the dot). The value is an
// argv array; the edited file's absolute path is appended at run time. Empty in the
// Hatchery base: a decanted project fills it for its languages, for example
//   '.rs': ['rustfmt', '--edition', '2021'],
// A non-zero exit or a 20 s timeout is reported back to the agent, never blocks.
const FORMATTERS = {};

// Test seam: a JSON object in this variable is merged over FORMATTERS, so the harness
// can exercise the formatter path without editing this file. Unset in normal use.
if (process.env.POST_EDIT_FORMATTERS_JSON) {
  try { Object.assign(FORMATTERS, JSON.parse(process.env.POST_EDIT_FORMATTERS_JSON)); } catch (_) { /* ignore */ }
}

const DOCS_CAP = 300;
const HANDOFF_CAP = 40;
const INDEX_EXEMPT = new Set(['README.md', 'HANDOFF.md', 'backlog.md']);
// Files exempt from the 300-line cap, by repo-relative path (ADR 0020).
const CAP_EXEMPT = new Set(['docs/PLANNING.md', 'docs/TASKS.md', 'docs/SERVER.md']);

const lineCount = (text) => (text.match(/\n/g) || []).length; // same as wc -l

// True when the file starts with ---, closes with --- within the first 20 lines,
// and every key appears at line start between the two markers.
function hasFrontmatter(text, keys) {
  const lines = text.split(/\r?\n/);
  if (lines[0] !== '---') return false;
  let end = -1;
  for (let i = 1; i < Math.min(lines.length, 20); i++) {
    if (lines[i] === '---') { end = i; break; }
  }
  if (end < 0) return false;
  const body = lines.slice(1, end);
  return keys.every((k) => body.some((l) => l.startsWith(`${k}:`)));
}

function readOr(file, fallback) {
  try { return fs.readFileSync(file, 'utf8'); } catch (_) { return fallback; }
}

function checkDocs(root, rel, abs) {
  const msgs = [];
  const text = fs.readFileSync(abs, 'utf8');
  const n = lineCount(text);
  const parts = rel.split('/');
  const base = parts[parts.length - 1];

  if (n > DOCS_CAP && !rel.startsWith('docs/design/proposals/') && !CAP_EXEMPT.has(rel)) {
    msgs.push(`post-edit: ${rel} is ${n} lines; cap is ${DOCS_CAP} (.claude/rules/docs.md). Split by topic before the PR.`);
  }
  if (rel === 'docs/HANDOFF.md' && n > HANDOFF_CAP) {
    msgs.push(`post-edit: docs/HANDOFF.md is ${n} lines; cap is ${HANDOFF_CAP}. Move detail to a finding or docs/TASKS.md.`);
  }
  if (parts.length === 3 && parts[1] === 'findings' && base !== 'README.md' &&
      !hasFrontmatter(text, ['date', 'status', 'area'])) {
    msgs.push(`post-edit: ${rel} lacks finding frontmatter (date, status, area). See the finding skill.`);
  }
  if (parts.length === 3 && parts[1] === 'reference' && base !== 'README.md' &&
      !hasFrontmatter(text, ['verified'])) {
    msgs.push(`post-edit: ${rel} lacks verified: frontmatter. See .claude/rules/docs.md.`);
  }
  // Index check: a file under docs/<dir>/... must be named in a README.md, looking in its
  // own directory first and then in each parent up to docs/<dir>/ (ADRs, for example,
  // are listed in docs/design/README.md).
  if (parts.length >= 3 && !INDEX_EXEMPT.has(base)) {
    let listed = false;
    for (let depth = parts.length - 1; depth >= 2 && !listed; depth--) {
      const readme = path.join(root, ...parts.slice(0, depth), 'README.md');
      listed = readOr(readme, '').includes(base);
    }
    if (!listed) {
      const dir = parts.slice(1, -1).join('/');
      msgs.push(`post-edit: ${rel} is not listed in ${dir}/README.md. Add one line.`);
    }
  }
  return msgs;
}

function runFormatter(argv, abs, rel) {
  const r = childProcess.spawnSync(argv[0], [...argv.slice(1), abs], { timeout: 20000, encoding: 'utf8' });
  if (!r.error && r.status === 0) return [];
  let detail = (r.stderr || '').split(/\r?\n/).filter((l) => l !== '').slice(0, 5).join('\n');
  if (!detail && r.error) detail = r.error.message;
  if (!detail && r.signal) detail = `killed by ${r.signal} (timeout 20 s?)`;
  return [`post-edit: formatter ${argv[0]} failed on ${rel}: ${detail}`];
}

function main(raw) {
  const input = JSON.parse(raw);
  const filePath = input && input.tool_input && input.tool_input.file_path;
  if (typeof filePath !== 'string' || filePath === '') return;
  const root = typeof input.cwd === 'string' && input.cwd ? input.cwd : process.cwd();
  const abs = path.resolve(root, filePath);
  const relRaw = path.relative(root, abs);
  const inside = relRaw && !relRaw.startsWith('..') && !path.isAbsolute(relRaw);
  const rel = inside ? relRaw.split(path.sep).join('/') : abs;

  const msgs = [];
  if (inside && rel.startsWith('docs/') && rel.endsWith('.md')) msgs.push(...checkDocs(root, rel, abs));
  const fmt = FORMATTERS[path.extname(abs).toLowerCase()];
  if (Array.isArray(fmt) && fmt.length > 0) msgs.push(...runFormatter(fmt, abs, rel));

  if (msgs.length > 0) {
    process.stdout.write(JSON.stringify({
      hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: msgs.join('\n') },
    }));
  }
}

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (c) => (raw += c));
process.stdin.on('end', () => {
  try { main(raw); } catch (_) { /* non-blocking by design: swallow everything */ }
  process.exit(0);
});
