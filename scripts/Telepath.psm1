# Shared helpers for the telepath scripts: the settings files, adb and the TV.

$script:RepoRoot = Split-Path -Parent $PSScriptRoot

function Get-TelepathRoot { $script:RepoRoot }

# telepath.properties, then telepath.local.properties on top: the same files the build reads.
function Get-TelepathSettings {
    $settings = @{}
    foreach ($name in 'telepath.properties', 'telepath.local.properties') {
        $path = Join-Path $script:RepoRoot $name
        if (-not (Test-Path -LiteralPath $path)) { continue }
        foreach ($line in Get-Content -LiteralPath $path -Encoding utf8) {
            $text = $line.Trim()
            if (-not $text -or $text.StartsWith('#') -or $text.StartsWith('!')) { continue }
            $split = $text.IndexOfAny([char[]]'=:')
            if ($split -lt 1) { continue }
            $settings[$text.Substring(0, $split).Trim()] = $text.Substring($split + 1).Trim()
        }
    }
    return $settings
}

# adb: -Adb, then $env:ADB, then adb on PATH, then the Android SDK, then a tools folder in or next to the repo.
function Resolve-Adb([string]$Adb) {
    $candidates = [System.Collections.Generic.List[string]]::new()
    foreach ($c in $Adb, $env:ADB) { if ($c) { $candidates.Add($c) } }
    $onPath = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { $candidates.Add($onPath.Source) }
    foreach ($sdk in $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT) {
        if ($sdk) { $candidates.Add((Join-Path $sdk 'platform-tools/adb.exe')); $candidates.Add((Join-Path $sdk 'platform-tools/adb')) }
    }
    foreach ($tools in (Join-Path $script:RepoRoot 'tools'), (Join-Path (Split-Path -Parent $script:RepoRoot) 'tools')) {
        $candidates.Add((Join-Path $tools 'platform-tools/adb.exe')); $candidates.Add((Join-Path $tools 'platform-tools/adb'))
    }
    foreach ($c in $candidates) {
        if (Test-Path -LiteralPath $c -PathType Leaf) { return (Resolve-Path -LiteralPath $c).Path }
    }
    throw "adb wasn't found. Install Android SDK Platform-Tools (https://developer.android.com/tools/releases/platform-tools), then add it to PATH or pass -Adb <path to adb>."
}

# The TV to work on: -Tv, then $env:TELEPATH_TV, then tv.address in the settings, else the one device adb already sees.
# A serial adb already lists (USB, wireless debugging) is used as it is; an address is connected to first.
# Returns what the other functions need: adb, the TV's adb serial, the app's package ID and the settings.
function Connect-TelepathTv([string]$Tv, [string]$Adb) {
    $ErrorActionPreference = 'Continue' # adb's notes on stderr aren't failures; exit codes and output are checked
    $adbPath = Resolve-Adb $Adb
    $settings = Get-TelepathSettings
    $attached = @(& $adbPath devices 2>&1 | ForEach-Object { "$_" } | Select-Object -Skip 1 |
        Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split '\t')[0] })
    $address = @($Tv, $env:TELEPATH_TV, $settings['tv.address']) | Where-Object { $_ } | Select-Object -First 1
    if ($address) {
        if ($attached -notcontains $address) {
            if ($address -notmatch ':\d+$') { $address = "${address}:5555" }
            if ($attached -notcontains $address) {
                $said = (& $adbPath connect $address 2>&1 | ForEach-Object { "$_" } | Out-String).Trim()
                if ($said -notmatch 'connected to') {
                    throw "Couldn't reach the TV at $address ($said). Is it on, on the same network, with network debugging on? (Settings > System > Developer options)"
                }
            }
        }
    } else {
        if ($attached.Count -ne 1) {
            throw "Which TV? adb sees $($attached.Count) devices. Pass -Tv <address>, or set tv.address in telepath.local.properties."
        }
        $address = $attached[0]
    }
    $state = (& $adbPath -s $address get-state 2>&1 | ForEach-Object { "$_" } | Out-String).Trim()
    if ($state -ne 'device') {
        throw "The TV at $address isn't ready for adb ($state). Allow this computer in the prompt on the TV, then run the script again."
    }
    $package = $settings['app.id']
    if (-not $package) { throw 'app.id is missing from telepath.properties.' }
    return [pscustomobject]@{ Adb = $adbPath; Serial = $address; Package = $package; Settings = $settings }
}

# One adb command on the TV; stops with adb's own message if it fails. Returns its output, trimmed.
function Invoke-Tv($Tv, [string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $output = & $Tv.Adb -s $Tv.Serial @Arguments 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "adb $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)" }
    return ($output -join "`n").Trim()
}

# Whether a package is installed on the TV (pm path exits with an error for one that isn't).
function Test-TvPackage($Tv, [string]$Package) {
    $ErrorActionPreference = 'Continue'
    $output = & $Tv.Adb -s $Tv.Serial shell pm path $Package 2>&1 | ForEach-Object { "$_" }
    return [bool](($output -join "`n") -match '(?m)^package:')
}

# Where this TV's snapshots of its original settings are kept: backups/<TV serial number>, git-ignored.
function Get-SnapshotFolder($Tv) {
    $serial = Invoke-Tv $Tv @('shell', 'getprop', 'ro.serialno')
    if (-not $serial) { throw "The TV didn't report its serial number." }
    $safe = $serial -replace '[^A-Za-z0-9_-]', '_'
    return [pscustomobject]@{ Serial = $serial; Path = (Join-Path (Join-Path $script:RepoRoot 'backups') $safe) }
}

Export-ModuleMember -Function Get-TelepathRoot, Get-TelepathSettings, Resolve-Adb, Connect-TelepathTv, Invoke-Tv, Test-TvPackage, Get-SnapshotFolder
