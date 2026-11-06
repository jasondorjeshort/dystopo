# The emulator guard (T231). tools\android-emu.ps1 starts one beside every emulator it boots or adopts, hidden and
# detached, and records its PID in the emulator's lock as guardPid. It watches that one emulator and nothing else:
#
#   - its qemu process's private bytes, from the PID in the lock (-LockFile); past -LimitGB it stops the emulator.
#   - the PC's free (available) physical RAM; under -FloorGB for -FloorSeconds in a row it stops the emulator, unless
#     another guarded emulator holds more private bytes (that one's guard goes first; the next reads see the RAM back).
#
# A stop is the one emulator-stop.js does: `adb -s <serial> emu kill` when the console answers with the lock's AVD, then,
# if the PID still runs after 20 s, the PID itself, and the lock's emulator.exe parent likewise; each only after checking
# the PID still runs an emulator image (qemu-system-*, emulator*). Never by name or command line (CLAUDE.md; finding
# 2026-10-10-kill-by-command-line-substring). The lock is removed once the emulator is gone. Each stop is a line, with
# the figures, in the lock folder's stop.log and in -Log (out\emulator-guard-<avd>.log in the booting checkout).
#
# The guard ends with its emulator: when the lock's PID no longer runs an emulator image, when the lock file is gone or
# names another PID, when the lock still boots and the script that wrote it has gone, or after a stop. It writes
# nothing into the lock. Not run by hand; docs/reference/android-emulator.md, The emulator cap.
param(
    [Parameter(Mandatory = $true)] [string]$LockFile,
    [Parameter(Mandatory = $true)] [string]$Serial,
    [Parameter(Mandatory = $true)] [string]$Avd,
    [Parameter(Mandatory = $true)] [double]$LimitGB,
    [Parameter(Mandatory = $true)] [double]$FloorGB,
    [Parameter(Mandatory = $true)] [string]$Log,
    [Parameter(Mandatory = $true)] [string]$Adb,
    [int]$FloorSeconds = 30,
    [int]$IntervalSeconds = 2
)
$ErrorActionPreference = 'Stop'
$emuImage = '^(qemu-system-[\w.-]+|emulator\d*)(\.exe)?$'
$lockDir = Split-Path -Parent $LockFile
$stopLog = Join-Path $lockDir 'stop.log'
Add-Type -AssemblyName Microsoft.VisualBasic

function Stamp { (Get-Date).ToUniversalTime().ToString('o') }
function Say([string]$line) {
    try { [IO.File]::AppendAllText($Log, "$(Stamp) $line`n") } catch { }
}
function Gb([double]$bytes) { '{0:n1} GB' -f ($bytes / 1GB) }
function FreeBytes { [double](New-Object Microsoft.VisualBasic.Devices.ComputerInfo).AvailablePhysicalMemory }
function Get-ProcImage([int]$id) {
    if ($id -le 0) { return '' }
    $p = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($p) { "$($p.ProcessName).exe" } else { '' }
}
function Test-EmuPid([int]$id) { (Get-ProcImage $id) -match $emuImage }
function Read-Lock([string]$file) {
    try { Get-Content -Raw -LiteralPath $file -ErrorAction Stop | ConvertFrom-Json } catch { $null }
}
function Native {
    $ErrorActionPreference = 'Continue'
    $exe = $args[0]
    $rest = @($args | Select-Object -Skip 1)
    & $exe @rest 2>&1 | ForEach-Object { "$_" }
}
function Wait-Gone([int]$id, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Test-EmuPid $id) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 250 }
    -not (Test-EmuPid $id)
}

# Stops this guard's emulator; returns how.
function Stop-Emulator($lock) {
    $qemu = [int]$lock.pid
    $how = ''
    $name = @(Native $Adb -s $Serial emu avd name | ForEach-Object { $_.Trim() })
    if ($name -contains $Avd) {
        Native $Adb -s $Serial emu kill | Out-Null
        if (Wait-Gone $qemu 20) { $how = 'console kill' }
    }
    if (-not $how -and (Test-EmuPid $qemu)) {
        try { Stop-Process -Id $qemu -Force -ErrorAction Stop } catch { }
        if (Wait-Gone $qemu 5) { $how = "pid $qemu terminated" }
    }
    if ($lock.emulatorPid -and (Test-EmuPid ([int]$lock.emulatorPid))) {
        if (-not (Wait-Gone ([int]$lock.emulatorPid) 5)) {
            try { Stop-Process -Id ([int]$lock.emulatorPid) -Force -ErrorAction Stop } catch { }
        }
    }
    if (Test-EmuPid $qemu) { return "could not stop it: pid $qemu still runs; lock kept" }
    $now = Read-Lock $LockFile
    if ($now -and [int]$now.pid -eq $qemu) { Remove-Item -LiteralPath $LockFile -Force -ErrorAction SilentlyContinue }
    "stopped by $(if ($how) { $how } else { 'pid' }); lock removed"
}

