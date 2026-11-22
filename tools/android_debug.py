"""Finding the Dystopo app's debug server through adb (T57), shared by android-check.py and android-probe.py.

Debug builds start their debug routes on a random loopback port per launch and announce it, with the
per-launch token, only in logcat:

    I/Dystopo( 1234): debug server on port 41237 token 0f3a...

`endpoint()` reads that line for the app's current process; `bootstrap()` also points a host port at it with
`adb forward`. With the app setting `debugFixedPort=1` the line names the tile server's port (8688 in a debug build, T303; which then also
answers the debug routes and hands out the token from GET /health without one, as before T57); `health_token()`
is that fallback, for a host port forwarded by hand with no adb serial to read.

The scripts run with `python -I`, which leaves the script directory off sys.path, so they load this file by path.
"""
import json
import os
import re
import subprocess
import time
import urllib.request

# T303: the app's two application ids, the one place the Python scripts read them from. The debug build has its own id
# (androidApp/build.gradle.kts, applicationIdSuffix ".debug"), so it installs beside the release on one phone; its tile
# server is on device port 8688, the release's on 8686. Debug-build steps (debug routes, run-as) use DEBUG_PACKAGE; the
# release parts (taps only) use RELEASE_PACKAGE. The activity's class name is the Kotlin package's and does not change.
RELEASE_PACKAGE = "io.github.jasondorjeshort.dystopo"
DEBUG_PACKAGE = RELEASE_PACKAGE + ".debug"
PACKAGE = DEBUG_PACKAGE  # what this module's debug-port helpers look for
MAIN_ACTIVITY = "io.github.jasondorjeshort.dystopo.app.MainActivity"
RELEASE_TILE_PORT = 8686
DEBUG_TILE_PORT = 8688


def activity(package):
    """The launcher activity's component for `am start -n`: <package>/<the full class name>."""
    return f"{package}/{MAIN_ACTIVITY}"


TAG = "Dystopo"
LINE = re.compile(r"debug server on port (\d+) token ([0-9a-f]+)")


def adb_path():
    sdk = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("USERPROFILE", os.path.expanduser("~")), "Android", "sdk")
    exe = os.path.join(sdk, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
    return exe if os.path.isfile(exe) else None


def run_bounded(cmd, timeout, input=None, grace=10.0, capture_output=True, **kw):
    """T191: `subprocess.run(cmd, capture_output=True, timeout=...)` with every wait bounded. On a timeout `run()` kills
    the child and then reads its pipes with no limit, and its `with` block waits for the exit with none: a reader thread
    that died (MemoryError when the paging file ran out) or a grandchild holding an inherited pipe (an adb server) kept
    the post-wave sweep waiting 9.5 h (finding 2026-10-31). Here the child is killed by its own handle, never a tree
    (an adb client's only child can be the shared adb server), and the pipes get `grace` seconds; still open after
    that, they are abandoned (closing them would wait on the blocked reader) and TimeoutExpired is raised as `run()`
    would. A process that cannot be created raises OSError at once (WinError 1455 when the host is out of commit)."""
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE if input is not None else None,
                         stdout=subprocess.PIPE if capture_output else None, stderr=subprocess.PIPE if capture_output else None, **kw)
    try:
        out, err = p.communicate(input, timeout=timeout)
    except subprocess.TimeoutExpired:
        p.kill()
        try:
            out, err = p.communicate(timeout=grace)
        except subprocess.TimeoutExpired:
            out = err = None
        raise subprocess.TimeoutExpired(cmd, timeout, output=out, stderr=err) from None
    return subprocess.CompletedProcess(cmd, p.returncode, out, err)


# T303, the phone guard: sweep.ps1 -Phone sets DYSTOPO_PHONE_GUARD to the phone's serial for its phone parts. Then every
# adb call this module makes to that serial is refused when it would install, uninstall or clear anything, or names the
# release package or its data (the owner's own app and data): the phone parts touch only the debug package.
GUARD_ENV = "DYSTOPO_PHONE_GUARD"


class PhoneGuardRefused(RuntimeError):
    """An adb call the phone guard refused (T303); the message names the call."""


