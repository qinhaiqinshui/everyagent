<#
.SYNOPSIS
版本单点源 → 脚手架模板 / 插件文档站版本引用 一键同步（known-issues #23「缺统一 bump 脚本」项）。

.DESCRIPTION
改根 pom 的版本属性（spring-boot-starter-parent 父版本 / java.version / spring-ai.version /
<revision> 工程版本）后运行本脚本，把下列位置的版本字面量同步成根 pom 当前值
（对当前值执行 = 幂等无变更）：

  槽位         值来源（只读）                               同步目标（写入）
  -----------  -------------------------------------------  ----------------------------------------------
  java.version 根 pom <properties>java.version              模板/文档中「Java NN / JDK NN」字样
  revision     根 pom <properties>revision（工程版本单点源） 文档 every-agent-parent:<v> /
               every-agent-parent、plugin-api、worker        every-agent-plugin-api(:jar)：<v> /
               三者的版本都等于它                              every-agent-worker-<v>-exec.jar 引用
  spring-ai    根 pom <properties>spring-ai.version         文档 spring-ai-client-chat <v> 引用

铁律（不破并行协作与红线）：
  - 文档站 docs/plugin-guide/web/ 子目录与 reference/known-issues.md 永不写入（另有归属）；
    本脚本的文档目标全部显式登记在下方规则表里；
  - **pom 模板不在规则表内**：脚手架只有一份 pom 模板
    （create-everyagent-plugin/templates/java/pom.xml.tpl），它继承插件 SDK parent，
    Java 版本 / 编码 / 依赖版本管理 / 打包 全由 parent 提供，模板里没有任何版本字面量
    （内置形态的 parent 版本写 ${revision}、外部形态由生成器读根 pom 填实际版本）。
    往这里写死版本会破坏版本单点源；
  - 脚手架 vendor 类型副本（index.d.ts.raw）是内容同步不是版本同步，不归本脚本管
    （归 scripts/sync-plugin-api-copy.ps1 —— 它管类型副本的逐字节内容，并配
    create-everyagent-plugin/test/plugin-api-copy.test.mjs 漂移守卫测试）；
  - @everyagent/plugin-api js 包版本（every-agent-plugin-api/js/package.json）归
    scripts/bump-version.py 统一管理（与 <revision> 同号）。

人工后置项（脚本只改版本字样，会打印提醒）：
  - spring-ai 槽位变化时，文档里「javap 实测」的常量值（ToolCallingAdvisor.DEFAULT_ORDER 等）
    必须重新测量。

.PARAMETER DryRun
只打印将发生的逐行变更，不写盘。

.EXAMPLE
# 预览（不写盘）
powershell -ExecutionPolicy Bypass -File scripts\sync-plugin-template-versions.ps1 -DryRun

# 实际同步
powershell -ExecutionPolicy Bypass -File scripts\sync-plugin-template-versions.ps1

.NOTES
PowerShell 5.1 兼容（无 && / || / ?? 等新语法），零第三方依赖；退出码 0 成功 / 1 失败。
#>

