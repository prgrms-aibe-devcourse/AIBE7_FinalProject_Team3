import { QueryClient } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { getDropDetail, getDropStocks } from '../../../api/drop/drops'
import { openDropStockStream } from '../../../api/drop/stockStream'
import { dropKeys } from '../logic/queryKeys'
import { subscribeDropStockStream } from '../logic/useDropStockStream'

vi.mock('../../../api/drop/drops', () => ({
  getDropDetail: vi.fn(),
  getDropStocks: vi.fn(),
}))
vi.mock('../../../api/drop/stockStream', () => ({
  openDropStockStream: vi.fn(),
}))

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

function setup() {
  const source = new FakeSource()
  vi.mocked(openDropStockStream).mockReturnValue(
    source as unknown as EventSource,
  )
  const fetchQuery = vi.fn().mockResolvedValue({})
  const cancelQueries = vi.fn().mockResolvedValue(undefined)
  const client = {
    fetchQuery,
    cancelQueries,
    getQueryCache: () => ({ subscribe: () => () => undefined }),
    getQueryData: () => undefined,
    getQueryState: () => undefined,
  } as unknown as QueryClient
  const subscription = subscribeDropStockStream(100, client)
  return {
    source,
    fetchQuery,
    cancelQueries,
    stop: subscription.dispose,
    refresh: subscription.refresh,
  }
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.resetAllMocks()
})

test('수동 refresh는 상세와 재고 키를 정확히 조회한다', async () => {
  const { fetchQuery, refresh, stop } = setup()
  refresh()
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(2)
  expect(fetchQuery.mock.calls.map(([arg]) => arg.queryKey)).toEqual([
    ['drops', 'detail', 100],
    ['drops', 'detail', 100, 'stocks'],
  ])
  stop()
})

test('연속 refresh는 요청을 하나로 묶는다', async () => {
  const { fetchQuery, refresh, stop } = setup()
  refresh()
  refresh()
  refresh()
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(2)
  stop()
})

test('refresh는 조회 중이면 후속 조회로 병합한다', async () => {
  const { fetchQuery, refresh, stop } = setup()
  let finishStocks: (() => void) | undefined
  fetchQuery.mockImplementation(({ queryKey }) =>
    queryKey.length === 4 && !finishStocks
      ? new Promise<void>((resolve) => {
          finishStocks = resolve
        })
      : Promise.resolve({}),
  )
  refresh()
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(2)

  refresh()
  await vi.advanceTimersByTimeAsync(2000)
  expect(fetchQuery).toHaveBeenCalledTimes(3)

  finishStocks?.()
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(4)
  stop()
})

test('refresh는 이전 조회 시작 뒤 2초가 지나기 전에 다시 요청하지 않는다', async () => {
  vi.setSystemTime(new Date('2026-10-19T00:00:00Z'))
  const { fetchQuery, refresh, stop } = setup()
  const times: number[] = []
  fetchQuery.mockImplementation(() => {
    times.push(Date.now())
    return Promise.resolve({})
  })
  refresh()
  await vi.advanceTimersByTimeAsync(1)
  expect(times).toHaveLength(2)

  refresh()
  await vi.advanceTimersByTimeAsync(1998)
  expect(times).toHaveLength(2)
  await vi.advanceTimersByTimeAsync(2)
  expect(times).toHaveLength(4)
  expect(times[2] - times[0]).toBeGreaterThanOrEqual(2000)
  stop()
})

test('dispose 뒤 refresh와 늦은 변경은 요청을 만들지 않는다', async () => {
  const { source, fetchQuery, refresh, stop } = setup()
  stop()
  refresh()
  source.emit('drop-changed', { dropId: 100, changes: ['STOCK'] })
  await vi.advanceTimersByTimeAsync(3000)
  expect(fetchQuery).not.toHaveBeenCalled()
})

