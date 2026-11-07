<#
PreToolUse-hook (Bash/PowerShell): защита реального ТВ.
Блокирует (exit 2): любое упоминание 192.168.1.151; adb без явного "-s emulator-NNNN" (кроме devices/version/start-server);
tv.ps1/perf-check.ps1 с -Device tv / -AllowRealTv. Реальный ТВ — только командой, которую владелец запускает сам.

ИСКЛЮЧЕНИЯ, разрешённые владельцем в чате (2026-10-01: сначала «разрешаю самому разворачивать версии приложения на ТВ по моему
запросу», затем «запускай тестирование на реальном ТВ»). Только перечисленные формы, одной простой строкой без цепочек, подстановок
и перенаправлений (`; & | ` $ ( ) { } < >` и переводы строк запрещены); всё прочее про ТВ по-прежнему блокируется:
  1) `adb connect 192.168.1.151:5555`;
  2) `powershell [-NoProfile] [-ExecutionPolicy Bypass] -File <путь>tv.ps1 -Device tv -AllowRealTv -Variant beta <подкоманда> [аргументы]`
     — подкоманды: install (без аргументов), start, stop, restart, keys, text, tap, swipe, wait, shot, focus, log, deeplink bluebox://…,
     start-time, adb (tv.ps1 сам пускает на ТВ только `shell getprop|dumpsys`), devices, expect-focus, expect-text, wait-for, expect-alive,
     expect-no-crash; install-home / start-home / stop-home (без аргументов) — лаунчер Blue Box Home (:app-home, debug-APK из
     app-home/build/outputs/apk/debug), 2026-11-07 «Разрешаю хук»: только `install -r` готового APK и явный запуск/остановка
     HomeActivity, по умолчанию лаунчером он не становится. Остальное для реального ТВ запрещает сам tv.ps1 (scenario, reset-app, proxy, emu, прочий adb, не-beta установка);
  3) `powershell … -File <путь>perf-check.ps1 -Device tv -AllowRealTv -Variant beta [-Uri main] [-Runs N] [-Baseline none]
     [-Compile none|speed-profile|speed|verify] [-KeyDelayMs N] [-OpenCards N] [-NoPrime]` — замер плавности (D-pad по главной, gfxinfo, meminfo);
  4) `powershell … -File <путь>tv-deploy.ps1 -AllowRealTv [-NoSync] [-ErrLines N]` — fast-forward до origin/main, beta-сборка, установка
     без запуска (2026-10-17: «сделай скрипт который будет собирать актуальный апк и заливать на тв»).
Использовать только по запросу владельца в чате; пароли и логины агент не вводит.
#>
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
try { $j = [Console]::In.ReadToEnd() | ConvertFrom-Json } catch { exit 0 }
$cmd = [string]$j.tool_input.command
if (-not $cmd) { exit 0 }
function Block([string]$why) { [Console]::Error.WriteLine("BLOCKED (защита реального ТВ): $why. Только эмуляторы emulator-NNNN; на реальном ТВ разрешены лишь формы из шапки scripts/hooks/guard-real-tv.ps1 (tv.ps1/perf-check.ps1 с -Device tv -AllowRealTv -Variant beta и узким списком подкоманд)."); exit 2 }

$tvSubcommands = @('install', 'start', 'stop', 'restart', 'keys', 'text', 'tap', 'swipe', 'wait', 'shot', 'focus', 'log', 'deeplink', 'start-time',
    'adb', 'devices', 'expect-focus', 'expect-text', 'wait-for', 'expect-alive', 'expect-no-crash',
    'install-home', 'start-home', 'stop-home', 'home-tour')

# Разбирает одну простую строку `powershell … -File <script> <аргументы>`; возвращает @{ Script; Args } или $null
function Split-PowerShellCall([string]$c) {
    if ($c -match '[;&|`$(){}<>\r\n]') { return $null }
    $tok = @([regex]::Matches($c.Trim(), '"[^"]*"|\S+') | ForEach-Object { $_.Value })
    if ($tok.Count -lt 3 -or $tok[0] -inotmatch '^powershell(\.exe)?$') { return $null }
    $i = 1
    while ($i -lt $tok.Count -and $tok[$i] -ine '-File') {
        if ($tok[$i] -ieq '-NoProfile') { $i += 1 }
        elseif ($tok[$i] -ieq '-ExecutionPolicy' -and ($i + 1) -lt $tok.Count -and $tok[$i + 1] -ieq 'Bypass') { $i += 2 }
        else { return $null }
    }
    if (($i + 1) -ge $tok.Count) { return $null }
    $rest = @(); for ($k = $i + 2; $k -lt $tok.Count; $k++) { $rest += $tok[$k] }
    return @{ Script = $tok[$i + 1]; Args = $rest }
}

