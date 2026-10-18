#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
// The privacy scan (ADR 0018; contract: docs/design/decisions/0018-privacy-scan.md, sections 1 to 3).
// One reader for everything a push sends, with three callers: git-guard's rule 20 requires this
// module, pusher check 5 runs the CLI, and .claude/hooks/git/pre-push runs it for every push made
// outside Claude Code. It reads every commit in `git rev-list <refs> --not --remotes=<target>
// [--remotes=hatchery] [<extra>]`, never the net diff: author and committer, the message, the
// lines each commit adds against every parent, the names of the files it adds, and each
// annotated tag among the refs (message and tagger). No other remote counts as published.
//
// Usage:
//   node .claude/hooks/privacy-scan.js [--remote <name> | --no-remote] [--json] [<ref> ...]
//   node .claude/hooks/privacy-scan.js --pre-push <remote-name> <remote-url>   (stdin: git's lines)
//   node .claude/hooks/privacy-scan.js --text <file> [--json]
// Refs default to HEAD; the remote to the one git would pick for a plain push (it must be a
// configured remote; a URL or path is "no remote"). The repository is the current directory.
// --pre-push scans each local sha git sends and adds each remote sha that is not all zeros to the
// --not list. stdout: one "HIT <class> <where>: <match>" line per hit, then "privacy-scan: <n> hits
// in <c> commits" or "privacy-scan: clean, <c> commits" ("<c> lines" with --text). Notices go to
// stderr, prefixed "privacy-scan:". Exit 0 clean, 1 hits, 2 cannot compute ("privacy-scan: cannot
// <reason>" on stderr and no last line). --json prints one object instead of the lines:
// { "commits", "hits": [{ "class", "where", "match", "commit": <sha40> | null }], "error" }.
// Policy: privacy.allow and privacy.identities through policy.js (HOOK_POLICY_FILE as the hooks);
// a policy that does not load is exit 2. The owner's files, <home>/.claude/ with home being HOME,
// else USERPROFILE, one literal per line, "#" comments, absent is empty, unreadable is exit 2:
// privacy-never.txt (more literals for the word class) and privacy-allow.txt (literals that lift
// word, host and home hits without entering the tracked policy).
// Module: scanPush, scanText, pushSources, pushRemote, gitRead, context (git-guard uses them).
// Node built-ins only (ADR 0013). Harness: group PV in test-hooks.js.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const TIMEOUT = 20000;
const MAX_BUFFER = 512 * 1024 * 1024;
const SUBST = '\u0000'; // shell-parse's mark for a value the guard's reader cannot see
const MATCH_CAP = 80;
const BASE_REMOTE = 'hatchery'; // the published base: its commits are never outgoing
const POLICY_PATH = '.claude/hooks/policy.json'; // declares privacy.identities: their addresses pass in its added lines
const ZEROS = /^0+$/;

// A failure to work out or read what a push sends. Its message reads after "cannot ".
class ScanError extends Error {}
const cannot = (reason) => new ScanError(reason);

// The repository and config a scan reads: { dir, pre, env, key }. pre holds global options
// (--git-dir, --work-tree, -c, --config-env) and env the environment for git (undefined: this
// process's own); key identifies the pair for the cache. git-guard builds its own from a command.
const context = (dir, pre, env) => ({ dir, pre: pre || [], env, key: JSON.stringify([dir, pre || [], env ? 'env' : null]) });

// One read-only git command: { ok, out, err }. A timeout or spawn failure is not ok.
function gitRead(g, args) {
  const r = spawnSync('git', ['-C', g.dir, ...g.pre, ...args],
    { encoding: 'utf8', timeout: TIMEOUT, maxBuffer: MAX_BUFFER, windowsHide: true, env: g.env });
  const err = r.error ? r.error.message : ((r.stderr || '').trim().split('\n')[0] || `exit ${r.status}`);
  return { ok: !r.error && r.status === 0, out: r.stdout || '', err };
}
const configAll = (g, key) => {
  const r = gitRead(g, ['config', '--get-all', key]);
  return r.ok ? r.out.split('\n').map((l) => l.trim()).filter(Boolean) : [];
};
function listRefs(g, pattern) {
  const r = gitRead(g, ['for-each-ref', '--format=%(refname)', pattern]);
  if (!r.ok) throw cannot(`list the refs ${pattern} (${r.err})`);
  return r.out.split('\n').map((l) => l.trim()).filter(Boolean);
}
function remotes(g) {
  const r = gitRead(g, ['remote']);
  if (!r.ok) throw cannot(`list the remotes (${r.err})`);
  return r.out.split('\n').map((l) => l.trim()).filter(Boolean);
}
const isUrl = (r) => /[:/\\]/.test(r) || r === '.' || r === '..';

