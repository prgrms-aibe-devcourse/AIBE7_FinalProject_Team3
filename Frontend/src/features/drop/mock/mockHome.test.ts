import { describe, expect, it } from 'vitest'
import { HOME_LIMIT, fetchHomeDrops, homeDrops, homeSections } from './mockHome'

describe('홈 인기 Mock', () => {
  it('GRAB은 판매 수량, WISH는 WISH 수 기준 내림차순으로 최대 8개를 준다', () => {
    expect(homeDrops.GRAB.length).toBeLessThanOrEqual(HOME_LIMIT)
    expect(homeDrops.WISH.length).toBeLessThanOrEqual(HOME_LIMIT)

    expect(homeDrops.GRAB.every((drop) => drop.status === 'GRAB')).toBe(true)
    expect(homeDrops.WISH.every((drop) => drop.status === 'WISH')).toBe(true)

    for (let i = 1; i < homeDrops.GRAB.length; i += 1) {
      expect(homeDrops.GRAB[i - 1].soldQuantity).toBeGreaterThanOrEqual(
        homeDrops.GRAB[i].soldQuantity,
      )
    }
    for (let i = 1; i < homeDrops.WISH.length; i += 1) {
      expect(homeDrops.WISH[i - 1].wishCount).toBeGreaterThanOrEqual(
        homeDrops.WISH[i].wishCount,
      )
    }
  })

  it('두 영역은 서로 다른 목록과 전체 보기 경로를 가진다', () => {
    expect(homeSections.map((section) => section.status)).toEqual([
      'GRAB',
      'WISH',
    ])
    expect(homeSections.map((section) => section.to)).toEqual([
      '/grab',
      '/wish',
    ])
    expect(homeDrops.GRAB).not.toEqual(homeDrops.WISH)
  })

  it('영역별 조회는 지연 후 해당 목록을 반환한다', async () => {
    await expect(fetchHomeDrops('GRAB')).resolves.toBe(homeDrops.GRAB)
    await expect(fetchHomeDrops('WISH')).resolves.toBe(homeDrops.WISH)
  })
})
