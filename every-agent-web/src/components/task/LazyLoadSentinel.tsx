import React from 'react'
import { InlineSpinner } from '@/components/shared/ui'

/**
 * 懒加载占位元素：滚入可视区（root 内 + rootMargin 预取）即触发 onVisible 续拉。
 * seq / dir 挂 data-* 属性供调试/测试，驱动逻辑以 props 为准。
 *
 * - loading 为 true 时显示加载指示器且不重复触发续拉；
 * - 父级在「没有更多」时直接不渲染本组件 = 占位删除；
 * - root 变化时重建 observer（如滚动容器挂载晚于本组件）。
 *
 * 可见态：
 * - 空闲(非 loading)：居中显示「上滚/下滚加载更多」提示文本；
 * - 加载中：居中显示 InlineSpinner + 「正在加载…」。
 */
export interface LazyLoadSentinelProps {
  /** 续拉方向：forward=向后拉更多内容；backward=向前拉更早内容。 */
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

  const hint = dir === 'backward' ? '上滚加载更多' : '下滚加载更多'

  return (
    <div
      ref={ref}
      className="nagent-lazy-sentinel"
      data-lazy-dir={dir}
      data-lazy-seq={String(seq)}
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        padding: '12px 0',
        color: 'var(--text-muted)',
        fontSize: 'var(--text-sm)',
      }}
    >
      {loading ? (
        <>
          <InlineSpinner size={14} />
          <span>正在加载…</span>
        </>
      ) : (
        <span>{hint}</span>
      )}
    </div>
  )
}
