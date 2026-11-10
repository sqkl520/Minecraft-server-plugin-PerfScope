# perfscope-jfr-overhead.ps1 -- measure PerfScope's own share of JVM thread-time
# via JDK Flight Recorder (JFR) sampling.
#
# Usage (while the perfscope server is RUNNING):
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\perfscope-jfr-overhead.ps1 -DurationSec 90
#
# Optional:
#   -ServerPid 12345        target JVM directly (default: whoever listens on -Port)
#   -Port 25668             port used to discover the server JVM
#   -OutFile <path>         JFR file (default: <workspace>\logs\perfscope-overhead-<ts>.jfr)
#   -PluginsDir <path>      plugin directory of the target server (default: <jvm user.dir>\plugins)
#   -MainThreadName 'Server thread'   name of the tick thread in the sampled JVM
#   -MinServerSamples 30    below this many main-thread samples no conclusion is reported
#   -Settings profile       JFR settings: a preset name, or the path of a .jfc file
#                           (a custom file lets you set equal periods for a fair share)
#   -StackDepth 64          frames kept per sample (frame-LINE counts scale with it)
#   -FromFile <x.jfr>       analyze an existing recording instead of recording a new one
#
# IMPORTANT -- what the headline number is, and is not:
#   * A JFR sampling share is "fraction of samples whose stack contains a cn.perfscope
#     frame". It is a share of SAMPLES, not a CPU percentage, and it is NOT a TPS or
#     tick-latency impact.
#   * Frames under cn.perfscope.shaded.* are EXCLUDED. Those are third-party libraries
#     bundled into the jar (bStats); bStats opens an HTTPS connection to report anonymous
#     usage statistics, and counting its network stack as PerfScope overhead inflated the
#     headline: with every collector disabled the number still read 6.46%.
#   * This script requests BOTH jdk.ExecutionSample (Java code on-CPU) and
#     jdk.NativeMethodSample (native/blocked frames). Under settings=profile the two
#     events have DIFFERENT periods (10 ms vs 20 ms), so the denominator is dominated
#     by idle threads blocking in native I/O. Treat it as an upper bound on
#     "how often PerfScope shows up in a stack", never as CPU usage. For a fair share,
#     pass a .jfc with equal periods for both events.
#   * Counting is per SAMPLE BLOCK (one jdk.*Sample { ... } block = one sample).
#     Counting matching LINES instead inflates the number ~3x, because a single
#     sample spans several frames of the same call chain. Both numbers are printed,
#     clearly labelled; only the sample count is the headline.
#   * Main-thread (Server thread) samples are often few (tens in a 90 s window on an
#     idle server), so the script prints a Wilson 95% confidence interval and refuses
#     to draw a conclusion when the count is below -MinServerSamples. A publishable
#     measurement needs >= 1000 main-thread samples.
#   * The build under test is identified from plugins\PerfScope-*.jar (version from
#     MANIFEST.MF or plugin.yml, plus a SHA-256 prefix) and printed in the header.
#     A result without that line is not attributable to any build.
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads BOM-less .ps1 files as ANSI,
# so non-ASCII text in this file would be mojibake on some hosts.

[CmdletBinding()]
param(
    [int]$DurationSec = 90,
    [int]$Port = 25668,
    [int]$ServerPid = 0,
    [string]$OutFile = '',
    [string]$PluginsDir = '',
    [string]$JdkHome = 'D:\Java\zulu21.48.17-ca-jdk21.0.10-win_x64',
    [string]$MainThreadName = 'Server thread',
    [string]$Settings = 'profile',
    [int]$MinServerSamples = 30,
    [int]$StackDepth = 64,
    [string]$SummaryPath = '',
    # Analyze an already-recorded .jfr instead of recording a new one. The build
    # identity is then read from -PluginsDir (or is reported as unknown), and no
    # live JVM is contacted -- useful for re-checking an archived recording.
    [string]$FromFile = ''
)

$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue

$script:Report = New-Object System.Collections.Generic.List[string]

