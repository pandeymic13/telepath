[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    # The TV's address (192.168.1.20 or 192.168.1.20:5555), or a serial from adb devices. Default: tv.address in
    # telepath.local.properties.
    [string]$Tv,
    [string]$Adb,
    [switch]$Apply
)

# Makes telepath the TV's home screen: the default Home app, its Home button service, the one-time permission it
# needs, and its screensaver with your photos (to keep another screensaver, choose it in Home settings > Screensaver).
# Preview by default; -Apply asks first. Before the first change it saves the TV's original settings in
# backups/<TV serial>, for Restore-Telepath.ps1. Nothing is disabled or uninstalled: the TV's own launcher stays
# installed and enabled.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'Telepath.psm1') -Force

$device = Connect-TelepathTv $Tv $Adb
$pkg = $device.Package
$homeActivity = "$pkg/dev.plume.MainActivity"
$service = "$pkg/dev.plume.HomeOverride"
$dream = "$pkg/dev.plume.GalleryDream"
if (-not (Test-TvPackage $device $pkg)) {
    throw "telepath ($pkg) isn't installed on this TV. Run Install-Telepath.ps1 first."
}

$folder = Get-SnapshotFolder $device
$snapshot = Join-Path $folder.Path 'home-original.json'
$saverSnapshot = Join-Path $folder.Path 'screensaver-original.json'
$resolved = Invoke-Tv $device @('shell', 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
$role = Invoke-Tv $device @('shell', 'cmd role get-role-holders --user 0 android.app.role.HOME')
$current = Invoke-Tv $device @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
$services = @($current -split ':' | Where-Object { $_ -and $_ -ne 'null' })
$granted = (Invoke-Tv $device @('shell', 'dumpsys', 'package', $pkg)) -match 'WRITE_SECURE_SETTINGS: granted=true'
$currentSaver = Invoke-Tv $device @('shell', 'settings', 'get', 'secure', 'screensaver_components')

if (Test-Path -LiteralPath $snapshot) {
    $original = Get-Content -LiteralPath $snapshot -Raw | ConvertFrom-Json
    if ($original.DeviceId -cne $folder.Serial) { throw "$snapshot belongs to another TV." }
    Write-Output "Original settings already saved: $snapshot"
} elseif ($role -ceq $pkg -or $resolved -match [regex]::Escape("$pkg/")) {
    throw "telepath is already this TV's Home app, and there's no snapshot of the original settings in $($folder.Path). Restore-Telepath.ps1 can still go back to Google TV's own home screen."
} else {
    $line = ($resolved -split "`n" | Where-Object { $_ -match '^[A-Za-z0-9._]+/[A-Za-z0-9._$]+$' } | Select-Object -Last 1)
    if (-not $line) { throw "Couldn't tell which app is the TV's Home now: $resolved" }
    $original = [ordered]@{
        Version = 1
        CapturedAt = (Get-Date).ToString('o')
        DeviceId = $folder.Serial
        Model = Invoke-Tv $device @('shell', 'getprop', 'ro.product.model')
        OriginalHome = $line.Trim()
        OriginalHomeRole = $role
        OriginalAccessibilityServices = $current
        OriginalAccessibilityEnabled = Invoke-Tv $device @('shell', 'settings', 'get', 'secure', 'accessibility_enabled')
        SecureSettingsOriginallyGranted = [bool]$granted
    }
    Write-Output "Original Home: $($original.OriginalHome) (saved to $snapshot when you apply)"
}

Write-Output "Default Home: $homeActivity"
Write-Output "Home button service: $service (the $($services.Count) accessibility services already on stay on)"
Write-Output "One-time permission: android.permission.WRITE_SECURE_SETTINGS (now granted: $([bool]$granted))"
Write-Output "Screensaver: telepath's photo slideshow (now: $currentSaver)"
Write-Output 'Nothing is disabled or uninstalled. Undo with Restore-Telepath.ps1.'
if (-not $Apply) {
    Write-Output 'PREVIEW ONLY. Add -Apply to make these changes (you are asked to confirm).'
    return
}
if (-not $PSCmdlet.ShouldProcess($folder.Serial, 'Make telepath the home screen')) { return }

New-Item -ItemType Directory -Force -Path $folder.Path | Out-Null
if (-not (Test-Path -LiteralPath $snapshot)) { $original | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $snapshot -Encoding utf8 }
# Saved before the permission is granted: with it, telepath takes over the screensaver as soon as it opens.
if (-not (Test-Path -LiteralPath $saverSnapshot) -and ($currentSaver -split ',')[0] -ne $dream) {
    $saver = [ordered]@{
        time = (Get-Date).ToString('o'); serial = $folder.Serial
        note = 'Screensaver settings before telepath took over the screensaver.'
        secure = [ordered]@{}; system = [ordered]@{ screen_off_timeout = Invoke-Tv $device @('shell', 'settings', 'get', 'system', 'screen_off_timeout') }
    }
    foreach ($key in 'screensaver_components', 'screensaver_enabled', 'screensaver_activate_on_sleep', 'screensaver_activate_on_dock') {
        $value = Invoke-Tv $device @('shell', 'settings', 'get', 'secure', $key)
        $saver.secure[$key] = if ($value -eq 'null') { $null } else { $value }
    }
    $saver | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $saverSnapshot -Encoding utf8
}
if (($currentSaver -split ',')[0] -ne $dream) {
    Invoke-Tv $device @('shell', 'settings', 'put', 'secure', 'screensaver_components', $dream) | Out-Null
    if ((Invoke-Tv $device @('shell', 'settings', 'get', 'secure', 'screensaver_components')) -ne $dream) { throw 'The screensaver did not switch to telepath.' }
    Write-Output 'Screensaver set to your photo slideshow.'
}
if (-not $granted) { Invoke-Tv $device @('shell', 'pm', 'grant', $pkg, 'android.permission.WRITE_SECURE_SETTINGS') | Out-Null }
Invoke-Tv $device @('shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $homeActivity) | Write-Output
$wanted = @($services | Where-Object { $_ -ne $service }) + $service
Invoke-Tv $device @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ($wanted -join ':')) | Out-Null
Invoke-Tv $device @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') | Out-Null
Invoke-Tv $device @('shell', 'am', 'start', '-W', '-n', $homeActivity) | Out-Null

$role = Invoke-Tv $device @('shell', 'cmd role get-role-holders --user 0 android.app.role.HOME')
if ($role -cne $pkg) { throw "The default Home didn't change (it is $role). Run Restore-Telepath.ps1 -Apply to undo." }
$resolved = Invoke-Tv $device @('shell', 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
if ($resolved -notmatch [regex]::Escape($homeActivity)) {
    Write-Output "This TV keeps its own Home preference ($(($resolved -split "`n")[-1])); the Home button service brings you to telepath instead."
}
$enabled = @((Invoke-Tv $device @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')) -split ':')
if ($service -notin $enabled) { throw "The Home button service didn't stay on. Check Settings > Accessibility, or run Restore-Telepath.ps1 -Apply." }
foreach ($entry in $services) { if ($entry -notin $enabled) { throw "An accessibility service that was already on was lost: $entry" } }
$bound = $false
foreach ($attempt in 1..5) {
    # Running as a bound service, by name (any language); else the English and translated labels, which share the app name.
    if ((Invoke-Tv $device @('shell', 'dumpsys', 'activity', 'services', $service)) -match ('ServiceRecord\{[^}]*' + [regex]::Escape($service))) { $bound = $true; break }
    if ((Invoke-Tv $device @('shell', 'dumpsys', 'accessibility')) -match 'Bound services:\{[^\r\n]*label=telepath') { $bound = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $bound) { throw 'The Home button service is on but not running. Check Settings > Accessibility on the TV.' }
Write-Output 'Done: telepath is the home screen, and its Home button service is running. Press Home on the remote to try it.'
