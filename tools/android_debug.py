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


def endpoint(exe, serial, timeout=30.0):
    """(device port, token) from the app's logcat line, for its current process; waits up to `timeout` s."""
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
            raise RuntimeError(f"no 'debug server on port' line in {serial}'s logcat for {PACKAGE} (pid {pid}) within {timeout:.0f} s")
        time.sleep(0.5)


def bootstrap(exe, serial, host_port, timeout=30.0):
    """Reads the endpoint and forwards host tcp:<host_port> to it; returns (device port, token)."""
    port, token = endpoint(exe, serial, timeout)
    code, out = adb(exe, serial, "forward", f"tcp:{host_port}", f"tcp:{port}", timeout=30)
    if code != 0:
        raise RuntimeError(f"adb forward tcp:{host_port} tcp:{port} failed: {out.strip()}")
    return port, token


def health_token(base, timeout=10.0):
    """The debugFixedPort fallback: GET /health without a token answers {"token": ...} to loopback callers."""
    with urllib.request.urlopen(base + "/health", timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))["token"]
