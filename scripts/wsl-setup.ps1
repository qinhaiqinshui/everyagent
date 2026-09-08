# eagent wsl-bwrap 沙箱一键装配(傻瓜式入口)
#
# 用法:双击 scripts\wsl-setup.cmd,或在普通 PowerShell(非管理员、非沙箱)里:
#     powershell -NoProfile -ExecutionPolicy Bypass -File scripts\wsl-setup.ps1
# 全程零参数、可重复运行(幂等);每一步失败都会告诉你怎么办。
#
# 流程(wsl2-bubblewrap-sandbox-design.md §4.7/§7):
#   [1/6] 自检(Windows / 调用方非 Low-IL —— WSL 服务拒绝降权调用方)
#   [2/6] WSL 平台(缺则引导 UAC 启用;版本钉 2)
#   [3/6] 发行版(托管镜像自动导入 > 托管 eagent 已在 > WSL 默认发行版 > 引导安装)
#   [4/6] 依赖(发行版内装 python3/bubblewrap/ripgrep/git,root 直装,需网络)
#   [5/6] userns 修复(Ubuntu 24.04 AppArmor 限制,自动尝试)
#   [6/6] 终验(调 wsl-sandbox-probe.ps1 全量 12 项)→ 提示重启 worker
param(
    [string]$Distro = "",      # 显式指定发行版;默认自动选择
    [string]$Tarball = ""      # 托管镜像 tar.gz;默认探测 <用户目录>\.everyagent\wsl\
)

$ErrorActionPreference = "SilentlyContinue"   # 抑制原生命令 stderr 被 PS 包装成 NativeCommandError 的红字噪音
$env:WSL_UTF8 = "1"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$script:fail = 0
$eagentHome = Join-Path $env:USERPROFILE ".everyagent"
$managedDir = Join-Path $eagentHome "wsl\distro"

function Say($msg, $color)      { Write-Host $msg -ForegroundColor $color }
function Step($n, $t)           { Write-Host "`n===== [$n/6] $t =====" -ForegroundColor Cyan }
function Ok($t)                 { Say "  [OK] $t" "Green" }
function Bad($t)                { Say "  [FAIL] $t" "Red"; $script:fail++ }
function Warn($t)               { Say "  [注意] $t" "Yellow" }
function Ask($t)                { Read-Host ("  " + $t) }

function WslExec($wslArgs) {
    # & 调用保证含空格参数引号正确(Start-Process -ArgumentList 在 PS5.1 会碎参)
    $out = & wsl.exe @wslArgs 2>&1 | Out-String
    return @($LASTEXITCODE, $out)
}

function Get-Distros() {
    # 解析 wsl -l -v:@{ names = @(...); default = "带 * 者" }(表头门控,兼容提示文案)。
    # 失败返回 $null(调用方须区分「读取失败」与「真没有发行版」)。NUL 剥离用字符串
    # 重载 Replace(string,string)——char 重载不接受空串替换值(实测 PS5.1 报错)。
    $raw = & wsl.exe -l -v 2>&1 | Out-String
    if ($null -eq $raw -or $LASTEXITCODE -ne 0) { return $null }
    $list = $raw.Replace([string][char]0, '')
    $names = @(); $def = $null; $header = $false
    foreach ($line in ($list -split "`r?`n")) {
        $t = $line.Trim()
        if ($t -eq "") { continue }
        if ($t.StartsWith("NAME") -or $t.Contains("VERSION")) { $header = $true; continue }
        if (-not $header) { continue }
        $isDef = $t.StartsWith("*")
        if ($isDef) { $t = $t.Substring(1).Trim() }
        $name = ($t -split '\s+')[0]
        if ($name -eq "") { continue }
        if ($isDef) { $def = $name }
        $names += $name
    }
    return @{ names = $names; default = $def }
}

