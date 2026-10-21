/**
 * test/sdk-version.test.mjs —— 版本守卫：CLI 内嵌 everyagent.sdkVersion 必须与宿主根 pom <revision> 一致。
 *
 * 背景：CLI 发布到 npm 后（`npm create everyagent-plugin` 场景），宿主仓库不在身边，
 * standalone 形态生成的插件 pom 的 parent 版本取 CLI 自身 package.json 的
 * everyagent.sdkVersion（lib/naming.mjs 的 readEmbeddedSdkVersion）。该值一旦落后于
 * 工程版本，仓库外生成的工程就会钉到旧 SDK——且不报错，用户构建时才发现。
 * 故本测试钉住「内嵌值 == 宿主 <revision>」，drift 时 `node --test` 直接失败。
 *
 * 修复方式：在仓库根执行
 *   powershell -ExecutionPolicy Bypass -File scripts\sync-plugin-template-versions.ps1
 * （该脚本的 revision 槽位已覆盖 create-everyagent-plugin/package.json 的 sdkVersion 字段。）
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

test('CLI 内嵌 everyagent.sdkVersion 与宿主根 pom <revision> 一致', (t) => {
  // 仓库级守卫：宿主根 pom 不在（npm 发布包 / 单独拷走）时跳过，不误报失败。
  const rootPom = join(repoRoot, 'pom.xml')
  if (!existsSync(rootPom)) {
    t.skip(`找不到宿主根 pom.xml（${rootPom}）——非仓库环境，跳过版本守卫`)
    return
  }

  const raw = readFileSync(rootPom, 'utf8')
  const m = raw.match(/<revision>([^<]+)<\/revision>/)
  assert.ok(m, '宿主根 pom.xml 缺 <revision> 属性（工程版本单点源）')

  const pkg = JSON.parse(readFileSync(join(packageRoot, 'package.json'), 'utf8'))
  const embedded = pkg?.everyagent?.sdkVersion
  assert.ok(
    typeof embedded === 'string' && embedded.trim(),
    'CLI package.json 缺 everyagent.sdkVersion 字段（npm 发布后 standalone 形态的 SDK 版本来源）',
  )
  assert.equal(
    embedded.trim(),
    m[1].trim(),
    `CLI 内嵌 SDK 版本（${embedded}）与宿主工程版本（${m[1].trim()}）漂移。修复：` +
      `powershell -ExecutionPolicy Bypass -File scripts\\sync-plugin-template-versions.ps1`,
  )
})
