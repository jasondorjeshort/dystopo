#!/usr/bin/env python3
"""Bad-input probes against the app's loopback server (through `adb forward`).

    python -I tools/android-probe.py [--port 8686] [--serial emulator-5570] [--host 127.0.0.1]

Sends malformed and hostile requests (huge, negative and out-of-range tile coordinates, 10k
headers, an over-long request line, a slow header drip, an oversized body, a lying Content-Length,
non-HTTP bytes, a burst of idle connections) and checks after each that `/health` still answers.
Prints one JSON object: per probe the HTTP status seen (or the connection outcome), the expected
statuses, and whether the server was alive afterwards. Exit 0 when every probe got an expected answer
and the server survived; 1 when the server died (or never answered); 2 when it survived but some
probe got an unexpected answer. Python 3 standard library only.

The app requires its per-launch loopback token on every request (T49): the script sends it as an
X-Dystopo-Token header on every probe, except the no-token and wrong-token probes, which expect 401.
T57: debug builds serve the debug routes on a random port per launch, announced with the token only in
logcat (`debug server on port N token T`). With --serial (or ANDROID_SERIAL, or the serial that owns
--port) the script reads that line, forwards --port to it, expects GET /health without a token to be a
401 (no bootstrap), and also probes the tile server (device port 8686, through a temporary forward): map
routes need the token, debug routes are 404 there with or without it. Without adb it reads the token from
GET /health without one (the app's debugFixedPort=1 mode) and expects that bootstrap to answer 200.

T78: --share probes the phone-to-phone share listener instead (the Share screen must be open: POST /share/start).
The script reads its port from GET /share/status, forwards a host port to it, and checks that only sealed requests
get through: plain sync, hello and peer-source routes 426, GET of the sealed route 405, a body that is not a frame
400, a frame under a wrong code 403 (once: five would lock this address out for ten minutes, T110), a valid frame (from the debug route
/share/peers/testframe) 200 and the same frame again 409, an unknown path 404, plus the malformed-request probes.
"""
import argparse
import importlib.util
import json
import os
import socket
import sys
import time

# T57: logcat bootstrap of the debug server's port and token (tools/android_debug.py; -I keeps tools/ off sys.path).
_spec = importlib.util.spec_from_file_location("android_debug", os.path.join(os.path.dirname(os.path.abspath(__file__)), "android_debug.py"))
android_debug = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(android_debug)


def recv_status(sock, timeout):
    """Reads until the status line is complete; returns the status code, or a string outcome."""
    sock.settimeout(timeout)
    data = b""
    try:
        while b"\r\n" not in data and len(data) < 4096:
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk
    except socket.timeout:
        return "timeout" if not data else "partial"
    except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
        return "reset" if not data else parse_status(data)
    if not data:
        return "closed"
    return parse_status(data)


def parse_status(data):
    line = data.split(b"\r\n", 1)[0].decode("latin-1", "replace")
    parts = line.split(" ")
    if len(parts) >= 2 and parts[0].startswith("HTTP/") and parts[1].isdigit():
        return int(parts[1])
    return "garbage:" + line[:40]


def raw(host, port, payload, timeout=15.0, chunks=None, delay=0.0):
    """Sends payload (or chunks with a delay between them) on a new connection; returns the status."""
    try:
        sock = socket.create_connection((host, port), timeout=5)
    except OSError as e:
        return "connect-failed:" + type(e).__name__
    with sock:
        try:
            if chunks is None:
                sock.sendall(payload)
            else:
                for c in chunks:
                    sock.sendall(c)
                    time.sleep(delay)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass  # the server may cut us off mid-send; its answer (if any) is still readable
        except socket.timeout:
            pass
        return recv_status(sock, timeout)


TOKEN = ""


def auth():
    """The token header line (empty before the token is known)."""
    return f"X-Dystopo-Token: {TOKEN}\r\n".encode() if TOKEN else b""


def get(path, token=True):
    head = f"GET {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n".encode()
    return head + (auth() if token else b"") + b"\r\n"


def read_token(host, port):
    """GET /health without a token: the app answers {"token": ...} to loopback callers (debug builds)."""
    try:
        sock = socket.create_connection((host, port), timeout=5)
    except OSError:
        return None
    with sock:
        sock.settimeout(10)
        sock.sendall(get("/health", token=False))
        data = b""
        try:
            while True:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                data += chunk
        except OSError:
            pass
    try:
        return json.loads(data.split(b"\r\n\r\n", 1)[1].decode("utf-8")).get("token")
    except (IndexError, ValueError, AttributeError):
        return None


