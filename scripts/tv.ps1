<#
Compact helper for building and driving the TV app. Output is deliberately short (token-cheap).

  powershell -File scripts/tv.ps1 <command> [args] [-Device auto|emu|tv|<serial>] [-Variant debug|beta] [-AllowRealTv] [-WaitLease <sec>]

Emulator lease (parallel sessions): every emulator command first takes a lease on its emulator for THIS worktree
(file in %LOCALAPPDATA%\BlueBox\emu-leases, renewed by each call / scenario step, expires after BLUEBOX_LEASE_TTL_MIN=30 min idle).
A live lease of another worktree -> `BUSY: ...`, exit 3, nothing is sent to the emulator. -Device auto (default) picks the
emulator from the pool BLUEBOX_EMU_POOL (default emulator-5554,emulator-5556): own lease > free running > (emu only) free stopped.
-WaitLease <sec> (or env BLUEBOX_LEASE_WAIT) waits in a queue instead of BUSY. A new lease prints `lease: <serial> ...`
(another emulator = install the APK there). Exit codes: 0 ok, 1 fail, 2 environment problem, 3 BUSY.

Commands:
  devices                        adb devices -l
  leases                         pool status: running/stopped, free/busy, whose lease
  acquire                        take/renew the lease, print `device: <serial>` (for other scripts)
  release [-Kill]                drop own lease(s) (-Device auto: all own); -Kill also shuts that emulator down
  emu                            start the emulator if it does not run, wait for boot (timeout 240 s); emulator-5554 = AVD
                                 Ani_TV_API_36, any other port = own AVD Bb_TV_2 (-port N -read-only, temp data);
                                 passes -http-proxy when env BLUEBOX_EMU_PROXY (host:port) is set
  build                          :app-tv:assemble<Variant>, prints only errors + result
  install [apk]                  adb install -r (default: latest app-tv APK of -Variant)
  start | stop | restart         launch / force-stop the app (start/restart/deeplink remember the time for expect-no-crash)
  reset-app                      pm clear ONLY our own package (app.bluebox.tv.debug | .beta by -Variant); emulator only
  start-time [maxMs]             force-stop + cold `am start -W` of the launcher activity -> prints TotalTime; with maxMs: PASS/FAIL
  keys "DOWN DOWN CENTER" [-Delay 0.5]   D-pad keys (UP DOWN LEFT RIGHT CENTER BACK HOME MENU PLAY or KEYCODE_*; "DOWN*3" repeats)
  tap <x> <y> | tap-text <regex> touch tap at pixel coordinates / on the centre of the first UI node whose text or
                                 content-desc matches the regex (uiautomator bounds); sets the pointer, not the D-pad focus
  swipe <x1> <y1> <x2> <y2> [ms] touch swipe (default 300 ms)
  text "query"                   `input text` into the focused field. ASCII ONLY (adb limitation): for Cyrillic use
                                 deeplink "bluebox://search?q=<query>"
  shot <name> [-Width 960] [-Crop x,y,w,h]   screenshot, downscaled, prints the PNG path
  focus                          focused node as text (class, id, text, bounds) via uiautomator - prefer over shot
  log [-Lines 60] [-Pattern re]  app logcat (errors/exceptions by default)
  wait <sec>                     sleep
  deeplink <uri>                 am start -W -a VIEW -d <uri> <pkg>
  proxy on|off                   emulator HTTP proxy: `settings put global http_proxy 10.0.2.2:<port>` (port from env
                                 BLUEBOX_EMU_PROXY=host:port, else from HTTPS_PROXY/HTTP_PROXY if it points to 127.0.0.1) / `:0`
  adb "<args>"                   passthrough to adb -s <serial> (install/uninstall/pm clear/-s are refused);
                                 on a non-emulator serial only `shell getprop ...` and `shell dumpsys ...`
  scenario <file> [-DryRun]      run a script (-DryRun: only parse and print); one command per line, # comments, "quoted args";
                                 a line prefixed with `always ` runs even after a failure (cleanup); ends with SCENARIO PASS|FAIL, exit 0|1.
                                 After FAIL: `FAIL-CLASS: CRASH|TOOL|TIMEOUT|ASSERT` + a hint, `KNOWN-FLAKY ...` when scripts/scenarios/known-flaky.json
                                 lists it, `REPEAT xN` when the same TIMEOUT/TOOL failure comes again (= stop retrying). PASS writes build/receipts/scenario-<name>.json.
  scenarios <name|@pack>... [-Skip '<names/packs>']   a batch on one lease/install; packs from scripts/scenarios/packs.json plus
                                 '@all' (minus _manual), '@changed' (by the diff against origin/main, map _paths), '@failed' (last batch);
                                 a failure with a network error on screen = class NET, two NET in a row stop the batch (rest NOTRUN)

Assertions (print PASS:/FAIL:, FAIL stops a scenario (only `always` lines still run); standalone -> exit 1):
  expect-focus <regex>           focused node text/desc/inner texts match
  expect-text <regex>            any UI node text/content-desc matches
  expect-no-text <regex>         NO UI node text/content-desc matches (a filtered-out title, a hidden chip)
  wait-for <regex> [sec=10]      poll the UI (back-to-back dumps, 0.25 s pause) until a node text matches
  expect-alive                   app process is running
  expect-no-crash                process alive and no FATAL EXCEPTION for it since last start/restart/deeplink
  expect-playing [sec=15]        dumpsys media_session: our package in PLAYING (state=3)
  expect-query <uri> <regex> [sec=5]  adb shell content query --uri <uri>; FAIL if no `Row:` lines or output starts with an adb/content
                                 error; regex is .NET on the FULL output ((?s) and backreferences \1 work); [sec] > 0 polls
                                 every 0.5 s until it matches (e.g. a state that settles after buffering)
  expect-prop <prop> <regex>     getprop <prop> matches (preconditions, e.g. expect-prop persist.sys.locale "^en")

