<#
.SYNOPSIS
宿主 plugin-api 类型单点源 → 脚手架 standalone 模板 vendor 类型副本 一键同步（内容同步，非版本同步）。

.DESCRIPTION
脚手架 standalone 形态的工程自带一份 `@everyagent/plugin-api` 类型副本（`index.d.ts.raw`，`.raw`
后缀意为「渲染时逐字节复制、不做占位符替换」）。该副本**是宿主真源码的逐字节镜像**：

  单点源（只读）                                            同步目标（写入，逐字节覆盖）
  -------------------------------------------------------  -------------------------------------------------------------
  every-agent-plugin-api/js/index.ts                       create-everyagent-plugin/templates/overlay/web-standalone/
                                                             vendor/@everyagent/plugin-api/index.d.ts.raw
                                                           create-everyagent-plugin/templates/overlay/full-standalone/
                                                             vendor/@everyagent/plugin-api/index.d.ts.raw
                                                           every-agent-plugin-api/js/index.d.ts
                                                             （npm 发布包的类型入口镜像，package.json 的 types 指向它）

为什么需要本脚本：该副本是手工复制的，宿主每加一个 `ctx.ui.*` 方法 / 扩展点接口，副本就落后一点，
且**不会报错**——standalone 插件在仓库外 `tsc` 时只会「少一个方法」，直到用户踩坑才发现。这个坑
已经踩过两次（`ca0211e7` 那次、以及 `openTab` 更名那次）。故立此脚本 + 配套
漂移守卫测试（`create-everyagent-plugin/test/plugin-api-copy.test.mjs`，`npm test` 会失败）。

与 `sync-plugin-template-versions.ps1` 的分工（互相排除，勿混）：
  - 版本同步（Java / Spring Boot / Spring AI / revision 等**版本字面量**）→ 那个脚本；
  - 内容同步（本文件，类型副本的**逐字节内容**）→ 本脚本。
  两个脚本各自在文件头声明「对方的事不归自己管」，保持单一职责。

.PARAMETER DryRun
只打印将发生的变更（按行 diff 摘要），不写盘。

.EXAMPLE
# 预览（不写盘）
powershell -ExecutionPolicy Bypass -File scripts\sync-plugin-api-copy.ps1 -DryRun

# 实际同步
powershell -ExecutionPolicy Bypass -File scripts\sync-plugin-api-copy.ps1

.NOTES
PowerShell 5.1 兼容（无 && / || / ?? 等新语法），零第三方依赖；退出码 0 成功 / 1 失败。
#>

param(
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$sourceRel = 'every-agent-plugin-api/js/index.ts'
$targetRels = @(
    'create-everyagent-plugin/templates/overlay/web-standalone/vendor/@everyagent/plugin-api/index.d.ts.raw',
    'create-everyagent-plugin/templates/overlay/full-standalone/vendor/@everyagent/plugin-api/index.d.ts.raw',
    'every-agent-plugin-api/js/index.d.ts'
)

$sourceAbs = Join-Path $root $sourceRel
if (-not (Test-Path $sourceAbs)) { Write-Error "找不到单点源：$sourceAbs" }

# 逐字节读源（副本口径就是字节镜像，不做 BOM/行尾归一化）。
$sourceBytes = [IO.File]::ReadAllBytes($sourceAbs)

Write-Host ("单点源：{0}（{1} 字节）" -f $sourceRel, $sourceBytes.Length)
if ($DryRun) { Write-Host '模式：-DryRun（只打印变更，不写盘）' } else { Write-Host '模式：实际同步' }
Write-Host ''

$changed = 0
$missing = 0
foreach ($rel in $targetRels) {
    $abs = Join-Path $root $rel
    if (-not (Test-Path $abs)) {
        Write-Warning "目标不存在，跳过：$rel"
        $missing++
        continue
    }

    $currentBytes = [IO.File]::ReadAllBytes($abs)
    $same = ($currentBytes.Length -eq $sourceBytes.Length)
    if ($same) {
        for ($i = 0; $i -lt $sourceBytes.Length; $i++) {
            if ($currentBytes[$i] -ne $sourceBytes[$i]) { $same = $false; break }
        }
    }

    if ($same) {
        Write-Host ("= {0}（已一致，幂等跳过）" -f $rel)
        continue
    }

    $changed++
    Write-Host ("--> {0}" -f $rel)
    Write-Host ("    字节数 {0} -> {1}" -f $currentBytes.Length, $sourceBytes.Length)

    # 文本层 diff 摘要（归一化行尾后按行比较；只影响可读性，不影响写盘口径）。
    $srcText = [Text.Encoding]::UTF8.GetString($sourceBytes).Replace("`r`n", "`n").Replace("`r", "`n")
    $curText = [Text.Encoding]::UTF8.GetString($currentBytes).Replace("`r`n", "`n").Replace("`r", "`n")
    $srcLines = $srcText -split "`n"
    $curLines = $curText -split "`n"
    $max = [Math]::Max($srcLines.Count, $curLines.Count)
    $shown = 0
    for ($i = 0; $i -lt $max; $i++) {
        $c = if ($i -lt $curLines.Count) { $curLines[$i] } else { '<无>' }
        $s = if ($i -lt $srcLines.Count) { $srcLines[$i] } else { '<无>' }
        if ($c -cne $s) {
            if ($shown -lt 12) {
                Write-Host ("      L{0}- / {1}" -f ($i + 1), $c.TrimEnd())
                Write-Host ("      L{0}+ / {1}" -f ($i + 1), $s.TrimEnd())
            }
            $shown++
        }
    }
    if ($shown -gt 12) { Write-Host ("      …… 其余 {0} 处差异略（见完整 diff）" -f ($shown - 12)) }

    if (-not $DryRun) {
        [IO.File]::WriteAllBytes($abs, $sourceBytes)
    }
}

Write-Host ''
if ($changed -eq 0) {
    Write-Host '结果：全部副本与单点源逐字节一致，0 个文件需要变更（幂等通过）。'
} else {
    Write-Host ("结果：{0} 个文件{1}。" -f $changed, $(if ($DryRun) { '待更新（DryRun 未写盘）' } else { '已更新' }))
}
if ($missing -gt 0) {
    Write-Warning ("有 {0} 个目标缺失——若模板目录结构变更，请同步更新本脚本的目标清单。" -f $missing)
}
if (-not $DryRun -and $changed -gt 0) {
    Write-Host '提示：改完请跑 `node --test`（在 create-everyagent-plugin/ 下）确认漂移守卫测试通过。'
}

exit 0