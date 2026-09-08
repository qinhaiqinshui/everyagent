import React from 'react'

/** 悬浮操作容器属性。 */
export type FloatingActionContainerProps = {
  /** 容器内部内容。 */
  children: React.ReactNode
  /** 默认距离右侧的偏移。 */
  defaultRight?: number | string
  /** 默认距离顶部的偏移（指定后改为右侧靠上、垂直居中的锚定方式）。 */
  defaultTop?: number | string
  /** 默认距离底部的偏移。 */
  defaultBottom?: number | string
  /** 容器层级。 */
  zIndex?: number
  /** 允许父组件覆盖根样式。 */
  rootStyle?: React.CSSProperties
  /** 允许父组件覆盖内容区域样式。 */
  contentStyle?: React.CSSProperties
}

const DEFAULT_RIGHT = 6
const DEFAULT_TOP = '50%'
const DEFAULT_BOTTOM = 16
const DEFAULT_Z_INDEX = 35

/**
 * 通用悬浮操作容器。
 * 绝对定位（锚定到最近的已定位祖先，即工作区根容器），位置由固定的
 * right / bottom 决定：默认锚定在右下角；传入 defaultTop 时改为右侧靠上、
 * 垂直居中的锚定方式。
 */
export default function FloatingActionContainer({
  children,
  defaultTop,
  defaultRight = DEFAULT_RIGHT,
  defaultBottom = DEFAULT_BOTTOM,
  zIndex = DEFAULT_Z_INDEX,
  rootStyle,
  contentStyle,
}: FloatingActionContainerProps) {
  const anchorStyle: React.CSSProperties = defaultTop !== undefined
    ? {
        right: defaultRight,
        top: defaultTop,
        bottom: 'auto',
        transform: 'translateY(-50%)',
      }
    : {
        right: defaultRight,
        bottom: defaultBottom,
        top: 'auto',
        transform: 'none',
      }

  return (
    <div
      className="floating-action-container"
      style={{
        ...floatingRootStyle,
        zIndex,
        ...anchorStyle,
        ...rootStyle,
      }}
    >
      <div style={{ ...floatingContentStyle, ...contentStyle }}>
        {children}
      </div>
    </div>
  )
}

const floatingRootStyle: React.CSSProperties = {
  position: 'absolute',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'stretch',
  gap: 4,
  boxSizing: 'border-box',
  padding: '1px 1px 3px',
  borderRadius: 12,
  background: 'color-mix(in srgb, var(--bg-primary) 78%, transparent)',
  boxShadow: '0 6px 12px rgba(0, 0, 0, 0.05)',
  backdropFilter: 'blur(16px)',
  userSelect: 'none',
}

const floatingContentStyle: React.CSSProperties = {
  width: '100%',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'stretch',
  gap: 3,
}
