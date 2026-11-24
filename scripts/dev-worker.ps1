#Requires -Version 5.1
<#
.SYNOPSIS
  启动 dev 环境 worker(every-agent-worker,profile=dev),替代 IDEA 运行配置。

.DESCRIPTION
  等价于 IDEA 里 Run 'WorkerApplication'(不带 debug agent):
    1) 增量编译 every-agent-worker 及其 reactor 依赖(-am;hub 系 test 依赖会被顺带编译,无害);
    2) 生成依赖 classpath 缓存 target/dev-worker.classpath.txt(dependency:build-classpath,
       runtime scope;缓存命中即跳过,依赖变更后用 -RefreshDeps 重建);
    3) java -cp <worker classes + 依赖> dev.everyagent.worker.WorkerApplication 前台启动,
       Ctrl+C 停止。
  dev 配置要点(every-agent-worker/src/main/resources/application-dev.yml):
    管理端口 8200(仅本机)、连 ws://localhost:8100/ws(dev hub)、home-dir=~/.everyagent-dev
    (hubs/models 密钥在该目录 application-worker.yaml,不进 git)。
  cwd 固定为仓库根:runtime/(rg 二进制等程序附属文件)按 user.dir 相对解析
  (WorkerProperties.resolveRuntimeDir,缺省 ./runtime)。

.PARAMETER SkipBuild
  跳过编译(代码未改、快速重启时用)。

.PARAMETER RefreshDeps
  忽略 classpath 缓存强制重建(升级依赖版本后启动报 ClassNotFound 时用)。

.PARAMETER Offline
  编译/生成 classpath 时追加 -o(离线),跳过远程仓库探测,启动更快。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dev-worker.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dev-worker.ps1 -SkipBuild

.NOTES
  PowerShell 5.1 兼容(无 && / ?? 等新语法);退出码 0 成功 / 1 失败。
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$RefreshDeps,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'

# --- 仓库根(本脚本位于 scripts/ 下) ---
$Repo = Split-Path -Parent $PSScriptRoot
$CpFile = Join-Path $Repo 'target\dev-worker.classpath.txt'

# --- JAVA_HOME 校验/探测:须为 JDK 25(根 pom 锁定 java.version=25) ---
function Resolve-JavaHome {
    param([string]$Current)
    if ($Current -and (Test-Path (Join-Path $Current 'bin\java.exe'))) { return $Current }
    foreach ($cand in @(
            (Get-ChildItem 'D:\Programs\jdks' -Directory -Filter 'corretto-25*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1).FullName,
            (Get-ChildItem 'C:\Program Files\Amazon Corretto' -Directory -Filter 'jdk25*' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName,
            (Get-ChildItem 'C:\Program Files\Java' -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
        )) {
        if ($cand -and (Test-Path (Join-Path $cand 'bin\java.exe'))) { return $cand }
    }
    throw "未找到 JDK 25:请设置 JAVA_HOME(当前='$Current')"
}

# 版本软校验(读 <javaHome>/release 的 JAVA_VERSION 主版本),不符仅警告不阻断
function Test-JdkVersion {
    param([string]$Home2)
    $rel = Join-Path $Home2 'release'
    if (-not (Test-Path $rel)) { return }
    $line = (Get-Content $rel -ErrorAction SilentlyContinue | Where-Object { $_ -match '^JAVA_VERSION=' } | Select-Object -First 1)
    if ($line -match 'JAVA_VERSION="(\d+)') {
        if ([int]$Matches[1] -lt 25) {
            Write-Warning "JAVA_HOME 指向 JDK $($Matches[1]),项目要求 JDK 25,编译可能失败"
        }
    }
}

# --- maven wrapper 调用封装(失败即抛) ---
function Invoke-Mvn {
    param([string[]]$Goals)
    $mvn = Join-Path $Repo 'mvnw.cmd'
    $full = @()
    if ($Offline) { $full += '-o' }
    $full += $Goals
    & $mvn @full '-f' (Join-Path $Repo 'pom.xml')
    if ($LASTEXITCODE -ne 0) { throw "maven 失败: mvnw $($full -join ' ')" }
}

# --- 前置检查:8200 已被监听 → dev worker 可能正在运行 ---
$used = Get-NetTCPConnection -LocalPort 8200 -State Listen -ErrorAction SilentlyContinue
if ($used) {
    Write-Host "端口 8200 已被 PID $($used[0].OwningProcess) 占用(dev worker 可能已在运行),请先停止它再启动。" -ForegroundColor Yellow
    exit 1
}

# --- 剥离 desktop 注入的 EVERYAGENT_HOME(如被继承):
#     logback 的日志目录优先级是 ${EVERYAGENT_HOME:-worker.home-dir},不剥离的话
#     dev worker 日志会误落 desktop 目录(~/.everyagent/logs/worker.log,与正式 worker
#     同文件互踩)。dev 数据/日志目录由 dev profile 的 worker.home-dir=~/.everyagent-dev
#     决定,与该变量无关,清除无副作用。 ---
if ($env:EVERYAGENT_HOME) {
    Write-Host "剥离继承的 EVERYAGENT_HOME=$env:EVERYAGENT_HOME(dev 日志将落 ~/.everyagent-dev/logs)" -ForegroundColor DarkGray
    Remove-Item Env:EVERYAGENT_HOME -ErrorAction SilentlyContinue
}

$JavaHome = Resolve-JavaHome $env:JAVA_HOME
Test-JdkVersion $JavaHome
if ($JavaHome -ne $env:JAVA_HOME) {
    Write-Host "JAVA_HOME 未设置/无效,探测到: $JavaHome" -ForegroundColor DarkGray
    $env:JAVA_HOME = $JavaHome
}

# --- 1) 增量编译(worker + reactor 依赖模块) ---
if (-not $SkipBuild) {
    Write-Host "[1/3] 编译 every-agent-worker ..." -ForegroundColor Cyan
    Invoke-Mvn @('-q', '-pl', 'every-agent-worker', '-am', 'compile')
}
else {
    Write-Host "[1/3] 跳过编译(-SkipBuild)" -ForegroundColor DarkGray
}

# --- 2) 依赖 classpath(runtime scope;reactor 模块以 target/classes 形式入表) ---
if ($RefreshDeps -or -not (Test-Path $CpFile)) {
    Write-Host "[2/3] 生成依赖 classpath ..." -ForegroundColor Cyan
    Invoke-Mvn @('-q', '-pl', 'every-agent-worker', '-am', 'dependency:build-classpath', "-Dmdep.outputFile=$CpFile", '-Dmdep.includeScope=runtime')
}
else {
    Write-Host "[2/3] 复用 classpath 缓存($(Split-Path -Leaf $CpFile))" -ForegroundColor DarkGray
}

# --- 3) 前台启动(cwd=仓库根:./runtime 按 user.dir 解析) ---
$deps = (Get-Content $CpFile -Raw).Trim()
if (-not $deps) { throw "classpath 文件为空: $CpFile(可加 -RefreshDeps 重建)" }
$cp = (Join-Path $Repo 'every-agent-worker\target\classes') + ';' + $deps
$java = Join-Path $JavaHome 'bin\java.exe'

Set-Location $Repo
Write-Host "[3/3] 启动 dev worker(端口 8200 → hub ws://localhost:8100/ws,Ctrl+C 停止)..." -ForegroundColor Cyan
& $java `
    '-Dspring.profiles.active=dev' `
    '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' `
    -cp $cp dev.everyagent.worker.WorkerApplication
exit $LASTEXITCODE
