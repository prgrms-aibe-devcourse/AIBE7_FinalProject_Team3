import type { Drop } from '../../types/drop'
import { drops } from './mockDrops'

// 목록 정렬용 예시 데이터다. 기존 상세·판매자 Mock과 API 계약은 변경하지 않는다.
export type MockCatalogDrop = Drop & {
  publishedAt: string
  createdAt: string
  // 결제 확정·미취소 주문의 판매 수량 예시. SKU의 sold 재고와 구분한다.
  soldQuantity: number
}

const catalogMetrics: Record<
  number,
  { publishedDaysAgo: number; soldQuantity: number }
> = {
  101: { publishedDaysAgo: 7, soldQuantity: 0 },
  102: { publishedDaysAgo: 5, soldQuantity: 0 },
  103: { publishedDaysAgo: 3, soldQuantity: 0 },
  201: { publishedDaysAgo: 8, soldQuantity: 27 },
  202: { publishedDaysAgo: 4, soldQuantity: 12 },
  203: { publishedDaysAgo: 6, soldQuantity: 18 },
}

const now = Date.now()
const daysAgo = (days: number) =>
  new Date(now - days * 86_400_000).toISOString()

export const catalogDrops: MockCatalogDrop[] = drops
  .filter((drop) => drop.status === 'WISH' || drop.status === 'GRAB')
  .map((drop) => {
    const metrics = catalogMetrics[drop.id]
    return {
      ...drop,
      publishedAt: daysAgo(metrics.publishedDaysAgo),
      createdAt: daysAgo(metrics.publishedDaysAgo + 2),
      soldQuantity: metrics.soldQuantity,
    }
  })