function Write-Report {
    param([string]$Text = '')
    Write-Host $Text
    $script:Report.Add($Text) | Out-Null
}

function Format-Pct {
    param([double]$Value, [int]$Digits = 2)
    # Culture-invariant on purpose: the summary file is parsed by tools and pasted
    # into README; a comma decimal separator would corrupt both.
    return $Value.ToString('F' + $Digits, [Globalization.CultureInfo]::InvariantCulture)
}

# Wilson score interval -- the right tool for "k hits out of n samples" with tiny n.
# Normal approximation would happily return a negative lower bound here.
function Get-WilsonInterval {
    param([int]$Hits, [int]$Total, [double]$Z = 1.96)
    if ($Total -le 0) { return $null }
    $p = [double]$Hits / [double]$Total
    $z2 = $Z * $Z
    $denom = 1.0 + ($z2 / $Total)
    $center = ($p + ($z2 / (2.0 * $Total))) / $denom
    $radicand = ($p * (1.0 - $p) / $Total) + ($z2 / (4.0 * $Total * $Total))
    if ($radicand -lt 0) { $radicand = 0 }
    $half = ($Z * [math]::Sqrt($radicand)) / $denom
    return [pscustomobject]@{
        Point = $p
        Low   = [math]::Max(0.0, $center - $half)
        High  = [math]::Min(1.0, $center + $half)
    }
}

# jcmd/JFR option values that contain spaces must reach the native process with
# literal double quotes around the path. PowerShell 5.1 strips quotes added as
# `"..."` before the process is spawned, so the quotes are passed backslash-escaped
# (\" ... \") inside a single argument; jcmd's option parser turns them back into
# plain quotes. Verified against jcmd 21 with a path containing spaces.
function New-QuotedJfrValue {
    param([string]$Name, [string]$Value)
    return [string]::Concat($Name, '=\"', $Value, '\"')
}

# ---------------------------------------------------------------------------
# 1) locate the target JVM (skipped when -FromFile replays an existing recording)
# ---------------------------------------------------------------------------
$jcmd = Join-Path $JdkHome 'bin\jcmd.exe'
$jfr = Join-Path $JdkHome 'bin\jfr.exe'
if (-not (Test-Path -LiteralPath $jfr)) {
    throw "JDK tool not found: $jfr  (pass -JdkHome <jdk-root>)"
}

$targetPid = $ServerPid
$jvmCwd = ''
$jvmJavaVersion = ''

if ($FromFile -ne '') {
    if (-not (Test-Path -LiteralPath $FromFile)) { throw "Recording not found: $FromFile" }
    $OutFile = [System.IO.Path]::GetFullPath($FromFile)
} else {
    if (-not (Test-Path -LiteralPath $jcmd)) {
        throw "JDK tool not found: $jcmd  (pass -JdkHome <jdk-root>)"
    }
    if ($targetPid -le 0) {
        if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
            $conn = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
                Select-Object -First 1
            if ($null -ne $conn) { $targetPid = [int]$conn.OwningProcess }
        }
        if ($targetPid -le 0) {
            # Fallback for hosts without the NetTCPIP module: parse netstat -ano.
            $listenLine = (& netstat.exe -ano) | Where-Object { $_ -match "LISTENING\s+\d+\s*$" -and $_ -match ":$Port\s" } |
                Select-Object -First 1
            if ($null -ne $listenLine) {
                $targetPid = [int](($listenLine -split '\s+') | Where-Object { $_ -ne '' } | Select-Object -Last 1)
            }
        }
        if ($targetPid -le 0) {
            throw "No process is listening on port $Port. Start the test server first, or pass -ServerPid."
        }
    }

    $proc = Get-Process -Id $targetPid -ErrorAction SilentlyContinue
    if ($null -eq $proc) { throw "PID $targetPid does not exist (or is not accessible)." }
}

