import type { Drop } from '../../../types/drop'
import { queryCatalog } from '../catalog'
import { catalogDrops, type MockCatalogDrop } from './mockCatalog'

export const HOME_LIMIT = 8

const HOME_MOCK_DELAY = 300

export type HomeSectionKey = 'GRAB' | 'WISH'

export type HomeSectionConfig = {
  status: HomeSectionKey
  title: string
  to: string
}

// 인기 조회도 목록과 같은 조건을 쓴다. GRAB은 서버 시각상 구매 가능한 상품,
// WISH는 판매 시작 전 상품만 후보가 되고, 동률은 공개 시각·ID로 갈린다.
const byPopularity = (status: HomeSectionKey) =>
  queryCatalog(
    catalogDrops,
    status === 'GRAB'
      ? { status, sort: 'soldQuantity,desc' }
      : { status, sort: 'wishCount,desc' },
  ).content.slice(0, HOME_LIMIT)

// 두 영역은 각자의 인기 정렬 기준으로 분리된 목록을 사용한다.
export const homeSections: HomeSectionConfig[] = [
  {
    status: 'GRAB',
    title: '지금 인기 GRAB',
    to: '/grab?sort=soldQuantity,desc',
  },
  {
    status: 'WISH',
    title: '곧 만날 인기 WISH',
    to: '/wish?sort=wishCount,desc',
  },
]

// 홈을 열 때 처음 계산한다. 목록 Mock만 실패한 경우 홈 Mock 임포트가 영향을 받지 않는다.
let cached: Record<HomeSectionKey, MockCatalogDrop[]> | null = null
const ensureHomeDrops = () =>
  (cached ??= { GRAB: byPopularity('GRAB'), WISH: byPopularity('WISH') })

export const homeDrops = {
  get GRAB() {
    return ensureHomeDrops().GRAB
  },
  get WISH() {
    return ensureHomeDrops().WISH
  },
}

// 실제 연동 시 GET /api/v1/drops?status=...&sort=... 요청으로 교체한다.
// Mock은 지연 후 각 영역 목록을 반환하고, 실패는 호출부에서 처리한다.
export function fetchHomeDrops(status: HomeSectionKey): Promise<Drop[]> {
  return new Promise((resolve) => {
    const drops = homeDrops[status]
    setTimeout(() => resolve(drops), HOME_MOCK_DELAY)
  })
}
