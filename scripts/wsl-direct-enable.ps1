# wsl-direct 后端发行版装配:关闭 automount,让沙箱发行版内只可见工作区
#
# 用法(普通 PowerShell,非管理员、非沙箱):
#     powershell -NoProfile -ExecutionPolicy Bypass -File scripts\wsl-direct-enable.ps1 [-Distro eagent]
#
# 与 wsl-bwrap 的 wsl.conf 差异:两种后端的 automount 取向相反——
#   wsl-bwrap:automount=true (启动器/绑定源走 /mnt,需整盘挂载)
#   wsl-direct:automount=false(宿主盘不自动挂载,只手动 mount 工作区到 /c/a/foo)
# 两者共享同一发行版时 wsl.conf 只能取其一,切后端需重跑对应装配。
#
# 生效条件:wsl.conf 改动需 wsl --terminate 后重启发行版进程才生效。
param(
    [string]$Distro = "eagent"   # 目标发行版;默认 eagent(托管)
)

$ErrorActionPreference = "SilentlyContinue"
$env:WSL_UTF8 = "1"

function WslExec($wslArgs) {
    $out = & wsl.exe @wslArgs 2>&1 | Out-String
    return @($LASTEXITCODE, $out)
}

Write-Host "===== [wsl-direct] 关闭 automount 装配 =====" -ForegroundColor Cyan
Write-Host "  目标发行版: $Distro" "Gray"

# 写 /etc/wsl.conf:关闭 automount + interop,不注入 Windows PATH
$conf = "[automount]`nenabled=false`nmountFsTab=false`n[interop]`nenabled=false`nappendWindowsPath=false`n"
$r = WslExec @("-d", $Distro, "-u", "root", "-e", "/bin/sh", "-c", "printf '$conf' > /etc/wsl.conf")
if ($r[0] -ne 0) {
    Write-Host "  [FAIL] 写 /etc/wsl.conf 失败: $($r[1])" "Red"
    exit 1
}
Write-Host "  [OK] /etc/wsl.conf 已写: automount=false" "Green"

# 终止发行版进程,使配置生效
& wsl.exe --terminate $Distro 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host "  [OK] 已 wsl --terminate $Distro(配置生效)" "Green"
} else {
    Write-Host "  [注意] wsl --terminate 未执行(发行版可能未在运行,首次进入时配置即生效)" "Yellow"
}

Write-Host "`n完成。worker 重启后启用 wsl-direct 后端即可(worker.sandbox.type: wsl-direct)。" -ForegroundColor Cyan
exit 0