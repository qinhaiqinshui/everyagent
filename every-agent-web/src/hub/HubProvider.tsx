/**
 * Hub 连接的 React 出口:把 hubSession 的状态(subscription 驱动)挂进 React 树。
 *
 * 组件用 useHub() 读取 {state, workers, directory, ...};
 * 连接/断开/改配置走 hubSession 单例方法(非 React 模块共用同一出口)。
 */
import React from 'react'
import type { HubState } from '@every-agent/client'
import { hubSession, type HubConnectionConfig, type WorkerConnectResult, type WorkerInfo } from './session'

export interface HubContextValue {
  state: HubState
  configured: boolean
  /** workerId → online(presence 目录)。 */
  workers: Map<string, boolean>
  /** worker 纳管列表(在线/启用/是否已填 apiKey/连接错误)。 */
  directory: WorkerInfo[]
  config: HubConnectionConfig | null
  /** 保存配置并重连(设置页用)。 */
  applyConfig: (config: HubConnectionConfig) => Promise<void>
  disconnect: () => void
  /** 为指定 worker 保存 apiKey(加密)并启用/建连;返回连接结果供设置页反馈。 */
  setWorkerApiKey: (workerId: string, apiKey: string) => Promise<WorkerConnectResult>
  /** 启用/禁用指定 worker;返回连接结果供设置页反馈。 */
  setWorkerEnabled: (workerId: string, enabled: boolean) => Promise<WorkerConnectResult>
  /** reconnect 信号版本号:每次连接(含重连)建立后 +1,组件据此做全量校准。 */
  reconnectVersion: number
  /** 目录连接级致命错误,null 表示无;设置页据此展示明确提示。 */
  fatalError: { code: string; detail: string } | null
  /** 是否被 hub 限流(静默退避中)。 */
  rateLimited: boolean
  /** 是否有任一连接(目录/worker)正在重连(瞬态断连,传输层自动重连中)。 */
  reconnecting: boolean
}

const HubContext = React.createContext<HubContextValue | null>(null)

export function HubProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = React.useState<HubState>(hubSession.state)
  const [workers, setWorkers] = React.useState(new Map(hubSession.workersOnline))
  const [directory, setDirectory] = React.useState<WorkerInfo[]>(hubSession.directory)
  const [config, setConfig] = React.useState(hubSession.config)
  const [reconnectVersion, setReconnectVersion] = React.useState(0)
  const [fatalError, setFatalError] = React.useState(hubSession.fatalError)
  const [rateLimited, setRateLimited] = React.useState(hubSession.rateLimited)
  const [reconnecting, setReconnecting] = React.useState(hubSession.isReconnecting)

  React.useEffect(() => {
    const unsubState = hubSession.onState(setState)
    const unsubWorkers = hubSession.onWorkers((next) => {
      setWorkers(next)
    })
    const unsubDirectory = hubSession.onDirectory(setDirectory)
    const unsubReconnect = hubSession.onReconnect(() => {
      setReconnectVersion((v) => v + 1)
    })
    const unsubFatal = hubSession.onFatalError(setFatalError)
    const unsubRateLimited = hubSession.onRateLimited(setRateLimited)
    const unsubReconnecting = hubSession.onReconnecting(setReconnecting)
    void hubSession.ensureConnected()
    return () => {
      unsubState()
      unsubWorkers()
      unsubDirectory()
      unsubReconnect()
      unsubFatal()
      unsubRateLimited()
      unsubReconnecting()
    }
  }, [])

  const value = React.useMemo<HubContextValue>(() => ({
    state,
    configured: hubSession.configured,
    workers,
    directory,
    config,
    applyConfig: async (next) => {
      await hubSession.applyConfig(next)
      setConfig({ ...next })
      setDirectory(hubSession.directory)
    },
    disconnect: () => hubSession.disconnect(),
    setWorkerApiKey: async (workerId, apiKey) => {
      const result = await hubSession.setWorkerApiKey(workerId, apiKey)
      setConfig(hubSession.config ? { ...hubSession.config } : null)
      setDirectory(hubSession.directory)
      return result
    },
    setWorkerEnabled: async (workerId, enabled) => {
      const result = await hubSession.setWorkerEnabled(workerId, enabled)
      setConfig(hubSession.config ? { ...hubSession.config } : null)
      setDirectory(hubSession.directory)
      return result
    },
    reconnectVersion,
    fatalError,
    rateLimited,
    reconnecting,
  }), [state, workers, directory, config, reconnectVersion, fatalError, rateLimited, reconnecting])

  return <HubContext.Provider value={value}>{children}</HubContext.Provider>
}

export function useHub(): HubContextValue {
  const value = React.useContext(HubContext)
  if (!value) {
    throw new Error('useHub 必须在 HubProvider 内使用')
  }
  return value
}