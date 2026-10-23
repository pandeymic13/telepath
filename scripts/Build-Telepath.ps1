[CmdletBinding()]
param(
    # Build the APK without running the unit tests first.
    [switch]$SkipTests
)

# Builds your telepath with the settings in telepath.properties (and telepath.local.properties), and copies
# the APK to build/telepath-<version>.apk. Needs JDK 17 or newer and the Android SDK: JAVA_HOME and ANDROID_HOME
# (or sdk.dir in local.properties), or a tools folder next to this repo with jdk*/ and android-sdk/ in it.
# On macOS and Linux, run ./gradlew :app:assembleRelease instead.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$tools = Join-Path (Split-Path -Parent $root) 'tools'
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin'))) {
    $jdk = Get-ChildItem -Path (Join-Path $tools 'jdk*') -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { if (Test-Path (Join-Path $_.FullName 'bin\java.exe')) { $_ } else { Get-ChildItem $_.FullName -Directory } } |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
    elseif (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'No JDK found. Install JDK 17 or newer (for example Eclipse Temurin 21) and set JAVA_HOME.' }
}
if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;$env:Path" }
$sdkInProperties = (Test-Path (Join-Path $root 'local.properties')) -and (Select-String -Path (Join-Path $root 'local.properties') -Pattern '^sdk\.dir=' -Quiet)
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT -and -not $sdkInProperties) {
    $sdk = Join-Path $tools 'android-sdk'
    if (Test-Path $sdk) { $env:ANDROID_HOME = $sdk }
    else { throw 'No Android SDK found. Install Android Studio or the command-line tools, then set ANDROID_HOME.' }
}

Push-Location $root
try {
    $tasks = @(':app:assembleRelease')
    if (-not $SkipTests) { $tasks = @(':app:testDebugUnitTest') + $tasks }
    & (Join-Path $root 'gradlew.bat') @tasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "The build failed (exit code $LASTEXITCODE). The message above says why." }
} finally {
    Pop-Location
}
$release = Join-Path $root 'app\build\outputs\apk\release'
$version = (Get-Content -Raw (Join-Path $release 'output-metadata.json') | ConvertFrom-Json).elements[0].versionName
New-Item -ItemType Directory -Force -Path (Join-Path $root 'build') | Out-Null
$apk = Join-Path $root "build\telepath-$version.apk"
Copy-Item -LiteralPath (Join-Path $release 'app-release.apk') -Destination $apk -Force
Write-Output "Built: $apk"
Write-Output 'Install it with: .\scripts\Install-Telepath.ps1'
