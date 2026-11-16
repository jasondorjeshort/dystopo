"""T297: an alpha's release notes with the mechanical sections written, the prose left to the coordinator.

    python -I tools/release-notes.py <tag> --sweep <folder> [--changes <changes.json>] [--changelog CHANGELOG.md]
                                     [--prev <tag>] [--out <notes.md>]
    python -I tools/release-notes.py <tag> --check <notes.md> [the same inputs]

Reads the draft's assets (`gh release view <tag> --json assets`) and its `SHA256SUMS.txt` (`gh release download`), the
sweep's `summary.json` (`changes`: count, paired, timing, added; `retried` and `failed` as hints), or `--changes` in
place of the summary's `changes` (a sheet rebuilt without some pairs; T295's left-out and unknown lists, when present,
become hints), the CHANGELOG section `## <tag>` (else `## Unreleased`), and the previous release's notes (the newest
published tag below this one, or `--prev`). It prints the notes as the published ones read, with two marked blocks:

    <!-- BEGIN what-changed ... -->   the opening paragraph's last sentences and the "What changed" section,
    <!-- END what-changed -->         prefilled with the sheet's line and the CHANGELOG items
    <!-- BEGIN known-limits ... -->   the previous notes' limits carried forward with a "Still from <prev>:" prefix,
    <!-- END known-limits -->         and the draft-check line

Everything else (the opening's fixed sentences, Install on a phone, Files, Checksums and licences, the certificate
line) is written whole. The checksums are the draft's `SHA256SUMS.txt` verbatim, checked against the assets' digests.
The output goes through `java tools/PrivacyCheck.java scan`; a hit exits 1 (the notes are still written to `--out`).

`--check <notes.md>`: the coordinator's finished notes (or a published body) against the same template: they must
equal it outside the two blocks, carry no marker or hint line, and pass the scan; prints JSON and exits 0, 1 (privacy) or 4.

Offline inputs, for tests and for a draft read by hand: `--release-json <file>` (the `gh release view --json assets`
output), `--sums <SHA256SUMS.txt>`, `--prev-notes <file>`, `--privacy-local <file>` (PrivacyCheck's `--local`).
Exit codes: 0 written, 1 a privacy hit, 2 usage or a missing input, 3 a checksum that disagrees with an asset's digest,
4 `--check` found text outside the blocks that differs from the template, or a marker left in.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(here)
DEFAULT_REPO = "jasondorjeshort/dystopo"
BLOCKS = ("what-changed", "known-limits")
HINT = {
    "what-changed": "the opening's last sentences, then the section; keep the sheet's line, rewrite the items for a "
                    "phone user, delete these two marker lines",
    "known-limits": "strike what landed, add what this build leaves, delete these two marker lines",
}


class Fail(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def version_key(tag):
    m = re.match(r"v?(\d+)\.(\d+)\.(\d+)", tag)
    return tuple(int(g) for g in m.groups()) if m else None


def plain(tag):
    """v0.1.31-alpha -> ('0.1.31', 'alpha')."""
    core = tag[1:] if tag.startswith("v") else tag
    num, _, channel = core.partition("-")
    return num, channel


def count(x):
    if isinstance(x, list):
        return len(x)
    try:
        return int(x or 0)
    except (TypeError, ValueError):
        return 0


def gh(*args):
    try:
        p = subprocess.run(["gh", *args], capture_output=True, text=True, encoding="utf-8", timeout=180)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise Fail(2, f"gh {' '.join(args)}: {e}")
    if p.returncode != 0:
        raise Fail(2, f"gh {' '.join(args)}: {p.stderr.strip()}")
    return p.stdout


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def read_json(path):
    with open(path, encoding="utf-8-sig") as f:
        return json.load(f)


# ---------------------------------------------------------------- inputs

def changelog_items(text, tag):
    """The `- ` items of `## <tag>` (else `## Unreleased`), and the heading used."""
    lines = text.splitlines()
    for want in (tag, "Unreleased"):
        start = next((i for i, l in enumerate(lines) if re.match(r"##\s+" + re.escape(want) + r"\b", l)), None)
        if start is None:
            continue
        items = []
        for l in lines[start + 1:]:
            if l.startswith("## "):
                break
            if l.startswith("- "):
                items.append(l[2:].strip())
            elif items and l.startswith("  ") and l.strip():
                items[-1] += " " + l.strip()
        if items or want == tag:
            return items, want
    return [], None


def section(body, heading):
    """The lines of a `## <heading>...` section of a notes body, up to the next `## `."""
    lines = body.replace("\r\n", "\n").split("\n")
    start = next((i for i, l in enumerate(lines) if l.startswith("## " + heading)), None)
    if start is None:
        return []
    out = []
    for l in lines[start + 1:]:
        if l.startswith("## "):
            break
        out.append(l)
    return out


def carried_limits(prev_body, prev_num):
    """The previous notes' Known limits, each with a "Still from" prefix, the draft-check line left out."""
    items = []
    for l in section(prev_body or "", "Known limits"):
        if l.startswith("- "):
            items.append(l[2:].strip())
        elif items and l.strip() and not l.startswith("#"):
            items[-1] += " " + l.strip()
    out = []
    for it in items:
        if it.startswith("Tested on"):
            continue
        if not it.startswith("Still from"):
            if len(it) > 1 and it[0].isupper() and not it[1].isupper():
                it = it[0].lower() + it[1:]
            it = f"Still from {prev_num}: {it}"
        out.append(it)
    return out


