# The post-wave sweep as one command (T71). Runs, in order:
#   check        ./gradlew check (T105: once more after :androidApp:clean with --no-build-cache when a unit-test compile
#                reports Unresolved reference, the stale build state of the 2026-10-14 finding; steps.tsv says so)
#   server       ./gradlew :server:installDist
#   notices      python -I tools/notices.py --check (after check, which writes the licence reports it reads)
#   tour         devtool tour --zooms 9,12 over the public areas of areas.json (never devtool.local.json's)
#   demo-static  demo-check.py --views-file tools/demo-tour.txt against tools/demo-server.py (static page)
#   demo-serve   the same tour against devtool serve (pack mode: the phone style, the Android stand-in)
#   live         demo-check.py against devtool serve --live: one view per elevation source, |src= checked
#   sync-sim     sync-sim with clock skew, crash-restarts and paged pulls, 20 seeds
#   device-boot  android-emu.ps1 -Build on a fresh emulator (own AVD, console port and host port). T105: the debug
#                build's server is the sweep's own (-DeviceServer: live, its serve --live on -BasePort+2 as 10.0.2.2, or
#                direct, none), set before the app's first start and named in steps.tsv; never the build's default 8765
#   device-tour  android-check.py --views-file tools/android-tour.txt --server <the same> (twice by default; token from
#                logcat; T105: the first view waits until the device's load besides the app has settled)
#   probe        android-probe.py
#   startup      (T268, debug build, after bothunits) the launch budget: tools/android-startup.py --launches 5 (cold) over a
#                seeded store (52 synthetic tracks through POST /tracks/import, the Rainier packs), each stage's median of
#                GET /debug/launch, the first frame's and the map drawn's against tools/launch-budget.json: red on a median
#                over its ceiling or a StrictMode kind a stage's declared work does not allow. Report startup.json; the
#                table in summary.json. Runs after the reboot check every device part gets (-EmuRebootGB), and seeds after it.
#   bothunits    (T139, T129's follow-up; T147: in every device sweep, -NoBothUnits leaves it out) android-ui.py bothunits: the scale bar's Both units choice set on
#                the sweep's emulator (debug build) reaches a second one through a sync peer and back. The second emulator
#                is booted on the matrix pattern (AVD <Avd>-b, console -MatrixEmuPort, default -EmuPort+2, debug forward
#                -Port+2, the same debug build) and stopped and deleted afterwards; the peer is the standalone server on
#                -BasePort+3 (all interfaces; 10.0.2.2 to both emulators). A second emulator that does not start is a
#                skip with its reason in steps.tsv and summary.json, not a failure. JSON bothunits.json, screenshots of
#                both phones after each carry in ui\bothunits\.
#   release      android-release.ps1 -Build -Port: the release smoke tour, tiles 401 / debug 404 without a token
#   first-run    T79: the release build from a fresh install with no pack and no assets, driven only by taps
#                (tools/android-firstrun.py): Settings > Sources with the host's LAN address and the sweep's own
#                server (the standalone server on -BasePort+3, bound to all interfaces), Test shows the catalog count,
#                "Download the map around me" at a mock fix at Rainier, a map with contours; then a second fresh
#                install imports the Rainier pair through the system file picker. Screenshots in first-run\.
#                T91: then the same release with no server (android-firstrun.py --direct: the map straight from the
#                public sources at Rainier and Mont Blanc, mobile data only, the phone's own download, offline);
#                its report is first-run-direct.json, screenshots in first-run\direct\.
# T81, release realism (tools/android-ui.py; everything on the RELEASE build by taps unless noted):
#   routes       (debug build, after the device tour) tools/routing-tour.txt: public routes, distance, ascent, trails
#   search       (debug build) tools/search-tour.txt: each query's expected top hit
#   apk-strings  (no device) every string in the release APK through PrivacyCheck: no private term or local pair
#   features     search, a route kept as a trip (T105: the route card with its line and the kept trip's line in
#                screenshots), the Routes list, a recorded track, GPX export, sync with the sweep's server, the sync
#                sheet, the Share screen, Settings; no crash
#   recenter     T105 (T93's follow-up): pan away from the fix, one tap on Recenter, the puck back where it was
#   diagnostics  T154 (T143's follow-up): Settings > About and advanced > Diagnostics on the sweep's release build (the
#                release key where tools\release.properties exists): every line, the max heap equal to dalvik.vm.heapsize,
#                Copy report, Refresh, then after a force-stop "Last session" names how it ended. Screenshots in ui\diagnostics\.
#   upgrade      the previous published release (-PrevRelease, default the latest on GitHub) with a track, a trip,
#                the pack and metric units, then this build installed over it: all of it survives. T91: the
#                downloaded APK is pinned (tag, file and SHA-256 in ui\previous\previous.json) and must match its
#                release's SHA256SUMS.txt, else the part stops (the update part checks its copy the same way).
#   permissions  location denied and allowed from the system dialog, notifications at the first recording
#   background   recording through forced doze with the app in the background
#   network      the emulator throttled, the server killed mid-download and (through a stall proxy) mid-sync,
#                then retries once it is back
#   update       T83, the in-app update: the previous published release with data, then this build over it, both pointed
#                at a mock releases API (127.0.0.1:-BasePort+7): a lower release quiet (this build's start-up check asks
#                the mock although the previous release checked minutes before, T99), a wrong SHA256SUMS.txt and an APK
#                signed with a throwaway key refused, a second build of this commit (versionCode +1, built here) offered
#                on the map and installed through the system installer by taps, data intact; the real GitHub API read
#                only. Screenshots in ui\update\ (on the sheet), the app's DystopoUpdate log in ui\update\updater-log.txt.
#   matrix       (-Matrix only) font scale 2.0 and dark mode on the sweep's emulator, then fresh emulators per
#                -MatrixDevices entry (default: the minimum API 26 phone, an API 35 tablet in German), each
#                checked with android-ui.py matrix and deleted afterwards
# The release build is made once with -PdystopoVersion=<describe>-sweep -PdystopoVersionCode=<commit count>, as
# release.yml does, so it installs over the published release (a versionCode 1 build cannot). T105: its APKs are copied
# to apk\ (apks.json: file, bytes, SHA-256) and every release part installs those copies, since the update part's
# second build overwrites androidApp\build\outputs\apk\release.
# and writes out\sweep-<date>\: steps.tsv, each part's log and JSON, summary.json and sheet.png (a contact
# sheet of the contact sheets) through tools/sweep-summary.py. Exit 1 when any part failed.
# T259: with -Prev <previous sweep folder>, then tools/sweep-diff.py: changes.png (the changed screenshots before and
# after, with the differing pixels in red; -First <screen names> puts the owner's first) and changes.json, and the
# count in summary.json's `changes`. -SummaryOnly -Out <existing sweep folder> -Prev <folder> redoes just those steps.
#
#   powershell -ExecutionPolicy Bypass -File tools\sweep.ps1                     # everything
#   powershell -ExecutionPolicy Bypass -File tools\sweep.ps1 -NoDevice           # no emulator on this machine
#   powershell -ExecutionPolicy Bypass -File tools\sweep.ps1 -Skip check,tour    # parts by name
#   powershell -ExecutionPolicy Bypass -File tools\sweep.ps1 -Only live          # just these parts
#   powershell -ExecutionPolicy Bypass -File tools\sweep.ps1 -NoBothUnits        # no second emulator (T147: bothunits is on by default)
#   powershell -ExecutionPolicy Bypass -File tools\sweep.ps1 -NoDaemon -EmuMemory 3072   # T170: no Gradle daemon left running, 3 GB emulators
#   powershell -ExecutionPolicy Bypass -File tools\sweep.ps1 -PartTimeouts update=2000      # T191: one part's ceiling in seconds
#   powershell -ExecutionPolicy Bypass -File tools\sweep.ps1 -Only server,notices -InjectHang server -PartTimeouts server=30   # T191: test the ceiling
#
# Time limits (T191, finding 2026-10-31: one stuck child once held the sweep 9.5 h). Every part has a ceiling,
# -PartTimeout (900 s) unless the table $ceilings below (device-tour 1200 s a run, device-boot 1500, update 1500, check
# 2400, matrix 2400) or -PartTimeouts says otherwise (the release build has 1800 s of its own, not the part's); the programs a part runs are waited on with WaitForExit(ms) up to its deadline, and
# at the deadline the running program's process tree is killed by its PID (taskkill /PID <pid> /T /F), the part's
# steps.tsv line reads "timeout" with what was killed, and the sweep goes on with the next part. Cleanup after a part
# (stopping its own emulator) has 300 s of its own. Before device-boot (and the bothunits boot) the sweep waits up to
# a minute for the console port pair to be free (a just-killed emulator lets go of it slowly), then fails the part
# naming the PID that holds it; nothing it did not boot is stopped. Every boot goes through tools\android-emu.ps1.
#
# Ports and processes. The script starts its own servers (demo-server.py on -BasePort, serve on +1, serve
# --live on +2, for the live part and then for the debug device parts) and its own emulator (-Avd, console -EmuPort, debug forward -Port, release probe -Port+1),
# records their PIDs and stops exactly those (taskkill /T on the recorded PID), never by name. It refuses the
# coordinator's ports (8765 for the live viewer, 8686/8687, console 5554) and any host port already listening.
# The default AVD is new each day (dystopo35-sweep-<yyyyMMdd>) and is deleted afterwards (-KeepEmulator keeps it).
#
# Data: the Rainier packs (rainier-dem, rainier-outdoors; rainier-osm optional). In the main checkout they are
# out\ itself; in a worktree they are copied (never linked) from -DataDir, default the main checkout's out\.
# Only those files are copied, so packs of other places in the main out\ never reach a worktree.
# DYSTOPO_CACHE defaults to the main checkout's cache\ and JAVA_HOME to the JDK 21 named in docs/reference/machines.md.
param(
    [string]$Date = (Get-Date -Format 'yyyyMMdd-HHmm'),
    # Relative to the checkout only (T154); default out\sweep-<Date>.
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
    # T139: the two-emulator both-units check (a second boot, about two minutes); its emulator takes -MatrixEmuPort, its
    # peer -BasePort+3. T147: part of every device sweep; -NoBothUnits leaves it out, -BothUnits is kept for old command lines.
    [switch]$BothUnits,
    [switch]$NoBothUnits,
    # T170 (T154's follow-up): no Gradle daemon for any build of the sweep, its own and those of the scripts it runs
    # (android-emu.ps1 -Build, android-push-pack.ps1, android-release.ps1): GRADLE_OPTS gains -Dorg.gradle.daemon=false,
    # which every child inherits, so each build runs in a single-use daemon that ends with it.
    [switch]$NoDaemon,
    # T170: the RAM in MB of every emulator the sweep boots (android-emu.ps1 -Memory; the AVD's hw.ramSize too); 0 keeps
    # android-emu.ps1's default (3072, T190).
    [int]$EmuMemory = 0,
    # T233: the sweep keeps its emulator under T231's guard. -EmuLimitGB is the guard's private-bytes limit for every
    # emulator the sweep boots (android-emu.ps1 -PrivateLimitGB; task agents keep policy.json's 8 GB); before each device
    # part (and each device tour run, and first-run's --direct run) an emulator of the sweep's own whose qemu private bytes
    # are past -EmuRebootGB is stopped, left 60 s, and booted again, as is one that is gone. The free-RAM floor is
    # policy.json's, as for every emulator. 0 takes the defaults, measured in T233: a boot ends at 4.4 to 4.8 GB; the
    # largest runs grew it by 3.1 and 3.7 GB (a device tour run) and 3.9 to over 4.0 GB (first-run --direct), every other
    # part by 0 to 1.5 GB. So 6 GB to reboot (a boot plus a GB or so of small parts) and 12 GB for the guard: the threshold
    # plus the largest run plus half of it again (docs/reference/android-emulator.md, The emulator cap).
    [double]$EmuLimitGB = 0,
    [double]$EmuRebootGB = 0,
    # T191 (finding 2026-10-31, a 9.5 h hang): the ceiling in seconds of every part, the wait on its programs done with
    # WaitForExit(ms); at the ceiling the program's process tree is killed by its PID (taskkill /PID <pid> /T /F, never by
    # name), the part's steps.tsv line says "timeout" and names what was killed, and the sweep goes on. -PartTimeouts
    # overrides a part ('name=seconds', comma-separated works); the built-in overrides are below ($ceilings).
    [int]$PartTimeout = 900,
    [string[]]$PartTimeouts = @(),
    # T191, for testing the ceiling only: the named part runs a child process tree that sleeps instead of its body.
    [string]$InjectHang = '',
    # T81: known defects (filed follow-ups) the android-ui.py parts report without failing; '' for none.
    [string]$Known = '', # T91: overlap:search/Settings fixed (the map chips cap their font scale at 1.3)
    # T259: the previous sweep's folder (relative to the checkout, or absolute: it is only read). After the summary,
    # tools/sweep-diff.py pairs every screenshot with its namesake there and writes changes.png and changes.json into
    # -Out; summary.json gets `changes` (the count and the paths). -First names the screens the merge bodies flag for
    # the owner (shown first). -SummaryOnly runs only the summary and the diff over an existing -Out: no part, no build.
    [string]$Prev = '',
    [string[]]$First = @(),
    [switch]$SummaryOnly,
    # Playwright browser channel for demo-check.py and the sheet ('' = bundled chromium).
    [string]$Channel = 'chrome',
    # T105: the server the debug build's device parts use, set in the app before its first start (android-emu.ps1 -Server)
    # and again by the device tour (android-check.py --server): 'live' is the sweep's own devtool serve --live on
    # -BasePort+2 (the emulator's 10.0.2.2), 'direct' is no server (the packs and the public sources). Never the debug
    # build's default, the host's 8765 (the coordinator's live viewer).
    [ValidateSet('live', 'direct')] [string]$DeviceServer = 'live',
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
    'routes', 'search', 'probe', 'bothunits', 'release', 'apk-strings', 'first-run', 'features', 'recenter', 'diagnostics', 'upgrade', 'permissions', 'background', 'network',
    'update', 'matrix')