test('ready 뒤 상세와 재고를 조회하고 변경 종류에 맞는 정확한 키만 갱신한다', async () => {
  const { source, fetchQuery, cancelQueries, stop } = setup()
  expect(openDropStockStream).toHaveBeenCalledWith(100)

  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(0)
  expect(cancelQueries).toHaveBeenCalledWith({
    queryKey: ['drops', 'detail', 100],
    exact: true,
  })
  expect(cancelQueries).toHaveBeenCalledWith({
    queryKey: ['drops', 'detail', 100, 'stocks'],
    exact: true,
  })
  expect(fetchQuery).toHaveBeenCalledTimes(2)

  source.emit('drop-changed', { dropId: 100, changes: ['SALES'] })
  await vi.advanceTimersByTimeAsync(2000)
  expect(fetchQuery).toHaveBeenCalledTimes(3)
  expect(fetchQuery.mock.lastCall?.[0].queryKey).toEqual([
    'drops',
    'detail',
    100,
    'stocks',
  ])

  source.emit('drop-changed', { dropId: 100, changes: ['STOCK', 'STATUS'] })
  await vi.advanceTimersByTimeAsync(2000)
  expect(fetchQuery).toHaveBeenCalledTimes(5)
  stop()
})

test('조회 중 연속 변경은 묶고 완료 뒤 후속 조회한다', async () => {
  const { source, fetchQuery, stop } = setup()
  let finishStocks: (() => void) | undefined
  fetchQuery.mockImplementation(({ queryKey }) =>
    queryKey.length === 4 && !finishStocks
      ? new Promise<void>((resolve) => {
          finishStocks = resolve
        })
      : Promise.resolve({}),
  )

  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(0)
  source.emit('drop-changed', { dropId: 100, changes: ['SALES'] })
  source.emit('drop-changed', { dropId: 100, changes: ['SALES'] })
  await vi.advanceTimersByTimeAsync(2000)
  expect(fetchQuery).toHaveBeenCalledTimes(2)

  finishStocks?.()
  await vi.advanceTimersByTimeAsync(0)
  expect(fetchQuery).toHaveBeenCalledTimes(3)
  stop()
})

test('이전 연결을 닫으면 예약된 갱신과 늦은 이벤트를 배제한다', async () => {
  const { source, fetchQuery, stop } = setup()
  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(0)
  source.emit('drop-changed', { dropId: 100, changes: ['STOCK'] })
  stop()
  source.emit('drop-changed', { dropId: 100, changes: ['STOCK'] })
  await vi.advanceTimersByTimeAsync(2000)
  expect(source.close).toHaveBeenCalledTimes(1)
  expect(fetchQuery).toHaveBeenCalledTimes(2)
})

test('다른 상품이나 잘못된 변경 알림은 적용하지 않고 연결을 닫는다', async () => {
  const { source, fetchQuery, stop } = setup()
  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(0)
  source.emit('drop-changed', { dropId: 101, changes: ['STOCK'] })
  await vi.advanceTimersByTimeAsync(2000)
  expect(source.close).toHaveBeenCalledTimes(1)
  expect(fetchQuery).toHaveBeenCalledTimes(2)
  stop()
})

test('ready 이전의 늦은 상세 응답은 새 조회 결과를 덮지 못한다', async () => {
  const source = new FakeSource()
  vi.mocked(openDropStockStream).mockReturnValue(
    source as unknown as EventSource,
  )
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  })
  let finishOld: ((value: { dropId: number; name: string }) => void) | undefined
  vi.mocked(getDropDetail)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOld = resolve
        }),
    )
    .mockResolvedValue({ dropId: 100, name: '최신 상품' })
  vi.mocked(getDropStocks).mockResolvedValue({ dropId: 100, options: [] })
  const old = client
    .fetchQuery({
      queryKey: dropKeys.detail(100),
      queryFn: ({ signal }) => getDropDetail(100, signal),
    })
    .catch(() => undefined)

  const { dispose: stop } = subscribeDropStockStream(100, client)
  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(0)
  expect(client.getQueryData(dropKeys.detail(100))).toEqual({
    dropId: 100,
    name: '최신 상품',
  })

  finishOld?.({ dropId: 100, name: '오래된 상품' })
  await old
  expect(client.getQueryData(dropKeys.detail(100))).toEqual({
    dropId: 100,
    name: '최신 상품',
  })
  stop()
  client.clear()
})

