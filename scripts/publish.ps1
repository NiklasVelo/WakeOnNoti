<#
.SYNOPSIS
    Build, sign and publish a WakeOnNoti release.

.DESCRIPTION
    1. Optionally bumps VERSION (-Bump patch|minor|major, or -Version x.y.z).
    2. Builds the signed release APK (key from %APPDATA%\Secrets\WakeOnNoti\.env).
    3. Commits it as release/WakeOnNoti.apk - the always-current download link -
       pushes, and creates the GitHub release vX.Y.Z with the APK attached.
    4. Only then writes release/version.json and pushes it. That manifest is
       what installed apps poll (Updater.kt), so it goes live last: no app can
       ever be offered a version whose APK is not downloadable yet.

    Refuses to run without the release key: a debug-signed APK cannot update
    existing installs, and users would have to uninstall - losing their rules.

.EXAMPLE
    powershell -File scripts\publish.ps1 -Bump patch -Notes "Fixes the cooldown field"
#>
param(
    [ValidateSet("none", "patch", "minor", "major")][string]$Bump = "none",
    [string]$Version,
    [string]$Notes = "",
    [switch]$NoPush
)
$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent
Set-Location $repo
$utf8 = New-Object System.Text.UTF8Encoding($false)  # no BOM: org.json rejects one

function Run([string]$exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "$exe $($argv -join ' ') failed ($LASTEXITCODE)" }
}

# --- preconditions
$envFile = Join-Path $env:APPDATA "Secrets\WakeOnNoti\.env"
if (-not (Test-Path $envFile) -or -not (Select-String -Path $envFile -Pattern "^WAKEONNOTI_KEYSTORE=" -Quiet)) {
    throw "No release key in $envFile - see README 'Building'."
}
$dirty = & git status --porcelain | Where-Object { $_ -notmatch ' VERSION$' }
if ($dirty) { throw "Commit or stash your changes first - the APK must match committed source:`n$($dirty -join "`n")" }
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "C:\Program Files\Microsoft\jdk-17*" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}

# --- version
$v = (Get-Content VERSION -Raw).Trim()
if ($Version) { $v = $Version }
elseif ($Bump -ne "none") {
    $p = $v.Split(".") | ForEach-Object { [int]$_ }
    switch ($Bump) {
        "major" { $p = @($p[0] + 1, 0, 0) }
        "minor" { $p = @($p[0], $p[1] + 1, 0) }
        "patch" { $p = @($p[0], $p[1], $p[2] + 1) }
    }
    $v = $p -join "."
}
if ($v -notmatch '^\d+\.\d+\.\d+$') { throw "version '$v' is not x.y.z" }
& git rev-parse -q --verify "refs/tags/v$v" *> $null
if ($LASTEXITCODE -eq 0) { throw "v$v is already released - bump the version" }
[IO.File]::WriteAllText((Join-Path $repo "VERSION"), "$v`n", $utf8)
Write-Host "== WakeOnNoti $v" -ForegroundColor Cyan

# --- build + verify the signature
Run ".\gradlew.bat" @(":app:assembleRelease", "--console=plain", "-q")
$built = "app\build\outputs\apk\release\app-release.apk"
$sdkLine = Get-Content local.properties | Where-Object { $_ -match '^sdk\.dir=' }
$sdk = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'
$apksigner = Get-ChildItem (Join-Path $sdk "build-tools") -Directory | Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "apksigner.bat" } | Where-Object { Test-Path $_ } | Select-Object -First 1
$certs = & $apksigner verify --print-certs $built
if ($LASTEXITCODE -ne 0 -or -not ($certs -match "CN=WakeOnNoti")) { throw "APK is not signed with the WakeOnNoti release key:`n$certs" }

New-Item -ItemType Directory -Force release | Out-Null
Copy-Item $built release\WakeOnNoti.apk -Force
$asset = "app\build\outputs\apk\release\WakeOnNoti-$v.apk"
Copy-Item $built $asset -Force
$sha = (Get-FileHash release\WakeOnNoti.apk -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item release\WakeOnNoti.apk).Length
Write-Host "   $([math]::Round($size / 1MB, 2)) MB  sha256 $sha"

if ($NoPush) { Write-Host "== built only (-NoPush): release\WakeOnNoti.apk" -ForegroundColor Yellow; exit 0 }

# --- 1: the APK, then the GitHub release that hosts it
Run git @("add", "VERSION", "release/WakeOnNoti.apk")
Run git @("commit", "-q", "-m", "Release $v")
Run git @("push", "-q")
if (-not $Notes) { $Notes = "WakeOnNoti $v" }
$body = "$Notes`n`nInstall: download **WakeOnNoti-$v.apk** below. Already installed? The app offers this update itself - your rules and settings are kept."
Run gh @("release", "create", "v$v", $asset, "--title", "WakeOnNoti $v", "--notes", $body, "--target", "main")

# --- 2: the manifest goes live last
$manifest = [ordered]@{
    version     = $v
    file        = "WakeOnNoti.apk"
    url         = "https://github.com/NiklasVelo/WakeOnNoti/releases/download/v$v/WakeOnNoti-$v.apk"
    sha256      = $sha
    size        = $size
    notes       = $Notes
    released_at = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
}
[IO.File]::WriteAllText((Join-Path $repo "release\version.json"), ($manifest | ConvertTo-Json) + "`n", $utf8)
Run git @("add", "release/version.json")
Run git @("commit", "-q", "-m", "Publish $v to the update channel")
Run git @("push", "-q")
Write-Host "== published $v - installed apps will offer it within a few hours (or on 'Check for updates')" -ForegroundColor Green
