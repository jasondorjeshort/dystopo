"""T246: tools/verify-task.ps1's summary from a fixture, with no program run (-Fixture: exit codes from codes.json, git and
adb answers from answers.json, the worktree's test results, the tour's JSON, the guard log and stop.log in the fixture's
folders). The cases: every step ok (the test counts summed over modules, the tour's views, the guard's lowest free RAM
and peak private bytes, a clean scan, the screenshot paths); a failed boot stops the run there by name while the stop and
the guard still run; -Continue runs on; origin/main moved stops at base before check; a console port another AVD holds
stops at slot; a stop.log line about this emulator fails guard; a privacy hit fails privacy; -Skip; and -DryRun on the
real checkout lists the commands (gradlew check --no-daemon, android-emu.ps1 -Build) and runs nothing.

    python -I tools/test_verify_task.py
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(here, "verify-task.ps1")
AVD = "dystopo35-T999v"
STEPS = ["fetch", "worktree", "base", "privacy", "data", "check", "slot", "boot", "tour", "ui-routes", "stop", "guard"]
CLEAN_SCAN = {"lines": 12, "privateTerms": 3, "privateBoxes": 1, "termHits": 0, "coordinateValueHits": 0, "coordinatePairHits": 0,
              "commitsWithHits": 0, "publicAreas": 33, "pairsOutsidePublicAreas": 0, "localFile": True}


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def junit(path, tests, failures=0, skipped=0):
    write(path, f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="x" tests="{tests}" skipped="{skipped}" '
                f'failures="{failures}" errors="0" timestamp="2026-11-09T10:00:00" time="0.1"></testsuite>\n')


def fixture(root, codes=None, answers=None, scan=None, stop_line=None, ui_ok=True):
    a = {"head": "a" * 40, "dirty": "", "mergeBase": "b" * 40, "mainHead": "b" * 40, "behind": "0", "ahead": "3",
         "adbDevices": "List of devices attached\nemulator-5572\tdevice\n", "avdName": "",
         "adbDevicesAfter": "List of devices attached\nemulator-5572\tdevice\n"}
    a.update(answers or {})
    write(os.path.join(root, "answers.json"), json.dumps(a))
    write(os.path.join(root, "codes.json"), json.dumps(codes or {}))
    logs = os.path.join(root, "logs")
    write(os.path.join(logs, "privacy.out.log"), json.dumps(scan or CLEAN_SCAN))
    write(os.path.join(logs, "boot.out.log"), "building the debug APK\nbuilt in 95 s\nserial emulator-5570\n")
    write(os.path.join(logs, "boot.err.log"), "-EmuPort must be even, 5554-5682 (got 5571)\n" if (codes or {}).get("boot") else "")
    write(os.path.join(logs, "ui-routes.out.log"), json.dumps({"part": "routes", "ok": ui_ok, "seconds": 40.1}))
    wt = os.path.join(root, "wt")
    junit(os.path.join(wt, "shared", "build", "test-results", "jvmTest", "TEST-a.xml"), 5, skipped=1)
    junit(os.path.join(wt, "shared", "build", "test-results", "jvmTest", "TEST-b.xml"), 7)
    junit(os.path.join(wt, "devtool", "build", "test-results", "test", "TEST-c.xml"), 3)
    os.makedirs(os.path.join(wt, "androidApp", "build"), exist_ok=True)  # a module with no test results
    tour = os.path.join(root, "tour")
    views = [{"label": f"v{i}", "ok": True, "failed": []} for i in range(10)]
    write(os.path.join(tour, "tour.json"), json.dumps({"ok": True, "viewsOk": 10, "viewsTotal": 10, "views": views,
                                                        "glyphs": {"ok": True}, "backgroundStep": {"ok": True}, "directStep": {"ok": True},
                                                        "cardsStep": {"ok": True}, "sheet": os.path.join(tour, "tour-sheet.png")}))
    for name in ("tour-0.png", "tour-1.png", "tour-sheet.png"):
        write(os.path.join(tour, name), "png")
    write(os.path.join(root, "ui", "routes", "route-1.png"), "png")
    write(os.path.join(wt, "out", f"emulator-guard-{AVD}.log"),
          "2026-11-09T10:00:00.0000000Z guard pid 1 for emulator-5570 (" + AVD + ") started: limit 8 GB private, floor 4 GB free for 30 s; free now 80.4 GB\n"
          "2026-11-09T10:01:00.0000000Z private 4.0 GB (pid 99), free 76.1 GB\n"
          "2026-11-09T10:05:00.0000000Z private 6.0 GB (pid 99), free 71.9 GB\n"
          "2026-11-09T10:09:00.0000000Z guard pid 1 for emulator-5570 (" + AVD + ") ends: its lock is gone (peak private 6.3 GB)\n")
    lines = ["2026-11-09T09:00:00.000Z agent abc (general-purpose): left its emulators alone"]
    if stop_line:
        lines.append(stop_line)
    write(os.path.join(root, "lock", "stop.log"), "\n".join(lines) + "\n")


def run(root, *extra):
    port = [] if "-EmuPort" in extra else ["-EmuPort", "5570"]
    cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT, "-Task", "T999", "-Fixture", root,
           "-Avd", AVD, *port, "-Port", "8694", "-Parts", "routes", *extra]
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    summary = None
    f = os.path.join(root, "verify-summary.json")
    if os.path.exists(f):
        with open(f, encoding="utf-8") as fh:
            summary = json.load(fh)
    text = open(os.path.join(root, "verify-summary.txt"), encoding="utf-8").read() if os.path.exists(os.path.join(root, "verify-summary.txt")) else ""
    return p, summary, text


def steps(summary):
    return {s["name"]: s for s in summary["steps"]}


def case(name, **kw):
    root = tempfile.mkdtemp(prefix="verify-task-test-")
    try:
        args = kw.pop("args", ())
        fixture(root, **kw)
        return run(root, *args)
    finally:
        CLEANUP.append(root)


CLEANUP = []
failures = []


def check(cond, what):
    if not cond:
        failures.append(what)
        print("FAIL:", what)


# 1. Every step ok.
p, s, text = case("ok")
check(p.returncode == 0, f"ok: exit {p.returncode}: {p.stdout[-800:]} {p.stderr[-800:]}")
check(s is not None, "ok: no summary")
if s:
    st = steps(s)
    check([x["name"] for x in s["steps"]] == STEPS, f"ok: step order {[x['name'] for x in s['steps']]}")
    check(s["ok"] is True and s["failedStep"] is None, f"ok: ok={s['ok']} failedStep={s['failedStep']}")
    check(s["head"] == "a" * 40 and s["task"] == "T999" and s["branch"] == "task/T999", "ok: head, task, branch")
    check(all(x["status"] == "ok" and x["ok"] is True and isinstance(x["seconds"], (int, float)) for x in s["steps"]),
          f"ok: statuses {[(x['name'], x['status']) for x in s['steps']]}")
    c = st["check"]
    check((c["tests"], c["failures"], c["errors"], c["skipped"], c["testFiles"]) == (15, 0, 0, 1, 3), f"ok: test counts {c}")
    check(set(c["modules"]) == {"shared", "devtool"} and c["modules"]["shared"]["tests"] == 12, f"ok: modules {c['modules']}")
    check(st["base"]["mainMovedBy"] == 0 and st["base"]["branchCommits"] == 3, f"ok: base {st['base']}")
    check(st["privacy"]["termHits"] == 0 and st["privacy"]["coordinatePairHits"] == 0, f"ok: privacy {st['privacy']}")
    check(st["slot"]["cap"] == 2 and st["slot"]["others"] == ["emulator-5572"], f"ok: slot {st['slot']}")
    check(st["boot"]["buildSeconds"] == 95 and st["boot"]["serial"] == "emulator-5570", f"ok: boot {st['boot']}")
    t = st["tour"]
    check((t["viewsOk"], t["viewsTotal"], t["tourOk"], t["screenshots"]) == (10, 10, True, 3), f"ok: tour {t}")
    check(st["ui-routes"]["partOk"] is True and st["ui-routes"]["screenshots"] == 1, f"ok: ui {st['ui-routes']}")
    g = st["guard"]
    check(g["lowestFreeGB"] == 71.9 and g["peakPrivateGB"] == 6.3 and g["stops"] == [] and g["lines"] == 4, f"ok: guard {g}")
    check(len(s["screenshots"]) == 4 and all(x.endswith(".png") for x in s["screenshots"]), f"ok: screenshots {s['screenshots']}")
    check(any("gradlew.bat check --no-daemon" in x["command"] for x in s["commands"]), "ok: the check command recorded")
    check("RESULT: ok" in text and "15 tests" in text and "10/10 views ok" in text and "lowest free 71.9 GB" in text, f"ok: text {text}")

# 2. A failed boot stops there by name; the stop and the guard still run, the tour does not.
p, s, text = case("boot", codes={"boot": 1})
check(p.returncode == 1, f"boot: exit {p.returncode}")
if s:
    st = steps(s)
    check(s["ok"] is False and s["failedStep"] == "boot", f"boot: failedStep {s['failedStep']}")
    check("EmuPort must be even" in st["boot"]["error"], f"boot: error {st['boot'].get('error')}")
    check(st["tour"]["status"] == "not run" and st["ui-routes"]["status"] == "not run", "boot: later steps not run")
    check(st["stop"]["status"] == "ok" and st["guard"]["status"] == "ok", f"boot: cleanup {st['stop']['status']} {st['guard']['status']}")
    check("RESULT: FAILED at step boot" in text, f"boot: text {text}")

# 3. -Continue runs on after the failure, and the first failure stays the one named.
p, s, text = case("continue", codes={"boot": 1, "tour": 1}, args=("-Continue",))
if s:
    st = steps(s)
    check(s["failedStep"] == "boot" and st["tour"]["status"] == "failed" and st["ui-routes"]["status"] == "ok",
          f"continue: {s['failedStep']} {st['tour']['status']} {st['ui-routes']['status']}")

# 4. origin/main moved: stops at base, before check; no emulator was booted, so no stop.
p, s, text = case("behind", answers={"behind": "4"})
if s:
    st = steps(s)
    check(s["failedStep"] == "base" and st["base"]["mainMovedBy"] == 4 and "rebase" in st["base"]["error"], f"behind: {st['base']}")
    check(st["check"]["status"] == "not run" and st["stop"]["status"] == "not run", "behind: check and stop not run")
p, s, text = case("allow-behind", answers={"behind": "4"}, args=("-AllowBehind",))
check(s and s["ok"] is True and steps(s)["base"]["mainMovedBy"] == 4, "allow-behind: ok with the count")

# 5. A console port that another AVD holds stops at slot, before the boot.
p, s, text = case("port", answers={"adbDevices": "List of devices attached\nemulator-5570\tdevice\n", "avdName": "dystopo35-T248\nOK"})
if s:
    st = steps(s)
    check(s["failedStep"] == "slot" and "dystopo35-T248" in st["slot"]["error"], f"port: {st['slot']}")
    check(st["boot"]["status"] == "not run", "port: boot not run")
p, s, text = case("odd-port", args=("-EmuPort", "5571"))
check(s and s["failedStep"] == "slot" and "even" in steps(s)["slot"]["error"], "odd port: fails at slot")

# 6. A stop.log line about this emulator fails guard.
p, s, text = case("guard", stop_line="2026-11-09T10:04:00.000Z guard pid 1: emulator-5570 (" + AVD + ", owner T999-verify, pid 99): private bytes 8.2 GB over the limit 8 GB; stopped by console kill; lock removed")
if s:
    g = steps(s)["guard"]
    check(s["failedStep"] == "guard" and len(g["stops"]) == 1 and "over the limit" in g["error"], f"guard: {g}")

# 7. A privacy hit fails privacy.
p, s, text = case("privacy", scan={**CLEAN_SCAN, "termHits": 1}, codes={"privacy": 1})
check(s and s["failedStep"] == "privacy" and steps(s)["check"]["status"] == "not run", "privacy: fails before check")

# 8. -Skip leaves steps out; a failed ui part names its step.
p, s, text = case("skip", args=("-Skip", "check,tour"), ui_ok=False)
if s:
    st = steps(s)
    check(st["check"]["status"] == "skipped" and st["tour"]["status"] == "skipped", "skip: statuses")
    check(s["failedStep"] == "ui-routes", f"skip: failedStep {s['failedStep']}")

# 8b. -KeepEmulator leaves the stop to a later -Only stop,guard, whose summary keeps the earlier steps.
root = tempfile.mkdtemp(prefix="verify-task-test-")
CLEANUP.append(root)
fixture(root)
p, s, text = run(root, "-KeepEmulator")
check(s and s["ok"] and steps(s)["stop"]["status"] == "kept" and "-Stop -Delete" in steps(s)["stop"]["note"], "keep: stop kept with its command")
p, s, text = run(root, "-Only", "stop,guard")
if s:
    st = steps(s)
    check(st["stop"]["status"] == "ok" and st["guard"]["status"] == "ok", f"only: {st['stop']['status']} {st['guard']['status']}")
    check(st["check"]["status"] == "ok" and st["check"]["tests"] == 15 and "from" in st["check"], f"only: check carried {st['check']}")
    check(s["head"] == "a" * 40, "only: head carried")

# 9. -DryRun on the real checkout: the commands, nothing run, no summary.
out = tempfile.mkdtemp(prefix="verify-task-dry-")
CLEANUP.append(out)
p = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT, "-Task", "T999", "-WorktreeRoot", out,
                    "-Avd", AVD, "-EmuPort", "5570", "-Port", "8694", "-DryRun"], capture_output=True, text=True, timeout=120)
check(p.returncode == 0, f"dry run: exit {p.returncode} {p.stderr[-500:]}")
for needle in ("worktree add --detach", "gradlew.bat check --no-daemon", "android-emu.ps1 -Avd " + AVD, "-Build", "android-check.py",
               "PrivacyCheck.java scan", "-Stop -Delete", "commands listed, nothing run"):
    check(needle in p.stdout, f"dry run: '{needle}' missing")
check(not os.path.exists(os.path.join(out, "T999-verify")), "dry run: made the worktree")

# 10. Bad arguments exit 2.
p = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT, "-Task", "T999", "-Skip", "nope", "-DryRun"],
                   capture_output=True, text=True, timeout=120)
check(p.returncode == 2 and "unknown step" in p.stdout, f"bad step: exit {p.returncode}")

for d in CLEANUP:
    shutil.rmtree(d, ignore_errors=True)
if failures:
    print(f"{len(failures)} failures")
    sys.exit(1)
print("verify-task: all cases ok")
