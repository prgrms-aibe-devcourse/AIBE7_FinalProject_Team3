import {
  QueryClient,
  QueryObserver,
  focusManager,
  onlineManager,
  useQuery,
} from '@tanstack/react-query'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { getDrops } from '../../../api/drop/drops'
import { openDropStockStream } from '../../../api/drop/stockStream'
import { dropKeys, type DropListParams } from '../logic/queryKeys'
import { subscribeDropListStockStream } from '../logic/useDropListStockStream'
import { useDropList } from '../logic/useDrops'

vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: vi.fn(),
}))

vi.mock('../../../api/drop/drops', () => ({
  getDrops: vi.fn(),
}))
vi.mock('../../../api/drop/stockStream', () => ({
  normalizeDropIds: vi.fn((ids: number | readonly number[]) => {
    const values = typeof ids === 'number' ? [ids] : [...ids]
    return [...new Set(values)].sort((a, b) => a - b)
  }),
  openDropStockStream: vi.fn(),
}))

type CacheEvent = {
  type: 'updated'
  query: { queryKey: readonly unknown[]; state: { data: unknown } }
  action: { type: 'success' }
}

const params: DropListParams = {
  status: 'GRAB',
  sort: 'publishedAt,desc',
  page: 0,
  size: 24,
}
const key = dropKeys.list(params)

class FakeSource {
  listeners = new Map<string, (event: MessageEvent) => void>()
  close = vi.fn()
  onerror: (() => void) | null = null

  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    this.listeners.set(type, listener)
  }

  emit(type: string, data: unknown) {
    this.listeners.get(type)?.({ data: JSON.stringify(data) } as MessageEvent)
  }
}

const page = (ids: number[]) =>
  ({
    content: ids.map((dropId) => ({ dropId })),
    page: 0,
    size: 24,
    totalElements: ids.length,
    totalPages: ids.length ? 1 : 0,
    hasNext: false,
  }) as never

function setup(initialIds: number[] = [101, 102]) {
  const source = new FakeSource()
  const openedAt: number[] = []
  vi.mocked(openDropStockStream).mockImplementation(() => {
    openedAt.push(Date.now())
    return source as unknown as EventSource
  })
  const fetchQuery = vi.fn().mockResolvedValue({})
  const cancelQueries = vi.fn().mockResolvedValue(undefined)
  let cacheListener: ((event: CacheEvent) => void) | undefined
  const client = {
    getQueryData: vi.fn(() => page(initialIds)),
    fetchQuery,
    cancelQueries,
    getQueryCache: () => ({
      subscribe: (listener: (event: CacheEvent) => void) => {
        cacheListener = listener
        return () => undefined
      },
    }),
  } as unknown as QueryClient
  const subscription = subscribeDropListStockStream(params, client, {
    startedAt: null,
  })
  const emitCache = (ids: number[]) =>
    cacheListener?.({
      type: 'updated',
      query: { queryKey: key, state: { data: page(ids) } },
      action: { type: 'success' },
    })
  return {
    source,
    openedAt,
    fetchQuery,
    cancelQueries,
    subscription,
    client,
    emitCache,
  }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-19T00:00:00Z'))
  vi.spyOn(Math, 'random').mockReturnValue(0)
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.resetAllMocks()
})

test.each(['focus', 'online'] as const)(
  '%s 복귀 조회는 실제 QueryClient에서도 2초 간격으로 병합된다',
  async (trigger) => {
    const doc = new EventTarget()
    let visibilityState = 'visible'
    Object.defineProperty(doc, 'visibilityState', {
      get: () => visibilityState,
    })
    const win = new EventTarget()
    vi.stubGlobal('document', doc)
    vi.stubGlobal('window', win)
    vi.stubGlobal('navigator', { onLine: true })
    const source = new FakeSource()
    vi.mocked(openDropStockStream).mockReturnValue(
      source as unknown as EventSource,
    )
    const starts: number[] = []
    vi.mocked(getDrops).mockImplementation(async () => {
      starts.push(Date.now())
      return page([101])
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: Infinity } },
    })
    client.mount()
    client.setQueryData(key, page([101]))
    useDropList(params)
    const options = vi.mocked(useQuery).mock.calls.at(-1)![0]
    const observer = new QueryObserver(client, {
      ...options,
      refetchOnMount: false,
    })
    const unsubscribe = observer.subscribe(() => {})
    const subscription = subscribeDropListStockStream(params, client)
    try {
      subscription.refresh()
      await vi.advanceTimersByTimeAsync(1)
      expect(starts).toHaveLength(1)
      await vi.advanceTimersByTimeAsync(99)

      if (trigger === 'focus') {
        visibilityState = 'hidden'
        focusManager.setFocused(false)
        doc.dispatchEvent(new Event('visibilitychange'))
        visibilityState = 'visible'
        focusManager.setFocused(true)
        doc.dispatchEvent(new Event('visibilitychange'))
      } else {
        onlineManager.setOnline(false)
        win.dispatchEvent(new Event('offline'))
        onlineManager.setOnline(true)
        win.dispatchEvent(new Event('online'))
      }
      await vi.advanceTimersByTimeAsync(1)
      expect(starts).toHaveLength(1)
      await vi.advanceTimersByTimeAsync(2000)
      expect(starts).toHaveLength(2)
      expect(starts[1] - starts[0]).toBeGreaterThanOrEqual(2000)
    } finally {
      subscription.dispose()
      unsubscribe()
      client.unmount()
      client.clear()
      focusManager.setFocused(undefined)
      onlineManager.setOnline(true)
    }
  },
)

