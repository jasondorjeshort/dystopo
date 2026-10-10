# Installs or updates the Android SDK pieces Dystopo needs, without Android Studio and without touching
# machine-wide settings. Everything goes under $SdkRoot (default C:\Users\<you>\Android\sdk, or
# $env:ANDROID_HOME when set); the only file written outside it is local.properties in the repo root
# (gitignored), which tells Gradle where the SDK is.
#
#   powershell -ExecutionPolicy Bypass -File tools\android-sdk.ps1            # install / update
#   powershell -ExecutionPolicy Bypass -File tools\android-sdk.ps1 -NoEmulator  # build-only machine
#
# Needs a JDK 17+ (JAVA_HOME or java on PATH) for sdkmanager. Licenses are accepted non-interactively.
param(
    [string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'Android\sdk' }),
    [string]$Api = '35',
    [string]$BuildTools = '35.0.0',
    [switch]$NoEmulator
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is very slow with the progress bar on

$repoRoot = Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Force $SdkRoot | Out-Null

# 1. Command-line tools (sdkmanager, avdmanager): newest Windows zip listed in Google's repository index.
$sdkmanager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    $index = (Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/repository2-3.xml').Content
    $zipName = [regex]::Matches($index, 'commandlinetools-win-(\d+)_latest\.zip') |
        Sort-Object { [long]$_.Groups[1].Value } | Select-Object -Last 1 | ForEach-Object Value
    if (-not $zipName) { throw 'Could not find the command-line tools zip in the repository index.' }
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("dystopo-sdk-" + [guid]::NewGuid())
    New-Item -ItemType Directory $tmp | Out-Null
    try {
        $zip = Join-Path $tmp $zipName
        Write-Host "Downloading $zipName"
        Invoke-WebRequest -UseBasicParsing "https://dl.google.com/android/repository/$zipName" -OutFile $zip
        Expand-Archive $zip -DestinationPath $tmp
        # The zip holds cmdline-tools/{bin,lib}; sdkmanager expects <sdk>/cmdline-tools/latest/{bin,lib}.
        $dest = Join-Path $SdkRoot 'cmdline-tools\latest'
        New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
        if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
        Move-Item (Join-Path $tmp 'cmdline-tools') $dest
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

# 2. Packages. The system image is the newest google_apis x86_64 image for $Api.
$packages = @('platform-tools', "platforms;android-$Api", "build-tools;$BuildTools")
if (-not $NoEmulator) {
    $packages += 'emulator'
    $packages += "system-images;android-$Api;google_apis;x86_64"
}
$env:ANDROID_HOME = $SdkRoot
$androidCli = Join-Path $SdkRoot 'cmdline-tools\latest\bin\android.exe'
Write-Host ("Installing: " + ($packages -join ', '))
if (Test-Path $androidCli) {
    # Command-line tools 23+ replace sdkmanager with the `android` CLI, which names packages with
    # slashes (platforms/android-35) and shows the SDK terms instead of asking for each license.
    $slashed = $packages | ForEach-Object { $_ -replace ';', '/' }
    $ErrorActionPreference = 'Continue'   # the CLI's JVM prints a harmless warning on stderr
    # Large archives (the system image is ~1.5 GB) occasionally fail mid-download; retry a few times.
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        & $androidCli --sdk=$SdkRoot sdk install @slashed 2>&1 | ForEach-Object { "$_" } |
            Where-Object { $_ -notmatch '\[[#.]+\]|UseAllWindowsProcessorGroups|^At |^\+ |CategoryInfo|FullyQualifiedErrorId' }
        if ($LASTEXITCODE -eq 0) { break }
        Write-Host "android sdk install failed (exit $LASTEXITCODE), attempt $attempt"
    }
    if ($LASTEXITCODE -ne 0) { throw "android sdk install failed with exit code $LASTEXITCODE" }
    $ErrorActionPreference = 'Stop'
} else {
    # Older command-line tools: sdkmanager.bat. Package names contain ';', which cmd.exe treats as an
    # argument separator, so each one is passed in literal quotes.
    $yes = ('y' + [Environment]::NewLine) * 40
    $yes | & $sdkmanager --sdk_root=$SdkRoot --licenses | Out-Null
    $quoted = $packages | ForEach-Object { '"' + $_ + '"' }
    $yes | & $sdkmanager --sdk_root=$SdkRoot @quoted | Where-Object { $_ -notmatch '^\[=*\s*\]' }
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE" }
}
foreach ($p in $packages) {
    $dir = Join-Path $SdkRoot ($p -replace ';', '\')
    if (-not (Test-Path $dir)) { throw "package $p is missing after install ($dir)" }
}

# 3. local.properties for Gradle, escaped as .properties files require (Android lint checks it).
$props = Join-Path $repoRoot 'local.properties'
$line = 'sdk.dir=' + ($SdkRoot -replace '\\', '/' -replace ':', '\:')
$existing = if (Test-Path $props) { @(Get-Content $props | Where-Object { $_ -notmatch '^sdk\.dir=' }) } else { @() }
[IO.File]::WriteAllText($props, ((@($line) + $existing) -join "`n") + "`n")
Write-Host "Wrote $props ($line)"

& (Join-Path $SdkRoot 'platform-tools\adb.exe') version | Select-Object -First 1
Write-Host "SDK ready at $SdkRoot"
