// SPDX-License-Identifier: GPL-3.0-or-later
// Quote-aware reading of a Bash or PowerShell command line, enough for git-guard to tell the words
// a command runs with from text that is only data. Not a shell: no expansion, no aliases, no
// functions. Spec: docs/hatchery/decisions/0002-hook-specs.md section 1, Parsing.
//
// parse(text, dialect) with dialect 'bash' or 'powershell' returns { commands, data }:
//   commands  every simple command, in source order, as { words, opaque, text, stdin }: words are
//             the dequoted words (redirections removed; an unknown substitution reads as SUBST);
//             opaque[k] is true when word k holds an expansion whose value the reader cannot see
//             (a variable, a substitution, a Bash brace expansion, a PowerShell splat); text is
//             the source slice for messages; stdin the heredoc and here-string bodies fed to it.
//             Commands inside $(...), backticks, <(...), (...) and, in PowerShell, $(...),
//             @(...) and {...} script blocks are listed too, because they run.
//   data      quoted strings and heredoc / here-string bodies. Not code unless a command hands
//             them to a shell; git-guard decides that.
'use strict';

const SUBST = '\u0000';
// The character after a "$" that makes it an expansion rather than a literal dollar.
const VAR_START = { bash: /[A-Za-z_{0-9@*#?!$-]/, powershell: /[A-Za-z_{:?]/ };
// A Bash brace expansion in a word's unquoted characters: {a,b} or {1..3}.
const BRACE_EXPANSION = /\{[^{}]*(,|\.\.)[^{}]*\}/;

// Index of the bracket closing the one at open, skipping quoted text. Returns src.length when
// unbalanced (the rest of the line is then treated as inside).
function matchClose(src, open, dialect) {
  const o = src[open];
  const c = o === '(' ? ')' : '}';
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    const ch = src[i];
    if (ch === (dialect === 'bash' ? '\\' : '`')) { i++; continue; }
    const hd = dialect === 'bash' && /^<<-?[ \t]*(['"]?)([A-Za-z0-9_]+)\1/.exec(src.slice(i, i + 80));
    if (hd && src[i + 2] !== '<') {
      // A heredoc body is text, so its quotes and brackets do not count: skip to its delimiter line.
      let pos = src.indexOf('\n', i);
      while (pos >= 0 && pos < src.length) {
        let e = src.indexOf('\n', pos + 1);
        if (e < 0) e = src.length;
        const line = src.slice(pos + 1, e);
        if ((hd[0].startsWith('<<-') ? line.replace(/^\t+/, '') : line) === hd[2]) { i = e - 1; break; }
        pos = e < src.length ? e : -1;
      }
      if (pos < 0) return src.length;
      continue;
    }
    if (ch === "'") { const j = src.indexOf("'", i + 1); i = j < 0 ? src.length : j; continue; }
    if (ch === '"') {
      for (i++; i < src.length && src[i] !== '"'; i++) if (src[i] === (dialect === 'bash' ? '\\' : '`')) i++;
      continue;
    }
    if (ch === o) depth++;
    else if (ch === c && --depth === 0) return i;
  }
  return src.length;
}

function parse(text, dialect) {
  const out = { commands: [], data: [] };
  lex(String(text).replace(/\r\n?/g, '\n'), dialect === 'powershell' ? 'powershell' : 'bash', out);
  return out;
}

// Scans text that expands substitutions but is otherwise literal (inside double quotes, an
// unquoted heredoc, a PowerShell @"..."@): lexes each $(...) or backtick body as code. Returns
// the value with substitutions replaced by SUBST, and opaque when a variable or substitution was
// expanded. stopAtQuote: end at an unescaped '"'.
function expandable(src, start, dialect, out, stopAtQuote) {
  const ps = dialect === 'powershell';
  let v = '';
  let i = start;
  let opaque = false;
  while (i < src.length) {
    const ch = src[i];
    if (stopAtQuote && ch === '"') {
      if (ps && src[i + 1] === '"') { v += '"'; i += 2; continue; }
      return { value: v, end: i + 1, opaque };
    }
    if (!ps && ch === '\\' && i + 1 < src.length) {
      const n = src[i + 1];
      if (n === '\n') { i += 2; continue; }
      v += '$`"\\'.includes(n) ? n : ch + n; i += 2; continue;
    }
    if (ps && ch === '`' && i + 1 < src.length) { v += src[i + 1]; i += 2; continue; }
    if (ch === '$' && src[i + 1] === '(') {
      const j = matchClose(src, i + 1, dialect);
      lex(src.slice(i + 2, j), dialect, out);
      v += SUBST; opaque = true; i = j + 1; continue;
    }
    if (!ps && ch === '`') {
      let j = i + 1;
      while (j < src.length && src[j] !== '`') j += src[j] === '\\' ? 2 : 1;
      lex(src.slice(i + 1, j), dialect, out);
      v += SUBST; opaque = true; i = j + 1; continue;
    }
    if (ch === '$' && VAR_START[dialect].test(src[i + 1] || '')) opaque = true;
    v += ch; i++;
  }
  return { value: v, end: src.length, opaque };
}

function lex(src, dialect, out) {
  const ps = dialect === 'powershell';
  const n = src.length;
  const fresh = () => ({ words: [], opaque: [], text: '', stdin: [] });
  let cur = fresh();
  let word = null;
  let quoted = false;
  let opq = false; // the current word holds an expansion
  let bare = ''; // the current word's unquoted characters, for Bash brace expansion
  let start = 0;
  let redirect = null; // pending redirection whose target is the next word
  const heredocs = []; // bash heredocs waiting for the next newline

  const flush = () => {
    if (word === null) return;
    const w = word;
    word = null;
    if (redirect) {
      if (redirect.kind === 'heredoc') heredocs.push({ delim: w, strip: redirect.strip, quoted, cmd: cur });
      else if (redirect.kind === 'herestring') { cur.stdin.push(w); out.data.push(w); }
      redirect = null;
    } else if (!ps && !quoted && (w === '{' || w === '}')) {
      // Bash group braces: not words of the command they enclose.
    } else {
      cur.words.push(w);
      cur.opaque.push(opq || (!ps && BRACE_EXPANSION.test(bare)));
    }
    quoted = false;
    opq = false;
    bare = '';
  };
  const end = (at) => {
    flush();
    if (cur.words.length > 0) {
      cur.text = src.slice(start, at === null ? n : at).trim();
      out.commands.push(cur);
    }
    cur = fresh();
    if (at !== null) start = at;
  };
  const add = (s) => { word = (word === null ? '' : word) + s; };

  let i = 0;
  while (i < n) {
    const ch = src[i];
    if (ch === '\n') {
      end(i);
      i++;
      while (heredocs.length > 0) i = readHeredoc(src, i, heredocs.shift(), dialect, out);
      start = i;
      continue;
    }
    if (ch === ' ' || ch === '\t') { flush(); i++; continue; }
    if (ch === '#' && word === null) {
      const j = src.indexOf('\n', i);
      i = j < 0 ? n : j;
      continue;
    }
    if (ps && ch === '<' && src[i + 1] === '#') {
      const j = src.indexOf('#>', i + 2);
      i = j < 0 ? n : j + 2;
      continue;
    }
    const esc = ps ? '`' : '\\';
    if (ch === esc) {
      if (src[i + 1] === '\n') { i += 2; continue; }
      add(src[i + 1] || ''); quoted = true; i += 2; continue;
    }
    if (ch === "'" || (!ps && ch === '$' && src[i + 1] === "'")) {
      // Single quotes are literal; PowerShell doubles a quote to escape it; Bash $'...' takes \'.
      const ansi = ch === '$';
      let j = i + (ansi ? 2 : 1);
      let v = '';
      while (j < n) {
        if (src[j] === "'" && ps && src[j + 1] === "'") { v += "'"; j += 2; continue; }
        if (src[j] === "'") break;
        if (ansi && src[j] === '\\' && j + 1 < n) { v += src[j + 1]; j += 2; continue; }
        v += src[j]; j++;
      }
      add(v); out.data.push(v); quoted = true; i = j + 1; continue;
    }
    if (ps && ch === '@' && (src[i + 1] === "'" || src[i + 1] === '"') && src[i + 2] === '\n') {
      const q = src[i + 1];
      const close = src.indexOf(`\n${q}@`, i + 2);
      const body = src.slice(i + 3, close < 0 ? n : close);
      const r = q === '"' ? expandable(body, 0, dialect, out, false) : { value: body, opaque: false };
      add(r.value); out.data.push(r.value); quoted = true; opq = opq || r.opaque;
      i = close < 0 ? n : close + 3; continue;
    }
    if (ch === '"') {
      const r = expandable(src, i + 1, dialect, out, true);
      add(r.value); out.data.push(r.value); quoted = true; opq = opq || r.opaque; i = r.end; continue;
    }
    if (ch === '$' && src[i + 1] === '(') {
      const j = matchClose(src, i + 1, dialect);
      lex(src.slice(i + 2, j), dialect, out);
      add(SUBST); opq = true; i = j + 1; continue;
    }
    if (!ps && ch === '`') {
      let j = i + 1;
      while (j < n && src[j] !== '`') j += src[j] === '\\' ? 2 : 1;
      lex(src.slice(i + 1, j), dialect, out);
      add(SUBST); opq = true; i = j + 1; continue;
    }
    if (!ps && ch === '$' && src[i + 1] === '{') {
      const j = matchClose(src, i + 1, dialect);
      add(src.slice(i, j + 1)); opq = true; i = j + 1; continue;
    }
    if (!ps && (ch === '<' || ch === '>') && src[i + 1] === '(') {
      const j = matchClose(src, i + 1, dialect);
      flush();
      lex(src.slice(i + 2, j), dialect, out);
      i = j + 1; continue;
    }
    const op = /^(&>>|&>|<<<|<<-|<<|<>|<&|>&|>>|>\||\*>>|\*>|<|>)/.exec(src.slice(i, i + 3));
    if (op && (ch !== '*' || (ps && word === null)) && (ch !== '&' || !ps)) {
      // Redirection: a numeric fd just before it belongs to it; the next word is its target
      // (a file, an fd such as the 1 of 2>&1, a heredoc delimiter, or a here-string).
      if (word !== null && /^\d+$/.test(word) && !quoted) word = null;
      flush();
      i += op[1].length;
      if (op[1] === '<<<') redirect = { kind: 'herestring' };
      else if (op[1] === '<<' || op[1] === '<<-') redirect = { kind: 'heredoc', strip: op[1] === '<<-' };
      else redirect = { kind: 'file' };
      continue;
    }
    if (ch === ';' || ch === '|' || ch === '&' || ch === '(' || ch === ')' ||
        (ps && (ch === '{' || ch === '}'))) {
      end(i);
      i++;
      start = i;
      continue;
    }
    if (ch === '$' && VAR_START[dialect].test(src[i + 1] || '')) opq = true;
    if (ps && ch === '@' && word === null && /[A-Za-z_]/.test(src[i + 1] || '')) opq = true; // splatting
    add(ch); bare += ch; i++;
  }
  end(null);
  // A heredoc whose newline never came (the command ends on the same line) has an empty body.
}

// Reads one heredoc body starting at i (just after a newline); returns the index after its
// delimiter line. An unquoted delimiter means the body expands substitutions.
function readHeredoc(src, i, h, dialect, out) {
  const lines = [];
  let pos = i;
  while (pos < src.length) {
    let e = src.indexOf('\n', pos);
    if (e < 0) e = src.length;
    const line = src.slice(pos, e);
    pos = e + 1;
    if ((h.strip ? line.replace(/^\t+/, '') : line) === h.delim) break;
    lines.push(line);
  }
  const body = lines.join('\n');
  const v = h.quoted ? body : expandable(body, 0, dialect, out, false).value;
  h.cmd.stdin.push(v);
  out.data.push(v);
  return Math.min(pos, src.length);
}

module.exports = { parse, SUBST };
