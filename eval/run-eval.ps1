<#
.SYNOPSIS
  MAA golden-set one-shot runner: start app -> wait ready -> run eval -> diff vs previous run -> stop app.

.DESCRIPTION
  Collapses "start server / provide key / run mvnw / compare CSVs" into one command.
  Artifacts:
    - target/eval/<label>-<timestamp>.json  per-case detail (includes <trace>)
    - eval/results/ablation-v3.csv             long-term comparison table (version-controlled, appended each run)

  NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 using the system ANSI codepage
  unless the file has a UTF-8 BOM, so non-ASCII text here would break parsing. Chinese docs live in
  MAA-QA.md instead.

.PARAMETER Label
  Run label written into the comparison table (default: one-click).

.PARAMETER Layer
  offline | agent | all (default: all).

.PARAMETER Repeat
  Repeat count per case for stability stats (default: 1).

.PARAMETER Cases
  Comma-separated case ids to run only, e.g. -Cases A10,A19 (default: all).

.PARAMETER NoStart
  Reuse an already running server (never touches existing processes).

.PARAMETER Set
  Dataset path (default: eval/golden-set.json; incremental: eval/golden-set-v2.json).

.PARAMETER KeepServer
  Do not stop the server after the run.

.EXAMPLE
  ./eval/run-eval.ps1
  ./eval/run-eval.ps1 -Label p4a -Layer agent -Cases A01,A06,A10
  ./eval/run-eval.ps1 -NoStart -Layer offline
#>
param(
    [string]$Label = "one-click",
    [ValidateSet("offline", "agent", "all")]
    [string]$Layer = "all",
    [int]$Repeat = 1,
    [string]$Cases = "",
    [string]$Set = "eval/golden-set.json",
    [switch]$NoStart,
    [switch]$KeepServer
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Test-ServerReady {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/health?strict=true" -TimeoutSec 5 -UseBasicParsing
        return ($r.StatusCode -eq 200)
    } catch {
        return $false
    }
}

$ownedProcesses = @{}
function Remember-OwnedProcesses {
    $rows = @(Get-CimInstance Win32_Process -Property ProcessId,ParentProcessId)
    do {
        $added = $false
        foreach ($row in $rows) {
            $id = [int]$row.ProcessId
            if ($ownedProcesses.ContainsKey([int]$row.ParentProcessId) -and -not $ownedProcesses.ContainsKey($id)) {
                $parentProcess = Get-Process -Id $row.ParentProcessId -ErrorAction SilentlyContinue
                if (-not $parentProcess -or $parentProcess.StartTime -ne $ownedProcesses[[int]$row.ParentProcessId]) { continue }
                $process = Get-Process -Id $id -ErrorAction SilentlyContinue
                if ($process) { $ownedProcesses[$id] = $process.StartTime; $added = $true }
            }
        }
    } while ($added)
}
function Stop-OwnedProcesses {
    Remember-OwnedProcesses
    foreach ($id in @($ownedProcesses.Keys)) {
        $process = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($process -and $process.StartTime -eq $ownedProcesses[$id]) {
            Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
        }
    }
}
# ---------- 1. validate evaluation authentication ----------
if (-not $env:MAA_EVAL_AUTH_TOKEN) {
    Write-Host "[1/4] set MAA_EVAL_AUTH_TOKEN to a logged-in test account token"
    exit 4
}
Write-Host "[1/4] evaluation authentication configured (credentials are not printed)"
# ---------- 2. start server and wait ----------

