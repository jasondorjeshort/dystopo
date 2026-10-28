"""T154: merge-log.py offline, on a two-task board: the Status line after a middle task's Done-when and after the last
task's, with the file ending in a blank line, in one newline and in none; the log entry and the changelog line; and the
whole script in a scratch git repository, where a refused task leaves every file as it was.

    python -I tools/test_merge_log.py
"""
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("merge_log", os.path.join(here, "merge-log.py"))
ml = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ml)

BOARD = ("# Tasks\n\n## Wave 1\n\n"
         "### T1. First task\n**Goal:** one.\n**Owns:** a.\n**Done when:** a works.\n\n"
         "### T2. Second task\n**Goal:** two.\n**Owns:** b.\n**Done when:** b works.")
PLANNING = "# Planning\n\n## Log\n\n**2026-01-01: T0 merged: zero.** Text.\n\n" + ml.LOG_ANCHOR + " and more.\n"
CHANGELOG = "# Changelog\n\n## Unreleased\n\n(nothing yet)\n\n## v0.1.0\n\n- old\n"
S1, S2 = "**Status: merged (aaa1111).** One.", "**Status: merged (bbb2222).** Two."

# A middle task: the Status line goes between its Done-when and the blank line before the next task.
out, title = ml.add_status(BOARD + "\n", "T1", S1)
assert title == "T1. First task", title
assert "**Done when:** a works.\n" + S1 + "\n\n### T2." in out, out
# The last task, the file ending in one newline, in none, and in a blank line (the case that worked before T154).
for ending in ("\n", "", "\n\n"):
    out, title = ml.add_status(BOARD + ending, "T2", S2)
    assert title == "T2. Second task", title
    assert out == BOARD + "\n" + S2 + (ending if ending else "\n"), repr(out[-80:])
# Both in turn, and a second Status refused.
both, _ = ml.add_status(ml.add_status(BOARD, "T2", S2)[0], "T1", S1)
assert both.count("**Status:") == 2 and both.endswith(S2 + "\n"), repr(both[-80:])
for task, status in (("T1", S1), ("T2", S2)):
    try:
        ml.add_status(both, task, status)
        raise SystemExit(f"a second Status line for {task} was not refused")
    except AssertionError:
        pass

# The log entry before the anchor; the same task refused twice.
entry = "**2026-10-27: T2 merged: second task.** Did it."
log = ml.add_log(PLANNING, "T2", entry)
assert entry + "\n\n" + ml.LOG_ANCHOR in log, log
try:
    ml.add_log(log, "T2", entry)
    raise SystemExit("a second log entry for T2 was not refused")
except AssertionError:
    pass

# The changelog line under a new date heading, then a second line under the same heading, newest first.
cl, line = ml.add_changelog(CHANGELOG, "T2", "T2. Second task", entry)
assert line == "- T2: Second task", line
assert "## Unreleased\n\n### 2026-10-27\n\n- T2: Second task\n\n## v0.1.0" in cl, cl
cl, _ = ml.add_changelog(cl, "T1", "T1. First task", "**2026-10-27: T1 merged: first.** x", "12")
assert "### 2026-10-27\n\n- T1: First task (#12)\n- T2: Second task\n" in cl, cl

# The script end to end in a scratch repository, on the last task of a board that ends without a blank line.
d = tempfile.mkdtemp(prefix="t154-merge-log-")
try:
    def write(rel, text):
        p = os.path.join(d, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        return p

    def read(rel):
        with open(os.path.join(d, rel), encoding="utf-8") as f:
            return f.read()

    write("docs/TASKS.md", BOARD + "\n")
    write("docs/PLANNING.md", PLANNING)
    write("CHANGELOG.md", CHANGELOG)
    subprocess.run(["git", "init", "-q", d], check=True)
    ef, sf = write("entry.txt", entry + "\n"), write("status.txt", S2 + "\n")
    script = os.path.join(here, "merge-log.py")
    r = subprocess.run([sys.executable, "-I", script, "T2", ef, sf], cwd=d, capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    assert r.stdout.startswith("ok T2 | changelog: - T2: Second task"), r.stdout
    assert read("docs/TASKS.md") == BOARD + "\n" + S2 + "\n"
    assert entry + "\n\n" + ml.LOG_ANCHOR in read("docs/PLANNING.md")
    assert "- T2: Second task" in read("CHANGELOG.md")
    # Run again: the log refuses T2, and no file changes (all three are computed before any is written).
    before = [read(p) for p in ("docs/TASKS.md", "docs/PLANNING.md", "CHANGELOG.md")]
    r = subprocess.run([sys.executable, "-I", script, "T2", ef, sf], cwd=d, capture_output=True, text=True)
    assert r.returncode != 0 and "already logged" in r.stderr, (r.returncode, r.stderr[-300:])
    assert before == [read(p) for p in ("docs/TASKS.md", "docs/PLANNING.md", "CHANGELOG.md")]
finally:
    shutil.rmtree(d, ignore_errors=True)

print("test_merge_log: ok")
