[CmdletBinding()]
param(
    # The TV's address (192.168.1.20 or 192.168.1.20:5555), or a serial from adb devices. Default: tv.address in
    # telepath.local.properties.
    [string]$Tv,
    [string]$Adb,
    # The APK to install. Default: the newest build/telepath-*.apk from Build-Telepath.ps1.
    [string]$Apk,
    # After installing, make telepath the home screen (runs Activate-Telepath.ps1 -Apply).
    [switch]$Activate
)

# Installs (or updates) telepath on the TV over adb. Updating keeps its settings and photos.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'Telepath.psm1') -Force

if (-not $Apk) {
    $Apk = Get-ChildItem -Path (Join-Path (Get-TelepathRoot) 'build') -Filter 'telepath-*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $Apk) { throw 'No APK to install. Build one with .\scripts\Build-Telepath.ps1, or pass -Apk <file>.' }
}
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }

$device = Connect-TelepathTv $Tv $Adb
Write-Output "Installing $(Split-Path -Leaf $Apk) on $($device.Serial)..."
# adb reports a failed install on stderr; Windows PowerShell 5.1 would stop at that before the advice below.
$ErrorActionPreference = 'Continue'
$said = (& $device.Adb -s $device.Serial install -r $Apk 2>&1 | ForEach-Object { "$_" } | Out-String).Trim()
$ErrorActionPreference = 'Stop'
if ($said -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
    throw "This TV has a telepath build ($($device.Package)) signed with another key, so it can't be updated in place. Either uninstall it first (adb uninstall $($device.Package); its settings are lost), or give your build its own app.id in telepath.local.properties."
}
if ($said -notmatch '(?m)^Success') { throw "The install failed: $said" }
Write-Output 'Installed.'
if ($Activate) {
    # -Activate is the go-ahead, so Activate doesn't ask again.
    & (Join-Path $PSScriptRoot 'Activate-Telepath.ps1') -Tv $device.Serial -Adb $device.Adb -Apply -Confirm:$false
} else {
    Write-Output 'Next: open telepath on the TV and follow its setup, or run .\scripts\Activate-Telepath.ps1'
}