function Guide-Elevate($label, $wslArgs) {
    # 需要管理员的动作:弹 UAC 代跑 wsl.exe,回来提示重启/重跑
    Say "  需要管理员权限:$label" "Yellow"
    Ask "按回车弹出 UAC 并执行(或 Ctrl+C 退出)…" | Out-Null
    Start-Process -Verb RunAs -FilePath "wsl.exe" -ArgumentList $wslArgs -Wait
    Say "  已执行。若 Windows 提示需要重启,请重启电脑后再运行一次本脚本。" "Yellow"
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " eagent wsl-bwrap 沙箱一键装配(可重复运行,已装会跳过)" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

# ---- [1/6] 自检 ----
Step 1 "自检"
if ($env:OS -ne "Windows_NT") { Bad "本脚本只在 Windows 上有意义"; exit 1 }
$ilLine = & whoami.exe /groups | Select-String "S-1-16-(\d+)"
$il = if ($ilLine) { [int]$ilLine.Matches[0].Groups[1].Value } else { 8192 }
if ($il -lt 8192) {
    Bad "当前进程完整性级别过低(S-1-16-$il):WSL 服务会拒绝调用(E_ACCESSDENIED)"
    Say "  请在【普通的 PowerShell / 终端】里运行本脚本,不要在沙箱/降权环境里跑。" "Yellow"
    exit 1
}
Ok "Windows + Medium 完整性(S-1-16-$il)"

# ---- [2/6] WSL 平台 ----
Step 2 "WSL 平台"
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    Warn "本机未安装 WSL。接下来弹 UAC 执行:wsl --install --no-distribution"
    Warn "(只装平台不装发行版;装完通常需要重启电脑)"
    Guide-Elevate "启用 WSL 平台" @("--install", "--no-distribution")
    Say "  请重启电脑,然后重新运行本脚本继续。" "Yellow"
    exit 0
}
$st = & wsl.exe --status 2>&1 | Out-String
Say $st.Trim() "DarkGray"
if ($st -match "默认版本:\s*2|Default Version:\s*2") {
    Ok "WSL2 就绪"
} else {
    & wsl.exe --set-default-version 2 | Out-Null
    $st2 = & wsl.exe --status 2>&1 | Out-String
    if ($st2 -match "默认版本:\s*2|Default Version:\s*2") { Ok "已钉默认版本 2" }
    else { Warn "默认版本未能确认为 2(导入/安装时已显式 --version 2,可继续)" }
}

