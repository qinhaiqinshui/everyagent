# Generate Every Agent app icon (build/icon.ico + build/icon.png).
# Visual matches frontend BrandMark: blue-green gradient (#61a5ff -> #41d19c)
# rounded square with a dark letter N. Multi-size ICO for electron-builder.
param(
  [string]$OutDir = (Join-Path $PSScriptRoot '..\build')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$blue  = [System.Drawing.ColorTranslator]::FromHtml('#61a5ff')
$green = [System.Drawing.ColorTranslator]::FromHtml('#41d19c')
$text  = [System.Drawing.ColorTranslator]::FromHtml('#061014')

$sizes = @(16, 20, 24, 32, 40, 48, 64, 128, 256)
$null = New-Item -ItemType Directory -Force -Path $OutDir

function New-IconPng([int]$size) {
  $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  try {
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear([System.Drawing.Color]::Transparent)

    # Radius and font size follow BrandMark's derivation.
    $radius = if ($size -lt 24) { [Math]::Max(4, [Math]::Round($size * 0.28)) }
              else { [Math]::Max(12, [Math]::Round($size * 0.29)) }
    $fontSize = if ($size -lt 24) { [Math]::Max(10, [Math]::Round($size * 0.54)) }
                else { [Math]::Max(18, [Math]::Round($size * 0.54)) }

    $rect = New-Object System.Drawing.RectangleF(0, 0, $size, $size)
    $d = [float]($radius * 2)

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($rect.X, $rect.Y, $d, $d, 180, 90)
    $path.AddArc($rect.Right - $d, $rect.Y, $d, $d, 270, 90)
    $path.AddArc($rect.Right - $d, $rect.Bottom - $d, $d, $d, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - $d, $d, $d, 90, 90)
    $path.CloseFigure()

    # 135deg: top-left (blue) -> bottom-right (green).
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $blue, $green, 135)
    $g.FillPath($brush, $path)
    $brush.Dispose()

    $font = New-Object System.Drawing.Font('Segoe UI', [float]$fontSize, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $textBrush = New-Object System.Drawing.SolidBrush($text)
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
    $sf.FormatFlags = [System.Drawing.StringFormatFlags]::NoClip
    $g.DrawString('N', $font, $textBrush, $rect, $sf)

    $font.Dispose(); $textBrush.Dispose(); $sf.Dispose(); $path.Dispose()

    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bytes = $ms.ToArray()
    $ms.Dispose()
    # Comma wrapper prevents PowerShell from unrolling byte[] into single bytes.
    return ,$bytes
  } finally {
    $g.Dispose()
    $bmp.Dispose()
  }
}

$entries = @()
foreach ($size in $sizes) {
  $data = [byte[]](New-IconPng $size)
  $entries += [PSCustomObject]@{
    Size = $size
    Data = $data
    WidthByte  = [byte]$(if ($size -ge 256) { 0 } else { $size })
    HeightByte = [byte]$(if ($size -ge 256) { 0 } else { $size })
  }
}

# 256x256 also saved as PNG (dev-mode BrowserWindow taskbar icon + fallback).
$png256 = [byte[]](($entries | Where-Object { $_.Size -eq 256 }).Data)
[System.IO.File]::WriteAllBytes((Join-Path $OutDir 'icon.png'), $png256)

# Assemble ICO (directory + PNG entries, Vista+ standard).
$ms = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter($ms)
$bw.Write([UInt16]0)                      # reserved
$bw.Write([UInt16]1)                      # type = icon
$bw.Write([UInt16]$entries.Count)         # count
$offset = 6 + 16 * $entries.Count
foreach ($e in $entries) {
  $bw.Write($e.WidthByte)
  $bw.Write($e.HeightByte)
  $bw.Write([byte]0)                      # palette
  $bw.Write([byte]0)                      # reserved
  $bw.Write([UInt16]1)                    # planes
  $bw.Write([UInt16]32)                   # bpp
  $bw.Write([UInt32]$e.Data.Length)       # bytes in resource
  $bw.Write([UInt32]$offset)              # image offset
  $offset += $e.Data.Length
}
foreach ($e in $entries) {
  $bw.Write([byte[]]$e.Data)
}
$bw.Flush()
[System.IO.File]::WriteAllBytes((Join-Path $OutDir 'icon.ico'), $ms.ToArray())
$bw.Dispose(); $ms.Dispose()

Write-Output "generated: $OutDir\icon.ico ($( $entries.Count ) sizes), $OutDir\icon.png"