UI probes (debug build only; keys = ProbeKeys in ui/components/Probe.kt, table content://app.bluebox.tv.debug.state/ui):
  (the table is read from the app's files/ui-probe.txt via run-as, ~0.05 s, polled every 0.1 s; content query as fallback;
   BLUEBOX_NO_PROBE_FILE=1 = content query only)
  wait-ready [sec=30]            poll the `ui` table until ready=true (home finished its first load) - instead of waiting for a row title
  expect-focus-key <key> [sec=2] the focused element carries probe <key> (`ui` focus=<key>), e.g. expect-focus-key action:score
  tap-id <key>                   tap the centre of the node whose resource-id (= probe test tag) is <key>, e.g. tap-id card:tmdb:movie:603
  expect-ui <field>=<regex> [sec=2]  a field of the `ui` row matches the regex as a WHOLE value (route, rail, focus, sheet,
                                 overlay, ready, rows, screen, items), e.g. expect-ui sheet=sheet:score, expect-ui screen=detail:ready
  wait-ui <field>=<regex> [sec=15]   the same with a longer wait - instead of wait-for "<title>" while a screen loads

Safety: default device auto (emulator pool), `emu` = emulator-5554. Any non-emulator serial (-Device tv = $env:BLUEBOX_TV, host:port of the real box) needs -AllowRealTv,
install there is allowed only for -Variant beta, and scenario / reset-app / proxy / emu / adb other than getprop|dumpsys are refused.
Every adb call carries an explicit -s <serial>. pm clear is only ever run for our own package.
Package: app.bluebox.tv.debug (default) | app.bluebox.tv.beta. Screenshots go to -Out (default %TEMP%\bluebox-shots\<worktree folder name>).
#>
param(
    [Parameter(Position = 0)][string]$Command = 'help',
    [Parameter(Position = 1)][string]$Arg,
    [Parameter(Position = 2)][string]$Arg2,
    [Parameter(Position = 3)][string]$Arg3,
    [Parameter(ValueFromRemainingArguments)][string[]]$More,   # swipe <x1> <y1> <x2> <y2> [ms] has more than three arguments
    [string]$Device = $(if ($env:BLUEBOX_TV_DEVICE) { $env:BLUEBOX_TV_DEVICE } else { 'auto' }),
    [ValidateSet('debug', 'beta')][string]$Variant = $(if ($env:BLUEBOX_TV_VARIANT) { $env:BLUEBOX_TV_VARIANT } else { 'debug' }),
    [switch]$AllowRealTv,
    [switch]$DryRun,
    [switch]$ForceInstall,
    [switch]$NoBuild,
    [double]$Delay = 0.5,
    [int]$Width = 960,
    [string]$Crop,
    [int]$Lines = 60,
    [string]$Pattern = '^[EF]/|FATAL|Exception',
    [string]$Out,
    [int]$WaitLease = $(if ($env:BLUEBOX_LEASE_WAIT) { [int]$env:BLUEBOX_LEASE_WAIT } else { 0 }),
    [switch]$Kill,
    [string]$Skip   # scenarios: names/packs to leave out, e.g. -Skip '@d4,m4-collections'
)
$ErrorActionPreference = 'Continue'
$enc = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $enc
$OutputEncoding = $enc
try { . (Join-Path $PSScriptRoot 'env.ps1') }
catch { [Console]::Error.WriteLine($_.Exception.Message); exit 2 }

$repo = $bbRoot
# One folder per worktree: parallel sessions never read each other's fail.png ($PSScriptRoot is empty in a param default on PS 5.1).
if (-not $Out) { $Out = Join-Path (Join-Path $env:TEMP 'bluebox-shots') (Split-Path -Leaf $bbRoot) }
$appId = 'app.bluebox.home'
$suffix = if ($Variant -eq 'beta') { '.beta' } else { '.debug' }
$pkg = "$appId$suffix"
$activity = "$pkg/$appId.HomeActivity"
$serial = switch ($Device) { 'emu' { 'emulator-5554' } 'tv' { if (-not $env:BLUEBOX_TV) { throw 'BLUEBOX_TV (host:port of the TV) is not set: put it into $HOME\.bluebox-home.ps1' }; $env:BLUEBOX_TV } default { $Device } }
$isEmulator = $serial -match '^(emulator-\d+|auto)$'   # 'auto' is resolved to a leased emulator before the command runs
$adbExe = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
function Get-StateFile { Join-Path $env:TEMP ("bluebox-tv-start-" + ($serial -replace '[^\w\.-]', '_') + ".txt") }
$stateFile = Get-StateFile

# ---- safety ---------------------------------------------------------------------------------
if (-not $isEmulator -and $Command -notin @('devices', 'help')) {
    if (-not $AllowRealTv) {
        Write-Output "REFUSED: '$serial' is not an emulator. Real devices need -AllowRealTv (install: beta only)."
        exit 1
    }
    if ($Command -in @('emu', 'scenario', 'reset-app', 'proxy')) { Write-Output "REFUSED: '$Command' works with emulators only."; exit 1 }
    if ($Command -eq 'adb' -and (@($Arg, $Arg2, $Arg3) -join ' ').Trim() -notmatch '^shell\s+(getprop|dumpsys)(\s|$)') {
        Write-Output "REFUSED: on a non-emulator serial adb allows only 'shell getprop ...' and 'shell dumpsys ...'."; exit 1
    }
    # a network device (the TV) drops out of adb after sleep / reboot / Wi-Fi loss and adb never reconnects it itself
    if ($Command -notin @('build', 'wait') -and -not $DryRun) {
        . (Join-Path $PSScriptRoot 'adb-net.ps1')
        $netErr = Connect-NetDevice $adbExe $serial
        if ($netErr) { Write-Output "ПРОБЛЕМА ОКРУЖЕНИЯ: $netErr"; exit 2 }
    }
}

function Adb { & $adbExe -s $serial @args 2>&1 }
function AdbQuiet { $o = & $adbExe -s $serial @args 2>&1; if ($LASTEXITCODE -ne 0) { throw ($o | Out-String).Trim() } }
function AdbShell([string]$cmdline) { & $adbExe -s $serial shell $cmdline 2>&1 | ForEach-Object { "$_" } }
function ShQuote([string]$s) { "'" + ($s -replace "'", "'\''") + "'" }

class AssertFail : System.Exception { AssertFail([string]$m) : base($m) {} }
function Fail([string]$msg) { throw [AssertFail]::new($msg) }
function Pass([string]$msg) { Write-Output "PASS: $msg" }

# ---- keys / screenshot ----------------------------------------------------------------------
# One adb call per `keys` line: `input keyevent A; sleep <Delay>; input keyevent B` runs on the device (the per-key adb round
# trip is gone, the pause between keys is the same). The pause after the LAST key is lazy ($script:SettleUntil): commands that
# settle by themselves (a uiautomator dump waits for 1 s of UI idle; deeplink/restart start the activity anew) skip it, every
# other command (the next keys, a probe query, wait, shot...) first sleeps out the rest (Wait-Settle in Invoke-One).
$script:SettleUntil = [datetime]::MinValue; $script:InputSettleUntil = [datetime]::MinValue
function Wait-Settle {
    $ms = ($script:SettleUntil - (Get-Date)).TotalMilliseconds
    if ($ms -gt 0) { Start-Sleep -Milliseconds ([int]$ms); $script:Tm.keys += $ms / 1000; $script:Settled += $ms / 1000 }   # counted as keys time
    $script:SettleUntil = [datetime]::MinValue
}
function Send-Keys([string]$spec) {
    $cmds = @()
    foreach ($tok in ($spec -split '\s+' | Where-Object { $_ })) {
        $n = 1
        if ($tok -match '^(.+)\*(\d+)$') { $tok = $Matches[1]; $n = [int]$Matches[2] }
        $code = switch ($tok.ToUpper()) {
            'UP' { 'KEYCODE_DPAD_UP' } 'DOWN' { 'KEYCODE_DPAD_DOWN' } 'LEFT' { 'KEYCODE_DPAD_LEFT' }
            'RIGHT' { 'KEYCODE_DPAD_RIGHT' } 'CENTER' { 'KEYCODE_DPAD_CENTER' } 'OK' { 'KEYCODE_DPAD_CENTER' }
            'BACK' { 'KEYCODE_BACK' } 'HOME' { 'KEYCODE_HOME' } 'MENU' { 'KEYCODE_MENU' }
            'PLAY' { 'KEYCODE_MEDIA_PLAY_PAUSE' } default { if ($tok -like 'KEYCODE_*') { $tok } else { "KEYCODE_$($tok.ToUpper())" } }
        }
        if ($code -notmatch '^KEYCODE_\w+$') { throw "keys: bad key '$tok'" }
        for ($i = 0; $i -lt $n; $i++) {
            $cmds += $(if ($tok.ToUpper() -in @('LONGCENTER', 'LONGOK')) { 'input keyevent --longpress KEYCODE_DPAD_CENTER' } else { "input keyevent $code" })
        }
    }
    if (-not $cmds.Count) { return }
    Wait-Settle
    $pause = '; sleep ' + $Delay.ToString([Globalization.CultureInfo]::InvariantCulture) + '; '
    # `&&`-free on purpose: a key the app ignores is not an error; adb's exit code is the last `input`'s
    AdbQuiet shell ($cmds -join $pause)
    $script:SettleUntil = (Get-Date).AddMilliseconds($Delay * 1000)
}

function Send-Tap([int]$x, [int]$y) { AdbQuiet shell input tap $x $y }

# Centre of the smallest node whose resource-id is exactly $id (debug builds export Compose test tags = probe keys as resource-id).
function Find-NodeById([string]$id) {
    $best = $null; $bestArea = [long]::MaxValue
    foreach ($n in (Get-UiDoc).SelectNodes('//node')) {
        if ("$($n.'resource-id')" -ne $id -or $n.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { continue }
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        $area = [long]($x2 - $x1) * ($y2 - $y1)
        if ($area -gt 0 -and $area -lt $bestArea) { $bestArea = $area; $best = @([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2)) }
    }
    $best
}

# Centre of the first node whose text / content-desc matches the regex (smallest node wins: the label, not its container).
function Find-NodeCenter([string]$re) {
    $best = $null; $bestArea = [long]::MaxValue
    foreach ($n in (Get-UiDoc).SelectNodes('//node')) {
        if ((("$($n.text)" -notmatch $re) -and (("$($n.'content-desc')") -notmatch $re)) -or (-not "$($n.text)$($n.'content-desc')")) { continue }
        if ($n.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { continue }
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        $area = [long]($x2 - $x1) * ($y2 - $y1)
        if ($area -gt 0 -and $area -lt $bestArea) { $bestArea = $area; $best = @([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2)) }
    }
    $best
}

function Take-Shot([string]$name) {
    if (-not $name) { $name = 'shot' }
    New-Item -ItemType Directory -Force $Out | Out-Null
    $raw = Join-Path $Out "$name.raw.png"; $dst = Join-Path $Out "$name.png"
    AdbQuiet shell screencap -p /sdcard/bluebox_shot.png
    AdbQuiet pull /sdcard/bluebox_shot.png $raw
    Add-Type -AssemblyName System.Drawing
    $img = [System.Drawing.Image]::FromFile($raw)
    try {
        $src = New-Object System.Drawing.Rectangle 0, 0, $img.Width, $img.Height
        if ($Crop) { $c = $Crop -split ',' | ForEach-Object { [int]$_ }; $src = New-Object System.Drawing.Rectangle $c[0], $c[1], $c[2], $c[3] }
        $w = [Math]::Min($Width, $src.Width); $h = [int]($src.Height * $w / $src.Width)
        $bmp = New-Object System.Drawing.Bitmap $w, $h
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = 'HighQualityBicubic'
        $g.DrawImage($img, (New-Object System.Drawing.Rectangle 0, 0, $w, $h), $src, 'Pixel')
        $g.Dispose(); $bmp.Save($dst, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
    } finally { $img.Dispose() }
    Remove-Item $raw
    "shot: $dst"
}

# ---- UI dump --------------------------------------------------------------------------------
# Scenario timing (printed as TIMING: and stored in the receipt): where the seconds go.
$script:Tm = @{ dump = 0.0; dumps = 0; hits = 0; query = 0.0; queries = 0; fileQueries = 0; fileQuery = 0.0; tree = 0.0; trees = 0; treeMiss = 0; rescue = 0; wait = 0.0; start = 0.0; keys = 0.0; other = 0.0 }

# Dump cache (scenario only): consecutive expect-text / expect-no-text / expect-focus / tap-text without a key press, tap, deeplink,
# wait or restart in between read ONE dump (a uiautomator dump costs ~2 s). Any command that can change the screen drops the cache;
# wait-for never reads it (it polls); a cached dump older than 3 s is not used. BLUEBOX_NO_DUMP_CACHE=1 turns it off.
$script:UiCache = $null; $script:UiCacheAt = [datetime]::MinValue; $script:UiCacheOn = $false

function Get-UiDoc([switch]$Fresh) {
    if (-not $Fresh -and $script:UiCacheOn -and $script:UiCache -and ((Get-Date) - $script:UiCacheAt).TotalSeconds -lt 3) { $script:Tm.hits++; return $script:UiCache }
    $script:UiCache = $null
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $xml = Join-Path $env:TEMP ('bluebox_ui-' + ($serial -replace '[^\w\.-]', '_') + '-' + (Split-Path -Leaf $bbRoot) + '.xml')   # not shared between sessions/worktrees
        for ($try = 0; $try -lt 3; $try++) {
            # toybox `timeout`: a dump stuck on a busy screen (player) is killed after 15 s (exit 124) and retried, not waited for.
            # Dump and read in ONE adb call (exec-out: raw bytes, no pull round trip); the XML is a single line.
            $o = (& $adbExe -s $serial exec-out 'timeout 15 uiautomator dump /sdcard/bluebox_ui.xml >/dev/null 2>&1 && cat /sdcard/bluebox_ui.xml' 2>&1 | ForEach-Object { "$_" }) -join "`n"
            $at = $o.IndexOf('<?xml')
            if ($LASTEXITCODE -eq 0 -and $at -ge 0 -and $o.TrimEnd().EndsWith('</hierarchy>')) {
                $o = $o.Substring($at)
                [System.IO.File]::WriteAllText($xml, $o, (New-Object System.Text.UTF8Encoding($false)))   # last dump kept for debugging, as before
                [xml]$doc = $o
                $script:UiCache = $doc; $script:UiCacheAt = Get-Date
                return $doc
            }
            Start-Sleep -Milliseconds 300   # only after a failed dump (no sleep on the normal path)
        }
        throw "uiautomator dump failed: $($o.Trim())"
    } finally { $script:Tm.dump += $sw.Elapsed.TotalSeconds; $script:Tm.dumps++ }
}

function Get-FocusInfo($doc) {
    $res = @()
    foreach ($n in $doc.SelectNodes("//node[@focused='true']")) {
        $inner = @($n.SelectNodes('.//node') | ForEach-Object { $_.text; $_.'content-desc' } | Where-Object { $_ } | Select-Object -First 6) -join ' | '
        $res += [pscustomobject]@{
            Class = ($n.class -replace '^.*\.', ''); Id = ($n.'resource-id' -replace '^.*:id/', '')
            Text = $n.text; Desc = $n.'content-desc'; Bounds = $n.bounds; Inner = $inner
        }
    }
    , $res
}

function Get-AllTexts($doc) {
    @($doc.SelectNodes('//node') | ForEach-Object { $_.text; $_.'content-desc' } | Where-Object { $_ })
}

function Show-Focus {
    $f = Get-FocusInfo (Get-UiDoc)
    if (-not $f.Count) { 'focus: nothing focused'; return }
    foreach ($i in $f) { "focus: $($i.Class) id=$($i.Id) text='$($i.Text)' desc='$($i.Desc)' bounds=$($i.Bounds) inner='$($i.Inner)'" }
}

function Show-Log {
    $appPid = (& $adbExe -s $serial shell pidof $pkg 2>$null)
    $a = @('logcat', '-d', '-v', 'brief')
    if ($appPid) { $a += "--pid=$($appPid.Trim())" }
    & $adbExe -s $serial @a 2>$null | Select-String -Pattern $Pattern | Select-Object -Last $Lines | ForEach-Object { $_.Line }
}

# ---- lifecycle ------------------------------------------------------------------------------
function Device-Now { ((AdbShell "date +'%m-%d %H:%M:%S.000'") | Out-String).Trim() }

function Mark-Start { Device-Now | Set-Content -Path $stateFile -Encoding ASCII }

function Launch-App {
    Mark-Start
    $o = (AdbShell "am start -W -n $activity" | Out-String)
    if ($o -match 'Error|Exception') { throw "am start failed: $($o.Trim())" }
}

function Reset-App {
    if (-not $isEmulator) { throw 'reset-app works with emulators only' }
    # the package name comes ONLY from -Variant; never take it from arguments
    if ($pkg -notmatch '^app\.bluebox\.tv\.(debug|beta)$') { throw "reset-app: refusing to clear '$pkg'" }
    $o = (AdbShell "pm clear $pkg" | Out-String).Trim()
    if ($o -notmatch 'Success') { throw "pm clear $pkg failed: $o" }
    "reset-app: pm clear $pkg -> Success"
}

# Emulator animations off for the duration of a scenario / batch (uiautomator waits for idle, focus moves settle at once), then the
# previous values come back. perf-check does NOT use this: performance is measured with animations. BLUEBOX_KEEP_ANIM=1 = leave as is.
$animKeys = @('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')
$script:AnimSaved = $null
function Disable-Anim {
    if ($script:AnimSaved -or $env:BLUEBOX_KEEP_ANIM -or -not $isEmulator -or $DryRun) { return }
    $cur = @(AdbShell (($animKeys | ForEach-Object { "settings get global $_" }) -join '; ') | ForEach-Object { "$_".Trim() })
    if ($cur.Count -lt $animKeys.Count) { return }   # unreadable: change nothing
    $script:AnimSaved = @{}; for ($i = 0; $i -lt $animKeys.Count; $i++) { $script:AnimSaved[$animKeys[$i]] = $cur[$i] }
    AdbShell (($animKeys | ForEach-Object { "settings put global $_ 0" }) -join '; ') | Out-Null
}
function Restore-Anim {
    if (-not $script:AnimSaved) { return }
    $cmds = foreach ($k in $animKeys) {
        $v = "$($script:AnimSaved[$k])"
        if (-not $v -or $v -eq 'null') { "settings delete global $k" } elseif ($v -match '^[\d.]+$') { "settings put global $k $v" } else { "settings put global $k 1.0" }
    }
    AdbShell ($cmds -join '; ') | Out-Null
    $script:AnimSaved = $null
}

function Measure-StartTime($maxMs) {
    AdbQuiet shell am force-stop $pkg
    Start-Sleep -Milliseconds 800
    Mark-Start
    $o = (AdbShell "am start -W -n $activity" | Out-String)
    if ($o -match 'Error|Exception') { throw "am start failed: $($o.Trim())" }
    $m = [regex]::Match($o, 'TotalTime:\s*(\d+)')
    if (-not $m.Success) { throw "start-time: no TotalTime in am start -W output: $($o.Trim())" }
    $ms = [int]$m.Groups[1].Value
    if ($null -eq $maxMs) { Write-Output "start-time: TotalTime=${ms}ms"; return }
    # debug starts in 4+ s (no R8, no AOT): its time says nothing about the product, so the limit is enforced on beta only (docs/testing.md)
    if ($Variant -ne 'beta') { Pass "start-time TotalTime=${ms}ms (debug: limit ${maxMs}ms is checked on -Variant beta only)"; return }
    if ($ms -gt [int]$maxMs) { Fail "start-time: TotalTime=${ms}ms > ${maxMs}ms" }
    Pass "start-time TotalTime=${ms}ms <= ${maxMs}ms"
}

function Set-Proxy([string]$mode) {
    if (-not $isEmulator) { throw 'proxy works with emulators only' }
    if ($mode -eq 'off') { AdbQuiet shell settings put global http_proxy ':0'; return 'proxy: off (http_proxy=:0)' }
    if ($mode -ne 'on') { throw 'proxy on|off' }
    $hp = $null
    if ($env:BLUEBOX_EMU_PROXY) { $hp = $env:BLUEBOX_EMU_PROXY.Trim() -replace '^https?://', '' -replace '/+$', '' }
    else {
        $e = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { $env:HTTP_PROXY }
        if ($e -and $e -match '^(?:https?://)?((?:127\.0\.0\.1|localhost):\d+)') { $hp = $Matches[1] }
    }
    if (-not $hp -or $hp -notmatch '^([^:]+):(\d+)$') { throw 'proxy on: set env BLUEBOX_EMU_PROXY=host:port (or HTTPS_PROXY/HTTP_PROXY pointing to 127.0.0.1:<port>)' }
    $h = $Matches[1]; $port = $Matches[2]
    if ($h -in @('127.0.0.1', 'localhost')) { $h = '10.0.2.2' }   # emulator alias of the host loopback
    AdbQuiet shell settings put global http_proxy "${h}:${port}"
    "proxy: on (http_proxy=${h}:${port})"
}

function Wait-Boot([int]$timeoutSec = 240) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    & $adbExe -s $serial wait-for-device 2>&1 | Out-Null
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        $b = (& $adbExe -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($b -eq '1') { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# Own AVD for a second emulator, created next to the repo (never inside D:\Ani). Returns the AVD home dir.
function Ensure-SecondAvd([string]$name) {
    $root = $repo -replace '[\\/]\.claude[\\/]worktrees[\\/].*$', ''   # main checkout even when run from a worktree
    $home2 = if ($env:BLUEBOX_EMU2_AVD_HOME) { $env:BLUEBOX_EMU2_AVD_HOME } else { Join-Path (Split-Path $root -Parent) 'BlueBox-avd' }
    $dir = Join-Path $home2 "$name.avd"
    if (-not (Test-Path (Join-Path $dir 'config.ini'))) {
        New-Item -ItemType Directory -Force $dir | Out-Null
        @('avd.ini.encoding=UTF-8', "path=$dir", 'target=android-36') | Set-Content (Join-Path $home2 "$name.ini") -Encoding ASCII
        @('PlayStore.enabled=no', 'abi.type=x86', 'hw.cpu.arch=x86', 'hw.cpu.ncore=4', 'hw.device.manufacturer=Google', 'hw.device.name=tv_1080p',
          'hw.dPad=yes', 'hw.screen=multi-touch', 'hw.keyboard=no', 'hw.mainKeys=yes', 'hw.gpu.enabled=no', 'hw.gpu.mode=auto',
          'hw.lcd.density=320', 'hw.lcd.depth=16', 'hw.lcd.height=1080', 'hw.lcd.width=1920', 'hw.lcd.vsync=60', 'hw.ramSize=2048',
          'hw.sdCard=no', 'disk.dataPartition.size=6G', 'image.sysdir.1=system-images\android-36\android-tv\x86\',
          'tag.display=Android TV', 'tag.displaynames=Android TV', 'tag.id=android-tv', 'tag.ids=android-tv', 'target=android-36',
          'vm.heapSize=128M', 'showDeviceFrame=no') | Set-Content (Join-Path $dir 'config.ini') -Encoding ASCII
    }
    $home2
}

function Ensure-Emulator {
    $devs = (& $adbExe devices | Out-String)
    if ($devs -match "$serial\s+device") { return "emulator already running: $serial" }
    if ($serial -eq 'emulator-5554') {
        # Ani_TV_API_36 is not -read-only and cannot run twice; a running Bb_TV_2 (5556, 5558) does not block it
        foreach ($s in @([regex]::Matches($devs, '(emulator-\d+)\s+device') | ForEach-Object { $_.Groups[1].Value })) {
            $avd = ((& $adbExe -s $s emu avd name 2>$null | Select-Object -First 1) | Out-String).Trim()
            if ($avd -eq 'Ani_TV_API_36') { throw "AVD Ani_TV_API_36 already runs as $s, not $serial (use -Device $s)" }
        }
    }
    $emuExe = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
    if (-not (Test-Path $emuExe)) { throw 'ПРОБЛЕМА ОКРУЖЕНИЯ: emulator.exe не найден в SDK' }
    # stale crash reports make the emulator wait on a hidden "send report?" dialog and never boot
    Remove-Item (Join-Path $env:TEMP 'AndroidEmulator\emu-crash-*') -Recurse -Force -ErrorAction SilentlyContinue
    $avdName = 'Ani_TV_API_36'; $avdHomeOld = $env:ANDROID_AVD_HOME
    $emuArgs = @('-no-snapshot-save', '-no-boot-anim', '-no-audio')
    if ($serial -eq 'emulator-5554') { $emuArgs += @('-port', '5554') }
    if ($serial -match '^emulator-(\d+)$' -and [int]$Matches[1] -ne 5554) {
        # Second instance (-Device emulator-5556): one AVD cannot run twice unless BOTH use -read-only, and the D:\Ani fork
        # is read-only for us -> own AVD (same system image, our AVD home), data in a temp copy (-read-only), clean each boot.
        $avdName = 'Bb_TV_2'; $env:ANDROID_AVD_HOME = Ensure-SecondAvd $avdName
        $emuArgs += @('-port', $Matches[1], '-read-only')
    }
    $emuArgs = @('-avd', $avdName) + $emuArgs
    if ($env:BLUEBOX_EMU_PROXY) {
        $px = $env:BLUEBOX_EMU_PROXY.Trim(); if ($px -notmatch '^https?://') { $px = "http://$px" }
        $emuArgs += @('-http-proxy', $px)
    }
    Start-Process $emuExe -ArgumentList $emuArgs -WindowStyle Minimized
    $env:ANDROID_AVD_HOME = $avdHomeOld
    if (-not (Wait-Boot 240)) { throw 'ПРОБЛЕМА ОКРУЖЕНИЯ: эмулятор не загрузился за 240 с' }
    'emulator booted'
}

function Find-Apk {
    $dir = Join-Path $repo "app-tv\build\outputs\apk\$Variant"
    $f = Get-ChildItem $dir -Filter *.apk -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $f) { throw "no APK in $dir - run build" }
    $f.FullName
}

function Do-Build {
    Push-Location $repo
    try {
        $task = ':app-tv:assemble' + (Get-Culture).TextInfo.ToTitleCase($Variant)
        $log = & .\gradlew.bat $task --console=plain -q 2>&1 | ForEach-Object { "$_" }
        if ($LASTEXITCODE -ne 0) {
            $bad = $log | Select-String -Pattern '^e: |error:|FAILURE|What went wrong|BUILD FAILED|Could not' | Select-Object -First 40
            $bad | ForEach-Object { $_.Line }; if (-not $bad) { $log | Select-Object -Last 30 }
            throw 'BUILD FAILED'
        }
        'BUILD OK: ' + (Find-Apk)
    } finally { Pop-Location }
}

# ---- assertions -----------------------------------------------------------------------------
function Assert-Alive {
    $p = (& $adbExe -s $serial shell pidof $pkg 2>$null | Out-String).Trim()
    if (-not $p) { Fail "process $pkg is not running" }
    Pass "process alive ($pkg pid $p)"
}

function Assert-NoCrash {
    $p = (& $adbExe -s $serial shell pidof $pkg 2>$null | Out-String).Trim()
    if (-not $p) { Fail "process $pkg is not running (crashed or never started)" }
    $since = if (Test-Path $stateFile) { (Get-Content $stateFile -Raw).Trim() } else { '01-01 00:00:00.000' }
    $log = (& $adbExe -s $serial shell "logcat -d -v threadtime -T '$since'" 2>&1 | Out-String)
    $m = [regex]::Match($log, "FATAL EXCEPTION[^\r\n]*[\r\n]+[^\r\n]*Process: $([regex]::Escape($pkg))[^\r\n]*[\r\n]+[^\r\n]*[\r\n]+[^\r\n]*")
    if ($m.Success) { Fail ("FATAL EXCEPTION in ${pkg}: " + (($m.Value -split '[\r\n]+' | Select-Object -Last 2) -join ' / ')) }
    Pass "no FATAL EXCEPTION for $pkg since $since"
}

function Assert-Focus([string]$re) {
    $p = Get-TreeDoc
    if ($p) {
        foreach ($i in (Get-FocusInfo $p)) {
            if ("$($i.Text) | $($i.Desc) | $($i.Inner)" -match $re) { Pass "focus /$re/ -> $($i.Class) '$($i.Text)$($i.Desc)' (app)"; return }
        }
    }
    $f = Get-FocusInfo (Get-UiDoc)
    if (-not $f.Count) { Fail "expect-focus /$re/: nothing is focused" }
    foreach ($i in $f) {
        $hay = "$($i.Text) | $($i.Desc) | $($i.Inner)"
        if ($hay -match $re) { Pass "focus /$re/ -> $($i.Class) '$($i.Text)$($i.Desc)'"; return }
    }
    Fail ("expect-focus /$re/: focused = " + (($f | ForEach-Object { "$($_.Class) '$($_.Text)' '$($_.Desc)' inner='$($_.Inner)'" }) -join '; '))
}

# The app's own picture of the screen (debug build: app-tv/src/debug/.../UiTreeProbe.kt, asked through the QueryFile channel as
# `uitree:<stableMs>`): the focused window's texts / descriptions / focus in the shape of a uiautomator dump, ~0.2-0.4 s against
# ~2.4 s. It only ever lets a check PASS: expect-text / expect-focus / wait-for take a hit from it and fall back to a real dump on a
# miss; expect-no-text takes "absent" from it after a longer stable window (400 ms) and confirms a hit with a dump. The app says
# it cannot answer (null here -> dump) when another window has focus (system dialog, transition), a WebView is on screen, or the
# screen does not settle (a running player clock). Waits out the key settle first, as the dump path did. BLUEBOX_NO_UITREE=1 turns it off.
$script:TreeOff = $false; $script:TreeEmpty = 0
function Get-TreeDoc([int]$stableMs = 150) {
    if ($script:QueryFileOff -or $script:TreeOff) { return $null }
    Wait-Settle
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $id = [guid]::NewGuid().ToString('N').Substring(0, 10)
        $sh = "cd files && echo $id uitree:$stableMs >dq.req.tmp && mv dq.req.tmp dq.req && i=0; while [ ! -f dq-$id.txt ] && [ `$i -lt 60 ]; do sleep 0.05; i=`$((i+1)); done; cat dq-$id.txt && rm -f dq-$id.txt"
        $o = ((AdbShell "pidof $pkg >/dev/null || { echo NOPID; exit 0; }; run-as $pkg sh -c $(ShQuote $sh)" | Out-String) -replace "`r", '').Trim()
        if ($o.StartsWith('<?xml') -and $o.EndsWith('</hierarchy>')) {
            try { [xml]$d = $o; $script:Tm.trees++; $script:TreeEmpty = 0; return $d } catch { $script:Tm.treeMiss++; return $null }
        }
        $script:Tm.treeMiss++
        if (-not $o) { if ((++$script:TreeEmpty) -ge 2) { $script:TreeOff = $true } }   # no answer twice: a build without uitree
        elseif ($o -notmatch '^(UITREE |NOPID)') { $script:TreeOff = $true }           # run-as refused (beta) and the like
        return $null
    } finally { $script:Tm.tree += $sw.Elapsed.TotalSeconds }
}

function Find-Text($doc, [string]$re) { Get-AllTexts $doc | Where-Object { $_ -match $re } | Select-Object -First 1 }

function Assert-Text([string]$re) {
    $p = Get-TreeDoc
    if ($p) { $hit = Find-Text $p $re; if ($hit) { Pass "text /$re/ -> '$hit' (app)"; return } }
    $hit = Find-Text (Get-UiDoc) $re
    if (-not $hit) { Fail "expect-text /$re/: not found in UI" }
    if ($p) { $script:Tm.rescue++ }   # the app tree missed what the dump shows: worth a look (UiTreeProbe)
    Pass "text /$re/ -> '$hit'"
}

function Assert-NoText([string]$re) {
    $p = Get-TreeDoc 400
    if ($p -and -not (Find-Text $p $re)) { Pass "no text /$re/ in UI (app)"; return }
    $hit = Find-Text (Get-UiDoc) $re
    if ($hit) { Fail "expect-no-text /$re/: found '$hit' in UI" }
    Pass "no text /$re/ in UI"
}

function Wait-For([string]$re, [double]$sec) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $tree = $true
    do {
        $hit = $null
        $p = if ($tree) { Get-TreeDoc } else { $null }
        if ($p) { $hit = Find-Text $p $re; $how = ' (app)' }
        else { $tree = $false; $how = ''; try { $hit = Find-Text (Get-UiDoc -Fresh) $re } catch { $hit = $null } }   # no tree: dumps to the end
        if ($hit) { Pass ("wait-for /$re/ -> '$hit' ({0:N1}s){1}" -f $sw.Elapsed.TotalSeconds, $how); return }
        Test-AppHealth "wait-for /$re/"
        Start-Sleep -Milliseconds 250
    } while ($sw.Elapsed.TotalSeconds -lt $sec)
    if ($tree) {   # the tree saw nothing all along: one dump before failing
        $hit = Find-Text (Get-UiDoc -Fresh) $re
        if ($hit) { $script:Tm.rescue++; Pass ("wait-for /$re/ -> '$hit' ({0:N1}s, dump)" -f $sw.Elapsed.TotalSeconds); return }
    }
    Fail "wait-for /$re/: not seen within ${sec}s"
}

function Assert-Playing([double]$sec) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $state = 'no session'
    do {
        $d = (& $adbExe -s $serial shell dumpsys media_session 2>&1 | Out-String)
        $i = $d.IndexOf("package=$pkg")
        if ($i -ge 0) {
            $blk = $d.Substring($i + 8)
            $j = $blk.IndexOf('package=')
            if ($j -gt 0) { $blk = $blk.Substring(0, $j) }
            $m = [regex]::Match($blk, 'state=PlaybackState \{state=(?:[A-Z_]+\()?(\d+)')
            if ($m.Success) {
                $state = "state=$($m.Groups[1].Value)"
                if ($m.Groups[1].Value -eq '3') { Pass ("playing (media_session $state, {0:N1}s)" -f $sw.Elapsed.TotalSeconds); return }
            }
        }
        Test-AppHealth 'expect-playing'
        Start-Sleep -Milliseconds 250
    } while ($sw.Elapsed.TotalSeconds -lt $sec)
    Fail "expect-playing: $pkg not PLAYING within ${sec}s (last: $state)"
}

# Fast channel of the `ui` table: the debug build mirrors its row into files/ui-probe.txt (`pid=<pid>` + the `content query`
# row, app-tv/src/debug/.../UiProbeFile.kt); `run-as cat` costs ~0.05 s against ~1.3 s of `content query`. The file counts
# only while its pid is alive (a killed process leaves a stale one); otherwise that poll asks the provider. run-as refused
# (beta, not debuggable) or no file twice while the app runs (an older build) -> content query for the rest of the scenario.
# The file's `at=` (write time, elapsedRealtime ms) against /proc/uptime gives the age of the state -> $script:ProbeAgeMs (-1 unknown).
function Read-UiProbeFile {
    $script:ProbeAgeMs = -1
    if ($script:ProbeFileOff) { return $null }
    $lines = @(AdbShell "pidof $pkg; cat /proc/uptime; run-as $pkg cat files/ui-probe.txt" | ForEach-Object { ($_ -replace "`r", '').Trim() })
    if ($lines -match '^run-as:') { $script:ProbeFileOff = $true; return $null }
    $pids = @($lines | Where-Object { $_ -match '^\d+(\s+\d+)*$' } | ForEach-Object { $_ -split '\s+' })
    $filePid = ($lines | Where-Object { $_ -match '^pid=\d+$' } | Select-Object -First 1) -replace '^pid=', ''
    $row = @($lines | Where-Object { $_ -match '^Row: ' })
    if ($filePid -and $pids -contains $filePid -and $row.Count) {
        $script:ProbeMiss = 0
        $up = $lines | Where-Object { $_ -match '^\d+\.\d+ \d+\.\d+$' } | Select-Object -First 1
        $at = ($lines | Where-Object { $_ -match '^at=\d+$' } | Select-Object -First 1) -replace '^at=', ''
        if ($up -and $at) { $script:ProbeAgeMs = [Math]::Max(0, [double]::Parse(($up -split ' ')[0], [Globalization.CultureInfo]::InvariantCulture) * 1000 - [double]$at) }
        return ($row -join "`n")
    }
    if ($pids.Count -and -not $filePid -and (++$script:ProbeMiss) -ge 2) { $script:ProbeFileOff = $true }
    $null
}

# Fast channel of every debug table (app-tv/src/debug/.../QueryFile.kt): `run-as` writes `files/dq.req` (`<id> <uri>`), the app
# answers `files/dq-<id>.txt` in the `content query` format; one adb call, ~0.1-0.2 s against ~1.3 s. No app process -> $null
# (content query starts it, as before); no answer within 1.5 s or run-as refused (beta, an older build) -> content query for
# the rest of the scenario. BLUEBOX_NO_QUERY_FILE=1 turns it off.
function Read-QueryFile([string]$uri) {
    if ($script:QueryFileOff -or $uri -notmatch '^content://app\.bluebox\.tv\.debug\.state/' -or $uri -match "[\s'""``$\\]") { return $null }
    $id = [guid]::NewGuid().ToString('N').Substring(0, 10)
    $u = $uri -replace '([&;|<>()?*\[\]#~!{}])', '\$1'   # literal for the inner sh
    $sh = "cd files && echo $id $u >dq.req.tmp && mv dq.req.tmp dq.req && i=0; while [ ! -f dq-$id.txt ] && [ `$i -lt 30 ]; do sleep 0.05; i=`$((i+1)); done; cat dq-$id.txt && rm -f dq-$id.txt"
    $o = ((AdbShell "pidof $pkg >/dev/null || { echo NOPID; exit 0; }; run-as $pkg sh -c $(ShQuote $sh)" | Out-String) -replace "`r", '').Trim()
    if ($o -eq 'NOPID') { return $null }
    if ($o -match '^(Row: |No result found\.|Exception )') { return $o }
    $script:QueryFileOff = $true   # run-as refused / no answer: this build has no QueryFile
    $null
}

# Fail-fast health check inside the polls (wait-for, expect-query/wait-ui, expect-playing): at most every 3 s, one adb call.
# Process gone -> CRASH at once; an ANR / crash dialog -> CRASH; another package's window in front at two checks in a row
# (the launcher after a lost BACK, a system dialog) -> FAIL. Instead of burning the whole timeout.
$script:HealthAt = [datetime]::MinValue; $script:ForeignSeen = 0
function Test-AppHealth([string]$what) {
    if (((Get-Date) - $script:HealthAt).TotalSeconds -lt 3) { return }
    $script:HealthAt = Get-Date
    $o = @(AdbShell "pidof $pkg; dumpsys window | grep -m1 mCurrentFocus" | ForEach-Object { ($_ -replace "`r", '').Trim() } | Where-Object { $_ })
    if (-not ($o | Where-Object { $_ -match '^\d+(\s+\d+)*$' })) { Fail "$what - process $pkg is not running (died while waiting)" }
    $focus = "$($o | Where-Object { $_ -match 'mCurrentFocus' } | Select-Object -First 1)"
    if ($focus -match 'Application Not Responding|Application Error|isn.t responding') {
        if ($focus -match [regex]::Escape($pkg) -or $focus -notmatch 'systemui') { Fail "$what - $pkg is not responding (ANR) or a crash dialog is up: $focus" }
    }
    if ($focus -match '\{\S+ \S+ ([\w.]+)/' -and $Matches[1] -ne $pkg) {
        if ((++$script:ForeignSeen) -ge 2) { Fail "$what - another app's window is in front ($($Matches[1])): $focus" }
    } else { $script:ForeignSeen = 0 }
}

function Assert-Query([string]$uri, [string]$re, [double]$sec = 0) {
    if (-not $uri -or -not $re) { throw 'expect-query <uri> <regex> [sec]' }
    # poll until the regex matches or the time is up (state that settles after buffering, a timer): every 0.25 s,
    # every 0.1 s while the `ui` table comes from the fast file
    $deadline = (Get-Date).AddSeconds($sec)
    while ($true) {
        $qs = [Diagnostics.Stopwatch]::StartNew()
        $o = if ($uri -eq $uiProbeUri) { Read-UiProbeFile } else { $null }
        $fast = $null -ne $o
        $generic = $false
        if (-not $fast) { $o = Read-QueryFile $uri; $generic = $fast = $null -ne $o }
        if (-not $fast) { $o = ((AdbShell "content query --uri $(ShQuote $uri)" | Out-String) -replace "`r", '').Trim() }
        $script:Tm.query += $qs.Elapsed.TotalSeconds; $script:Tm.queries++
        if ($fast) { $script:Tm.fileQuery += $qs.Elapsed.TotalSeconds; $script:Tm.fileQueries++ }
        if ((Get-Date) -ge $deadline -or [regex]::IsMatch($o, $re)) { break }
        $hs = [Diagnostics.Stopwatch]::StartNew(); Test-AppHealth "expect-query $uri /$re/"; $script:Tm.query += $hs.Elapsed.TotalSeconds
        Start-Sleep -Milliseconds $(if ($fast) { 100 } else { 250 })
    }
    # the slow `content query` returned ~0.3 s after reading the state; a fast answer does not: the next key/tap waits that
    # long (lazy, like the pause after keys) so a key never lands mid transition
    if ($generic) { $script:InputSettleUntil = (Get-Date).AddMilliseconds($probeSettleMs) }
    $short = $o.Substring(0, [Math]::Min(200, $o.Length))
    # FAIL only on adb/content errors or an empty result; a row that merely contains the word Error/Exception is fine
    if ($o -match '^(adb: |error: |Error while |Exception |java\.\w+\.\w*(Exception|Error)|Unable to|Permission Denial)') { Fail "expect-query ${uri}: $short" }
    if ($o -notmatch '(?m)^Row: ') { Fail "expect-query ${uri}: no 'Row:' lines in output '$short'" }
    $m = [regex]::Match($o, $re)   # .NET regex over the full multi-line output: (?s), backreferences allowed
    if (-not $m.Success) { Fail "expect-query $uri /$re/: output '$short'" }
    # a fast match can come mid screen transition (250 ms): focus is already restored, the next key would be lost — let the
    # state age to $probeSettleMs (content query's ~1.3 s used to hide this; m5-settings-focus, m4-collections, 2026-10-20)
    if ($fast -and $script:ProbeAgeMs -ge 0 -and $script:ProbeAgeMs -lt $probeSettleMs) {
        $ss = [Diagnostics.Stopwatch]::StartNew(); Start-Sleep -Milliseconds ([int]($probeSettleMs - $script:ProbeAgeMs))
        $script:Tm.fileQuery += $ss.Elapsed.TotalSeconds; $script:Tm.query += $ss.Elapsed.TotalSeconds
    }
    $val = $m.Value; if ($val.Length -gt 160) { $val = $val.Substring(0, 160) + '...' }
    Pass "query $uri /$re/ -> '$($val -replace '\s*\n\s*', ' / ')'"
}

function Assert-Prop([string]$prop, [string]$re) {
    if (-not $prop -or -not $re) { throw 'expect-prop <prop> <regex>' }
    if ($prop -notmatch '^[\w.\-]+$') { throw "expect-prop: bad property name '$prop'" }
    $v = ((AdbShell "getprop $prop" | Out-String) -replace "`r", '').Trim()
    if ($v -notmatch $re) { Fail "expect-prop ${prop}: value '$v' does not match /$re/" }
    Pass "prop $prop='$v' matches /$re/"
}

# ---- emulator lease -------------------------------------------------------------------------
# One session = one worktree = one lease owner. Shared by all worktrees of the machine (not $env:TEMP: sessions may override it).
$leaseDir = if ($env:BLUEBOX_LEASE_DIR) { $env:BLUEBOX_LEASE_DIR } else { Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'BlueBox\emu-leases' }
$leaseTtlMin = if ($env:BLUEBOX_LEASE_TTL_MIN) { [double]$env:BLUEBOX_LEASE_TTL_MIN } else { 30 }
$emuPool = @(if ($env:BLUEBOX_EMU_POOL) { $env:BLUEBOX_EMU_POOL -split '[,;\s]+' | Where-Object { $_ -match '^emulator-\d+$' } } else { 'emulator-5554', 'emulator-5556' })
$leaseOwner = [IO.Path]::GetFullPath($(if ($env:BLUEBOX_LEASE_OWNER) { $env:BLUEBOX_LEASE_OWNER } else { $bbRoot })).TrimEnd('\')   # override: tests only

function With-LeaseLock([scriptblock]$body) {
    $m = New-Object System.Threading.Mutex($false, 'Local\BlueBoxEmuLease')
    $held = $false
    try {
        try { $held = $m.WaitOne(15000) } catch [System.Threading.AbandonedMutexException] { $held = $true }
        & $body
    } finally { if ($held) { $m.ReleaseMutex() }; $m.Dispose() }
}

function Get-RunningEmulators { @((& $adbExe devices 2>$null) | ForEach-Object { if ("$_" -match '^(emulator-\d+)\s+device\b') { $Matches[1] } }) }

function Read-Lease([string]$s) {
    $f = Join-Path $leaseDir "$s.json"
    if (-not (Test-Path $f)) { return $null }
    try { $l = Get-Content $f -Raw -Encoding UTF8 | ConvertFrom-Json } catch { return $null }
    $age = ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - [long]$l.renewedUnix) / 60
    $l | Add-Member -NotePropertyName ageMin -NotePropertyValue ([math]::Round($age, 1)) -Force
    $l | Add-Member -NotePropertyName live -NotePropertyValue ($age -lt $leaseTtlMin) -Force
    $l | Add-Member -NotePropertyName mine -NotePropertyValue ($l.owner -eq $leaseOwner) -Force
    $l
}

function Write-Lease([string]$s, [string]$cmd, $prev) {
    New-Item -ItemType Directory -Force $leaseDir | Out-Null
    $now = [DateTimeOffset]::UtcNow
    $acq = if ($prev -and $prev.live -and $prev.mine) { $prev.acquired } else { $now.LocalDateTime.ToString('yyyy-MM-dd HH:mm:ss') }
    $json = [ordered]@{
        serial = $s; owner = $leaseOwner; worktree = (Split-Path -Leaf $leaseOwner); command = $cmd
        acquired = $acq; renewed = $now.LocalDateTime.ToString('yyyy-MM-dd HH:mm:ss'); renewedUnix = $now.ToUnixTimeSeconds()
    } | ConvertTo-Json
    [IO.File]::WriteAllText((Join-Path $leaseDir "$s.json"), $json, $enc)
}

function Describe-Lease($l) { "$($l.worktree) ($($l.command), $($l.ageMin) мин назад; свободен не позже чем через $([math]::Ceiling($leaseTtlMin - $l.ageMin)) мин)" }

# Takes or renews a lease. $want = 'auto' or a serial. Returns @{Serial; New} or @{Serial=$null; Busy; Stopped}.
function Try-Lease([string]$want, [string]$cmd, [bool]$allowStopped) {
    With-LeaseLock {
        $cands = if ($want -eq 'auto') { $emuPool } else { @($want) }
        $leases = @{}; foreach ($c in $cands) { $leases[$c] = Read-Lease $c }
        $free = { param($c) -not ($leases[$c] -and $leases[$c].live -and -not $leases[$c].mine) }
        $pick = $null; $running = @()
        if ($want -eq 'auto') {
            $running = Get-RunningEmulators
            $pick = $cands | Where-Object { $leases[$_] -and $leases[$_].live -and $leases[$_].mine -and ($allowStopped -or $running -contains $_) } | Select-Object -First 1
            if (-not $pick) { $pick = $cands | Where-Object { $running -contains $_ -and (& $free $_) } | Select-Object -First 1 }
            if (-not $pick -and $allowStopped) { $pick = $cands | Where-Object { (& $free $_) } | Select-Object -First 1 }
        } elseif (& $free $want) { $pick = $want }
        if ($pick) {
            $prev = $leases[$pick]
            Write-Lease $pick $cmd $prev
            return [pscustomobject]@{ Serial = $pick; New = -not ($prev -and $prev.live -and $prev.mine) }
        }
        [pscustomobject]@{
            Serial = $null
            Busy = @($cands | Where-Object { -not (& $free $_) } | ForEach-Object { "$_ — $(Describe-Lease $leases[$_])" })
            Stopped = @($cands | Where-Object { (& $free $_) -and $running -notcontains $_ })
        }
    }
}

function Acquire-Lease([string]$want, [string]$cmd) {
    $sw = [Diagnostics.Stopwatch]::StartNew(); $said = $false
    while ($true) {
        $r = Try-Lease $want $cmd ($cmd -eq 'emu' -or $cmd -like 'scenario*')
        if ($r.Serial) {
            if ($r.New) { Write-Host "lease: $($r.Serial) -> $(Split-Path -Leaf $leaseOwner) (новая аренда: приложение там может быть чужой сборки — сделай install)" }
            return $r.Serial
        }
        if (-not $r.Busy.Count) { throw "ПРОБЛЕМА ОКРУЖЕНИЯ: ни один эмулятор пула ($($emuPool -join ', ')) не запущен — сначала tv.ps1 emu" }
        $msg = 'занят: ' + ($r.Busy -join '; ')
        if ($r.Stopped.Count) { $msg += "; свободен, но не запущен: $($r.Stopped -join ', ') (tv.ps1 emu поднимет его)" }
        if ($sw.Elapsed.TotalSeconds -ge $WaitLease) {
            throw "BUSY: $msg. Чужой эмулятор не трогать: подожди (-WaitLease 1800), возьми свободный (-Device auto) или займись тем, что не требует эмулятора."
        }
        if (-not $said) { Write-Host "WAIT: $msg; жду до $WaitLease с"; $said = $true }
        Start-Sleep -Seconds 15
    }
}

function Release-Lease {
    $targets = if ($Device -eq 'auto') { $emuPool } else { @($serial) }
    $done = @(With-LeaseLock {
        foreach ($s in $targets) {
            $l = Read-Lease $s
            if (-not $l -or -not $l.mine) { continue }
            if ($Kill) { & $adbExe -s $s emu kill 2>&1 | Out-Null; "killed: $s" }
            Remove-Item (Join-Path $leaseDir "$s.json") -Force
            "released: $s"
        }
    })
    if ($done.Count) { $done } else { 'release: своей аренды нет' }
}

function Show-Leases {
    $running = Get-RunningEmulators
    foreach ($s in @(@($emuPool) + $running | Select-Object -Unique)) {
        $l = Read-Lease $s
        $st = if ($running -contains $s) { 'running' } else { 'stopped' }
        $who = if ($l -and $l.live) { 'busy: ' + (Describe-Lease $l) + $(if ($l.mine) { ' [моя]' } else { '' }) } else { 'free' }
        "$s  $st  $who"
    }
}

# ---- dispatcher -----------------------------------------------------------------------------
function Invoke-One([string]$cmd, [string[]]$t) {
    $sw = [Diagnostics.Stopwatch]::StartNew(); $d0 = $script:Tm.dump; $q0 = $script:Tm.query; $script:Settled = 0.0; $r0 = $script:Tm.tree
    # read-only on the screen: expect-*, focus, shot, log, query... keep the cache; tap-text / tap-id read it and then drop it
    if ($cmd -notin @('expect-focus', 'expect-text', 'expect-no-text', 'focus', 'tap-text', 'tap-id', 'wait-for', 'expect-alive', 'expect-no-crash',
                      'expect-playing', 'expect-query', 'expect-prop', 'expect-focus-key', 'wait-ready', 'expect-ui', 'wait-ui', 'shot', 'log')) {
        $script:UiCache = $null
    }
    # the lazy pause after the last key (Send-Keys): only reads that settle by themselves (a dump waits for idle UI) skip it;
    # deeplink/restart/... keep it, or the key's navigation races the new intent (m4-collections: BACK + deeplink)
    if ($cmd -notin @('keys', 'expect-focus', 'expect-text', 'expect-no-text', 'wait-for', 'tap-text', 'tap-id', 'focus')) { Wait-Settle }
    elseif ($cmd -ne 'keys') { $script:SettleUntil = [datetime]::MinValue }
    if ($cmd -in @('keys', 'tap', 'text', 'swipe')) {   # after a fast expect-query match (Assert-Query)
        $ms = ($script:InputSettleUntil - (Get-Date)).TotalMilliseconds
        if ($ms -gt 0) { Start-Sleep -Milliseconds ([int]$ms); $script:Tm.query += $ms / 1000; $script:Settled += $ms / 1000 }
    }
    $script:InputSettleUntil = [datetime]::MinValue
    try { Invoke-OneCore $cmd $t }
    finally {
        if ($cmd -in @('tap-text', 'tap-id')) { $script:UiCache = $null }
        $el = $sw.Elapsed.TotalSeconds - $script:Settled; $dd = ($script:Tm.dump - $d0) + ($script:Tm.query - $q0) + ($script:Tm.tree - $r0)   # dumps and probe queries are counted apart
        switch ($cmd) {
            'wait' { $script:Tm.wait += $el }
            { $_ -in @('wait-for', 'wait-ready', 'wait-ui', 'expect-playing') } { $script:Tm.wait += ($el - $dd) }
            { $_ -in @('restart', 'start', 'stop', 'reset-app', 'start-time') } { $script:Tm.start += $el }
            'keys' { $script:Tm.keys += $el }
            default { $script:Tm.other += ($el - $dd) }
        }
    }
}

function Invoke-OneCore([string]$cmd, [string[]]$t) {
    $a0 =if ($t.Count -gt 0) { $t[0] } else { $null }
    $a1 = if ($t.Count -gt 1) { $t[1] } else { $null }
    $joined = $t -join ' '
    switch ($cmd) {
        'devices' { & $adbExe devices -l }
        'leases' { Show-Leases }
        'acquire' { "device: $serial" }
        'release' { Release-Lease }
        'emu' { Ensure-Emulator }
        'build' { Do-Build }
        'install' {
            $apk = $a0
            if ($isEmulator -eq $false -and $Variant -ne 'beta') { Write-Output 'REFUSED: real device install is allowed for -Variant beta only.'; throw 'refused' }
            if (-not $apk) {
                # Emulator only: an APK older than the sources (pulled/merged code, edits) is rebuilt first - otherwise the
                # scenarios silently test the old build (2026-10-21: 3 runs of a fix went to a stale APK). Gradle up-to-date ->
                # the APK is touched so the next install does not rebuild again.
                if ($isEmulator -and -not $NoBuild) {
                    $cur = Get-ChildItem (Join-Path $repo "app-tv\build\outputs\apk\$Variant") -Filter *.apk -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
                    $src = Get-ChildItem $repo -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'src') } |
                        ForEach-Object { Get-ChildItem (Join-Path $_.FullName 'src') -Recurse -File -ErrorAction SilentlyContinue; Get-ChildItem $_.FullName -Filter '*.gradle.kts' -File } |
                        Sort-Object LastWriteTime | Select-Object -Last 1
                    if (-not $cur -or ($src -and $src.LastWriteTime -gt $cur.LastWriteTime)) {
                        "install: APK older than $($src.Name) - building"
                        Do-Build
                        $f = Get-ChildItem (Join-Path $repo "app-tv\build\outputs\apk\$Variant") -Filter *.apk | Sort-Object LastWriteTime | Select-Object -Last 1
                        if ($f.LastWriteTime -lt $src.LastWriteTime) { $f.LastWriteTime = Get-Date }
                    }
                }
                $apk = Find-Apk
            }
            if ($isEmulator -eq $false -and $apk -notmatch 'beta') { Write-Output 'REFUSED: real device install is allowed for the beta APK only.'; throw 'refused' }
            # Emulator only: skip the install when this exact APK (SHA-256) is already there - same boot, same package lastUpdateTime as after our own previous install
            # (any other install or a wiped/rebooted emulator changes one of them). -ForceInstall disables the skip.
            $stamp = $null; $stateFile = Join-Path $env:TEMP "bluebox-install-$($serial -replace '[^\w.-]', '_').json"
            if ($isEmulator) {
                $sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash
                $boot = ((AdbShell 'cat /proc/sys/kernel/random/boot_id' | Out-String).Trim())
                $upd = ((AdbShell "dumpsys package $pkg | grep -m1 lastUpdateTime" | Out-String).Trim())
                $stamp = @{ apk = $sha; boot = $boot; pkg = $pkg }
                if (-not $ForceInstall -and $upd -and (Test-Path $stateFile)) {
                    try {
                        $old = Get-Content $stateFile -Raw | ConvertFrom-Json
                        if ($old.apk -eq $sha -and $old.boot -eq $boot -and $old.pkg -eq $pkg -and $old.upd -eq $upd) { "install skipped: $apk is already installed on $serial (use -ForceInstall to reinstall)"; return }
                    } catch { }
                }
            }
            AdbQuiet install -r $apk; "installed: $apk -> $serial"
            if ($stamp) {
                $stamp.upd = ((AdbShell "dumpsys package $pkg | grep -m1 lastUpdateTime" | Out-String).Trim())
                try { $stamp | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8 } catch { }
            }
            # A fresh install is only 'verify'-compiled: Compose runs interpreted/JIT and the home screen drops to ~14 fps on a TV box
            # (Realtek rtd1325: first start 1.9 s -> 0.65 s, frames 215 -> 500 in the same D-pad scenario after AOT). Compile OUR package right away.
            if ($apk -match 'beta') { $c = (AdbShell "cmd package compile -m speed -f $pkg" | Out-String).Trim(); "compile -m speed: $c" }
        }
        # Blue Box Home (:app) debug APK for the real TV (owner allowed 2026-11-07: «Разрешаю хук»). Only -r install of the
        # prebuilt APK; the launcher is only started explicitly - it never becomes the default Home by itself.
        'install-home' {
            $homeApk = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
            if (-not (Test-Path $homeApk)) { throw "no APK: build it first (gradlew :app:assembleDebug): $homeApk" }
            AdbQuiet install -r $homeApk; "installed: $homeApk -> $serial"
        }
        # one process for a fast tour (the TV's screensaver starts within a minute: separate tv.ps1 launches are too slow)
        'home-tour' {
            Send-Keys 'BACK'; AdbQuiet shell am start -n app.bluebox.home.debug/app.bluebox.home.HomeActivity; Start-Sleep -Seconds 3
            Take-Shot 'ht-home'; AdbShell 'dumpsys activity activities | grep -m1 mResumedActivity'
            Send-Keys 'UP RIGHT*2'; Take-Shot 'ht-focus'
            Send-Keys 'CENTER'; Start-Sleep -Seconds 2; Take-Shot 'ht-panel'
            Send-Keys 'DOWN*4'; Take-Shot 'ht-panel-access'
            Send-Keys 'CENTER'; Start-Sleep -Seconds 3; Take-Shot 'ht-access'
            AdbShell 'dumpsys activity activities | grep -m1 mResumedActivity'
        }
        # opens the panel, the notification-access card and the system screen, flips the switch (owner asked 2026-11-07), shoots the dialog
        'home-grant' {
            Send-Keys 'BACK'; AdbQuiet shell am start -n app.bluebox.home.debug/app.bluebox.home.HomeActivity; Start-Sleep -Seconds 3
            Send-Keys 'UP RIGHT*2'; Send-Keys 'CENTER'; Start-Sleep -Seconds 2
            Send-Keys 'DOWN*4'; Send-Keys 'CENTER'; Start-Sleep -Seconds 3
            Take-Shot 'hg-1'; Send-Keys 'CENTER'; Start-Sleep -Seconds 2; Take-Shot 'hg-2'
            AdbShell 'dumpsys activity activities | grep -m1 mResumedActivity'
        }
        'home-listings' {
            Send-Keys 'BACK'; AdbQuiet shell am start -n app.bluebox.home.debug/app.bluebox.home.HomeActivity; Start-Sleep -Seconds 3
            Send-Keys 'UP RIGHT*2'; Send-Keys 'CENTER'; Start-Sleep -Seconds 2
            Send-Keys 'CENTER'; Start-Sleep -Seconds 2
            Send-Keys 'DOWN*2'; Send-Keys 'RIGHT*2'; Start-Sleep -Seconds 1; Take-Shot 'hl-1'
            Send-Keys 'CENTER'; Start-Sleep -Seconds 2; Take-Shot 'hl-2'
            AdbShell 'dumpsys activity activities | grep -m1 mResumedActivity'
        }
        'home-shot' { Take-Shot 'hs'; AdbShell 'dumpsys package app.bluebox.home.debug | grep READ_TV_LISTINGS' }
        'start-home' { AdbQuiet shell am start -n app.bluebox.home.debug/app.bluebox.home.HomeActivity; 'home started' }
        'stop-home' { AdbQuiet shell am force-stop app.bluebox.home.debug; 'home stopped' }
        # signed release of Home (applicationId app.bluebox.home) for the real TV (owner: «Да, ставь на ТВ», 2026-11-08): `install -r` only,
        # never a downgrade; the launcher is started explicitly and does not become the default Home by itself.
        'install-release' {
            $relApk = Join-Path $repo 'appuild\outputspkeleasepp-release.apk'
            if (-not (Test-Path $relApk)) { throw "no APK: build it first (gradlew :app:assembleRelease): $relApk" }
            AdbQuiet install -r $relApk; "installed: $relApk -> $serial"
        }
        'start-release' { AdbQuiet shell am start -n app.bluebox.home/.HomeActivity; 'release home started' }
        'start' { Launch-App; 'started' }
        'stop' { AdbQuiet shell am force-stop $pkg; 'stopped' }
        'restart' { AdbQuiet shell am force-stop $pkg; Launch-App; 'restarted' }
        'keys' { Send-Keys $joined; "keys: $joined" }
        'reset-app' { Reset-App }
        'start-time' { Measure-StartTime $a0 }
        'proxy' { Set-Proxy $a0 }
        'text' {
            if ($joined -match '[^\x00-\x7F]') { throw "text: 'input text' supports ASCII only; for Cyrillic use deeplink ""bluebox://search?q=<query>""" }
            AdbQuiet shell input text ($joined -replace ' ', '%s'); "text: $joined"
        }
        'tap' {
            if ($t.Count -lt 2) { throw 'tap <x> <y>' }
            Send-Tap ([int]$a0) ([int]$a1); "tap: $a0,$a1"
        }
        'tap-text' {
            if (-not $a0) { throw 'tap-text <regex>' }
            $c = Find-NodeCenter $a0
            if (-not $c) { Fail "tap-text /$a0/: no node with matching text" }
            Send-Tap $c[0] $c[1]; Pass "tap-text /$a0/ -> $($c[0]),$($c[1])"
        }
        'swipe' {
            if ($t.Count -lt 4) { throw 'swipe <x1> <y1> <x2> <y2> [ms]' }
            $ms = if ($t.Count -gt 4) { [int]$t[4] } else { 300 }
            AdbQuiet shell input swipe $t[0] $t[1] $t[2] $t[3] $ms; "swipe: $($t[0..3] -join ',') ${ms}ms"
        }
        'wait' { Start-Sleep -Milliseconds ([int]([double]$a0 * 1000)) }
        'shot' { Take-Shot $a0 }
        'focus' { Show-Focus }
        'log' { Show-Log }
        'deeplink' {
            if (-not $a0) { throw 'deeplink <uri>' }
            Mark-Start
            $o =(AdbShell "am start -W -a android.intent.action.VIEW -d $(ShQuote $a0) $pkg" | Out-String)
            if ($o -match 'Error|Exception') { Fail "deeplink $a0 : $($o.Trim())" }
            Pass "deeplink $a0"
        }
        'adb' {
            if (-not $joined) { throw 'adb "<args>"' }
            if (-not $isEmulator -and $joined -notmatch '^shell\s+(getprop|dumpsys)(\s|$)') { Write-Output 'REFUSED: on a non-emulator serial only shell getprop/dumpsys are allowed.'; throw 'refused' }
            if ($joined -match '(^|\s)(-s|install\S*|uninstall|pm\s+clear|pm\s+uninstall)(\s|$)') { Write-Output 'REFUSED: install/uninstall/pm clear/-s are not allowed here.'; throw 'refused' }
            $out = if ($joined -match '^shell\s+(.*)$') { & $adbExe -s $serial shell $Matches[1] 2>&1 } else { & $adbExe -s $serial @($joined -split '\s+') 2>&1 }
            $out | Select-Object -First 80 | ForEach-Object { "$_" }
        }
        'expect-focus' { Assert-Focus $a0 }
        'expect-text' { Assert-Text $a0 }
        'expect-no-text' { Assert-NoText $a0 }
        'wait-for' { Wait-For $a0 $(if ($a1) { [double]$a1 } else { 10 }) }
        'expect-alive' { Assert-Alive }
        'expect-no-crash' { Assert-NoCrash }
        'expect-playing' { Assert-Playing $(if ($a0) { [double]$a0 } else { 15 }) }
        'expect-query' { Assert-Query $a0 $a1 $(if ($t.Count -gt 2) { [double]$t[2] } else { 5 }) }
        'expect-prop' { Assert-Prop $a0 $a1 }
        'wait-ready' { Assert-Query $uiProbeUri 'ready=true' $(if ($a0) { [double]$a0 } else { 30 }) }
        'expect-focus-key' {
            if (-not $a0) { throw 'expect-focus-key <key> [sec]' }
            Assert-Query $uiProbeUri ('focus=' + [regex]::Escape($a0) + ',') $(if ($a1) { [double]$a1 } else { 2 })
        }
        { $_ -in 'expect-ui', 'wait-ui' } {
            if ($a0 -notmatch '^(\w+)=(.+)$') { throw "$cmd <field>=<regex> [sec]" }
            # whole value of one field: the row is `Row: 0 route=..., rail=..., ...`
            $re = '(?m)(?:^Row: \d+ |, )' + $Matches[1] + '=(?:' + $Matches[2] + ')(?=,|$)'
            Assert-Query $uiProbeUri $re $(if ($a1) { [double]$a1 } elseif ($cmd -eq 'wait-ui') { 15 } else { 2 })
        }
        'tap-id' {
            if (-not $a0) { throw 'tap-id <key>' }
            $c = Find-NodeById $a0
            if (-not $c) { Fail "tap-id ${a0}: no node with this resource-id (debug build? probe on the element?)" }
            Send-Tap $c[0] $c[1]; Pass "tap-id $a0 -> $($c[0]),$($c[1])"
        }
        default {
            $hdr = @(); foreach ($l in (Get-Content $PSCommandPath -Encoding UTF8 | Select-Object -Skip 1)) { if ($l -eq '#>') { break }; $hdr += $l }
            $hdr -join "`n" | Write-Output
        }
    }
}