param(
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# ---------- 工具：读文本（记住 BOM 状态），写文本（原样回写 BOM） ----------

function Read-TextPreserveBom {
    param([string]$Path)
    $bytes = [IO.File]::ReadAllBytes($Path)
    $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
    $text = [Text.Encoding]::UTF8.GetString($bytes)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    return @{ Text = $text; HasBom = $hasBom }
}

function Write-TextPreserveBom {
    param([string]$Path, [string]$Text, [bool]$HasBom)
    $enc = New-Object System.Text.UTF8Encoding($HasBom)
    [IO.File]::WriteAllText($Path, $Text, $enc)
}

# ---------- 工具：从 pom XML 抓「坐标版本 / 属性值」 ----------

function Get-CoordinateVersion {
    # 项目坐标版本，兼容两种排列：
    #   <artifactId>X</artifactId> 之后紧邻 <version>，或 <version> 在 <artifactId>X</artifactId> 之前
    # （every-agent-worker/pom.xml 就是「version 在前」的写法）
    param([string]$Xml, [string]$Artifact)
    $esc = [regex]::Escape($Artifact)
    $m = [regex]::Match($Xml, "(?s)<artifactId>$esc</artifactId>\s*<version>([^<]+)</version>")
    if ($m.Success) { return $m.Groups[1].Value }
    $m = [regex]::Match($Xml, "(?s)<version>([^<]+)</version>\s*<artifactId>$esc</artifactId>")
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

function Get-PropertyValue {
    param([string]$Xml, [string]$Name)
    $m = [regex]::Match($Xml, "(?s)<$([regex]::Escape($Name))>([^<]+)</$([regex]::Escape($Name))>")
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

# ---------- 读 pom，解析各槽位目标值 ----------

$rootPomPath = Join-Path $root 'pom.xml'
if (-not (Test-Path $rootPomPath)) { Write-Error "找不到根 pom：$rootPomPath" }

$rootPom = (Read-TextPreserveBom $rootPomPath).Text
$springBootNew = Get-CoordinateVersion -Xml $rootPom -Artifact 'spring-boot-starter-parent'
$javaNew = Get-PropertyValue -Xml $rootPom -Name 'java.version'
$springAiNew = Get-PropertyValue -Xml $rootPom -Name 'spring-ai.version'

# 工程版本单点源：根 pom 的 <revision>（Maven CI-friendly versions）。
# every-agent-parent / every-agent-plugin-api / every-agent-worker 的版本都等于它 —— 三者自身已不写
# <version>（继承 ${revision}），所以不能再从各自 pom 抓「坐标版本」（抓到的会是 ${revision} 字面量）。
$parentNew = Get-PropertyValue -Xml $rootPom -Name 'revision'
$apiNew = $parentNew
$workerNew = $parentNew

$missing = @()
foreach ($pair in @(
    @('spring-boot（根 pom parent）', $springBootNew),
    @('every-agent-parent（根 pom 工程版本）', $parentNew),
    @('java.version', $javaNew),
    @('spring-ai.version', $springAiNew),
    @('every-agent-plugin-api 版本', $apiNew),
    @('every-agent-worker 版本', $workerNew))) {
    if ([string]::IsNullOrEmpty($pair[1])) { $missing += $pair[0] }
}
if ($missing.Count -gt 0) { Write-Error ("pom 解析失败，槽位缺值：{0}" -f ($missing -join '、')) }

foreach ($v in @($springBootNew, $parentNew, $javaNew, $springAiNew, $apiNew, $workerNew)) {
    if ($v -match '[\$<>&]') { Write-Error "版本值含非法字符（$v），中止以防误替换" }
}

Write-Host ("根 pom / 模块 pom 当前值：Spring Boot={0}  Java={1}  Spring AI={2}  every-agent-parent={3}  plugin-api={4}  worker={5}" -f `
    $springBootNew, $javaNew, $springAiNew, $parentNew, $apiNew, $workerNew)
if ($DryRun) { Write-Host '模式：-DryRun（只打印变更，不写盘）' } else { Write-Host '模式：实际同步' }
Write-Host ''

# ---------- 规则表：槽位 → 文件 → (规则名, 正则, 替换串) ----------
# 说明：
#   - 替换串用 ${1}/${2} 引用捕获组（后跟数字也安全），版本值已校验不含 $；
#   - 文档目标全部显式列出，web/ 与 reference/known-issues.md 永不进表。
#   - pom 模板已不进规则表：脚手架只有一份 pom.xml.tpl，且不含任何版本字面量（详见文件头「铁律」）。

$rules = @()
$rules += @{ File = 'create-everyagent-plugin/README.md'; Slot = 'java.version'; Name = '「Java NN」字样'
    Pattern = '(?<![\w.])Java \d{1,3}(?![\d.])'
    Replacement = ("Java {0}" -f $javaNew) }
foreach ($f in @('docs/plugin-guide/getting-started.md',
                 'docs/plugin-guide/guides/build-and-run.md',
                 'docs/plugin-guide/guides/troubleshooting.md',
                 'docs/plugin-guide/guides/debugging-and-testing.md')) {
    $rules += @{ File = $f; Slot = 'java.version'; Name = '「JDK NN」字样'
        Pattern = '(?<![\w.])JDK \d{1,3}(?![\d.])'
        Replacement = ("JDK {0}" -f $javaNew) }
    $rules += @{ File = $f; Slot = 'java.version'; Name = '「Java NN」字样'
        Pattern = '(?<![\w.])Java \d{1,3}(?![\d.])'
        Replacement = ("Java {0}" -f $javaNew) }
}
foreach ($f in @('docs/plugin-guide/index.md', 'docs/plugin-guide/guides/build-and-run.md')) {
    $rules += @{ File = $f; Slot = 'every-agent-parent'; Name = '「every-agent-plugin-parent:<v>」引用'
        Pattern = 'every-agent-plugin-parent:[\d.]+'
        Replacement = ("every-agent-plugin-parent:{0}" -f $parentNew) }
}
$rules += @{ File = 'docs/plugin-guide/guides/debugging-and-testing.md'; Slot = 'plugin-api'; Name = '「every-agent-plugin-api:<v>」引用'
    Pattern = 'every-agent-plugin-api:[\d.]+'
    Replacement = ("every-agent-plugin-api:{0}" -f $apiNew) }
$rules += @{ File = 'docs/plugin-guide/guides/troubleshooting.md'; Slot = 'plugin-api'; Name = '「every-agent-plugin-api:jar:<v>」引用'
    Pattern = 'every-agent-plugin-api:jar:[\d.]+'
    Replacement = ("every-agent-plugin-api:jar:{0}" -f $apiNew) }
$rules += @{ File = 'docs/plugin-guide/index.md'; Slot = 'plugin-api'; Name = '「every-agent-plugin-api`（<v>）」引用（js 侧版本由 bump-version.py 管理，已对齐同号）'
    Pattern = 'every-agent-plugin-api`（[\d.]+）'
    Replacement = ('every-agent-plugin-api`（' + $apiNew + '）') }
$rules += @{ File = 'docs/plugin-guide/guides/build-and-run.md'; Slot = 'worker'; Name = '「every-agent-worker-<v>-exec.jar」文件名'
    Pattern = 'every-agent-worker-[\d.]+(?=-exec\.jar)'
    Replacement = ("every-agent-worker-{0}" -f $workerNew) }
foreach ($f in @('docs/plugin-guide/backend/advisors.md', 'docs/plugin-guide/reference/builtin-plugins.md')) {
    # 兼容三种写法：`spring-ai-client-chat 2.0.1`、`spring-ai-client-chat` 2.0.1（反引号）、**2.0.1**（粗体）
    $rules += @{ File = $f; Slot = 'spring-ai'; Name = '「spring-ai-client-chat <v>」实测引用'
        Pattern = '(?<![\w.-])spring-ai-client-chat([`\s*]+)[\d.]+'
        Replacement = ('spring-ai-client-chat${1}' + $springAiNew) }
}

# ---------- 执行：按文件聚合规则 → 逐条替换 → 打印逐行 diff →（非 DryRun）写盘 ----------

$byFile = @{}
foreach ($r in $rules) {
    if (-not $byFile.ContainsKey($r.File)) { $byFile[$r.File] = @() }
    $byFile[$r.File] += $r
}

$changedFiles = 0
$slotChanged = @{}
foreach ($f in ($byFile.Keys | Sort-Object)) {
    $abs = Join-Path $root $f
    if (-not (Test-Path $abs)) {
        Write-Warning "目标不存在，跳过：$f"
        continue
    }
    $bag = Read-TextPreserveBom $abs
    $text = $bag.Text
    $orig = $text
    $fileHits = 0
    foreach ($r in $byFile[$f]) {
        $before = $text
        $hits = [regex]::Matches($text, $r.Pattern)
        if ($hits.Count -eq 0) { continue }
        $text = [regex]::Replace($text, $r.Pattern, $r.Replacement)
        if ($text -cne $before) {
            # 替换确实改变了文本才记账（命中但值已一致 = 幂等，不算变更）
            if (-not $slotChanged.ContainsKey($r.Slot)) { $slotChanged[$r.Slot] = $true }
            $fileHits += $hits.Count
            Write-Host ("  [{0}] {1} × {2} 处" -f $r.Slot, $r.Name, $hits.Count)
        }
    }
    if ($text -ceq $orig) { continue }

    $changedFiles++
    Write-Host ("--> {0}（{1} 处替换）" -f $f, $fileHits)
    $oldLines = $orig -split "`r?`n"
    $newLines = $text -split "`r?`n"
    $max = [Math]::Max($oldLines.Count, $newLines.Count)
    for ($i = 0; $i -lt $max; $i++) {
        $o = if ($i -lt $oldLines.Count) { $oldLines[$i] } else { '<无>' }
        $n = if ($i -lt $newLines.Count) { $newLines[$i] } else { '<无>' }
        if ($o -cne $n) {
            Write-Host ("    - {0}" -f $o.TrimEnd())
            Write-Host ("    + {0}" -f $n.TrimEnd())
        }
    }
    if (-not $DryRun) {
        Write-TextPreserveBom -Path $abs -Text $text -HasBom $bag.HasBom
    }
}

# ---------- 汇总与人工后置提醒 ----------

Write-Host ''
if ($changedFiles -eq 0) {
    Write-Host '结果：所有目标与 pom 当前值一致，0 个文件需要变更（幂等通过）。'
} else {
    Write-Host ("结果：{0} 个文件{1}。" -f $changedFiles, $(if ($DryRun) { '待更新（DryRun 未写盘）' } else { '已更新' }))
}

if ($slotChanged.ContainsKey('spring-boot')) {
    Write-Warning ('spring-boot 槽位有变更：standalone 模板显式钉死的 maven-compiler / maven-resources 插件版本不会自动跟随，请用 `mvn -pl every-agent-plugin-api help:effective-pom` 复核 spring-boot-dependencies 新管理值后手工更新模板两份 pom.xml.tpl。'
    )
}
if ($slotChanged.ContainsKey('spring-ai')) {
    Write-Warning 'spring-ai 槽位有变更：文档中「javap 实测」的常量值（ToolCallingAdvisor.DEFAULT_ORDER 等）只是引用了版本号，常量本身必须重新测量后再核对。'
}

exit 0
