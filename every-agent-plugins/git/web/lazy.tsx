/**
 * git 插件内部懒加载辅助（自宿主 components/shared/LazyRouteView 复制，插件不引用宿主模块）。
 */
import React from 'react'

/** 懒加载模块的最小约定：默认导出一个 React 组件。 */
type LazyComponentLoader<TProps extends object> = () => Promise<{ default: React.ComponentType<TProps> }>

/** 通用的懒加载占位，不展示额外文案。 */
function LazyRouteFallback() {
  return <div style={fallbackStyle} aria-hidden="true" />
}

/** 创建带预加载能力的懒加载组件（统一套 Suspense）。 */
export function createLazyRouteComponent<TProps extends object>(
  loader: LazyComponentLoader<TProps>,
) {
  const LazyComponent = React.lazy(loader) as unknown as React.ComponentType<Record<string, unknown>>

  function LazyRouteComponent(props: TProps) {
    return (
      <React.Suspense fallback={<LazyRouteFallback />}>
        <LazyComponent {...(props as Record<string, unknown>)} />
      </React.Suspense>
    )
  }

  LazyRouteComponent.preload = loader

  return LazyRouteComponent
}

const fallbackStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  background: 'var(--bg-primary)',
}
