#!/usr/bin/env python3
"""Bad-input probes against the app's loopback server (through `adb forward`).

    python -I tools/android-probe.py [--port 8686] [--host 127.0.0.1]

Sends malformed and hostile requests (huge, negative and out-of-range tile coordinates, 10k
headers, an over-long request line, a slow header drip, an oversized body, a lying Content-Length,
non-HTTP bytes, a burst of idle connections) and checks after each that `/health` still answers.
Prints one JSON object: per probe the HTTP status seen (or the connection outcome), the expected
statuses, and whether the server was alive afterwards. Exit 0 when every probe got an expected answer
and the server survived; 1 when the server died (or never answered); 2 when it survived but some
probe got an unexpected answer. Python 3 standard library only.
"""
import argparse
import json
import socket
import sys
import time


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


def get(path):
    return f"GET {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".encode()


def health(host, port):
    status = raw(host, port, get("/health"), timeout=10)
    return status == 200


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=8686)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--quick", action="store_true", help="skip the slow probes (header drip, idle connections)")
    a = ap.parse_args()
    host, port = a.host, a.port

    if not health(host, port):
        print(json.dumps({"ok": False, "serverAlive": False, "error": f"/health did not answer on {host}:{port} before probing"}))
        return 1

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
        ("location-missing-lat", lambda: raw(host, port, b"POST /location HTTP/1.1\r\nContent-Length: 0\r\n\r\n"), {400}),
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

    unexpected = [r["probe"] for r in results if not r["ok"]]
    print(json.dumps({"ok": alive and not unexpected, "serverAlive": alive, "unexpected": unexpected, "probes": results}, indent=1))
    if not alive:
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
