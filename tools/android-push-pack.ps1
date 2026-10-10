# Copies a region pack onto the device or emulator for the debug app: the DEM PMTiles and the OSM vector
# PMTiles go to the app's files dir as files/packs/dem.pmtiles and files/packs/osm.pmtiles, and the label
# glyphs (Noto Sans PBF ranges for MapLibre, SIL OFL 1.1 fonts, fetched once into the cache) to
# files/glyphs/. Uses `run-as`, so it needs the debug build installed. Restart the app (or POST /reload
# on the debug server) afterwards.
#
#   powershell -ExecutionPolicy Bypass -File tools\android-push-pack.ps1 -Dem out\rainier-dem.pmtiles -Osm out\rainier-outdoors.pmtiles
param(
    [string]$Dem = 'out\rainier-dem.pmtiles',
    [string]$Osm = 'out\rainier-outdoors.pmtiles',
    [switch]$NoGlyphs,
    [string]$Package = 'io.github.jasondorjeshort.dystopo',
    [string]$Serial = ''
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repoRoot = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'Android\sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$adbArgs = if ($Serial) { @('-s', $Serial) } else { @() }

function Adb {
    $ErrorActionPreference = 'Continue'
    $out = & $adb @adbArgs @args 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') failed: $out" }
    $out
}

function Resolve-RepoPath([string]$p) { if ([IO.Path]::IsPathRooted($p)) { $p } else { Join-Path $repoRoot $p } }

$staging = '/data/local/tmp/dystopo'
Adb shell "rm -rf $staging && mkdir -p $staging/packs" | Out-Null
Adb shell "run-as $Package mkdir -p files/packs" | Out-Null

foreach ($pack in @(@{ Src = $Dem; Name = 'dem.pmtiles' }, @{ Src = $Osm; Name = 'osm.pmtiles' })) {
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
    # (Basic Latin, Latin-1, Latin Extended-A/B, general punctuation); other ranges get an empty response.
    $cacheRoot = if ($env:DYSTOPO_CACHE) { $env:DYSTOPO_CACHE } else { Join-Path $repoRoot 'cache' }
    $glyphDir = Join-Path $cacheRoot 'glyphs'
    $fonts = @('Noto Sans Regular', 'Noto Sans Italic', 'Noto Sans Medium')
    $ranges = @('0-255', '256-511', '8192-8447')
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

Adb shell "rm -rf $staging" | Out-Null
Adb shell "run-as $Package ls -l files/packs" | ForEach-Object { Write-Host $_ }
