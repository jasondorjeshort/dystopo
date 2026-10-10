# Copies a region pack onto the device or emulator for the debug app: the DEM PMTiles and the OSM vector
# PMTiles go to the app's files dir as files/packs/dem.pmtiles and files/packs/osm.pmtiles (with -Name rainier:
# files/packs/rainier-dem.pmtiles and rainier-outdoors.pmtiles, a named pack), and the label
# glyphs (Noto Sans PBF ranges for MapLibre, SIL OFL 1.1 fonts, fetched once into the cache) to
# files/glyphs/, and the marker sprite sheet (`devtool sprite` into out/, remade on every push)
# to files/sprites/. Uses `run-as`, so it needs the debug build installed. Restart the app (or POST /reload
# on the debug server) afterwards.
#
#   powershell -ExecutionPolicy Bypass -File tools\android-push-pack.ps1 -Dem out\rainier-dem.pmtiles -Osm out\rainier-outdoors.pmtiles
#
# A DEM-only pack: -NoOsm (or -Osm none). Not -Osm '': Windows PowerShell drops an empty argument on its way
# to `powershell -File`, so the script would see -Osm without a value and refuse to start.
param(
    [string]$Dem = 'out\rainier-dem.pmtiles',
    [string]$Osm = 'out\rainier-outdoors.pmtiles',
    [switch]$NoGlyphs,
    [switch]$NoOsm,
    [switch]$NoSprites,
    [string]$Package = 'io.github.jasondorjeshort.dystopo',
    [string]$Name = '',
    [string]$Serial = ''
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repoRoot = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'Android\sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
# Always target one device explicitly: -Serial, else ANDROID_SERIAL, else the only device adb lists.
# With several devices and neither set, stop rather than push to whichever adb picks.
if (-not $Serial) { $Serial = $env:ANDROID_SERIAL }
if (-not $Serial) {
    $ErrorActionPreference = 'Continue'
    $devices = @(& $adb devices 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -match '^(\S+)\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
    $ErrorActionPreference = 'Stop'
    if ($devices.Count -eq 1) { $Serial = $devices[0] }
    elseif ($devices.Count -eq 0) { throw 'no device connected (adb devices)' }
    else { throw "several devices connected ($($devices -join ', ')); pass -Serial or set ANDROID_SERIAL" }
}
Write-Host "device: $Serial"
$adbArgs = @('-s', $Serial)

function Adb {
    $ErrorActionPreference = 'Continue'
    $out = & $adb @adbArgs @args 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') failed: $out" }
    $out
}

function Resolve-RepoPath([string]$p) { if ([IO.Path]::IsPathRooted($p)) { $p } else { Join-Path $repoRoot $p } }

if ($NoOsm -or $Osm -in @('none', '-')) { $Osm = '' }

$staging = '/data/local/tmp/dystopo'
Adb shell "rm -rf $staging && mkdir -p $staging/packs" | Out-Null
Adb shell "run-as $Package mkdir -p files/packs" | Out-Null

# -Name pushes a named pack (T29): files/packs/<name>-dem.pmtiles and <name>-outdoors.pmtiles. Without it the
# unnamed pair dem.pmtiles / osm.pmtiles is written (the app treats it as pack `default`).
if ($Name -and $Name -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$') { throw "-Name must be letters, digits, '.', '_' or '-' (got '$Name')" }
$demName = if ($Name) { "$Name-dem.pmtiles" } else { 'dem.pmtiles' }
$osmName = if ($Name) { "$Name-outdoors.pmtiles" } else { 'osm.pmtiles' }
foreach ($pack in @(@{ Src = $Dem; Name = $demName }, @{ Src = $Osm; Name = $osmName })) {
    if (-not $pack.Src) { continue }
    $src = Resolve-RepoPath $pack.Src
    if (-not (Test-Path $src)) { throw "pack not found: $src" }
    $mb = [math]::Round((Get-Item $src).Length / 1MB, 1)
    Write-Host "pushing $($pack.Src) ($mb MB) -> files/packs/$($pack.Name)"
    Adb push $src "$staging/packs/$($pack.Name)" | Out-Null
    Adb shell "run-as $Package cp $staging/packs/$($pack.Name) files/packs/$($pack.Name)" | Out-Null
}

if (-not $NoGlyphs) {
    # MapLibre loads label glyphs as PBF ranges. These three fonts and ranges cover the style's text
    # (Latin, Greek, Cyrillic, Armenian, Hebrew, Arabic, Thai, Georgian, general punctuation; the second
    # name line in the local script needs them); other ranges get an empty response. The range list must
    # equal MapLibreStyle.GLYPH_RANGES (GlyphRangesTest checks).
    # The font list must equal MapLibreStyle.FONTS (GlyphFontsTest checks). The app points MapLibre at
    # files/glyphs with a file:// URL (Glyphs.kt), so a missing range is simply skipped.
    $cacheRoot = if ($env:DYSTOPO_CACHE) { $env:DYSTOPO_CACHE } else { Join-Path $repoRoot 'cache' }
    $glyphDir = Join-Path $cacheRoot 'glyphs'
    $fonts = @('Noto Sans Regular', 'Noto Sans Italic', 'Noto Sans Medium')
    $ranges = @('0-255', '256-511', '512-767', '768-1023', '1024-1279', '1280-1535', '1536-1791', '1792-2047', '3584-3839', '4096-4351', '7680-7935', '8192-8447')
    foreach ($font in $fonts) {
        $dir = Join-Path $glyphDir $font
        New-Item -ItemType Directory -Force $dir | Out-Null
        foreach ($range in $ranges) {
            $file = Join-Path $dir "$range.pbf"
            if (-not (Test-Path $file)) {
                $url = 'https://protomaps.github.io/basemaps-assets/fonts/' + [uri]::EscapeDataString($font) + "/$range.pbf"
                Invoke-WebRequest -UseBasicParsing $url -OutFile $file
            }
        }
    }
    Write-Host "pushing glyphs ($($fonts.Count) fonts x $($ranges.Count) ranges)"
    Adb push $glyphDir "$staging/" | Out-Null
    Adb shell "run-as $Package rm -rf files/glyphs && run-as $Package cp -r $staging/glyphs files/glyphs" | Out-Null
}

if (-not $NoSprites) {
    # Marker icons for MapLibre (Sprites.kt spec, drawn by devtool sprite with the renderer's shapes). The app
    # points MapLibre at files/sprites/sprite with a file:// URL (SpriteFiles.kt); without all four files the
    # style falls back to marker discs.
    $spriteFiles = @('sprite.json', 'sprite.png', 'sprite@2x.json', 'sprite@2x.png')
    $outDir = Join-Path $repoRoot 'out'
    # Always remade (a few seconds with a warm Gradle daemon): a sheet left in out/ from an older marker table would
    # lack icons the style names.
    Write-Host 'making the sprite sheet (devtool sprite)'
    Push-Location $repoRoot
    try {
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat :devtool:run --args='sprite' --console=plain -q | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'devtool sprite failed' }
    } finally { Pop-Location; $ErrorActionPreference = 'Stop' }
    Adb shell "mkdir -p $staging/sprites" | Out-Null
    foreach ($f in $spriteFiles) { Adb push (Join-Path $outDir $f) "$staging/sprites/$f" | Out-Null }
    Write-Host "pushing sprites ($($spriteFiles.Count) files)"
    Adb shell "run-as $Package rm -rf files/sprites && run-as $Package cp -r $staging/sprites files/sprites" | Out-Null
}

Adb shell "rm -rf $staging" | Out-Null
Adb shell "run-as $Package ls -l files/packs" | ForEach-Object { Write-Host $_ }