test('최초 목록 ID를 정규화해 한 연결로 구독하고 ready 뒤 같은 조건을 재조회한다', async () => {
  const { source, fetchQuery, cancelQueries, subscription } = setup([
    102, 101, 102,
  ])
  await vi.advanceTimersByTimeAsync(1)
  expect(openDropStockStream).toHaveBeenCalledTimes(1)
  expect(openDropStockStream).toHaveBeenCalledWith([101, 102])

  source.emit('ready', {
    dropIds: [101, 102],
    serverTime: '2026-10-19T00:00:00Z',
  })
  await vi.advanceTimersByTimeAsync(1)
  expect(cancelQueries).toHaveBeenCalledWith({ queryKey: key, exact: true })
  expect(fetchQuery).toHaveBeenCalledTimes(1)
  expect(fetchQuery.mock.lastCall?.[0].queryKey).toEqual(key)

  subscription.dispose()
})

test('변경 알림은 2초 창에서 묶고 지원하지 않는 변경만 있으면 무시한다', async () => {
  const { source, fetchQuery, subscription } = setup()
  await vi.advanceTimersByTimeAsync(1)
  source.emit('ready', {
    dropIds: [101, 102],
    serverTime: '2026-10-19T00:00:00Z',
  })
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(1)

  source.emit('drop-changed', { dropId: 101, changes: ['STOCK'] })
  source.emit('drop-changed', { dropId: 102, changes: ['SALES', 'UNKNOWN'] })
  await vi.advanceTimersByTimeAsync(2000)
  expect(fetchQuery).toHaveBeenCalledTimes(2)

  source.emit('drop-changed', { dropId: 101, changes: ['UNKNOWN'] })
  await vi.advanceTimersByTimeAsync(2000)
  expect(fetchQuery).toHaveBeenCalledTimes(2)

  subscription.dispose()
})

test('응답의 ID 집합이 바뀌면 이전 연결을 닫고 최소 간격 뒤 새 집합으로 연결한다', async () => {
  const { source, openedAt, emitCache, subscription } = setup([101, 102])
  await vi.advanceTimersByTimeAsync(1)
  source.emit('ready', {
    dropIds: [101, 102],
    serverTime: '2026-10-19T00:00:00Z',
  })
  await vi.advanceTimersByTimeAsync(1)
  expect(openedAt).toHaveLength(1)

  emitCache([103, 104])
  expect(source.close).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(6000)
  expect(openedAt).toHaveLength(2)
  expect(openedAt[1] - openedAt[0]).toBeGreaterThanOrEqual(5000)
  expect(openDropStockStream).toHaveBeenLastCalledWith([103, 104])

  subscription.dispose()
})

test('빈 목록은 연결하지 않고 주기 조회를 유지하다가 상품이 생기면 연결한다', async () => {
  const { fetchQuery, emitCache, subscription } = setup([])
  await vi.advanceTimersByTimeAsync(1)
  expect(openDropStockStream).not.toHaveBeenCalled()

  await vi.advanceTimersByTimeAsync(10000)
  expect(fetchQuery).toHaveBeenCalledTimes(1)
  expect(openDropStockStream).not.toHaveBeenCalled()

  emitCache([101])
  await vi.advanceTimersByTimeAsync(1)
  expect(openDropStockStream).toHaveBeenCalledWith([101])

  subscription.dispose()
})

