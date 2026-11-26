#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// PostToolUse hook for the Write and Edit tools. Non-blocking: it never exits non-zero
// and never denies. It returns hookSpecificOutput.additionalContext when an edit to CLAUDE.md,
// .claude/agreement/*.md or .claude/project/*.md leaves a --claude-check FAIL (claudeCheck in
// policy.js: sections, the 40 and 190 caps, unresolved imports), or an edited docs/**/*.md file
// breaks a size cap or passes the 300 threshold without its [length:] marker, lacks required
// frontmatter, is missing from its directory index (or, in a queue directory listed on demand,
// would list as ? in queue-list.js, or, an idea, has an entry line out of place, ADR 0026), or
// names a repository path that does not exist, or an edited
// .claude/skills/*/SKILL.md passes 500 lines, and it runs the formatter for the file's extension
// if one is set. Every @
// import is read by imports.js, the one parser; every queue file by queue-list.js's read().
// Spec: docs/hatchery/decisions/0002-hook-specs.md section 2; policy: ADR 0006; CLAUDE.md: ADR 0016;
// queue directories: ADR 0017.
// Test harness: node .claude/hooks/test-hooks.js
'use strict';
const fs = require('fs');
const path = require('path');
const childProcess = require('child_process');
const { load, matches, docCap, lineCount, overLimit, claudeCheck, DOCS_CAP, HANDOFF_CAP } = require('./policy');
// The parser is loaded lazily and guarded, as policy.js loads it: a missing or broken imports.js
// drops the import checks, never the hook's other messages, and never makes it exit non-zero.
function resolve(root, file, opts) {
  try { return require('./imports').resolve(root, file, opts); } catch (_) { return []; }
}
function claudeLines(root) {
  try { return claudeCheck(root, edit).lines; } catch (_) { return []; }
}
// The queue listing (ADR 0017) is loaded the same way, and only when a queue README carries a
// Listed on demand: line: a missing or broken queue-list.js costs that check one line, never the
// hook's other messages. The hook requires it and never spawns it.
function queueList() {
  try { return require('./queue-list'); } catch (_) { return null; }
}
// The marker line (0017-queue-list.md, section 4). queue-list.js exports it as MARKER and the hook
// uses that; this copy only tells a marked directory apart when the script does not load.
const QUEUE_MARKER = /^Listed on demand: node \.claude\/hooks\/queue-list\.js (ideas|operator|findings)[ \t]*$/m;
const QUEUE_PREFIX = 'Listed on demand:';
const QUEUE_DIRS = ['ideas', 'operator', 'findings']; // the only directories that may carry the marker
const queueMarker = (ql) => (ql && ql.MARKER instanceof RegExp ? ql.MARKER : QUEUE_MARKER);
// True when the line is the marker for docs/<dir>/: the whole line, the captured word <dir>.
function isQueueMarker(line, dir, marker) {
  const m = marker.exec(line);
  return !!m && m[1] === dir;
}

// Project choices from .claude/hooks/policy.json (edit block); a malformed policy means the base
// defaults here, since this hook only advises.
const { edit } = load().policy;
// Formatters, keyed by file extension (lower case, with the dot). The value is an argv array;
// the edited file's absolute path is appended at run time. Empty in the base; a project sets
// edit.formatters, for example ".rs": ["rustfmt", "--edition", "2021"]. A non-zero exit or a
// 20 s timeout is reported back to the agent, never blocks. On Windows a command that names or
// resolves to a .cmd or .bat file (an npm shim such as ["prettier", "--write"]) runs through
// cmd.exe, every argument quoted; an .exe and every other platform spawn directly (batchFile).
// An argument holding " or % or a line break is not run through cmd.exe (cmdLine), and a timeout
// there ends cmd.exe, not what the batch file started.
const FORMATTERS = Object.assign({}, edit.formatters);
// Test seam: a JSON object in this variable is merged over FORMATTERS, so the harness
// can exercise the formatter path without a policy file. Unset in normal use.
if (process.env.POST_EDIT_FORMATTERS_JSON) {
  try { Object.assign(FORMATTERS, JSON.parse(process.env.POST_EDIT_FORMATTERS_JSON)); } catch (_) { /* ignore */ }
}

