<#
Network adb device (host:port, e.g. the real TV): adb does not reconnect a dropped TCP device by itself, so after the box
slept, rebooted or lost Wi-Fi it stays `offline` or vanishes from `adb devices` until someone runs `adb connect`.
Connect-NetDevice brings it back before a command. Dot-sourced by tv.ps1 and perf-check.ps1.

  $err = Connect-NetDevice $adbExe '192.168.1.151:5555'   # $null = ready ('device'); otherwise a Russian reason
Prints `reconnect: <serial> ...` (Write-Host) when it had to reconnect. Non host:port serials are returned as ready untouched.
#>

function Get-NetDeviceState([string]$adbExe, [string]$serial) {
    $o = (& $adbExe -s $serial get-state 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -eq 0) { return $o }                         # device | offline | bootloader ...
    if ($o -match 'unauthorized') { return 'unauthorized' }
    if ($o -match 'offline') { return 'offline' }
    'missing'
}

function Test-TcpPort([string]$hostName, [int]$port, [int]$timeoutMs = 2000) {
    $c = New-Object System.Net.Sockets.TcpClient
    try { $ar = $c.BeginConnect($hostName, $port, $null, $null); $ar.AsyncWaitHandle.WaitOne($timeoutMs) -and $c.Connected }
    catch { $false }
    finally { $c.Close() }
}

function Connect-NetDevice([string]$adbExe, [string]$serial, [int]$tries = 3) {
    if ($serial -notmatch '^(?<h>[^:\s]+):(?<p>\d+)$') { return $null }   # emulator-NNNN / USB serial: nothing to connect
    $h = $Matches['h']; $p = [int]$Matches['p']
    $unauth = "$serial не авторизован: подтвердите запрос отладки по сети на экране ТВ"
    $state = Get-NetDeviceState $adbExe $serial
    if ($state -eq 'device') { return $null }
    if ($state -eq 'unauthorized') { return $unauth }
    Write-Host "reconnect: $serial is '$state', running adb connect"
    $o = ''
    for ($i = 1; $i -le $tries; $i++) {
        if (-not (Test-TcpPort $h $p)) {
            if ($i -lt $tries) { Start-Sleep -Seconds 2; continue }
            return "$serial недоступен по сети (порт $p не отвечает): ТВ спит или выключен, занят/сменился IP или выключена отладка по сети"
        }
        & $adbExe disconnect $serial 2>&1 | Out-Null                # drop a stale 'offline' transport first
        $o = (& $adbExe connect $serial 2>&1 | Out-String).Trim()    # exit code is 0 even on failure: check the state
        for ($w = 0; $w -lt 5; $w++) {
            $state = Get-NetDeviceState $adbExe $serial
            if ($state -eq 'device') { Write-Host "reconnect: $serial ok"; return $null }
            if ($state -eq 'unauthorized') { return $unauth }
            Start-Sleep -Seconds 1
        }
        if ($i -lt $tries) { Start-Sleep -Seconds 2 }
    }
    "$serial не подключился ($state; adb connect: $o)"
}
