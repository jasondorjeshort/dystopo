# Boots a headless emulator, installs the debug app, pushes the Rainier region pack, launches the app and
# forwards its debug server, so agents can `curl` it:
#
#   powershell -ExecutionPolicy Bypass -File tools\android-emu.ps1 -Build
#   curl http://localhost:8686/health        curl -o out\phone.png http://localhost:8686/screenshot
#
# Needs tools\android-sdk.ps1 first. The AVD lives under <sdk>\avd (ANDROID_AVD_HOME), next to the SDK.
# The emulator needs CPU virtualization: Windows Hypervisor Platform (WHPX) or the Android Emulator
# hypervisor driver (AEHD); both need an administrator once (see CLAUDE.md > Android). Without either the
# script stops with instructions: x86_64 images under pure software emulation (-accel off) start QEMU but
# never run the guest on emulator 37 (tested 2026-10), and ARM images are refused on x86_64 hosts.
# -Accel off still tries, for experiments. The GPU is always software (swiftshader), as -no-window needs.
param(
    [string]$Avd = 'dystopo35',
    [string]$SystemImage = 'system-images;android-35;google_apis;x86_64',
    [string]$Device = 'pixel_6',
    [int]$Port = 8686,
    [string]$Apk = 'androidApp\build\outputs\apk\debug\androidApp-debug.apk',
    [switch]$Build,
    [string]$Dem = 'out\rainier-dem.pmtiles',
    [string]$Osm = 'out\rainier-outdoors.pmtiles',
    [switch]$NoPush,
    [int]$BootTimeoutMinutes = 60,
    [ValidateSet('auto', 'on', 'off')] [string]$Accel = 'auto',
    [int]$Memory = 4096,
    [int]$Cores = 4,
    # Use the already connected device (a phone with USB debugging) instead of an emulator.
    [switch]$NoEmulator
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'Android\sdk' }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
if (-not $env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME = Join-Path $sdk 'avd' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$package = 'io.github.jasondorjeshort.dystopo'
$activity = "$package/io.github.jasondorjeshort.dystopo.app.MainActivity"
foreach ($tool in @($adb, $emulator)) { if (-not (Test-Path $tool)) { throw "$tool missing; run tools\android-sdk.ps1" } }

function Native {
    # Runs a native tool without PowerShell turning its stderr into terminating errors.
    $ErrorActionPreference = 'Continue'
    $exe = $args[0]
    $rest = @($args | Select-Object -Skip 1)
    & $exe @rest 2>&1 | ForEach-Object { "$_" }
}

# 1. Build (optional) and the AVD.
if ($Build) {
    Push-Location $repoRoot
    try {
        & .\gradlew.bat :androidApp:assembleDebug --console=plain -q
        if ($LASTEXITCODE -ne 0) { throw 'assembleDebug failed' }
    } finally { Pop-Location }
}
$apkPath = if ([IO.Path]::IsPathRooted($Apk)) { $Apk } else { Join-Path $repoRoot $Apk }
if (-not (Test-Path $apkPath)) { throw "APK not found: $apkPath (use -Build)" }

New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME | Out-Null
if (-not $NoEmulator -and -not (Test-Path (Join-Path $env:ANDROID_AVD_HOME "$Avd.ini"))) {
    Write-Host "creating AVD $Avd ($SystemImage, $Device)"
    $avdmanager = Join-Path $sdk 'cmdline-tools\latest\bin\avdmanager.bat'
    # ';' separates arguments in cmd.exe, so the package name goes in literal quotes.
    'no' | Native $avdmanager create avd -n $Avd -k ('"' + $SystemImage + '"') -d $Device --force | Out-Null
    $config = Join-Path $env:ANDROID_AVD_HOME "$Avd.avd\config.ini"
    Add-Content -Encoding ascii $config "hw.ramSize=$Memory`nhw.cpu.ncore=$Cores`nhw.keyboard=yes"
}

# 2. Boot (unless an emulator is already online).
$online = (Native $adb devices) | Where-Object { $_ -match '^emulator-\d+\s+device$' }
if ($NoEmulator) {
    $online = (Native $adb devices) | Where-Object { $_ -match '\sdevice$' }
    if (-not $online) { throw 'no device connected (adb devices)' }
} elseif (-not $online) {
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
    $log = Join-Path $repoRoot 'out\emulator.log'
    New-Item -ItemType Directory -Force (Split-Path $log) | Out-Null
    $emuArgs = @('-avd', $Avd, '-no-window', '-gpu', 'swiftshader_indirect', '-no-audio', '-no-boot-anim', '-no-snapshot-save',
        '-memory', "$Memory", '-cores', "$Cores") + $accelArgs
    Write-Host ("starting emulator " + ($emuArgs -join ' ') + "  (log: $log)")
    Start-Process -FilePath $emulator -ArgumentList $emuArgs -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden | Out-Null
}

$deadline = (Get-Date).AddMinutes($BootTimeoutMinutes)
$started = Get-Date
Write-Host 'waiting for sys.boot_completed'
while ($true) {
    $booted = (Native $adb shell getprop sys.boot_completed) -join ''
    if ($booted.Trim() -eq '1') { break }
    if ((Get-Date) -gt $deadline) { throw "emulator did not boot within $BootTimeoutMinutes minutes" }
    Start-Sleep -Seconds 10
}
Write-Host ("booted after {0:n0} s" -f ((Get-Date) - $started).TotalSeconds)
# Animations off: faster, and screenshots never catch a transition.
foreach ($k in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') { Native $adb shell settings put global $k 0 | Out-Null }
Native $adb shell svc power stayon true | Out-Null
Native $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
Native $adb shell wm dismiss-keyguard | Out-Null

# 3. Install (-g grants the location permission, so the app shows no dialog), push the pack, launch.
Write-Host "installing $Apk"
$install = Native $adb install -r -g $apkPath
if (($install -join ' ') -notmatch 'Success') { throw "install failed: $install" }
Native $adb shell am force-stop $package | Out-Null
if (-not $NoPush) {
    & (Join-Path $PSScriptRoot 'android-push-pack.ps1') -Dem $Dem -Osm $Osm -Package $package
}
Native $adb forward "tcp:$Port" "tcp:$Port" | Out-Null
Native $adb shell am start -W -n $activity | Out-Null

$url = "http://localhost:$Port"
$deadline = (Get-Date).AddMinutes(5)
while ($true) {
    try {
        $health = Invoke-RestMethod -TimeoutSec 10 "$url/health"
        if ($health.map.styleLoaded) { break }
    } catch { }
    if ((Get-Date) -gt $deadline) { Write-Warning 'app did not report a loaded style within 5 minutes'; break }
    Start-Sleep -Seconds 3
}
Write-Host ''
Write-Host "Dystopo debug server: $url"
Write-Host "  curl $url/health"
Write-Host "  curl $url/stats"
Write-Host "  curl -X POST `"$url/camera?lat=46.853&lon=-121.76&zoom=12`""
Write-Host "  curl -o out\phone.png $url/screenshot"
