/**
 * git 插件内部路径纯函数（自宿主 platform/fs/pathUtils 复制，插件不引用宿主模块）。
 *
 * 路径坐标系：工作区相对路径——空串 = 工作区根，目录间以 `/` 分隔，无前导 `/`。
 */

/** 规范化工作区相对路径：反斜杠归一、去重复分隔符、去首尾分隔符。 */
export function normalizeWorkspaceRelativePath(value: string): string {
  return value
    .replace(/\\/g, '/')
    .replace(/^\/+/, '')
    .replace(/\/+/g, '/')
    .split('/')
    .filter(Boolean)
    .join('/')
}