// The per-file limits (DOCS_CAP, the 300 threshold a [length: <reason>] marker passes; HANDOFF_CAP
// 40) and which applies are docCap and overLimit in policy.js, the one rule this hook and pusher
// check 7 share.
const HANDOFF_TOTAL_CAP = 120; // docs/HANDOFF.md plus every docs/handoff/*.md
// .claude/skills/<name>/SKILL.md over this many lines (the whole file, as wc -l) is warned, never
// failed, and has no marker (ADR 0023, item 5); a path matching edit.capExempt is not warned. The
// docs rule does not load for skills, so the message carries its own guidance.
const SKILL_THRESHOLD = 500;
// The CLAUDE.md caps (40, and 190 loaded) are CLAUDE_MD_CAP and CLAUDE_LOADED_CAP in policy.js.
const INDEX_EXEMPT = new Set(['README.md', 'HANDOFF.md']);

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

  const cap = docCap(edit, rel);
  if (cap === DOCS_CAP && overLimit(cap, n, text)) {
    msgs.push(`post-edit: ${rel} is ${n} lines, over the ${cap}-line threshold without [length: <reason>] (ADR 0023, .claude/rules/docs.md). Ask what the doc is made of: cut an inventory or a settled deliberation, move rationale or a phase only some runs use one level deep; if it is one procedure read start to finish, or its length is the exact commands and checks a pass needs, put [length: <reason>] in its first 10 lines. Without it the pusher will refuse to push.`);
  }
  if (cap === HANDOFF_CAP && n > cap) {
    msgs.push(`post-edit: ${rel} is ${n} lines; cap is ${cap}. Move detail to docs/ideas/ or a finding.`);
  }
  const isStream = parts.length === 3 && parts[1] === 'handoff';
  const isHandoff = rel === 'docs/HANDOFF.md' || isStream;
  if (isHandoff) {
    let total = n;
    const others = [];
    if (rel !== 'docs/HANDOFF.md') others.push(path.join(root, 'docs', 'HANDOFF.md'));
    try {
      for (const f of fs.readdirSync(path.join(root, 'docs', 'handoff'))) {
        if (f.endsWith('.md') && !(isStream && f === base)) others.push(path.join(root, 'docs', 'handoff', f));
      }
    } catch (_) { /* no split directory */ }
    for (const f of others) total += lineCount(readOr(f, ''));
    if (total > HANDOFF_TOTAL_CAP) {
      msgs.push(`post-edit: the handoff loads ${total} lines in total (docs/HANDOFF.md plus docs/handoff/*.md); cap is ${HANDOFF_TOTAL_CAP}. Close a stream or trim.`);
    }
  }
  if (parts.length === 3 && parts[1] === 'findings' && base !== 'README.md' &&
      !hasFrontmatter(text, ['date', 'status', 'area'])) {
    msgs.push(`post-edit: ${rel} lacks finding frontmatter (date, status, area). See the finding skill.`);
  }
  if (/^docs\/(hatchery\/)?reference\/[^/]+\.md$/.test(rel) && base !== 'README.md' &&
      !hasFrontmatter(text, ['verified'])) {
    msgs.push(`post-edit: ${rel} lacks verified: frontmatter. See .claude/rules/docs.md.`);
  }
  // Index check: a file under docs/<dir>/... must be named in a README.md, looking in its
  // own directory first and then in each parent up to docs/<dir>/ (ADRs, for example,
  // are listed in docs/design/README.md). A file directly in a queue directory whose README
  // carries the marker is listed on demand instead (ADR 0017): no index check, the parser warning.
  const queue = !isStream && parts.length === 3 && !INDEX_EXEMPT.has(base) ? queueLines(root, rel, abs, parts[1]) : null;
  if (isStream) {
    // docs/handoff/ has no README: a stream is listed when the map imports it.
    const map = resolve(root, 'docs/HANDOFF.md', { hops: 1 }).map((i) => i.target);
    if (!map.includes(rel)) {
      msgs.push(`post-edit: ${rel} is not imported by docs/HANDOFF.md. Add the line @handoff/${base} or delete the file.`);
    }
  } else if (parts.length === 3 && base === 'README.md') {
    if (QUEUE_DIRS.includes(parts[1])) msgs.push(...queueReadmeLines(rel, parts[1], text));
  } else if (queue) {
    msgs.push(...queue);
  } else if (parts.length >= 3 && !INDEX_EXEMPT.has(base)) {
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

// docs/<dir>/<file>.md under a README carrying the marker for <dir>: the problems queue-list.js
// reads in the file, one line each (0017-queue-list.md, section 4, item 1). null when the README
// has no valid marker, so the caller runs the index check exactly as before.
function queueLines(root, rel, abs, dir) {
  const readme = readOr(path.join(root, 'docs', dir, 'README.md'), '').replace(/\r/g, '');
  if (!readme.includes(QUEUE_PREFIX)) return null;
  const ql = queueList();
  const marker = queueMarker(ql);
  if (!readme.split('\n').some((l) => isQueueMarker(l, dir, marker))) return null;
  const notChecked = [`post-edit: queue-list.js did not load; ${rel} was not checked.`];
  if (!ql || typeof ql.read !== 'function') return notChecked;
  let row;
  try { row = ql.read(dir, abs); } catch (_) { return notChecked; }
  // After the problem lines, the placement warnings of ADR 0026 (0026-brainstorm-contract.md,
  // section 2), one line each; an older queue-list.js without warnings gives none.
  return [
    ...row.problems.map((p) => `post-edit: ${rel} would list as ? (${p.field} ${p.value === null ? 'missing' : `is "${p.value}"`}; allowed: ${p.allowed}). See docs/${dir}/README.md.`),
    ...(Array.isArray(row.warnings) ? row.warnings : []).map((w) => `post-edit: ${rel} line ${w.line}: ${w.text}. See the brainstorm skill.`),
  ];
}

// docs/<dir>/README.md: a line starting "Listed on demand:" that is not the marker for <dir>
// (section 4, item 2). One message however many such lines; the directory counts as indexed.
function queueReadmeLines(rel, dir, text) {
  const lines = text.replace(/\r/g, '').split('\n').filter((l) => l.startsWith(QUEUE_PREFIX));
  if (lines.length === 0) return [];
  const marker = queueMarker(queueList());
  if (lines.every((l) => isQueueMarker(l, dir, marker))) return [];
  return [`post-edit: ${rel} carries a Listed on demand: line that is not "Listed on demand: node .claude/hooks/queue-list.js ${dir}"; the directory counts as indexed.`];
}

// Path anchors: a backtick-quoted token that looks like a repository path (it contains "/" and
// starts with an existing top-level directory such as docs or .claude) must exist, unless its
// paragraph, or its list item, carries [no-check: ...]. Fenced blocks (indented ones too),
// placeholders (NNNN, <slug>, *) and elided paths (a segment that is "...", as in src/.../X.kt)
// are skipped.
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
      if (p.split('/').includes('...')) continue; // elided, not a path
      if (!missing.includes(p) && !fs.existsSync(path.join(root, ...p.split('/').filter(Boolean)))) missing.push(p);
    }
  }
  return missing;
}