$started = $false
try {
if (-not $NoStart) {
    if (Test-ServerReady) {
        Write-Host "[2/4] port 8080 already serving: pass -NoStart to reuse it, or stop it first (this script never kills processes it did not start)"
        exit 2
    }
    $outLog = Join-Path $root "target/eval-run/app-oneshot.out.log"
    $errLog = Join-Path $root "target/eval-run/app-oneshot.err.log"
    New-Item -ItemType Directory -Force -Path (Join-Path $root "target/eval-run") | Out-Null
    $appProcess = Start-Process -PassThru -FilePath (Join-Path $root "mvnw.cmd") -ArgumentList "-o", "spring-boot:run" `
        -WorkingDirectory $root -WindowStyle Hidden `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    $ownedProcesses[$appProcess.Id] = $appProcess.StartTime
    $started = $true
    Write-Host "[2/4] starting app (log: target/eval-run/app-oneshot.out.log) ..."
    $ready = $false
    for ($i = 0; $i -lt 40; $i++) {
        Remember-OwnedProcesses
        Start-Sleep -Seconds 3
        if (Test-ServerReady) { $ready = $true; Write-Host ("      ready after ~" + (($i + 1) * 3) + "s"); break }
    }
    if (-not $ready) {
        Write-Host "      start failed or timed out; inspect the startup logs locally"
        exit 3
    }
} else {
    Write-Host "[2/4] -NoStart: reusing the running server"
    if (-not (Test-ServerReady)) { Write-Host "      but port 8080 is not responding; eval would fail"; exit 3 }
}

# ---------- 3. run eval ----------
$mvnArgs = @("-o", "test", "-Dtest=GoldenSetEval", "-Deval=true", "-Deval.layer=$Layer", "-Deval.label=$Label")
$mvnArgs += "-Deval.set=$Set"
if ($Repeat -gt 1) { $mvnArgs += "-Deval.repeat=$Repeat" }
if ($Cases) { $mvnArgs += "-Deval.cases=$Cases" }
Write-Host ("[3/4] running: mvnw " + ($mvnArgs -join " "))
& (Join-Path $root "mvnw.cmd") @mvnArgs
$exitCode = $LASTEXITCODE

# ---------- 4. diff vs previous run ----------
$csv = Join-Path $root "eval/results/ablation-v3.csv"
if (Test-Path $csv) {
    # sort defensively: the table is chronological, but never trust file order for "previous run"
    $rows = @(Import-Csv $csv | Sort-Object timestamp)
    $n = $rows.Count
    if ($n -ge 2) {
        $cur = $rows[$n - 1]
        $prev = $rows[$n - 2]
        $compatible = $cur.setSha256 -eq $prev.setSha256 -and $cur.layer -eq $prev.layer -and $cur.repeat -eq $prev.repeat
        $detailRoot = [IO.Path]::GetFullPath((Join-Path $root "target/eval")) + [IO.Path]::DirectorySeparatorChar
        $caseSelections = @()
        foreach ($entry in @($prev, $cur)) {
            $detail = [IO.Path]::GetFullPath((Join-Path $detailRoot ($entry.label + "-" + $entry.timestamp + ".json")))
            if (-not $detail.StartsWith($detailRoot, [StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $detail)) { $compatible = $false; break }
            $data = Get-Content -LiteralPath $detail -Raw | ConvertFrom-Json
            $caseSelections += (($data.cases.id | Sort-Object) -join "|")
        }
        if ($caseSelections.Count -ne 2 -or $caseSelections[0] -ne $caseSelections[1]) { $compatible = $false }
        if ($compatible) {
        Write-Host ""
        Write-Host ("==== diff vs previous run (previous = " + $prev.label + " / current = " + $cur.label + ") ====")
        $fmt = "{0,-14} {1,-22} {2,-22} {3}"
        Write-Host ($fmt -f "metric", ("previous(" + $prev.label + ")"), ("current(" + $cur.label + ")"), "delta")
        foreach ($col in @("cases", "passRate", "adversarialRate", "avgMs", "p50Ms", "p95Ms", "inTokens", "outTokens", "modelCalls", "toolCalls")) {
            $a = $prev.$col
            $b = $cur.$col
            $delta = ""
            if ($a -and $b -and $a -match '^-?\d+(\.\d+)?$' -and $b -match '^-?\d+(\.\d+)?$') {
                $d = [double]$b - [double]$a
                if ($d -ne 0) { $delta = $(if ($d -gt 0) { "+" } else { "" }) + [math]::Round($d, 1) }
            }
            Write-Host ($fmt -f $col, $a, $b, $delta)
        }
        Write-Host ""
        Write-Host ("per-case detail: target/eval/" + $cur.label + "-<timestamp>.json")
        Write-Host "long-term table: eval/results/ablation-v3.csv (this run appended)"
        } else { Write-Host "Skipped comparison: dataset, selection, layer or repeat count differs (or detail is unavailable)." }
    }
}

# ---------- cleanup ----------
} finally {
if ($started -and -not $KeepServer) {
    Stop-OwnedProcesses
    Write-Host "[4/4] stopped the process tree started by this runner"
} elseif ($KeepServer) {
    Write-Host "[4/4] -KeepServer: leaving the server running"
}
}

exit $exitCode
