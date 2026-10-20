import { QueryClient } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { openDropStockStream } from '../../../api/drop/stockStream'
import { dropKeys, type DropListParams } from '../logic/queryKeys'
import { subscribeDropListStockStream } from '../logic/useDropListStockStream'

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
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.resetAllMocks()
})

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
