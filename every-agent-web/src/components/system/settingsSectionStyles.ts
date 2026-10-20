/**
 * 设置页「段」的共用样式（内置段与插件段同款，保证插件段与内置段视觉一致）。
 */
import type React from 'react'

export const settingsSectionStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  padding: '16px 0',
  borderBottom: '1px solid var(--border)',
}

export const settingsSectionTitleStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-base)',
  fontWeight: 700,
}