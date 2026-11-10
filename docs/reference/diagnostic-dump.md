---
verified: 2026-11-10
source: observed (T258, on an API 35 emulator); the code in `shared/.../reports/DumpFormat.kt` and `androidApp/.../app/reports/`
---

# The diagnostic dump

Settings › About and advanced › Report a problem › **Send diagnostics** (T258) saves one text file that a tester sends
with a report: the app's memory over the last day, the last starts' times, the Technical details figures and the app's
own log, with no place in it. A tap only, never automatic; the app sends nothing. The file goes through the system's
save dialog (`SaveFile.bytes`, the dialog Export GPX uses); a share sheet would need a FileProvider and is a follow-up.
On this side `devtool dump-scan` reads one dump or a folder of them.

## On the phone

- **The sampler** (`reports/MemorySampler.kt`): scheduled from `DystopoApp.onCreate` (one line and a start mark;
  nothing of it runs on the main thread, T229's rule), a thread of its own (`dystopo-sampler`, lowest priority) takes a
  sample 15 s after the process starts and then every 60 s while the process lives, the background included (a
  recording with the screen off is covered; a frozen or sleeping process takes none). Each sample is one line appended
  to `files/diagnostics/samples.txt`; the newest 1,440 lines are kept (about a day of running), the file cut back to
  them once it reaches 1,500, so it is rewritten about once an hour. No setting: the file stays on the phone and the
  cost is small (below).
- **The dump** (`reports/DiagnosticDump.kt`), made on a worker when the button is tapped: the header, the ring
  (`samples`), `files/reports/.startup-history` (`starts`: the last twenty starts), the Technical details snapshot as
  Copy report writes it (`diagnostics`) and this process's own log, `logcat -d -v threadtime --pid=<pid> -t 4000`, the
  newest lines within 768 kB (`logcat`). The file name is `dystopo-diagnostics-<yyyyMMdd-HHmm>.txt` (local time).
- **The scrub**: every header value and every line of every section goes through the report scrubber
  (`shared/.../reports/Scrub.kt`, prepared once per dump with `Scrub.prepare`) with the phone's pack, area and search
  names and its storage roots: paths under the app's and the shared storage, coordinates (pairs, keyed `lat=`/`lon=`
  values, degrees, lone decimals of a metre's precision), boxes, tile paths, query strings and the names become
  `<file>`, `<coords>`, `<coord>`, `<bbox>`, `<tile>`, `?<query>`, `<pack>`, `<area>`, `<search>`. The debug build's
  per-launch token in the log becomes `<token>`. A known name is replaced wherever it appears, so a pack called
  "default" also turns the word in a system log line into `<pack>`: the scrub errs on the side of removing.
- **Strings**: the button "Send diagnostics"; above it "Saves a file you can send with a report: the app's memory use
  once a minute over the last day, the times of the last twenty starts, the figures under Technical details and the
  app's own log. Locations, place names, map names and file paths are removed. The app sends nothing; you choose where
  the file goes."; under it "Preparing the file…", then "Saved <file name>", "Could not save <file name>: <why>" or
  "Could not make the diagnostics file."; nothing when the dialog is cancelled.
- **Routes** (debug builds): `GET /debug/dump` (`?last=1`), `GET /debug/dump/sampler`, `POST /debug/dump/sample`,
  `POST /debug/dump/save`; `android-debug-routes.md`.

## The format, `dystopo-dump 1`

UTF-8 text (`shared/.../reports/DumpFormat.kt`, round trip in `DumpFormatTest`):

```
dystopo-dump 1                  the format and its version; a reader refuses another major version, reads 1.x
kind: diagnostics               header lines `key: value` up to the first empty line
atMs: 1794293551292             epoch ms the dump was made; `at` the same in ISO 8601 UTC
version: 0.1.25-alpha           then versionCode, build (debug|release), sdk, android, device, as every report
pid: 3979                       this process; upS its age in seconds
samplePeriodS: 60               the sampler's period and span (lines kept)
sampleKeep: 1440
logLines: 369                   log lines read; logError the reason when logcat could not be read, else -

== samples                      a section: `== <name>`, its lines, an empty line
s t=1794293551166 up=17 pid=3979 heapKb=70784 nativeKb=31567 pssKb=166374 threads=362 fds=242 demServed=5 ...

== starts                       `0.1.25-alpha cold, first frame 1.20 s, longest stall 0.40 s, finished`
== diagnostics                  the Technical details lines
== logcat                       logcat -v threadtime lines
```

A section line is never empty: an empty line, and one that begins with a blank or with `== `, is written with a leading
blank that the reader removes. A reader skips header keys, sections and sample keys it does not know; a new key is a
minor change, a changed meaning a new major version.

**A sample line** is `s` and then `key=value` pairs; values are integers or a word, never a decimal, so the scrubber
leaves samples as they are. Keys, in order: `t` epoch ms; `up` seconds since the process started; `pid`; `heapKb` the
Java heap in use (`Runtime` total minus free); `nativeKb` native allocated (`Debug.getNativeHeapAllocatedSize`); `pssKb`
this process's PSS (`Debug.getMemoryInfo`); `threads` and `fds` the entries of `/proc/self/task` and `/proc/self/fd`;
`demServed`, `osmServed`, `contourServed`, `tileKb`, `tileErrors` the tile counters since the process started;
`cacheRuns` the cache budget's runs; `trims` the heap guard's; `fg` 1 while the map is on screen; `rec` the recorder's
state (`idle`, `recording`, `waiting-for-gps`, `paused`, `auto-paused`, `stopped-by-system`; `-` before the tracker is ready); `cpuUs` the sampler thread's CPU
time for the reading (the file write left out).

## Reading dumps: `devtool dump-scan`

`./gradlew :devtool:run --args="dump-scan <file or folder> [...]"` (`devtool-commands.md`) reads every argument's
dumps together (a folder's `*.txt` files whose first line is a dump's) and prints JSON: `dumps` (each file: version,
build, device, Android, samples, runs, hours, starts, log lines, bytes); `growth` per figure: `slopePerHour`, the
least-squares slope pooled within each process run (a run ends where `pid` changes or `up` goes back, so a restart is
never read as a fall), `peak`, `last` (the newest sample's), `hours` (the runs' spans), `runs`, `samples`; `table`, the
same as fixed-width text; `starts`, the first-frame medians by version and start kind; `refused`. **A dump is refused**
when any line still holds a place by the scrubber's rules (a coordinate pair, a keyed latitude or longitude, a value
in degrees, a lone decimal of a metre's precision, a box: `DumpScan.places`), named by its line numbers and never their
text, and so is a file that is not a dump; neither is read further, and any refusal makes the exit status 2 after the
JSON. The phone scrubs before it writes, so a refusal is a defect to report.

Check a dump with the privacy scanner too: `java tools/PrivacyCheck.java scan < dump.txt` must show no term, value or
pair hits; its `pairsOutsidePublicAreas` counts timing pairs in log lines such as `avg=78.48ms min=1.04ms` and GC pauses,
which are not places.

## Cost (T258, API 35 emulator)

See the T258 entry of the dated log in `docs/PLANNING.md` for the measured figures: the sampler's CPU per sample, the
ring's size at a day and a dump's build time and size.
