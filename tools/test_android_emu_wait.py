"""T375: tools/android-emu.ps1 -WaitForSlot, driven in its test mode (-SlotDecisionOnly) against a scratch lock folder
(-TestLockDir) with fake live locks, and no adb: the script stops after the slot decision and never builds or boots.

A fake holder is a lock file emulator-<port>.json still "booting" (no qemu pid) whose bootedBy is a live PowerShell
process this test starts (a sleeper), which the script's liveness rule reads as a live lock; deleting the file frees the
slot. A waiter is the script itself, run with -SlotDecisionOnly -TestLockDir <dir> -WaitForSlot <minutes>.

Cases: two holders and a waiter (it announces once, naming the holders, and takes the slot when one lock goes); the
refusal at the time limit, naming the holders and the wait, and at once without -WaitForSlot; an older ticket goes first;
two real waiters served oldest first; a waiter cut off that asks again keeps its place; a dead ticket reclaimed (and one
dead for less than the grace kept); the test-only switches refused outside the test mode, and the test mode stopping
before any build or boot.

    python -I tools/test_android_emu_wait.py
"""
import json
import os
import shutil
import subprocess
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone

here = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(here, "android-emu.ps1")
PS = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass"]


def iso(dt):
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def write_json(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f)


def wait_for(cond, timeout, what):
    t = time.time()
    while time.time() - t < timeout:
        if cond():
            return
        time.sleep(0.5)
    raise AssertionError(f"timed out after {timeout} s waiting for {what}")


class Waiter:
    def __init__(self, test, owner, port, minutes, hold=0, extra=()):
        self.owner = owner
        self.log = os.path.join(test.dir, f"{owner}-{len(test.procs)}.log")
        self.fh = open(self.log, "w", encoding="utf-8")
        test.files.append(self.fh)
        cmd = PS + ["-File", SCRIPT, "-Avd", f"dystopo35-{owner}", "-EmuPort", str(port), "-Port", str(port + 3000), "-Owner", owner,
                    "-SlotDecisionOnly", "-TestLockDir", test.locks, "-WaitForSlot", str(minutes), *extra]
        if hold:
            cmd += ["-TestHoldSeconds", str(hold)]
        self.p = subprocess.Popen(cmd, stdout=self.fh, stderr=subprocess.STDOUT)
        test.procs.append(self.p)

    def text(self):
        with open(self.log, encoding="utf-8", errors="replace") as f:
            return f.read()

    def finish(self, timeout):
        rc = self.p.wait(timeout=timeout)
        self.fh.close()
        return rc, self.text()


