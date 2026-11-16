"""T297: tools/release-notes.py from a fixture draft, offline (no gh): the assets and checksums of a synthetic draft, a
sweep folder whose summary.json carries `changes`, `failed` and T292's `retried`, a rebuilt changes.json with a
left-out list (T295's, by name), a CHANGELOG section and the previous notes' Known limits.

The cases: the notes are written with the two marked blocks and everything else whole (checksums verbatim, the Files
lines, the install steps, the sheet's counts from --changes over the summary, the limits carried with "Still from");
absent `retried` and left-out lists are tolerated; no changes at all gives the no-sheet line; `--check` passes finished
notes that differ only inside the blocks and refuses a change outside them or a marker left in; a checksum that
disagrees with the draft's digest exits 3; a planted synthetic private term (PrivacyCheck's --local fixture file, never
a real term) exits 1.

    python -I tools/test_release_notes.py
"""
import importlib.util
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("release_notes", os.path.join(here, "release-notes.py"))
rn = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rn)

TAG, PREV = "v0.9.5-alpha", "v0.9.4-alpha"
TERM = "Qqvorbleplace"  # synthetic: in the fixture local file only
LOCAL = {"areas": {"stand-in": {"bbox": [-121.86, 46.78, -121.66, 46.93]}}, "privateTerms": [TERM]}
H = {n: (c * 64) for n, c in (("NOTICES.md", "a"), (f"dystopo-devtool-{TAG}.zip", "b"), (f"dystopo-server-{TAG}.zip", "c"),
                              (f"dystopo-{TAG}-universal.apk", "d"), ("version.json", "e"),
                              (f"dystopo-{TAG}-world-tour.png", "f"))}
SUMS = "".join(f"{h}  {n}\n" for n, h in H.items())
ASSETS = [{"name": n, "digest": "sha256:" + h} for n, h in H.items()] + [
    {"name": "SHA256SUMS.txt", "digest": "sha256:" + "0" * 64}, {"name": f"dystopo-{TAG}-changes.png", "digest": None}]
CHANGELOG = f"""# Changelog

## Unreleased

## {TAG} (2026-01-02)

### 2026-01-02

- T901: The map's scale bar reads in the chosen units on Mount Rainier
- T902: Tooling: the sweep draws one more sheet

## {PREV} (2026-01-01)

- T900: older
"""
PREV_NOTES = """Dystopo 0.9.4 alpha. Intro.

## Known limits in this build

- A trail without a grade reads Walk.
- Still from 0.9.2: in landscape the profile panel runs under the cutout.
- Tested on emulators and verified from this draft on an emulator; the real-phone checks are still to come.

## Files
"""


