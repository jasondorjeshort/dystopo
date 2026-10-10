"""Static file server with HTTP Range support, for viewing local tile output in a browser.

Python's built-in server ignores Range headers, which PMTiles needs. Run from the repo root:

    python -I tools/demo-server.py 8765

then open http://localhost:8765/tools/demo/ for the viewer (it reads out/rainier-best/ and
out/rainier-osm.pmtiles; see tools/demo/index.html for how to generate them).
"""
import os
import re
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


class RangeHandler(SimpleHTTPRequestHandler):
    def send_head(self):
        self._range_len = None
        path = self.translate_path(self.path)
        rng = self.headers.get("Range")
        m = re.match(r"bytes=(\d*)-(\d*)$", rng or "")
        if m and os.path.isfile(path):
            size = os.path.getsize(path)
            if m.group(1):
                start = int(m.group(1))
                end = int(m.group(2)) if m.group(2) else size - 1
            else:
                start = max(0, size - int(m.group(2)))
                end = size - 1
            end = min(end, size - 1)
            if start >= size or start > end:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return None
            f = open(path, "rb")
            f.seek(start)
            self.send_response(206)
            self.send_header("Content-Type", self.guess_type(path))
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            self._range_len = end - start + 1
            return f
        return super().send_head()

    def copyfile(self, source, outputfile):
        if self._range_len is None:
            return super().copyfile(source, outputfile)
        remaining = self._range_len
        while remaining > 0:
            chunk = source.read(min(65536, remaining))
            if not chunk:
                break
            outputfile.write(chunk)
            remaining -= len(chunk)

    def end_headers(self):
        self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def log_message(self, *args):
        pass


class DemoServer(ThreadingHTTPServer):
    """One thread per connection, with room for a busy page.

    The stock listen backlog is 5: when the machine is loaded (a Gradle build alongside), MapLibre's
    burst of tile and range requests overflows it and Windows refuses the extra connections, which the
    page reports as failed requests. A deep backlog queues them instead. Daemon threads let Ctrl+C
    exit with requests in flight, and a client that hangs up mid-response is not an error.
    """

    request_queue_size = 1024
    daemon_threads = True

    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError)):
            return
        super().handle_error(request, client_address)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print(f"serving {os.getcwd()} on http://localhost:{port}/", flush=True)
    DemoServer(("127.0.0.1", port), RangeHandler).serve_forever()
