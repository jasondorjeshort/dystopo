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
              when the view centre and zoom are inside its pack (from /health), or when the view lists
              it with |req=dem+osm+contours
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
              same process, with no crash and no OutOfMemoryError in logcat, and peak at most --direct-heap-share (0.95)
              of the heap's max; idleSeconds, dumpsys meminfo and the screenshot (<stem>-direct-<i>.png) are reported (`directStep`). --direct-only runs only the
              direct views, each in a new process (the per-zoom peak table of finding 2026-10-21-direct-view-out-of-memory-after-a-tour).
The lines and labels checks need the app's /features routes; an app without them skips them (reported).
The summary's `requests` is the app's request log at the end (GET /stats/requests?limit=12: per-route counts and the
last lines).
Views: one `z/lat/lon[:label][|req=a+b]` per line in --views-file (`#` comments), or --views a,b,c.
Every view must need tiles that no earlier view loaded, or the tile check has nothing to count.

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

Device selection: --port is the host port forwarded to the app (tools/android-emu.ps1 -Port). With
--serial (or ANDROID_SERIAL) the script refuses a port that `adb forward --list` maps to another serial
(it forwards the port itself when nothing holds it), so a tour can never drive another emulator; without
either it uses the serial that owns the port. adb is also used for the restart and to collect the app's logcat
warnings during the tour (reported, not checked).

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
                if line:
                    items.append(line)
    return [parse_view(s) for s in items]


# ---------------------------------------------------------------------------------------------------
# HTTP and adb

class Server:
    """The app's debug server. Every request carries the app's per-launch loopback token (T49). T57: with adb and
    a serial the port and token come from the app's logcat line and --port is forwarded to that port; read again
    after a 401 or a refused connection, since a relaunch makes new ones. Without adb: GET /health without a
    token (the app's debugFixedPort=1 mode)."""

    def __init__(self, port, exe=None, serial=None):
        self.port = port
        self.base = f"http://127.0.0.1:{port}"
        self.exe = exe
        self.serial = serial
        self.token = None
        self.device_port = None

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
        for attempt in (0, 1):
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
                self.token = None  # the app was relaunched: fetch its new token and retry once
            except (urllib.error.URLError, ConnectionError, http.client.RemoteDisconnected):
                if attempt or not self.serial:
                    raise
                self.token = None  # T57: relaunched on a new port: read logcat again and re-forward

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


def expected_sources(health, v):
    packs = health.get("packs", {})
    dem, osm = packs.get("dem"), packs.get("osm")
    # MapLibre requests DEM and OSM tiles up to the pack's max zoom and overzooms past it, so deeper views
    # still load new (parent) tiles; contours are generated from the DEM one zoom past it and overzoomed too.
    exp = {"dem": covers(dem, v["z"], v["lat"], v["lon"], 4), "osm": covers(osm, v["z"], v["lat"], v["lon"], 4),
           "contours": covers(dem, v["z"] + 1, v["lat"], v["lon"], 4)}
    if v["req"] is not None:
        exp = {s: s in v["req"] for s in SOURCES}
    return [s for s in SOURCES if exp[s]]


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


