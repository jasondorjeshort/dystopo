# Remove one task worktree folder on Windows, long paths included, and drop it from git's worktree list.
#
#   powershell -ExecutionPolicy Bypass -File <main>\tools\worktree-remove.ps1 -Path <worktrees>\Tnn -WorktreeRoot <worktrees>
#     [-Main <main checkout>] [-StopPid <pid>[,<pid>...]] [-DiscardChanges]
#
# Gradle and Android build output nests past 260 characters (androidApp's dex archive reaches 248 below the checkout),
# so `git worktree remove` and a plain Remove-Item fail; this deletes through the \\?\ prefix, then unlocks the entry
# when git holds it locked and runs `git worktree prune`, each git command a call of its own, every one with
# `-c core.longpaths=true` (docs/reference/windows-and-shells.md, Worktrees).
#
# What it refuses (exit 2), before it deletes anything:
#   - a -Path that is not a direct child of -WorktreeRoot, or that is, holds or sits inside the main checkout;
#   - a -WorktreeRoot that is a drive root, or is, holds or sits inside the main checkout;
#   - a folder whose .git is a repository of its own, or a worktree of another repository;
#   - a folder holding a junction or symbolic link (a recursive delete can follow one into its target; finding
#     docs/findings/2026-10-09-junction-into-main-checkout.md); remove the link itself first;
#   - a -StopPid that is not a Gradle daemon (its command line names GradleDaemon).
# Exit 5: the worktree has uncommitted changes (`git status --porcelain` lists them); -DiscardChanges deletes anyway.
# Exit 4: a process holds a file or the folder (a rename of the folder fails); the refusal names each holder the
#   Restart Manager reports, with its PID, and nothing is deleted. Stop only a process you started, by its PID.
# Exit 1: anything else that failed (a delete that left files, a prune that left the entry); exit 0: removed, or
#   nothing was there and the entry is pruned.
# -StopPid: Gradle daemons the caller started (and recorded) are stopped by PID before the delete; daemons are never
#   looked up by name or command line (CLAUDE.md, Working in parallel), and `./gradlew --stop` is never run.
# -Main defaults to the checkout that owns this script's repository (git's common dir).
# Tested by tools/test_worktree_remove.py (Windows only).
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$WorktreeRoot,
    [string]$Main = '',
    [int[]]$StopPid = @(),
    [switch]$DiscardChanges
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
$started = Get-Date
$LP = '\\?\'

function Say([string]$m) { Write-Output "worktree-remove: $m" }
function Refuse([string]$m, [int]$code) { Write-Output "worktree-remove: REFUSED: $m"; exit $code }
function Fail([string]$m) { Write-Output "worktree-remove: FAILED: $m"; exit 1 }

function Full([string]$p) {
    $p = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($p)
    $f = [System.IO.Path]::GetFullPath($p)
    if ($f.Length -gt 3) { $f = $f.TrimEnd('\', '/') }
    return $f
}
function Same([string]$a, [string]$b) { return [string]::Equals($a, $b, [System.StringComparison]::OrdinalIgnoreCase) }
function Under([string]$child, [string]$parent) {
    return $child.StartsWith($parent.TrimEnd('\') + '\', [System.StringComparison]::OrdinalIgnoreCase)
}

# One git command per call; stderr folded into the output as text; the exit code kept.
function Invoke-Git([string[]]$a) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = @(& git -c core.longpaths=true @a 2>&1 | ForEach-Object { "$_" })
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    return New-Object psobject -Property @{ code = $code; out = $out }
}

$holderSource = @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class DystopoRmHolders {
    [StructLayout(LayoutKind.Sequential)]
    struct RM_UNIQUE_PROCESS { public int dwProcessId; public System.Runtime.InteropServices.ComTypes.FILETIME ProcessStartTime; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct RM_PROCESS_INFO {
        public RM_UNIQUE_PROCESS Process;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string strAppName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string strServiceShortName;
        public int ApplicationType; public uint AppStatus; public uint TSSessionId;
        [MarshalAs(UnmanagedType.Bool)] public bool bRestartable;
    }
    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)] static extern int RmStartSession(out uint h, int flags, string key);
    [DllImport("rstrtmgr.dll")] static extern int RmEndSession(uint h);
    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)]
    static extern int RmRegisterResources(uint h, uint nFiles, string[] files, uint nApps, RM_UNIQUE_PROCESS[] apps, uint nServices, string[] services);
    [DllImport("rstrtmgr.dll")]
    static extern int RmGetList(uint h, out uint needed, ref uint count, [In, Out] RM_PROCESS_INFO[] info, ref uint reasons);
    // The PIDs of the processes holding any of the files (Restart Manager), or an empty list.
    public static int[] Pids(string[] files) {
        uint h; var pids = new List<int>();
        if (RmStartSession(out h, 0, Guid.NewGuid().ToString()) != 0) return pids.ToArray();
        try {
            if (RmRegisterResources(h, (uint)files.Length, files, 0, null, 0, null) != 0) return pids.ToArray();
            uint needed = 0, count = 0, reasons = 0;
            int r = RmGetList(h, out needed, ref count, null, ref reasons);
            if (r == 234 && needed > 0) {
                var info = new RM_PROCESS_INFO[needed];
                count = needed;
                r = RmGetList(h, out needed, ref count, info, ref reasons);
                if (r == 0) for (int i = 0; i < count; i++) pids.Add(info[i].Process.dwProcessId);
            }
        } finally { RmEndSession(h); }
        return pids.ToArray();
    }
}
'@