function Test-TvScript($call) {
    if ($call.Script -notmatch '^[\w:./\\-]*tv\.ps1$') { return $false }
    $a = $call.Args
    $device = $false; $allow = $false; $variant = $false; $sub = $null; $subArgs = @()
    $i = 0
    while ($i -lt $a.Count) {
        $t = $a[$i]
        if ($t -ieq '-Device') { if (($i + 1) -ge $a.Count -or $a[$i + 1] -ine 'tv') { return $false }; $device = $true; $i += 2 }
        elseif ($t -ieq '-Variant') { if (($i + 1) -ge $a.Count -or $a[$i + 1] -ine 'beta') { return $false }; $variant = $true; $i += 2 }
        elseif ($t -ieq '-AllowRealTv') { $allow = $true; $i += 1 }
        elseif ($t -ieq '-Delay' -or $t -ieq '-Width' -or $t -ieq '-Lines') { if (($i + 1) -ge $a.Count -or $a[$i + 1] -notmatch '^\d+(\.\d+)?$') { return $false }; $i += 2 }
        elseif ($t -ieq '-Crop') { if (($i + 1) -ge $a.Count -or $a[$i + 1] -notmatch '^\d+,\d+,\d+,\d+$') { return $false }; $i += 2 }
        elseif ($t -ieq '-Pattern') { if (($i + 1) -ge $a.Count) { return $false }; $i += 2 }
        elseif ($t.StartsWith('-') -and $t -notmatch '^-\d') { return $false }
        elseif ($null -eq $sub) { $sub = $t; $i += 1 }
        else { $subArgs += $t; $i += 1 }
    }
    if (-not ($device -and $allow -and $variant) -or $null -eq $sub -or $sub -cnotin $tvSubcommands) { return $false }
    if ($sub -in @('install', 'install-home', 'start-home', 'stop-home', 'home-tour') -and $subArgs.Count -gt 0) { return $false }
    if ($sub -eq 'deeplink' -and ($subArgs.Count -ne 1 -or $subArgs[0].Trim('"') -notmatch '^bluebox://[\w/.-]*$')) { return $false }
    return $true
}

function Test-PerfScript($call) {
    if ($call.Script -notmatch '^[\w:./\\-]*perf-check\.ps1$') { return $false }
    $a = $call.Args
    $device = $false; $allow = $false; $variant = $false
    $i = 0
    while ($i -lt $a.Count) {
        $t = $a[$i]
        $v = if (($i + 1) -lt $a.Count) { $a[$i + 1] } else { $null }
        if ($t -ieq '-Device') { if ($v -ine 'tv') { return $false }; $device = $true; $i += 2 }
        elseif ($t -ieq '-Variant') { if ($v -ine 'beta') { return $false }; $variant = $true; $i += 2 }
        elseif ($t -ieq '-AllowRealTv') { $allow = $true; $i += 1 }
        elseif ($t -ieq '-NoPrime') { $i += 1 }
        elseif ($t -ieq '-Uri') { if ($v -ine 'main') { return $false }; $i += 2 }
        elseif ($t -ieq '-Baseline') { if ($v -ine 'none') { return $false }; $i += 2 }
        elseif ($t -ieq '-Compile') { if ($v -inotmatch '^(none|speed-profile|speed|verify)$') { return $false }; $i += 2 }
        elseif ($t -ieq '-Runs' -or $t -ieq '-KeyDelayMs' -or $t -ieq '-OpenCards') { if ($v -notmatch '^\d{1,3}$') { return $false }; $i += 2 }
        else { return $false }
    }
    return ($device -and $allow -and $variant)
}

function Test-DeployScript($call) {
    if ($call.Script -notmatch '^[\w:./\\-]*tv-deploy\.ps1$') { return $false }
    $a = $call.Args; $allow = $false; $i = 0
    while ($i -lt $a.Count) {
        $t = $a[$i]
        if ($t -ieq '-AllowRealTv') { $allow = $true; $i += 1 }
        elseif ($t -ieq '-NoSync') { $i += 1 }
        elseif ($t -ieq '-ErrLines') { if (($i + 1) -ge $a.Count -or $a[$i + 1] -notmatch '^\d{1,2}$') { return $false }; $i += 2 }
        else { return $false }
    }
    return $allow
}

$tvConnect =$cmd -match '(?i)^\s*(?:[\w:./\\-]*[\\/])?adb(?:\.exe)?\s+connect\s+192\.168\.1\.151:5555\s*$'
$call = Split-PowerShellCall $cmd
$tvAllowed = $tvConnect -or ($null -ne $call -and ((Test-TvScript $call) -or (Test-PerfScript $call) -or (Test-DeployScript $call)))
if ($tvAllowed) { exit 0 }

if ($cmd -match '192\.168\.1\.151') { Block 'команда обращается к адресу реального ТВ' }
if ($cmd -match '(?i)-AllowRealTv|-Device\s+["'']?tv\b') { Block 'tv.ps1/perf-check.ps1 с реальным устройством вне разрешённых форм' }
foreach ($m in [regex]::Matches($cmd, '(?im)(?:^|[;&|({])\s*(?:&\s*)?["'']?(?:[^\s"'';&|]*[\\/])?adb(?:\.exe)?["'']?\s+([^|;&\r\n]*)')) {
    $rest = $m.Groups[1].Value
    if ($rest -match '^(devices|version|start-server|kill-server)\b') { continue }
    if ($rest -notmatch '(^|\s)-s\s+["'']?emulator-\d+') { Block "adb без явного -s emulator-NNNN: adb $rest" }
}
exit 0
