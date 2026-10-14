#!/usr/bin/env python3
"""Writes NOTICES.md: third-party licences for everything Dystopo depends on or bundles.

Inputs:
  - the dependency licence reports that `./gradlew check` (licenseCheck, buildSrc/.../LicenseCheckTask.kt)
    writes to <module>/build/reports/licenses/licenses.json (a CSV with artifact,license[,module] columns
    is read too, for reports made by other tools);
  - shared/src/commonMain/resources/sources.json, the data source registry (data and font licences);
  - the bundled Noto Sans fonts (serverlib/src/main/resources/fonts/OFL.txt);
  - the JavaScript libraries the browser pages load from unpkg (tools/demo/index.html and serverlib resources).

Usage (repo root, after `./gradlew check` with the Android SDK configured so :androidApp reports too):
  python -I tools/notices.py                 # writes NOTICES.md
  python -I tools/notices.py --check         # exit 1 if NOTICES.md is not what this would write
Prints one JSON summary line to stdout.
"""
import argparse
import csv
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

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


def render(paths, deps, sources, js):
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
    ofl = os.path.join(ROOT, 'serverlib', 'src', 'main', 'resources', 'fonts', 'OFL.txt')
    copyright_line = ''
    if os.path.exists(ofl):
        for line in open(ofl, encoding='utf-8'):
            if line.strip().lower().startswith('copyright'):
                copyright_line = line.strip()
                break
    w('- Noto Sans Regular and Bold, and Noto Sans Hebrew, Arabic, Thai and Georgian Bold (T92: the second name line),')
    w('  in `serverlib/src/main/resources/fonts/`, used to draw text in rendered PNGs:')
    w('  SIL Open Font License 1.1, full text in `serverlib/src/main/resources/fonts/OFL.txt`.' + (' ' + copyright_line if copyright_line else ''))
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
    text = render(paths, deps, sources, js)
    out = os.path.join(ROOT, args.out)
    modules = sorted({m for a in deps.values() for m in a['modules']})
    summary = {'ok': True, 'reports': len(paths), 'modules': modules, 'artifacts': len(deps),
               'licences': len({a['license'] for a in deps.values()}), 'dataSources': len(sources),
               'jsLibraries': len(js), 'out': args.out}
    if ':androidApp' not in modules:
        summary['warning'] = 'no :androidApp report (Android SDK not configured?); the app dependencies are missing'
    if args.check:
        current = open(out, encoding='utf-8').read() if os.path.exists(out) else ''
        summary['upToDate'] = current == text
        summary['ok'] = summary['upToDate']
        print(json.dumps(summary))
        return 0 if summary['ok'] else 1
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print(json.dumps(summary))
    return 0


if __name__ == '__main__':
    sys.exit(main())
