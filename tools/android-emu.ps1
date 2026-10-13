# Boots a headless emulator, installs the debug app, pushes the Rainier region pack, launches the app and
# forwards its debug server, so agents can `curl` it:
#
#   powershell -ExecutionPolicy Bypass -File tools\android-emu.ps1 -Build
#   curl -H "X-Dystopo-Token: <token>" http://localhost:8686/health
#
# Every request needs the app's per-launch loopback token (T49), as `-H "X-Dystopo-Token: <token>"` or
# `?t=<token>`. T57: the debug routes answer on a random device port per launch, logged with the token only
# in logcat (`I/Dystopo: debug server on port N token T`); this script reads that line and forwards -Port to
# that device port, and ends by printing the token. Both change whenever the app restarts: run
# `tools\android-emu.ps1 -Avd <avd> -EmuPort <n> -Port <p> -Forward` (or the Python tools, which do it
# themselves) to re-read them. The app setting debugFixedPort=1 keeps the debug routes on 8686 with the
# GET /health token bootstrap (from the next launch); the script then still finds the port in logcat.
#
# Several emulators side by side: give each its own AVD, console port and host port. The serial is
# emulator-<EmuPort>; every adb call in this script (and android-push-pack.ps1) uses it, and the run ends
# by printing it for ANDROID_SERIAL:
#
#   powershell -ExecutionPolicy Bypass -File tools\android-emu.ps1 -Avd dystopo35-T28a -EmuPort 5570 -Port 8694
#   powershell -ExecutionPolicy Bypass -File tools\android-emu.ps1 -Avd dystopo35-T28a -EmuPort 5570 -Port 8694 -Stop
#
# The script only ever acts on the emulator running -Avd: an emulator on -EmuPort that runs another AVD,
# or a host port already forwarded to another device, stops the run instead of being reused. Without
# -EmuPort it reuses the running instance of -Avd, or boots on the first free even port from 5554.
#
# T81, device matrix and network: -Api <n> boots the google_apis x86_64 image of that API level (installed with
# sdkmanager when missing; the minimum supported is 26), -Device a hardware profile (pixel_tablet for a tablet),
# -Locale de-DE the system locale (emulator -change-locale, at boot), -FontScale 2.0 and -Dark the system font
# scale and night mode (applied after boot, every run), -NetSpeed/-NetDelay the emulator's network throttling
# (gsm edge umts hsdpa lte full, gprs edge umts none; at boot, and also set on a running emulator through its
# console). -BootOnly stops after the boot and those settings: no build, install, push or debug forward (the
# release parts in tools/android-ui.py install their own APK).
#
#   powershell -ExecutionPolicy Bypass -File tools\android-emu.ps1 -Avd dystopo26-T81 -EmuPort 5662 -Port 9055 -Api 26 -BootOnly -AvdHome E:\dystopo-avd
#   powershell -ExecutionPolicy Bypass -File tools\android-emu.ps1 -Avd dystopo35-tab -EmuPort 5664 -Port 9056 -Device pixel_tablet -Locale de-DE -BootOnly
#
# Needs tools\android-sdk.ps1 first. The AVD lives under -AvdHome (default ANDROID_AVD_HOME, else <sdk>\avd);
# each takes about 6 GB, so put several on a big disk. -Stop -Delete removes the AVD after stopping it.
# The emulator needs CPU virtualization: Windows Hypervisor Platform (WHPX) or the Android Emulator
# hypervisor driver (AEHD); both need an administrator once (see docs/reference/android-emulator.md). Without either the
# script stops with instructions: x86_64 images under pure software emulation (-accel off) start QEMU but
# never run the guest on emulator 37 (tested 2026-10), and ARM images are refused on x86_64 hosts.
# -Accel off still tries, for experiments. -Gpu picks the emulator GPU at boot (default: host when there is a
# hardware GPU; swiftshader draws no MapLibre text or dashes). To change it, -Stop the AVD and boot again;
# the app is reinstalled and the packs re-pushed on every run (a GPU mode change can wipe the app).
param(
    [string]$Avd = 'dystopo35',
    [string]$SystemImage = 'system-images;android-35;google_apis;x86_64',
    [string]$Device = 'pixel_6',
    # Host port forwarded to the app's debug server (T57: a random device port per launch, read from logcat).
    [int]$Port = 8686,
    # T57: only re-read the debug port and token from logcat and re-forward -Port (after an app restart).
    [switch]$Forward,
    # Emulator console port (even, 5554-5682); adb uses EmuPort+1 and the serial is emulator-<EmuPort>.
    [int]$EmuPort = 0,
    # Kill this AVD's emulator (found by -EmuPort or by AVD name) and remove its port forward.
    [switch]$Stop,
    # With -Stop: also delete the AVD (its .avd folder and .ini, about 6 GB each).
    [switch]$Delete,
    # Where AVDs live (created and booted from). Default: ANDROID_AVD_HOME, else <sdk>\avd. Each AVD takes
    # about 6 GB, so point this at a big disk when running several.
    [string]$AvdHome = '',
    [string]$Apk = 'androidApp\build\outputs\apk\debug\androidApp-debug.apk',
    [switch]$Build,
    [string]$Dem = 'out\rainier-dem.pmtiles',
    [string]$Osm = 'out\rainier-outdoors.pmtiles',
    [switch]$NoPush,
    # Push the packs as a named pack (<name>-dem.pmtiles, <name>-outdoors.pmtiles); see android-push-pack.ps1.
    [string]$PackName = '',
    [int]$BootTimeoutMinutes = 60,
    [ValidateSet('auto', 'on', 'off')] [string]$Accel = 'auto',
    # Emulator GPU. auto: host when the machine has a hardware GPU, else swiftshader_indirect. Under the
    # software GPU MapLibre draws no text and no dash patterns (found in T25), so tours need host.
    [ValidateSet('auto', 'host', 'swiftshader_indirect', 'angle_indirect')] [string]$Gpu = 'auto',
    [int]$Memory = 4096,
    [int]$Cores = 4,
    # Use a connected device (a phone with USB debugging) instead of an emulator; -Serial picks one of several.
    [switch]$NoEmulator,
    [string]$Serial = '',
    # T81: API level of the system image (overrides -SystemImage); 0 keeps -SystemImage.
    [int]$Api = 0,
    # T81: system locale at boot (BCP 47, e.g. de-DE); '' leaves the image's.
    [string]$Locale = '',
    # T81: system font scale after boot (0 leaves it; 1.0 resets).
    [double]$FontScale = 0,
    # T81: system night mode after boot.
    [switch]$Dark,
    # T81: emulator network speed and latency ('' leaves them).
    [string]$NetSpeed = '',
    [string]$NetDelay = '',
    # T81: boot (and apply the settings above) only.
    [switch]$BootOnly,
    # T81: emulator feature flags at boot (-feature X), e.g. GLESDynamicVersion: older images otherwise offer only
    # OpenGL ES 2 and MapLibre (ES 3) crashes in its render thread.
    [string[]]$Feature = @()
)
$ErrorActionPreference = 'Stop'
# T65: progress lines go to the output stream, not the host (Write-Host is the information stream in
# PowerShell 5.1, which `| Tee-Object`, `| Select-Object` and `> log` in the calling session never see), so a
# piped or redirected run shows each step as it happens. Functions below that return values never print.
function Write-Host { Write-Output ($args -join ' ') }
$repoRoot = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'Android\sdk' }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
if ($AvdHome) { $env:ANDROID_AVD_HOME = [IO.Path]::GetFullPath($AvdHome) }
elseif (-not $env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME = Join-Path $sdk 'avd' }
if ($Delete -and -not $Stop) { throw '-Delete goes with -Stop' }
if ($Api -gt 0) { $SystemImage = "system-images;android-$Api;google_apis;x86_64" }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$package = 'io.github.jasondorjeshort.dystopo'
$activity = "$package/io.github.jasondorjeshort.dystopo.app.MainActivity"
$devicePort = 8686
foreach ($tool in @($adb, $emulator)) { if (-not (Test-Path $tool)) { throw "$tool missing; run tools\android-sdk.ps1" } }
if ($EmuPort -ne 0 -and ($EmuPort % 2 -ne 0 -or $EmuPort -lt 5554 -or $EmuPort -gt 5682)) { throw "-EmuPort must be even, 5554-5682 (got $EmuPort)" }

