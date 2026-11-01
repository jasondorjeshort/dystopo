<#
.SYNOPSIS
  Is the home site up? One line per check, status words and counts only; exit 1 on any failure.

.DESCRIPTION
  Reads <Root>\site.env (written by tools\site\install.ps1) and checks:
    - both scheduled tasks ("Dystopo site server<Name>", "Dystopo site proxy<Name>"): state, last result,
      logon type;
    - /healthz on 127.0.0.1 (status, time, uptime, packs) and through Caddy on 127.0.0.1:443 with the
      site's host name (the router and the dynamic-DNS name are not in the path);
    - /metrics through Caddy must be 403;
    - Caddy's certificate as served: issuer kind, hours or days left, whether Windows trusts it;
    - counts from /stats and /metrics on 127.0.0.1: requests, 5xx, tiles served, live renders, errors.
  It prints no URL, host name, path or log line, so its output can be pasted anywhere.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\site\status.ps1
#>
[CmdletBinding()]
param(
    [string]$Root = (Join-Path $env:USERPROFILE '.dystopo\site'),
    [string]$Name = ''
)

$ErrorActionPreference = 'Stop'
$Root = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Root).TrimEnd('\', '/')
$script:failures = 0

function Line([string]$what, [string]$text, [bool]$ok) {
    $mark = if ($ok) { 'ok  ' } else { 'FAIL'; $script:failures++ }
    Write-Host ("{0} {1,-16} {2}" -f $mark, $what, $text)
}

$envFile = Join-Path $Root 'site.env'
if (-not (Test-Path -LiteralPath $envFile)) { Line 'install' 'no site.env: not installed (tools\site\install.ps1)' $false; exit 1 }
$siteEnv = @{}
foreach ($l in Get-Content -LiteralPath $envFile) { if ($l -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $siteEnv[$matches[1]] = $matches[2] } }
$port = [int]$siteEnv['DYSTOPO_SITE_PORT']
$siteHost = $siteEnv['DYSTOPO_SITE_HOST']
$tmp = Join-Path $env:TEMP ("dystopo-site-status-{0}.json" -f $PID)

# GET over curl.exe: status code, milliseconds, and the body parsed as JSON when it is JSON.
function Get-Url([string]$url, [string[]]$extra = @()) {
    $out = & curl.exe -s -o $tmp -w '%{http_code} %{time_total}' --max-time 20 --ssl-revoke-best-effort @extra $url 2>$null
    $code = 0; $ms = 0; $body = $null
    if ($out -match '^(\d+) ([\d.]+)') { $code = [int]$matches[1]; $ms = [int]([double]::Parse($matches[2], [Globalization.CultureInfo]::InvariantCulture) * 1000) }
    if ($code -gt 0 -and (Test-Path -LiteralPath $tmp)) { try { $body = Get-Content -LiteralPath $tmp -Raw | ConvertFrom-Json } catch { $body = $null } }
    Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
    @{ code = $code; ms = $ms; body = $body }
}

function Format-Result([int64]$r) {
    switch ($r) { 0 { 'last result 0' } 267009 { 'running' } 267011 { 'never run' } 267014 { 'stopped by request' } 2147946720 { 'a start skipped while it ran' } -2147020576 { 'a start skipped while it ran' } default { 'last result 0x{0:X}' -f $r } }
}

# --- the tasks -----------------------------------------------------------------------------------------
$svc = New-Object -ComObject Schedule.Service
$svc.Connect()
$running = @{}
foreach ($rt in @($svc.GetRunningTasks(0))) { $running[$rt.Path] = [int]$rt.EnginePID }
foreach ($kind in @('server', 'proxy')) {
    $taskName = "Dystopo site $kind$Name"
    $t = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue | Where-Object { $_.TaskPath -eq '\' }
    if (-not $t) { Line "$kind task" 'not registered' $false; continue }
    $info = Get-ScheduledTaskInfo -TaskName $taskName
    $logon = [string]$t.Principal.LogonType
    $isRunning = $running.ContainsKey("\$taskName")
    Line "$kind task" ("{0}, {1}, logon {2}" -f $t.State, (Format-Result $info.LastTaskResult), $logon) $isRunning
}

# --- the server, direct --------------------------------------------------------------------------------
$h = Get-Url "http://127.0.0.1:$port/healthz"
if ($h.code -eq 200 -and $h.body) {
    $up = [int64]$h.body.uptimeS
    $upText = if ($up -ge 86400) { '{0:N1} days' -f ($up / 86400) } elseif ($up -ge 3600) { '{0:N1} h' -f ($up / 3600) } else { "$up s" }
    Line 'server' ("healthz 200 in {0} ms, up {1}, {2} packs, catalog {3}" -f $h.ms, $upText, $h.body.packs, $h.body.catalog.state) $true
} else {
    Line 'server' ("healthz {0} (0: no answer)" -f $h.code) $false
}

# --- the proxy -----------------------------------------------------------------------------------------
# Straight to 127.0.0.1:443 with the site's name, so the check does not depend on the router or the DNS.
$curlTls = @('--resolve', "${siteHost}:443:127.0.0.1")
$rootCrt = Join-Path $Root 'caddy\data\caddy\pki\authorities\local\root.crt'
if ($siteHost -eq 'localhost' -and (Test-Path -LiteralPath $rootCrt)) { $curlTls += @('--cacert', $rootCrt) }
$p = Get-Url "https://${siteHost}/healthz" $curlTls
if ($p.code -eq 200) { Line 'proxy' ("healthz 200 in {0} ms, certificate verified" -f $p.ms) $true }
else { Line 'proxy' ("healthz {0} (0: no answer, or the certificate did not verify)" -f $p.code) $false }
$m = Get-Url "https://${siteHost}/metrics" $curlTls
Line 'proxy metrics' ("{0} (403 expected: not public)" -f $m.code) ($m.code -eq 403)

# --- the certificate Caddy serves ----------------------------------------------------------------------
try {
    $tcp = New-Object Net.Sockets.TcpClient
    $tcp.ReceiveTimeout = 10000
    $tcp.Connect('127.0.0.1', 443)
    $script:tlsErrors = $null
    $cb = [Net.Security.RemoteCertificateValidationCallback] { param($s, $c, $ch, $e) $script:tlsErrors = $e; $true }
    $ssl = New-Object Net.Security.SslStream($tcp.GetStream(), $false, $cb)
    $ssl.AuthenticateAsClient($siteHost)
    $cert = New-Object Security.Cryptography.X509Certificates.X509Certificate2($ssl.RemoteCertificate)
    $ssl.Dispose(); $tcp.Dispose()
    $left = $cert.NotAfter.ToUniversalTime() - [DateTime]::UtcNow
    $leftText = if ($left.TotalDays -ge 2) { '{0:N0} days left' -f $left.TotalDays } else { '{0:N1} h left' -f $left.TotalHours }
    $internal = $cert.Issuer -match 'Caddy Local Authority'
    $trusted = ($script:tlsErrors -eq [Net.Security.SslPolicyErrors]::None)
    if ($internal) {
        # localhost: Caddy's own CA, renewed by Caddy every few hours; Windows is not asked to trust it.
        Line 'certificate' ("Caddy internal CA, {0}, not trusted by Windows (expected for localhost)" -f $leftText) ($left.TotalSeconds -gt 0)
    } else {
        $issuer = if ($cert.Issuer -match 'O=([^,]+)') { $matches[1].Trim('"') } else { 'public CA' }
        # Caddy renews a public certificate with about a third of its life left; under 10 days means renewals fail.
        Line 'certificate' ("{0}, {1}, trusted {2}" -f $issuer, $leftText, $(if ($trusted) { 'yes' } else { 'no' })) ($trusted -and $left.TotalDays -gt 10)
    }
} catch {
    Line 'certificate' ("no TLS answer on 443 ({0})" -f $_.Exception.GetType().Name) $false
}

# --- counts --------------------------------------------------------------------------------------------
$s = Get-Url "http://127.0.0.1:$port/stats"
$mt = Get-Url "http://127.0.0.1:$port/metrics"
if ($s.code -eq 200 -and $s.body -and $mt.code -eq 200 -and $mt.body) {
    $tiles = 0
    foreach ($route in $s.body.tiles.PSObject.Properties) { foreach ($src in $route.Value.PSObject.Properties) { $tiles += [int64]$src.Value.tiles } }
    $requests = 0; $errors5 = 0
    foreach ($r in $mt.body.requests.PSObject.Properties) { $requests += [int64]$r.Value.count; $errors5 += [int64]$r.Value.status.'5xx' }
    Line 'counts' ("{0} requests, {1} 5xx, {2} tiles served, {3} live renders, {4} tile errors" -f $requests, $errors5, $tiles, $s.body.liveDemRendered, $s.body.errors) $true
} else {
    Line 'counts' ("stats {0}, metrics {1}" -f $s.code, $mt.code) $false
}

if ($script:failures -gt 0) { Write-Host "site: $($script:failures) check(s) failed"; exit 1 }
Write-Host 'site: all checks passed'
exit 0