# ---------------------------------------------------------------------------
# 2) read the target JVM's working directory -> plugins dir -> build identity
# ---------------------------------------------------------------------------
if ($FromFile -eq '') {
    $props = & $jcmd $targetPid VM.system_properties 2>$null
    foreach ($line in $props) {
        if ($line -match '^\s*user\.dir=(.*)$') {
            # java.util.Properties escapes ':' and '\' on write; undo both.
            $jvmCwd = $Matches[1]
            $jvmCwd = $jvmCwd -replace '\\\\', [char]1
            $jvmCwd = $jvmCwd -replace '\\:', ':'
            $jvmCwd = $jvmCwd -replace [string][char]1, '\'
        } elseif ($line -match '^\s*java\.version=(.*)$') {
            $jvmJavaVersion = $Matches[1]
        }
    }
}

if ($PluginsDir -eq '') {
    if ($jvmCwd -ne '') { $PluginsDir = Join-Path $jvmCwd 'plugins' }
    else { $PluginsDir = Join-Path $PSScriptRoot '..\PerfScope\plugins' }
}

function Read-JarEntryText {
    param([string]$JarPath, [string]$EntryName)
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
        try {
            $entry = $zip.Entries | Where-Object { $_.FullName -eq $EntryName } | Select-Object -First 1
            if ($null -eq $entry) { return '' }
            $reader = New-Object System.IO.StreamReader($entry.Open())
            try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
        } finally { $zip.Dispose() }
    } catch {
        return ''
    }
}

$pluginVersion = ''
$versionSource = 'unknown'
$jarPath = ''
$jarSha = ''
$jarSize = 0
$jarTime = $null

if (Test-Path -LiteralPath $PluginsDir) {
    $jarPath = Get-ChildItem -LiteralPath $PluginsDir -Filter 'PerfScope-*.jar' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 |
        ForEach-Object { $_.FullName }
}

if ($jarPath -ne '') {
    $item = Get-Item -LiteralPath $jarPath
    $jarSize = $item.Length
    $jarTime = $item.LastWriteTime
    $jarSha = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.Substring(0, 16).ToLowerInvariant()

    # Preferred: an Implementation-Version in the manifest (build-time metadata).
    $manifest = Read-JarEntryText -JarPath $jarPath -EntryName 'META-INF/MANIFEST.MF'
    if ($manifest -match '(?m)^Implementation-Version:\s*(.+?)\s*$') {
        $pluginVersion = $Matches[1]
        $versionSource = 'MANIFEST.MF Implementation-Version'
    }
    # Fallback: the shipped plugin.yml (version is filtered from ${project.version}).
    if ($pluginVersion -eq '') {
        $pluginYml = Read-JarEntryText -JarPath $jarPath -EntryName 'plugin.yml'
        if ($pluginYml -match '(?m)^version:\s*(.+?)\s*$') {
            $pluginVersion = $Matches[1]
            $versionSource = 'jar plugin.yml version'
        }
    }
}

# An installed plugin.yml (if a server wrote one out) wins over the jar only when
# the jar is missing -- the jar is what actually runs.
$installedYml = Join-Path $PluginsDir 'PerfScope\plugin.yml'
if ($pluginVersion -eq '' -and (Test-Path -LiteralPath $installedYml)) {
    $yml = Get-Content -LiteralPath $installedYml -ErrorAction SilentlyContinue
    foreach ($line in $yml) {
        if ($line -match '^\s*version:\s*(.+?)\s*$') {
            $pluginVersion = $Matches[1]
            $versionSource = "plugin.yml at $installedYml"
            break
        }
    }
}
if ($pluginVersion -eq '') {
    $pluginVersion = '(unknown)'
    $versionSource = 'not found (no jar manifest entry, no plugin.yml)'
}

