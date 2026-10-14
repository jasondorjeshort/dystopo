#!/usr/bin/env python3
"""Writes NOTICES.md: third-party licences for everything Dystopo depends on or bundles.

Inputs:
  - the dependency licence reports that `./gradlew check` (licenseCheck, buildSrc/.../LicenseCheckTask.kt)
    writes to <module>/build/reports/licenses/licenses.json (a CSV with artifact,license[,module] columns
    is read too, for reports made by other tools);
  - shared/src/commonMain/resources/sources.json, the data source registry (data and font licences);
  - the bundled Noto Sans fonts (serverlib/src/main/resources/fonts/*.ttf): each font's copyright notice and
    licence come from its name table, and the notice block at the top of fonts/OFL.txt is written from them
    (T97: the Hebrew, Arabic, Thai and Georgian fonts carried their notices only in the name tables);
  - the JavaScript libraries the browser pages load from unpkg (tools/demo/index.html and serverlib resources).

Usage (repo root, after `./gradlew check` with the Android SDK configured so :androidApp reports too):
  python -I tools/notices.py                 # writes NOTICES.md (and the notice block of fonts/OFL.txt when it differs)
  python -I tools/notices.py --check         # exit 1 if NOTICES.md or fonts/OFL.txt is not what this would write
Prints one JSON summary line to stdout. A font without a copyright notice or without the SIL Open Font License in its
name table fails the run (`fontProblems`).
"""
import argparse
import csv
import glob
import json
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FONT_DIR = os.path.join('serverlib', 'src', 'main', 'resources', 'fonts')
# The line of fonts/OFL.txt where the licence starts; what is above it is the generated notice block.
OFL_LICENCE_START = 'This Font Software is licensed under'

# Licences of the browser libraries the demo pages load from a CDN at runtime (not bundled in any artifact).
JS_LICENSES = {
    'maplibre-gl': ('BSD-3-Clause', 'https://github.com/maplibre/maplibre-gl-js/blob/main/LICENSE.txt'),
    'pmtiles': ('BSD-3-Clause', 'https://github.com/protomaps/PMTiles/blob/main/LICENSE'),
    'maplibre-contour': ('BSD-3-Clause', 'https://github.com/onthegomap/maplibre-contour/blob/main/LICENSE'),
}

LICENSE_URLS = {
    'Apache-2.0': 'https://www.apache.org/licenses/LICENSE-2.0',
    'MIT': 'https://opensource.org/license/mit',
    'BSD-2-Clause': 'https://opensource.org/license/bsd-2-clause',
    'BSD-3-Clause': 'https://opensource.org/license/bsd-3-clause',
    'EPL-1.0': 'https://www.eclipse.org/legal/epl-v10.html',
    'EPL-2.0': 'https://www.eclipse.org/legal/epl-2.0/',
    'ISC': 'https://opensource.org/license/isc-license-txt',
    '0BSD': 'https://opensource.org/license/0bsd',
    'OFL-1.1': 'https://openfontlicense.org/open-font-license-official-text/',
}


def label(lic):
    return lic.get('id') or lic.get('name') or 'unknown'


def read_json_report(path):
    d = json.load(open(path, encoding='utf-8'))
    module = d.get('module') or os.path.basename(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(path)))))
    rows = []
    for a in d.get('artifacts', []):
        lics = a.get('licenses') or []
        declared = [label(l) for l in lics]
        choice = a.get('choice')
        if choice and 'best chosen:' in choice:
            chosen = choice.split('best chosen:', 1)[1].strip()
            chosen = re.sub(r'\s*\[[a-z]+\].*$', '', chosen)
            # Prefer the declared label that the chosen name belongs to (an id when there is one).
            for l in lics:
                if chosen in (l.get('id'), l.get('name')):
                    chosen = label(l)
                    break
        elif len(declared) > 1:
            chosen = ' OR '.join(declared)
        else:
            chosen = declared[0] if declared else 'unknown'
        rows.append({'artifact': a['artifact'], 'license': chosen, 'declared': declared,
                     'category': a.get('category'), 'module': module})
    return rows


def read_csv_report(path):
    module = ':' + os.path.relpath(path, ROOT).split(os.sep)[0]
    rows = []
    with open(path, encoding='utf-8', newline='') as f:
        for r in csv.DictReader(f):
            art = r.get('artifact') or r.get('dependency') or ''
            lic = r.get('license') or r.get('licenses') or 'unknown'
            if art:
                rows.append({'artifact': art, 'license': lic, 'declared': [lic], 'category': r.get('category'),
                             'module': r.get('module') or module})
    return rows


def dependencies(report_glob):
    paths = sorted(glob.glob(os.path.join(ROOT, report_glob)))
    by_artifact = {}
    for p in paths:
        rows = read_json_report(p) if p.endswith('.json') else read_csv_report(p)
        for r in rows:
            e = by_artifact.setdefault(r['artifact'], dict(r, modules=set()))
            e['modules'].add(r['module'])
    return paths, by_artifact


