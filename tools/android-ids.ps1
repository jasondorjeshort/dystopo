# T303: the app's two application ids, the one place the PowerShell scripts read them from (dot-source it:
# `. (Join-Path $PSScriptRoot 'android-ids.ps1')`). The Python scripts read the same facts from tools/android_debug.py.
# The debug build has its own id (androidApp/build.gradle.kts, applicationIdSuffix ".debug", labelled "Dystopo debug"),
# so it installs beside the release on one phone and never touches the release's data; its tile server is on device
# port 8688, the release's on 8686. The activity's class name is the Kotlin package's and does not change.
$DystopoReleasePackage = 'io.github.jasondorjeshort.dystopo'
$DystopoDebugPackage = "$DystopoReleasePackage.debug"
$DystopoMainActivity = 'io.github.jasondorjeshort.dystopo.app.MainActivity'
$DystopoReleaseTilePort = 8686
$DystopoDebugTilePort = 8688

# The launcher activity's component for `am start -n`.
function Get-DystopoActivity([string]$package) { "$package/$DystopoMainActivity" }

# A regex that matches the package's name in a log line but not the other build's (the release id is a prefix of the debug's).
function Get-DystopoPackagePattern([string]$package) { [regex]::Escape($package) + '(?!\.debug)' }
