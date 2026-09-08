/**
 * 通知卡片外壳(自 v2 起由 antd notification 接管渲染)。
 *
 * 保留此组件仅为维持挂载点与调用约定;实际通知由 antd notification
 * (经 utils/appAntdBridge 的桥接实例)渲染,本组件渲染为空。
 */
import React from 'react'

export default function NotificationHost() {
  return null
}
