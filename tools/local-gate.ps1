# local-gate.ps1 -- the L1/L2 gate runner (no server needed, ~1-3 min).
#
# Why one entry point: the existing release-check.ps1 answers "is the jar's third-party layout
# right" very well, but it does not answer "does the code behave", "can the assertions fail",
# "is the archive the same code", "does an old config still load". Those are four different
# tools. Nobody runs four tools. This runs them in the order that fails fastest.
#
# Tiers:
#   -L1   (default) compile + i18n + scenarios + config compat. Seconds to a minute.
#                   Run before every commit; suitable for a pre-commit hook.
#   -L2            L1 + mvn test + release-check.ps1 (jar fingerprint).
#                   Run before every release.
#   -L2 -WithServer  L2 + testharness scenarios (needs the local Paper test server, minutes).
#   -L3            L2 + full scenario set + soak + compat matrix reminder.
#                   Minor-or-above releases.
#   -Artifact <ver>  add the archive-vs-source comparison for a version.
#
# Exit code: 0 = every step passed, 1 = at least one step failed.
#
# Usage (PowerShell 5.1 compatible; ASCII-only on purpose):
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\local-gate.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\local-gate.ps1 -L2 -Artifact 1.3.0
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\local-gate.ps1 -L1 -SkipBuild

[CmdletBinding()]
param(
    [switch]$L1,
    [switch]$L2,
    [switch]$L3,
    [switch]$WithServer,
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [string]$Artifact = '',
    [string]$RepoRoot = '',
    [string]$Harness = ''
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$script:Steps = @()
$script:MvnCmd = 'D:\maven\apache-maven-3.9.9\bin\mvn.cmd'
$script:DefaultJavaHome = 'D:\Java\zulu21.48.17-ca-jdk21.0.10-win_x64'

# Path defaults. The script used to live in the PARENT workspace, so "one level up, then
# PerfScope" resolved to this repo. It now lives INSIDE the repo at tools/, so one level up
# IS the repo -- appending 'PerfScope' again lands on <repo>/PerfScope and every step fails
# with "pom.xml not found" (same bug that hit verify-release-artifact.mjs; observed 2026-11-09).
#
# Layout:  <Server plugin>/  ─┬─ PerfScope/PerfScope/  (this repo)
#                             └─ testharness/
# From tools/ inside the repo, the harness needs THREE levels up, not two.
if ($RepoRoot -eq '') { $RepoRoot = Split-Path $PSScriptRoot -Parent }
if ($Harness -eq '') { $Harness = Join-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent) 'testharness' }

# tier selection: -L3 implies L2 implies L1
$tier = 1
if ($L2 -or $L3) { $tier = 2 }
if ($L3) { $tier = 3 }

function Write-Step {
    param([string]$Message, [string]$Level = 'INFO')
    $line = "[{0}][{1}] {2}" -f (Get-Date -Format 'HH:mm:ss'), $Level, $Message
    switch ($Level) {
        'FAIL' { Write-Host $line -ForegroundColor Red }
        'WARN' { Write-Host $line -ForegroundColor Yellow }
        'OK'   { Write-Host $line -ForegroundColor Green }
        'HEAD' { Write-Host $line -ForegroundColor Cyan }
        default { Write-Host $line }
    }
}

function Invoke-Step {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [switch]$Advisory
    )
    Write-Host ''
    Write-Step ("--- {0}" -f $Name) 'HEAD'
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $code = 0
    try {
        & $Action
        $code = $LASTEXITCODE
        if ($null -eq $code) { $code = 0 }
    } catch {
        Write-Step ("{0}: {1}" -f $Name, $_.Exception.Message) 'FAIL'
        $code = 1
    }
    $sw.Stop()
    $passed = ($code -eq 0) -or $Advisory
    $script:Steps += [pscustomobject]@{
        Name = $Name; Passed = $passed; Code = $code
        Seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        Advisory = [bool]$Advisory
    }
    if ($code -eq 0) { Write-Step ("{0} -- OK ({1}s)" -f $Name, [math]::Round($sw.Elapsed.TotalSeconds, 1)) 'OK' }
    elseif ($Advisory) { Write-Step ("{0} -- findings ({1}s, advisory: does not fail the gate)" -f $Name, [math]::Round($sw.Elapsed.TotalSeconds, 1)) 'WARN' }
    else { Write-Step ("{0} -- FAILED (exit {1}, {2}s)" -f $Name, $code, [math]::Round($sw.Elapsed.TotalSeconds, 1)) 'FAIL' }
}

function Resolve-Node {
    $n = Get-Command node -ErrorAction SilentlyContinue
    if ($null -eq $n) { return $null }
    return $n.Source
}

# --- toolchain ------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $script:MvnCmd)) {
    Write-Step ("Maven not found at {0}" -f $script:MvnCmd) 'FAIL'
    exit 1
}
$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrEmpty($javaHome) -or -not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
    if (Test-Path -LiteralPath (Join-Path $script:DefaultJavaHome 'bin\java.exe')) {
        $env:JAVA_HOME = $script:DefaultJavaHome
        Write-Step ("JAVA_HOME -> {0}" -f $script:DefaultJavaHome) 'WARN'
    }
}
$node = Resolve-Node
if ($null -eq $node) { Write-Step 'node not found: static gates will be skipped (install Node 18+)' 'WARN' }

