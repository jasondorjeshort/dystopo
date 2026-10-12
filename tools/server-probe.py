#!/usr/bin/env python3
"""Bad-input probes against the standalone server (T82; tools/android-probe.py does the same for the phone).

    python -I tools/server-probe.py --port 9090 [--host 127.0.0.1] [--token T] [--user-token U] [--admin-token A]
                                    [--quick] [--no-bomb] [--out out/probe.json]

Sends malformed, hostile and oversized requests to every route family (tiles, the style, /out/, /build,
/sync, the relay, /route, /admin, share links, CORS, the HTTP layer itself: an over-long request line,
thousands of headers, a 1 MB header, a lying Content-Length, a chunked body past the cap, a gzip bomb,
non-HTTP bytes, a slow header drip, a slowloris burst, hundreds of idle connections) and checks after each
that `GET /healthz` still answers 200. Prints one JSON object: per probe the HTTP status seen (or the
connection outcome: reset, closed, timeout), the statuses the server is supposed to give, whether it was
alive afterwards, and the time. Answers that pass but are lenient rather than right (an
unreadable bbox ignored, a DELETE on a read-only route; T74 made the empty glyph range, unknown units and the gzip bomb strict)
are listed under `lenient` with `lenientCount` and printed to stderr; they do not change the exit code. A few wrong answers are known and filed (`known` in the JSON); they fail
the run only with --strict. Exit 0 when every probe got an expected answer and the server survived;
1 when the server died or never answered; 2 when it survived but some probe got an unexpected answer.

Tokens: --token is the server's `token` (or a user token) for /build and /sync; --user-token a T67 user
token (an /admin probe expects 403 with it); --admin-token the admin token. Probes needing a token that
was not given are skipped (listed under `skipped`). The probes write nothing that lasts: builds are
refused before they start, sync pushes are refused or empty, relay posts are malformed.
Python 3 standard library only. Never point it at a server you do not own: the slowloris and idle
probes hold up to a few hundred connections for up to a minute.
"""
import argparse
import gzip
import io
import json
import socket
import sys
import time
import urllib.error
import urllib.request
import zlib

HOST = "127.0.0.1"
PORT = 9090
TOKEN = USER_TOKEN = ADMIN_TOKEN = ""


def parse_status(data):
    line = data.split(b"\r\n", 1)[0].decode("latin-1", "replace")
    parts = line.split(" ")
    if len(parts) >= 2 and parts[0].startswith("HTTP/") and parts[1].isdigit():
        return int(parts[1])
    return "garbage:" + line[:40]


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


def raw(payload, timeout=20.0, chunks=None, delay=0.0, port=None):
    """Sends payload (or chunks with a delay between them) on a new connection; returns the status."""
    try:
        sock = socket.create_connection((HOST, port or PORT), timeout=5)
    except OSError as e:
        return "connect-failed:" + type(e).__name__
    with sock:
        try:
            if chunks is None:
                sock.sendall(payload)
            else:
                for c in chunks:
                    sock.sendall(c() if callable(c) else c)
                    if delay:
                        time.sleep(delay)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass  # the server may cut us off mid-send; its answer (if any) is still readable
        except socket.timeout:
            pass
        except OSError:
            pass
        return recv_status(sock, timeout)


def req(method, path, headers=None, body=b"", auth=None):
    """One HTTP/1.1 request as bytes; auth is a bearer token (or None)."""
    h = [f"{method} {path} HTTP/1.1", "Host: localhost", "Connection: close"]
    if auth:
        h.append(f"Authorization: Bearer {auth}")
    for k, v in (headers or {}).items():
        h.append(f"{k}: {v}")
    if body or method in ("POST", "PUT"):
        if not any(k.lower() in ("content-length", "transfer-encoding") for k in (headers or {})):
            h.append(f"Content-Length: {len(body)}")
    return ("\r\n".join(h) + "\r\n\r\n").encode("latin-1") + body


def get(path, **kw):
    return raw(req("GET", path, **kw))


