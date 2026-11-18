/**
 * textDiff 回滚辅助函数测试:buildRollbackBlocks / buildRollbackReplacePair。
 *
 * 锁定「反向 update_file」口径:回滚块切分与行分块同构;替换对 = 上下文 + 新文本
 * (search,定位)→ 上下文 + 旧文本(replacement);纯删除块靠上下文锚定;
 * 无任何可定位内容时返回 null。行序交错时按行号稳定排序还原真实文件行序。
 */
import { describe, it, expect } from 'vitest'
import { buildLineDiff, buildRollbackBlocks, buildRollbackReplacePair } from './textDiff'

describe('buildRollbackBlocks', () => {
  it('无变更返回空数组', () => {
    const lines = buildLineDiff('a\nb\n', 'a\nb\n')
    expect(buildRollbackBlocks(lines)).toEqual([])
  })

  it('多段变更切分为多个块,间隔的 context 行切段', () => {
    const lines = buildLineDiff('a\nB\nc\nd\nE\n', 'a\nb\nc\nd\ne\n')
    const blocks = buildRollbackBlocks(lines)
    expect(blocks).toHaveLength(2)
    // 第一块:B→b;第二块:E→e(中间 c/d 是 context)
    expect(buildRollbackReplacePair(lines, blocks[0], 0)).toEqual({
      search: 'b',
      replacement: 'B',
    })
    expect(buildRollbackReplacePair(lines, blocks[1], 0)).toEqual({
      search: 'e',
      replacement: 'E',
    })
  })

  it('整文件新增切为单块(新建文件场景)', () => {
    const lines = buildLineDiff('', 'x\ny\n')
    const blocks = buildRollbackBlocks(lines)
    expect(blocks).toHaveLength(1)
    // after 'x\ny\n' 的行集是 ['x','y',''](尾随换行切出的空行),join 后保留尾随 \n
    expect(buildRollbackReplacePair(lines, blocks[0], 0)).toEqual({
      search: 'x\ny\n',
      replacement: '',
    })
  })
})

describe('buildRollbackReplacePair', () => {
  it('纯删除块:无上下文时不可定位(null),带上下文后锚定插入位置', () => {
    // 整文件删除(无任何 context)→ 不可回滚
    const gone = buildLineDiff('a\nb\n', '')
    expect(buildRollbackBlocks(gone)).toHaveLength(1)
    expect(buildRollbackReplacePair(gone, buildRollbackBlocks(gone)[0], 1)).toBeNull()

    // 文件内删除一段:context=1 时 search=「前一行+后一行」相邻形态,replacement 把被删行放回中间
    const lines = buildLineDiff('head\nremoved1\nremoved2\ntail\n', 'head\ntail\n')
    const block = buildRollbackBlocks(lines)[0]
    expect(buildRollbackReplacePair(lines, block, 0)).toBeNull()
    expect(buildRollbackReplacePair(lines, block, 1)).toEqual({
      search: 'head\ntail',
      replacement: 'head\nremoved1\nremoved2\ntail',
    })
  })

  it('纯删除块位于文件尾:末尾空行(尾随换行)作为 context 参与锚定', () => {
    // before 'a\nb\nc\n' → after 'a\n':b/c 被删,''(尾随换行切出的空行)是 context
    const lines = buildLineDiff('a\nb\nc\n', 'a\n')
    const block = buildRollbackBlocks(lines)[0]
    const pair = buildRollbackReplacePair(lines, block, 1)
    expect(pair).not.toBeNull()
    expect(pair!.search).toBe('a\n')
    expect(pair!.replacement).toBe('a\nb\nc\n')
  })

  it('带上下文时 search/replacement 两侧上下文一致,只中段不同', () => {
    const lines = buildLineDiff('p1\np2\nOLD\nn1\nn2\n', 'p1\np2\nNEW\nn1\nn2\n')
    const block = buildRollbackBlocks(lines)[0]
    expect(buildRollbackReplacePair(lines, block, 2)).toEqual({
      search: 'p1\np2\nNEW\nn1\nn2',
      replacement: 'p1\np2\nOLD\nn1\nn2',
    })
  })

  it('上下文不足请求值时取到多少算多少(块在文件首/尾)', () => {
    const lines = buildLineDiff('OLD\ntail\n', 'NEW\ntail\n')
    const block = buildRollbackBlocks(lines)[0]
    // 块前无 context;块后 context 有 tail 与 ''(尾随换行)两行
    expect(buildRollbackReplacePair(lines, block, 3)).toEqual({
      search: 'NEW\ntail\n',
      replacement: 'OLD\ntail\n',
    })
  })

  it('块内 removed/added 交错时按行号稳定排序还原真实行序', () => {
    // 构造手工 DiffLine 序列(removed/added 交错,buildLineDiff 的 LCS 回溯可能如此输出)
    const lines = buildLineDiff('a\nx1\nx2\nb\n', 'a\ny1\ny2\nb\n')
    const block = buildRollbackBlocks(lines)[0]
    const pair = buildRollbackReplacePair(lines, block, 0)
    expect(pair!.search.split('\n')).toEqual(['y1', 'y2'])
    expect(pair!.replacement.split('\n')).toEqual(['x1', 'x2'])
  })

  it('回滚替换对可直接用于字符串替换(端到端小闭环)', () => {
    const before = 'l1\nl2\nOLD\nl4\nl5\n'
    const after = 'l1\nl2\nNEW\nl4\nl5\n'
    const lines = buildLineDiff(before, after)
    const pair = buildRollbackReplacePair(lines, buildRollbackBlocks(lines)[0], 1)
    // 当前文件 = after;按对替换(模拟 worker 的唯一匹配替换)
    expect(pair).not.toBeNull()
    expect(after.replace(pair!.search, pair!.replacement)).toBe(before)
  })
})