function Split-Line([string]$line) {
    $tok = @()
    foreach ($m in [regex]::Matches($line, '"([^"]*)"|(\S+)')) {
        $tok += $(if ($m.Groups[1].Success) { $m.Groups[1].Value } else { $m.Groups[2].Value })
    }
    , $tok
}

# command -> minimum number of arguments (for -DryRun validation)
$knownCommands = @{
    'devices' = 0; 'emu' = 0; 'build' = 0; 'install' = 0; 'install-home' = 0; 'home-tour' = 0; 'home-grant' = 0; 'home-listings' = 0; 'home-shot' = 0; 'start-home' = 0; 'stop-home' = 0; 'install-release' = 0; 'start-release' = 0; 'start' = 0; 'stop' = 0; 'restart' = 0; 'reset-app' = 0; 'start-time' = 0
    'keys' = 1; 'tap' = 2; 'tap-text' = 1; 'swipe' = 4; 'text' = 1; 'wait' = 1; 'shot' = 0; 'focus' = 0; 'log' = 0; 'deeplink' = 1; 'proxy' = 1; 'adb' = 1
    'expect-focus' = 1; 'expect-text' = 1; 'expect-no-text' = 1; 'wait-for' = 1; 'expect-alive' = 0; 'expect-no-crash' = 0; 'expect-playing' = 0
    'expect-query' = 2; 'expect-prop' = 2
    'wait-ready' = 0; 'expect-focus-key' = 1; 'tap-id' = 1; 'expect-ui' = 1; 'wait-ui' = 1
}
$uiProbeUri = 'content://app.bluebox.tv.debug.state/ui'   # UI probes table (debug only): route, rail, focus, sheet, overlay, ready, rows, screen, items
$script:QueryFileOff = [bool]$env:BLUEBOX_NO_QUERY_FILE
$script:ProbeFileOff = [bool]$env:BLUEBOX_NO_PROBE_FILE; $script:ProbeMiss = 0   # BLUEBOX_NO_PROBE_FILE=1: always content query (Read-UiProbeFile)
$script:ProbeAgeMs = -1; $probeSettleMs = if ($env:BLUEBOX_PROBE_SETTLE_MS) { [int]$env:BLUEBOX_PROBE_SETTLE_MS } else { 400 }   # Assert-Query
# Texts the app shows when the catalog / source is unreachable (strings.xml, strings_d4.xml): seen at a failure = class NET.
$netErrorRe = '^Нет сети$|Каталог не ответил|Нет связи с источником|Произведение не найдено в каталоге'