class Base(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="emu-wait-test-")
        self.locks = os.path.join(self.dir, "locks")
        os.makedirs(self.locks)
        self.procs = []
        self.files = []

    def tearDown(self):
        for p in self.procs:
            if p.poll() is None:
                p.kill()
                p.wait(30)
        for f in self.files:
            f.close()
        shutil.rmtree(self.dir, ignore_errors=True)

    def sleeper(self):
        p = subprocess.Popen(PS + ["-Command", "Start-Sleep -Seconds 900"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.procs.append(p)
        return p.pid

    def holder(self, owner, port):
        f = os.path.join(self.locks, f"emulator-{port}.json")
        write_json(f, {"serial": f"emulator-{port}", "consolePort": port, "avd": f"dystopo35-{owner}", "owner": owner, "pid": None,
                       "emulatorPid": None, "hostPort": port + 3000, "checkout": "<repo>", "bootedBy": self.sleeper(),
                       "startedAt": iso(datetime.now(timezone.utc)), "state": "booting"})
        return f

    def ticket(self, owner, pid, since_ago_s, seen_ago_s):
        now = datetime.now(timezone.utc)
        f = os.path.join(self.locks, f"wait-{owner}.json")
        write_json(f, {"owner": owner, "pid": pid, "avd": f"dystopo35-{owner}", "serial": "emulator-5650", "checkout": "<repo>",
                       "since": iso(now - timedelta(seconds=since_ago_s)), "seen": iso(now - timedelta(seconds=seen_ago_s))})
        return f

    def tickets(self):
        return sorted(n for n in os.listdir(self.locks) if n.startswith("wait-"))

    def run_once(self, owner, port, minutes, extra=()):
        w = Waiter(self, owner, port, minutes, extra=extra)
        return w.finish(120)


class TwoHoldersAndAWaiter(Base):
    def test_announces_once_and_takes_the_slot_when_a_lock_goes(self):
        a = self.holder("holdA", 5600)
        self.holder("holdB", 5602)
        w = Waiter(self, "w1", 5590, 2)
        wait_for(lambda: "waiting up to" in w.text(), 60, "the announcement")
        line = [x for x in w.text().splitlines() if "waiting up to" in x][0]
        for needle in ("2 of 2 running", "owner holdA", "owner holdB"):
            self.assertIn(needle, line)
        time.sleep(8)  # two or three more tries: still one announcement, still waiting, its ticket in the folder
        self.assertIsNone(w.p.poll(), w.text())
        self.assertEqual(w.text().count("waiting up to"), 1)
        self.assertEqual(self.tickets(), ["wait-w1.json"])
        os.remove(a)
        rc, out = w.finish(60)
        self.assertEqual(rc, 0, out)
        self.assertIn("slot decision: emulator-5590 taken", out)
        self.assertIn("waited", out)
        self.assertEqual(out.count("waiting up to"), 1)
        with open(os.path.join(self.locks, "emulator-5590.json"), encoding="utf-8") as f:
            self.assertEqual(json.load(f)["owner"], "w1")
        self.assertEqual(self.tickets(), [])  # the ticket ends when the lock is written

    def test_refuses_at_the_limit_naming_the_holders(self):
        self.holder("holdA", 5600)
        self.holder("holdB", 5602)
        t = time.time()
        rc, out = self.run_once("w1", 5590, 0.1)
        self.assertNotEqual(rc, 0, out)
        self.assertGreaterEqual(time.time() - t, 6)
        flat = " ".join(out.split())
        for needle in ("emulator cap reached: 2 of 2 running", "owner holdA", "owner holdB", "Not booting dystopo35-w1 on emulator-5590",
                       "Waited 0.1 min (-WaitForSlot 0.1)", "Wait in the foreground until one is stopped"):
            self.assertIn(needle, flat)
        self.assertNotIn("slot decision", out)
        self.assertEqual(self.tickets(), [])
        self.assertFalse(os.path.exists(os.path.join(self.locks, "emulator-5590.json")))
        rc, out = self.run_once("w1", 5590, 0)  # no -WaitForSlot: today's refusal at once
        self.assertNotEqual(rc, 0, out)
        flat = " ".join(out.split())
        self.assertIn("emulator cap reached: 2 of 2 running", flat)
        self.assertNotIn("waiting up to", flat)
        self.assertNotIn("Waited", flat)


class OldestFirst(Base):
    def test_an_older_ticket_keeps_a_free_slot(self):
        self.holder("holdA", 5600)  # one slot of two free
        old = self.ticket("old", self.sleeper(), 60, 1)
        w = Waiter(self, "w1", 5590, 2)
        wait_for(lambda: "waiting up to" in w.text(), 60, "the announcement")
        self.assertIn("1 waiting before this one (owner old)", " ".join(w.text().split()))
        time.sleep(7)
        self.assertIsNone(w.p.poll(), w.text())
        os.remove(old)
        rc, out = w.finish(60)
        self.assertEqual(rc, 0, out)
        self.assertIn("slot decision", out)

    def test_two_waiters_are_served_oldest_first(self):
        a = self.holder("holdA", 5600)
        self.holder("holdB", 5602)
        w1 = Waiter(self, "w1", 5590, 3, hold=15)
        wait_for(lambda: "waiting up to" in w1.text(), 60, "w1's announcement")
        time.sleep(1)
        w2 = Waiter(self, "w2", 5592, 3)
        wait_for(lambda: "waiting up to" in w2.text(), 60, "w2's announcement")
        self.assertIn("1 waiting before this one (owner w1)", " ".join(w2.text().split()))
        os.remove(a)
        wait_for(lambda: "slot decision" in w1.text(), 60, "w1 to take the slot")
        self.assertNotIn("slot decision", w2.text())  # w1 holds its lock 15 s: w2 still waits
        time.sleep(5)
        self.assertNotIn("slot decision", w2.text())
        rc1, _ = w1.finish(60)
        rc2, out2 = w2.finish(90)  # w1's script ends, its lock (still booting) is reclaimed, w2 takes it
        self.assertEqual((rc1, rc2), (0, 0), out2)
        self.assertIn("reclaimed the lock of emulator-5590", out2)
        self.assertIn("slot decision: emulator-5592 taken", out2)

    def test_a_waiter_that_asks_again_keeps_its_place(self):
        a = self.holder("holdA", 5600)
        self.holder("holdB", 5602)
        w1 = Waiter(self, "w1", 5590, 3)
        wait_for(lambda: "waiting up to" in w1.text(), 60, "w1's announcement")
        with open(os.path.join(self.locks, "wait-w1.json"), encoding="utf-8") as f:
            since = json.load(f)["since"]
        time.sleep(1)
        w2 = Waiter(self, "w2", 5592, 3)
        wait_for(lambda: "waiting up to" in w2.text(), 60, "w2's announcement")
        w1.p.kill()  # cut off, as an agent's foreground command is at its ten-minute limit
        w1.p.wait(30)
        self.assertIn("wait-w1.json", self.tickets())  # a killed script leaves its ticket
        w1b = Waiter(self, "w1", 5590, 3, hold=15)
        wait_for(lambda: "waiting up to" in w1b.text(), 60, "w1's second announcement")
        self.assertIn("kept from", w1b.text())
        with open(os.path.join(self.locks, "wait-w1.json"), encoding="utf-8") as f:
            self.assertEqual(json.load(f)["since"], since)
        self.assertIn("1 waiting before this one (owner w1)", " ".join(w2.text().split()))
        os.remove(a)
        wait_for(lambda: "slot decision" in w1b.text(), 60, "w1 to take the slot")
        self.assertNotIn("slot decision", w2.text())
        rc, out = w1b.finish(60)
        self.assertEqual(rc, 0, out)


class DeadTickets(Base):
    def test_a_dead_ticket_is_reclaimed_and_a_fresh_one_kept(self):
        self.holder("holdA", 5600)  # one slot free
        done = subprocess.run(PS + ["-Command", "$PID"], capture_output=True, text=True)
        dead_pid = int(done.stdout.strip())
        fresh = self.ticket("fresh", dead_pid, 300, 10)  # its script gone, but seen 10 s ago: within the grace
        rc, out = self.run_once("w1", 5590, 0)
        self.assertNotEqual(rc, 0, out)
        self.assertIn("1 waiting before this one (owner fresh)", " ".join(out.split()))
        self.assertTrue(os.path.exists(fresh))
        os.remove(fresh)
        gone = self.ticket("gone", dead_pid, 1200, 600)  # gone and not seen for ten minutes
        rc, out = self.run_once("w1", 5590, 0)
        self.assertEqual(rc, 0, out)
        self.assertIn("reclaimed the waiting ticket of owner gone", out)
        self.assertIn("slot decision", out)
        self.assertFalse(os.path.exists(gone))


class TheOverrideCannotBoot(Base):
    def test_test_switches_are_refused_outside_the_test_mode(self):
        for extra in (["-TestLockDir", self.locks], ["-TestAdbSerials", "emulator-5600"], ["-TestHoldSeconds", "5"]):
            p = subprocess.run(PS + ["-File", SCRIPT, "-Avd", "dystopo35-x", "-EmuPort", "5590", "-Port", "8590", "-Build", "-WaitForSlot", "1", *extra],
                               capture_output=True, text=True, timeout=120)
            out = " ".join((p.stdout + p.stderr).split())
            self.assertNotEqual(p.returncode, 0, out)
            self.assertIn("go only with -SlotDecisionOnly", out)
            for never in ("building the debug APK", "creating AVD", "starting emulator", "lock "):
                self.assertNotIn(never, p.stdout)
        self.assertEqual(os.listdir(self.locks), [])

    def test_the_test_mode_stops_before_any_build_or_boot(self):
        rc, out = self.run_once("w1", 5590, 1, extra=["-Build", "-TestAdbSerials", "emulator-5600"])
        self.assertEqual(rc, 0, out)
        self.assertIn("nothing built, created or booted", out)
        for never in ("building the debug APK", "creating AVD", "starting emulator", "installing", "waiting for sys.boot_completed"):
            self.assertNotIn(never, out)
        p = subprocess.run(PS + ["-File", SCRIPT, "-Avd", "dystopo35-x", "-EmuPort", "5590", "-SlotDecisionOnly", "-TestLockDir", self.locks, "-Stop"],
                           capture_output=True, text=True, timeout=120)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("-SlotDecisionOnly takes -Avd and -EmuPort", " ".join((p.stdout + p.stderr).split()))
        # The script itself: the test mode's exit comes before the first build, AVD creation and boot in the source.
        with open(SCRIPT, encoding="utf-8") as f:
            src = f.read()
        stop = src.index("-SlotDecisionOnly stops here")
        exit_at = src.index("exit 0", stop)
        for later in ("Start-Detached (Join-Path $repoRoot 'gradlew.bat')", "create avd -n", "Start-Detached $emulator $emuArgs"):
            self.assertGreater(src.index(later), exit_at, later)


if __name__ == "__main__":
    unittest.main()
