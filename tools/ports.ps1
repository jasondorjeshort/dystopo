<#
.SYNOPSIS
  Names every listening TCP port on this PC: the PID, the image, the session and, for java, what it runs.

.DESCRIPTION
  One row per (port, PID) from Get-NetTCPConnection -State Listen, joined to Win32_Process for the image
  path, the session id and the command line. For a java image the "runs" column is the main class or the
  jar taken from the command line. A process the shell cannot read (another account's, a protected one,
  or everything the agents' sandbox hides) gets a row that says "cannot read", never an error. Run it
  elevated to see every owner. Inside the agents' sandbox the listener table omits listeners and owners,
  even the script's own (docs/reference/machines.md, T191): use -SelfTest there.

  -Port <n>   only that port.        -Json   one JSON array on stdout instead of a table.
  -SelfTest   binds a port in this script's own process, prints its row (PID, image, and what it runs),
              checks the java command-line parser on sample lines, and exits 1 on a failure. When the
              sandbox's listener table does not list the bound port the row says so.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\ports.ps1 -Port 80
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\ports.ps1 -Json
#>
[CmdletBinding()]
param(
    [int]$Port = 0,
    [switch]$Json,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$home_ = $env:USERPROFILE

# The main class or jar of a java command line: the first non-option token after the options, where
# -cp/-classpath/-jar take a value. Returns '' when there is none.
function Get-JavaMain([string]$cmd) {
    if (-not $cmd) { return '' }
    $tokens = [regex]::Matches($cmd, '"([^"]*)"|(\S+)') | ForEach-Object { if ($_.Groups[1].Success) { $_.Groups[1].Value } else { $_.Groups[2].Value } }
    $tokens = @($tokens)
    $i = 1   # token 0 is the executable
    while ($i -lt $tokens.Count) {
        $t = $tokens[$i]
        if ($t -eq '-jar') { if ($i + 1 -lt $tokens.Count) { return 'jar ' + (Split-Path -Leaf $tokens[$i + 1]) } else { return '' } }
        if ($t -in '-cp', '-classpath', '--class-path', '-p', '--module-path') { $i += 2; continue }
        if ($t -eq '-m' -or $t -eq '--module') { if ($i + 1 -lt $tokens.Count) { return 'module ' + $tokens[$i + 1] } else { return '' } }
        if ($t.StartsWith('-')) { $i++; continue }
        return $t
    }
    ''
}

# A path with the user's profile folder shown as ~, so output can be pasted.
function Show-Path([string]$p) {
    if ($p -and $home_ -and $p.StartsWith($home_, [StringComparison]::OrdinalIgnoreCase)) { return '~' + $p.Substring($home_.Length) }
    $p
}

function Get-Row([int]$listenPort, [int]$procId, [string]$address) {
    $row = [ordered]@{ port = $listenPort; address = $address; pid = $procId; image = ''; session = $null; runs = ''; note = '' }
    if ($procId -eq 0 -or $procId -eq 4) { $row.image = 'System'; $row.note = 'kernel (HTTP.sys or a system service)'; return [pscustomobject]$row }
    $p = $null
    try { $p = Get-CimInstance Win32_Process -Filter "ProcessId = $procId" -ErrorAction Stop } catch { }
    if (-not $p) { $row.note = 'cannot read'; return [pscustomobject]$row }
    $row.session = [int]$p.SessionId
    if ($p.ExecutablePath) { $row.image = Show-Path $p.ExecutablePath }
    else { $row.image = $p.Name; $row.note = 'cannot read the image path' }
    if ($p.Name -match '^javaw?\.exe$') {
        if ($p.CommandLine) { $row.runs = Get-JavaMain $p.CommandLine } else { $row.note = 'cannot read the command line' }
    }
    [pscustomobject]$row
}

function Get-Rows([int]$only) {
    # Filtered here, not with -LocalPort: inside the sandbox that parameter hid rows the plain call lists.
    $conns = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $only -le 0 -or $_.LocalPort -eq $only })
    $seen = @{}
    foreach ($c in ($conns | Sort-Object LocalPort, OwningProcess, LocalAddress)) {
        $key = "$($c.LocalPort)/$($c.OwningProcess)"
        if ($seen.ContainsKey($key)) { $seen[$key].address += ", $($c.LocalAddress)"; continue }
        $seen[$key] = Get-Row $c.LocalPort $c.OwningProcess $c.LocalAddress
    }
    @($seen.Values | Sort-Object port, pid)
}

function Write-Rows($rows, [bool]$asJson) {
    if ($asJson) { if ($rows.Count -eq 0) { '[]' } else { ConvertTo-Json -InputObject @($rows) -Depth 3 }; return }
    if ($rows.Count -eq 0) { Write-Host 'no listener visible to this shell'; return }
    $rows | Format-Table port, pid, session, image, runs, note, address -AutoSize -Wrap | Out-String -Width 4096 | Write-Host
}

if ($SelfTest) {
    $fail = 0
    $cases = @(
        @{ cmd = '"C:\jdk\bin\javaw.exe" -Xmx2g -cp "C:\site\server\lib\*" io.example.MainKt --config "x.json"'; want = 'io.example.MainKt' },
        @{ cmd = 'java -jar C:\tools\planetiler.jar --area=x'; want = 'jar planetiler.jar' },
        @{ cmd = 'java -Dfoo=bar -classpath a;b org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.5'; want = 'org.gradle.launcher.daemon.bootstrap.GradleDaemon' },
        @{ cmd = 'java'; want = '' }
    )
    foreach ($c in $cases) {
        $got = Get-JavaMain $c.cmd
        $ok = $got -eq $c.want
        if (-not $ok) { $fail++ }
        Write-Host ("{0} parser: '{1}'" -f $(if ($ok) { 'ok  ' } else { 'FAIL' }), $got)
    }
    $l = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $l.Start()
    try {
        $bound = ([Net.IPEndPoint]$l.LocalEndpoint).Port
        $rows = @(Get-Rows $bound | Where-Object { $_.pid -eq $PID })
        if ($rows.Count -eq 0) {
            $row = Get-Row $bound $PID '127.0.0.1'
            $row.note = ("not in the listener table (sandbox hides it); row built from the process itself; " + $row.note).Trim('; ')
            $rows = @($row)
            Write-Host "note: the listener table did not list port $bound"
        }
        Write-Host ("self-test: bound port {0} in PID {1}" -f $bound, $PID)
        Write-Rows $rows $Json.IsPresent
        $r = $rows[0]
        $good = $r.pid -eq $PID -and $r.image -and $r.port -eq $bound
        if (-not $good) { $fail++; Write-Host 'FAIL own row incomplete' } else { Write-Host 'ok   own row: PID, image and port' }
    } finally { $l.Stop() }
    exit $(if ($fail) { 1 } else { 0 })
}

$rows = Get-Rows $Port
Write-Rows $rows $Json.IsPresent
if ($Port -gt 0 -and $rows.Count -eq 0) { Write-Host "nothing listening on $Port that this shell can see (an unelevated shell, or the sandbox, may hide listeners: run it elevated)" }
exit 0
