import React from 'react'

/** 懒加载模块的最小约定：默认导出一个 React 组件。 */
type LazyComponentLoader<TProps extends object> = () => Promise<{ default: React.ComponentType<TProps> }>

/** 浏览器空闲回调句柄，兼容当前 TypeScript DOM 类型缺省声明。 */
type IdleCallbackHandle = number

/** 浏览器空闲回调入口，避免直接扩展全局 Window 类型。 */
type IdleWindow = Window & {
  requestIdleCallback?: (callback: () => void, options?: { timeout?: number }) => IdleCallbackHandle
  cancelIdleCallback?: (handle: IdleCallbackHandle) => void
}

/** 通用的懒加载占位，不展示额外文案，避免页面切换时出现大块提示文本。 */
export function LazyRouteFallback() {
  return <div style={fallbackStyle} aria-hidden="true" />
}

/** 创建带预加载能力的懒加载页面组件。 */
export function createLazyRouteComponent<TProps extends object>(
  loader: LazyComponentLoader<TProps>,
) {
  const LazyComponent = React.lazy(loader) as unknown as React.ComponentType<Record<string, unknown>>

  /** 渲染懒加载组件，并统一套 Suspense。 */
  function LazyRouteComponent(props: TProps) {
    return (
      <React.Suspense fallback={<LazyRouteFallback />}>
        <LazyComponent {...(props as Record<string, unknown>)} />
      </React.Suspense>
    )
  }

  /** 预加载对应 chunk；重复调用由浏览器与模块系统去重。 */
  LazyRouteComponent.preload = loader

  return LazyRouteComponent
}

/** 在首屏渲染后空闲预加载后续页面资源。 */
export function scheduleLazyRoutePreload(loaders: Array<() => Promise<unknown>>): () => void {
  let cancelled = false
  let timeoutId: number | null = null
  let idleId: IdleCallbackHandle | null = null
  const idleWindow = window as IdleWindow

  const run = () => {
    if (cancelled) return
    void Promise.allSettled(loaders.map((loader) => loader()))
  }

  // 先等一帧，让首页完成首次绘制，再把预加载交给浏览器空闲期。
  const frameId = window.requestAnimationFrame(() => {
    if (cancelled) return
    if (idleWindow.requestIdleCallback) {
      idleId = idleWindow.requestIdleCallback(run, { timeout: 3000 })
      return
    }
    timeoutId = window.setTimeout(run, 800)
  })

  return () => {
    cancelled = true
    window.cancelAnimationFrame(frameId)
    if (idleId !== null && idleWindow.cancelIdleCallback) {
      idleWindow.cancelIdleCallback(idleId)
    }
    if (timeoutId !== null) {
      window.clearTimeout(timeoutId)
    }
  }
}

const fallbackStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  background: 'var(--bg-primary)',
}
