/**
 * 设置页扫描式目录 Hook。
 *
 * 目录唯一来源是 DOM 扫描：在滚动容器（WorkspacePageShell 的 body）内
 * `querySelectorAll('[data-settings-section-id]')` 取段落元素，读段内第一个
 * `<h3>` 文本作目录项标题——内置段与插件段一视同仁，零硬编码清单。
 *
 * - MutationObserver 监听容器子树（childList + characterData），段落增删 / 标题
 *   变化（含插件段晚注册）时自动重建目录。
 * - scroll（passive + rAF 节流）按各段相对滚动容器的顶部位置计算视口顶部当前
 *   所在段；滚动到底部时视为激活最后一段。
 * - scrollToSection(id) 平滑滚动到段顶部。header 是滚动容器外的 flex 兄弟节点、
 *   不占容器滚动空间，容器内相对定位即可；统一用 getBoundingClientRect 差值换算，
 *   不依赖 offsetParent 链。
 */
import React from 'react'
import { SETTINGS_SECTION_ID_ATTR } from './settingsSectionIds'

export type SettingsSectionDirectoryEntry = {
  /** 段锚点 id（data-settings-section-id 属性值）。 */
  id: string
  /** 目录项标题（段内第一个 h3 的文本，缺省回落到 id）。 */
  title: string
  /** 段元素（跳转定位 / 激活计算用）。 */
  element: HTMLElement
}

export type SettingsSectionDirectory = {
  /** 目录项（按 DOM 顺序）。 */
  entries: SettingsSectionDirectoryEntry[]
  /** 视口顶部当前所在段的锚点 id（无段落时为 null）。 */
  activeId: string | null
  /** 平滑滚动到指定段顶部。 */
  scrollToSection: (id: string) => void
}

/** 判定「已滚动到底部」的像素容差。 */
const BOTTOM_THRESHOLD = 4

/** 判定段顶已越过视口顶部的像素容差。 */
const TOP_THRESHOLD = 1

/** 计算元素顶部相对滚动容器滚动内容顶部的位置（不依赖 offsetParent 链）。 */
function offsetTopWithin(element: HTMLElement, container: HTMLElement): number {
  return element.getBoundingClientRect().top - container.getBoundingClientRect().top + container.scrollTop
}

/** 扫描容器内全部设置段落，生成目录项列表（标题取段内第一个 h3 文本）。 */
function scanSectionEntries(root: HTMLElement): SettingsSectionDirectoryEntry[] {
  const entries: SettingsSectionDirectoryEntry[] = []
  root.querySelectorAll<HTMLElement>(`[${SETTINGS_SECTION_ID_ATTR}]`).forEach((element) => {
    const id = element.getAttribute(SETTINGS_SECTION_ID_ATTR)
    if (!id) return
    const title = (element.querySelector('h3')?.textContent ?? '').trim()
    entries.push({ id, title: title || id, element })
  })
  return entries
}

export function useSettingsSectionDirectory(
  scrollContainerRef: React.RefObject<HTMLDivElement>,
): SettingsSectionDirectory {
  const [entries, setEntries] = React.useState<SettingsSectionDirectoryEntry[]>([])
  const [activeId, setActiveId] = React.useState<string | null>(null)
  /** 始终持有最新目录（含未触发重渲的元素替换），滚动计算与跳转取值不经渲染闭包。 */
  const entriesRef = React.useRef<SettingsSectionDirectoryEntry[]>([])

  const rescanSections = React.useCallback((root: HTMLElement): void => {
    const next = scanSectionEntries(root)
    const prev = entriesRef.current
    const unchanged = prev.length === next.length
      && prev.every((item, index) => item.id === next[index].id && item.title === next[index].title)
    entriesRef.current = next
    if (!unchanged) setEntries(next)
  }, [])

  const computeActiveId = React.useCallback((root: HTMLElement): void => {
    const list = entriesRef.current
    if (list.length === 0) {
      setActiveId(null)
      return
    }
    // 最后一段特殊处理：滚动到底部时视为激活最后一段（尾段过短时顶部判定够不到）。
    if (root.scrollTop + root.clientHeight >= root.scrollHeight - BOTTOM_THRESHOLD) {
      setActiveId(list[list.length - 1].id)
      return
    }
    let currentId = list[0].id
    for (const item of list) {
      if (offsetTopWithin(item.element, root) <= root.scrollTop + TOP_THRESHOLD) {
        currentId = item.id
      } else {
        break
      }
    }
    setActiveId(currentId)
  }, [])

  React.useEffect(() => {
    const root = scrollContainerRef.current
    if (!root) return

    rescanSections(root)
    computeActiveId(root)

    // 段落增删 / 标题文本变化（插件段晚注册、内容重渲）→ 重建目录并重算激活段。
    const observer = new MutationObserver(() => {
      rescanSections(root)
      computeActiveId(root)
    })
    observer.observe(root, { childList: true, subtree: true, characterData: true })

    let rafId: number | null = null
    const handleScroll = () => {
      if (rafId !== null) return
      rafId = requestAnimationFrame(() => {
        rafId = null
        computeActiveId(root)
      })
    }
    root.addEventListener('scroll', handleScroll, { passive: true })
    // 视口尺寸变化会改变各段位置，复用同一节流通道重算激活段。
    window.addEventListener('resize', handleScroll, { passive: true })

    return () => {
      observer.disconnect()
      root.removeEventListener('scroll', handleScroll)
      window.removeEventListener('resize', handleScroll)
      if (rafId !== null) cancelAnimationFrame(rafId)
    }
  }, [scrollContainerRef, rescanSections, computeActiveId])

  const scrollToSection = React.useCallback((id: string): void => {
    const root = scrollContainerRef.current
    const target = entriesRef.current.find((item) => item.id === id)
    if (!root || !target) return
    root.scrollTo({ top: Math.max(0, offsetTopWithin(target.element, root)), behavior: 'smooth' })
  }, [scrollContainerRef])

  return { entries, activeId, scrollToSection }
}

export default useSettingsSectionDirectory
