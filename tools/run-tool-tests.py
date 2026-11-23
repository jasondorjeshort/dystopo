"""T345: run the tools' own tests, each in its own process, and print one JSON object.

Every `tools/test_*.py` runs as `python -I <file>` from the repo root (one file's failure hides no other file), then
`node .claude/hooks/test-hooks.js`. The hook tests run beside the Python files (two lanes) because they alone take
over two minutes. A file that needs a program this machine lacks is skipped with a named reason (`SKIP_WITHOUT`; the
hooks without `node`), never failed. The report: per file `ok`, `seconds` and, on a failure, the last lines of its
output (`tail`); a skipped file carries `skipped` with the reason. Exit 0 when nothing failed, 1 otherwise.

Usage: python -I tools/run-tool-tests.py [--out report.json] [--root <repo>] [--no-hooks] [--jobs 2]
`./gradlew check` runs it as `toolsTest` (root build.gradle.kts), up to date while its inputs are unchanged.
"""
import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import threading
import time

# A file that needs a program to run at all: the program and the reason a machine without it skips the file.
SKIP_WITHOUT = {
    "test_verify_task.py": ("powershell", "needs Windows PowerShell (powershell) to run tools/verify-task.ps1"),
    "test_privacy_check.py": ("java", "needs java (JAVA_HOME or on PATH) to run tools/PrivacyCheck.java"),
}
HOOKS = os.path.join(".claude", "hooks", "test-hooks.js")
TAIL_LINES = 25
TIMEOUT_S = 900


def have(program):
    if program == "java":
        home = os.environ.get("JAVA_HOME")
        if home and any(os.path.isfile(os.path.join(home, "bin", n)) for n in ("java", "java.exe")):
            return True
    return shutil.which(program) is not None


def run_one(name, cmd, root):
    t0 = time.monotonic()
    try:
        p = subprocess.run(cmd, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                           timeout=TIMEOUT_S)
        code, out = p.returncode, p.stdout.decode("utf-8", "replace")
    except subprocess.TimeoutExpired as e:
        code, out = "timeout", (e.stdout or b"").decode("utf-8", "replace") + f"\n(timed out after {TIMEOUT_S} s)"
    except OSError as e:
        code, out = "error", f"{type(e).__name__}: {e}"
    r = {"file": name, "ok": code == 0, "seconds": round(time.monotonic() - t0, 1)}
    if code != 0:
        r["exit"] = code
        r["tail"] = out.replace("\r\n", "\n").rstrip("\n").split("\n")[-TAIL_LINES:]
    return r


def plan(root, hooks=True):
    """The runs in order: (name, command or None, skip reason or None)."""
    runs = []
    for path in sorted(glob.glob(os.path.join(root, "tools", "test_*.py"))):
        name = os.path.basename(path)
        need = SKIP_WITHOUT.get(name)
        if need and not have(need[0]):
            runs.append(("tools/" + name, None, need[1]))
        else:
            runs.append(("tools/" + name, [sys.executable, "-I", os.path.join("tools", name)], None))
    if hooks:
        name = HOOKS.replace(os.sep, "/")
        if not os.path.isfile(os.path.join(root, HOOKS)):
            runs.append((name, None, "no " + name + " in this tree"))
        elif not have("node"):
            runs.append((name, None, "needs node to run the hook tests"))
        else:
            runs.append((name, ["node", HOOKS], None))
    return runs


def main(argv=None):
    ap = argparse.ArgumentParser(description="Run every tools/test_*.py and the hook tests; one JSON report.")
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--out", help="also write the report to this file")
    ap.add_argument("--no-hooks", action="store_true", help="leave out node .claude/hooks/test-hooks.js")
    ap.add_argument("--jobs", type=int, default=2, help="2 runs the hook tests beside the Python files; 1 runs all in turn")
    a = ap.parse_args(argv)
    root = os.path.abspath(a.root)
    t0 = time.monotonic()
    runs = plan(root, hooks=not a.no_hooks)
    results = {}
    for name, cmd, why in runs:
        if cmd is None:
            results[name] = {"file": name, "ok": True, "seconds": 0.0, "skipped": why}

    def lane(items):
        for name, cmd, _ in items:
            results[name] = run_one(name, cmd, root)
            r = results[name]
            print(f"run-tool-tests: {name} {'ok' if r['ok'] else 'FAILED'} {r['seconds']} s", file=sys.stderr, flush=True)

    todo = [r for r in runs if r[1] is not None]
    slow = [r for r in todo if r[0].endswith(".js")] if a.jobs > 1 else []
    rest = [r for r in todo if r not in slow]
    threads = [threading.Thread(target=lane, args=(items,)) for items in (slow, rest) if items]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    files = [results[name] for name, _, _ in runs]
    failed = [r["file"] for r in files if not r["ok"]]
    report = {"ok": not failed, "files": files, "passed": sum(1 for r in files if r["ok"] and "skipped" not in r),
              "failed": failed, "skipped": {r["file"]: r["skipped"] for r in files if "skipped" in r},
              "seconds": round(time.monotonic() - t0, 1)}
    text = json.dumps(report, indent=1)
    if a.out:
        os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    print(text)
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
