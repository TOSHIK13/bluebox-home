# Ворота Blue Box Home: unit-тесты + сборка debug-APK. exit 0 = OK, 1 = сбой, 2 = проблема окружения.
param([string]$Only = '')
$ErrorActionPreference = 'Stop'
try { . "$PSScriptRoot\env.ps1" } catch { Write-Output $_.Exception.Message; exit 2 }
Set-Location (Split-Path -Parent $PSScriptRoot)
$tasks = if ($Only) { @($Only -split ' ') } else { @(':app:testDebugUnitTest', ':app:assembleDebug') }
$sw = [Diagnostics.Stopwatch]::StartNew()
$ErrorActionPreference = 'Continue'
$out = & .\gradlew.bat @tasks --console=plain 2>&1 | ForEach-Object { "$_" }
$rc = $LASTEXITCODE
if ($rc -eq 0) { "VERIFY OK ($([int]$sw.Elapsed.TotalSeconds)s)"; exit 0 }
$out | Select-String -Pattern 'error:|e: |FAILED|What went wrong' -Context 0,2 | Select-Object -First 20 | ForEach-Object { $_.ToString() }
"VERIFY FAIL (exit $rc)"; exit 1