def health(timeout=10):
    return raw(req("GET", "/healthz"), timeout=timeout) == 200


def chunked_body(path, total, auth=None, piece=1 << 16):
    """A chunked POST of `total` zero bytes (no Content-Length, so only a read-time cap can stop it)."""
    head = req("POST", path, headers={"Transfer-Encoding": "chunked", "Content-Type": "application/octet-stream"}, auth=auth)
    block = b"%x\r\n" % piece + b"\0" * piece + b"\r\n"
    n = max(1, total // piece)
    chunks = [head] + [block] * n + [b"0\r\n\r\n"]
    try:
        sock = socket.create_connection((HOST, PORT), timeout=5)
    except OSError as e:
        return "connect-failed:" + type(e).__name__
    with sock:
        sock.settimeout(30)
        try:
            for c in chunks:
                sock.sendall(c)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, socket.timeout, OSError):
            pass  # cut off at the cap: what we want; read the answer
        return recv_status(sock, 30)


def gzip_bomb(mb):
    """A gzip stream of `mb` MB of zeros (about mb kB on the wire)."""
    buf = io.BytesIO()
    zero = b"\0" * (1 << 20)
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9) as g:
        for _ in range(mb):
            g.write(zero)
    return buf.getvalue()


def slowloris(n, seconds):
    """n connections that each send a request line and then one header every few seconds; then /healthz must answer."""
    socks = []
    for _ in range(n):
        try:
            s = socket.create_connection((HOST, PORT), timeout=5)
            s.sendall(b"GET /healthz HTTP/1.1\r\nHost: localhost\r\n")
            socks.append(s)
        except OSError:
            break
    t0 = time.time()
    answered = None
    i = 0
    while time.time() - t0 < seconds:
        for s in socks:
            try:
                s.sendall(b"X-Drip-%d: x\r\n" % i)
            except OSError:
                pass
        i += 1
        if answered is None and time.time() - t0 > 2:
            st = raw(req("GET", "/healthz"), timeout=max(5, seconds))
            answered = round(time.time() - t0, 1) if st == 200 else f"health:{st}"
            break
        time.sleep(1)
    closed = 0
    for s in socks:
        try:
            s.settimeout(0.1)
            if s.recv(1) == b"":
                closed += 1
        except (socket.timeout, BlockingIOError):
            pass
        except OSError:
            closed += 1
        s.close()
    return ("ok" if isinstance(answered, float) else str(answered)), {"connections": len(socks), "healthAfterS": answered, "closedByServer": closed}


def glyph_not_empty(path):
    """T74: a glyph range the fonts lack is a valid PBF with no glyphs (or a 404), never a 200 with zero bytes."""
    try:
        r = urllib.request.urlopen(f"http://{HOST}:{PORT}{path}", timeout=30)
        body = r.read()
        return ("ok" if len(body) > 0 else "empty-200"), {"status": r.status, "bytes": len(body)}
    except urllib.error.HTTPError as e:
        return ("ok" if e.code == 404 else f"http-{e.code}"), {"status": e.code}


def idle_burst(n):
    """n connections that send nothing; /healthz must answer while they hang."""
    socks = []
    for _ in range(n):
        try:
            socks.append(socket.create_connection((HOST, PORT), timeout=5))
        except OSError:
            break
    time.sleep(0.5)
    t0 = time.time()
    status = raw(req("GET", "/healthz"), timeout=60)
    ms = int((time.time() - t0) * 1000)
    for s in socks:
        s.close()
    return ("ok" if status == 200 else f"health-during-burst:{status}"), {"connections": len(socks), "healthMs": ms}


