/**
 * test/plugin-api-copy.test.mjs —— 漂移守卫：脚手架 vendor 类型副本必须与宿主单点源逐字节一致。
 *
 * 背景：standalone 形态的生成工程自带一份 `@everyagent/plugin-api` 类型副本
 * （`templates/overlay/{web-standalone,full-standalone}/vendor/@everyagent/plugin-api/index.d.ts.raw`），
 * 它是宿主真源码 `every-agent-plugin-api/js/index.ts` 的**逐字节镜像**（`.raw` = 渲染时不做占位符替换）。
 *
 * 这个副本是手工复制的，宿主每加一个 `ctx.ui.*` 方法 / 扩展点接口它就落后一点，而且**不会报错**——
 * standalone 插件在仓库外 `tsc` 时只是「少一个方法」，用户踩坑才发现。此坑已发生两次：
 *   - 提交 `ca0211e7` 那次逐字节重拷；
 *   - `openPluginTab` 更名 `openTab` 那次（副本里还留着旧名 `openPluginTab`）。
 * 故本测试钉住「副本 == 单点源」，drift 时 `node --test` 直接失败。
 *
 * 修复方式：在仓库根执行
 *   powershell -ExecutionPolicy Bypass -File scripts\sync-plugin-api-copy.ps1
 * （加 `-DryRun` 可先预览）。详见该脚本文件头与 create-everyagent-plugin/README.md。
 *
 * 跑法：node --test（或 npm test），在 create-everyagent-plugin/ 下执行。
 */

import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { readFileSync, existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const packageRoot = resolve(here, '..')
const repoRoot = resolve(packageRoot, '..')

/** 宿主单点源（权威）。 */
const SOURCE = join(repoRoot, 'every-agent-plugin-api', 'js', 'index.ts')

/** 全部类型镜像副本（新增 standalone 模板时同步登记在此）。除两份 vendor 副本外，
 *  还包括 npm 发布包的类型入口 every-agent-plugin-api/js/index.d.ts（types 字段指向它）。 */
const COPIES = [
  join(packageRoot, 'templates', 'overlay', 'web-standalone', 'vendor', '@everyagent', 'plugin-api', 'index.d.ts.raw'),
  join(packageRoot, 'templates', 'overlay', 'full-standalone', 'vendor', '@everyagent', 'plugin-api', 'index.d.ts.raw'),
  join(repoRoot, 'every-agent-plugin-api', 'js', 'index.d.ts'),
]

/**
 * 与 lib/render.mjs 的 readTemplate 同口径：去 BOM、CRLF/CR → LF。
 * 副本写盘是逐字节复制，理论上连字节都一致；这里归一化只是为了在「行尾被工具改过」时
 * 仍能给出「内容一致」的判定，避免把行尾噪声误报成 API 漂移。
 */
function normalize(buffer) {
  let text = buffer.toString('utf8')
  if (text.startsWith('\uFEFF')) text = text.slice(1)
  return text.replace(/\r\n?/g, '\n')
}

test('vendor 类型副本与宿主单点源逐字节一致（去 BOM/行尾归一化后）', (t) => {
  // 本测试是**仓库级**守卫：它读宿主单点源，故只在仓库内（create-everyagent-plugin/ 与
  // every-agent-plugin-api/ 同级）有意义。包被发布/单独拷走后源不存在，跳过而非误报失败。
  if (!existsSync(SOURCE)) {
    t.skip(`找不到宿主单点源（${SOURCE}）——非仓库环境，跳过漂移守卫`)
    return
  }

  const sourceRaw = readFileSync(SOURCE)
  for (const copyPath of COPIES) {
    const copyRaw = readFileSync(copyPath)

    // 1) 逐字节：这是副本的正确口径，任何偏差都直接失败并给出字节数线索。
    const byteIdentical = copyRaw.length === sourceRaw.length && copyRaw.equals(sourceRaw)
    if (!byteIdentical) {
      const sourceText = normalize(sourceRaw)
      const copyText = normalize(copyRaw)
      const sourceLines = sourceText.split('\n')
      const copyLines = copyText.split('\n')
      const missing = sourceLines.filter((line) => !copyLines.includes(line))
      const suggestion = [
        `脚手架 vendor 类型副本已落后于单点源，请执行：`,
        `  powershell -ExecutionPolicy Bypass -File scripts\\sync-plugin-api-copy.ps1`,
        `  源：${SOURCE}`,
        `  副本：${copyPath}`,
        `  源 ${sourceRaw.length} 字节 / 副本 ${copyRaw.length} 字节`,
      ]
      if (missing.length > 0) {
        const sample = missing.slice(0, 8).map((line) => `      + ${line.trim()}`).join('\n')
        suggestion.push(`  副本缺失的源内容（前 ${Math.min(8, missing.length)} 行）：\n${sample}`)
      }
      assert.fail(suggestion.join('\n'))
    }

    // 2) 归一化后再断言一次：把「行尾/BOM 差异」单独区分出来，报错信息更精准。
    assert.equal(
      normalize(copyRaw),
      normalize(sourceRaw),
      `副本与单点源在归一化后仍不一致：${copyPath}`,
    )
  }
})