test('ready 지연과 연결 오류에는 장애 조회와 제한된 재연결을 사용한다', async () => {
  vi.spyOn(Math, 'random').mockReturnValue(0)
  const { source, fetchQuery, stop } = setup()
  const next = new FakeSource()
  vi.mocked(openDropStockStream).mockReturnValueOnce(
    next as unknown as EventSource,
  )

  await vi.advanceTimersByTimeAsync(10000)
  expect(source.close).toHaveBeenCalledTimes(1)
  expect(fetchQuery).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(2499)
  expect(openDropStockStream).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(1)
  expect(openDropStockStream).toHaveBeenCalledTimes(2)

  next.onerror?.()
  await vi.advanceTimersByTimeAsync(4999)
  expect(openDropStockStream).toHaveBeenCalledTimes(2)
  await vi.advanceTimersByTimeAsync(1)
  expect(openDropStockStream).toHaveBeenCalledTimes(3)
  await vi.advanceTimersByTimeAsync(2501)
  expect(fetchQuery).toHaveBeenCalledTimes(4)
  await vi.advanceTimersByTimeAsync(9998)
  expect(fetchQuery).toHaveBeenCalledTimes(4)
  await vi.advanceTimersByTimeAsync(2)
  expect(fetchQuery).toHaveBeenCalledTimes(6)
  stop()
})

test('연결이 반복 실패해도 대체 조회가 예정된 주기로 실행된다', async () => {
  vi.spyOn(Math, 'random').mockReturnValue(0.9)
  vi.setSystemTime(new Date('2026-10-19T00:00:00Z'))
  vi.mocked(openDropStockStream).mockImplementation(() => {
    const next = new FakeSource()
    globalThis.setTimeout(() => next.onerror?.(), 0)
    return next as unknown as EventSource
  })
  const fetchQuery = vi.fn().mockResolvedValue({})
  const client = {
    fetchQuery,
    cancelQueries: vi.fn().mockResolvedValue(undefined),
    getQueryCache: () => ({ subscribe: () => () => undefined }),
    getQueryData: () => undefined,
    getQueryState: () => undefined,
  } as unknown as QueryClient
  const startedAt = Date.now()
  const calls: number[] = []
  fetchQuery.mockImplementation(() => {
    calls.push(Date.now() - startedAt)
    return Promise.resolve({})
  })
  const { dispose: stop } = subscribeDropStockStream(100, client)

  await vi.advanceTimersByTimeAsync(9999)
  expect(calls).toHaveLength(0)
  await vi.advanceTimersByTimeAsync(2001)
  expect(calls.length).toBeGreaterThanOrEqual(2)
  expect(calls[0]).toBeGreaterThanOrEqual(10000)
  expect(calls[0]).toBeLessThanOrEqual(12500)

  await vi.advanceTimersByTimeAsync(13000)
  expect(calls.length).toBeGreaterThanOrEqual(4)
  stop()
})

test('정상 연결이 끊기면 30초 예약을 기다리지 않고 장애 주기로 전환한다', async () => {
  vi.spyOn(Math, 'random').mockReturnValue(0)
  vi.setSystemTime(new Date('2026-10-19T00:00:00Z'))
  const startedAt = Date.now()
  const { source, fetchQuery, stop } = setup()
  const calls: number[] = []
  fetchQuery.mockImplementation(() => {
    calls.push(Date.now() - startedAt)
    return Promise.resolve({})
  })
  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(0)
  expect(calls).toHaveLength(2)

  await vi.advanceTimersByTimeAsync(5000)
  source.onerror?.()
  await vi.advanceTimersByTimeAsync(9999)
  expect(calls).toHaveLength(2)
  await vi.advanceTimersByTimeAsync(2)
  expect(calls.length).toBeGreaterThanOrEqual(4)
  expect(calls[2]).toBeGreaterThanOrEqual(15000)
  expect(calls[2]).toBeLessThanOrEqual(17500)
  stop()
})

test('ready 복구 뒤 정상 주기로 돌아가고 장애 주기 타이머가 남지 않는다', async () => {
  vi.spyOn(Math, 'random').mockReturnValue(0)
  vi.setSystemTime(new Date('2026-10-19T00:00:00Z'))
  const { source, fetchQuery, stop } = setup()
  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(0)
  expect(fetchQuery).toHaveBeenCalledTimes(2)

  await vi.advanceTimersByTimeAsync(1000)
  source.onerror?.()
  await vi.advanceTimersByTimeAsync(2500)
  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(0)
  expect(fetchQuery).toHaveBeenCalledTimes(4)

  await vi.advanceTimersByTimeAsync(8999)
  expect(fetchQuery).toHaveBeenCalledTimes(4)
  await vi.advanceTimersByTimeAsync(21003)
  expect(fetchQuery).toHaveBeenCalledTimes(6)
  stop()
})