def guard_problem(serial, args):
    """Why the phone guard refuses `adb -s <serial> <args>`, or None (also None when no guard is set for that serial)."""
    guarded = os.environ.get(GUARD_ENV, "")
    if not guarded or serial != guarded:
        return None
    words = " ".join(str(a) for a in args).split()
    for i, w in enumerate(words):
        w = w.strip("'\"")
        if w in ("install", "install-multiple", "uninstall") or (w == "clear" and i and words[i - 1].strip("'\"") == "pm"):
            return f"'{w}' on the phone"
        if w == RELEASE_PACKAGE or w.startswith(RELEASE_PACKAGE + "/") or f"/{RELEASE_PACKAGE}/" in w or w.endswith(f"/{RELEASE_PACKAGE}"):
            return f"the release package ({w}) on the phone"
    return None


def adb(exe, serial, *args, timeout=120):
    why = guard_problem(serial, args)
    if why:
        raise PhoneGuardRefused(f"refused by the phone guard (T303): {why}: adb -s {serial} {' '.join(map(str, args))}")
    cmd = [exe] + (["-s", serial] if serial else []) + list(args)
    p = run_bounded(cmd, timeout, text=True, encoding="utf-8", errors="replace")
    return p.returncode, p.stdout + p.stderr


def forward_owner(exe, port):
    """The serial whose `adb forward` holds host tcp:<port>, or None."""
    _, out = adb(exe, None, "forward", "--list", timeout=30)
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[1] == f"tcp:{port}":
            return parts[0]
    return None


def app_pid(exe, serial, package=None):
    code, out = adb(exe, serial, "shell", "pidof", package or PACKAGE, timeout=30)
    out = out.strip().split()
    return out[0] if code == 0 and out and out[0].isdigit() else None


class LaunchCrash(RuntimeError):
    """T86: the app died with an uncaught exception (the message carries its first line from logcat)."""


DATE_FORMAT = "'+%m-%d %H:%M:%S.000'"


def device_now(exe, serial):
    """The device clock in logcat's `-t` format, to pass as `since` to `launch_crash` (taken before a launch).

    T105: `adb shell` joins its arguments with spaces and the device's shell splits them again, so the format goes
    quoted as one word (unquoted, `date` got `+%m-%d` and `%H:%M:%S.000`, refused them, and this always returned None,
    so a crash lookup read the whole crash buffer)."""
    code, out = adb(exe, serial, "shell", "date", DATE_FORMAT, timeout=30)
    out = out.strip()
    return out if code == 0 and re.fullmatch(r"\d\d-\d\d \d\d:\d\d:\d\d\.000", out) else None


CRASH_DIR = os.path.join("out", "launch-crashes")  # T328: under the working directory (the repo root for every caller)
CRASH_BLOCK_MAX = 400
_BRIEF_PID = re.compile(r"^\w/[^(]*\(\s*(\d+)\):")


def launch_crash(exe, serial, since=None, package=None, out_dir=None):
    """The app's uncaught exception since `since` (from `device_now`), as "Type: message [Process: ..., PID n]
    (stack: <file>)", or None.

    Reads logcat's crash buffer for a `FATAL EXCEPTION` of our package, so a launch that dies reports the
    exception instead of "no loaded style" after a long wait. T328: the whole crash block (the stack with its
    "Caused by" chain, at most CRASH_BLOCK_MAX lines) is written to `out/launch-crashes/launch-crash-<serial>-<time>.txt`
    under the working directory (or `out_dir`), and the string ends with that path; callers take the string as before.
    """
    args = ["logcat", "-d", "-b", "crash", "-v", "brief"]
    if since:
        args += ["-t", since]
    _, out = adb(exe, serial, *args, timeout=60)
    block = crash_block(out, package)
    if not block:
        return None
    summary = summarize_crash(block)
    path = save_crash(block, serial, out_dir)
    return f"{summary} (stack: {path})" if path else f"{summary} (stack not saved)"


def crash_block(text, package=None):
    """T328: the lines of the last `FATAL EXCEPTION` block of our package in logcat `-v brief` text, from the FATAL line
    through the last line that process logged before another FATAL line, CRASH_BLOCK_MAX lines at most; [] when none.
    The last, not the first: with no `since` (android-a11y.py's bootstrap) the buffer can hold older crashes.
    T303: `package` (default the debug build's) must be the process name exactly: the release id is a prefix of the debug's."""
    lines = text.splitlines()
    found = []
    for i, ln in enumerate(lines):
        if "FATAL EXCEPTION" not in ln:
            continue
        m = _BRIEF_PID.match(ln)
        pid = m.group(1) if m else None
        block = [ln]
        for b in lines[i + 1:]:
            if "FATAL EXCEPTION" in b or len(block) >= CRASH_BLOCK_MAX:
                break
            bm = _BRIEF_PID.match(b)
            if pid is None or (bm and bm.group(1) == pid):
                block.append(b)
            elif bm is None and not b.startswith("---------"):
                block.append(b)  # a continuation line without a header (another log format): keep it
        body = [b.split("): ", 1)[-1].strip() for b in block[1:]]
        proc = next((b for b in body if b.startswith("Process:")), "")
        if re.match(rf"Process: {re.escape(package or PACKAGE)}(,|$)", proc):
            found = block
    return found