function Native {
    # Runs a native tool without PowerShell turning its stderr into terminating errors.
    $ErrorActionPreference = 'Continue'
    $exe = $args[0]
    $rest = @($args | Select-Object -Skip 1)
    & $exe @rest 2>&1 | ForEach-Object { "$_" }
}

# adb against the chosen device only; never a bare `adb` that could pick whichever device is first.
$script:serial = ''
function Adb {
    if (-not $script:serial) { throw 'internal: adb call before a serial was chosen' }
    Native $adb -s $script:serial @args
}

# serial -> state ('device', 'offline', ...) for everything adb knows about.
function Get-AdbDevices {
    $map = @{}
    foreach ($line in (Native $adb devices)) {
        if ($line -match '^(\S+)\s+(device|offline|unauthorized|recovery|sideload|bootloader)\s*$') { $map[$Matches[1]] = $Matches[2] }
    }
    $map
}

# The AVD an emulator serial runs (from its console), or '' if it does not answer.
function Get-EmuAvdName([string]$s) {
    $out = Native $adb -s $s emu avd name
    $name = $out | Where-Object { $_ -and $_.Trim() -ne 'OK' -and $_ -notmatch 'error|KO' } | Select-Object -First 1
    if ($name) { $name.Trim() } else { '' }
}

