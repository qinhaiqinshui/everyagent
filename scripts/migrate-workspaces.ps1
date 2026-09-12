# 磁盘布局迁移命令(手动执行一次,幂等可重试):旧 data/ 布局 → 新 workspaces/ 布局。
# 用法:.\scripts\migrate-workspaces.ps1 [-Home <EVERYAGENT_HOME>]
#   不传 -Home 时默认 $env:EVERYAGENT_HOME 或 ~\.everyagent。
param(
    [string]$Home = ""
)

$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir

Write-Host "[migrate] 仓库根: $RepoRoot" -ForegroundColor Cyan
Set-Location $RepoRoot

# 先安装 contract/worker 到本地仓库(供 exec:java 解析 classpath),跳过测试与打包。
mvn -q -pl every-agent-worker -am install -DskipTests -Dmaven.test.skip=true
if ($LASTEXITCODE -ne 0) { throw "mvn install 失败" }

# 执行迁移工具(仅 worker 模块,避免 -am 让父模块也跑 exec)。
$args = @()
if ($Home -ne "") { $args += "--home"; $args += $Home }
mvn -q -pl every-agent-worker exec:java `
    "-Dexec.mainClass=dev.everyagent.worker.migrate.LegacyLayoutMigrator" `
    "-Dexec.args=$($args -join ' ')"
if ($LASTEXITCODE -ne 0) { throw "迁移工具执行失败" }

Write-Host "[migrate] 完成。重启 worker 生效(托管发行版将由 autoImport 重建到 sandbox\distro)" -ForegroundColor Green
