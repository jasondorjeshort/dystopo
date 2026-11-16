"""T246, T296: tools/verify-task.ps1's summary from a fixture, with no program run (-Fixture: exit codes from codes.json,
git and adb answers from answers.json (sweep.ps1 -ListParts's output under listParts), the worktree's test results, the
tour's JSON, the guard log, stop.log, the sweep's summary.json and the Checks' outputs in the fixture's folders).

T246's cases: every step ok (the test counts summed over modules, the tour's views, the guard's lowest free RAM and peak
private bytes, a clean scan, the screenshot paths); a failed boot stops the run there by name while the stop and the guard
still run; -Continue runs on; origin/main moved stops at base before check; a console port another AVD holds stops at
slot; a stop.log line about this emulator fails guard; a privacy hit fails privacy; -Skip; and -DryRun on the real
checkout lists the commands (gradlew check --no-daemon, android-emu.ps1 -Build) and runs nothing.
T296's cases: -SweepThrough's command line from a -ListParts fixture (the parts after it skipped, the sweep's own check
skipped, -NoBothUnits unless the part is bothunits, the emulator steps left to the sweep, two slots for bothunits, no slot
for a range with no device part, a list of plain names, a part not in the list, a red sweep, a dry run naming the parts);
a Checks file's three kinds (a JSON path and value, an exit code, a test count) passing and failing, and a bad Checks
file; a task with no emulator reads slot, stop and guard as skipped; -Commit.

    python -I -m unittest discover -s tools -p test_verify_task.py
"""
import json
import os
import shutil
import subprocess
import tempfile
import unittest

here = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(here, "verify-task.ps1")
AVD = "dystopo35-T999v"
STEPS = ["fetch", "worktree", "base", "privacy", "data", "check", "slot", "boot", "tour", "ui-routes", "stop", "guard"]
CLEAN_SCAN = {"lines": 12, "privateTerms": 3, "privateBoxes": 1, "termHits": 0, "coordinateValueHits": 0, "coordinatePairHits": 0,
              "commitsWithHits": 0, "publicAreas": 33, "pairsOutsidePublicAreas": 0, "localFile": True}
# The shape assumed for T292's tools/sweep.ps1 -ListParts (the parts in the order the script runs them; startup and a11y
# after bothunits, apk-strings last): {"parts": [{"name": ..., "device": ...}, ...]}. A plain list of names is read too.
DEVICE = {"device-boot", "device-tour", "routes", "search", "probe", "bothunits", "startup", "a11y", "release", "first-run",
          "features", "recenter", "diagnostics", "upgrade", "permissions", "background", "network", "update", "matrix"}
ORDER = ["check", "server", "notices", "tour", "demo-static", "demo-serve", "live", "sync-sim", "route-sheet", "device-boot",
         "device-tour", "routes", "search", "probe", "bothunits", "startup", "a11y", "release", "first-run", "features", "recenter",
         "diagnostics", "upgrade", "permissions", "background", "network", "update", "matrix", "apk-strings"]
LIST_PARTS = json.dumps({"parts": [{"name": n, "device": n in DEVICE} for n in ORDER]})


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def junit(path, tests, failures=0, skipped=0):
    write(path, f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="x" tests="{tests}" skipped="{skipped}" '
                f'failures="{failures}" errors="0" timestamp="2026-11-09T10:00:00" time="0.1"></testsuite>\n')


