/**
 * 插件扩展点注册表抽象。
 *
 * ExtensionRegistry 定义了扩展点注册表的通用接口，默认实现 ListExtensionRegistry
 * 使用普通数组存储，注册进来的项目都有效。
 *
 * 通过 ExtensionRegistryFactory 可以创建不同类型的注册表实例，
 * 允许未来扩展（如按优先级排序、条件过滤等）而不影响调用方。
 */

import type { Disposable } from '@everyagent/plugin-api'

/**
 * 扩展点注册表接口。
 */
export interface ExtensionRegistry<T> {
  /** 注册一个扩展点实现，返回 Disposable 用于注销。 */
  register(pluginId: string, item: T): Disposable
  /** 获取所有已注册的扩展点实现。 */
  getAll(): T[]
  /**
   * 订阅注册表变更（注册或注销时触发），返回取消订阅函数。
   * 插件的注册发生在 `activate()` 里（异步、晚于宿主首屏渲染），
   * 消费方必须能感知变更才会重渲染；只靠 `getAll()` 快照会把贡献读成渲染期的一次性值。
   */
  subscribe(listener: () => void): () => void
}

/**
 * 扩展点注册表工厂接口。
 */
export interface ExtensionRegistryFactory {
  /** 为指定扩展点创建注册表实例。 */
  create<T>(extensionPoint: string): ExtensionRegistry<T>
}

/**
 * 默认的扩展点注册表实现——普通列表。
 * 注册进来的项目按注册顺序排列，全部有效；内容变化时通知订阅者。
 */
export class ListExtensionRegistry<T> implements ExtensionRegistry<T> {
  private items: T[] = []
  private listeners = new Set<() => void>()

  register(pluginId: string, item: T): Disposable {
    this.items.push(item)
    this.notifyChanged()
    return {
      dispose: () => {
        const i = this.items.indexOf(item)
        if (i < 0) return
        this.items.splice(i, 1)
        this.notifyChanged()
      },
    }
  }

  getAll(): T[] {
    return [...this.items]
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  /** 通知全部订阅者；副本遍历，避免订阅者在回调里增删订阅导致迭代错乱。 */
  private notifyChanged(): void {
    for (const listener of [...this.listeners]) {
      listener()
    }
  }
}

/**
 * 默认的扩展点注册表工厂。
 * 为每个扩展点创建 ListExtensionRegistry 实例。
 */
export class DefaultExtensionRegistryFactory implements ExtensionRegistryFactory {
  create<T>(extensionPoint: string): ExtensionRegistry<T> {
    return new ListExtensionRegistry<T>()
  }
}