def parse_sums(text):
    rows = []
    for l in text.replace("\r\n", "\n").split("\n"):
        m = re.match(r"([0-9a-fA-F]{64})\s+\*?(.+)$", l.strip())
        if m:
            rows.append((m.group(1).lower(), m.group(2).strip()))
    return rows


def check_sums(rows, assets):
    """Errors for a checksum that disagrees with its asset's digest; warnings for an asset the file does not list."""
    errors, warnings = [], []
    digests = {a["name"]: (a.get("digest") or "") for a in assets}
    listed = {name for _, name in rows}
    for h, name in rows:
        if name not in digests:
            warnings.append(f"SHA256SUMS.txt lists {name}, which is not an asset of the draft")
            continue
        d = digests[name]
        if d.startswith("sha256:") and d[7:].lower() != h:
            errors.append(f"SHA256SUMS.txt disagrees with the draft's digest of {name}")
    for a in assets:
        n = a["name"]
        if n not in listed and n != "SHA256SUMS.txt" and not n.endswith("-changes.png"):
            warnings.append(f"the asset {n} is not in SHA256SUMS.txt")
    return errors, warnings


def sweep_inputs(sweep, changes_file):
    """(changes dict or None, hints) from the sweep's summary.json and an optional rebuilt changes.json."""
    hints, changes = [], None
    summary = {}
    if sweep:
        path = os.path.join(sweep, "summary.json")
        if not os.path.isfile(path):
            raise Fail(2, f"no summary.json in {sweep}")
        summary = read_json(path)
        changes = summary.get("changes") if isinstance(summary.get("changes"), dict) else None
        failed = summary.get("failed") or []
        if failed:
            hints.append("the sweep's red parts: " + ", ".join(map(str, failed)))
        retried = summary.get("retried") or []  # T292, read by name
        if isinstance(retried, dict):
            retried = [dict(part=k, **(v if isinstance(v, dict) else {"reason": v})) for k, v in retried.items()]
        for r in retried:
            if isinstance(r, dict):
                hints.append(f"the sweep retried {r.get('part', '?')} ({r.get('reason', 'no reason given')})")
            else:
                hints.append(f"the sweep retried {r}")
    if changes_file:
        if not os.path.isfile(changes_file):
            raise Fail(2, f"no such changes file: {changes_file}")
        changes = read_json(changes_file)
    if changes:  # T295's lists, read by name, any spelling it lands with
        for key, what in (("leftOut", "left out of the sheet"), ("left_out", "left out of the sheet"),
                          ("excluded", "left out of the sheet"), ("unknown", "of unknown kind")):
            if count(changes.get(key)):
                hints.append(f"{count(changes.get(key))} pairs {what} (changes.json `{key}`)")
    return changes, hints


# ---------------------------------------------------------------- the notes

