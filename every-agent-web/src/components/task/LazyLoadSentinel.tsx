import React from 'react'

/**
 * 懒加载占位元素：用户不可见的 0 高度 div，滚入可视区（root 内 + rootMargin 预取）即触发
 * onVisible 续拉。seq / dir 挂 data-* 属性供调试/测试，驱动逻辑以 props 为准。
 *
 * - loading 为 true 时不触发（防重入）；父级在「没有更多」时直接不渲染本组件 = 占位删除。
 * - root 变化时重建 observer（如滚动容器挂载晚于本组件）。
 */
export interface LazyLoadSentinelProps {
  /** 续拉方向：forward=向后拉更早之后的内容；backward=向前拉更早内容。 */
  dir: 'forward' | 'backward'
  /** 当前续拉游标 seq（用于 data-* 展示，实际续拉参数由父级 state 维护）。 */
  seq: number | string
  /** 是否正在拉取（拉取期间不重复触发）。 */
  loading: boolean
  /** 进入可视区回调。 */
  onVisible: () => void
  /** IntersectionObserver 的 root（滚动容器）；null 用视口。 */
  root?: HTMLElement | null
  /** 预取边距，默认上下各 200px。 */
  rootMargin?: string
}

export default function LazyLoadSentinel({
  dir,
  seq,
  loading,
  onVisible,
  root,
  rootMargin = '200px 0px',
}: LazyLoadSentinelProps): React.ReactNode {
  const ref = React.useRef<HTMLDivElement>(null)
  const loadingRef = React.useRef(loading)
  loadingRef.current = loading
  const onVisibleRef = React.useRef(onVisible)
  onVisibleRef.current = onVisible

  React.useEffect(() => {
    const node = ref.current
    if (!node) return
    // loading 期间不挂 observer;loading 结束后重建,IntersectionObserver 初始总会回一次
    // 当前 intersection,若占位仍在可视区即续拉下一页(拉完一屏未满时连续续拉)。
    if (loadingRef.current) return
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting && !loadingRef.current) {
            onVisibleRef.current()
          }
        }
      },
      { root: root ?? null, rootMargin, threshold: 0 },
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [root, rootMargin, loading])

  return (
    <div
      ref={ref}
      className="nagent-lazy-sentinel"
      data-lazy-dir={dir}
      data-lazy-seq={String(seq)}
      style={{ height: 0, overflow: 'hidden' }}
      aria-hidden="true"
    />
  )
}