test('정상 연결 중에는 30초마다 재동기화한다', async () => {
  vi.spyOn(Math, 'random').mockReturnValue(0)
  const { source, fetchQuery, stop } = setup()
  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(0)
  expect(fetchQuery).toHaveBeenCalledTimes(2)
  await vi.advanceTimersByTimeAsync(30000)
  await vi.advanceTimersByTimeAsync(1)
  expect(fetchQuery).toHaveBeenCalledTimes(4)
  stop()
})

test('서버의 재연결 안내는 이전 연결을 닫고 최소 간격 뒤 새로 연결한다', async () => {
  vi.spyOn(Math, 'random').mockReturnValue(0)
  const { source, fetchQuery, stop } = setup()
  const next = new FakeSource()
  vi.mocked(openDropStockStream).mockReturnValueOnce(
    next as unknown as EventSource,
  )
  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(0)
  source.emit('stream-closed', {
    reason: 'RECONNECT_REQUIRED',
    reconnect: true,
  })
  source.emit('drop-changed', { dropId: 100, changes: ['STOCK'] })
  expect(source.close).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(4999)
  expect(openDropStockStream).toHaveBeenCalledTimes(1)
  expect(fetchQuery).toHaveBeenCalledTimes(2)
  await vi.advanceTimersByTimeAsync(1)
  expect(openDropStockStream).toHaveBeenCalledTimes(2)
  stop()
})

test('숨김 탭은 연결과 주기를 멈추고 복귀 후 다시 조회한다', async () => {
  vi.spyOn(Math, 'random').mockReturnValue(0)
  const page = new EventTarget()
  let visibilityState = 'visible'
  Object.defineProperty(page, 'visibilityState', {
    get: () => visibilityState,
  })
  vi.stubGlobal('document', page)
  vi.stubGlobal('window', new EventTarget())
  vi.stubGlobal('navigator', { onLine: true })
  const { source, fetchQuery, stop } = setup()
  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(0)

  visibilityState = 'hidden'
  page.dispatchEvent(new Event('visibilitychange'))
  const before = fetchQuery.mock.calls.length
  await vi.advanceTimersByTimeAsync(60000)
  expect(source.close).toHaveBeenCalledTimes(1)
  expect(fetchQuery).toHaveBeenCalledTimes(before)
  expect(openDropStockStream).toHaveBeenCalledTimes(1)

  visibilityState = 'visible'
  page.dispatchEvent(new Event('visibilitychange'))
  await vi.advanceTimersByTimeAsync(0)
  expect(fetchQuery).toHaveBeenCalledTimes(before + 2)
  expect(openDropStockStream).toHaveBeenCalledTimes(2)
  stop()
})

test('오프라인에는 주기를 멈추고 온라인 복귀 후 다시 조회한다', async () => {
  vi.spyOn(Math, 'random').mockReturnValue(0)
  const page = new EventTarget()
  Object.defineProperty(page, 'visibilityState', {
    get: () => 'visible',
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
  const { source, fetchQuery, stop } = setup()
  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(0)
  const before = fetchQuery.mock.calls.length

  onLine = false
  win.dispatchEvent(new Event('offline'))
  await vi.advanceTimersByTimeAsync(60000)
  expect(source.close).toHaveBeenCalledTimes(1)
  expect(fetchQuery).toHaveBeenCalledTimes(before)
  expect(openDropStockStream).toHaveBeenCalledTimes(1)

  onLine = true
  win.dispatchEvent(new Event('online'))
  await vi.advanceTimersByTimeAsync(0)
  expect(fetchQuery).toHaveBeenCalledTimes(before + 2)
  stop()
})

test('서버 시각으로 계산한 판매 시작 시각에 상세를 다시 조회한다', async () => {
  vi.setSystemTime(new Date('2026-10-19T00:00:00Z'))
  const source = new FakeSource()
  vi.mocked(openDropStockStream).mockReturnValue(
    source as unknown as EventSource,
  )
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  })
  client.setQueryData(dropKeys.detail(100), {
    dropId: 100,
    serverTime: '2026-10-19T00:00:00Z',
    saleStartsAt: '2026-10-19T00:00:05Z',
  })
  vi.mocked(getDropDetail).mockResolvedValue({
    dropId: 100,
    serverTime: '2026-10-19T00:00:06Z',
    saleStartsAt: '2026-10-19T00:00:05Z',
  })
  vi.mocked(getDropStocks).mockResolvedValue({ dropId: 100, options: [] })
  const { dispose: stop } = subscribeDropStockStream(100, client)

  await vi.advanceTimersByTimeAsync(4999)
  expect(getDropDetail).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(1)
  await vi.advanceTimersByTimeAsync(1)
  expect(getDropDetail).toHaveBeenCalledTimes(1)
  stop()
  client.clear()
})