function Find-AvdSerial {
    foreach ($s in (Get-AdbDevices).Keys) {
        if ($s -match '^emulator-\d+$' -and (Get-EmuAvdName $s) -eq $Avd) { return $s }
    }
    ''
}

function Test-PortFree([int]$p) {
    $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    -not ($listeners | Where-Object { $_.Port -eq $p -or $_.Port -eq ($p + 1) })
}

# `adb forward --list` lines: "<serial> tcp:<host> tcp:<device>".
function Get-Forwards {
    foreach ($line in (Native $adb forward --list)) {
        if ($line -match '^(\S+)\s+tcp:(\d+)\s+(\S+)') { [pscustomobject]@{ Serial = $Matches[1]; Port = [int]$Matches[2]; Remote = $Matches[3] } }
    }
}

# T57: the debug server's device port and token, from the app's logcat line `debug server on port N token T`
# (its current process only); waits up to $timeoutS seconds. Returns @(port, token) or $null.
function Get-DebugEndpoint([int]$timeoutS = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutS)
    while ($true) {
        $procId = (Adb shell pidof $package | Select-Object -First 1)
        if ($procId -and "$procId".Trim() -match '^\d+') {
            $lines = Adb logcat -d -v brief "--pid=$($Matches[0])" -s 'Dystopo:I'
            $hit = @($lines | Where-Object { $_ -match 'debug server on port (\d+) token ([0-9a-f]+)' } | Select-Object -Last 1)
            if ($hit -and $hit[0] -match 'debug server on port (\d+) token ([0-9a-f]+)') { return @([int]$Matches[1], $Matches[2]) }
        }
        if ((Get-Date) -gt $deadline) { return $null }
        Start-Sleep -Milliseconds 500
    }
}

# T57: forwards -Port to the debug server; returns the token ('' when the line never appeared: then 8686 and
# the /health bootstrap, the debugFixedPort mode of builds before T57).
function Connect-DebugServer {
    $taken = @(Get-Forwards | Where-Object { $_.Port -eq $Port -and $_.Serial -ne $script:serial })
    if ($taken) { throw "host port $Port is already forwarded to $($taken[0].Serial); pick another -Port" }
    $ep = Get-DebugEndpoint
    $remote = if ($ep) { $ep[0] } else { $devicePort }
    $fwd = Adb forward "tcp:$Port" "tcp:$remote"
    if ($LASTEXITCODE -ne 0) { throw "adb forward tcp:$Port failed: $fwd" }
    $script:debugRemote = $remote
    if ($ep) { $ep[1] } else { '' }
}

