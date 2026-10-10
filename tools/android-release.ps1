# Release build smoke test and timings (T55). Installs an APK (by default the release split for the device's
# ABI), pushes a region pack, glyphs and sprites to the app's external files dir (release builds are not
# debuggable, so android-push-pack.ps1's run-as does not work for them), then opens the map at each view
# through the deep link (dystopo://map?lat=&lon=&z=) and watches the screen with `adb exec-out screencap`
# every -PollMs. No debug route is used, so it drives a release build exactly as a user would.
#
#   powershell -ExecutionPolicy Bypass -File tools\android-release.ps1 -Build -Serial emulator-5674 -Port 8825
#   powershell -ExecutionPolicy Bypass -File tools\android-release.ps1 -Serial emulator-5674 -Apk androidApp\build\outputs\apk\debug\androidApp-debug.apk -Label debug -Runs 3
#
# Per view it reports `am start -W` TotalTime/WaitTime, firstContourMs (launch to the first screencap that
# shows contour-coloured pixels in the map area), firstOsmMs (T63: the first capture showing the OSM layer: water and
# stream blue, which no DEM or contour layer draws; osmLagMs = firstOsmMs - firstContourMs) and settledMs (contours shown and two captures in a row
# agree), and saves the settled capture as <Out>\<Label>-<n>.png: look at them. The first view is a cold
# start (am start -S); the others reuse the running activity (--activity-single-top), which is how a link
# arrives in practice. -Runs N adds N cold starts at the first view for the timing table (run 1 right after
# the install has an empty contour cache, later runs a warm one; T63: -ColdCache empties the app's cache dir, contour,
# cliff and shade tiles, with `pm trim-caches` before every cold start, so each run generates its contours). With -Port the tile server is forwarded
# and probed. T49: every loopback request needs the app's per-launch token, and release builds have no /health
# to hand it out (by design), so without it the tile routes (/style.json, /dem, /contours) must answer 401 and the
# debug routes (/health /stats /camera /screenshot /features) 404; the with-token tile check is skipped (the
# deep-link views above already show the tiles are served to the app's own map). probeOk says whether it held.
# Ends with a logcat check (crash buffer and the app's errors) and prints one JSON object.
param(
    [Parameter(Mandatory = $true)] [string]$Serial,
    [switch]$Build,
    [string]$Apk = '',
    [string]$Label = 'release',
    [int]$Port = 0,
    [string]$Dem = 'out\rainier-dem.pmtiles',
    [string]$Osm = 'out\rainier-outdoors.pmtiles',
    [string]$PackName = 'rainier',
    [switch]$NoPush,
    [switch]$NoInstall,
    # lat,lon,zoom per view. Default: three views of tools/android-tour.txt (summit, Paradise, Sunrise; Rainier pack).
    [string[]]$Views = @('46.853,-121.760,12', '46.786,-121.736,13.5', '46.914,-121.643,12'),
    [int]$Runs = 0,
    [switch]$ColdCache,
    [int]$PollMs = 500,
    [int]$StableMs = 3000,
    [int]$TimeoutS = 60,
    [string]$Out = 'out\release'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repoRoot = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'Android\sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$package = 'io.github.jasondorjeshort.dystopo'
$activity = "$package/io.github.jasondorjeshort.dystopo.app.MainActivity"
$external = "/sdcard/Android/data/$package/files"
function Resolve-RepoPath([string]$p) { if ([IO.Path]::IsPathRooted($p)) { $p } else { Join-Path $repoRoot $p } }
$outDir = Resolve-RepoPath $Out
New-Item -ItemType Directory -Force $outDir | Out-Null

function Adb {
    $ErrorActionPreference = 'Continue'
    $o = & $adb -s $Serial @args 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') failed: $o" }
    $o
}

# Screen captures and their pixel statistics, in C#: `adb exec-out screencap` in raw RGBA (a PNG takes the
# emulator about a second to encode; raw skips that), read through a Process
# stream (a PowerShell pipe would re-encode the bytes as text), and statistics over the pixels.
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
public class DystopoFrame { public int W, H; public int[] Px; }
public static class DystopoShot {
    public static DystopoFrame Capture(string adb, string serial) {
        var psi = new ProcessStartInfo(adb, "-s " + serial + " exec-out screencap");
        psi.UseShellExecute = false; psi.RedirectStandardOutput = true; psi.CreateNoWindow = true;
        byte[] raw;
        using (var p = Process.Start(psi)) using (var ms = new MemoryStream()) {
            p.StandardOutput.BaseStream.CopyTo(ms); p.WaitForExit(); raw = ms.ToArray();
        }
        if (raw.Length < 16) return null;
        int w = BitConverter.ToInt32(raw, 0), h = BitConverter.ToInt32(raw, 4);
        if (w <= 0 || h <= 0 || (long)w * h * 4 > raw.Length) return null;
        int header = raw.Length - w * h * 4; // 12 bytes before Android 9, 16 (with a colour space) after
        var f = new DystopoFrame { W = w, H = h, Px = new int[w * h] };
        for (int i = 0, o = header; i < f.Px.Length; i++, o += 4)
            f.Px[i] = (255 << 24) | (raw[o] << 16) | (raw[o + 1] << 8) | raw[o + 2]; // RGBA -> ARGB
        return f;
    }
    public static System.Threading.Tasks.Task<DystopoFrame> CaptureAsync(string adb, string serial) {
        return System.Threading.Tasks.Task.Run(() => Capture(adb, serial));
    }
    public static void Save(DystopoFrame f, string path) {
        using (var bmp = new Bitmap(f.W, f.H, PixelFormat.Format32bppArgb)) {
            var data = bmp.LockBits(new Rectangle(0, 0, f.W, f.H), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
            try { Marshal.Copy(f.Px, 0, data.Scan0, f.Px.Length); } finally { bmp.UnlockBits(data); }
            bmp.Save(path, ImageFormat.Png);
        }
    }
    // Returns { contourShare, detailShare, changedShare, osmShare } over the map area (top 12% and bottom 8% skipped:
    // status bar, search box, attribution, navigation bar), sampling every 3rd pixel in both directions.
    // contour: the style's contour brown #8c5523 at 0.6-0.85 opacity over paper or hillshade (red well above
    // blue, green between). detail: 40+ away from the most common colour (a blank map is 0). changed: differs
    // from the previous frame by more than 30 (1.0 without one). osm (T63): the OSM layer's water and streams
    // (#a5cbe9 lakes, #4a8fd0 / #5b9bd5 streams and shores: blue well above red, blue at least green); neither
    // the hillshade (warm greys) nor the contours (brown) nor the paper draws any blue.
    public static double[] Stats(DystopoFrame a, DystopoFrame b) {
        int[] pa = a.Px, pb = (b != null && b.W == a.W && b.H == a.H) ? b.Px : null;
        int w = a.W, h = a.H, y0 = h * 12 / 100, y1 = h * 92 / 100;
        var hist = new System.Collections.Generic.Dictionary<int, int>();
        long n = 0, contour = 0, changed = 0, osm = 0;
        for (int y = y0; y < y1; y += 3) for (int x = 0; x < w; x += 3) {
            int p = pa[y * w + x];
            int q = (p >> 4) & 0x0F0F0F;
            int c; hist.TryGetValue(q, out c); hist[q] = c + 1;
            int r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
            if (r - bl >= 60 && r - g >= 25 && g > bl) contour++;
            if (bl - r >= 40 && bl >= g) osm++;
            if (pb != null && Dist(p, pb[y * w + x]) > 30) changed++;
            n++;
        }
        int mode = 0, best = -1;
        foreach (var kv in hist) if (kv.Value > best) { best = kv.Value; mode = kv.Key; }
        int modeColor = (mode << 4) | 0x080808;
        long detail = 0;
        for (int y = y0; y < y1; y += 3) for (int x = 0; x < w; x += 3) if (Dist(pa[y * w + x], modeColor) > 40) detail++;
        return new double[] { contour / (double)n, detail / (double)n, pb == null ? 1.0 : changed / (double)n, osm / (double)n };
    }
    static int Dist(int p, int q) {
        return Math.Abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.Abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.Abs((p & 255) - (q & 255));
    }
}
'@

function Start-Am([string[]]$amArgs) {
    $psi = New-Object Diagnostics.ProcessStartInfo $adb, ("-s $Serial shell am start " + ($amArgs -join ' '))
    $psi.UseShellExecute = $false; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true; $psi.CreateNoWindow = $true
    [Diagnostics.Process]::Start($psi)
}

$contourMin = 0.002   # share of sampled map pixels in contour brown that counts as contours shown
$osmMin = 0.001       # T63: share of sampled map pixels in water/stream blue that counts as the OSM layer shown
$stableMax = 0.002    # share of changed pixels below which two captures agree
# captures must agree for -StableMs to count as settled. 1 s was too short: contours and hillshade can sit
# on screen for over a second before the OSM layer arrives (seen on a cold start).

# Opens one view and watches the screen until the contours are drawn and the captures stop changing.
# Cold: the app is stopped and the home screen shown first, so the clock starts from a screen without the map;
# firstContourMs is the first capture with contours. Warm (a link to the open map): the old view is still on
# screen, so only settledMs is measured, and only after a capture that differs from the old view.
function Open-View([string]$view, [bool]$cold, [string]$name) {
    $lat, $lon, $z = $view -split ','
    # Quoted for the device shell: '&' would end the command there.
    $uri = "'dystopo://map?lat=$lat&lon=$lon&z=$z'"
    $amArgs = @('-W', '-a', 'android.intent.action.VIEW', '-d', $uri, '-n', $activity)
    $baseline = $null; $osmBaseline = $null; $homeFrame = $null; $timeline = @()
    if ($cold) {
        Adb shell am force-stop $package | Out-Null
        if ($ColdCache) { Adb shell pm trim-caches 999G | Out-Null }
        Adb shell input keyevent KEYCODE_HOME | Out-Null
        Start-Sleep -Milliseconds 1500
        $homeFrame = [DystopoShot]::Capture($adb, $Serial)
        if ($homeFrame) { $homeStats = [DystopoShot]::Stats($homeFrame, $null); $baseline = $homeStats[0]; $osmBaseline = $homeStats[3] }
    } else { $amArgs = @('--activity-single-top') + $amArgs }
    # The home screen's wallpaper can hold a little contour brown: contours count from three times that.
    $threshold = [math]::Max($contourMin, 3 * $(if ($null -ne $baseline) { $baseline } else { 0 }))
    # The launcher wallpaper can be very blue (15% of the samples on the API 35 image), so OSM counts only on frames
    # that are the app's (more than half the samples differ from the home screen), from an absolute share, and only
    # after an app frame without any blue (the empty map): the launch splash in between is blue too.
    $osmThreshold = $osmMin; $osmArmed = $false
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $am = Start-Am $amArgs
    $firstContour = $null; $firstOsm = $null; $settled = $null; $stableSince = $null; $moved = $cold
    $prev = $null; $prevT = 0; $i = 0; $stats = $null; $captureMs = 0
    # A capture takes the emulator over a second end to end (mostly the 10 MB transfer), so captures overlap:
    # one starts every -PollMs and they are judged in start order, each stamped with its start time.
    $pending = New-Object System.Collections.Queue
    $nextAt = 0
    while ($null -eq $settled -and ($sw.ElapsedMilliseconds -lt $TimeoutS * 1000 -or $pending.Count -gt 0)) {
        $now = $sw.ElapsedMilliseconds
        if ($now -ge $nextAt -and $now -lt $TimeoutS * 1000 -and $pending.Count -lt 6) {
            $pending.Enqueue(@{ T = $now; Task = [DystopoShot]::CaptureAsync($adb, $Serial) })
            $nextAt = $now + $PollMs
        }
        while ($pending.Count -gt 0 -and $pending.Peek().Task.IsCompleted) {
            $item = $pending.Dequeue()
            $t = $item.T
            $frame = $item.Task.Result
            $captureMs += $sw.ElapsedMilliseconds - $t
            $i++
            if (-not $frame -or $null -ne $settled) { continue }
            $stats = [DystopoShot]::Stats($frame, $prev)
            $hasContours = $stats[0] -ge $threshold
            if ($cold -and $null -eq $firstContour -and $hasContours) { $firstContour = $t }
            $isApp = (-not $homeFrame) -or [DystopoShot]::Stats($frame, $homeFrame)[2] -gt 0.5
            if ($isApp -and $stats[3] -lt $osmThreshold / 2) { $osmArmed = $true }
            if ($cold -and $null -eq $firstOsm -and $osmArmed -and $stats[3] -ge $osmThreshold) { $firstOsm = $t }
            if ($cold) { $timeline += , @($t, [math]::Round($stats[0], 4), [math]::Round($stats[3], 4), [int]$isApp) }
            if ($null -ne $prev -and $stats[2] -gt $stableMax) { $moved = $true; $stableSince = $null }
            elseif ($moved -and $hasContours -and $null -ne $prev) {
                if ($null -eq $stableSince) { $stableSince = $prevT }
                if ($t - $stableSince -ge $StableMs) { $settled = $stableSince }
            }
            $prev = $frame; $prevT = $t
        }
        Start-Sleep -Milliseconds 20
    }
    while ($pending.Count -gt 0) { [void]$pending.Dequeue().Task.Wait(30000) }
    $amOut = $am.StandardOutput.ReadToEnd(); $am.WaitForExit()
    $total = if ($amOut -match 'TotalTime: (\d+)') { [int]$Matches[1] } else { $null }
    $waitT = if ($amOut -match 'WaitTime: (\d+)') { [int]$Matches[1] } else { $null }
    $png = Join-Path $outDir "$name.png"
    if ($prev) { [DystopoShot]::Save($prev, $png) }
    $viewPid = (Adb shell pidof $package | Select-Object -First 1)
    if ($viewPid) { $script:pids[$viewPid.Trim()] = $true }
    [ordered]@{
        view = $view; cold = $cold; amTotalMs = $total; amWaitMs = $waitT
        firstContourMs = $firstContour; firstOsmMs = $firstOsm
        osmLagMs = if ($null -ne $firstOsm -and $null -ne $firstContour) { $firstOsm - $firstContour } else { $null }
        settledMs = $settled; captures = $i
        avgCaptureMs = if ($i) { [math]::Round($captureMs / $i) } else { $null }
        homeContourShare = if ($null -ne $baseline) { [math]::Round($baseline, 4) } else { $null }
        contourShare = if ($stats) { [math]::Round($stats[0], 4) } else { $null }
        osmShare = if ($stats) { [math]::Round($stats[3], 4) } else { $null }
        homeOsmShare = if ($null -ne $osmBaseline) { [math]::Round($osmBaseline, 4) } else { $null }
        timeline = $timeline # cold starts: [captureStartMs, contourShare, osmShare, appFrame] per capture
        detailShare = if ($stats) { [math]::Round($stats[1], 4) } else { $null }
        png = $png; ok = ($null -ne $settled -and $null -ne $waitT -and (-not $cold -or $null -ne $firstContour))
    }
}

if ($Build) {
    Push-Location $repoRoot
    try {
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat :androidApp:assembleRelease --console=plain -q 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'assembleRelease failed' }
    } finally { Pop-Location; $ErrorActionPreference = 'Stop' }
}
$abi = (Adb shell getprop ro.product.cpu.abi | Select-Object -First 1).Trim()
if (-not $Apk) { $Apk = "androidApp\build\outputs\apk\release\androidApp-$abi-release.apk" }
$apkPath = Resolve-RepoPath $Apk
if (-not (Test-Path $apkPath)) { throw "APK not found: $apkPath (build with -Build)" }

$report = [ordered]@{ serial = $Serial; abi = $abi; apk = $Apk; apkBytes = (Get-Item $apkPath).Length; label = $Label; coldCache = [bool]$ColdCache }
if (-not $NoInstall) {
    # Grant the runtime permissions (-g) so no dialog covers the map. A build signed with another key (debug vs
    # release) cannot update in place: uninstall first, which also drops the pushed packs.
    $ErrorActionPreference = 'Continue'
    $inst = & $adb -s $Serial install -r -g $apkPath 2>&1 | ForEach-Object { "$_" }
    if (($inst -join ' ') -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
        & $adb -s $Serial uninstall $package 2>&1 | Out-Null
        $inst = & $adb -s $Serial install -r -g $apkPath 2>&1 | ForEach-Object { "$_" }
        $report.reinstalled = $true
    }
    $ErrorActionPreference = 'Stop'
    if (($inst -join ' ') -notmatch 'Success') { throw "install failed: $inst" }
}

if (-not $NoPush) {
    Adb shell "mkdir -p $external/packs $external/sprites" | Out-Null
    foreach ($pack in @(@{ Src = $Dem; Name = "$PackName-dem.pmtiles" }, @{ Src = $Osm; Name = "$PackName-outdoors.pmtiles" })) {
        $src = Resolve-RepoPath $pack.Src
        if (-not (Test-Path $src)) { throw "pack not found: $src" }
        Adb push $src "$external/packs/$($pack.Name)" | Out-Null
    }
    # Glyphs and sprites as android-push-pack.ps1 prepares them (run it once with the debug build, or devtool sprite).
    $cacheRoot = if ($env:DYSTOPO_CACHE) { $env:DYSTOPO_CACHE } else { Join-Path $repoRoot 'cache' }
    $glyphDir = Join-Path $cacheRoot 'glyphs'
    if (-not (Test-Path (Join-Path $glyphDir 'Noto Sans Regular\0-255.pbf'))) { throw "no glyphs in $glyphDir; run tools\android-push-pack.ps1 once" }
    Adb shell "rm -rf $external/glyphs" | Out-Null
    Adb push $glyphDir "$external/" | Out-Null
    foreach ($f in @('sprite.json', 'sprite.png', 'sprite@2x.json', 'sprite@2x.png')) {
        $sf = Join-Path $repoRoot "out\$f"
        if (-not (Test-Path $sf)) { throw "$sf missing; run ./gradlew :devtool:run --args=sprite" }
        Adb push $sf "$external/sprites/$f" | Out-Null
    }
    # Directories adb creates there belong to shell with mode 2770, which the app cannot read: open them up.
    # (packs/ itself only: the app writes its own files there, which shell may not chmod.)
    Adb shell "chmod a+rwx $external/packs && chmod -R a+rwX $external/glyphs $external/sprites" | Out-Null
    $report.pushed = @(Adb shell "ls $external/packs")
}

Adb logcat -c | Out-Null
$script:pids = @{}
# PowerShell names are case-insensitive: these must not reuse the parameter names ($Views, $Runs).
$viewResults = @()
$n = 0
foreach ($v in $Views) {
    $n++
    $viewResults += Open-View $v ($n -eq 1) "$Label-$n"
}
$report.views = $viewResults
$runResults = @()
for ($r = 1; $r -le $Runs; $r++) { $runResults += Open-View $Views[0] $true "$Label-run$r" }
if ($Runs -gt 0) {
    $report.runs = $runResults
    # T63: medians over the cold-start runs (the host is shared with other emulators, so single runs swing).
    $med = [ordered]@{}
    foreach ($k in @('amTotalMs', 'firstContourMs', 'firstOsmMs', 'osmLagMs', 'settledMs')) {
        $vals = @($runResults | ForEach-Object { $_[$k] } | Where-Object { $null -ne $_ } | Sort-Object)
        $med[$k] = if ($vals.Count) { $vals[[int][math]::Floor(($vals.Count - 1) / 2)] } else { $null }
    }
    $report.runMedians = $med
}

if ($Port -gt 0) {
    Adb forward "tcp:$Port" 'tcp:8686' | Out-Null
    # The /dem tile under the first view at z10.
    $lat, $lon, $z = $Views[0] -split ','
    $zt = 10; $nTiles = [math]::Pow(2, $zt)
    $tx = [math]::Floor(([double]$lon + 180) / 360 * $nTiles)
    $latR = [double]$lat * [math]::PI / 180
    $ty = [math]::Floor((1 - [math]::Log([math]::Tan($latR) + 1 / [math]::Cos($latR)) / [math]::PI) / 2 * $nTiles)
    $probe = [ordered]@{}
    $expect = [ordered]@{}
    $tiles = @('/style.json', "/dem/$zt/$tx/$ty.png", "/contours/12/$([math]::Floor($tx * 4))/$([math]::Floor($ty * 4)).pbf")
    $debug = @('/health', '/stats', '/camera', '/screenshot', '/features')
    foreach ($path in $tiles + $debug) {
        $expect[$path] = if ($tiles -contains $path) { 401 } else { 404 }
        try { $probe[$path] = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 30 "http://127.0.0.1:$Port$path").StatusCode }
        catch { $probe[$path] = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { "$($_.Exception.Message)" } }
    }
    $report.probe = $probe
    $report.probeExpected = $expect
    $report.probeOk = @($expect.Keys | Where-Object { $probe[$_] -ne $expect[$_] }).Count -eq 0
    $report.probeNote = 'no token sent: release has no /health token bootstrap (T49), so tile routes must answer 401 and debug routes 404; the with-token tile check is skipped'
    Write-Host "probe: $($report.probeNote)"
}

# Crash-free: nothing in the crash buffer, and the app's own error lines.
$crash = @(Adb logcat -d -b crash | Where-Object { $_ -match $package })
$errors = @(Adb logcat -d '*:E' | Where-Object { $f = ($_ -split '\s+'); $f.Count -gt 3 -and $script:pids.ContainsKey($f[2]) })
$report.logcat = [ordered]@{ crashLines = $crash.Count; appErrorLines = $errors.Count; appErrors = @($errors | Select-Object -First 20); pids = @($script:pids.Keys) }
$report.ok = (@($viewResults + $runResults | Where-Object { -not $_.ok }).Count -eq 0) -and $crash.Count -eq 0 -and ($Port -le 0 -or $report.probeOk)
$json = $report | ConvertTo-Json -Depth 6
Set-Content -Encoding utf8 (Join-Path $outDir "$Label-summary.json") $json
$json
if (-not $report.ok) { exit 1 }