Write-Host ''
Write-Host ('=' * 74)
Write-Host ("PerfScope local gate -- tier L{0}" -f $tier) -ForegroundColor Cyan
Write-Host ("  repo    : {0}" -f $RepoRoot)
Write-Host ("  harness : {0}" -f $Harness)
Write-Host ("  node    : {0}" -f $(if ($node) { $node } else { '(missing)' }))
Write-Host ('=' * 74)

# ======================================================================================
# L1
# ======================================================================================
if (-not $SkipBuild) {
    Invoke-Step 'L1 build (mvn clean package)' {
        Push-Location -LiteralPath $RepoRoot
        try { & $script:MvnCmd '-B' '-ntp' '-q' 'clean' 'package' '-DskipTests' } finally { Pop-Location }
    }
} else {
    Write-Step 'L1 build skipped (-SkipBuild)' 'WARN'
}

if ($null -ne $node) {
    Invoke-Step 'L1 i18n key + placeholder parity' {
        & $node (Join-Path $PSScriptRoot 'check-i18n.mjs') '--repo' $RepoRoot
    }
    Invoke-Step 'L1 scenario assertion validity' {
        & $node (Join-Path $PSScriptRoot 'check-scenarios.mjs') '--repo' $RepoRoot '--harness' $Harness
    }
    # Negative assertions (`notPresent` / `expectNot`) pass both when the guarded failure never
    # happens AND when the pattern can never match. This step feeds each one a sample of what
    # the failure WOULD emit and confirms the matcher fires -- the only way to tell those apart.
    Invoke-Step 'L1 negative-assertion controls' {
        & $node (Join-Path $PSScriptRoot 'negative-controls.mjs')
    }
    Invoke-Step 'L1 config backward compatibility' {
        & $node (Join-Path $PSScriptRoot 'check-config-compat.mjs') '--repo' $RepoRoot
    }
} else {
    Write-Step 'static gates need node -- skipped' 'WARN'
}

# ======================================================================================
# L2
# ======================================================================================
if ($tier -ge 2) {
    if (-not $SkipTests) {
        Invoke-Step 'L2 unit tests (mvn test)' {
            Push-Location -LiteralPath $RepoRoot
            try { & $script:MvnCmd '-B' '-ntp' 'test' } finally { Pop-Location }
        }
    } else {
        Write-Step 'L2 unit tests skipped (-SkipTests)' 'WARN'
    }

    $releaseCheck = Join-Path $PSScriptRoot 'release-check.ps1'
    if (Test-Path -LiteralPath $releaseCheck) {
        Invoke-Step 'L2 jar fingerprint (release-check.ps1)' {
            if ($WithServer) {
                & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $releaseCheck -RepoRoot $RepoRoot -SkipBuild -WithServer
            } else {
                & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $releaseCheck -RepoRoot $RepoRoot -SkipBuild
            }
        }
    } else {
        Write-Step ("release-check.ps1 not found at {0}" -f $releaseCheck) 'WARN'
    }
}

# ======================================================================================
# artifact comparison (any tier, opt-in)
# ======================================================================================
if ($Artifact -ne '') {
    if ($null -eq $node) {
        Write-Step 'artifact check needs node -- skipped' 'WARN'
    } else {
        Invoke-Step ("ARTIFACT archive {0} vs source" -f $Artifact) {
            & $node (Join-Path $PSScriptRoot 'verify-release-artifact.mjs') '--repo' $RepoRoot '--version' $Artifact '--skip-fresh-build'
        }
    }
}

# ======================================================================================
# L3
# ======================================================================================
if ($tier -ge 3) {
    Write-Step ''
    Write-Step 'L3 extra requirements (not automated here; each is a manual gate):' 'HEAD'
    Write-Host '  [ ] full scenario regression: all 16 perfscope-* scenarios, both languages'
    Write-Host '      cd <testharness>; powershell -File scripts\run-all.ps1 -Server perfscope'
    Write-Host '  [ ] soak 30 minutes (perfscope-soak), no unhandled exception, alert rate < 5/min'
    Write-Host '  [ ] compatibility matrix: Paper 1.20.4 + Paper 1.21.x + one fork (Purpur/Folia/Leaves)'
    Write-Host '  [ ] upgrade from the previous release: old plugins/ + old config.yml + old history/'
    Write-Host '  [ ] main-thread collection cost < 50ms per cycle (/perfscope status shows collectDurationMs)'
    Write-Host ''
}

# ======================================================================================
# summary
# ======================================================================================
Write-Host ''
Write-Host ('=' * 74)
foreach ($s in $script:Steps) {
    $tag = $(if ($s.Passed) { 'PASS' } else { 'FAIL' })
    $color = $(if ($s.Passed) { 'Green' } else { 'Red' })
    Write-Host ("[{0}] {1}  ({2}s){3}" -f $tag, $s.Name, $s.Seconds, $(if ($s.Advisory) { ' [advisory]' } else { '' })) -ForegroundColor $color
}
$failed = @($script:Steps | Where-Object { -not $_.Passed }).Count
$total = $script:Steps.Count
Write-Host ('=' * 74)
if ($failed -eq 0) {
    Write-Host ("LOCAL GATE (L{0}): PASS ({1}/{2})" -f $tier, $total, $total) -ForegroundColor Green
    exit 0
} else {
    Write-Host ("LOCAL GATE (L{0}): FAIL ({1}/{2} failed)" -f $tier, $failed, $total) -ForegroundColor Red
    exit 1
}