# Every command that talks to an emulator holds its lease first (auto -> a concrete serial). BUSY = exit 3, nothing sent.
$cmdLower = $Command.ToLower()
if ($cmdLower -eq 'lease') { $cmdLower = 'leases' }
$needsLease = $isEmulator -and -not $DryRun -and $cmdLower -notin @('devices', 'build', 'leases', 'release') -and
    ($knownCommands.ContainsKey($cmdLower) -or $cmdLower -in @('scenario', 'scenarios', 'acquire'))
if ($needsLease) {
    $leaseCmd = if ($cmdLower -eq 'scenario') { "scenario $([IO.Path]::GetFileNameWithoutExtension("$Arg"))" } elseif ($cmdLower -eq 'scenarios') { "scenarios $Arg" } else { $cmdLower }
    try { $serial = Acquire-Lease $(if ($Device -eq 'auto') { 'auto' } else { $serial }) $leaseCmd }
    catch {
        $m = $_.Exception.Message; Write-Output $m
        exit $(if ($m -like 'BUSY:*') { 3 } elseif ($m -like 'ПРОБЛЕМА ОКРУЖЕНИЯ*') { 2 } else { 1 })
    }
    # scenario(s) on auto take a free STOPPED pool emulator rather than wait -WaitLease on a busy one (2026-10-21: a pack
    # waited 30 min on 5554 while 5556 was free but stopped) - boot it now.
    if ($Device -eq 'auto' -and $cmdLower -ne 'emu' -and (Get-RunningEmulators) -notcontains $serial) {
        try { Write-Output (Ensure-Emulator) } catch { Write-Output "ПРОБЛЕМА ОКРУЖЕНИЯ: $($_.Exception.Message)"; exit 2 }
    }
    $stateFile = Get-StateFile
} elseif ($isEmulator -and $Device -eq 'auto' -and $cmdLower -notin @('devices', 'build', 'leases', 'release') -and -not $DryRun) {
    $cmdLower = 'help'   # unknown command: print help, never touch an unresolved device
}