def sheet_line(tag, prev_num, changes, asset_names, repo):
    name = f"dystopo-{tag}-changes.png"
    if not changes:
        return f"There is no before-and-after sheet for this release (the sweep ran without a previous release's)."
    n, paired, timing, added = (count(changes.get(k)) for k in ("count", "paired", "timing", "added"))
    if n == 0 and added == 0:
        return f"No screen changed since {prev_num} ({paired} paired screenshots), so there is no before-and-after sheet."
    parts = [f"{n} of {paired} paired screenshots changed"]
    if timing:
        parts.append(f"{timing} more only by timing")
    if added:
        parts.append(f"{added} new")
    url = f"https://github.com/{repo}/releases/download/{tag}/{name}"
    return (f"Every screen that changed since {prev_num}, before and after, is on one sheet: [{name}]({url}) "
            f"({', '.join(parts)}).")


FILE_LINES = [
    (lambda t: f"dystopo-{t}-universal.apk", "the Android app (one file for every phone during alpha)."),
    (lambda t: f"dystopo-server-{t}.zip", "the standalone server (`bin/server`; Java 21). Configuration: docs/SERVER.md."),
    (lambda t: f"dystopo-devtool-{t}.zip",
     "the command-line tool (`bin/devtool`; Java 21), run from a checkout of the repo."),
]


def files_section(tag, asset_names):
    out, done = [], set()
    for name_of, text in FILE_LINES:
        n = name_of(tag)
        if n in asset_names:
            out.append(f"- `{n}`: {text}")
            done.add(n)
    meta = ["NOTICES.md", "SHA256SUMS.txt", "version.json"]
    if all(m in asset_names for m in meta):
        out.append("- `NOTICES.md`: third-party licences; `SHA256SUMS.txt`: checksums; `version.json`: tag, versionCode "
                   "and the universal APK's checksum (the app's update check reads it).")
        done.update(meta)
    sheet = f"dystopo-{tag}-changes.png"
    if sheet in asset_names:
        out.append(f"- `{sheet}`: the before-and-after sheet (not in the checksum file, which the build wrote before it "
                   "was attached).")
        done.add(sheet)
    tour = f"dystopo-{tag}-world-tour.png"
    if tour in asset_names:
        out.append(f"- `{tour}`: world tour contact sheet (z9).")
        done.add(tour)
    rest = [n for n in asset_names if n not in done]
    for n in rest:
        if n in meta:
            out.append(f"- `{n}`.")
        else:
            out.append(f"- `{n}`: (an asset this script has no line for)")
    return out, rest


def build(tag, prev_tag, asset_names, sums_text, changes, hints, items, prev_body, repo):
    num, channel = plain(tag)
    prev_num, _ = plain(prev_tag)
    sheet_name = f"dystopo-{tag}-changes.png"
    if changes and count(changes.get("count")) + count(changes.get("added")) > 0 and sheet_name not in asset_names:
        hints = hints + [f"{sheet_name} is not attached to the draft yet (gh release upload {tag} <file>)"]
    L = []
    L.append(f"Dystopo {num} {channel or 'release'}. Offline-first topographic maps and navigation for Android, with a "
             f"desktop toolchain and a self-hosted server. Installs over {prev_num} and keeps your data; a phone "
             f"running {prev_num} or older is offered this build by its own update check.")
    L.append(f"<!-- BEGIN what-changed: {HINT['what-changed']} -->")
    L.append("(One or two sentences: what this release is.)")
    L.append("")
    L.append(f"## What changed since {prev_num}")
    L.append("")
    L.append(sheet_line(tag, prev_num, changes, asset_names, repo) + " (What the sheet shows.)")
    for h in hints:
        L.append(f"<!-- hint: {h} -->")
    L.append("")
    for it in items:
        L.append(f"- {it}")
    if not items:
        L.append("- (No CHANGELOG items found.)")
    L.append("<!-- END what-changed -->")
    L.append("")
    L.append("## Install on a phone")
    L.append("")
    L.append(f"1. On {prev_num} or older, wait for \"Update to {tag[1:] if tag.startswith('v') else tag}\" on the map "
             f"or tap Settings > About and advanced > Check for updates, then Install. Otherwise download "
             f"`dystopo-{tag}-universal.apk` and open it; allow installs from your browser when asked, and if Play "
             f"Protect says it does not know the publisher, tap *More details*, then *Install anyway* (once).")
    L.append("2. On first launch tap **Download the map around me** or **Just look at the map**. No server is required. "
             "A Dystopo server can be set under Settings > Data and storage > Sources, or import map files you have.")
    L.append("3. Location permission is asked for when the app opens; notifications when you first record or download "
             "an area; installing updates when the first update is offered.")
    L.append("")
    L.append("## Known limits in this build")
    L.append("")
    L.append(f"<!-- BEGIN known-limits: {HINT['known-limits']} -->")
    for it in carried_limits(prev_body, prev_num):
        L.append(f"- {it}")
    L.append(f"- Tested on emulators and verified from this draft on an emulator (the upgrade from {prev_num} keeping "
             f"its data, the feature checks, the strings scan); the real-phone checks of this build are still to come.")
    L.append("<!-- END known-limits -->")
    L.append("")
    L.append("## Files")
    L.append("")
    files, _ = files_section(tag, asset_names)
    L.extend(files)
    L.append("")
    L.append("## Checksums and licences")
    L.append("")
    L.append("```")
    L.extend(f"{h}  {n}" for h, n in parse_sums(sums_text))
    L.append("```")
    L.append("")
    L.append("The release key's certificate is \"CN=Dystopo release, O=Dystopo\", fingerprint beginning `47:31:38:10`. "
             "Code is GPL-3.0 (see LICENSE in the repository). Map data keeps its sources' licences; see `NOTICES.md` "
             "and the About screen.")
    return "\n".join(L) + "\n"