// Import check for a handoff file: each of its own @ imports (imports.js: line start or after
// whitespace, outside code spans and fences) resolves from the importing file's directory (Claude
// Code's rule); an absent target loads nothing and says nothing.
function missingImports(root, rel) {
  return resolve(root, rel, { hops: 1 }).filter((i) => !i.outside && !i.exists).map((i) =>
    `post-edit: ${rel} imports @${i.token}, which resolves to ${i.target} and ${i.isDir ? 'is a directory, which loads nothing' : 'does not exist'} (an import resolves from the importing file's directory).`);
}

// On Windows Node cannot spawn a .cmd or .bat file without a shell. batchFile finds the file the
// command names as cmd.exe would: a name with a directory part in that directory, a bare name in
// the current directory and then each PATH directory, and a name with no extension with each
// PATHEXT extension in turn, first match wins. It returns that file's full path when it is a .cmd
// or .bat, else null: an .exe or .com (rustfmt, node), a name found nowhere, and every other
// platform spawn directly as before, so the 20 s timeout still ends the formatter itself and a
// cmd.exe builtin (echo, del) is never run.
const BATCH = /^\.(cmd|bat)$/i;
function batchFile(cmd) {
  if (process.platform !== 'win32' || typeof cmd !== 'string' || cmd === '') return null;
  const ext = path.extname(cmd);
  if (ext !== '' && !BATCH.test(ext)) return null;
  const exts = ext !== '' ? [''] : (process.env.PATHEXT || '.COM;.EXE;.BAT;.CMD').split(';').filter(Boolean);
  const dirs = /[\\/:]/.test(cmd) ? ['']
    : [process.cwd(), ...(process.env.PATH || '').split(';').map((d) => d.replace(/"/g, '')).filter(Boolean)];
  for (const d of dirs) {
    for (const e of exts) {
      const f = path.resolve(d, cmd + e);
      let found = false;
      try { found = fs.statSync(f).isFile(); } catch (_) { /* absent */ }
      if (found) return BATCH.test(path.extname(f)) ? f : null;
    }
  }
  return null;
}
// Inside double quotes cmd.exe still expands %name% and ends the line at CR or LF, and a " would
// close the quote; & | < > ^ ( ) and spaces are literal there, and ! is too because /v:off turns
// delayed expansion off. So the batch file's full path and every argument are quoted (a trailing
// run of backslashes doubled, so the program's own parser does not read \" as a quote) and an
// argument holding " % CR LF or NUL is refused. The full path, never a bare name: a batch file
// found on PATH under a quoted name gets a wrong %~dp0, which npm shims use to find their script.
const CMD_REFUSED = /["%\r\n\0]/;
function cmdLine(file, args) {
  const all = [file, ...args];
  for (const a of all) {
    const m = CMD_REFUSED.exec(a);
    if (m) return { refused: { '\r': 'CR', '\n': 'LF', '\0': 'NUL' }[m[0]] || m[0] };
  }
  return { line: all.map((a) => `"${a.replace(/(\\+)$/, '$1$1')}"`).join(' ') };
}

function runFormatter(argv, abs, rel) {
  let r;
  const batch = batchFile(argv[0]);
  if (batch) {
    // A timeout here ends cmd.exe; what the batch file started may outlive it.
    const c = cmdLine(batch, [...argv.slice(1), abs]);
    if (c.refused) return [`post-edit: formatter ${argv[0]} failed on ${rel}: not run: cmd.exe cannot be passed ${c.refused} safely`];
    // /s strips the outer quotes and runs the line as written; verbatim, so Node adds no quoting.
    r = childProcess.spawnSync(process.env.ComSpec || 'cmd.exe', ['/d', '/v:off', '/s', '/c', `"${c.line}"`],
      { timeout: 20000, encoding: 'utf8', windowsVerbatimArguments: true });
  } else {
    r = childProcess.spawnSync(argv[0], [...argv.slice(1), abs], { timeout: 20000, encoding: 'utf8' });
  }
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
  if (inside && (rel === 'docs/HANDOFF.md' || /^docs\/handoff\/[^/]+\.md$/.test(rel))) {
    msgs.push(...missingImports(root, rel));
  }
  // The project CLAUDE.md and the files it is built from: the same FAIL lines as pusher check 7's
  // policy.js --claude-check, for the whole tree, since an edit to one file can break another's line.
  if (inside && (rel === 'CLAUDE.md' || /^\.claude\/(agreement|project)\/[^/]+\.md$/.test(rel))) {
    msgs.push(...claudeLines(root));
  }
  if (inside && /^\.claude\/skills\/[^/]+\/SKILL\.md$/.test(rel) && !matches(edit.capExempt, rel)) {
    const n = lineCount(fs.readFileSync(abs, 'utf8'));
    if (n > SKILL_THRESHOLD) {
      msgs.push(`post-edit: ${rel} is ${n} lines; Anthropic advises a SKILL.md body under ${SKILL_THRESHOLD} lines, so split content into files the skill names, one level deep (Claude may read only part of a file referenced from a referenced file). Nothing fails.`);
    }
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
