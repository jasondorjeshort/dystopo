<#
.SYNOPSIS
  T82: soak the standalone server for hours under a steady mixed load and record how it ages.

.DESCRIPTION
  Starts the installed server (./gradlew :server:installDist, copied under -Root so a rebuild cannot touch
  the running jars) on -Port with its own cache and out directories under -Root, then loops until -Hours
  have passed:
    - a round of tile requests every -RoundSeconds through curl (parallel): DEM, vector, contour and
      hillshade tiles walked across the served pack at z8 to z14 (so new keys keep arriving and the walk
      wraps every few hours), the style with map options, glyphs, sprites, the catalog, /healthz,
      /out/ range reads of the pack, a route, the sync status and a pull, relay status and relay posts
      of well-formed sealed frames under random slots (they expire after the relay TTL);
    - with -LiveEvery N, one new live tile column (DEM, contours, OSM) every N rounds outside the packs
      (the cache grows by those; polite to the upstreams);
    - every -TripMinutes a planned route saved as a trip (route -> /route/bundle -> /sync/push as the
      soak user) and, every -ShareMinutes (an hour), a share link for it (the replica and shares.json grow);
    - every -BuildMinutes a small /build (same name, overwrite=1) that replaces a pack under out/;
    - every -ProbeMinutes tools/server-probe.py --quick --no-bomb (the error paths, again and again).
  T111: to look for something that happens once an hour, run the hourly items every 5 minutes for an hour
  (-Minutes 60 -TripMinutes 5 -ShareMinutes 5 -BuildMinutes 5 -ProbeMinutes 5); the server logs every request
  (logRequests, on by default) to <Root>/logs/server.log, with the status and time of each.
  Every -SampleMinutes it appends one JSON line to <Root>/soak.jsonl: /metrics (heap, heap after GC,
  threads, GC, disk under cache/ and out/, log, the server's own count per route and status class), T107: /stats
  sourceDownloads (the elevation readers' own downloads under the T92 byte budget: requests, bytes, last hour,
  largest, refusals per cap), the OS view of the process (working set, private bytes, handles, threads, CPU
  seconds) and the client side of the interval (requests, statuses, p50/p95/max per group). At the end: a full
  probe, a class histogram, <Root>/soak.svg (the chart), <Root>/summary.json (first, last and per-hour growth of
  each series; T111: `unanswered`, the requests the server never answered (status `none` in /metrics) per route,
  with the samples where each count grew, counted once more after the final probe), and the server is stopped by its PID.

  -ChartOnly redraws soak.svg and summary.json from an existing soak.jsonl (also while a soak runs), prints the
  verdict's PASS/FAIL lines and exits with its result. A run that ended early (no summary-extra.json) gets a
  partial verdict: server alive at the last sample, the last hourly probe, no OutOfMemoryError, failure rate,
  unanswered requests and live heap growth; it says "partial run" and has no final probe.
  Refuses the coordinator's and the phone's ports (8765, 8686, 8687). Stops only the server it started.

.EXAMPLE
  # A detached 12-hour soak (Windows): the PID of the soak script goes to out\soak\soak-script.pid.
  $p = Start-Process powershell -WindowStyle Hidden -PassThru -ArgumentList '-ExecutionPolicy','Bypass','-File','tools\server-soak.ps1','-Hours','12','-Port','9090'
  $p.Id | Out-File out\soak\soak-script.pid

.EXAMPLE
  pwsh tools/server-soak.ps1 -Minutes 20 -SampleMinutes 2 -Port 9090 -Root out/soak-ci -BuildPack   # CI: no pack on disk, build one first
#>
param(
    [int]$Port = 9090,
    [double]$Hours = 0,
    [int]$Minutes = 0,
    [int]$SampleMinutes = 10,
    [string]$Root = "out/soak",
    [int]$RoundSeconds = 20,
    [int]$Parallel = 6,
    [int]$LiveEvery = 6,
    [int]$TripMinutes = 10,
    [int]$ShareMinutes = 60,
    [int]$BuildMinutes = 60,
    [int]$ProbeMinutes = 60,
    [int]$HeapMb = 2048,
    [switch]$NoFullGc,            # skip the class histogram (a full GC) at every sample
    [string]$Packs = "",          # comma-separated .pmtiles to copy into <Root>/out when it has none
    [switch]$BuildPack,           # no pack on disk (CI): build a small one through /build first
    [switch]$NoBuild,             # use the install already under <Root>/install
    [switch]$ChartOnly,
    [string]$Python = "",
    [double]$MaxFailureRate = 0.001,      # verdict: failed requests (5xx or no answer) / all requests
    [int]$MaxUnanswered = 0,              # verdict (T111): requests the server never answered (/metrics status none)
    [double]$MaxHeapGrowthMbPerHour = 5   # verdict: live heap growth over the second half
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$onWindows = ($PSVersionTable.PSEdition -ne "Core") -or $IsWindows
$sep = [IO.Path]::DirectorySeparatorChar
$Root = if ([IO.Path]::IsPathRooted($Root)) { [IO.Path]::GetFullPath($Root) } else { [IO.Path]::GetFullPath((Join-Path $repo $Root)) }
New-Item -ItemType Directory -Force $Root | Out-Null
$logFile = Join-Path $Root "soak.jsonl"
if (-not $Python) { $Python = if ($onWindows) { "python" } else { "python3" } }
$curl = if ($onWindows) { "curl.exe" } else { "curl" }

function Write-Json($obj) { $obj | ConvertTo-Json -Depth 12 -Compress }

# ---------------------------------------------------------------- chart and summary (pure PowerShell, SVG)
function Get-Path($o, [string]$path) {
    $v = $o
    foreach ($k in $path.Split('.')) { if ($null -eq $v) { return $null }; $v = $v.$k }
    return $v
}

function Write-Chart {
    if (-not (Test-Path $logFile)) { Write-Host "no $logFile"; return }
    $all = @(Get-Content $logFile | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    $rows = @($all | Where-Object { $_.kind -eq "sample" })
    $evts = @($all | Where-Object { $_.kind -eq "event" })
    if ($rows.Count -lt 1) { Write-Host "no samples yet"; return }
    # T111: the requests the server never answered (status none in its own /metrics, per route group, counted since
    # it started), summed per sample for the chart; older soak.jsonl files have the same metrics, so this works on them.
    foreach ($r in $rows) {
        $n = $null
        $rq = Get-Path $r "metrics.requests"
        if ($rq) { $n = 0; foreach ($g in $rq.PSObject.Properties) { $n += [double](Get-Path $g.Value "status.none") } }
        $r | Add-Member -NotePropertyName serverNone -NotePropertyValue $n -Force
    }
    $t0 = [double]$rows[0].epochS
    $mb = 1048576.0
    # panel title, unit, series (label, path, scale, colour)
    $panels = @(
        @{ title = "JVM heap"; unit = "MB"; series = @(
            @("used", "metrics.jvm.heapUsedBytes", $mb, "#9aa7b8"),
            @("after last GC", "metrics.jvm.heapAfterGcBytes", $mb, "#93c5fd"),
            @("live after full GC (leak signal)", "liveHeap.bytes", $mb, "#1d4ed8")) },
        @{ title = "Process memory (OS)"; unit = "MB"; series = @(
            @("working set", "process.workingSetBytes", $mb, "#0f766e"),
            @("private / RSS", "process.privateBytes", $mb, "#b45309")) },
        @{ title = "Handles and threads"; unit = "count"; series = @(
            @("OS handles / open files", "process.handles", 1, "#7c3aed"),
            @("JVM threads", "metrics.jvm.threads", 1, "#db2777")) },
        @{ title = "Source downloads (T92 budget)"; unit = "MB"; series = @(
            @("downloaded since start", "sourceDownloads.bytes", $mb, "#2563eb"),
            @("in the last hour", "sourceDownloads.lastHourBytes", $mb, "#b45309"),
            @("largest one", "sourceDownloads.largestRequestBytes", $mb, "#6b7280")) },
        @{ title = "Disk"; unit = "MB"; series = @(
            @("cache/", "metrics.disk.cacheDir.bytes", $mb, "#2563eb"),
            @("out/", "metrics.disk.outDir.bytes", $mb, "#b45309"),
            @("log", "logBytesOnDisk", $mb, "#6b7280")) },
        @{ title = "Client latency p95 per interval"; unit = "ms"; series = @(
            @("pack tiles", "client.groups.pack.p95Ms", 1, "#0f766e"),
            @("live tiles", "client.groups.live.p95Ms", 1, "#b45309"),
            @("other routes", "client.groups.other.p95Ms", 1, "#6b7280")) },
        @{ title = "Requests and failures per interval"; unit = "count"; series = @(
            @("requests", "client.requests", 1, "#2563eb"),
            @("5xx + failed", "client.failures", 1, "#dc2626"),
            @("unanswered (server total)", "serverNone", 1, "#7c2d12")) }
    )
    $W = 1100; $ph = 210; $pad = 56; $gap = 28; $top = 70
    $H = $top + $panels.Count * ($ph + $gap) + 20
    $hoursSpan = [math]::Max(0.01, ([double]$rows[-1].epochS - $t0) / 3600.0)
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("<svg xmlns='http://www.w3.org/2000/svg' width='$W' height='$H' viewBox='0 0 $W $H' font-family='Segoe UI, Arial, sans-serif'>")
    [void]$sb.Append("<rect width='100%' height='100%' fill='#ffffff'/>")
    $title = "Dystopo server soak: {0:N1} h, {1} samples, port {2}" -f $hoursSpan, $rows.Count, $rows[0].port
    [void]$sb.Append("<text x='$pad' y='30' font-size='18' font-weight='600' fill='#111827'>$title</text>")
    $sub = "started {0} UTC; heap max {1:N0} MB" -f $rows[0].ts, ((Get-Path $rows[0] "metrics.jvm.heapMaxBytes") / $mb)
    [void]$sb.Append("<text x='$pad' y='50' font-size='12' fill='#4b5563'>$sub</text>")
    $i = 0
    foreach ($p in $panels) {
        $y0 = $top + $i * ($ph + $gap); $i++
        $x0 = $pad; $x1 = $W - 220
        $max = 0.0
        foreach ($s in $p.series) { foreach ($r in $rows) { $v = Get-Path $r $s[1]; if ($null -ne $v) { $v = [double]$v / $s[2]; if ($v -gt $max) { $max = $v } } } }
        if ($max -le 0) { $max = 1 }
        $max = $max * 1.1
        [void]$sb.Append("<rect x='$x0' y='$y0' width='$($x1 - $x0)' height='$ph' fill='#f9fafb' stroke='#d1d5db'/>")
        [void]$sb.Append("<text x='$x0' y='$($y0 - 6)' font-size='13' font-weight='600' fill='#111827'>$($p.title) ($($p.unit))</text>")
        foreach ($f in 0, 0.5, 1) {
            $yy = $y0 + $ph - $f * $ph
            [void]$sb.Append("<line x1='$x0' x2='$x1' y1='$yy' y2='$yy' stroke='#e5e7eb'/>")
            [void]$sb.Append(("<text x='{0}' y='{1}' font-size='10' fill='#6b7280' text-anchor='end'>{2:G4}</text>" -f ($x0 - 4), ($yy + 3), ($max * $f)))
        }
        foreach ($h in 0..([math]::Ceiling($hoursSpan))) {
            if ($h -gt $hoursSpan) { break }
            $xx = $x0 + ($h / $hoursSpan) * ($x1 - $x0)
            [void]$sb.Append(("<text x='{0:F1}' y='{1}' font-size='10' fill='#6b7280' text-anchor='middle'>{2}h</text>" -f $xx, ($y0 + $ph + 12), $h))
        }
        $li = 0
        foreach ($s in $p.series) {
            $pts = @()
            foreach ($r in $rows) {
                $v = Get-Path $r $s[1]
                if ($null -eq $v) { continue }
                $x = $x0 + (([double]$r.epochS - $t0) / 3600.0 / $hoursSpan) * ($x1 - $x0)
                $y = $y0 + $ph - ([double]$v / $s[2] / $max) * $ph
                $pts += ("{0:F1},{1:F1}" -f $x, $y)
            }
            if ($pts.Count -gt 0) {
                [void]$sb.Append("<polyline fill='none' stroke='$($s[3])' stroke-width='2' points='$($pts -join ' ')'/>")
                if ($pts.Count -eq 1) { $xy = $pts[0].Split(','); [void]$sb.Append("<circle cx='$($xy[0])' cy='$($xy[1])' r='3' fill='$($s[3])'/>") }
            }
            $last = Get-Path $rows[-1] $s[1]
            $lastTxt = if ($null -ne $last) { "{0:G4}" -f ([double]$last / $s[2]) } else { "-" }
            $ly = $y0 + 16 + $li * 18; $li++
            [void]$sb.Append("<rect x='$($x1 + 12)' y='$($ly - 9)' width='12' height='4' fill='$($s[3])'/>")
            [void]$sb.Append("<text x='$($x1 + 30)' y='$ly' font-size='11' fill='#111827'>$($s[0]): $lastTxt</text>")
        }
    }
    [void]$sb.Append("</svg>")
    $svg = Join-Path $Root "soak.svg"
    [IO.File]::WriteAllText($svg, $sb.ToString())

    # summary: first, last, max and the growth per hour (least squares over the second half, after warm-up)
    $series = [ordered]@{
        liveHeapMb = @("liveHeap.bytes", $mb); heapAfterGcMb = @("metrics.jvm.heapAfterGcBytes", $mb); heapUsedMb = @("metrics.jvm.heapUsedBytes", $mb)
        workingSetMb = @("process.workingSetBytes", $mb); privateMb = @("process.privateBytes", $mb)
        handles = @("process.handles", 1); threads = @("metrics.jvm.threads", 1)
        cacheMb = @("metrics.disk.cacheDir.bytes", $mb); cacheFiles = @("metrics.disk.cacheDir.files", 1)
        outMb = @("metrics.disk.outDir.bytes", $mb); logMbOnDisk = @("logBytesOnDisk", $mb)
        packP95Ms = @("client.groups.pack.p95Ms", 1); liveP95Ms = @("client.groups.live.p95Ms", 1); otherP95Ms = @("client.groups.other.p95Ms", 1)
        sourceMb = @("sourceDownloads.bytes", $mb); sourceRequests = @("sourceDownloads.requests", 1); sourceLastHourMb = @("sourceDownloads.lastHourBytes", $mb)
        sourceRefusedRequestCap = @("sourceDownloads.refused.requestCap", 1); sourceRefusedHourCap = @("sourceDownloads.refused.hourCap", 1)
    }
    $sum = [ordered]@{ hours = [math]::Round($hoursSpan, 2); samples = $rows.Count; startedAt = $rows[0].ts; lastSampleAt = $rows[-1].ts
        requests = ($rows | ForEach-Object { [double](Get-Path $_ "client.requests") } | Measure-Object -Sum).Sum
        failures = ($rows | ForEach-Object { [double](Get-Path $_ "client.failures") } | Measure-Object -Sum).Sum
        statuses = @{}; series = [ordered]@{} }
    # what a verdict needs when there is no summary-extra.json (the run ended early, or is still going)
    $startEv = @($evts | Where-Object { $_.type -eq "start" })[0]
    $sum.plannedHours = if ($startEv) { [double]$startEv.hours } else { $null }
    $sum.lastSampleAlive = [bool]$rows[-1].alive
    $sum.serverExited = (@($evts | Where-Object { $_.type -eq "serverExited" }).Count -gt 0)
    $sum.lastProbe = @($evts | Where-Object { $_.type -in "probe", "probeFinal" })[-1]
    # T111: requests the server never answered, per route group (the last sample holds the counts since the start),
    # the slowest request of each such group, and the samples at which a count grew (to line up with the events:
    # the hourly probe, trip, share link and build).
    # The counts after the final probe (summary-extra.json) when the run got that far, else the last sample's.
    $lastReq = @($rows | Where-Object { Get-Path $_ "metrics.requests" })
    $extraFile = Join-Path $Root "summary-extra.json"
    $afterProbe = if (Test-Path $extraFile) { (Get-Content $extraFile -Raw | ConvertFrom-Json).requestsAfterProbe } else { $null }
    $points = @($lastReq | ForEach-Object { , @($_.ts, (Get-Path $_ "metrics.requests")) })
    if ($afterProbe) { $points += , @("after the final probe", $afterProbe) }
    if ($points.Count -gt 0) {
        $by = [ordered]@{}; $maxMs = [ordered]@{}; $grew = New-Object System.Collections.ArrayList
        foreach ($g in $points[-1][1].PSObject.Properties) {
            $n = [double](Get-Path $g.Value "status.none")
            if ($n -gt 0) { $by[$g.Name] = $n; $maxMs[$g.Name] = Get-Path $g.Value "maxMs" }
        }
        $prev = @{}
        foreach ($pt in $points) {
            foreach ($g in $pt[1].PSObject.Properties) {
                $n = [double](Get-Path $g.Value "status.none")
                $was = if ($prev.ContainsKey($g.Name)) { $prev[$g.Name] } else { 0 }
                if ($n -gt $was -and $grew.Count -lt 100) { [void]$grew.Add([ordered]@{ ts = $pt[0]; route = $g.Name; count = $n }) }
                $prev[$g.Name] = $n
            }
        }
        $sum.unanswered = [ordered]@{ total = (($by.Values | Measure-Object -Sum).Sum + 0); byRoute = $by; maxMsByRoute = $maxMs
            countedAt = $(if ($afterProbe) { "after the final probe" } else { "last sample" }); grew = @($grew) }
    } else { $sum.unanswered = $null }
    foreach ($r in $rows) { $st = Get-Path $r "client.status"; if ($st) { foreach ($pp in $st.PSObject.Properties) { $sum.statuses[$pp.Name] = [double]$sum.statuses[$pp.Name] + [double]$pp.Value } } }
    foreach ($k in $series.Keys) {
        $pts = @($rows | ForEach-Object { $v = Get-Path $_ $series[$k][0]; if ($null -ne $v) { , @((([double]$_.epochS - $t0) / 3600.0), ([double]$v / $series[$k][1])) } })
        if ($pts.Count -eq 0) { continue }
        $half = @($pts | Where-Object { $_[0] -ge ($hoursSpan / 2) })
        if ($half.Count -lt 3) { $half = $pts }
        $n = $half.Count; $sx = 0; $sy = 0; $sxx = 0; $sxy = 0
        foreach ($q in $half) { $sx += $q[0]; $sy += $q[1]; $sxx += $q[0] * $q[0]; $sxy += $q[0] * $q[1] }
        $den = $n * $sxx - $sx * $sx
        $slope = if ($n -ge 2 -and [math]::Abs($den) -gt 1e-9) { ($n * $sxy - $sx * $sy) / $den } else { $null }
        $sum.series[$k] = [ordered]@{ first = [math]::Round($pts[0][1], 2); last = [math]::Round($pts[-1][1], 2)
            min = [math]::Round(($pts | ForEach-Object { $_[1] } | Measure-Object -Minimum).Minimum, 2)
            max = [math]::Round(($pts | ForEach-Object { $_[1] } | Measure-Object -Maximum).Maximum, 2)
            perHourSecondHalf = if ($null -ne $slope) { [math]::Round($slope, 3) } else { $null } }
    }
    $extra = Join-Path $Root "summary-extra.json"
    if (Test-Path $extra) { $sum.end = Get-Content $extra -Raw | ConvertFrom-Json }
    (Write-Json $sum) | Out-File -Encoding utf8 (Join-Path $Root "summary.json")
    Write-Host "chart: $svg"
    return $sum
}

# The verdict: each check with its reason. A passing run is: the server alive at the end and after the final
# probe, the final probe without unexpected answers, no OutOfMemoryError in the kept logs, failed requests
# (5xx or no answer) at most -MaxFailureRate of all requests (a cold live render may 500 once in 100,000;
# T82's post-fix soak exited 1 on exactly that), at most -MaxUnanswered requests the server never answered (T111,
# its own /metrics status none, per route), and the live heap after full GC growing at most
# -MaxHeapGrowthMbPerHour over the second half (memory flat after warm-up).
function Get-Verdict($sum) {
    $checks = New-Object System.Collections.ArrayList
    function chk([string]$name, [bool]$ok, [string]$detail) { [void]$checks.Add([ordered]@{ name = $name; ok = $ok; detail = $detail }) }
    $end = $sum.end
    $partial = $null
    if ($null -eq $end) {
        # No summary-extra.json: the soak ended early (the session was signed out, the machine slept, the script was
        # killed) or is still running. Judge what was sampled, the last hourly probe standing in for the final one,
        # and say so in the verdict. A server that exited on its own is still a FAIL.
        chk "serverAlive" ([bool]$sum.lastSampleAlive -and -not $sum.serverExited) ("server running at the last sample ({0}): {1}; serverExited event: {2}" -f $sum.lastSampleAt, $sum.lastSampleAlive, $sum.serverExited)
        $lp = $sum.lastProbe
        if ($lp) { chk "lastProbe" ([bool]$lp.ok) ("last hourly probe {0}: exit {1}, {2} probes, unexpected [{3}]" -f $lp.ts, $lp.exit, $lp.probes, (@($lp.unexpected | Where-Object { $_ }) -join ", ")) }
        # T111: an archived soak may come without its logs (Select-String on a missing folder stops the script).
        $kept = @(Get-ChildItem (Join-Path $Root "logs") -Filter "server.log*" -ErrorAction SilentlyContinue)
        $ooms = if ($kept.Count) { @(Select-String -Path ($kept | ForEach-Object { $_.FullName }) -Pattern "OutOfMemoryError" -SimpleMatch).Count } else { 0 }
        chk "noOutOfMemory" ($ooms -eq 0) ("OutOfMemoryError lines in the kept logs: $ooms" + $(if (-not $kept.Count) { " (no logs kept)" }))
        $partial = "partial run: {0} of {1} h planned, no final probe" -f $sum.hours, $sum.plannedHours
    }
    else {
        chk "aliveAtEnd" ([bool]$end.aliveAtEnd) "server running when the load stopped: $($end.aliveAtEnd)"
        chk "aliveAfterProbe" ([bool]$end.aliveAfterProbe) "server running after the final probe: $($end.aliveAfterProbe)"
        $fp = $end.finalProbe
        $unexp = @($fp.unexpected | Where-Object { $_ })
        chk "finalProbe" ([bool]$fp.ok) ("exit {0}, {1} probes, unexpected [{2}], known [{3}]{4}" -f $fp.exit, $fp.probes, ($unexp -join ", "), (@($fp.known) -join ", "), $(if ($fp.error) { ", $($fp.error)" }))
        chk "noOutOfMemory" ([int]$end.outOfMemoryLinesInKeptLogs -eq 0) "OutOfMemoryError lines in the kept logs: $($end.outOfMemoryLinesInKeptLogs)"
    }
    $req = [double]$sum.requests; $fail = [double]$sum.failures
    $rate = if ($req -gt 0) { $fail / $req } else { 1.0 }
    chk "failureRate" ($req -gt 0 -and $rate -le $MaxFailureRate) ("{0} of {1} requests failed (5xx or no answer) = {2:P4}, limit {3:P4}" -f $fail, $req, $rate, $MaxFailureRate)
    # T111: a request the server never answered (the client went away first, or the connection was cut) is counted
    # by the server under status none; the 11.4 h soak of T107 had one such /route request an hour. Each route's
    # count is in the line, with its slowest request.
    $un = $sum.unanswered
    if ($null -ne $un) {
        $byTxt = if ($un.byRoute.Count -gt 0) { ($un.byRoute.Keys | ForEach-Object { "{0} {1} (slowest {2} ms)" -f $_, $un.byRoute[$_], $un.maxMsByRoute[$_] }) -join ", " } else { "none per route" }
        chk "unanswered" ([double]$un.total -le $MaxUnanswered) ("{0} requests never answered (server /metrics status none, {3}): {1}; limit {2}" -f $un.total, $byTxt, $MaxUnanswered, $un.countedAt)
    } else { chk "unanswered" $true "not measured (no /metrics in the samples)" }
    $lh = $sum.series["liveHeapMb"]
    if ($lh -and $null -ne $lh.perHourSecondHalf) {
        chk "liveHeapFlat" ([double]$lh.perHourSecondHalf -le $MaxHeapGrowthMbPerHour) ("live heap after full GC {0} -> {1} MB, {2} MB/h over the second half, limit {3}" -f $lh.first, $lh.last, $lh.perHourSecondHalf, $MaxHeapGrowthMbPerHour)
    } else { chk "liveHeapFlat" $true "not measured (-NoFullGc or too few samples)" }
    $ok = -not ($checks | Where-Object { -not $_.ok })
    $v = [ordered]@{ ok = [bool]$ok; checks = @($checks) }
    if ($partial) { $v.partial = $partial }
    return $v
}

function Write-Verdict($v) {
    foreach ($c in $v.checks) { Write-Host ("{0} {1}: {2}" -f $(if ($c.ok) { "PASS" } else { "FAIL" }), $c.name, $c.detail) }
    Write-Host ("verdict: {0}{1}" -f $(if ($v.ok) { "PASS" } else { "FAIL (" + (($v.checks | Where-Object { -not $_.ok } | ForEach-Object { $_.name }) -join ", ") + ")" }), $(if ($v.partial) { " [$($v.partial)]" }))
}

if ($ChartOnly) {
    $s = Write-Chart
    if (-not $s) { exit 1 }
    $s.verdict = Get-Verdict $s
    (Write-Json $s) | Out-File -Encoding utf8 (Join-Path $Root "summary.json")
    Write-Json $s
    Write-Verdict $s.verdict
    exit $(if ($s.verdict.ok) { 0 } else { 1 })
}

# ---------------------------------------------------------------- setup
if ($Port -in 8765, 8686, 8687) { throw "port $Port belongs to the coordinator or the phone; pick 9090..9099" }
$span = if ($Minutes -gt 0) { $Minutes * 60 } elseif ($Hours -gt 0) { [int]($Hours * 3600) } else { 12 * 3600 }
$busy = $false
try { $c = New-Object Net.Sockets.TcpClient; $c.Connect("127.0.0.1", $Port); $c.Close(); $busy = $true } catch { }
if ($busy) { throw "port $Port is in use" }

$install = Join-Path $Root "install"
if (-not $NoBuild) {
    $gradlew = if ($onWindows) { Join-Path $repo "gradlew.bat" } else { Join-Path $repo "gradlew" }
    & $gradlew -q :server:installDist
    if ($LASTEXITCODE -ne 0) { throw "installDist failed" }
    if (Test-Path $install) { Remove-Item -Recurse -Force $install }
    Copy-Item -Recurse (Join-Path $repo "server/build/install/server") $install
}
$cacheDir = Join-Path $Root "cache"; $outDir = Join-Path $Root "out"; $logDir = Join-Path $Root "logs"
New-Item -ItemType Directory -Force $cacheDir, $outDir, $logDir | Out-Null
if ($Packs) {
    foreach ($f in $Packs.Split(',')) { if (-not (Test-Path (Join-Path $outDir (Split-Path -Leaf $f)))) { Copy-Item $f $outDir } }
}

$token = "soak-legacy-token-" + [guid]::NewGuid().ToString("N")
$userToken = "soak-user-token-" + [guid]::NewGuid().ToString("N")
$adminToken = "soak-admin-token-" + [guid]::NewGuid().ToString("N")
$config = [ordered]@{
    bind = "127.0.0.1"; port = $Port; cacheDir = $cacheDir; outDir = $outDir
    token = $token; users = @(@{ id = "soak"; token = $userToken }); adminToken = $adminToken
    log = @{ file = (Join-Path $logDir "server.log"); maxMb = 16; keep = 3 }
    metrics = @{ diskScanMinutes = [math]::Max(1, $SampleMinutes) }
    build = @{ maxTiles = 4000 }
}
$configFile = Join-Path $Root "server.json"
(Write-Json $config) | Out-File -Encoding ascii $configFile

$javaHome = $env:JAVA_HOME
$java = if ($javaHome) { Join-Path $javaHome ("bin" + $sep + "java") } else { "java" }
$cpSep = if ($onWindows) { ";" } else { ":" }
$cp = (Get-ChildItem (Join-Path $install "lib") -Filter *.jar | ForEach-Object { $_.FullName }) -join $cpSep
$cpFile = Join-Path $Root "classpath.txt"
[IO.File]::WriteAllText($cpFile, "-cp `"" + $cp.Replace('\', '\\') + "`"")
$jvmArgs = @("-Xmx$($HeapMb)m", "-Djava.awt.headless=true", "-Dstdout.encoding=UTF-8",
    "@$cpFile", "io.github.jasondorjeshort.dystopo.server.MainKt", "--config", $configFile)
$srv = Start-Process -FilePath $java -ArgumentList $jvmArgs -WorkingDirectory $Root -PassThru -NoNewWindow `
    -RedirectStandardOutput (Join-Path $logDir "stdout.txt") -RedirectStandardError (Join-Path $logDir "stderr.txt")
$srvPid = $srv.Id
$srvPid | Out-File -Encoding ascii (Join-Path $Root "server.pid")
$base = "http://127.0.0.1:$Port"
Write-Host "server pid $srvPid on $base; root $Root; $([math]::Round($span / 3600.0, 2)) h"

function Invoke-Curl([string[]]$curlArgs) {
    $out = & $curl -s @curlArgs 2>$null
    return $out
}
function Get-JsonUrl([string]$path, [string]$auth = "") {
    $a = @("--max-time", "60")
    if ($auth) { $a += @("-H", "Authorization: Bearer $auth") }
    $t = Invoke-Curl ($a + @("$base$path"))
    if (-not $t) { return $null }
    try { return ($t -join "`n") | ConvertFrom-Json } catch { return $null }
}
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
    $h = Get-JsonUrl "/healthz"
    if ($h -and $h.status -eq "ok") { break }
    if ($srv.HasExited) { throw "server exited at startup (see $logDir)" }
    Start-Sleep 1
}

# ---------------------------------------------------------------- the pack and the walk
function Get-Pack {
    $cat = Get-JsonUrl "/catalog"
    if ($cat -and $cat.packs) { return @($cat.packs | Where-Object { -not $_.single -and $_.id -ne "soakbuild" } | Select-Object -First 1)[0] }
    return $null
}
$events = New-Object System.Collections.ArrayList
function Add-Event($e) { $e.ts = (Get-Date).ToUniversalTime().ToString("o"); [void]$events.Add($e); (Write-Json ([ordered]@{ kind = "event" } + $e)) | Add-Content -Encoding utf8 $logFile }

function Invoke-Build([string]$name, [string]$bbox, [int]$minZ, [int]$maxZ) {
    $t0 = Get-Date
    $r = Invoke-Curl @("-X", "POST", "--max-time", "60", "-H", "Authorization: Bearer $userToken", "$base/build?name=$name&bbox=$bbox&minZoom=$minZ&maxZoom=$maxZ&overwrite=1")
    try { $job = ($r -join "") | ConvertFrom-Json } catch { return @{ type = "build"; ok = $false; error = "$r" } }
    $id = $job.id
    $state = $job.status
    if (-not $id) { return @{ type = "build"; ok = $false; error = ("$r" -replace '\s+', ' ') } }
    while ($state -in "queued", "running" -and ((Get-Date) - $t0).TotalSeconds -lt 1800) {
        Start-Sleep 3
        $j = Get-JsonUrl "/build/$id" $userToken
        if ($j) { $state = $j.status }
    }
    return @{ type = "build"; name = $name; id = $id; state = $state; ok = ($state -eq "done"); seconds = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1) }
}

$pack = Get-Pack
if (-not $pack -and $BuildPack) {
    # Paradise, Mount Rainier (a public area): a small DEM + OpenFreeMap pack built through the server itself.
    $e = Invoke-Build "soakpack" "-121.80,46.76,-121.68,46.84" 8 13
    Add-Event $e
    $pack = Get-Pack
}
if (-not $pack) { throw "no pack to walk: pass -Packs out/rainier-dem.pmtiles,out/rainier-outdoors.pmtiles or -BuildPack" }
$b = $pack.bounds
$west = [double]$b[0]; $south = [double]$b[1]; $east = [double]$b[2]; $north = [double]$b[3]
$minZ = [math]::Max(8, [int]$pack.minZoom); $maxZ = [math]::Min(14, [int]$pack.maxZoom)
function Tile-X([double]$lon, [int]$z) { [int][math]::Floor(($lon + 180.0) / 360.0 * [math]::Pow(2, $z)) }
function Tile-Y([double]$lat, [int]$z) { $r = $lat * [math]::PI / 180; [int][math]::Floor((1 - [math]::Log([math]::Tan($r) + 1 / [math]::Cos($r)) / [math]::PI) / 2 * [math]::Pow(2, $z)) }
$walk = New-Object System.Collections.ArrayList
foreach ($z in $minZ..$maxZ) {
    $x0 = Tile-X $west $z; $x1 = Tile-X $east $z; $y0 = Tile-Y $north $z; $y1 = Tile-Y $south $z
    for ($y = $y0; $y -le $y1; $y++) { for ($x = $x0; $x -le $x1; $x++) { [void]$walk.Add("$z/$x/$y") } }
}
# Interleave zooms so every round touches several (a deterministic shuffle).
$rng = New-Object System.Random 82
$walk = @($walk | Sort-Object { $rng.Next() })
Write-Host "walking $($walk.Count) tiles of pack '$($pack.id)' z$minZ..z$maxZ"
Add-Event @{ type = "start"; pid = $srvPid; port = $Port; pack = $pack.id; walkTiles = $walk.Count; heapMb = $HeapMb; hours = [math]::Round($span / 3600.0, 2) }

# Live: columns of z12 tiles walked northwards outside the packs (3DEP and OpenFreeMap),
# one step every -LiveEvery rounds. The first visit of each is a cold render; it is cached afterwards.
$liveLon = -120.5; $liveLat = 45.6
$liveStep = 0

$cxx = 0
$unitsCycle = @("m", "ft")
$mapOptions = @("", "hillshade=multi", "slope=on", "contourInterval=40", "night=on", "hillshade=off&trails=off", "slope=steepness") # T107: both T104 shadings (on is avalanche)
$paradise = "46.7864,-121.7366"; $panorama = "46.8024,-121.7289"

function New-Round([int]$round) {
    $lines = New-Object System.Collections.ArrayList
    function add([string]$group, [string]$path, [string[]]$hdr = @()) {
        $ln = "url = `"$base$path`"`noutput = `"" + ($(if ($onWindows) { "NUL" } else { "/dev/null" })) + "`"`n"
        foreach ($hh in $hdr) { $ln += "header = `"$hh`"`n" }
        # T107: each URL is its own operation (the round file joins them with `next`), with its own write-out and
        # time limit: curl applies a config file's header lines to every URL of one operation, so before this every
        # request of a round carried the sync lines' token and the /out/ line's Range header.
        $ln += "write-out = `"%{http_code} %{time_total} %{url_effective}\n`"`nmax-time = 120`n"
        [void]$lines.Add(@{ group = $group; text = $ln; url = "$base$path" })
    }
    for ($k = 0; $k -lt 12; $k++) {
        $t = $walk[($script:cxx++) % $walk.Count]
        $u = $unitsCycle[$script:cxx % 2]
        add "pack" "/dem/$t.png"
        add "pack" "/osm/$t.pbf"
        add "pack" "/contours/$t.pbf?units=$u"
        if ($k % 4 -eq 0) { add "pack" "/shade/$t.png?hillshade=multi" }
    }
    $opt = $mapOptions[$round % $mapOptions.Count]
    $su = $unitsCycle[$round % 2]
    # T111: 40 is a contour interval in feet only; with seven options the metric rounds met it too and got a 400.
    if ($su -eq "m") { $opt = $opt -replace 'contourInterval=40', 'contourInterval=20' }
    add "other" "/style.json?units=$su$(if ($opt) { "&$opt" })"
    add "other" "/healthz"
    add "other" "/catalog"
    add "other" "/glyphs/Noto%20Sans%20Regular/0-255.pbf"
    add "other" "/sprite.json"
    add "other" "/stats"
    $off = ($round * 7919) % 100000000
    add "other" "/out/$($pack.files[0].file)" @("Range: bytes=$off-$($off + 65535)")
    if ($round % 3 -eq 0) { add "other" "/route?from=$paradise&to=$panorama" @("Authorization: Bearer $userToken") }
    add "other" "/sync/status" @("Authorization: Bearer $userToken")
    if ($round % 5 -eq 0) { add "other" "/sync/pull" @("Authorization: Bearer $userToken") }
    add "other" "/sync/relay/status"
    if ($LiveEvery -gt 0 -and $round % $LiveEvery -eq 0) {
        # 23 steps north (45.6 to 46.9), then the next column 0.1 degrees east: eastern Washington and Oregon.
        $lat = $script:liveLat + ($script:liveStep % 23) * 0.06
        $lon = $script:liveLon + [math]::Floor($script:liveStep / 23) * 0.1
        $script:liveStep++
        $z = 12; $x = Tile-X $lon $z; $y = Tile-Y $lat $z
        add "live" "/dem/$z/$x/$y.png"
        add "live" "/contours/$z/$x/$y.pbf"
        add "live" "/osm/$z/$x/$y.pbf"
        add "live" "/dem/$($z + 1)/$(2 * $x)/$(2 * $y).png"
    }
    return $lines
}

# Relay posts: a well-formed sealed frame (DSEAL v1, purpose relay, random salt and bytes); the server keeps
# it for the TTL without being able to read it, like a phone's.
$frameFile = Join-Path $Root "relay-frame.bin"
$frame = New-Object byte[] 2048
(New-Object System.Random 7).NextBytes($frame)
$magic = [Text.Encoding]::ASCII.GetBytes("DSEAL"); [Array]::Copy($magic, $frame, 5); $frame[5] = 1; $frame[6] = 1
[IO.File]::WriteAllBytes($frameFile, $frame)

# ---------------------------------------------------------------- the loop
$client = @{}
function Reset-Client { $script:client = @{ requests = 0; failures = 0; status = @{}; groups = @{} } }
Reset-Client
function Add-Result([string]$group, [string]$code, [double]$ms) {
    $c = $script:client
    $c.requests++
    $c.status[$code] = 1 + [int]$c.status[$code]
    if ($code -eq "000" -or $code.StartsWith("5")) { $c.failures++ }
    if (-not $c.groups.ContainsKey($group)) { $c.groups[$group] = New-Object System.Collections.ArrayList }
    [void]$c.groups[$group].Add($ms)
}
function Get-Pct($list, [double]$q) { if ($list.Count -eq 0) { return $null }; $s = @($list | Sort-Object); return [math]::Round($s[[math]::Min($s.Count - 1, [int][math]::Ceiling($q * $s.Count) - 1)], 1) }

# The live heap: GC.class_histogram runs a full GC first, so its total is what the heap really holds
# (the robust leak signal; heap-used saws with allocation). Also the five biggest classes.
function Get-LiveHeap([string]$label = "") {
    $jcmd = if ($javaHome) { Join-Path $javaHome ("bin" + $sep + "jcmd") } else { "jcmd" }
    try {
        $h = @(& $jcmd $srvPid GC.class_histogram 2>$null)
        if ($label) { [IO.File]::WriteAllLines((Join-Path $Root "histogram-$label.txt"), [string[]]@($h | Select-Object -First 30)) }
        $total = $h | Where-Object { $_ -match '^Total\s' } | Select-Object -First 1
        $top = @($h | Where-Object { $_ -match '^\s*\d+:\s+\d+\s+\d+\s' } | Select-Object -First 5 | ForEach-Object {
            $f = ($_.Trim() -split '\s+'); [ordered]@{ cls = $f[3]; instances = [long]$f[1]; bytes = [long]$f[2] } })
        if (-not $total) { return $null }
        $tf = $total.Trim() -split '\s+'
        return [ordered]@{ bytes = [long]$tf[2]; instances = [long]$tf[1]; top = $top }
    } catch { return $null }
}

function Get-Sample {
    $live = if ($NoFullGc) { $null } else { Get-LiveHeap }
    $m = Get-JsonUrl "/metrics"
    $stats = Get-JsonUrl "/stats"
    $src = if ($stats) { $stats.sourceDownloads } else { $null }
    $p = Get-Process -Id $srvPid -ErrorAction SilentlyContinue
    $proc = $null
    if ($p) {
        $p.Refresh()
        $proc = [ordered]@{ workingSetBytes = $p.WorkingSet64; privateBytes = $p.PrivateMemorySize64; handles = $p.HandleCount; threads = $p.Threads.Count; cpuSeconds = [math]::Round($p.TotalProcessorTime.TotalSeconds, 1) }
        if (-not $onWindows) {
            $fdDir = "/proc/$srvPid/fd"
            if (Test-Path $fdDir) { $proc.handles = @(Get-ChildItem $fdDir -ErrorAction SilentlyContinue).Count }
            $proc.privateBytes = $p.WorkingSet64
        }
    }
    $groups = [ordered]@{}
    foreach ($g in $script:client.groups.Keys) {
        $l = $script:client.groups[$g]
        $groups[$g] = [ordered]@{ count = $l.Count; p50Ms = (Get-Pct $l 0.5); p95Ms = (Get-Pct $l 0.95); maxMs = (Get-Pct $l 1.0) }
    }
    $logBytes = (Get-ChildItem $logDir -Filter "server.log*" -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
    $s = [ordered]@{ kind = "sample"; ts = (Get-Date).ToUniversalTime().ToString("o"); epochS = [math]::Round(((Get-Date).ToUniversalTime() - [datetime]'1970-01-01').TotalSeconds, 1)
        port = $Port; alive = [bool]($p -and -not $srv.HasExited); process = $proc; metrics = $m; sourceDownloads = $src; liveHeap = $live; logBytesOnDisk = $logBytes; logFiles = @(Get-ChildItem $logDir -Filter "server.log*").Count
        client = [ordered]@{ requests = $script:client.requests; failures = $script:client.failures; status = $script:client.status; groups = $groups } }
    (Write-Json $s) | Add-Content -Encoding utf8 $logFile
    Reset-Client
    return $s
}

function Save-Histogram([string]$label) { [void](Get-LiveHeap $label) }

function Invoke-Probe([switch]$Full) {
    $out = Join-Path $Root ("probe-" + $(if ($Full) { "final" } else { "quick" }) + ".json")
    $a = @("-I", (Join-Path $repo "tools/server-probe.py"), "--port", "$Port", "--token", $userToken, "--user-token", $userToken, "--admin-token", $adminToken, "--out", $out)
    if (-not $Full) { $a += @("--quick", "--no-bomb") }
    & $Python @a | Out-Null
    $code = $LASTEXITCODE
    $r = try { Get-Content $out -Raw | ConvertFrom-Json } catch { $null }
    return @{ type = $(if ($Full) { "probeFinal" } else { "probe" }); exit = $code; ok = ($code -eq 0); unexpected = $r.unexpected; known = @($r.known.PSObject.Properties | ForEach-Object { $_.Name }); probes = $r.probes }
}

$start = Get-Date
$end = $start.AddSeconds($span)
$nextSample = $start
$nextTrip = $start.AddMinutes(1)
$nextBuild = $start.AddMinutes([math]::Min(5, $BuildMinutes))
$nextProbe = $start.AddMinutes([math]::Min(15, $ProbeMinutes))
$nextShare = $start.AddMinutes([math]::Min(30, $ShareMinutes))
$histAt = $start.AddHours(1)
$round = 0
$trips = New-Object System.Collections.ArrayList
$deviceId = [guid]::NewGuid().ToString()
$wall = 0L; $counter = 0
function Get-Hlc { $now = [long]((Get-Date).ToUniversalTime() - [datetime]'1970-01-01').TotalMilliseconds; if ($now -gt $script:wall) { $script:wall = $now; $script:counter = 0 } else { $script:counter++ }; return ("{0:x12}.{1:x6}.{2}" -f $script:wall, $script:counter, $deviceId) }
$tmpBody = Join-Path $Root "trip-ops.json"; $tmpBundle = Join-Path $Root "trip.dsync"
$curlCfg = Join-Path $Root "round.curl"

Save-Histogram "start"
[void](Get-Sample)
$nextSample = $start.AddMinutes($SampleMinutes)
while ((Get-Date) -lt $end) {
    if ($srv.HasExited) { Add-Event @{ type = "serverExited"; exitCode = $srv.ExitCode }; break }
    $round++
    $lines = New-Round $round
    [IO.File]::WriteAllText($curlCfg, (($lines | ForEach-Object { $_.text }) -join "next`n"))
    # curl --parallel reports in completion order, so each line names its URL; the group comes from the URL.
    $res = Invoke-Curl @("--parallel", "--parallel-max", "$Parallel", "-K", $curlCfg)
    $groupOf = @{}
    foreach ($l in $lines) { $groupOf[$l.url] = $l.group }
    $seen = 0
    foreach ($o in @($res | Where-Object { $_ -match '^\d{3} ' })) {
        $parts = $o.Split(' ', 3)
        $g = $groupOf[$parts[2]]
        if (-not $g) { $g = "other" }
        Add-Result $g $parts[0] ([double]$parts[1] * 1000)
        $seen++
    }
    for ($k = $seen; $k -lt $lines.Count; $k++) { Add-Result "other" "000" 0 }
    # relay: two sides of one random slot post, then forget one; the other expires with the TTL
    $slot = [guid]::NewGuid().ToString("N"); $side = [guid]::NewGuid().ToString("N").Substring(0, 16)
    $code = Invoke-Curl @("-o", $(if ($onWindows) { "NUL" } else { "/dev/null" }), "-w", "%{http_code} %{time_total}", "-X", "POST", "--data-binary", "@$frameFile", "$base/sync/relay?slot=$slot&side=$side")
    if ($code) { $pp = "$code".Split(' '); Add-Result "relay" $pp[0] ([double]$pp[1] * 1000) }

    $now = Get-Date
    if ($now -ge $nextTrip) {
        $nextTrip = $now.AddMinutes($TripMinutes)
        # T107: a server with users keeps /route* behind its tokens (T92), so the trip's route and bundle send the
        # soak user's; without it every trip since T92 got a 401 and none was saved or shared.
        $tr = [Diagnostics.Stopwatch]::StartNew()
        $route = Get-JsonUrl "/route?from=$paradise&to=$panorama" $userToken
        $routeMs = $tr.ElapsedMilliseconds
        if ($route -and $route.path) {
            $id = [guid]::NewGuid().ToString()
            $ops = @(
                [ordered]@{ hlc = (Get-Hlc); type = "put"; id = $id; name = "Soak trip $($trips.Count + 1)" },
                [ordered]@{ hlc = (Get-Hlc); type = "set"; id = $id; field = "path"; value = $route.path })
            (Write-Json @{ device = $deviceId; ops = $ops }) | Out-File -Encoding ascii $tmpBody
            $bc = "$(Invoke-Curl @("-o", $tmpBundle, "-w", "%{http_code} %{time_total}", "--max-time", "120", "-X", "POST", "-H", "Authorization: Bearer $userToken", "-H", "Content-Type: application/json", "--data-binary", "@$tmpBody", "$base/route/bundle"))".Split(' ')
            $pc = "$(Invoke-Curl @("-o", $(if ($onWindows) { "NUL" } else { "/dev/null" }), "-w", "%{http_code} %{time_total}", "--max-time", "120", "-X", "POST", "-H", "Authorization: Bearer $userToken", "-H", "Content-Type: application/octet-stream", "--data-binary", "@$tmpBundle", "$base/sync/push"))".Split(' ')
            Add-Result "sync" $pc[0] ([double]$pc[-1] * 1000)
            # T111: every trip is an event with its three timings, so a slow or unanswered request lines up with it.
            Add-Event @{ type = "trip"; ok = ($pc[0] -eq "200"); routeMs = $routeMs; bundle = $bc[0]; bundleMs = [math]::Round([double]$bc[-1] * 1000); push = $pc[0]; pushMs = [math]::Round([double]$pc[-1] * 1000) }
            if ($pc[0] -eq "200") { [void]$trips.Add($id) }
        }
        else { Add-Event @{ type = "tripRoute"; ok = $false; routeMs = $routeMs; error = $(if ($route) { "$($route.error)" } else { "no answer" }) } }
    }
    if ($now -ge $nextShare -and $trips.Count -gt 0) {
        $nextShare = $now.AddMinutes($ShareMinutes)
        $sc = "$(Invoke-Curl @("-o", $(if ($onWindows) { "NUL" } else { "/dev/null" }), "-w", "%{http_code} %{time_total}", "--max-time", "120", "-X", "POST", "-H", "Authorization: Bearer $userToken", "$base/sync/share?route=$($trips[-1])"))".Split(' ')
        Add-Result "sync" $sc[0] ([double]$sc[-1] * 1000)
        Add-Event @{ type = "share"; ok = ($sc[0] -eq "200"); status = $sc[0]; ms = [math]::Round([double]$sc[-1] * 1000) }
    }
    if ($BuildMinutes -gt 0 -and $now -ge $nextBuild) {
        $nextBuild = $now.AddMinutes($BuildMinutes)
        Add-Event (Invoke-Build "soakbuild" "-121.76,46.77,-121.72,46.80" 8 12)
    }
    if ($ProbeMinutes -gt 0 -and $now -ge $nextProbe) {
        $nextProbe = $now.AddMinutes($ProbeMinutes)
        Add-Event (Invoke-Probe)
    }
    if ($now -ge $histAt) { $histAt = $end.AddDays(1); Save-Histogram "1h" }
    if ($now -ge $nextSample) {
        $nextSample = $nextSample.AddMinutes($SampleMinutes)
        $s = Get-Sample
        Write-Host ("{0} live heap {1:N0} MB, ws {2:N0} MB, handles {3}, cache {4:N0} MB, round {5}" -f $s.ts, ((Get-Path $s "liveHeap.bytes") / 1MB), ((Get-Path $s "process.workingSetBytes") / 1MB), (Get-Path $s "process.handles"), ((Get-Path $s "metrics.disk.cacheDir.bytes") / 1MB), $round)
        [void](Write-Chart)
    }
    $sleep = $RoundSeconds - ((Get-Date) - $now).TotalSeconds
    if ($sleep -gt 0) { Start-Sleep -Milliseconds ([int]($sleep * 1000)) }
}

# ---------------------------------------------------------------- the end
[void](Get-Sample)
Save-Histogram "end"
$alive = -not $srv.HasExited
$final = if ($alive) { Invoke-Probe -Full } else { @{ type = "probeFinal"; ok = $false; error = "server not running" } }
Add-Event $final
$aliveAfterProbe = -not $srv.HasExited
# T111: the server's per-route counts once more after the final probe (the last sample comes before it), so the
# unanswered check sees the final probe's requests too.
$requestsAfterProbe = if ($aliveAfterProbe) { (Get-JsonUrl "/metrics").requests } else { $null }
$ooms = @(Select-String -Path (Join-Path $logDir "server.log*") -Pattern "OutOfMemoryError" -SimpleMatch -ErrorAction SilentlyContinue).Count
(Write-Json ([ordered]@{ aliveAtEnd = $alive; aliveAfterProbe = $aliveAfterProbe; finalProbe = $final; events = $events.Count; outOfMemoryLinesInKeptLogs = $ooms; requestsAfterProbe = $requestsAfterProbe })) | Out-File -Encoding utf8 (Join-Path $Root "summary-extra.json")
$summary = Write-Chart
if (-not $srv.HasExited) { Stop-Process -Id $srvPid -Force }
$verdict = Get-Verdict $summary
$summary.verdict = $verdict
(Write-Json $summary) | Out-File -Encoding utf8 (Join-Path $Root "summary.json")
Write-Json $summary
Write-Verdict $verdict
exit $(if ($verdict.ok) { 0 } else { 1 })