# ---------------------------------------------------------------------------
# 3) record (skipped with -FromFile)
# ---------------------------------------------------------------------------
if ($FromFile -eq '') {
    if ($OutFile -eq '') {
        $logDir = Join-Path $PSScriptRoot '..\logs'
        if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
        $OutFile = Join-Path $logDir ('perfscope-overhead-{0}.jfr' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
    }
    $OutFile = [System.IO.Path]::GetFullPath($OutFile)
    $outDir = Split-Path -Parent $OutFile
    if ($outDir -ne '' -and -not (Test-Path -LiteralPath $outDir)) {
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    }
    if (Test-Path -LiteralPath $OutFile) { Remove-Item -LiteralPath $OutFile -Force }

    $recName = 'perfscope_overhead_' + (Get-Date -Format 'yyyyMMddHHmmss')
    # jcmd resolves filename= inside the TARGET JVM, relative to ITS working directory,
    # never relative to the caller's cwd. So the path handed over is always absolute,
    # and it is quoted for spaces (see New-QuotedJfrValue).
    $fileArg = New-QuotedJfrValue -Name 'filename' -Value $OutFile
    # A settings FILE path may also contain spaces, so it goes through the same
    # quoting; a bare preset name ('profile' / 'default') is passed as-is.
    if (Test-Path -LiteralPath $Settings) {
        $settingsArg = New-QuotedJfrValue -Name 'settings' -Value ([System.IO.Path]::GetFullPath($Settings))
    } else {
        $settingsArg = "settings=$Settings"
    }
    $startOut = & $jcmd $targetPid JFR.start "name=$recName" "duration=${DurationSec}s" $fileArg $settingsArg 2>&1
    Write-Host ("[jfr] {0}" -f (($startOut | Out-String).Trim()))

    $deadline = (Get-Date).AddSeconds($DurationSec + 30)
    $lastSize = -1
    $stableTicks = 0
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if (Test-Path -LiteralPath $OutFile) {
            $size = (Get-Item -LiteralPath $OutFile).Length
            if ($size -gt 0 -and $size -eq $lastSize) {
                $stableTicks++
                if ($stableTicks -ge 2) { break }
            } else {
                $stableTicks = 0
            }
            $lastSize = $size
        }
    }

    if (-not (Test-Path -LiteralPath $OutFile)) {
        # The timed recording should have flushed itself; if it did not, force it.
        Write-Host '[jfr] timed file not found - forcing JFR.dump'
        $dumpOut = & $jcmd $targetPid JFR.dump "name=$recName" $fileArg 2>&1
        Write-Host ("[jfr] {0}" -f (($dumpOut | Out-String).Trim()))
        $null = & $jcmd $targetPid JFR.stop "name=$recName" 2>&1
        Start-Sleep -Seconds 2
    }
    if (-not (Test-Path -LiteralPath $OutFile)) {
        throw "JFR recording was not produced at $OutFile"
    }
}

# ---------------------------------------------------------------------------
# 4) analyze: count SAMPLE BLOCKS (not lines)
#
#    jfr print is written to a temp file and streamed back, rather than captured
#    into an array: a 10-minute profile recording prints ~140 MB, and holding that
#    as a PowerShell string array costs gigabytes of process memory.
# ---------------------------------------------------------------------------
$printTmp = Join-Path ([System.IO.Path]::GetTempPath()) ('perfscope-jfr-{0}.txt' -f ([guid]::NewGuid().ToString('N')))
$printArgs = @('print', '--events', 'jdk.ExecutionSample,jdk.NativeMethodSample', '--stack-depth', $StackDepth, $OutFile)
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $jfr $printArgs 2>$null > $printTmp
$ErrorActionPreference = $prevEap
if (-not (Test-Path -LiteralPath $printTmp) -or (Get-Item -LiteralPath $printTmp).Length -eq 0) {
    if (Test-Path -LiteralPath $printTmp) { Remove-Item -LiteralPath $printTmp -Force }
    throw "jfr print produced no output for $OutFile (is the file complete?)"
}

$totalSamples = 0
$hitSamples = 0
$frameLines = 0
$byThread = @{}
$hitByThread = @{}
$typeTotal = @{}
$typeHit = @{}

$blockOpen = $false
$blockHit = $false
$blockThread = '(unknown)'
$blockType = '(unknown)'

