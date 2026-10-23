#Requires -Version 5.1
<#
.SYNOPSIS
  扫描(并可选修复)工作区内被打上低完整性(Low Integrity)标签的文件/目录。

.DESCRIPTION
  背景:某些安全软件会给"可疑"工具(实测案例:node_modules 里的 7za.exe /
  app-builder.exe)打 Low 完整性标签做软沙箱。从 Low 标签镜像启动的进程会被
  降为 Low IL,受 No-Write-Up 策略限制:能执行、能读,但向任何 Medium 对象
  写入一律 Access Denied —— 典型症状:electron-builder 报
  "mkdir win-unpacked: Access is denied",而管理员令牌可豁免。
  本脚本递归扫描 Root,列出所有带 Low 标签的对象;-Fix 时重设为 Medium。

  实现说明:icacls /FindSid 不检索完整性标签 ACE(已实测),故走全量列表
  流式解析:对象首行 = "完整路径 + 第一条ACE",ACE 续行以空白缩进;首行
  路径可能含空格,用"自右向左按空格回退 + 文件系统存在性校验"剥出真实路径。

.PARAMETER Root
  扫描根目录,默认脚本所在目录的上一级(仓库根)。

.PARAMETER Fix
  将命中的 Low 标签对象重设为 Medium(默认完整性级别)并逐一复核。

.PARAMETER IncludeUntrusted
  一并检测 Untrusted(比 Low 更低)标签。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\scan-low-integrity.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\scan-low-integrity.ps1 -Fix
#>
[CmdletBinding()]
param(
    [string]$Root = '',
    [switch]$Fix,
    [switch]$IncludeUntrusted
)

$ErrorActionPreference = 'Continue'
# 注意:PS 5.1 在 param 默认值表达式里取不到 $PSScriptRoot,必须在脚本体内解析默认根
if (-not $Root) {
    $Root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } else { (Get-Location).Path }
}
$Root = (Resolve-Path -LiteralPath $Root).ProviderPath

$labelPattern = 'Mandatory Label\\Low Mandatory Level'
if ($IncludeUntrusted) { $labelPattern = 'Mandatory Label\\(Low|Untrusted) Mandatory Level' }

# Test-Path 对 >260 字符长路径不可靠,改用 \\?\ 前缀走 .NET 判存在
function Test-PathSafe([string]$p) {
    return ([System.IO.File]::Exists("\\?\$p") -or [System.IO.Directory]::Exists("\\?\$p") -or
            [System.IO.File]::Exists($p) -or [System.IO.Directory]::Exists($p))
}

# 从 icacls 对象首行("完整路径 第一条ACE")剥出真实路径:
# 自右向左按空格回退,直到剩余串真实存在于文件系统(路径含空格也能正确还原)
function Split-HeaderPath([string]$line) {
    $p = $line.TrimEnd()
    while ($p) {
        if (Test-PathSafe $p) { return $p }
        $i = $p.LastIndexOf(' ')
        if ($i -lt 1) { return $null }
        $p = $p.Substring(0, $i).TrimEnd()
    }
    return $null
}

Write-Host "[scan] 根目录: $Root"
$scope = if ($IncludeUntrusted) { 'Low / Untrusted' } else { 'Low' }
Write-Host "[scan] 检测标签: $scope$(if ($Fix) { '  (修复模式: 重设为 Medium)' })"

$hits      = New-Object System.Collections.Generic.List[string]
$summary   = ''
$current   = $null
$lineCount = 0
$sw        = [System.Diagnostics.Stopwatch]::StartNew()

# /T 递归 /C 出错继续 /L 不跟随符号链接(防止越出 Root 扫到区外)
& icacls $Root /T /C /L 2>$null | ForEach-Object {
    $lineCount++
    if ($_ -match '^\s') {
        # ACE 续行:归属当前对象 $current
        if ($null -ne $current -and $_ -match $labelPattern) { $hits.Add($current) }
    }
    elseif ([string]::IsNullOrWhiteSpace($_)) {
        $current = $null
    }
    elseif ($_ -match '^Successfully processed') {
        $summary = $_.Trim()
        $current = $null
    }
    else {
        # 对象首行:路径 + 第一条 ACE(首条 ACE 本身也可能命中标签,一并判)
        $current = Split-HeaderPath $_
        if ($null -ne $current -and $_ -match $labelPattern) { $hits.Add($current) }
    }
}

$hits = @($hits | Sort-Object -Unique)
Write-Host "[scan] 完成: 解析 $lineCount 行,命中 $($hits.Count) 个对象,耗时 $([int]$sw.Elapsed.TotalSeconds)s"
if ($summary) { Write-Host "[scan] icacls: $summary" }

if ($hits.Count -eq 0) {
    Write-Host '[scan] OK 未发现目标标签的文件/目录'
    exit 0
}

foreach ($h in $hits) { Write-Host "[scan] 命中: $h" }

if (-not $Fix) {
    Write-Host '[scan] 提示: 追加 -Fix 参数可将上述对象重设为 Medium 标签'
    exit 1
}

$okCount = 0
$failed  = @()
foreach ($h in $hits) {
    icacls $h /setintegritylevel M | Out-Null
    $after = (icacls $h) -join "`n"
    if ($after -match 'Low Mandatory|Untrusted Mandatory') {
        $failed += $h
        Write-Host "[fix] FAIL $h"
    }
    else {
        $okCount++
        Write-Host "[fix] OK   $h"
    }
}

Write-Host "[fix] 修复成功 $okCount / $($hits.Count)"
if ($failed.Count -gt 0) {
    Write-Host '[fix] 以下对象修复失败(可能被占用或权限不足,可尝试管理员终端):'
    $failed | ForEach-Object { Write-Host "        $_" }
    exit 2
}
exit 0