test('상세 조회의 404가 확인되면 연결과 복구 타이머를 멈춘다', async () => {
  vi.spyOn(Math, 'random').mockReturnValue(0)
  const source = new FakeSource()
  vi.mocked(openDropStockStream).mockReturnValue(
    source as unknown as EventSource,
  )
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  })
  vi.mocked(getDropDetail).mockRejectedValue({
    isAxiosError: true,
    response: { status: 404 },
  })
  vi.mocked(getDropStocks).mockResolvedValue({ dropId: 100, options: [] })
  const { dispose: stop } = subscribeDropStockStream(100, client)
  source.emit('ready', { dropIds: [100], serverTime: '2026-10-19T00:00:00Z' })
  await vi.advanceTimersByTimeAsync(1)
  expect(source.close).toHaveBeenCalledTimes(1)
  expect(client.getQueryState(dropKeys.detail(100))?.status).toBe('error')
  await vi.advanceTimersByTimeAsync(60000)
  expect(openDropStockStream).toHaveBeenCalledTimes(1)
  expect(getDropDetail).toHaveBeenCalledTimes(1)
  stop()
  client.clear()
})

test.each([3000, 6000])(
  '캐시 수신 뒤 %ims가 지나도 판매 경계를 뒤로 미루지 않는다',
  async (elapsed) => {
    vi.setSystemTime(new Date('2026-10-19T00:05:00Z'))
    const source = new FakeSource()
    vi.mocked(openDropStockStream).mockReturnValue(
      source as unknown as EventSource,
    )
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: Infinity } },
    })
    client.setQueryData(dropKeys.detail(100), {
      dropId: 100,
      serverTime: '2026-10-19T00:00:00Z',
      saleStartsAt: '2026-10-19T00:00:05Z',
    })
    await vi.advanceTimersByTimeAsync(elapsed)
    vi.mocked(getDropDetail).mockResolvedValue({
      dropId: 100,
      serverTime: '2026-10-19T00:00:06Z',
      saleStartsAt: '2026-10-19T00:00:05Z',
    })
    vi.mocked(getDropStocks).mockResolvedValue({ dropId: 100, options: [] })
    const { dispose } = subscribeDropStockStream(100, client)
    await vi.advanceTimersByTimeAsync(Math.max(0, 5000 - elapsed) + 1)
    expect(getDropDetail).toHaveBeenCalledTimes(1)
    dispose()
    await vi.advanceTimersByTimeAsync(3000)
    expect(getDropDetail).toHaveBeenCalledTimes(1)
    client.clear()
  },
)

test('판매 경계가 지난 뒤 복귀해도 같은 갱신을 후속 요청으로 중복하지 않는다', async () => {
  vi.setSystemTime(new Date('2026-10-19T00:00:00Z'))
  const win = new EventTarget()
  let online = true
  vi.stubGlobal('window', win)
  vi.stubGlobal('navigator', {
    get onLine() {
      return online
    },
  })
  const source = new FakeSource()
  vi.mocked(openDropStockStream).mockReturnValue(
    source as unknown as EventSource,
  )
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  })
  const detail = {
    dropId: 100,
    serverTime: '2026-10-19T00:00:00Z',
    saleStartsAt: '2026-10-19T00:00:05Z',
  }
  client.setQueryData(dropKeys.detail(100), detail)
  vi.mocked(getDropDetail).mockImplementation(
    () =>
      new Promise((resolve) => {
        setTimeout(
          () => resolve({ ...detail, serverTime: '2026-10-19T00:00:06Z' }),
          10,
        )
      }),
  )
  vi.mocked(getDropStocks).mockResolvedValue({ dropId: 100, options: [] })
  const { dispose } = subscribeDropStockStream(100, client)
  online = false
  win.dispatchEvent(new Event('offline'))
  await vi.advanceTimersByTimeAsync(6000)
  online = true
  win.dispatchEvent(new Event('online'))
  await vi.advanceTimersByTimeAsync(2100)
  expect(getDropDetail).toHaveBeenCalledTimes(1)
  expect(getDropStocks).toHaveBeenCalledTimes(1)
  dispose()
  client.clear()
})