def fixture(root, codes=None, answers=None, scan=None, stop_line=None, ui_ok=True, sweep=None, logs_extra=None):
    a = {"head": "a" * 40, "dirty": "", "mergeBase": "b" * 40, "mainHead": "b" * 40, "behind": "0", "ahead": "3",
         "adbDevices": "List of devices attached\nemulator-5572\tdevice\n", "avdName": "",
         "adbDevicesAfter": "List of devices attached\nemulator-5572\tdevice\n", "listParts": LIST_PARTS}
    a.update(answers or {})
    write(os.path.join(root, "answers.json"), json.dumps(a))
    write(os.path.join(root, "codes.json"), json.dumps(codes or {}))
    logs = os.path.join(root, "logs")
    write(os.path.join(logs, "privacy.out.log"), json.dumps(scan or CLEAN_SCAN))
    write(os.path.join(logs, "boot.out.log"), "building the debug APK\nbuilt in 95 s\nserial emulator-5570\n")
    write(os.path.join(logs, "boot.err.log"), "-EmuPort must be even, 5554-5682 (got 5571)\n" if (codes or {}).get("boot") else "")
    write(os.path.join(logs, "ui-routes.out.log"), json.dumps({"part": "routes", "ok": ui_ok, "seconds": 40.1}))
    for name, text in (logs_extra or {}).items():
        write(os.path.join(logs, name), text)
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
    if sweep is not None:
        write(os.path.join(wt, "out", "verify-sweep", "summary.json"), json.dumps(sweep))
        write(os.path.join(wt, "out", "verify-sweep", "sheet.png"), "png")


def run(root, *extra, parts=True, emu=True):
    port = [] if "-EmuPort" in extra or not emu else ["-EmuPort", "5570"]
    emu_args = ["-Avd", AVD, *port, "-Port", "8694"] if emu else []
    cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT, "-Task", "T999", "-Fixture", root,
           *emu_args, *(["-Parts", "routes"] if parts else []), *extra]
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    summary = None
    f = os.path.join(root, "verify-summary.json")
    if os.path.exists(f):
        with open(f, encoding="utf-8") as fh:
            summary = json.load(fh)
    t = os.path.join(root, "verify-summary.txt")
    text = ""
    if os.path.exists(t):
        with open(t, encoding="utf-8") as fh:
            text = fh.read()
    return p, summary, text


def steps(summary):
    return {s["name"]: s for s in summary["steps"]}


def sweep_summary(ok=True, parts=("server", "device-boot", "a11y"), failed=()):
    return {"ok": ok, "failed": list(failed), "skipped": [], "parts": {p: {"ok": p not in failed, "seconds": 1.0} for p in parts},
            "sheet": "out/verify-sweep/sheet.png"}


class Base(unittest.TestCase):
    def setUp(self):
        self.dirs = []

    def tearDown(self):
        for d in self.dirs:
            shutil.rmtree(d, ignore_errors=True)

    def tmp(self, prefix="verify-task-test-"):
        d = tempfile.mkdtemp(prefix=prefix)
        self.dirs.append(d)
        return d

    def case(self, args=(), parts=True, emu=True, **kw):
        root = self.tmp()
        fixture(root, **kw)
        p, s, text = run(root, *args, parts=parts, emu=emu)
        self.assertIsNotNone(s, f"no summary: exit {p.returncode}: {p.stdout[-800:]} {p.stderr[-800:]}")
        return p, s, text

    def checks_file(self, checks):
        d = self.tmp("verify-task-checks-")
        f = os.path.join(d, "checks.json")
        write(f, json.dumps(checks))
        return f


