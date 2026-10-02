import { describe, expect, it } from 'vitest'
import type { Drop, DropStatus } from '../../types/drop'
import { upcoming } from './selectors'

const now = Date.UTC(2026, 0, 10, 12, 0, 0)
const at = (hours: number) => new Date(now + hours * 3_600_000).toISOString()

const drop = (status: DropStatus, startsIn: number, endsIn: number) =>
  ({
    status,
    saleStartsAt: at(startsIn),
    saleEndsAt: at(endsIn),
  }) as Drop

describe('upcoming', () => {
  it('WISH는 시작 시각을 본다', () => {
    expect(upcoming(drop('WISH', 5, 200), now)).toEqual({
      eventType: 'START',
      label: '시작 임박',
      remain: '5시간 후',
    })
  })

  it('GRAB은 종료 시각을 본다', () => {
    expect(upcoming(drop('GRAB', -10, 3), now)).toEqual({
      eventType: 'END',
      label: '종료 임박',
      remain: '3시간 후',
    })
  })

  it('1시간 미만은 분으로 표시한다', () => {
    expect(upcoming(drop('GRAB', -10, 0.5), now)?.remain).toBe('30분 후')
  })

  it('24시간을 넘으면 임박이 아니다', () => {
    expect(upcoming(drop('WISH', 25, 200), now)).toBeNull()
  })

  it('이미 지난 일정은 임박이 아니다', () => {
    expect(upcoming(drop('GRAB', -10, -1), now)).toBeNull()
  })

  it('ENDED는 임박 대상이 아니다', () => {
    expect(upcoming(drop('ENDED', 1, 2), now)).toBeNull()
  })
})
