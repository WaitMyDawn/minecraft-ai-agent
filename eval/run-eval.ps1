<#
.SYNOPSIS
  MAA golden-set one-shot runner: start app -> wait ready -> run eval -> diff vs previous run -> stop app.

.DESCRIPTION
  Collapses "start server / provide key / run mvnw / compare CSVs" into one command.
  Artifacts:
    - target/eval/<label>-<timestamp>.json  per-case detail (includes <trace>)
    - eval/results/ablation.csv             long-term comparison table (version-controlled, appended each run)

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
    [switch]$NoStart,
    [switch]$KeepServer
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Test-ServerReady {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:8080/api/meta/categories" -TimeoutSec 5 -UseBasicParsing
        return ($r.StatusCode -eq 200)
    } catch {
        return $false
    }
}

function Get-JavaPids {
    return @(Get-Process java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
}

# ---------- 1. load key from .env (never printed) ----------
$apiKey = ""
if (Test-Path (Join-Path $root ".env")) {
    $line = Get-Content (Join-Path $root ".env") -Encoding UTF8 |
        Where-Object { $_ -match '^\s*DEEPSEEK_API_KEY\s*=' } | Select-Object -First 1
    if ($line) { $apiKey = ($line -split '=', 2)[1].Trim().Trim('"').Trim("'") }
}
if ($apiKey) { $env:DEEPSEEK_API_KEY = $apiKey }
if ($apiKey) {
    Write-Host ("[1/4] key loaded from .env (length " + $apiKey.Length + ", not printed)")
} else {
    Write-Host "[1/4] no key found: agent layer will fail (offline layer still works)"
}

# ---------- 2. start server and wait ----------
$pidsBefore = Get-JavaPids
$started = $false
if (-not $NoStart) {
    if (Test-ServerReady) {
        Write-Host "[2/4] port 8080 already serving: pass -NoStart to reuse it, or stop it first (this script never kills processes it did not start)"
        exit 2
    }
    $outLog = Join-Path $root "target/eval-run/app-oneshot.out.log"
    $errLog = Join-Path $root "target/eval-run/app-oneshot.err.log"
    New-Item -ItemType Directory -Force -Path (Join-Path $root "target/eval-run") | Out-Null
    Start-Process -FilePath (Join-Path $root "mvnw.cmd") -ArgumentList "-o", "spring-boot:run" `
        -WorkingDirectory $root -WindowStyle Hidden `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    $started = $true
    Write-Host "[2/4] starting app (log: target/eval-run/app-oneshot.out.log) ..."
    $ready = $false
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Seconds 3
        if (Test-ServerReady) { $ready = $true; Write-Host ("      ready after ~" + (($i + 1) * 3) + "s"); break }
    }
    if (-not $ready) {
        Write-Host "      start failed or timed out; last 20 log lines:"
        Get-Content $outLog -Tail 20 -Encoding UTF8
        exit 3
    }
} else {
    Write-Host "[2/4] -NoStart: reusing the running server"
    if (-not (Test-ServerReady)) { Write-Host "      but port 8080 is not responding; eval would fail"; exit 3 }
}

# ---------- 3. run eval ----------
$mvnArgs = @("-o", "test", "-Dtest=GoldenSetEval", "-Deval=true", "-Deval.layer=$Layer", "-Deval.label=$Label")
if ($Repeat -gt 1) { $mvnArgs += "-Deval.repeat=$Repeat" }
if ($Cases) { $mvnArgs += "-Deval.cases=$Cases" }
Write-Host ("[3/4] running: mvnw " + ($mvnArgs -join " "))
& (Join-Path $root "mvnw.cmd") @mvnArgs
$exitCode = $LASTEXITCODE

# ---------- 4. diff vs previous run ----------
$csv = Join-Path $root "eval/results/ablation.csv"
if (Test-Path $csv) {
    # sort defensively: the table is chronological, but never trust file order for "previous run"
    $rows = @(Import-Csv $csv | Sort-Object timestamp)
    $n = $rows.Count
    if ($n -ge 2) {
        $cur = $rows[$n - 1]
        $prev = $rows[$n - 2]
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
        Write-Host "long-term table: eval/results/ablation.csv (this run appended)"
    }
}

# ---------- cleanup ----------
if ($started -and -not $KeepServer) {
    $new = @(Get-JavaPids | Where-Object { $pidsBefore -notcontains $_ })
    if ($new.Count -gt 0) {
        Stop-Process -Id $new -Force -ErrorAction SilentlyContinue
        Write-Host ("[4/4] stopped server processes started by this run: " + ($new -join ", "))
    }
} elseif ($KeepServer) {
    Write-Host "[4/4] -KeepServer: leaving the server running"
}

exit $exitCode