// The remote a push goes to, as git picks it: the command line's repository (words[0]), --repo,
// branch.<current>.pushRemote, remote.pushDefault, branch.<current>.remote, origin. null for a URL
// or a path; a name that is not a configured remote throws.
function pushRemote(g, words, repo) {
  let r = (words && words[0]) || repo;
  if (!r) {
    const b = gitRead(g, ['symbolic-ref', '-q', '--short', 'HEAD']);
    const br = b.ok ? b.out.trim() : '';
    r = (br && configAll(g, `branch.${br}.pushRemote`)[0]) || configAll(g, 'remote.pushDefault')[0] ||
      (br && configAll(g, `branch.${br}.remote`)[0]) || 'origin';
  }
  if (r.includes(SUBST)) throw cannot('see the remote (its value is an expansion)');
  if (isUrl(r)) return null;
  if (!remotes(g).includes(r)) throw cannot(`find the remote ${r} (not a configured remote)`);
  return r;
}

// What a push sends: { refs, remote }. p: { words (positionals, the repository first), repo
// (--repo), del, all (--all or --branches), tags, followTags }. refs are each refspec's source
// (HEAD as itself; a glob as every ref it matches, a bare glob under refs/heads/; a deletion
// sends nothing); with no refspec, every ref for a mirror remote, else remote.<r>.push, else
// push.default (matching: each local branch the remote has; nothing: none; current, simple,
// upstream or unset: HEAD); every local branch for --all; every tag for --tags or --follow-tags.
function pushSources(g, p) {
  const words = p.words || [];
  const remote = pushRemote(g, words, p.repo);
  let specs = words.slice(1);
  const refs = new Set();
  const bare = !p.del && specs.length === 0 && !p.all && !p.tags;
  if (bare && remote && (configAll(g, `remote.${remote}.mirror`)[0] || '').toLowerCase() === 'true') {
    for (const r of listRefs(g, 'refs')) refs.add(r);
    return { refs: [...refs], remote };
  }
  if (bare) {
    specs = remote ? configAll(g, `remote.${remote}.push`) : [];
    if (specs.length === 0) {
      const mode = (configAll(g, 'push.default')[0] || 'simple').toLowerCase();
      if (mode === 'matching') {
        for (const r of listRefs(g, 'refs/heads')) {
          const short = r.replace(/^refs\/heads\//, '');
          if (!remote || gitRead(g, ['rev-parse', '--verify', '-q', `refs/remotes/${remote}/${short}`]).ok) refs.add(r);
        }
      } else if (mode !== 'nothing') refs.add('HEAD');
    }
  }
  if (!p.del) {
    for (let i = 0; i < specs.length; i++) {
      let s = specs[i];
      // "tag <name>" is the refspec refs/tags/<name>.
      if (s === 'tag' && specs[i + 1] !== undefined) s = `refs/tags/${specs[++i]}`;
      if (s.includes(SUBST)) throw cannot('see a refspec (its value is an expansion)');
      const spec = s.replace(/^\+/, '');
      const src = spec.includes(':') ? spec.slice(0, spec.indexOf(':')) : spec;
      if (src === '') continue;
      if (src.includes('*')) for (const r of listRefs(g, src.startsWith('refs/') ? src : `refs/heads/${src}`)) refs.add(r);
      else refs.add(src);
    }
  }
  if (p.all) for (const r of listRefs(g, 'refs/heads')) refs.add(r);
  if (p.tags || p.followTags) for (const r of listRefs(g, 'refs/tags')) refs.add(r);
  return { refs: [...refs], remote };
}

// ---------- classes (section 2) ----------
const EMAIL = /(?<![\w.+-])[\w.+-]+@[\w-]+(?:\.[\w-]+)+/g;
const PLACEHOLDER_DOMAIN = /^example\.(com|net|org)$|\.invalid$/;
const GITHUB_NOREPLY = /@users\.noreply\.github\.com$/;
const URL_USERS = new Set(['git', 'x-access-token', 'oauth2']);
const URL_SCHEME = /(?:https?|ssh|git):\/\/$/i;
const USERS = '[Uu][Ss][Ee][Rr][Ss]';
const HOME_DRIVE = new RegExp(String.raw`[A-Za-z]:[\\/]+${USERS}[\\/]+([A-Za-z0-9._-]+)`, 'g');
// Root spellings where a path starts; /<d>/Users/ is the Git Bash drive spelling.
const HOME_ROOT = new RegExp(String.raw`(?:^|(?<=[\s"'\x60(=:[+/\\,>]))\/(?:home|Users|(?:cygdrive\/|mnt\/)?[A-Za-z]\/Users)\/([A-Za-z0-9._-]+)`, 'g');
// A WSL distribution's home seen from Windows: \\wsl$\<distro>\home\<seg> (or wsl.localhost, either slash).
const HOME_WSL = new RegExp(String.raw`[\\/]{2}wsl(?:\$|\.localhost)[\\/]+[^\\/\s"'\x60]+[\\/]+home[\\/]+([A-Za-z0-9._-]+)`, 'gi');
// The project-folder slug Claude Code writes for a path (every separator a hyphen).
const HOME_SLUG = new RegExp(String.raw`(?:^|(?<=[\s"'\x60(=:/\\,>]))(?:[A-Za-z]--${USERS}|-home|-Users)-([A-Za-z0-9._]+)-`, 'g');
const PROFILE = /^(?:public|default user|default|all users|\.\.\.)(?![A-Za-z0-9._])/i;
const PROFILE_SLUG = /^(?:public|default-user|default|all-users|\.\.\.)-/i;
const HOST = /(?<=\w)-desktop|desktop-(?=\w)/gi;
const TRAILER = /^Claude-Session:/i;
const URL_RE = /\bhttps?:\/\/(?:[^\s/@"'`<>]*@)?([A-Za-z0-9.-]+)(?::\d+)?(\/[^\s"'`<>()[\]{}|\\^]*)?/gi;
// Full key shapes only, each bounded by non-key characters on both sides.
const KEY_EDGE_L = '(?<![A-Za-z0-9_-])';
const KEY_EDGE_R = '(?![A-Za-z0-9_-])';
const SECRETS = [
  /-----BEGIN [A-Z ]*PRIVATE KEY-----/g,
  new RegExp(`${KEY_EDGE_L}AKIA[A-Z0-9]{16}${KEY_EDGE_R}`, 'g'),
  new RegExp(`${KEY_EDGE_L}ghp_[A-Za-z0-9]{36}${KEY_EDGE_R}`, 'g'),
  new RegExp(`${KEY_EDGE_L}github_pat_[A-Za-z0-9_]{82}${KEY_EDGE_R}`, 'g'),
  new RegExp(`${KEY_EDGE_L}sk-ant-[A-Za-z0-9_-]{80,}${KEY_EDGE_R}`, 'g'),
];
const SECRET_FILE = (p) => { const b = p.split('/').pop(); return b === '.env' || /\.(pem|key)$/i.test(b); };
const USER_LIFTS = new Set(['word', 'host', 'home']);
const escapeRe = (s) => s.replace(/[\\^$.*+?()[\]{}|/]/g, '\\$&');
const clip = (s) => (s.length > MATCH_CAP ? `${s.slice(0, MATCH_CAP - 3)}...` : s);
// A secret is shown by its first eight characters only, so a refusal does not repeat the key.
const veil = (s) => (s.length > 8 ? `${s.slice(0, 8)}...` : s);
const liftKey = (s) => s.replace(/\.+$/, '').toLowerCase();

// The owner's literal files under <home>/.claude/: [] when absent; throws when unreadable.
function ownerList(name) {
  const home = process.env.HOME || process.env.USERPROFILE;
  if (!home) return [];
  const file = path.join(home, '.claude', name);
  let text;
  try { text = fs.readFileSync(file, 'utf8'); } catch (e) {
    if (e.code === 'ENOENT') return [];
    throw cannot(`read ~/.claude/${name} (${e.code || e.message})`);
  }
  return text.replace(/^\uFEFF/, '').split(/\r?\n/).map((l) => l.trim()).filter((t) => t !== '' && !t.startsWith('#'));
}

// Everything a scan needs from the policy and the machine: { allow, userAllow, identities, words,
// notices, said }. allow (privacy.allow) lifts a hit of any class; userAllow (privacy-allow.txt)
// lifts word, host and home hits. Both hold lift keys (lower-cased, trailing dots dropped).
function scanSetup(privacy) {
  const notices = [];
  const allow = new Set((privacy.allow || []).map(liftKey));
  const userAllow = new Set(ownerList('privacy-allow.txt').map(liftKey));
  const identities = (privacy.identities || []).map((i) => ({ name: i.name.trim(), email: i.email.trim().toLowerCase() }));
  if (identities.length === 0) notices.push('privacy.identities is empty: the identity class is off');
  const literals = [];
  try { literals.push(['the OS user name', os.userInfo().username]); } catch (e) {
    notices.push(`the OS user name cannot be read (${e.message}); the word class runs without it`);
  }
  literals.push(['the host name', os.hostname()]);
  for (const t of ownerList('privacy-never.txt')) literals.push(['a privacy-never.txt line', t]);
  const seen = new Set();
  const words = [];
  for (const [from, lit] of literals) {
    const low = (lit || '').toLowerCase();
    if (low === '' || seen.has(low)) continue;
    seen.add(low);
    if (lit.length < 3) { notices.push(`${from} is shorter than 3 characters and is skipped`); continue; }
    words.push(new RegExp(`(?<!\\w)${escapeRe(lit)}(?!\\w)`, 'giu'));
  }
  return { allow, userAllow, identities, words, notices, said: new Set() };
}

// An address the email class passes. own: an author, committer or tagger field, or an added line
// of the policy file that declares the identities, where an identity's own address passes; in any
// other content only a listed GitHub noreply address does.
function emailOk(addr, before, c, own) {
  const e = addr.toLowerCase();
  const at = e.indexOf('@');
  const listed = c.identities.some((i) => i.email === e);
  return (listed && (own || GITHUB_NOREPLY.test(e))) || e === 'noreply@anthropic.com' || e.endsWith('@noreply.anthropic.com') ||
    PLACEHOLDER_DOMAIN.test(e.slice(at + 1)) || URL_USERS.has(e.slice(0, at)) || URL_SCHEME.test(before);
}
function isTranscript(text) {
  const t = text.trim();
  if (!t.startsWith('{') || !t.endsWith('}')) return false;
  try {
    const o = JSON.parse(t);
    const own = (k) => Object.prototype.hasOwnProperty.call(o, k);
    return o !== null && typeof o === 'object' && !Array.isArray(o) && own('sessionId') && own('parentUuid');
  } catch (_) { return false; }
}

// Adds a hit unless a lift applies. key is what the lifts compare (the match; for home the user
// segment, for host the word without the marker). The user allow list lifts word, host and home
// only; an entry it holds for another class's hit is ignored, with one notice per class.
function push(out, c, cls, where, commit, match, key) {
  const k = liftKey(key === undefined ? match : key);
  if (c.allow.has(k)) return;
  if (c.userAllow.has(k)) {
    if (USER_LIFTS.has(cls)) return;
    if (!c.said.has(cls)) {
      c.said.add(cls);
      c.notices.push(`privacy-allow.txt lifts only word, host and home hits; its entry is ignored for a ${cls} hit`);
    }
  }
  out.push({ class: cls, where, match: clip(match), commit });
}

// The addresses in a line as [{ addr, before }]. A \n, \r or \t escape in source text is not part
// of the user part after it (a string literal "\nname@host" holds the address name@host, and
// "\n@x.md" holds none).
function addresses(text) {
  const out = [];
  for (const m of text.matchAll(EMAIL)) {
    let addr = m[0];
    let at = m.index;
    if (at > 0 && text[at - 1] === '\\' && /^[nrt]/.test(addr)) { addr = addr.slice(1); at++; }
    if (!addr.startsWith('@')) out.push({ addr, before: text.slice(0, at) });
  }
  return out;
}

// The hits in one line of text, appended to out. opts.transcript: run the transcript class.
// opts.policyLine: an added line of POLICY_PATH, where a listed identity's address passes.
function lineHits(text, where, commit, c, out, opts) {
  const add = (cls, match, key) => push(out, c, cls, where, commit, match, key);
  for (const { addr, before } of addresses(text)) if (!emailOk(addr, before, c, opts.policyLine === true)) add('email', addr);
  const ends = new Set();
  for (const re of [HOME_DRIVE, HOME_ROOT, HOME_WSL]) {
    for (const m of text.matchAll(re)) {
      const end = m.index + m[0].length;
      if (ends.has(end)) continue; // a drive spelling read again from its root part
      ends.add(end);
      if (PROFILE.test(text.slice(end - m[1].length))) continue;
      add('home', m[0], m[1]);
    }
  }
  for (const m of text.matchAll(HOME_SLUG)) {
    if (PROFILE_SLUG.test(text.slice(m.index + m[0].length - m[1].length - 1))) continue;
    add('home', m[0], m[1]);
  }
  const starts = new Set();
  for (const m of text.matchAll(HOST)) {
    let a = m.index;
    let b = m.index + m[0].length;
    while (a > 0 && /[\w-]/.test(text[a - 1])) a--;
    while (b < text.length && /[\w-]/.test(text[b])) b++;
    if (starts.has(a)) continue;
    starts.add(a);
    const token = text.slice(a, b);
    add('host', token, (token.slice(0, m.index - a) + token.slice(m.index - a + m[0].length)).replace(/^-+|-+$/g, ''));
  }
  for (const re of c.words) for (const m of text.matchAll(re)) add('word', m[0]);
  if (TRAILER.test(text)) add('session', text.trim());
  for (const m of text.matchAll(URL_RE)) {
    const host = m[1].toLowerCase().replace(/\.$/, '');
    const segs = (m[2] || '').split(/[?#]/)[0].split('/').filter(Boolean);
    if ((host === 'claude.ai' || host.endsWith('.claude.ai')) && segs.length > 0) add('session', m[0]);
  }
  if (opts.transcript && isTranscript(text)) add('transcript', text.trim());
  for (const re of SECRETS) for (const m of text.matchAll(re)) add('secret', veil(m[0]), m[0]);
}

// An author, committer or tagger: the email class on the name and the email (an identity's own
// address passes here), and the identity class.
function personHits(name, email, where, commit, c, out) {
  for (const t of [name, email]) {
    for (const { addr, before } of addresses(t)) if (!emailOk(addr, before, c, true)) push(out, c, 'email', where, commit, addr);
  }
  if (c.identities.length === 0) return;
  const n = name.trim();
  const e = email.trim().toLowerCase();
  if (!c.identities.some((i) => i.name === n && i.email === e)) push(out, c, 'identity', where, commit, `${name} <${email}>`);
}

// A path as git writes it in a diff header: C-quoted when it holds a quote, a backslash or a
// control character (core.quotePath=false leaves other bytes alone).
function unquotePath(p) {
  if (!p.startsWith('"')) return p;
  const esc = { n: '\n', t: '\t', '"': '"', '\\': '\\' };
  return p.slice(1, -1).replace(/\\(.)/g, (_, ch) => esc[ch] || ch);
}
// The path of a `diff --git a/P b/P` (no renames, so both sides are P) or `diff --cc P` line.
function diffPath(l) {
  if (!l.startsWith('diff --git ')) return unquotePath(l.replace(/^diff --(?:cc|combined) /, ''));
  const rest = l.slice('diff --git '.length);
  const q = /^"a\/((?:[^"\\]|\\.)*)" "b\/((?:[^"\\]|\\.)*)"$/.exec(rest);
  if (q) return unquotePath(`"${q[2]}"`);
  return rest.slice(rest.length - (rest.length - 5) / 2);
}

// The one `git log -p` over the range, as [{ sha, an, ae, cn, ce, message, lines, files }]. Each
// commit opens with a NUL-C line (no message can hold a NUL) and its message closes with a NUL-E
// line; the patch follows. Patch header lines are read only between a diff line and its first
// hunk, so content that begins "++ " is content. In a merge's combined diff a line counts when
// every parent column is "+": the lines the merge adds against every parent. n is the line number
// in the new file.
const OPEN = '\0C';
const CLOSE = '\0E';
function parseLog(out) {
  const commits = [];
  const ls = out.split('\n');
  let cur = null;
  let file = null;
  let header = false;
  let cols = 1;
  let n = 0;
  for (let k = 0; k < ls.length; k++) {
    const l = ls[k].replace(/\r$/, '');
    if (l === OPEN) {
      const [sha, an, ae, cn, ce] = ls.slice(k + 1, k + 6).map((x) => x.replace(/\r$/, ''));
      let e = k + 6;
      while (e < ls.length && ls[e].replace(/\r$/, '') !== CLOSE) e++;
      const message = ls.slice(k + 6, e).map((x) => x.replace(/\r$/, ''));
      while (message.length && message[message.length - 1] === '') message.pop();
      cur = { sha: sha.trim(), an, ae, cn, ce, message, lines: [], files: [] };
      commits.push(cur);
      file = null;
      header = false;
      k = e;
      continue;
    }
    if (cur === null) continue;
    if (/^diff --(git|cc|combined) /.test(l)) { file = diffPath(l); header = true; continue; }
    const h = /^(@@+) (?:-\S+ )+\+(\d+)(?:,\d+)? @@+/.exec(l);
    if (h) { header = false; cols = h[1].length - 1; n = Number(h[2]); continue; }
    if (file === null) continue;
    if (header) {
      if (l.startsWith('new file mode ')) cur.files.push(file);
      else if (l.startsWith('+++ ') && l !== '+++ /dev/null') file = unquotePath(l.slice(4).replace(/\t$/, '')).replace(/^b\//, ''); // a path with a space ends in a tab
      continue;
    }
    const pre = l.slice(0, cols);
    if (pre.length < cols || pre.includes('-') || pre.startsWith('\\')) continue;
    if (/^\++$/.test(pre)) cur.lines.push({ file, n, text: l.slice(cols) });
    n++;
  }
  return commits;
}

// Reads and scans what refs send to remote: { commits, hits }. extra: more --not revisions (the
// remote shas of a pre-push). Throws ScanError.
function computePush(g, refs, remote, extra, c) {
  const hits = [];
  if (refs.length === 0) return { commits: 0, hits };
  for (const r of [...refs, ...extra]) if (r.includes(SUBST)) throw cannot('see a ref (its value is an expansion)');
  const not = [];
  if (remote) not.push(`--remotes=${remote}`);
  else c.notices.push('no remote: the range is bounded only by the hatchery remote and the caller\'s list');
  if (remote !== BASE_REMOTE && remotes(g).includes(BASE_REMOTE)) not.push(`--remotes=${BASE_REMOTE}`);
  // A remote sha the repository does not have cannot bound the range; git would refuse it.
  for (const s of extra) if (gitRead(g, ['cat-file', '-e', s]).ok) not.push(s);
  const range = [...refs, ...(not.length ? ['--not', ...not] : []), '--'];
  const log = gitRead(g, ['-c', 'core.quotePath=false', 'log', '--reverse', '-p', '--cc', '-U0', '--root', '--no-color',
    '--no-show-signature', '--no-use-mailmap', '--no-ext-diff', '--no-textconv', '--no-renames', '--no-relative',
    '--src-prefix=a/', '--dst-prefix=b/', '--format=%x00C%n%H%n%an%n%ae%n%cn%n%ce%n%B%n%x00E', ...range]);
  if (!log.ok) throw cannot(`read the outgoing commits (git log ${refs.join(' ')}: ${log.err})`);
  const commits = parseLog(log.out);
  for (const k of commits) {
    const s7 = k.sha.slice(0, 7);
    personHits(k.an, k.ae, `${s7} author`, k.sha, c, hits);
    personHits(k.cn, k.ce, `${s7} committer`, k.sha, c, hits);
    for (const l of k.message) lineHits(l, `${s7} message`, k.sha, c, hits, { transcript: true });
    for (const a of k.lines) lineHits(a.text, `${s7} ${a.file}:${a.n}`, k.sha, c, hits, { transcript: true, policyLine: a.file === POLICY_PATH });
    for (const p of k.files) {
      const where = `${s7} file ${p}`;
      lineHits(p, where, k.sha, c, hits, { transcript: false });
      if (SECRET_FILE(p)) push(hits, c, 'secret', where, k.sha, p);
    }
  }
  // Annotated tags among the refs, by name or by the tag object's sha (pre-push sends shas); a tag
  // whose object is in the --not list is already on the remote.
  const tags = gitRead(g, ['for-each-ref',
    '--format=%(refname)%00%(objectname)%00%(objecttype)%00%(taggername)%00%(taggeremail)%00%(contents)%01', 'refs/tags']);
  if (!tags.ok) throw cannot(`read the tags (${tags.err})`);
  const sent = new Set(refs);
  const known = new Set(extra);
  for (const rec of tags.out.split('\x01')) {
    const [ref, obj, type, tagger, taggerEmail, body] = rec.replace(/^\n+/, '').split('\0');
    if (!ref || type !== 'tag' || known.has(obj)) continue;
    const name = ref.replace(/^refs\/tags\//, '');
    if (![ref, name, `tags/${name}`, obj].some((x) => sent.has(x))) continue;
    personHits(tagger || '', (taggerEmail || '').replace(/^<|>$/g, ''), `tag ${name} tagger`, null, c, hits);
    for (const l of (body || '').split('\n')) lineHits(l.replace(/\r$/, ''), `tag ${name} message`, null, c, hits, { transcript: true });
  }
  return { commits: commits.length, hits };
}

// The scan of what a push of refs to remote sends, cached per (repository, refs, remote, extra)
// for the life of the process: { commits, hits, notices, error }. error is a reason (it reads
// after "cannot ") or null; on an error commits is 0 and hits is empty.
const cache = new Map();
function scanPush(g, refs, remote, privacy, extra) {
  const more = extra || [];
  const key = JSON.stringify([g.key, refs, remote, more]);
  if (!cache.has(key)) {
    let r;
    try {
      const c = scanSetup(privacy);
      r = Object.assign(computePush(g, refs, remote, more, c), { notices: c.notices, error: null });
    } catch (e) {
      if (!(e instanceof ScanError)) throw e;
      r = { commits: 0, hits: [], notices: [], error: e.message };
    }
    cache.set(key, r);
  }
  return cache.get(key);
}

// The --text scan: each line of text as an added line under <file>:<n>. { lines, hits, notices, error }.
function scanText(text, file, privacy) {
  let c;
  try { c = scanSetup(privacy); } catch (e) {
    if (!(e instanceof ScanError)) throw e;
    return { lines: 0, hits: [], notices: [], error: e.message };
  }
  const all = text.replace(/^\uFEFF/, '').split('\n');
  if (all.length && all[all.length - 1] === '') all.pop();
  const hits = [];
  all.forEach((l, i) => lineHits(l.replace(/\r$/, ''), `${file}:${i + 1}`, null, c, hits, { transcript: true }));
  return { lines: all.length, hits, notices: c.notices, error: null };
}

module.exports = { scanPush, scanText, pushSources, pushRemote, gitRead, context, ScanError };

// ---------- CLI ----------
const USAGE = 'usage: node .claude/hooks/privacy-scan.js [--remote <name> | --no-remote] [--json] [<ref> ...] | ' +
  '--pre-push <remote-name> <remote-url> | --text <file> [--json]';

function parseArgs(argv) {
  const o = { json: false, remote: undefined, noRemote: false, prePush: null, text: null, refs: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--json') o.json = true;
    else if (a === '--no-remote') o.noRemote = true;
    else if (a === '--remote' && argv[i + 1] !== undefined) o.remote = argv[++i];
    else if (a === '--pre-push' && argv[i + 1] !== undefined && argv[i + 2] !== undefined) o.prePush = [argv[++i], argv[++i]];
    else if (a === '--text' && argv[i + 1] !== undefined) o.text = argv[++i];
    else if (a.startsWith('-')) return null;
    else o.refs.push(a);
  }
  const modes = [o.prePush !== null, o.text !== null, o.remote !== undefined || o.noRemote || o.refs.length > 0];
  if (modes.filter(Boolean).length > 1 || (o.remote !== undefined && o.noRemote) || (o.prePush && o.json)) return null;
  return o;
}

// Prints a result and returns the exit code. r: { count, unit, hits, notices, error }.
function emit(r, json) {
  for (const n of r.notices) process.stderr.write(`privacy-scan: ${n}\n`);
  const code = r.error ? 2 : r.hits.length ? 1 : 0;
  if (json) {
    const hits = r.hits.map((h) => ({ class: h.class, where: h.where, match: h.match, commit: h.commit || null }));
    process.stdout.write(`${JSON.stringify({ commits: r.error ? 0 : r.count, hits: r.error ? [] : hits, error: r.error || null })}\n`);
  } else if (!r.error) {
    const lines = r.hits.map((h) => `HIT ${h.class} ${h.where}: ${h.match}\n`).join('');
    const last = r.hits.length ? `privacy-scan: ${r.hits.length} hits in ${r.count} ${r.unit}` : `privacy-scan: clean, ${r.count} ${r.unit}`;
    process.stdout.write(`${lines}${last}\n`);
  }
  if (r.error) process.stderr.write(`privacy-scan: cannot ${r.error}\n`);
  return code;
}

function main() {
  const o = parseArgs(process.argv.slice(2));
  if (!o) return emit({ notices: [], hits: [], error: `read the arguments (${USAGE})` }, process.argv.includes('--json'));
  const loaded = require('./policy').load();
  if (loaded.error) return emit({ notices: [], hits: [], error: `load the policy (${loaded.error})` }, o.json);
  const privacy = loaded.policy.privacy;
  if (o.text !== null) {
    let text;
    try { text = fs.readFileSync(o.text, 'utf8'); } catch (e) {
      return emit({ notices: [], hits: [], error: `read ${o.text} (${e.code || e.message})` }, o.json);
    }
    const r = scanText(text, o.text, privacy);
    return emit({ count: r.lines, unit: 'lines', hits: r.hits, notices: r.notices, error: r.error }, o.json);
  }
  const g = context(process.cwd());
  let refs;
  let remote;
  const extra = [];
  try {
    if (o.prePush) {
      // git's lines: <local ref> <local sha> <remote ref> <remote sha>. The local sha is what goes
      // out (a deletion's is all zeros and sends nothing); a remote sha the remote already has
      // bounds the range.
      refs = [];
      for (const l of fs.readFileSync(0, 'utf8').split('\n')) {
        const w = l.trim().split(/\s+/);
        if (w.length < 4) continue;
        if (!/^[0-9a-f]{7,}$/i.test(w[1]) || !/^[0-9a-f]{7,}$/i.test(w[3])) throw cannot(`read the pre-push line ${JSON.stringify(l.trim().slice(0, 100))}`);
        if (ZEROS.test(w[1])) continue;
        if (!refs.includes(w[1])) refs.push(w[1]);
        if (!ZEROS.test(w[3]) && !extra.includes(w[3])) extra.push(w[3]);
      }
      remote = pushRemote(g, [o.prePush[0]]);
    } else {
      refs = o.refs.length ? o.refs : ['HEAD'];
      remote = o.noRemote ? null : pushRemote(g, o.remote !== undefined ? [o.remote] : []);
    }
  } catch (e) {
    if (!(e instanceof ScanError)) throw e;
    return emit({ notices: [], hits: [], error: e.message }, o.json);
  }
  const r = scanPush(g, refs, remote, privacy, extra);
  return emit({ count: r.commits, unit: 'commits', hits: r.hits, notices: r.notices, error: r.error }, o.json);
}

if (require.main === module) {
  // A crash is "cannot compute" (exit 2), never "hits" (exit 1) and never a pass.
  const crash = (e) => {
    process.stderr.write(`privacy-scan: cannot run (${e && e.message ? e.message : String(e)})\n`);
    process.exit(2);
  };
  process.on('uncaughtException', crash);
  try { process.exitCode = main(); } catch (e) { crash(e); }
}
