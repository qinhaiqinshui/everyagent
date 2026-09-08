import { DOMAIN_EVENTS } from './domainEvents'
import type { DomainEventMap, DomainEventName } from './domainEvents'

type EventHandler<TEventName extends DomainEventName> = (payload: DomainEventMap[TEventName]) => void

class EventBus {
  private handlers = new Map<DomainEventName, Set<EventHandler<any>>>()

  emit<TEventName extends DomainEventName>(eventName: TEventName, payload: DomainEventMap[TEventName]): void {
    const listeners = this.handlers.get(eventName)
    if (!listeners || listeners.size === 0) return
    const callbacks = Array.from(listeners)
    queueMicrotask(() => {
      callbacks.forEach((handler) => {
        handler(payload)
      })
    })
  }

  subscribe<TEventName extends DomainEventName>(eventName: TEventName, handler: EventHandler<TEventName>): () => void {
    const listeners = this.handlers.get(eventName) ?? new Set<EventHandler<TEventName>>()
    listeners.add(handler)
    this.handlers.set(eventName, listeners as Set<EventHandler<any>>)
    return () => {
      const current = this.handlers.get(eventName)
      if (!current) return
      current.delete(handler)
      if (current.size === 0) {
        this.handlers.delete(eventName)
      }
    }
  }
}

export const domainEventBus = new EventBus()
export { DOMAIN_EVENTS }
