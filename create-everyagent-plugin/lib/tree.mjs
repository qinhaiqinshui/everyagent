/**
 * lib/tree.mjs —— --dry-run 文件树打印 + 每个文件一句话作用。
 *
 * 作用说明来源（优先级从高到低）：
 *   1) 模板文件前 10 行内的 `ea: 一句话作用` 标记（见 lib/render.mjs 契约第六节）；
 *   2) 本文件的内置表（按渲染后的文件名 / 路径前缀匹配）；
 *   3) 兜底文案 '生成文件'。
 * 步骤 4~6 往模板里加新文件类型时，只需在模板里写 ea: 标记，不必改本文件。
 */

import { extractPurpose } from './render.mjs'

/** 按渲染后的文件名精确匹配。 */
const PURPOSE_BY_NAME = new Map([
  ['plugin.json', '插件清单（id/name/version/main/webMain/enabled），宿主扫描与加载的唯一依据'],
  ['pom.xml', 'Maven 构建定义（parent=every-agent-parent；插件不进根 reactor，须单独 mvn -f 构建）'],
  ['README.md', '本插件的构建 / 调试 / 打包说明'],
  ['.gitignore', '忽略 target/、web/index.js 等构建产物（与仓库根 .gitignore 一致）'],
  ['.gitkeep', '占位空目录（渲染时保留）'],
  ['package.json', 'standalone 形态的 npm 清单（builtin 形态复用宿主，不生成此文件）'],
  ['tsconfig.json', 'standalone 形态的 TS 配置（paths 指向 vendor 类型副本）'],
  ['build.mjs', 'standalone 前端构建脚本（esbuild，external 白名单与宿主 build-plugins.mjs 一致）'],
  ['index.ts', '前端入口（导出 PluginModule.activate，宿主经 plugin.webSource 加载）'],
  ['index.js', '前端 bundle 产物（由宿主 esbuild 生成，不入库）'],
])

