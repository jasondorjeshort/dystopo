"""Device tour of the Android app through its debug server: the phone equivalent of demo-check.py.

    python -I tools/android-check.py --views-file tools/android-tour.txt [--port 8686] [--serial emulator-5570]
                                     [--out out/android-tour.png] [--contour-budget-ms 1000]

For each view it sets the camera (POST /camera), waits for the map to go idle and takes a screenshot
(GET /screenshot?wait=...), reads /health and the /stats difference, and checks:
  - style:    the style is loaded and MapLibre reported no load error
  - idle:     the map went idle before the screenshot (health map.lastScreenshotIdle); T115: a view that did not
              reports `notIdleBecause` (health map.notIdleBecause, such as pendingTiles)
  - errors:   no tile source counted a server error during the view
  - contours: every contour tile generated during the view took at most --contour-budget-ms (only with
              --strict; otherwise a miss is a warning, since timings swing with host load)
  - tiles:    each expected source (dem, osm, contours) served tiles for the view; a source is expected
              when the view's zoom is in its pack's range (from /health) and, T154, a tile of the viewport itself
              (the view's box from GET /attribution viewBounds, at the zooms MapLibre requests that source at)
              overlaps the pack's bounds; or when the view lists it with |req=dem+osm+contours. One of those
              overlapping tiles (`packTiles`, over a pack /health lists now) that a pack answered before the view's
              baseline counts too (MapLibre held it, so the view's delta is 0): it
              is read from GET /stats/requests and reported as tiles.<source>.servedBeforeBaseline and
              beforeBaseline (tile, pack, time); the online fallback's answers never count. The view's report
              carries `expectation` per source: expected, the reason and the overlapping `packTiles`.
              T170: a contour delta counts only when the view's own lines of the request log show a pack
              answered one of them, or the online fallback answered one of the `packTiles` (a tile over the
              pack's edge: tiles.contours.servedFromPack, servedOverPack, servedOnline), since contour tiles
              made over online DEM elsewhere count in the delta too
  - blank:    the screenshot is not blank (luma standard deviation of a pixel sample >= --min-stddev)
  - settled:  a second screenshot, taken after the label check has let the map settle, differs from the
              first in at most --max-settle-change of the pixels (else /screenshot returned a half-loaded map;
              the second one is saved as <stem>-<i>-settled.png)
  - lines:    contour line layers rendered features where contours are expected (GET /features,
              MapLibre queryRenderedFeatures per layer)
  - labels:   where OSM or contours are expected, text symbol layers placed at least one label AND the
              labels reached the pixels: GET /features/text snapshots the map with and without text
              (text-opacity 0) and counts changed pixels, which must be >= --min-text-pixels
and once for the whole tour:
  - glyphs:   at least one glyph range was served over HTTP for every font the style uses, or (glyphs
              loaded from a file:// URL never hit the server) text was drawn on at least one view
  - background (T123, with adb, unless --no-background): after the views, `input keyevent HOME`; the routes that need
              the activity on screen (/screenshot, /features, /features/text, /help, POST /attribution/open) must each
              answer 503 with a `reason` within --background-limit-s (5 s); then `am start -n` brings the app's
              task back (`returnedTask`; no second activity) and /screenshot, /features and /help must answer 200 (`backgroundStep` in the summary)
  - direct (T127, unless --direct-view none): after the background step, a cold direct view (no pack, no server; default
              --direct-view 9/45.865/6.865, Mont Blanc z9, which the tour never loads): the online cache (pinned tiles
              kept) and the phone's source range cache cleared, the camera set, the Java heap sampled from GET /stats
              every 0.4 s (and the app's own per-burst peak, GET /online direct.memory). It must render DEM tiles in the
              same process, with no crash and no OutOfMemoryError in logcat, and its live heap (after a collection,
              through `am send-trim-memory`) at most --direct-heap-share of the heap's max; idle and the sampled peaks
              are reported, not required (blank tiles can pass); idleSeconds, dumpsys meminfo and the screenshot (<stem>-direct-<i>.png) are reported (`directStep`).
              T147: a RUNNING_LOW Android refuses ("higher trim level": the process already had it) is followed by
              RUNNING_CRITICAL, the one foreground level above it (COMPLETE is refused to a foreground app); both refused
              is said in words (`live.level`, `live.refused` with Android's reasons); the step's stderr line names the level and
              the decoded DEM blocks the trim dropped (`live.droppedBytes.decodedBlocks`). --direct-only runs only the
              direct views, each in a new process (the per-zoom peak table of finding 2026-10-21-direct-view-out-of-memory-after-a-tour).
The lines and labels checks need the app's /features routes; an app without them skips them (reported).
The summary's `requests` is the app's request log at the end (GET /stats/requests?limit=12: per-route counts and the
last lines).
Views: one `z/lat/lon[:label][|req=a+b]` per line in --views-file (`#` comments), or --views a,b,c.
Every view should need tiles that no earlier view loaded, so its own delta counts them; a viewport tile the app's
start or an earlier view loaded from a pack counts only through the request log (T154, above).

Run it on an emulator with a hardware GPU (android-emu.ps1 -Gpu host, the default when there is one): under
swiftshader MapLibre draws no text or dash patterns, and the labels check fails on every view.

Before the tour (unless --no-restart) the app is force-stopped and relaunched through adb so counters,
MapLibre's tile caches and the glyph cache start empty, and the contour disk cache is cleared (unless
--keep-cache) so contour timings measure generation, not disk reads. T105: --server <url>|none sets the app's
server first (a debug build on the emulator otherwise uses the host's 8765; the sweep passes its own); and the
first view waits until the device is quiet, by its load: the CPU used besides the app at most --quiet-cores in two
3 s windows in a row (setup.quiet in the summary: the wait and the windows). A just-booted emulator runs near all four
cores for one to two minutes, and a cold view started then missed the 30 s idle (T88, T98).
T110: the first view waits up to --first-wait-ms (120 s since T127) for idle, the others --wait-ms (30 s). The first view is the
cold one (a new process; with no server its terrain around the packs is rendered from the public sources). Measured on
fresh API 35 AVDs with no server and two other emulators on the host: 10.3 and 14.5 s after the quiet wait, 24.2 s when
the view started 19 s after the relaunch while the device still used two of its four cores besides the app; T105's
43 s (run C) is what a view costs when the device or the host is busy again during it. Each view reports
`idleSeconds` (how long /screenshot waited for idle) and `deviceCpu` (cores the device used besides the app, and the
app's, while the view ran), so a slow view says whether it ran on a busy device.

Every request carries the app's per-launch loopback token (T49). T57: the debug routes answer on a random
port per launch, announced with the token only in logcat (`debug server on port N token T`, tag Dystopo):
with a device (--serial, or the serial that owns --port) the script reads that line and forwards --port to
it (again after a relaunch or a 401); without adb it falls back to GET /health without a token, which
answers {"token": ...} when the app runs with the setting debugFixedPort=1. setup.token in the summary
records the device port and that a request without the token got 401.
T185: a request refused on the host port (adb on the second PC drops a forward now and then) forwards it again to
the app's device port and is sent once more; a GET whose body arrives cut short (IncompleteRead) is asked once more,
a POST never. The summary's `connection` counts both (forwardRestores, incompleteReadRetries, tokenRefreshes, events).

Device selection: --port is the host port forwarded to the app (tools/android-emu.ps1 -Port). With
--serial (or ANDROID_SERIAL) the script refuses a port that `adb forward --list` maps to another serial
(it forwards the port itself when nothing holds it), so a tour can never drive another emulator; without
either it uses the serial that owns the port. adb is also used for the restart and to collect the app's logcat
warnings during the tour (reported, not checked).

T224, T241: every view and card step reads GET /layout (`layout`: the rectangles each element's Modifier.control node
published as it was placed, the pairs that overlap, what is hidden and why). --layout-baseline <earlier stem>.json
compares those rectangles place by place with an earlier run, such as the same tour on the fork's build, and reports the
ones that moved more than --layout-tolerance-px (1) in `layoutBaseline` and one stderr line; never a failure.
T243: a view or card step fails when `GET /layout` `semanticsAway` lists an element (`layoutSemanticsWhereDrawn`): its
semantics node, which TalkBack and `uiautomator` read and which cuts the bounds of the nodes under it, is not where its
node drew it (a tag outside the control node left the strip's under the long-press card). A build before the field passes.

Writes <out stem>-<i>.png per view, <out stem>-sheet.png (a contact sheet, red/green bar per view) and
<out stem>.json (the summary, also printed to stdout); a table goes to stderr. Exit 1 if any check fails,
2 if the device or server could not be reached. Needs only the standard library; uses Pillow for PNG
decoding when it is installed (faster), else a built-in decoder.
"""
import argparse
import json
import math
import os
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
import http.client
import importlib.util

# T57: the debug server's port and token come from logcat (tools/android_debug.py; -I keeps tools/ off sys.path).
_spec = importlib.util.spec_from_file_location("android_debug", os.path.join(os.path.dirname(os.path.abspath(__file__)), "android_debug.py"))
android_debug = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(android_debug)

SOURCES = ("dem", "osm", "contours")
PACKAGE = "io.github.jasondorjeshort.dystopo"
ACTIVITY = PACKAGE + "/io.github.jasondorjeshort.dystopo.app.MainActivity"


# ---------------------------------------------------------------------------------------------------
# Views

def parse_view(text):
    opts = text.split("|")[1:]
    head = text.split("|")[0]
    req = None
    for o in opts:
        o = o.strip()
        if o.startswith("req="):
            req = [s for s in o[4:].split("+") if s]
            bad = [s for s in req if s not in SOURCES]
            if bad:
                raise SystemExit(f"unknown source {bad} in {text!r}; sources: {', '.join(SOURCES)}")
    label = ""
    if ":" in head:
        head, label = head.split(":", 1)
    z, lat, lon = (float(x) for x in head.strip().split("/"))
    return {"view": f"{z:g}/{lat:g}/{lon:g}", "z": z, "lat": lat, "lon": lon, "label": label.strip(), "req": req}


def read_views(args):
    items = [s for s in args.views.split(",") if s.strip()] if args.views else []
    if args.views_file:
        with open(args.views_file, encoding="utf-8") as f:
            for line in f:
                line = line.split("#", 1)[0].strip()
                if line and not line.startswith("@"):  # T212: `@` lines are steps (read_steps)
                    items.append(line)
    return [parse_view(s) for s in items]


def read_steps(args):
    """T212: the tour file's step lines (`@cards ...`, parse_cards)."""
    steps = []
    if args.views_file:
        with open(args.views_file, encoding="utf-8") as f:
            for line in f:
                line = line.split("#", 1)[0].strip()
                if line.startswith("@cards"):
                    steps.append(parse_cards(line))
    return steps


# ---------------------------------------------------------------------------------------------------
# HTTP and adb

class Server:
    """The app's debug server. Every request carries the app's per-launch loopback token (T49). T57: with adb and
    a serial the port and token come from the app's logcat line and --port is forwarded to that port; read again
    after a 401 or a refused connection, since a relaunch makes new ones. Without adb: GET /health without a
    token (the app's debugFixedPort=1 mode).

    T185: a refused connection means nothing listens on the host port any more: adb on the second PC drops a forward
    now and then (T155's and T170's follow-up), while an app that died behind a live forward resets the connection
    instead. So a refused request forwards the port again to the app's known device port (`adb forward --no-rebind`,
    never taking a port another device holds; the logcat read again when the port is not known) and is sent once more,
    counted in `counters["forwardRestores"]`. A GET whose body arrives cut short (http.client.IncompleteRead, seen on a
    screenshot under a parallel build) is asked once more (`incompleteReadRetries`); a POST is never repeated for it.
    The tour's summary carries the counters as `connection`."""

    def __init__(self, port, exe=None, serial=None):
        self.port = port
        self.base = f"http://127.0.0.1:{port}"
        self.exe = exe
        self.serial = serial
        self.token = None
        self.device_port = None
        self.counters = {"forwardRestores": 0, "incompleteReadRetries": 0, "tokenRefreshes": 0}
        self.events = []  # the first few retries: what failed and what was done

    def note(self, what):
        if len(self.events) < 20:
            self.events.append(f"{time.strftime('%H:%M:%S')} {what}")
        print(f"[server] {what}", file=sys.stderr)

    def restore_forward(self):
        """T185: forward the host port again to the app's device port; False when that is not possible (no adb, the port
        not known yet, or adb refused, such as another device holding the port), so the caller reads logcat again."""
        if not (self.exe and self.serial and self.device_port):
            return False
        code, out = android_debug.adb(self.exe, self.serial, "forward", "--no-rebind", f"tcp:{self.port}",
                                      f"tcp:{self.device_port}", timeout=30)
        if code != 0:
            self.note(f"adb forward tcp:{self.port} tcp:{self.device_port} failed: {out.strip()[:200]}")
        return code == 0

    def fetch_token(self, timeout=10.0):
        if self.exe and self.serial:
            self.device_port, self.token = android_debug.bootstrap(self.exe, self.serial, self.port, timeout=max(timeout, 30.0))
        else:
            self.token = android_debug.health_token(self.base, timeout)
        return self.token

    def reset(self):
        """Forget the token and port (after a relaunch)."""
        self.token = None

    def _open(self, path, method="GET", timeout=30.0):
        attempt, reread, restored = 0, False, False
        while True:
            if self.token is None:
                self.fetch_token(min(timeout, 10.0))
            req = urllib.request.Request(self.base + path, method=method, data=b"" if method == "POST" else None,
                                         headers={"X-Dystopo-Token": self.token})
            try:
                with urllib.request.urlopen(req, timeout=timeout) as r:
                    return r.read()
            except urllib.error.HTTPError as e:
                if e.code != 401 or attempt:
                    raise
                self.counters["tokenRefreshes"] += 1
                self.token = None  # the app was relaunched: fetch its new token and retry once
            except http.client.IncompleteRead as e:
                if method != "GET" or reread:
                    raise  # T185: a POST is never sent twice
                reread = True
                self.counters["incompleteReadRetries"] += 1
                self.note(f"GET {path.split('?')[0]}: {len(e.partial)} bytes of the body arrived; asked again")
                continue  # does not use up the retry below
            except (urllib.error.URLError, ConnectionError, http.client.RemoteDisconnected) as e:
                if attempt or not self.serial:
                    raise
                if refused(e):
                    self.counters["forwardRestores"] += 1
                    if not restored and self.restore_forward():
                        restored = True  # a forward to a port the app has since left still gets the logcat path below
                        self.note(f"{method} {path.split('?')[0]}: connection refused; forwarded tcp:{self.port} again")
                        continue
                    self.note(f"{method} {path.split('?')[0]}: connection refused; reading logcat and forwarding again")
                self.token = None  # T57: relaunched on a new port: read logcat again and re-forward
            attempt += 1

    def get(self, path, timeout=30.0):
        return self._open(path, timeout=timeout)

    def json(self, path, method="GET", timeout=30.0):
        return json.loads(self._open(path, method, timeout).decode("utf-8"))

    def status_without_token(self, path, timeout=10.0):
        """HTTP status of a request with no token (expected: 401)."""
        try:
            with urllib.request.urlopen(self.base + path, timeout=timeout) as r:
                return r.status
        except urllib.error.HTTPError as e:
            return e.code


