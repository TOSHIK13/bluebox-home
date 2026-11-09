# Cuts a release: checks the tree, runs the gate, tags vX.Y.Z and (only with -Push) pushes the tag; CI builds and publishes.
#   powershell -File scripts/release.ps1 -Bump patch            # dry run: prints the next tag, changes nothing
#   powershell -File scripts/release.ps1 -Bump minor -Tag       # verify + create the local tag
#   powershell -File scripts/release.ps1 -Version 0.3.0-beta.1 -Tag -Push
param(
    [ValidateSet('patch', 'minor', 'major')][string]$Bump = 'patch',
    [string]$Version,
    [switch]$Tag,
    [switch]$Push
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

if (-not $Version) {
    $last = git tag --list 'v*' --sort=-v:refname | Where-Object { $_ -match '^v\d+\.\d+\.\d+$' } | Select-Object -First 1
    $cur = if ($last) { $last.TrimStart('v') } else { '0.1.0' }
    $p = $cur.Split('.') | ForEach-Object { [int]$_ }
    switch ($Bump) {
        'major' { $p = @(($p[0] + 1), 0, 0) }
        'minor' { $p = @($p[0], ($p[1] + 1), 0) }
        'patch' { $p = @($p[0], $p[1], ($p[2] + 1)) }
    }
    $Version = $p -join '.'
}
if ($Version -notmatch '^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$') { Write-Host "Плохая версия: $Version"; exit 1 }
$base = ($Version -split '-')[0].Split('.') | ForEach-Object { [int]$_ }
$code = $base[0] * 10000 + $base[1] * 100 + $base[2]
Write-Host "Релиз v$Version (versionCode $code)"
if (-not $Tag) { Write-Host 'Сухой прогон: добавьте -Tag, чтобы проверить и поставить тег.'; exit 0 }

if (git status --porcelain) { Write-Host 'Рабочее дерево не чистое: закоммитьте изменения.'; exit 1 }
if (git tag --list "v$Version") { Write-Host "Тег v$Version уже есть."; exit 1 }
& powershell -NoProfile -File (Join-Path $PSScriptRoot 'verify.ps1')
if ($LASTEXITCODE -ne 0) { Write-Host 'verify не прошёл, тег не поставлен.'; exit 1 }
git tag -a "v$Version" -m "Blue Box Home $Version"
Write-Host "Тег v$Version создан локально."
if ($Push) { git push origin "v$Version"; Write-Host 'Тег отправлен, дальше собирает CI.' } else { Write-Host "Отправить: git push origin v$Version" }