# ---- [3/6] 发行版 ----
Step 3 "发行版"
$target = $Distro.Trim()
$distros = Get-Distros
if ($null -eq $distros) {
    Bad "无法读取发行版列表(wsl -l -v 失败);请先确认 wsl.exe --status 正常再重跑"
    exit 1
}
if ($target -ne "") {
    # 用户点名:必须在位
    if ($distros.names -notcontains $target) {
        Bad "指定的发行版 '$target' 不存在;已装: $($distros.names -join ', ')"
        exit 1
    }
    Ok "使用指定发行版 $target"
} else {
    # 自动:托管镜像 > eagent 已在 > WSL 默认发行版
    $tar = $Tarball
    if ($tar -eq "" -and (Test-Path (Join-Path $eagentHome "wsl\eagent-rootfs.tar.gz"))) {
        $tar = Join-Path $eagentHome "wsl\eagent-rootfs.tar.gz"
    }
    if ($distros.names -contains "eagent") {
        $target = "eagent"
        Ok "托管发行版 eagent 已在位"
    } elseif ($tar -ne "" -and (Test-Path $tar)) {
        Say "  发现托管镜像:$tar" "Gray"
        $shaFile = "$tar.sha256"
        $shaOk = $false
        if (Test-Path $shaFile) {
            $expect = ((Get-Content $shaFile -Raw).Trim() -split '\s+')[0].ToLower()
            $actual = (Get-FileHash $tar -Algorithm SHA256).Hash.ToLower()
            if ($expect -eq $actual) { $shaOk = $true; Ok "镜像 sha256 校验通过" }
            else { Bad "镜像 sha256 不符(文件损坏),放弃导入,改走既有发行版" }
        } else { Bad "缺少 $shaFile 旁证文件(无法校验完整性),放弃导入,改走既有发行版" }
        if ($shaOk) {
            New-Item -ItemType Directory -Force -Path $managedDir | Out-Null
            Say "  导入中(解包约 1~5 分钟)…" "Gray"
            & wsl.exe --import eagent $managedDir $tar --version 2 | Out-Null
            if ($LASTEXITCODE -eq 0) {
                # 烤 wsl.conf:interop 关闭收窄逃逸面;automount 必须开(启动器/绑定源走 /mnt)
                & wsl.exe -d eagent -u root -e /bin/sh -c "printf '[automount]\nenabled=true\n[interop]\nenabled=false\nappendWindowsPath=false\n' > /etc/wsl.conf" | Out-Null
                & wsl.exe --terminate eagent 2>$null
                $target = "eagent"
                Ok "托管发行版 eagent 导入完成(依赖已烤入镜像,跳过第 4 步联网安装)"
            } else {
                Bad "wsl --import 失败(rc=$LASTEXITCODE),改走既有发行版"
            }
        }
    }
    if ($target -eq "") {
        if ($distros.names.Count -gt 0) {
            $target = $distros.default
            if ($target) { Ok "使用 WSL 默认发行版:$target" }
            else { $target = $distros.names[0]; Ok "无默认标记,取第一个:$target" }
        } else {
            Warn "本机没有任何发行版。接下来弹 UAC 安装 Ubuntu(wsl --install -d Ubuntu,装完需重启)。"
            Guide-Elevate "安装 Ubuntu 发行版" @("--install", "-d", "Ubuntu")
            Say "  请重启电脑,然后重新运行本脚本继续。" "Yellow"
            exit 0
        }
    }
}

# ---- [4/6] 依赖(python3/bubblewrap/ripgrep/git) ----
Step 4 "发行版内依赖"
$dep = WslExec @("-d", $target, "-e", "/bin/sh", "-c",
    'command -v apt-get >/dev/null; echo APT=$?; command -v python3 >/dev/null; echo P=$?; command -v bwrap >/dev/null; echo B=$?')
$apt = if ($dep[1] -match "APT=(\d+)") { $Matches[1] } else { "1" }
$py  = if ($dep[1] -match "P=(\d+)")   { $Matches[1] } else { "1" }
$bw  = if ($dep[1] -match "B=(\d+)")   { $Matches[1] } else { "1" }
if ($apt -ne "0") {
    Bad "'$target' 不是 Debian 系(无 apt-get),本脚本不会往里装东西"
    Say "  两条路:① 用托管镜像:把 eagent-rootfs.tar.gz(+.sha256)放到 $eagentHome\wsl\ 再跑本脚本;" "Yellow"
    Say "           ② 自己在发行版内装 python3 + bubblewrap(ripgrep/git 建议)后重跑。" "Yellow"
    exit 1
}
if ($py -eq "0" -and $bw -eq "0") {
    Ok "python3 + bwrap 已在位(ripgrep/git 顺手补齐)"
    $r = WslExec @("-d", $target, "-u", "root", "-e", "/bin/sh", "-c",
        'command -v rg >/dev/null && command -v git >/dev/null || (apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ripgrep git)')
    if ($r[0] -eq 0) { Ok "ripgrep/git 就绪" } else { Warn "ripgrep/git 补齐失败(非阻断):rc=$($r[0])" }
} else {
    $miss = @(); if ($py -ne "0") { $miss += "python3" }; if ($bw -ne "0") { $miss += "bubblewrap" }
    Say "  缺 $($miss -join '/'),以 root 安装(需几分钟,走 apt 网络)…" "Gray"
    $r = WslExec @("-d", $target, "-u", "root", "-e", "/bin/sh", "-c",
        'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3 bubblewrap ripgrep git')
    if ($r[0] -eq 0) { Ok "依赖安装完成(python3/bubblewrap/ripgrep/git)" }
    else {
        Bad "apt 安装失败 rc=$($r[0])(网络/镜像源问题?):$($r[1].Trim())"
        Say "  排查网络后重跑本脚本;或改用托管镜像路线(见上方说明)。" "Yellow"
        exit 1
    }
}