# 0. -Stop: kill this AVD's emulator and drop its forward.
if ($Stop) {
    if ($NoEmulator) { throw '-Stop applies to emulators only' }
    $s = ''
    if ($EmuPort) {
        $candidate = "emulator-$EmuPort"
        if ((Get-AdbDevices).ContainsKey($candidate)) {
            $name = Get-EmuAvdName $candidate
            if ($name -ne $Avd) { throw "$candidate runs AVD '$name', not '$Avd'; refusing to stop it" }
            $s = $candidate
        }
    } else { $s = Find-AvdSerial }
    if (-not $s) {
        $elsewhere = Find-AvdSerial
        if ($elsewhere -and $Delete) { throw "AVD $Avd runs as $elsewhere, not on -EmuPort $EmuPort; stop that one before deleting" }
        Write-Host "AVD $Avd is not running"
    } else {
        $script:serial = $s
        foreach ($f in @(Get-Forwards | Where-Object { $_.Serial -eq $s })) { Adb forward --remove "tcp:$($f.Port)" | Out-Null }
        Write-Host "stopping $s ($Avd)"
        Adb emu kill | Out-Null
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-AdbDevices).ContainsKey($s) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
        if ((Get-AdbDevices).ContainsKey($s)) { Write-Warning "$s still listed by adb after 60 s" } else { Write-Host "$s stopped" }
    }
    if ($Delete) {
        $ini = Join-Path $env:ANDROID_AVD_HOME "$Avd.ini"
        $dir = Join-Path $env:ANDROID_AVD_HOME "$Avd.avd"
        if (-not (Test-Path $ini) -and -not (Test-Path $dir)) { Write-Host "no AVD $Avd under $env:ANDROID_AVD_HOME"; exit 0 }
        $bytes = if (Test-Path $dir) { (Get-ChildItem -Recurse -Force -File $dir | Measure-Object Length -Sum).Sum } else { 0 }
        # The emulator process can hold its disk images for a few seconds after adb drops it.
        $deadline = (Get-Date).AddSeconds(60)
        while ($true) {
            try {
                if (Test-Path $dir) { Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction Stop }
                if (Test-Path $ini) { Remove-Item -LiteralPath $ini -Force -ErrorAction Stop }
                break
            } catch {
                if ((Get-Date) -gt $deadline) { throw "could not delete AVD ${Avd}: $_" }
                Start-Sleep -Seconds 3
            }
        }
        Write-Host ("deleted AVD $Avd ({0:n1} GB) from $env:ANDROID_AVD_HOME" -f ($bytes / 1GB))
    }
    exit 0
}

# T57: -Forward: the app restarted (new debug port and token); read them from logcat and re-forward -Port.
if ($Forward) {
    if ($NoEmulator) {
        $script:serial = if ($Serial) { $Serial } else { @((Get-AdbDevices).GetEnumerator() | Where-Object { $_.Value -eq 'device' -and $_.Key -notmatch '^emulator-' } | ForEach-Object { $_.Key })[0] }
    } elseif ($EmuPort) {
        $candidate = "emulator-$EmuPort"
        if ((Get-EmuAvdName $candidate) -ne $Avd) { throw "$candidate does not run AVD $Avd" }
        $script:serial = $candidate
    } else { $script:serial = Find-AvdSerial }
    if (-not $script:serial) { throw "no running device for -Forward" }
    $token = Connect-DebugServer
    Write-Host "ANDROID_SERIAL=$script:serial"
    Write-Host "Dystopo debug server: http://localhost:$Port -> device port $($script:debugRemote) (token $token)"
    exit 0
}

