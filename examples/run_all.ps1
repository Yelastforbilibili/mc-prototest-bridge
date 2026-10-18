# Runs every truth check in this directory and prints one summary.
#
# Usage:
#   pwsh -File run_all.ps1 -Python 'C:\path\to\client\.venv\Scripts\python.exe' -ClientRepo 'C:\path\to\client'
#   pwsh -File run_all.ps1 -SkipBot                       # bridge-only checks
param(
    [string]$Python = $env:MC_PYTHON,
    [string]$ClientRepo = $env:MC_CLIENT_REPO,
    [string]$BridgeUrl = 'http://127.0.0.1:25963',
    [string]$Registry = 'artifacts/registry-blocks-26.3.txt',
    [switch]$SkipBot
)
$ErrorActionPreference = 'Continue'

if (-not $Python) { $Python = 'python' }
$status = Invoke-RestMethod "$BridgeUrl/status" -TimeoutSec 10 -ErrorAction SilentlyContinue
if (-not $status) {
    Write-Host "bridge is not reachable on $BridgeUrl -- start the Paper server with prototest-bridge first" -ForegroundColor Red
    exit 2
}
Write-Host ("bridge ok: {0} | tick {1} | players {2}" -f $status.serverVersion, $status.tick, $status.onlinePlayers) -ForegroundColor Green

$common = @('--bridge', $BridgeUrl)
if ($ClientRepo) { $common += @('--client-repo', $ClientRepo) }

$checks = @(
    @{ name = 'block state palette vs shipped data'; script = 'compare_blocks.py'; args = @() },
    @{ name = 'live client probe';                  script = 'probe_bot.py' },
    @{ name = 'collision shapes vs server';         script = 'probe_shapes.py' }
)
if ($SkipBot) {
    $checks = @($checks[0])
}
# Prefer a saved registry dump (identical content, no 3 MB transfer); fall back to asking the bridge.
$checks[0].args = if (Test-Path $Registry) { @('--registry', $Registry) } else { @('--registry-url', $BridgeUrl) }

$failed = 0
foreach ($c in $checks) {
    Write-Host ''
    Write-Host ("=== {0} ({1}) ===" -f $c.name, $c.script) -ForegroundColor Cyan
    $arguments = @((Join-Path $PSScriptRoot $c.script)) + $common + @($c.args)
    $out = & $Python @arguments 2>&1
    $code = $LASTEXITCODE
    $out | Select-Object -Last 8 | ForEach-Object { Write-Host "  $_" }
    if ($code -ne 0) {
        $failed++
        Write-Host ("  -> exit {0}" -f $code) -ForegroundColor Yellow
    } else {
        Write-Host '  -> ok' -ForegroundColor Green
    }
}
Write-Host ''
if ($failed -eq 0) {
    Write-Host 'ALL CHECKS PASSED' -ForegroundColor Green
} else {
    Write-Host ("{0} check group(s) reported problems" -f $failed) -ForegroundColor Yellow
}
exit $failed
