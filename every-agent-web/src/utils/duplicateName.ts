/**
 * 粘贴副本名生成(对标 VSCode explorer 的「xxx copy.txt」;本应用中文语境用
 * 「xxx - 副本.txt」,与 VSCode 中文语言包一致)。
 *
 * 规则(与 VSCode 同构):
 * - 首个冲突:`name - 副本.ext`;继续冲突:`name - 副本 2.ext`、`name - 副本 3.ext`…;
 * - 扩展名取**最后一个点**之后(`c.tar.gz` → `c.tar - 副本.gz`);
 * - 无扩展名(`b`)、点在开头的隐藏文件(`.gitignore`)都按整体名处理(无扩展段)。
 * 纯函数,存在性探测由调用方负责(existsRaw 探测 → 冲突则用本函数递增序号再探测)。
 */

/** 取 `name - 副本`(index=1)或 `name - 副本 N`(index≥2)形态的目标名。 */
export function duplicateName(name: string, index: number): string {
  const n = Math.max(1, Math.trunc(index))
  const suffix = n <= 1 ? ' - 副本' : ` - 副本 ${n}`
  const dot = name.lastIndexOf('.')
  // 无点或点在开头(隐藏文件如 .gitignore)→ 整体视为名称,无扩展段
  if (dot <= 0) return `${name}${suffix}`
  return `${name.slice(0, dot)}${suffix}${name.slice(dot)}`
}

/**
 * 逐个探测生成目标目录下不冲突的名字:原名可用即原名;否则「- 副本」起按序号递增。
 * exists 为异步存在性探测(目标目录下的候选名);最多尝试 maxAttempts 次,全部冲突时
 * 返回最后一次候选(由落盘 RPC 兜底报错,防病态循环)。
 */
export async function allocatePasteName(
  name: string,
  exists: (candidate: string) => Promise<boolean>,
  maxAttempts = 100,
): Promise<string> {
  if (!(await exists(name))) return name
  for (let i = 1; i <= maxAttempts; i += 1) {
    const candidate = duplicateName(name, i)
    if (!(await exists(candidate))) return candidate
  }
  return duplicateName(name, maxAttempts)
}
