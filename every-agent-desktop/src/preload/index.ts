/**
 * preload:在 contextIsolation 下经 contextBridge 暴露最小 bootstrap 接口。
 * 前端据此自动配置 hub 连接与 worker apiKey,实现开箱即用;启动页据此展示启动进度。
 */
import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'

export interface DesktopBootstrap {
  hubUrl: string
  hubKey: string
  workerId: string
  workerApiKey: string
}

contextBridge.exposeInMainWorld('everyAgentDesktop', {
  getBootstrap: (): Promise<DesktopBootstrap | null> => ipcRenderer.invoke('desktop:get-bootstrap'),
  getStartupStatus: (): Promise<string[]> => ipcRenderer.invoke('desktop:get-startup-status'),
  onStartupStatus: (callback: (line: string) => void): void => {
    const listener = (_event: IpcRendererEvent, line: string): void => callback(line)
    ipcRenderer.on('desktop:startup-status', listener)
  },
  notify: (payload: { title: string; body?: string; tag?: string }): void => {
    ipcRenderer.send('desktop:notify', payload)
  },
  onNotifyClick: (callback: (tag: string) => void): void => {
    const listener = (_event: IpcRendererEvent, tag: string): void => callback(tag)
    ipcRenderer.on('desktop:notify-click', listener)
  },
  windowControl: {
    minimize: (): Promise<void> => ipcRenderer.invoke('desktop:window-minimize'),
    toggleMaximize: (): Promise<void> => ipcRenderer.invoke('desktop:window-toggle-maximize'),
    close: (): Promise<void> => ipcRenderer.invoke('desktop:window-close'),
    isMaximized: (): Promise<boolean> => ipcRenderer.invoke('desktop:window-is-maximized'),
    onMaximizedChanged: (callback: (maximized: boolean) => void): void => {
      const listener = (_event: IpcRendererEvent, maximized: boolean): void => callback(maximized)
      ipcRenderer.on('desktop:window-maximized-changed', listener)
    },
  },
})
