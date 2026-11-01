<#
.SYNOPSIS
  Install, update or remove the home site: the standalone server and a Caddy TLS proxy as two Windows
  scheduled tasks that start with the PC (docs/reference/home-site.md, ADR 0052).

.DESCRIPTION
  Install (no switch):
    - snapshots the distributions (./gradlew :server:installDist :tilebuild:installDist in this checkout,
      copied to <Root>\server and <Root>\tilebuild) when <Root>\server is missing, so a build in any
      checkout never alters the running site;
    - writes <Root>\server.json from tools\site\server.example.json (bind 127.0.0.1, -Port, cache, out,
      tilebuild and the rotated JSON log under the root, a generated token, publicUrl https://<Host>;
      other keys of an existing server.json are kept) and <Root>\site.env (the host, the port, the token,
      JAVA_HOME as it is now, JAVA_OPTS=-Xmx2g, Caddy's folders);
    - downloads Caddy (the official windows_amd64 release asset, SHA-512 checked against the release's
      checksums file) into <Root>\caddy and copies tools\site\Caddyfile there;
    - registers "Dystopo site server<Name>" (javaw.exe from the recorded JDK, the distribution's lib\*
      classpath) and "Dystopo site proxy<Name>" (caddy run --envfile site.env) for the current user:
      logon type S4U (no stored password, no window, runs before anyone logs on), an at-startup trigger
      plus a trigger every minute that starts the task again when it is not running (restart-on-failure
      alone does not restart a killed process), no time limit, battery allowed, restart on failure;
    - starts both and waits for /healthz.
  Task Scheduler refuses S4U and at-startup triggers from a PowerShell that is not elevated; the script
  then says so and prints the one command to run as administrator. -AtLogon registers interactive tasks
  with an at-logon trigger instead (no elevation; they run only while you are logged on and Caddy shows a
  console window): for test instances.

  -Update: rebuilds and re-copies the distributions, takes a newer Caddy release, rewrites the configs
  (token and host kept) and restarts both tasks. -Firewall (elevated): adds the inbound rule for TCP 80
  and 443 to caddy.exe. -Uninstall: stops and removes both tasks and the rule; the folder (token, cache,
  packs) stays, delete it by hand.
  A Stop-ScheduledTask is a hard kill: no graceful shutdown (docs/SERVER.md, Shutdown).

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\site\install.ps1 -Host maps.example.org -Firewall   # as administrator
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\site\install.ps1 -Update
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\site\install.ps1 -Root $env:TEMP\site-test -Name " test" -Port 8693 -AtLogon
#>
[CmdletBinding()]
param(
    [string]$Root = (Join-Path $env:USERPROFILE '.dystopo\site'),
    [string]$Name = '',
    [int]$Port = 8801,
    [Alias('Host')][string]$SiteHost = 'localhost',
    [switch]$Firewall,
    [switch]$Update,
    [switch]$Uninstall,
    [switch]$AtLogon
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Root = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Root).TrimEnd('\', '/')
$rootFwd = $Root -replace '\\', '/'
$serverTask = "Dystopo site server$Name"
$proxyTask = "Dystopo site proxy$Name"
$ruleName = "Dystopo site proxy$Name (TCP 80, 443)"
$envFile = Join-Path $Root 'site.env'
$configFile = Join-Path $Root 'server.json'
$caddyDir = Join-Path $Root 'caddy'
$caddyExe = Join-Path $caddyDir 'caddy.exe'
$mainClass = 'io.github.jasondorjeshort.dystopo.server.MainKt'
$isAdmin = (New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
$elevatedHint = 'from an administrator PowerShell (right-click PowerShell, Run as administrator)'

function Say([string]$text) { Write-Host "site: $text" }
function Fail([string]$text, [int]$code = 1) { Write-Host "site: $text" -ForegroundColor Red; exit $code }

if ($Name -notmatch '^[ A-Za-z0-9._-]*$') { Fail "-Name may hold letters, digits, spaces, dots, dashes and underscores only" }
if ($SiteHost -notmatch '^[A-Za-z0-9.-]+$') { Fail "-Host must be a bare host name (no scheme, port or path)" }

# --- helpers -------------------------------------------------------------------------------------------

function Read-SiteEnv {
    $h = @{}
    if (Test-Path -LiteralPath $envFile) {
        foreach ($line in Get-Content -LiteralPath $envFile) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $h[$matches[1]] = $matches[2] }
        }
    }
    $h
}

function Get-Task([string]$taskName) { Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue | Where-Object { $_.TaskPath -eq '\' } }

# The PID of a task's running action (Task Scheduler's own record, not a process-name match).
function Get-TaskPid([string]$taskName) {
    $svc = New-Object -ComObject Schedule.Service
    $svc.Connect()
    foreach ($t in @($svc.GetRunningTasks(0))) { if ($t.Path -eq "\$taskName") { return [int]$t.EnginePID } }
    $null
}

function Get-HttpCode([string]$url) {
    $code = & curl.exe -s -o NUL -w '%{http_code}' --max-time 10 $url 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $code) { return 0 }
    [int]$code
}

function Wait-Healthz([int]$seconds) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $seconds) {
        if ((Get-HttpCode "http://127.0.0.1:$Port/healthz") -eq 200) { return [int]$sw.Elapsed.TotalSeconds }
        Start-Sleep -Seconds 2
    }
    -1
}