def summarize_crash(block):
    """"Type: message [Process: ..., PID n]" from a crash block (crash_block's lines)."""
    body = [b.split("): ", 1)[-1].strip() for b in block[1:]]
    proc = next((b for b in body if b.startswith("Process:")), "")
    exc = next((b for b in body if re.match(r"[A-Za-z0-9_.$]+(Exception|Error)(?::|$)", b)), "uncaught exception")
    return f"{exc} [{proc}]"


def save_crash(block, serial, out_dir=None):
    """Writes a crash block to <out_dir or out/launch-crashes>/launch-crash-<serial>-<host time>.txt; the path, or None
    when it cannot be written (a crash report never fails for its file)."""
    d = out_dir or CRASH_DIR
    name = f"launch-crash-{re.sub(r'[^A-Za-z0-9_.-]', '_', serial or 'device')}-{time.strftime('%Y%m%d-%H%M%S')}"
    try:
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, name + ".txt")
        n = 1
        while os.path.exists(path):
            n += 1
            path = os.path.join(d, f"{name}-{n}.txt")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(block) + "\n")
        return path.replace("\\", "/")
    except OSError:
        return None


def parse_crash(text, package=None):
    """The last `FATAL EXCEPTION` of our package in logcat `-v brief` text, as "Type: message [Process: ..., PID n]",
    or None (crash_block's rule)."""
    block = crash_block(text, package)
    return summarize_crash(block) if block else None


def raise_if_crashed(exe, serial, since=None, package=None):
    crash = launch_crash(exe, serial, since, package)
    if crash:
        raise LaunchCrash(f"the app crashed: {crash}")


def endpoint(exe, serial, timeout=30.0, since=None):
    """(device port, token) from the app's logcat line, for its current process; waits up to `timeout` s.

    T86: when the line never comes and the app crashed since `since`, raises `LaunchCrash` with the exception line.
    """
    deadline = time.time() + timeout
    while True:
        pid = app_pid(exe, serial)
        if pid:
            _, out = adb(exe, serial, "logcat", "-d", "-v", "brief", f"--pid={pid}", "-s", f"{TAG}:I", timeout=60)
            found = LINE.findall(out)
            if found:
                port, token = found[-1]
                return int(port), token
        if time.time() >= deadline:
            raise_if_crashed(exe, serial, since)
            raise RuntimeError(f"no 'debug server on port' line in {serial}'s logcat for {PACKAGE} (pid {pid}) within {timeout:.0f} s")
        time.sleep(0.5)


def bootstrap(exe, serial, host_port, timeout=30.0, since=None):
    """Reads the endpoint and forwards host tcp:<host_port> to it; returns (device port, token)."""
    port, token = endpoint(exe, serial, timeout, since)
    code, out = adb(exe, serial, "forward", f"tcp:{host_port}", f"tcp:{port}", timeout=30)
    if code != 0:
        raise RuntimeError(f"adb forward tcp:{host_port} tcp:{port} failed: {out.strip()}")
    return port, token


def parse_cpu(text):
    """(system busy ticks, total ticks, cores, process ticks or None) from `cat /proc/stat; cat /proc/<pid>/stat` output.
    Idle, iowait and steal count as not busy (steal is the host's other emulators, not work in this guest)."""
    busy = total = cores = 0
    proc = None
    for line in text.splitlines():
        f = line.split()
        if not f:
            continue
        if f[0] == "cpu":
            v = [int(x) for x in f[1:9]] + [0] * (8 - len(f[1:9]))
            total = sum(v)
            busy = total - v[3] - v[4] - v[7]
        elif f[0].startswith("cpu") and f[0][3:].isdigit():
            cores += 1
        elif ")" in line and f[0].isdigit():
            rest = line.rsplit(")", 1)[1].split()  # after the command name, which may hold spaces: state is field 3
            proc = int(rest[11]) + int(rest[12])  # utime, stime (fields 14 and 15)
    return busy, total, cores, proc