def ttf_names(path):
    """The name-table strings of a TrueType/OpenType font as {name id: text} (Windows entries, else Macintosh Roman)."""
    data = open(path, 'rb').read()
    count = struct.unpack('>H', data[4:6])[0]
    for i in range(count):
        tag, _, offset, _length = struct.unpack('>4sIII', data[12 + 16 * i:28 + 16 * i])
        if tag == b'name':
            break
    else:
        return {}
    _fmt, n, strings = struct.unpack('>HHH', data[offset:offset + 6])
    windows, mac = {}, {}
    for i in range(n):
        platform, _enc, lang, name_id, length, start = struct.unpack('>6H', data[offset + 6 + 12 * i:offset + 18 + 12 * i])
        raw = data[offset + strings + start:offset + strings + start + length]
        if platform in (0, 3):
            if name_id not in windows or lang == 0x409:
                windows[name_id] = raw.decode('utf-16-be')
        elif platform == 1 and name_id not in mac:
            mac[name_id] = raw.decode('mac_roman')
    return {**mac, **windows}


def bundled_fonts():
    """[{file, label, copyright, ofl}] for each TTF in the fonts directory, by file name. The label comes from the
    file name (`NotoSansHebrew-Bold.ttf` is `Noto Sans Hebrew Bold`), since name tables disagree about families."""
    fonts = []
    for path in sorted(glob.glob(os.path.join(ROOT, FONT_DIR, '*.ttf'))):
        names = ttf_names(path)
        file = os.path.basename(path)
        label = re.sub(r'(?<=[a-z])(?=[A-Z])', ' ', os.path.splitext(file)[0].replace('-', ' '))
        fonts.append({'file': file, 'label': label, 'copyright': names.get(0, '').strip(),
                      'ofl': 'SIL Open Font License' in names.get(13, '')})
    return fonts


def font_groups(fonts):
    """The fonts grouped by copyright notice, in file-name order of each group's first font: [(notice, [font])]."""
    groups = {}
    for f in fonts:
        groups.setdefault(f['copyright'], []).append(f)
    return list(groups.items())


def font_problems(fonts):
    problems = []
    if not fonts:
        problems.append('no fonts found in ' + FONT_DIR)
    for f in fonts:
        if not f['copyright'].lower().startswith('copyright'):
            problems.append('%s has no copyright notice in its name table' % f['file'])
        if not f['ofl']:
            problems.append('%s does not say it is under the SIL Open Font License (name table entry 13)' % f['file'])
    return problems


def ofl_text(current, fonts):
    """fonts/OFL.txt with its notice block (everything above the licence) written from the fonts' name tables."""
    start = current.index(OFL_LICENCE_START)
    head = ["The fonts in this directory and their copyright notices (the notice is also in each font's name table;",
            "this block is written by tools/notices.py; the licence below it is the SIL OFL 1.1 as the fonts' authors ship it).", '']
    for notice, group in font_groups(fonts):
        head += [', '.join(f['file'] for f in group), notice, '']
    return '\n'.join(head) + '\n' + current[start:]


def js_libraries():
    found = {}
    files = [os.path.join(ROOT, 'tools', 'demo', 'index.html')]
    files += glob.glob(os.path.join(ROOT, 'serverlib', 'src', 'main', 'resources', '**', '*.html'), recursive=True)
    for f in files:
        if not os.path.exists(f):
            continue
        for m in re.finditer(r'https://unpkg\.com/([a-z0-9@/._-]+?)@([0-9][0-9A-Za-z.\-]*)/', open(f, encoding='utf-8').read()):
            found.setdefault(m.group(1), set()).add(m.group(2))
    return found


