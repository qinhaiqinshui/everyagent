/**
 * src/utils/readStringArray.ts
 *
 * 从任意值中安全读取字符串数组：非数组返回空数组；逐项过滤非字符串、
 * 去空白、去重。用于从 `unknown` 类型（如 agent/运行 metadata）里稳妥地取出
 * 字符串 ID 列表，避免 `metadata.x as string[]` 这类硬断言在结构异常时崩溃。
 *
 * 此前该逻辑在 task 层 `selectSkillIdsNode`、subAgent 插件 `subAgentSkillFilterNode`、
 * Agent 层 `loopOps.readStringArrayMetadata` 三处各自 copy；统一收敛到此公共 util，
 * 消除重复实现（函数体完全一致）。
 */

/** 从任意值中安全读取字符串数组（去重 / 去空白 / 去掉非字符串）。 */
export function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  const out: string[] = []
  const seen = new Set<string>()
  for (const item of value) {
    if (typeof item !== 'string') continue
    const normalized = item.trim()
    if (!normalized || seen.has(normalized)) continue
    seen.add(normalized)
    out.push(normalized)
  }
  return out
}
