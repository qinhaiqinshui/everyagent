# wsl-bwrap 沙箱 Phase 0 探针 + 开发装配(wsl2-bubblewrap-sandbox-design.md §7 Phase 0)
#
# 用法(在【非降权】的普通 PowerShell 终端运行——沙箱内 Low-IL 进程会被 WSL 服务拒:
#   Wsl/E_ACCESSDENIED,这是设计已知约束,不是故障):
#   一键傻瓜式装配(推荐):     双击 scripts\wsl-setup.cmd(引导平台/发行版/依赖/userns,末尾自动跑本探针)
#   探针(只读,不改系统):   powershell -File scripts\wsl-sandbox-probe.ps1 -Distro Ubuntu
#   开发装配(装 bwrap 等):  powershell -File scripts\wsl-sandbox-probe.ps1 -Distro Ubuntu -Setup
#   托管发行版导入:          powershell -File scripts\wsl-sandbox-probe.ps1 -Tarball rootfs.tar.x64.tar.gz
#
# 参数:
#   -Distro <name>   发行版名(默认 eagent;开发试点可直接指向既有发行版如 Ubuntu)
#   -Setup           探针前先在该发行版内安装 python3/bubblewrap/ripgrep/git(需网络,apt)
#   -Tarball <path>  先 wsl --import <Distro> %LOCALAPPDATA%\eagent\wsl <Tarball> 再探针(不装包)
param(
    [string]$Distro = "eagent",
    [switch]$Setup,
    [string]$Tarball = ""
)

$ErrorActionPreference = "SilentlyContinue"   # 抑制原生命令 stderr 被 PS 包装成 NativeCommandError 的红字噪音
$env:WSL_UTF8 = "1"   # wsl.exe 自身消息(非子进程输出)按 UTF-8 输出,避免重定向乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$script:fail = 0

function Check($name, $ok, $detail) {
    $tag = if ($ok) { "PASS" } else { "FAIL"; $script:fail++ }
    Write-Host ("[{0}] {1}  {2}" -f $tag, $name, $detail)
}

function WslExec($shCmd, $timeoutSec = 60) {
    # 经 wsl.exe -d $Distro -e /bin/sh -c 执行,返回 @(rc, output)。
    # 用 & 调用(参数引号语义正确):Start-Process -ArgumentList 在 PS 5.1 不给含空格的
    # 参数加引号,sh 命令会碎参;超时参数保留兼容旧调用,不再依赖(命令均为快返回)。
    $out = & wsl.exe -d $Distro -e /bin/sh -c $shCmd 2>&1 | Out-String
    return @($LASTEXITCODE, $out)
}

# ---- 0. 自检:本进程不能是 Low-IL(WSL 服务会拒) ----
$il = (& whoami.exe /groups | Select-String "S-1-16-(\d+)").Matches[0].Groups[1].Value
Check "调用方完整性级别" ($il -ge 8192) "S-1-16-$il (>=8192 Medium 才能访问 WSL 服务;Low=4096 会被拒,实测 E_ACCESSDENIED)"

# ---- 1. WSL 存在与版本 ----
$v = (wsl.exe --version 2>$null | Select-String "WSL").Line
Check "WSL 安装" ($null -ne $v) "$v"

# ---- 2. 发行版在位(或按 -Tarball 导入) ----
if ($Tarball -ne "") {
    $dir = "$env:LOCALAPPDATA\eagent\wsl"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    & wsl.exe --import $Distro $dir $Tarball
    Check "发行版导入($Distro)" ($LASTEXITCODE -eq 0) "tar=$Tarball dir=$dir"
}
$list = (wsl.exe -l -q 2>$null) -join " "
Check "发行版存在($Distro)" ($list -match [regex]::Escape($Distro)) "已装: $list"

