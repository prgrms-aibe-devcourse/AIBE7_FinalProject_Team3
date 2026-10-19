import type { Page } from '@playwright/test'

declare global {
  interface Window {
    __stockStream: {
      autoReady: boolean
      sources: Array<{ url: string; readyState: number }>
      emit: (type: string, data: unknown, index?: number) => void
      error: (index?: number) => void
    }
  }
}

export async function installStockStream(page: Page) {
  await page.addInitScript(() => {
    class MockEventSource extends EventTarget {
      readonly url: string
      readonly withCredentials: boolean
      readyState = 0
      onerror: ((event: Event) => void) | null = null

      constructor(url: string | URL, options?: EventSourceInit) {
        super()
        this.url = String(url)
        this.withCredentials = options?.withCredentials ?? false
        window.__stockStream.sources.push(this)
        if (window.__stockStream.autoReady)
          queueMicrotask(() => {
            const dropIds = new URL(this.url, location.href).searchParams
              .get('dropIds')
              ?.split(',')
              .map(Number)
            this.emit('ready', {
              dropIds,
              serverTime: new Date().toISOString(),
            })
          })
      }

      close() {
        this.readyState = 2
      }

      emit(type: string, data: unknown) {
        if (this.readyState === 2) return
        if (type === 'ready') this.readyState = 1
        this.dispatchEvent(
          new MessageEvent(type, { data: JSON.stringify(data) }),
        )
      }

      error() {
        if (this.readyState === 2) return
        this.onerror?.(new Event('error'))
      }
    }

    const stockStream = {
      autoReady: true,
      sources: [] as MockEventSource[],
      emit(type: string, data: unknown, index?: number) {
        stockStream.sources[index ?? stockStream.sources.length - 1]?.emit(
          type,
          data,
        )
      },
      error(index?: number) {
        stockStream.sources[index ?? stockStream.sources.length - 1]?.error()
      },
    }
    window.__stockStream = stockStream
    Object.defineProperty(window, 'EventSource', { value: MockEventSource })
  })
}
