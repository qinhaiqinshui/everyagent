/**
 * 轻量 className 合并工具（不引入额外依赖）。
 * 接受字符串 / falsy 值，过滤后拼接。
 */
export type ClassValue = string | false | null | undefined

export function cn(...values: ClassValue[]): string {
  return values.filter(Boolean).join(' ')
}
