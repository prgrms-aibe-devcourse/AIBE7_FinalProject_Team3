import { describe, expect, it } from 'vitest'
import type { MockCatalogDrop } from '../mock/mockCatalog'
import {
  HOME_LIMIT,
  fetchHomeDrops,
  homeDrops,
  homeSections,
} from '../mock/mockHome'

const availableStock = (drop: MockCatalogDrop) =>
  drop.skus.reduce(
    (sum, sku) => sum + (sku.active === false ? 0 : sku.stock),
    0,
  )

describe('홈 인기 Mock', () => {
  it('GRAB은 판매 수량, WISH는 WISH 수 기준 내림차순으로 최대 8개를 준다', () => {
    expect(homeDrops.GRAB.length).toBeLessThanOrEqual(HOME_LIMIT)
    expect(homeDrops.WISH.length).toBeLessThanOrEqual(HOME_LIMIT)

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

  it('GRAB은 구매 가능한 상품, WISH는 판매 시작 전 상품만 담는다', () => {
    const now = Date.now()
    for (const drop of homeDrops.GRAB) {
      expect(drop.status === 'WISH' || drop.status === 'GRAB').toBe(true)
      expect(new Date(drop.saleStartsAt).getTime()).toBeLessThanOrEqual(now)
      expect(new Date(drop.saleEndsAt).getTime()).toBeGreaterThan(now)
      expect(availableStock(drop)).toBeGreaterThan(0)
    }
    for (const drop of homeDrops.WISH) {
      expect(drop.status).toBe('WISH')
      expect(new Date(drop.saleStartsAt).getTime()).toBeGreaterThan(now)
    }
  })

  it('두 영역은 서로 다른 목록과 전체 보기 경로를 가진다', () => {
    expect(homeSections.map((section) => section.status)).toEqual([
      'GRAB',
      'WISH',
    ])
    expect(homeSections.map((section) => section.to)).toEqual([
      '/grab?sort=soldQuantity,desc',
      '/wish?sort=wishCount,desc',
    ])
    expect(homeDrops.GRAB).not.toEqual(homeDrops.WISH)
  })

  it('영역별 조회는 지연 후 해당 목록을 반환한다', async () => {
    await expect(fetchHomeDrops('GRAB')).resolves.toBe(homeDrops.GRAB)
    await expect(fetchHomeDrops('WISH')).resolves.toBe(homeDrops.WISH)
  })
})
