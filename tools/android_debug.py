"""Finding the Dystopo app's debug server through adb (T57), shared by android-check.py and android-probe.py.

Debug builds start their debug routes on a random loopback port per launch and announce it, with the
per-launch token, only in logcat:

    I/Dystopo( 1234): debug server on port 41237 token 0f3a...

`endpoint()` reads that line for the app's current process; `bootstrap()` also points a host port at it with
`adb forward`. With the app setting `debugFixedPort=1` the line names 8686 (the tile server, which then also
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

PACKAGE = "io.github.jasondorjeshort.dystopo"
TAG = "Dystopo"
LINE = re.compile(r"debug server on port (\d+) token ([0-9a-f]+)")


def adb_path():
    sdk = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("USERPROFILE", os.path.expanduser("~")), "Android", "sdk")
    exe = os.path.join(sdk, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
    return exe if os.path.isfile(exe) else None


def adb(exe, serial, *args, timeout=120):
    cmd = [exe] + (["-s", serial] if serial else []) + list(args)
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    return p.returncode, p.stdout + p.stderr


def forward_owner(exe, port):
    """The serial whose `adb forward` holds host tcp:<port>, or None."""
    _, out = adb(exe, None, "forward", "--list", timeout=30)
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[1] == f"tcp:{port}":
            return parts[0]
    return None


def app_pid(exe, serial):
    code, out = adb(exe, serial, "shell", "pidof", PACKAGE, timeout=30)
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


def launch_crash(exe, serial, since=None):
    """The app's uncaught exception since `since` (from `device_now`), as "Type: message (Process: ..., PID n)", or None.

    Reads logcat's crash buffer for a `FATAL EXCEPTION` of our package, so a launch that dies reports the
    exception instead of "no loaded style" after a long wait.
    """
    args = ["logcat", "-d", "-b", "crash", "-v", "brief"]
    if since:
        args += ["-t", since]
    _, out = adb(exe, serial, *args, timeout=60)
    return parse_crash(out)


def parse_crash(text):
    """The first `FATAL EXCEPTION` of our package in logcat `-v brief` text, as "Type: message [Process: ..., PID n]"."""
    lines = text.splitlines()
    for i, ln in enumerate(lines):
        if "FATAL EXCEPTION" not in ln:
            continue
        block = [b.split("): ", 1)[-1].strip() for b in lines[i + 1:i + 40]]
        proc = next((b for b in block if b.startswith("Process:")), "")
        if PACKAGE not in proc:
            continue
        exc = next((b for b in block if re.match(r"[A-Za-z0-9_.$]+(Exception|Error)(?::|$)", b)), "uncaught exception")
        return f"{exc} [{proc}]"
    return None


def raise_if_crashed(exe, serial, since=None):
    crash = launch_crash(exe, serial, since)
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
