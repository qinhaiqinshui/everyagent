import InlineBrandLoading from './InlineBrandLoading'

/**
 * 通用内联加载文案组件。
 * 适合按钮、行内状态、轻量提示等场景，避免各页面重复拼接品牌 loading + 文案。
 */
export default function InlineLoadingLabel({
  label,
  loading = false,
  spinnerSize = 12,
}: {
  /** 展示文案。 */
  label: string
  /** 当前是否处于加载中。 */
  loading?: boolean
  /** 旋转图标尺寸。 */
  spinnerSize?: number
}) {
  return (
    <span className="inline-loading-label">
      {loading ? (
        <span className="inline-loading-label__spinner" aria-hidden="true">
          <InlineBrandLoading size={spinnerSize} />
        </span>
      ) : null}
      <span>{label}</span>
    </span>
  )
}