$deviceParts = @('device-boot', 'device-tour', 'routes', 'search', 'probe', 'bothunits', 'release', 'first-run', 'features', 'recenter', 'diagnostics', 'upgrade', 'permissions',
    'background', 'network', 'update', 'matrix')
$Skip = @($Skip | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$Only = @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$LiveViews = @($LiveViews | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$MatrixDevices = @($MatrixDevices | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
# T268: the launch budget's part, a debug-build device part
$allParts += 'startup'; $deviceParts += 'startup'
# end T268
foreach ($p in $Skip + $Only + @($InjectHang | Where-Object { $_ })) { if ($allParts -notcontains $p) { throw "unknown part '$p' (parts: $($allParts -join ', '))" } }

# T191: the per-part ceilings, from the parts' timings in the sweeps of waves 20 to 23 (steps.tsv of out\sweep-w20,
# -w21, -wave22, -20261031-0254 and its reruns): no other part took more than 322 s (features), so 900 s is the default,
# about 2.8 times the slowest. device-tour took 500 to 565 s for its two runs, so 750 s a run (1500 s for the default two); T275: T264's world views
# took a run to about 650 s (the sweep of fb0a6463 killed its second run at 1500 s), so 1200 s a run (2400 s for two);
# update took 230 to 513 s and builds a second release APK inside the part, which alone takes minutes under load: 1500 s;
# check took 27 to 246 s warm, but a fresh worktree compiles everything and the part may run check twice (the
# stale-compile retry after :androidApp:clean): 2400 s; matrix has no timing on record and boots two fresh emulators
# (one an API 26 image) around three runs: 2400 s. device-boot took 123 to 159 s in those sweeps, but it builds the debug
# APK, boots and waits up to five minutes for the app inside android-emu.ps1: 786 s in T191's run on the loaded second
# PC (a 226 s boot and the whole style wait), so 1500 s. Code outside a part (the devtool build, the cleanup) gets 1800 s,
# and the release build its own 1800 s wherever it runs (Build-Release).
if ($PartTimeout -lt 1) { throw '-PartTimeout must be a number of seconds' }
$ceilings = @{ 'device-tour' = 1200 * [math]::Max(1, $DeviceTourRuns); 'update' = 1500; 'check' = 2400; 'matrix' = 2400; 'device-boot' = 1500 }
$releaseBuildS = 1800
foreach ($kv in @($PartTimeouts | ForEach-Object { $_ -split ',' } | Where-Object { $_ })) {
    if ($kv -notmatch '^\s*([\w-]+)\s*=\s*(\d+)\s*$' -or $allParts -notcontains $Matches[1] -or [int]$Matches[2] -lt 1) {
        throw "-PartTimeouts entries are part=seconds with a known part (got '$kv')"
    }
    $ceilings[$Matches[1]] = [int]$Matches[2]
}
function Part-Ceiling([string]$name) { if ($ceilings.ContainsKey($name)) { $ceilings[$name] } else { $PartTimeout } }
$outsideTimeoutS = 1800

# --- environment -------------------------------------------------------------------------------------------
foreach ($jdk in @('C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot', 'C:\Program Files\Java\jdk-21')) { # the two PCs (machines.md)
    if (-not $env:JAVA_HOME -and (Test-Path $jdk)) { $env:JAVA_HOME = $jdk }
}
if ($NoDaemon -and "$env:GRADLE_OPTS" -notmatch 'org\.gradle\.daemon=false') { $env:GRADLE_OPTS = ("$env:GRADLE_OPTS -Dorg.gradle.daemon=false").Trim() } # T170
if ($EmuMemory -lt 0) { throw '-EmuMemory must be a size in MB, or 0 for the default' }
$emuMemoryArgs = @(if ($EmuMemory -gt 0) { '-Memory'; "$EmuMemory" }) # T170: every emulator the sweep boots (an empty array, never null)
# T233: the guard's limit for every emulator the sweep boots, and the reboot threshold (the param block says why).
if ($EmuLimitGB -lt 0 -or $EmuRebootGB -lt 0) { throw '-EmuLimitGB and -EmuRebootGB are sizes in GB, or 0 for the defaults' }
if (-not $EmuLimitGB) { $EmuLimitGB = 12 }
if (-not $EmuRebootGB) { $EmuRebootGB = 6 }
if ($EmuRebootGB -ge $EmuLimitGB) { throw "-EmuRebootGB ($EmuRebootGB) must be under -EmuLimitGB ($EmuLimitGB)" }
$emuMemoryArgs += @('-PrivateLimitGB', "$EmuLimitGB")
if ($env:JAVA_HOME) { $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH }
$commonDir = (& git rev-parse --path-format=absolute --git-common-dir 2>$null)
$mainCheckout = if ($commonDir) { Split-Path -Parent ($commonDir.Trim() -replace '/', '\') } else { $repo }
if (-not $env:DYSTOPO_CACHE -and (Test-Path (Join-Path $mainCheckout 'cache'))) { $env:DYSTOPO_CACHE = Join-Path $mainCheckout 'cache' }
if (-not $DataDir) { $DataDir = Join-Path $mainCheckout 'out' }
if (-not $Avd) { $Avd = 'dystopo35-sweep-' + (Get-Date -Format 'yyyyMMdd') }
if (-not $AvdHome) {
    # T191: the folder, not the drive: on the second PC E: is a small drive the AVDs must not fill (machines.md).
    if ($env:DYSTOPO_AVD_HOME) { $AvdHome = $env:DYSTOPO_AVD_HOME } elseif (Test-Path 'E:\dystopo-avd') { $AvdHome = 'E:\dystopo-avd' }
}
if (-not $Out) { $Out = Join-Path 'out' "sweep-$Date" }
# T154 (T147's follow-up): -Out is a folder under the checkout, given relative to it; an absolute path used to throw
# inside GetFullPath (Join-Path glued it onto the repo path), and a folder outside the checkout is refused the same way.
if ([IO.Path]::IsPathRooted($Out)) { throw "-Out must be relative to the checkout (such as out\sweep-w21), not '$Out'" }
$Out = [IO.Path]::GetFullPath((Join-Path $repo $Out))
if (-not $Out.StartsWith($repo.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "-Out must stay inside the checkout ($repo), not '$Out'" }
# T259: the summary (sweep-summary.py), then with -Prev the before-and-after sheet (sweep-diff.py, which adds `changes`
# to summary.json); the sweep's exit code is the summary's, never the diff's.
$First = @($First | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
if ($Prev) {
    $Prev = if ([IO.Path]::IsPathRooted($Prev)) { [IO.Path]::GetFullPath($Prev) } else { [IO.Path]::GetFullPath((Join-Path $repo $Prev)) }
    if (-not (Test-Path -LiteralPath $Prev -PathType Container)) { throw "-Prev is not a folder: '$Prev'" }
}
function Write-Summary {
    & python -I (Join-Path $repo 'tools\sweep-summary.py') $Out --channel $Channel | Out-Host
    $code = $LASTEXITCODE
    if ($Prev) {
        $diffArgs = @('-I', (Join-Path $repo 'tools\sweep-diff.py'), $Prev, $Out, '--channel', $Channel)
        if ($First.Count) { $diffArgs += @('--first', ($First -join ',')) }
        & python @diffArgs | Out-Host
        if ($LASTEXITCODE -ne 0) { Write-Host "[sweep] sweep-diff.py failed (exit $LASTEXITCODE); no changes.png" }
    }
    $code
}
if ($SummaryOnly) {
    if (-not (Test-Path -LiteralPath (Join-Path $Out 'steps.tsv'))) { throw "-SummaryOnly needs an existing sweep folder as -Out (no steps.tsv in '$Out')" }
    exit (Write-Summary)
}
New-Item -ItemType Directory -Force $Out | Out-Null
$steps = Join-Path $Out 'steps.tsv'
# T99: steps.tsv is written through Write-Steps (below), never Add-Content: Add-Content opens the file without read
# sharing, so a reader holding it open (tail -F, an editor) made every append throw and the whole sweep die
# (2026-10-14). Lines are also kept in memory; a line that could not be written is written with the rest at the end.
$script:stepLines = New-Object System.Collections.Generic.List[string]
$script:stepsBehind = $false
function Write-Steps([string[]]$lines, [switch]$Rewrite) {
    $mode = if ($Rewrite) { [IO.FileMode]::Create } else { [IO.FileMode]::Append }
    for ($i = 0; $i -lt 20; $i++) {
        try {
            $fs = [IO.FileStream]::new($steps, $mode, [IO.FileAccess]::Write, ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
            try {
                $bytes = [Text.Encoding]::UTF8.GetBytes((@($lines | ForEach-Object { "$_`r`n" }) -join ''))
                $fs.Write($bytes, 0, $bytes.Length)
            } finally { $fs.Dispose() }
            return $true
        } catch { Start-Sleep -Milliseconds 250 }
    }
    Write-Host "[sweep] could not write $steps after 20 tries; its lines are kept and written at the end"
    $false
}
$null = Write-Steps @() -Rewrite
$serial = "emulator-$EmuPort"

# --- ports: never the coordinator's, never one already in use ----------------------------------------------
$reserved = @(8765, 8686, 8687)
$ports = [ordered]@{ 'demo-server' = $BasePort; 'serve' = $BasePort + 1; 'serve-live' = $BasePort + 2; 'firstrun-server' = $BasePort + 3
    'ui-server' = $BasePort + 4; 'network-server' = $BasePort + 5; 'network-proxy' = $BasePort + 6; 'update-mock' = $BasePort + 7 }
if (-not $NoDevice) { $ports['debug-forward'] = $Port; $ports['release-probe'] = $Port + 1; $ports['bothunits-forward'] = $Port + 2 }
if (-not $NoDevice -and $EmuPort -eq 5554) { throw "-EmuPort 5554 is the coordinator's emulator; pick another" }
if (-not $MatrixEmuPort) { $MatrixEmuPort = $EmuPort + 2 }
if (($Matrix -or -not $NoBothUnits) -and -not $NoDevice -and $MatrixEmuPort -eq 5554) { throw "-MatrixEmuPort 5554 is the coordinator's emulator; pick another" }
if ($BothUnits -and $NoBothUnits) { throw '-BothUnits and -NoBothUnits together' }

$script:bootImplied = $false
function Want([string]$part) {
    if ($part -eq 'device-boot' -and $script:bootImplied) { return -not $NoDevice } # T79: implied by device parts
    if ($part -eq 'matrix' -and -not $Matrix -and $Only -notcontains 'matrix') { return $false } # T81: opt-in
    if ($part -eq 'bothunits' -and $NoBothUnits) { return $false } # T139, T147: on unless -NoBothUnits (a second emulator)
    if ($Only.Count -and $Only -notcontains $part) { return $false }
    if ($Skip -contains $part) { return $false }
    if ($NoDevice -and $deviceParts -contains $part) { return $false }
    $true
}

# T105: only the ports of the parts that will run are checked, so a -BasePort range may hold a reserved or busy port that
# no selected part uses (-BasePort 8760 puts the network part's server on 8765, the coordinator's viewer).
$portParts = @{ 'demo-server' = @('demo-static'); 'serve' = @('demo-serve'); 'firstrun-server' = @('first-run', 'bothunits')
    'serve-live' = @('live') + $(if ($DeviceServer -eq 'live') { $deviceParts } else { @() })
    'ui-server' = @('features', 'permissions'); 'network-server' = @('network'); 'network-proxy' = @('network'); 'update-mock' = @('update')
    'debug-forward' = $deviceParts; 'release-probe' = $deviceParts; 'bothunits-forward' = @('bothunits') }
# T191: whether a TCP port is taken. The listener table first; then a bind of the wildcard address with exclusive use,
# which fails while any socket holds the port: inside the agents' sandbox the table leaves out listeners (a listener
# held by python on 127.0.0.1, even the asking process's own, was missing from GetActiveTcpListeners, netstat and
# Get-NetTCPConnection alike on 2026-10-31), so the table alone said free and the boot went on to fail.
function Test-PortHeld([int]$p) {
    if (@([System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq $p }).Count) { return $true }
    $sock = [Net.Sockets.Socket]::new([Net.Sockets.AddressFamily]::InterNetwork, [Net.Sockets.SocketType]::Stream, [Net.Sockets.ProtocolType]::Tcp)
    try { $sock.ExclusiveAddressUse = $true; $sock.Bind([Net.IPEndPoint]::new([Net.IPAddress]::Any, $p)); $false } catch { $true } finally { $sock.Close() }
}

foreach ($k in $ports.Keys) {
    if (-not @($portParts[$k] | Where-Object { Want $_ }).Count) { continue }
    $p = $ports[$k]
    if ($reserved -contains $p) { throw "$k port $p is reserved for the coordinator (8765 live viewer, 8686/8687); pick another -BasePort/-Port" }
    if (Test-PortHeld $p) { throw "$k port $p is already in use; pick another -BasePort/-Port" } # T191: the table or a bind
}

function Step([string]$name, $code, [double]$seconds, [string]$note = '') {
    $line = "$name`t$code`t$([math]::Round($seconds, 1))`t$($note -replace '[\t\r\n]', ' ')"
    $script:stepLines.Add($line)
    if ($script:stepsBehind) { $script:stepsBehind = -not (Write-Steps $script:stepLines.ToArray() -Rewrite) }
    else { $script:stepsBehind = -not (Write-Steps @($line)) }
    Write-Host ("[sweep] {0,-12} {1,-5} {2,7:N1} s {3}" -f $name, $(if ("$code" -eq '0') { 'ok' } elseif ("$code" -eq 'skip') { 'skip' } else { "exit $code" }), $seconds, $note)
}

function Skipped([string]$name, [string]$why) { Step $name 'skip' 0 $why }

function Quote([string]$a) {
    if ($a -eq '') { return '""' }
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    $a
}

# T191: kills one process and its descendants by the recorded PID (taskkill /T walks the tree from it; nothing is
# matched by name). Returns the PIDs taskkill reported terminated, for the steps.tsv note.
function Stop-Tree([int]$id) {
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $out = @(& taskkill.exe /PID $id /T /F 2>&1 | ForEach-Object { "$_" })
    } catch { $out = @("taskkill failed: $($_.Exception.Message)") } finally { $ErrorActionPreference = $eap }
    $killed = @($out | ForEach-Object { if ($_ -match 'PID (\d+)') { $Matches[1] } })
    if (-not $killed.Count) { try { (Get-Process -Id $id -ErrorAction Stop).Kill(); $killed = @("$id") } catch { } }
    $killed -join ', '
}

# T191: the part running now: its ceiling and its deadline (set by Part), and the note of a part that hit it.
$script:partDeadline = $null
$script:partCeiling = 0
$script:partTimedOut = ''

# Runs one program to completion with stdout and stderr in files; returns the exit code. Start-Process
# -Wait would also wait for the Gradle daemon the wrapper leaves behind, so wait on the process itself.
# T191: the wait is bounded, by the part's deadline inside a part (-TimeoutS shortens it), by -TimeoutS alone for a
# -Cleanup call (it runs after a part's deadline too: stopping the part's own emulator), and by $outsideTimeoutS
# elsewhere. At the deadline the program's process tree is killed by its PID; at a part's deadline Run throws and the
# part ends with "timeout" on its steps.tsv line; at its own -TimeoutS it returns 'timeout'.
function Run([string]$exe, [string[]]$argv, [string]$stdout, [string]$stderr, [int]$TimeoutS = 0, [switch]$Cleanup) {
    $own = if ($TimeoutS -gt 0) { (Get-Date).AddSeconds($TimeoutS) } elseif ($Cleanup -or -not $script:partDeadline) { (Get-Date).AddSeconds($outsideTimeoutS) } else { $null }
    $byPart = (-not $Cleanup) -and $script:partDeadline -and (-not $own -or $script:partDeadline -le $own)
    $deadline = if ($byPart) { $script:partDeadline } else { $own }
    $p = Start-Process -FilePath $exe -ArgumentList (($argv | ForEach-Object { Quote $_ }) -join ' ') -NoNewWindow -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WorkingDirectory $repo
    $null = $p.Handle  # makes ExitCode available after the wait
    # T233: while a device part runs, the wait is cut into 2 s slices (the guard reads every 2 s too) and the emulator's private bytes read between them.
    while ($true) {
        $ms = [math]::Max(0, [math]::Min([double][int]::MaxValue, ($deadline - (Get-Date)).TotalMilliseconds))
        $slice = if ($script:emuWatch) { [math]::Min($ms, 2000) } else { $ms }
        if ($p.WaitForExit([int]$slice)) { return $p.ExitCode }
        if ($slice -ge $ms) { break }
        if (-not $Cleanup -and (Test-EmuLost)) {
            $killed = Stop-Tree $p.Id
            $null = $p.WaitForExit(30000)
            throw "$($script:emuLost); $([IO.Path]::GetFileName($exe)) (PID $($p.Id)) killed by PID (taskkill /T: $(if ($killed) { $killed } else { 'none reported' }))"
        }
    }
    $killed = Stop-Tree $p.Id
    $gone = $p.WaitForExit(30000)
    $what = "$([IO.Path]::GetFileName($exe)) (PID $($p.Id)) and its process tree killed by PID (taskkill /T: $(if ($killed) { $killed } else { 'none reported' })$(if (-not $gone) { '; still running 30 s later' }))"
    if ($byPart) {
        $script:partTimedOut = "timed out at its ceiling of $($script:partCeiling) s: $what"
        throw $script:partTimedOut
    }
    Write-Host "[sweep] $([IO.Path]::GetFileName($exe)) did not finish within its time: $what"
    'timeout'
}

# T191: the trimmed stdout of a short program (adb, gh) under a bounded wait; '' when it failed or ran out of time.
$script:toolRuns = 0
function Tool-Text([string]$exe, [string[]]$argv, [int]$TimeoutS = 60, [switch]$Cleanup) {
    $dir = Join-Path $Out 'tool'
    New-Item -ItemType Directory -Force $dir | Out-Null
    $script:toolRuns++
    $o = Join-Path $dir "$($script:toolRuns).out.log"
    $code = Run $exe $argv $o (Join-Path $dir "$($script:toolRuns).err.log") -TimeoutS $TimeoutS -Cleanup:$Cleanup
    if ("$code" -ne '0' -or -not (Test-Path $o)) { return '' }
    "$(Get-Content -Raw $o)".Trim()
}

# T191: who holds a console port pair, for a message: the listener table's owners, else (the sandbox hides them there)
# the processes whose command line names "-port <console port>", which is how an emulator is started. Read only:
# nothing is ever stopped by this (CLAUDE.md: never stop a process found by name or command line).
function Describe-PortHolder([int[]]$ports, [int]$consolePort) {
    $ids = @(foreach ($port in $ports) { Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | ForEach-Object { $_.OwningProcess } })
    $ids = @($ids | Where-Object { $_ } | Sort-Object -Unique)
    if (-not $ids.Count) {
        $ids = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -match "(^|\s)-port\s+$consolePort(\s|$)" } | ForEach-Object { $_.ProcessId })
    }
    $who = @($ids | ForEach-Object { $n = (Get-Process -Id $_ -ErrorAction SilentlyContinue).ProcessName; "PID $_$(if ($n) { " ($n)" })" })
    if ($who.Count) { $who -join ', ' } else { 'a process the listener table does not show' }
}

# T191 (finding 2026-10-31: a rerun lost device-boot to the killed run's console ports, "ports 5640/5641 are in use",
# 35 s in): before a boot, waits up to $timeoutS (50 s, so the part ends within a minute) for the console port pair
# (<port>, <port>+1) to be free and for adb to list no emulator-<port>. Returns '' then, or at once when that serial already runs this very AVD (android-emu.ps1
# reuses it); otherwise the reason, naming the serial adb lists and each holder's PID. The caller boots nothing then,
# and stops nothing it did not start.
function Wait-ConsolePorts([int]$emuPort, [string]$avdName, [int]$timeoutS = 50) {
    $pair = @($emuPort, ($emuPort + 1))
    $s = "emulator-$emuPort"
    $t0 = Get-Date
    while ($true) {
        $listed = ''
        if ((Tool-Text $adbExe @('devices') 30) -match "(?m)^$([regex]::Escape($s))\s+(\S+)") {
            $listed = $Matches[1]
            if ($listed -eq 'device') {
                $name = @((Tool-Text $adbExe @('-s', $s, 'emu', 'avd', 'name') 30) -split "`r?`n")[0].Trim()
                if ($name -eq $avdName) { return '' }
                if ($name) { $listed = "device, AVD $name" }
            }
        }
        $held = @($pair | Where-Object { Test-PortHeld $_ })
        $waited = ((Get-Date) - $t0).TotalSeconds
        if (-not $held.Count -and -not $listed) {
            if ($waited -ge 1) { Write-Host ("[sweep] console ports {0} free after {1:N0} s" -f ($pair -join '/'), $waited) }
            return ''
        }
        if ($waited -ge $timeoutS) { break }
        Start-Sleep -Seconds 2
    }
    $parts = @(if ($held.Count) { "$($held -join ' and ') held by $(Describe-PortHolder $pair $emuPort)" })
    if ($listed) { $parts += "adb lists $s ($listed)" }
    "console ports $($pair -join '/') not free after $timeoutS s ($($parts -join '; ')); not booting $s on them"
}

# --- T233: the sweep's emulator under T231's guard ----------------------------------------------------------
# The qemu PID comes from the emulator's lock (emulators.lockDir of .claude/hooks/policy.json, emulator-<EmuPort>.json),
# checked to still run an emulator image, never found by name. While a device part runs, Run reads its private bytes
# every 2 s; the part's steps.tsv line gets the figures and device\emu-private.tsv a row. Before each device part (and
# each device tour run after the first) an emulator the sweep booted is stopped and booted again when its private bytes
# are past -EmuRebootGB, or when it is gone (its guard or a hand stopped it); a part whose emulator went away while it
# ran is killed after 20 s and ends failed, with the guard's line when the guard stopped it.
$emuImage = '^(qemu-system-[\w.-]+|emulator\d*)(\.exe)?$'
function Get-EmuLockFile {
    $dir = '~/.dystopo/emulators'
    try { $e = (Get-Content -Raw (Join-Path $repo '.claude\hooks\policy.json') | ConvertFrom-Json).emulators; if ($e -and $e.lockDir) { $dir = [string]$e.lockDir } } catch { }
    if ($dir -eq '~' -or $dir -match '^~[\\/]') { $dir = Join-Path $env:USERPROFILE $dir.Substring(1).TrimStart('\', '/') }
    Join-Path $dir "emulator-$EmuPort.json"
}
$emuLockFile = Get-EmuLockFile
function Format-Gb($bytes) { if ($null -eq $bytes) { 'none' } else { '{0:n1} GB' -f ($bytes / 1GB) } }
$script:emuWatch = $false     # a device part runs: Run samples
$script:emuSeen = $false      # the emulator ran at some point of this part
$script:emuPeak = 0.0         # this part's peak private bytes
$script:emuRunPeak = 0.0      # the device tour run's peak
$script:emuGoneSince = $null
$script:emuLost = ''          # why this part lost its emulator, for its steps.tsv line
$script:emuBroken = ''        # a reboot failed: the device parts after it fail with this
$script:emuReboots = 0
$script:emuPhase = 'debug'    # 'release' from the release part on: a reboot then boots with -BootOnly
# The sweep's emulator's qemu private bytes, or $null when it does not run. Updates the peaks while a part watches.
function Read-EmuPrivate {
    $l = $null
    try { $l = Get-Content -Raw -LiteralPath $emuLockFile -ErrorAction Stop | ConvertFrom-Json } catch { }
    if (-not $l -or -not $l.pid -or $l.serial -ne $serial -or $l.avd -ne $Avd) { return $null }
    $p = Get-Process -Id ([int]$l.pid) -ErrorAction SilentlyContinue
    if (-not $p -or "$($p.ProcessName).exe" -notmatch $emuImage) { return $null }
    $b = [double]$p.PrivateMemorySize64
    if ($script:emuWatch) {
        $script:emuSeen = $true
        if ($b -gt $script:emuPeak) { $script:emuPeak = $b }
        if ($b -gt $script:emuRunPeak) { $script:emuRunPeak = $b }
    }
    $b
}
# The guard's log of the sweep's AVD (android-emu.ps1 writes it in this checkout's out\) and its stop lines since $skip.
function Get-GuardLog { Join-Path $repo "out\emulator-guard-$Avd.log" }
function Get-GuardLineCount { $f = Get-GuardLog; if (Test-Path $f) { @(Get-Content $f).Count } else { 0 } }
function Get-GuardStops([int]$skip) {
    $f = Get-GuardLog
    if (-not (Test-Path $f)) { return @() }
    @(Get-Content $f | Select-Object -Skip $skip | Where-Object { $_ -match "guard pid \d+: $([regex]::Escape($serial)) \(" } |
        ForEach-Object { ($_ -replace '^\S+\s+', '').Trim() })
}
# Called by Run between its 2 s slices: once the emulator a part was using is gone for 20 s, the part has lost it.
function Test-EmuLost {
    if (-not $script:emuWatch -or $script:emuLost) { return $false }
    $b = Read-EmuPrivate
    if (-not $script:emuSeen -or $script:emuWatch -eq 'boot') { return $false } # device-boot: android-emu.ps1 reports its own
    if ($null -ne $b) { $script:emuGoneSince = $null; return $false }
    if (-not $script:emuGoneSince) { $script:emuGoneSince = Get-Date; return $false }
    if (((Get-Date) - $script:emuGoneSince).TotalSeconds -lt 20) { return $false }
    $script:emuLost = Describe-EmuLost
    $true
}
function Describe-EmuLost {
    $g = @(Get-GuardStops $script:guardSkip)
    if ($g.Count) { "the emulator's guard stopped $serial while the part ran: $($g[-1])" }
    else { "$serial stopped while the part ran, not by its guard (no stop line in out\emulator-guard-$Avd.log)" }
}
# Before a device part or tour run: reboots the sweep's own emulator when it is past -EmuRebootGB or gone. Returns
# @{ Ok; Note }: Note is the reboot's line ('' when none was needed); Ok false when the reboot failed.
function Ensure-Emulator([string]$before) {
    if ($script:emuBroken) { return @{ Ok = $false; Note = $script:emuBroken } }
    if (-not $script:emulatorStarted) { return @{ Ok = $true; Note = '' } } # one the sweep did not boot is left as it is
    $b = Read-EmuPrivate
    if ($null -ne $b -and $b -le $EmuRebootGB * 1GB) { return @{ Ok = $true; Note = '' } }
    $why = if ($null -eq $b) { "$serial was not running" } else { "private $(Format-Gb $b) over $EmuRebootGB GB" }
    $script:emuReboots++
    $n = $script:emuReboots
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Write-Host "[sweep] rebooting $serial before ${before}: $why"
    $null = Run 'powershell.exe' ($emuArgs + @('-Stop')) (Join-Path $dev "emu-reboot$n-stop.log") (Join-Path $dev "emu-reboot$n-stop.err.log") -Cleanup -TimeoutS 300
    # Two tries, each after 60 s (the finding of 2026-11-05: a boot within about 10 s of a kill can exit at once; 60 s
    # worked). In T233's measuring run one boot 60 s after a stop found the console ports listening again although the
    # wait just before had found them free ("ports 5640/5641 are in use"); the second try is for that.
    $bootArgs = if ($script:emuPhase -eq 'release') { @('-BootOnly') } else { @('-Dem', $dem, '-Osm', $osm, '-Server', $deviceServerArg) }
    $fails = @()
    $after = $null
    foreach ($try in 1, 2) {
        Start-Sleep -Seconds 60
        $w = Wait-ConsolePorts $EmuPort $Avd
        $log = Join-Path $dev "emu-reboot$n-$try"
        $c = if ($w) { $w } else { Run 'powershell.exe' ($emuArgs + $bootArgs) "$log.log" "$log.err.log" -Cleanup -TimeoutS 1500 }
        $after = Read-EmuPrivate
        if ("$c" -eq '0' -and $null -ne $after) { break }
        $first = @(Get-Content "$log.err.log" -ErrorAction SilentlyContinue | Where-Object { $_ -match '\S' }) | Select-Object -First 1
        $fails += "try $try$(if ($w) { ": $w" } else { ": android-emu.ps1 exit $c$(if ($first) { " ($($first.Trim()))" }), device\emu-reboot$n-$try.err.log; console ports held by $(Describe-PortHolder @($EmuPort, ($EmuPort + 1)) $EmuPort)" })"
        Write-Host "[sweep] reboot of $serial, $($fails[-1])"
        $after = $null
    }
    if ($null -eq $after) {
        $script:emuBroken = "the reboot of $serial before $before ($why) failed: $($fails -join '; ')"
        Add-Content -Encoding ascii (Join-Path $dev 'emu-reboots.log') "$(Get-Date -Format s) $($script:emuBroken)"
        return @{ Ok = $false; Note = $script:emuBroken }
    }
    $line = "rebooted $serial before ${before}: $why; stopped and booted again in $([math]::Round($sw.Elapsed.TotalSeconds)) s with the 60 s wait$(if ($fails.Count) { " (after $($fails -join '; '))" }), private $(Format-Gb $after)"
    Add-Content -Encoding ascii (Join-Path $dev 'emu-reboots.log') "$(Get-Date -Format s) $line"
    Write-Host "[sweep] $line"
    @{ Ok = $true; Note = $line }
}
# Inside a part, between two runs that each grow the emulator by about as much as a part (the device tour's runs, and
# first-run's two installs): the same check, its reboot's minutes moving the part's deadline on, as the release build's
# do. The reboot's line goes on the part's steps.tsv line. Returns false when the reboot failed.
function Ensure-EmulatorBetween([string]$before) {
    $tb = Get-Date
    $r = Ensure-Emulator $before
    if ($script:partDeadline) { $script:partDeadline = $script:partDeadline + ((Get-Date) - $tb) }
    if ($r.Note) { $script:partNote = (@($script:partNote, $r.Note) | Where-Object { $_ }) -join '; ' }
    if ($r.Ok -and $r.Note) { $script:emuSeen = $false; $script:emuGoneSince = $null } # a new qemu process
    $r.Ok
}

# T91: the previous release's APK, pinned and checked: downloads the first asset matching $patterns plus the release's
# SHA256SUMS.txt into $dir, and stops unless the APK's SHA-256 is the one SHA256SUMS.txt lists for it. Writes
# previous.json (tag, file, sha256, expected, verified) next to it and returns the file.
function Get-PreviousApk([string]$tag, [string]$dir, [string[]]$patterns, [string]$logBase) {
    New-Item -ItemType Directory -Force $dir | Out-Null
    $argv = @('release', 'download', $tag) + @($patterns | ForEach-Object { @('-p', $_) }) + @('-p', 'SHA256SUMS.txt', '-D', $dir, '--clobber')
    $c = Run 'gh' $argv "$logBase.log" "$logBase.err.log"
    $apk = @(foreach ($p in $patterns) { Get-ChildItem $dir -Filter $p }) | Select-Object -First 1
    if ($c -ne 0 -or -not $apk) { throw "could not download $($patterns -join ' or ') of $tag" }
    $sums = Join-Path $dir 'SHA256SUMS.txt'
    if (-not (Test-Path $sums)) { throw "$tag has no SHA256SUMS.txt, so its APK cannot be checked" }
    $want = $null
    foreach ($line in Get-Content $sums) {
        if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+)$' -and $Matches[2].Trim() -eq $apk.Name) { $want = $Matches[1].ToLower(); break }
    }
    if (-not $want) { throw "SHA256SUMS.txt of $tag has no line for $($apk.Name)" }
    $hash = (Get-FileHash -Algorithm SHA256 $apk.FullName).Hash.ToLower()
    [ordered]@{ tag = $tag; file = $apk.Name; bytes = $apk.Length; sha256 = $hash; expected = $want; verified = ($hash -eq $want) } |
        ConvertTo-Json | Set-Content -Encoding ascii (Join-Path $dir 'previous.json')
    if ($hash -ne $want) { throw "the $tag APK $($apk.Name) has SHA-256 $hash, SHA256SUMS.txt says $want" }
    Write-Host "[sweep] previous release ${tag}: $($apk.Name) sha256 $hash (matches SHA256SUMS.txt)"
    $apk
}

function Part([string]$name, [scriptblock]$body) {
    if (-not (Want $name)) { Skipped $name $(if ($NoDevice -and $deviceParts -contains $name) { 'no device' } elseif ($name -eq 'matrix' -and -not $Matrix) { 'needs -Matrix' } elseif ($name -eq 'bothunits' -and $NoBothUnits) { '-NoBothUnits' } else { 'not selected' }); return $false }
    # T233: a device part runs on an emulator under the sweep's threshold (rebooted first when past it or gone), and its
    # private bytes are read while it runs.
    $device = (-not $NoDevice) -and ($deviceParts -contains $name)
    $rebootNote = ''
    if ($device -and $name -ne 'device-boot') {
        $r = Ensure-Emulator $name
        if (-not $r.Ok) { Step $name 'error' 0 $r.Note; return $false }
        $rebootNote = $r.Note
    }
    if ($device) {
        $script:guardSkip = Get-GuardLineCount
        $script:emuWatch = if ($name -eq 'device-boot') { 'boot' } else { $true }
        $script:emuSeen = $false; $script:emuPeak = 0.0; $script:emuRunPeak = 0.0; $script:emuGoneSince = $null; $script:emuLost = ''
        $emuStart = Read-EmuPrivate
    }
    $t0 = Get-Date
    $sw = [System.Diagnostics.Stopwatch]::StartNew() # the seconds column: monotonic, so a clock change (DST) cannot make it negative
    $note = ''
    $script:partNote = '' # T105: a body may leave a note for its steps.tsv line (a retry and its reason)
    # T191: the part's ceiling; every Run inside it waits at most until this deadline.
    $script:partCeiling = Part-Ceiling $name
    $script:partDeadline = $t0.AddSeconds($script:partCeiling)
    $script:partTimedOut = ''
    if ($InjectHang -eq $name) {
        # T191, a test of the ceiling: a child that starts a grandchild, prints its PID and both sleep past any ceiling.
        # T233: it also leaves an empty <part>.stdout.json, as a killed device tour does, for the retry count to read.
        [IO.File]::WriteAllText((Join-Path $Out "$name.stdout.json"), '')
        $body = { Run $py @('-I', '-c', "import subprocess, sys, time; g = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(100000)']); print('grandchild', g.pid, flush=True); time.sleep(100000)") (Join-Path $Out "$name.hang.log") (Join-Path $Out "$name.hang.err.log") }
    }
    try {
        # The body's last output is the exit code (anything it printed before that is ignored).
        $code = @(& $body) | Select-Object -Last 1
        if ($script:partTimedOut) { $code = 'timeout'; $note = $script:partTimedOut } # a body that caught it still timed out
    } catch {
        if ($script:partTimedOut) { $code = 'timeout'; $note = $script:partTimedOut }
        else { $code = 'error'; $note = "$($_.Exception.Message)" }
    } finally {
        $script:partDeadline = $null
    }
    # T233: what the part did to its emulator, and whether it lost it (its guard, or a stop by hand).
    if ($device) {
        $emuEnd = Read-EmuPrivate
        $script:emuWatch = $false
        if (-not $script:emuLost -and $script:emuSeen -and $null -eq $emuEnd -and $name -ne 'device-boot') { $script:emuLost = Describe-EmuLost }
        if ($script:emuLost) {
            $how = if ($script:emuLost -match 'guard stopped') { 'guard-stop' } else { 'emulator-gone' }
            $rest = if ("$code" -eq 'error') { $note.Replace($script:emuLost, '').TrimStart(';', ' ') } else { (@("the part's own result: $code", $note) | Where-Object { $_ }) -join '; ' }
            $note = (@($script:emuLost, $rest) | Where-Object { $_ }) -join '; '
            $code = $how
        }
        $peak = if ($script:emuSeen) { $script:emuPeak } else { $null }
        $mem = "emulator private $(Format-Gb $emuStart) at the start, peak $(Format-Gb $peak), $(Format-Gb $emuEnd) at the end" +
            $(if ($null -ne $emuStart -and $null -ne $peak) { " (+$(Format-Gb ($peak - $emuStart)) to the peak)" } else { '' })
        $script:partNote = (@($rebootNote, $script:partNote, $mem) | Where-Object { $_ }) -join '; '
        $tsv = Join-Path $dev 'emu-private.tsv'
        if (-not (Test-Path $tsv)) { Set-Content -Encoding ascii $tsv "part`tstartGB`tpeakGB`tendGB`trebooted`tlost" }
        $g = { param($v) if ($null -eq $v) { '' } else { '{0:f2}' -f ($v / 1GB) } }
        Add-Content -Encoding ascii $tsv ("$name`t$(& $g $emuStart)`t$(& $g $peak)`t$(& $g $emuEnd)`t$([bool]$rebootNote)`t$($script:emuLost -replace '[\t\r\n]', ' ')")
    }
    # T185: the retries the part's reports counted (screen captures, adb forwards restored, cut reads) on its steps.tsv line.
    # T233: an empty report (a program killed before it wrote one; Get-Content -Raw gives $null) counts nothing, and a
    # report that cannot be read leaves the counts out; neither may stop the sweep before its summary (2026-11-06).
    $n = @{}
    try {
        foreach ($f in @(Get-ChildItem $Out -Recurse -File -Include "$name.json", '*.stdout.json' -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $t0 })) {
            $text = "$(Get-Content -Raw -LiteralPath $f.FullName -ErrorAction SilentlyContinue)"
            if (-not $text) { continue }
            foreach ($m in [regex]::Matches($text, '"(captureRetries|forwardRestores|incompleteReadRetries)":\s*(\d+)')) { $n[$m.Groups[1].Value] += [int]$m.Groups[2].Value } }
    } catch { Write-Host "[sweep] $name`: the retry counts were not read ($($_.Exception.Message))" }
    $counts = @($n.Keys | Sort-Object | Where-Object { $n[$_] } | ForEach-Object { '{0} {1}' -f $_, $n[$_] })
    $counts = $counts -join ', '
    if ($counts) { $script:partNote = (@($script:partNote, $counts) | Where-Object { $_ }) -join '; ' }
    if ($script:partNote) { $note = (@($script:partNote, $note) | Where-Object { $_ }) -join '; ' }
    Step $name $code $sw.Elapsed.TotalSeconds $note
    "$code" -eq '0'
}

# T105: the build-cache failure of the finding docs/findings/2026-10-14-debug-test-compile-loses-internal-after-cache-hit.md
# (a restored compile output whose module file lists only some of main's file facades, so a unit-test compile finds none of
# main's other top-level functions): "Unresolved reference" in a unit-test compile task. androidApp's main compiles no
# longer use the cache since T105; this is the check part's fallback for another module or a stale build directory.
function Test-StaleCompile([string[]]$logs) {
    $text = (@($logs | Where-Object { Test-Path $_ } | ForEach-Object { Get-Content -Raw $_ }) -join "`n")
    ($text -match "Task :[\w:-]*compile\w*UnitTestKotlin FAILED|Execution failed for task ':[\w:-]*compile\w*UnitTestKotlin'") -and
        ($text -match 'e: .*Unresolved reference')
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
    if ($script:partDeadline -and $script:partDeadline -lt $deadline) { $deadline = $script:partDeadline } # T191
    while ((Get-Date) -lt $deadline) {
        if ($p.HasExited) { throw "$name exited before answering (see $name.err.log)" }
        try { if ((Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 "http://127.0.0.1:$port$readyPath").StatusCode -eq 200) { return } } catch { }
        Start-Sleep -Milliseconds 500
    }
    if ($script:partDeadline -and (Get-Date) -ge $script:partDeadline) {
        $script:partTimedOut = "timed out at its ceiling of $($script:partCeiling) s waiting for $name to answer http://127.0.0.1:$port$readyPath"
        throw $script:partTimedOut
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
$apkCopies = Join-Path $Out 'apk'
function Build-Release {
    if ($script:releaseBuilt) { return }
    $count = "$(& git rev-list --count HEAD 2>$null)".Trim()
    $desc = "$(& git describe --tags --always 2>$null)".Trim() -replace '^v', ''
    # T191: the build is every release part's prerequisite, not the work of the part that happens to come first, so it
    # has its own ceiling ($releaseBuildS) and the part's deadline moves on by the time it took (526 s in a fresh worktree
    # on the loaded second PC, 2026-10-31, against the part's own 150 to 220 s).
    $savedDeadline = $script:partDeadline; $script:partDeadline = $null; $tb = Get-Date
    try {
        $code = Run $gradle @(':androidApp:assembleRelease', "-PdystopoVersion=$desc-sweep", "-PdystopoVersionCode=$count") (Join-Path $Out 'assemble-release.log') (Join-Path $Out 'assemble-release.err.log') -TimeoutS $releaseBuildS
    } finally { if ($savedDeadline) { $script:partDeadline = $savedDeadline + ((Get-Date) - $tb) } }
    if ("$code" -eq 'timeout') { throw "assembleRelease did not finish within $releaseBuildS s (killed by PID; see assemble-release.log)" }
    if ($code -ne 0) { throw "assembleRelease failed (see assemble-release.err.log)" }
    # T105: the sweep's own copies of the release APKs, which every release part installs: the update part's second build
    # (versionCode +1) overwrites androidApp\build\outputs\apk\release, and the parts after it, and a later check of the
    # APK the release part tried, must not pick that one up. apks.json lists them with their SHA-256.
    New-Item -ItemType Directory -Force $apkCopies | Out-Null
    $list = foreach ($f in Get-ChildItem (Join-Path $repo 'androidApp\build\outputs\apk\release') -Filter '*.apk') {
        Copy-Item -LiteralPath $f.FullName -Destination $apkCopies -Force
        [ordered]@{ file = $f.Name; bytes = $f.Length; sha256 = (Get-FileHash -Algorithm SHA256 $f.FullName).Hash.ToLower() }
    }
    [ordered]@{ version = "$desc-sweep"; versionCode = [int]$count; apks = @($list) } | ConvertTo-Json -Depth 4 | Set-Content -Encoding ascii (Join-Path $apkCopies 'apks.json')
    $script:releaseBuilt = $true
}

# T105: the sweep's copy of the release APK for a device's ABI (the universal one when there is no split for it).
function Release-Apk([string]$s) {
    Build-Release
    $abi = Tool-Text $adbExe @('-s', $s, 'shell', 'getprop', 'ro.product.cpu.abi') # T191: a bounded wait
    $apk = Join-Path $apkCopies "androidApp-$abi-release.apk"
    if ($abi -and (Test-Path $apk)) { return $apk }
    Join-Path $apkCopies 'androidApp-universal-release.apk'
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
$adbExe = Join-Path $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'Android\sdk' }) 'platform-tools\adb.exe'
$gradle = Join-Path $repo 'gradlew.bat'
$devtool = Join-Path $repo 'devtool\build\install\devtool\bin\devtool.bat'
$meta = [ordered]@{ host = 'windows'; startedAt = (Get-Date).ToString('s'); noDevice = [bool]$NoDevice; noDaemon = [bool]$NoDaemon; emuMemoryMb = $EmuMemory
    commit = "$(& git rev-parse --short HEAD 2>$null)".Trim(); partTimeoutS = $PartTimeout; partTimeoutsS = $ceilings
    emuLimitGB = $EmuLimitGB; emuRebootGB = $EmuRebootGB } # T233
$meta | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $Out 'meta.json')

$emulatorStarted = $false
try {
    # --- data ----------------------------------------------------------------------------------------------
    # T87: rainier-osm.pmtiles (the OpenMapTiles profile) is optional: copied when present, never warned about,
    # because no part needs it (the demo page reads it only with ?osm=openmaptiles).
    $packs = @('rainier-dem.pmtiles', 'rainier-outdoors.pmtiles')
    $optionalPacks = @('rainier-osm.pmtiles')
    $localOut = Join-Path $repo 'out'
    foreach ($f in $packs + $optionalPacks) {
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
    $null = Part 'check' {
        $c = Run $gradle @('check', '--continue') (Join-Path $Out 'check.log') (Join-Path $Out 'check.err.log')
        if ("$c" -ne '0' -and (Test-StaleCompile @((Join-Path $Out 'check.log'), (Join-Path $Out 'check.err.log')))) {
            # Once: :androidApp:clean drops the stale outputs and --no-build-cache keeps a cached entry from coming back.
            $script:partNote = "retried once after :androidApp:clean with --no-build-cache: the first run's unit-test compile reported Unresolved reference (check-first.log)"
            Move-Item -Force (Join-Path $Out 'check.log') (Join-Path $Out 'check-first.log')
            Move-Item -Force (Join-Path $Out 'check.err.log') (Join-Path $Out 'check-first.err.log')
            $null = Run $gradle @(':androidApp:clean') (Join-Path $Out 'check-clean.log') (Join-Path $Out 'check-clean.err.log')
            $c = Run $gradle @('check', '--continue', '--no-build-cache') (Join-Path $Out 'check.log') (Join-Path $Out 'check.err.log')
        }
        $c
    }
    $null = Part 'server' { Run $gradle @(':server:installDist') (Join-Path $Out 'server.log') (Join-Path $Out 'server.err.log') }
    $null = Part 'notices' { Run $py @('-I', 'tools/notices.py', '--check') (Join-Path $Out 'notices.log') (Join-Path $Out 'notices.err.log') }

    $debugDeviceParts = @('device-boot', 'device-tour', 'routes', 'search', 'probe', 'bothunits')
    $debugDeviceParts += 'startup' # T268 (end T268)
    $needDevtool = @('tour', 'demo-serve', 'live', 'sync-sim') + $(if ($DeviceServer -eq 'live' -and -not $NoDevice) { $debugDeviceParts } else { @() }) |
        Where-Object { Want $_ }
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
    $emuArgs = @('-ExecutionPolicy', 'Bypass', '-File', 'tools\android-emu.ps1', '-Avd', $Avd, '-EmuPort', "$EmuPort", '-Port', "$Port", '-Owner', "sweep-$Date")
    if ($AvdHome) { $emuArgs += @('-AvdHome', $AvdHome) }
    $emuArgs += $emuMemoryArgs
    $dem = Join-Path $localOut 'rainier-dem.pmtiles'
    $osm = Join-Path $localOut 'rainier-outdoors.pmtiles'
    # T79: the device parts need a running emulator. When device-boot is not selected (-Only first-run, -Skip
    # device-boot) and nothing answers on the serial, device-boot runs anyway (and its AVD is stopped and deleted
    # at the end like any the sweep boots); an emulator already running on -EmuPort is used as it is.
    if (-not $NoDevice -and -not (Want 'device-boot') -and @($deviceParts | Where-Object { $_ -ne 'device-boot' -and (Want $_) }).Count) {
        $state = Tool-Text $adbExe @('-s', $serial, 'get-state') 30 # T191: a bounded wait
        if ($state -ne 'device') {
            $script:bootImplied = $true
            Write-Host "[sweep] $serial is not running: device-boot runs first (the device parts selected need it)"
        }
    }
    # T105: the debug build's server, set in the app by the sweep (never the build's default, the host's 8765): the sweep's
    # own serve --live on -BasePort+2 (free again once the live part has stopped its server), or none with -DeviceServer direct.
    $deviceServerArg = 'none'
    $deviceServerNote = 'server none (-DeviceServer direct)'
    if (-not $NoDevice -and $DeviceServer -eq 'live' -and ($script:bootImplied -or @($debugDeviceParts | Where-Object { Want $_ }).Count)) {
        $dsp = $BasePort + 2
        try {
            Start-Server 'device-server' $devtool @('serve', '--live', '--port', "$dsp") $dsp '/stats' 180 @{ DYSTOPO_SYNC_DB = (Join-Path $Out 'device-peer.db') }
            $deviceServerArg = "http://10.0.2.2:$dsp"
            $deviceServerNote = "server $deviceServerArg (the sweep's serve --live)"
        } catch {
            $deviceServerNote = "server none: the sweep's serve --live did not start ($($_.Exception.Message))"
        }
        Write-Host "[sweep] the debug build's $deviceServerNote"
    }
    $booted = Part 'device-boot' {
        $script:partNote = $deviceServerNote
        # T191: the previous emulator's console ports free first (up to a minute), else the part fails naming the holder;
        # until then nothing here is the sweep's to stop, so the cleanup at the end leaves that holder alone.
        $why = Wait-ConsolePorts $EmuPort $Avd
        if ($why) { throw $why }
        # An emulator of this very AVD already running on the serial is not the sweep's to stop at the end.
        $already = ((Tool-Text $adbExe @('devices') 30) -match "(?m)^$([regex]::Escape($serial))\s+device")
        if ($already) { $script:partNote = (@($script:partNote, "$serial already ran ${Avd}: left running at the end") | Where-Object { $_ }) -join '; ' }
        else { $script:emulatorStarted = $true }
        # android-emu.ps1 prints nothing when its output is piped (T65 follow-up); its exit code and the
        # logcat line the Python tools read are what count, so the logs are kept only for a post-mortem.
        $c = Run 'powershell.exe' ($emuArgs + @('-Build', '-Dem', $dem, '-Osm', $osm, '-Server', $deviceServerArg)) (Join-Path $dev 'emu.log') (Join-Path $dev 'emu.err.log')
        # A refusal at the emulator cap (T190) is its own word on the steps.tsv line, not "exit 1".
        if ($c -ne 0 -and $c -ne 'timeout' -and (Select-String -Path (Join-Path $dev 'emu.err.log'), (Join-Path $dev 'emu.log') -Pattern 'emulator cap reached' -Quiet -ErrorAction SilentlyContinue)) {
            $script:partNote = (@($script:partNote, 'emulator cap reached: see device\emu.err.log for the holders') | Where-Object { $_ }) -join '; '
            $script:emulatorStarted = $false
            return 'cap-refused'
        }
        $c
    }
    $deviceSkipNote = 'emulator did not boot'
    if ($booted -or -not (Want 'device-boot')) {
        $null = Part 'device-tour' {
            $worst = 0
            $script:partNote = $deviceServerNote
            $say = { param($t) if ($t) { $script:partNote = (@($script:partNote, $t) | Where-Object { $_ }) -join '; ' } }
            for ($i = 1; $i -le $DeviceTourRuns; $i++) {
                # T233: each run grows the emulator by about as much as a part does (3.1 and 3.7 GB measured), so the
                # threshold is checked between runs too.
                if ($i -gt 1 -and -not (Ensure-EmulatorBetween "device tour run $i")) { $worst = 'error'; break }
                $script:emuRunPeak = 0.0
                $s0 = Read-EmuPrivate
                # T105: --server sets the app's server again (an emulator the sweep did not boot keeps what it had), and the
                # first view waits for a quiet device by its load (android-check.py --quiet-cores).
                $c = Run $py @('-I', 'tools/android-check.py', '--port', "$Port", '--serial', $serial, '--views-file', 'tools/android-tour.txt',
                    '--server', $deviceServerArg, '--out', (Join-Path $dev "tour$i.png")) (Join-Path $dev "tour$i.stdout.json") (Join-Path $dev "tour$i.err.log")
                & $say "run $i private $(Format-Gb $s0) to a peak of $(Format-Gb $script:emuRunPeak)"
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
        # T139 (T129's follow-up): the both-units check on the debug build the parts above left running, and a second
        # emulator booted for it on the matrix pattern; without that second emulator the part is a skip with its reason.
        $null = Part 'bothunits' {
            $bdir = Join-Path $Out 'ui\bothunits'
            New-Item -ItemType Directory -Force $bdir | Out-Null
            $bAvd = "$Avd-b"; $bEmu = $MatrixEmuPort; $bSerial = "emulator-$bEmu"; $bFwd = $Port + 2; $bp = $BasePort + 3
            $bArgs = @('-ExecutionPolicy', 'Bypass', '-File', 'tools\android-emu.ps1', '-Avd', $bAvd, '-EmuPort', "$bEmu", '-Port', "$bFwd", '-Owner', "sweep-$Date")
            if ($AvdHome) { $bArgs += @('-AvdHome', $AvdHome) }
            $bArgs += $emuMemoryArgs
            # The same debug build as the first emulator's (built by device-boot); built here only when it is missing.
            $debugApk = Join-Path $repo 'androidApp\build\outputs\apk\debug\androidApp-debug.apk'
            $boot = $bArgs + @('-Dem', $dem, '-Osm', $osm, '-Server', $deviceServerArg) + $(if (Test-Path $debugApk) { @() } else { @('-Build') })
            $why = Wait-ConsolePorts $bEmu $bAvd # T191: as before device-boot; a held pair is a skip like a failed boot
            if ($why) { $script:partNote = "skipped: $why"; 'skip'; return }
            try {
                $c = Run 'powershell.exe' $boot (Join-Path $bdir 'emu-b.log') (Join-Path $bdir 'emu-b.err.log')
                if ($c -ne 0) {
                    $why = @(Get-Content (Join-Path $bdir 'emu-b.err.log') -ErrorAction SilentlyContinue | Where-Object { $_ -match '\S' -and $_ -notmatch '^\s*(At |\+|CategoryInfo|FullyQualifiedErrorId)' }) | Select-Object -First 1
                    $script:partNote = "skipped: the second emulator $bSerial ($bAvd) did not start (android-emu.ps1 exit $c$(if ($why) { ": $($why.Trim())" }); ui\bothunits\emu-b.err.log)"
                    'skip'
                    return
                }
                if (-not (Test-Path (Join-Path $repo 'server\build\install\server\bin\server.bat'))) {
                    $c = Run $gradle @(':server:installDist') (Join-Path $bdir 'server-install.log') (Join-Path $bdir 'server-install.err.log')
                    if ($c -ne 0) { throw ':server:installDist failed' }
                }
                $cfg = Server-Config (Join-Path $bdir 'server') $bp
                Start-Server 'bothunits-server' (Join-Path $repo 'server\build\install\server\bin\server.bat') @('--config', $cfg) $bp '/healthz' 180
                try {
                    $script:partNote = "peer http://10.0.2.2:$bp, phones $serial and $bSerial"
                    Run $py (@('-I', 'tools/android-ui.py', 'bothunits', '--serial', $serial, '--port', "$Port", '--serial2', $bSerial, '--port2', "$bFwd",
                            '--server', "http://10.0.2.2:$bp", '--out', $bdir)) (Join-Path $Out 'bothunits.json') (Join-Path $Out 'bothunits.err.log')
                } finally { Stop-Server 'bothunits-server' }
            } finally {
                $null = Run 'powershell.exe' ($bArgs + @('-Stop', '-Delete')) (Join-Path $bdir 'emu-b-stop.log') (Join-Path $bdir 'emu-b-stop.err.log') -Cleanup -TimeoutS 300
            }
        }
        # T268: the launch budget, on the debug build before the release phase. Part's reboot check (Ensure-Emulator, at
        # -EmuRebootGB) runs before the body, so the store is seeded and the packs copied after it: the part never leans on
        # what an earlier part left. Five cold launches add about 2.5 GB to the emulator's private bytes (finding of
        # 2026-11-05), inside the guard's -EmuLimitGB from any level under -EmuRebootGB.
        $null = Part 'startup' {
            Run $py @('-I', 'tools/android-startup.py', '--serial', $serial, '--port', "$Port", '--launches', '5', '--seed-tracks', '52',
                '--dem', $dem, '--osm', $osm, '--quiet', '240', '--budget', 'tools/launch-budget.json') (Join-Path $Out 'startup.json') (Join-Path $Out 'startup.err.log')
        }
        # end T268
        Stop-Server 'device-server' # T105: the debug parts are done; the release build has no server unless a part types one
        $script:emuPhase = 'release' # T233: a reboot from here boots only; every release part installs its own APK
        $null = Part 'release' {
            # After the debug parts: it uninstalls the debug build (signed with the other key). T105: the sweep's own copy.
            Run 'powershell.exe' @('-ExecutionPolicy', 'Bypass', '-File', 'tools\android-release.ps1', '-Serial', $serial, '-Apk', (Release-Apk $serial),
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
            $relApk = Release-Apk $serial # T81: built once per sweep, with the derived version; T105: the sweep's copy
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
            $say = { param($t) $script:partNote = (@($script:partNote, $t) | Where-Object { $_ }) -join '; ' }
            $script:emuRunPeak = 0.0; $s0 = Read-EmuPrivate
            try {
                $c1 = Run $py @('-I', 'tools/android-firstrun.py', '--serial', $serial, '--apk', $relApk, '--server', "http://${lan}:$fp", '--out', $fdir,
                    '--import', "$dem,$osm") (Join-Path $Out 'first-run.json') (Join-Path $Out 'first-run.err.log')
            } finally { Stop-Server 'firstrun-server' }
            & $say "server run private $(Format-Gb $s0) to a peak of $(Format-Gb $script:emuRunPeak)"
            # T233: the --direct run alone grew the emulator by over 4 GB in T233's measuring run, so the threshold is checked
            # before it as between two parts.
            if (-not (Ensure-EmulatorBetween 'first-run --direct')) { return 'error' }
            $script:emuRunPeak = 0.0; $s0 = Read-EmuPrivate
            # T91 (the T81 and T83 follow-up): the same fresh release install with no server, through the public sources.
            $c2 = Run $py @('-I', 'tools/android-firstrun.py', '--serial', $serial, '--apk', $relApk, '--direct', '--out', (Join-Path $fdir 'direct')) `
                (Join-Path $Out 'first-run-direct.json') (Join-Path $Out 'first-run-direct.err.log')
            & $say "direct run private $(Format-Gb $s0) to a peak of $(Format-Gb $script:emuRunPeak)"
            if ("$c1" -ne '0') { $c1 } else { $c2 }
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
                Run $py (@('-I', 'tools/android-ui.py', 'features', '--serial', $serial, '--apk', (Release-Apk $serial), '--server', $uiServer,
                        '--dem', $dem, '--osm', $osm, '--out', (Join-Path $ui 'features')) + $knownArgs) (Join-Path $Out 'features.json') (Join-Path $Out 'features.err.log')
            }
            # T105 (T93's follow-up): the recentre button by taps on the release build.
            $null = Part 'recenter' {
                Run $py (@('-I', 'tools/android-ui.py', 'recenter', '--serial', $serial, '--apk', (Release-Apk $serial), '--dem', $dem, '--osm', $osm,
                        '--out', (Join-Path $ui 'recenter')) + $knownArgs) (Join-Path $Out 'recenter.json') (Join-Path $Out 'recenter.err.log')
            }
            $null = Part 'permissions' {
                if (-not $uiServer) { throw 'the sweep server for the UI parts did not start' }
                Run $py (@('-I', 'tools/android-ui.py', 'permissions', '--serial', $serial, '--apk', (Release-Apk $serial), '--server', $uiServer,
                        '--out', (Join-Path $ui 'permissions')) + $knownArgs) (Join-Path $Out 'permissions.json') (Join-Path $Out 'permissions.err.log')
            }
        } finally { Stop-Server 'ui-server' }
        # T154 (T143's follow-up): the Diagnostics row on the sweep's own release build, by taps.
        $null = Part 'diagnostics' {
            Run $py (@('-I', 'tools/android-ui.py', 'diagnostics', '--serial', $serial, '--apk', (Release-Apk $serial), '--dem', $dem, '--osm', $osm,
                    '--out', (Join-Path $ui 'diagnostics')) + $knownArgs) (Join-Path $Out 'diagnostics.json') (Join-Path $Out 'diagnostics.err.log')
        }
        $null = Part 'upgrade' {
            Build-Release
            $prevDir = Join-Path $ui 'previous'
            $tag = $PrevRelease
            if (-not $tag) { $tag = Tool-Text 'gh' @('release', 'list', '--exclude-drafts', '--limit', '1', '--json', 'tagName', '--jq', '.[0].tagName') } # T191: bounded
            if (-not $tag) { throw 'no previous release (gh release list); pass -PrevRelease' }
            $abi = Tool-Text $adbExe @('-s', $serial, 'shell', 'getprop', 'ro.product.cpu.abi')
            # Alpha releases since v0.1.4 ship only the universal APK (owner's policy 2026-10-11); older ones have per-ABI files too.
            $prev = Get-PreviousApk $tag $prevDir @("*-$abi.apk", '*-universal.apk') (Join-Path $ui 'previous') # T91: pinned and checked
            Run $py (@('-I', 'tools/android-ui.py', 'upgrade', '--serial', $serial, '--prev-apk', $prev.FullName, '--apk', (Release-Apk $serial), '--dem', $dem, '--osm', $osm,
                    '--label', $tag, '--out', (Join-Path $ui 'upgrade')) + $knownArgs) (Join-Path $Out 'upgrade.json') (Join-Path $Out 'upgrade.err.log')
        }
        $null = Part 'background' {
            Build-Release
            Run $py (@('-I', 'tools/android-ui.py', 'background', '--serial', $serial, '--apk', (Release-Apk $serial), '--dem', $dem, '--osm', $osm,
                    '--out', (Join-Path $ui 'background')) + $knownArgs) (Join-Path $Out 'background.json') (Join-Path $Out 'background.err.log')
        }
        $null = Part 'network' {
            Build-Release
            # The part starts, kills and restarts this server itself (by PID); the phone talks to the stall proxy.
            $cfg = Server-Config (Join-Path $ui 'network-server') ($BasePort + 5) @($dem, $osm)
            Run $py (@('-I', 'tools/android-ui.py', 'network', '--serial', $serial, '--apk', (Release-Apk $serial), '--server', "http://$(Lan-Address):$($BasePort + 6)",
                    '--proxy-port', "$($BasePort + 6)", '--server-config', $cfg, '--out', (Join-Path $ui 'network')) + $knownArgs) (Join-Path $Out 'network.json') (Join-Path $Out 'network.err.log')
        }
        $null = Part 'update' {
            # T83: this build (A) and a second build of the same commit with versionCode +1 (B), both with this machine's
            # release key, and the previous published release's universal APK (the start of the user's path).
            Build-Release
            $udir = Join-Path $ui 'update'
            New-Item -ItemType Directory -Force $udir | Out-Null
            $apkDir = Join-Path $repo 'androidApp\build\outputs\apk\release'
            Copy-Item (Join-Path $apkCopies 'androidApp-universal-release.apk') (Join-Path $udir 'current.apk') -Force # T105: the sweep's copy
            $count = [int]"$(& git rev-list --count HEAD 2>$null)".Trim()
            $desc = "$(& git describe --tags --always 2>$null)".Trim() -replace '^v', ''
            $c = Run $gradle @(':androidApp:assembleRelease', "-PdystopoVersion=$desc-sweep-next", "-PdystopoVersionCode=$($count + 1)") (Join-Path $udir 'assemble-next.log') (Join-Path $udir 'assemble-next.err.log')
            if ($c -ne 0) { throw 'assembleRelease of the next build failed (see update\assemble-next.err.log)' }
            Copy-Item (Join-Path $apkDir 'androidApp-universal-release.apk') (Join-Path $udir 'next.apk') -Force
            $tag = $PrevRelease
            if (-not $tag) { $tag = Tool-Text 'gh' @('release', 'list', '--exclude-drafts', '--limit', '1', '--json', 'tagName', '--jq', '.[0].tagName') } # T191: bounded
            if (-not $tag) { throw 'no previous release (gh release list); pass -PrevRelease' }
            $prev = Get-PreviousApk $tag (Join-Path $udir 'previous') @('*-universal.apk') (Join-Path $udir 'previous') # T91: pinned and checked
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
                $c = Run $py (@('-I', 'tools/android-ui.py', 'matrix', '--serial', $serial, '--apk', (Release-Apk $serial), '--out', $mdir) + $v + $knownArgs) (Join-Path $mdir "$($v[-1]).json") (Join-Path $mdir "$($v[-1]).err.log")
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
                $mArgs = @('-ExecutionPolicy', 'Bypass', '-File', 'tools\android-emu.ps1', '-Avd', $mAvd, '-EmuPort', "$mPort", '-Port', "$($Port + 2 + $i)", '-Owner', "sweep-$Date",
                    '-Api', $api, '-Device', $devName, '-BootOnly')
                if ([int]$api -lt 30) { $mArgs += @('-Feature', 'GLESDynamicVersion') } # images before 30 offer only GLES 2 otherwise
                if ($loc) { $mArgs += @('-Locale', $loc) }
                if ($AvdHome) { $mArgs += @('-AvdHome', $AvdHome) }
                $mArgs += $emuMemoryArgs
                try {
                    $c = Run 'powershell.exe' $mArgs (Join-Path $mdir "$label-emu.log") (Join-Path $mdir "$label-emu.err.log")
                    if ($c -ne 0) { $worst = $c; continue }
                    $c = Run $py (@('-I', 'tools/android-ui.py', 'matrix', '--serial', $mSerial, '--apk', (Release-Apk $mSerial), '--dem', $dem, '--osm', $osm, '--label', $label, '--out', $mdir) + $knownArgs) (Join-Path $mdir "$label.json") (Join-Path $mdir "$label.err.log")
                    if ($c -ne 0) { $worst = $c }
                } finally {
                    $null = Run 'powershell.exe' ($mArgs[0..7] + @('-Stop', '-Delete') + $(if ($AvdHome) { @('-AvdHome', $AvdHome) } else { @() })) (Join-Path $mdir "$label-stop.log") (Join-Path $mdir "$label-stop.err.log") -Cleanup -TimeoutS 300
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
        Run $py @('-I', 'tools/android-ui.py', 'strings', '--apk', (Join-Path $apkCopies 'androidApp-universal-release.apk'), # T105: the sweep's copy
            '--out', (Join-Path $Out 'ui\strings')) (Join-Path $Out 'apk-strings.json') (Join-Path $Out 'apk-strings.err.log')
    }
} catch {
    # T233 (the sweep of 2026-11-06 died here with no summary): an error outside any part is a failed line of its own, and
    # the summary and the sheet are still written below.
    $script:emuWatch = $false
    Step 'sweep' 'error' 0 "the sweep stopped early: $($_.Exception.Message) ($($_.InvocationInfo.PositionMessage -replace '\s+', ' '))"
} finally {
    foreach ($name in @($script:servers.Keys)) { Stop-Server $name }
    if ($emulatorStarted -and -not $KeepEmulator) {
        # T79: a forward that was never set (a partial -Only run) makes adb exit 1 with stderr; that must not stop
        # the cleanup, or the AVD stays up and summary.json is never written. T191: bounded, like the stop below.
        if (Test-Path $adbExe) { try { $null = Tool-Text $adbExe @('-s', $serial, 'forward', '--remove', "tcp:$($Port + 1)") 30 -Cleanup } catch { } }
        $code = Run 'powershell.exe' ($emuArgs + @('-Stop', '-Delete')) (Join-Path $dev 'emu-stop.log') (Join-Path $dev 'emu-stop.err.log') -Cleanup -TimeoutS 300
        Write-Host "[sweep] emulator $serial ($Avd) stopped and deleted (exit $code)"
    }
}

# --- summary ---------------------------------------------------------------------------------------------
if ($script:stepsBehind -and -not (Write-Steps $script:stepLines.ToArray() -Rewrite)) {
    Write-Host "[sweep] steps.tsv is still held by another process; the summary will miss steps:"
    $script:stepLines | ForEach-Object { Write-Host "[sweep]   $_" }
}
exit (Write-Summary)