# ---- 3. 开发装配(-Setup):发行版内装齐 python3/bwrap/rg/git ----
if ($Setup) {
    Write-Host "`n== Setup: 在 $Distro 内安装 python3 bubblewrap ripgrep git ==" -ForegroundColor Cyan
    $r = WslExec "sudo -n true 2>/dev/null && echo SUDO_OK || echo SUDO_NEED_PW" 20
    if ($r[1] -match "SUDO_OK") {
        WslExec "sudo apt-get update -qq && sudo apt-get install -y -qq python3 bubblewrap ripgrep git" 600 | Out-Null
        Check "apt 安装(python3/bubblewrap/ripgrep/git)" ($LASTEXITCODE -eq 0) "rc=$LASTEXITCODE"
    } else {
        # 无免密 sudo:以 root 用户直跑(wsl -u root 对默认发行版通常免密)
        & wsl.exe -d $Distro -u root -e /bin/sh -c "apt-get update -qq && apt-get install -y -qq python3 bubblewrap ripgrep git"
        Check "apt 安装(python3/bubblewrap/ripgrep/git)" ($LASTEXITCODE -eq 0) "rc=$LASTEXITCODE"
    }
}

# ---- 4. 发行版内组件 ----
$r = WslExec "command -v python3 && python3 --version"
Check "python3" ($r[0] -eq 0) ($r[1].Trim() -replace "`n", " ")
$r = WslExec "command -v bwrap && bwrap --version"
Check "bubblewrap" ($r[0] -eq 0) ($r[1].Trim())
Check "rg(建议)" ((WslExec "command -v rg")[0] -eq 0) "模型内容搜索依赖"
Check "git(建议)" ((WslExec "command -v git")[0] -eq 0) ""

# ---- 5. user namespace(bwrap 的地基)----
$r = WslExec "unshare -Ur true && echo USERNETNS_OK" 20
Check "非特权 user namespace" ($r[1] -match "USERNETNS_OK") ($r[1].Trim())

# ---- 6. bwrap 冒烟(与 WslBwrapSandbox.probe / eagent-run.py RO_BASE 同款。
#      bwrap 的 root 是全新 tmpfs,只有显式 bind 的路径才存在——只 bind /usr 时
#      /bin、/lib64 不在,ELF 解释器缺失 execvp ENOENT,会误报成 userns 故障)----
$bwrapBase = "bwrap --die-with-parent --ro-bind-try /usr /usr --ro-bind-try /etc /etc --ro-bind-try /opt /opt --ro-bind-try /var /var --ro-bind-try /bin /bin --ro-bind-try /sbin /sbin --ro-bind-try /lib /lib --ro-bind-try /lib64 /lib64 --ro-bind-try /libx32 /libx32 --proc /proc --dev /dev --tmpfs /tmp --tmpfs /run"
$r = WslExec "$bwrapBase -- /bin/true && echo BWRAP_OK" 30
Check "bwrap 沙箱冒烟" ($r[1] -match "BWRAP_OK") ($r[1].Trim())

# ---- 7. 网络命名空间隔离(deny-all 语义)----
$r = WslExec "$bwrapBase --unshare-net -- /bin/sh -c 'getent hosts example.com >/dev/null 2>&1 && echo NET_LEAK || echo NET_DENIED'" 30
Check "unshare-net 全拒" ($r[1] -match "NET_DENIED") ($r[1].Trim())

# ---- 8. interop 禁用状态:仅托管发行版(eagent)硬性要求 false(镜像已烤入
#      wsl.conf);日常发行版开着 interop 只算多一条逃逸面,按提示项处理 ----
$r = WslExec "grep -A2 '^\[interop\]' /etc/wsl.conf 2>/dev/null || echo NO_WSL_CONF"
if ($Distro -eq "eagent") {
    Check "interop 状态(托管发行版应为 false)" ($r[1] -match "enabled\s*=\s*false") ($r[1].Trim() -replace "`n", "; ")
} else {
    Write-Host ("[INFO] interop 状态(仅托管发行版要求 false):" + ($r[1].Trim() -replace "`n", "; "))
}