# The holders of the files under $dir: "name (PID n)" per process, or an empty list.
function Find-Holders([string]$dir) {
    if (-not ('DystopoRmHolders' -as [type])) { Add-Type -TypeDefinition $holderSource }
    $files = New-Object System.Collections.Generic.List[string]
    $stack = New-Object System.Collections.Generic.Stack[string]
    $stack.Push($LP + $dir)
    while ($stack.Count -gt 0) {
        $d = New-Object System.IO.DirectoryInfo $stack.Pop()
        try { $items = $d.GetFileSystemInfos() } catch { continue }
        foreach ($i in $items) {
            if ($i.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { continue }
            if ($i.Attributes -band [System.IO.FileAttributes]::Directory) { $stack.Push($i.FullName) }
            else { $n = $i.FullName.Substring(4); if ($n.Length -ge 260) { $n = $i.FullName }; $files.Add($n) }
        }
    }
    $pids = @{}
    for ($k = 0; $k -lt $files.Count; $k += 500) {
        $chunk = $files.GetRange($k, [Math]::Min(500, $files.Count - $k)).ToArray()
        foreach ($p in [DystopoRmHolders]::Pids($chunk)) { $pids[$p] = $true }
    }
    $names = @()
    foreach ($p in ($pids.Keys | Sort-Object)) {
        $proc = Get-Process -Id $p -ErrorAction SilentlyContinue
        $names += "$(if ($proc) { $proc.ProcessName } else { '?' }) (PID $p)"
    }
    return ,$names
}

# --- Paths ---------------------------------------------------------------------------------------------------------
$Path = Full $Path
$WorktreeRoot = Full $WorktreeRoot
if (-not $Main) {
    $r = Invoke-Git @('-C', $PSScriptRoot, 'rev-parse', '--path-format=absolute', '--git-common-dir')
    if ($r.code -ne 0) { Fail "cannot find the main checkout from $PSScriptRoot; pass -Main: $($r.out -join ' ')" }
    $Main = Split-Path -Parent ($r.out[0])
}
$Main = Full $Main
if (Same ([System.IO.Path]::GetPathRoot($WorktreeRoot).TrimEnd('\')) $WorktreeRoot.TrimEnd('\')) {
    Refuse "-WorktreeRoot $WorktreeRoot is a drive root" 2
}
if ((Same $WorktreeRoot $Main) -or (Under $Main $WorktreeRoot) -or (Under $WorktreeRoot $Main)) {
    Refuse "-WorktreeRoot $WorktreeRoot is, holds or sits inside the main checkout $Main" 2
}
if ((Same $Path $Main) -or (Under $Main $Path) -or (Under $Path $Main)) {
    Refuse "$Path is, holds or sits inside the main checkout $Main" 2
}
if (-not (Same (Split-Path -Parent $Path) $WorktreeRoot)) {
    Refuse "$Path is not a folder directly under the worktrees folder $WorktreeRoot" 2
}

$r = Invoke-Git @('-C', $Main, 'rev-parse', '--path-format=absolute', '--git-common-dir')
if ($r.code -ne 0) { Fail "$Main is not a git checkout: $($r.out -join ' ')" }
$common = Full ($r.out[0])

function Get-Entry {
    $r = Invoke-Git @('-C', $Main, 'worktree', 'list', '--porcelain')
    if ($r.code -ne 0) { Fail "git worktree list: $($r.out -join ' ')" }
    $entry = $null
    foreach ($line in $r.out) {
        if ($line -like 'worktree *') {
            if ($entry) { break }
            if (Same (Full $line.Substring(9)) $Path) { $entry = @{ locked = $false } }
        } elseif ($entry -and ($line -like 'locked*')) { $entry.locked = $true }
    }
    return $entry
}
$entry = Get-Entry
$exists = [System.IO.Directory]::Exists($LP + $Path)
Say "$Path ($(if ($entry) { 'a worktree of ' + $Main + $(if ($entry.locked) { ', locked' } else { '' }) } elseif ($exists) { 'a folder git does not list' } else { 'not there' }))"

if ($exists) {
    # --- Ownership: a worktree of this repository, or a leftover with no .git ---------------------------------------
    $dotGit = $LP + (Join-Path $Path '.git')
    if ([System.IO.Directory]::Exists($dotGit)) { Refuse "$Path\.git is a repository of its own, not a worktree" 2 }
    if ([System.IO.File]::Exists($dotGit)) {
        $line = ([System.IO.File]::ReadAllText($dotGit)).Trim()
        if ($line -notlike 'gitdir: *') { Refuse "$Path\.git does not name a gitdir" 2 }
        $gitdir = Full ($line.Substring(8).Trim())
        if (-not (Under $gitdir (Join-Path $common 'worktrees'))) {
            Refuse "$Path is a worktree of another repository ($gitdir), not of $Main" 2
        }
        if ($entry -and -not $DiscardChanges) {
            $r = Invoke-Git @('-C', $Path, 'status', '--porcelain')
            if ($r.code -ne 0) { Fail "git status in ${Path}: $($r.out -join ' ')" }
            if ($r.out.Count -gt 0) {
                Write-Output ($r.out | Select-Object -First 10)
                Refuse "$Path has uncommitted changes (above); commit them, or pass -DiscardChanges" 5
            }
        }
    }

    # --- Gradle daemons the caller started, by PID only ---------------------------------------------------------------
    foreach ($p in $StopPid) {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$p"
        if (-not $proc) { Say "PID $p is not running"; continue }
        if ("$($proc.CommandLine)" -notmatch 'GradleDaemon') { Refuse "PID $p ($($proc.Name)) is not a Gradle daemon; -StopPid takes only daemons you started" 2 }
        Stop-Process -Id $p -Force
        try { Wait-Process -Id $p -Timeout 30 -ErrorAction Stop } catch { }
        Say "stopped the Gradle daemon PID $p"
    }

    # --- Walk: count, longest path, no links ----------------------------------------------------------------------------
    $entries = 0; $longest = $Path.Length
    $stack = New-Object System.Collections.Generic.Stack[string]
    $stack.Push($LP + $Path)
    while ($stack.Count -gt 0) {
        $d = New-Object System.IO.DirectoryInfo $stack.Pop()
        foreach ($i in $d.GetFileSystemInfos()) {
            $entries++
            $n = $i.FullName.Length - 4
            if ($n -gt $longest) { $longest = $n }
            if ($i.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
                Refuse "$($i.FullName.Substring(4)) is a junction or symbolic link; remove the link itself (rmdir on it, not a recursive delete), then run this again" 2
            }
            if ($i.Attributes -band [System.IO.FileAttributes]::Directory) { $stack.Push($i.FullName) }
        }
    }
    Say "$entries entries, longest path $longest characters"

    # --- Held? A rename of the folder fails while any process holds a file in it or the folder itself -----------------
    $probe = $Path + '.rm-probe-' + $PID
    try { [System.IO.Directory]::Move($LP + $Path, $LP + $probe) }
    catch {
        $holders = Find-Holders $Path
        $who = if ($holders.Count -gt 0) { $holders -join ', ' } else { 'a process the Restart Manager does not name (a shell or program whose working directory is inside it?)' }
        Refuse "$Path is held by $who; nothing deleted. Stop only a process you started, by PID" 4
    }
    try { [System.IO.Directory]::Move($LP + $probe, $LP + $Path) }
    catch { Fail "the folder was moved to $probe for the probe and could not be moved back: $($_.Exception.Message)" }

    # --- Delete ---------------------------------------------------------------------------------------------------------
    $delErr = @()
    Remove-Item -LiteralPath ($LP + $Path) -Recurse -Force -ErrorAction SilentlyContinue -ErrorVariable delErr
    if ([System.IO.Directory]::Exists($LP + $Path)) {
        $holders = Find-Holders $Path
        if ($holders.Count -gt 0) { Refuse "the delete stopped part way; $Path is held by $($holders -join ', ')" 4 }
        Fail "the delete left $Path in place: $(if ($delErr.Count -gt 0) { $delErr[0].Exception.Message } else { 'no error reported' })"
    }
    Say "deleted $Path"
}

# --- Git's list -------------------------------------------------------------------------------------------------------
if ($entry -and $entry.locked) {
    $r = Invoke-Git @('-C', $Main, 'worktree', 'unlock', $Path)
    if ($r.code -ne 0) { Fail "git worktree unlock: $($r.out -join ' ')" }
}
$r = Invoke-Git @('-C', $Main, 'worktree', 'prune')
if ($r.code -ne 0) { Fail "git worktree prune: $($r.out -join ' ')" }
if (Get-Entry) { Fail "git worktree list still names $Path after the prune" }
Say ("removed $Path in {0:N1} s" -f ((Get-Date) - $started).TotalSeconds)
exit 0