function Format-Timing([double]$total) {
    $t = $script:Tm
    'TIMING: total {0:N1}s | dumps {1:N1}s ({2} dumps, {3} cached) | app tree {12:N1}s ({13} reads, {14} no answer, {15} dump rescues) | probes {8:N1}s ({9} queries; {10} via file, {11:N1}s) | wait {4:N1}s | start {5:N1}s | keys {6:N1}s | other {7:N1}s' -f
        $total, $t.dump, $t.dumps, $t.hits, $t.wait, $t.start, $t.keys, $t.other, $t.query, $t.queries, $t.fileQueries, $t.fileQuery, $t.tree, $t.trees, $t.treeMiss, $t.rescue
}

# Runs one scenario file; prints the usual lines; the outcome is left in $script:ScenResult (Name, Exit 0|1|3, Result, Class, Secs).
function Run-Scenario([string]$file) {
    $script:Tm = @{ dump = 0.0; dumps = 0; hits = 0; query = 0.0; queries = 0; fileQueries = 0; fileQuery = 0.0; tree = 0.0; trees = 0; treeMiss = 0; rescue = 0; wait = 0.0; start = 0.0; keys = 0.0; other = 0.0 }
    $script:ProbeFileOff = [bool]$env:BLUEBOX_NO_PROBE_FILE; $script:ProbeMiss = 0   # the fast ui-probe file is tried again in each scenario
    $script:QueryFileOff = [bool]$env:BLUEBOX_NO_QUERY_FILE; $script:TreeOff = [bool]$env:BLUEBOX_NO_UITREE; $script:TreeEmpty = 0; $script:HealthAt = [datetime]::MinValue; $script:ForeignSeen = 0; $script:SettleUntil = $script:InputSettleUntil = [datetime]::MinValue
    $totalSw = [Diagnostics.Stopwatch]::StartNew()
    $step = 0; $failed = $false; $firstFail = $null; $badLines = 0; $lostLease = $false
    $scenarioName = [IO.Path]::GetFileNameWithoutExtension("$file")
    $res = [pscustomobject]@{ Name = $scenarioName; Exit = 0; Result = 'PASS'; Class = ''; Secs = 0.0; Net = $false }
    $script:ScenResult = $res
    if (-not $file -or -not (Test-Path $file)) { Write-Output "FAIL: scenario file not found: $file"; $res.Exit = 1; $res.Result = 'FAIL'; return }
    $leaseSw = [Diagnostics.Stopwatch]::StartNew()
    $script:UiCache = $null; $script:UiCacheOn = -not $env:BLUEBOX_NO_DUMP_CACHE
    foreach ($line in Get-Content $file -Encoding UTF8) {
        $line = $line.Trim(); if (-not $line -or $line.StartsWith('#')) { continue }
        $step++
        $tok = Split-Line $line
        $always = $false
        if ($tok[0] -ieq 'always' -and $tok.Count -gt 1) { $always = $true; $tok = @($tok[1..($tok.Count - 1)]) }
        $name = $tok[0].ToLower()
        $rest = @(if ($tok.Count -gt 1) { $tok[1..($tok.Count - 1)] })   # @() keeps a single argument an array ($rest[0] was its first CHARACTER)
        if ($DryRun) {
            $ok = 'ok '
            if (-not $knownCommands.ContainsKey($name)) { $ok = 'UNKNOWN '; $badLines++ }
            elseif ($rest.Count -lt $knownCommands[$name]) { $ok = 'BAD-ARGS '; $badLines++ }
            elseif ($name -eq 'proxy' -and $rest[0] -notin @('on', 'off')) { $ok = 'BAD-ARGS '; $badLines++ }
            elseif ($name -eq 'start-time' -and $rest.Count -gt 0 -and $rest[0] -notmatch '^\d+$') { $ok = 'BAD-ARGS '; $badLines++ }
            elseif ($name -in @('expect-query', 'expect-focus', 'expect-text', 'expect-no-text', 'wait-for', 'expect-prop')) {
                $re = if ($name -in @('expect-query', 'expect-prop')) { $rest[1] } else { $rest[0] }
                try { [void][regex]::new($re) } catch { $ok = 'BAD-REGEX '; $badLines++ }
            }
            Write-Output ($ok + $(if ($always) { 'always ' } else { '' }) + $name + ' | ' + ($rest -join ' | ')); continue
        }
        if ($failed -and -not $always) { continue }   # after a failure only `always` lines (cleanup) run
        if ($leaseSw.Elapsed.TotalSeconds -ge 60) {   # keep the lease alive through a long scenario
            $r = Try-Lease $serial $leaseCmd $true
            if (-not $r.Serial) { Write-Output "FAIL: аренда $serial перехвачена ($($r.Busy -join '; ')) — эмулятор больше не наш"; $failed = $true; $lostLease = $true; break }
            $leaseSw.Restart()
        }
        try { Invoke-One $name $rest }
        catch {
            $msg = $_.Exception.Message
            $tag = if ($failed) { 'FAIL(always)' } else { 'FAIL' }
            if ($_.Exception -is [AssertFail]) { Write-Output "${tag}: $msg  (step ${step}: $line)" }
            elseif ($msg -eq 'refused') { Write-Output "${tag}: step ${step}: $line (refused)" }
            else { Write-Output "${tag}: step ${step}: $line -- $msg" }
            if (-not $failed) {
                $firstFail = [pscustomobject]@{ Step = $step; Msg = $msg; IsAssert = ($_.Exception -is [AssertFail]) }
                try { Write-Output (Take-Shot 'fail') } catch { }
                # the screen at the failure says "no network": an environment failure, not a code one (batch stops after two in a row)
                try { $null = Invoke-One 'expect-text' @($netErrorRe); $res.Net = $true } catch { }
            }
            $failed = $true
        }
    }
    $script:UiCacheOn = $false; $script:UiCache = $null
    $res.Secs = [math]::Round($totalSw.Elapsed.TotalSeconds, 1)
    if ($DryRun) {
        if ($badLines) { Write-Output "DRYRUN FAIL: $badLines bad line(s)"; $res.Exit = 1; $res.Result = 'FAIL' } else { Write-Output "DRYRUN OK: $step step(s)" }
        return
    }
    Write-Output (Format-Timing $totalSw.Elapsed.TotalSeconds)
    if ($lostLease) {
        Write-Output 'SCENARIO FAIL'; Write-Output 'FAIL-CLASS: BUSY — not an app failure: the emulator was taken by another session'
        $res.Exit = 3; $res.Result = 'FAIL'; $res.Class = 'BUSY'
    } elseif ($failed) {
        Write-Output 'SCENARIO FAIL'
        $res.Exit = 1; $res.Result = 'FAIL'; $res.Class = ''
        if ($res.Net) {
            $res.Class = 'NET'
            Write-Output 'NET: на экране ошибка сети (каталог не ответил) — сбой среды, не кода: проверь сеть эмулятора (`tv.ps1 proxy on`), код не трогай'
        }
    } else {
        Write-Output 'SCENARIO PASS'
    }
}

