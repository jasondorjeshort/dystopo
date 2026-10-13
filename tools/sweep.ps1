# The post-wave sweep as one command (T71). Runs, in order:
#   check        ./gradlew check
#   server       ./gradlew :server:installDist
#   notices      python -I tools/notices.py --check (after check, which writes the licence reports it reads)
#   tour         devtool tour --zooms 9,12 over the public areas of areas.json (never devtool.local.json's)
#   demo-static  demo-check.py --views-file tools/demo-tour.txt against tools/demo-server.py (static page)
#   demo-serve   the same tour against devtool serve (pack mode: the phone style, the Android stand-in)
#   live         demo-check.py against devtool serve --live: one view per elevation source, |src= checked
#   sync-sim     sync-sim with clock skew, crash-restarts and paged pulls, 20 seeds
#   device-boot  android-emu.ps1 -Build on a fresh emulator (own AVD, console port and host port)
#   device-tour  android-check.py --views-file tools/android-tour.txt (twice by default; token from logcat)
#   probe        android-probe.py
#   release      android-release.ps1 -Build -Port: the release smoke tour, tiles 401 / debug 404 without a token
#   first-run    T79: the release build from a fresh install with no pack and no assets, driven only by taps
#                (tools/android-firstrun.py): Settings > Sources with the host's LAN address and the sweep's own
#                server (the standalone server on -BasePort+3, bound to all interfaces), Test shows the catalog count,
#                "Download the map around me" at a mock fix at Rainier, a map with contours; then a second fresh
#                install imports the Rainier pair through the system file picker. Screenshots in first-run\.
# T81, release realism (tools/android-ui.py; everything on the RELEASE build by taps unless noted):
#   routes       (debug build, after the device tour) tools/routing-tour.txt: public routes, distance, ascent, trails
#   search       (debug build) tools/search-tour.txt: each query's expected top hit
#   apk-strings  (no device) every string in the release APK through PrivacyCheck: no private term or local pair
#   features     search, a route kept as a trip, the Routes list, a recorded track, GPX export, sync with the sweep's
#                server, the sync sheet, the Share screen, Settings; no crash
#   upgrade      the previous published release (-PrevRelease, default the latest on GitHub) with a track, a trip,
#                the pack and metric units, then this build installed over it: all of it survives
#   permissions  location denied and allowed from the system dialog, notifications at the first recording
#   background   recording through forced doze with the app in the background
#   network      the emulator throttled, the server killed mid-download and (through a stall proxy) mid-sync,
#                then retries once it is back
#   update       T83, the in-app update: the previous published release with data, then this build over it pointed at
#                a mock releases API (127.0.0.1:-BasePort+7): a lower release quiet, a wrong SHA256SUMS.txt and a
#                debug-signed APK refused, a second build of this commit (versionCode +1, built here) offered on the
#                map and installed through the system installer by taps, data intact; the real GitHub API read only
#   matrix       (-Matrix only) font scale 2.0 and dark mode on the sweep's emulator, then fresh emulators per
#                -MatrixDevices entry (default: the minimum API 26 phone, an API 35 tablet in German), each
#                checked with android-ui.py matrix and deleted afterwards
# The release build is made once with -PdystopoVersion=<describe>-sweep -PdystopoVersionCode=<commit count>, as
# release.yml does, so it installs over the published release (a versionCode 1 build cannot).
# and writes out\sweep-<date>\: steps.tsv, each part's log and JSON, summary.json and sheet.png (a contact
# sheet of the contact sheets) through tools/sweep-summary.py. Exit 1 when any part failed.
#
#   powershell -ExecutionPolicy Bypass -File tools\sweep.ps1                     # everything
#   powershell -ExecutionPolicy Bypass -File tools\sweep.ps1 -NoDevice           # no emulator on this machine
#   powershell -ExecutionPolicy Bypass -File tools\sweep.ps1 -Skip check,tour    # parts by name
#   powershell -ExecutionPolicy Bypass -File tools\sweep.ps1 -Only live          # just these parts
#
# Ports and processes. The script starts its own servers (demo-server.py on -BasePort, serve on +1, serve
# --live on +2) and its own emulator (-Avd, console -EmuPort, debug forward -Port, release probe -Port+1),
# records their PIDs and stops exactly those (taskkill /T on the recorded PID), never by name. It refuses the
# coordinator's ports (8765 for the live viewer, 8686/8687, console 5554) and any host port already listening.
# The default AVD is new each day (dystopo35-sweep-<yyyyMMdd>) and is deleted afterwards (-KeepEmulator keeps it).
#
# Data: the Rainier packs (rainier-dem, rainier-outdoors, rainier-osm .pmtiles). In the main checkout they are
# out\ itself; in a worktree they are copied (never linked) from -DataDir, default the main checkout's out\.
# Only those three files are copied, so packs of other places in the main out\ never reach a worktree.
# DYSTOPO_CACHE defaults to the main checkout's cache\ and JAVA_HOME to the JDK 21 named in docs/reference/machines.md.
param(
    [string]$Date = (Get-Date -Format 'yyyyMMdd-HHmm'),
    [string]$Out = '',
    [switch]$NoDevice,
    [string[]]$Skip = @(),
    [string[]]$Only = @(),
    [string]$Zooms = '9,12',
    [int]$BasePort = 8980,
    [string]$Avd = '',
    [int]$EmuPort = 5640,
    [int]$Port = 8990,
    [string]$AvdHome = '',
    [int]$DeviceTourRuns = 2,
    [switch]$KeepEmulator,
    [string]$DataDir = '',
    # T81: the published release the upgrade part starts from ('' = the latest on GitHub, through gh).
    [string]$PrevRelease = '',
    # T81: the device matrix (slow: one boot per entry). Entries are api:device[:locale].
    [switch]$Matrix,
    [string[]]$MatrixDevices = @('26:pixel_6', '35:pixel_tablet:de-DE'),
    [int]$MatrixEmuPort = 0,
    # T81: known defects (filed follow-ups) the android-ui.py parts report without failing; '' for none.
    [string]$Known = 'overlap:search/Settings',
    # Playwright browser channel for demo-check.py and the sheet ('' = bundled chromium).
    [string]$Channel = 'chrome',
    # One view per elevation source for the live part; labels are public places from areas.json.
    # Comma-separated works too (powershell -File passes an array as one string).
    [string[]]$LiveViews = @(
        '12/46.853/-121.760:rainier|src=pack',
        '12/36.10/-112.11:grand-canyon|src=live-3dep',
        '12/46.537/7.963:jungfrau|src=live-swissalti',
        '12/61.636/8.313:galdhopiggen|src=live-kartverket',
        '12/47.074/12.695:grossglockner|src=live-bev',
        # No |src= here: 3DEP reaches this far into British Columbia and outranks HRDEM in the label.
        '12/49.851/-123.005:garibaldi-hrdem',
        '12/-43.595/170.142:aoraki|src=live-linz',
        '11/27.988/86.925:everest|src=live-copernicus'
    )
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$allParts = @('check', 'server', 'notices', 'tour', 'demo-static', 'demo-serve', 'live', 'sync-sim', 'device-boot', 'device-tour',
    'routes', 'search', 'probe', 'release', 'apk-strings', 'first-run', 'features', 'upgrade', 'permissions', 'background', 'network', 'update', 'matrix')
$deviceParts = @('device-boot', 'device-tour', 'routes', 'search', 'probe', 'release', 'first-run', 'features', 'upgrade', 'permissions',
    'background', 'network', 'update', 'matrix')
$Skip = @($Skip | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$Only = @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$LiveViews = @($LiveViews | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$MatrixDevices = @($MatrixDevices | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
foreach ($p in $Skip + $Only) { if ($allParts -notcontains $p) { throw "unknown part '$p' (parts: $($allParts -join ', '))" } }

# --- environment -------------------------------------------------------------------------------------------
$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
if (-not $env:JAVA_HOME -and (Test-Path $jdk)) { $env:JAVA_HOME = $jdk }
if ($env:JAVA_HOME) { $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH }
$commonDir = (& git rev-parse --path-format=absolute --git-common-dir 2>$null)
$mainCheckout = if ($commonDir) { Split-Path -Parent ($commonDir.Trim() -replace '/', '\') } else { $repo }
if (-not $env:DYSTOPO_CACHE -and (Test-Path (Join-Path $mainCheckout 'cache'))) { $env:DYSTOPO_CACHE = Join-Path $mainCheckout 'cache' }
if (-not $DataDir) { $DataDir = Join-Path $mainCheckout 'out' }
if (-not $Avd) { $Avd = 'dystopo35-sweep-' + (Get-Date -Format 'yyyyMMdd') }
if (-not $AvdHome) {
    if ($env:DYSTOPO_AVD_HOME) { $AvdHome = $env:DYSTOPO_AVD_HOME } elseif (Test-Path 'E:\') { $AvdHome = 'E:\dystopo-avd' }
}
if (-not $Out) { $Out = Join-Path 'out' "sweep-$Date" }
$Out = [IO.Path]::GetFullPath((Join-Path $repo $Out))
New-Item -ItemType Directory -Force $Out | Out-Null
$steps = Join-Path $Out 'steps.tsv'
Set-Content -Encoding utf8 $steps ''
$serial = "emulator-$EmuPort"

# --- ports: never the coordinator's, never one already in use ----------------------------------------------
$reserved = @(8765, 8686, 8687)
$ports = [ordered]@{ 'demo-server' = $BasePort; 'serve' = $BasePort + 1; 'serve-live' = $BasePort + 2; 'firstrun-server' = $BasePort + 3
    'ui-server' = $BasePort + 4; 'network-server' = $BasePort + 5; 'network-proxy' = $BasePort + 6; 'update-mock' = $BasePort + 7 }
if (-not $NoDevice) { $ports['debug-forward'] = $Port; $ports['release-probe'] = $Port + 1 }
$listening = @([System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | ForEach-Object { $_.Port })
foreach ($k in $ports.Keys) {
    $p = $ports[$k]
    if ($reserved -contains $p) { throw "$k port $p is reserved for the coordinator (8765 live viewer, 8686/8687); pick another -BasePort/-Port" }
    if ($listening -contains $p) { throw "$k port $p is already in use; pick another -BasePort/-Port" }
}
if (-not $NoDevice -and $EmuPort -eq 5554) { throw "-EmuPort 5554 is the coordinator's emulator; pick another" }
if (-not $MatrixEmuPort) { $MatrixEmuPort = $EmuPort + 2 }
if ($Matrix -and $MatrixEmuPort -eq 5554) { throw "-MatrixEmuPort 5554 is the coordinator's emulator; pick another" }

$script:bootImplied = $false
function Want([string]$part) {
    if ($part -eq 'device-boot' -and $script:bootImplied) { return -not $NoDevice } # T79: implied by device parts
    if ($part -eq 'matrix' -and -not $Matrix -and $Only -notcontains 'matrix') { return $false } # T81: opt-in
    if ($Only.Count -and $Only -notcontains $part) { return $false }
    if ($Skip -contains $part) { return $false }
    if ($NoDevice -and $deviceParts -contains $part) { return $false }
    $true
}

function Step([string]$name, $code, [double]$seconds, [string]$note = '') {
    $line = "$name`t$code`t$([math]::Round($seconds, 1))`t$($note -replace '[\t\r\n]', ' ')"
    Add-Content -Encoding utf8 $steps $line
    Write-Host ("[sweep] {0,-12} {1,-5} {2,7:N1} s {3}" -f $name, $(if ("$code" -eq '0') { 'ok' } elseif ("$code" -eq 'skip') { 'skip' } else { "exit $code" }), $seconds, $note)
}

function Skipped([string]$name, [string]$why) { Step $name 'skip' 0 $why }

function Quote([string]$a) {
    if ($a -eq '') { return '""' }
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    $a
}

# Runs one program to completion with stdout and stderr in files; returns the exit code. Start-Process
# -Wait would also wait for the Gradle daemon the wrapper leaves behind, so wait on the process itself.
function Run([string]$exe, [string[]]$argv, [string]$stdout, [string]$stderr) {
    $p = Start-Process -FilePath $exe -ArgumentList (($argv | ForEach-Object { Quote $_ }) -join ' ') -NoNewWindow -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WorkingDirectory $repo
    $null = $p.Handle  # makes ExitCode available after the wait
    $p.WaitForExit()
    $p.ExitCode
}

function Part([string]$name, [scriptblock]$body) {
    if (-not (Want $name)) { Skipped $name $(if ($NoDevice -and $deviceParts -contains $name) { 'no device' } elseif ($name -eq 'matrix' -and -not $Matrix) { 'needs -Matrix' } else { 'not selected' }); return $false }
    $t0 = Get-Date
    $note = ''
    try {
        # The body's last output is the exit code (anything it printed before that is ignored).
        $code = @(& $body) | Select-Object -Last 1
    } catch {
        $code = 'error'; $note = "$($_.Exception.Message)"
    }
    Step $name $code ((Get-Date) - $t0).TotalSeconds $note
    "$code" -eq '0'
}

# --- servers we start: PID only ----------------------------------------------------------------------------
$script:servers = @{}
function Start-Server([string]$name, [string]$exe, [string[]]$argv, [int]$port, [string]$readyPath, [int]$timeoutS = 120, [hashtable]$envVars = @{}) {
    $saved = @{}
    foreach ($k in $envVars.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $envVars[$k]) }
    try {
        $p = Start-Process -FilePath $exe -ArgumentList (($argv | ForEach-Object { Quote $_ }) -join ' ') -NoNewWindow -PassThru `
            -RedirectStandardOutput (Join-Path $Out "$name.log") -RedirectStandardError (Join-Path $Out "$name.err.log") -WorkingDirectory $repo
    } finally {
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
    }
    $script:servers[$name] = $p.Id
    $deadline = (Get-Date).AddSeconds($timeoutS)
    while ((Get-Date) -lt $deadline) {
        if ($p.HasExited) { throw "$name exited before answering (see $name.err.log)" }
        try { if ((Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 "http://127.0.0.1:$port$readyPath").StatusCode -eq 200) { return } } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "$name did not answer http://127.0.0.1:$port$readyPath within $timeoutS s"
}

function Stop-Server([string]$name) {
    $id = $script:servers[$name]
    if (-not $id) { return }
    # The recorded PID and its children (devtool.bat -> java), nothing matched by name.
    & taskkill.exe /PID $id /T /F 2>&1 | Out-Null
    $script:servers.Remove($name)
}

# T81: the release APK, built once per sweep with a derived version (as release.yml does), so it installs over the
# published release; android-release.ps1 and android-firstrun.py then use it as it is.
$script:releaseBuilt = $false
function Build-Release {
    if ($script:releaseBuilt) { return }
    $count = "$(& git rev-list --count HEAD 2>$null)".Trim()
    $desc = "$(& git describe --tags --always 2>$null)".Trim() -replace '^v', ''
    $code = Run $gradle @(':androidApp:assembleRelease', "-PdystopoVersion=$desc-sweep", "-PdystopoVersionCode=$count") (Join-Path $Out 'assemble-release.log') (Join-Path $Out 'assemble-release.err.log')
    if ($code -ne 0) { throw "assembleRelease failed (see assemble-release.err.log)" }
    $script:releaseBuilt = $true
}

# T81: a standalone server config on all interfaces (the emulator reaches it at the host's LAN address).
function Server-Config([string]$dir, [int]$port, [string[]]$withPacks = @()) {
    New-Item -ItemType Directory -Force $dir, (Join-Path $dir 'out') | Out-Null
    foreach ($f in $withPacks) { Copy-Item -LiteralPath $f -Destination (Join-Path $dir 'out') -Force }
    $tbHome = @((Join-Path $repo 'tilebuild\build\install\tilebuild'), (Join-Path $mainCheckout 'tilebuild\build\install\tilebuild')) | Where-Object { Test-Path $_ } | Select-Object -First 1
    $cfg = [ordered]@{ bind = '0.0.0.0'; port = $port; cacheDir = $(if ($env:DYSTOPO_CACHE) { $env:DYSTOPO_CACHE } else { Join-Path $repo 'cache' })
        outDir = (Join-Path $dir 'out'); logRequests = $false }
    if ($tbHome) { $cfg.tilebuildHome = $tbHome }
    $path = Join-Path $dir 'server.json'
    $cfg | ConvertTo-Json | Set-Content -Encoding ascii $path
    $path
}

$script:lan = ''
function Lan-Address {
    if ($script:lan) { return $script:lan }
    $script:lan = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object {
            $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and $_.PrefixOrigin -in @('Dhcp', 'Manual') } |
        Sort-Object { if (Get-NetRoute -InterfaceIndex $_.InterfaceIndex -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue) { 0 } else { 1 } } |
        Select-Object -First 1).IPAddress
    if (-not $script:lan) { throw 'no LAN IPv4 address on this host' }
    $script:lan
}

$py = 'python'
$gradle = Join-Path $repo 'gradlew.bat'
$devtool = Join-Path $repo 'devtool\build\install\devtool\bin\devtool.bat'
$meta = [ordered]@{ host = 'windows'; startedAt = (Get-Date).ToString('s'); noDevice = [bool]$NoDevice
    commit = "$(& git rev-parse --short HEAD 2>$null)".Trim() }
$meta | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $Out 'meta.json')

$emulatorStarted = $false
try {
    # --- data ----------------------------------------------------------------------------------------------
    $packs = @('rainier-dem.pmtiles', 'rainier-outdoors.pmtiles', 'rainier-osm.pmtiles')
    $localOut = Join-Path $repo 'out'
    foreach ($f in $packs) {
        $dst = Join-Path $localOut $f
        $src = Join-Path $DataDir $f
        if (-not (Test-Path $dst) -and (Test-Path $src) -and ([IO.Path]::GetFullPath($src) -ne [IO.Path]::GetFullPath($dst))) {
            Copy-Item -LiteralPath $src -Destination $dst
            Write-Host "[sweep] copied $f from $DataDir"
        }
    }
    $missing = @($packs | Where-Object { -not (Test-Path (Join-Path $localOut $_)) })
    if ($missing.Count) { Write-Host "[sweep] missing in out\: $($missing -join ', ') (demo and device parts will fail)" }

    # --- JVM parts ----------------------------------------------------------------------------------------
    $null = Part 'check' { Run $gradle @('check', '--continue') (Join-Path $Out 'check.log') (Join-Path $Out 'check.err.log') }
    $null = Part 'server' { Run $gradle @(':server:installDist') (Join-Path $Out 'server.log') (Join-Path $Out 'server.err.log') }
    $null = Part 'notices' { Run $py @('-I', 'tools/notices.py', '--check') (Join-Path $Out 'notices.log') (Join-Path $Out 'notices.err.log') }

    $needDevtool = @('tour', 'demo-serve', 'live', 'sync-sim') | Where-Object { Want $_ }
    if ($needDevtool) {
        $code = Run $gradle @(':devtool:installDist') (Join-Path $Out 'devtool-install.log') (Join-Path $Out 'devtool-install.err.log')
        if ($code -ne 0) { Write-Host "[sweep] :devtool:installDist failed (exit $code); devtool parts will fail" }
    }

    $null = Part 'tour' {
        # Public areas only: the tour's default also reads devtool.local.json in the main checkout.
        $areas = ((Get-Content -Raw (Join-Path $repo 'areas.json') | ConvertFrom-Json).areas.PSObject.Properties |
            Where-Object { $_.Value.expectedElevation } | ForEach-Object { $_.Name }) -join ','
        Run $devtool @('tour', '--zooms', $Zooms, '--areas', $areas, '--out', (Join-Path $Out 'tour')) (Join-Path $Out 'tour.json') (Join-Path $Out 'tour.err.log')
    }

    $null = Part 'demo-static' {
        Start-Server 'demo-server' $py @('-I', 'tools/demo-server.py', "$BasePort") $BasePort '/tools/demo/'
        try {
            Run $py @('-I', 'tools/demo-check.py', '--channel', $Channel, '--url', "http://localhost:$BasePort/tools/demo/",
                '--views-file', 'tools/demo-tour.txt', '--timeout', '120', '--out', (Join-Path $Out 'demo-static\view.png')) (Join-Path $Out 'demo-static.json') (Join-Path $Out 'demo-static.err.log')
        } finally { Stop-Server 'demo-server' }
    }

    $null = Part 'demo-serve' {
        $sp = $BasePort + 1
        Start-Server 'serve' $devtool @('serve', '--port', "$sp") $sp '/stats' 180 @{ DYSTOPO_SYNC_DB = (Join-Path $Out 'serve-peer.db') }
        try {
            Run $py @('-I', 'tools/demo-check.py', '--channel', $Channel, '--url', "http://localhost:$sp/",
                '--views-file', 'tools/demo-tour.txt', '--timeout', '120', '--out', (Join-Path $Out 'demo-serve\view.png')) (Join-Path $Out 'demo-serve.json') (Join-Path $Out 'demo-serve.err.log')
        } finally { Stop-Server 'serve' }
    }

    $null = Part 'live' {
        $lp = $BasePort + 2
        $viewsFile = Join-Path $Out 'live-views.txt'
        Set-Content -Encoding ascii $viewsFile ($LiveViews -join "`n")
        Start-Server 'serve-live' $devtool @('serve', '--live', '--port', "$lp") $lp '/stats' 180 @{ DYSTOPO_SYNC_DB = (Join-Path $Out 'live-peer.db') }
        try {
            Run $py @('-I', 'tools/demo-check.py', '--channel', $Channel, '--timeout', '120', '--url', "http://localhost:$lp/?live=1",
                '--views-file', $viewsFile, '--out', (Join-Path $Out 'live\view.png')) (Join-Path $Out 'live.json') (Join-Path $Out 'live.err.log')
        } finally { Stop-Server 'serve-live' }
    }

    $null = Part 'sync-sim' {
        Run $devtool @('sync-sim', '--peers', '3', '--ops', '2000', '--seed', '42', '--partitions', '5', '--reorder', '--seeds', '20',
            '--skew-days', '2', '--crashes', '3', '--page', '50') (Join-Path $Out 'sync-sim.json') (Join-Path $Out 'sync-sim.err.log')
    }

    # --- device parts -------------------------------------------------------------------------------------
    $dev = Join-Path $Out 'device'
    New-Item -ItemType Directory -Force $dev | Out-Null
    $emuArgs = @('-ExecutionPolicy', 'Bypass', '-File', 'tools\android-emu.ps1', '-Avd', $Avd, '-EmuPort', "$EmuPort", '-Port', "$Port")
    if ($AvdHome) { $emuArgs += @('-AvdHome', $AvdHome) }
    $dem = Join-Path $localOut 'rainier-dem.pmtiles'
    $osm = Join-Path $localOut 'rainier-outdoors.pmtiles'
    # T79: the device parts need a running emulator. When device-boot is not selected (-Only first-run, -Skip
    # device-boot) and nothing answers on the serial, device-boot runs anyway (and its AVD is stopped and deleted
    # at the end like any the sweep boots); an emulator already running on -EmuPort is used as it is.
    if (-not $NoDevice -and -not (Want 'device-boot') -and @($deviceParts | Where-Object { $_ -ne 'device-boot' -and (Want $_) }).Count) {
        $sdkDir = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'Android\sdk' }
        $state = ''
        $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try { $state = "$(& (Join-Path $sdkDir 'platform-tools\adb.exe') -s $serial get-state 2>$null)".Trim() } catch { } finally { $ErrorActionPreference = $eap }
        if ($state -ne 'device') {
            $script:bootImplied = $true
            Write-Host "[sweep] $serial is not running: device-boot runs first (the device parts selected need it)"
        }
    }
    $booted = Part 'device-boot' {
        $script:emulatorStarted = $true
        # android-emu.ps1 prints nothing when its output is piped (T65 follow-up); its exit code and the
        # logcat line the Python tools read are what count, so the logs are kept only for a post-mortem.
        Run 'powershell.exe' ($emuArgs + @('-Build', '-Dem', $dem, '-Osm', $osm)) (Join-Path $dev 'emu.log') (Join-Path $dev 'emu.err.log')
    }
    $deviceSkipNote = 'emulator did not boot'
    if ($booted -or -not (Want 'device-boot')) {
        $null = Part 'device-tour' {
            $worst = 0
            for ($i = 1; $i -le $DeviceTourRuns; $i++) {
                $c = Run $py @('-I', 'tools/android-check.py', '--port', "$Port", '--serial', $serial, '--views-file', 'tools/android-tour.txt',
                    '--out', (Join-Path $dev "tour$i.png")) (Join-Path $dev "tour$i.stdout.json") (Join-Path $dev "tour$i.err.log")
                if ($c -ne 0) { $worst = $c }
            }
            $worst
        }
        # T81: the routing and search tours, on the debug build the device tour left running.
        $null = Part 'routes' {
            Run $py @('-I', 'tools/android-ui.py', 'routes', '--port', "$Port", '--serial', $serial, '--out', (Join-Path $Out 'ui\routes')) (Join-Path $Out 'routes.json') (Join-Path $Out 'routes.err.log')
        }
        $null = Part 'search' {
            Run $py @('-I', 'tools/android-ui.py', 'search', '--port', "$Port", '--serial', $serial, '--out', (Join-Path $Out 'ui\search')) (Join-Path $Out 'search.json') (Join-Path $Out 'search.err.log')
        }
        $null = Part 'probe' {
            Run $py @('-I', 'tools/android-probe.py', '--port', "$Port", '--serial', $serial) (Join-Path $dev 'probe.json') (Join-Path $dev 'probe.err.log')
        }
        $null = Part 'release' {
            # After the debug parts: it uninstalls the debug build (signed with the other key).
            Build-Release
            Run 'powershell.exe' @('-ExecutionPolicy', 'Bypass', '-File', 'tools\android-release.ps1', '-Serial', $serial,
                '-Port', "$($Port + 1)", '-Dem', $dem, '-Osm', $osm, '-Out', (Join-Path $Out 'release')) (Join-Path $Out 'release.log') (Join-Path $Out 'release.err.log')
        }
        $null = Part 'first-run' {
            # T79: a fresh release install gets a map through the UI alone. The server is the standalone one (devtool
            # serve listens on 127.0.0.1 only), on all interfaces, reached from the emulator at the host's LAN address:
            # that exercises the app's private-range cleartext rule, not the emulator's 10.0.2.2 alias.
            $fp = $BasePort + 3
            $fdir = Join-Path $Out 'first-run'
            New-Item -ItemType Directory -Force $fdir, (Join-Path $fdir 'server-out') | Out-Null
            if (-not (Test-Path (Join-Path $repo 'server\build\install\server\bin\server.bat'))) {
                $c = Run $gradle @(':server:installDist') (Join-Path $fdir 'server-install.log') (Join-Path $fdir 'server-install.err.log')
                if ($c -ne 0) { throw ':server:installDist failed' }
            }
            Build-Release # T81: once per sweep, with the derived version
            $lan = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object {
                    $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and $_.PrefixOrigin -in @('Dhcp', 'Manual') } |
                Sort-Object { if (Get-NetRoute -InterfaceIndex $_.InterfaceIndex -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue) { 0 } else { 1 } } |
                Select-Object -First 1).IPAddress
            if (-not $lan) { throw 'no LAN IPv4 address on this host' }
            $tbHome = @((Join-Path $repo 'tilebuild\build\install\tilebuild'), (Join-Path $mainCheckout 'tilebuild\build\install\tilebuild')) | Where-Object { Test-Path $_ } | Select-Object -First 1
            $cfg = [ordered]@{ bind = '0.0.0.0'; port = $fp; cacheDir = $(if ($env:DYSTOPO_CACHE) { $env:DYSTOPO_CACHE } else { Join-Path $repo 'cache' })
                outDir = (Join-Path $fdir 'server-out'); logRequests = $false }
            if ($tbHome) { $cfg.tilebuildHome = $tbHome }
            $cfgPath = Join-Path $fdir 'server.json'
            $cfg | ConvertTo-Json | Set-Content -Encoding ascii $cfgPath
            Start-Server 'firstrun-server' (Join-Path $repo 'server\build\install\server\bin\server.bat') @('--config', $cfgPath) $fp '/healthz' 180
            try {
                Run $py @('-I', 'tools/android-firstrun.py', '--serial', $serial, '--server', "http://${lan}:$fp", '--out', $fdir,
                    '--import', "$dem,$osm") (Join-Path $Out 'first-run.json') (Join-Path $Out 'first-run.err.log')
            } finally { Stop-Server 'firstrun-server' }
        }

        # --- T81: release realism (tools/android-ui.py) ---------------------------------------------------------
        $ui = Join-Path $Out 'ui'
        $knownArgs = if ($Known) { @('--known', $Known) } else { @() }
        $uiNeedsServer = @('features', 'permissions') | Where-Object { Want $_ }
        if ($uiNeedsServer) {
            try {
                Build-Release
                if (-not (Test-Path (Join-Path $repo 'server\build\install\server\bin\server.bat'))) {
                    $c = Run $gradle @(':server:installDist') (Join-Path $Out 'ui-server-install.log') (Join-Path $Out 'ui-server-install.err.log')
                    if ($c -ne 0) { throw ':server:installDist failed' }
                }
                $cfg = Server-Config (Join-Path $ui 'server') ($BasePort + 4)
                Start-Server 'ui-server' (Join-Path $repo 'server\build\install\server\bin\server.bat') @('--config', $cfg) ($BasePort + 4) '/healthz' 180
                $uiServer = "http://$(Lan-Address):$($BasePort + 4)"
            } catch { Write-Host "[sweep] ui server: $($_.Exception.Message)"; $uiServer = '' }
        }
        try {
            $null = Part 'features' {
                if (-not $uiServer) { throw 'the sweep server for the UI parts did not start' }
                Run $py (@('-I', 'tools/android-ui.py', 'features', '--serial', $serial, '--server', $uiServer, '--dem', $dem, '--osm', $osm,
                        '--out', (Join-Path $ui 'features')) + $knownArgs) (Join-Path $Out 'features.json') (Join-Path $Out 'features.err.log')
            }
            $null = Part 'permissions' {
                if (-not $uiServer) { throw 'the sweep server for the UI parts did not start' }
                Run $py (@('-I', 'tools/android-ui.py', 'permissions', '--serial', $serial, '--server', $uiServer,
                        '--out', (Join-Path $ui 'permissions')) + $knownArgs) (Join-Path $Out 'permissions.json') (Join-Path $Out 'permissions.err.log')
            }
        } finally { Stop-Server 'ui-server' }
        $null = Part 'upgrade' {
            Build-Release
            $prevDir = Join-Path $ui 'previous'
            New-Item -ItemType Directory -Force $prevDir | Out-Null
            $tag = $PrevRelease
            if (-not $tag) { $tag = "$(& gh release list --exclude-drafts --limit 1 --json tagName --jq '.[0].tagName' 2>$null)".Trim() }
            if (-not $tag) { throw 'no previous release (gh release list); pass -PrevRelease' }
            $abi = "$(& (Join-Path $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'Android\sdk' }) 'platform-tools\adb.exe') -s $serial shell getprop ro.product.cpu.abi)".Trim()
            # Alpha releases since v0.1.4 ship only the universal APK (owner's policy 2026-10-11); older ones have per-ABI files too.
            $c = Run 'gh' @('release', 'download', $tag, '-p', "*-$abi.apk", '-p', '*-universal.apk', '-D', $prevDir, '--clobber') (Join-Path $ui 'previous.log') (Join-Path $ui 'previous.err.log')
            $prev = @(Get-ChildItem $prevDir -Filter "*-$abi.apk") + @(Get-ChildItem $prevDir -Filter '*-universal.apk') | Select-Object -First 1
            if ($c -ne 0 -or -not $prev) { throw "could not download the $abi or universal APK of $tag" }
            Run $py (@('-I', 'tools/android-ui.py', 'upgrade', '--serial', $serial, '--prev-apk', $prev.FullName, '--dem', $dem, '--osm', $osm,
                    '--label', $tag, '--out', (Join-Path $ui 'upgrade')) + $knownArgs) (Join-Path $Out 'upgrade.json') (Join-Path $Out 'upgrade.err.log')
        }
        $null = Part 'background' {
            Build-Release
            Run $py (@('-I', 'tools/android-ui.py', 'background', '--serial', $serial, '--dem', $dem, '--osm', $osm,
                    '--out', (Join-Path $ui 'background')) + $knownArgs) (Join-Path $Out 'background.json') (Join-Path $Out 'background.err.log')
        }
        $null = Part 'network' {
            Build-Release
            # The part starts, kills and restarts this server itself (by PID); the phone talks to the stall proxy.
            $cfg = Server-Config (Join-Path $ui 'network-server') ($BasePort + 5) @($dem, $osm)
            Run $py (@('-I', 'tools/android-ui.py', 'network', '--serial', $serial, '--server', "http://$(Lan-Address):$($BasePort + 6)",
                    '--proxy-port', "$($BasePort + 6)", '--server-config', $cfg, '--out', (Join-Path $ui 'network')) + $knownArgs) (Join-Path $Out 'network.json') (Join-Path $Out 'network.err.log')
        }
        $null = Part 'update' {
            # T83: this build (A) and a second build of the same commit with versionCode +1 (B), both with this machine's
            # release key, and the previous published release's universal APK (the start of the user's path).
            Build-Release
            $udir = Join-Path $ui 'update'
            New-Item -ItemType Directory -Force $udir | Out-Null
            $apkDir = Join-Path $repo 'androidApp\build\outputs\apk\release'
            Copy-Item (Join-Path $apkDir 'androidApp-universal-release.apk') (Join-Path $udir 'current.apk') -Force
            $count = [int]"$(& git rev-list --count HEAD 2>$null)".Trim()
            $desc = "$(& git describe --tags --always 2>$null)".Trim() -replace '^v', ''
            $c = Run $gradle @(':androidApp:assembleRelease', "-PdystopoVersion=$desc-sweep-next", "-PdystopoVersionCode=$($count + 1)") (Join-Path $udir 'assemble-next.log') (Join-Path $udir 'assemble-next.err.log')
            if ($c -ne 0) { throw 'assembleRelease of the next build failed (see update\assemble-next.err.log)' }
            Copy-Item (Join-Path $apkDir 'androidApp-universal-release.apk') (Join-Path $udir 'next.apk') -Force
            $tag = $PrevRelease
            if (-not $tag) { $tag = "$(& gh release list --exclude-drafts --limit 1 --json tagName --jq '.[0].tagName' 2>$null)".Trim() }
            if (-not $tag) { throw 'no previous release (gh release list); pass -PrevRelease' }
            $prevDir = Join-Path $udir 'previous'
            New-Item -ItemType Directory -Force $prevDir | Out-Null
            $c = Run 'gh' @('release', 'download', $tag, '-p', '*-universal.apk', '-D', $prevDir, '--clobber') (Join-Path $udir 'previous.log') (Join-Path $udir 'previous.err.log')
            $prev = Get-ChildItem $prevDir -Filter '*-universal.apk' | Select-Object -First 1
            if ($c -ne 0 -or -not $prev) { throw "could not download the universal APK of $tag" }
            Run $py (@('-I', 'tools/android-ui.py', 'update', '--serial', $serial, '--prev-apk', $prev.FullName, '--apk', (Join-Path $udir 'current.apk'),
                    '--new-apk', (Join-Path $udir 'next.apk'), '--mock-port', "$($BasePort + 7)", '--dem', $dem, '--osm', $osm,
                    '--label', $tag, '--out', $udir) + $knownArgs) (Join-Path $Out 'update.json') (Join-Path $Out 'update.err.log')
        }
        $null = Part 'matrix' {
            Build-Release
            $worst = 0
            $mdir = Join-Path $ui 'matrix'
            New-Item -ItemType Directory -Force $mdir | Out-Null
            # On the sweep's own emulator (API 35 phone): system font scale 2.0, then dark mode.
            foreach ($v in @(@('--font-scale', '2.0', '--label', 'phone-font2'), @('--dark', '--label', 'phone-dark'))) {
                $c = Run $py (@('-I', 'tools/android-ui.py', 'matrix', '--serial', $serial, '--out', $mdir) + $v + $knownArgs) (Join-Path $mdir "$($v[-1]).json") (Join-Path $mdir "$($v[-1]).err.log")
                if ($c -ne 0) { $worst = $c }
            }
            # Fresh emulators: one per -MatrixDevices entry, deleted afterwards.
            $i = -1
            foreach ($entry in $MatrixDevices) {
                $i++
                $fields = @($entry -split ':')
                $api = $fields[0]; $devName = $fields[1]; $loc = if ($fields.Count -gt 2) { $fields[2] } else { '' }
                $label = "api$api-$devName" + $(if ($loc) { "-$loc" } else { '' })
                $mAvd = "$Avd-m$i"; $mPort = $MatrixEmuPort + 2 * $i; $mSerial = "emulator-$mPort"
                $mArgs = @('-ExecutionPolicy', 'Bypass', '-File', 'tools\android-emu.ps1', '-Avd', $mAvd, '-EmuPort', "$mPort", '-Port', "$($Port + 2 + $i)",
                    '-Api', $api, '-Device', $devName, '-BootOnly')
                if ([int]$api -lt 30) { $mArgs += @('-Feature', 'GLESDynamicVersion') } # images before 30 offer only GLES 2 otherwise
                if ($loc) { $mArgs += @('-Locale', $loc) }
                if ($AvdHome) { $mArgs += @('-AvdHome', $AvdHome) }
                try {
                    $c = Run 'powershell.exe' $mArgs (Join-Path $mdir "$label-emu.log") (Join-Path $mdir "$label-emu.err.log")
                    if ($c -ne 0) { $worst = $c; continue }
                    $c = Run $py (@('-I', 'tools/android-ui.py', 'matrix', '--serial', $mSerial, '--dem', $dem, '--osm', $osm, '--label', $label, '--out', $mdir) + $knownArgs) (Join-Path $mdir "$label.json") (Join-Path $mdir "$label.err.log")
                    if ($c -ne 0) { $worst = $c }
                } finally {
                    $null = Run 'powershell.exe' ($mArgs[0..7] + @('-Stop', '-Delete') + $(if ($AvdHome) { @('-AvdHome', $AvdHome) } else { @() })) (Join-Path $mdir "$label-stop.log") (Join-Path $mdir "$label-stop.err.log")
                }
            }
            $worst
        }
    } else {
        foreach ($p in @($deviceParts | Where-Object { $_ -ne 'device-boot' })) { if (Want $p) { Step $p 'error' 0 $deviceSkipNote } else { Skipped $p 'not selected' } }
    }
    # T81: the release APK's strings through PrivacyCheck (no device; builds the APK when no device part did).
    $null = Part 'apk-strings' {
        Build-Release
        Run $py @('-I', 'tools/android-ui.py', 'strings', '--apk', (Join-Path $repo 'androidApp\build\outputs\apk\release\androidApp-universal-release.apk'),
            '--out', (Join-Path $Out 'ui\strings')) (Join-Path $Out 'apk-strings.json') (Join-Path $Out 'apk-strings.err.log')
    }
} finally {
    foreach ($name in @($script:servers.Keys)) { Stop-Server $name }
    if ($emulatorStarted -and -not $KeepEmulator) {
        $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'Android\sdk' }
        $adb = Join-Path $sdk 'platform-tools\adb.exe'
        # T79: a forward that was never set (a partial -Only run) makes adb exit 1 with stderr; that must not stop
        # the cleanup, or the AVD stays up and summary.json is never written.
        if (Test-Path $adb) {
            $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            try { & $adb -s $serial forward --remove "tcp:$($Port + 1)" 2>&1 | Out-Null } catch { } finally { $ErrorActionPreference = $eap }
        }
        $code = Run 'powershell.exe' ($emuArgs + @('-Stop', '-Delete')) (Join-Path $dev 'emu-stop.log') (Join-Path $dev 'emu-stop.err.log')
        Write-Host "[sweep] emulator $serial ($Avd) stopped and deleted (exit $code)"
    }
}

# --- summary ---------------------------------------------------------------------------------------------
& $py -I (Join-Path $repo 'tools\sweep-summary.py') $Out --channel $Channel
exit $LASTEXITCODE