class ReleaseNotes(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(prefix="t297-")
        self.addCleanup(shutil.rmtree, self.d, True)
        self.files = {}
        for name, text in (("release.json", json.dumps({"assets": ASSETS})), ("SHA256SUMS.txt", SUMS),
                           ("CHANGELOG.md", CHANGELOG), ("prev.md", PREV_NOTES), ("local.json", json.dumps(LOCAL))):
            self.files[name] = self.write(name, text)
        self.sweep = os.path.join(self.d, "sweep")
        os.makedirs(self.sweep)
        self.summary({"ok": False, "failed": ["a11y"], "changes": {"count": 9, "paired": 100, "timing": 2, "added": 0},
                      "retried": [{"part": "features", "reason": "emulator-gone", "log": "device offline"}]})
        self.changes = self.write("changes.json", json.dumps(
            {"count": 7, "paired": 90, "timing": ["a", "b", "c"], "added": ["n1"], "leftOut": ["x1", "x2"]}))

    def write(self, name, text):
        p = os.path.join(self.d, name)
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        return p

    def summary(self, data):
        with open(os.path.join(self.sweep, "summary.json"), "w", encoding="utf-8") as f:
            json.dump(data, f)

    def run_script(self, *extra, changelog=None, sums=None, release=None):
        args = [TAG, "--sweep", self.sweep, "--prev", PREV, "--prev-notes", self.files["prev.md"],
                "--release-json", release or self.files["release.json"], "--sums", sums or self.files["SHA256SUMS.txt"],
                "--changelog", changelog or self.files["CHANGELOG.md"], "--privacy-local", self.files["local.json"],
                *extra]
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = rn.main(args)
        return code, out.getvalue(), err.getvalue()

    def test_notes_written_with_two_blocks(self):
        code, notes, err = self.run_script("--changes", self.changes)
        self.assertEqual(code, 0, err)
        for name in rn.BLOCKS:
            self.assertEqual(notes.count(f"<!-- BEGIN {name}"), 1)
            self.assertEqual(notes.count(f"<!-- END {name} -->"), 1)
        self.assertTrue(notes.startswith("Dystopo 0.9.5 alpha. Offline-first"))
        self.assertIn("Installs over 0.9.4 and keeps your data", notes)
        self.assertIn("```\n" + SUMS + "```", notes)  # verbatim, in the file's order
        self.assertIn(f"(7 of 90 paired screenshots changed, 3 more only by timing, 1 new)", notes)
        self.assertIn(f"releases/download/{TAG}/dystopo-{TAG}-changes.png", notes)
        self.assertIn("- T901: The map's scale bar reads in the chosen units on Mount Rainier", notes)
        self.assertIn("- Still from 0.9.4: a trail without a grade reads Walk.", notes)
        self.assertIn("- Still from 0.9.2: in landscape", notes)
        self.assertNotIn("Still from 0.9.4: Tested", notes)
        self.assertIn("hint: the sweep retried features (emulator-gone)", notes)
        self.assertIn("hint: the sweep's red parts: a11y", notes)
        self.assertIn("hint: 2 pairs left out of the sheet", notes)
        self.assertIn(f"- `dystopo-{TAG}-universal.apk`: the Android app", notes)
        self.assertIn(f"- `dystopo-{TAG}-changes.png`: the before-and-after sheet", notes)
        self.assertIn(f"- `dystopo-{TAG}-world-tour.png`: world tour contact sheet (z9).", notes)
        self.assertIn('wait for "Update to 0.9.5-alpha"', notes)
        self.assertLess(notes.index("## Install on a phone"), notes.index("## Known limits in this build"))
        self.assertLess(notes.index("## Known limits"), notes.index("## Files"))
        self.assertLess(notes.index("## Files"), notes.index("## Checksums and licences"))

    def test_summary_counts_and_absent_fields(self):
        self.summary({"ok": True, "changes": {"count": 9, "paired": 100, "timing": 0, "added": 0}})
        code, notes, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("(9 of 100 paired screenshots changed)", notes)
        self.assertNotIn("hint:", notes)

    def test_no_sheet(self):
        self.summary({"ok": True})
        code, notes, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("There is no before-and-after sheet", notes)

    def test_check_finished_notes(self):
        code, notes, _ = self.run_script("--changes", self.changes)
        self.assertEqual(code, 0)
        b = notes.index("<!-- BEGIN what-changed")
        e = notes.index("<!-- END what-changed -->") + len("<!-- END what-changed -->")
        finished = notes[:b].rstrip("\n") + " The scale bar follows your units.\n\n## What changed since 0.9.4\n\n- Hand.\n" + notes[e:]
        b = finished.index("<!-- BEGIN known-limits")
        e = finished.index("<!-- END known-limits -->") + len("<!-- END known-limits -->")
        finished = finished[:b] + "- One limit, by hand.\n" + finished[e:]
        code, out, err = self.run_script("--changes", self.changes, "--check", self.write("done.md", finished))
        self.assertEqual(code, 0, out + err)
        self.assertTrue(json.loads(out)["matchesOutsideBlocks"])
        outside = finished.replace("## Checksums and licences", "## Checksums")
        code, out, _ = self.run_script("--changes", self.changes, "--check", self.write("bad.md", outside))
        self.assertEqual(code, 4)
        self.assertFalse(json.loads(out)["matchesOutsideBlocks"])
        code, out, _ = self.run_script("--changes", self.changes, "--check", self.write("raw.md", notes))
        self.assertEqual(code, 4)
        self.assertTrue(json.loads(out)["markersLeft"])

    def test_checksum_disagrees(self):
        bad = self.write("bad-sums.txt", SUMS.replace("d" * 64, "9" * 64))
        code, _, err = self.run_script(sums=bad)
        self.assertEqual(code, 3)
        self.assertIn("disagrees", err)

    def test_planted_private_term_exits_nonzero(self):
        planted = self.write("planted.md", CHANGELOG.replace("Mount Rainier", "Mount Rainier and " + TERM))
        code, notes, err = self.run_script(changelog=planted)
        self.assertEqual(code, 1, err)
        self.assertIn("PRIVACY", err)
        self.assertNotIn(TERM, err)
        code, out, _ = self.run_script("--check", planted)
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
