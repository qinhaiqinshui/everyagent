/**
 * workspaceExplorerTreeUtils 选中集合清理纯函数特征化测试。
 *
 * 目的:锁定「文件树刷新后清理选中 key」的行为口径——删除后移除被删项及其子树、
 * 目录刷新后只清理被刷新目录范围内已不存在的 key(范围外/未加载子树里的选中项、
 * 工作区外绝对路径一律保留)、无变化时返回原集合引用(调用方据此免去无谓 setState)。
 *
 * 背景 bug:树内新建文件后树不刷新需手动刷新;修复为「只刷新所在目录」后,
 * 刷新会带来选中集合与树不一致的可能,清理口径由这两个纯函数唯一承担。
 */
import { describe, it, expect } from 'vitest'
import {
  mergeExplorerChildrenPreservingLoaded,
  pruneSelectionUnderDirectory,
  removePathsFromSelection,
} from './workspaceExplorerTreeUtils'
import type { WorkspaceExplorerNode } from '@/types/workspaceExplorer'

function file(path: string): WorkspaceExplorerNode {
  const name = path.slice(path.lastIndexOf('/') + 1)
  return { path, name, type: 'file', size: 0, mtimeMs: 0, createdTs: 0 }
}

function dir(path: string, children?: WorkspaceExplorerNode[]): WorkspaceExplorerNode {
  const name = path.slice(path.lastIndexOf('/') + 1)
  return { path, name, type: 'directory', size: 0, mtimeMs: 0, createdTs: 0, children, loaded: children !== undefined }
}

describe('removePathsFromSelection', () => {
  it('移除被删路径本身', () => {
    const selected = new Set(['/src/a.ts'])
    expect(removePathsFromSelection(selected, ['/src/a.ts'])).toEqual(new Set())
  })

  it('移除被删目录的整棵子树,其余选中项保持', () => {
    const selected = new Set(['/src', '/src/a.ts', '/src/deep/b.ts', '/other/c.ts'])
    expect(removePathsFromSelection(selected, ['/src'])).toEqual(new Set(['/other/c.ts']))
  })

  it('前缀相近但不在子树内的路径不误删', () => {
    const selected = new Set(['/src', '/src-extra/a.ts'])
    expect(removePathsFromSelection(selected, ['/src'])).toEqual(new Set(['/src-extra/a.ts']))
  })

  it('无变化时返回原集合引用', () => {
    const selected = new Set(['/keep.ts'])
    expect(removePathsFromSelection(selected, ['/deleted.ts'])).toBe(selected)
    expect(removePathsFromSelection(selected, [])).toBe(selected)
  })
})

describe('pruneSelectionUnderDirectory', () => {
  const tree = [
    dir('/src', [dir('/src/deep'), file('/src/a.ts')]),
    file('/README.md'),
  ]

  it('普通目录:清理该目录范围内已不存在的 key', () => {
    const selected = new Set(['/src/gone.ts', '/src/a.ts', '/README.md'])
    expect(pruneSelectionUnderDirectory('/src', tree, selected)).toEqual(new Set(['/src/a.ts', '/README.md']))
  })

  it('普通目录:范围外的选中项不因「树中找不到」被清理', () => {
    // /other/x.ts 位于未加载子树(树里没有该节点),但不在被刷新目录范围内 → 保留
    const selected = new Set(['/other/x.ts'])
    expect(pruneSelectionUnderDirectory('/src', tree, selected)).toBe(selected)
  })

  it('工作区根刷新:覆盖第一层及其子树', () => {
    const selected = new Set(['/gone.ts', '/README.md', '/src/a.ts', '/src/gone.ts'])
    expect(pruneSelectionUnderDirectory('/', tree, selected)).toEqual(new Set(['/README.md', '/src/a.ts']))
  })

  it('工作区外绝对路径(不在树中)一律保留', () => {
    const selected = new Set(['/C:/Users/me/a.txt', '//home/me/b.txt'])
    expect(pruneSelectionUnderDirectory('/', tree, selected)).toBe(selected)
  })

  it('无变化时返回原集合引用', () => {
    const selected = new Set(['/src/a.ts'])
    expect(pruneSelectionUnderDirectory('/src', tree, selected)).toBe(selected)
  })

  it('空选中集合原样返回', () => {
    const selected = new Set<string>()
    expect(pruneSelectionUnderDirectory('/', tree, selected)).toBe(selected)
  })
})

describe('mergeExplorerChildrenPreservingLoaded(根刷新复用同一合并口径)', () => {
  it('保留仍存在且已加载的子目录子树,新节点按最新列表', () => {
    const existing = [dir('/src', [file('/src/a.ts')]), dir('/old', [])]
    const fresh = [dir('/src'), dir('/new')]
    const merged = mergeExplorerChildrenPreservingLoaded(existing, fresh)
    expect(merged.map((node) => node.path)).toEqual(['/src', '/new'])
    expect(merged[0].children).toEqual([file('/src/a.ts')])
    expect(merged[0].loaded).toBe(true)
    expect(merged[1].children).toBeUndefined()
  })
})