# ---- 9. 退出码透传 ----
$r = WslExec "exit 42"
Check "退出码透传" ($r[0] -eq 42) "rc=$($r[0])"

# ---- 10. UTF-8 字节流 ----
$r = WslExec "printf '中文测试'"
Check "UTF-8 输出" ($r[1] -match "中文测试") ($r[1])

# ---- 11. wsl.exe 退出后 setsid 后台进程存活(eagent-run.py 同款形态)----
# eagent-run.py setsid 脱离 WSL 会话并登记 pgid:setsid 后代若在 wsl.exe 退出后
# 仍存活,超时收割必须靠 pkill -g(设计假设);若 WSL 连带收割,pkill 退化为兜底。
# 两种结果 worker 都能处理 → 信息项,不算失败。(裸 `&` 不 setsid 的后台进程实测
# 一定会被 WSL 收割,拿它判定只会误报。注意 PS 转义:双引号串里用反引号 `$!)
$r = WslExec "setsid sh -c 'sleep 300 >/dev/null 2>&1' >/dev/null 2>&1 & echo `$!"
$bgpid = $r[1].Trim()
if ($bgpid -match '^\d+$') {
    Start-Sleep -Milliseconds 500
    # 上面那次 WslExec 已返回(wsl.exe 已退),看 setsid 后的 sleep 是否仍活着
    $r2 = WslExec "kill -0 $bgpid 2>/dev/null && echo STILL_ALIVE || echo GONE"
    if ($r2[1] -match "STILL_ALIVE") {
        Write-Host "[INFO] wsl.exe 退出后 setsid 后台进程: 仍存活 pid=$bgpid(收割依赖 pkill -g,设计假设成立)"
    } else {
        Write-Host "[INFO] wsl.exe 退出后 setsid 后台进程: 已被 WSL 连带收割 pid=$bgpid(pkill -g 退化为兜底)"
    }
    WslExec "kill -9 $bgpid 2>/dev/null" 10 | Out-Null
} else {
    Write-Host "[INFO] wsl.exe 退出后后台进程: 未取到 pid(输出=$bgpid)"
}

# ---- 12. pgid 击杀(eagent-run 的收割机制)----
$r = WslExec "setsid sh -c 'sleep 300 & sleep 300' >/dev/null 2>&1 & echo pgid=`$!; sleep 0.3"
if ($r[1] -match "pgid=(\d+)") {
    $pg = $Matches[1]
    # 先确证组确实活着再 pkill:否则「组早被 WSL 收割 + pkill 空打」也会显示 PG_REAPED 假阳性
    $pre = WslExec "ps -eo pgid 2>/dev/null | grep -qx $pg && echo ALIVE || echo DEAD"
    if ($pre[1] -match "ALIVE") {
        WslExec "pkill -9 -g $pg 2>/dev/null" 10 | Out-Null
        Start-Sleep -Milliseconds 300
        $r3 = WslExec "ps -eo pgid 2>/dev/null | grep -qx $pg && echo PG_ALIVE || echo PG_REAPED"
        Check "pkill -g 进程组收割" ($r3[1] -match "PG_REAPED") "pgid=$pg"
    } else {
        Write-Host "[INFO] pkill -g 进程组收割: 测试组已被 WSL 连带收割(无需 pkill),跳过实杀验证"
    }
} else {
    Check "pkill -g 进程组收割" $false "未能创建测试进程组"
}

# ---- 汇总 ----
Write-Host ""
if ($script:fail -eq 0) {
    Write-Host "全部通过:wsl-bwrap 后端可用(worker.sandbox.wsl.distro=$Distro)" -ForegroundColor Green
    Write-Host "启用方式:worker 配置 worker.sandbox.type: wsl-bwrap(默认 auto 走 windows-mic,不探测 WSL)" -ForegroundColor Green
} else {
    Write-Host "$($script:fail) 项失败:见上;不配置 backend 时 worker 默认走 windows-mic(不探测 WSL)" -ForegroundColor Yellow
}