/** 按渲染后路径前缀 / 后缀匹配（顺序敏感：先具体后泛化）。 */
const PURPOSE_BY_RULE = [
  [/\/[A-Z][A-Za-z0-9]*Plugin\.java$/, '插件入口类（实现 EveryAgentPlugin，activate() 里注册扩展点）'],
  [/^src\/test\/java\//, '插件单元测试（只依赖 every-agent-plugin-api，不得依赖 worker）'],
  [/^src\/main\/java\//, '插件 Java 源码'],
  [/^src\/main\/resources\//, '插件资源文件（打进 jar）'],
  [/^web\//, '插件前端源码'],
  [/^vendor\//, 'standalone 自带的 plugin-api 类型副本'],
  [/^scripts\//, '插件构建脚本'],
  [/\.d\.ts$/, '类型声明副本'],
  [/\.tsx$/, '前端 React 组件'],
  [/\.css$/, '前端样式'],
]

const FALLBACK_PURPOSE = '生成文件'

/**
 * 取一个产物文件的一句话作用。
 * @param {string} relPath 渲染后的相对路径
 * @param {string|null} [inlinePurpose] 模板里的 ea: 标记
 * @returns {string}
 */
export function purposeFor(relPath, inlinePurpose) {
  if (inlinePurpose) return inlinePurpose
  const base = relPath.split('/').pop()
  if (PURPOSE_BY_NAME.has(base)) return PURPOSE_BY_NAME.get(base)
  for (const [re, text] of PURPOSE_BY_RULE) {
    if (re.test(relPath)) return text
  }
  return FALLBACK_PURPOSE
}

/** 把扁平产物清单折成嵌套树。 */
function buildTree(files) {
  const root = { dirs: new Map(), files: [] }
  for (const file of files) {
    const segments = file.relPath.split('/')
    let node = root
    for (let i = 0; i < segments.length - 1; i++) {
      const seg = segments[i]
      if (!node.dirs.has(seg)) node.dirs.set(seg, { dirs: new Map(), files: [] })
      node = node.dirs.get(seg)
    }
    node.files.push({ name: segments[segments.length - 1], file })
  }
  return root
}

function sortKeys(map) {
  return [...map.keys()].sort((a, b) => a.localeCompare(b, 'en'))
}

/**
 * 渲染文件树文本。
 * @param {object} input
 * @param {string} input.title 根节点显示名（一般是插件目录名）
 * @param {Array<{relPath:string, purpose?:string|null, overriddenSource?:string|null}>} input.files
 * @param {(relPath:string, inline?:string|null)=>string} [input.purposeOf]
 * @returns {string[]}
 */
export function renderTree({ title, files, purposeOf = purposeFor }) {
  const withPurpose = files.map((f) => ({
    ...f,
    purpose: f.purposeText || purposeOf(f.relPath, f.purpose || null),
  }))
  const tree = buildTree(withPurpose)

  const annotated = []
  const collectLines = (node, prefix) => {
    const entries = []
    for (const dir of sortKeys(node.dirs)) entries.push({ kind: 'dir', name: `${dir}/`, key: dir })
    for (const item of node.files.sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
      entries.push({ kind: 'file', name: item.name, key: item.name, file: item.file })
    }
    entries.forEach((entry, index) => {
      const last = index === entries.length - 1
      const branch = last ? '└── ' : '├── '
      if (entry.kind === 'file') {
        const tail = entry.file.overriddenSource
          ? `${entry.file.purpose}（覆盖 ${entry.file.overriddenSource}）`
          : entry.file.purpose
        annotated.push({ text: `${prefix}${branch}${entry.name}`, purpose: tail })
      } else {
        annotated.push({ text: `${prefix}${branch}${entry.name}`, purpose: '' })
      }
      if (entry.kind === 'dir') {
        collectLines(node.dirs.get(entry.key), prefix + (last ? '    ' : '│   '))
      }
    })
  }
  collectLines(tree, '')

  const width = Math.min(
    Math.max(...annotated.map((l) => l.text.length), title.length + 1),
    64,
  )
  const lines = [`${title}/`]
  for (const item of annotated) {
    lines.push(item.purpose ? `${item.text.padEnd(width + 2)}${item.purpose}` : item.text)
  }
  return lines
}

/**
 * --dry-run / 写盘前统一的计划文本。
 * @param {object} input
 * @param {string} input.target 目标目录绝对路径
 * @param {string} input.displayTarget 展示用路径
 * @param {object} input.spec
 * @param {Record<string,string>} input.vars
 * @param {string} input.root 模板根
 * @param {boolean} input.fromEnv 模板根是否来自 EA_PLUGIN_TEMPLATES
 * @param {string[]} input.layersUsed
 * @param {Array<object>} input.files
 * @param {string[]} [input.overwritten] --force 时将被覆盖的既有文件
 * @returns {string}
 */
export function formatPlan({
  target,
  displayTarget,
  spec,
  vars,
  root,
  fromEnv,
  layersUsed,
  files,
  overwritten = [],
}) {
  const title = displayTarget.replace(/\/+$/, '').split('/').pop() || vars.pluginId
  const lines = [
    `插件 id      ${vars.pluginId}`,
    `形态         kind=${spec.kind}  mode=${spec.mode}`,
    `入口类       ${vars.mainClass}`,
    `Java 包      ${vars.package}`,
    `目标目录     ${displayTarget}`,
    fromEnv ? `模板根       ${root}（来自环境变量 EA_PLUGIN_TEMPLATES）` : `模板根       ${root}`,
    `命中模板层   ${layersUsed.join(' → ')}`,
    `文件数       ${files.length}`,
    '',
    `将生成的文件树（绝对路径 ${target}）：`,
    '',
    ...renderTree({ title, files }),
  ]
  if (overwritten.length) {
    lines.push('', `将被覆盖的既有文件（${overwritten.length} 个）：`)
    lines.push(...overwritten.map((rel) => `  ${rel}`))
  }
  return lines.join('\n')
}