$reader = New-Object System.IO.StreamReader($printTmp)
try {
    while ($null -ne ($ln = $reader.ReadLine())) {
        if (-not $blockOpen) {
            if ($ln -match '^(jdk\.[A-Za-z0-9_]+)\s*\{\s*$') {
                $blockType = $Matches[1]
                $blockOpen = $true
                $blockHit = $false
                $blockThread = '(unknown)'
            }
            continue
        }

        if ($ln -match '^\}\s*$') {
            # end of sample block -> one sample, counted once no matter how many frames matched
            $totalSamples++
            if (-not $typeTotal.ContainsKey($blockType)) { $typeTotal[$blockType] = 0 }
            $typeTotal[$blockType]++
            if (-not $byThread.ContainsKey($blockThread)) { $byThread[$blockThread] = 0 }
            $byThread[$blockThread]++
            if ($blockHit) {
                $hitSamples++
                if (-not $typeHit.ContainsKey($blockType)) { $typeHit[$blockType] = 0 }
                $typeHit[$blockType]++
                if (-not $hitByThread.ContainsKey($blockThread)) { $hitByThread[$blockThread] = 0 }
                $hitByThread[$blockThread]++
            }
            $blockOpen = $false
            continue
        }

        if ($ln -match '^\s*sampledThread\s*=\s*"(.*?)"') { $blockThread = $Matches[1] }
        # Match our own frames, but NOT the third-party libraries we shade into
        # cn.perfscope.shaded.*. bStats in particular opens an HTTPS connection to report
        # anonymous statistics; those frames sit in cn/perfscope/shaded/bstats/** and used to
        # be counted as PerfScope overhead. Measured impact: with every collector disabled the
        # headline still read 6.46% -- that residue was bStats' network stack, not our code.
        # A plugin's own cost should not include the cost of its telemetry library.
        if ($ln -like '*cn.perfscope*' -and $ln -notlike '*cn.perfscope.shaded.*') {
            $blockHit = $true
            $frameLines++   # separate unit: frame LINES, not samples
        }
    }
} finally {
    $reader.Dispose()
    Remove-Item -LiteralPath $printTmp -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------------------
# 5) report
# ---------------------------------------------------------------------------
Write-Report ''
Write-Report '==================== PerfScope JFR overhead ===================='
Write-Report ("run at          : {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
if ($FromFile -ne '') {
    Write-Report 'target JVM      : not contacted (-FromFile replay of an existing recording)'
} else {
    Write-Report ("target JVM      : pid {0} (port {1}), java {2}" -f $targetPid, $Port, $(if ($jvmJavaVersion -ne '') { $jvmJavaVersion } else { '(unknown)' }))
    Write-Report ("target cwd      : {0}" -f $(if ($jvmCwd -ne '') { $jvmCwd } else { '(unknown)' }))
}
Write-Report ("plugin build    : {0} ({1})" -f $pluginVersion, $versionSource)
if ($jarPath -ne '') {
    Write-Report ("plugin jar      : {0}" -f $jarPath)
    Write-Report ("jar sha256[:16] : {0}  ({1} bytes, {2:yyyy-MM-dd HH:mm})" -f $jarSha, $jarSize, $jarTime)
} else {
    Write-Report ("plugin jar      : NOT FOUND under {0}" -f $PluginsDir)
    Write-Report '                  -> the build under test cannot be identified; results are not attributable.'
}
Write-Report ("recording       : {0}" -f $OutFile)
Write-Report ("settings        : {0}" -f $Settings)
if ($Settings -eq 'profile') {
    Write-Report '                  (profile preset: jdk.ExecutionSample 10 ms + jdk.NativeMethodSample 20 ms)'
} elseif ($Settings -eq 'default') {
    Write-Report '                  (default preset: jdk.ExecutionSample 20 ms + jdk.NativeMethodSample 20 ms)'
} else {
    Write-Report '                  (custom .jfc: sample periods are whatever the file sets; native samples'
    Write-Report '                   are only a fair proxy if the two periods are equal)'
}
Write-Report ''
Write-Report '--- sampling share (headline) ---'
Write-Report ("total samples                    : {0}" -f $totalSamples)
if ($totalSamples -gt 0) {
    $share = 100.0 * $hitSamples / $totalSamples
    Write-Report ("samples containing cn.perfscope  : {0}  ({1}%)" -f $hitSamples, (Format-Pct $share 2))
}
Write-Report ("  by event type                  : {0}" -f (($typeTotal.GetEnumerator() | Sort-Object Name | ForEach-Object { "{0}={1}" -f $_.Key, $_.Value }) -join ', '))
Write-Report ("  hits by event type             : {0}" -f $(if ($typeHit.Count -eq 0) { '(none)' } else { (($typeHit.GetEnumerator() | Sort-Object Name | ForEach-Object { "{0}={1}" -f $_.Key, $_.Value }) -join ', ') }))
Write-Report ("frame LINES with cn.perfscope    : {0}   [different unit: lines, NOT samples; a call chain spans several lines, and the count grows with -StackDepth (now {1})]" -f $frameLines, $StackDepth)
Write-Report ''
Write-Report '--- main thread (tick thread) ---'
$mainTotal = 0
$mainHits = 0
if ($byThread.ContainsKey($MainThreadName)) { $mainTotal = $byThread[$MainThreadName] }
if ($hitByThread.ContainsKey($MainThreadName)) { $mainHits = $hitByThread[$MainThreadName] }
Write-Report ("samples / hits                   : {0} / {1}" -f $mainTotal, $mainHits)
if ($mainTotal -gt 0) {
    $mShare = 100.0 * $mainHits / $mainTotal
    $ci = Get-WilsonInterval -Hits $mainHits -Total $mainTotal
    Write-Report ("share                            : {0}%   Wilson 95% CI {1}% .. {2}%" -f (Format-Pct $mShare 2), (Format-Pct (100.0 * $ci.Low) 1), (Format-Pct (100.0 * $ci.High) 1))
    if ($mainTotal -lt $MinServerSamples) {
        Write-Report ("WARNING: only {0} main-thread samples (< -MinServerSamples {1}). The interval above spans" -f $mainTotal, $MinServerSamples)
        Write-Report '         a wide range, so this run CANNOT support any conclusion about main-thread overhead.'
        Write-Report '         Sample a busy server longer (see docs/07-guides/PERF-BASELINE-PLAN.md).'
    }
} else {
    Write-Report ("no samples for thread '{0}' - check -MainThreadName (Folia uses a different tick thread name)" -f $MainThreadName)
}
Write-Report ''
Write-Report '--- thread distribution, all samples (top 10) ---'
$byThread.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 10 | ForEach-Object {
    $pct = if ($totalSamples -gt 0) { Format-Pct (100.0 * $_.Value / $totalSamples) 2 } else { '0' }
    Write-Report ("  {0,6}  {1,6}%  {2}" -f $_.Value, $pct, $_.Key)
}
Write-Report ''
Write-Report '--- threads with cn.perfscope hits ---'
if ($hitByThread.Count -eq 0) {
    Write-Report '  (none)'
} else {
    $hitByThread.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object {
        $pct = if ($totalSamples -gt 0) { Format-Pct (100.0 * $_.Value / $totalSamples) 2 } else { '0' }
        Write-Report ("  {0,6}  {1,6}%  {2}" -f $_.Value, $pct, $_.Key)
    }
}
Write-Report ''
Write-Report 'Note: a sampling share is thread-time composition, NOT CPU%, and NOT a TPS or'
Write-Report '      tick-latency impact. Under settings=profile most samples come from idle'
Write-Report '      threads blocked in native I/O, which inflates the denominator.'
Write-Report '================================================================='
Write-Report ''

if ($SummaryPath -eq '') { $SummaryPath = "$OutFile.summary.txt" }
$summaryDir = Split-Path -Parent $SummaryPath
if ($summaryDir -ne '' -and -not (Test-Path -LiteralPath $summaryDir)) {
    New-Item -ItemType Directory -Force -Path $summaryDir | Out-Null
}
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($SummaryPath, $script:Report.ToArray(), $utf8)
Write-Host ("summary written to {0}" -f $SummaryPath)
Write-Host ("DONE file={0}" -f $OutFile)

if ($totalSamples -le 0) { exit 2 }
exit 0
