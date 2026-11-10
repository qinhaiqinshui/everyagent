import { describe, expect, it } from 'vitest'
import { allocatePasteName, duplicateName } from './duplicateName'

describe('duplicateName', () => {
  it('首个冲突生成「- 副本」后缀', () => {
    expect(duplicateName('a.txt', 1)).toBe('a - 副本.txt')
  })

  it('继续冲突按序号递增', () => {
    expect(duplicateName('a.txt', 2)).toBe('a - 副本 2.txt')
    expect(duplicateName('a.txt', 10)).toBe('a - 副本 10.txt')
  })

  it('扩展名取最后一个点之后(多点名)', () => {
    expect(duplicateName('c.tar.gz', 1)).toBe('c.tar - 副本.gz')
  })

  it('无扩展名整体处理', () => {
    expect(duplicateName('b', 1)).toBe('b - 副本')
    expect(duplicateName('b', 3)).toBe('b - 副本 3')
  })

  it('点开头的隐藏文件按整体名处理(无扩展段)', () => {
    expect(duplicateName('.gitignore', 1)).toBe('.gitignore - 副本')
  })

  it('index 下限钳制为 1', () => {
    expect(duplicateName('a.txt', 0)).toBe('a - 副本.txt')
    expect(duplicateName('a.txt', -5)).toBe('a - 副本.txt')
  })
})

describe('allocatePasteName', () => {
  it('原名无冲突时直接用原名', async () => {
    expect(await allocatePasteName('a.txt', async () => false)).toBe('a.txt')
  })

  it('原名冲突时生成副本名', async () => {
    const taken = new Set(['a.txt'])
    expect(await allocatePasteName('a.txt', async (n) => taken.has(n))).toBe('a - 副本.txt')
  })

  it('副本名也冲突时序号递增', async () => {
    const taken = new Set(['a.txt', 'a - 副本.txt', 'a - 副本 2.txt'])
    expect(await allocatePasteName('a.txt', async (n) => taken.has(n))).toBe('a - 副本 3.txt')
  })

  it('全部冲突时返回最后一次候选(不无限循环)', async () => {
    expect(await allocatePasteName('a.txt', async () => true, 5)).toBe('a - 副本 5.txt')
  })
})
