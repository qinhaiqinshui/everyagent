/**
 * 解析 every-agent-plugins/pom.xml,返回所有未被 XML 注释的 <module> 名称集合。
 *
 * 用途:构建脚本感知插件启停——被注释的插件不参与编译,其附属的 runtime 资源
 * 也不应被打进安装包。
 *
 * @param {string} repoRoot 仓库根目录(含 every-agent-plugins/pom.xml)
 * @returns {Set<string>} 活跃(未注释)的插件模块名集合
 */
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'

export function getActivePluginModules(repoRoot) {
  const pomPath = join(repoRoot, 'every-agent-plugins', 'pom.xml')
  if (!existsSync(pomPath)) return new Set()

  const content = readFileSync(pomPath, 'utf8')
  // 移除所有 XML 注释块,再从剩余内容提取 <module> 标签
  const withoutComments = content.replace(/<!--[\s\S]*?-->/g, '')
  const modules = [...withoutComments.matchAll(/<module>\s*([^<\s]+)\s*<\/module>/g)]
    .map((m) => m[1].trim())
  return new Set(modules)
}