# ---- [5/6] userns 修复(Ubuntu 24.04+ AppArmor 限制非特权 user namespace) ----
Step 5 "bwrap 冒烟(userns)"
# 冒烟与 eagent-run.py 的 RO_BASE + 基础挂载逐字同构:bwrap 的 root 是全新 tmpfs,
# 只有显式 bind 的路径才存在——只 bind /usr 时 /bin、/lib64 不在,execvp ENOENT
# 会被误报成 userns 故障(真机实测,userns 本身是好的)
$smokeCmd = 'bwrap --die-with-parent --ro-bind-try /usr /usr --ro-bind-try /etc /etc --ro-bind-try /opt /opt --ro-bind-try /var /var --ro-bind-try /bin /bin --ro-bind-try /sbin /sbin --ro-bind-try /lib /lib --ro-bind-try /lib64 /lib64 --ro-bind-try /libx32 /libx32 --proc /proc --dev /dev --tmpfs /tmp --tmpfs /run -- /bin/true'
$smoke = WslExec @("-d", $target, "-e", "/bin/sh", "-c", $smokeCmd)
if ($smoke[0] -eq 0) {
    Ok "bwrap 冒烟通过(userns 可用)"
} else {
    Say "  冒烟失败,尝试 Ubuntu 24.04 的 AppArmor userns 修复(root,自动持久化)…" "Gray"
    WslExec @("-d", $target, "-u", "root", "-e", "/bin/sh", "-c",
        '[ -f /proc/sys/kernel/apparmor_restrict_unprivileged_userns ] && sysctl -w kernel.apparmor_restrict_unprivileged_userns=0 >/dev/null && printf "kernel.apparmor_restrict_unprivileged_userns=0\n" > /etc/sysctl.d/99-eagent.conf || true') | Out-Null
    # 不 --terminate 重启发行版:sysctl -w 对运行中内核立即生效;terminate 会连坐杀掉
    # 住在发行版里的第三方代理(如 Docker Desktop WSL 集成),触发「integration stopped」弹窗
    $smoke = WslExec @("-d", $target, "-e", "/bin/sh", "-c", $smokeCmd)
    if ($smoke[0] -eq 0) { Ok "userns 修复生效,冒烟通过" }
    else {
        Bad "bwrap 冒烟仍失败:$($smoke[1].Trim())"
        Say "  可能原因:BIOS 虚拟化未开 / 内核不支持非特权 userns。" "Yellow"
        Say "  检查:任务管理器-性能-CPU「虚拟化:已启用」;仍不行把 WSL2 内核 uname -r 报告给开发者。" "Yellow"
    }
}

# ---- [6/6] 终验 + 收尾 ----
Step 6 "终验(全量 12 项探针)"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "wsl-sandbox-probe.ps1") -Distro $target

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
if ($script:fail -eq 0) {
    Say "装配完成。最后两步:" "Green"
    Say "  1. worker 配置启用(默认 auto 走 windows-mic,不探测 WSL):" "Green"
    Say "     application.yml 的 worker.sandbox 下取消注释并设 type: wsl-bwrap" "DarkGray"
    Say "  2. 重启 every-agent-worker,启动日志出现下面这行即成功:" "Green"
    Say "     [sandbox] 生效后端 = wsl-bwrap(配置 wsl-bwrap):distro=…授权根 = --bind 白名单…" "DarkGray"
    Say "发行版 '$target'(worker 侧 distro 留空即用 WSL 默认发行版)" "Gray"
} else {
    Say "有 $($script:fail) 项未完成,按上面 [FAIL]/[注意] 的提示处理后重跑本脚本。" "Yellow"
}
Write-Host "==================================================" -ForegroundColor Cyan
Ask "按回车退出…" | Out-Null
