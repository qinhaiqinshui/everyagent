#!/usr/bin/env node
/**
 * skill.md 组装脚本:把 src/ 资产(scripts + extension)打包为 base64 manifest,
 * 附在 build/skill-body.md(正文模板)之后,生成 skill/browser-automation/skill.md。
 *
 * 用法:node build/pack.mjs(在插件根目录执行;幂等可重复)
 * 产物尾部形态:
 *   <<<EA-ASSETS
 *   <base64(JSON)>
 *   EA-ASSETS>>>
 * 首次使用时 AI 按安装节用 PowerShell 从本文件提取 manifest 解码落盘(自包含分发,
 * 不依赖 worker 物化机制搬运非 skill.md 文件)。
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const pluginRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const srcDir = join(pluginRoot, 'src')
const bodyFile = join(pluginRoot, 'build', 'skill-body.md')
const outFile = join(pluginRoot, 'skill', 'browser-automation', 'skill.md')

/** 递归收集目录下全部文件(相对路径,POSIX 分隔符)。 */
function collectFiles(dir) {
  const out = []
  for (const name of readdirSync(dir).sort()) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) {
      out.push(...collectFiles(p))
    } else {
      out.push(p)
    }
  }
  return out
}

const pluginJson = JSON.parse(readFileSync(join(pluginRoot, 'plugin.json'), 'utf8'))
const version = pluginJson.version || '0.0.0'

// 资产映射:src/scripts → <skill>/scripts,src/extension → <skill>/extension
const files = []
for (const [srcSub, destSub] of [['scripts', 'scripts'], ['extension', 'extension']]) {
  const dir = join(srcDir, srcSub)
  for (const f of collectFiles(dir)) {
    const rel = relative(dir, f).split(sep).join('/')
    files.push({ path: `${destSub}/${rel}`, b64: readFileSync(f).toString('base64') })
  }
}

const manifest = { version, files }
const manifestB64 = Buffer.from(JSON.stringify(manifest), 'utf8').toString('base64')

const body = readFileSync(bodyFile, 'utf8')
const skill = `${body.trimEnd()}\n\n<<<EA-ASSETS\n${manifestB64}\nEA-ASSETS>>>\n`
writeFileSync(outFile, skill)

const totalBytes = files.reduce((n, f) => n + Math.floor(f.b64.length * 3 / 4), 0)
console.log(`[pack] 生成 ${relative(pluginRoot, outFile)}: ${files.length} 个资产文件, 解码后共 ${(totalBytes / 1024).toFixed(1)}KB, skill.md ${(Buffer.byteLength(skill) / 1024).toFixed(1)}KB, version=${version}`)
