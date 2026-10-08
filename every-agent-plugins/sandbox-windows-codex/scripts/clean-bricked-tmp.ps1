# 清理 AclPrimitivesTest 历史「deny-Everyone 砖目录」（需管理员 PowerShell）
#
# 背景（design.md §8「deny 对象纪律」）：修复前的 AclPrimitivesTest 对 @TempDir 里的
# 目录涂 deny-write Everyone ACE——DENY_WRITE_MASK 经 FILE_GENERIC_WRITE 含 READ_CONTROL，
# 连属主隐式自救一起封死，revoke 与 JUnit @TempDir 清理全数失败，每跑一次在
# <workspace>/.everyagent/tmp 留一个 junit-* 砖目录。测试已修复（deny 对象改合成 SID），
# 本脚本只负责收割历史残留；deny-Everyone 封掉 READ_CONTROL 后非管理员无法自救，
# 这是 Windows 语义（属主隐式权利压不过显式 deny），故必须以管理员运行。
#
# 用法（在仓库根或任意目录）：
#   powershell -ExecutionPolicy Bypass -File every-agent-plugins/sandbox-windows-codex/scripts/clean-bricked-tmp.ps1 `
#       -TmpRoot <工作区>/.everyagent/tmp
#Requires -RunAsAdministrator
param(
    # 砖目录所在的工作区 tmp 根（跑过 mvn 测试的工作区各跑一次）
    [string]$TmpRoot = (Join-Path (Get-Location) '.everyagent\tmp')
)

if (-not (Test-Path -LiteralPath $TmpRoot)) {
    Write-Host "tmp 根不存在，无事可做: $TmpRoot"
    exit 0
}

$dirs = @(Get-ChildItem -LiteralPath $TmpRoot -Directory -Filter 'junit-*' -Force -ErrorAction SilentlyContinue)
if ($dirs.Count -eq 0) {
    Write-Host "无 junit-* 残留: $TmpRoot"
    exit 0
}

$failed = @()
foreach ($d in $dirs) {
    # 砖目录上 deny ACE 封死 READ_CONTROL/WRITE_DAC/DELETE：先取属主再重置 DACL 才能删
    takeown /f "$($d.FullName)" /r /d y | Out-Null
    icacls "$($d.FullName)" /reset /t /c /q | Out-Null
    Remove-Item -LiteralPath $d.FullName -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $d.FullName) { $failed += $d.Name }
}

if ($failed.Count -eq 0) {
    Write-Host "已清理 $($dirs.Count) 个砖目录: $TmpRoot"
} else {
    Write-Warning "清理了 $($dirs.Count - $failed.Count) 个，失败 $($failed.Count) 个（需人工检查 deny ACE 之外的占用，如仍存活 的 JVM 句柄）:"
    $failed | ForEach-Object { Write-Warning "  $_" }
    exit 1
}