class T246Steps(Base):
    def test_every_step_ok(self):
        p, s, text = self.case()
        self.assertEqual(p.returncode, 0, p.stdout[-800:])
        st = steps(s)
        self.assertEqual([x["name"] for x in s["steps"]], STEPS)
        self.assertTrue(s["ok"])
        self.assertIsNone(s["failedStep"])
        self.assertEqual((s["head"], s["task"], s["branch"]), ("a" * 40, "T999", "task/T999"))
        for x in s["steps"]:
            self.assertEqual((x["name"], x["status"], x["ok"]), (x["name"], "ok", True))
            self.assertIsInstance(x["seconds"], (int, float))
        c = st["check"]
        self.assertEqual((c["tests"], c["failures"], c["errors"], c["skipped"], c["testFiles"]), (15, 0, 0, 1, 3))
        self.assertEqual(set(c["modules"]), {"shared", "devtool"})
        self.assertEqual(c["modules"]["shared"]["tests"], 12)
        self.assertEqual((st["base"]["mainMovedBy"], st["base"]["branchCommits"]), (0, 3))
        self.assertEqual((st["privacy"]["termHits"], st["privacy"]["coordinatePairHits"]), (0, 0))
        self.assertEqual((st["slot"]["cap"], st["slot"]["others"]), (2, ["emulator-5572"]))
        self.assertEqual((st["boot"]["buildSeconds"], st["boot"]["serial"]), (95, "emulator-5570"))
        t = st["tour"]
        self.assertEqual((t["viewsOk"], t["viewsTotal"], t["tourOk"], t["screenshots"]), (10, 10, True, 3))
        self.assertEqual((st["ui-routes"]["partOk"], st["ui-routes"]["screenshots"]), (True, 1))
        g = st["guard"]
        self.assertEqual((g["lowestFreeGB"], g["peakPrivateGB"], g["stops"], g["lines"]), (71.9, 6.3, [], 4))
        self.assertEqual(len(s["screenshots"]), 4)
        self.assertTrue(any("gradlew.bat check --no-daemon" in x["command"] for x in s["commands"]))
        for needle in ("RESULT: ok", "15 tests", "10/10 views ok", "lowest free 71.9 GB"):
            self.assertIn(needle, text)

    def test_failed_boot_stops_there_and_cleans_up(self):
        p, s, text = self.case(codes={"boot": 1})
        self.assertEqual(p.returncode, 1)
        st = steps(s)
        self.assertEqual((s["ok"], s["failedStep"]), (False, "boot"))
        self.assertIn("EmuPort must be even", st["boot"]["error"])
        self.assertEqual((st["tour"]["status"], st["ui-routes"]["status"]), ("not run", "not run"))
        self.assertEqual((st["stop"]["status"], st["guard"]["status"]), ("ok", "ok"))
        self.assertIn("RESULT: FAILED at step boot", text)

    def test_continue_keeps_the_first_failure(self):
        p, s, text = self.case(codes={"boot": 1, "tour": 1}, args=("-Continue",))
        st = steps(s)
        self.assertEqual((s["failedStep"], st["tour"]["status"], st["ui-routes"]["status"]), ("boot", "failed", "ok"))

    def test_main_moved_stops_at_base(self):
        p, s, text = self.case(answers={"behind": "4"})
        st = steps(s)
        self.assertEqual((s["failedStep"], st["base"]["mainMovedBy"]), ("base", 4))
        self.assertIn("rebase", st["base"]["error"])
        self.assertEqual((st["check"]["status"], st["stop"]["status"]), ("not run", "not run"))
        p, s, text = self.case(answers={"behind": "4"}, args=("-AllowBehind",))
        self.assertTrue(s["ok"])
        self.assertEqual(steps(s)["base"]["mainMovedBy"], 4)

    def test_held_and_odd_console_ports_stop_at_slot(self):
        p, s, text = self.case(answers={"adbDevices": "List of devices attached\nemulator-5570\tdevice\n", "avdName": "dystopo35-T248\nOK"})
        st = steps(s)
        self.assertEqual(s["failedStep"], "slot")
        self.assertIn("dystopo35-T248", st["slot"]["error"])
        self.assertEqual(st["boot"]["status"], "not run")
        p, s, text = self.case(args=("-EmuPort", "5571"))
        self.assertEqual(s["failedStep"], "slot")
        self.assertIn("even", steps(s)["slot"]["error"])

    def test_stop_log_line_fails_guard(self):
        p, s, text = self.case(stop_line="2026-11-09T10:04:00.000Z guard pid 1: emulator-5570 (" + AVD +
                               ", owner T999-verify, pid 99): private bytes 8.2 GB over the limit 8 GB; stopped by console kill; lock removed")
        g = steps(s)["guard"]
        self.assertEqual((s["failedStep"], len(g["stops"])), ("guard", 1))
        self.assertIn("over the limit", g["error"])

    def test_privacy_hit_fails_before_check(self):
        p, s, text = self.case(scan={**CLEAN_SCAN, "termHits": 1}, codes={"privacy": 1})
        self.assertEqual((s["failedStep"], steps(s)["check"]["status"]), ("privacy", "not run"))

    def test_skip_and_a_failed_ui_part(self):
        p, s, text = self.case(args=("-Skip", "check,tour"), ui_ok=False)
        st = steps(s)
        self.assertEqual((st["check"]["status"], st["tour"]["status"]), ("skipped", "skipped"))
        self.assertEqual(s["failedStep"], "ui-routes")

    def test_keep_emulator_then_only_stop_guard(self):
        root = self.tmp()
        fixture(root)
        p, s, text = run(root, "-KeepEmulator")
        self.assertTrue(s["ok"])
        self.assertEqual(steps(s)["stop"]["status"], "kept")
        self.assertIn("-Stop -Delete", steps(s)["stop"]["note"])
        p, s, text = run(root, "-Only", "stop,guard")
        st = steps(s)
        self.assertEqual((st["stop"]["status"], st["guard"]["status"]), ("ok", "ok"))
        self.assertEqual((st["check"]["status"], st["check"]["tests"]), ("ok", 15))
        self.assertIn("from", st["check"])
        self.assertEqual(s["head"], "a" * 40)

    def test_dry_run_on_the_real_checkout(self):
        out = self.tmp("verify-task-dry-")
        p = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT, "-Task", "T999", "-WorktreeRoot", out,
                            "-Avd", AVD, "-EmuPort", "5570", "-Port", "8694", "-DryRun"], capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 0, p.stderr[-500:])
        for needle in ("worktree add --detach", "gradlew.bat check --no-daemon", "android-emu.ps1 -Avd " + AVD, "-Build", "android-check.py",
                       "PrivacyCheck.java scan", "-Stop -Delete", "commands listed, nothing run"):
            self.assertIn(needle, p.stdout)
        self.assertFalse(os.path.exists(os.path.join(out, "T999-verify")))

    def test_bad_step_exits_2(self):
        p = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT, "-Task", "T999", "-Skip", "nope", "-DryRun"],
                           capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 2)
        self.assertIn("unknown step", p.stdout)