# The private bytes of the biggest other emulator whose guard runs, with its serial, or $null.
function Get-BiggerGuarded([double]$mine) {
    foreach ($f in @(Get-ChildItem -LiteralPath $lockDir -Filter 'emulator-*.json' -File -ErrorAction SilentlyContinue)) {
        if ($f.FullName -eq $LockFile) { continue }
        $l = Read-Lock $f.FullName
        if (-not $l -or -not $l.pid -or -not $l.guardPid) { continue }
        if ((Get-ProcImage ([int]$l.guardPid)) -notmatch '^(powershell|pwsh)\.exe$') { continue }
        $p = Get-Process -Id ([int]$l.pid) -ErrorAction SilentlyContinue
        if ($p -and "$($p.ProcessName).exe" -match $emuImage -and $p.PrivateMemorySize64 -gt $mine) {
            return [pscustomobject]@{ Serial = $l.serial; Bytes = [double]$p.PrivateMemorySize64 }
        }
    }
    $null
}

$who = "$Serial ($Avd)"
Say "guard pid $PID for $who started: limit $LimitGB GB private, floor $FloorGB GB free for $FloorSeconds s; free now $(Gb (FreeBytes))"
$peak = 0.0
$nextMark = 1GB
$underSince = $null
$deferredTo = ''
$why = ''
while ($true) {
    $lock = Read-Lock $LockFile
    if (-not (Test-Path -LiteralPath $LockFile)) { $why = 'its lock is gone'; break }
    if (-not $lock) { Start-Sleep -Seconds $IntervalSeconds; continue } # torn while being rewritten
    if ($lock.serial -ne $Serial -or $lock.avd -ne $Avd) { $why = "the lock now holds $($lock.serial) ($($lock.avd))"; break }
    if (-not $lock.pid) {
        if ((Get-ProcImage ([int]$lock.bootedBy)) -notmatch '^(powershell|pwsh)\.exe$') { $why = "still booting and its script (pid $($lock.bootedBy)) is gone"; break }
        Start-Sleep -Seconds $IntervalSeconds
        continue
    }
    $qemu = [int]$lock.pid
    $p = Get-Process -Id $qemu -ErrorAction SilentlyContinue
    if (-not $p -or "$($p.ProcessName).exe" -notmatch $emuImage) { $why = "its emulator (pid $qemu) is gone"; break }
    $priv = [double]$p.PrivateMemorySize64
    $free = FreeBytes
    if ($priv -gt $peak) { $peak = $priv }
    if ($priv -ge $nextMark) {
        Say "private $(Gb $priv) (pid $qemu), free $(Gb $free)"
        while ($nextMark -le $priv) { $nextMark += 1GB }
    }
    $reason = ''
    if ($priv -gt $LimitGB * 1GB) {
        $reason = "private bytes $(Gb $priv) over the limit $LimitGB GB (free RAM $(Gb $free), floor $FloorGB GB)"
    } elseif ($free -lt $FloorGB * 1GB) {
        if (-not $underSince) { $underSince = Get-Date; Say "free RAM $(Gb $free) under the floor $FloorGB GB (private $(Gb $priv)); stopping after $FloorSeconds s of it" }
        if (((Get-Date) - $underSince).TotalSeconds -ge $FloorSeconds) {
            $bigger = Get-BiggerGuarded $priv
            if ($bigger) {
                if ($deferredTo -ne $bigger.Serial) { Say "free RAM $(Gb $free) under the floor: $($bigger.Serial) holds more ($(Gb $bigger.Bytes)) and its guard goes first"; $deferredTo = $bigger.Serial }
            } else {
                $reason = "free RAM $(Gb $free) under the floor $FloorGB GB for $FloorSeconds s (private $(Gb $priv), limit $LimitGB GB)"
            }
        }
    } else {
        if ($underSince) { Say "free RAM $(Gb $free) back over the floor" }
        $underSince = $null; $deferredTo = ''
    }
    if ($reason) {
        $owner = if ($lock.owner) { $lock.owner } else { '?' }
        $result = Stop-Emulator $lock
        $line = "guard pid ${PID}: $Serial ($Avd, owner $owner, pid $qemu): $reason; $result"
        Say $line
        try { [IO.File]::AppendAllText($stopLog, "$(Stamp) $line`n") } catch { }
        $why = 'it stopped its emulator'
        break
    }
    Start-Sleep -Seconds $IntervalSeconds
}
Say "guard pid $PID for $who ends: $why (peak private $(Gb $peak))"