BLOCK_RE = re.compile(r"<!-- BEGIN (?P<name>[\w-]+)[^>]*-->.*?<!-- END (?P=name) -->", re.S)


def template_regex(template):
    """A pattern that matches any notes equal to the template outside its blocks (whitespace at a block's edges free)."""
    pos, parts = 0, []
    for m in BLOCK_RE.finditer(template):
        parts.append(re.escape(template[pos:m.start()].strip()))
        parts.append(r"\s*(?P<" + m.group("name").replace("-", "_") + r">.*?)\s*")
        pos = m.end()
    parts.append(re.escape(template[pos:].strip()))
    return re.compile(r"^\s*" + "".join(parts) + r"\s*$", re.S)


def check(notes, template):
    notes = notes.replace("\r\n", "\n")
    result = {"markersLeft": bool(re.search(r"<!-- (BEGIN|END) (what-changed|known-limits)|<!-- hint:", notes))}
    m = template_regex(template).match(notes)
    result["matchesOutsideBlocks"] = bool(m)
    if m:
        result["blockLines"] = {k: len([l for l in v.split("\n") if l.strip()]) for k, v in m.groupdict().items()}
    else:  # the first line outside the blocks that differs, to say where
        fixed = [l for l in BLOCK_RE.sub("\0", template).split("\n") if l.strip() and "\0" not in l]
        have = set(notes.split("\n"))
        missing = [l for l in fixed if l not in have]
        result["firstMissingLine"] = missing[0][:160] if missing else None
    return result


# ---------------------------------------------------------------- privacy

def java():
    home = os.environ.get("JAVA_HOME")
    return os.path.join(home, "bin", "java") if home else "java"


def privacy(text, local=None):
    cmd = [java(), os.path.join("tools", "PrivacyCheck.java"), "scan"]
    if local:
        cmd += ["--local", local]
    try:
        p = subprocess.run(cmd, cwd=REPO, input=text, text=True, encoding="utf-8", capture_output=True, timeout=300)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise Fail(2, f"PrivacyCheck did not run: {e}")
    report = None
    for line in reversed(p.stdout.strip().splitlines()):
        try:
            report = json.loads(line)
            break
        except ValueError:
            pass
    if report is None:
        raise Fail(2, f"PrivacyCheck printed no report (exit {p.returncode}): {p.stderr.strip()[:300]}")
    hits = report.get("termHits", 0) + report.get("coordinateValueHits", 0) + report.get("coordinatePairHits", 0)
    return {"exit": p.returncode, "hits": hits, "localFile": report.get("localFile"),
            "privateTerms": report.get("privateTerms")}


# ---------------------------------------------------------------- main