class T296NoEmulator(Base):
    def test_no_emulator_reads_skipped(self):
        # T268's and T291's note: a task with no emulator read "FAILED at step slot" (-EmuPort 0 is not even-and-in-range).
        p, s, text = self.case(args=("-Skip", "boot,tour"), parts=False, emu=False)
        self.assertEqual(p.returncode, 0, p.stdout[-800:])
        st = steps(s)
        self.assertTrue(s["ok"])
        for n in ("slot", "stop", "guard"):
            self.assertEqual((n, st[n]["status"], st[n]["note"]), (n, "skipped", "no emulator wanted"))
        self.assertIn("RESULT: ok", text)
        self.assertNotIn("FAILED", text)

    def test_commit_instead_of_a_branch(self):
        p, s, text = self.case(args=("-Commit", "944294c1", "-Skip", "check,boot,tour,stop,guard"), parts=False, emu=False,
                               answers={"behind": "40"})
        self.assertTrue(s["ok"], text)
        self.assertEqual((s["commit"], s["branch"]), ("944294c1", None))
        self.assertIn("a past commit", steps(s)["base"]["note"])
        self.assertTrue(any("checkout --detach --quiet 944294c1" in c["command"] for c in s["commands"]), s["commands"])


class T296SweepThrough(Base):
    def sweep_cmd(self, s):
        cmds = [c["command"] for c in s["commands"] if c["id"] == "sweep"]
        self.assertEqual(len(cmds), 1, s["commands"])
        return cmds[0]

    def test_through_a11y(self):
        p, s, text = self.case(args=("-SweepThrough", "a11y"), parts=False, sweep=sweep_summary())
        self.assertEqual(p.returncode, 0, p.stdout[-1500:])
        st = steps(s)
        self.assertEqual([x["name"] for x in s["steps"]], ["fetch", "worktree", "base", "privacy", "data", "check", "slot", "boot", "tour",
                                                          "sweep", "stop", "guard"])
        cmd = self.sweep_cmd(s)
        after = ORDER[ORDER.index("a11y") + 1:]
        self.assertIn("tools\\sweep.ps1 -Out out\\verify-sweep -NoDaemon -Skip " + ",".join(["check"] + after) + " -NoBothUnits", cmd)
        self.assertIn(f"-Avd {AVD} -EmuPort 5570 -Port 8694", cmd)
        sw = st["sweep"]
        self.assertEqual(sw["run"], ORDER[1:ORDER.index("a11y") + 1])
        self.assertEqual((sw["emulators"], sw["sweepOk"], sw["ran"]), (1, True, ["server", "device-boot", "a11y"]))
        for n in ("boot", "tour", "stop"):
            self.assertEqual(st[n]["status"], "skipped")
            self.assertIn("-SweepThrough", st[n]["note"])
        self.assertEqual((st["slot"]["status"], st["slot"]["need"]), ("ok", 1))
        self.assertEqual(st["guard"]["status"], "ok")  # the sweep's emulator ran under the guard
        self.assertTrue(any(x.endswith("sheet.png") for x in s["screenshots"]))

    def test_through_bothunits_wants_two_slots(self):
        p, s, text = self.case(args=("-SweepThrough", "bothunits"), parts=False, sweep=sweep_summary())
        st = steps(s)
        # One other emulator on a cap of two leaves one slot: not enough for bothunits's second phone.
        self.assertEqual(s["failedStep"], "slot")
        self.assertEqual(st["sweep"]["status"], "not run")
        p, s, text = self.case(args=("-SweepThrough", "bothunits"), parts=False, sweep=sweep_summary(),
                               answers={"adbDevices": "List of devices attached\n"})
        self.assertTrue(s["ok"], text)
        cmd = self.sweep_cmd(s)
        self.assertNotIn("-NoBothUnits", cmd)
        self.assertEqual(steps(s)["sweep"]["emulators"], 2)
        self.assertIn("-Skip " + ",".join(["check"] + ORDER[ORDER.index("bothunits") + 1:]) + " -Avd", cmd)

    def test_through_a_part_with_no_device(self):
        p, s, text = self.case(args=("-SweepThrough", "route-sheet"), parts=False, emu=False,
                               sweep=sweep_summary(parts=("server", "route-sheet")))
        self.assertTrue(s["ok"], text)
        st = steps(s)
        self.assertEqual(st["sweep"]["emulators"], 0)
        for n in ("slot", "guard"):
            self.assertEqual(st[n]["status"], "skipped")

    def test_a_list_of_plain_names(self):
        names = json.dumps(ORDER)
        p, s, text = self.case(args=("-SweepThrough", "startup"), parts=False, sweep=sweep_summary(), answers={"listParts": names})
        self.assertTrue(s["ok"], text)
        self.assertEqual(steps(s)["sweep"]["emulators"], 1)  # no device flags: device-boot in the range
        self.assertIn("-Skip check,a11y,", self.sweep_cmd(s))

    def test_a_part_not_in_the_list(self):
        p, s, text = self.case(args=("-SweepThrough", "nope-part"), parts=False, sweep=sweep_summary())
        self.assertEqual(s["failedStep"], "sweep")
        self.assertIn("not a part of sweep.ps1 -ListParts", steps(s)["sweep"]["error"])

    def test_no_list_parts_yet(self):
        p, s, text = self.case(args=("-SweepThrough", "a11y"), parts=False, sweep=sweep_summary(), answers={"listParts": "usage: ..."})
        self.assertEqual(s["failedStep"], "sweep")
        self.assertIn("no list of parts", steps(s)["sweep"]["error"])

    def test_a_red_sweep(self):
        p, s, text = self.case(args=("-SweepThrough", "a11y"), parts=False, codes={"sweep": 1},
                               sweep=sweep_summary(ok=False, failed=("a11y",)))
        self.assertEqual((p.returncode, s["failedStep"]), (1, "sweep"))
        sw = steps(s)["sweep"]
        self.assertEqual(sw["failed"], ["a11y"])
        self.assertIn("failed: a11y", sw["error"])
        self.assertEqual(steps(s)["guard"]["status"], "ok")

    def test_dry_run_names_the_parts(self):
        root = self.tmp()
        fixture(root)
        p, s, text = run(root, "-SweepThrough", "a11y", "-DryRun", parts=False)
        self.assertEqual(p.returncode, 0, p.stdout[-800:])
        self.assertIn("sweep through a11y: runs " + ", ".join(ORDER[1:ORDER.index("a11y") + 1]) + "; skips " +
                      ", ".join(["check"] + ORDER[ORDER.index("a11y") + 1:]) + "; 1 emulator(s)", p.stdout)

    def test_real_dry_run_reads_the_real_list_or_says_it_is_missing(self):
        # On this checkout's own sweep.ps1: before T292's -ListParts the step fails by name (a sweep.ps1 without the switch
        # would start a sweep), after it the dry run names the parts; either way no sweep folder is made.
        out_dir = os.path.join(os.path.dirname(here), "out")
        before = set(os.listdir(out_dir)) if os.path.isdir(out_dir) else set()
        wt_root = self.tmp("verify-task-dry-")
        p = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT, "-Task", "T999", "-WorktreeRoot", wt_root,
                            "-Avd", AVD, "-EmuPort", "5570", "-Port", "8694", "-SweepThrough", "a11y", "-DryRun"],
                           capture_output=True, text=True, timeout=180)
        if "has no -ListParts switch" in p.stdout:
            self.assertEqual(p.returncode, 1)
        else:
            self.assertEqual(p.returncode, 0, p.stdout[-1500:])
            self.assertIn("sweep through a11y: runs ", p.stdout)
            self.assertIn("-NoBothUnits", p.stdout)
        after = set(os.listdir(out_dir)) if os.path.isdir(out_dir) else set()
        self.assertEqual({n for n in after - before if n.startswith("sweep-")}, set())

    def test_with_parts_is_refused(self):
        root = self.tmp()
        fixture(root)
        p, s, text = run(root, "-SweepThrough", "a11y")
        self.assertEqual(p.returncode, 2)
        self.assertIn("-SweepThrough and -Parts", p.stdout)


