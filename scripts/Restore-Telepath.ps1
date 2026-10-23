[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    # The TV's address (192.168.1.20 or 192.168.1.20:5555), or a serial from adb devices. Default: tv.address in
    # telepath.local.properties.
    [string]$Tv,
    [string]$Adb,
    [switch]$Apply
)

# Puts the TV's own home screen back: the original Home app, accessibility and screensaver settings saved by
# Activate-Telepath.ps1 (in backups/<TV serial>). Without that snapshot it returns to Google TV's home screen
# (or the classic Android TV one). telepath stays installed with its settings; uninstall it separately if you want.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'Telepath.psm1') -Force

$device = Connect-TelepathTv $Tv $Adb
$pkg = $device.Package
$service = "$pkg/dev.plume.HomeOverride"
$ourDream = "$pkg/dev.plume.GalleryDream"
$folder = Get-SnapshotFolder $device
$snapshot = Join-Path $folder.Path 'home-original.json'
$saverSnapshot = Join-Path $folder.Path 'screensaver-original.json'

$original = $null
if (Test-Path -LiteralPath $snapshot) {
    $original = Get-Content -LiteralPath $snapshot -Raw | ConvertFrom-Json
    if ($original.DeviceId -cne $folder.Serial) { throw "$snapshot belongs to another TV." }
    if ($original.OriginalHome -notmatch '^[A-Za-z0-9._]+/[A-Za-z0-9._$]+$') { throw "The snapshot's original Home ($($original.OriginalHome)) isn't valid." }
    $targetHome = $original.OriginalHome
    $targetRole = $original.OriginalHomeRole
    $wasGranted = if ($original.PSObject.Properties['SecureSettingsOriginallyGranted']) { $original.SecureSettingsOriginallyGranted }
        elseif ($original.PSObject.Properties['AsterSecurePermissionOriginallyGranted']) { $original.AsterSecurePermissionOriginallyGranted } else { $true }
} else {
    $fallbacks = @('com.google.android.apps.tv.launcherx/.home.HomeActivity', 'com.google.android.tvlauncher/.MainActivity')
    $targetHome = $fallbacks | Where-Object { Test-TvPackage $device ($_ -split '/')[0] } | Select-Object -First 1
    if (-not $targetHome) { throw "No snapshot in $($folder.Path), and neither Google TV's nor Android TV's own launcher is installed. Choose a Home app in the TV's settings instead." }
    $targetRole = ($targetHome -split '/')[0]
    $wasGranted = $true # unknown: leave the permission as it is
    Write-Output "No snapshot of the original settings in $($folder.Path): going back to $targetHome."
}

$currentHome = Invoke-Tv $device @('shell', 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
$currentRole = Invoke-Tv $device @('shell', 'cmd role get-role-holders --user 0 android.app.role.HOME')
if ($currentRole -cne $pkg -and $currentRole -cne $targetRole -and $currentHome -notmatch [regex]::Escape("$pkg/")) {
    throw "The Home app is now $currentRole, not telepath. Nothing to undo here; change it in the TV's settings if you want."
}
$services = @((Invoke-Tv $device @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')) -split ':' | Where-Object { $_ -and $_ -ne 'null' })
$remaining = @($services | Where-Object { $_ -ne $service })
$saver = $null
if (Test-Path -LiteralPath $saverSnapshot) {
    $saver = Get-Content -LiteralPath $saverSnapshot -Raw | ConvertFrom-Json
    if ($saver.serial -cne $folder.Serial) { throw "$saverSnapshot belongs to another TV." }
}
$currentSaver = Invoke-Tv $device @('shell', 'settings', 'get', 'secure', 'screensaver_components')
$saverIsOurs = ($currentSaver -split ',')[0] -eq $ourDream

Write-Output "Home to restore: $targetHome"
Write-Output "telepath's Home button service on now: $($remaining.Count -ne $services.Count). Other accessibility services stay as they are."
if ($saverIsOurs) {
    $was = if ($saver -and $null -ne $saver.secure.screensaver_components) { $saver.secure.screensaver_components } else { "the TV's default (Google TV ambient mode)" }
    Write-Output "Screensaver to restore: $was"
} else {
    Write-Output "Screensaver isn't telepath's ($currentSaver): left as it is."
}
Write-Output 'telepath stays installed, with its settings and photos.'
if (-not $Apply) {
    Write-Output 'PREVIEW ONLY. Add -Apply to restore (you are asked to confirm).'
    return
}
if (-not $PSCmdlet.ShouldProcess($folder.Serial, 'Restore the original home screen')) { return }

Invoke-Tv $device @('shell', 'am', 'force-stop', $pkg) | Out-Null
if ($remaining.Count -ne $services.Count) {
    if ($remaining.Count -gt 0) {
        Invoke-Tv $device @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ($remaining -join ':')) | Out-Null
    } else {
        Invoke-Tv $device @('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services') | Out-Null
        $enabled = if ($original) { $original.OriginalAccessibilityEnabled } else { '0' }
        if ($enabled -eq 'null') { Invoke-Tv $device @('shell', 'settings', 'delete', 'secure', 'accessibility_enabled') | Out-Null }
        else { Invoke-Tv $device @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', $enabled) | Out-Null }
    }
}
if ($wasGranted -eq $false) { Invoke-Tv $device @('shell', 'pm', 'revoke', $pkg, 'android.permission.WRITE_SECURE_SETTINGS') | Out-Null }
if ($saverIsOurs) {
    if (-not $saver -or $null -eq $saver.secure.screensaver_components) {
        Invoke-Tv $device @('shell', 'settings', 'delete', 'secure', 'screensaver_components') | Out-Null
    } else {
        Invoke-Tv $device @('shell', 'settings', 'put', 'secure', 'screensaver_components', $saver.secure.screensaver_components) | Out-Null
    }
    if ($saver) {
        foreach ($key in 'screensaver_enabled', 'screensaver_activate_on_sleep', 'screensaver_activate_on_dock') {
            $want = $saver.secure.$key
            if ($null -ne $want -and (Invoke-Tv $device @('shell', 'settings', 'get', 'secure', $key)) -ne $want) {
                Invoke-Tv $device @('shell', 'settings', 'put', 'secure', $key, $want) | Out-Null
            }
        }
    }
    $restoredSaver = Invoke-Tv $device @('shell', 'settings', 'get', 'secure', 'screensaver_components')
    if (($restoredSaver -split ',')[0] -eq $ourDream) { throw "The screensaver didn't change back: $restoredSaver" }
    Write-Output "Screensaver restored ($restoredSaver; null is the TV's default)."
}
Invoke-Tv $device @('shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $targetHome) | Write-Output
Invoke-Tv $device @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
$resolved = Invoke-Tv $device @('shell', 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
if ($resolved -notmatch [regex]::Escape($targetHome)) { throw "The original Home didn't come back: $resolved" }
$role = Invoke-Tv $device @('shell', 'cmd role get-role-holders --user 0 android.app.role.HOME')
if ($targetRole -and $role -cne $targetRole) { throw "The Home role didn't come back (it is $role)." }
if ((Invoke-Tv $device @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')) -match [regex]::Escape($service)) {
    throw "telepath's Home button service is still on."
}
Write-Output 'Done: the original home screen is back. telepath is still installed; nothing was erased.'
