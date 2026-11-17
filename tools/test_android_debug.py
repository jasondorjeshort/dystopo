"""T86: launch-crash parsing of android_debug.py; T105: device_now.  Run: python -I tools/test_android_debug.py"""
import importlib.util
import os
import shlex
import time

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("android_debug", os.path.join(here, "android_debug.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

SAMPLE = """--------- beginning of crash
E/AndroidRuntime( 6796): FATAL EXCEPTION: main
E/AndroidRuntime( 6796): Process: io.github.jasondorjeshort.dystopo, PID: 6796
E/AndroidRuntime( 6796): java.lang.IllegalStateException: Reading a state that was created after the snapshot was taken
E/AndroidRuntime( 6796): 	at androidx.compose.runtime.snapshots.SnapshotKt.readError(Snapshot.kt:2109)
"""
got = m.parse_crash(SAMPLE, m.RELEASE_PACKAGE)
assert got and got.startswith("java.lang.IllegalStateException: Reading a state") and "PID: 6796" in got, got
assert m.parse_crash(SAMPLE.replace("io.github.jasondorjeshort.dystopo", "com.other")) is None
# T303: the debug build is <release id>.debug; each id matches only its own process, never the other's by prefix.
assert m.DEBUG_PACKAGE == m.RELEASE_PACKAGE + ".debug" and m.PACKAGE == m.DEBUG_PACKAGE
REL = SAMPLE.replace("io.github.jasondorjeshort.dystopo,", m.RELEASE_PACKAGE + ",")
DBG = SAMPLE.replace("io.github.jasondorjeshort.dystopo,", m.DEBUG_PACKAGE + ",")
assert m.parse_crash(REL) is None and m.parse_crash(DBG) is not None
assert m.parse_crash(REL, m.RELEASE_PACKAGE) is not None and m.parse_crash(DBG, m.RELEASE_PACKAGE) is None
assert m.activity(m.DEBUG_PACKAGE) == "io.github.jasondorjeshort.dystopo.debug/io.github.jasondorjeshort.dystopo.app.MainActivity"
assert m.parse_crash("") is None


# T105: device_now() through a stand-in for `adb shell`, which joins its arguments with spaces and hands the line to
# the device's shell, which splits it again: that split is what gave `date` the unquoted format as two arguments.
def fake_adb(exe, serial, *args, timeout=120):
    assert args[0] == "shell", args
    argv = shlex.split(" ".join(args[1:]))
    if argv[0] != "date":
        return 127, f"/system/bin/sh: {argv[0]}: inaccessible or not found\n"
    if len(argv) != 2 or not argv[1].startswith("+"):
        return 1, f"date: bad date '{argv[-1]}'\n"
    return 0, time.strftime(argv[1][1:]) + "\n"


m.adb = fake_adb
now = m.device_now("adb", "emulator-5650")
assert now is not None and len(now) == len("10-14 21:03:07.000") and now.endswith(".000"), now
# The call before T105, for the record: the device's shell splits the unquoted format and `date` refuses it.
assert fake_adb("adb", "emulator-5650", "shell", "date", "+%m-%d %H:%M:%S.000")[0] != 0

# T191: run_bounded. A plain run, then a child that times out while a grandchild holds its pipes open (as an adb server
# holding an inherited handle can): subprocess.run would kill the child and then read the pipes until the grandchild
# exits (60 s here); run_bounded raises TimeoutExpired after the timeout plus the grace. The grandchild is ended by its
# PID afterwards (written by the child to a file).
import subprocess
import sys
import tempfile

p = m.run_bounded([sys.executable, "-I", "-c", "print('hi')"], 30, text=True)
assert p.returncode == 0 and p.stdout.strip() == "hi", p
pidfile = os.path.join(tempfile.mkdtemp(prefix="t191-"), "grandchild.pid")
child = ("import subprocess, sys, time\n"
         "g = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(60)'], stdout=sys.stdout, stderr=sys.stderr)\n"
         f"open({pidfile!r}, 'w').write(str(g.pid))\n"
         "time.sleep(60)\n")
t0 = time.time()
try:
    m.run_bounded([sys.executable, "-I", "-c", child], 2, grace=2)
    raise AssertionError("the sleeping child must time out")
except subprocess.TimeoutExpired as e:
    took = time.time() - t0
    assert e.timeout == 2 and took < 15, took
finally:
    if os.path.exists(pidfile):
        gpid = int(open(pidfile).read())
        try:
            os.kill(gpid, 9)  # our own grandchild, by its PID
        except OSError:
            pass
# A program that cannot be started fails at once (OSError), never a wait.
t0 = time.time()
try:
    m.run_bounded([os.path.join(here, "no-such-program.exe")], 30)
    raise AssertionError("a missing program must raise")
except OSError:
    assert time.time() - t0 < 2
print("ok")


# T303: the phone guard refuses installs, uninstalls, pm clear and anything naming the release package, on the guarded
# serial only; the debug package's calls pass.
os.environ.pop(m.GUARD_ENV, None)
assert m.guard_problem("phone1", ["uninstall", m.RELEASE_PACKAGE]) is None  # no guard set
os.environ[m.GUARD_ENV] = "phone1"
try:
    assert m.guard_problem("emulator-5570", ["uninstall", m.RELEASE_PACKAGE]) is None  # another serial
    for bad in (["uninstall", m.DEBUG_PACKAGE], ["install", "-r", "x.apk"], ["shell", "pm", "clear", m.DEBUG_PACKAGE],
                ["shell", "am", "force-stop", m.RELEASE_PACKAGE], ["shell", "run-as", m.RELEASE_PACKAGE, "ls"],
                ["shell", "am", "start", "-n", m.activity(m.RELEASE_PACKAGE)],
                ["shell", f"ls /sdcard/Android/data/{m.RELEASE_PACKAGE}/files"], ["shell", f"run-as {m.RELEASE_PACKAGE} ls"]):
        assert m.guard_problem("phone1", bad), bad
    for good in (["shell", "am", "force-stop", m.DEBUG_PACKAGE], ["shell", "run-as", m.DEBUG_PACKAGE, "ls", "files/packs"],
                 ["shell", "am", "start", "-W", "-n", m.activity(m.DEBUG_PACKAGE)], ["forward", "tcp:8700", "tcp:41237"],
                 ["shell", "pidof", m.DEBUG_PACKAGE], ["logcat", "-d", "-b", "crash"],
                 ["shell", f"ls /sdcard/Android/data/{m.DEBUG_PACKAGE}/files"]):
        assert m.guard_problem("phone1", good) is None, good
    fresh = importlib.util.module_from_spec(spec)  # the module's own adb (the tests above replaced m.adb)
    spec.loader.exec_module(fresh)
    try:
        fresh.adb("adb-not-run", "phone1", "uninstall", m.RELEASE_PACKAGE)
        raise AssertionError("not refused")
    except fresh.PhoneGuardRefused as e:
        assert "phone guard" in str(e)
finally:
    os.environ.pop(m.GUARD_ENV, None)
print("guard ok")