def previous_tag(tag):
    rows = json.loads(gh("release", "list", "--limit", "100", "--json", "tagName,isDraft"))
    me = version_key(tag)
    older = [r["tagName"] for r in rows if not r.get("isDraft") and version_key(r["tagName"])
             and version_key(r["tagName"]) < me]
    if not older:
        raise Fail(2, f"no published release below {tag}; give --prev")
    return max(older, key=version_key)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Write an alpha's release notes with the mechanical sections filled.")
    ap.add_argument("tag")
    ap.add_argument("--sweep", help="the release sweep's folder (its summary.json)")
    ap.add_argument("--changes", help="a rebuilt changes.json, in place of the summary's changes")
    ap.add_argument("--changelog", default=os.path.join(REPO, "CHANGELOG.md"))
    ap.add_argument("--prev", help="the previous release's tag (default: the newest published one below <tag>)")
    ap.add_argument("--repo", default=DEFAULT_REPO)
    ap.add_argument("--out", help="write the notes here (default: stdout)")
    ap.add_argument("--check", help="finished notes to check against the template")
    ap.add_argument("--release-json", help="offline: `gh release view <tag> --json assets` output")
    ap.add_argument("--sums", help="offline: the draft's SHA256SUMS.txt")
    ap.add_argument("--prev-notes", help="offline: the previous release's notes")
    ap.add_argument("--privacy-local", help="PrivacyCheck's --local file (tests)")
    a = ap.parse_args(argv)
    try:
        if not version_key(a.tag):
            raise Fail(2, f"not a release tag: {a.tag}")
        if not a.sweep and not a.changes:
            raise Fail(2, "give --sweep (and --changes for a rebuilt sheet)")
        rel = read_json(a.release_json) if a.release_json else json.loads(gh("release", "view", a.tag, "--repo", a.repo,
                                                                             "--json", "assets"))
        assets = rel.get("assets") or []
        names = [x["name"] for x in assets]
        if a.sums:
            sums = read(a.sums)
        else:
            with tempfile.TemporaryDirectory() as d:
                gh("release", "download", a.tag, "--repo", a.repo, "-p", "SHA256SUMS.txt", "-D", d)
                sums = read(os.path.join(d, "SHA256SUMS.txt"))
        errors, warnings = check_sums(parse_sums(sums), assets)
        for w in warnings:
            print("warning: " + w, file=sys.stderr)
        if errors:
            raise Fail(3, "; ".join(errors))
        prev = a.prev or previous_tag(a.tag)
        if a.prev_notes:
            prev_body = read(a.prev_notes)
        else:
            prev_body = json.loads(gh("release", "view", prev, "--repo", a.repo, "--json", "body"))["body"]
        changes, hints = sweep_inputs(a.sweep, a.changes)
        items, heading = changelog_items(read(a.changelog), a.tag)
        if heading != a.tag:
            print(f"warning: CHANGELOG has no `## {a.tag}` section; " +
                  ("used Unreleased" if heading else "no items"), file=sys.stderr)
        notes = build(a.tag, prev, names, sums, changes, hints, items, prev_body, a.repo)
        if a.check:
            given = read(a.check)
            res = check(given, notes)
            res["privacy"] = privacy(given, a.privacy_local)
            print(json.dumps(res))
            if res["privacy"]["hits"]:
                return 1
            return 0 if res["matchesOutsideBlocks"] and not res["markersLeft"] else 4
        if a.out:
            with open(a.out, "w", encoding="utf-8", newline="\n") as f:
                f.write(notes)
        else:
            sys.stdout.write(notes)
        scan = privacy(notes, a.privacy_local)
        print(json.dumps({"tag": a.tag, "prev": prev, "assets": len(names), "checksums": len(parse_sums(sums)),
                          "changelogItems": len(items), "carriedLimits": len(carried_limits(prev_body, plain(prev)[0])),
                          "hints": len(hints), "warnings": len(warnings), "privacy": scan}), file=sys.stderr)
        if scan["hits"]:
            print("PRIVACY: the notes hold a private term or coordinate; fix the source (CHANGELOG, previous notes) "
                  "or the text", file=sys.stderr)
            return 1
        return 0
    except Fail as e:
        print(f"release-notes: {e}", file=sys.stderr)
        return e.code


if __name__ == "__main__":
    sys.exit(main())