# scenarios <name|@pack>...: names (without .txt) and/or packs from scripts/scenarios/packs.json ("@d1"; a pack may include "@other").
# Virtual packs: @all = every scenario except packs.json "_manual" (needs a stub, beta or live HDRezka);
# @changed = scenarios for the files changed against origin/main (+ uncommitted), by packs.json "_paths" (prefix -> list;
# unmapped code -> @core, docs only -> nothing); @failed = what failed or was not run in the last batch of this worktree.
# -Skip '<names/packs>' leaves those out.
$lastBatchFile = Join-Path $repo 'build\receipts\last-batch.json'
function Get-ChangedFiles {
    $base = (& git -C $repo merge-base HEAD origin/main 2>$null | Out-String).Trim()
    $f = @()
    if ($base) { $f += @(& git -C $repo diff --name-only $base HEAD 2>$null) }
    $f += @(& git -C $repo diff --name-only HEAD 2>$null)
    $f += @(& git -C $repo ls-files --others --exclude-standard 2>$null)
    @($f | Where-Object { $_ } | Sort-Object -Unique)
}
function Resolve-ScenarioList([string[]]$items) {
    $dir = Join-Path $repo 'scripts\scenarios'
    $packs = $null; $pf = Join-Path $dir 'packs.json'
    if (Test-Path $pf) { $packs = Get-Content $pf -Raw -Encoding UTF8 | ConvertFrom-Json }
    $out = New-Object System.Collections.Generic.List[string]
    $expand = $null
    $expand = { param($it, $depth)
        if ($depth -gt 5) { throw "pack nesting too deep at $it" }
        if ($it -eq '@all') {
            $manual = @($packs._manual)
            foreach ($t in (Get-ChildItem $dir -Filter '*.txt' | Sort-Object Name)) { if ($t.BaseName -notin $manual) { & $expand $t.BaseName ($depth + 1) } }
        } elseif ($it -eq '@failed') {
            if (-not (Test-Path $lastBatchFile)) { throw "@failed: no last batch ($lastBatchFile)" }
            $lb = Get-Content $lastBatchFile -Raw -Encoding UTF8 | ConvertFrom-Json
            foreach ($r in @($lb.rows)) { if ($r.result -ne 'PASS') { & $expand $r.name ($depth + 1) } }
        } elseif ($it -eq '@changed') {
            $map = $packs._paths
            foreach ($c in (Get-ChangedFiles)) {
                if ($c -match '^scripts/scenarios/([\w-]+)\.txt$') { if ($Matches[1] -notin @($packs._manual)) { & $expand $Matches[1] ($depth + 1) }; continue }
                $hit = $false
                foreach ($p in $map.PSObject.Properties) { if ($c.StartsWith($p.Name)) { $hit = $true; foreach ($x in $p.Value) { & $expand $x ($depth + 1) } } }
                if (-not $hit -and $c -match '\.(kt|kts|xml|json|toml)$' -and $c -notmatch '^(docs|scripts)/') { & $expand '@core' ($depth + 1) }
            }
        } elseif ($it.StartsWith('@')) {
            $p = $packs.PSObject.Properties[$it.Substring(1)]
            if (-not $p -or $it.StartsWith('@_')) { throw "unknown pack $it (packs.json: $(($packs.PSObject.Properties.Name | Where-Object { -not $_.StartsWith('_') }) -join ', '), all, changed, failed)" }
            foreach ($x in $p.Value) { & $expand $x ($depth + 1) }
        } else {
            $path = if (Test-Path $it) { $it } else { Join-Path $dir (($it -replace '\.txt$', '' -replace '^.*[\/]', '') + '.txt') }
            if (-not (Test-Path $path)) { throw "scenario not found: $it" }
            if (-not $out.Contains($path)) { $out.Add($path) }
        }
    }
    foreach ($i in $items) { foreach ($s in ($i -split '[,\s]+' | Where-Object { $_ })) { & $expand $s 0 } }
    , @($out)
}

