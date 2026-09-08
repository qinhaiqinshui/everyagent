/**
 * antd App 实例桥接组件。
 *
 * 必须在 <AntApp> 内部渲染:把 App.useApp() 得到的 message / notification /
 * modal 实例注册到 utils/appAntdBridge,供自研外壳(showToast / notifyApp)
 * 在任意上下文调用的 antd 实例。渲染为空节点。
 */
import React from 'react'
import { App } from 'antd'
import { registerAntdApp } from '@/utils/appAntdBridge'

export default function AntdAppBridge() {
  const app = App.useApp()
  React.useEffect(() => {
    registerAntdApp(app)
  }, [app])
  return null
}