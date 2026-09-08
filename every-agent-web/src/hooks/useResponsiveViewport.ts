import React from 'react'

/**
 * 响应式视口信息。
 * 用于给界面层提供统一的断点判断结果。
 */
type ResponsiveViewportState = {
  /** 当前是否处于手机断点。 */
  isMobile: boolean
  /** 当前是否处于平板紧凑断点。 */
  isTablet: boolean
}

const MOBILE_MAX_WIDTH = 768
const TABLET_MAX_WIDTH = 1024

/**
 * 读取当前窗口宽度对应的响应式断点状态。
 * 仅服务界面展示，不承载任何业务逻辑。
 */
function readResponsiveViewportState(): ResponsiveViewportState {
  if (typeof window === 'undefined') {
    return {
      isMobile: false,
      isTablet: false,
    }
  }

  const width = window.innerWidth
  return {
    isMobile: width <= MOBILE_MAX_WIDTH,
    isTablet: width > MOBILE_MAX_WIDTH && width <= TABLET_MAX_WIDTH,
  }
}

/**
 * 响应式视口 Hook。
 * 统一向页面组件提供手机端和平板端判断结果。
 */
export function useResponsiveViewport(): ResponsiveViewportState {
  const [state, setState] = React.useState<ResponsiveViewportState>(() => readResponsiveViewportState())

  React.useEffect(() => {
    /**
     * 视口变化时同步更新当前断点状态。
     */
    const handleResize = () => {
      setState(readResponsiveViewportState())
    }

    handleResize()
    window.addEventListener('resize', handleResize, { passive: true })
    return () => {
      window.removeEventListener('resize', handleResize)
    }
  }, [])

  return state
}

export default useResponsiveViewport