# 1. Build (optional) and the AVD.
if ($Build) {
    # A fresh worktree has no local.properties (it is gitignored); without it Gradle skips the Android module.
    $props = Join-Path $repoRoot 'local.properties'
    if (-not (Test-Path $props)) {
        $line = 'sdk.dir=' + ($sdk -replace '\\', '/' -replace ':', '\:')
        [IO.File]::WriteAllText($props, "$line`n")
        Write-Host "wrote $props ($line)"
    }
    Push-Location $repoRoot
    $buildStarted = Get-Date
    Write-Host 'building the debug APK (gradlew :androidApp:assembleDebug)'
    try {
        # Gradle's warnings arrive on stderr: Continue, so they are passed on as lines instead of stopping the script.
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat :androidApp:assembleDebug --console=plain -q 2>&1 | ForEach-Object { "  $_" }
        if ($LASTEXITCODE -ne 0) { throw 'assembleDebug failed' }
    } finally { Pop-Location; $ErrorActionPreference = 'Stop' }
    Write-Host ("built in {0:n0} s" -f ((Get-Date) - $buildStarted).TotalSeconds)
}
$apkPath = if ([IO.Path]::IsPathRooted($Apk)) { $Apk } else { Join-Path $repoRoot $Apk }
if (-not $BootOnly -and -not (Test-Path $apkPath)) { throw "APK not found: $apkPath (use -Build)" }

New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME | Out-Null
$imageDir = Join-Path $sdk (($SystemImage -split ';') -join '\')
if (-not $NoEmulator -and -not (Test-Path (Join-Path $imageDir 'system.img'))) {
    # T81: the matrix's other API levels. About 1 to 3 GB each under the SDK; remove with sdkmanager --uninstall.
    Write-Host "installing $SystemImage (sdkmanager)"
    $sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    ('y' + "`n") * 20 | Native $sdkmanager "--sdk_root=$sdk" ('"' + $SystemImage + '"') | Select-Object -Last 2 | ForEach-Object { "  $_" }
    if (-not (Test-Path (Join-Path $imageDir 'system.img'))) { throw "sdkmanager did not install $SystemImage" }
}
if (-not $NoEmulator -and -not (Test-Path (Join-Path $env:ANDROID_AVD_HOME "$Avd.ini"))) {
    Write-Host "creating AVD $Avd ($SystemImage, $Device)"
    $avdmanager = Join-Path $sdk 'cmdline-tools\latest\bin\avdmanager.bat'
    # ';' separates arguments in cmd.exe, so the package name goes in literal quotes.
    'no' | Native $avdmanager create avd -n $Avd -k ('"' + $SystemImage + '"') -d $Device --force | Out-Null
    $config = Join-Path $env:ANDROID_AVD_HOME "$Avd.avd\config.ini"
    if (-not (Test-Path $config)) { throw "avdmanager did not create $config" }
    $set = @{ 'hw.ramSize' = "$Memory"; 'hw.cpu.ncore' = "$Cores"; 'hw.keyboard' = 'yes' }
    $lines = @(Get-Content $config | Where-Object { $_ -notmatch ('^(' + (($set.Keys | ForEach-Object { [regex]::Escape($_) }) -join '|') + ')=') })
    $lines += $set.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
    Set-Content -Encoding ascii $config $lines
}

# 2. Choose the device, and boot unless this AVD is already running.
$proc = $null
if ($NoEmulator) {
    $phones = @((Get-AdbDevices).GetEnumerator() | Where-Object { $_.Value -eq 'device' -and $_.Key -notmatch '^emulator-' } | ForEach-Object { $_.Key })
    if ($Serial) {
        if ($phones -notcontains $Serial) { throw "device $Serial not connected (adb devices: $($phones -join ', '))" }
        $script:serial = $Serial
    } elseif ($phones.Count -eq 1) { $script:serial = $phones[0] }
    elseif ($phones.Count -eq 0) { throw 'no device connected (adb devices)' }
    else { throw "several devices connected ($($phones -join ', ')); pass -Serial" }
} else {
    $running = Find-AvdSerial
    if ($EmuPort) {
        $candidate = "emulator-$EmuPort"
        if ((Get-AdbDevices).ContainsKey($candidate)) {
            $name = Get-EmuAvdName $candidate
            if ($name -ne $Avd) { throw "$candidate already runs AVD '$name'; pick another -EmuPort for $Avd" }
        } elseif ($running) {
            throw "AVD $Avd already runs as $running; use -EmuPort $($running -replace 'emulator-', '') or -Stop it first"
        } elseif (-not (Test-PortFree $EmuPort)) {
            throw "ports $EmuPort/$($EmuPort + 1) are in use by another process; pick another -EmuPort"
        }
        $script:serial = $candidate
    } elseif ($running) {
        $script:serial = $running
    } else {
        $known = Get-AdbDevices
        $free = 5554..5682 | Where-Object { $_ % 2 -eq 0 -and -not $known.ContainsKey("emulator-$_") -and (Test-PortFree $_) } | Select-Object -First 1
        if (-not $free) { throw 'no free emulator console port in 5554-5682' }
        $EmuPort = $free
        $script:serial = "emulator-$EmuPort"
    }

    if (-not (Get-AdbDevices).ContainsKey($script:serial)) {
        $accelArgs = @()
        if ($Accel -eq 'off') { $accelArgs = @('-accel', 'off') }
        elseif ($Accel -eq 'auto') {
            $check = Native $emulator -accel-check
            if ($LASTEXITCODE -ne 0) {
                throw ("No hardware acceleration for the emulator (" + (($check | Select-Object -Skip 1 -First 1) -join ' ') + "). " +
                    "An administrator must do one of: (a) Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All, then reboot; " +
                    "(b) android sdk install extras/google/Android_Emulator_Hypervisor_Driver, then run " +
                    "<sdk>\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat as administrator. " +
                    "Or connect a phone with USB debugging and pass -NoEmulator. (-Accel off forces software emulation; it does not boot x86_64 images.)")
            }
        }
        $log = Join-Path $repoRoot "out\emulator-$Avd.log"
        New-Item -ItemType Directory -Force (Split-Path $log) | Out-Null
        $consolePort = $script:serial -replace 'emulator-', ''
        if ($Gpu -eq 'auto') {
            $hw = @(Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -and $_.Name -notmatch 'Basic Display|Basic Render|Remote|Hyper-V|Virtual|Parsec|Mirage' })
            $Gpu = if ($hw) { 'host' } else { 'swiftshader_indirect' }
            Write-Host ("gpu: $Gpu" + $(if ($hw) { " ($($hw[0].Name))" } else { ' (no hardware GPU found; MapLibre text and dashes will not draw)' }))
        }
        $emuArgs = @('-avd', $Avd, '-port', $consolePort, '-no-window', '-gpu', $Gpu, '-no-audio', '-no-boot-anim', '-no-snapshot-save',
            '-memory', "$Memory", '-cores', "$Cores") + $accelArgs
        if ($Locale) { $emuArgs += @('-change-locale', $Locale) }
        if ($NetSpeed) { $emuArgs += @('-netspeed', $NetSpeed) }
        if ($NetDelay) { $emuArgs += @('-netdelay', $NetDelay) }
        foreach ($f in @($Feature | ForEach-Object { $_ -split ',' } | Where-Object { $_ })) { $emuArgs += @('-feature', $f) }
        Write-Host ("starting emulator " + ($emuArgs -join ' ') + "  (log: $log)")
        $proc = Start-Process -FilePath $emulator -ArgumentList $emuArgs -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden -PassThru
    }
}
if (-not $proc -and -not $NoEmulator -and $PSBoundParameters.ContainsKey('Gpu')) {
    Write-Warning "$script:serial is already running; -Gpu $Gpu applies only at boot (use -Stop first to change it)"
}
$env:ANDROID_SERIAL = $script:serial  # for the child scripts and anything run from this process
Write-Host "device: $script:serial"