def health(host, port):
    status = raw(host, port, get("/health"), timeout=10)
    return status == 200


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=8686)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", ""), help="device to read the debug port and token from (logcat)")
    ap.add_argument("--quick", action="store_true", help="skip the slow probes (header drip, idle connections)")
    ap.add_argument("--share", action="store_true", help="T78: probe the share listener (Share screen open) instead of the loopback servers")
    a = ap.parse_args()
    host, port = a.host, a.port

    global TOKEN
    exe = android_debug.adb_path()
    owner = android_debug.forward_owner(exe, port) if exe else None
    if a.serial and owner and owner != a.serial:
        print(json.dumps({"ok": False, "serverAlive": False, "error": f"host port {port} is forwarded to {owner}, not {a.serial}"}))
        return 1
    serial = a.serial or owner
    device_port = None
    if exe and serial:
        try:
            device_port, TOKEN = android_debug.bootstrap(exe, serial, port)  # T57: from logcat
        except RuntimeError as e:
            print(json.dumps({"ok": False, "serverAlive": False, "error": str(e)}))
            return 1
    else:
        TOKEN = read_token(host, port) or ""
    fixed = device_port in (None, 8686)  # debugFixedPort=1: debug routes and the /health bootstrap on 8686
    if not TOKEN or not health(host, port):
        print(json.dumps({"ok": False, "serverAlive": False, "error": f"/health did not answer on {host}:{port} before probing (token read: {bool(TOKEN)})"}))
        return 1
    if a.share:
        return share_probes(host, port, exe, serial)

    big = "9" * 40
    probes = [
        # name, payload builder, expected statuses
        # A valid tile (inside the Rainier pack; 404 is fine for another pack): must not be refused.
        ("tile-ok", lambda: raw(host, port, get("/dem/10/165/357.png")), {200, 404}),
        ("tile-negative-x", lambda: raw(host, port, get("/dem/10/-1/357.png")), {400}),
        ("tile-negative-z", lambda: raw(host, port, get("/osm/-3/0/0.pbf")), {400}),
        ("tile-huge-z", lambda: raw(host, port, get("/contours/99/0/0.pbf")), {400}),
        ("tile-huge-digits", lambda: raw(host, port, get(f"/dem/{big}/{big}/{big}.png")), {400}),
        ("tile-x-out-of-range", lambda: raw(host, port, get("/osm/3/8/0.pbf")), {400}),
        ("tile-int-overflow", lambda: raw(host, port, get("/contours/24/2147483648/0.pbf")), {400}),
        ("long-request-line", lambda: raw(host, port, get("/" + "a" * 20000)), {414, "reset"}),
        ("10k-headers", lambda: raw(host, port, b"GET /health HTTP/1.1\r\n" + b"".join(b"X-H%d: v\r\n" % i for i in range(10000)) + b"\r\n"), {431, "reset"}),
        ("huge-header-line", lambda: raw(host, port, b"GET /health HTTP/1.1\r\nX-Big: " + b"b" * 20000 + b"\r\n\r\n"), {431, "reset"}),
        ("oversized-body", lambda: raw(host, port, b"POST /camera HTTP/1.1\r\nContent-Length: 100000000\r\n\r\n" + b"x" * 65536), {413, "reset"}),
        ("negative-content-length", lambda: raw(host, port, b"POST /camera HTTP/1.1\r\nContent-Length: -5\r\n\r\n"), {400}),
        ("short-body", lambda: raw(host, port, b"POST /location HTTP/1.1\r\nContent-Length: 50\r\n\r\n{\"lat\":", timeout=20), {400, 408}),
        ("non-http-line", lambda: raw(host, port, b"\x16\x03\x01\x02\x00 hello there\r\n\r\n"), {400}),
        ("bad-percent-encoding", lambda: raw(host, port, get("/dem/%zz/1/1.png")), {400}),
        ("empty-connection", lambda: raw(host, port, b"", timeout=10), {"closed", 408}),
        ("unknown-route", lambda: raw(host, port, get("/nope")), {404}),
        ("location-missing-lat", lambda: raw(host, port, b"POST /location HTTP/1.1\r\n" + auth() + b"Content-Length: 0\r\n\r\n"), {400}),
        # T49: the loopback token. Without it (or with a wrong one) every route is 401; /health without it is the bootstrap.
        ("no-token-tile", lambda: raw(host, port, get("/dem/10/165/357.png", token=False)), {401}),
        ("no-token-style", lambda: raw(host, port, get("/style.json", token=False)), {401}),
        ("no-token-stats", lambda: raw(host, port, get("/stats", token=False)), {401}),
        ("no-token-camera-post", lambda: raw(host, port, b"POST /camera?lat=0&lon=0 HTTP/1.1\r\nContent-Length: 0\r\n\r\n"), {401}),
        ("wrong-token-query", lambda: raw(host, port, get("/contours/10/165/357.pbf?t=0000", token=False)), {401}),
        ("wrong-token-header", lambda: raw(host, port, b"GET /stats HTTP/1.1\r\nX-Dystopo-Token: " + (TOKEN + "x").encode() + b"\r\n\r\n"), {401}),
        ("token-query-ok", lambda: raw(host, port, get(f"/dem/10/165/357.png?t={TOKEN}", token=False)), {200, 404}),
        # T57: the token-only /health answer exists only in debugFixedPort mode; otherwise the token is in logcat.
        ("health-no-token-bootstrap" if fixed else "health-no-token-401", lambda: raw(host, port, get("/health", token=False)), {200} if fixed else {401}),
    ]
    tile_port = None
    if exe and serial and not fixed:
        # T57: the tile server on device port 8686 answers map routes only (like release); a temporary host forward.
        code, out = android_debug.adb(exe, serial, "forward", "tcp:0", "tcp:8686", timeout=30)
        tile_port = int(out.strip().splitlines()[-1]) if code == 0 and out.strip().splitlines()[-1].strip().isdigit() else None
    if tile_port:
        probes += [
            ("tile-server-style-with-token", lambda: raw(host, tile_port, get("/style.json")), {200}),
            ("tile-server-style-no-token", lambda: raw(host, tile_port, get("/style.json", token=False)), {401}),
            ("tile-server-health-no-token", lambda: raw(host, tile_port, get("/health", token=False)), {404}),
            ("tile-server-health-with-token", lambda: raw(host, tile_port, get("/health")), {404}),
            ("tile-server-stats-with-token", lambda: raw(host, tile_port, get("/stats")), {404}),
            ("tile-server-camera-post", lambda: raw(host, tile_port, b"POST /camera?lat=0&lon=0 HTTP/1.1\r\n" + auth() + b"Content-Length: 0\r\n\r\n"), {404}),
        ]
    if not a.quick:
        probes += [
            ("slow-header-drip", lambda: raw(host, port, None, chunks=[b"GET /health HTTP/1.1\r\n"] + [b"X-Drip: %d\r\n" % i for i in range(40)], delay=0.5, timeout=30), {408, "reset"}),
            ("idle-connections-x80", lambda: idle_burst(host, port, 80), {"ok"}),
        ]

    results = []
    alive = True
    for name, run, expected in probes:
        t0 = time.time()
        status = run()
        ms = int((time.time() - t0) * 1000)
        up = health(host, port)
        results.append({"probe": name, "status": status, "expected": sorted(map(str, expected)), "ok": status in expected and up, "serverAlive": up, "ms": ms})
        if not up:
            # Give a busy server a moment before declaring it dead.
            time.sleep(3)
            up = health(host, port)
            results[-1]["serverAlive"] = up
            if not up:
                alive = False
                break

    if tile_port:
        android_debug.adb(exe, serial, "forward", "--remove", f"tcp:{tile_port}", timeout=30)
    unexpected = [r["probe"] for r in results if not r["ok"]]
    print(json.dumps({"ok": alive and not unexpected, "serverAlive": alive, "unexpected": unexpected, "serial": serial,
                      "debugPort": device_port, "tileServerHostPort": tile_port, "probes": results}, indent=1))
    if not alive:
        return 1
    return 2 if unexpected else 0


