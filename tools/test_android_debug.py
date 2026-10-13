"""T86: launch-crash parsing of android_debug.py.  Run: python -I tools/test_android_debug.py"""
import importlib.util
import os

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
print("ok")
