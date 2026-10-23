[CmdletBinding()]
param(
    # The TV's address (192.168.1.20 or 192.168.1.20:5555), or a serial from adb devices. Default: tv.address in
    # telepath.local.properties.
    [string]$Tv,
    [string]$Adb,
    # The photo folder. Default: wallpapers.folder in the settings (the repo's wallpapers folder).
    [string]$Source,
    # Portrait and square photos: Fill zooms them to fill the screen from their centre, keeping their shape;
    # Stretch stretches them out to the screen's shape, keeping all of each photo.
    [ValidateSet('Fill', 'Stretch')]
    [string]$Portrait = 'Fill',
    [switch]$Apply
)

# Mirrors a folder of your photos into telepath's slideshow on the TV (Windows; on macOS and Linux use
# sync-wallpapers.sh). Photos are turned upright, made to fill 1920x1080 and re-encoded WITHOUT metadata (no GPS or
# camera data leaves the computer). They go into telepath's own folder on the TV, which other apps can't read on
# Android 11 and newer. Only files this script created (pc_*.jpg) are ever removed from the TV. Preview by default;
# -Apply copies.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:OS -ne 'Windows_NT') { throw 'This script needs Windows. On macOS and Linux, use scripts/sync-wallpapers.sh.' }
Add-Type -AssemblyName System.Drawing
Import-Module (Join-Path $PSScriptRoot 'Telepath.psm1') -Force

$supported = @('.jpg', '.jpeg', '.png', '.bmp', '.gif', '.tif', '.tiff')

function Get-Upright([System.Drawing.Image]$Image) {
    if ($Image.PropertyIdList -notcontains 0x0112) { return }
    $flip = switch ([BitConverter]::ToUInt16($Image.GetPropertyItem(0x0112).Value, 0)) {
        2 { 'RotateNoneFlipX' } 3 { 'Rotate180FlipNone' } 4 { 'Rotate180FlipX' } 5 { 'Rotate90FlipX' }
        6 { 'Rotate90FlipNone' } 7 { 'Rotate270FlipX' } 8 { 'Rotate270FlipNone' } default { $null }
    }
    if ($flip) { $Image.RotateFlip([System.Drawing.RotateFlipType]$flip) }
}

function Set-Quality([System.Drawing.Graphics]$Graphics) {
    $Graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $Graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $Graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $Graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
}

# Portrait and square photos: upright width under 1.2 times the height.
function Test-Portrait([int]$Width, [int]$Height) { return $Width -lt 1.2 * $Height }

# Whether a photo is portrait or square, from its header and orientation tag (the pixels aren't decoded).
function Get-IsPortrait([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    try {
        try { $image = [System.Drawing.Image]::FromStream($stream, $false, $false) }
        catch { throw "Not a picture this computer can read: $Path" }
        try {
            $sideways = $image.PropertyIdList -contains 0x0112 -and [BitConverter]::ToUInt16($image.GetPropertyItem(0x0112).Value, 0) -ge 5
            if ($sideways) { return Test-Portrait $image.Height $image.Width }
            return Test-Portrait $image.Width $image.Height
        } finally { $image.Dispose() }
    } finally { $stream.Dispose() }
}

# Every photo fills the screen. Landscape photos, and with -Portrait Fill portrait and square ones too, are zoomed
# to fill it from their centre, keeping their shape. With -Portrait Stretch, portrait and square photos are
# stretched out to 16:9 instead, keeping all of the photo.
function Convert-Wallpaper([string]$Path, [string]$Destination, [string]$Mode = 'Fill') {
    $image = [System.Drawing.Image]::FromFile($Path)
    try {
        Get-Upright $image
        $w = $image.Width
        $h = $image.Height
        $outW = 1920
        $outH = 1080
        $bitmap = New-Object System.Drawing.Bitmap $outW, $outH, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                Set-Quality $graphics
                $edges = New-Object System.Drawing.Imaging.ImageAttributes
                $edges.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
                $full = New-Object System.Drawing.Rectangle 0, 0, $outW, $outH
                if ($Mode -eq 'Stretch' -and (Test-Portrait $w $h)) {
                    $graphics.DrawImage($image, $full, 0, 0, $w, $h, [System.Drawing.GraphicsUnit]::Pixel, $edges)
                } else {
                    if ($w * 9 -gt $h * 16) { $cw = [int]($h * 16 / 9); $ch = $h } else { $cw = $w; $ch = [int]($w * 9 / 16) }
                    $graphics.DrawImage($image, $full, [int](($w - $cw) / 2), [int](($h - $ch) / 2), $cw, $ch, [System.Drawing.GraphicsUnit]::Pixel, $edges)
                }
            } finally { $graphics.Dispose() }
            $jpeg = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
            $settings = New-Object System.Drawing.Imaging.EncoderParameters 1
            $settings.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), 90L
            $bitmap.Save($Destination, $jpeg, $settings)
        } finally { $bitmap.Dispose() }
    } finally { $image.Dispose() }
}