def debug_json(host, port, path):
    """GET path from the debug server with the token; the JSON body (None on failure)."""
    try:
        sock = socket.create_connection((host, port), timeout=5)
    except OSError:
        return None
    with sock:
        sock.settimeout(20)
        sock.sendall(get(path))
        data = b""
        try:
            while True:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                data += chunk
        except OSError:
            pass
    try:
        return json.loads(data.split(b"\r\n\r\n", 1)[1].decode("utf-8"))
    except (IndexError, ValueError):
        return None


def post(path, body):
    return f"POST {path} HTTP/1.1\r\nHost: x\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode() + body


def share_probes(host, port, exe, serial):
    """T78: the share listener (see the module comment); one JSON object, exit codes as for the loopback probes."""
    import base64
    status = debug_json(host, port, "/share/status") or {}
    device_share = status.get("port")
    if not status.get("listening") or not device_share or not (exe and serial):
        print(json.dumps({"ok": False, "serverAlive": False, "error": "the share listener is not open (POST /share/start), or no adb serial"}))
        return 1
    code, out = android_debug.adb(exe, serial, "forward", "tcp:0", f"tcp:{device_share}", timeout=30)
    lines = out.strip().splitlines()
    sp = int(lines[-1]) if code == 0 and lines and lines[-1].strip().isdigit() else None
    if not sp:
        print(json.dumps({"ok": False, "serverAlive": False, "error": f"adb forward to share port {device_share} failed: {out}"}))
        return 1
    tf = debug_json(host, port, "/share/peers/testframe") or {}
    frame = base64.b64decode(tf["frame"]) if tf.get("frame") else b""
    sealed = "/share/sealed"
    fake = b"DSEAL\x01\x02" + os.urandom(16) + os.urandom(64)  # a frame's header and random bytes: opens under no code

    def alive():
        return raw(host, sp, post(sealed, b"not a frame"), timeout=10) == 400

    probes = [
        ("plain-sync-pull", lambda: raw(host, sp, post("/sync/pull?since=", b"")), {426}),
        ("plain-sync-status", lambda: raw(host, sp, get("/sync/status", token=False)), {426}),
        ("plain-hello", lambda: raw(host, sp, get("/share/hello?code=0000000000000000", token=False)), {426}),
        ("plain-peer-catalog", lambda: raw(host, sp, get("/peer/catalog", token=False)), {426}),
        ("plain-peer-file", lambda: raw(host, sp, get("/peer/file/rainier-dem.pmtiles?from=0&len=1024", token=False)), {426}),
        ("plain-tile", lambda: raw(host, sp, get("/dem/10/165/357.png", token=False)), {404}),
        ("sealed-get", lambda: raw(host, sp, get(sealed, token=False)), {405}),
        ("not-a-frame", lambda: raw(host, sp, post(sealed, b"plain text")), {400}),
        ("wrong-code-frame", lambda: raw(host, sp, post(sealed, fake)), {403}),
        ("valid-frame", lambda: raw(host, sp, post(sealed, frame)), {200}),
        ("replayed-frame", lambda: raw(host, sp, post(sealed, frame)), {409}),
        ("unknown-route", lambda: raw(host, sp, get("/health", token=False)), {404}),
        ("long-request-line", lambda: raw(host, sp, get("/" + "a" * 20000, token=False)), {414, "reset"}),
        ("10k-headers", lambda: raw(host, sp, b"POST /share/sealed HTTP/1.1\r\n" + b"".join(b"X-H%d: v\r\n" % i for i in range(10000)) + b"\r\n"), {431, "reset"}),
        ("oversized-body", lambda: raw(host, sp, b"POST /share/sealed HTTP/1.1\r\nContent-Length: 100000000\r\n\r\n" + b"x" * 65536), {413, "reset"}),
        ("non-http-line", lambda: raw(host, sp, b"\x16\x03\x01\x02\x00 hello there\r\n\r\n"), {400}),
        ("empty-connection", lambda: raw(host, sp, b"", timeout=10), {"closed", 408}),
    ]
    results = []
    up = True
    for name, run, expected in probes:
        if name.endswith("-frame") and name != "wrong-code-frame" and not frame:
            results.append({"probe": name, "status": "no-frame", "expected": sorted(map(str, expected)), "ok": False, "serverAlive": True, "ms": 0})
            continue
        t0 = time.time()
        st = run()
        ms = int((time.time() - t0) * 1000)
        up = alive()
        results.append({"probe": name, "status": st, "expected": sorted(map(str, expected)), "ok": st in expected and up, "serverAlive": up, "ms": ms})
        if not up:
            break
    after = debug_json(host, port, "/share/status") or {}
    android_debug.adb(exe, serial, "forward", "--remove", f"tcp:{sp}", timeout=30)
    unexpected = [r["probe"] for r in results if not r["ok"]]
    print(json.dumps({"ok": up and not unexpected, "serverAlive": up, "unexpected": unexpected, "serial": serial, "sharePort": device_share,
                      "gate": after.get("gate"), "codeUnchanged": after.get("code") == status.get("code"), "probes": results}, indent=1))
    if not up:
        return 1
    return 2 if unexpected else 0


def idle_burst(host, port, n):
    """Opens n connections that send nothing, checks /health answers while they hang, then closes them."""
    socks = []
    for _ in range(n):
        try:
            socks.append(socket.create_connection((host, port), timeout=5))
        except OSError:
            break
    time.sleep(0.5)
    # With 4 workers busy on idle sockets and the queue holding the rest, a health check may wait for
    # the read timeout to free a worker; it must not be lost.
    status = raw(host, port, get("/health"), timeout=30)
    for s in socks:
        s.close()
    return "ok" if status in (200, 503) else f"health-during-burst:{status}"


if __name__ == "__main__":
    sys.exit(main())
