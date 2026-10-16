#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// PostToolUse hook for the Write and Edit tools. Non-blocking: it never exits non-zero
// and never denies. It returns hookSpecificOutput.additionalContext when CLAUDE.md passes
// its 150-line cap or an edited docs/**/*.md file breaks a size cap, lacks required frontmatter, is missing from its
// directory index, or names a repository path that does not exist, and it runs the
// formatter for the file's extension if one is set.
// Spec: docs/design/decisions/0002-hook-specs.md section 2; policy: ADR 0006.
// Test harness: node .claude/hooks/test-hooks.js
'use strict';
const fs = require('fs');
const path = require('path');
const childProcess = require('child_process');
const { load, matches } = require('./policy');

// Project choices from .claude/hooks/policy.json (edit block); a malformed policy means the base
// defaults here, since this hook only advises.
const { edit } = load().policy;
// Formatters, keyed by file extension (lower case, with the dot). The value is an argv array;
// the edited file's absolute path is appended at run time. Empty in the base; a project sets
// edit.formatters, for example ".rs": ["rustfmt", "--edition", "2021"]. A non-zero exit or a
// 20 s timeout is reported back to the agent, never blocks.
const FORMATTERS = Object.assign({}, edit.formatters);
// Test seam: a JSON object in this variable is merged over FORMATTERS, so the harness
// can exercise the formatter path without a policy file. Unset in normal use.
if (process.env.POST_EDIT_FORMATTERS_JSON) {
  try { Object.assign(FORMATTERS, JSON.parse(process.env.POST_EDIT_FORMATTERS_JSON)); } catch (_) { /* ignore */ }
}

const DOCS_CAP = 300;
const HANDOFF_CAP = 40;
const CLAUDE_MD_CAP = 150; // .claude/rules/docs.md; loaded every turn, so the tightest cap
const INDEX_EXEMPT = new Set(['README.md', 'HANDOFF.md']);

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

  if (n > DOCS_CAP && !rel.startsWith('docs/design/proposals/') && !matches(edit.capExempt, rel)) {
    msgs.push(`post-edit: ${rel} is ${n} lines; cap is ${DOCS_CAP} (.claude/rules/docs.md). Split by topic; the pusher will refuse to push.`);
  }
  if (rel === 'docs/HANDOFF.md' && n > HANDOFF_CAP) {
    msgs.push(`post-edit: docs/HANDOFF.md is ${n} lines; cap is ${HANDOFF_CAP}. Move detail to docs/ideas/ or a finding.`);
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
  // Closed records (proposals, decisions, findings by default) name paths as they were.
  const missing = matches(edit.anchorExempt, rel) ? [] : missingPaths(root, text);
  if (missing.length > 0) {
    const list = missing.map((m) => `\`${m}\``).join(', ');
    msgs.push(`post-edit: ${rel} names paths that do not exist: ${list}. Fix them, or mark the paragraph [no-check: <reason>] (.claude/rules/docs.md).`);
  }
  return msgs;
}

// Path anchors: a backtick-quoted token that looks like a repository path (it contains "/" and
// starts with an existing top-level directory such as docs or .claude) must exist, unless its
// paragraph, or its list item, carries [no-check: ...]. Fenced blocks (indented ones too) and
// placeholders (NNNN, <slug>, *) are skipped.
function missingPaths(root, text) {
  let tops;
  try {
    tops = new Set(fs.readdirSync(root, { withFileTypes: true })
      .filter((e) => e.isDirectory() && e.name !== '.git').map((e) => e.name));
  } catch (_) { return []; }
  const prose = text.replace(/\r/g, '').replace(/^[ \t]*(```|~~~)[^\n]*\n[\s\S]*?^[ \t]*\1[^\n]*$/gm, '');
  const units = prose.split(/\n[ \t]*\n/).flatMap((p) => p.split(/\n(?=[ \t]*(?:[-*+]|\d+[.)])[ \t])/));
  const missing = [];
  for (const para of units) {
    if (para.includes('[no-check:')) continue;
    for (const m of para.matchAll(/`([^`\s]+)`/g)) {
      const p = m[1].replace(/[#:].*$/, '');
      if (!p.includes('/') || /[*<>{}$]|NNNN|YYYY/.test(p) || !tops.has(p.split('/')[0])) continue;
      if (!missing.includes(p) && !fs.existsSync(path.join(root, ...p.split('/').filter(Boolean)))) missing.push(p);
    }
  }
  return missing;
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
  if (inside && rel === 'CLAUDE.md') {
    const n = lineCount(fs.readFileSync(abs, 'utf8'));
    if (n > CLAUDE_MD_CAP) msgs.push(`post-edit: CLAUDE.md is ${n} lines; cap is ${CLAUDE_MD_CAP} (.claude/rules/docs.md). Move facts to docs/reference/ and path-scoped rules to .claude/rules/.`);
  }
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
