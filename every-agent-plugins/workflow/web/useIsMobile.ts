/**
 * 移动端断点 hook（插件自持）。
 *
 * 宿主有 useResponsiveViewport（every-agent-web/src/hooks），但插件不得
 * import 宿主 web 模块（bare import 白名单约束），故本地经
 * matchMedia('(max-width: 768px)') 判定——断点值与宿主 MOBILE_MAX_WIDTH 对齐，
 * 保证插件面板与宿主布局在同一窗口宽度下同时切换形态。change 监听让窄窗
 * 拖动实时切换（桌面窄窗也能进入移动形态，便于开发验证）。
 */
import React from 'react'

const MOBILE_QUERY = '(max-width: 768px)'

/** 当前视口是否处于移动端断点（SSR / 无 matchMedia 环境回落 false）。 */
export function useIsMobile(): boolean {
  const [isMobile, setIsMobile] = React.useState(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
    return window.matchMedia(MOBILE_QUERY).matches
  })

  React.useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    const mql = window.matchMedia(MOBILE_QUERY)
    const onChange = () => setIsMobile(mql.matches)
    onChange() // 挂载即同步一次（初始 state 与媒体查询竞态兜底）
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }, [])

  return isMobile
}
