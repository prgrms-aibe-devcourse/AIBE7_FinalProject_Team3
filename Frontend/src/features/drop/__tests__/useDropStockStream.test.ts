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
  } as unknown as QueryClient
  const stop = subscribeDropStockStream(100, client)
  return { source, fetchQuery, cancelQueries, stop }
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.resetAllMocks()
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

  const stop = subscribeDropStockStream(100, client)
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
  await vi.advanceTimersByTimeAsync(5000)
  expect(fetchQuery).toHaveBeenCalledTimes(2)
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
  const stop = subscribeDropStockStream(100, client)

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
  const stop = subscribeDropStockStream(100, client)
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
