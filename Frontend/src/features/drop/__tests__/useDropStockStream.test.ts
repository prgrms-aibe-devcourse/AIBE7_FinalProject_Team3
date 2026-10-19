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
  const client = { fetchQuery, cancelQueries } as unknown as QueryClient
  const stop = subscribeDropStockStream(100, client)
  return { source, fetchQuery, cancelQueries, stop }
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
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
