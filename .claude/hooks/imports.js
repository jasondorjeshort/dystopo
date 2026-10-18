// SPDX-License-Identifier: GPL-3.0-or-later
// The one parser of Claude Code's @ imports in CLAUDE.md and the files it loads (ADR 0016, item 4;
// spec: docs/design/decisions/0016-update-procedure.md, Checks). policy.js --claude-check,
// post-edit.js and ownership.js --report call it, so the edit, the push and the update agree.
//
// The rule, following the loader (https://code.claude.com/docs/en/memory#import-additional-files):
//   - an @ counts at line start or after whitespace, so "See @README" imports README;
//   - never inside a fenced block (``` or ~~~, any indent; a ``` line whose info string holds a
//     backtick is not a fence, as in CommonMark) or a code span (`...`, ``...``, also one that
//     crosses a line break inside its paragraph);
//   - the token runs to the next whitespace, with trailing . , ; : ) stripped;
//   - it resolves from the importing file's directory;
//   - imports nest four hops: the start file is hop 0, its imports hop 1; a file at hop 4 is loaded
//     but its own imports are not.
// A target outside the repository root (~/..., an absolute path, a ../ that climbs above the root)
// is returned with outside: true and is never stat'ed, read or followed: the checks are about the
// tree, and a file outside it is the user's.
// The sentinel procedure in the agreement-loading reference entry checks this against the loader;
// a mismatch is a fix here.
//
// parse(text) -> [{ line, token }]  line 1-based, token without the @.
// resolve(root, file, opts) -> [{ file, line, token, target, outside, exists, isDir, hop }] for every
//   import reached from file (repo-relative, forward slashes) within opts.hops (default 4), each file
//   read once, breadth first. target is repo-relative with forward slashes; when outside is true it
//   is the path as written (~/x, /x, C:/x) or relative to the root (../x), and exists and isDir are
//   null. exists is true only for a regular file (a directory loads nothing); isDir marks a
//   directory target. opts.skip: targets listed but not followed (the handoff chain for the 190).
// Node built-ins only (ADR 0013). An unreadable or absent file yields no imports, never a throw.
'use strict';
const fs = require('fs');
const path = require('path');

const HOPS = 4;
const SPAN = '\u0000'; // stands in for code-span text: not whitespace, so an @ after a span does not count

// Replaces each code span (a run of n backticks up to the next run of exactly n) with SPAN
// characters, keeping line breaks so line numbers hold; an unmatched run stays literal, as
// CommonMark has it. text is one paragraph, so a span may cross a line break but not a blank line.
function blankSpans(text) {
  let out = '';
  let i = 0;
  while (i < text.length) {
    if (text[i] !== '`') { out += text[i++]; continue; }
    let n = 0;
    while (text[i + n] === '`') n++;
    let j = i + n;
    let close = -1;
    while (j < text.length) {
      if (text[j] !== '`') { j++; continue; }
      let m = 0;
      while (text[j + m] === '`') m++;
      if (m === n) { close = j; break; }
      j += m;
    }
    if (close < 0) { out += text.slice(i, i + n); i += n; continue; }
    out += text.slice(i, close + n).replace(/[^\n]/g, SPAN);
    i = close + n;
  }
  return out;
}

// A fence opener: ``` or ~~~ (three or more), any indent; a backtick fence's info string may not
// hold a backtick.
function fenceOf(raw) {
  const f = raw.match(/^[ \t]*(`{3,}|~{3,})(.*)$/);
  if (!f || (f[1][0] === '`' && f[2].includes('`'))) return null;
  return { ch: f[1][0], len: f[1].length, rest: f[2] };
}

function parse(text) {
  const found = [];
  const rows = String(text).replace(/\r/g, '').split('\n');
  let fence = null; // { ch, len } while inside a fenced block
  let para = []; // [{ k, raw }] of the current paragraph
  const flush = () => {
    if (para.length === 0) return;
    const blanked = blankSpans(para.map((p) => p.raw).join('\n')).split('\n');
    blanked.forEach((line, i) => {
      for (const m of line.matchAll(/(^|\s)@(\S+)/g)) {
        let token = m[2];
        const cut = token.indexOf(SPAN);
        if (cut >= 0) token = token.slice(0, cut);
        token = token.replace(/[.,;:)]+$/, '');
        if (token !== '') found.push({ line: para[i].k + 1, token });
      }
    });
    para = [];
  };
  rows.forEach((raw, k) => {
    const f = fenceOf(raw);
    if (fence !== null) {
      if (f && f.ch === fence.ch && f.len >= fence.len && f.rest.trim() === '') fence = null;
      return;
    }
    if (f) { flush(); fence = { ch: f.ch, len: f.len }; return; }
    if (raw.trim() === '') { flush(); return; }
    para.push({ k, raw });
  });
  flush();
  return found;
}

// The target of token imported from file (repo-relative): { target, outside }.
function targetOf(file, token) {
  if (token.startsWith('~') || token.startsWith('/') || token.startsWith('\\') || /^[A-Za-z]:[\\/]/.test(token)) {
    return { target: token.replace(/\\/g, '/'), outside: true };
  }
  const rel = path.posix.normalize(path.posix.join(path.posix.dirname(file), token));
  if (rel === '..' || rel.startsWith('../')) return { target: rel, outside: true };
  return { target: rel.replace(/^\.\//, ''), outside: false };
}

const absOf = (root, target) => path.join(root, ...target.split('/'));

function readOrNull(file) {
  try { return fs.readFileSync(file, 'utf8'); } catch (_) { return null; }
}

function resolve(root, file, opts) {
  const o = opts || {};
  const hops = o.hops === undefined ? HOPS : o.hops;
  const skip = new Set(o.skip || []);
  const out = [];
  const seen = new Set([file]);
  let level = [file];
  for (let hop = 1; hop <= hops && level.length > 0; hop++) {
    const next = [];
    for (const f of level) {
      const text = readOrNull(absOf(root, f));
      if (text === null) continue;
      for (const { line, token } of parse(text)) {
        const { target, outside } = targetOf(f, token);
        if (outside) { out.push({ file: f, line, token, target, outside, exists: null, isDir: null, hop }); continue; }
        let exists = false;
        let isDir = false;
        try { const s = fs.statSync(absOf(root, target)); exists = s.isFile(); isDir = s.isDirectory(); } catch (_) { /* absent */ }
        out.push({ file: f, line, token, target, outside, exists, isDir, hop });
        if (exists && !skip.has(target) && !seen.has(target)) { seen.add(target); next.push(target); }
      }
    }
    level = next;
  }
  return out;
}

module.exports = { parse, resolve, absOf, HOPS };
