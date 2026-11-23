"""T345: run the tools' own tests, each in its own process, and print one JSON object.

Every `tools/test_*.py` runs as `python -I <file>` from the repo root (one file's failure hides no other file), then
`node .claude/hooks/test-hooks.js`. The hook tests run beside the Python files (two lanes) because they alone take
over two minutes. A file that needs a program this machine lacks is skipped with a named reason (`SKIP_WITHOUT`; the
hooks without `node` or `bash`, and off Windows, `HOOKS_OFF_WINDOWS`, unless RUN_TOOL_TESTS_HOOKS_ANYWHERE is set),
never failed. The hook tests get Git Bash ahead on their PATH on Windows, where a PowerShell session lacks it. The
report: per file `ok`, `seconds` and, on a failure, the last lines of its output (`tail`) and its FAIL/ERROR lines
(`failLines`); a skipped file carries `skipped` with the reason. Exit 0 when nothing failed, 1 otherwise.

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
    "test_sweep_diff.py": ("python:playwright", "needs the Python module playwright (tools/sweep-diff.py draws with it)"),
}
HOOKS = os.path.join(".claude", "hooks", "test-hooks.js")
# The hook tests assume Windows: on Linux (CI, 2026-11-23) seven cases failed, the emulator-stop group's fake
# emulators among them, and the Gradle run after them hung until the job's timeout.
HOOKS_OFF_WINDOWS = "the hook tests assume Windows (on Linux seven cases fail and the build after them hangs)"
TAIL_LINES = 25
FAIL_LINES = 30
TIMEOUT_S = 900


def have(program):
    if program.startswith("python:"):
        import importlib.util
        return importlib.util.find_spec(program[len("python:"):]) is not None
    if program == "java":
        home = os.environ.get("JAVA_HOME")
        if home and any(os.path.isfile(os.path.join(home, "bin", n)) for n in ("java", "java.exe")):
            return True
    return shutil.which(program) is not None


def bash_dir():
    """The folder whose bash the hook tests should spawn: Git Bash on Windows (found from git's exec path, ahead of
    the WSL launcher, and absent from PATH in a PowerShell session), else the one on PATH; None when there is none."""
    if os.name != "nt":
        b = shutil.which("bash")
        return os.path.dirname(b) if b else None
    try:
        d = subprocess.run(["git", "--exec-path"], capture_output=True, text=True, timeout=30).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        d = ""
    d = os.path.normpath(d) if d else ""
    for _ in range(5 if d else 0):
        if os.path.isfile(os.path.join(d, "bin", "bash.exe")):
            return os.path.join(d, "bin")
        d = os.path.dirname(d)
    return None


def run_one(name, cmd, root, env=None):
    t0 = time.monotonic()
    try:
        p = subprocess.run(cmd, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                           timeout=TIMEOUT_S, env=env)
        code, out = p.returncode, p.stdout.decode("utf-8", "replace")
    except subprocess.TimeoutExpired as e:
        code, out = "timeout", (e.stdout or b"").decode("utf-8", "replace") + f"\n(timed out after {TIMEOUT_S} s)"
    except OSError as e:
        code, out = "error", f"{type(e).__name__}: {e}"
    r = {"file": name, "ok": code == 0, "seconds": round(time.monotonic() - t0, 1)}
    if code != 0:
        r["exit"] = code
        lines = out.replace("\r\n", "\n").rstrip("\n").split("\n")
        r["tail"] = lines[-TAIL_LINES:]
        # The failed cases by name, wherever they are in the output: unittest's FAIL:/ERROR: headers, the hook tests' FAIL lines.
        fails = [x for x in lines if x.startswith(("FAIL", "ERROR:"))]
        if fails:
            r["failLines"] = fails[:FAIL_LINES]
    return r


def plan(root, hooks=True):
    """The runs in order: (name, command or None, skip reason or None, environment or None)."""
    runs = []
    for path in sorted(glob.glob(os.path.join(root, "tools", "test_*.py"))):
        name = os.path.basename(path)
        need = SKIP_WITHOUT.get(name)
        if need and not have(need[0]):
            runs.append(("tools/" + name, None, need[1], None))
        else:
            runs.append(("tools/" + name, [sys.executable, "-I", os.path.join("tools", name)], None, None))
    if hooks:
        name = HOOKS.replace(os.sep, "/")
        if not os.path.isfile(os.path.join(root, HOOKS)):
            runs.append((name, None, "no " + name + " in this tree", None))
        elif not have("node"):
            runs.append((name, None, "needs node to run the hook tests", None))
        elif os.name != "nt" and not os.environ.get("RUN_TOOL_TESTS_HOOKS_ANYWHERE"):
            runs.append((name, None, HOOKS_OFF_WINDOWS, None))
        else:
            # The hook tests spawn `bash`; a PowerShell session (tools/verify-task.ps1) has Git Bash off its PATH.
            b = bash_dir()
            if b is None:
                runs.append((name, None, "needs bash (Git Bash on Windows) to run the hook tests", None))
            else:
                env = dict(os.environ)
                env["PATH"] = b + os.pathsep + env.get("PATH", "")
                runs.append((name, ["node", HOOKS], None, env))
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
    for name, cmd, why, _ in runs:
        if cmd is None:
            results[name] = {"file": name, "ok": True, "seconds": 0.0, "skipped": why}

    def lane(items):
        for name, cmd, _, env in items:
            results[name] = run_one(name, cmd, root, env)
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

    files = [results[name] for name, *_ in runs]
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
