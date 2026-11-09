# The verifier's mechanical half as one command (T246; idea 2026-11-08, cheaper verification). Given a task id and the
# brief's machine facts, it runs, in order, and stops at the first step that fails (naming it; -Continue runs on):
#   fetch     git fetch --prune origin in the main checkout
#   worktree  the detached worktree <WorktreeRoot>\<Task>-verify at origin/task/<Task> (git worktree add --detach); one
#             that already exists and is clean is moved to origin/task/<Task> instead (the rerun after a rebase)
#   base      whether origin/main moved since the branch forked (merge-base): moved is a failure that names the count,
#             since bringing the branch up to date is the agent's step (in the worktree: rebase and push --force-with-lease,
#             or where a force push is refused, merge origin/main and push; then run again);
#             -AllowBehind goes on without it
#   privacy   the branch's commit messages (git log origin/main..origin/task/<Task> --format=%B), and -ReportFile, through
#             java tools/PrivacyCheck.java scan; any hit fails
#   data      local.properties and the Rainier packs (rainier-dem, rainier-outdoors) copied from the main checkout into
#             the worktree (copies, never links: CLAUDE.md, Working in parallel)
#   check     gradlew check --no-daemon in the worktree; the test counts read from every TEST-*.xml under */build/test-results
#   slot      waits (adb devices every 10 s, up to -SlotWaitMinutes) while the machine's emulator cap (emulators.max in
#             .claude/hooks/policy.json) is taken by other emulators
#   boot      tools\android-emu.ps1 -Build with the brief's AVD, AVD home, console and host ports, -Owner and -Server
#             (and -PrivateLimitGB when given), from the worktree, so the APK is the branch's
#   tour      python -I tools/android-check.py --views-file tools/android-tour.txt --server <Server>: views ok, the sheet
#   ui-<part> python -I tools/android-ui.py <part> for each of -Parts (with --serial, --port, --out and -UiArgs)
#   stop      android-emu.ps1 -Stop -Delete of the same emulator (runs whenever boot ran, also after a failure;
#             -KeepEmulator leaves it running for the agent's own scenario and prints the stop command)
#   guard     the guard log's lowest free RAM and peak private bytes since the boot (out\emulator-guard-<Avd>.log in the
#             worktree) and any stop.log line naming this emulator (emulators.lockDir); a stop.log line fails it
# Everything goes to -Out (default <worktree>\out\verify): verify-summary.json (each step's ok, timing, counts, the
# screenshot paths), verify-summary.txt (the same in lines an agent reads in one go), logs\<id>.out.log and .err.log per
# program, tour\ and ui\<part>\ with the screenshots. Exit 0 when every step that ran passed, 1 when one failed.
#
#   powershell -ExecutionPolicy Bypass -File tools\verify-task.ps1 -Task T246 -WorktreeRoot C:\...\dystopo-wt `
#       -Avd dystopo35-T246v -AvdHome E:\dystopo-avd -EmuPort 5570 -Port 8694
#   ... -DryRun                       # prints every command, runs nothing
#   ... -Skip boot,tour,stop,guard    # no emulator (a docs-only or tool-only task)
#   ... -Parts routes,search          # debug-build parts of android-ui.py after the tour
#   ... -KeepEmulator                 # leave the emulator for the report's scenario; stop it with the printed command
#   ... -Only stop,guard              # later: stop it and read the guard; the other steps stay in the summary
#
# Every Gradle run is --no-daemon (and GRADLE_OPTS gains -Dorg.gradle.daemon=false for the builds of the scripts it runs);
# every program's wait has a ceiling, at which its process tree is killed by its PID (taskkill /PID /T, never by name),
# as tools\sweep.ps1 does. The procedure around it: docs/reference/verifying-a-task.md.
# -Fixture <dir> runs no program: exit codes come from <dir>\codes.json, git and adb answers from <dir>\answers.json, the
# worktree is <dir>\wt and the lock folder <dir>\lock (tools/test_verify_task.py builds one).
param(
    [Parameter(Mandatory = $true)] [string]$Task,
    # Where <Task>-verify is made; default the main checkout's sibling <name>-wt (docs/reference/machines.md).
    [string]$WorktreeRoot = '',
    [string]$Branch = '',
    [string]$Avd = '',
    [string]$AvdHome = '',
    [int]$EmuPort = 0,
    [int]$Port = 0,
    # The guard's private-bytes limit for this boot (android-emu.ps1 -PrivateLimitGB); 0 keeps policy.json's.
    [double]$PrivateLimitGB = 0,
    [string]$JavaHome = '',
    [string]$Cache = '',
    # The app's server for the boot and the tour: 'none', or a URL the emulator reaches. Never the coordinator's 8765.
    [string]$Server = 'none',
    [string]$Owner = '',
    [string[]]$Parts = @(),
    [string[]]$UiArgs = @(),
    # The implementer's report as a text file: scanned with the commit messages.
    [string]$ReportFile = '',
    [string]$Out = '',
    [string[]]$Skip = @(),
    [string[]]$Only = @(),
    [switch]$Continue,
    [switch]$DryRun,
    [switch]$KeepEmulator,
    [switch]$AllowBehind,
    [int]$SlotWaitMinutes = 60,
    [string]$Fixture = ''
)
$ErrorActionPreference = 'Stop'
$started = Get-Date
# Progress goes straight to stdout: values returned by the functions below stay clean, and a caller that redirects or
# pipes the run sees each line as it happens.
function Put([string]$s) { [Console]::Out.WriteLine($s); [Console]::Out.Flush() }
function Say([string]$s) { Put "[verify] $s" }
function Csv([string[]]$a) { @($a | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
$Parts = Csv $Parts; $Skip = Csv $Skip; $Only = Csv $Only; $UiArgs = @($UiArgs | Where-Object { $_ -ne $null })

if ($Task -notmatch '^T\d+[a-z]?$') { Write-Output "verify-task: -Task is a task id such as T246 (got '$Task')"; exit 2 }
foreach ($p in $Parts) { if ($p -notmatch '^[a-z]+$') { Write-Output "verify-task: -Parts names android-ui.py parts (got '$p')"; exit 2 } }
$stepNames = @('fetch', 'worktree', 'base', 'privacy', 'data', 'check', 'slot', 'boot', 'tour') + @($Parts | ForEach-Object { "ui-$_" }) + @('stop', 'guard')
foreach ($s in $Skip + $Only) { if ($stepNames -notcontains $s) { Write-Output "verify-task: unknown step '$s' (steps: $($stepNames -join ', '))"; exit 2 } }
$deviceSteps = @('boot', 'tour', 'stop') + @($Parts | ForEach-Object { "ui-$_" })
function Selected([string]$name) { (-not $Only.Count -or $Only -contains $name) -and $Skip -notcontains $name }
if (@($deviceSteps | Where-Object { Selected $_ }).Count -and -not $Fixture) {
    if (-not $EmuPort -or -not $Port -or -not $Avd) { Write-Output 'verify-task: the device steps need -Avd, -EmuPort and -Port from the brief (or -Skip boot,tour,stop,guard)'; exit 2 }
    if ($Port -eq 8765 -or $EmuPort -eq 5554) { Write-Output 'verify-task: 8765 and console 5554 are the coordinator''s'; exit 2 }
}
if ($Server -match ':8765\b') { Write-Output 'verify-task: -Server must not be the coordinator''s live viewer (8765)'; exit 2 }

# --- where things are ----------------------------------------------------------------------------------------------
$here = Split-Path -Parent $PSScriptRoot
if ($Fixture) {
    $Fixture = [IO.Path]::GetFullPath($Fixture)
    $main = $Fixture; $wt = Join-Path $Fixture 'wt'; $lockDir = Join-Path $Fixture 'lock'
    $answers = Get-Content -Raw (Join-Path $Fixture 'answers.json') | ConvertFrom-Json
    $codesFile = Join-Path $Fixture 'codes.json'
    $codes = if (Test-Path $codesFile) { Get-Content -Raw $codesFile | ConvertFrom-Json } else { $null }
    if (-not $Out) { $Out = $Fixture }
    if (-not $Avd) { $Avd = "dystopo35-$Task" + 'v' }
    if (-not $EmuPort) { $EmuPort = 5570 }
    if (-not $Port) { $Port = 8694 }
} else {
    $common = (& git -C $here rev-parse --path-format=absolute --git-common-dir).Trim()
    $main = Split-Path -Parent ($common -replace '/', '\')
    if (-not $WorktreeRoot) { $WorktreeRoot = Join-Path (Split-Path -Parent $main) ((Split-Path -Leaf $main) + '-wt') }
    $wt = Join-Path ([IO.Path]::GetFullPath($WorktreeRoot)) "$Task-verify"
    $policyFile = Join-Path $main '.claude\hooks\policy.json'
    $policy = if (Test-Path $policyFile) { (Get-Content -Raw $policyFile | ConvertFrom-Json).emulators } else { $null }
    $lockDir = if ($policy -and $policy.lockDir) { $policy.lockDir -replace '^~', $env:USERPROFILE } else { Join-Path $env:USERPROFILE '.dystopo\emulators' }
    if (-not $Out) { $Out = Join-Path $wt 'out\verify' }
}
$Out = [IO.Path]::GetFullPath($Out)
if (-not $Branch) { $Branch = "task/$Task" }
$remoteRef = "origin/$Branch"
if (-not $Owner) { $Owner = "$Task-verify" }
$serial = "emulator-$EmuPort"
$emuMax = if ($policy -and $policy.max) { [int]$policy.max } else { 2 }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:USERPROFILE 'Android\sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'

if (-not $Fixture) {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    foreach ($jdk in @('C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot', 'C:\Program Files\Java\jdk-21')) { # the two PCs (machines.md)
        if (-not $env:JAVA_HOME -and (Test-Path $jdk)) { $env:JAVA_HOME = $jdk }
    }
    if (-not $env:JAVA_HOME) { Write-Output 'verify-task: no JAVA_HOME (pass -JavaHome)'; exit 2 }
    $env:JAVA_HOME = $env:JAVA_HOME -replace '/', '\'
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
    $env:DYSTOPO_CACHE = if ($Cache) { $Cache } elseif ($env:DYSTOPO_CACHE) { $env:DYSTOPO_CACHE } else { Join-Path $main 'cache' }
    # Every Gradle build of this run and of the scripts it runs (android-emu.ps1, android-push-pack.ps1) without a daemon.
    if ("$env:GRADLE_OPTS" -notmatch 'org\.gradle\.daemon=false') { $env:GRADLE_OPTS = ("$env:GRADLE_OPTS -Dorg.gradle.daemon=false").Trim() }
}
$logs = Join-Path $Out 'logs'
if (-not $DryRun) { New-Item -ItemType Directory -Force $logs | Out-Null }

# --- programs ------------------------------------------------------------------------------------------------------
function Quote([string]$a) {
    if ($a -eq '') { return '""' }
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    $a
}
$script:commands = New-Object System.Collections.Generic.List[object]
$script:current = ''
# Kills one process and its descendants by its PID (taskkill /T walks the tree from it; nothing is matched by name).
function Stop-Tree([int]$id) {
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $o = @(& taskkill.exe /PID $id /T /F 2>&1 | ForEach-Object { "$_" }) } catch { $o = @() } finally { $ErrorActionPreference = $eap }
    @($o | ForEach-Object { if ($_ -match 'PID (\d+)') { $Matches[1] } }) -join ', '
}
# Runs one program to completion, stdout and stderr in logs\<id>.*.log; returns its exit code. At the ceiling its process
# tree is killed by its PID and the step fails with what was killed.
function Invoke-Program([string]$id, [string]$exe, [string[]]$argv, [string]$cwd, [int]$timeoutS, [string]$stdin = '') {
    $line = "$cwd> " + ((@($exe) + $argv | ForEach-Object { Quote $_ }) -join ' ') + $(if ($stdin) { " < $stdin" } else { '' })
    $script:commands.Add([ordered]@{ step = $script:current; id = $id; command = $line })
    if ($DryRun) { Put "  $line"; return 0 }
    if ($Fixture) { if ($codes -and $null -ne $codes.$id) { return [int]$codes.$id } else { return 0 } }
    Put "  $line"
    $sp = @{ FilePath = $exe; ArgumentList = (($argv | ForEach-Object { Quote $_ }) -join ' '); NoNewWindow = $true; PassThru = $true
        RedirectStandardOutput = (Join-Path $logs "$id.out.log"); RedirectStandardError = (Join-Path $logs "$id.err.log"); WorkingDirectory = $cwd }
    if ($stdin) { $sp.RedirectStandardInput = $stdin }
    $p = Start-Process @sp
    $null = $p.Handle # makes ExitCode available after the wait
    if ($p.WaitForExit([int][math]::Min([int]::MaxValue, $timeoutS * 1000.0))) { return $p.ExitCode }
    $killed = Stop-Tree $p.Id
    $null = $p.WaitForExit(30000)
    throw "timed out at its ceiling of $timeoutS s: $([IO.Path]::GetFileName($exe)) (PID $($p.Id)) and its process tree killed by PID (taskkill /T: $(if ($killed) { $killed } else { 'none reported' }))"
}
function Log-Text([string]$id, [string]$which = 'out') {
    $f = Join-Path $logs "$id.$which.log"
    if (Test-Path $f) { "$(Get-Content -Raw $f)".Trim() } else { '' }
}
function Log-Tail([string]$id, [int]$n = 4) {
    $t = @((Log-Text $id 'out') -split "`r?`n" | Where-Object { $_.Trim() }) + @((Log-Text $id 'err') -split "`r?`n" | Where-Object { $_.Trim() })
    (@($t | Select-Object -Last $n) -join ' | ') -replace '\s+', ' '
}
# A short read-only query (git, adb): its trimmed stdout. In a dry run it is printed and '' returned; with -Fixture the
# answer is answers.json's $key.
function Query([string]$key, [string]$exe, [string[]]$argv) {
    if ($Fixture) { $v = $answers.$key; return $(if ($null -eq $v) { '' } else { "$v".Trim() }) }
    $null = Invoke-Program "q-$key" $exe $argv $main 120
    if ($DryRun) { return '' }
    Log-Text "q-$key"
}

# --- the summary ---------------------------------------------------------------------------------------------------
function Write-Summary {
    $file = Join-Path $Out 'verify-summary.json'
    $stepsOut = @($script:steps.Values)
    # A rerun with -Only or -Skip keeps the earlier run's results for the steps it did not select.
    if (($Only.Count -or $Skip.Count) -and (Test-Path $file)) {
        try {
            $prev = Get-Content -Raw $file | ConvertFrom-Json
            if ($prev.task -eq $Task) {
                if (-not $script:headSha) { $script:headSha = $prev.head }
                $stepsOut = @(foreach ($s in $stepsOut) {
                    $old = @($prev.steps | Where-Object { $_.name -eq $s.name })[0]
                    if ($s.status -eq 'skipped' -and $old -and $old.status -ne 'skipped') { $o = $old; if (-not $o.from) { $o | Add-Member -Force NoteProperty from $prev.started }; $o } else { $s }
                })
            }
        } catch { }
    }
    $okAll = -not @($stepsOut | Where-Object { $_.status -eq 'failed' }).Count
    $failedStep = @($stepsOut | Where-Object { $_.status -eq 'failed' } | ForEach-Object { $_.name })[0]
    $sum = [ordered]@{
        task = $Task; branch = $Branch; head = $script:headSha; worktree = $wt; out = $Out
        started = $started.ToString('o'); seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
        ok = $okAll; failedStep = $failedStep; dryRun = [bool]$DryRun
        steps = $stepsOut; screenshots = $script:screens.ToArray(); commands = $script:commands.ToArray()
    }
    New-Item -ItemType Directory -Force $Out | Out-Null
    [IO.File]::WriteAllText($file, ($sum | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    $lines = @()
    $lines += "verify $Task at $(if ($script:headSha) { $script:headSha } else { '?' }) ($remoteRef), worktree $wt"
    $lines += if ($okAll) { "RESULT: ok ($(@($stepsOut | Where-Object { $_.status -eq 'ok' }).Count) steps ok)" } else { "RESULT: FAILED at step $failedStep" }
    foreach ($s in $stepsOut) {
        $detail = if ($s.error) { $s.error } elseif ($s.note) { $s.note } else { '' }
        $lines += ("{0,-10} {1,-8} {2,7} s  {3}" -f $s.name, $s.status, $(if ($null -ne $s.seconds) { $s.seconds } else { '-' }), $detail)
    }
    if ($script:screens.Count) { $lines += "screenshots: $($script:screens.Count) (look at each; the sheet first): $(@($script:screens | Where-Object { $_ -like '*sheet*' }) -join ', ')" }
    $lines += "summary: $file"
    [IO.File]::WriteAllText((Join-Path $Out 'verify-summary.txt'), (($lines -join "`r`n") + "`r`n"), [Text.UTF8Encoding]::new($false))
    $script:textLines = $lines
}

# --- steps ---------------------------------------------------------------------------------------------------------
$script:steps = [ordered]@{}
foreach ($n in $stepNames) { $script:steps[$n] = [ordered]@{ name = $n; status = $(if (Selected $n) { 'not run' } else { 'skipped' }); ok = $null } }
$script:failed = ''
$script:bootRan = $false
$script:screens = New-Object System.Collections.Generic.List[string]
$script:headSha = ''

function Fail([string]$why) { throw [System.Exception]::new("STEPFAIL:$why") }
# Runs one step's body, which returns a hashtable of figures (merged into the step) and calls Fail for a failure.
function Run-Step([string]$name, [scriptblock]$body, [switch]$Cleanup) {
    $s = $script:steps[$name]
    if (-not (Selected $name)) { return }
    if ($script:failed -and -not $Continue -and -not $Cleanup) { return }
    $script:current = $name
    Say "$name ..."
    $t0 = Get-Date
    try {
        $figs = & $body
        if ($figs -is [System.Collections.IDictionary]) { foreach ($k in @($figs.Keys)) { $s[$k] = $figs[$k] } }
        $s.status = if ($DryRun) { 'dry run' } else { 'ok' }
        $s.ok = $true
    } catch {
        $msg = $_.Exception.Message -replace '^STEPFAIL:', ''
        $s.status = 'failed'; $s.ok = $false; $s.error = $msg
        if (-not $script:failed) { $script:failed = $name }
    }
    $s.seconds = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
    Say ("{0} {1} in {2} s{3}" -f $name, $s.status, $s.seconds, $(if ($s.error) { ": $($s.error)" } elseif ($s.note) { ": $($s.note)" } else { '' }))
    if (-not $DryRun) { Write-Summary }
}

function GitArgs([string[]]$a) { @('-C', $main) + $a }

Run-Step 'fetch' {
    $c = Invoke-Program 'fetch' 'git' (GitArgs @('fetch', '--prune', 'origin')) $main 600
    if ($c -ne 0) { Fail "git fetch exit ${c}: $(Log-Tail 'fetch')" }
    $sha = Query 'head' 'git' (GitArgs @('rev-parse', '--verify', '--quiet', "$remoteRef^{commit}"))
    if (-not $DryRun -and -not $sha) { Fail "no $remoteRef on the remote (pushed?)" }
    $script:headSha = $sha
    @{ head = $sha }
}

Run-Step 'worktree' {
    $isWt = (Test-Path (Join-Path $wt '.git'))
    if ($Fixture) { $isWt = $true }
    # An empty folder is fine (git worktree add fills it): a removed worktree's folder can stay while a process that
    # started inside it (seen in T246: one started during the run, the adb server likely) holds it as its directory.
    $emptyDir = (Test-Path $wt) -and -not @(Get-ChildItem -Force -LiteralPath $wt -ErrorAction SilentlyContinue).Count
    if (-not $isWt -and (Test-Path $wt) -and -not $emptyDir -and -not $DryRun) { Fail "$wt exists but is not a worktree: remove it (docs/reference/windows-and-shells.md, Worktrees)" }
    if ($isWt) {
        $dirty = Query 'dirty' 'git' @('-C', $wt, 'status', '--porcelain', '--untracked-files=no')
        if ($dirty) { Fail "$wt exists with changes ($(($dirty -split "`n").Count) files): commit or push your rebase first, or remove it" }
        $c = Invoke-Program 'worktree' 'git' @('-C', $wt, 'checkout', '--detach', '--quiet', $remoteRef) $wt 300
        if ($c -ne 0) { Fail "checkout of $remoteRef in $wt exit ${c}: $(Log-Tail 'worktree')" }
        return @{ path = $wt; reused = $true }
    }
    $c = Invoke-Program 'worktree' 'git' (GitArgs @('worktree', 'add', '--detach', $wt, $remoteRef)) $main 600
    if ($c -ne 0) { Fail "git worktree add exit ${c}: $(Log-Tail 'worktree')" }
    @{ path = $wt; reused = $false }
}

Run-Step 'base' {
    $mb = Query 'mergeBase' 'git' (GitArgs @('merge-base', 'origin/main', $remoteRef))
    $mainSha = Query 'mainHead' 'git' (GitArgs @('rev-parse', 'origin/main'))
    $behind = Query 'behind' 'git' (GitArgs @('rev-list', '--count', "$(if ($mb) { $mb } else { '<merge-base>' })..origin/main"))
    $ahead = Query 'ahead' 'git' (GitArgs @('rev-list', '--count', "origin/main..$remoteRef"))
    if ($DryRun) { return @{} }
    $f = @{ mergeBase = $mb; mainHead = $mainSha; mainMovedBy = [int]$behind; branchCommits = [int]$ahead }
    if ([int]$behind -gt 0) {
        $f.note = "origin/main moved $behind commits since the fork"
        if (-not $AllowBehind) {
            foreach ($k in $f.Keys) { $script:steps['base'][$k] = $f[$k] }
            Fail "origin/main moved $behind commits since the fork ($($mb.Substring(0, [math]::Min(9, $mb.Length)))): bring the branch up to date in $wt (git rebase origin/main, then git push --force-with-lease origin HEAD:$Branch; where a force push is refused, git merge origin/main, then git push origin HEAD:$Branch), then run this again (or -AllowBehind)"
        }
    } else { $f.note = 'origin/main has not moved since the fork' }
    $f
}

Run-Step 'privacy' {
    $msgFile = Join-Path $logs 'privacy-input.txt'
    $c = Invoke-Program 'privacy-log' 'git' (GitArgs @('log', "origin/main..$remoteRef", '--format=%B')) $main 120
    if ($c -ne 0) { Fail "git log exit ${c}: $(Log-Tail 'privacy-log')" }
    if (-not $DryRun -and -not $Fixture) {
        $text = Log-Text 'privacy-log'
        if ($ReportFile) { $text += "`n" + (Get-Content -Raw $ReportFile) }
        [IO.File]::WriteAllText($msgFile, $text + "`n")
    }
    $java = if ($Fixture) { 'java' } else { Join-Path $env:JAVA_HOME 'bin\java.exe' }
    $c = Invoke-Program 'privacy' $java @('tools\PrivacyCheck.java', 'scan') $(if (Test-Path $wt) { $wt } else { $main }) 300 $msgFile
    if ($DryRun) { return @{} }
    $j = $null
    try { $j = (Log-Text 'privacy') | ConvertFrom-Json } catch { }
    if (-not $j) { Fail "PrivacyCheck scan exit ${c} without its JSON: $(Log-Tail 'privacy')" }
    $f = @{ lines = $j.lines; termHits = $j.termHits; coordinateValueHits = $j.coordinateValueHits; coordinatePairHits = $j.coordinatePairHits
        pairsOutsidePublicAreas = $j.pairsOutsidePublicAreas; localFile = $j.localFile; reportScanned = [bool]$ReportFile }
    $hits = [int]$j.termHits + [int]$j.coordinateValueHits + [int]$j.coordinatePairHits
    if ($c -ne 0 -or $hits -gt 0) { foreach ($k in $f.Keys) { $script:steps['privacy'][$k] = $f[$k] }; Fail "PrivacyCheck scan found $hits hits (exit $c): describe the place generically" }
    $f.note = "clean ($($j.lines) lines$(if ($j.localFile -eq $false) { '; no devtool.local.json, so no private terms: read the text yourself' }))"
    $f
}

Run-Step 'data' {
    $copied = @()
    foreach ($rel in @('local.properties', 'out\rainier-dem.pmtiles', 'out\rainier-outdoors.pmtiles')) {
        $src = Join-Path $main $rel; $dst = Join-Path $wt $rel
        if ($DryRun) { Put "  copy $src -> $dst"; continue }
        if ($Fixture) { $copied += $rel; continue }
        if (-not (Test-Path $src)) {
            if ($rel -like 'out\*' -and -not (Selected 'boot')) { continue }
            Fail "$src missing in the main checkout (build the Rainier packs first: docs/reference/android-emulator.md)"
        }
        if ((Test-Path $dst) -and (Get-Item $dst).Length -eq (Get-Item $src).Length) { continue }
        New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
        Copy-Item -LiteralPath $src -Destination $dst -Force
        $copied += $rel
    }
    @{ copied = $copied }
}

# The test counts of every TEST-*.xml under the worktree's */build/test-results (JUnit XML, one per test class).
function Read-Tests {
    $t = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0; files = 0; modules = [ordered]@{} }
    foreach ($dir in @(Get-ChildItem -LiteralPath $wt -Directory -ErrorAction SilentlyContinue)) {
        $res = Join-Path $dir.FullName 'build\test-results'
        if (-not (Test-Path $res)) { continue }
        $m = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
        foreach ($f in @(Get-ChildItem -LiteralPath $res -Recurse -Filter 'TEST-*.xml')) {
            try { $x = ([xml](Get-Content -Raw -LiteralPath $f.FullName)).testsuite } catch { continue }
            foreach ($k in @('tests', 'failures', 'errors', 'skipped')) { $m[$k] += [int]$x.$k; $t[$k] += [int]$x.$k }
            $t.files++
        }
        if ($m.tests) { $t.modules[$dir.Name] = $m }
    }
    $t
}

Run-Step 'check' {
    $gradle = Join-Path $wt 'gradlew.bat'
    $c = Invoke-Program 'check' $gradle @('check', '--no-daemon', '--console=plain') $wt 2400
    if ($DryRun) { return @{} }
    $t = Read-Tests
    $f = @{ tests = $t.tests; failures = $t.failures; errors = $t.errors; skipped = $t.skipped; testFiles = $t.files; modules = $t.modules
        log = (Join-Path $logs 'check.out.log') }
    $f.note = "$($t.tests) tests, $($t.failures) failures, $($t.errors) errors, $($t.skipped) skipped in $($t.files) files"
    if ($c -ne 0) {
        foreach ($k in $f.Keys) { $script:steps['check'][$k] = $f[$k] }
        $what = @((Log-Text 'check') -split "`r?`n" | Where-Object { $_ -match "^> Task .*FAILED|^\* What went wrong|FAILED$" } | Select-Object -First 3) -join ' | '
        Fail "gradlew check exit ${c}: $(if ($what) { $what } else { Log-Tail 'check' }) ($($f.note))"
    }
    $f
}

Run-Step 'slot' {
    # A wrong console port fails here, before the build: android-emu.ps1's own shape rule, and a serial that runs another AVD.
    if ($EmuPort % 2 -ne 0 -or $EmuPort -lt 5554 -or $EmuPort -gt 5682) { Fail "-EmuPort must be even, 5554-5682 (got $EmuPort)" }
    $deadline = (Get-Date).AddMinutes($SlotWaitMinutes)
    $waited = 0
    while ($true) {
        $listing = Query 'adbDevices' $adb @('devices')
        if ($DryRun) { return @{} }
        $all = @(($listing -split "`r?`n") | ForEach-Object { if ($_ -match '^(emulator-\d+)\s+\S+') { $Matches[1] } })
        if ($all -contains $serial) {
            $name = @((Query 'avdName' $adb @('-s', $serial, 'emu', 'avd', 'name')) -split "`r?`n")[0].Trim()
            if ($name -ne $Avd) { Fail "console port $EmuPort is taken: $serial runs $(if ($name) { "AVD $name" } else { 'an AVD that does not answer' }), not $Avd (the brief's port?)" }
        }
        $others = @($all | Where-Object { $_ -ne $serial })
        if ($others.Count -lt $emuMax) { return @{ others = $others; cap = $emuMax; waitedSeconds = $waited; note = "$($others.Count) of $emuMax slots taken by others$(if ($waited) { "; waited $waited s" })" } }
        if ((Get-Date) -ge $deadline -or $Fixture) { Fail "emulator cap $emuMax still taken after $waited s: $($others -join ', ')" }
        if ($waited % 60 -eq 0) { Say "slot: $($others.Count) of $emuMax taken ($($others -join ', ')); waiting" }
        Start-Sleep -Seconds 10; $waited += 10
    }
}

function EmuArgs([string[]]$extra) {
    $a = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $wt 'tools\android-emu.ps1'), '-Avd', $Avd, '-EmuPort', "$EmuPort", '-Port', "$Port")
    if ($AvdHome) { $a += @('-AvdHome', $AvdHome) }
    $a + $extra
}

Run-Step 'boot' {
    $script:bootRan = -not $DryRun
    $script:bootStarted = (Get-Date).ToUniversalTime()
    $extra = @('-Build', '-Owner', $Owner, '-Server', $Server)
    if ($PrivateLimitGB -gt 0) { $extra += @('-PrivateLimitGB', "$PrivateLimitGB") }
    $c = Invoke-Program 'boot' 'powershell.exe' (EmuArgs $extra) $wt 1500
    if ($DryRun) { return @{} }
    $built = if ((Log-Text 'boot') -match 'built in ([\d,]+) s') { [int]($Matches[1] -replace ',', '') } else { $null }
    if ($c -ne 0) { Fail "android-emu.ps1 -Build exit ${c}: $(Log-Tail 'boot' 3)" }
    @{ serial = $serial; buildSeconds = $built; note = "$serial booted, the branch's debug APK installed, server $Server" }
}

Run-Step 'tour' {
    $dir = Join-Path $Out 'tour'
    if (-not $DryRun -and -not $Fixture) { New-Item -ItemType Directory -Force $dir | Out-Null }
    $c = Invoke-Program 'tour' 'python' @('-I', 'tools/android-check.py', '--port', "$Port", '--serial', $serial, '--views-file', 'tools/android-tour.txt',
        '--server', $Server, '--out', (Join-Path $dir 'tour.png')) $wt 1500
    if ($DryRun) { return @{} }
    $j = $null
    try { $j = Get-Content -Raw (Join-Path $dir 'tour.json') | ConvertFrom-Json } catch { }
    if (-not $j) { Fail "android-check.py exit $c without tour.json: $(Log-Tail 'tour')" }
    $shots = @(Get-ChildItem -LiteralPath $dir -Filter '*.png' -ErrorAction SilentlyContinue | Sort-Object Name | ForEach-Object { $_.FullName })
    foreach ($s in $shots) { $script:screens.Add($s) }
    $badViews = @($j.views | Where-Object { -not $_.ok } | ForEach-Object { "$($_.label): $(@($_.failed) -join ' ')" })
    $f = [ordered]@{ viewsOk = $j.viewsOk; viewsTotal = $j.viewsTotal; tourOk = $j.ok; glyphsOk = $j.glyphs.ok
        backgroundOk = $j.backgroundStep.ok; directOk = $j.directStep.ok; cardsOk = $j.cardsStep.ok; failedViews = $badViews
        sheet = $j.sheet; screenshots = $shots.Count; json = (Join-Path $dir 'tour.json') }
    $f.note = "$($j.viewsOk)/$($j.viewsTotal) views ok; sheet $($j.sheet)"
    if ($c -ne 0 -or -not $j.ok) {
        foreach ($k in @($f.Keys)) { $script:steps['tour'][$k] = $f[$k] }
        $parts = @(foreach ($k in @('glyphsOk', 'backgroundOk', 'directOk', 'cardsOk')) { if ($f[$k] -eq $false) { $k -replace 'Ok$', '' } })
        Fail "tour not ok (exit $c): $($f.note)$(if ($badViews.Count) { '; ' + ($badViews -join '; ') })$(if ($parts.Count) { '; failed steps: ' + ($parts -join ', ') })"
    }
    $f
}

foreach ($part in $Parts) {
    Run-Step "ui-$part" {
        $dir = Join-Path $Out "ui\$part"
        if (-not $DryRun -and -not $Fixture) { New-Item -ItemType Directory -Force $dir | Out-Null }
        $c = Invoke-Program "ui-$part" 'python' (@('-I', 'tools/android-ui.py', $part, '--serial', $serial, '--port', "$Port", '--out', $dir) + $UiArgs) $wt 900
        if ($DryRun) { return @{} }
        $j = $null
        try { $j = (Log-Text "ui-$part") | ConvertFrom-Json } catch { }
        $shots = @(Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.png' -ErrorAction SilentlyContinue | Sort-Object FullName | ForEach-Object { $_.FullName })
        foreach ($s in $shots) { $script:screens.Add($s) }
        $f = @{ partOk = $(if ($j) { [bool]$j.ok } else { $false }); screenshots = $shots.Count; json = (Join-Path $logs "ui-$part.out.log") }
        if ($c -ne 0 -or -not $f.partOk) {
            foreach ($k in $f.Keys) { $script:steps["ui-$part"][$k] = $f[$k] }
            Fail "android-ui.py $part exit ${c}: $(if ($j -and $j.error) { "$($j.error)".Substring(0, [math]::Min(300, "$($j.error)".Length)) } else { Log-Tail "ui-$part" })"
        }
        $f.note = "ok, $($shots.Count) screenshots"
        $f
    }
}

$stopCommand = 'powershell -ExecutionPolicy Bypass -File ' + ((EmuArgs @('-Stop', '-Delete') | Select-Object -Skip 4 | ForEach-Object { Quote $_ }) -join ' ')
if ($KeepEmulator -and (Selected 'stop')) {
    $script:steps['stop'].status = 'kept'
    $script:steps['stop'].note = "-KeepEmulator: stop it yourself with $stopCommand, then read the guard with -Only stop,guard"
} elseif ($script:bootRan -or $DryRun -or $Only -contains 'stop') {
    Run-Step 'stop' {
        $c = Invoke-Program 'stop' 'powershell.exe' (EmuArgs @('-Stop', '-Delete')) $wt 300
        if ($DryRun) { return @{} }
        if ($c -ne 0) { Fail "android-emu.ps1 -Stop -Delete exit ${c}: $(Log-Tail 'stop')" }
        $listing = Query 'adbDevicesAfter' $adb @('devices')
        if ($listing -match "(?m)^$([regex]::Escape($serial))\s") { Fail "$serial still listed by adb devices after the stop" }
        @{ note = "$serial stopped and $Avd deleted; adb devices no longer lists it" }
    } -Cleanup
}

# Figures from the guard's log since the boot: free RAM is in its start line and in every per-GB line, private bytes in
# those lines and in its end line (peak private).
function Read-Guard([datetime]$since) {
    $log = Join-Path $wt "out\emulator-guard-$Avd.log"
    $g = [ordered]@{ log = $log; lines = 0; lowestFreeGB = $null; peakPrivateGB = $null; stops = @() }
    $stamp = '^(\d{4}-\d\d-\d\dT[\d:.]+Z)\s+(.*)$'
    function In-Range([string]$line) {
        if ($line -notmatch $stamp) { return $null }
        $t = [datetime]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime()
        if ($t -lt $since) { return $null }
        $Matches[2]
    }
    if (Test-Path $log) {
        foreach ($line in Get-Content -LiteralPath $log) {
            $body = In-Range $line
            if ($null -eq $body) { continue }
            $g.lines++
            foreach ($m in [regex]::Matches($body, 'free (?:now |RAM )?([\d,]+\.\d) GB')) {
                $v = [double]($m.Groups[1].Value -replace ',', '')
                if ($null -eq $g.lowestFreeGB -or $v -lt $g.lowestFreeGB) { $g.lowestFreeGB = $v }
            }
            foreach ($m in [regex]::Matches($body, 'private (?:bytes )?([\d,]+\.\d) GB')) {
                $v = [double]($m.Groups[1].Value -replace ',', '')
                if ($null -eq $g.peakPrivateGB -or $v -gt $g.peakPrivateGB) { $g.peakPrivateGB = $v }
            }
        }
    }
    $stopLog = Join-Path $lockDir 'stop.log'
    if (Test-Path $stopLog) {
        foreach ($line in Get-Content -LiteralPath $stopLog) {
            $body = In-Range $line
            if ($null -ne $body -and ($body -match "\b$([regex]::Escape($serial))\b" -or $body -match "\($([regex]::Escape($Avd))\b")) { $g.stops += $body }
        }
    }
    $g
}

if ($script:bootRan -or $DryRun -or $Only -contains 'guard') {
    Run-Step 'guard' {
        if ($DryRun) { Put "  read $(Join-Path $wt "out\emulator-guard-$Avd.log") and $(Join-Path $lockDir 'stop.log')"; return @{} }
        $since = if ($Fixture) { [datetime]::MinValue } elseif ($script:bootStarted) { $script:bootStarted } else { $started.ToUniversalTime() }
        if ($Only -contains 'guard' -and -not $script:bootRan) { $since = (Get-Date).ToUniversalTime().AddDays(-1) } # a later -Only stop,guard: the last day's lines
        $g = Read-Guard $since
        $g.note = "lowest free $($g.lowestFreeGB) GB, peak private $($g.peakPrivateGB) GB, $($g.stops.Count) stop.log lines"
        if (-not $g.lines) { $g.note = "no guard lines since the boot in $($g.log)" }
        if ($g.stops.Count) { foreach ($k in @($g.Keys)) { $script:steps['guard'][$k] = $g[$k] }; Fail "stop.log: $($g.stops -join ' | ')" }
        $g
    } -Cleanup
}


if ($DryRun) {
    Say "dry run: $($script:commands.Count) commands listed, nothing run"
    exit 0
}
Write-Summary
Write-Output ''
$script:textLines | ForEach-Object { Write-Output $_ }
if ($script:failed) { exit 1 }
exit 0
