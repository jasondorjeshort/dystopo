#!/usr/bin/env python3
"""Fails when user-visible text names another mapping product (T104).

The rule (CLAUDE.md > Rules, the owner 2026-10-13): what the app, the devtool, the server and the browser viewer show
or print names no other mapping software. Code comments, KDoc, docs, the README and the log may. So this scans text a
user can see, not the repo:
  - the string literals of the Kotlin sources of androidApp, devtool, serverlib, server and shared (main source sets
    only; comments are skipped, string templates are followed into their code);
  - the app's string resources (androidApp/src/main/res/values*/*.xml, element text);
  - the browser viewer (tools/demo/*.html and *.js): page text, the title/placeholder/alt/aria-label/label/value
    attributes, and the string literals of its scripts.
The names are in tools/user-text-names.txt (one per line, case-insensitive, whole words). A source line that must
name a product (a named integration, an attribution) carries `user-text-ok` in a comment on the same line.

Usage (repo root):
  python -I tools/user-text-check.py                    # JSON summary on stdout; exit 1 when a name is found
  python -I tools/user-text-check.py --root <checkout>  # another checkout (e.g. main, to see it fail there)
  python -I tools/user-text-check.py --out build/reports/user-text-check.json   # also write the summary there
Run by `./gradlew check` (the root project's userTextCheck task).
"""
import argparse
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from html.parser import HTMLParser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NAMES = os.path.join(ROOT, 'tools', 'user-text-names.txt')
EXEMPT = 'user-text-ok'

KOTLIN = [
    'androidApp/src/main/**/*.kt',
    'devtool/src/main/**/*.kt',
    'serverlib/src/main/**/*.kt',
    'server/src/main/**/*.kt',
    'shared/src/*Main/**/*.kt',
]
RESOURCES = ['androidApp/src/main/res/values*/*.xml']
VIEWER_HTML = ['tools/demo/**/*.html']
VIEWER_JS = ['tools/demo/**/*.js']
HTML_ATTRS = {'title', 'placeholder', 'alt', 'aria-label', 'label', 'value'}


def load_names(path):
    """[(name, compiled regex)] from the names file."""
    out = []
    with open(path, encoding='utf-8') as f:
        for line in f:
            name = line.strip()
            if not name or name.startswith('#'):
                continue
            body = r'\s+'.join(re.escape(part) for part in name.split())
            out.append((name, re.compile(r'(?<![A-Za-z0-9])' + body + r'(?![A-Za-z0-9])', re.IGNORECASE)))
    return out


# ---- Kotlin -------------------------------------------------------------------------------------------------------

def kotlin_literals(src):
    """(offset, text) of every string literal in Kotlin source [src], comments skipped, templates followed."""
    out = []
    n = len(src)

    def code(i, until_brace):
        """Scans code from i; returns the index after the closing brace when until_brace, else n."""
        depth = 0
        while i < n:
            c = src[i]
            if src.startswith('//', i):
                j = src.find('\n', i)
                i = n if j < 0 else j
            elif src.startswith('/*', i):
                i = block_comment(i)
            elif src.startswith('"""', i):
                i = string(i, raw=True)
            elif c == '"':
                i = string(i, raw=False)
            elif c == "'":
                i = char_literal(i)
            elif c == '{':
                depth += 1
                i += 1
            elif c == '}':
                if until_brace and depth == 0:
                    return i + 1
                depth -= 1
                i += 1
            else:
                i += 1
        return i

    def block_comment(i):
        depth = 0
        while i < n:
            if src.startswith('/*', i):
                depth += 1
                i += 2
            elif src.startswith('*/', i):
                depth -= 1
                i += 2
                if depth == 0:
                    return i
            else:
                i += 1
        return n

    def char_literal(i):
        j = i + 1
        if j < n and src[j] == '\\':
            j += 2
            while j < n and src[j] != "'" and src[j] != '\n':
                j += 1
        else:
            j += 1
        return j + 1 if j < n and src[j] == "'" else i + 1

    def string(i, raw):
        start = i
        i += 3 if raw else 1
        parts = []
        piece = i
        while i < n:
            if raw and src.startswith('"""', i):
                parts.append(src[piece:i])
                i += 3
                while i < n and src[i] == '"':  # """a""""" ends at the last quotes
                    i += 1
                break
            if not raw and src[i] == '"':
                parts.append(src[piece:i])
                i += 1
                break
            if not raw and src[i] == '\\':
                i += 2
                continue
            if not raw and src[i] == '\n':  # unterminated; give up on this line
                parts.append(src[piece:i])
                break
            if src.startswith('${', i):
                parts.append(src[piece:i])
                i = code(i + 2, until_brace=True)
                piece = i
                continue
            i += 1
        out.append((start, ' '.join(parts)))
        return i

    code(0, until_brace=False)
    return out


# ---- JavaScript and HTML ------------------------------------------------------------------------------------------