def refused(e):
    """True for a refused connection, bare or inside a URLError (nothing listening on the host port)."""
    return isinstance(e, ConnectionRefusedError) or isinstance(getattr(e, "reason", None), ConnectionRefusedError)


adb_path = android_debug.adb_path
adb = android_debug.adb
forward_owner = android_debug.forward_owner


def device_cpu(exe, serial):
    """T110: a /proc/stat sample of the device with the app's own ticks (android_debug.parse_cpu), or None without adb."""
    if not exe or not serial:
        return None
    try:
        pid = android_debug.app_pid(exe, serial)
        _, out = adb(exe, serial, "shell", "cat /proc/stat" + (f"; cat /proc/{pid}/stat" if pid else ""), timeout=30)
        return android_debug.parse_cpu(out)
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def cpu_between(a, b):
    """T110: cores busy besides the app (`others`) and the app's (`app`) between two device_cpu samples."""
    if not a or not b or b[1] <= a[1] or not b[2]:
        return None
    dt = b[1] - a[1]
    busy = (b[0] - a[0]) / dt * b[2]
    app = (b[3] - a[3]) / dt * b[2] if a[3] is not None and b[3] is not None and b[3] >= a[3] else 0.0
    return {"others": round(max(0.0, busy - app), 2), "app": round(app, 2), "cores": b[2]}


def restart_app(exe, serial, server, timeout):
    since = android_debug.device_now(exe, serial)
    adb(exe, serial, "shell", "am", "force-stop", PACKAGE)
    code, out = adb(exe, serial, "shell", "am", "start", "-W", "-n", ACTIVITY)
    if code != 0:
        raise RuntimeError(f"am start failed: {out.strip()}")
    server.reset()  # T57: new process, new debug port and token
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            h = server.json("/health", timeout=10)
            if h.get("map", {}).get("styleLoaded"):
                return h
        except (urllib.error.URLError, OSError, ValueError, RuntimeError):
            server.reset()
        android_debug.raise_if_crashed(exe, serial, since)  # T86: a launch crash is its own failure, with the exception line
        time.sleep(1)
    android_debug.raise_if_crashed(exe, serial, since)
    raise RuntimeError(f"app did not report a loaded style within {timeout:.0f} s of a restart")


def logcat_since(exe, serial, since):
    """Warnings and errors the app logged since device time `since`: counts per tag and the distinct messages."""
    code, pid = adb(exe, serial, "shell", "pidof", PACKAGE, timeout=30)
    pid = pid.strip().split()[0] if code == 0 and pid.strip() else None
    args = ["logcat", "-d", "-v", "brief", "-T", since]
    if pid:
        args.append(f"--pid={pid}")
    args.append("*:W")
    code, out = adb(exe, serial, *args, timeout=60)
    tags, messages = {}, {}
    for line in out.splitlines():
        # brief format: "W/Tag( pid): message"
        if len(line) < 3 or line[1] != "/" or line[0] not in "WEF":
            continue
        tag = line[2:line.find("(")].strip() if "(" in line else "?"
        msg = line.split("):", 1)[1].strip() if "):" in line else line
        key = f"{line[0]}/{tag}"
        tags[key] = tags.get(key, 0) + 1
        k = f"{key}: {msg[:200]}"
        messages[k] = messages.get(k, 0) + 1
    top = sorted(messages.items(), key=lambda kv: -kv[1])[:15]
    return {"pid": pid, "byTag": tags, "top": [{"count": n, "line": m} for m, n in top]}


# ---------------------------------------------------------------------------------------------------
# PNG (decode via Pillow when present, else a small stdlib decoder; encode with zlib)

try:
    from PIL import Image  # noqa: F401
    HAVE_PIL = True
except ImportError:
    HAVE_PIL = False


class Raster:
    """8-bit RGB pixels, row-major, 3 bytes per pixel."""

    def __init__(self, w, h, data=None):
        self.w, self.h = w, h
        self.data = data if data is not None else bytearray(w * h * 3)

    def px(self, x, y):
        i = (y * self.w + x) * 3
        return self.data[i], self.data[i + 1], self.data[i + 2]


def decode_png(raw):
    if HAVE_PIL:
        import io
        from PIL import Image
        im = Image.open(io.BytesIO(raw)).convert("RGB")
        return Raster(im.width, im.height, bytearray(im.tobytes()))
    return _decode_png_stdlib(raw)


