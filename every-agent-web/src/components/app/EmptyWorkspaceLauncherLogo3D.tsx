
/**
 * 空工作区启动台 3D Logo 属性。
 */
export interface EmptyWorkspaceLauncherLogo3DProps {
  /** 追加到根节点的 className，用于外部在极少数场景下做局部定位。 */
  className?: string
}

/**
 * 空工作区专用的 3D 启动台 Logo。
 * 视觉仿照 Windows 10 光线穿过窗口的构图：四块窗格负责主体，右侧光束负责空间感。
 */
export default function EmptyWorkspaceLauncherLogo3D({
  className,
}: EmptyWorkspaceLauncherLogo3DProps) {
  return (
    <span
      className={['empty-workspace-launcher-logo', className].filter(Boolean).join(' ')}
      aria-hidden="true"
    >
      <span className="empty-workspace-launcher-logo__field" />
      <span className="empty-workspace-launcher-logo__light empty-workspace-launcher-logo__light--top" />
      <span className="empty-workspace-launcher-logo__light empty-workspace-launcher-logo__light--middle" />
      <span className="empty-workspace-launcher-logo__light empty-workspace-launcher-logo__light--bottom" />
      <span className="empty-workspace-launcher-logo__shadow" />
      <span className="empty-workspace-launcher-logo__window">
        <span className="empty-workspace-launcher-logo__pane empty-workspace-launcher-logo__pane--top-left" />
        <span className="empty-workspace-launcher-logo__pane empty-workspace-launcher-logo__pane--top-right" />
        <span className="empty-workspace-launcher-logo__pane empty-workspace-launcher-logo__pane--bottom-left" />
        <span className="empty-workspace-launcher-logo__pane empty-workspace-launcher-logo__pane--bottom-right" />
      </span>
    </span>
  )
}