$deadline = (Get-Date).AddMinutes($BootTimeoutMinutes)
$started = Get-Date
Write-Host 'waiting for sys.boot_completed'
while ($true) {
    $booted = (Adb shell getprop sys.boot_completed) -join ''
    if ($booted.Trim() -eq '1') { break }
    if ($proc -and $proc.HasExited) {
        $tail = if (Test-Path $log) { (Get-Content $log -Tail 10) -join "`n" } else { '' }
        throw "emulator exited (code $($proc.ExitCode)) before booting. Log tail:`n$tail"
    }
    if ((Get-Date) -gt $deadline) { throw "emulator did not boot within $BootTimeoutMinutes minutes" }
    Start-Sleep -Seconds 5
}
if (-not $NoEmulator) {
    $name = Get-EmuAvdName $script:serial
    if ($name -ne $Avd) { throw "$script:serial runs AVD '$name', not '$Avd'" }
}
Write-Host ("booted after {0:n0} s" -f ((Get-Date) - $started).TotalSeconds)
# Animations off: faster, and screenshots never catch a transition.
foreach ($k in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') { Adb shell settings put global $k 0 | Out-Null }
Adb shell svc power stayon true | Out-Null
Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
Adb shell wm dismiss-keyguard | Out-Null
# T81: matrix and network settings (every run, so a reused emulator gets them too).
if ($FontScale -gt 0) { Adb shell settings put system font_scale ([string]$FontScale) | Out-Null; Write-Host "font scale $FontScale" }
if ($Dark) { Adb shell cmd uimode night yes | Out-Null; Write-Host 'night mode on' }
if (-not $NoEmulator) {
    if ($NetSpeed) { Adb emu network speed $NetSpeed | Out-Null; Write-Host "network speed $NetSpeed" }
    if ($NetDelay) { Adb emu network delay $NetDelay | Out-Null; Write-Host "network delay $NetDelay" }
}
if ($Locale) { Write-Host ("locale: " + ((Adb shell getprop persist.sys.locale) -join '').Trim()) }
if ($BootOnly) {
    Write-Host "ANDROID_SERIAL=$script:serial (boot only: nothing installed)"
    exit 0
}

# 3. Install (-g grants the location permission, so the app shows no dialog), push the pack, launch.
Write-Host "installing $Apk"
$install = Adb install -r -g $apkPath
if (($install -join ' ') -notmatch 'Success') { throw "install failed: $install" }
Adb shell am force-stop $package | Out-Null
if (-not $NoPush) {
    & (Join-Path $PSScriptRoot 'android-push-pack.ps1') -Dem $Dem -Osm $Osm -Package $package -Serial $script:serial -Name $PackName
}

# The host port must not already be forwarded to another device (that would be cross-talk).
$taken = @(Get-Forwards | Where-Object { $_.Port -eq $Port -and $_.Serial -ne $script:serial })
if ($taken) { throw "host port $Port is already forwarded to $($taken[0].Serial); pick another -Port" }
Adb logcat -c | Out-Null
Adb shell am start -W -n $activity | Out-Null
$token = Connect-DebugServer # T57: random debug port and token from logcat

$url = "http://localhost:$Port"
$deadline = (Get-Date).AddMinutes(5)
while ($true) {
    try {
        # T57: token from logcat; else (debugFixedPort) /health without the token gives it.
        if (-not $token) { $token = (Invoke-RestMethod -TimeoutSec 10 "$url/health").token }
        $health = Invoke-RestMethod -TimeoutSec 10 -Headers @{ 'X-Dystopo-Token' = $token } "$url/health"
        if ($health.map.styleLoaded) { break }
    } catch { $token = $null }
    if ((Get-Date) -gt $deadline) { Write-Warning 'app did not report a loaded style within 5 minutes'; break }
    Start-Sleep -Seconds 3
}
$stopArgs = if ($NoEmulator) { '' } else { "-Avd $Avd -EmuPort $($script:serial -replace 'emulator-', '') -Port $Port" + $(if ($AvdHome) { " -AvdHome $env:ANDROID_AVD_HOME" } else { '' }) + " -Stop [-Delete]" }
Write-Host ''
Write-Host "ANDROID_SERIAL=$script:serial"
Write-Host "Dystopo debug server: $url -> device port $($script:debugRemote) (token $token; after an app restart re-run with -Forward)"
Write-Host "  curl -H `"X-Dystopo-Token: $token`" $url/health"
Write-Host "  curl `"$url/stats?t=$token`""
Write-Host "  curl -X POST `"$url/camera?lat=46.853&lon=-121.76&zoom=12&t=$token`""
Write-Host "  curl -o out\phone.png `"$url/screenshot?t=$token`""
Write-Host "  python -I tools\android-check.py --port $Port --serial $script:serial --views-file tools\android-tour.txt"
if ($stopArgs) { Write-Host "  powershell -ExecutionPolicy Bypass -File tools\android-emu.ps1 $stopArgs" }
