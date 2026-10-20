import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { normalizeDropIds, openDropStockStream } from '../stockStream'

class FakeEventSource {
  readonly url: string
  readonly options?: EventSourceInit

  constructor(url: string | URL, options?: EventSourceInit) {
    this.url = String(url)
    this.options = options
    sources.push(this)
  }
}

const sources: FakeEventSource[] = []

beforeEach(() => {
  sources.length = 0
  vi.stubGlobal('EventSource', FakeEventSource)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

test('단일 상세 ID는 기존 URL과 withCredentials 설정을 유지한다', () => {
  openDropStockStream(100)
  expect(sources).toHaveLength(1)
  expect(sources[0]?.url).toBe('/api/v1/drops/stock-stream?dropIds=100')
  expect(sources[0]?.options).toEqual({ withCredentials: true })
})

test('목록 ID는 검증 후 중복 제거·오름차순으로 정규화한다', () => {
  openDropStockStream([103, 101, 103, 102, 101])
  expect(sources).toHaveLength(1)
  expect(sources[0]?.url).toBe('/api/v1/drops/stock-stream?dropIds=101,102,103')
})

test('최대 100개까지 한 연결로 구독한다', () => {
  const ids = Array.from({ length: 100 }, (_, index) => index + 1)
  openDropStockStream(ids)
  expect(sources).toHaveLength(1)
  expect(sources[0]?.url).toBe(
    `/api/v1/drops/stock-stream?dropIds=${ids.join(',')}`,
  )
})

test('빈 배열과 잘못된 ID는 연결을 만들지 않는다', () => {
  expect(() => normalizeDropIds([])).toThrow(RangeError)
  for (const dropId of [0, -1, 1.5, Number.NaN, Number.MAX_SAFE_INTEGER + 1])
    expect(() => openDropStockStream(dropId)).toThrow(RangeError)
  expect(sources).toHaveLength(0)
})

test('중복 제거 전 100개를 넘으면 임의로 자르지 않고 거부한다', () => {
  const ids = Array.from({ length: 101 }, (_, index) => index + 1)
  expect(() => openDropStockStream(ids)).toThrow(RangeError)
  expect(() => openDropStockStream([100, ...ids])).toThrow(RangeError)
  expect(sources).toHaveLength(0)
})