test('구독 무효화 뒤에는 목록 재조회가 성공할 때까지 재접속하지 않는다', async () => {
  const { source, openedAt, fetchQuery, emitCache, subscription } = setup([101])
  await vi.advanceTimersByTimeAsync(1)
  source.emit('ready', { dropIds: [101], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(1)

  source.emit('stream-closed', {
    reason: 'SUBSCRIPTION_INVALIDATED',
    reconnect: false,
  })
  expect(source.close).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(6000)
  expect(openedAt).toHaveLength(1)

  emitCache([101])
  await vi.advanceTimersByTimeAsync(6000)
  expect(openedAt).toHaveLength(2)
  expect(openedAt[1] - openedAt[0]).toBeGreaterThanOrEqual(5000)

  subscription.dispose()
})

test('수동 refresh는 기존 목록 조회 제어기로 병합되고 dispose 뒤에는 요청하지 않는다', async () => {
  const { fetchQuery, subscription } = setup()
  await vi.advanceTimersByTimeAsync(1)
  subscription.refresh()
  subscription.refresh()
  await vi.advanceTimersByTimeAsync(2001)
  expect(fetchQuery).toHaveBeenCalledTimes(1)

  subscription.dispose()
  subscription.refresh()
  await vi.advanceTimersByTimeAsync(2000)
  expect(fetchQuery).toHaveBeenCalledTimes(1)
})

test('미구독 ID가 포함된 변경 알림은 연결을 닫고 복구한다', async () => {
  const { source, subscription } = setup([101])
  await vi.advanceTimersByTimeAsync(1)
  source.emit('ready', { dropIds: [101], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(1)

  source.emit('drop-changed', { dropId: 999, changes: ['STOCK'] })
  expect(source.close).toHaveBeenCalledTimes(1)

  subscription.dispose()
})

test('같은 ID 집합의 순서만 바뀌면 연결을 교체하지 않는다', async () => {
  const { source, openedAt, emitCache, subscription } = setup([101, 102])
  await vi.advanceTimersByTimeAsync(1)
  source.emit('ready', {
    dropIds: [101, 102],
    serverTime: '2026-10-19T00:00:00Z',
  })
  await vi.advanceTimersByTimeAsync(1)

  emitCache([102, 101])
  await vi.advanceTimersByTimeAsync(6000)
  expect(openedAt).toHaveLength(1)
  expect(source.close).not.toHaveBeenCalled()

  subscription.dispose()
})

test('ready가 오지 않으면 장애 조회로 전환하고 제한된 재연결을 수행한다', async () => {
  const { source, openedAt, fetchQuery, subscription } = setup([101])
  await vi.advanceTimersByTimeAsync(1)
  expect(openedAt).toHaveLength(1)

  await vi.advanceTimersByTimeAsync(10000)
  expect(source.close).toHaveBeenCalledTimes(1)
  expect(fetchQuery).toHaveBeenCalledTimes(1)

  await vi.advanceTimersByTimeAsync(2498)
  expect(openedAt).toHaveLength(1)
  await vi.advanceTimersByTimeAsync(1)
  expect(openedAt).toHaveLength(2)

  subscription.dispose()
})

test('재연결 안내는 이전 연결 시작 뒤 최소 5초를 기다린다', async () => {
  const { source, openedAt, subscription } = setup([101])
  await vi.advanceTimersByTimeAsync(1)
  source.emit('ready', { dropIds: [101], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(1)

  source.emit('stream-closed', {
    reason: 'RECONNECT_REQUIRED',
    reconnect: true,
  })
  expect(source.close).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(4997)
  expect(openedAt).toHaveLength(1)
  await vi.advanceTimersByTimeAsync(1)
  expect(openedAt).toHaveLength(2)

  subscription.dispose()
})

test('숨김·오프라인에는 연결과 주기를 멈추고 복귀 후 다시 조회한다', async () => {
  const page = new EventTarget()
  let visibilityState = 'visible'
  Object.defineProperty(page, 'visibilityState', {
    get: () => visibilityState,
  })
  const win = new EventTarget()
  let onLine = true
  vi.stubGlobal('document', page)
  vi.stubGlobal('window', win)
  vi.stubGlobal('navigator', {
    get onLine() {
      return onLine
    },
  })
  const { source, openedAt, fetchQuery, subscription } = setup([101])
  await vi.advanceTimersByTimeAsync(1)
  source.emit('ready', { dropIds: [101], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(1)

  visibilityState = 'hidden'
  page.dispatchEvent(new Event('visibilitychange'))
  await vi.advanceTimersByTimeAsync(60000)
  expect(source.close).toHaveBeenCalledTimes(1)
  expect(fetchQuery).toHaveBeenCalledTimes(1)
  expect(openedAt).toHaveLength(1)

  visibilityState = 'visible'
  page.dispatchEvent(new Event('visibilitychange'))
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(2)
  expect(openedAt).toHaveLength(2)

  onLine = false
  win.dispatchEvent(new Event('offline'))
  await vi.advanceTimersByTimeAsync(60000)
  expect(fetchQuery).toHaveBeenCalledTimes(2)
  expect(openedAt).toHaveLength(2)

  onLine = true
  win.dispatchEvent(new Event('online'))
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(3)
  expect(openedAt).toHaveLength(3)

  subscription.dispose()
})

test('ready의 ID 집합이 구독 대상과 다르면 연결을 닫고 복구한다', async () => {
  const { source, subscription } = setup([101])
  await vi.advanceTimersByTimeAsync(1)
  source.emit('ready', {
    dropIds: [999],
    serverTime: '2026-10-19T00:00:00Z',
  })
  expect(source.close).toHaveBeenCalledTimes(1)

  subscription.dispose()
})

test('연속 변경은 첫 dirty 기준 2초 창을 연장하지 않는다', async () => {
  const { source, fetchQuery, subscription } = setup([101])
  const times: number[] = []
  fetchQuery.mockImplementation(() => {
    times.push(Date.now())
    return Promise.resolve({})
  })
  await vi.advanceTimersByTimeAsync(1)
  source.emit('ready', { dropIds: [101], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(1)
  expect(times).toHaveLength(1)

  source.emit('drop-changed', { dropId: 101, changes: ['STOCK'] })
  await vi.advanceTimersByTimeAsync(500)
  source.emit('drop-changed', { dropId: 101, changes: ['SALES'] })
  await vi.advanceTimersByTimeAsync(500)
  source.emit('drop-changed', { dropId: 101, changes: ['STATUS'] })
  await vi.advanceTimersByTimeAsync(1000)
  expect(times).toHaveLength(2)
  expect(times[1] - times[0]).toBeGreaterThanOrEqual(2000)

  subscription.dispose()
})

test('조회 중 변경은 같은 키를 병렬로 쌓지 않고 완료 뒤 후속 조회한다', async () => {
  const { source, fetchQuery, subscription } = setup([101])
  let finish: (() => void) | undefined
  fetchQuery.mockImplementationOnce(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve
      }),
  )
  await vi.advanceTimersByTimeAsync(1)
  source.emit('ready', { dropIds: [101], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(1)

  source.emit('drop-changed', { dropId: 101, changes: ['STOCK'] })
  await vi.advanceTimersByTimeAsync(2000)
  expect(fetchQuery).toHaveBeenCalledTimes(1)

  finish?.()
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(2)

  subscription.dispose()
})

// GR-209: 목록도 변경 알림으로 생긴 재조회만 max(최소 간격의 남은 시간, 0~1초 무작위)를 기다린다.
async function listReadyAndIdle() {
  const context = setup()
  await vi.advanceTimersByTimeAsync(1)
  context.source.emit('ready', {
    dropIds: [101, 102],
    serverTime: '2026-10-19T00:00:00Z',
  })
  await vi.advanceTimersByTimeAsync(1)
  await vi.advanceTimersByTimeAsync(5000)
  context.fetchQuery.mockClear()
  return context
}

test('목록 변경 알림 재조회는 최소 간격이 지났으면 0~1초 중 무작위로 기다린다', async () => {
  const { source, fetchQuery, subscription } = await listReadyAndIdle()
  vi.spyOn(Math, 'random').mockReturnValue(0.3)

  source.emit('drop-changed', { dropId: 101, changes: ['STOCK'] })
  await vi.advanceTimersByTimeAsync(299)
  expect(fetchQuery).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(1)
  subscription.dispose()
})

test('목록 수동 갱신은 알림으로 분산 대기 중인 예약을 당겨 바로 조회한다', async () => {
  const { source, fetchQuery, subscription } = await listReadyAndIdle()
  vi.spyOn(Math, 'random').mockReturnValue(0.9)

  source.emit('drop-changed', { dropId: 101, changes: ['STOCK'] })
  await vi.advanceTimersByTimeAsync(100)
  subscription.refresh()
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(2000)
  expect(fetchQuery).toHaveBeenCalledTimes(1)
  subscription.dispose()
})
