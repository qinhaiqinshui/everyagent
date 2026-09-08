import React from 'react'
import { Tabs as AntTabs } from 'antd'
import type { TabsProps as AntTabsProps } from 'antd'
import { cn } from './cn'

export type TabItem = {
  key: string
  label: React.ReactNode
  icon?: React.ReactNode
  disabled?: boolean
}

export type TabsProps = {
  tabs: TabItem[]
  activeKey: string
  onChange: (key: string) => void
  className?: string
}

/** 选项卡栏（antd Tabs 实现）。 */
export default function Tabs({ tabs, activeKey, onChange, className }: TabsProps) {
  const items: AntTabsProps['items'] = tabs.map((t) => ({
    key: t.key,
    label: (
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
        {t.icon}
        {t.label}
      </span>
    ),
    disabled: t.disabled,
  }))

  return (
    <AntTabs
      activeKey={activeKey}
      onChange={onChange}
      className={cn(className)}
      items={items}
      tabBarStyle={{ marginBottom: 0 }}
    />
  )
}
