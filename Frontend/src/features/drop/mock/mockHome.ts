import type { Drop } from '../../../types/drop'
import { catalogDrops, type MockCatalogDrop } from './mockCatalog'

export const HOME_LIMIT = 8

const HOME_MOCK_DELAY = 300

export type HomeSectionKey = 'GRAB' | 'WISH'

export type HomeSectionConfig = {
  status: HomeSectionKey
  title: string
  to: string
}

const byPopularity = (status: HomeSectionKey) =>
  catalogDrops
    .filter((drop) => drop.status === status)
    .sort((a, b) =>
      status === 'GRAB'
        ? b.soldQuantity - a.soldQuantity
        : b.wishCount - a.wishCount,
    )
    .slice(0, HOME_LIMIT)

// 두 영역은 각자의 인기 정렬 기준으로 분리된 목록을 사용한다.
export const homeSections: HomeSectionConfig[] = [
  { status: 'GRAB', title: '지금 인기 GRAB', to: '/grab' },
  { status: 'WISH', title: '곧 만날 인기 WISH', to: '/wish' },
]

export const homeDrops: Record<HomeSectionKey, MockCatalogDrop[]> = {
  GRAB: byPopularity('GRAB'),
  WISH: byPopularity('WISH'),
}

// 실제 연동 시 GET /api/v1/drops?status=...&sort=... 요청으로 교체한다.
// Mock은 지연 후 각 영역 목록을 반환하고, 실패는 호출부에서 처리한다.
export function fetchHomeDrops(status: HomeSectionKey): Promise<Drop[]> {
  return new Promise((resolve) => {
    const drops = homeDrops[status]
    setTimeout(() => resolve(drops), HOME_MOCK_DELAY)
  })
}