class T296Checks(Base):
    def run_checks(self, checks, logs_extra=None, codes=None):
        root = self.tmp()
        fixture(root, logs_extra=logs_extra, codes=codes)
        write(os.path.join(root, "wt", "out", "x", "summary.json"), json.dumps({"routeSheet": {"failed": 0, "new": 24}, "views": [{"ok": True}]}))
        f = self.checks_file(checks)
        p, s, text = run(root, "-Checks", f, "-Skip", "check,boot,tour", parts=False, emu=False)
        return p, s, text

    KINDS = {"checks": [
        {"name": "route-diff-tests", "run": ["python", "-I", "-m", "unittest", "discover", "-s", "tools", "-p", "test_route_diff.py"], "tests": 7},
        {"name": "summary-exit", "run": ["python", "-I", "tools/test_sweep_summary.py"], "exit": 0},
        {"name": "sheet-none-failed", "file": "{wt}/out/x/summary.json", "path": "routeSheet.failed", "equals": 0},
        {"name": "first-view-ok", "file": "{wt}/out/x/summary.json", "path": "views.0.ok", "equals": True},
        {"name": "stdout-json", "run": ["python", "-c", "print(1)"], "path": "parts.a11y.ok", "equals": True},
    ]}
    LOGS = {"chk-route-diff-tests.err.log": "......\n----------------------------------------------------------------------\nRan 7 tests in 1.2s\n\nOK\n",
            "chk-stdout-json.out.log": json.dumps({"parts": {"a11y": {"ok": True}}})}

    def test_three_kinds_pass(self):
        p, s, text = self.run_checks(self.KINDS, logs_extra=self.LOGS)
        self.assertEqual(p.returncode, 0, p.stdout[-1500:])
        st = steps(s)
        names = [x["name"] for x in s["steps"]]
        self.assertEqual(names[names.index("tour") + 1:], ["chk-route-diff-tests", "chk-summary-exit", "chk-sheet-none-failed",
                                                           "chk-first-view-ok", "chk-stdout-json", "stop", "guard"])
        self.assertEqual((st["chk-route-diff-tests"]["tests"], st["chk-route-diff-tests"]["status"]), (7, "ok"))
        self.assertEqual(st["chk-summary-exit"]["exitCode"], 0)
        self.assertEqual((st["chk-sheet-none-failed"]["value"], st["chk-sheet-none-failed"]["note"]), (0, "routeSheet.failed = 0"))
        self.assertEqual(st["chk-first-view-ok"]["value"], True)
        self.assertEqual(st["chk-stdout-json"]["note"], "exit 0; parts.a11y.ok = true")
        self.assertTrue(any("unittest discover -s tools -p test_route_diff.py" in c["command"] for c in s["commands"]))

    def test_each_kind_fails_by_name(self):
        bad_count = {"chk-t.err.log": "Ran 6 tests in 0.1s\n\nOK\n"}
        p, s, text = self.run_checks([{"name": "t", "run": ["python", "x"], "tests": 7}], logs_extra=bad_count)
        self.assertEqual((p.returncode, s["failedStep"]), (1, "chk-t"))
        self.assertIn("6 tests, expected 7", steps(s)["chk-t"]["error"])
        p, s, text = self.run_checks([{"name": "e", "run": ["python", "x"]}], codes={"chk-e": 3})
        self.assertEqual(s["failedStep"], "chk-e")
        self.assertIn("exit 3, expected 0", steps(s)["chk-e"]["error"])
        p, s, text = self.run_checks([{"name": "e", "run": ["python", "x"], "exit": 3}], codes={"chk-e": 3})
        self.assertTrue(s["ok"])
        p, s, text = self.run_checks([{"name": "v", "file": "{wt}/out/x/summary.json", "path": "routeSheet.new", "equals": 25}])
        self.assertEqual(s["failedStep"], "chk-v")
        self.assertIn("routeSheet.new is 24, expected 25", steps(s)["chk-v"]["error"])
        p, s, text = self.run_checks([{"name": "m", "file": "{wt}/out/x/summary.json", "path": "routeSheet.gone", "equals": 0}])
        self.assertIn("no routeSheet.gone", steps(s)["chk-m"]["error"])
        p, s, text = self.run_checks([{"name": "n", "run": ["python", "x"], "minTests": 1}])
        self.assertIn("no test count", steps(s)["chk-n"]["error"])

    def test_a_bad_checks_file_exits_2(self):
        root = self.tmp()
        fixture(root)
        for checks, needle in (([{"name": "x", "run": ["a"], "expect": 1}], "unknown key expect"),
                               ([{"name": "X y", "run": ["a"]}], "lower-case"),
                               ([{"name": "x", "file": "f.json"}], "file needs path"),
                               ([{"name": "x", "run": ["a"], "path": "a"}], "path and equals"),
                               ([{"name": "x", "run": ["a"]}, {"name": "x", "run": ["b"]}], "used twice"),
                               ({"checks": []}, "holds no checks")):
            p, s, text = run(root, "-Checks", self.checks_file(checks), parts=False, emu=False)
            self.assertEqual(p.returncode, 2, (checks, p.stdout))
            self.assertIn(needle, p.stdout)


if __name__ == "__main__":
    unittest.main()