def render(paths, deps, sources, js, fonts):
    out = []
    w = out.append
    w('# Third-party notices')
    w('')
    w('Dystopo itself is licensed under the GNU General Public License v3 (see [LICENSE](LICENSE)). It uses the')
    w('third-party software and data below under their own licences. This file is generated by')
    w('`python -I tools/notices.py` from the dependency licence reports that `./gradlew check` writes')
    w('(`<module>/build/reports/licenses/licenses.json`) and from the data source registry')
    w('(`shared/src/commonMain/resources/sources.json`); do not edit it by hand.')
    w('')
    w('Modules: `androidApp` is the Android app; `server` and `serverlib` the standalone server; `devtool` the')
    w('command-line tool; `shared` the core every one of them includes. `tilebuild` is build-side tooling that')
    w('turns OpenStreetMap extracts into map data; it is not part of the app, and it is the only module allowed')
    w('LGPL libraries (each stays a separate, replaceable jar).')
    w('')
    w('## Software libraries')
    w('')
    groups = {}
    for a in deps.values():
        groups.setdefault(a['license'], []).append(a)
    w('%d artifacts from the runtime classpaths of %d modules, grouped by the licence under which we use them.' % (len(deps), len(paths)))
    w('')
    w('| Licence | Artifacts |')
    w('|---|---|')
    order = sorted(groups, key=lambda k: (-len(groups[k]), k.lower()))
    for k in order:
        w('| %s | %d |' % (k, len(groups[k])))
    w('')
    for k in order:
        url = LICENSE_URLS.get(k)
        w('### %s' % k)
        w('')
        if url:
            w('Licence text: %s' % url)
            w('')
        for a in sorted(groups[k], key=lambda a: a['artifact']):
            mods = ', '.join(m.lstrip(':') for m in sorted(a['modules']))
            note = ''
            if len(a['declared']) > 1:
                note = '; declares: ' + ', '.join(a['declared'])
            w('- `%s` (%s%s)' % (a['artifact'], mods, note))
        w('')
    w('## Browser libraries')
    w('')
    w('The browser demo page (`tools/demo/index.html`) and the pages `devtool serve` and the server send load these')
    w('from unpkg.com at run time; they are not bundled in any Dystopo artifact.')
    w('')
    for name in sorted(js):
        lic, url = JS_LICENSES.get(name, ('see the package', 'https://www.npmjs.com/package/' + name))
        w('- `%s@%s`: %s (%s)' % (name, ', '.join(sorted(js[name])), lic, url))
    w('')
    w('## Fonts')
    w('')
    w('The Noto Sans fonts in `serverlib/src/main/resources/fonts/` draw text in rendered PNGs (the Hebrew, Arabic, Thai')
    w('and Georgian ones for the second name line, T92). They are under the SIL Open Font License 1.1, full text in')
    w("`serverlib/src/main/resources/fonts/OFL.txt`; each font's copyright notice, as in its name table:")
    w('')
    for notice, group in font_groups(fonts):
        w('- %s: %s' % (' and '.join(f['label'] for f in group), notice))
    w('')
    w('## Map and elevation data')
    w('')
    w('Map data keeps its sources\' licences. Tiles, packs and rendered images record the sources that contributed in')
    w('their metadata, and the app and the web pages show the attribution below for what is on screen.')
    w('')
    w('| Source | Kind | Provider | Licence | Attribution |')
    w('|---|---|---|---|---|')
    for s in sources:
        lic = s.get('licenseName') or s.get('license')
        if s.get('licenseUrl'):
            lic = '[%s](%s)' % (lic, s['licenseUrl'])
        cells = [s.get('name', s['id']), s.get('kind', ''), s.get('provider', ''), lic, s.get('attribution', '')]
        w('| ' + ' | '.join(str(c).replace('|', '/').replace('\n', ' ') for c in cells) + ' |')
    w('')
    return '\n'.join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--reports', default=os.path.join('*', 'build', 'reports', 'licenses', 'licenses.*'),
                    help='glob, relative to the repo root, of licence reports (.json or .csv)')
    ap.add_argument('--sources', default=os.path.join('shared', 'src', 'commonMain', 'resources', 'sources.json'))
    ap.add_argument('--out', default='NOTICES.md')
    ap.add_argument('--check', action='store_true', help='compare with the existing file instead of writing it')
    args = ap.parse_args()

    paths, deps = dependencies(args.reports)
    if not paths:
        print(json.dumps({'ok': False, 'error': 'no licence reports found; run ./gradlew check first'}))
        return 1
    sources = json.load(open(os.path.join(ROOT, args.sources), encoding='utf-8'))['sources']
    js = js_libraries()
    fonts = bundled_fonts()
    text = render(paths, deps, sources, js, fonts)
    out = os.path.join(ROOT, args.out)
    ofl_path = os.path.join(ROOT, FONT_DIR, 'OFL.txt')
    ofl_current = open(ofl_path, encoding='utf-8', newline='').read() if os.path.exists(ofl_path) else ''
    ofl_new = ofl_text(ofl_current, fonts) if OFL_LICENCE_START in ofl_current else None
    modules = sorted({m for a in deps.values() for m in a['modules']})
    summary = {'ok': True, 'reports': len(paths), 'modules': modules, 'artifacts': len(deps),
               'licences': len({a['license'] for a in deps.values()}), 'dataSources': len(sources),
               'jsLibraries': len(js), 'fonts': len(fonts), 'out': args.out}
    problems = font_problems(fonts)
    if ofl_new is None:
        problems.append('%s lacks the licence text (a line starting "%s")' % (os.path.join(FONT_DIR, 'OFL.txt'), OFL_LICENCE_START))
    if problems:
        summary.update(ok=False, fontProblems=problems)
        print(json.dumps(summary))
        return 1
    if ':androidApp' not in modules:
        summary['warning'] = 'no :androidApp report (Android SDK not configured?); the app dependencies are missing'
    if args.check:
        current = open(out, encoding='utf-8').read() if os.path.exists(out) else ''
        summary['noticesUpToDate'] = current == text
        summary['oflUpToDate'] = ofl_current == ofl_new
        summary['upToDate'] = summary['noticesUpToDate'] and summary['oflUpToDate']
        summary['ok'] = summary['upToDate']
        print(json.dumps(summary))
        return 0 if summary['ok'] else 1
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    if ofl_new != ofl_current:
        with open(ofl_path, 'w', encoding='utf-8', newline='') as f:
            f.write(ofl_new)
        summary['oflWritten'] = True
    print(json.dumps(summary))
    return 0


if __name__ == '__main__':
    sys.exit(main())