REGEX_BEFORE = set('(,=:[!&|?{};+-*%<>~^')


def js_literals(src):
    """(offset, text) of the string and template literals of JavaScript [src], comments and regex literals skipped."""
    out = []
    n = len(src)
    i = 0
    prev = ''  # last significant character, to tell a regex literal from a division
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c in '"\'`':
            j = i + 1
            while j < n and src[j] != c:
                if src[j] == '\\':
                    j += 1
                elif c != '`' and src[j] == '\n':
                    break
                j += 1
            out.append((i, src[i + 1:j]))
            i = j + 1
            prev = c
            continue
        if c == '/' and (prev == '' or prev in REGEX_BEFORE):
            j = i + 1
            in_class = False
            while j < n and src[j] != '\n':
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '[':
                    in_class = True
                elif src[j] == ']':
                    in_class = False
                elif src[j] == '/' and not in_class:
                    break
                j += 1
            i = j + 1
            prev = '/'
            continue
        if not c.isspace():
            prev = c
        i += 1
    return out


class ViewerHtml(HTMLParser):
    """Page text, a few attributes, and the scripts' literals of an HTML page, as (line, text)."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.texts = []
        self.in_script = False
        self.in_style = False
        self.script_start = None
        self.script_parts = []

    def handle_starttag(self, tag, attrs):
        line = self.getpos()[0]
        for k, v in attrs:
            if k in HTML_ATTRS and v:
                self.texts.append((line, v))
        if tag == 'script':
            self.in_script = True
            self.script_start = line
            self.script_parts = []
        elif tag == 'style':
            self.in_style = True

    def handle_endtag(self, tag):
        if tag == 'script' and self.in_script:
            self.in_script = False
            body = ''.join(self.script_parts)
            for offset, text in js_literals(body):
                self.texts.append((self.script_start + body.count('\n', 0, offset), text))
        elif tag == 'style':
            self.in_style = False

    def handle_data(self, data):
        if self.in_script:
            self.script_parts.append(data)
        elif not self.in_style and data.strip():
            self.texts.append((self.getpos()[0], data))


# ---- the check ----------------------------------------------------------------------------------------------------

def files(root, patterns):
    seen = []
    for p in patterns:
        for f in sorted(glob.glob(os.path.join(root, p), recursive=True)):
            if os.sep + 'build' + os.sep in f or f in seen:
                continue
            seen.append(f)
    return seen


def line_of(src, offset):
    return src.count('\n', 0, offset) + 1


def texts_of(path, kind):
    """(line, text) pairs of user-visible text in one file."""
    with open(path, encoding='utf-8-sig') as f:
        src = f.read()
    if kind == 'kotlin':
        return [(line_of(src, o), t) for o, t in kotlin_literals(src)], src
    if kind == 'js':
        return [(line_of(src, o), t) for o, t in js_literals(src)], src
    if kind == 'html':
        p = ViewerHtml()
        p.feed(src)
        p.close()
        return p.texts, src
    if kind == 'xml':
        out = []
        for el in ET.fromstring(src.encode('utf-8')).iter():
            for t in (el.text, el.tail):
                if t and t.strip():
                    out.append((0, t))
        # ElementTree has no line numbers: find each text's first line in the source.
        return [(line_of(src, max(0, src.find(t.strip()))), t) for _, t in out], src
    raise ValueError(kind)


def check(root, names):
    hits = []
    counts = {}
    scanned = 0
    for kind, patterns in (('kotlin', KOTLIN), ('xml', RESOURCES), ('html', VIEWER_HTML), ('js', VIEWER_JS)):
        for path in files(root, patterns):
            scanned += 1
            texts, src = texts_of(path, kind)
            lines = src.split('\n')
            counts[kind] = counts.get(kind, 0) + len(texts)
            for line, text in texts:
                for name, rx in names:
                    if not rx.search(text):
                        continue
                    source_line = lines[line - 1] if 0 < line <= len(lines) else ''
                    if EXEMPT in source_line:
                        continue
                    hits.append({
                        'file': os.path.relpath(path, root).replace(os.sep, '/'),
                        'line': line,
                        'name': name,
                        'text': ' '.join(text.split())[:160],
                    })
    return {'ok': not hits, 'files': scanned, 'texts': counts, 'names': len(names), 'hits': hits}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--root', default=ROOT, help='checkout to scan (default: this one)')
    ap.add_argument('--names', default=NAMES, help='names file (default: tools/user-text-names.txt)')
    ap.add_argument('--out', help='also write the JSON summary to this file')
    args = ap.parse_args()
    report = check(os.path.abspath(args.root), load_names(args.names))
    text = json.dumps(report)
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, 'w', encoding='utf-8') as f:
            f.write(text + '\n')
    print(text)
    for h in report['hits']:
        print(f"{h['file']}:{h['line']}: names {h['name']} in user-visible text: {h['text']}", file=sys.stderr)
    return 0 if report['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