def direct_step(server, exe, serial, view, bound_share, wait_ms, restart=False, clear_sources=True, shot=None):
    """T127: one cold direct view (no pack covers it, no server): the online cache cleared (pinned tiles kept) and,
    with `clear_sources`, the phone's source range cache too, so every DEM tile is rendered from the public sources;
    then the camera, a screenshot once idle (or after `wait_ms`), and the Java heap sampled from /stats all along. ok:
    the same process before and after, no crash and no OutOfMemoryError in logcat, DEM tiles rendered, and the heap peak
    at most `bound_share` of the heap's max; `idle` and `idleSeconds` are reported. With `restart`, a new process first
    (the per-zoom table's fresh case)."""
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
    mx = rep["heap"]["maxBytes"]
    rep["boundBytes"] = int(mx * bound_share) if mx else None
    rep["peakShare"] = round(peak / mx, 3) if peak and mx else None
    rendered = (rep.get("app") or {}).get("demRendered")
    # Idle is reported, not required: a cold z8 to z11 view from the public sources takes about a minute on the emulator
    # whatever the heap does (T127's table); the step is about the heap, so it needs renders, no crash and the bound.
    rep["ok"] = bool(rep["sameProcess"] and not rep["oom"] and not rep["crash"] and "error" not in rep and (rendered or 0) > 0
                     and peak is not None and rep["boundBytes"] is not None and peak <= rep["boundBytes"])
    return rep


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
    return {"fonts": sorted(fonts), "textLayers": text, "contourLineLayers": contour_lines}


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
                         "the cold Rainier z8 view went idle in 73 to 93 s where it took 50 to 61 s before, on a day the sources were slow")
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
    ap.add_argument("--direct-heap-share", type=float, default=0.95,
                    help="T127: the bound on the direct view's heap peak, a share of the max heap. The peak counts garbage not yet "
                         "collected, which ART lets grow about a third above the live heap, so a healthy view peaks at 0.7 to 0.93 "
                         "of 192 MB; one at the max (0.99 to 1.0 before T127) is collecting in vain and throwing OutOfMemoryErrors")
    ap.add_argument("--direct-only", action="store_true",
                    help="T127: only the direct views, each in a new process (the per-zoom peak table); no tour views, no background step")
    ap.add_argument("--direct-keep-sources", action="store_true", help="T127: keep the phone's source range cache (cache/sources) before a direct view")
    args = ap.parse_args()

    views = [] if args.direct_only else read_views(args)
    direct_views = [] if args.direct_view.strip().lower() == "none" else [parse_view(x) for x in args.direct_view.split(",") if x.strip()]
    if args.direct_only and not direct_views:
        ap.error("--direct-only needs --direct-view")
    if not views and not args.direct_only:
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

        exp = expected_sources(health, v)
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
        c["tiles"] = all(tiles[s]["served"] > 0 for s in exp)
        if not c["tiles"]:
            rep["sourcesWithoutTiles"] = [s for s in exp if tiles[s]["served"] == 0]
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
                             "ok": http_ok or drawn > 0 or args.direct_only,  # T127: no tour views, nothing to check
                             "evidence": "http" if http_ok else "drawn text" if drawn else "none"}
        cg = final["contourGeneration"]
        summary["contourGeneration"] = {k: cg[k] for k in ("count", "minMs", "avgMs", "maxMs")}
        summary["contourGeneration"]["noDemOverPack"] = (cg.get("noDemOverPack") or {}).get("count")  # T98
        summary["online"] = online_state(server)  # T98: the state at the end of the tour
        summary["memory"] = final.get("memory")
    except (urllib.error.URLError, OSError, ValueError) as e:
        summary["glyphs"] = {"ok": False, "error": str(e)}
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
    summary["ok"] = (summary["viewsOk"] == len(reports) and summary["glyphs"]["ok"] and summary["backgroundStep"].get("ok", True) is not False
                     and summary["directStep"].get("ok", True) is not False)
    with open(stem + ".json", "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    print(f"{'#':>2} {'view':<22} {'label':<18} {'ok':<4} {'dem':>4} {'osm':>4} {'cont':>4} {'gen':>4} {'maxMs':>7} {'line':>5} {'clbl':>4} {'text':>4} {'txpx':>6} {'idle':<5} {'luma':>5} {'s':>5}  failed", file=sys.stderr)
    for r in reports:
        t = r.get("tiles", {})
        cmax = r.get("contours", {}).get("maxMs")
        rd = r.get("rendered") or {}
        print(f"{r['index']:>2} {r['view']:<22} {r['label'][:18]:<18} {'ok' if r['ok'] else 'FAIL':<4} "
              f"{t.get('dem', {}).get('served', '-'):>4} {t.get('osm', {}).get('served', '-'):>4} {t.get('contours', {}).get('served', '-'):>4} "
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
    for r in summary["directStep"].get("views", []):
        mb = lambda b: f"{b / 1048576:.0f}" if b else "-"
        print(f"direct {r['view']} {r['label']}: {'ok' if r['ok'] else 'FAIL'} heap peak {mb(r.get('peakBytes'))} of "
              f"{mb((r.get('heap') or {}).get('maxBytes'))} MB (bound {mb(r.get('boundBytes'))}), idle {r.get('idleSeconds')} s, "
              f"same process {r.get('sameProcess')}, OOM lines {len(r.get('oom') or [])}" + (f", error {r['error']}" if r.get("error") else ""),
              file=sys.stderr)
    print(f"{summary['viewsOk']}/{summary['viewsTotal']} views ok; sheet {summary['sheet']}; summary {stem}.json", file=sys.stderr)
    print(json.dumps(summary, indent=2))
    return 0 if summary["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