def main():
    global HOST, PORT, TOKEN, USER_TOKEN, ADMIN_TOKEN
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=9090)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--token", default="", help="bearer token for /build and /sync (the server's token or a user token)")
    ap.add_argument("--user-token", default="", help="a T67 user token (expects 403 on /admin)")
    ap.add_argument("--admin-token", default="")
    ap.add_argument("--quick", action="store_true", help="skip the slow probes (header drip, slowloris, idle connections)")
    ap.add_argument("--no-bomb", action="store_true", help="skip the gzip bomb and the 300 MB chunked body")
    ap.add_argument("--out", default="", help="also write the JSON here")
    ap.add_argument("--strict", action="store_true", help="known (filed) wrong answers fail the run too")
    ap.add_argument("--only", default="", help="comma-separated probe names to run (default: all)")
    a = ap.parse_args()
    HOST, PORT, TOKEN, USER_TOKEN, ADMIN_TOKEN = a.host, a.port, a.token, a.user_token, a.admin_token
    if PORT in (8765, 8686, 8687):
        print(json.dumps({"ok": False, "error": f"port {PORT} belongs to the coordinator or the phone; probe your own server"}))
        return 1
    if not health():
        print(json.dumps({"ok": False, "serverAlive": False, "error": f"/healthz did not answer on {HOST}:{PORT} before probing"}))
        return 1

    big = "9" * 40
    T, U, A = TOKEN, USER_TOKEN, ADMIN_TOKEN
    bad = {400}
    gone = {"reset", "closed"}
    # Coordinates the tile pattern does not match (a minus sign) or outside the zoom's range are "no such
    # tile": 404 on the server (the phone answers 400). Either is fine; a 5xx or a hang is not.
    nosuch = {400, 404}
    probes = [
        # name, run, expected, needs
        ("tile-ok", lambda: get("/dem/10/165/357.png"), {200, 204, 404}, None),
        ("tile-negative-x", lambda: get("/dem/10/-1/357.png"), nosuch, None),
        ("tile-negative-z", lambda: get("/osm/-3/0/0.pbf"), nosuch, None),
        ("tile-huge-z", lambda: get("/contours/99/0/0.pbf"), nosuch, None),
        ("tile-huge-digits", lambda: get(f"/dem/{big}/{big}/{big}.png"), bad, None),
        ("tile-x-out-of-range", lambda: get("/osm/3/8/0.pbf"), nosuch, None),
        ("tile-int-overflow", lambda: get("/contours/24/2147483648/0.pbf"), bad, None),
        # T74: an unknown value is a 400 (it fell back to the default before).
        ("contours-bad-units", lambda: get("/contours/12/655/1431.pbf?units=parsecs"), bad, None),
        ("contours-interval-zero", lambda: get("/contours/12/655/1431.pbf?interval=0"), bad, None),
        ("contours-interval-nan", lambda: get("/contours/12/655/1431.pbf?interval=NaN"), bad, None),
        ("shade-bad-intensity", lambda: get("/shade/12/655/1431.png?hillshade=multi&hillshadeIntensity=7"), bad, None),
        ("style-bad-option", lambda: get("/style.json?hillshade=purple"), bad, None),
        ("style-bad-units", lambda: get("/style.json?units=furlongs"), bad, None),  # T74: strict
        ("glyphs-traversal", lambda: get("/glyphs/..%2F..%2Fserver.json/0-255.pbf"), {400, 404}, None),
        # T74: a range that is not a glyph block is a 404, never an empty 200 (MapLibre fails an empty range).
        ("glyphs-bad-range", lambda: get("/glyphs/Noto%20Sans%20Regular/99999999999999-1.pbf"), {400, 404}, None),
        ("glyphs-unknown-font", lambda: get("/glyphs/No%20Such%20Font/0-255.pbf"), {404}, None),
        ("glyphs-range-not-empty", lambda: glyph_not_empty("/glyphs/Noto%20Sans%20Regular/5120-5375.pbf"), {"ok"}, None),
        ("version", lambda: get("/version"), {200}, None),
        ("asset-manifest", lambda: get("/assets/manifest.json"), {200}, None),
        ("live-sources-bad-bbox", lambda: get("/live/sources?bbox=a,b,c,d"), {200, 400}, None),  # lenient: no bbox
        ("out-traversal-dots", lambda: get("/out/../server.json"), {400, 403, 404}, None),
        ("out-traversal-encoded", lambda: get("/out/%2e%2e/%2e%2e/build.gradle.kts"), {400, 403, 404}, None),
        ("out-traversal-backslash", lambda: get("/out/..%5c..%5cbuild.gradle.kts"), {400, 403, 404}, None),
        ("out-missing", lambda: get("/out/nope.pmtiles"), {404}, None),
        ("out-range-past-end", lambda: get("/out/rainier-dem.pmtiles", headers={"Range": "bytes=999999999999-"}), {416, 404}, None),
        ("out-range-garbage", lambda: get("/out/rainier-dem.pmtiles", headers={"Range": "bytes=zz-yy"}), {200, 400, 416, 404}, None),
        ("out-range-many", lambda: get("/out/rainier-dem.pmtiles", headers={"Range": "bytes=" + ",".join(f"{i}-{i}" for i in range(0, 4000, 2))}), {200, 206, 400, 416, 404}, None),
        ("out-kbps-garbage", lambda: get("/out/rainier-outdoors.pmtiles?kbps=-1", headers={"Range": "bytes=0-99"}), {206, 400, 404}, None),
        ("bad-percent-encoding", lambda: get("/dem/%zz/1/1.png"), bad, None),
        ("unknown-route", lambda: get("/nope"), {404}, None),
        ("method-not-allowed", lambda: raw(req("DELETE", "/catalog")), {200, 404, 405}, None),  # read-only routes ignore the method
        ("cors-preflight", lambda: raw(req("OPTIONS", "/build", headers={"Origin": "https://x.example", "Access-Control-Request-Method": "POST"})), {204}, None),
        ("share-link-unknown", lambda: get("/s/notatoken"), {404}, None),
        ("share-link-traversal", lambda: get("/s/..%2f..%2fshares.json"), {400, 404}, None),
        ("route-bad-point", lambda: get("/route?from=999,999&to=0,0"), bad, None),
        ("route-too-many-points", lambda: get("/route?points=" + ";".join(f"46.{i:03d},-121.7" for i in range(60))), bad, None),
        ("route-edit-bad-json", lambda: raw(req("POST", "/route/edit", body=b"{not json", headers={"Content-Type": "application/json"})), bad, None),
        ("route-edit-deep-json", lambda: raw(req("POST", "/route/edit", body=b"[" * 100000 + b"]" * 100000, headers={"Content-Type": "application/json"})), bad, None),
        ("relay-bad-slot", lambda: raw(req("POST", "/sync/relay?slot=zz&side=yy", body=b"x")), bad, None),
        ("relay-not-a-frame", lambda: raw(req("POST", "/sync/relay?slot=" + "a" * 32 + "&side=" + "b" * 16, body=b"hello")), bad, None),
        ("relay-declared-oversize", lambda: raw(req("POST", "/sync/relay?slot=" + "a" * 32 + "&side=" + "b" * 16, headers={"Content-Length": "100000000"}) + b"x" * 65536), {413} | gone, None),
        # Tokens: /build and /sync need one; /admin only the admin token.
        ("build-no-token", lambda: raw(req("POST", "/build?name=x&bbox=1,1,1.01,1.01")), {401}, None),
        ("build-wrong-token", lambda: raw(req("POST", "/build?name=x&bbox=1,1,1.01,1.01", auth="wrong-token-wrong-token")), {401}, None),
        ("sync-no-token", lambda: get("/sync/status"), {401}, None),
        ("catalog-refresh-no-token", lambda: raw(req("POST", "/catalog/refresh")), {401}, None),
        ("admin-no-token", lambda: get("/admin/users"), {401}, None),
        ("admin-user-token", lambda: get("/admin/users", auth=U), {403}, "user"),
        ("admin-sync-without-user", lambda: get("/sync/status", auth=A), bad, "admin"),
        ("admin-sync-unknown-user", lambda: get("/sync/status?user=nobody", auth=A), {404}, "admin"),
        ("admin-add-bad-id", lambda: raw(req("POST", "/admin/users?id=../../etc", auth=A)), bad, "admin"),
        ("build-bad-bbox", lambda: raw(req("POST", "/build?name=x&bbox=nan,1,2,3", auth=T)), bad, "token"),
        ("build-name-traversal", lambda: raw(req("POST", "/build?name=..%2F..%2Fx&bbox=-121.76,46.77,-121.75,46.78&minZoom=8&maxZoom=9", auth=T)), bad, "token"),
        ("build-zoom-garbage", lambda: raw(req("POST", "/build?name=probe&bbox=-121.76,46.77,-121.75,46.78&minZoom=99&maxZoom=-1", auth=T)), bad, "token"),
        ("build-box-too-big", lambda: raw(req("POST", "/build?name=probe&bbox=-130,40,-110,50&minZoom=8&maxZoom=9", auth=T)), {413}, "token"),
        ("build-status-unknown", lambda: get("/build/nope", auth=T), {404}, "token"),
        ("sync-pull-bad-since", lambda: get("/sync/pull?since=%%%garbage", auth=T), bad, "token"),
        ("sync-push-garbage", lambda: raw(req("POST", "/sync/push", body=b"\x1f\x8bnot really gzip", auth=T)), bad, "token"),
        ("sync-push-declared-oversize", lambda: raw(req("POST", "/sync/push", headers={"Content-Length": str(300 << 20)}, auth=T) + b"x" * 65536), {413} | gone, "token"),
        ("sync-share-no-route", lambda: raw(req("POST", "/sync/share", auth=T)), bad, "token"),
        # The HTTP layer itself.
        ("long-request-line", lambda: get("/" + "a" * 20000), {400, 404, 414} | gone, None),
        ("very-long-request-line", lambda: get("/" + "a" * 1_000_000), {400, 414, 431} | gone, None),
        ("10k-headers", lambda: raw(b"GET /healthz HTTP/1.1\r\n" + b"".join(b"X-H%d: v\r\n" % i for i in range(10000)) + b"\r\n"), {400, 431} | gone, None),
        ("1mb-header-line", lambda: raw(b"GET /healthz HTTP/1.1\r\nX-Big: " + b"b" * (1 << 20) + b"\r\n\r\n"), {400, 431} | gone, None),
        ("declared-oversize-body", lambda: raw(req("POST", "/route/edit", headers={"Content-Length": "100000000"}) + b"x" * 65536), {413} | gone, None),
        ("chunked-oversize-body", lambda: chunked_body("/route/edit", 20 << 20), {413} | gone, None),
        ("negative-content-length", lambda: raw(b"POST /route/edit HTTP/1.1\r\nHost: x\r\nContent-Length: -5\r\n\r\n"), {400} | gone, None),
        ("two-content-lengths", lambda: raw(b"POST /route/edit HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nContent-Length: 50\r\n\r\nhello"), {400, 413} | gone, None),
        ("short-body", lambda: raw(b"POST /route/edit HTTP/1.1\r\nHost: x\r\nContent-Length: 50\r\n\r\n{\"op\":", timeout=100), {400, 408, 413} | gone, None),  # cut at http.maxRequestSeconds
        ("non-http-line", lambda: raw(b"\x16\x03\x01\x02\x00 hello there\r\n\r\n"), {400, 404} | gone, None),  # the JDK reads method + path
        ("http-0.9", lambda: raw(b"GET /healthz\r\n\r\n"), {400, 505} | gone, None),
        ("bad-version", lambda: raw(b"GET /healthz HTTP/7.3\r\nHost: x\r\n\r\n"), {200, 400, 505} | gone, None),  # the JDK takes any version
        ("empty-connection", lambda: raw(b"", timeout=45), {"closed", "reset", 408}, None),
    ]
    if not a.no_bomb:
        bomb = gzip_bomb(1024)
        probes += [
            # 1 GiB of zeros in about 1 MB: a push must be refused, not inflated into memory (T74: 413 past 64 MiB inflated).
            ("sync-push-gzip-bomb", lambda: raw(req("POST", "/sync/push", body=bomb, headers={"Content-Type": "application/octet-stream"}, auth=T), timeout=120), {413} | gone, "token"),
            ("sync-push-chunked-300mb", lambda: chunked_body("/sync/push", 300 << 20, auth=T), {413} | gone, "token"),
        ]
    if not a.quick:
        probes += [
            ("slow-header-drip", lambda: raw(None, chunks=[b"GET /healthz HTTP/1.1\r\n"] + [b"X-Drip: %d\r\n" % i for i in range(80)], delay=0.5, timeout=45), {408} | gone, None),
            ("slowloris-x64", lambda: slowloris(64, 90), {"ok"}, None),
            ("idle-connections-x300", lambda: idle_burst(300), {"ok"}, None),
        ]

    need = {"token": T, "user": U, "admin": A}
    # Known answers outside the expected set, each a filed follow-up (not this script's to fix): reported
    # under `known` and not failing the run unless --strict. The server must still survive them.
    known = {}  # T74 closed the last one (the gzip bomb: pushes inflate with a cap now)
    results, skipped = [], []
    alive = True
    only = set(filter(None, a.only.split(",")))
    for name, run, expected, needs in probes:
        if only and name not in only:
            continue
        if needs and not need[needs]:
            skipped.append(name)
            continue
        t0 = time.time()
        out = run()
        detail = None
        if isinstance(out, tuple):
            out, detail = out
        ms = int((time.time() - t0) * 1000)
        up = health()
        r = {"probe": name, "status": out, "expected": sorted(map(str, expected)), "ok": out in expected and up, "serverAlive": up, "ms": ms}
        if detail:
            r["detail"] = detail
        results.append(r)
        if not up:
            time.sleep(5)  # a busy server (a GC after the bomb) gets a moment before it is declared dead
            up = health(timeout=30)
            r["serverAlive"] = up
            r["ok"] = out in expected and up
            if not up:
                alive = False
                break

    known_hits = {r["probe"]: known[r["probe"]] for r in results
                  if not r["ok"] and r["serverAlive"] and r["probe"] in known and not a.strict}
    unexpected = [r["probe"] for r in results if not r["ok"] and r["probe"] not in known_hits]
    # Answers inside the expected set that are lenient rather than right: they pass (exit code unchanged) but
    # are listed, so a run never hides them. A probe with an entry here is lenient when it got one of the listed
    # statuses.
    lenient_rules = {
        "live-sources-bad-bbox": ({200}, "an unreadable bbox is ignored instead of a 400"),
        "method-not-allowed": ({200}, "DELETE on a read-only route answers 200 instead of 405"),
        "bad-version": ({200}, "the JDK listener accepts any HTTP version"),
        "tile-negative-x": ({404}, "out-of-range tile coordinates are 404 (the phone answers 400)"),
        "tile-negative-z": ({404}, "out-of-range tile coordinates are 404 (the phone answers 400)"),
        "tile-huge-z": ({404}, "out-of-range tile coordinates are 404 (the phone answers 400)"),
        "tile-x-out-of-range": ({404}, "out-of-range tile coordinates are 404 (the phone answers 400)"),
    }
    lenient = [{"probe": r["probe"], "status": r["status"], "why": lenient_rules[r["probe"]][1]}
               for r in results if r["probe"] in lenient_rules and r["status"] in lenient_rules[r["probe"]][0]]
    report = {"ok": alive and not unexpected, "serverAlive": alive, "host": HOST, "port": PORT, "probes": len(results),
              "unexpected": unexpected, "known": known_hits, "lenientCount": len(lenient), "lenient": lenient,
              "skipped": skipped, "results": results}
    text = json.dumps(report, indent=1)
    print(text)
    for l in lenient:
        print(f"lenient: {l['probe']} -> {l['status']}: {l['why']}", file=sys.stderr)
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    if not alive:
        return 1
    return 2 if unexpected else 0


if __name__ == "__main__":
    sys.exit(main())
