# T293: the one step that signs an APK with the release key, where the key lives.
#
#   powershell -ExecutionPolicy Bypass -File tools\sign-release.ps1 -Apk <apk> [-Out <signed apk>] [-KeyRoot <checkout>]
#
# A task worktree has no release key (tools\release.properties and the keystore are gitignored, exist only in the main
# checkout, and agents are denied reading them), so its release build is signed with the debug key. This script re-signs
# such an APK with the SDK's apksigner, reading tools\release.properties of the main checkout (the parent of this
# repository's git common dir; -KeyRoot names another checkout) and passing the keystore's path and the passwords to
# apksigner through the environment of its process only. Nothing of the key is printed, copied or written: stdout is one
# JSON object with the signed APK's path, its bytes and SHA-256 and the certificate's SHA-256 (colon-separated, as the
# release skill quotes it, 47:31:38:10...); apksigner's own error text is printed with every password value masked.
# Without -Out the APK is signed in place (through a temporary file next to it, renamed over it on success).
#
# Exit 0 signed; 2 no release key in the key root (tools\release.properties absent: the APK is left as it was);
# 1 any other failure (no apksigner, a sign or verify error).
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Out = '',
    [string]$KeyRoot = ''
)
$ErrorActionPreference = 'Stop'

function Fail([int]$code, [string]$msg) {
    [ordered]@{ ok = $false; apk = $Apk; error = $msg } | ConvertTo-Json -Compress
    exit $code
}

if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { Fail 1 "no such APK: $Apk" }
$Apk = (Resolve-Path -LiteralPath $Apk).Path
if (-not $Out) { $Out = $Apk } else { $Out = [IO.Path]::GetFullPath($Out) }

# The key root: the main checkout (the parent of the git common dir of the repository this script is in).
if (-not $KeyRoot) {
    $common = "$(& git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir 2>$null)".Trim()
    $KeyRoot = if ($common) { Split-Path -Parent ($common -replace '/', '\') } else { Split-Path -Parent $PSScriptRoot }
}
$props = Join-Path $KeyRoot 'tools\release.properties'
if (-not (Test-Path -LiteralPath $props -PathType Leaf)) { Fail 2 "no release key: tools\release.properties is absent in $KeyRoot" }

# java.util.Properties' simple form, as androidApp/build.gradle.kts reads it (key=value or key: value, \-escapes).
$p = @{}
foreach ($line in [IO.File]::ReadAllLines($props)) {
    if ($line -match '^\s*([#!]|$)') { continue }
    if ($line -match '^\s*([^=:\s]+)\s*[=:\s]\s*(.*)$') { $p[$Matches[1]] = ($Matches[2].TrimEnd() -replace '\\(.)', '$1') }
}
$storeRel = if ($p['storeFile']) { $p['storeFile'] } else { 'tools/release.keystore' }
$store = if ([IO.Path]::IsPathRooted($storeRel)) { $storeRel } else { Join-Path $KeyRoot ($storeRel -replace '/', '\') }
if (-not (Test-Path -LiteralPath $store -PathType Leaf)) { Fail 1 "the keystore named by tools\release.properties is absent in $KeyRoot" }
$alias = if ($p['keyAlias']) { $p['keyAlias'] } else { 'dystopo' }
$storePass = $p['storePassword']
$keyPass = if ($p['keyPassword']) { $p['keyPassword'] } else { $storePass }
if (-not $storePass) { Fail 1 'tools\release.properties has no storePassword' }
$secrets = @($storePass, $keyPass) | Where-Object { $_ } | Sort-Object Length -Descending -Unique

function Mask([string]$t) { foreach ($s in $secrets) { $t = $t.Replace($s, '***') }; $t.Trim() }

# apksigner from the newest build-tools (as tools/android-ui.py's build_tools does).
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:USERPROFILE 'Android\sdk' }
$bt = Get-ChildItem -Directory (Join-Path $sdk 'build-tools') -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } | Select-Object -Last 1
if (-not $bt) { Fail 1 "no build-tools under $sdk" }
$signer = Join-Path $bt.FullName 'apksigner.bat'
if (-not (Test-Path -LiteralPath $signer)) { Fail 1 "no apksigner in $($bt.FullName)" }

function Invoke-Signer([string[]]$argv) {
    # Arguments carry no secret (the passwords go by env:), so a plain call is safe; output is masked all the same.
    # PowerShell 5.1 turns a native program's stderr into error records; under Stop the first one would throw.
    $ErrorActionPreference = 'Continue'
    $o = & $signer @argv 2>&1 | ForEach-Object { "$_" } | Out-String
    @{ code = $LASTEXITCODE; text = (Mask $o) }
}

$tmp = "$Out.signing.tmp"
if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
$env:DYSTOPO_SIGN_KS_PASS = $storePass
$env:DYSTOPO_SIGN_KEY_PASS = $keyPass
try {
    $r = Invoke-Signer @('sign', '--ks', $store, '--ks-key-alias', $alias, '--ks-pass', 'env:DYSTOPO_SIGN_KS_PASS',
        '--key-pass', 'env:DYSTOPO_SIGN_KEY_PASS', '--out', $tmp, $Apk)
} finally {
    Remove-Item Env:\DYSTOPO_SIGN_KS_PASS, Env:\DYSTOPO_SIGN_KEY_PASS -ErrorAction SilentlyContinue
}
if ($r.code -ne 0 -or -not (Test-Path -LiteralPath $tmp)) {
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
    Fail 1 "apksigner sign failed: $($r.text)"
}
$idsig = "$tmp.idsig"
if (Test-Path -LiteralPath $idsig) { Remove-Item -LiteralPath $idsig -Force }

$v = Invoke-Signer @('verify', '--print-certs', $tmp)
$hex = if ($v.text -match 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})') { $Matches[1].ToUpper() } else { '' }
if ($v.code -ne 0 -or -not $hex) {
    Remove-Item -LiteralPath $tmp -Force
    Fail 1 "apksigner verify failed: $($v.text)"
}
Move-Item -LiteralPath $tmp -Destination $Out -Force
$cert = (($hex -split '(..)' | Where-Object { $_ }) -join ':')
$item = Get-Item -LiteralPath $Out
[ordered]@{ ok = $true; apk = $Out; bytes = $item.Length; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Out).Hash.ToLower()
    certSha256 = $cert; signedFrom = $Apk; keyRoot = $KeyRoot; apksigner = $bt.Name } | ConvertTo-Json -Compress
exit 0
