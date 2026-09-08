/**
 * antd v6 主题基座。
 *
 * 视觉对齐既有设计 token（ui-tokens.css）：
 *  - 暗色为默认，主色沿用品牌蓝 --accent-blue(#61a5ff)，成功色用 --accent-green(#41d19c)；
 *  - 圆角/字号与既有 --radius-* / --text-* 刻度保持一致；
 *  - 浅色主题走 antd defaultAlgorithm + 浅色 token 覆盖。
 *
 * 仅负责「配色与基础 token」，组件实现全部走 antd 组件（见 components/shared/ui）。
 */
import { theme, type ThemeConfig } from 'antd'
import type { ThemeMode } from '@/types'

const BRAND = {
  primary: '#61a5ff',
  success: '#41d19c',
  warning: '#f7bd54',
  error: '#ff6b6b',
  info: '#61a5ff',
}

const FONT_FAMILY =
  "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', Arial, sans-serif"

export function getAntdTheme(mode: ThemeMode): ThemeConfig {
  const isDark = mode !== 'light'
  return {
    algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: {
      colorPrimary: BRAND.primary,
      colorInfo: BRAND.info,
      colorSuccess: BRAND.success,
      colorWarning: BRAND.warning,
      colorError: BRAND.error,
      colorLink: BRAND.primary,
      borderRadius: 8,
      borderRadiusSM: 6,
      borderRadiusLG: 10,
      fontSize: 14,
      fontSizeSM: 13,
      fontFamily: FONT_FAMILY,
      // 暗色下把底色种子对齐既有 --bg-primary，使 antd 浮层与外壳 chrome 融合。
      colorBgBase: isDark ? '#0b0d12' : '#ffffff',
      colorTextBase: isDark ? '#eef1f5' : '#000000',
      colorBorder: isDark ? '#2a303b' : '#d9d9d9',
      colorBorderSecondary: isDark ? '#1b202a' : '#e9e9e9',
      colorBgContainer: isDark ? '#11141b' : '#ffffff',
      colorBgElevated: isDark ? '#151922' : '#ffffff',
      colorBgLayout: isDark ? '#0b0d12' : '#f5f5f5',
      motionDurationMid: '0.2s',
    },
    components: {
      Button: {
        controlHeight: 32,
        controlHeightSM: 24,
        controlHeightLG: 40,
        fontWeight: 500,
      },
      Modal: {
        borderRadiusLG: 12,
      },
      Drawer: {
        borderRadiusLG: 12,
      },
      Tabs: {
        horizontalItemPadding: '8px 12px',
      },
    },
  }
}