def wait_quiet(exe, serial, pid=None, max_busy=1.0, window=3.0, need=2, timeout=240.0):
    """T105: waits until the device is quiet: the CPU the system uses besides process `pid` (the app, which may be warming
    up on purpose) is at most `max_busy` cores in `need` windows of `window` s in a row, or until `timeout`. A just-booted
    emulator spends one to two minutes near all of its four cores on first-boot work (package optimisation, the
    installs), during which the debug build decodes a block in seconds instead of a tenth of one (T88, T98): a cold
    view started then missed the tour's 30 s idle. Returns {"quiet", "waitedS", "windows": [[others, app], ...]}."""
    cmd = "cat /proc/stat" + (f"; cat /proc/{pid}/stat" if pid else "")
    t0 = time.time()
    _, out = adb(exe, serial, "shell", cmd, timeout=30)
    prev = parse_cpu(out)
    windows, run = [], 0
    while True:
        time.sleep(window)
        _, out = adb(exe, serial, "shell", cmd, timeout=30)
        cur = parse_cpu(out)
        dt = cur[1] - prev[1]
        if dt <= 0 or not cur[2]:
            return {"quiet": None, "waitedS": round(time.time() - t0, 1), "windows": windows, "error": "no /proc/stat"}
        cores = cur[2]
        busy = (cur[0] - prev[0]) / dt * cores
        app = ((cur[3] - prev[3]) / dt * cores) if cur[3] is not None and prev[3] is not None else 0.0
        others = max(0.0, busy - app)
        windows.append([round(others, 2), round(app, 2)])
        run = run + 1 if others <= max_busy else 0
        prev = cur
        if run >= need:
            return {"quiet": True, "waitedS": round(time.time() - t0, 1), "windows": windows[-40:]}
        if time.time() - t0 >= timeout:
            return {"quiet": False, "waitedS": round(time.time() - t0, 1), "windows": windows[-40:]}


def health_token(base, timeout=10.0):
    """The debugFixedPort fallback: GET /health without a token answers {"token": ...} to loopback callers."""
    with urllib.request.urlopen(base + "/health", timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))["token"]


def main(argv=None):
    """T303: `python -I tools/android_debug.py get --serial S --port P --path /diagnostics [--launch]`: starts the debug
    build when --launch (or when it is not running), forwards --port to its debug server and prints the route's JSON.
    T328: `python -I tools/android_debug.py crash --serial S [--package P]` prints {"crash": <launch_crash's line or
    null>}, saving the crash buffer's last block of the package to out/launch-crashes/ (exit 1 when there is one)."""
    import argparse
    ap = argparse.ArgumentParser(description="GET one debug route of the debug build through adb, or read its last crash")
    ap.add_argument("cmd", choices=["get", "crash"])
    ap.add_argument("--serial", required=True)
    ap.add_argument("--port", type=int)
    ap.add_argument("--path")
    ap.add_argument("--package", default=PACKAGE, help="crash: the process name (default the debug build's)")
    ap.add_argument("--launch", action="store_true", help="start the app first (am start -W) even when it runs")
    a = ap.parse_args(argv)
    exe = adb_path()
    if not exe:
        print(json.dumps({"error": "adb not found (set ANDROID_HOME)"}))
        return 2
    if a.cmd == "crash":
        crash = launch_crash(exe, a.serial, None, a.package)
        print(json.dumps({"serial": a.serial, "package": a.package, "crash": crash}))
        return 1 if crash else 0
    if a.port is None or not a.path:
        ap.error("get needs --port and --path")
    try:
        if a.launch or not app_pid(exe, a.serial):
            adb(exe, a.serial, "shell", "am", "start", "-W", "-n", activity(PACKAGE), timeout=120)
        _, token = bootstrap(exe, a.serial, a.port, timeout=60.0)
        sep = "&" if "?" in a.path else "?"
        with urllib.request.urlopen(f"http://127.0.0.1:{a.port}{a.path}{sep}t={token}", timeout=60) as r:
            body = json.loads(r.read().decode("utf-8"))
    except Exception as e:
        print(json.dumps({"error": f"{type(e).__name__}: {e}"[:400], "path": a.path}))
        return 1
    print(json.dumps({"path": a.path, "package": PACKAGE, "body": body}))
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