function Stop-SiteTasks([switch]$Disable) {
    foreach ($t in @($serverTask, $proxyTask)) {
        if (-not (Get-Task $t)) { continue }
        # Disabled first, so the every-minute trigger cannot start the task again in the middle of an update.
        if ($Disable) { Disable-ScheduledTask -TaskName $t | Out-Null }
        $taskPid = Get-TaskPid $t
        Stop-ScheduledTask -TaskName $t
        if ($taskPid) {
            for ($i = 0; $i -lt 30 -and (Get-Process -Id $taskPid -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Milliseconds 500 }
        }
    }
}

function New-Token {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# Only the user, SYSTEM and Administrators may read a file that holds the token.
function Protect-File([string]$path) {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $path /inheritance:r /grant:r "*${sid}:F" '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
}

function Get-JavaHome([hashtable]$siteEnv) {
    $candidates = @($env:JAVA_HOME, $siteEnv['JAVA_HOME']) | Where-Object { $_ }
    foreach ($c in $candidates) {
        $release = Join-Path $c 'release'
        if (-not (Test-Path -LiteralPath (Join-Path $c 'bin\javaw.exe')) -or -not (Test-Path -LiteralPath $release)) { continue }
        $v = Get-Content -LiteralPath $release -Raw
        if ($v -match 'JAVA_VERSION="(\d+)' -and [int]$matches[1] -ge 21) { return ($c.TrimEnd('\', '/')) }
    }
    Fail "JAVA_HOME must name a JDK 21 (docs/reference/machines.md)"
}

function Build-Snapshot([string]$javaHome) {
    Say "building the server and tilebuild distributions in this checkout"
    $env:JAVA_HOME = $javaHome
    Push-Location $repo
    try {
        & .\gradlew.bat :server:installDist :tilebuild:installDist --no-daemon -q
        if ($LASTEXITCODE -ne 0) { Fail "gradle failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
}

function Copy-Snapshot {
    foreach ($m in @('server', 'tilebuild')) {
        $from = Join-Path $repo "$m\build\install\$m"
        $to = Join-Path $Root $m
        if (-not (Test-Path -LiteralPath (Join-Path $from 'lib'))) { Fail "no $m distribution in this checkout ($m\build\install\$m)" }
        if (Test-Path -LiteralPath $to) { Remove-Item -LiteralPath ('\\?\' + $to) -Recurse -Force }
        Copy-Item -LiteralPath $from -Destination $to -Recurse
    }
    Say "copied the server and tilebuild distributions into the site folder"
}

# Caddy: the latest release's windows_amd64 zip, checked against the release's own SHA-512 list (integrity
# of the download, not provenance). Returns $true when caddy.exe was (re)placed.
function Get-Caddy([switch]$Refresh) {
    $verFile = Join-Path $caddyDir 'version.txt'
    if ((Test-Path -LiteralPath $caddyExe) -and -not $Refresh) { return $false }
    try {
        $rel = Invoke-RestMethod -UseBasicParsing -Uri 'https://api.github.com/repos/caddyserver/caddy/releases/latest' -Headers @{ 'User-Agent' = 'dystopo-site-install' }
    } catch {
        if (Test-Path -LiteralPath $caddyExe) { Say "could not ask GitHub for the latest Caddy ($($_.Exception.Message)); keeping the one installed"; return $false }
        Fail "could not ask GitHub for the latest Caddy release: $($_.Exception.Message)"
    }
    $tag = [string]$rel.tag_name
    if ((Test-Path -LiteralPath $caddyExe) -and (Test-Path -LiteralPath $verFile) -and ((Get-Content -LiteralPath $verFile -Raw).Trim() -eq $tag)) {
        Say "Caddy $tag is current"
        return $false
    }
    $ver = $tag.TrimStart('v')
    $zipName = "caddy_${ver}_windows_amd64.zip"
    $sumName = "caddy_${ver}_checksums.txt"
    $zipAsset = $rel.assets | Where-Object { $_.name -eq $zipName }
    $sumAsset = $rel.assets | Where-Object { $_.name -eq $sumName }
    if (-not $zipAsset -or -not $sumAsset) { Fail "Caddy $tag has no $zipName or $sumName asset" }
    $dl = Join-Path $caddyDir 'download'
    if (Test-Path -LiteralPath $dl) { Remove-Item -LiteralPath $dl -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $dl | Out-Null
    $zip = Join-Path $dl $zipName
    $sums = Join-Path $dl $sumName
    Invoke-WebRequest -UseBasicParsing -Uri $zipAsset.browser_download_url -OutFile $zip
    Invoke-WebRequest -UseBasicParsing -Uri $sumAsset.browser_download_url -OutFile $sums
    $expected = $null
    foreach ($line in Get-Content -LiteralPath $sums) {
        if ($line -match ('^([0-9a-fA-F]{128})\s+\*?' + [regex]::Escape($zipName) + '\s*$')) { $expected = $matches[1].ToLowerInvariant() }
    }
    if (-not $expected) { Fail "the checksums file of Caddy $tag does not list $zipName" }
    $actual = (Get-FileHash -LiteralPath $zip -Algorithm SHA512).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { Remove-Item -LiteralPath $dl -Recurse -Force; Fail "Caddy $tag download does not match its SHA-512; nothing installed" }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [IO.Compression.ZipFile]::OpenRead($zip)
    try {
        foreach ($entry in $z.Entries) {
            if ($entry.FullName -eq 'caddy.exe') { [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, "$caddyExe.new", $true) }
            if ($entry.FullName -eq 'LICENSE') { [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $caddyDir 'LICENSE'), $true) }
        }
    } finally { $z.Dispose() }
    if (-not (Test-Path -LiteralPath "$caddyExe.new")) { Fail "the Caddy zip has no caddy.exe" }
    Move-Item -LiteralPath "$caddyExe.new" -Destination $caddyExe -Force
    Set-Content -LiteralPath $verFile -Value $tag -Encoding Ascii
    Remove-Item -LiteralPath $dl -Recurse -Force
    Say "Caddy $tag installed (SHA-512 checked)"
    $true
}

function Write-Configs([hashtable]$siteEnv, [string]$javaHome) {
    $token = $siteEnv['DYSTOPO_SITE_TOKEN']
    if (-not $token) { $token = New-Token; Say "generated a new token (in site.env and server.json)" }
    $javaOpts = $siteEnv['JAVA_OPTS']
    if (-not $javaOpts) { $javaOpts = '-Xmx2g' }
    $local = $SiteHost -eq 'localhost'
    $bind = if ($local) { 'default_bind 127.0.0.1 [::1]' } else { '' }
    # A localhost instance needs no port 80; a public one redirects 80 to 443 unless site.env already says
    # otherwise (another program holding port 80: docs/reference/home-site.md).
    $autoHttps = if ($local) { 'auto_https disable_redirects' } elseif ($siteEnv.ContainsKey('DYSTOPO_SITE_AUTO_HTTPS')) { $siteEnv['DYSTOPO_SITE_AUTO_HTTPS'] } else { '' }
    $lines = @(
        '# The home site (tools/site/install.ps1; docs/reference/home-site.md). Holds the token: never commit or share.',
        '# Caddy reads every line (caddy run --envfile); install.ps1 reads them back on -Update.',
        "DYSTOPO_SITE_HOST=$SiteHost",
        "DYSTOPO_SITE_PORT=$Port",
        "DYSTOPO_SITE_ROOT=$rootFwd",
        "DYSTOPO_SITE_BIND=$bind",
        "DYSTOPO_SITE_AUTO_HTTPS=$autoHttps",
        "DYSTOPO_SITE_TOKEN=$token",
        "DYSTOPO_SITE_LOGON=$(if ($AtLogon) { 'interactive' } else { 'S4U' })",
        "JAVA_HOME=$javaHome",
        "JAVA_OPTS=$javaOpts",
        "XDG_DATA_HOME=$rootFwd/caddy/data",
        "XDG_CONFIG_HOME=$rootFwd/caddy/config"
    )
    Set-Content -LiteralPath $envFile -Value $lines -Encoding Ascii
    Protect-File $envFile

    # server.json: the template's keys, or the existing file's (an owner's own keys survive -Update), with the
    # keys this script manages set again.
    $base = if (Test-Path -LiteralPath $configFile) { $configFile } else { Join-Path $PSScriptRoot 'server.example.json' }
    $cfg = Get-Content -LiteralPath $base -Raw | ConvertFrom-Json
    function Set-Key($obj, [string]$key, $value) {
        if ($obj.PSObject.Properties[$key]) { $obj.$key = $value } else { $obj | Add-Member -NotePropertyName $key -NotePropertyValue $value }
    }
    Set-Key $cfg 'bind' '127.0.0.1'
    Set-Key $cfg 'port' $Port
    Set-Key $cfg 'publicUrl' "https://$SiteHost"
    Set-Key $cfg 'cacheDir' "$rootFwd/cache"
    Set-Key $cfg 'outDir' "$rootFwd/out"
    Set-Key $cfg 'token' $token
    Set-Key $cfg 'tilebuildHome' "$rootFwd/tilebuild"
    if (-not $cfg.PSObject.Properties['log']) { Set-Key $cfg 'log' ([pscustomobject]@{ maxMb = 64; keep = 5 }) }
    Set-Key $cfg.log 'file' "$rootFwd/logs/server.log"
    Set-Content -LiteralPath $configFile -Value ($cfg | ConvertTo-Json -Depth 10) -Encoding Ascii
    Protect-File $configFile
    foreach ($d in @('cache', 'out', 'logs')) { New-Item -ItemType Directory -Force -Path (Join-Path $Root $d) | Out-Null }

    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Caddyfile') -Destination (Join-Path $caddyDir 'Caddyfile') -Force
    @{ token = $token; javaOpts = $javaOpts }
}

function Register-SiteTask([string]$taskName, [string]$exe, [string]$arguments, [string]$workDir, [string]$what) {
    $user = "$env:USERDOMAIN\$env:USERNAME"
    $action = New-ScheduledTaskAction -Execute $exe -Argument $arguments -WorkingDirectory $workDir
    if ($AtLogon) {
        $first = New-ScheduledTaskTrigger -AtLogOn -User $user
        $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    } else {
        $first = New-ScheduledTaskTrigger -AtStartup
        $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType S4U -RunLevel Limited
    }
    # Restart-on-failure covers a task that fails to start, not an action that dies (measured: a killed
    # process stayed down); this trigger starts the task every minute, ignored while it is running.
    $watch = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries -RestartInterval (New-TimeSpan -Minutes 1) -RestartCount 999 `
        -MultipleInstances IgnoreNew -StartWhenAvailable -Priority 5
    Register-ScheduledTask -TaskName $taskName -TaskPath '\' -Action $action -Trigger @($first, $watch) -Principal $principal `
        -Settings $settings -Description "Dystopo home site: $what (tools/site/install.ps1; docs/reference/home-site.md)" -Force | Out-Null
}

function Register-SiteTasks([hashtable]$siteEnv, [string]$javaHome, [string]$javaOpts) {
    $javaw = Join-Path $javaHome 'bin\javaw.exe'
    $serverArgs = "$javaOpts -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djava.awt.headless=true " +
        "-cp `"$Root\server\lib\*`" $mainClass --config `"$configFile`""
    $caddyArgs = "run --config `"$caddyDir\Caddyfile`" --adapter caddyfile --envfile `"$envFile`""
    try {
        Register-SiteTask $serverTask $javaw $serverArgs $Root 'the map server on 127.0.0.1'
        Register-SiteTask $proxyTask $caddyExe $caddyArgs $caddyDir 'the Caddy TLS proxy on ports 80 and 443'
    } catch {
        $denied = $_.Exception.Message -match 'denied'
        if ($denied -and -not $AtLogon) {
            Say "Task Scheduler refused the tasks: logon type S4U and an at-startup trigger need an elevated PowerShell."
            Say "Everything else is in place. Run this once $elevatedHint, in this checkout:"
            Write-Host "  powershell -NoProfile -ExecutionPolicy Bypass -File tools\site\install.ps1$(Format-Args) -Firewall"
            exit 3
        }
        throw
    }
    $mode = if ($AtLogon) { 'interactive logon, at logon' } else { 'S4U, at startup' }
    foreach ($t in @($serverTask, $proxyTask)) { Say "registered '$t' ($mode, every minute while stopped): $((Get-Task $t).State)" }
}

# The arguments that name this instance, for the commands the script prints.
function Format-Args {
    $s = ''
    if ($Root -ne (Join-Path $env:USERPROFILE '.dystopo\site')) { $s += " -Root `"$Root`"" }
    if ($Name) { $s += " -Name `"$Name`"" }
    $s
}

function Set-FirewallRule {
    if (-not $isAdmin) { Fail "-Firewall needs an elevated PowerShell: run it $elevatedHint" }
    Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName $ruleName -Group 'Dystopo site' -Direction Inbound -Action Allow -Protocol TCP `
        -LocalPort 80, 443 -Program $caddyExe -Profile Any | Out-Null
    Say "firewall: inbound TCP 80 and 443 allowed to the site's caddy.exe"
}

function Assert-PortFree([int]$p, [int[]]$ours) {
    $held = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue |
        Where-Object { $ours -notcontains $_.OwningProcess -and @('127.0.0.1', '0.0.0.0', '::', '::1') -contains $_.LocalAddress }
    if ($held) { Fail "port $p on 127.0.0.1 is taken by another program (PID $(@($held)[0].OwningProcess)); pick another -Port" }
}

# --- uninstall -----------------------------------------------------------------------------------------

if ($Uninstall) {
    $left = @()
    Stop-SiteTasks -Disable
    foreach ($t in @($serverTask, $proxyTask)) {
        if (-not (Get-Task $t)) { continue }
        try { Unregister-ScheduledTask -TaskName $t -TaskPath '\' -Confirm:$false; Say "removed '$t'" }
        catch { $left += $t; Say "could not remove '$t': $($_.Exception.Message)" }
    }
    $rules = @(Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)
    if ($rules.Count -gt 0) {
        if ($isAdmin) { $rules | Remove-NetFirewallRule; Say "removed the firewall rule" }
        else { $left += 'the firewall rule' }
    }
    if ($left.Count -gt 0) { Fail "still there: $($left -join ', '); run -Uninstall again $elevatedHint" }
    Say "uninstalled; the site folder (token, cache, packs, logs) is kept: delete it by hand if you want it gone"
    exit 0
}

# --- install and update --------------------------------------------------------------------------------

if ($Firewall -and -not $isAdmin) { Fail "-Firewall needs an elevated PowerShell: run it $elevatedHint" }
if (@(8686, 8687, 8765) -contains $Port) { Fail "port $Port belongs to the phone or the coordinator (docs/reference/machines.md)" }

$siteEnv = Read-SiteEnv
# What an earlier install recorded wins over a default; an argument given now wins over both.
if (-not $PSBoundParameters.ContainsKey('SiteHost') -and $siteEnv['DYSTOPO_SITE_HOST']) { $SiteHost = $siteEnv['DYSTOPO_SITE_HOST'] }
if (-not $PSBoundParameters.ContainsKey('Port') -and $siteEnv['DYSTOPO_SITE_PORT']) { $Port = [int]$siteEnv['DYSTOPO_SITE_PORT'] }
if (-not $PSBoundParameters.ContainsKey('AtLogon') -and $siteEnv['DYSTOPO_SITE_LOGON'] -eq 'interactive') { $AtLogon = [switch]$true }

New-Item -ItemType Directory -Force -Path $Root, $caddyDir | Out-Null
$javaHome = Get-JavaHome $siteEnv

$haveSnapshot = Test-Path -LiteralPath (Join-Path $Root 'server\lib')
if ($Update -or -not $haveSnapshot) { Build-Snapshot $javaHome }

if ($Update -or -not $haveSnapshot) {
    Stop-SiteTasks -Disable
    Copy-Snapshot
}
if ($Update -or -not (Test-Path -LiteralPath $caddyExe)) {
    if ($Update -and (Get-Task $proxyTask)) { Stop-SiteTasks -Disable }
    $null = Get-Caddy -Refresh:$Update
}
$written = Write-Configs $siteEnv $javaHome
Say "configs written: server.json and site.env (token kept across runs), Caddyfile"

if ($Firewall) { Set-FirewallRule }

Stop-SiteTasks -Disable
Assert-PortFree $Port @()
Register-SiteTasks $siteEnv $javaHome $written.javaOpts

foreach ($t in @($serverTask, $proxyTask)) { Enable-ScheduledTask -TaskName $t | Out-Null; Start-ScheduledTask -TaskName $t }
$up = Wait-Healthz 90
if ($up -lt 0) { Fail "the server did not answer /healthz within 90 s; run tools\site\status.ps1$(Format-Args) for details" }
Say "server up in $up s"
# The proxy, straight to 127.0.0.1:443 under the site's name (-k: whether it answers; status.ps1 checks the certificate).
$proxyUp = $false
for ($i = 0; $i -lt 30 -and -not $proxyUp; $i++) {
    $code = & curl.exe -s -k -o NUL -w '%{http_code}' --max-time 10 --resolve "${SiteHost}:443:127.0.0.1" "https://${SiteHost}/healthz" 2>$null
    if ($code -eq '200') { $proxyUp = $true } else { Start-Sleep -Seconds 2 }
}
if ($proxyUp) { Say "proxy answers on 443" }
elseif ($SiteHost -eq 'localhost') { Fail "the proxy did not answer on 443 within 60 s (task $((Get-Task $proxyTask).State)); Caddy's log is caddy\caddy.log under the site folder" }
else { Say "the proxy does not answer on 443 yet: a public certificate needs ports 80 and 443 forwarded to this PC and the name pointing here (docs/reference/home-site.md); Caddy keeps trying" }
Say "check it any time: powershell -NoProfile -ExecutionPolicy Bypass -File tools\site\status.ps1$(Format-Args)"
if ($SiteHost -ne 'localhost' -and -not $Firewall -and -not (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)) {
    Say "no firewall rule yet: run once with -Firewall $elevatedHint"
}
exit 0