def _decode_png_stdlib(raw):
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, idat, w = 8, [], None
    while pos < len(raw):
        n, kind = struct.unpack(">I4s", raw[pos:pos + 8])
        body = raw[pos + 8:pos + 8 + n]
        pos += 12 + n
        if kind == b"IHDR":
            w, h, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or ctype not in (2, 6) or interlace:
                raise ValueError(f"unsupported PNG (depth {depth}, colour type {ctype}, interlace {interlace}); install Pillow")
        elif kind == b"IDAT":
            idat.append(body)
        elif kind == b"IEND":
            break
    bpp = 4 if ctype == 6 else 3
    stride = w * bpp
    data = zlib.decompress(b"".join(idat))
    out = Raster(w, h)
    prev = bytearray(stride)
    mask7 = int.from_bytes(b"\x7f" * stride, "big")
    mask8 = int.from_bytes(b"\x80" * stride, "big")
    o = 0
    for y in range(h):
        f = data[y * (stride + 1)]
        row = bytearray(data[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        if f == 1:
            for i in range(bpp, stride):
                row[i] = (row[i] + row[i - bpp]) & 255
        elif f == 2:
            # Byte-wise add without carries between bytes, on whole rows at once.
            a, b = int.from_bytes(row, "big"), int.from_bytes(prev, "big")
            row = bytearray((((a & mask7) + (b & mask7)) ^ ((a ^ b) & mask8)).to_bytes(stride, "big"))
        elif f == 3:
            for i in range(stride):
                left = row[i - bpp] if i >= bpp else 0
                row[i] = (row[i] + ((left + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = row[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                row[i] = (row[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        if bpp == 3:
            out.data[o:o + w * 3] = row
        else:
            rgb = out.data
            rgb[o:o + w * 3:3] = row[0::4]
            rgb[o + 1:o + w * 3:3] = row[1::4]
            rgb[o + 2:o + w * 3:3] = row[2::4]
        o += w * 3
        prev = row
    return out


def encode_png(r, path):
    rows = b"".join(b"\x00" + bytes(r.data[y * r.w * 3:(y + 1) * r.w * 3]) for y in range(r.h))

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", r.w, r.h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(rows, 6)) + chunk(b"IEND", b""))


def changed_share(a, b, samples=200):
    """Share of a samples x samples grid whose largest channel difference between two images exceeds 32."""
    if (a.w, a.h) != (b.w, b.h):
        return 1.0
    n = changed = 0
    for j in range(samples):
        y = min(a.h - 1, int((j + 0.5) * a.h / samples))
        for i in range(samples):
            x = min(a.w - 1, int((i + 0.5) * a.w / samples))
            p, q = a.px(x, y), b.px(x, y)
            n += 1
            if max(abs(p[0] - q[0]), abs(p[1] - q[1]), abs(p[2] - q[2])) > 32:
                changed += 1
    return round(changed / n, 4)


def image_stats(r, samples=200):
    """Luma mean/stddev over a samples x samples grid, and the share of the most common colour."""
    n, s, ss, counts = 0, 0.0, 0.0, {}
    for j in range(samples):
        y = min(r.h - 1, int((j + 0.5) * r.h / samples))
        for i in range(samples):
            x = min(r.w - 1, int((i + 0.5) * r.w / samples))
            px = r.px(x, y)
            l = 0.299 * px[0] + 0.587 * px[1] + 0.114 * px[2]
            n += 1
            s += l
            ss += l * l
            counts[px] = counts.get(px, 0) + 1
    mean = s / n
    return {"lumaMean": round(mean, 1), "lumaStddev": round(math.sqrt(max(0.0, ss / n - mean * mean)), 1),
            "dominantColorShare": round(max(counts.values()) / n, 3), "width": r.w, "height": r.h}


# 3x5 bitmap font for contact-sheet captions (rows top to bottom, 3 bits each).
FONT = {
    "0": "111101101101111", "1": "010110010010111", "2": "111001111100111", "3": "111001111001111",
    "4": "101101111001001", "5": "111100111001111", "6": "111100111101111", "7": "111001010010010",
    "8": "111101111101111", "9": "111101111001111", "A": "010101111101101", "B": "110101110101110",
    "C": "011100100100011", "D": "110101101101110", "E": "111100110100111", "F": "111100110100100",
    "G": "011100101101011", "H": "101101111101101", "I": "111010010010111", "J": "001001001101010",
    "K": "101101110101101", "L": "100100100100111", "M": "101111111101101", "N": "110101101101101",
    "O": "010101101101010", "P": "110101110100100", "Q": "010101101110011", "R": "110101110101101",
    "S": "011100010001110", "T": "111010010010010", "U": "101101101101111", "V": "101101101101010",
    "W": "101101111111101", "X": "101101010101101", "Y": "101101010010010", "Z": "111001010100111",
    "-": "000000111000000", ".": "000000000000010", "/": "001001010100100", ":": "000010000010000",
    " ": "000000000000000",
}


def draw_text(r, x0, y0, text, color, scale=2):
    x = x0
    for ch in text.upper():
        bits = FONT.get(ch, FONT[" "])
        for row in range(5):
            for col in range(3):
                if bits[row * 3 + col] == "1":
                    fill(r, x + col * scale, y0 + row * scale, scale, scale, color)
        x += 4 * scale
        if x > r.w - 4 * scale:
            break


def fill(r, x0, y0, w, h, color):
    line = bytes(color) * max(0, min(w, r.w - x0))
    for y in range(max(0, y0), min(r.h, y0 + h)):
        i = (y * r.w + x0) * 3
        r.data[i:i + len(line)] = line


def contact_sheet(reports, rasters, path, thumb_w=216, cols=5):
    shots = [(rep, ras) for rep, ras in zip(reports, rasters) if ras is not None]
    if not shots:
        return None
    thumb_h = max(int(ras.h * thumb_w / ras.w) for _, ras in shots)
    bar, gap = 30, 6
    rows = (len(shots) + cols - 1) // cols
    sheet = Raster(cols * (thumb_w + gap) + gap, rows * (thumb_h + bar + gap) + gap)
    fill(sheet, 0, 0, sheet.w, sheet.h, (40, 40, 40))
    for k, (rep, ras) in enumerate(shots):
        x0 = gap + (k % cols) * (thumb_w + gap)
        y0 = gap + (k // cols) * (thumb_h + bar + gap)
        fill(sheet, x0, y0, thumb_w, bar, (40, 150, 60) if rep["ok"] else (190, 40, 40))
        draw_text(sheet, x0 + 4, y0 + 4, f"{rep['index']} {rep['label']}"[:26], (255, 255, 255))
        draw_text(sheet, x0 + 4, y0 + 17, ("OK" if rep["ok"] else " ".join(rep["failed"]))[:26], (255, 255, 255))
        th = int(ras.h * thumb_w / ras.w)
        for ty in range(th):
            sy = min(ras.h - 1, int(ty * ras.h / th))
            di = ((y0 + bar + ty) * sheet.w + x0) * 3
            for tx in range(thumb_w):
                si = (sy * ras.w + min(ras.w - 1, int(tx * ras.w / thumb_w))) * 3
                sheet.data[di:di + 3] = ras.data[si:si + 3]
                di += 3
    encode_png(sheet, path)
    return path


# ---------------------------------------------------------------------------------------------------
# Checks

def covers(pack, z, lat, lon, overzoom):
    """Does a pack (from /health) cover this view centre and zoom?"""
    if not pack:
        return False
    w, s, e, n = pack["bounds"]
    return w <= lon <= e and s <= lat <= n and pack["minZoom"] <= z <= pack["maxZoom"] + overzoom


# MapLibre requests DEM and OSM tiles up to the pack's max zoom and overzooms past it, so deeper views still load new
# (parent) tiles; contours are generated from the DEM one zoom past it and overzoomed too: hence the +4 and the +1 below.
# T154 (finding 2026-10-26-z8-tiles-check-misses-the-start-up-tile): a source is expected when a tile of the viewport
# itself overlaps its pack's bounds, and a tile of the viewport that a pack answered before the view's baseline counts.
# The z8 view needs three OSM tiles of which one overlaps the narrow outdoors pack; the app fetched that one at start,
# before the tour's first /stats, and MapLibre kept it, so the view's own delta was 0 although the pack had answered it.

def lon_to_x(lon, z):
    return (lon + 180.0) / 360.0 * (1 << z)


def lat_to_y(lat, z):
    r = math.radians(max(-85.0511287798, min(85.0511287798, lat)))
    return (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * (1 << z)


def tile_box(z, x, y):
    """[west, south, east, north] of tile z/x/y in degrees."""
    n = 1 << z
    lat = lambda yy: math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / n))))
    return [x / n * 360.0 - 180.0, lat(y + 1), (x + 1) / n * 360.0 - 180.0, lat(y)]


def boxes_overlap(a, b):
    """Whether boxes [w, s, e, n] overlap; touching edges do not count (PackPrecedence.overlaps, MapViewport.overlaps)."""
    return a[2] > b[0] and a[0] < b[2] and a[3] > b[1] and a[1] < b[3]


def viewport_tiles(box, z):
    """The tiles (z, x, y) a view's box [w, s, e, n] covers more than an edge of, at zoom z."""
    n, eps = 1 << z, 1e-9
    w, s, e, nn = box
    if e < w:  # across the antimeridian
        e += 360.0
    x0, x1 = math.floor(lon_to_x(w, z) + eps), math.ceil(lon_to_x(e, z) - eps) - 1
    y0, y1 = math.floor(lat_to_y(nn, z) + eps), math.ceil(lat_to_y(s, z) - eps) - 1
    y0, y1 = max(0, y0), min(n - 1, y1)
    return [(z, x % n, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def covering_zooms(view_z, src):
    """The zooms MapLibre Native requests a style source's tiles at for a view at view_z: its covering zoom counts
    512 px tiles, so a vector source (512 px by default) is asked at floor(z) and the 256 px raster-dem one zoom deeper;
    raster sources round instead of flooring, so both are given. Clamped to the source's maxzoom (overzoomed past it);
    a view below the source's minzoom asks for none."""
    size = (src or {}).get("tileSize", 512)
    zz = view_z + math.log2(512.0 / size)
    lo, hi = (src or {}).get("minzoom", 0), (src or {}).get("maxzoom", 22)
    return sorted({min(q, hi) for q in (math.floor(zz), math.floor(zz + 0.5)) if q >= lo})


def tile_name(t):
    return f"{t[0]}/{t[1]}/{t[2]}"


def source_expectations(health, v, view_box, style_sources):
    """Per source: whether the view expects tiles from its pack, why, and the viewport tiles that overlap the pack's
    bounds (`packTiles`, the only tiles that may count from before the baseline). Without the view's box (an app without viewBounds in GET /attribution) the
    centre test of covers() decides, as before T154. A view's |req= decides `expected` and keeps the rest as evidence."""
    packs = health.get("packs", {}) or {}
    out = {}
    for s, pack, zoff in (("dem", packs.get("dem"), 0), ("osm", packs.get("osm"), 0), ("contours", packs.get("dem"), 1)):
        e = {"expected": False, "packTiles": []}
        if not pack:
            e["reason"] = "no pack in /health"
        elif not pack["minZoom"] <= v["z"] + zoff <= pack["maxZoom"] + 4:
            e["reason"] = f"zoom {v['z'] + zoff:g} outside the pack's {pack['minZoom']} to {pack['maxZoom']} (+4 overzoom)"
        elif view_box is None:
            e["expected"] = covers(pack, v["z"] + zoff, v["lat"], v["lon"], 4)
            e["reason"] = f"view centre {'inside' if e['expected'] else 'outside'} the pack's bounds (no viewBounds)"
        else:
            zooms = covering_zooms(v["z"], (style_sources or {}).get(s))
            tiles = [t for z in zooms for t in viewport_tiles(view_box, z)]
            over = [t for t in tiles if boxes_overlap(tile_box(*t), pack["bounds"])]
            e["expected"] = bool(over)
            e["packTiles"] = [tile_name(t) for t in over]
            e["reason"] = (f"{len(over)} of {len(tiles)} viewport tiles at z{'/'.join(str(z) for z in zooms) or '-'} "
                           f"overlap the pack's bounds")
        if v["req"] is not None:
            e["expected"] = s in v["req"]
            e["reason"] = f"|req= in the view ({e.get('reason')})"
        out[s] = e
    return out


def served_before_baseline(log_lines, source, pack_tiles):
    """Lines of the phone's request log (GET /stats/requests) where a pack (not the online fallback) answered one of
    `pack_tiles` ("z/x/y", the viewport's tiles that overlap a pack /health lists now) for this source: the tiles MapLibre
    already held when the view's baseline was read. Only tiles over a pack still listed count, so log lines from a pack
    removed since (a /reload in the same process) never do. Each tile once, oldest first."""
    want, seen, out = set(pack_tiles or ()), set(), []
    for ln in log_lines or []:
        if ln.get("route") != source or ln.get("status") != 200:
            continue
        pack = ln.get("pack")
        if not pack or str(pack).startswith("online:"):
            continue
        tile = tile_of_path(ln.get("path"))
        if tile in want and tile not in seen:
            seen.add(tile)
            out.append({"tile": tile, "pack": pack, "ts": ln.get("ts")})
    return out


# T170 (T154's follow-up): the sources whose delta also counts tiles made over the online fallback (a contour tile is
# generated over online DEM where no pack covers it), so only the request log's pack-answered lines prove the pack.
PACK_ONLY_SOURCES = ("contours",)


def pack_answered(lines, source):
    """T170: the lines of `lines` (the request log) where a pack, not the online fallback, answered a tile of `source`."""
    return [ln for ln in lines or [] if ln.get("route") == source and ln.get("status") == 200
            and ln.get("pack") and not str(ln.get("pack")).startswith("online:")]


def online_over_pack(lines, source, pack_tiles):
    """T170: the lines where the online fallback answered one of `pack_tiles` ("z/x/y", the viewport's tiles that overlap
    the pack's bounds) for `source`: a tile the pack covers only in part is made over the pack's and the online elevation
    together, so it shows the pack's contours too."""
    want = set(pack_tiles or ())
    return [ln for ln in lines or [] if ln.get("route") == source and ln.get("status") == 200
            and str(ln.get("pack") or "").startswith("online:") and tile_of_path(ln.get("path")) in want]


def tile_of_path(path):
    """"/contours/10/165/360.pbf" -> "10/165/360"."""
    return str(path or "").rsplit(".", 1)[0].strip("/").split("/", 1)[-1]


REQUEST_LOG_CAPACITY = 4096  # RequestLog.DEFAULT_CAPACITY on the phone


def request_log_total(server):
    """T170: the request log's `total` (lines ever added), or None from an app without GET /stats/requests."""
    try:
        return server.json("/stats/requests?limit=0", timeout=30).get("total")
    except (urllib.error.URLError, OSError, ValueError, RuntimeError):
        return None


def tiles_check(tiles, exp, log_lines, expectation, view_lines=None):
    """T154: the tiles check. A source passes when the view's own delta served a tile, or when the request log shows a
    pack answered one of the source's `packTiles` (source_expectations) before the baseline (adds `servedBeforeBaseline`
    and `beforeBaseline` to tiles[source]). T170: for PACK_ONLY_SOURCES the delta counts only when the view's own lines
    of the request log (`view_lines`, None when not read or not whole) show a pack answered one of them, or the online
    fallback answered one of the source's `packTiles` (a tile over the pack's edge; adds `servedFromPack`, `servedOverPack`
    and `servedOnline`); else the source is checked as one with no delta. Returns (ok, the expected sources without
    tiles)."""
    missing = []
    for s in exp:
        if tiles[s]["served"] > 0:
            if s not in PACK_ONLY_SOURCES or view_lines is None:
                continue
            own = len(pack_answered(view_lines, s))
            edge = len(online_over_pack(view_lines, s, expectation[s].get("packTiles")))
            tiles[s]["servedFromPack"] = own
            tiles[s]["servedOverPack"] = edge
            tiles[s]["servedOnline"] = sum(1 for ln in view_lines if ln.get("route") == s and ln.get("status") == 200
                                           and str(ln.get("pack") or "").startswith("online:"))
            if own + edge > 0:
                continue
        early = served_before_baseline(log_lines, s, expectation[s].get("packTiles"))
        tiles[s]["servedBeforeBaseline"] = len(early)
        if early:
            tiles[s]["beforeBaseline"] = early[:12]
        else:
            missing.append(s)
    return not missing, missing


# T123: the routes the app's background guard answers 503 while its activity is stopped (RequestChecks.needsActivity),
# and the ones of them checked again in the foreground (POST /attribution/open is left out there: it opens a screen).
BACKGROUND_ROUTES = [("GET", "/screenshot?wait=15000"), ("GET", "/features"), ("GET", "/features/text?wait=15000"),
                     ("GET", "/help"), ("POST", "/attribution/open"),
                     # T128: the sheet-driving UI routes (closing or the list, harmless if one ever got through)
                     ("POST", "/settings/ui?open=0"), ("POST", "/settings/open?group=list"), ("POST", "/search/ui"),
                     ("POST", "/storage/ui?open=0"), ("POST", "/areas/ui?open=0")]
FOREGROUND_ROUTES = [("GET", "/screenshot?wait=15000"), ("GET", "/features"), ("GET", "/help")]


def timed_call(server, method, path, timeout):
    """Status, seconds and the start of the body of one request (an HTTP error is an answer, not an exception)."""
    t0 = time.time()
    try:
        body = server._open(path, method, timeout)
        status = 200
    except urllib.error.HTTPError as e:
        status, body = e.code, e.read()
    except (urllib.error.URLError, OSError) as e:
        return {"method": method, "path": path, "status": None, "seconds": round(time.time() - t0, 2), "error": str(e)[:200]}
    out = {"method": method, "path": path, "status": status, "seconds": round(time.time() - t0, 2), "bytes": len(body)}
    if not body.startswith(b"\x89PNG"):
        try:
            out["body"] = json.loads(body.decode("utf-8")) if status != 200 else None
        except (ValueError, UnicodeDecodeError):
            out["body"] = body[:200].decode("utf-8", "replace")
    return out


def wait_activity(server, want_started, timeout=15.0):
    """Polls GET /health until map.activity is `started` (or is not, with want_started False); the last value."""
    deadline = time.time() + timeout
    state = None
    while time.time() < deadline:
        try:
            state = server.json("/health", timeout=10).get("map", {}).get("activity")
        except (urllib.error.URLError, OSError, ValueError):
            state = None
        if state is not None and (state == "started") == want_started:
            break
        time.sleep(0.3)
    return state


def background_step(server, exe, serial, limit_s):
    """T123: HOME, then each activity-bound route must answer 503 with a reason within limit_s; back to the app
    (am start of the running task), then the same routes answer 200. Reports every call."""
    rep = {"limitSeconds": limit_s, "background": [], "foreground": []}
    adb(exe, serial, "shell", "input", "keyevent", "KEYCODE_HOME", timeout=30)
    rep["activityAfterHome"] = wait_activity(server, False)
    for method, path in BACKGROUND_ROUTES:
        r = timed_call(server, method, path, timeout=limit_s * 6)
        body = r.get("body") if isinstance(r.get("body"), dict) else {}
        r["ok"] = r["status"] == 503 and r["seconds"] <= limit_s and bool(body.get("reason"))
        rep["background"].append(r)
    # The intent restart_app launched with brings the running task back ("its current task has been brought to the
    # front"); the launcher's MAIN/LAUNCHER intent did not match that task's root and stacked a second map activity.
    _, out = adb(exe, serial, "shell", "am", "start", "-n", ACTIVITY, timeout=30)
    rep["returnedTask"] = "brought to the front" in out
    rep["activityAfterReturn"] = wait_activity(server, True)
    for method, path in FOREGROUND_ROUTES:
        r = timed_call(server, method, path, timeout=90)
        r["ok"] = r["status"] == 200
        rep["foreground"].append(r)
    rep["ok"] = (all(r["ok"] for r in rep["background"] + rep["foreground"]) and rep["activityAfterReturn"] == "started"
                 and rep["returnedTask"])
    return rep


# T127: the cold direct view after the tour: no pack, no server, a public area the tour never loaded.
DIRECT_VIEW = "9/45.865/6.865:mont-blanc-z9"
OOM_PATTERN = "OutOfMemoryError"


class HeapSampler(threading.Thread):
    """T127: polls GET /stats (memory.javaHeapUsedBytes) every `period` s on its own connection until stopped; the
    peak, the samples and how many polls the app did not answer (a crash or a stuck server)."""

    def __init__(self, server, period=0.4):
        super().__init__(daemon=True)
        self.base, self.token, self.period = server.base, server.token, period
        self.samples, self.failures, self.max_bytes = [], 0, None
        self._halt = threading.Event()

    def run(self):
        t0 = time.time()
        while not self._halt.is_set():
            try:
                req = urllib.request.Request(self.base + "/stats", headers={"X-Dystopo-Token": self.token})
                with urllib.request.urlopen(req, timeout=10) as r:
                    m = json.loads(r.read().decode("utf-8")).get("memory") or {}
                self.samples.append((round(time.time() - t0, 1), m.get("javaHeapUsedBytes")))
                self.max_bytes = m.get("javaHeapMaxBytes") or self.max_bytes
            except (urllib.error.URLError, OSError, ValueError, http.client.HTTPException):
                self.failures += 1
            self._halt.wait(self.period)

    def stop(self):
        self._halt.set()
        self.join(15)
        used = [b for _, b in self.samples if b is not None]
        return {"polls": len(self.samples), "failedPolls": self.failures, "periodS": self.period,
                "peakBytes": max(used) if used else None, "maxBytes": self.max_bytes}


def meminfo(exe, serial):
    """T127: the app's `dumpsys meminfo` summary in kB (Java Heap, Native Heap, Graphics, Code, TOTAL PSS), or None."""
    if not exe or not serial:
        return None
    code, out = adb(exe, serial, "shell", "dumpsys", "meminfo", PACKAGE, timeout=60)
    if code != 0:
        return None
    rows = {}
    for line in out.splitlines():
        parts = line.strip().split(":")
        if len(parts) >= 2 and parts[0].strip() in ("Java Heap", "Native Heap", "Graphics", "Code", "TOTAL PSS"):
            nums = parts[1].split()
            if nums and nums[0].isdigit():
                rows[parts[0].strip().replace(" ", "")] = int(nums[0])
    return rows or None


def oom_lines(exe, serial, since):
    """T127: logcat lines (every buffer) naming an OutOfMemoryError since device time `since`."""
    if not exe or not serial or not since:
        return []
    _, out = adb(exe, serial, "logcat", "-d", "-b", "all", "-v", "brief", "-t", since, timeout=60)
    return [l.strip()[:200] for l in out.splitlines() if OOM_PATTERN in l][:10]


def live_heap(server, exe, serial, timeout=20.0):
    """T127: the app's live Java heap: `am send-trim-memory <package> RUNNING_LOW` makes the app's onTrimMemory
    (HeapGuard.trim with measureLive) collect, wait 200 ms and read the used heap before it drops any cache; the reading
    is the newest `recentTrims` entry's `liveBeforeTrimBytes` in GET /online direct.memory. None when it never came.

    T147 (T138's follow-up): Android refuses a level at or below the one the process already has ("Unable to set a
    higher trim level than current level"; seen on API 35 for RUNNING_LOW after the direct step's own trim in the same
    process, while the tour's background step leaves it at 0 again), and refuses COMPLETE and every level from
    UI_HIDDEN up to a foreground process ("Unable to set a background trim level on a foreground process"). So a
    refused RUNNING_LOW is followed by RUNNING_CRITICAL, the one foreground level above it (the app measures at any level
    from RUNNING_LOW); when both are refused the error says why in words, never the shell's stack trace. `level` is the
    one delivered, `refused` each level turned down with Android's reason, `droppedBytes` the trim's own (decodedBlocks,
    geoidMemo, ...)."""
    def mem():
        return ((server.json("/online").get("direct") or {}).get("memory") or {})
    n0 = mem().get("trims") or 0
    refused, code, out = [], 0, ""
    for level in TRIM_LEVELS:
        code, out = adb(exe, serial, "shell", "am", "send-trim-memory", PACKAGE, level, timeout=30)
        why = trim_refusal(code, out)
        if not why:
            break
        refused.append({"level": level, "reason": why})
    else:
        return {"liveBytes": None, "sent": False, "refused": refused, "error": refusal_words(refused)}
    rep = {"sent": True, "level": level, **({"refused": refused} if refused else {})}
    deadline = time.time() + timeout
    while time.time() < deadline:
        m = mem()
        if (m.get("trims") or 0) > n0:
            for t in reversed(m.get("recentTrims") or []):
                if str(t.get("reason", "")).startswith("onTrimMemory") and t.get("liveBeforeTrimBytes") is not None:
                    return {"liveBytes": t["liveBeforeTrimBytes"], **rep, "droppedBytes": t.get("droppedBytes"), "trim": t}
        time.sleep(0.5)
    return {"liveBytes": None, **rep, "error": f"{level} was accepted but the app recorded no onTrimMemory trim within {timeout:g} s"}


# T147: the `am send-trim-memory` levels a foreground app accepts and the app measures at (HeapGuard.onTrimMemory takes
# RUNNING_LOW and up; Android gives a foreground process nothing from UI_HIDDEN up, COMPLETE included), lowest first.
TRIM_LEVELS = ("RUNNING_LOW", "RUNNING_CRITICAL")


def trim_refusal(code, out):
    """Android's reason when `am send-trim-memory` was refused (the exception's message, one line), else None. The shell
    prints a stack trace and exits non-zero; older images print "Error: ..." with exit 0."""
    text = (out or "").strip()
    if code == 0 and "exception" not in text.lower() and not text.lower().startswith("error"):
        return None
    for line in text.splitlines():
        if "Exception:" in line:
            return line.split("Exception:", 1)[1].strip()
        if line.lower().startswith("error:"):
            return line.split(":", 1)[1].strip()
    return (text.splitlines() or [f"exit {code}"])[0][:200]


def refusal_words(refused):
    """T147: every level refused, said plainly."""
    levels = " and ".join(r["level"] for r in refused)
    if all("higher trim level" in r["reason"] for r in refused):
        return (f"not measured: Android refused {levels} because this process already has {refused[-1]['level']} from an earlier "
                "trim (Android delivers only a level above the current one, and refuses COMPLETE to a foreground app)")
    return "not measured: Android refused " + "; ".join(f"{r['level']} ({r['reason']})" for r in refused)


def trim_words(live):
    """T147 (T138's follow-up): the direct step's trim on its stderr line: the level delivered, the decoded DEM blocks it
    dropped (`droppedBytes.decodedBlocks`, the largest cache) and the levels Android refused first."""
    if not live.get("level"):
        return "trim not delivered"
    dropped = (live.get("droppedBytes") or {}).get("decodedBlocks")
    words = f"trim {live['level']} dropped decodedBlocks " + ("-" if dropped is None else f"{dropped / 1048576:.1f} MB")
    if live.get("refused"):
        words += f" (after Android refused {', '.join(r['level'] for r in live['refused'])}: the process already had it)"
    return words


def direct_step(server, exe, serial, view, bound_share, wait_ms, restart=False, clear_sources=True, shot=None):
    """T135 (finding 2026-10-23-direct-step-live-heap-unread-with-a-server): the direct view measures the phone's own
    renders, so with a server configured (the sweep's `--server`) the app is switched to no server for the step
    (`POST /settings?server=`, the same process: the tour's caches stay) and switched back afterwards (`serverSwitch`:
    from, switched, restored). When it cannot be switched the step is recorded as skipped with the reason (`skipped`,
    ok) rather than measuring a view the server answers; a failed restore fails the step (the rest of the run would
    use the wrong server)."""
    configured = server.json("/online").get("server")  # AppSettings.server; None with no server (direct://sources)
    if not configured:
        return direct_view(server, exe, serial, view, bound_share, wait_ms, restart, clear_sources, shot)
    switch = {"from": configured}
    try:
        set_app_server(server, "")
        now = server.json("/online")
        switch["switched"] = not now.get("server") and (now.get("direct") or {}).get("direct") is True
        if not switch["switched"]:
            switch["error"] = f"the app reports server {now.get('server')!r} and direct {(now.get('direct') or {}).get('direct')!r}"
    except (urllib.error.URLError, OSError, ValueError, RuntimeError, http.client.HTTPException) as e:
        switch["switched"], switch["error"] = False, str(e)[:200]
    try:
        if switch["switched"]:
            rep = direct_view(server, exe, serial, view, bound_share, wait_ms, restart, clear_sources, shot)
        else:
            rep = {"label": view["label"], "view": view["view"], "boundShare": bound_share, "ok": True,
                   "skipped": f"the app's server could not be switched off for the step: {switch.get('error')}"}
    finally:
        try:
            set_app_server(server, configured)
            switch["restored"] = server.json("/online").get("server") == configured
        except (urllib.error.URLError, OSError, ValueError, RuntimeError, http.client.HTTPException) as e:
            switch["restored"], switch["restoreError"] = False, str(e)[:200]
    rep["serverSwitch"] = switch
    if not switch["restored"]:
        rep["ok"] = False
    return rep


def direct_view(server, exe, serial, view, bound_share, wait_ms, restart=False, clear_sources=True, shot=None):
    """T127: one cold direct view (no pack covers it, no server): the online cache cleared (pinned tiles kept) and,
    with `clear_sources`, the phone's source range cache too, so every DEM tile is rendered from the public sources;
    then the camera, a screenshot once idle (or after `wait_ms`), the Java heap sampled from /stats all along, then the
    live heap after a collection (`live_heap`). ok: the same process before and after, no crash and no OutOfMemoryError
    in logcat, DEM tiles rendered, and the live heap at most `bound_share` of the heap's max. Not required: idle (a cold
    z8 to z11 view from the public sources can take minutes; blank tiles can pass) and the sampled peaks (`peakBytes`, the
    app's burst peak), which count garbage not yet collected and are reported as numbers only. With `restart`, a new
    process first (the per-zoom table's fresh case)."""
    rep = {"label": view["label"], "view": view["view"], "boundShare": bound_share}
    if restart:
        restart_app(exe, serial, server, 120)
    since = android_debug.device_now(exe, serial) if exe and serial else None
    pid = android_debug.app_pid(exe, serial) if exe and serial else None
    online = server.json("/online")
    rep["server"] = online.get("server") or (online.get("fetcher") or {}).get("server")
    rep["onlineCacheCleared"] = server.json("/online/cache/clear", method="POST")
    if clear_sources and exe and serial:
        # The app's own files, through run-as (debug builds); OpenFreeMap's TileJSON (sources/openfreemap) stays.
        code, out = adb(exe, serial, "shell", "run-as", PACKAGE, "sh", "-c",
                        "'cd cache/sources 2>/dev/null && for d in *; do [ \"$d\" = openfreemap ] || rm -rf \"$d\"; done; echo cleared'",
                        timeout=120)
        rep["sourceCacheCleared"] = code == 0 and "cleared" in out
    seq0 = ((online.get("direct") or {}).get("memory") or {}).get("burstSeq") or 0  # T127: the app's render bursts so far
    before = server.json("/stats").get("memory") or {}
    rep["heapBeforeBytes"] = before.get("javaHeapUsedBytes")
    sampler = HeapSampler(server)  # the token is current: /stats just answered
    sampler.start()
    t0 = time.time()
    try:
        server.json(f"/camera?lat={view['lat']}&lon={view['lon']}&zoom={view['z']}&bearing=0&tilt=0", method="POST")
        png = server.get(f"/screenshot?wait={wait_ms}", timeout=wait_ms / 1000 + 60)
        rep["idleSeconds"] = round(time.time() - t0, 1)
        if shot:
            with open(shot, "wb") as f:
                f.write(png)
            rep["screenshot"] = shot
        health = server.json("/health")
        rep["idle"] = health.get("map", {}).get("lastScreenshotIdle") is True
        time.sleep(2)  # renders that finish just after idle (out-of-view synthesis edges) count in the peak too
    except (urllib.error.URLError, OSError, ValueError, http.client.HTTPException) as e:
        rep["error"] = str(e)[:200]
        rep["idle"] = False
    finally:
        rep["heap"] = sampler.stop()
    try:
        d = server.json("/online").get("direct") or {}
        rep["app"] = {k: d.get(k) for k in ("demRendered", "renderedByZoom", "demMsMax", "failures", "lastError")}
        rep["app"]["memory"] = d.get("memory")  # T127: the app's own peak, render limit and trims (none before T127)
    except (urllib.error.URLError, OSError, ValueError, http.client.HTTPException) as e:
        rep["app"] = {"error": str(e)[:200]}
    rep["meminfoKb"] = meminfo(exe, serial)
    try:
        rep["live"] = live_heap(server, exe, serial) if exe and serial else {"liveBytes": None, "error": "no adb device"}
    except (urllib.error.URLError, OSError, ValueError, http.client.HTTPException, subprocess.SubprocessError) as e:
        rep["live"] = {"liveBytes": None, "error": str(e)[:200]}
    pid_after = android_debug.app_pid(exe, serial) if exe and serial else None
    rep["sameProcess"] = pid is None or pid == pid_after
    rep["oom"] = oom_lines(exe, serial, since)
    rep["crash"] = android_debug.launch_crash(exe, serial, since) if exe and serial and since else None
    peak = rep["heap"]["peakBytes"]
    mem = (rep.get("app") or {}).get("memory") or {}
    app_peak = max([b.get("peakHeapBytes") or 0 for b in mem.get("bursts") or [] if b.get("seq", 0) > seq0] or [0]) or None
    if app_peak is not None and (peak is None or app_peak > peak):
        peak = app_peak  # the app samples between the host's polls
    rep["peakBytes"] = peak
    mx = rep["heap"]["maxBytes"] or ((rep.get("app") or {}).get("memory") or {}).get("heapMaxBytes")
    live = rep["live"].get("liveBytes")
    rep["liveBytes"] = live
    rep["boundBytes"] = int(mx * bound_share) if mx else None
    rep["peakShare"] = round(peak / mx, 3) if peak and mx else None
    rep["liveShare"] = round(live / mx, 3) if live and mx else None
    rendered = (rep.get("app") or {}).get("demRendered")
    # Idle and the sampled peak are reported, not required (see the docstring); the bound is on the live heap.
    rep["ok"] = bool(rep["sameProcess"] and not rep["oom"] and not rep["crash"] and "error" not in rep and (rendered or 0) > 0
                     and live is not None and rep["boundBytes"] is not None and live <= rep["boundBytes"])
    return rep


# ---------------------------------------------------------------------------------------------------
# T212: the cards at a large font scale

def parse_cards(text):
    """T212: `@cards <fromLat>,<fromLon>;<toLat>,<toLon> [scale=2.0] [fixture=still-fall] [bearing=30] [density=<dpi>]`
    from the tour file: the font-scale step's route, the scale, the warning's fixture, the camera's bearing (a turned
    map shows the compass) and, optionally, a display density to set for the step (`wm density`, restored after).
    T224: `rotation=0|1` the phone upright or on its side (`user_rotation`, restored after)."""
    parts = text.split()
    ends = parts[1].split(";")
    step = {"from": ends[0], "to": ends[1], "scale": "2.0", "fixture": "still-fall", "bearing": "30", "density": None, "rotation": "0"}
    for p in parts[2:]:
        k, _, v = p.partition("=")
        if k not in step:
            raise ValueError(f"@cards: unknown option {k!r}")
        step[k] = v
    return step


def overlaps(a, b, slack=0):
    """T212: whether two `[left, top, right, bottom]` rectangles (px) share more than `slack` px in both directions; None
    when either is missing."""
    if not a or not b:
        return None
    return a[0] < b[2] - slack and b[0] < a[2] - slack and a[1] < b[3] - slack and b[1] < a[3] - slack


# T224: the pairs `GET /layout` must never name: a chip column under the credit line, the strip reaching a right-hand chip.
LAYOUT_CHIPS = ("M8", "M11", "M41")
LAYOUT_FORBIDDEN = [(chip, "M99") for chip in LAYOUT_CHIPS] + [("M98", "M11"), ("M98", "M41")]


def layout_forbidden(overlap_list):
    """T224: the entries of `GET /layout` `overlaps` that name a forbidden pair, either way round."""
    bad = []
    for o in overlap_list or []:
        pair = (o.get("a"), o.get("b"))
        if pair in LAYOUT_FORBIDDEN or pair[::-1] in LAYOUT_FORBIDDEN:
            bad.append(o)
    return bad


def layout_read(server):
    """T224: `GET /layout` cut down for a report: the window, the pairs that overlap today, the hidden elements with their
    reasons, what the pure rule would change and whether its own rectangles overlap; `error` when the route did not answer.
    T241: `rects` are read from each element's `Modifier.control` node (none worked out from an edge), the Online chip's
    card and the first-run card among them while they show; `--layout-baseline` compares them with an earlier run."""
    try:
        lay = server.json("/layout", timeout=30)
    except (urllib.error.URLError, OSError, ValueError, http.client.HTTPException) as e:
        return {"error": str(e)[:200], "overlaps": []}
    rule = lay.get("rule") or {}
    return {"window": lay.get("window"), "fontScale": lay.get("fontScale"), "overlaps": lay.get("overlaps") or [],
            "hidden": {e["id"]: e.get("reason") for e in lay.get("elements") or [] if e.get("state") == "hidden" and e.get("reason") not in ("notUp", "mapFacingNorth")},
            "compact": {e["id"]: e.get("reason") for e in lay.get("elements") or [] if e.get("state") == "compact"},
            "rects": {e["id"]: e.get("rectPx") for e in lay.get("elements") or [] if e.get("rectPx")},
            "ruleRects": {e["id"]: e.get("rectPx") for e in rule.get("elements") or [] if e.get("rectPx")},
            "ruleOverlaps": rule.get("overlaps") or [], "ruleDiffers": rule.get("differs") or [], "ruleSideColumn": rule.get("sideColumn"), "ruleCardColumns": rule.get("cardColumns"),
            "semanticsAway": lay.get("semanticsAway") or []}  # T243


def pair_names(overlap_list):
    """T224: `a x b` per overlap, for the summary line."""
    return sorted({f"{o.get('aName') or o.get('a')} x {o.get('bName') or o.get('b')}" for o in overlap_list or []})


def layout_rects_by_place(summary):
    """T241: every `GET /layout` read of a summary as {place: {id: rect}}: a tour view by its label (`view <label>`), a
    card step's view by its font scale, rotation and name (`cards 2.0/0 planning`)."""
    out = {}
    for r in summary.get("views") or []:
        lay = r.get("layout") or {}
        if "error" not in lay:
            out[f"view {r.get('label')}"] = lay.get("rects") or {}
    for st in (summary.get("cardsStep") or {}).get("steps") or []:
        for name, lay in (st.get("layout") or {}).items():
            if isinstance(lay, dict) and "error" not in lay:
                out[f"cards {st.get('scale')}/{st.get('rotation', '0')} {name}"] = lay.get("rects") or {}
    return out


def layout_compare(base, now, tolerance=1):
    """T241 (`--layout-baseline`): the rectangles of this run against an earlier run's summary, place by place and element
    by element. An element drawn in both whose sides moved more than `tolerance` px is in `differs` (with `maxDiffPx`);
    one drawn in only one run is in `onlyBase` or `onlyNow`; places read in one run only are listed, not compared.
    Reported, never a failure: the content of a view (a credit line's text, the clock) can move a rectangle too."""
    b, n = layout_rects_by_place(base), layout_rects_by_place(now)
    rep = {"tolerancePx": tolerance, "places": 0, "compared": 0, "within": 0, "differs": [], "onlyBase": [], "onlyNow": [],
           "placesOnlyBase": sorted(set(b) - set(n)), "placesOnlyNow": sorted(set(n) - set(b))}
    for place in sorted(set(b) & set(n)):
        rep["places"] += 1
        rb, rn = b[place], n[place]
        for eid in sorted(set(rb) | set(rn)):
            if eid not in rn:
                rep["onlyBase"].append({"place": place, "id": eid, "base": rb[eid]})
            elif eid not in rb:
                rep["onlyNow"].append({"place": place, "id": eid, "now": rn[eid]})
            else:
                rep["compared"] += 1
                diff = max(abs(x - y) for x, y in zip(rb[eid], rn[eid]))
                if diff <= tolerance:
                    rep["within"] += 1
                else:
                    rep["differs"].append({"place": place, "id": eid, "base": rb[eid], "now": rn[eid], "maxDiffPx": diff})
    return rep


def plate_edges(ras, rect, colour, reach=6, tolerance=3):
    """T224: where a plate of one colour is really drawn around `rect` ([left, top, right, bottom], px): along several lines
    through each side, away from its rounded corners, the first pixel within `reach` of the side that has the colour, moving
    from outside in, so the text inside the plate is not reached; the innermost over the lines, since a map the colour of
    the plate reads as plate on some of them. None for a side with no such pixel (covered, or off the picture)."""
    def is_plate(x, y):
        if not (0 <= x < ras.w and 0 <= y < ras.h):
            return False
        return all(abs(a - b) <= tolerance for a, b in zip(ras.px(x, y)[:3], colour))
    left, top, right, bottom = [int(v) for v in rect]
    rows = [top + (bottom - top) * k // 8 for k in range(2, 7)]
    cols = [left + (right - left) * k // 8 for k in range(2, 7)]
    def first(line, is_inside):
        for p in line:
            if is_inside(p):
                return p
        return None
    def side(lines, pick):
        found = [v for v in lines if v is not None]
        return pick(found) if found else None
    return [
        side([first(range(left - reach, left + reach + 1), lambda x: is_plate(x, y)) for y in rows], max),
        side([first(range(top - reach, top + reach + 1), lambda y: is_plate(x, y)) for x in cols], max),
        (lambda v: None if v is None else v + 1)(side([first(range(right + reach - 1, right - reach - 1, -1), lambda x: is_plate(x, y)) for y in rows], min)),
        (lambda v: None if v is None else v + 1)(side([first(range(bottom + reach - 1, bottom - reach - 1, -1), lambda y: is_plate(x, y)) for x in cols], min)),
    ]


def strip_drawn_check(path, rect, night=False):
    """T224: the strip's published rectangle against its plate in a screenshot: the drawn edges, the largest difference in
    px (None when a side could not be read). `rect` is `GET /layout`'s, the plate `readout/ReadoutStrip.kt`'s opaque colour."""
    if not path or not rect:
        return None
    with open(path, "rb") as f:
        ras = decode_png(f.read())
    colour = (0x20, 0x1C, 0x1A) if night else (0xF6, 0xF1, 0xE4)
    edges = plate_edges(ras, rect, colour)
    diff = None if any(e is None for e in edges) else max(abs(a - b) for a, b in zip(edges, rect))
    return {"rectPx": rect, "drawnPx": edges, "maxDiffPx": diff}


def screencap(exe, serial, path):
    """T212: the whole screen as the user sees it (the cards are Compose, which `/screenshot`'s map snapshot leaves out)."""
    r = subprocess.run([exe, "-s", serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=60)
    if r.returncode == 0 and r.stdout.startswith(b"\x89PNG"):
        with open(path, "wb") as f:
            f.write(r.stdout)
        return path
    return None


def ui_descriptions(exe, serial):
    """T212: the content descriptions `uiautomator dump` lists (the scroll regions carry theirs; TalkBack itself cannot be
    driven on an emulator, the critic's objection 17)."""
    code, _ = adb(exe, serial, "shell", "uiautomator dump /sdcard/dystopo-ui.xml", timeout=60)
    code2, xml = adb(exe, serial, "exec-out", "cat /sdcard/dystopo-ui.xml", timeout=60)
    if code != 0 or code2 != 0:
        return None
    import re
    return sorted(set(re.findall(r'content-desc="([^"]+)"', xml)))


def swipe_to_end(exe, serial, box, times=6):
    """T212: swipes up inside `box` (px) to scroll a region to its end."""
    if not box:
        return
    x = int(box[2] - 6)  # at the thumb's edge, clear of the lines' taps (a slow swipe on one reads as a tap on a busy host)
    y0 = int(box[1] + (box[3] - box[1]) * 0.85)
    y1 = int(box[1] + (box[3] - box[1]) * 0.15)
    for _ in range(times):
        adb(exe, serial, "shell", f"input swipe {x} {y0} {x} {y1} 120", timeout=30)  # quick: a fling carries a small box further
        time.sleep(0.3)


def cards_step(server, exe, serial, step, shot_stem, ext):
    """T212 (the research `cards-at-large-font-scales`): at the system font scale `step["scale"]` (2.0), plans the route,
    raises the pressure warning through `POST /readout/trend?fixture=` and navigates the route, and checks from the
    bounds the debug routes give that nothing overlaps: the planning card under the top chips (`routeCardClearOfTopChips`)
    and under the scale bar's line, the scale bar and the readout strip (at least the elevation) drawn and clear of the
    card, every line reachable (a swipe in the card's scrolling region reaches `routeCardScrollMaxPx`), the scroll region's
    content description in `uiautomator dump`; the warning card clear of the scale bar, the strip and the compass (the
    map turned to `bearing`, so MapLibre draws its compass; `GET /camera` `orientation.heading.compass.boundsPx`); the
    navigation card at the user's font scale (`layout.navCard.fontScale`), capped at its band and clear of the scale
    bar and the online indicator. Screenshots `<stem>-cards-<name>.png` (adb screencap: the cards are not in /screenshot).
    The warning is raised with the route cleared: with a planning card up at 2.0 no band is left for it, and it then keeps
    its title, advice and actions and reaches over the card (`trend.card.layout.overRoom`). The font scale (and the
    density, when the step sets one) is restored afterwards.

    T224: reads `GET /layout` at every view of the step (`layout` per view: the pairs in `overlaps`, what is hidden and
    why, what the pure rule would change), fails a view whose `overlaps` names a chip column under the credit line or the
    strip reaching a right-hand chip, checks the strip's rectangle against its drawn plate within 1 px, and runs in
    `rotation=1` too: T212's checks written for the upright phone are then reported in `landscapeNotes`, not failed, since
    `overlaps` is the record there. T210's glance step: opens the glance view while navigating, checks `legible`, closes it."""
    rep = {"scale": step["scale"], "rotation": step.get("rotation", "0"), "route": f"{step['from']};{step['to']}", "checks": {}, "shots": {}, "layout": {}}
    c = rep["checks"]
    landscape = str(step.get("rotation", "0")) != "0"

    def view_layout(name):
        lay = layout_read(server)
        rep["layout"][name] = lay
        c[f"layout{name[0].upper()}{name[1:]}Answers"] = "error" not in lay and bool(lay.get("window"))
        c[f"layout{name[0].upper()}{name[1:]}NoForbiddenPair"] = not layout_forbidden(lay.get("overlaps"))
        c[f"layout{name[0].upper()}{name[1:]}RuleClear"] = not lay.get("ruleOverlaps")
        c[f"layout{name[0].upper()}{name[1:]}SemanticsWhereDrawn"] = not lay.get("semanticsAway")  # T243
        return lay

    _, before_scale = adb(exe, serial, "shell", "settings get system font_scale", timeout=30)
    rep["fontScaleBefore"] = before_scale.strip()
    _, before_auto = adb(exe, serial, "shell", "settings get system accelerometer_rotation", timeout=30)
    _, before_rotation = adb(exe, serial, "shell", "settings get system user_rotation", timeout=30)
    density_before = None
    try:
        adb(exe, serial, "shell", f"settings put system font_scale {step['scale']}", timeout=30)
        adb(exe, serial, "shell", "settings put system accelerometer_rotation 0", timeout=30)
        adb(exe, serial, "shell", f"settings put system user_rotation {step.get('rotation', '0')}", timeout=30)
        if step.get("density"):
            _, d = adb(exe, serial, "shell", "wm density", timeout=30)
            density_before = "reset" if "Override" not in d else d.strip().splitlines()[-1].split()[-1]
            adb(exe, serial, "shell", f"wm density {step['density']}", timeout=30)
        time.sleep(4)  # the configuration change recreates the activity
        wait_activity(server, True, 30)
        until = time.time() + 60
        while time.time() < until:  # the map after the activity's recreation (a camera request before it answers 500)
            try:
                if server.json("/camera").get("ready"):
                    break
            except (urllib.error.URLError, OSError, ValueError):
                pass
            time.sleep(1)
        server.json("/route/clear", method="POST")
        lat0, lon0 = (float(x) for x in step["from"].split(","))
        server.json(f"/camera?lat={lat0 + 0.006}&lon={lon0 + 0.003}&zoom=14&bearing={step['bearing']}&tilt=0", method="POST")
        # T239 (T236's card in the online column the rule places): the chip's card open at rest, `GET /layout` read with it.
        try:
            server.json("/online/chip?open=1", method="POST", timeout=30)
            time.sleep(2)
            rep["shots"]["onlineCard"] = screencap(exe, serial, f"{shot_stem}-cards-online{ext}")
            lay_card = view_layout("onlineCard")
            c["onlineCardShown"] = bool((lay_card.get("rects") or {}).get("onlineCard"))
        except (urllib.error.URLError, OSError, ValueError, RuntimeError) as e:
            rep["onlineCardError"] = str(e)[:200]
        finally:
            try:
                server.json("/online/chip?open=0", method="POST", timeout=30)
            except (urllib.error.URLError, OSError, ValueError, RuntimeError):
                pass
        planned =server.json(f"/route?from={step['from']}&to={step['to']}&mode=hike&wait=180000", method="POST", timeout=240)
        rep["planned"] = (planned.get("route") or {}).get("distanceM") is not None or planned.get("error") is None
        time.sleep(4)  # the water, terrain and past-effort lines arrive on their workers
        route = server.json("/route", timeout=60)
        lay = (route.get("ui") or {}).get("layout") or {}
        bar = server.json("/scalebar")
        strip = (server.json("/readout").get("layout")) or {}
        d = bar.get("density") or 1.0
        card_top_px = (lay.get("routeCardTopFromWindowTopDp") or 0) * d
        rep["planning"] = {k: lay.get(k) for k in ("routeCardCapDp", "routeCardBandDp", "mapRowReservedDp", "mapRowKept", "topChipsBottomDp",
                                                   "routeCardTopFromWindowTopDp", "routeCardClearOfTopChips", "routeCardScrollMaxPx", "routeCard")}
        rep["planning"]["scaleBar"] = {k: bar.get(k) for k in ("shown", "hiddenBecause", "boundsPx", "clear")}
        rep["planning"]["strip"] = {k: strip.get(k) for k in ("shown", "hiddenBecause", "boundsPx", "drawnItems", "droppedItems", "placement")}
        c["planClearOfTopChips"] = lay.get("routeCardClearOfTopChips") is True
        c["planScaleBarShown"] = bar.get("shown") is True and bar.get("clear") is not False
        c["planElevationShown"] = strip.get("shown") is True and (strip.get("drawnItems") or 0) >= 1 and strip.get("clearOfBottomPanels") is not False
        bb, sb = bar.get("boundsPx"), strip.get("boundsPx")
        c["planCardClearOfScaleBarLine"] = all(b is None or b[3] <= card_top_px + 1 for b in (bb, sb))
        descs = ui_descriptions(exe, serial)
        rep["planning"]["descriptions"] = [x for x in (descs or []) if "details" in x.lower()]
        c["planScrollDescribed"] = descs is None or any(x.startswith("Route details") for x in descs)
        rep["shots"]["planning"] = screencap(exe, serial, f"{shot_stem}-cards-planning{ext}")
        lay_plan = view_layout("planning")
        # T224: the strip's rectangle against its plate as drawn, within 1 px, wherever the strip shows.
        strip_drawn = strip_drawn_check(rep["shots"]["planning"], (lay_plan.get("rects") or {}).get("M98"), bool(bar.get("night")))
        rep["stripDrawn"] = strip_drawn
        if strip_drawn and strip_drawn.get("maxDiffPx") is not None:
            c["stripRectMatchesDrawn"] = strip_drawn["maxDiffPx"] <= 1
        body = (lay.get("routeCard") or {}).get("bodyPx")
        if (lay.get("routeCardScrollMaxPx") or 0) > 0:
            # Swipes until the scroll is at its end (lines that arrive late, such as the terrain line, lengthen it).
            lay2, swipes = lay, 0
            while swipes < 30:
                swipe_to_end(exe, serial, body, times=2)
                swipes += 2
                time.sleep(0.8)
                lay2 = ((server.json("/route", timeout=60).get("ui") or {}).get("layout")) or {}
                if (lay2.get("routeCardScrollPx") or 0) >= (lay2.get("routeCardScrollMaxPx") or 0) > 0:
                    break
            rep["planning"]["scrolledTo"] = [lay2.get("routeCardScrollPx"), lay2.get("routeCardScrollMaxPx"), swipes]
            c["planEveryLineReached"] = (lay2.get("routeCardScrollPx") or 0) >= (lay2.get("routeCardScrollMaxPx") or 0) > 0
            rep["shots"]["planningScrolled"] = screencap(exe, serial, f"{shot_stem}-cards-planning-scrolled{ext}")

        # T212 (the verifier's note): the warning raised with the route still planned: the two cards share the band (the
        # warning above, the planning card under it), and the scale bar is either drawn clear of both or hidden.
        server.json("/readout/trend?clear=1", method="POST", timeout=60)
        server.json(f"/readout/trend?fixture={step['fixture']}", method="POST", timeout=60)
        time.sleep(3)
        card = ((server.json("/readout").get("trend") or {}).get("card")) or {}
        lay3 = ((server.json("/route", timeout=60).get("ui") or {}).get("layout")) or {}
        bar = server.json("/scalebar")
        wb = card.get("boundsPx")
        route_top_px = (lay3.get("routeCardTopFromWindowTopDp") or 0) * d
        bb = bar.get("boundsPx") if bar.get("shown") else None
        rep["warningWithRoute"] = {"warningPx": wb, "routeCardTopPx": round(route_top_px), "routeCardCapDp": lay3.get("routeCardCapDp"),
                                   "routeCardMode": (lay3.get("routeCard") or {}).get("mode"), "warningOverRoom": (card.get("layout") or {}).get("overRoom"),
                                   "scaleBar": {k: bar.get(k) for k in ("shown", "hiddenBecause", "boundsPx", "clear", "clearOfWarningCard")}}
        c["warnRouteShown"] = card.get("shown") is True and bool(wb)
        c["warnRouteClearOfRouteCard"] = bool(wb) and wb[3] <= route_top_px + 1 and lay3.get("routeCardClearOfWarningCard") is True
        c["warnRouteScaleBarClear"] = bb is None or (overlaps(bb, wb) is False and bb[3] <= route_top_px + 1 and bar.get("clear") is True)
        rc = ((server.json("/readout").get("layout")) or {}).get("recentreBoundsPx")
        rep["warningWithRoute"]["recentrePx"] = rc
        c["warnRouteRecentreClear"] = overlaps(rc, wb) is not True
        rep["shots"]["warningWithRoute"] = screencap(exe, serial, f"{shot_stem}-cards-warning-route{ext}")
        view_layout("warningWithRoute")

        # The warning card on the map alone, the map turned so the compass shows.
        server.json("/route/clear", method="POST")
        server.json("/readout/trend?clear=1", method="POST", timeout=60)
        server.json(f"/readout/trend?fixture={step['fixture']}", method="POST", timeout=60)
        time.sleep(2.5)
        trend = server.json("/readout").get("trend") or {}
        card = trend.get("card") or {}
        bar = server.json("/scalebar")
        strip = (server.json("/readout").get("layout")) or {}
        cam = server.json("/camera")
        compass = ((((cam.get("orientation") or {}).get("heading") or {}).get("compass")) or {}).get("boundsPx")
        rep["warning"] = {"boundsPx": card.get("boundsPx"), "layout": card.get("layout"), "scaleBarPx": bar.get("boundsPx"),
                          "stripPx": strip.get("boundsPx"), "stripHiddenBecause": strip.get("hiddenBecause"), "compassPx": compass}
        c["warningShown"] = card.get("shown") is True and bool(card.get("boundsPx"))
        c["warningClearOfScaleBar"] = bar.get("shown") is True and overlaps(card.get("boundsPx"), bar.get("boundsPx")) is False
        c["warningClearOfStrip"] = overlaps(card.get("boundsPx"), strip.get("boundsPx")) is not True
        c["warningClearOfCompass"] = overlaps(card.get("boundsPx"), compass) is not True
        rep["warning"]["compassDrawn"] = compass is not None
        rep["shots"]["warning"] = screencap(exe, serial, f"{shot_stem}-cards-warning{ext}")
        view_layout("warning")
        server.json("/readout/trend?clear=1", method="POST", timeout=60)

        # Navigation of the same route at the user's font scale.
        server.json(f"/route?from={step['from']}&to={step['to']}&mode=hike&wait=180000", method="POST", timeout=240)
        server.json("/nav/start?route=current", method="POST", timeout=60)
        try:
            server.json("/location/replay?route=current&kmh=4", method="POST", timeout=60)
        except (urllib.error.URLError, OSError, ValueError) as e:
            rep["replayError"] = str(e)[:200]
        time.sleep(8)
        nav = server.json("/nav", timeout=60)
        nl = nav.get("layout") or {}
        bar = server.json("/scalebar")
        rep["navigation"] = {"navCardPx": nl.get("navCardPx"), "navCard": nl.get("navCard"), "onlineClearOfNavCard": nl.get("onlineClearOfNavCard"),
                             "tracksClearOfNavCard": nl.get("tracksClearOfNavCard"), "scaleBar": {k: bar.get(k) for k in ("shown", "hiddenBecause", "boundsPx", "clearOfNavCard")},
                             "turnText": nl.get("navTurnText")}
        nc = nl.get("navCard") or {}
        c["navAtUserScale"] = abs(float(nc.get("fontScale") or 0) - float(step["scale"])) < 0.05
        c["navCappedAtBand"] = (nl.get("navCardPx") is not None and nc.get("bandDp") is not None
                                and (nl["navCardPx"][3] - nl["navCardPx"][1]) / d <= nc["bandDp"] + 1)
        c["navClearOfChips"] = nl.get("onlineClearOfNavCard") is not False and nl.get("tracksClearOfNavCard") is not False
        # T212 (the verifier's note): and under the search box, which wraps to two lines at display size largest.
        sb = (nl.get("searchBottomDp") or 0) * d
        c["navClearOfSearch"] = nl.get("navCardPx") is not None and nl["navCardPx"][1] >= sb - 1 and nc.get("clearOfSearch") is not False
        rep["navigation"]["searchBottomPx"] = round(sb)
        c["navScaleBarClear"] = bar.get("clearOfNavCard") is not False
        c["navTurnWhole"] = (nl.get("navTurnText") or {}).get("cut") is not True
        descs = ui_descriptions(exe, serial)
        c["navScrollDescribed"] = descs is None or any(x.startswith("Navigation details") for x in descs)
        rep["shots"]["navigation"] = screencap(exe, serial, f"{shot_stem}-cards-navigation{ext}")
        view_layout("navigation")
        rep["navigation"]["drawCheck"] = nl.get("drawCheck")  # T146: frames drawn with a chip column under the card
        rep["navigation"]["chipsHidden"] = nl.get("chipsHidden")  # T224
        # T224 (T210's glance step): the glance view at this font scale and rotation, while navigating; closed again.
        glance = server.json("/readout/glance?open=1", method="POST", timeout=60)
        time.sleep(1)
        rep["glance"] = {k: glance.get(k) for k in ("open", "count", "legible", "clipped", "fontScale", "inside")}
        c["glanceLegible"] = glance.get("legible") is True
        rep["shots"]["glance"] = screencap(exe, serial, f"{shot_stem}-cards-glance{ext}")
        server.json("/readout/glance?open=0", method="POST", timeout=60)
    finally:
        for method, path in (("DELETE", "/location/replay"), ("POST", "/nav/stop"), ("POST", "/route/clear"), ("POST", "/readout/trend?clear=1")):
            try:
                server.json(path, method=method, timeout=60)
            except (urllib.error.URLError, OSError, ValueError, RuntimeError):
                pass
        adb(exe, serial, "shell", f"settings put system font_scale {rep['fontScaleBefore'] or '1.0'}", timeout=30)
        adb(exe, serial, "shell", f"settings put system user_rotation {before_rotation.strip() if before_rotation.strip().isdigit() else '0'}", timeout=30)
        adb(exe, serial, "shell", f"settings put system accelerometer_rotation {before_auto.strip() if before_auto.strip().isdigit() else '0'}", timeout=30)
        if density_before:
            adb(exe, serial, "shell", f"wm density {density_before}", timeout=30)
        time.sleep(3)
    failed = [k for k, ok in c.items() if not ok]
    # T224: on its side `overlaps` is the record; T212's checks for the upright phone are reported, the second half's list.
    rep["landscapeNotes"] = [k for k in failed if k in UPRIGHT_ONLY] if landscape else []
    rep["failed"] = [k for k in failed if k not in rep["landscapeNotes"]]
    rep["ok"] = not rep["failed"]
    rep["overlapPairs"] = pair_names([o for lay in rep["layout"].values() for o in lay.get("overlaps") or []])
    return rep


# T224: T212's checks written for the upright phone: bounds it read before `GET /layout` existed.
UPRIGHT_ONLY = {"planClearOfTopChips", "planScaleBarShown", "planElevationShown", "planCardClearOfScaleBarLine", "planEveryLineReached",
                "warnRouteShown", "warnRouteClearOfRouteCard", "warnRouteScaleBarClear", "warnRouteRecentreClear", "warningClearOfScaleBar",
                "warningClearOfStrip", "warningClearOfCompass", "navCappedAtBand", "navClearOfSearch", "navScaleBarClear"}


def cards_sheet(steps, path):
    """T224: one contact sheet of every cards step's screenshots, labelled scale, rotation and view."""
    reports, rasters = [], []
    for st in steps:
        for name, shot in (st.get("shots") or {}).items():
            if not shot:
                continue
            with open(shot, "rb") as f:
                rasters.append(decode_png(f.read()))
            reports.append({"index": f"{st.get('scale')}r{st.get('rotation')}", "label": name, "ok": st.get("ok"), "failed": (st.get("failed") or [])[:1]})
    return contact_sheet(reports, rasters, path, cols=6)


def set_app_server(server, value, timeout=60.0):
    """T105: the app's server (POST /settings?server=, persisted; '' for none), then the style the setting reloads.
    Returns the server the app reports."""
    r = server.json("/settings?server=" + urllib.parse.quote(value, safe=""), method="POST", timeout=30)
    time.sleep(2)
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if server.json("/health", timeout=10).get("map", {}).get("styleLoaded"):
                break
        except (urllib.error.URLError, OSError, ValueError, RuntimeError):
            pass
        time.sleep(1)
    return r.get("server")


def online_state(server):
    """T98: the app's online target and its state (GET /online): a configured server that does not answer changes what
    the tour measures (a debug build on an emulator defaults to the host's 8765)."""
    try:
        o = server.json("/online")
    except (urllib.error.URLError, OSError, RuntimeError, ValueError) as e:
        return {"error": str(e)}
    return {"server": o.get("server") or (o.get("fetcher") or {}).get("server"), "state": o.get("state")}  # direct://sources with no server


def no_dem_over_pack(before, after):
    """T98: contour tiles made between two /stats snapshots whose DEM was all NaN although a DEM pack overlaps them."""
    b = (before["contourGeneration"].get("noDemOverPack") or {}).get("count", 0)
    a = after["contourGeneration"].get("noDemOverPack") or {}
    return a.get("count", 0) - b, a.get("recent", [])


def new_contour_timings(before, after):
    """Generation times (ms) of the contour tiles made between two /stats snapshots."""
    b, a = before["contourGeneration"], after["contourGeneration"]
    n = a["count"] - b["count"]
    if n <= 0:
        return [], 0
    recent = [t["ms"] for t in a["recent"]][-n:]
    if n > len(recent) and (a["maxMs"] or 0) > (b["maxMs"] or 0):
        recent.append(a["maxMs"])  # the overall max moved during this view, so one of the unseen tiles took that long
    return recent, n


def style_info(server):
    """Fonts used by symbol layers, the text layers, and the contour line layers of the app's style."""
    style = json.loads(server.get("/style.json").decode("utf-8"))
    fonts, text, contour_lines = set(), [], []
    for layer in style.get("layers", []):
        layout = layer.get("layout", {})
        f = layout.get("text-font")
        if isinstance(f, list) and f and all(isinstance(x, str) for x in f):
            fonts.add(",".join(f))
        if layer.get("type") == "symbol" and "text-field" in layout:
            text.append(layer["id"])
        if layer.get("type") == "line" and layer.get("source") == "contours":
            contour_lines.append(layer["id"])
    # T154: each tile source's type, tile size and zoom range (covering_zooms)
    sources = {k: {kk: s[kk] for kk in ("type", "tileSize", "minzoom", "maxzoom") if kk in s}
               for k, s in (style.get("sources") or {}).items() if isinstance(s, dict)}
    return {"fonts": sorted(fonts), "textLayers": text, "contourLineLayers": contour_lines, "sources": sources}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=8686, help="host port forwarded to the app's debug server")
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", ""), help="device that must own --port (default: ANDROID_SERIAL)")
    ap.add_argument("--views", default="")
    ap.add_argument("--views-file", default="")
    ap.add_argument("--out", default="out/android-tour.png", help="screenshots <stem>-<i>.png, <stem>-sheet.png, <stem>.json")
    ap.add_argument("--wait-ms", type=int, default=30000, help="how long /screenshot waits for the map to go idle")
    ap.add_argument("--first-wait-ms", type=int, default=120000,
                    help="T110: the same for the first view, the cold one (measured 10 to 24 s on fresh AVDs, 43 s on a busy one). "
                         "T127: 120 s, from 60: with the direct renders admitted by the heap (three at once on 192 MB, ten before) "
                         "the cold Rainier z8 view went idle in 73 to 117 s where it took 50 to 61 s before, on a day the sources were slow")
    ap.add_argument("--contour-budget-ms", type=float, default=1000.0, help="contour tiles slower than this are a warning (a failure with --strict)")
    ap.add_argument("--max-settle-change", type=float, default=0.02,
                    help="share of pixels that may change between the screenshot and a second one after the map settled")
    ap.add_argument("--strict",action="store_true", help="contour budget misses fail the view instead of warning")
    ap.add_argument("--min-stddev", type=float, default=4.0, help="luma standard deviation below which a screenshot counts as blank")
    ap.add_argument("--min-text-pixels", type=int, default=200, help="pixels that must change when text is hidden, where labels are placed")
    ap.add_argument("--no-restart", action="store_true", help="don't relaunch the app first (counters and caches carry over)")
    ap.add_argument("--keep-cache", action="store_true", help="don't clear the contour disk cache first")
    ap.add_argument("--server", default=None,
                    help="T105: set the app's server first (POST /settings?server=): a URL the emulator reaches, or 'none' for no "
                         "server (packs and the public sources). Default: leave it (a debug build on the emulator defaults to the host's 8765)")
    ap.add_argument("--quiet-cores", type=float, default=1.0,
                    help="T105: before the first view, wait until the device's CPU use besides the app is at most this many cores "
                         "in two 3 s windows in a row (a just-booted emulator spends minutes near all its cores on first-boot work)")
    ap.add_argument("--quiet-timeout", type=float, default=240.0, help="T105: the longest wait for a quiet device (s); 0 skips the wait")
    ap.add_argument("--no-background", action="store_true",
                    help="T123: skip the background step (HOME, the activity-bound routes must answer 503, back, then 200)")
    ap.add_argument("--background-limit-s", type=float, default=5.0,
                    help="T123: how fast a backgrounded activity-bound route must answer its 503")
    ap.add_argument("--direct-view", default=DIRECT_VIEW,
                    help="T127: after the tour and the background step, cold direct views (no pack, no server: z/lat/lon:label, "
                         "comma-separated; 'none' skips): online and source caches cleared, the Java heap sampled, the peak must "
                         "stay under --direct-heap-share of the heap's max with no crash and no OutOfMemoryError")
    ap.add_argument("--direct-heap-share", type=float, default=0.8,
                    help="T127: the bound on the direct view's live Java heap, a share of the max heap, read after the view by "
                         "`am send-trim-memory <package> RUNNING_LOW`: the app's onTrimMemory collects, waits 200 ms and reads the "
                         "used heap before dropping any cache (GET /online direct.memory recentTrims liveBeforeTrimBytes). The "
                         "sampled peaks count garbage not yet collected and are reported only. 0.8: the reading includes the up to "
                         "three renders still running (the view keeps rendering for minutes), so it was 0.18 to 0.63 of 192 MB over twelve "
                         "cold views (T127), while the admission's own ceiling is the max less the reserve, 0.875; above 0.8 the renders "
                         "take more than budgeted or something new is retained")
    ap.add_argument("--direct-only", action="store_true",
                    help="T127: only the direct views, each in a new process (the per-zoom peak table); no tour views, no background step")
    ap.add_argument("--direct-keep-sources", action="store_true", help="T127: keep the phone's source range cache (cache/sources) before a direct view")
    ap.add_argument("--no-cards", action="store_true",
                    help="T212: skip the tour file's @cards step (the planning, warning and navigation cards at font scale 2.0)")
    ap.add_argument("--cards-only", action="store_true",
                    help="T212: only the @cards step of --views-file (no views, no background or direct step); with no "
                         "--views-file it reads tools/android-tour.txt")
    ap.add_argument("--layout-baseline", default="",
                    help="T241: an earlier run's <stem>.json; GET /layout's rectangles at each view and card step are compared "
                         "with it (layoutBaseline in the summary; reported, never a failure)")
    ap.add_argument("--layout-tolerance-px", type=float, default=1.0, help="T241: what --layout-baseline lets a side move, px")
    args = ap.parse_args()

    views = [] if (args.direct_only or args.cards_only) else read_views(args)
    card_steps = [] if args.no_cards or args.direct_only else read_steps(args)  # T212
    if args.cards_only:
        if not args.views_file:
            args.views_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), "android-tour.txt")
            card_steps = read_steps(args)
        args.no_background, args.direct_view = True, "none"
    direct_views = [] if args.direct_view.strip().lower() == "none" else [parse_view(x) for x in args.direct_view.split(",") if x.strip()]
    if args.direct_only and not direct_views:
        ap.error("--direct-only needs --direct-view")
    if not views and not args.direct_only and not (args.cards_only and card_steps):
        ap.error("no views: pass --views or --views-file")
    stem, ext = os.path.splitext(args.out)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)) or ".", exist_ok=True)

    # Which device answers on --port? Refuse to drive anything but --serial.
    exe = adb_path()
    owner = forward_owner(exe, args.port) if exe else None
    if args.serial:
        if not exe:
            print(json.dumps({"error": "--serial given but adb was not found (set ANDROID_HOME)"}))
            return 2
        if owner and owner != args.serial:
            print(json.dumps({"error": f"host port {args.port} is forwarded to {owner}, not {args.serial}; pick another --port "
                                       f"(tools/android-emu.ps1 -Port) or remove that forward"}))
            return 2
    serial = args.serial or owner
    server = Server(args.port, exe if serial else None, serial)  # T57: logcat bootstrap through this serial
    if serial and serial.startswith("emulator-") and exe:
        _, name = adb(exe, serial, "emu", "avd", "name", timeout=30)
        avd = next((l.strip() for l in name.splitlines() if l.strip() and l.strip() != "OK"), None)
    else:
        avd = None

    summary = {"port": args.port, "serial": serial, "avd": avd, "pngDecoder": "pillow" if HAVE_PIL else "stdlib",
               "contourBudgetMs": args.contour_budget_ms, "setup": {}}
    want = None if args.server is None else ("" if args.server.lower() in ("none", "direct", "") else args.server)
    try:
        if want is not None:
            # T105: before the restart, so the relaunched process starts on it (the setting is persisted). An app that
            # does not answer yet gets it after the restart below.
            try:
                summary["setup"]["serverSet"] = {"before": set_app_server(server, want)}
            except (urllib.error.URLError, OSError, ValueError, RuntimeError) as e:
                summary["setup"]["serverSet"] = {"beforeError": str(e)[:200]}
        if not args.no_restart:
            if exe and serial:
                t0 = time.time()
                restart_app(exe, serial, server, 120)
                summary["setup"]["restart"] = {"seconds": round(time.time() - t0, 1)}
            else:
                server.json("/reload", method="POST", timeout=60)
                summary["setup"]["restart"] = "no adb device for the port; used POST /reload"
        now = online_state(server).get("server") or ""
        if want is not None and (now if not now.startswith("direct://") else "") != want:  # no server reads direct://sources
            summary["setup"]["serverSet"]["after"] = set_app_server(server, want)
        if not args.keep_cache:
            summary["setup"]["contourCacheCleared"] = server.json("/cache/clear", method="POST")["deleted"]
        if exe and serial and args.quiet_timeout > 0:
            # T105 (T88's follow-up): a just-booted emulator is still busy with first-boot work; wait for it by its load.
            q = android_debug.wait_quiet(exe, serial, android_debug.app_pid(exe, serial), max_busy=args.quiet_cores,
                                         timeout=args.quiet_timeout)
            summary["setup"]["quiet"] = q
            print(f"quiet device after {q['waitedS']} s (cores busy besides the app, per 3 s: "
                  f"{' '.join(str(w[0]) for w in q['windows'][-12:])})" + ("" if q["quiet"] else " - gave up"), file=sys.stderr)
        since = None
        if exe and serial:
            _, since = adb(exe, serial, "shell", "date '+%m-%d %H:%M:%S.000'", timeout=30)
            since = since.strip()
        health = server.json("/health")
        summary["setup"]["token"] = {"sent": True, "noTokenStatsStatus": server.status_without_token("/stats"),  # T49: expect 401
                                     "devicePort": server.device_port, "from": "logcat" if server.serial else "/health"}  # T57
        summary["setup"]["online"] = online_state(server)  # T98: the server the tour runs against
        summary["app"] = health.get("app")
        summary["packs"] = {k: (v if not isinstance(v, dict) else {kk: v[kk] for kk in ("file", "minZoom", "maxZoom", "bounds", "bytes")})
                            for k, v in health.get("packs", {}).items()}
        info = style_info(server)
        fonts = info["fonts"]
    except (urllib.error.URLError, OSError, RuntimeError, ValueError) as e:
        summary["error"] = f"cannot reach the app on port {args.port}: {e}"
        summary["connection"] = {**server.counters, "events": server.events}  # T185
        print(json.dumps(summary, indent=2))
        return 2

    reports, rasters = [], []
    for i, v in enumerate(views):
        rep = {"index": i, "label": v["label"], "view": v["view"], "checks": {}, "failed": []}
        wait_ms = args.first_wait_ms if i == 0 else args.wait_ms  # T110: the cold first view
        cpu0 = device_cpu(exe, serial)
        t0 = time.time()
        try:
            before = server.json("/stats")
            log_total0 = request_log_total(server)  # T170: where this view's lines of the request log start
            cam = server.json(f"/camera?lat={v['lat']}&lon={v['lon']}&zoom={v['z']}&bearing=0&tilt=0", method="POST")
            png = server.get(f"/screenshot?wait={wait_ms}", timeout=wait_ms / 1000 + 60)
            rep["idleSeconds"] = round(time.time() - t0, 1)  # T110
            rep["deviceCpu"] = cpu_between(cpu0, device_cpu(exe, serial))
            health = server.json("/health")
            try:
                feats = server.json("/features", timeout=60)
                text = server.json(f"/features/text?wait={args.wait_ms}", timeout=args.wait_ms * 3 / 1000 + 60)
                # A low count can be a settling map (the first view after a restart shows up as unstable
                # pixels) rather than missing text: measure up to twice more. Text that is really not drawn
                # (the swiftshader case) stays at 0 every time.
                tries = [text.get("changedPixels")]
                for _ in range(2):
                    if (text.get("changedPixels") or 0) >= args.min_text_pixels:
                        break
                    text = server.json(f"/features/text?wait={args.wait_ms}", timeout=args.wait_ms * 3 / 1000 + 60)
                    tries.append(text.get("changedPixels"))
                text["tries"] = tries
            except urllib.error.HTTPError as e:
                feats = {"ready": False, "error": f"HTTP {e.code} (app without the /features routes)"}
                text = {}
            # A second screenshot once everything above has settled: if it differs from the first, /screenshot
            # returned a half-loaded map while reporting idle. Stats are read last so late tiles count.
            png2 = server.get(f"/screenshot?wait={args.wait_ms}", timeout=args.wait_ms / 1000 + 60)
            after = server.json("/stats")
        except (urllib.error.URLError, OSError, ValueError) as e:
            rep.update({"ok": False, "failed": ["unreachable"], "error": str(e), "seconds": round(time.time() - t0, 1)})
            reports.append(rep)
            rasters.append(None)
            continue
        rep["seconds"] = round(time.time() - t0, 1)
        rep["camera"] = {k: (round(cam[k], 5) if isinstance(cam.get(k), float) else cam.get(k)) for k in ("lat", "lon", "zoom")}
        m = health.get("map", {})

        tiles = {}
        for s in SOURCES + ("glyphs",):
            a, b = after["tiles"][s], before["tiles"][s]
            tiles[s] = {k: a[k] - b[k] for k in ("served", "notFound", "errors")}
        rep["tiles"] = tiles
        times, generated = new_contour_timings(before, after)
        rep["contours"] = {"generated": generated, "maxMs": max(times) if times else None,
                           "avgMs": round(sum(times) / len(times), 1) if times else None,
                           "diskCacheHits": after["tiles"]["contours"].get("diskCacheHits", 0) - before["tiles"]["contours"].get("diskCacheHits", 0)}
        shot = f"{stem}-{i}{ext}"
        with open(shot, "wb") as f:
            f.write(png)
        rep["screenshot"] = shot
        try:
            ras = decode_png(png)
            rep["image"] = image_stats(ras)
        except (ValueError, zlib.error, struct.error) as e:
            ras, rep["image"] = None, {"error": str(e)}
        rasters.append(ras)
        settle_share = None
        try:
            ras2 = decode_png(png2)
            if ras is not None:
                settle_share = changed_share(ras, ras2)
        except (ValueError, zlib.error, struct.error):
            pass
        if settle_share is not None and settle_share > args.max_settle_change:
            shot2 = f"{stem}-{i}-settled{ext}"
            with open(shot2, "wb") as f:
                f.write(png2)
            rep["settledScreenshot"] = shot2
        rep["settleChange"] = settle_share

        try:  # T154: the view's box, for the expectation per source from the viewport's own tiles
            view_box = server.json("/attribution", timeout=30).get("viewBounds")
        except (urllib.error.URLError, OSError, ValueError):
            view_box = None
        expectation = source_expectations(health, v, view_box, info.get("sources"))
        exp = [s for s in SOURCES if expectation[s]["expected"]]
        rep["viewBox"] = view_box
        rep["expectation"] = expectation
        rep["expectedSources"] = exp
        c = rep["checks"]
        c["style"] = bool(m.get("styleLoaded")) and not m.get("styleError")
        c["idle"] = m.get("lastScreenshotIdle") is True
        if not c["idle"]:  # T115: what held it (pendingTiles: a request still open at the loopback server, an OpenFreeMap fetch for one)
            rep["notIdleBecause"] = m.get("notIdleBecause")
        c["errors"] = all(tiles[s]["errors"] == 0 for s in tiles)
        rep["warnings"] = []
        over = [t for t in times if t > args.contour_budget_ms]
        if args.strict:
            c["contours"] = not over
        elif over:
            # Contour timings swing with host load (several emulators on one machine): a warning unless --strict.
            rep["warnings"].append(f"{len(over)} contour tile(s) over {args.contour_budget_ms:g} ms (max {max(over):g})")
        empty, empty_tiles = no_dem_over_pack(before, after)
        if empty > 0:  # T98 diagnostic: an empty contour tile where a DEM pack has elevations
            rep["contours"]["noDemOverPack"] = empty
            rep["warnings"].append(f"{empty} contour tile(s) with no elevation over a DEM pack ({', '.join(empty_tiles[-empty:])})")
        log_lines, view_lines = None, None
        pack_only = log_total0 is not None and any(s in exp and tiles[s]["served"] > 0 for s in PACK_ONLY_SOURCES)
        if any(tiles[s]["served"] == 0 for s in exp) or pack_only:
            # T154: a tile MapLibre already held counts when a pack answered it before the baseline (the request log keeps
            # the last 4,096 requests; `requestLogDropped` says whether the start of the process is still in it).
            # T170: and this view's own lines (from log_total0 on) tell a pack's contour tiles from those made over online DEM.
            try:
                rl = server.json(f"/stats/requests?limit={REQUEST_LOG_CAPACITY}", timeout=60)
                log_lines = rl.get("lines") or []
                rep["requestLogDropped"] = rl.get("dropped")
                if pack_only:
                    n = rl.get("total", 0) - log_total0
                    if 0 <= n <= len(log_lines):
                        view_lines = log_lines[len(log_lines) - n:]
                    else:
                        rep["viewLogIncomplete"] = n  # more lines than the log keeps: the delta counts as before
            except (urllib.error.URLError, OSError, ValueError, RuntimeError) as e:
                log_lines, rep["requestLogError"] = [], str(e)[:200]
        c["tiles"], missing = tiles_check(tiles, exp, log_lines, expectation, view_lines)
        if not c["tiles"]:
            rep["sourcesWithoutTiles"] = missing
        c["blank"] = "lumaStddev" in rep["image"] and rep["image"]["lumaStddev"] >= args.min_stddev
        if settle_share is not None:
            c["settled"] = settle_share <= args.max_settle_change
            if not c["settled"]:
                rep["settleProblem"] = (f"{settle_share:.0%} of the screenshot changed after the map settled: /screenshot "
                                        f"returned a half-loaded map although health said idle={m.get('lastScreenshotIdle')}")
        if feats.get("ready"):
            L = feats["layers"]
            rep["layers"] = L
            rep["rendered"] = {"contourLines": sum(L.get(l, 0) for l in info["contourLineLayers"]),
                               "contourLabels": L.get("contour-labels", 0),
                               "text": sum(L.get(l, 0) for l in info["textLayers"]),
                               "textLayersWithLabels": [l for l in info["textLayers"] if L.get(l, 0) > 0],
                               "textPixels": text.get("changedPixels") if text.get("ready") else None,
                               "unstablePixels": text.get("unstablePixels"), "textTries": text.get("tries")}
            if "contours" in exp:
                c["lines"] = rep["rendered"]["contourLines"] > 0
            if "osm" in exp or "contours" in exp:
                tp = rep["rendered"]["textPixels"]
                c["labels"] = rep["rendered"]["text"] > 0 and tp is not None and tp >= args.min_text_pixels
                if not c["labels"]:
                    rep["labelsProblem"] = ("no text layer placed a label" if rep["rendered"]["text"] == 0 else
                                            f"text snapshot diff failed: {text.get('error')}" if tp is None else
                                            f"{rep['rendered']['text']} labels placed but hiding text changed {tp} pixels: labels not drawn")
        else:
            rep["rendered"] = None
            rep["featuresUnavailable"] = feats.get("error", "map not ready")
        rep["layout"] = layout_read(server)  # T224: the pairs that overlap at this view, reported
        rep["overlapPairs"] = pair_names(rep["layout"].get("overlaps"))
        c["layoutAnswers"] = "error" not in rep["layout"]
        c["layoutNoForbiddenPair"] = not layout_forbidden(rep["layout"].get("overlaps"))
        c["layoutSemanticsWhereDrawn"] = not rep["layout"].get("semanticsAway")  # T243: each element's semantics node where it is drawn
        rep["failed"] = [k for k, ok in c.items() if not ok]
        rep["ok"] = not rep["failed"]
        reports.append(rep)

    # Tour-wide: did MapLibre ask for glyphs for every font the style uses?
    try:
        final = server.json("/stats")
        g = final["tiles"]["glyphs"]
        # Since T25 the style may load glyphs from a file:// URL, so /glyphs is never requested; text that
        # reached the pixels on any view proves the glyphs loaded just as well.
        drawn = sum(1 for r in reports if ((r.get("rendered") or {}).get("textPixels") or 0) >= args.min_text_pixels)
        http_ok = g["served"] >= len(fonts) and len(fonts) > 0
        summary["glyphs"] = {"fontsInStyle": fonts, "glyphRangesServed": g["served"], "glyphRangesEmpty": g["notFound"],
                             "viewsWithDrawnText": drawn, "glyphDir": (health.get("packs") or {}).get("glyphs"),
                             "ok": http_ok or drawn > 0 or args.direct_only or args.cards_only,  # T127, T212: no tour views, nothing to check
                             "evidence": "http" if http_ok else "drawn text" if drawn else "none"}
        cg = final["contourGeneration"]
        summary["contourGeneration"] = {k: cg[k] for k in ("count", "minMs", "avgMs", "maxMs")}
        summary["contourGeneration"]["noDemOverPack"] = (cg.get("noDemOverPack") or {}).get("count")  # T98
        summary["online"] = online_state(server)  # T98: the state at the end of the tour
        summary["memory"] = final.get("memory")
    except (urllib.error.URLError, OSError, ValueError) as e:
        summary["glyphs"] = {"ok": False, "error": str(e)}
    if card_steps and exe and serial:
        # T212: the cards at a large font scale, after the views (it plans, warns and navigates, then clears all three).
        steps = []
        for k, st in enumerate(card_steps):
            try:
                steps.append(cards_step(server, exe, serial, st, stem + (f"-{k}" if k else ""), ext))
            except (urllib.error.URLError, OSError, ValueError, RuntimeError, subprocess.SubprocessError, http.client.HTTPException) as e:
                steps.append({"ok": False, "error": str(e)[:200], "route": f"{st['from']};{st['to']}"})
        summary["cardsStep"] = {"ok": all(r["ok"] for r in steps), "steps": steps}
        try:
            summary["cardsStep"]["sheet"] = cards_sheet(steps, f"{stem}-cards-sheet{ext}")  # T224
        except (OSError, ValueError) as e:
            summary["cardsStep"]["sheetError"] = str(e)[:200]
    else:
        summary["cardsStep"] = {"skipped": "--no-cards" if args.no_cards else "no @cards line" if not card_steps else "no adb device"}
    if exe and serial and not args.no_background and not args.direct_only:
        # T123: the tour never left the foreground; activity-bound routes hung there until their own timeouts.
        try:
            summary["backgroundStep"] = background_step(server, exe, serial, args.background_limit_s)
        except (urllib.error.URLError, OSError, ValueError, subprocess.SubprocessError) as e:
            summary["backgroundStep"] = {"ok": False, "error": str(e)[:200]}
    else:
        summary["backgroundStep"] = {"skipped": "--no-background" if args.no_background else "--direct-only" if args.direct_only else "no adb device"}
    if direct_views:
        # T127 (finding 2026-10-21-direct-view-out-of-memory-after-a-tour): after the full tour, a cold direct view must fit the heap.
        steps = []
        for k, dv in enumerate(direct_views):
            try:
                steps.append(direct_step(server, exe, serial, dv, args.direct_heap_share, args.first_wait_ms,
                                         restart=args.direct_only, clear_sources=not args.direct_keep_sources,
                                         shot=f"{stem}-direct-{k}{ext}"))
            except (urllib.error.URLError, OSError, ValueError, RuntimeError, subprocess.SubprocessError) as e:
                steps.append({"label": dv["label"], "view": dv["view"], "ok": False, "error": str(e)[:200]})
        summary["directStep"] = {"ok": all(r["ok"] for r in steps), "views": steps}
    else:
        summary["directStep"] = {"skipped": "--direct-view none"}
    try:
        summary["requests"] = {k: v for k, v in server.json("/stats/requests?limit=12").items()}  # T123: the phone's request log
    except (urllib.error.URLError, OSError, ValueError, RuntimeError) as e:  # T127: RuntimeError, the app crashed (LaunchCrash)
        summary["requests"] = {"error": str(e)[:200]}
    if exe and serial and since:
        summary["logcat"] = logcat_since(exe, serial, since)

    summary["views"] = reports
    summary["viewsOk"] = sum(1 for r in reports if r["ok"])
    summary["viewsTotal"] = len(reports)
    summary["sheet"] = contact_sheet(reports, rasters, f"{stem}-sheet{ext}")
    summary["connection"] = {**server.counters, "events": server.events}  # T185: forwards restored, cut reads asked again
    summary["ok"] = (summary["viewsOk"] == len(reports) and summary["glyphs"]["ok"] and summary["backgroundStep"].get("ok", True) is not False
                     and summary["directStep"].get("ok", True) is not False and summary["cardsStep"].get("ok", True) is not False)
    if args.layout_baseline:  # T241: reported, not part of `ok`
        try:
            with open(args.layout_baseline, encoding="utf-8") as f:
                summary["layoutBaseline"] = {"file": args.layout_baseline, **layout_compare(json.load(f), summary, args.layout_tolerance_px)}
        except (OSError, ValueError) as e:
            summary["layoutBaseline"] = {"file": args.layout_baseline, "error": str(e)[:200]}
    with open(stem + ".json", "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    print(f"{'#':>2} {'view':<22} {'label':<18} {'ok':<4} {'dem':>4} {'osm':>4} {'cont':>4} {'gen':>4} {'maxMs':>7} {'line':>5} {'clbl':>4} {'text':>4} {'txpx':>6} {'idle':<5} {'luma':>5} {'s':>5}  failed", file=sys.stderr)
    def served(t):  # T154: "0+1" is a tile the pack answered before the view's baseline
        return t.get("served", "-") if not t.get("servedBeforeBaseline") else f"{t['served']}+{t['servedBeforeBaseline']}"

    for r in reports:
        t = r.get("tiles", {})
        cmax = r.get("contours", {}).get("maxMs")
        rd = r.get("rendered") or {}
        print(f"{r['index']:>2} {r['view']:<22} {r['label'][:18]:<18} {'ok' if r['ok'] else 'FAIL':<4} "
              f"{served(t.get('dem', {})):>4} {served(t.get('osm', {})):>4} {served(t.get('contours', {})):>4} "
              f"{r.get('contours', {}).get('generated', '-'):>4} {cmax if cmax is not None else '-':>7} "
              f"{rd.get('contourLines', '-'):>5} {rd.get('contourLabels', '-'):>4} {rd.get('text', '-'):>4} {str(rd.get('textPixels', '-')):>6} "
              f"{str(r['checks'].get('idle', '-')):<5} {r.get('image', {}).get('lumaStddev', '-'):>5} {r['seconds']:>5}  {' '.join(r['failed'])}"
              + (f"  warn: {'; '.join(r['warnings'])}" if r.get("warnings") else ""), file=sys.stderr)
    lab = summary["glyphs"]
    print(f"glyphs: {'ok' if lab['ok'] else 'FAIL'} (evidence: {lab.get('evidence')}; {lab.get('glyphRangesServed')} ranges served over HTTP, "
          f"{lab.get('viewsWithDrawnText')} views with drawn text; fonts in style: {', '.join(lab.get('fontsInStyle', []))})", file=sys.stderr)
    on = summary.get("online") or summary["setup"].get("online") or {}
    print(f"online: server {on.get('server') or '(none)'}, {on.get('state') or on.get('error')}", file=sys.stderr)  # T98
    bg = summary["backgroundStep"]
    if "skipped" in bg or "error" in bg:
        print(f"background: {bg.get('skipped') or 'FAIL ' + bg.get('error', '')}", file=sys.stderr)
    else:
        print(f"background: {'ok' if bg['ok'] else 'FAIL'} (after HOME {bg.get('activityAfterHome')}: "
              + ", ".join(f"{r['path'].split('?')[0]} {r['status']} {r['seconds']} s" for r in bg["background"])
              + f"; back {bg.get('activityAfterReturn')}: " + ", ".join(f"{r['path'].split('?')[0]} {r['status']}" for r in bg["foreground"]) + ")",
              file=sys.stderr)
    for r in summary["cardsStep"].get("steps", []):  # T212
        print(f"cards {r.get('route')} at font scale {r.get('scale')}: {'ok' if r['ok'] else 'FAIL'}"
              + (f" rotation {r.get('rotation')}" if r.get("rotation") else "")
              + (f" failed {' '.join(r.get('failed') or [])}" if r.get("failed") else "") + (f" error {r['error']}" if r.get("error") else "")
              + (f"; overlaps {', '.join(r.get('overlapPairs'))}" if r.get("overlapPairs") else "; overlaps none")  # T224
              + (f"; upright-only {' '.join(r.get('landscapeNotes'))}" if r.get("landscapeNotes") else ""), file=sys.stderr)
    for r in summary["directStep"].get("views", []):
        mb = lambda b: f"{b / 1048576:.0f}" if b else "-"
        sw = r.get("serverSwitch")
        sw = (f"; server {sw.get('from')} switched {sw.get('switched')}, restored {sw.get('restored')}" if sw else "")
        if r.get("skipped"):
            print(f"direct {r['view']} {r['label']}: {'skipped' if r['ok'] else 'FAIL'} ({r['skipped']}){sw}", file=sys.stderr)
            continue
        live = r.get("live") or {}
        live_err = live.get("error")  # T135 (T127's follow-up): why the live heap was not read
        print(f"direct {r['view']} {r['label']}: {'ok' if r['ok'] else 'FAIL'} live heap {mb(r.get('liveBytes'))} of "
              f"{mb((r.get('heap') or {}).get('maxBytes'))} MB (bound {mb(r.get('boundBytes'))}), sampled peak {mb(r.get('peakBytes'))} MB, "
              f"{trim_words(live)}, idle {r.get('idle')} at {r.get('idleSeconds')} s, "
              f"same process {r.get('sameProcess')}, OOM lines {len(r.get('oom') or [])}" + (f", error {r['error']}" if r.get("error") else "")
              + (f", live error {live_err}" if live_err else "") + sw,
              file=sys.stderr)
    lb = summary.get("layoutBaseline")
    if lb:  # T241
        print(f"layout against {lb['file']}: " + (f"error {lb['error']}" if "error" in lb else
              f"{lb['within']}/{lb['compared']} rectangles within {lb['tolerancePx']:g} px at {lb['places']} places; "
              f"{len(lb['differs'])} differ, {len(lb['onlyBase'])} only before, {len(lb['onlyNow'])} only now"
              + "".join(f"; {d['place']} {d['id']} {d['maxDiffPx']:g} px" for d in lb["differs"][:8])), file=sys.stderr)
    print(f"{summary['viewsOk']}/{summary['viewsTotal']} views ok; sheet {summary['sheet']}; summary {stem}.json", file=sys.stderr)
    print(json.dumps(summary, indent=2))
    return 0 if summary["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
