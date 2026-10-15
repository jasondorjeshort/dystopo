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
got = m.parse_crash(SAMPLE)
assert got and got.startswith("java.lang.IllegalStateException: Reading a state") and "PID: 6796" in got, got
assert m.parse_crash(SAMPLE.replace("io.github.jasondorjeshort.dystopo", "com.other")) is None
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
print("ok")
