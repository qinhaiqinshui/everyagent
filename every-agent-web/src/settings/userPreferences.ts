/**
 * 用户偏好同步服务:监听 worker 连接就绪 / config.changed{preferences},
 * 从 worker 拉取最新偏好(当前主要是主题),校正 localStorage 缓存并发领域事件。
 *
 * 仿 modelConfigs.wire() 模式:进程内只 wire 一次,main.tsx 调用。
 * localStorage 是首帧缓存(避免 FOUC),worker 是事实源——连接就绪后立即校正。
 */
import { hubSession } from '@/hub/session'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { syncThemeFromWorker } from './localSettings'

class UserPreferencesService {
  private wired = false

  wire(): void {
    if (this.wired) return
    this.wired = true

    // worker 连接恢复(含首次连接 + 每次重连)→ 同步偏好
    hubSession.onReconnect(() => {
      void this.syncTheme()
    })

    // 目录连接状态 open → 尝试同步(首次连接尚未有 worker 时静默跳过)
    hubSession.onState((state) => {
      if (state === 'open') {
        void this.syncTheme()
      }
    })

    // 收到 config.changed{keys:["preferences"]} → 重新同步(其他端修改了偏好)
    hubSession.onFrame((frame) => {
      if (frame.event !== 'config.changed') return
      const keys: unknown = (frame.payload as Record<string, unknown> | null)?.keys
      if (!Array.isArray(keys) || !keys.includes('preferences')) return
      void this.syncTheme()
    })
  }

  /** 从 worker 拉取主题偏好,校正缓存并发领域事件。 */
  private async syncTheme(): Promise<void> {
    const theme = await syncThemeFromWorker()
    if (theme !== null) {
      domainEventBus.emit(DOMAIN_EVENTS.SETTINGS_THEME_PATCHED, { themeMode: theme })
    }
  }
}

export const userPreferences = new UserPreferencesService()
