# Dot-source: . scripts/env.ps1
# Sets JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME / GRADLE_RO_DEP_CACHE / PATH for the local toolchain.
# Toolchain location: $env:BLUEBOX_TOOLS (default D:\Ani\.gradle\codex-tools; only read).
# Gradle home (writable, OUTSIDE the repo and OUTSIDE the D:\Ani fork): $env:BLUEBOX_GRADLE_HOME (default D:\AI\.bluebox-gradle).
# Read-only dependency cache: $env:BLUEBOX_GRADLE_RO_CACHE (default D:\Ani\.gradle\caches) -> GRADLE_RO_DEP_CACHE.
# Environment problems THROW "ПРОБЛЕМА ОКРУЖЕНИЯ: ..." (never exit: this file is dot-sourced). Callers catch and exit 2.

$bbRoot = Split-Path -Parent $PSScriptRoot
$bbTools = if ($env:BLUEBOX_TOOLS) { $env:BLUEBOX_TOOLS } else { 'D:\Ani\.gradle\codex-tools' }

function Stop-EnvProblem([string]$msg) {
    throw "ПРОБЛЕМА ОКРУЖЕНИЯ: $msg"
}

$jdkFile = Join-Path $bbTools 'jdk-home.txt'
if (-not (Test-Path $jdkFile)) { Stop-EnvProblem "нет $jdkFile (задайте BLUEBOX_TOOLS)" }
$env:JAVA_HOME = (Get-Content $jdkFile -Raw).Trim()
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { Stop-EnvProblem "JDK не найден: $env:JAVA_HOME" }

$env:ANDROID_HOME = Join-Path $bbTools 'android-sdk'
if (-not (Test-Path (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'))) { Stop-EnvProblem "Android SDK не найден: $env:ANDROID_HOME" }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $bbTools 'android-user-home'
$env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'

$env:GRADLE_USER_HOME = if ($env:BLUEBOX_GRADLE_HOME) { $env:BLUEBOX_GRADLE_HOME } else { 'D:\AI\.bluebox-gradle' }
if (-not (Test-Path $env:GRADLE_USER_HOME)) {
    try { New-Item -ItemType Directory -Force $env:GRADLE_USER_HOME -ErrorAction Stop | Out-Null }
    catch { Stop-EnvProblem "не удалось создать каталог Gradle: $env:GRADLE_USER_HOME ($($_.Exception.Message))" }
}
# Read-only dependency cache (the fork's Gradle cache): Gradle only reads it, never writes.
$roCache = if ($env:BLUEBOX_GRADLE_RO_CACHE) { $env:BLUEBOX_GRADLE_RO_CACHE } else { 'D:\Ani\.gradle\caches' }
if (Test-Path (Join-Path $roCache 'modules-2')) { $env:GRADLE_RO_DEP_CACHE = $roCache } else { Remove-Item Env:\GRADLE_RO_DEP_CACHE -ErrorAction SilentlyContinue }

# Java ignores HTTP(S)_PROXY env vars: hand a local proxy to Gradle/wrapper once via <gradle home>/gradle.properties.
$bbGradleProps = Join-Path $env:GRADLE_USER_HOME 'gradle.properties'
$bbProxy = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { $env:HTTP_PROXY }
if ($bbProxy -and $bbProxy -match '^(?:https?://)?(127\.0\.0\.1|localhost):(\d+)/?$' -and -not (Test-Path $bbGradleProps)) {
    $h = $Matches[1]; $p = $Matches[2]
    $lines = @("systemProp.http.proxyHost=$h", "systemProp.http.proxyPort=$p", "systemProp.https.proxyHost=$h", "systemProp.https.proxyPort=$p",
        'systemProp.http.nonProxyHosts=localhost|127.0.0.1', 'systemProp.https.nonProxyHosts=localhost|127.0.0.1')
    [System.IO.File]::WriteAllLines($bbGradleProps, $lines, (New-Object System.Text.UTF8Encoding($false)))
}

# Worktree bootstrap: local.properties is git-ignored, so a fresh worktree has no TMDB key (network scenarios then fail in a confusing way).
# Copy it from the main checkout, and again whenever the main one is newer (a key added there after the worktree was made
# must reach the build: a stale copy once shipped a beta without the AniList client id); values are never printed.
$bbLocalProps = Join-Path $bbRoot 'local.properties'
if ($bbRoot -match '^(.*?)[\\/]\.claude[\\/]worktrees[\\/]') {
    $bbMainProps = Join-Path $Matches[1] 'local.properties'
    if ((Test-Path $bbMainProps) -and (-not (Test-Path $bbLocalProps) -or (Get-Item $bbMainProps).LastWriteTimeUtc -gt (Get-Item $bbLocalProps).LastWriteTimeUtc)) {
        Copy-Item $bbMainProps $bbLocalProps; [Console]::Error.WriteLine('env.ps1: local.properties copied from the main checkout')
    }
}

$bbPathAdd = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator"
if ($env:PATH -notlike "$bbPathAdd*") { $env:PATH = "$bbPathAdd;$env:PATH" }
