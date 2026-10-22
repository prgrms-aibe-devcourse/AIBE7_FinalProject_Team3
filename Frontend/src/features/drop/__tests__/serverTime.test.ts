import { expect, test } from 'vitest'
import { openingSeconds, serverTimeAt } from '../logic/serverTime'

const serverTime = '2026-10-22T10:00:00+09:00'
const startsAt = '2026-10-22T10:10:01+09:00'
const receivedAt = Date.parse(serverTime) + 5 * 60_000

test('클라이언트 시각 오차와 무관하게 응답 수신 뒤 경과 시간을 더한다', () => {
  for (const skew of [-300_000, 300_000]) {
    const received = Date.parse(serverTime) + skew
    expect(serverTimeAt(serverTime, received, received + 1000)).toBe(
      Date.parse(serverTime) + 1000,
    )
    expect(openingSeconds(startsAt, serverTime, received, received)).toBe(601)
    expect(
      openingSeconds(startsAt, serverTime, received, received + 1000),
    ).toBe(600)
  }
})

test('캐시 경과·초 올림·0초 제한과 잘못된 시각을 처리한다', () => {
  expect(
    openingSeconds(startsAt, serverTime, receivedAt, receivedAt + 600_001),
  ).toBe(1)
  expect(
    openingSeconds(startsAt, serverTime, receivedAt, receivedAt + 601_000),
  ).toBe(0)
  expect(
    openingSeconds(startsAt, serverTime, receivedAt, receivedAt + 900_000),
  ).toBe(0)
  expect(openingSeconds(startsAt, 'invalid', receivedAt, receivedAt)).toBeNull()
  expect(
    openingSeconds('invalid', serverTime, receivedAt, receivedAt),
  ).toBeNull()
  expect(serverTimeAt(serverTime, undefined, receivedAt)).toBeNaN()
})
