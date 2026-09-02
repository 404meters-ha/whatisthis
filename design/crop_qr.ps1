# One-shot: crop the square QR area out of the tall WeChat pay image.
# Source resolved via *.jpg glob so no non-ASCII path ever appears in this file.
# Params: crop x, y, size, then output path.
param(
    [int]$X = 200,
    [int]$Y = 240,
    [int]$Size = 440,
    # Original-image Y where content below the QR (payee name text) starts;
    # everything under it inside the crop is painted white.
    [int]$WhiteFromY = 648,
    [Parameter(Mandatory = $true)][string]$OutPath
)
Add-Type -AssemblyName System.Drawing
$src = (Get-ChildItem "$PSScriptRoot\*.jpg" | Select-Object -First 1).FullName
$image = [System.Drawing.Image]::FromFile($src)
Write-Host "source: $($image.Width)x$($image.Height)"
$crop = New-Object System.Drawing.Bitmap($Size, $Size)
$graphics = [System.Drawing.Graphics]::FromImage($crop)
$graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$destRect = New-Object System.Drawing.Rectangle(0, 0, $Size, $Size)
$srcRect = New-Object System.Drawing.Rectangle($X, $Y, $Size, $Size)
$graphics.DrawImage($image, $destRect, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
if ($WhiteFromY -gt $Y) {
    $brush = [System.Drawing.Brushes]::White
    $cut = $WhiteFromY - $Y
    if ($cut -lt $Size) { $graphics.FillRectangle($brush, 0, $cut, $Size, $Size - $cut) }
}
$graphics.Dispose()
$image.Dispose()
$newDir = Split-Path -Parent $OutPath
if (!(Test-Path $newDir)) { New-Item -ItemType Directory -Path $newDir | Out-Null }
$crop.Save($OutPath, [System.Drawing.Imaging.ImageFormat]::Png)
$crop.Dispose()
Write-Host "saved: $OutPath ($((Get-Item $OutPath).Length) bytes)"