# The photo's file name on the TV, from its name and content. Portrait and square photos also carry how they were
# made to fit (_fill or _stretch), so a change of -Portrait replaces their copies on the TV.
function Get-TvName([System.IO.FileInfo]$Photo, [bool]$Tall, [string]$Mode) {
    $hash = (Get-FileHash -LiteralPath $Photo.FullName -Algorithm SHA256).Hash.Substring(0, 12).ToLowerInvariant()
    $safe = $Photo.BaseName -replace '[^A-Za-z0-9_-]', '_'
    if ($safe.Length -gt 60) { $safe = $safe.Substring(0, 60) }
    $layout = if ($Tall) { '_' + $Mode.ToLowerInvariant() } else { '' }
    return "pc_${safe}_$hash$layout.jpg"
}

$device = Connect-TelepathTv $Tv $Adb
$remote = "/sdcard/Android/data/$($device.Package)/files/slideshow"
if (-not $Source) {
    $folder = $device.Settings['wallpapers.folder']
    if (-not $folder) { $folder = 'wallpapers' }
    $Source = if ([IO.Path]::IsPathRooted($folder)) { $folder } else { Join-Path (Get-TelepathRoot) $folder }
}
if (-not (Test-Path -LiteralPath $Source -PathType Container)) { throw "Photo folder not found: $Source" }
if (-not (Test-TvPackage $device $device.Package)) { throw "telepath ($($device.Package)) isn't installed on this TV." }

function Get-RemotePhotos {
    $listing = & $device.Adb -s $device.Serial shell "ls -1 $remote 2>/dev/null"
    return @($listing | ForEach-Object { "$_".Trim() } | Where-Object { $_ -match '^pc_[A-Za-z0-9_-]+\.jpg$' })
}

$all = @(Get-ChildItem -LiteralPath $Source -File)
$photos = @($all | Where-Object { $supported -contains $_.Extension.ToLowerInvariant() } | Sort-Object Name)
$ignored = @($all | Where-Object { $supported -notcontains $_.Extension.ToLowerInvariant() -and $_.Name -ne 'README.md' })
$plan = @(foreach ($photo in $photos) {
    $tall = Get-IsPortrait $photo.FullName
    [pscustomobject]@{ Photo = $photo; Tall = $tall; Name = Get-TvName $photo $tall $Portrait }
})
$planned = @($plan | ForEach-Object { $_.Name })
$onTv = Get-RemotePhotos
$toAdd = @($plan | Where-Object { $onTv -notcontains $_.Name })
$toRemove = @($onTv | Where-Object { $planned -notcontains $_ })

Write-Output "Source: $Source"
Write-Output "Photos in folder: $($photos.Count). Already on the TV: $($plan.Count - $toAdd.Count). To add or update: $($toAdd.Count). To remove from the TV: $($toRemove.Count)."
Write-Output ("Portrait and square photos: {0}, {1} to fill the screen." -f @($plan | Where-Object Tall).Count, $(if ($Portrait -eq 'Stretch') { 'stretched' } else { 'zoomed' }))
foreach ($item in $ignored) { Write-Output "Skipped (not a photo type this script reads): $($item.Name)" }
Write-Output "TV folder (telepath only): $remote"
if (-not $Apply) {
    Write-Output 'PREVIEW ONLY. Add -Apply to copy the photos to the TV.'
    return
}

# telepath creates its own folder when it starts, so the files copied in stay readable by it.
if (-not (& $device.Adb -s $device.Serial shell "ls -d $remote 2>/dev/null")) {
    Invoke-Tv $device @('shell', 'am', 'start', '-W', '-n', "$($device.Package)/dev.plume.MainActivity") | Out-Null
    Start-Sleep -Seconds 3
    if (-not (& $device.Adb -s $device.Serial shell "ls -d $remote 2>/dev/null")) { throw 'telepath did not create its slideshow folder. Open it once on the TV and try again.' }
}

$temp = Join-Path ([IO.Path]::GetTempPath()) ("telepath-wallpapers-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $i = 0
    foreach ($item in $toAdd) {
        $i++
        $out = Join-Path $temp $item.Name
        Convert-Wallpaper $item.Photo.FullName $out $Portrait
        Invoke-Tv $device @('push', $out, "$remote/$($item.Name)") | Out-Null
        Write-Output ("[{0}/{1}] {2} -> {3} ({4:N0} KB)" -f $i, $toAdd.Count, $item.Photo.Name, $item.Name, ((Get-Item -LiteralPath $out).Length / 1KB))
    }
} finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}
foreach ($name in $toRemove) {
    Invoke-Tv $device @('shell', 'rm', '-f', "$remote/$name") | Out-Null
    Write-Output "Removed from the TV: $name"
}

$after = Get-RemotePhotos
$missing = @($planned | Where-Object { $after -notcontains $_ })
$extra = @($after | Where-Object { $planned -notcontains $_ })
if ($missing.Count -or $extra.Count) { throw "The sync didn't check out. Missing: $($missing -join ', '). Unexpected: $($extra -join ', ')." }
Write-Output "Slideshow checked: $($after.Count) photos on the TV. A new one shows each time you return to Home."