$exitCode = 0
if ($Command -eq 'scenarios') {
    $files = $null
    try {
        $files = Resolve-ScenarioList @(@($Arg, $Arg2, $Arg3) + @($More) | Where-Object { $_ })
        if ($Skip) { $skipSet = Resolve-ScenarioList @($Skip); $files = @($files | Where-Object { $_ -notin $skipSet }) }
    }
    catch { Write-Output ("FAIL: " + $_.Exception.Message); exit 1 }
    if (-not $files -or $files.Count -eq 0) { Write-Output 'NOTHING TO RUN: the list is empty (scenarios <name|@pack|@all|@changed|@failed>... [-Skip ...])'; exit 0 }
    Write-Output ("batch ($($files.Count)): " + (($files | ForEach-Object { [IO.Path]::GetFileNameWithoutExtension($_) }) -join ' '))
    $rows = @(); $batchSw = [Diagnostics.Stopwatch]::StartNew(); $netRun = 0; $stopWhy = $null
    if (-not $DryRun) {
        try { Invoke-One 'install' @() } catch { Write-Output "install: $($_.Exception.Message) (continuing with what is on the emulator)" }
    }
    try {
        Disable-Anim
        foreach ($f in $files) {
            Write-Output "=== $([IO.Path]::GetFileNameWithoutExtension($f))"
            Run-Scenario $f
            $rows += $script:ScenResult
            if ($script:ScenResult.Exit -eq 3) { $stopWhy = 'emulator lease lost'; break }
            $netRun = if ($script:ScenResult.Net) { $netRun + 1 } else { 0 }
            if ($netRun -ge 2) { $stopWhy = 'two NET failures in a row: the emulator has no catalog network (`tv.ps1 proxy on`?); rerun with @failed'; break }
        }
    } finally { Restore-Anim }
    $done = @($rows | ForEach-Object { $_.Name })
    foreach ($f in $files) {
        $n = [IO.Path]::GetFileNameWithoutExtension($f)
        if ($n -notin $done) { $rows += [pscustomobject]@{ Name = $n; Exit = 1; Result = 'NOTRUN'; Class = ''; Secs = 0.0; Net = $false } }
    }
    if ($stopWhy) { Write-Output "BATCH STOP: $stopWhy" }
    $w = ($rows | ForEach-Object { $_.Name.Length } | Measure-Object -Maximum).Maximum
    Write-Output (('{0,-' + $w + '} | result | secs   | class') -f 'scenario')
    foreach ($r in $rows) { Write-Output (('{0,-' + $w + '} | {1,-6} | {2,6:N1} | {3}') -f $r.Name, $r.Result, $r.Secs, $r.Class) }
    Write-Output ('BATCH {0}: {1}/{2} passed, {3:N0}s' -f $(if ($rows | Where-Object { $_.Exit -ne 0 }) { 'FAIL' } else { 'PASS' }), @($rows | Where-Object { $_.Exit -eq 0 }).Count, $files.Count, $batchSw.Elapsed.TotalSeconds)
    if (-not $DryRun) {
        try {
            New-Item -ItemType Directory -Force (Split-Path $lastBatchFile) | Out-Null
            $lb = @{ utc = (Get-Date).ToUniversalTime().ToString('o'); rows = @($rows | ForEach-Object { @{ name = $_.Name; result = $_.Result; class = $_.Class; secs = $_.Secs } }) }
            [IO.File]::WriteAllText($lastBatchFile, ($lb | ConvertTo-Json -Depth 4 -Compress), (New-Object Text.UTF8Encoding($false)))
        } catch { }
    }
    $exitCode = if ($rows | Where-Object { $_.Exit -eq 3 }) { 3 } elseif ($rows | Where-Object { $_.Exit -ne 0 }) { 1 } else { 0 }
} elseif ($Command -eq 'scenario') {
    # A freshly booted pool emulator has no app yet: install it instead of failing at the first reset-app/start.
    if (-not $DryRun -and -not ((AdbShell "pm path $pkg" | Out-String) -match 'package:')) {
        try { Invoke-One 'install' @() } catch { Write-Output "install: $($_.Exception.Message)" }
    }
    try { Disable-Anim; Run-Scenario $Arg } finally { Restore-Anim }
    $exitCode = $script:ScenResult.Exit
} else {
    try { Invoke-One $cmdLower @(@($Arg, $Arg2, $Arg3) + @($More) | Where-Object { $_ }) }
    catch {
        $msg = $_.Exception.Message
        if ($msg -ne 'refused') { Write-Output ("FAIL: " + $msg) }
        $exitCode = if ($msg -like 'ПРОБЛЕМА ОКРУЖЕНИЯ*') { 2 } else { 1 }   # 2 = environment problem (emu-check relies on it)
    }
}
exit $exitCode
